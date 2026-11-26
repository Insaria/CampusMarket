package edu.bistu.campusmarket;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.bistu.campusmarket.config.ApiAccessConfig;
import edu.bistu.campusmarket.controller.AccountFinanceController;
import edu.bistu.campusmarket.controller.CheckoutController;
import edu.bistu.campusmarket.dto.AccountFinanceDtos.PointsEntry;
import edu.bistu.campusmarket.dto.AccountFinanceDtos.PointsView;
import edu.bistu.campusmarket.dto.AccountFinanceDtos.WalletView;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.CheckoutDtos.PendingPage;
import edu.bistu.campusmarket.mapper.AccountAccessMapper;
import edu.bistu.campusmarket.mapper.AccountAccessMapper.AccountAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.AccountFinanceService;
import edu.bistu.campusmarket.service.CheckoutService;
import edu.bistu.campusmarket.service.OrderCancellationService;
import edu.bistu.campusmarket.service.WalletPaymentService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({AccountFinanceController.class, CheckoutController.class})
@ActiveProfiles("db")
@Import({ApiAccessConfig.class, CurrentUserService.class})
class AccountFinanceReadTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AccountAccessMapper accounts;
    @MockitoBean
    private AccountFinanceService finance;
    @MockitoBean
    private CheckoutService checkout;
    @MockitoBean
    private WalletPaymentService payments;
    @MockitoBean
    private OrderCancellationService cancellations;

    @Test
    void financeReadsRequireOrdinaryUserSession() throws Exception {
        mvc.perform(get("/api/wallet")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/points").session(session("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/checkout-batches/7").session(session("ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(finance, checkout);
    }

    @Test
    void returnsUncachedAccountPageAndIgnoresSuppliedUserId() throws Exception {
        when(finance.wallet(2, 2, 8)).thenReturn(new WalletView(new BigDecimal("42.50"), List.of(), 9, 2, 8));
        mvc.perform(get("/api/wallet").session(session("USER")).param("userId", "1").param("page", "2").param("size", "8"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.balance").value(42.5))
                .andExpect(jsonPath("$.data.total").value(9));
    }

    @Test
    void pointsBalancesAndLedgerKeepLargeIntegerPrecision() throws Exception {
        when(finance.points(2, 1, 20)).thenReturn(new PointsView(Long.MAX_VALUE,
                List.of(new PointsEntry(1L, 3L, "order-3", "ORDER_POINTS_REWARD", "CREDIT", Long.MAX_VALUE, null)),
                1, 1, 20));
        mvc.perform(get("/api/points").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value("9223372036854775807"))
                .andExpect(jsonPath("$.data.items[0].quantity").value("9223372036854775807"))
                .andExpect(jsonPath("$.data.items[0].orderNo").value("order-3"));
    }

    @Test
    void pendingBatchesUseAuthenticatedBuyerAndValidatedPagination() throws Exception {
        when(checkout.pending(2, 2, 10)).thenReturn(new PendingPage(List.of(), 0, 2, 10));
        var buyer = session("USER");
        mvc.perform(get("/api/checkout-batches").session(buyer).param("buyerId", "5").param("page", "2").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2));
        mvc.perform(get("/api/checkout-batches").session(buyer).param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    private MockHttpSession session(String role) {
        when(accounts.findByUserId(2L)).thenReturn(new AccountAccess(2L, role, "APPROVED", "ENABLED",
                null, null, null, null, null));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthenticatedUser.SESSION_ATTRIBUTE, new AuthenticatedUser(2L, role));
        return session;
    }
}
