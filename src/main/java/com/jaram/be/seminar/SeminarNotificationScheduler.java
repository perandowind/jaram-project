package com.jaram.be.seminar;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "jaram.notifications.enabled", havingValue = "true")
class SeminarNotificationScheduler {
    private static final int MAX_SEMINARS_PER_POLL = 20;

    private final SeminarNotifications notifications;

    @Scheduled(fixedDelayString = "${jaram.notifications.poll-delay-ms:1000}", scheduler = "notificationScheduler")
    void dispatchDueNotifications() {
        Instant now = Instant.now();
        for (int processed = 0; processed < MAX_SEMINARS_PER_POLL; processed++) {
            if (!notifications.dispatchNext(now)) return;
        }
    }
}
