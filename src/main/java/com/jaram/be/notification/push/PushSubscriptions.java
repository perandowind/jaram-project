package com.jaram.be.notification.push;

import com.jaram.be.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class PushSubscriptions {
    private final JdbcClient jdbc;

    UUID upsert(String recipientId, PushSubscriptionRequest request) {
        // PostgreSQL arbitrates concurrent registrations using the unique endpoint index.
        return jdbc.sql("""
                        insert into web_push_subscription (id, recipient_id, endpoint, p256dh, auth, expiration_time)
                        values (:id, :recipient, :endpoint, :p256dh, :auth, :expires)
                        on conflict (endpoint) do update
                        set p256dh = excluded.p256dh, auth = excluded.auth, expiration_time = excluded.expiration_time
                        where web_push_subscription.recipient_id = excluded.recipient_id
                        returning id
                        """)
                .param("id", UUID.randomUUID())
                .param("recipient", recipientId)
                .param("endpoint", request.endpoint())
                .param("p256dh", request.keys().p256dh())
                .param("auth", request.keys().auth())
                .param("expires", request.expirationTime())
                .query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "CONFLICT",
                        "다른 계정에 등록된 푸시 구독입니다."));
    }

    void deleteOwned(String recipientId, UUID id) {
        jdbc.sql("delete from web_push_subscription where recipient_id = :recipient and id = :id")
                .param("recipient", recipientId).param("id", id).update();
    }

    List<Subscription> findActive(String recipientId, Instant now) {
        return jdbc.sql("""
                        select id, endpoint, p256dh, auth from web_push_subscription
                        where recipient_id = :recipient and (expiration_time is null or expiration_time > :now)
                        """)
                .param("recipient", recipientId).param("now", now.toEpochMilli())
                .query((rs, row) -> new Subscription(rs.getObject("id", UUID.class),
                        rs.getString("endpoint"), rs.getString("p256dh"), rs.getString("auth")))
                .list();
    }

    record Subscription(UUID id, String endpoint, String p256dh, String auth) {}
}
