package edu.bistu.campusmarket.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

public final class TradeAppointmentDtos {
    private TradeAppointmentDtos() {
    }

    public record Proposal(@NotNull OffsetDateTime scheduledTradeAt, @NotBlank @Size(max = 255) String location) {
    }

    public record Acceptance(@NotNull @Min(1) Long proposalId) {
    }

    public record Pending(Long id, Long actorUserId, LocalDateTime scheduledTradeAt, String location) {
    }

    public record View(Long proposalId, LocalDateTime scheduledTradeAt, String location, boolean canAccept) {
    }
}
