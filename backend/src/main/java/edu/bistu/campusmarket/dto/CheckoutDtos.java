package edu.bistu.campusmarket.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class CheckoutDtos {
    private CheckoutDtos() {
    }

    public record Request(@NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._-]+") String requestKey,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid Selection> items) {
    }

    public record Selection(@NotNull @Min(1) Long productId, @NotNull @Min(1) Integer quantity) {
    }

    public record PaidRequest(@Valid @NotNull Request checkout,
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal quotedAmount,
            @Size(max = 100) List<@NotNull @Valid SellerRedemption> redemptions) {
    }

    public record SellerRedemption(@NotNull @Min(1) Long sellerId, @NotNull @Min(1) Long points) {
    }

    public record Order(Long orderId, Long sellerId, String orderNo, String orderStatus, BigDecimal quotedAmount) {
    }

    public record Result(Long batchId, BigDecimal quotedAmount, List<Order> orders) {
    }

    public record BatchDetails(Long batchId, BigDecimal quotedAmount, List<Order> orders, WalletPaymentResult payment) {
    }

    public record PendingBatch(Long batchId, LocalDateTime createdAt, long orderCount, BigDecimal quotedAmount) {
    }

    public record PendingPage(List<PendingBatch> items, long total, int page, int size) {
    }
}
