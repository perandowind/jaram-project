package com.jaram.be.seminar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.notification.NotificationEvent;
import com.jaram.be.notification.NotificationType;
import com.jaram.be.seminar.dto.SeminarCreateRequest;
import com.jaram.be.support.PostgresTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "jaram.notifications.enabled=false")
@Transactional
class SeminarNotificationTest extends PostgresTest {
    @Autowired SeminarService seminars;
    @Autowired SeminarNotifications notifications;
    @Autowired SeminarRepository repository;
    @Autowired MemberRepository members;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper objectMapper;
    @BeforeEach
    void isolateDueReservations() {
        entityManager.createQuery("""
                update Seminar s set s.reminderDueAt = null, s.startNotificationDueAt = null
                """).executeUpdate();
    }

    @Test
    void dispatchesEachEventOnceToMembersActiveAtThatTime() throws Exception {
        Member first = saveMember(MemberStatus.ACTIVE);
        Member second = saveMember(MemberStatus.ON_LEAVE);
        Instant startsAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofDays(3));
        Instant reminderAt = startsAt.minus(Duration.ofHours(24));
        String id = seminars.create(request(startsAt), null).id();

        assertEvent(id, NotificationType.SEMINAR_CREATED, first.getId(), second.getId());

        first.setStatus(MemberStatus.ON_LEAVE);
        second.setStatus(MemberStatus.ACTIVE);
        assertThat(dispatch(reminderAt.minusSeconds(1))).isFalse();
        assertThat(dispatch(reminderAt)).isTrue();
        assertThat(dispatch(reminderAt)).isFalse();
        assertEvent(id, NotificationType.SEMINAR_REMINDER, second.getId(), first.getId());

        assertThat(dispatch(startsAt)).isTrue();
        assertThat(dispatch(startsAt)).isFalse();
        assertEvent(id, NotificationType.SEMINAR_STARTED, second.getId(), first.getId());
    }

    @Test
    void schedulesReminderWhenConfirmedExactly24HoursBeforeStart() {
        saveMember(MemberStatus.ACTIVE);
        Instant confirmedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant startsAt = confirmedAt.plus(Duration.ofHours(24));
        Seminar seminar = Seminar.create("세미나", null, null, startsAt,
                null, null, null, null, null, null);
        seminar.approve();
        repository.save(seminar);
        notifications.confirmed(seminar, confirmedAt);

        assertThat(dispatch(confirmedAt.minusSeconds(1))).isFalse();
        assertThat(dispatch(confirmedAt)).isTrue();
        assertThat(events(seminar.getId(), NotificationType.SEMINAR_REMINDER)).hasSize(1);
    }

    @Test
    void skipsReminderForAnOpeningLessThan24HoursAway() {
        saveMember(MemberStatus.ACTIVE);
        Instant startsAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofHours(2));
        String id = seminars.create(request(startsAt), null).id();

        assertThat(dispatch(startsAt)).isTrue();
        assertThat(events(id, NotificationType.SEMINAR_REMINDER)).isEmpty();
        assertThat(events(id, NotificationType.SEMINAR_STARTED)).hasSize(1);
    }

    @Test
    void rejectsPendingReservationsAndRecalculatesThemAfterResubmission() {
        saveMember(MemberStatus.ACTIVE);
        Instant oldStart = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofDays(3));
        Instant newStart = oldStart.plus(Duration.ofDays(2));
        String id = seminars.submitFromSlot(request(oldStart), null, null, oldStart, null, null).id();

        assertThat(events(id, NotificationType.SEMINAR_CREATED)).isEmpty();
        seminars.approve(id);
        seminars.approve(id);
        seminars.reject(id, "일정 변경");
        assertThat(dispatch(oldStart)).isFalse();

        seminars.resubmit(id, request(newStart), null);
        seminars.approve(id);
        assertThat(dispatch(oldStart)).isFalse();
        assertThat(dispatch(newStart.minus(Duration.ofHours(24)))).isTrue();
        assertThat(dispatch(newStart)).isTrue();
        assertThat(events(id, NotificationType.SEMINAR_CREATED)).hasSize(1);
        assertThat(events(id, NotificationType.SEMINAR_REMINDER)).hasSize(1);
        assertThat(events(id, NotificationType.SEMINAR_STARTED)).hasSize(1);
    }

    @Test
    void preservesRecordedReminderAfterRescheduling() {
        saveMember(MemberStatus.ACTIVE);
        Instant oldStart = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofDays(3));
        Instant newStart = oldStart.plus(Duration.ofDays(2));
        String id = seminars.create(request(oldStart), null).id();
        assertThat(dispatch(oldStart.minus(Duration.ofHours(24)))).isTrue();

        seminars.reject(id, "일정 변경");
        seminars.resubmit(id, request(newStart), null);
        seminars.approve(id);
        assertThat(dispatch(newStart.minus(Duration.ofHours(24)))).isFalse();
        assertThat(dispatch(newStart)).isTrue();
        assertThat(events(id, NotificationType.SEMINAR_REMINDER)).hasSize(1);
    }

    @Test
    void skipsAnOverdueReminderWhenTheSeminarHasAlreadyStarted() {
        saveMember(MemberStatus.ACTIVE);
        Instant startsAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofDays(3));
        String id = seminars.create(request(startsAt), null).id();

        assertThat(dispatch(startsAt.plusSeconds(1))).isTrue();
        assertThat(events(id, NotificationType.SEMINAR_REMINDER)).isEmpty();
        assertThat(events(id, NotificationType.SEMINAR_STARTED)).hasSize(1);
    }

    @Test
    void deletingTheSeminarRemovesItsPendingReservations() {
        saveMember(MemberStatus.ACTIVE);
        Instant startsAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofDays(3));
        String id = seminars.create(request(startsAt), null).id();

        repository.deleteById(id);

        assertThat(dispatch(startsAt)).isFalse();
        assertThat(events(id, NotificationType.SEMINAR_REMINDER)).isEmpty();
        assertThat(events(id, NotificationType.SEMINAR_STARTED)).isEmpty();
    }

    private boolean dispatch(Instant now) {
        entityManager.flush();
        entityManager.clear();
        return notifications.dispatchNext(now);
    }

    private List<String> events(String id, NotificationType type) {
        return entityManager.createQuery("""
                select e.payload from OutboxEvent e where e.routingKey = :type
                """, String.class)
                .setParameter("type", type.routingKey())
                .getResultList().stream()
                .filter(payload -> payload.contains(id))
                .toList();
    }

    private void assertEvent(String id, NotificationType type, String recipientId, String excludedId) throws Exception {
        List<String> payloads = events(id, type);
        assertThat(payloads).hasSize(1);
        NotificationEvent event = objectMapper.readValue(payloads.getFirst(), NotificationEvent.class);
        assertThat(event.type().getTargetPath()).isEqualTo("/seminar");
        assertThat(event.resourceId()).isEqualTo(id);
        assertThat(event.recipientIds()).contains(recipientId).doesNotContain(excludedId);
    }

    private Member saveMember(MemberStatus status) {
        String suffix = UUID.randomUUID().toString();
        Member member = Member.newPending("Seminar notification fixture", suffix,
                suffix + "@example.invalid", "fixture-hash");
        member.setStatus(status);
        return members.save(member);
    }

    private SeminarCreateRequest request(Instant startsAt) {
        return new SeminarCreateRequest("세미나", null, null, startsAt, null, null, null, null, null);
    }
}
