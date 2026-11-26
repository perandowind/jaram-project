# JARAM Backend — Codex repository instructions

## Mission and scope

- Maintain `home-jaram-be`, a Java 21 / Spring Boot 3 / Gradle / PostgreSQL backend.
- Make the smallest change that satisfies the request. Follow existing project conventions and preserve existing behavior unless the task explicitly changes it.
- Inspect the relevant current source code and tests before changing functionality. Never treat old design documents as proof that the current implementation still follows them.

## Source of truth and documentation

- For HTTP APIs, inspect the frontend-owned OpenAPI contract at `docs/api/openapi.yaml` (a symlink into the sibling `home-jaram-fe` repository), plus existing contract tests. Do not invent or silently change contract fields, paths, status codes, or enum values.
- For current authorization and architecture, read the relevant implementation, especially `security/SecurityConfig.java`, `security/authz/{Role,Permission,Policy}.java`, handler-level `@PreAuthorize`, and authorization tests. Old `MEMBER`/`OFFICER` documentation may be obsolete.
- Consult the relevant `docs/superpowers/specs/` or `docs/superpowers/plans/` files for design rationale. If they conflict with current code or OpenAPI, report the conflict and resolve the intended behavior before modifying it.
- Both `docs/api/openapi.yaml` and `src/test/resources/openapi/openapi.yaml` may be symlinks. Inspect targets (`ls -l`/`readlink`) before invoking `scripts/sync-openapi.sh`. Do not overwrite or mutate the frontend-owned contract as a side effect of syncing.

## Coding conventions

- Follow the existing package-by-feature structure under `com.jaram.be`: domain packages contain controllers, services, repositories, entities, and a `dto/` subpackage.
- Keep controllers thin; implement business rules and transactional boundaries in services; put persistence queries in repositories. Match the closest existing feature's patterns before introducing new layers.
- Use constructor injection. Prefer existing DTO conventions (including `record` where used), Bean Validation, and the shared exception/error response mechanism.
- Keep authorization fail-closed. Check both route-level authentication and method-level permissions; extend relevant authorization tests whenever access rules change.
- For database changes, assess existing rows, migration/rollback requirements, and compatibility. Do not assume Hibernate `ddl-auto: update` is a production migration strategy; use `docs/migrations/` where the rollout requires an explicit migration.
- Never include secrets, personal information, or production credentials in code, test fixtures, commits, or logs.

## Java Type Declaration Rules

- Prefer explicit types by default.
- Use `var` only when the inferred type is immediately obvious from the initializer and it meaningfully removes redundant type information.
- Prefer explicit types for business logic, repository results, entities, DTOs, value objects, and monetary calculations.
- Preserve interface-based declarations when abstraction is intentional.
- Avoid `var` when generic type inference may produce unintended types.

## Readability and Method Design

- Prioritize readability, maintainability, and correctness over brevity.
- Use descriptive variable, method, and class names that clearly express their purpose.
- Replace meaningful magic numbers with named constants.
- Prefer straightforward expressions over unnecessarily clever implementations.
- Keep methods focused on a single cohesive responsibility.
- Make high-level methods communicate the execution flow clearly.
- Extract complex implementation details when doing so improves readability.
- Avoid unnecessary method extraction, abstraction, and design patterns.
- Prefer early returns over deeply nested conditionals.
- Consider enums instead of boolean return values when representing distinct outcomes.

## Lombok Conventions

- Use `@RequiredArgsConstructor` for constructor-based dependency injection.
- Use `@Slf4j` for logging.
- Use `@Getter` for necessary accessors.
- Use `@NoArgsConstructor(access = AccessLevel.PROTECTED)` for JPA entities.
- Never use `@Data` on JPA entities.
- Avoid class-level `@Setter` on JPA entities.
- Use `@Builder` and `@AllArgsConstructor` only when justified.
- Prefer explicit exception handling over `@SneakyThrows`.
- Do not add unnecessary Lombok annotations to Java records.
- Configure Lombok using `compileOnly` and `annotationProcessor`.

## Entity and DTO Conventions

