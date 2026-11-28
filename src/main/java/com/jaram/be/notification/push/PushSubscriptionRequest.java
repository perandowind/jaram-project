package com.jaram.be.notification.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

record PushSubscriptionRequest(
        @NotBlank @Size(max = 2048) String endpoint,
        @PositiveOrZero Long expirationTime,
        @NotNull @Valid Keys keys
) {
    record Keys(
            @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{87}") String p256dh,
            @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{22}") String auth
    ) {}
}
