package edu.bistu.campusmarket.dto;

import java.math.BigDecimal;

public record RefundResult(long refundId, String refundStatus, BigDecimal cashRefundAmount, FundingPath fundingPath) {
    public enum FundingPath { ESCROW, SELLER_WALLET, POINTS_ONLY }
}
