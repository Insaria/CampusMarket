package edu.bistu.campusmarket.dto;

import java.math.BigDecimal;
import java.util.List;

public record WalletPaymentResult(long batchId, BigDecimal paidAmount, List<Long> orderIds,
        long pointsUsed, BigDecimal pointsDiscountAmount) {
}
