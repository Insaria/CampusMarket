package edu.bistu.campusmarket.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class ReviewDtos {
    private ReviewDtos() {
    }

    public record Write(@NotNull @Min(1) @Max(5) @Digits(integer = 1, fraction = 0) BigDecimal rating,
            @NotBlank @Size(max = 2000) String content) {
    }

    public record Created(long reviewId) {
    }

    public record ProductReview(Long id, Integer rating, String content, LocalDateTime createdAt) {
    }

    public record TradeReview(Long id, Long reviewerId, Long revieweeId, Long returnRequestId, String sceneKey,
            Integer rating, String content, LocalDateTime createdAt) {
    }

    public record TradeHistoryItem(Long id, Long orderId, String orderNo, Long reviewerId, Long revieweeId,
            Long returnRequestId, String sceneKey, Integer rating, String content, LocalDateTime createdAt) {
    }

    public record ReputationHistory(List<TradeHistoryItem> items, long total, int page, int size,
            RatingSummary sellerSatisfaction) {
    }

    public record RatingSummary(long reviewCount, long ratedCount, long positiveCount,
            BigDecimal averageRating, BigDecimal positiveRate) {
    }

    public record ProductReviewPage(List<ProductReview> items, RatingSummary summary, int page, int size) {
    }
}
