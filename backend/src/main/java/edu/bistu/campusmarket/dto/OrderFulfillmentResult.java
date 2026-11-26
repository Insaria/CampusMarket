package edu.bistu.campusmarket.dto;

import java.time.Instant;

public record OrderFulfillmentResult(long orderId, String orderStatus, Instant deliveredAt,
        Instant buyerConfirmedAt, Instant autoConfirmedAt) {
}
