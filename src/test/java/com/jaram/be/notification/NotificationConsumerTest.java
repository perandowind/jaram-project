package com.jaram.be.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationConsumerTest {
    private static final String STUDY_APPROVED_EVENT = """
            {
              "eventId": "eccc65a1-5f21-443d-a3e4-2fde4d8c6a9a",
              "type": "study.application.approved",
              "version": 1,
              "occurredAt": "2026-11-26T06:00:00Z",
              "subjectId": "application-id",
              "resourceId": "study-id",
              "recipientIds": ["member-id"]
            }
            """;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final NotificationStore notificationStore = mock(NotificationStore.class);
    private final NotificationConsumer consumer = new NotificationConsumer(objectMapper, notificationStore);

    @Test
    void acceptsAndSerializesTheExistingEventContract() throws Exception {
        NotificationEvent event = objectMapper.readValue(STUDY_APPROVED_EVENT, NotificationEvent.class);
        assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(event)))
                .isEqualTo(objectMapper.readTree(STUDY_APPROVED_EVENT));

        consumer.consume(message(STUDY_APPROVED_EVENT, "study.application.approved"));

        verify(notificationStore).save(event);
    }

    @Test
    void rejectsNullEventBeforeSaving() {
        assertThatThrownBy(() -> consumer.consume(message("null", "study.application.approved")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("body must not be null");
        verifyNoInteractions(notificationStore);
    }

    @Test
    void rejectsMismatchedRoutingKeyBeforeSaving() {
        assertThatThrownBy(() -> consumer.consume(message(STUDY_APPROVED_EVENT, "study.application.rejected")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("Routing key does not match")
                .hasMessageContaining("eccc65a1-5f21-443d-a3e4-2fde4d8c6a9a");
        verifyNoInteractions(notificationStore);
    }

    private Message message(String payload, String routingKey) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(routingKey);
        return new Message(payload.getBytes(StandardCharsets.UTF_8), properties);
    }
}