- Declare entity fields as private.
- Prefer static factory methods or explicit constructors that enforce domain invariants.
- Prefer domain-specific methods over setters for entity state changes.
- Expose only necessary accessors and methods.
- Prefer immutable Java records for DTOs and events where appropriate.
- Use `Instant` for timestamps representing actual points in time.
- Use `LocalDate` for date-only values.
- Preserve existing JSON serialization contracts.

## Repository Conventions

- Prefer Spring Data JPA repository interfaces extending `JpaRepository` for standard entity persistence.
- Do not add redundant `@Repository` annotations to Spring Data JPA repository interfaces.
- Do not manually reimplement standard CRUD operations.
- Prefer derived query methods for simple queries.
- Prefer JPQL with `@Query` for explicit queries.
- Use native SQL only when database-specific functionality or other concrete requirements justify it.
- Consider custom repository fragments for specialized persistence operations.
- Use `EntityManager`, Hibernate Session, or `JdbcTemplate` directly only when justified by specific technical requirements.
- Document the rationale for deviating from standard Spring Data JPA patterns.
- Keep repositories focused on persistence rather than business logic.
- Consider N+1 queries, unnecessary database operations, and concurrency risks.
- Enforce critical data invariants with database constraints.

## Transaction and Reliability

- Preserve transaction boundaries, locking behavior, and concurrency guarantees during refactoring.
- Use read-only transactions where appropriate.
- Avoid unnecessary external network calls within database transactions.
- Ensure message consumers are idempotent.
- Do not silently swallow operational exceptions.
- Include sufficient context in exception messages and logs.
- Separate readability refactoring from functional behavior changes.

## Communication

- Communicate with the user in Korean unless otherwise requested.
- Preserve English technical terms, code identifiers, file paths, and commands.
- Report implementation decisions, trade-offs, verification results, and known limitations clearly.

## Task workflow

- When available and relevant, use `$develop-backend` for backend changes, `$verify-backend` for testing/review/verification, and `$run-backend` for local startup/debugging.
- These skills are optional workflow aids, not prerequisites. If a skill is missing, unavailable, or incompatible, do not stop or request its contents solely for that reason. Continue using the applicable `AGENTS.md` instructions, current source code and tests, project documentation, and the verification procedures below.
- Existing repository-local skills from other coding agents (such as Claude Code) may be consulted when relevant. Check their compatibility before using any agent-specific tools, commands, or scripts; do not treat them as mandatory or let them override applicable project instructions.
- Do not create, install, copy, or modify skills unless explicitly requested.
- If a genuine blocker prevents part of the task, complete the remaining safe and independent work and report the blocker. Never bypass authorization, safety requirements, or required verification to work around an unavailable skill.
- Small fixes need no new planning document. For multi-step changes, risky data/authorization changes, or meaningful refactors, review applicable design documents and write/update a focused plan under `docs/superpowers/plans/` when it improves coordination.
- Prefer a focused regression test before fixing a reproducible bug, then implement and verify. For larger behavior changes, use test-first development where practical.
- Never perform unrelated refactors. Do not auto-commit, push, merge, or open PRs unless requested.

## Verification and handoff

- The verification requirements below apply whether or not a dedicated verification skill is available.
- For meaningful refactors or reproducible bugs, establish a baseline with relevant existing tests before changing code when practical.
- For Java code changes, check compilation with `./gradlew compileJava` and `./gradlew compileTestJava` as applicable.
- Focused tests: `./gradlew test --tests 'com.jaram.be.<package>.<TestClass>'`. Select actual test classes relevant to the change, including regression and integration tests where applicable.
- Contract suite: `./gradlew test --tests '*ContractTest'` (requires a resolvable OpenAPI target and Docker/Testcontainers). Run it when HTTP API contracts or related behavior may be affected.
- Full suite when appropriate: `./gradlew test` (requires Docker/Testcontainers).
- For code changes, review the diff and run `git diff --check`. Do not weaken existing tests or change unrelated code merely to make verification pass.
- If Docker, Testcontainers, missing configuration, or another environment issue prevents a check, run the remaining feasible checks and report exactly what could not be verified and why. Do not claim skipped or unexecuted tests passed.
- Report changed files, API or database compatibility effects, commands actually executed, results, unavailable relevant skills and the fallback workflow used, and any unverified assumptions or remaining risks.
