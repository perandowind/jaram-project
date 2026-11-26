# 알림 이벤트 및 API 계약

## 이벤트와 수신자

| `type` | 발생 기준 | 수신자 | 이동 경로 |
|---|---|---|---|
| `member.signup.requested` | 가입 신청 접수 성공 | 회장과 부회장 | `/admin/applications` |
| `seminar.created` | 세미나 개설 성공 | 발송 시점에 `ACTIVE`인 부원 | `/seminar` |
| `seminar.reminder` | 세미나 시작 24시간 전 | 발송 시점에 `ACTIVE`인 부원 | `/seminar` |
| `seminar.started` | 세미나 시작 시각 | 발송 시점에 `ACTIVE`인 부원 | `/seminar` |
| `study.application.approved` | 지원 상태가 승인으로 변경 | 해당 신청자 | `/study` |
| `study.application.rejected` | 지원 상태가 반려로 변경 | 해당 신청자 | `/study` |

세미나·스터디 링크는 현재 라우터에 등록된 페이지를 사용한다. 상세 이동을 추가할 때는 `resourceId`로 항목을 선택하고 기존 접근 권한을 적용한다.

이벤트는 JSON으로 발행하며, RabbitMQ 라우팅 키는 `type`과 동일하다.

```json
{
  "eventId": "eccc65a1-5f21-443d-a3e4-2fde4d8c6a9a",
  "type": "study.application.approved",
  "version": 1,
  "occurredAt": "2026-11-26T06:00:00Z",
  "subjectId": "application-id",
  "resourceId": "study-id",
  "recipientIds": ["member-id"]
}
```

- `eventId`는 UUID이며 발행 재시도와 재처리에서 유지한다. 소비자는 `eventId + recipientId`로 중복을 방지한다.
- `subjectId`는 가입 신청을 한 회원, 세미나 또는 스터디 지원 건의 식별자다. `resourceId`는 이동할 화면의 대상 식별자다. `recipientIds`는 대상 회원 ID 배열이다. 역할 기반 수신자는 발행용 이벤트를 기록할 때 조회한다.
- 날짜·시각은 UTC ISO 8601로 주고받는다. 가입 신청과 스터디 결과 등 즉시 이벤트는 업무 변경과 같은 DB 트랜잭션에서 Outbox에 기록한다.
- 개설 시 시작까지 24시간 미만이면 사전 알림을 생략한다. 일정 변경 시 미발송 예약을 재계산하고, 취소 시 제거한다. 예약 작업이 이벤트를 기록하기 직전에 현재 상태와 시각을 확인한다.

## 알림함 HTTP API

모든 요청은 기존 Bearer JWT를 사용한다. 본인의 알림만 조회하거나 수정할 수 있다.

| 메서드 및 경로 | 요청과 응답 |
|---|---|
| `GET /api/notifications?page=0&size=20` | 최신순 목록. `200` 응답은 `{ items, page, size, totalElements }`. 동일 생성 시각에서는 ID 내림차순으로 정렬한다. `page`는 0부터 시작하며 `size`는 1~100이다. |
| `GET /api/notifications/unread-count` | `200` 응답은 `{ "count": 3 }`. |
| `PATCH /api/notifications/{id}/read` | 요청 본문 없이 읽음 처리하고 `204`를 반환한다. 반복 호출도 `204`이며 처음 읽은 시각을 유지한다. 존재하지 않거나 다른 사용자 소유이면 `404`를 반환한다. |
| `GET /api/notifications/stream` | `text/event-stream`으로 본인 알림의 변경을 전달한다. 변경 이벤트 이름은 `notifications.changed`, 데이터는 `{ "id": "notification-id" }`이다. |

목록의 각 항목은 `id`, `type`, `title`, `message`, `targetPath`, `resourceId`, `createdAt`, `readAt`을 반환한다. `readAt`이 `null`이면 미읽음이다. `targetPath`는 서버가 정한 사이트 내부 경로다. 알림 본문과 웹 푸시에는 반려 사유 같은 민감한 세부 정보를 담지 않는다.

화면은 SSE가 처음 연결되거나 재연결되면 HTTP API를 조회한다. 변경 이벤트를 받으면 목록과 미읽음 개수를 갱신한다. 웹 푸시는 사용자가 구독한 기기에 같은 알림을 전달하며, 푸시 재시도는 알림 저장과 독립적으로 처리한다.
