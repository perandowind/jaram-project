package com.jaram.be.notification;

import com.jaram.be.common.ErrorResponse;
import com.jaram.be.notification.dto.NotificationPage;
import com.jaram.be.notification.dto.NotificationUnreadCount;
import com.jaram.be.security.CurrentMember;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationInboxService inbox;

    private final NotificationStreams streams;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal CurrentMember member, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return streams.subscribe(member.id());
    }

    @GetMapping
    public NotificationPage list(@AuthenticationPrincipal CurrentMember member,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return inbox.list(member.id(), page, size);
    }

    @GetMapping("/unread-count")
    public NotificationUnreadCount unreadCount(@AuthenticationPrincipal CurrentMember member) {
        return inbox.unreadCount(member.id());
    }

    @PatchMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal CurrentMember member, @PathVariable UUID id) {
        inbox.markRead(member.id(), id);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse invalidParameter() {
        return ErrorResponse.of("VALIDATION", "입력값을 확인해 주세요.");
    }
}
