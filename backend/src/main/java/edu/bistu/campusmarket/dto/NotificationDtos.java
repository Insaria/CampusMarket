package edu.bistu.campusmarket.dto;

import java.time.LocalDateTime;
import java.util.List;

public final class NotificationDtos {
    private NotificationDtos() {
    }

    public record Message(Long id, String notificationKind, String targetType, Long targetId,
            String title, String content, LocalDateTime readAt, LocalDateTime createdAt) {
    }

    public record Page(List<Message> items, long total, int page, int size) {
    }

    public record Unread(long count) {
    }
}
