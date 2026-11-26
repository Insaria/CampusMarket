package edu.bistu.campusmarket;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.bistu.campusmarket.config.ApiAccessConfig;
import edu.bistu.campusmarket.controller.NotificationController;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.NotificationDtos.Message;
import edu.bistu.campusmarket.mapper.AccountAccessMapper;
import edu.bistu.campusmarket.mapper.AccountAccessMapper.AccountAccess;
import edu.bistu.campusmarket.mapper.NotificationMapper;
import edu.bistu.campusmarket.security.CsrfTokens;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.NotificationService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
@ActiveProfiles("db")
@Import({ApiAccessConfig.class, CurrentUserService.class, NotificationService.class})
class NotificationTest {
    private static final LocalDateTime AT = LocalDateTime.parse("2026-11-20T08:00:00");
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AccountAccessMapper accounts;
    @MockitoBean
    private NotificationMapper messages;

    @Test
    void authenticationAndCsrfProtectNotificationActions() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/notifications/read-all").session(session(2))).andExpect(status().isForbidden());
        verifyNoInteractions(messages);
    }

    @Test
    void unreadPageAndBadgeUseSessionRecipient() throws Exception {
        when(messages.count(2, true)).thenReturn(25L);
        when(messages.page(2, true, 20, 20)).thenReturn(List.of(message(null)));
        var buyer = session(2);
        mvc.perform(get("/api/notifications").session(buyer).param("recipientId", "9")
                        .param("unreadOnly", "true").param("page", "2"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items[0].id").value(7)).andExpect(jsonPath("$.data.total").value(25));
        mvc.perform(get("/api/notifications/unread-count").session(buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(25));
    }

    @Test
    void cannotReadOrMarkAnotherRecipientsNotification() throws Exception {
        var other = session(3);
        mvc.perform(get("/api/notifications/7").session(other)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/notifications/7/read").session(other)
                        .header(CsrfTokens.HEADER_NAME, CsrfTokens.getOrCreate(other)))
                .andExpect(status().isNotFound());
        verify(messages).read(3, 7);
    }

    @Test
    void previouslyReadMessageCanBeAcknowledgedAgainAndReadAllIsScoped() throws Exception {
        when(messages.find(2, 7)).thenReturn(message(AT));
        when(messages.readAll(2)).thenReturn(4);
        var user = session(2);
        var token = CsrfTokens.getOrCreate(user);
        mvc.perform(patch("/api/notifications/7/read").session(user).header(CsrfTokens.HEADER_NAME, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.readAt").exists());
        mvc.perform(patch("/api/notifications/read-all").session(user).header(CsrfTokens.HEADER_NAME, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(4));
    }

    @Test
    void eventSelectsOtherPartyAndStableBusinessTarget() {
        var writer = new NotificationService(messages);
        writer.emit(NotificationService.Event.RETURN_APPLIED, 42, 12);
        writer.emit(NotificationService.Event.REFUND_COMPLETED, 42, 19);
        verify(messages).record(eq(42L), eq(true), eq("RETURN_APPLIED"), eq("RETURN_APPLIED:12"),
                eq("RETURN_REQUEST"), eq(12L), anyString(), anyString());
        verify(messages).record(eq(42L), eq(false), eq("REFUND_COMPLETED"), eq("REFUND_COMPLETED:19"),
                eq("ORDER"), eq(42L), anyString(), anyString());
    }

    private Message message(LocalDateTime readAt) {
        return new Message(7L, "ORDER_DELIVERED", "ORDER", 42L, "Delivered", "Review order", readAt, AT);
    }

    @Test
    void productReviewInsertUsesSellerAndAuditIdentityAndTime() {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(NotificationMapper.class);
        var sql = configuration.getMappedStatement(NotificationMapper.class.getName() + ".recordProductReview").getBoundSql(null);
        org.assertj.core.api.Assertions.assertThat(sql.getSql()).contains("m.user_id", "m.id = p.merchant_id",
                "CONCAT('PRODUCT_REVIEW:', a.id)", "'PRODUCT', p.id", "DATE_FORMAT(a.created_at", "a.id = ?");
    }

    private MockHttpSession session(long userId) {
        when(accounts.findByUserId(userId)).thenReturn(new AccountAccess(userId, "USER", "APPROVED", "ENABLED",
                null, null, null, null, null));
        var session = new MockHttpSession();
        session.setAttribute(AuthenticatedUser.SESSION_ATTRIBUTE, new AuthenticatedUser(userId, "USER"));
        return session;
    }
}
