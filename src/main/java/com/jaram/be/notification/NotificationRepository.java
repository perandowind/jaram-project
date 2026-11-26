package com.jaram.be.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;
import java.util.UUID;

interface NotificationRepository extends JpaRepository<Notification, UUID> {
    @Query("select n.recipientId from Notification n where n.eventId = :eventId")
    Set<String> findRecipientIdsByEventId(@Param("eventId") UUID eventId);
}
