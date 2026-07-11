# kira-backend

A standalone **Spring Boot / Kotlin / PostgreSQL** service that will become the remote
authority for the Kira Manga app's **source configuration** (the validated, versioned
`SourceConfigDocument` the app currently bundles), plus small JWT-auth, admin, and
completion foundations.

**The sole specification is [`docs/PLAN.md`](docs/PLAN.md).** This project is built in the
phased order of PLAN.md §15. This README is a **Phase-1 stub**; the full README and the
`docs/API.md`, `docs/SOURCE_CONFIG_LIFECYCLE.md`, `docs/SECURITY.md`, `docs/LOCAL_DEV.md`,
and `docs/MIGRATION_BUNDLED_TO_REMOTE.md` documents are written in Phase 10 (§15.10).

> **Standalone.** No dependency on, reference to, or modification of the mobile app's Gradle
> modules. The mobile repo is inspected read-only for config-shape parity only.

## Pinned versions (PLAN.md §3 version-pinning rule)

These were verified mutually compatible once at scaffold (Phase 1) — the build compiles and
`ContextLoadsIT` passes against a real PostgreSQL container. They are now **reproducible**:
builds resolve exactly these versions. After Phase 1 they change only via deliberate commits,
never by floating ranges. **Spring Boot stays on the 3.5.x line — do NOT auto-switch to 4.x**
(a major upgrade is a separate, fully-tested change).

| Component | Version | Pinned in |
|---|---|---|
| Gradle wrapper | **8.14.5** | `gradle/wrapper/gradle-wrapper.properties` |
| Java toolchain | **21** (built/tested on Homebrew OpenJDK 21.0.11) | `build.gradle.kts` |
| Kotlin (jvm/spring/jpa/serialization plugins + stdlib) | **2.1.21** | `gradle/libs.versions.toml` (+ `extra["kotlin.version"]` BOM override in `build.gradle.kts`) |
| Spring Boot | **3.5.16** | `gradle/libs.versions.toml` |
| io.spring.dependency-management | **1.1.7** | `gradle/libs.versions.toml` |
| kotlinx-serialization-json | **1.8.1** | `gradle/libs.versions.toml` |
| springdoc-openapi-starter-webmvc-ui | **2.8.17** | `gradle/libs.versions.toml` |
| PostgreSQL Docker image | **`postgres:17.6-alpine`** (digest `sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94`) | `docker-compose.yml`, test base class |

**BOM-managed** (versions supplied by the Spring Boot 3.5.16 dependency BOM — recorded here for
provenance; not separately pinned):

| Component | Resolved version |
|---|---|
| Testcontainers (postgresql, junit-jupiter, core) | **1.21.4** |
| PostgreSQL JDBC driver | **42.7.11** |
| Flyway (flyway-core, flyway-database-postgresql) | **11.7.2** |
| spring-security-oauth2-jose (Nimbus, via oauth2-resource-server) | **6.5.11** |

> Note: `./gradlew --version` reports "Kotlin: 2.0.21" — that is the Kotlin runtime *embedded in
> Gradle 8.14.5* for `.gradle.kts` script compilation, **not** the project's Kotlin. Project
> sources compile with the pinned **2.1.21** plugin.

## Prerequisites

- **JDK 21** (project toolchain). On macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- **Docker** running — required for the Testcontainers integration tests (`./gradlew build`)
  and for local dev via `docker compose`.

## Local development

```bash
# 1. Start the local PostgreSQL (host port 5433; throwaway local-only credentials kira/kira).
docker compose up -d

# 2. Run the app against it with the `dev` profile (reads the compose coordinates from
#    application-dev.yml — no .env needed for a plain local run).
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

Copy `.env.example` → `.env` (gitignored) and fill it in only when you need to run **outside**
the `dev` profile, or once later phases add `KIRA_JWT_SECRET` / admin-seed credentials. Never
commit a real `.env` or any real secret (PLAN hard-constraint 3).

## Build & test (the green gate)

```bash
./gradlew build          # compile + run tests (needs Docker running for Testcontainers)
```

`ContextLoadsIT` boots the Spring context against a real `postgres:17.6-alpine` Testcontainer,
applies Flyway `V1__users.sql`, and asserts the schema (the `security_state` singleton seed,
empty `users`, and the `uq_users_email_lower` case-insensitive email index). It uses one shared
container (Testcontainers singleton pattern) wired via `@ServiceConnection`.

**If Testcontainers cannot find your Docker daemon** (e.g. Colima/Rancher, where the socket is
not at `/var/run/docker.sock`), point it at the real socket for the build invocation, e.g. for
Colima:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew build
```

(These are machine-specific and intentionally not committed. Docker Desktop needs neither.)

## Layout

```
build.gradle.kts / settings.gradle.kts / gradle/libs.versions.toml   # single Gradle module, versions pinned
docker-compose.yml                                                   # local postgres:17.6-alpine on :5433, named volume
docs/PLAN.md                                                         # the specification (§1–§16 + appendices)
src/main/kotlin/me/manga/kira/backend/                               # KiraBackendApplication (packages grow per PLAN §3)
src/main/resources/                                                  # application.yml, application-dev.yml, db/migration/V*.sql
src/test/kotlin/me/manga/kira/backend/                               # ContextLoadsIT, support/AbstractIntegrationTest
```

See PLAN.md §2 (architecture), §3 (package/module structure), §5 (persistence), and §15
(phased build order) for the full design.
