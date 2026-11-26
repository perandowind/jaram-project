
package com.jaram.be.notification;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class NotificationPublisher {
    private static final int MAX_EVENTS_PER_POLL = 20;
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final NotificationOutbox outbox;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(
            fixedDelayString = "${jaram.notifications.poll-delay-ms:1000}",
            scheduler = "notificationScheduler"
    )
    public void publishPending() {
        for (int eventsProcessed = 0; eventsProcessed < MAX_EVENTS_PER_POLL; eventsProcessed++) {
            PublishResult result = transactionTemplate.execute(status -> publishNext());
            if (result != PublishResult.PUBLISHED) {
                return;
            }
        }
    }

    private PublishResult publishNext() {
        Optional<OutboxEvent> pendingEvent = outbox.lockNext(Instant.now());
        if (pendingEvent.isEmpty()) {
            return PublishResult.NO_PENDING_EVENT;
        }

        OutboxEvent event = pendingEvent.get();
        try {
            if (sendAndConfirm(event)) {
                event.markPublished();
                return PublishResult.PUBLISHED;
            }

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.debug("Notification publication interrupted for event {}", event.getEventId());
            return PublishResult.INTERRUPTED;
        } catch (AmqpException | ExecutionException | TimeoutException exception) {
            log.warn("Failed to publish notification event {} on attempt {}",
                    event.getEventId(), event.getAttempts() + 1, exception);
        }

        event.reschedule();
        return PublishResult.RESCHEDULED;
    }

    private boolean sendAndConfirm(OutboxEvent event)
            throws InterruptedException, ExecutionException, TimeoutException {
        CorrelationData correlationData = new CorrelationData();
        rabbitTemplate.send(
                NotificationMessaging.EVENT_EXCHANGE_NAME,
                event.getRoutingKey(),
                createMessage(event),
                correlationData
        );

        CorrelationData.Confirm confirm = correlationData.getFuture()
                .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // A broker can ACK a message that was returned as unroutable.
        ReturnedMessage returnedMessage = correlationData.getReturned();
        if (returnedMessage != null) {
            log.warn("RabbitMQ returned notification event {} on attempt {} with reply code {} (routing key {})",
                    event.getEventId(), event.getAttempts() + 1, returnedMessage.getReplyCode(), event.getRoutingKey());
            return false;
        }

        if (!confirm.isAck()) {
            log.warn("RabbitMQ did not confirm notification event {} on attempt {}: {}",
                    event.getEventId(), event.getAttempts() + 1, confirm.getReason());
            return false;
        }
        return true;
    }

    private Message createMessage(OutboxEvent event) {
        return MessageBuilder
                .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setMessageId(event.getEventId().toString())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }

    private enum PublishResult {
        PUBLISHED,
        NO_PENDING_EVENT,
        RESCHEDULED,
        INTERRUPTED
    }
}
