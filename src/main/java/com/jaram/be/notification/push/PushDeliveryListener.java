package com.jaram.be.notification.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
class PushDeliveryListener {
    private final PushSubscriptions subscriptions;
    private final VapidWebPush transport;
    private final ObjectMapper objectMapper;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512), Thread.ofPlatform().name("web-push-", 0).daemon(true).factory());

    @TransactionalEventListener
    public void afterCommit(PushDelivery delivery) {
        if (!transport.configured()) return;
        try {
            executor.execute(() -> deliver(delivery));
        } catch (RejectedExecutionException rejected) {
            log.warn("Web Push queue unavailable for notification {}", delivery.notificationId());
        }
    }

    private void deliver(PushDelivery delivery) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(delivery.payload());
            for (PushSubscriptions.Subscription subscription :
                    subscriptions.findActive(delivery.recipientId(), Instant.now())) {
                try {
                    int status = transport.send(subscription, payload);
                    if (status < 200 || status >= 300) {
                        log.warn("Web Push returned status {} for notification {}, subscription {}",
                                status, delivery.notificationId(), subscription.id());
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception failure) {
                    // Exception messages can contain the endpoint or request headers.
                    log.warn("Web Push failed for notification {}, subscription {} ({})",
                            delivery.notificationId(), subscription.id(), failure.getClass().getSimpleName());
                }
            }
        } catch (Exception failure) {
            log.warn("Web Push could not prepare notification {} ({})",
                    delivery.notificationId(), failure.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
        if (transport.configured()) transport.close();
    }
}
