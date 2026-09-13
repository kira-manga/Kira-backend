# Local development

Everything you need to build, run, and test kira-backend locally. Commands are runnable as written on
macOS. Authoritative spec: [`PLAN.md`](PLAN.md); versions: [`README.md`](README.md).

## Prerequisites

- **JDK 21** (the project toolchain). On macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- **Docker running** — required both for local dev (`docker compose`) and for the Testcontainers
  integration tests (`./gradlew build`). Docker Desktop, Colima, or Rancher all work.
- **curl + jq** — used by the API walkthroughs below and in [`USAGE.md`](USAGE.md).
- **Ed25519-capable OpenSSL** — use OpenSSL 3 with the existing Linux/macOS signing generator;
  set `OPENSSL_BIN` to its executable if it is not found automatically.

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

Copy the template, uncomment the two admin variables, and fill them in. The file uses shell syntax;
single-quote values containing spaces, `#`, `$`, or other shell-special characters.

```bash
cp .env.example .env      # .env is gitignored — never commit it or any real secret
# edit KIRA_ADMIN_EMAIL and KIRA_ADMIN_PASSWORD, then export every assignment into this shell
set +x
set -a; source .env; set +a
```

Spring Boot and Gradle do **not** load `.env` automatically. You must source it in each new shell (or
export the variables directly) before running `bootRun`. The datasource lines are commented by default
because the `dev` profile already has the matching local Docker coordinates.

| Variable | When needed | Notes |
|---|---|---|
| `KIRA_ADMIN_EMAIL`, `KIRA_ADMIN_PASSWORD` | admin seeding (on by default, incl. dev) | Startup fails fast if seeding is enabled but these are absent. Password must satisfy the policy (≥ 15 chars, ≤ 72 UTF-8 bytes); it is BCrypt-hashed and never logged. To run without seeding, set `KIRA_ADMIN_SEED_ENABLED=false`. |
| `KIRA_JWT_SECRET` | outside the `dev` profile | Base64 that decodes to ≥ 256 bits, e.g. `openssl rand -base64 32`. The `dev` profile ships a clearly-insecure default so you don't need this locally. |
| The four `KIRA_SIGNING_*` aliases below | every running profile, including `dev` | A valid active id and matching local Ed25519 pair are required at bean initialization. Missing, disabled, malformed or mismatched material refuses startup. |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | outside the `dev` profile | The `dev` profile reads the compose coordinates from `application-dev.yml` directly, so a plain local run needs none of these. |

A minimal local `dev` run needs local document-signing material plus `KIRA_ADMIN_EMAIL` +
`KIRA_ADMIN_PASSWORD` (unless admin seeding is explicitly disabled).

### Local document signing

Generate a **local-only** key pair once, from the repository root, using the existing Linux/macOS
recipe. The directory is gitignored; the generator sets restrictive permissions and refuses to
overwrite existing files. It prints the public key only. Do not enable shell tracing for secrets.

```bash
scripts/signing/generate-key.sh local-dev-01 .secrets/signing-dev
```

Export these four aliases in **each new shell**, reading the existing files; do not regenerate the
pair merely to restart the app. The private file is PKCS#8 DER in standard Base64; the public file
is X.509 SubjectPublicKeyInfo DER in standard Base64.

```bash
set +x
export KIRA_SIGNING_ACTIVE_KEY_ID=local-dev-01
export KIRA_SIGNING_PRIVATE_KEY="$(cat .secrets/signing-dev/local-dev-01.private.b64)"
export KIRA_SIGNING_VERIFICATION_KEYS_0_KEY_ID="$KIRA_SIGNING_ACTIVE_KEY_ID"
export KIRA_SIGNING_VERIFICATION_KEYS_0_PUBLIC_KEY="$(cat .secrets/signing-dev/local-dev-01.public.b64)"
```

Common `application.yml` explicitly maps these underscored aliases in every profile. There is no
generated/default key or unsigned running mode: `KIRA_SIGNING_ENABLED=false` refuses initialization,
even when global lazy initialization is enabled. Error messages identify `kira.signing.*` properties
and this recipe without printing configured ids or key material. Never commit the local files, share
them with production, install them as GitHub production secrets, or add them to shipping App trust
pins. The separate production ceremony and complete-list rotation configuration are in
[`SOURCE_DOCUMENT_SIGNING.md`](SOURCE_DOCUMENT_SIGNING.md).

## Running the app

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
docker compose up -d
set +x
set -a; source .env; set +a
# Repeat the four signing exports from "Local document signing" above in this shell.
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

The `dev` profile points at the compose DB (`localhost:5433`), ships the insecure JWT default, seeds an
admin, and enables open registration. Flyway applies the forward migrations through V13.2 at startup;
`ddl-auto=validate` means Hibernate only validates the Flyway-owned schema (see gotchas).

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
only a reachable Docker daemon. Their contexts provide ephemeral in-memory signing pairs through
canonical test properties; no real signing keys or committed test private keys are needed.

