package edu.bistu.campusmarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.dto.ReturnRequestDtos.Item;
import edu.bistu.campusmarket.dto.ReviewDtos.ProductReview;
import edu.bistu.campusmarket.dto.ReviewDtos.Write;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.ReturnRequestMapper;
import edu.bistu.campusmarket.mapper.ReviewMapper;
import edu.bistu.campusmarket.mapper.ReviewMapper.Parties;
import edu.bistu.campusmarket.mapper.ReviewMapper.PurchasedItem;
import edu.bistu.campusmarket.mapper.ReviewMapper.RatingAggregate;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.mapper.SettlementMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper.Order;
import edu.bistu.campusmarket.service.CatalogService;
import edu.bistu.campusmarket.service.ReviewService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

class ReviewServiceTest {
    private static final LocalDateTime TIME = LocalDateTime.parse("2026-11-20T09:00:00.123");
    private static final Write INPUT = new Write(BigDecimal.valueOf(5), "  Good service  ");
    private final ReviewMapper reviews = mock(ReviewMapper.class);
    private final SettlementMapper orders = mock(SettlementMapper.class);
    private final ReturnRequestMapper returns = mock(ReturnRequestMapper.class);
    private final CatalogService catalog = mock(CatalogService.class);
    private final ReviewService service = new ReviewService(reviews, orders, returns, catalog);

    @BeforeEach
    void orderAndActors() {
        when(orders.lockOrder(1L)).thenReturn(order("COMPLETED"));
        when(reviews.findParties(1L)).thenReturn(new Parties(2L, 3L));
        when(reviews.findItem(5L)).thenReturn(new PurchasedItem(1L, 4L));
        when(returns.findOrderIdForSeller(3L, 6L)).thenReturn(1L);
    }

    @Test
    void actualBuyerReviewsOrderItem() {
        when(reviews.insertProductReview(eq(5L), eq(5), eq("Good service"), any()))
                .thenAnswer(i -> generated(i.getArgument(3), 30L));

        assertEquals(30, service.reviewProduct(2L, 5L, INPUT).reviewId());
    }

    @Test
    void otherBuyerAndMismatchedProductMerchantCannotReview() {
        assertCode("REVIEW_TARGET_NOT_FOUND", () -> service.reviewProduct(8L, 5L, INPUT));
        when(reviews.findItem(5L)).thenReturn(new PurchasedItem(1L, 7L));
        assertCode("REVIEW_TARGET_NOT_FOUND", () -> service.reviewProduct(2L, 5L, INPUT));
        verify(reviews, never()).insertProductReview(anyLong(), anyInt(), any(), any());
    }

    @Test
    void ownProductAndOwnTradeCannotBeReviewed() {
        when(reviews.findParties(1L)).thenReturn(new Parties(2L, 2L));
        assertCode("SELF_REVIEW_FORBIDDEN", () -> service.reviewProduct(2L, 5L, INPUT));
        assertCode("SELF_REVIEW_FORBIDDEN", () -> service.reviewSeller(2L, 1L, INPUT));
    }

    @Test
    void paymentAloneDoesNotMakeTheOrderReviewable() {
        when(orders.lockOrder(1L)).thenReturn(order("PAID"));
        assertCode("ORDER_NOT_REVIEWABLE", () -> service.reviewProduct(2L, 5L, INPUT));
        assertCode("ORDER_NOT_REVIEWABLE", () -> service.reviewBuyerPurchase(3L, 1L, INPUT));
    }

    @Test
    void duplicateWritesAreConflicts() {
        when(reviews.insertProductReview(eq(5L), eq(5), eq("Good service"), any()))
                .thenThrow(new DuplicateKeyException("existing order item review"));
        when(reviews.insertTradeReview(eq(1L), eq(2L), eq(3L), eq(null), eq(5), eq("Good service"), any()))
                .thenThrow(new DuplicateKeyException("existing trade review"));

        assertCode("ALREADY_REVIEWED", () -> service.reviewProduct(2L, 5L, INPUT));
        assertCode("ALREADY_REVIEWED", () -> service.reviewSeller(2L, 1L, INPUT));
    }

    @Test
    void purchaseTradeReviewsDeriveBothDirectionsFromTheOrder() {
        prepareTrade(2L, 3L, null, 30L);
        prepareTrade(3L, 2L, null, 31L);

        assertEquals(30, service.reviewSeller(2L, 1L, INPUT).reviewId());
        assertEquals(31, service.reviewBuyerPurchase(3L, 1L, INPUT).reviewId());
    }

