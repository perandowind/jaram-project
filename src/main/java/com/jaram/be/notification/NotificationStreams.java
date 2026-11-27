package com.jaram.be.notification;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
class NotificationStreams {
    record Changed(String recipientId, UUID id) {}

    private static final long CONNECTION_TIMEOUT_MS = 60_000;
    private final Map<String, Set<SseEmitter>> connections = new ConcurrentHashMap<>();

    SseEmitter subscribe(String memberId) throws IOException {
        SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT_MS);
        connections.compute(memberId, (id, current) -> {
            Set<SseEmitter> streams = current == null ? ConcurrentHashMap.newKeySet() : current;
            streams.add(emitter);
            return streams;
        });
        Runnable cleanup = () -> remove(memberId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(error -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException error) {
            cleanup.run();
            throw error;
        }
        return emitter;
    }

    @TransactionalEventListener
    public void afterCommit(Changed change) {
        for (SseEmitter emitter : connections.getOrDefault(change.recipientId(), Set.of())) {
            try {
                emitter.send(SseEmitter.event().name("notifications.changed").data(Map.of("id", change.id())));
            } catch (IOException | IllegalStateException closed) {
                remove(change.recipientId(), emitter);
            }
        }
    }

    private void remove(String memberId, SseEmitter emitter) {
        connections.computeIfPresent(memberId, (id, streams) -> {
            streams.remove(emitter);
            return streams.isEmpty() ? null : streams;
        });
    }
}
