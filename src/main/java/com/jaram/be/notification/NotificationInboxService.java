package com.jaram.be.notification;

import com.jaram.be.common.ApiException;
import com.jaram.be.notification.dto.NotificationPage;
import com.jaram.be.notification.dto.NotificationUnreadCount;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class NotificationInboxService {

    private final NotificationRepository repository;

    @Transactional(readOnly = true)
    public NotificationPage list(String recipientId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION", "알림 목록의 페이지 범위를 확인해 주세요.");
        }

        Page<NotificationPage.Item> result = repository
                .findByRecipientIdOrderByCreatedAtDescIdDesc(recipientId, PageRequest.of(page, size))
                .map(notification -> new NotificationPage.Item(
                        notification.getId(), notification.getType(), notification.getTitle(),
                        notification.getMessage(), notification.getTargetPath(), notification.getResourceId(),
                        notification.getCreatedAt(), notification.getReadAt()));
        return new NotificationPage(result.getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public NotificationUnreadCount unreadCount(String recipientId) {
        return new NotificationUnreadCount(repository.countByRecipientIdAndReadAtIsNull(recipientId));
    }

    @Transactional
    public void markRead(String recipientId, UUID id) {
        Notification notification = repository.findByIdAndRecipientId(id, recipientId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "알림을 찾을 수 없습니다."));
        notification.markRead();
    }
}
