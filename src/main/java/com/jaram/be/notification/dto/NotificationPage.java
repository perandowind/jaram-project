package com.jaram.be.notification.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationPage(List<Item> items, int page, int size, long totalElements) {
    public record Item(UUID id, String type, String title, String message, String targetPath,
                       String resourceId, Instant createdAt, Instant readAt) {}
}
