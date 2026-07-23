# kira-backend

A standalone **Spring Boot / Kotlin / PostgreSQL** service that is the remote authority for the
Kira Manga app's **source configuration** — the validated, versioned `SourceConfigDocument` the app
currently bundles as a string constant. Configs are authored and validated server-side with the
app's mirrored rules plus server-side safety rules, published as immutable per-source revisions,
assembled into whole-document snapshots and lightweight signed v2 manifests, and served with strong
ETag/checksum support. Around that core sit three small foundations: JWT auth with `ADMIN`/`USER`
roles, admin source/user management with a full audit trail, Ed25519-signed document delivery, and an
optional authenticated completion service behind a production HTTPS provider abstraction. Echo is
restricted to explicit development/test profiles.

The service also owns bilingual website tutorials, immutable revisions, categories,
ordering/featured state, and sanitized media; see [`docs/TUTORIALS.md`](docs/TUTORIALS.md).

The design goal throughout: the app must eventually be able to fetch a document that is
**contract-equivalent, deterministically canonical, and byte-stable** with respect to what it
bundles today. This means stable backend canonical bytes, not byte identity with the app's
hand-authored bundled JSON.

> **The full specification is [`docs/PLAN.md`](docs/PLAN.md).** This project was built in the phased
> order of PLAN.md §15; the code is the source of truth for everything documented here.
> Before production use, read the fail-closed deployment prerequisites in
> [`docs/USAGE.md`](docs/USAGE.md#9-production-checklist).

Current release line: **1.0.0**. Releases are built from immutable tags and published with both the
semantic version and full Git SHA; see [`docs/RELEASE.md`](docs/RELEASE.md).

## Architecture

Clean, layered architecture with one dependency direction, per feature package:

```
api            controllers, request/response DTOs, DTO↔domain mappers
  ↓
application    transactional services, orchestration, use-case methods
  ↓
domain         pure Kotlin: models, value objects, repository PORTS (interfaces), domain rules
  ↑ implemented by
infrastructure JPA entities, Spring Data repos, port adapters, entity↔domain mappers
```

- `api` never touches `infrastructure` or JPA types; `application` depends only on domain ports.
- Three model families per feature — API DTOs (`api/dto`), domain models (`domain`), and JPA entities
  (`infrastructure`) — mapped explicitly (no MapStruct).
- **`sourceconfig/domain` + `sourceconfig/validation` are framework-free** (kotlin-stdlib +
  kotlinx-serialization only) so they can later be extracted into a shared module consumed by both
  backend and app.
- **Serialization split:** Spring MVC API DTOs use Jackson (the Boot default); the **source-config
  model uses kotlinx-serialization** so default-omission behaves identically to the app. Document
  endpoints return the stored pre-serialized canonical bytes verbatim (a raw-bytes writer, never a
  message converter) so the served bytes are byte-identical to the bytes checksummed and stored.
- Domain/application throw typed exceptions; a single `@RestControllerAdvice` maps them to a uniform
  RFC-9457 `application/problem+json` envelope.

## Tech stack (pinned versions)

Verified mutually compatible once at scaffold and now **reproducible** — builds resolve exactly these
versions. After scaffold they change only via deliberate commits, never floating ranges.
**Spring Boot stays on the 3.5.x line — do NOT auto-switch to 4.x** (a major upgrade is a separate,
fully-tested change).

| Component | Version | Pinned in |
|---|---|---|
| Gradle wrapper | **8.14.5** | `gradle/wrapper/gradle-wrapper.properties` |
| Java toolchain | **21** (built/tested on Homebrew OpenJDK 21.0.11) | `build.gradle.kts` |
| Kotlin (jvm/spring/jpa/serialization plugins + stdlib) | **2.1.21** | `gradle/libs.versions.toml` (+ `extra["kotlin.version"]` BOM override in `build.gradle.kts`) |
| Spring Boot | **3.5.16** | `gradle/libs.versions.toml` |
| io.spring.dependency-management | **1.1.7** | `gradle/libs.versions.toml` |
| kotlinx-serialization-json | **1.8.1** | `gradle/libs.versions.toml` |
| springdoc-openapi-starter-webmvc-ui | **2.8.17** | `gradle/libs.versions.toml` |
| Jackson BOM / Commons Lang / Commons Compress security overrides | **2.21.5 / 3.20.0 / 1.28.0** | `gradle/libs.versions.toml` + dependency locks |
| PostgreSQL Docker image | **`postgres:17.6-alpine`** (digest `sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94`) | `docker-compose.yml`, test base class |

**BOM-managed** (versions supplied by the Spring Boot 3.5.16 dependency BOM — recorded for
provenance, not separately pinned):

| Component | Resolved version |
|---|---|
| Testcontainers (postgresql, junit-jupiter, core) | **1.21.4** |
| PostgreSQL JDBC driver | **42.7.11** |
| Flyway (flyway-core, flyway-database-postgresql) | **11.7.2** |
| spring-security-oauth2-jose (Nimbus, via oauth2-resource-server) | **6.5.11** |

> Note: `./gradlew --version` reports "Kotlin: 2.0.21" — that is the Kotlin runtime *embedded in
> Gradle 8.14.5* for `.gradle.kts` script compilation, **not** the project's Kotlin. Project sources
> compile with the pinned **2.1.21** plugin.

## Quick start

```bash
# 0. macOS toolchain + Docker (Colima users: see docs/LOCAL_DEV.md for the socket exports).
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# 1. Start the local PostgreSQL (host port 5433; throwaway local-only credentials kira/kira).
docker compose up -d

# 2. Provide local secrets. Uncomment/edit the two KIRA_ADMIN_* lines, then export the file.
cp .env.example .env
set -a; source .env; set +a

# 3. Run against it with the `dev` profile (reads compose coordinates from application-dev.yml).
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun

# 4. The full green gate: compile + all unit + Testcontainers integration tests (Docker required).
./gradlew clean build
```

Spring Boot does **not** load `.env` automatically in this project. Run the `source` command in every
new shell before `bootRun`, or export `KIRA_ADMIN_EMAIL` and `KIRA_ADMIN_PASSWORD` directly. Keep
shell-special password values single-quoted inside `.env`.

`ddl-auto=validate` — **Flyway owns the schema** (`src/main/resources/db/migration/V1..V10`); Hibernate
only validates against it. Swagger UI (dev profile only) is at `/swagger-ui/index.html`; the OpenAPI
document is at `/v3/api-docs`.

See **[`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md)** for the full local workflow, seeding data, and gotchas.

## Documentation map

| Doc | Contents |
|---|---|
| [`docs/USAGE.md`](docs/USAGE.md) | Start-to-finish curl walkthrough: login, import, public reads, users, completions, source revisions, lifecycle, and current cautions. |
| [`docs/PLAN.md`](docs/PLAN.md) | The authoritative specification (§1–§16 + appendices A–C). |
| [`docs/API.md`](docs/API.md) | Every endpoint: method, auth level, request/response shapes, status codes, ETag/pagination/body-size rules. |
| [`docs/SOURCE_CONFIG_LIFECYCLE.md`](docs/SOURCE_CONFIG_LIFECYCLE.md) | The 6 server states, v1/v2 mappings, publish rules, the 10-step publication sequence + locks, revision numbering, startup consistency + recovery runbook. |
| [`docs/SECURITY.md`](docs/SECURITY.md) | JWT scheme, DB-backed per-request checks, password policy, throttling + trusted client-IP, secrets policy, and the §6 logging + retention + privacy expectations. |
| [`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md) | Prerequisites, docker-compose, `.env`, running the app + tests, Swagger, seeding, common gotchas. |
| [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) | Production Kubernetes topology, rollout, drain, rollback, and forward-recovery procedure. |
| [`docs/RELEASE.md`](docs/RELEASE.md) | Reproducible build, immutable image, semantic tag, SBOM, provenance, and publishing procedure. |
| [`docs/LOAD_TEST.md`](docs/LOAD_TEST.md) | Non-mutating k6 staging load profile, thresholds, metrics, and release evidence. |
| [`docs/SOURCE_DOCUMENT_SIGNING.md`](docs/SOURCE_DOCUMENT_SIGNING.md) | Signed-byte contract, key generation, protected installation, rotation, and incident procedure. |
| [`docs/MIGRATION_BUNDLED_TO_REMOTE.md`](docs/MIGRATION_BUNDLED_TO_REMOTE.md) | The bundled→remote migration contract, revision floors, signed app acceptance chain, and cutover checklist. |

## Project layout

```
build.gradle.kts / settings.gradle.kts / gradle/libs.versions.toml   # single Gradle module, versions pinned
docker-compose.yml                                                   # local postgres:17.6-alpine on :5433, named volume
.env.example                                                         # env template (secrets are BYO, .env gitignored)
docs/                                                                # specification, API, operations, security, and usage documents
src/main/kotlin/me/manga/kira/backend/
  KiraBackendApplication.kt
  config/          # @ConfigurationProperties (Kira{Security,Completion,Auth,Config,AdminSeed,Validation}Properties),
                   # OpenAPI, Clock, web-diagnostics filter registration
  common/          # problem envelope (ApiError/ApiFieldError), GlobalExceptionHandler, typed exceptions,
                   # PageResponse, Sha256, CanonicalJson (kcj-1), RequestDiagnosticsFilter
  security/        # SecurityConfig (filter chain), JwtService, DB-backed jwtAuthenticationConverter,
                   # CurrentUser, AuthThrottleService, ClientIpResolver, AdminSeeder, problem 401/403 handlers
  user/            # api (AuthController, AdminUsersController) / domain / application / infrastructure
  sourceconfig/    # api (public: SourceDocument/Sources; admin: AdminSources/AdminDocuments) / domain (+ model, validation)
                   # / application (SourceAdminService, DocumentAssemblyService, BundledImportService, SourceQueryService)
                   # / infrastructure (entities, repos, adapters, startup validators)
  completion/      # API/domain/application + bounded admission and dev/test echo / production HTTPS providers
  audit/           # domain / application (AuditService) / infrastructure
src/main/resources/
  application.yml, application-dev.yml, application-prod.yml
  db/migration/    # forward-only V1 through V10 (including incremental source-catalog v2)
src/test/kotlin/me/manga/kira/backend/
  ...mirrors main; support/ (Testcontainers base, JWT helpers, MutableClock); resources/fixtures/
```

## Test suite

**283 tests across 76 suites** (0 failures / 0 errors / 0 skipped on the current full Testcontainers gate).
Pure-unit tests (validator, canonical JSON, JWT, password hashing, state machine, echo provider,
contract inventory) run without a Spring context. Integration tests use **Testcontainers PostgreSQL**
(`postgres:17.6-alpine`, one shared container via `@ServiceConnection`) rather than H2, because the
schema leans on Postgres-specific behavior (jsonb, partial unique indexes, identity columns,
timestamptz). Named ITs prove the load-bearing invariants: concurrent publication/revision locking,
lifecycle-neutral storage, ETag/If-None-Match semantics, raw-bytes checksum stability, the full real
45-source bundled document round-trip, the security matrix, throttling, and startup consistency.

```bash
./gradlew clean build                                                  # full gate (all tests)
./gradlew test --tests "me.manga.kira.backend.user.SecurityMatrixIT"   # a single IT class
```
