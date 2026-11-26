package edu.bistu.campusmarket.dto;

import edu.bistu.campusmarket.dto.AdminSellerDtos.Seller;
import edu.bistu.campusmarket.dto.ReviewDtos.RatingSummary;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class AdminSettlementDtos {
    private AdminSettlementDtos() {
    }

    public enum GroupBy { SELLER, LEVEL }

    public record Filter(LocalDateTime fromInclusive, LocalDateTime toExclusive, Long sellerId, Integer levelCode) {
    }

    public record Totals(long settlementCount, BigDecimal settledAmount, BigDecimal feeAmount) {
    }

    public record Group(Long sellerId, Integer levelCode, long settlementCount, BigDecimal settledAmount,
            BigDecimal feeAmount) {
    }

    public record FeeReport(Totals totals, List<Group> groups) {
    }

    public record Detail(Long settlementId, Long orderId, Long sellerId, Integer levelCodeSnapshot,
            BigDecimal feeRateSnapshot, LocalDateTime feeRateAppliedAt, BigDecimal feeBaseAmount,
            BigDecimal settledAmount, BigDecimal feeAmount, BigDecimal sellerNetAmount, LocalDateTime settledAt) {
    }

    public record Page(List<Detail> items, long total, int page, int size) {
    }

    public record SellerMetrics(Seller seller, Totals settlements, RatingSummary satisfaction) {
    }
}
