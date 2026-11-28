package com.jaram.be.notification.push;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.notification.NotificationEvent;
import com.jaram.be.notification.NotificationType;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.support.PostgresTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebPushContractTest extends PostgresTest {
    private static final String PUSH = "/api/notifications/push";
    private static final String AUTH = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String UPDATED_AUTH = "BBBBBBBBBBBBBBBBBBBBBA";

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired JwtProvider jwt;
    @Autowired PushSubscriptions subscriptions;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcClient jdbc;
    @MockitoBean VapidWebPush push;

    private final OpenApiValidationFilter validation = new OpenApiValidationFilter(
            OpenApiInteractionValidator.createForSpecificationUrl("openapi/openapi.yaml")
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request.parameter.schema.invalidJson", ValidationReport.Level.IGNORE)
                            .build())
                    .build());

    private Member owner;
    private Member other;
    private String endpoint;
    private String browserKey;

    @BeforeEach
    void setUp() throws Exception {
        owner = activeMember();
        other = activeMember();
        endpoint = "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();
        browserKey = VapidWebPushTest.keyPair().publicKey();
        when(push.publicKey()).thenReturn(browserKey);
    }

    @Test
    void publicKeyAndAllSubscriptionOperationsRequireJwt() {
        given().port(port).when().get(PUSH + "/public-key").then().statusCode(401);
        given().port(port).when().post(PUSH + "/subscriptions").then().statusCode(401);
        given().port(port).when().delete(PUSH + "/subscriptions/" + UUID.randomUUID()).then().statusCode(401);

        authenticated(owner).filter(validation).when().get(PUSH + "/public-key")
                .then().statusCode(200).body("publicKey", equalTo(browserKey));
    }

    @Test
    void refreshesInPlaceAndProtectsOwnership() {
        String id = register(owner, endpoint, AUTH);
        long expiration = Instant.now().plusSeconds(60).toEpochMilli();

        authenticated(owner).contentType("application/json")
                .body(Map.of("endpoint", endpoint, "keys", keys(UPDATED_AUTH), "expirationTime", expiration))
                .when().post(PUSH + "/subscriptions")
                .then().statusCode(200).body("id", equalTo(id));
        assertThat(subscriptions.findActive(owner.getId(), Instant.ofEpochMilli(expiration).minusMillis(1)))
                .singleElement().satisfies(row -> assertThat(row.auth()).isEqualTo(UPDATED_AUTH));
        assertThat(subscriptions.findActive(owner.getId(), Instant.ofEpochMilli(expiration))).isEmpty();

        authenticated(other).filter(validation).contentType("application/json")
                .body(Map.of("endpoint", endpoint, "keys", keys(AUTH)))
                .when().post(PUSH + "/subscriptions")
                .then().statusCode(409).body("code", equalTo("CONFLICT"));

        delete(other, id);
        assertThat(subscriptions.findActive(owner.getId(), Instant.ofEpochMilli(expiration).minusMillis(1)))
                .hasSize(1);
        delete(owner, id);
        delete(owner, id);
        assertThat(register(other, endpoint, AUTH)).isNotEqualTo(id);
    }

    @Test
    void rejectsInvalidDestinationAndSubscriptionId() {
        authenticated(owner).contentType("application/json")
                .body(Map.of("endpoint", "https://fcm.googleapis.com.example.invalid/fcm/send/fixture",
                        "keys", keys(AUTH)))
                .when().post(PUSH + "/subscriptions")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
        authenticated(owner).when().delete(PUSH + "/subscriptions/not-a-uuid")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    @Test
    void pushFailureLeavesCommittedInboxAndSseIntact() throws Exception {
        String id = register(owner, endpoint, AUTH);
        register(other, endpoint + "-other", AUTH);
        register(owner, endpoint + "-second", AUTH);
        when(push.configured()).thenReturn(true);
        BlockingQueue<SendAttempt> attempts = new LinkedBlockingQueue<>();
        when(push.send(any(), any())).thenAnswer(invocation -> {
            PushSubscriptions.Subscription subscription = invocation.getArgument(0);
            byte[] payload = invocation.getArgument(1);
            int committedCount = jdbc.sql("select count(*) from notification where recipient_id = :id")
                    .param("id", owner.getId()).query(Integer.class).single();
            attempts.add(new SendAttempt(subscription.id(), committedCount,
                    new String(payload, StandardCharsets.UTF_8)));
            if (subscription.id().equals(UUID.fromString(id))) throw new IOException("push failed");
            return 201;
        });

        NotificationEvent event = NotificationEvent.create(NotificationType.SEMINAR_CREATED, "seminar", "seminar",
                List.of(owner.getId()));
        HttpURLConnection stream = (HttpURLConnection) URI.create(
                "http://localhost:" + port + "/api/notifications/stream").toURL().openConnection();
        stream.setRequestProperty("Authorization", "Bearer " + token(owner));
        stream.setReadTimeout(5000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream.getInputStream(),
                StandardCharsets.UTF_8))) {
            assertThat(reader.readLine()).contains("connected");
            transactions.executeWithoutResult(status -> {
                save(event);
                verifyNoInteractionsWithSender();
                status.setRollbackOnly();
            });
            verifyNoInteractionsWithSender();
            save(event);
            assertThat(reader.lines().limit(4).anyMatch(line -> line.contains("notifications.changed"))).isTrue();
        } finally {
            completeStream();
            stream.disconnect();
        }

        SendAttempt first = attempts.poll(5, TimeUnit.SECONDS);
        SendAttempt second = attempts.poll(5, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        List<SendAttempt> delivered = List.of(first, second);
        assertThat(delivered).extracting(SendAttempt::subscriptionId).contains(UUID.fromString(id));
        assertThat(delivered).allSatisfy(attempt -> {
            assertThat(attempt.committedCount()).isEqualTo(1);
            assertThat(attempt.payload()).contains("\"title\"", "\"body\"", "\"targetPath\":\"/seminar\"")
                    .doesNotContain(endpoint);
        });
        verify(push, never()).send(argThat(row -> row.endpoint().endsWith("-other")), any());
        save(event);
        authenticated(owner).when().get("/api/notifications")
                .then().statusCode(200).body("totalElements", equalTo(1))
                .body("items[0].targetPath", equalTo("/seminar"));
        verify(push, times(2)).send(any(), any());
    }

    private void completeStream() {
        Map<?, ?> connections = (Map<?, ?>) ReflectionTestUtils.getField(
                context.getBean("notificationStreams"), "connections");
        if (connections != null && connections.get(owner.getId()) instanceof Set<?> emitters) {
            emitters.forEach(emitter -> ((SseEmitter) emitter).complete());
        }
    }

    private record SendAttempt(UUID subscriptionId, int committedCount, String payload) {}

    private void verifyNoInteractionsWithSender() {
        assertThat(mockingDetails(push).getInvocations()).noneMatch(call -> call.getMethod().getName().equals("send"));
    }

    private void save(NotificationEvent event) {
        ReflectionTestUtils.invokeMethod(context.getBean("notificationStore"), "save", event);
    }

    private String register(Member member, String url, String auth) {
        return authenticated(member).filter(validation).contentType("application/json")
                .body(Map.of("endpoint", url, "keys", keys(auth)))
                .when().post(PUSH + "/subscriptions")
                .then().statusCode(200).extract().path("id");
    }

    private void delete(Member member, String id) {
        authenticated(member).filter(validation).when().delete(PUSH + "/subscriptions/{id}", id)
                .then().statusCode(204);
    }

    private Map<String, String> keys(String auth) {
        return Map.of("p256dh", browserKey, "auth", auth);
    }

    private RequestSpecification authenticated(Member member) {
        return given().port(port).header("Authorization", "Bearer " + token(member));
    }

    private String token(Member member) {
        return jwt.generate(member.getId(), member.getName(), member.getEmail());
    }

    private Member activeMember() {
        String suffix = UUID.randomUUID().toString();
        Member member = Member.newPending("Web Push fixture", suffix, suffix + "@example.invalid", "fixture-hash");
        member.setApproval(MemberApproval.APPROVED);
        member.setStatus(MemberStatus.ACTIVE);
        return members.save(member);
    }
}
