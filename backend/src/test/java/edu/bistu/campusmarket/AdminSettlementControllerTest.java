package edu.bistu.campusmarket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.bistu.campusmarket.controller.AdminSellerController;
import edu.bistu.campusmarket.controller.AdminSettlementController;
import edu.bistu.campusmarket.dto.AdminSellerDtos.Level;
import edu.bistu.campusmarket.dto.AdminSellerDtos.LevelChange;
import edu.bistu.campusmarket.dto.AdminSellerDtos.Seller;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.FeeReport;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.GroupBy;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.Totals;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.exception.GlobalApiExceptionHandler;
import edu.bistu.campusmarket.mapper.AccountAccessMapper;
import edu.bistu.campusmarket.mapper.AccountAccessMapper.AccountAccess;
import edu.bistu.campusmarket.security.ApiAccessInterceptor;
import edu.bistu.campusmarket.security.CsrfInterceptor;
import edu.bistu.campusmarket.security.CsrfTokens;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.AdminSellerService;
import edu.bistu.campusmarket.service.AdminSettlementService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminSettlementControllerTest {
    private final AccountAccessMapper accounts = mock(AccountAccessMapper.class);
    private final AdminSellerService sellers = mock(AdminSellerService.class);
    private final AdminSettlementService settlements = mock(AdminSettlementService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new AdminSellerController(sellers), new AdminSettlementController(settlements))
            .addInterceptors(new CsrfInterceptor(), new ApiAccessInterceptor(new CurrentUserService(accounts)))
            .setControllerAdvice(new GlobalApiExceptionHandler())
            .build();

    @Test
    void adminGetsFiveLevelsIncludingDisabledLevel() throws Exception {
        when(sellers.levels()).thenReturn(List.of(
                level(1, "0.001000", true), level(2, "0.002000", true), level(3, "0.005000", true),
                level(4, "0.007500", false), level(5, "0.010000", true)));

        mvc.perform(get("/api/admin/sellers/levels").session(session(1L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].feeRate").value(0.001))
                .andExpect(jsonPath("$.data[1].feeRate").value(0.002))
                .andExpect(jsonPath("$.data[2].feeRate").value(0.005))
                .andExpect(jsonPath("$.data[3].feeRate").value(0.0075))
                .andExpect(jsonPath("$.data[3].enabled").value(false))
                .andExpect(jsonPath("$.data[4].feeRate").value(0.01));
    }

    @Test
    void adminGetsFeeReportWithBoundDatesAndLevel() throws Exception {
        var from = OffsetDateTime.parse("2026-11-20T08:00:00+08:00");
        when(settlements.fees(from, null, 7L, 3, GroupBy.LEVEL))
                .thenReturn(new FeeReport(new Totals(1, new BigDecimal("100.00"), new BigDecimal("0.50")), List.of()));

        mvc.perform(get("/api/admin/settlements/fees").session(session(1L, "ADMIN"))
                        .param("from", "2026-11-20T08:00:00+08:00").param("sellerId", "7")
                        .param("levelCode", "3").param("groupBy", "LEVEL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totals.feeAmount").value(0.5))
                .andExpect(jsonPath("$.data.totals.settlementCount").value(1));
    }

    @Test
    void usersCannotReadReportsOrChangeTheirOwnLevel() throws Exception {
        var seller = session(7L, "USER");
        for (String route : List.of("/api/admin/sellers/levels", "/api/admin/sellers/7/metrics",
                "/api/admin/settlements", "/api/admin/settlements/fees")) {
            mvc.perform(get(route).session(seller)).andExpect(status().isForbidden());
        }
        mvc.perform(patch("/api/admin/sellers/7/level").session(seller)
                        .header(CsrfTokens.HEADER_NAME, CsrfTokens.getOrCreate(seller))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"levelCode\":3,\"reason\":\"reason\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(sellers, settlements);
    }

    @Test
    void adminNeedsCsrfAndReasonForLevelChange() throws Exception {
        var admin = session(1L, "ADMIN");
        mvc.perform(patch("/api/admin/sellers/7/level").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"levelCode\":3,\"reason\":\"reason\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        mvc.perform(patch("/api/admin/sellers/7/level").session(admin)
                        .header(CsrfTokens.HEADER_NAME, CsrfTokens.getOrCreate(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"levelCode\":3}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sellers);
    }

    @Test
    void adminCanAdjustLevelUsingExistingRoute() throws Exception {
        var admin = session(1L, "ADMIN");
        when(sellers.changeLevel(1L, 7L, new LevelChange(3, "Performance review")))
                .thenReturn(new Seller(7L, 8L, "Shop", null, "APPROVED", "ACTIVE", 3, null, "BUSINESS", null));

        mvc.perform(patch("/api/admin/sellers/7/level").session(admin)
                        .header(CsrfTokens.HEADER_NAME, CsrfTokens.getOrCreate(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"levelCode\":3,\"reason\":\"Performance review\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.levelCode").value(3));
    }

    private Level level(int code, String rate, boolean enabled) {
        return new Level((long) code, code, "Level " + code, new BigDecimal(rate), enabled);
    }

    private MockHttpSession session(long id, String role) {
        when(accounts.findByUserId(id)).thenReturn(new AccountAccess(id, role, "APPROVED", "ENABLED",
                7L, "APPROVED", "ACTIVE", 1, "BUSINESS"));
        var session = new MockHttpSession();
        session.setAttribute(AuthenticatedUser.SESSION_ATTRIBUTE, new AuthenticatedUser(id, role));
        return session;
    }
}
