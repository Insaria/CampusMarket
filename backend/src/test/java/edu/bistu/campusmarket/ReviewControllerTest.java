package edu.bistu.campusmarket;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.bistu.campusmarket.config.ApiAccessConfig;
import edu.bistu.campusmarket.controller.ReviewController;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.ReviewDtos.Created;
import edu.bistu.campusmarket.dto.ReviewDtos.ProductReviewPage;
import edu.bistu.campusmarket.dto.ReviewDtos.RatingSummary;
import edu.bistu.campusmarket.dto.ReviewDtos.Write;
import edu.bistu.campusmarket.mapper.AccountAccessMapper;
import edu.bistu.campusmarket.mapper.AccountAccessMapper.AccountAccess;
import edu.bistu.campusmarket.security.CsrfTokens;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.ReviewService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReviewController.class)
@ActiveProfiles("db")
@Import({ApiAccessConfig.class, CurrentUserService.class})
class ReviewControllerTest {
    private static final String INPUT = """
            {"rating":5,"content":"Good"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReviewService reviews;

    @MockitoBean
    private AccountAccessMapper accounts;

    @Test
    void productReviewsArePublic() throws Exception {
        when(reviews.productReviews(7L, 2, 10))
                .thenReturn(new ProductReviewPage(List.of(), new RatingSummary(0, 0, 0, null, null), 2, 10));

        mvc.perform(get("/api/products/7/reviews").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.summary.reviewCount").value(0));
        verifyNoInteractions(accounts);
    }

    @Test
    void writesRequireCsrfAndSellerPermission() throws Exception {
        MockHttpSession session = buyerSession();
        mvc.perform(post("/api/order-items/5/review").session(session).contentType(MediaType.APPLICATION_JSON).content(INPUT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        mvc.perform(post("/api/sellers/me/orders/1/buyer-review").session(session)
                        .header(CsrfTokens.HEADER_NAME, CsrfTokens.getOrCreate(session))
                        .contentType(MediaType.APPLICATION_JSON).content(INPUT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verifyNoInteractions(reviews);
    }

    @Test
    void fractionalStarsAreRejected() throws Exception {
        MockHttpSession session = buyerSession();
        mvc.perform(post("/api/order-items/5/review").session(session)
                        .header(CsrfTokens.HEADER_NAME, CsrfTokens.getOrCreate(session))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"rating":4.5,"content":"Good"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(reviews);
    }

    @Test
    void outOfRangeStarsAndBlankContentAreRejected() throws Exception {
        MockHttpSession session = buyerSession();
        mvc.perform(post("/api/order-items/5/review").session(session)
                        .header(CsrfTokens.HEADER_NAME, CsrfTokens.getOrCreate(session))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"rating":6,"content":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(reviews);
    }

    @Test
    void reviewUsesSessionIdentity() throws Exception {
        MockHttpSession session = buyerSession();
        when(reviews.reviewProduct(2L, 5L, new Write(BigDecimal.valueOf(5), "Good"))).thenReturn(new Created(30L));

        mvc.perform(post("/api/order-items/5/review").session(session)
                        .header(CsrfTokens.HEADER_NAME, CsrfTokens.getOrCreate(session))
                        .contentType(MediaType.APPLICATION_JSON).content(INPUT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reviewId").value(30));
    }

    private MockHttpSession buyerSession() {
        when(accounts.findByUserId(2L)).thenReturn(new AccountAccess(2L, "USER", "APPROVED", "ENABLED",
                null, null, null, null, null));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthenticatedUser.SESSION_ATTRIBUTE, new AuthenticatedUser(2L, "USER"));
        return session;
    }
}
