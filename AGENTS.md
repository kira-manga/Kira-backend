# kira-backend — agent instructions

This is a standalone, single-module Spring Boot service. It is the server-side authority for the
Kira app’s source-config lifecycle and also contains JWT authentication, admin/user management,
audit logging, and an authenticated completion foundation with an `echo` provider. The code is the
current truth; [`docs/PLAN.md`](docs/PLAN.md) is the normative product specification and can be
ahead of or behind an implementation detail.

## Read first

| Document | Use |
|---|---|
| [`README.md`](README.md) | Quick start, layout, stack, and test overview |
| [`docs/PLAN.md`](docs/PLAN.md) | Authoritative contract and phase design |
| [`docs/API.md`](docs/API.md) | Endpoint contract and response shapes |
| [`docs/SOURCE_CONFIG_LIFECYCLE.md`](docs/SOURCE_CONFIG_LIFECYCLE.md) | Publication, locking, lifecycle, revision, and recovery rules |
| [`docs/SECURITY.md`](docs/SECURITY.md) | JWT, password, throttling, secrets, privacy, and logging rules |
| [`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md) | Docker, `.env`, seeding, and local test workflow |
| [`docs/MIGRATION_BUNDLED_TO_REMOTE.md`](docs/MIGRATION_BUNDLED_TO_REMOTE.md) | App cutover and revision-floor contract |

## Verified stack and build

The version catalog and build script pin Kotlin **2.1.21**, Spring Boot **3.5.16**, Gradle
**8.14.5**, Java toolchain **21**, kotlinx-serialization-json **1.8.1**, and springdoc **2.8.17**.
PostgreSQL is `postgres:17.6-alpine` in Docker Compose and integration tests. Stay on the Spring
Boot 3.5.x line; a Boot 4 upgrade is a separate migration.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
docker compose up -d
cp .env.example .env       # set KIRA_ADMIN_EMAIL / KIRA_ADMIN_PASSWORD locally
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
./gradlew clean build       # Docker is required: ITs use Testcontainers PostgreSQL
```

Run commands from this directory and use its `./gradlew`. `.env` and credentials are local/BYO.
Hibernate is `ddl-auto=validate`; Flyway owns the schema in `src/main/resources/db/migration/`
(`V1__users.sql` through `V5__completions.sql`). A schema change means a new migration, not
entity-driven DDL.

## Architecture

Feature packages are layered one way:

```text
api → application → domain ← infrastructure
```

- `api` contains controllers and Jackson request/response DTOs. It must not expose JPA entities or
  reach infrastructure directly.
- `application` contains transactional orchestration and depends on domain ports only.
- `domain` contains models, rules, exceptions, and repository/provider interfaces.
- `infrastructure` contains JPA entities, Spring Data repositories, and port adapters. Entities
  live directly in the feature’s `infrastructure/` package; there is no `infrastructure/entity/`
  package in the current tree. Mapping is explicit; MapStruct is not used.
- Feature roots are `security`, `user`, `sourceconfig`, `completion`, and `audit`, with shared
  support in `common` and `config`. `sourceconfig` additionally has `validation`, `parsing`, and
  `domain/model`.
- `sourceconfig/domain` and `sourceconfig/validation` are framework-free (Kotlin plus
  kotlinx-serialization/model types). Keep Spring, JPA, Jackson, and configuration-property
  concerns out of them. `sourceconfig/parsing` is intentionally separate because strict authoring
  uses Jackson structural checks while compatibility import mirrors the app’s lenient parser.

## Source-config rules that affect changes

- The mirrored `SourceConfigDocument`/`SourceConfig` model is in
  [`sourceconfig/domain/model/SourceConfig.kt`](src/main/kotlin/me/manga/kira/backend/sourceconfig/domain/model/SourceConfig.kt).
  The backend validator mirrors the app validator and adds server-side safety rules. A validation
  failure rejects the candidate document; warnings do not block publish.
- Strict admin authoring rejects unknown keys, duplicate keys, trailing JSON, and malformed input.
  `import-bundled` is deliberately compatibility/lenient and reads the app’s bundled document.
- Outbound snapshots use `CanonicalJson`: `kcj-1`, defaults omitted, nulls omitted, compact UTF-8,
  recursively Unicode-code-point-sorted object keys, and arrays left in order. SHA-256 is computed
  over those exact bytes. Public document responses write the stored bytes directly and use a strong
  ETag/`If-None-Match` contract; do not route them through Jackson.
- The server stores source revision content lifecycle-neutral, injects lifecycle during assembly,
  orders sources by `position ASC, api ASC`, and resolves “latest” through the
  `document_publication_state` pointer. Publication/import mutations take the global publication
  lock before source-row locks; per-source revision allocation is locked.
- Server lifecycle states are `DRAFT`, `ACTIVE`, `DISABLED`, `RETIRED`, and `REMOVED`. The app-facing
  document vocabulary is narrower; consult the lifecycle doc before changing transitions.

## API/security boundaries

- Public read-only routes: `/api/v1/source-config/**` and `/api/v1/sources/**`, including document
  metadata, raw document, and per-source reads.
- Auth routes: register/login are profile-gated for registration; `/auth/me` requires a bearer token.
  Admin source/document/user routes are under `/api/v1/admin/**` and require `ADMIN`.
- Completions are under `/api/v1/completions/**`, authenticated for `USER` or `ADMIN`; v1 uses the
  provider port and fake `echo` implementation, not an AI SDK.
- Security is stateless HS256 JWT with issuer/audience/time validation. On every bearer request,
  the converter loads the user from the database, rejects missing/disabled users, and derives the
  role from the database rather than trusting the token’s diagnostic role claim. Passwords use
  Spring’s delegating encoder (bcrypt initially). Auth throttling is in-memory and client-IP
  forwarding is trusted only when explicitly configured.
- Errors should cross the boundary as RFC-9457-shaped `application/problem+json`. Never echo raw
  request bodies, secrets, passwords, tokens, provider exceptions, or stack traces.

## Known review status

[`docs/REVIEW_2026-07-12.md`](docs/REVIEW_2026-07-12.md) is a report-only adversarial review; it
does not apply fixes. Before touching validation, completion, error handling, import concurrency,
or request limits, check its open findings. At review time the highest-risk items were whitespace-
padded sensitive header names bypassing the header-name rule and an unbounded completion `model`
field that can become a database 500. Other findings include incomplete generic 4xx mappings and
the documented default request-body cap not being enforced in code. Do not call the backend
“hardened” merely because the test suite is green; distinguish implemented invariants from open
review findings.

The local `.agent/` tooling and review notes are workflow artifacts, not product code. Ignore them
for feature design unless the user explicitly asks to update the review workflow.
