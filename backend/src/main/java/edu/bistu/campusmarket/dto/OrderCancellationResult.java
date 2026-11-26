package edu.bistu.campusmarket.dto;

import java.util.List;

public record OrderCancellationResult(long batchId, String orderStatus, List<Long> orderIds) {
}
