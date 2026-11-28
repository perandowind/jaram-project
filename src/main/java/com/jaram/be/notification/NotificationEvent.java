package com.jaram.be.notification;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record NotificationEvent(
        UUID eventId,
        NotificationType type,
        int version,
        Instant occurredAt,
        String subjectId,
        String resourceId,
        List<String> recipientIds,
        @JsonInclude(JsonInclude.Include.NON_NULL) String studyTitle
) {
    private static final int SUPPORTED_VERSION = 1;
    private static final int MAX_IDENTIFIER_LENGTH = 255;

    public NotificationEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (version != SUPPORTED_VERSION) {
            throw new IllegalArgumentException("Unsupported notification event version: " + version);
        }
        requireId("subjectId", subjectId);
        requireId("resourceId", resourceId);
        Objects.requireNonNull(recipientIds, "recipientIds");
        for (String recipientId : recipientIds) {
            requireId("recipientIds element", Objects.requireNonNull(recipientId, "recipientIds element"));
        }
        recipientIds = List.copyOf(recipientIds);
    }

    public static NotificationEvent create(NotificationType type, String subjectId, String resourceId,
                                           List<String> recipientIds) {
        return create(type, subjectId, resourceId, recipientIds, null);
    }

    public static NotificationEvent create(NotificationType type, String subjectId, String resourceId,
                                           List<String> recipientIds, String studyTitle) {
        return new NotificationEvent(UUID.randomUUID(), type, SUPPORTED_VERSION, Instant.now(),
                subjectId, resourceId, recipientIds, studyTitle);
    }

    private static void requireId(String field, String value) {
        if (value == null || value.isBlank() || value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(field + " must be non-blank and at most "
                    + MAX_IDENTIFIER_LENGTH + " characters");
        }
    }
}
