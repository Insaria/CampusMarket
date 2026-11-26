package edu.bistu.campusmarket.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class AccountFinanceDtos {
    private AccountFinanceDtos() {
    }

    public record WalletEntry(Long id, Long orderId, String orderNo, String entryKind, String direction,
            BigDecimal amount, LocalDateTime createdAt) {
    }

    public record PointsEntry(Long id, Long orderId, String orderNo, String entryKind, String direction,
            @JsonSerialize(using = ToStringSerializer.class) Long quantity, LocalDateTime createdAt) {
    }

    public record WalletView(BigDecimal balance, List<WalletEntry> items, long total, int page, int size) {
    }

    public record PointsView(@JsonSerialize(using = ToStringSerializer.class) Long balance,
            List<PointsEntry> items, long total, int page, int size) {
    }
}
