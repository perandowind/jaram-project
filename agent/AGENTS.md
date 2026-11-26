# JARAM Backend — Codex repository instructions

## Mission and scope
- Maintain `home-jaram-be`, a Java 21 / Spring Boot 3 / Gradle / PostgreSQL backend.
- Make the smallest change that satisfies the request. Preserve existing behavior unless the task explicitly changes it.
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

## Task workflow
- For backend code changes use `$develop-backend`; for test/review/verification work use `$verify-backend`; for local startup/debugging use `$run-backend`.
- Small fixes need no new planning document. For multi-step changes, risky data/authorization changes, or meaningful refactors, review applicable design documents and write/update a focused plan under `docs/superpowers/plans/` when it improves coordination.
- Prefer a focused regression test before fixing a reproducible bug, then implement and verify. For larger behavior changes, use test-first development where practical.
- Never perform unrelated refactors. Do not auto-commit, push, merge, or open PRs unless requested.

## Verification and handoff
- Focused tests: `./gradlew test --tests 'com.jaram.be.<package>.<TestClass>'`.
- Contract suite: `./gradlew test --tests '*ContractTest'` (requires a resolvable OpenAPI target and Docker/Testcontainers).
- Full suite when appropriate: `./gradlew test` (requires Docker/Testcontainers).
- Report changed files, API or database compatibility effects, commands actually executed, results, and any unverified assumptions. Never state tests passed without running them.
