
package com.jaram.be.notification;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.notification.push.PushDelivery;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class NotificationStore {

    private final MemberRepository memberRepository;
    private final NotificationRepository notificationRepository;

    private final ApplicationEventPublisher events;

    @Transactional
    public void save(NotificationEvent event) {
        if (event.recipientIds().isEmpty()) {
            return;
        }

        // Prevent concurrent deliveries from creating duplicate notifications.
        List<Member> lockedRecipients = memberRepository
                .findNotificationRecipientsForUpdate(event.recipientIds());

        Set<String> existingRecipientIds = notificationRepository
                .findRecipientIdsByEventId(event.eventId());

        List<Notification> newNotifications = lockedRecipients.stream()
                .filter(member -> !existingRecipientIds.contains(member.getId()))
                .map(member -> Notification.create(event, member.getId()))
                .toList();

        for (Notification notification : notificationRepository.saveAll(newNotifications)) {
            events.publishEvent(new NotificationStreams.Changed(notification.getRecipientId(), notification.getId()));
            events.publishEvent(new PushDelivery(notification.getId(), notification.getRecipientId(),
                    new PushDelivery.Payload(notification.getTitle(), notification.getMessage(),
                            notification.getTargetPath())));
        }
    }
}
