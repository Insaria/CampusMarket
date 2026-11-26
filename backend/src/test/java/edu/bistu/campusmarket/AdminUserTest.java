package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.bistu.campusmarket.config.ApiAccessConfig;
import edu.bistu.campusmarket.controller.AdminUserController;
import edu.bistu.campusmarket.dto.AdminUserDtos.AccountChange;
import edu.bistu.campusmarket.dto.AdminUserDtos.AccountStatus;
import edu.bistu.campusmarket.dto.AdminUserDtos.RegistrationStatus;
import edu.bistu.campusmarket.dto.AdminUserDtos.ReviewDecision;
import edu.bistu.campusmarket.dto.AdminUserDtos.UserView;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.mapper.AccountAccessMapper;
import edu.bistu.campusmarket.mapper.AccountAccessMapper.AccountAccess;
import edu.bistu.campusmarket.mapper.AdminUserMapper;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper.Wallet;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.security.CsrfTokens;
import edu.bistu.campusmarket.service.AdminUserService;
import java.util.List;
import java.math.BigDecimal;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

@WebMvcTest(AdminUserController.class)
@ActiveProfiles("db")
@Import({ApiAccessConfig.class, CurrentUserService.class, AdminUserService.class, AdminUserTest.Transactions.class})
class AdminUserTest {
    private static final String PATH = "/api/admin/users";
    private static final long ACTOR = 10L;
    private static final long TARGET = 20L;

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AccountAccessMapper accessMapper;
    @MockitoBean
    private AdminUserMapper mapper;
    @MockitoBean
    private PlatformTransactionManager transactionManager;

