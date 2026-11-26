package com.jaram.be.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.LockMode;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class NotificationOutbox {
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(NotificationEvent event) {
        if (event.recipientIds().isEmpty()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(event);
            // The assigned UUID identifies a new row. A merge could update an existing event.
            entityManager.persist(new OutboxEvent(event, payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize notification event " + event.eventId(), exception);
        }
    }

    Optional<OutboxEvent> lockNext(Instant dueBefore) {
        // Hibernate's lock mode skips rows held by other publishers until their transactions finish.
        return entityManager.unwrap(Session.class).createSelectionQuery("""
                        select e from OutboxEvent e
                        where e.publishedAt is null and e.nextAttemptAt <= :dueBefore
                        order by e.nextAttemptAt, e.createdAt, e.eventId
                        """, OutboxEvent.class)
                .setParameter("dueBefore", dueBefore)
                .setHibernateLockMode(LockMode.UPGRADE_SKIPLOCKED)
                .setMaxResults(1)
                .getResultList().stream().findFirst();
    }
}
