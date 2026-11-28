package com.jaram.be.notification.push;

import java.util.UUID;

public record PushDelivery(UUID notificationId, String recipientId, Payload payload) {
    public record Payload(String title, String body, String targetPath) {}
}
