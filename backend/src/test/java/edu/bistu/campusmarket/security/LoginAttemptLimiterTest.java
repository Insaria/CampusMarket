package edu.bistu.campusmarket.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.bistu.campusmarket.controller.LoginController;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.exception.GlobalApiExceptionHandler;
import edu.bistu.campusmarket.service.LoginService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LoginAttemptLimiterTest {
    private static final Duration WINDOW = Duration.ofSeconds(5);
    private static final String CLIENT = "192.0.2.1";
    private final AtomicLong clock = new AtomicLong();
    private final LoginService login = mock(LoginService.class);
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(2, WINDOW, 2, clock::get);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LoginController(login, limiter))
            .setControllerAdvice(new GlobalApiExceptionHandler()).build();

    @Test
    void blocksBeforeAuthenticationAndRecoversAtExpiry() throws Exception {
        when(login.authenticate(any())).thenThrow(invalid()).thenThrow(invalid())
                .thenReturn(new AuthenticatedUser(1L, "USER"));
        authenticate("Campus_01", CLIENT).andExpect(status().isUnauthorized());
        authenticate("  campus_01 ", CLIENT).andExpect(status().isUnauthorized());
        authenticate("campus_01", CLIENT).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
        verify(login, times(2)).authenticate(any());
        clock.addAndGet(WINDOW.toNanos());
        authenticate("campus_01", CLIENT).andExpect(status().isOk());
    }

    @Test
    void successResetsOnlyItsOwnPair() throws Exception {
        when(login.authenticate(any())).thenThrow(invalid()).thenThrow(invalid()).thenThrow(invalid())
                .thenReturn(new AuthenticatedUser(1L, "USER")).thenThrow(invalid()).thenThrow(invalid());
        authenticate("other", CLIENT).andExpect(status().isUnauthorized());
        authenticate("other", CLIENT).andExpect(status().isUnauthorized());
        authenticate("campus_01", CLIENT).andExpect(status().isUnauthorized());
        authenticate("campus_01", CLIENT).andExpect(status().isOk());
        authenticate("campus_01", CLIENT).andExpect(status().isUnauthorized());
        authenticate("campus_01", CLIENT).andExpect(status().isUnauthorized());
        authenticate("other", CLIENT).andExpect(status().isTooManyRequests());
    }

    @Test
    void isolatesClientsAndIgnoresForwardingHeader() throws Exception {
        when(login.authenticate(any())).thenThrow(invalid());
        authenticate("campus_01", CLIENT).andExpect(status().isUnauthorized());
        authenticate("campus_01", CLIENT).andExpect(status().isUnauthorized());
        authenticate("campus_01", CLIENT).andExpect(status().isTooManyRequests());
        authenticate("campus_01", "192.0.2.2").andExpect(status().isUnauthorized());
    }

    @Test
    void boundsEntriesAndReclaimsExpiredRecords() {
        fail("first");
        fail("second");
        assertThatThrownBy(() -> limiter.authenticate("third", CLIENT, () -> "accepted"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.status())
                                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        clock.addAndGet(WINDOW.toNanos());
        org.assertj.core.api.Assertions.assertThat(limiter.authenticate("third", CLIENT, () -> "accepted"))
                .isEqualTo("accepted");
    }

    private void fail(String name) {
        assertThatThrownBy(() -> limiter.authenticate(name, CLIENT, () -> { throw invalid(); }))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("INVALID_CREDENTIALS"));
    }

    private BusinessException invalid() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
    }

    private ResultActions authenticate(String name, String remoteAddress) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "192.0.2.99")
                .content("{\"loginName\":\"%s\",\"password\":\"test-only\"}".formatted(name))
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                }));
    }
}