## Seeding data (atomic initial bootstrap)

On a fresh eligible catalog, **bootstrap while PENDING before ordinary source authoring** using
`POST /api/v1/admin/source-catalog-v2/cutover/import-bundled`. It admits one owner-reviewed, frozen
45-source JSON file and atomically creates the approved 12-generic/33-withheld origin with its
receipt. Ordinary import (even no-op) and normal materialization require COMPLETE afterward.
An existing populated local volume may instead be RECONCILIATION_REQUIRED; a matching source roster
does not qualify it for bootstrap. Read the [migration/rollout contract](MIGRATION_BUNDLED_TO_REMOTE.md)
before changing retained state; this is not an automatic reset/adoption procedure.

```bash
# 1. Log in as the seeded admin. This shell must already have sourced .env.
LOGIN_JSON=$(jq -n --arg email "$KIRA_ADMIN_EMAIL" --arg password "$KIRA_ADMIN_PASSWORD" \
  '{email: $email, password: $password}')
ADMIN_TOKEN=$(curl --fail-with-body -sS http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  --data "$LOGIN_JSON" | jq -er '.accessToken')
unset LOGIN_JSON

# 2. Inspect the advisory phase. ready is not payload approval or an admission reservation.
curl --fail-with-body -sS http://localhost:8080/api/v1/admin/source-catalog-v2/cutover \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq

# 3. Set this to the already reviewed, frozen raw 45-source JSON file (≤ 5 MiB).
# Do not regenerate, normalize, or edit it between review, submission and retry.
BOOTSTRAP_JSON='/absolute/path/to/owner-reviewed-frozen-45-source.json'
curl --fail-with-body -sS -X POST http://localhost:8080/api/v1/admin/source-catalog-v2/cutover/import-bundled \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'X-Kira-Bootstrap-Confirmation: WITHHOLD_33_LEGACY_SOURCES' \
  --data-binary @"$BOOTSTRAP_JSON" | jq

# 4. Inspect served metadata (this is not cryptographic delivery/activation proof).
curl --fail-with-body -sS http://localhost:8080/api/v1/source-config/document/meta | jq
curl --fail-with-body -sS http://localhost:8080/api/v2/source-config/manifest | jq
```

Success is a nine-field immutable origin receipt, not the ordinary import summary. Retain it and the
**exact request file**: same-byte replay after COMPLETE returns that receipt, even after later catalog
changes; any byte difference conflicts. The `jq` above formats responses only, never request bytes.
The old confirmation-only POST is nonmutating 409. The historical
`src/test/resources/fixtures/bundled-full.json` has generic drift and is **not** approved bootstrap
input; neither a test builder nor the 12-only reference projection replaces the reviewed 45-source
file. See the [cutover checklist](MIGRATION_BUNDLED_TO_REMOTE.md#6-cutover-checklist) for separate signed
v2 delivery/activation verification and owner rollout gates.
For user creation, completions, source edits, publishing, lifecycle changes, ETags, and production
configuration, continue with [`USAGE.md`](USAGE.md).

## Common gotchas

- **Testcontainers can't find Docker** → set `DOCKER_HOST` + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`
  (Colima section above). The symptom is the build hanging or failing at container startup.
- **`ddl-auto=validate` — Flyway owns the schema.** Hibernate never creates or alters tables; it only
  validates the entity mappings against the Flyway-applied schema. Schema changes are new
  `db/migration/V<n>__*.sql` files (roll forward only; `outOfOrder=false`), never entity-driven DDL.
  A mapping/schema mismatch fails startup — fix the migration, not `ddl-auto`.
- **Admin seeding fail-fast.** If seeding is enabled (default) without `KIRA_ADMIN_EMAIL` /
  `KIRA_ADMIN_PASSWORD`, startup fails with a clear message. Source `.env` first, set both directly, or
  export `KIRA_ADMIN_SEED_ENABLED=false`.
- **Document signing fail-fast.** Every running profile needs the four signing aliases above (or
  equivalent complete canonical properties). Re-export the local pair in a new shell; disabling
  signing is not a development workaround. Do not substitute production keys.
- **`./gradlew --version` shows "Kotlin: 2.0.21".** That is Gradle 8.14.5's embedded script-compiler
  Kotlin, not the project's — sources compile with the pinned 2.1.21 plugin.
- **Don't switch Spring Boot to 4.x** to "get the latest" — the 3.5.x pin is deliberate; a major upgrade
  is a separate, fully-tested change.
- **Wipe disposable local data only** with `docker compose down -v` (drops the `kira_pgdata` volume);
  a plain `down` keeps it. This is not a reconciliation/recovery procedure for a retained deployment.
