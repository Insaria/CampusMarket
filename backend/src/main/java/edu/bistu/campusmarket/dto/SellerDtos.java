package edu.bistu.campusmarket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public final class SellerDtos {
    private SellerDtos() {
    }

    public record Application(@NotBlank @Size(max = 100) String shopName, @Size(max = 500) String shopDescription) {
    }

    public record SellerView(Long sellerId, String sellerType, String shopName, String shopDescription,
            String reviewStatus, String operationStatus, Integer levelCode, String levelName, BigDecimal feeRate,
            String closureReason) {
    }
}
