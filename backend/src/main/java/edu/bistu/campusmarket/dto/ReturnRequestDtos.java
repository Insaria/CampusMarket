package edu.bistu.campusmarket.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class ReturnRequestDtos {
    private ReturnRequestDtos() {
    }

    public record Application(@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,128}") String requestKey,
            @NotBlank @Size(max = 500) String reason) {
    }

    public enum Decision { APPROVE, REJECT }

    public enum Status {
        PENDING, APPROVED, REJECTED, RETURNED;

        public static boolean isApproved(String status) {
            return APPROVED.name().equals(status) || RETURNED.name().equals(status);
        }
    }

    public record Review(@NotNull Decision decision, @Size(max = 500) String reviewReason) {
    }

    public record ReceiptConfirmation(@NotNull @AssertTrue Boolean resellable) {
    }

    public record Item(Long id, Long orderId, String orderNo, String orderStatus, BigDecimal payableAmount,
            String requestStatus, boolean active, String reason, LocalDateTime appliedAt,
            String reviewReason, LocalDateTime reviewedAt, LocalDateTime returnedAt) {
    }

    public record Received(Item request, List<RestockedItem> items) {
    }

    public record RestockedItem(Long orderItemId, Long productId, Integer quantity) {
    }

    public record Page(List<Item> items, long total, int page, int size) {
    }
}
