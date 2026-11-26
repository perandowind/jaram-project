# 알림 개발 환경

백엔드 레포 루트에서 Docker Compose로 PostgreSQL과 RabbitMQ를 실행한다. 이 스택은 로컬 개발용 계정과 로컬호스트 포트를 사용한다. RabbitMQ는 [공식 관리 UI 포함 이미지](https://www.rabbitmq.com/docs/download)를 사용한다.

```sh
docker compose -f docker-compose.dev.yml up -d --wait
```

별도 터미널에서 기존 로컬 JWT 설정을 환경변수로 준비한 뒤 백엔드를 실행한다.

```sh
DB_URL="jdbc:postgresql://127.0.0.1:${JARAM_DEV_POSTGRES_PORT:-5434}/jaram" \
DB_USER=jaram DB_PASSWORD=jaram \
SPRING_RABBITMQ_HOST=127.0.0.1 SPRING_RABBITMQ_PORT="${JARAM_DEV_AMQP_PORT:-5672}" \
SPRING_RABBITMQ_USERNAME=jaram SPRING_RABBITMQ_PASSWORD=jaram-dev \
./gradlew bootRun
```

RabbitMQ 관리 UI는 기본 포트 15672에서 계정 `jaram`, 비밀번호 `jaram-dev`로 접속한다. Spring AMQP는 발행·소비 등 브로커 연결이 필요한 시점에 연결을 열므로, 앱의 기동과 실제 AMQP 연결 확인은 별도다.

```sh
docker compose -f docker-compose.dev.yml ps
docker compose -f docker-compose.dev.yml exec -u rabbitmq rabbitmq rabbitmq-diagnostics -q check_port_connectivity
docker compose -f docker-compose.dev.yml down
```

PostgreSQL과 RabbitMQ 데이터는 Compose 프로젝트의 볼륨에 유지된다.
