package edu.bistu.campusmarket;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.config.ApiAccessConfig;
import edu.bistu.campusmarket.controller.HealthController;
import edu.bistu.campusmarket.controller.AdminSellerController;
import edu.bistu.campusmarket.controller.AdminProductController;
import edu.bistu.campusmarket.controller.LoginController;
import edu.bistu.campusmarket.controller.RegistrationController;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.mapper.AccountAccessMapper;
import edu.bistu.campusmarket.mapper.AccountAccessMapper.AccountAccess;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.security.CsrfTokens;
import edu.bistu.campusmarket.security.LoginAttemptLimiter;
import edu.bistu.campusmarket.service.LoginService;
import edu.bistu.campusmarket.service.AdminSellerService;
import edu.bistu.campusmarket.service.SellerProductService;
import edu.bistu.campusmarket.service.RegistrationCaptcha;
import edu.bistu.campusmarket.service.UserRegistrationService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest({HealthController.class, LoginController.class, RegistrationController.class, AdminSellerController.class,
        AdminProductController.class})
@ActiveProfiles("db")
@Import({ApiAccessConfig.class, CurrentUserService.class, LoginAttemptLimiter.class, ApiAccessTest.ProtectedController.class})
class ApiAccessTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AccountAccessMapper mapper;

    @MockitoBean
    private LoginService loginService;

    @MockitoBean
    private RegistrationCaptcha captcha;

    @MockitoBean
    private UserRegistrationService registrationService;

    @MockitoBean
    private AdminSellerService adminSellers;

    @MockitoBean
    private SellerProductService products;

    @Test
    void pendingProductCountIsAdminOnlyAndReturnsTheAuthoritativeTotal() throws Exception {
        String path = "/api/admin/products/pending/count";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        givenAccount("USER", "APPROVED", "ENABLED", 9L, "APPROVED", "ACTIVE", 1);
        mvc.perform(get(path).session(session("USER"))).andExpect(status().isForbidden());
        verifyNoInteractions(products);
        givenAccount("ADMIN", "APPROVED", "ENABLED", null, null, null, null);
        when(products.pendingCount()).thenReturn(29L, 0L);
        MockHttpSession admin = session("ADMIN");
        mvc.perform(get(path).session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(29));
        mvc.perform(get(path).session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(0));
    }

    @Test
    void blocksShopOperationsForMerchantAndWithoutCsrf() throws Exception {
        var request = patch("/api/admin/sellers/9/operation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SUSPENDED\",\"reason\":\"Test\"}");
        MockHttpSession session = session("USER");
        givenAccount("USER", "APPROVED", "ENABLED", 9L, "APPROVED", "ACTIVE", 1);
        mvc.perform(request.session(session).header(CsrfTokens.HEADER_NAME, CsrfTokens.getOrCreate(session)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        givenAccount("ADMIN", "APPROVED", "ENABLED", null, null, null, null);
        mvc.perform(patch("/api/admin/sellers/9/operation").session(session("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        verifyNoInteractions(adminSellers);
    }

    @Test
    void suspendedShopHasAccessToExistingSellerHistoryWithoutProductManagement() throws Exception {
        givenAccount("USER", "APPROVED", "ENABLED", 9L, "APPROVED", "SUSPENDED", 1);
        mvc.perform(get("/test-access/seller-history").session(session("USER"))).andExpect(status().isOk());
        mvc.perform(get("/test-access/merchant/17").session(session("USER"))).andExpect(status().isForbidden());
        givenAccount("USER", "APPROVED", "ENABLED", null, null, null, null);
        mvc.perform(get("/test-access/seller-history").session(session("USER"))).andExpect(status().isForbidden());
    }

    @Test
    void logoutInvalidatesOnlyCurrentSessionAndExpiresCookie() throws Exception {
        MockHttpSession current = session("USER");
        MockHttpSession other = session("USER");
        String oldId = current.getId();
        String oldToken = CsrfTokens.getOrCreate(current);
        mvc.perform(post("/api/auth/logout").session(current).header(CsrfTokens.HEADER_NAME, oldToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(cookie().maxAge("JSESSIONID", 0))
                .andExpect(cookie().path("JSESSIONID", "/"));
        assertThat(current.isInvalid()).isTrue();
        mvc.perform(post("/api/auth/logout").header(CsrfTokens.HEADER_NAME, oldToken)
                        .session(new MockHttpSession()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));

        mvc.perform(get("/api/auth/me").with(request -> {
            request.setRequestedSessionId(oldId);
            request.setRequestedSessionIdValid(false);
            return request;
        })).andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper);
        givenAccount("USER", "APPROVED", "ENABLED", null, null, null, null);
        mvc.perform(get("/api/auth/me").session(other)).andExpect(status().isOk());
    }

    @Test
    void repeatedLogoutWithoutValidTokenIsRejectedAndDoesNotCreateSession() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = mvc.perform(post("/api/auth/logout").with(request -> {
                request.setRequestedSessionId("expired");
                request.setRequestedSessionIdValid(false);
                return request;
            })).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN")).andReturn();
            assertThat(result.getRequest().getSession(false)).isNull();
        }
    }

    @Test
    void rejectsMissingAndExpiredSessionsWithoutLookingUpAccount() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        mvc.perform(get("/api/auth/me").with(request -> {
            request.setRequestedSessionId("expired");
            request.setRequestedSessionIdValid(false);
            return request;
        })).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        mvc.perform(get("/api/auth/me").sessionAttr(AuthenticatedUser.SESSION_ATTRIBUTE, "invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        verifyNoInteractions(mapper);
    }

    @Test
    void currentUserUsesCurrentRoleEvenIfSessionRoleIsStale() throws Exception {
        givenAccount("USER", "APPROVED", "ENABLED", null, null, null, null);
        mvc.perform(get("/api/auth/me").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(17))
                .andExpect(jsonPath("$.data.roleCode").value("USER"));
    }

    @ParameterizedTest
    @CsvSource({"USER,user,200", "USER,admin,403", "ADMIN,admin,200", "ADMIN,user,403"})
    void enforcesCurrentAccountRole(String role, String path, int expectedStatus) throws Exception {
        givenAccount(role, "APPROVED", "ENABLED", null, null, null, null);
        var result = mvc.perform(get("/test-access/" + path + "/17").session(session("ADMIN")))
                .andExpect(status().is(expectedStatus));
        result.andExpect(jsonPath("$.code").value(expectedStatus == 200 ? "OK" : "ACCESS_DENIED"));
    }

    @ParameterizedTest
    @CsvSource({
        "APPROVED,ENABLED,9,PENDING,INACTIVE,1,403,ACCESS_DENIED",
        "APPROVED,ENABLED,9,APPROVED,SUSPENDED,1,403,ACCESS_DENIED",
        "APPROVED,ENABLED,9,APPROVED,ACTIVE,1,200,OK",
        "APPROVED,ENABLED,9,APPROVED,ACTIVE,0,403,ACCESS_DENIED",
        "APPROVED,ENABLED,9,APPROVED,ACTIVE,,403,ACCESS_DENIED",
        "APPROVED,ENABLED,,,,,403,ACCESS_DENIED",
        "APPROVED,DISABLED,9,APPROVED,ACTIVE,1,403,ACCOUNT_UNAVAILABLE",
        "PENDING,ENABLED,9,APPROVED,ACTIVE,1,403,ACCOUNT_UNAVAILABLE"
    })
    void checksMerchantAndUserEligibility(String registration, String accountStatus, Long merchantId,
            String review, String operation, Integer levelEnabled, int expectedStatus, String code) throws Exception {
        givenAccount("USER", registration, accountStatus, merchantId, review, operation, levelEnabled);
        mvc.perform(get("/test-access/merchant/17").session(session("USER")))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void loginAndHealthRemainPublicInDbProfile() throws Exception {
        when(loginService.authenticate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AuthenticatedUser(17L, "USER"));
        var bootstrap = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store")).andReturn();
        MockHttpSession previous = (MockHttpSession) bootstrap.getRequest().getSession(false);
        String token = JsonPath.read(bootstrap.getResponse().getContentAsString(), "$.data.token");
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        mvc.perform(get("/api/auth/csrf").session(previous))
                .andExpect(jsonPath("$.data.token").value(token));
        var loggedIn = mvc.perform(login().session(previous).header(CsrfTokens.HEADER_NAME, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK")).andReturn();
        MockHttpSession current = (MockHttpSession) loggedIn.getRequest().getSession(false);
        assertThat(previous.isInvalid()).isTrue();
        assertThat(current.getId()).isNotEqualTo(previous.getId());
        assertThat(CsrfTokens.getOrCreate(current)).isNotEqualTo(token);
        mvc.perform(post("/api/auth/logout").session(current).header(CsrfTokens.HEADER_NAME, token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsMissingWrongAndOtherSessionTokensBeforeLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        CsrfTokens.getOrCreate(session);
        String otherToken = CsrfTokens.getOrCreate(new MockHttpSession());
        mvc.perform(login().session(session))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        mvc.perform(login().session(session).header(CsrfTokens.HEADER_NAME, "wrong"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        mvc.perform(login().session(session).header(CsrfTokens.HEADER_NAME, otherToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        verifyNoInteractions(loginService);
    }

    @Test
    void protectsPublicRegistrationBeforeItsServiceRuns() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = CsrfTokens.getOrCreate(session);
        String body = """
                {"loginName":"campus_01","password":"test-only","realName":"Test User","phone":"+8613800000000",
                 "email":"test@example.com","city":"Beijing","genderCode":"OTHER",
                 "bankAccount":"0000000000000001","captcha":"222222"}
                """;
        mvc.perform(post("/api/auth/register").session(session).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        verifyNoInteractions(registrationService);
        mvc.perform(post("/api/auth/register").session(session).header(CsrfTokens.HEADER_NAME, token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.registrationStatus").value("PENDING"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login() {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginName\":\"campus_01\",\"password\":\"test-only\"}");
    }

    @Test
    void rejectsSessionForAccountThatNoLongerExists() throws Exception {
        mvc.perform(get("/api/auth/me").session(session("USER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    private void givenAccount(String role, String registration, String accountStatus, Long merchantId,
            String review, String operation, Integer levelEnabled) {
        when(mapper.findByUserId(17L)).thenReturn(new AccountAccess(
                17L, role, registration, accountStatus, merchantId, review, operation, levelEnabled, "BUSINESS"));
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthenticatedUser.SESSION_ATTRIBUTE, new AuthenticatedUser(17L, role));
        return session;
    }

    @RestController
    static class ProtectedController {
        @ApiAccess(ApiAccess.Requirement.USER)
        @GetMapping("/test-access/user/{id}")
        ApiResponse<Long> user(@PathVariable Long id) {
            return ApiResponse.success(id);
        }

        @ApiAccess(ApiAccess.Requirement.ADMIN)
        @GetMapping("/test-access/admin/{id}")
        ApiResponse<Long> admin(@PathVariable Long id) {
            return ApiResponse.success(id);
        }

        @ApiAccess(ApiAccess.Requirement.MERCHANT)
        @GetMapping("/test-access/merchant/{id}")
        ApiResponse<Long> merchant(@PathVariable Long id) {
            return ApiResponse.success(id);
        }

        @ApiAccess(ApiAccess.Requirement.SELLER_HISTORY)
        @GetMapping("/test-access/seller-history")
        ApiResponse<Void> sellerHistory() {
            return ApiResponse.success(null);
        }
    }
}
