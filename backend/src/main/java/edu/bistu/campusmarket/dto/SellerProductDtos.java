package edu.bistu.campusmarket.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class SellerProductDtos {
    private SellerProductDtos() {
    }

    public record Draft(@NotNull @Min(1) Long categoryId, @NotBlank @Size(max = 120) String name,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal originalPrice,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal discountPrice,
            @Size(max = 100) String sizeDescription, @Size(max = 16000) String usageInstructions,
            boolean negotiable, @NotBlank @Size(max = 16) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String conditionCode) {
    }

    public record Stock(@NotNull Integer delta,
            @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._-]+") String operationKey) {
    }

    public record Item(Long id, Long categoryId, String name, BigDecimal originalPrice, BigDecimal discountPrice,
            String sizeDescription, String usageInstructions, Integer negotiable, String conditionCode,
            Integer availableQuantity, Integer reservedQuantity, String reviewStatus, String listingStatus) {
    }

    public record Page(List<Item> items, long total, int page, int size) {
    }

    public record Review(@NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision, @Size(max = 500) String reviewReason) {
    }

    public record ReviewSeller(Long sellerId, String shopName, String sellerType) {
    }

    public record ReviewDetail(Item product, ReviewSeller seller, List<Integer> imagePositions, String reviewReason) {
    }

    public record ImageReceipt(int sortOrder, boolean primary) {
    }
}
