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
