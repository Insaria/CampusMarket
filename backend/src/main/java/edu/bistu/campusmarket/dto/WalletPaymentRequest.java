package edu.bistu.campusmarket.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record WalletPaymentRequest(@Size(max = 100) List<@NotNull @Valid Redemption> redemptions) {
    public record Redemption(@NotNull @Positive Long orderId, @NotNull @Positive Long points) {
    }
}
