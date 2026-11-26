package edu.bistu.campusmarket.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class AdminSellerDtos {
    private AdminSellerDtos() {
    }

    public record Review(@NotNull @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @Min(1) @Max(5) Integer levelCode, @Size(max = 500) String reviewReason) {
    }

    public record LevelChange(@NotNull @Min(1) @Max(5) Integer levelCode, @NotBlank @Size(max = 500) String reason) {
    }

    public record Seller(Long id, Long userId, String shopName, String shopDescription, String reviewStatus,
            String operationStatus, Integer levelCode, String reviewReason, String sellerType, String closureReason) {
    }

    public record OperationChange(@NotNull @Pattern(regexp = "ACTIVE|SUSPENDED") String status,
            @Size(max = 500) String reason) {
    }

    public record Page(List<Seller> items, long total, int page, int size) {
    }

    public record Level(Long id, Integer levelCode, String levelName, BigDecimal feeRate, boolean enabled) {
    }

    public enum ReviewStatus { PENDING, APPROVED, REJECTED }

    public enum DocumentKind { BUSINESS_LICENSE, IDENTITY_DOCUMENT }
}
