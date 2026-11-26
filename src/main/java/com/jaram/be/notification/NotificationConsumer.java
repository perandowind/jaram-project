package com.jaram.be.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.io.IOException;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class NotificationConsumer {
    private final ObjectMapper objectMapper;
    private final NotificationStore notificationStore;

    @RabbitListener(queues = NotificationMessaging.NOTIFICATION_QUEUE_NAME, containerFactory = "notificationListenerFactory")
    public void consume(Message message) {
        NotificationEvent event = deserialize(message);
        validateRoutingKey(event, message.getMessageProperties().getReceivedRoutingKey());
        // Returning from the listener signals success only after the save transaction commits.
        notificationStore.save(event);
    }

    private NotificationEvent deserialize(Message message) {
        NotificationEvent event;
        try {
            event = objectMapper.readValue(message.getBody(), NotificationEvent.class);
        } catch (IOException exception) {
            throw new AmqpRejectAndDontRequeueException("Cannot deserialize notification event JSON", exception);
        }
        if (event == null) {
            throw new AmqpRejectAndDontRequeueException("Notification event body must not be null");
        }
        return event;
    }

    private void validateRoutingKey(NotificationEvent event, String receivedRoutingKey) {
        if (!event.type().routingKey().equals(receivedRoutingKey)) {
            throw new AmqpRejectAndDontRequeueException(
                    "Routing key does not match notification event " + event.eventId() + " of type "
                            + event.type().routingKey());
        }
    }
}