    @Test
    void rejectsAnonymousAndOrdinaryUsers() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        mvc.perform(get(PATH).session(session("USER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verifyNoInteractions(mapper);
    }

    @Test
    void countsPendingOrdinaryUsersAndSupportsZero() throws Exception {
        String count = PATH + "/pending/count";
        mvc.perform(get(count)).andExpect(status().isUnauthorized());
        mvc.perform(get(count).session(session("USER"))).andExpect(status().isForbidden());
        verifyNoInteractions(mapper);
        when(mapper.countPendingReview()).thenReturn(2L, 0L);
        MockHttpSession admin = session("ADMIN");
        mvc.perform(get(count).session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(2));
        mvc.perform(get(count).session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(0));
        var config = new Configuration();
        config.addMapper(AdminUserMapper.class);
        assertThat(config.getMappedStatement(AdminUserMapper.class.getName() + ".countPendingReview")
                .getBoundSql(null).getSql()).contains("COUNT(*)", "role_code = 'USER'",
                        "registration_status = 'PENDING'", "account_status <> 'DELETED'");
    }

    @Test
    void listsFilteredPageAndReadsSafeDetails() throws Exception {
        UserView user = view(TARGET, "USER", RegistrationStatus.PENDING, AccountStatus.ENABLED);
        when(mapper.count("%campus!_01%", RegistrationStatus.PENDING, AccountStatus.ENABLED)).thenReturn(3L);
        when(mapper.findPage("%campus!_01%", RegistrationStatus.PENDING, AccountStatus.ENABLED, 2, 2))
                .thenReturn(List.of(user));
        when(mapper.findById(TARGET)).thenReturn(user);
        MockHttpSession admin = session("ADMIN");
        mvc.perform(get(PATH).session(admin).param("loginName", "Campus_01")
                        .param("registrationStatus", "PENDING").param("accountStatus", "ENABLED")
                        .param("page", "2").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.page").value(2)).andExpect(jsonPath("$.data.items[0].id").value(20))
                .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].bankAccountEnc").doesNotExist());
        mvc.perform(get(PATH + "/20").session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accountStatus").value("ENABLED"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.bankAccount").doesNotExist())
                .andExpect(jsonPath("$.data.bankAccountEnc").doesNotExist());
    }

    @Test
    void validatesFiltersAndWriteDto() throws Exception {
        mvc.perform(get(PATH).session(session("ADMIN")).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).session(session("ADMIN")).param("registrationStatus", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        change("registration", "{\"decision\":\"PENDING\"}").andExpect(status().isBadRequest());
        change("status", "{\"status\":\"DELETED\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @EnumSource(ReviewDecision.class)
    void reviewsPendingUserWithoutChangingAccountStatusAndAudits(ReviewDecision decision) throws Exception {
        lockTarget("USER", RegistrationStatus.PENDING, AccountStatus.DISABLED);
        when(mapper.review(TARGET, decision)).thenReturn(1);
        String action = "USER_REGISTRATION_" + decision;
        when(mapper.audit(ACTOR, action, TARGET)).thenReturn(1);
        when(mapper.findById(TARGET)).thenReturn(
                view(TARGET, "USER", RegistrationStatus.valueOf(decision.name()), AccountStatus.DISABLED));

        change("registration", "{\"decision\":\"%s\"}".formatted(decision))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.registrationStatus").value(decision.name()))
                .andExpect(jsonPath("$.data.accountStatus").value("DISABLED"));
        var calls = inOrder(mapper);
        calls.verify(mapper).lockById(ACTOR);
        calls.verify(mapper).lockById(TARGET);
        calls.verify(mapper).review(TARGET, decision);
        calls.verify(mapper).audit(ACTOR, action, TARGET);
        calls.verify(mapper).findById(TARGET);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void rejectsRepeatReviewAndAdminTargets() throws Exception {
        lockTarget("USER", RegistrationStatus.APPROVED, AccountStatus.ENABLED);
        change("registration", "{\"decision\":\"REJECTED\"}").andExpect(status().isConflict());
        lockTarget("ADMIN", RegistrationStatus.PENDING, AccountStatus.ENABLED);
        change("registration", "{\"decision\":\"APPROVED\"}").andExpect(status().isForbidden());
        verify(mapper, org.mockito.Mockito.times(2)).lockById(ACTOR);
        verify(mapper, org.mockito.Mockito.times(2)).lockById(TARGET);
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @EnumSource(AccountChange.class)
    void changesAccountStatusWithoutApprovingRegistration(AccountChange change) throws Exception {
        AccountStatus previous = change == AccountChange.ENABLED ? AccountStatus.DISABLED : AccountStatus.ENABLED;
        lockTarget("USER", RegistrationStatus.PENDING, previous);
        when(mapper.changeStatus(TARGET, change)).thenReturn(1);
        when(mapper.audit(ACTOR, "USER_" + change, TARGET)).thenReturn(1);
        when(mapper.findById(TARGET)).thenReturn(
                view(TARGET, "USER", RegistrationStatus.PENDING, AccountStatus.valueOf(change.name())));
        change("status", "{\"status\":\"%s\"}".formatted(change))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.registrationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.accountStatus").value(change.name()));
        verify(mapper).audit(ACTOR, "USER_" + change, TARGET);
    }

    @Test
    void mapperBindsFiltersAndKeepsWriteColumnsAndAuditActorSeparate() {
        Configuration configuration = new Configuration();
        configuration.addMapper(AdminUserMapper.class);
        String prefix = AdminUserMapper.class.getName() + ".";
        var query = configuration.getMappedStatement(prefix + "findPage").getBoundSql(Map.of(
                "pattern", "%campus!_01%", "registration", RegistrationStatus.PENDING,
                "status", AccountStatus.ENABLED, "size", 20, "offset", 0L));
        assertThat(query.getSql()).contains("LIKE ? ESCAPE '!'", "registration_status = ?", "account_status = ?")
                .doesNotContain("campus", "password_hash", "bank_account_enc");
        assertThat(configuration.getMappedStatement(prefix + "review").getBoundSql(null).getSql())
                .contains("SET registration_status", "role_code = 'USER'", "registration_status = 'PENDING'")
                .doesNotContain("account_status");
        assertThat(configuration.getMappedStatement(prefix + "changeStatus").getBoundSql(null).getSql())
                .contains("SET account_status").doesNotContain("registration_status", "role_code");
        var audit = configuration.getMappedStatement(prefix + "audit").getBoundSql(null);
        assertThat(audit.getSql()).contains("actor_user_id", "target_type", "VALUES ('USER', ?, NULL, ?, 'USER', ?)");
        assertThat(audit.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("actorId", "action", "targetId");
    }

    @Test
    void rollsBackWhenAuditWriteFails() throws Exception {
        var transaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        lockTarget("USER", RegistrationStatus.PENDING, AccountStatus.ENABLED);
        when(mapper.review(TARGET, ReviewDecision.APPROVED)).thenReturn(1);
        when(mapper.audit(ACTOR, "USER_REGISTRATION_APPROVED", TARGET)).thenReturn(0);
        change("registration", "{\"decision\":\"APPROVED\"}")
                .andExpect(status().isInternalServerError());
        verify(transactionManager).rollback(transaction);
        verify(transactionManager, never()).commit(any());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {
    }

    @Test
    void rejectsAdminMutationWithoutToken() throws Exception {
        mvc.perform(patch(PATH + "/20/status").session(session("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        verifyNoInteractions(mapper);
    }

    @Test
    void editsOnlyProfileFieldsAndRecordsAudit() throws Exception {
        lockTarget("USER", RegistrationStatus.APPROVED, AccountStatus.ENABLED);
        when(mapper.audit(ACTOR, "USER_PROFILE_UPDATED", TARGET)).thenReturn(1);
        when(mapper.findById(TARGET)).thenReturn(view(TARGET, "USER", RegistrationStatus.APPROVED, AccountStatus.ENABLED));
        change("profile", """
                {"realName":" Demo ","phone":"+12025550100","email":"Demo@EXAMPLE.INVALID","city":" "}
                """).andExpect(status().isOk());
        verify(mapper).updateProfile(TARGET, new edu.bistu.campusmarket.dto.AdminUserDtos.ProfileUpdate(
                "Demo", "+12025550100", "Demo@example.invalid", null, null));
    }

    @Test
    void deletesOnlyWhenDatabaseGuardAllowsAndRecordsAudit() throws Exception {
        lockTarget("USER", RegistrationStatus.APPROVED, AccountStatus.DISABLED);
        var admin = session("ADMIN");
        String token = CsrfTokens.getOrCreate(admin);
        mvc.perform(delete(PATH + "/" + TARGET).session(admin).header(CsrfTokens.HEADER_NAME, token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_DELETE_BLOCKED"));
        verify(mapper, never()).audit(ACTOR, "USER_DELETED", TARGET);
        when(mapper.deleteUnusedAccount(TARGET)).thenReturn(1);
        when(mapper.audit(ACTOR, "USER_DELETED", TARGET)).thenReturn(1);
        when(mapper.findById(TARGET)).thenReturn(view(TARGET, "USER", RegistrationStatus.APPROVED, AccountStatus.DELETED));
        mvc.perform(delete(PATH + "/" + TARGET).session(admin).header(CsrfTokens.HEADER_NAME, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accountStatus").value("DELETED"));
    }

    @Test
    void rechargeCreatesOneLedgerAndAuditAndReturnsOriginalOnReplay() throws Exception {
        lockTarget("USER", RegistrationStatus.APPROVED, AccountStatus.ENABLED);
        var amount = new BigDecimal("5.00");
        when(mapper.lockWallet(TARGET)).thenReturn(new Wallet(3L, BigDecimal.ZERO), new Wallet(3L, amount));
        when(mapper.rechargeEntry(3L, "test-key")).thenReturn(null, new AdminUserMapper.RechargeEntry(33L, amount));
        when(mapper.credit(3L, amount)).thenReturn(1);
        when(mapper.recordRecharge(eq(3L), eq(amount), eq("test-key"), any())).thenAnswer(invocation -> {
            invocation.<GeneratedId>getArgument(3).setId(33L);
            return 1;
        });
        when(mapper.auditRecharge(ACTOR, TARGET, 33L, amount, "Development fixture")).thenReturn(1);
        var admin = session("ADMIN");

        recharge(admin, "5.00").andExpect(status().isOk()).andExpect(jsonPath("$.data.entryId").value(33))
                .andExpect(jsonPath("$.data.currentBalance").value(5));
        recharge(admin, "5.00").andExpect(status().isOk()).andExpect(jsonPath("$.data.entryId").value(33));
        recharge(admin, "6.00").andExpect(status().isConflict());
        verify(mapper).credit(3L, amount);
        verify(mapper).recordRecharge(eq(3L), eq(amount), eq("test-key"), any());
        verify(mapper).auditRecharge(ACTOR, TARGET, 33L, amount, "Development fixture");
    }

    @Test
    void rechargeAuditFailureRollsBackBalanceAndLedger() throws Exception {
        var transaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        lockTarget("USER", RegistrationStatus.APPROVED, AccountStatus.ENABLED);
        var amount = new BigDecimal("5.00");
        when(mapper.lockWallet(TARGET)).thenReturn(new Wallet(3L, BigDecimal.ZERO));
        when(mapper.credit(3L, amount)).thenReturn(1);
        when(mapper.recordRecharge(eq(3L), eq(amount), eq("test-key"), any())).thenAnswer(invocation -> {
            invocation.<GeneratedId>getArgument(3).setId(33L);
            return 1;
        });
        recharge(session("ADMIN"), "5.00").andExpect(status().isInternalServerError());
        verify(transactionManager).rollback(transaction);
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void rechargeRequiresAdminCsrfAndPositiveAmount() throws Exception {
        recharge(session("USER"), "5.00").andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/20/wallet/recharges").session(session("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        recharge(session("ADMIN"), "0").andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }

    private ResultActions recharge(MockHttpSession user, String amount) throws Exception {
        return mvc.perform(post(PATH + "/20/wallet/recharges").session(user)
                .header(CsrfTokens.HEADER_NAME, CsrfTokens.getOrCreate(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"reason\":\"Development fixture\",\"requestKey\":\"test-key\"}"));
    }

    private ResultActions change(String segment, String body) throws Exception {
        MockHttpSession admin = session("ADMIN");
        return mvc.perform(patch(PATH + "/20/" + segment).session(admin)
                .header(CsrfTokens.HEADER_NAME, CsrfTokens.getOrCreate(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void lockTarget(String role, RegistrationStatus registration, AccountStatus status) {
        when(mapper.lockById(ACTOR)).thenReturn(view(ACTOR, "ADMIN", RegistrationStatus.APPROVED, AccountStatus.ENABLED));
        when(mapper.lockById(TARGET)).thenReturn(view(TARGET, role, registration, status));
    }

    private MockHttpSession session(String role) {
        when(accessMapper.findByUserId(ACTOR))
                .thenReturn(new AccountAccess(ACTOR, role, "APPROVED", "ENABLED", null, null, null, null, null));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthenticatedUser.SESSION_ATTRIBUTE, new AuthenticatedUser(ACTOR, role));
        return session;
    }

    private UserView view(long id, String role, RegistrationStatus registration, AccountStatus state) {
        return new UserView(id, "campus_01", "Test User", null, null, null, null, role, registration, state, null, null);
    }
}
