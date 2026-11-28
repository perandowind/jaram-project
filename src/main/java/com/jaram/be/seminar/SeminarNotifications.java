package com.jaram.be.seminar;

import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.notification.NotificationEvent;
import com.jaram.be.notification.NotificationOutbox;
import com.jaram.be.notification.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
@RequiredArgsConstructor
class SeminarNotifications {
    private final SeminarRepository seminars;
    private final MemberRepository members;
    private final NotificationOutbox outbox;

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmed(Seminar seminar, Instant now) {
        seminar.refreshNotificationSchedule(now);
        if (seminar.recordCreationNotification()) {
            enqueue(seminar, NotificationType.SEMINAR_CREATED);
        }
    }

    @Transactional
    public boolean dispatchNext(Instant now) {
        // PostgreSQL stores timestamps to microsecond precision.
        Instant cutoff = now.truncatedTo(ChronoUnit.MICROS);
        Optional<Seminar> next = seminars.lockNextDueNotification(cutoff);
        if (next.isEmpty()) return false;

        Seminar seminar = next.get();
        if (seminar.recordDueReminder(cutoff)) {
            enqueue(seminar, NotificationType.SEMINAR_REMINDER);
        }
        if (seminar.recordDueStartNotification(cutoff)) {
            enqueue(seminar, NotificationType.SEMINAR_STARTED);
        }
        return true;
    }

    private void enqueue(Seminar seminar, NotificationType type) {
        outbox.enqueue(NotificationEvent.create(type, seminar.getId(), seminar.getId(),
                members.findIdsByStatus(MemberStatus.ACTIVE)));
    }
}
