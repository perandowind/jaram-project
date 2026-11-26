package com.jaram.be.notification;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum NotificationType {
    SIGNUP_REQUESTED(
            "member.signup.requested", "가입 승인 요청", "새 가입 신청이 접수되었습니다.", "/admin/applications"),
    SEMINAR_CREATED(
            "seminar.created", "새 세미나", "새 세미나가 개설되었습니다.", "/seminar"),
    SEMINAR_REMINDER(
            "seminar.reminder", "세미나 사전 알림", "세미나 시작까지 24시간 남았습니다.", "/seminar"),
    SEMINAR_STARTED(
            "seminar.started", "세미나 시작", "세미나가 시작되었습니다.", "/seminar"),
    STUDY_APPROVED(
            "study.application.approved", "스터디 지원 승인", "스터디 지원이 승인되었습니다.", "/study"),
    STUDY_REJECTED(
            "study.application.rejected", "스터디 지원 반려", "스터디 지원이 반려되었습니다.", "/study");

    @Getter(AccessLevel.NONE)
    private final String routingKey;
    private final String title;
    private final String message;
    private final String targetPath;

    @JsonValue
    public String routingKey() {
        return routingKey;
    }
}
