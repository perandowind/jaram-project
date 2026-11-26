# Flyway 도입

## 목적

운영 데이터를 유지하며 Hibernate 자동 스키마 변경을 버전 SQL로 전환한다.
HTTP 계약과 업무 로직은 유지한다.

## 작업

1. Spring Boot 관리 버전의 Flyway core/PostgreSQL 의존성을 추가한다.
2. 현재 엔티티의 PostgreSQL DDL을 V1으로 고정한다. 과거 수동 데이터 보정은 재실행하지 않는다.
3. 기본 및 테스트 Hibernate 설정을 validate로 전환한다. 자동 baseline/clean은 비활성화한다.
4. 신규 DB 초기화, 재기동 멱등성, 기존 DB 자동 편입 거부, 명시적 baseline의 회원 보존을 검증한다.
5. 기존 통합·계약 테스트 전체를 실행한다.
6. 기존 서버 baseline과 스키마/데이터 검증 절차를 migrations README에 남긴다.

## 경계

V1은 코드 매핑을 기반으로 하며 실제 운영 DB 일치는 별도 복원 검증이 필요하다.
운영 연결·baseline·배포는 이 로컬 작업에 포함하지 않는다.
기존 수동 스크립트의 실행 기록 공란 때문에 적용 상태를 추정하지 않는다.
develop 자동 배포 대상 DB도 머지 전에 baseline 검증 및 등록이 필요하다.

## 로컬 검증 결과 (2026-11-23)

- Java 21.0.10, Testcontainers PostgreSQL 16 사용.
- 집중 테스트 `./gradlew test --tests '*FlywayMigrationTest' --tests '*JaramBeApplicationTests' --no-daemon` 통과.
- 전체 `./gradlew test --no-daemon` 통과. 550개 테스트, 실패/오류/건너뜀 0개.
  이 중 HTTP 계약 테스트 42개 포함.
- OpenAPI 소스와 테스트 리소스의 심볼릭 링크를 확인했고 sync는 이미 일치하여 변경 없음.
- 테스트용 기존 DB는 V1과 동등한 스키마 및 합성 회원 데이터로 구성했다.
  실제 운영 백업 복원본의 호환성 검증은 별도 수행해야 한다.
