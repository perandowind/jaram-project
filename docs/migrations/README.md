# PostgreSQL / Flyway 마이그레이션

현재 스키마 변경은 `src/main/resources/db/migration/`의 Flyway 버전 SQL로 관리한다.
Hibernate는 `ddl-auto: validate`로 매핑을 검사한다. 테스트도 같은 SQL을 사용한다.

## 신규 DB

빈 DB에 애플리케이션을 시작하면 `V1__initial_schema.sql`부터 자동 적용된다.
V1은 도입 당시 엔티티의 전체 스키마다. 실제 회원 데이터는 포함하지 않는다.
관리 설정과 모집 설정은 기존 서비스의 초기화 동작을 따른다.

## 기존 DB 편입

기존 데이터가 있는 DB는 자동 baseline을 하지 않는다. 사전 검증 후 버전 1로
명시적으로 baseline해야 한다. baseline은 이력만 기록하며 기존 데이터를 보존한다.
V1과의 일치 여부나 과거 데이터 보정 완료 여부를 검사하지 않는다.

### 배포 전 검증

1. 운영 DB를 백업하고 별도 PostgreSQL 16 DB에 복원한다. 백업 복원 가능 여부도 확인한다.
2. 이 폴더의 과거 SQL/MD와 실제 스키마·데이터를 대조한다. 적용 이력 표의 공란은
   미적용 또는 적용 완료의 증거가 아니다. 과거 SQL을 일괄 재실행하지 않는다.
   특히 회원 임기 이관은 중복 임기를 만들 수 있고, 스터디 이관은 삭제된 구 컬럼을 참조한다.
3. V1으로 만든 신규 DB와 복원 DB의 스키마를 비교한다. `pg_dump --schema-only`와
   아래 사전 점검 쿼리를 사용할 수 있다. 컬럼 타입, nullable, 기본값, FK, UNIQUE,
   체크 제약, 인덱스를 확인한다. 구 컬럼·테이블의 잔존 여부도 기록한다.
   V1은 현재 매핑을 기준으로 작성했으며 실제 운영 스키마를 추출한 파일은 아니다.
   `study.status` 체크 제약은 신규 DB에는 생성되지만 수동 이관한 기존 DB에는 없을 수 있다.
   차이를 확인하고 필요한 보정은 검토한 별도 SQL로 처리한다.
4. 복원 DB에 아래 baseline을 실행하고 이 브랜치의 앱을 기동한다. Hibernate 검증과
   로그인, 회원 조회, 임기, 세미나/스터디 출석 동작 및 전후 데이터 보존을 확인한다.
   복원 DB의 외부 연동은 격리한다. 앱에는 학기 전환·개인정보 파기 등 예약 작업이 있으므로
   baseline 자체의 데이터 보존 검증과 앱 기동 후 업무 동작 검증을 구분해 확인한다.
5. 검증 결과를 바탕으로 실제 대상 DB를 확인하고 백업한 뒤 운영 baseline을 한 번 실행한다.
   현재 CI는 develop push 시 개발 서버를 자동 배포하므로 해당 서버도 머지 전에 편입한다.

### baseline 명령

앱이 사용하는 Flyway와 호환되는 CLI를 사용한다. 의존 버전은 다음으로 확인한다.

```bash
./gradlew dependencyInsight --dependency flyway-core --configuration runtimeClasspath
```

`FLYWAY_URL`(JDBC URL), `FLYWAY_USER`, `FLYWAY_PASSWORD`는 대상 DB 환경에 맞춰
안전하게 주입한다. 비밀번호를 저장소나 명령 이력에 직접 적지 않는다.
아래는 기본 스키마가 public인 경우다. 다른 스키마를 쓰면 앱 설정과 CLI를 일치시킨다.

```bash
flyway -schemas=public -baselineVersion=1 baseline
```

이 명령을 실행하는 시점에 DB는 V1과 호환되어야 한다. V1 이전의 변경이 빠진 DB를
baseline으로 정상화할 수는 없다. 이후 앱을 시작하면 V1을 건너뛰고 V2부터 적용한다.
운영 DB에 `clean`을 실행하지 않는다. 기본 설정에서도 clean을 비활성화했다.

### 사전 점검 쿼리

결과는 내부에서 검토하고 개인정보가 들어 있는 덤프를 커밋하지 않는다.

```sql
SELECT table_name, column_name, data_type, character_maximum_length,
       is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public'
ORDER BY table_name, ordinal_position;

SELECT conrelid::regclass AS table_name, conname, pg_get_constraintdef(oid)
FROM pg_constraint
WHERE connamespace = 'public'::regnamespace
ORDER BY conrelid::regclass::text, conname;

SELECT tablename, indexname, indexdef
FROM pg_indexes WHERE schemaname = 'public'
ORDER BY tablename, indexname;

SELECT count(*) AS member_count FROM member;
SELECT approval, grade, status, count(*) FROM member GROUP BY approval, grade, status;
SELECT count(*) AS pending_grade_backfill FROM member WHERE grade IS NULL;
SELECT count(*) AS contributor_backfill FROM member
WHERE contributor = false AND id IN (SELECT member_id FROM member_term);
SELECT member_id, count(*) FROM member_term WHERE end_gen IS NULL
GROUP BY member_id HAVING count(*) > 1;
SELECT count(*) AS study_status_backfill FROM study WHERE status IS NULL;
```

기준 등록 후에는 `flyway_schema_history`의 version/type/success를 확인한다.
Hibernate validate는 체크 제약·인덱스·업무 데이터 보정까지 모두 검증하지 않는다.
Flyway validate는 이력과 SQL 체크섬을 검증하며 스키마 실물을 V1과 비교하지 않는다.

## 이후 변경 규칙

- `V2__description.sql`, `V3__description.sql`처럼 버전을 증가시킨다.
- 적용한 버전 SQL은 수정하지 않고 새 파일로 보정한다.
- 필수 컬럼·타입·제약 변경 시 기존 데이터와 이전 앱 버전 호환성을 검토한다.
- 빈 DB 생성과 기존 버전 DB의 업그레이드를 테스트한다.
- 실패 복구는 변경 종류에 따라 트랜잭션 롤백, 보정 SQL 또는 백업 복원으로 계획한다.
  앱 코드만 이전 버전으로 되돌려도 DB 변경은 남는다.

## 과거 수동 마이그레이션 기록

아래 파일은 Flyway 도입 전 이력이며 자동 실행 경로에 포함하지 않는다.

| 파일 | 적용일 | 적용한 서버 | 실행 |
|---|---|---|---|
| 2026-07-20-member-refactor.sql | | | |
| 2026-08-02-contributor-backfill.sql | | | |
| 2026-08-03-pending-grade-backfill.sql | | | |
| 2026-09-08-reregister-status-constraint.sql | | | |
| 2026-09-15-study-lifecycle.sql | | | |

공식 문서

- https://documentation.red-gate.com/fd/baseline-277578867.html
- https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting
