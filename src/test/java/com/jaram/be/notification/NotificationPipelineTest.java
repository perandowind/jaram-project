package com.jaram.be.notification;

import com.jaram.be.auth.AuthService;
import com.jaram.be.auth.dto.SignupRequest;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.study.StudyService;
import com.jaram.be.study.dto.StudyCreateRequest;
import com.jaram.be.study.StudyApplication;
import com.jaram.be.study.StudyApplicationRepository;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jaram.be.security.JwtProvider;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jaram.notifications.enabled=true",
        "jaram.notifications.poll-delay-ms=100",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true"
})
class NotificationPipelineTest {
    // The publisher and listener commit on separate threads, so use an isolated database.
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    private static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.4.1-management")
            .withEnv("RABBITMQ_DEFAULT_USER", "jaram")
            .withEnv("RABBITMQ_DEFAULT_PASS", "notification-test")
            .withExposedPorts(5672)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void connectionProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "jaram");
        registry.add("spring.rabbitmq.password", () -> "notification-test");
    }

    @Autowired AuthService auth;
    @Autowired StudyService studies;
    @Autowired StudyApplicationRepository applications;
    @LocalServerPort int port;
    @Autowired JwtProvider jwt;
    @Autowired NotificationOutbox outbox;
    @Autowired NotificationStore store;
    @Autowired TransactionTemplate transaction;
    @Autowired EntityManager entityManager;
    @Autowired MemberRepository members;
    @Autowired RabbitTemplate rabbit;

    @Test
    void commitsAndPublishesThenDeduplicatesRedelivery() {
        Member member = newMember();
        String memberId = member.getId();
        NotificationEvent event = NotificationEvent.create(NotificationType.STUDY_APPROVED, "application", "study",
                List.of(memberId, memberId, "purged-member"));
        transaction.executeWithoutResult(status -> {
            members.save(member);
            outbox.enqueue(event);
        });

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(notificationCount(event.eventId())).isEqualTo(1);
            assertThat(publishedCount(event.eventId())).isEqualTo(1);
        });
        assertThat(entityManager.createQuery("""
                select n.recipientId, n.type, n.targetPath, n.resourceId
                from Notification n where n.eventId = :id
                """, Object[].class).setParameter("id", event.eventId()).getSingleResult())
                .containsExactly(memberId, "study.application.approved", "/study", "study");

        updateOutbox("update OutboxEvent e set e.publishedAt = null where e.eventId = :id", event.eventId());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(publishedCount(event.eventId())).isEqualTo(1);
            assertThat(notificationCount(event.eventId())).isEqualTo(1);
        });
        // The same queue and consumer process this sentinel after the duplicate delivery.
        NotificationEvent sentinel = NotificationEvent.create(NotificationType.STUDY_APPROVED, "next-application", "study",
                List.of(memberId));
        transaction.executeWithoutResult(status -> outbox.enqueue(sentinel));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(notificationCount(sentinel.eventId())).isEqualTo(1));
        assertThat(notificationCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void storesOneNotificationForConcurrentDeliveries() throws Exception {
        Member member = newMember();
        String memberId = member.getId();
        members.save(member);
        NotificationEvent event = NotificationEvent.create(NotificationType.STUDY_APPROVED, "application", "study",
                List.of(memberId));
        CountDownLatch firstSaved = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            store.save(event);
            firstSaved.countDown();
            try {
                assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }));
        assertThat(firstSaved.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
            secondStarted.countDown();
            store.save(event);
        });

        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
        assertThat(notificationCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void concurrentPublishersSkipLockedOutboxRows() throws Exception {
        NotificationEvent first = NotificationEvent.create(NotificationType.STUDY_APPROVED, "first", "study", List.of("member"));
        NotificationEvent second = NotificationEvent.create(NotificationType.STUDY_APPROVED, "second", "study", List.of("member"));
        Instant scheduledAt = Instant.now().plusSeconds(3600);
        transaction.executeWithoutResult(status -> {
            outbox.enqueue(first);
            outbox.enqueue(second);
            entityManager.createQuery("update OutboxEvent e set e.nextAttemptAt = :scheduledAt where e.eventId in :ids")
                    .setParameter("scheduledAt", scheduledAt)
                    .setParameter("ids", List.of(first.eventId(), second.eventId()))
                    .executeUpdate();
        });

        Instant cutoff = scheduledAt.plusSeconds(1);
        CompletableFuture<UUID> lockedId = new CompletableFuture<>();
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            lockedId.complete(outbox.lockNext(cutoff).orElseThrow().getEventId());
            try {
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }));
        try {
            UUID firstId = lockedId.get(5, TimeUnit.SECONDS);
            UUID nextId = CompletableFuture.supplyAsync(() ->
                    transaction.execute(status -> outbox.lockNext(cutoff).orElseThrow().getEventId()))
                    .get(5, TimeUnit.SECONDS);
            assertThat(List.of(firstId, nextId)).containsExactlyInAnyOrder(first.eventId(), second.eventId());
        } finally {
            release.countDown();
            worker.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void rollsBackBusinessChangeAndOutboxTogether() {
        Member member = newMember();
        String memberId = member.getId();
        NotificationEvent event = NotificationEvent.create(NotificationType.SIGNUP_REQUESTED, memberId, memberId, List.of(memberId));
        assertThatThrownBy(() -> outbox.enqueue(event)).isInstanceOf(IllegalTransactionStateException.class);
        transaction.executeWithoutResult(status -> {
            members.save(member);
            outbox.enqueue(event);
            status.setRollbackOnly();
        });

        assertThat(members.existsById(memberId)).isFalse();
        assertThat(entityManager.find(OutboxEvent.class, event.eventId())).isNull();
        assertThat(notificationCount(event.eventId())).isZero();
    }

    @Test
    void reschedulesUnroutableEventsAndPublishesAfterRecovery() {
        Member member = newMember();
        String memberId = member.getId();
        NotificationEvent event = NotificationEvent.create(NotificationType.STUDY_REJECTED, "application", "study", List.of(memberId));
        transaction.executeWithoutResult(status -> {
            members.save(member);
            outbox.enqueue(event);
            entityManager.createQuery("update OutboxEvent e set e.routingKey = 'missing-binding' where e.eventId = :id")
                    .setParameter("id", event.eventId())
                    .executeUpdate();
        });

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(count("""
                        select count(e) from OutboxEvent e
                        where e.eventId = :id and e.attempts > 0 and e.publishedAt is null
                        """, event.eventId())).isEqualTo(1));
        transaction.executeWithoutResult(status -> entityManager.createQuery(
                        "update OutboxEvent e set e.routingKey = :routingKey where e.eventId = :id")
                .setParameter("routingKey", event.type().routingKey())
                .setParameter("id", event.eventId())
                .executeUpdate());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(notificationCount(event.eventId())).isEqualTo(1));
    }

    @Test
    void retriesTransientDatabaseFailureBeforeAcknowledging() {
        Member member = newMember();
        String memberId = member.getId();
        NotificationEvent event = NotificationEvent.create(NotificationType.STUDY_APPROVED, "retry-application", "study",
                List.of(memberId));
        executeDdl("create sequence notification_test_attempt");
        try {
            executeDdl("""
                    create function notification_test_retry() returns trigger language plpgsql as $$
                    begin
                        if nextval('notification_test_attempt') = 1 then
                            raise exception 'simulated serialization failure' using errcode = '40001';
                        end if;
                        return new;
                    end
                    $$
                    """);
            executeDdl("""
                    create trigger notification_test_retry before insert on notification
                    for each row when (new.event_id = '%s') execute function notification_test_retry()
                    """.formatted(event.eventId()));

            transaction.executeWithoutResult(status -> {
                members.save(member);
                outbox.enqueue(event);
            });

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(notificationCount(event.eventId()))
                            .isEqualTo(1));
            assertThat(entityManager.createNativeQuery("select last_value from notification_test_attempt", Long.class).getSingleResult())
                    .isEqualTo(2L);
        } finally {
            executeDdl("drop trigger if exists notification_test_retry on notification");
            executeDdl("drop function if exists notification_test_retry()");
            executeDdl("drop sequence notification_test_attempt");
        }
    }

    @Test
    void sendsMalformedEventsToDeadLetterQueue() {
        Message malformed = MessageBuilder.withBody("not json".getBytes(StandardCharsets.UTF_8)).build();
        rabbit.send(NotificationMessaging.EVENT_EXCHANGE_NAME, NotificationType.SIGNUP_REQUESTED.routingKey(), malformed);

        Message deadLetter = rabbit.receive(NotificationMessaging.DEAD_LETTER_QUEUE_NAME, 10_000);
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.getBody()).isEqualTo(malformed.getBody());
    }

    @Test
    void signupNotifiesCurrentPresidentAndVicePresident() throws Exception {
        Member president = officer(MemberTitle.PRESIDENT, false);
        Member vicePresident = officer(MemberTitle.VICE_PRESIDENT, false);
        officer(MemberTitle.PRESIDENT, true);

        SignupRequest signup = new SignupRequest("신청자", "2026012345", "signup@hanyang.ac.kr",
                "Fixture123!", 42, "컴퓨터학부", "01000000000", true, true);
        try (BufferedReader stream = openStream(president)) {
            auth.signup(signup);
            assertChange(stream, president, "member.signup.requested");
        }
        String memberId = members.findByEmail(signup.email()).orElseThrow().getId();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(recipients(memberId, "member.signup.requested", "/admin/applications"))
                        .containsExactlyInAnyOrder(president.getId(), vicePresident.getId()));
    }

    private Member officer(MemberTitle title, boolean former) {
        Member member = newMember();
        member.setApproval(MemberApproval.APPROVED);
        member.setStatus(MemberStatus.ACTIVE);
        member.assignTerm(MemberDepartment.LEADERSHIP, title, 41);
        if (former) member.endCurrentTerm(42);
        return members.save(member);
    }

    @Test
    void studyDecisionsNotifyOnlyApplicantOncePerChange() throws Exception {
        Member applicant = newMember();
        applicant.setApproval(MemberApproval.APPROVED);
        applicant.setStatus(MemberStatus.ACTIVE);
        members.save(applicant);
        String studyId = UUID.randomUUID().toString();
        StudyApplication application = applications.save(StudyApplication.create(studyId, applicant.getId(), "지원"));
        try (BufferedReader stream = openStream(applicant)) {
            studies.approveApplicant(application.getId());
            assertChange(stream, applicant, "study.application.approved");
            studies.approveApplicant(application.getId());
            studies.rejectApplicant(application.getId(), "반려");
            assertChange(stream, applicant, "study.application.rejected");
        }
        studies.rejectApplicant(application.getId(), "사유 수정");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(recipients(studyId, "study.application.approved", "/study")).containsExactly(applicant.getId());
            assertThat(recipients(studyId, "study.application.rejected", "/study")).containsExactly(applicant.getId());
        });
    }

    private BufferedReader openStream(Member member) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "http://localhost:" + port + "/api/notifications/stream").toURL().openConnection();
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Authorization", "Bearer " + jwt.generate(member.getId(), member.getName(), member.getEmail()));
        assertThat(connection.getResponseCode()).isEqualTo(200);
        BufferedReader stream = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        assertThat(stream.readLine()).isEqualTo(":connected");
        assertThat(stream.readLine()).isEmpty();
        return stream;
    }

    private void assertChange(BufferedReader stream, Member owner, String type) throws Exception {
        assertThat(stream.readLine()).isEqualTo("event:notifications.changed");
        String data = stream.readLine();
        UUID id = entityManager.createQuery("""
                select n.id from Notification n where n.recipientId = :owner and n.type = :type
                """, UUID.class).setParameter("owner", owner.getId()).setParameter("type", type).getSingleResult();
        assertThat(data).isEqualTo("data:{\"id\":\"" + id + "\"}");
        assertThat(stream.readLine()).isEmpty();
    }

    @Test
    void studyCreationPublicationAndApplicationReachTheirRecipients() {
        Member president = officer(MemberTitle.PRESIDENT, false);
        Member academicLead = newMember();
        academicLead.setApproval(MemberApproval.APPROVED);
        academicLead.setStatus(MemberStatus.ACTIVE);
        academicLead.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        members.save(academicLead);

        Member leader = newMember();
        leader.setApproval(MemberApproval.APPROVED);
        leader.setStatus(MemberStatus.ACTIVE);
        members.save(leader);
        Member applicant = newMember();
        applicant.setApproval(MemberApproval.APPROVED);
        applicant.setStatus(MemberStatus.ACTIVE);
        members.save(applicant);

        List<String> activeIds = members.findIdsByStatus(MemberStatus.ACTIVE);
        String title = "알림 통합 테스트 스터디";
        List<String> reviewers = members.findByApproval(MemberApproval.APPROVED).stream()
                .filter(member -> member.getStatus() != MemberStatus.WITHDRAWN)
                .filter(member -> member.getTitle() == MemberTitle.PRESIDENT
                        || member.getTitle() == MemberTitle.VICE_PRESIDENT
                        || (member.getDepartment() == MemberDepartment.ACADEMIC && member.getTitle() == MemberTitle.LEAD))
                .map(Member::getId).toList();

        studies.setRecruitmentOpen(true);
        String studyId = studies.create(new StudyCreateRequest(title, List.of("Java"), 2,
                "스터디 소개", "금요일", "온라인", "ONLINE", "연락처",
                List.of(new StudyCreateRequest.WeekInput(1, "첫 주", null))), leader.getId()).id();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(recipients(studyId, "study.creation.requested", "/admin/studies"))
                        .containsExactlyInAnyOrderElementsOf(reviewers)
                        .contains(president.getId(), academicLead.getId())
                        .doesNotContain(leader.getId(), applicant.getId()));

        studies.approveStudy(studyId);
        studies.approveStudy(studyId);
        studies.apply(studyId, applicant.getId(), "지원");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(recipients(studyId, "study.opened", "/study"))
                    .containsExactlyInAnyOrderElementsOf(activeIds);
            assertThat(recipients(studyId, "study.application.requested", "/study"))
                    .containsExactly(leader.getId());
            assertThat(entityManager.createQuery("""
                    select distinct n.message from Notification n where n.resourceId = :studyId and n.type = 'study.opened'
                    """, String.class).setParameter("studyId", studyId).getResultList())
                    .containsExactly("새로운 스터디가 개설되었습니다! " + title);
        });
    }

    private List<String> recipients(String resourceId, String type, String path) {
        return entityManager.createQuery("""
                select n.recipientId from Notification n
                where n.resourceId = :resource and n.type = :type and n.targetPath = :path
                """, String.class).setParameter("resource", resourceId).setParameter("type", type)
                .setParameter("path", path).getResultList();
    }

    private Member newMember() {
        String suffix = UUID.randomUUID().toString();
        return Member.newPending("Notification fixture", suffix, suffix + "@example.invalid", "fixture-hash");
    }

    private void updateOutbox(String jpql, UUID eventId) {
        transaction.executeWithoutResult(status ->
                entityManager.createQuery(jpql).setParameter("id", eventId).executeUpdate());
    }

    private void executeDdl(String sql) {
        transaction.executeWithoutResult(status -> entityManager.createNativeQuery(sql).executeUpdate());
    }

    private long notificationCount(UUID eventId) {
        return count("select count(n) from Notification n where n.eventId = :id", eventId);
    }

    private long publishedCount(UUID eventId) {
        return count("select count(e) from OutboxEvent e where e.eventId = :id and e.publishedAt is not null", eventId);
    }

    private long count(String jpql, UUID eventId) {
        return entityManager.createQuery(jpql, Long.class).setParameter("id", eventId).getSingleResult();
    }
}
