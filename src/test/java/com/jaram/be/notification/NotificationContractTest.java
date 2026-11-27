package com.jaram.be.notification;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationContractTest extends PostgresTest {

    private static final String INBOX = "/api/notifications";

    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired NotificationRepository notifications;
    @Autowired JwtProvider jwt;

    // swagger-request-validator 2.43.0 misparses OAS 3.1 string path parameters as JSON.
    private final OpenApiValidationFilter validation = new OpenApiValidationFilter(
            OpenApiInteractionValidator.createForSpecificationUrl("openapi/openapi.yaml")
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request.parameter.schema.invalidJson", ValidationReport.Level.IGNORE)
                            .build())
                    .build());

    private String token;
    private UUID ownedId;
    private UUID otherId;

    @BeforeEach
    void setUp() {
        members.deleteAll();
        Member owner = activeMember("2023012345");
        Member other = activeMember("2023012346");
        token = jwt.generate(owner.getId(), owner.getName(), owner.getEmail());
        ownedId = seedNotification(owner);
        otherId = seedNotification(other);
    }

    @Test
    void inboxIsPrivateAndReadingUpdatesTheCountOnlyOnce() {
        given().port(port).filter(validation).header("Authorization", "Bearer " + token)
                .when().get(INBOX)
                .then().statusCode(200)
                .body("totalElements", equalTo(1))
                .body("items.id", equalTo(List.of(ownedId.toString())))
                .body("items[0].readAt", nullValue())
                .body("items[0].targetPath", equalTo("/study"));

        assertUnreadCount(1);

        given().port(port).filter(validation).header("Authorization", "Bearer " + token)
                .when().patch(INBOX + "/{id}/read", otherId)
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
        assertThat(notifications.findById(otherId).orElseThrow().getReadAt()).isNull();

        readOwnedNotification();
        assertUnreadCount(0);

        String readAt = given().port(port).filter(validation).header("Authorization", "Bearer " + token)
                .when().get(INBOX)
                .then().statusCode(200)
                .extract().path("items[0].readAt");
        assertThat(readAt).isNotBlank();

        readOwnedNotification();
        given().port(port).filter(validation).header("Authorization", "Bearer " + token)
                .when().get(INBOX)
                .then().statusCode(200).body("items[0].readAt", equalTo(readAt));
    }

    @Test
    void everyOperationRequiresAuthentication() {
        given().port(port).when().get(INBOX).then().statusCode(401);
        given().port(port).when().get(INBOX + "/stream").then().statusCode(401);
        given().port(port).when().get(INBOX + "/unread-count").then().statusCode(401);
        given().port(port).when().patch(INBOX + "/{id}/read", ownedId).then().statusCode(401);
    }

    @Test
    void paginationAndInvalidParametersFollowTheContract() {
        given().port(port).filter(validation).header("Authorization", "Bearer " + token).queryParam("page", 1).queryParam("size", 1)
                .when().get(INBOX)
                .then().statusCode(200)
                .body("page", equalTo(1)).body("size", equalTo(1))
                .body("totalElements", equalTo(1)).body("items", equalTo(List.of()));

        given().port(port).header("Authorization", "Bearer " + token).queryParam("size", 101)
                .when().get(INBOX).then().statusCode(422).body("code", equalTo("VALIDATION"));
        given().port(port).header("Authorization", "Bearer " + token).queryParam("page", "abc")
                .when().get(INBOX).then().statusCode(422).body("code", equalTo("VALIDATION"));
        given().port(port).header("Authorization", "Bearer " + token)
                .when().patch(INBOX + "/{id}/read", "invalid")
                .then().statusCode(422).body("code", equalTo("VALIDATION"));
    }

    private void assertUnreadCount(int count) {
        given().port(port).filter(validation).header("Authorization", "Bearer " + token)
                .when().get(INBOX + "/unread-count")
                .then().statusCode(200).body("count", equalTo(count));
    }

    private void readOwnedNotification() {
        given().port(port).filter(validation).header("Authorization", "Bearer " + token)
                .when().patch(INBOX + "/{id}/read", ownedId)
                .then().statusCode(204);
    }

    private Member activeMember(String studentId) {
        Member member = Member.newPending("테스트 회원", studentId, studentId + "@hanyang.ac.kr", "hash");
        member.setApproval(MemberApproval.APPROVED);
        member.setStatus(MemberStatus.ACTIVE);
        member.setGrade(MemberGrade.ASSOCIATE);
        member.setGen(41);
        return members.save(member);
    }

    private UUID seedNotification(Member member) {
        NotificationEvent event = NotificationEvent.create(
                NotificationType.STUDY_APPROVED, "application-id", "study-id", List.of(member.getId()));
        return notifications.save(Notification.create(event, member.getId())).getId();
    }
}
