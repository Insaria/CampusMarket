package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.bistu.campusmarket.config.ApiAccessConfig;
import edu.bistu.campusmarket.controller.OrderReadController;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.OrderReadDtos.Filter;
import edu.bistu.campusmarket.dto.OrderReadDtos.Line;
import edu.bistu.campusmarket.dto.OrderReadDtos.Order;
import edu.bistu.campusmarket.mapper.AccountAccessMapper;
import edu.bistu.campusmarket.mapper.AccountAccessMapper.AccountAccess;
import edu.bistu.campusmarket.mapper.OrderReadMapper;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.OrderReadService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderReadController.class)
@ActiveProfiles("db")
@Import({ApiAccessConfig.class, CurrentUserService.class, OrderReadService.class})
class OrderReadTest {
    private static final LocalDateTime CONFIRMED = LocalDateTime.parse("2026-11-20T01:00:00");

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AccountAccessMapper accounts;
    @MockitoBean
    private OrderReadMapper orders;

    @Test
    void buyerPageUsesSessionAndReturnsSnapshotLines() throws Exception {
        when(orders.count(2, false, Filter.COMPLETED)).thenReturn(25L);
        when(orders.page(2, false, Filter.COMPLETED, 20, 20)).thenReturn(List.of(completed()));
        when(orders.lines(List.of(6L))).thenReturn(List.of(new Line(17L, 6L, 9L, "Book snapshot",
                new BigDecimal("6.50"), 2, new BigDecimal("13.00"))));

        mvc.perform(get("/api/orders").session(session(2L, false)).param("buyerId", "9")
                        .param("page", "2").param("status", "COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.total").value(25))
                .andExpect(jsonPath("$.data.items[0].order.batchId").value(40))
                .andExpect(jsonPath("$.data.items[0].lines[0].productName").value("Book snapshot"))
                .andExpect(jsonPath("$.data.items[0].quotedAmount").value(13));
    }

    @Test
    void crossOwnerReadsStopBeforeChildData() throws Exception {
        mvc.perform(get("/api/orders/6").session(session(3L, false))).andExpect(status().isNotFound());
        mvc.perform(get("/api/sellers/me/orders/6").session(session(4L, true))).andExpect(status().isNotFound());
        verify(orders).find(3, false, 6);
        verify(orders).find(4, true, 6);
        org.mockito.Mockito.verifyNoMoreInteractions(orders);
    }

    @Test
    void sellerReadsRequireMerchantQualification() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sellers/me/orders").session(session(3L, false))).andExpect(status().isForbidden());
        mvc.perform(get("/api/sellers/me/orders").session(session(4L, true)).param("status", "RETURNS"))
                .andExpect(status().isOk());
        verify(orders).count(4, true, Filter.RETURNS);
    }

    @Test
    void returnWindowClosesExactlyAtTwentyFourHours() throws Exception {
        when(orders.find(2, false, 6)).thenReturn(completed());
        when(orders.utcNow()).thenReturn(CONFIRMED.plusHours(24).minusNanos(1_000_000), CONFIRMED.plusHours(24));
        var buyer = session(2L, false);
        mvc.perform(get("/api/orders/6").session(buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canRequestReturn").value(true));
        mvc.perform(get("/api/orders/6").session(buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canRequestReturn").value(false));
    }

    @Test
    void myBatisBindsOwnershipAndFiltersSeparately() {
        var config = new Configuration();
        config.addMapper(OrderReadMapper.class);
        var statement = config.getMappedStatement(OrderReadMapper.class.getName() + ".page");
        var buyer = statement.getBoundSql(Map.of("userId", 2L, "seller", false, "filter", Filter.PAID, "size", 20, "offset", 0));
        var seller = statement.getBoundSql(Map.of("userId", 4L, "seller", true, "filter", Filter.RETURNS, "size", 20, "offset", 0));
        assertThat(buyer.getSql()).contains("WHERE b.buyer_id = ?", "o.order_status = ?").doesNotContain("m.user_id = ?");
        assertThat(seller.getSql()).contains("WHERE m.user_id = ?", "r.request_status <> 'RETURNED'", "f.completed_at IS NULL")
                .doesNotContain("b.buyer_id = ?");
        verifyNoInteractions(orders);
    }

    private Order completed() {
        return new Order(6L, 40L, "order-6", "COMPLETED", 20L, "Shop", 2L, "buyer",
                new BigDecimal("13.00"), new BigDecimal("12.00"), BigDecimal.ONE, 100L,
                CONFIRMED.minusDays(1), null, null, CONFIRMED.minusHours(1), CONFIRMED, null, 4L);
    }

    private MockHttpSession session(Long id, boolean seller) {
        when(accounts.findByUserId(id)).thenReturn(new AccountAccess(id, "USER", "APPROVED", "ENABLED",
                seller ? 20L : null, seller ? "APPROVED" : null, seller ? "ACTIVE" : null,
                null, seller ? "PERSONAL" : null));
        var session = new MockHttpSession();
        session.setAttribute(AuthenticatedUser.SESSION_ATTRIBUTE, new AuthenticatedUser(id, "USER"));
        return session;
    }
}
