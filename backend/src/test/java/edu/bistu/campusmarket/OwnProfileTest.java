package edu.bistu.campusmarket;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.bistu.campusmarket.config.ApiAccessConfig;
import edu.bistu.campusmarket.controller.OwnProfileController;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.OwnProfile;
import edu.bistu.campusmarket.mapper.AccountAccessMapper;
import edu.bistu.campusmarket.mapper.AccountAccessMapper.AccountAccess;
import edu.bistu.campusmarket.mapper.UserAccountMapper;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.LoginAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OwnProfileController.class)
@ActiveProfiles("db")
@Import({ApiAccessConfig.class, CurrentUserService.class, LoginAccountService.class})
class OwnProfileTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private AccountAccessMapper access;
    @MockitoBean private UserAccountMapper accounts;

    @Test
    void requiresAnActiveSessionBeforeReadingPersonalInformation() throws Exception {
        mvc.perform(get("/api/users/me/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me/profile").session(session("DISABLED"))).andExpect(status().isForbidden());
        verifyNoInteractions(accounts);
    }

    @Test
    void returnsOnlyTheSessionProfileWithoutCachingOrCredentials() throws Exception {
        when(accounts.findOwnProfile(7L)).thenReturn(new OwnProfile("tester", "测试人员", "00000000000",
                "tester@example.test", null, null, "APPROVED", "ENABLED"));
        mvc.perform(get("/api/users/me/profile").param("userId", "99").session(session("ENABLED")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.loginName").value("tester"))
                .andExpect(jsonPath("$.data.registrationStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.bankAccountEnc").doesNotHaveJsonPath());
    }

    private MockHttpSession session(String state) {
        when(access.findByUserId(7L)).thenReturn(new AccountAccess(7L, "USER", "APPROVED", state,
                null, null, null, null, null));
        var session = new MockHttpSession();
        session.setAttribute(AuthenticatedUser.SESSION_ATTRIBUTE, new AuthenticatedUser(7L, "USER"));
        return session;
    }
}
