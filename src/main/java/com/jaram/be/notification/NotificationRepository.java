package com.jaram.be.notification;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Page<Notification> findByRecipientIdOrderByCreatedAtDescIdDesc(String recipientId, Pageable pageable);

    long countByRecipientIdAndReadAtIsNull(String recipientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Notification> findByIdAndRecipientId(UUID id, String recipientId);

    @Query("select n.recipientId from Notification n where n.eventId = :eventId")
    Set<String> findRecipientIdsByEventId(@Param("eventId") UUID eventId);
}
