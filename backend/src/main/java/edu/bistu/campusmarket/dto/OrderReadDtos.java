package edu.bistu.campusmarket.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class OrderReadDtos {
    private OrderReadDtos() {
    }

    public enum Filter { PENDING_PAYMENT, PAID, DELIVERED, COMPLETED, CANCELLED, RETURNS }

    public record Order(Long orderId, Long batchId, String orderNo, String orderStatus,
            Long sellerId, String shopName, Long buyerId, String buyerLoginName,
            BigDecimal payableAmount, BigDecimal cashPaid, BigDecimal pointsDiscountAmount, Long pointsUsed,
            LocalDateTime createdAt, LocalDateTime scheduledTradeAt, String tradeLocation,
            LocalDateTime deliveredAt, LocalDateTime buyerConfirmedAt, LocalDateTime autoConfirmedAt, Long sellerUserId) {
    }

    public record Line(Long orderItemId, Long orderId, Long productId, String productName, BigDecimal unitPrice,
            Integer quantity, BigDecimal lineAmount) {
    }

    public record OrderItem(Order order, List<Line> lines, BigDecimal quotedAmount) {
    }

    public record Page(List<OrderItem> items, long total, int page, int size) {
    }

    public record Event(int eventSeq, String fromStatus, String toStatus, String actorType,
            String systemSource, LocalDateTime createdAt) {
    }

    public record ReturnProgress(Long requestId, String requestStatus, boolean active, String reason,
            LocalDateTime appliedAt, String reviewReason, LocalDateTime reviewedAt, LocalDateTime returnedAt,
            Long refundId, String refundStatus, BigDecimal cashRefundAmount, Long pointsRestored, Long pointsReversed,
            LocalDateTime refundCompletedAt, String fundingPath) {
    }

    public record Restock(Long requestId, Long orderItemId, Long productId, Integer quantity) {
    }

    public record ItemReview(Long reviewId, Long orderItemId, Integer rating, String content, LocalDateTime createdAt) {
    }

    public record Details(OrderItem summary, List<Event> events, List<ReturnProgress> returns, List<Restock> restocks,
            long pointsRewarded, boolean batchCancellable, LocalDateTime returnClosesAt, boolean canRequestReturn,
            boolean canReview, List<ItemReview> itemReviews) {
    }
}
