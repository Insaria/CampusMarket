package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.dto.LoginAccountQuery;
import edu.bistu.campusmarket.mapper.UserAccountMapper;
import edu.bistu.campusmarket.mapper.UserAccountMapper.LoginAccount;
import edu.bistu.campusmarket.service.LoginAccountService;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(classes = {LoginAccountService.class, ValidationAutoConfiguration.class,
        LoginAccountLookupTest.TestConfiguration.class})
@ActiveProfiles("db")
class LoginAccountLookupTest {
    @Autowired
    private LoginAccountService service;

    @Autowired
    private UserAccountMapper mapper;

    @BeforeEach
    void resetMapper() {
        org.mockito.Mockito.reset(mapper);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "a b", "\ta", "a\n", "a\u00a0", "\u3000a", "Ａbc", "a@b",
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abc"})
    void rejectsInvalidLoginNamesBeforeQuerying(String loginName) {
        assertThrows(ConstraintViolationException.class,
                () -> service.findForAuthentication(new LoginAccountQuery(loginName)));
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsNullQueryBeforeQuerying() {
        assertThrows(ConstraintViolationException.class, () -> service.findForAuthentication(null));
        verifyNoInteractions(mapper);
    }

    @Test
    void normalizesNameAndReturnsAuthenticationProjection() {
        LoginAccount account = new LoginAccount(1L, "test-hash", "USER", "APPROVED", "ENABLED");
        when(mapper.findByLoginName("campus_01")).thenReturn(account);

        assertThat(service.findForAuthentication(new LoginAccountQuery("  Campus_01  "))).contains(account);
        verify(mapper).findByLoginName("campus_01");
        assertThat(account.toString()).doesNotContain("test-hash");
    }

    @Test
    void returnsEmptyWhenAccountDoesNotExist() {
        assertThat(service.findForAuthentication(new LoginAccountQuery("missing"))).isEmpty();
        verify(mapper).findByLoginName("missing");
    }

    @Test
    void mapperRegistersAParameterizedQuery() {
        Configuration configuration = new Configuration();
        configuration.addMapper(UserAccountMapper.class);
        var statement = configuration.getMappedStatement(UserAccountMapper.class.getName() + ".findByLoginName");
        var query = statement.getBoundSql(Map.of("loginName", "campus_01"));

        assertThat(query.getSql()).contains("WHERE login_name = ?").doesNotContain("campus_01");
        assertThat(query.getParameterMappings()).singleElement()
                .satisfies(parameter -> assertThat(parameter.getProperty()).isEqualTo("loginName"));
        assertThat(statement.getResultMaps()).singleElement()
                .satisfies(result -> assertThat(result.getType()).isEqualTo(LoginAccount.class));
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean
        UserAccountMapper userAccountMapper() {
            return mock(UserAccountMapper.class);
        }
    }
}
