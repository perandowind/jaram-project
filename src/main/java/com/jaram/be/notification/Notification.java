package com.jaram.be.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notification")
class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private String recipientId;

    @Column(nullable = false, length = 100)
    private String type;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(nullable = false)
    private String targetPath;

    @Column(nullable = false)
    private String resourceId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant readAt;

    private Notification(NotificationEvent event, String recipientId) {
        NotificationType notificationType = event.type();

        this.eventId = event.eventId();
        this.recipientId = recipientId;
        this.type = notificationType.routingKey();
        this.title = notificationType.getTitle();
        this.message = notificationType.getMessage();
        this.targetPath = notificationType.getTargetPath();
        this.resourceId = event.resourceId();
        this.createdAt = Instant.now();
    }

    static Notification create(NotificationEvent event, String recipientId) {
        return new Notification(event, recipientId);
    }
}
