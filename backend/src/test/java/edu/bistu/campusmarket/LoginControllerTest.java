package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.bistu.campusmarket.config.PasswordConfig;
import edu.bistu.campusmarket.controller.LoginController;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.LoginAccountQuery;
import edu.bistu.campusmarket.exception.GlobalApiExceptionHandler;
import edu.bistu.campusmarket.mapper.UserAccountMapper.LoginAccount;
import edu.bistu.campusmarket.service.LoginAccountService;
import edu.bistu.campusmarket.service.LoginService;
import edu.bistu.campusmarket.security.LoginAttemptLimiter;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LoginControllerTest {
    private static final String PASSWORD = "test-only-pass";
    private static final PasswordEncoder ENCODER = new PasswordConfig().passwordEncoder();
    private static final String HASH = ENCODER.encode(PASSWORD);

    private LoginAccountService accounts;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        accounts = mock(LoginAccountService.class);
        mvc = MockMvcBuilders.standaloneSetup(new LoginController(new LoginService(accounts, ENCODER),
                        new LoginAttemptLimiter(5, Duration.ofMinutes(5), 100)))
                .setControllerAdvice(new GlobalApiExceptionHandler())
                .build();
    }

    @Test
    void logsInAndReplacesExistingSessionWithoutReturningSecrets() throws Exception {
        account("APPROVED", "ENABLED", HASH);
        MockHttpSession oldSession = new MockHttpSession();
        String oldId = oldSession.getId();

        var result = mvc.perform(loginRequest("  Campus_01  ", PASSWORD).session(oldSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.userId").value(17))
                .andExpect(jsonPath("$.data.roleCode").value("USER"))
                .andExpect(resultValue -> assertThat(resultValue.getResponse().getContentAsString())
                        .doesNotContain(PASSWORD, HASH, "passwordHash", "bankAccount"))
                .andReturn();

        var session = result.getRequest().getSession(false);
        assertThat(oldSession.isInvalid()).isTrue();
        assertThat(session).isNotNull();
        assertThat(session.getId()).isNotEqualTo(oldId);
        assertThat(session.getAttribute(AuthenticatedUser.SESSION_ATTRIBUTE))
                .isEqualTo(new AuthenticatedUser(17L, "USER"));
    }

    @Test
    void unknownAccountAndWrongPasswordReturnIdenticalErrors() throws Exception {
        account("APPROVED", "ENABLED", HASH);
        String wrongPassword = rejected(mvc.perform(loginRequest("Campus_01", "wrong-pass")),
                401, "INVALID_CREDENTIALS").andReturn().getResponse().getContentAsString();

        rejected(mvc.perform(loginRequest("missing", PASSWORD)), 401, "INVALID_CREDENTIALS")
                .andExpect(content().string(wrongPassword));
    }

    @ParameterizedTest
    @CsvSource({
        "PENDING,ENABLED,REGISTRATION_NOT_APPROVED",
        "REJECTED,ENABLED,REGISTRATION_NOT_APPROVED",
        "APPROVED,DISABLED,ACCOUNT_UNAVAILABLE",
        "APPROVED,DELETED,ACCOUNT_UNAVAILABLE"
    })
    void rejectsAccountsWithoutLoginEligibility(String registration, String state, String code) throws Exception {
        account(registration, state, HASH);
        rejected(mvc.perform(loginRequest("Campus_01", PASSWORD)), 403, code);
        rejected(mvc.perform(loginRequest("Campus_01", "wrong-pass")), 401, "INVALID_CREDENTIALS");
    }

    @Test
    void neverAcceptsPlaintextStoredAsHash() throws Exception {
        account("APPROVED", "ENABLED", PASSWORD);
        rejected(mvc.perform(loginRequest("Campus_01", PASSWORD)), 401, "INVALID_CREDENTIALS");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"loginName\":\"a b\",\"password\":\"abc\"}",
        "{\"loginName\":\"a\\t\",\"password\":\"abc\"}",
        "{\"loginName\":\"campus_01\"}",
        "{\"loginName\":\"campus_01\",\"password\":\" \"}",
        "{\"password\":\"abc\"}"
    })
    void validatesRequestBeforeLookingUpAccount(String body) throws Exception {
        rejected(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)),
                400, "VALIDATION_FAILED");
        verifyNoInteractions(accounts);
    }

    @Test
    void encoderUsesVersionedSaltedHashes() {
        String otherHash = ENCODER.encode(PASSWORD);
        assertThat(HASH).startsWith("{pbkdf2-sha256-v1}").isNotEqualTo(otherHash);
        assertThat(ENCODER.matches(PASSWORD, otherHash)).isTrue();
    }

    private void account(String registration, String state, String hash) {
        when(accounts.findForAuthentication(new LoginAccountQuery("Campus_01")))
                .thenReturn(Optional.of(new LoginAccount(17L, hash, "USER", registration, state)));
    }

    private MockHttpServletRequestBuilder loginRequest(String name, String password) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginName\":\"%s\",\"password\":\"%s\"}".formatted(name, password));
    }

    private ResultActions rejected(ResultActions result, int httpStatus, String code) throws Exception {
        return result.andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(value -> assertThat(value.getRequest().getSession(false)).isNull());
    }
}
