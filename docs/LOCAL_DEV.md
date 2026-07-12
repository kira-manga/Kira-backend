# Local development

Everything you need to build, run, and test kira-backend locally. Commands are runnable as written on
macOS. Authoritative spec: [`PLAN.md`](PLAN.md); versions: [`README.md`](README.md).

## Prerequisites

- **JDK 21** (the project toolchain). On macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- **Docker running** — required both for local dev (`docker compose`) and for the Testcontainers
  integration tests (`./gradlew build`). Docker Desktop, Colima, or Rancher all work.

### Colima / non-default Docker socket

Testcontainers looks for the daemon at `/var/run/docker.sock`. If you use Colima (or any setup where
that socket is absent), point Testcontainers at the real socket for the build invocation:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

These are machine-specific and intentionally not committed. Docker Desktop needs neither.

## Database (docker-compose)

```bash
docker compose up -d      # postgres:17.6-alpine, host port 5433, named volume kira_pgdata
docker compose logs -f postgres   # tail
docker compose down       # stop (keeps the volume/data)
docker compose down -v    # stop AND delete the volume (wipe local data)
```

- Host port **5433** (avoids clashing with a local 5432); container port 5432.
- Credentials `kira` / `kira`, database `kira` — **local-only throwaway** (never used in any real
  deployment). Data persists in the named volume `kira_pgdata` across restarts.

## `.env` setup

Copy the template and fill in what you need:

```bash
cp .env.example .env      # .env is gitignored — never commit it or any real secret
```

| Variable | When needed | Notes |
|---|---|---|
| `KIRA_ADMIN_EMAIL`, `KIRA_ADMIN_PASSWORD` | admin seeding (on by default, incl. dev) | Startup fails fast if seeding is enabled but these are absent. Password must satisfy the policy (≥ 15 chars, ≤ 72 UTF-8 bytes); it is BCrypt-hashed and never logged. To run without seeding, set `kira.admin.seed-enabled=false`. |
| `KIRA_JWT_SECRET` | outside the `dev` profile | Base64 that decodes to ≥ 256 bits, e.g. `openssl rand -base64 32`. The `dev` profile ships a clearly-insecure default so you don't need this locally. |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | outside the `dev` profile | The `dev` profile reads the compose coordinates from `application-dev.yml` directly, so a plain local run needs none of these. |

A minimal local `dev` run only needs `KIRA_ADMIN_EMAIL` + `KIRA_ADMIN_PASSWORD`.

## Running the app

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
docker compose up -d
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

The `dev` profile points at the compose DB (`localhost:5433`), ships the insecure JWT default, seeds an
admin, and enables open registration. Flyway applies `V1..V5` at startup; `ddl-auto=validate` means
Hibernate only validates the Flyway-owned schema (see gotchas).

- **Swagger UI** (dev profile only): `http://localhost:8080/swagger-ui/index.html`
- **OpenAPI document**: `http://localhost:8080/v3/api-docs`
- **Health**: `http://localhost:8080/actuator/health` (also `/health/liveness`, `/health/readiness`)

Outside the `dev` profile, Swagger/api-docs require an `ADMIN` token.

## Running tests

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
# (Colima: also export the two socket vars above.)

./gradlew clean build          # the full green gate: compile + all unit + Testcontainers ITs

# A single class or method:
./gradlew test --tests "me.manga.kira.backend.user.SecurityMatrixIT"
./gradlew test --tests "me.manga.kira.backend.sourceconfig.admin.SourcePublishFlowIT"
./gradlew test --tests "me.manga.kira.backend.common.CanonicalJsonTest"
```

Integration tests share one `postgres:17.6-alpine` Testcontainer (started once, wired via
`@ServiceConnection`) under the `test` profile — no docker-compose or `.env` needed for the test run,
only a reachable Docker daemon.

## Seeding data (import the bundled document)

The backend starts empty. The migration on-ramp is `POST /api/v1/admin/sources/import-bundled` — send
the app's bundled document JSON (`CONFIG_BACKED_SOURCES_JSON`). It validates the whole document,
creates/updates per-source revisions, and materializes exactly one snapshot (all-or-nothing).

```bash
# 1. Log in as the seeded admin to get a token (replace with your .env admin credentials).
TOKEN=$(curl -s http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"<your-admin-email>","password":"<your-admin-password>"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

# 2. Import the bundled document (≤ 5 MiB).
curl -s -X POST http://localhost:8080/api/v1/admin/sources/import-bundled \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @bundled-full.json

# 3. Verify the served document + summaries.
curl -s -D - http://localhost:8080/api/v1/source-config/document/meta
curl -s http://localhost:8080/api/v1/sources
```

A test-fixture copy of the full document lives at
`src/test/resources/fixtures/bundled-full.json` (the real 45-source document). See
[`MIGRATION_BUNDLED_TO_REMOTE.md`](MIGRATION_BUNDLED_TO_REMOTE.md) for the full import contract.

## Common gotchas

- **Testcontainers can't find Docker** → set `DOCKER_HOST` + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`
  (Colima section above). The symptom is the build hanging or failing at container startup.
- **`ddl-auto=validate` — Flyway owns the schema.** Hibernate never creates or alters tables; it only
  validates the entity mappings against the Flyway-applied schema. Schema changes are new
  `db/migration/V<n>__*.sql` files (roll forward only; `outOfOrder=false`), never entity-driven DDL.
  A mapping/schema mismatch fails startup — fix the migration, not `ddl-auto`.
- **Admin seeding fail-fast.** If seeding is enabled (default) without `KIRA_ADMIN_EMAIL` /
  `KIRA_ADMIN_PASSWORD`, startup fails with a clear message. Set both, or `kira.admin.seed-enabled=false`.
- **`./gradlew --version` shows "Kotlin: 2.0.21".** That is Gradle 8.14.5's embedded script-compiler
  Kotlin, not the project's — sources compile with the pinned 2.1.21 plugin.
- **Don't switch Spring Boot to 4.x** to "get the latest" — the 3.5.x pin is deliberate; a major upgrade
  is a separate, fully-tested change.
- **Wipe local data** with `docker compose down -v` (drops the `kira_pgdata` volume); a plain
  `down` keeps it.
