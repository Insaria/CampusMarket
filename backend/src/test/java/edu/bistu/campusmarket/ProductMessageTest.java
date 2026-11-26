package edu.bistu.campusmarket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.bistu.campusmarket.config.ApiAccessConfig;
import edu.bistu.campusmarket.controller.ProductMessageController;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.mapper.AccountAccessMapper;
import edu.bistu.campusmarket.mapper.AccountAccessMapper.AccountAccess;
import edu.bistu.campusmarket.mapper.ProductMessageMapper;
import edu.bistu.campusmarket.mapper.ProductMessageMapper.Conversation;
import edu.bistu.campusmarket.mapper.ProductMessageMapper.Message;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.security.CsrfTokens;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.NotificationService;
import edu.bistu.campusmarket.service.ProductMessageService;
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

@WebMvcTest(ProductMessageController.class)
@ActiveProfiles("db")
@Import({ApiAccessConfig.class, CurrentUserService.class, ProductMessageService.class})
class ProductMessageTest {
    private static final Conversation THREAD = new Conversation(5, 10, 20);
    @Autowired private MockMvc mvc;
    @MockitoBean private AccountAccessMapper accounts;
    @MockitoBean private ProductMessageMapper mapper;
    @MockitoBean private NotificationService notifications;

    @Test
    void participantCanStartAndSellerCanReplyWithoutSupplyingRecipient() throws Exception {
        when(mapper.visibleSeller(5)).thenReturn(20L);
        savedBy(10, "尺寸是多少？", 31);
        savedBy(20, "长30厘米", 32);
        write("/api/products/5/messages?buyerId=99&sellerId=99", 10,
                "{\"content\":\" 尺寸是多少？ \", \"senderId\":99,\"sellerId\":99}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.messageId").value(31));
        when(mapper.owned(31, 20)).thenReturn(THREAD);
        write("/api/product-messages/31/replies", 20, "{\"content\":\"长30厘米\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.messageId").value(32));
        verify(notifications).emitProductMessage(20, 31);
        verify(notifications).emitProductMessage(10, 32);
    }

    @Test
    void validatesSessionCsrfAndThirdPartyAccess() throws Exception {
        mvc.perform(get("/api/product-messages/31")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/products/5/messages").session(session(10)).contentType("application/json")
                .content("{\"content\":\"咨询\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/product-messages/31").session(session(99))).andExpect(status().isNotFound());
        write("/api/product-messages/31/replies?buyerId=10&sellerId=20", 99,
                "{\"content\":\"咨询\", \"senderId\":10}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_MESSAGE_NOT_FOUND"));
        verify(mapper, never()).insert(any(), anyLong(), any(), any());
        verifyNoInteractions(notifications);
    }

    @Test
    void refusesOwnProductEvenWithForgedIdentitiesWithoutWriting() throws Exception {
        when(mapper.visibleSeller(5)).thenReturn(10L);
        write("/api/products/5/messages?buyerId=99&sellerId=20", 10,
                "{\"content\":\"咨询\", \"buyerId\":99, \"senderId\":99, \"sellerId\":20}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_MESSAGE"))
                .andExpect(jsonPath("$.message").value("不能给自己的商品留言"));
        verify(mapper, never()).insert(any(), anyLong(), any(), any());
        verifyNoInteractions(notifications);
    }

    @Test
    void refusesBlankMessage() throws Exception {
        write("/api/products/5/messages", 10, "{\"content\":\"  \"}").andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
        verifyNoInteractions(notifications);
    }

    @Test
    void participantReadsPrivatePageWithBareMessageContentAndNoSenderProfile() throws Exception {
        when(mapper.owned(31, 10)).thenReturn(THREAD);
        when(mapper.page(THREAD, null, 31)).thenReturn(List.of(
                new Message(32, 20, "长30厘米", LocalDateTime.of(2026, 11, 24, 8, 0))));
        mvc.perform(get("/api/product-messages/31").session(session(10))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items[0].mine").value(false))
                .andExpect(jsonPath("$.data.items[0].content").value("长30厘米"))
                .andExpect(jsonPath("$.data.items[0].senderId").doesNotExist());
    }

    private org.springframework.test.web.servlet.ResultActions write(String path, long actor, String json) throws Exception {
        MockHttpSession session = session(actor);
        return mvc.perform(post(path).session(session).header(CsrfTokens.HEADER_NAME, CsrfTokens.getOrCreate(session))
                .contentType("application/json").content(json));
    }

    private MockHttpSession session(long user) {
        when(accounts.findByUserId(user)).thenReturn(new AccountAccess(user, "USER", "APPROVED", "ENABLED",
                null, null, null, null, null));
        var session = new MockHttpSession();
        session.setAttribute(AuthenticatedUser.SESSION_ATTRIBUTE, new AuthenticatedUser(user, "USER"));
        return session;
    }

    private void savedBy(long actor, String content, long id) {
        when(mapper.insert(eq(THREAD), eq(actor), eq(content), any(GeneratedId.class))).thenAnswer(call -> {
            call.<GeneratedId>getArgument(3).setId(id);
            return 1;
        });
    }
}