    @Test
    void sellerCanReviewReceivedReturnAsSeparateScene() {
        when(returns.lockRequest(1L, 6L)).thenReturn(returnRequest("RETURNED", TIME));
        prepareTrade(3L, 2L, 6L, 32L);

        assertEquals(32, service.reviewBuyerReturn(3L, 6L, INPUT).reviewId());
    }

    @Test
    void approvalAloneOrAnotherSellerCannotReviewTheReturn() {
        when(returns.findOrderIdForSeller(9L, 6L)).thenReturn(null);
        when(returns.lockRequest(1L, 6L)).thenReturn(returnRequest("APPROVED", null));
        assertCode("RETURN_NOT_REVIEWABLE", () -> service.reviewBuyerReturn(3L, 6L, INPUT));
        assertCode("REVIEW_TARGET_NOT_FOUND", () -> service.reviewBuyerReturn(9L, 6L, INPUT));
        assertCode("REVIEW_TARGET_NOT_FOUND", () -> service.reviewBuyerPurchase(9L, 1L, INPUT));
        verify(reviews, never()).insertTradeReview(anyLong(), anyLong(), anyLong(), any(), anyInt(), any(), any());
    }

    @Test
    void productReviewsRequireCatalogVisibilityAndUseStablePagination() {
        var review = new ProductReview(30L, 5, "Good", TIME);
        when(reviews.productRatings(7L)).thenReturn(new RatingAggregate(3L, 3L, 2L, new BigDecimal("4.00")));
        when(reviews.findProductReviews(7L, 2, 2L)).thenReturn(List.of(review));

        var result = service.productReviews(7L, 2, 2);

        verify(catalog).find(7L);
        assertEquals(List.of(review), result.items());
        assertEquals(3, result.summary().reviewCount());
        assertEquals(new BigDecimal("0.6667"), result.summary().positiveRate());
        assertEquals(List.of(), service.productReviews(7L, 3, 2).items());
        verify(reviews, never()).findProductReviews(7L, 2, 4L);
    }

    @Test
    void hiddenProductDoesNotExposeReviewData() {
        when(catalog.find(7L)).thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", ""));
        assertCode("PRODUCT_NOT_FOUND", () -> service.productReviews(7L, 1, 20));
        verify(reviews, never()).productRatings(7L);
    }

    @Test
    void satisfactionAndReputationUseOnlyTheirOwnRatedDenominators() {
        when(reviews.sellerRatings(3L)).thenReturn(new RatingAggregate(3L, 2L, 1L, new BigDecimal("3.50")));
        when(reviews.buyerRatings(2L)).thenReturn(new RatingAggregate(1L, 0L, 0L, null));

        var satisfaction = service.sellerSatisfaction(3L);
        assertEquals(3, satisfaction.reviewCount());
        assertEquals(new BigDecimal("0.5000"), satisfaction.positiveRate());
        assertNull(service.buyerReputation(2L).positiveRate());
        verify(reviews, never()).productRatings(anyLong());
    }

    @Test
    void onlyParticipantsCanReadTradeReviews() {
        service.tradeReviews(2L, 1L);
        verify(reviews).findTradeReviews(1L, 2L, 3L);
        assertCode("REVIEW_TARGET_NOT_FOUND", () -> service.tradeReviews(8L, 1L));
    }

    @Test
    void reputationHistoryUsesOwnRecordsAndUnchangedSatisfactionStats() {
        when(reviews.countHistory(3L)).thenReturn(21L);
        when(reviews.hasSellerProfile(3L)).thenReturn(true);
        when(reviews.sellerRatings(3L)).thenReturn(new RatingAggregate(3L, 2L, 1L, new BigDecimal("3.50")));

        var result = service.reputationHistory(3L, 2, 20);

        verify(reviews).history(3L, 20, 20);
        assertEquals(21, result.total());
        assertEquals(new BigDecimal("0.5000"), result.sellerSatisfaction().positiveRate());
        assertNull(service.reputationHistory(2L, 1, 20).sellerSatisfaction());
    }

    private void prepareTrade(long reviewer, long reviewee, Long returnId, long id) {
        when(reviews.insertTradeReview(eq(1L), eq(reviewer), eq(reviewee), eq(returnId),
                eq(5), eq("Good service"), any())).thenAnswer(i -> generated(i.getArgument(6), id));
    }

    private int generated(GeneratedId key, long id) {
        key.setId(id);
        return 1;
    }

    private Order order(String state) {
        return new Order(1L, 10L, 4L, state, TIME, null, BigDecimal.ONE, BigDecimal.ONE);
    }

    private Item returnRequest(String state, LocalDateTime returned) {
        return new Item(6L, 1L, "sale", "COMPLETED", BigDecimal.ONE, state, true, "return", TIME, null, TIME, returned);
    }

    private void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(BusinessException.class, action::run).code());
    }
}
