package com.jaram.be.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notification_outbox")
class OutboxEvent {
    private static final int[] RETRY_DELAYS_SECONDS = {1, 2, 4, 8, 16, 32, 60};

    @Id
    @Getter(AccessLevel.PACKAGE)
    private UUID eventId;

    @Getter(AccessLevel.PACKAGE)
    @Column(nullable = false, length = 100)
    private String routingKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Getter(AccessLevel.PACKAGE)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Getter(AccessLevel.PACKAGE)
    @Column(nullable = false)
    private int attempts;

    private Instant publishedAt;

    OutboxEvent(NotificationEvent event, String payload) {
        this.eventId = event.eventId();
        this.routingKey = event.type().routingKey();
        this.payload = payload;
        this.createdAt = Instant.now();
        this.nextAttemptAt = createdAt;
    }

    void markPublished() {
        publishedAt = Instant.now();
    }

    void reschedule() {
        int delayIndex = Math.min(attempts, RETRY_DELAYS_SECONDS.length - 1);
        nextAttemptAt = Instant.now().plusSeconds(RETRY_DELAYS_SECONDS[delayIndex]);
        attempts++;
    }
}
