package edu.bistu.campusmarket.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class AdminUserDtos {
    private AdminUserDtos() {
    }

    public enum RegistrationStatus { PENDING, APPROVED, REJECTED }

    public enum AccountStatus { ENABLED, DISABLED, DELETED }

    public enum ReviewDecision { APPROVED, REJECTED }

    public enum AccountChange { ENABLED, DISABLED }

    public record ReviewRequest(@NotNull @Pattern(regexp = "APPROVED|REJECTED") String decision) {
    }

    public record AccountStatusRequest(@NotNull @Pattern(regexp = "ENABLED|DISABLED") String status) {
    }

    public record ProfileUpdate(@NotBlank @Size(max = 64) String realName,
            @NotBlank @Pattern(regexp = "\\+[1-9][0-9]{1,14}") String phone,
            @NotBlank @Email @Size(max = 254) String email,
            @Size(max = 64) String city,
            @Pattern(regexp = "[A-Z][A-Z0-9_]{0,15}") String genderCode) {
    }

    public record UserView(Long id, String loginName, String realName, String phone, String email, String city,
            String genderCode, String roleCode, RegistrationStatus registrationStatus, AccountStatus accountStatus,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record UserPage(List<UserView> items, long total, int page, int size) {
    }

    public record Recharge(@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,128}") String requestKey) {
    }

    public record RechargeReceipt(long entryId, BigDecimal amount, BigDecimal currentBalance) {
    }
}
