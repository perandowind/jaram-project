# 알림 개발 환경

백엔드 레포 루트에서 Docker Compose로 PostgreSQL과 RabbitMQ를 실행한다. 이 스택은 로컬 개발용 계정과 로컬호스트 포트를 사용한다. RabbitMQ는 [공식 관리 UI 포함 이미지](https://www.rabbitmq.com/docs/download)를 사용한다.

```sh
docker compose -f docker-compose.dev.yml up -d --wait
```

별도 터미널에서 기존 로컬 JWT 설정을 환경변수로 준비한 뒤 백엔드를 실행한다.

```sh
DB_URL="jdbc:postgresql://127.0.0.1:${JARAM_DEV_POSTGRES_PORT:-5434}/jaram" \
DB_USER=jaram DB_PASSWORD=jaram \
JARAM_NOTIFICATIONS_ENABLED=true \
SPRING_RABBITMQ_HOST=127.0.0.1 SPRING_RABBITMQ_PORT="${JARAM_DEV_AMQP_PORT:-5672}" \
SPRING_RABBITMQ_USERNAME=jaram SPRING_RABBITMQ_PASSWORD=jaram-dev \
./gradlew bootRun
```

RabbitMQ 관리 UI는 기본 포트 15672에서 계정 `jaram`, 비밀번호 `jaram-dev`로 접속한다. `JARAM_NOTIFICATIONS_ENABLED=true`로 Outbox 발행 작업과 알림 소비자를 활성화한다.

```sh
docker compose -f docker-compose.dev.yml ps
docker compose -f docker-compose.dev.yml exec -u rabbitmq rabbitmq rabbitmq-diagnostics -q check_port_connectivity
docker compose -f docker-compose.dev.yml down
```

PostgreSQL과 RabbitMQ 데이터는 Compose 프로젝트의 볼륨에 유지된다.

업무 서비스는 자신의 트랜잭션 안에서 `NotificationOutbox.enqueue`를 호출한다. 브로커 수락과 라우팅 성공이 확인되면 발행 완료로 표시하며, 확인에 실패하면 Outbox에서 최대 60초 간격으로 재시도한다. 소비자는 DB 커밋 뒤 메시지를 확인 처리한다. 일시적인 DB 오류는 총 3번까지 시도하며, 최종 실패나 유효하지 않은 메시지는 `jaram.notifications.dlq`로 이동한다.

## 웹 푸시 설정과 프론트엔드 연동

백엔드 실행 환경에 아래 값을 주입한다. Compose를 사용하면 백엔드 서비스의
`environment`에 전달하고 컨테이너를 다시 생성한다.

| 환경 변수 | 값 |
|---|---|
| `JARAM_NOTIFICATIONS_ENABLED` | `true`. RabbitMQ 알림 발행·소비를 활성화한다. |
| `JARAM_NOTIFICATIONS_PUSH_VAPID_PUBLIC_KEY` | P-256 공개 키. 패딩 없는 Base64URL 형식의 65바이트 비압축 점. |
| `JARAM_NOTIFICATIONS_PUSH_VAPID_PRIVATE_KEY` | 같은 키 쌍의 32바이트 개인 키. 패딩 없는 Base64URL 형식으로 백엔드에만 주입한다. |
| `JARAM_NOTIFICATIONS_PUSH_VAPID_SUBJECT` | 푸시 서비스에 제공할 `mailto:` 운영 연락처 또는 HTTPS URL. |

VAPID 설정을 전부 비워 두면 푸시 발송은 비활성화되고 공개 키 API는 `503`을 반환한다.
설정 일부가 비었거나 키 쌍이 일치하지 않으면 시작 시 오류가 발생한다.
구독을 유지하려면 재시작 후에도 같은 키 쌍을 사용한다.

프론트엔드는 HTTPS 또는 localhost에서 서비스 워커를 등록한다. 로그인한 사용자의
Bearer JWT로 공개 키를 조회하고, Base64URL 디코딩 결과를 `PushManager.subscribe`의
`applicationServerKey`에 전달한다. 반환된 `PushSubscription.toJSON()`을 구독 API에
보내고, 응답의 `id`를 해제할 때 사용한다. 서비스 워커는 푸시 데이터에서
`title`, `body`, `targetPath`를 읽는다. `targetPath`는 기존 알림함과 같은 사이트 내부 경로다.

푸시 전송은 알림 커밋 후 메모리 큐에서 실행한다. 실패와 큐 포화는 운영 로그에 기록하며,
발송을 재시도하지 않는다. 알림함과 SSE는 푸시 전송 결과와 독립적으로 동작한다.
