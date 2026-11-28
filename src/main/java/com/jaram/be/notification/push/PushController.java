package com.jaram.be.notification.push;

import com.jaram.be.common.ApiException;
import com.jaram.be.common.ErrorResponse;
import com.jaram.be.security.CurrentMember;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications/push")
@RequiredArgsConstructor
class PushController {
    private final PushSubscriptions subscriptions;
    private final VapidWebPush transport;

    @GetMapping("/public-key")
    PublicKeyResponse publicKey() {
        return new PublicKeyResponse(transport.publicKey());
    }

    @PostMapping("/subscriptions")
    SubscriptionResponse register(@AuthenticationPrincipal CurrentMember member,
                                  @Valid @RequestBody PushSubscriptionRequest request) {
        if (!PushEndpoint.supported(request.endpoint())) {
            throw invalidField("endpoint", "지원하는 웹 푸시 서비스의 HTTPS 주소를 입력해 주세요.");
        }
        if (!VapidWebPush.validPublicKey(request.keys().p256dh())) {
            throw invalidField("keys.p256dh", "유효한 P-256 공개 키가 필요합니다.");
        }
        return new SubscriptionResponse(subscriptions.upsert(member.id(), request));
    }

    @DeleteMapping("/subscriptions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal CurrentMember member, @PathVariable UUID id) {
        subscriptions.deleteOwned(member.id(), id);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ErrorResponse invalidRequest() {
        return ErrorResponse.of("VALIDATION", "입력값을 확인해 주세요.");
    }

    private ApiException invalidField(String field, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION", "입력값을 확인해 주세요.",
                Map.of(field, message));
    }

    record PublicKeyResponse(String publicKey) {}
    record SubscriptionResponse(UUID id) {}
}
