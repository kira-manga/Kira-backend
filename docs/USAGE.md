# Using kira-backend

This is the practical, start-to-finish workflow for the current backend. Run every command from the
`kira-backend/` directory. The exhaustive endpoint contract is in [`API.md`](API.md); source lifecycle
rules are in [`SOURCE_CONFIG_LIFECYCLE.md`](SOURCE_CONFIG_LIFECYCLE.md).

The backend currently provides source-config publication, JWT users/admin, audit records, and a fake
authenticated `echo` completion provider. The Kira app is **not yet wired to fetch this backend**, and
the backend checksum/ETag is not a detached signature.

## 1. Start locally

Prerequisites: JDK 21, a running Docker daemon, curl, and jq.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
docker compose up -d

cp .env.example .env
# Uncomment and edit KIRA_ADMIN_EMAIL and KIRA_ADMIN_PASSWORD in .env.
# Keep shell-special values single-quoted.
set -a; source .env; set +a

SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

`.env` is not loaded automatically. Source it in every new shell used to start or call the service.
The dev profile uses PostgreSQL at `localhost:5433`, enables registration, seeds the first admin, and
uses an intentionally insecure local JWT key.

In a second shell:

```bash
set -a; source .env; set +a
KIRA_API_URL='http://localhost:8080/api/v1'

curl --fail-with-body -sS http://localhost:8080/actuator/health | jq
```

Expected health status is `UP`. Swagger UI is available in dev at
`http://localhost:8080/swagger-ui/index.html`.

## 2. Log in as the seeded admin

```bash
ADMIN_LOGIN_JSON=$(jq -n \
  --arg email "$KIRA_ADMIN_EMAIL" \
  --arg password "$KIRA_ADMIN_PASSWORD" \
  '{email: $email, password: $password}')

ADMIN_TOKEN=$(curl --fail-with-body -sS "$KIRA_API_URL/auth/login" \
  -H 'Content-Type: application/json' \
  --data "$ADMIN_LOGIN_JSON" | jq -er '.accessToken')
unset ADMIN_LOGIN_JSON

curl --fail-with-body -sS "$KIRA_API_URL/auth/me" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

Tokens expire after 60 minutes by default. Log in again when a token expires. There is no refresh-token
endpoint in v1.

## 3. Seed the source catalog

A new database has no published source document. Import the real bundled test fixture once as the
initial migration:

```bash
curl --fail-with-body -sS -X POST "$KIRA_API_URL/admin/sources/import-bundled" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @src/test/resources/fixtures/bundled-full.json | jq
```

The import is transactional: validation failure returns 422 and writes nothing; a successful changed
import creates exactly one whole-document snapshot. Incoming document revision/timestamp values are
ignored because the backend allocates them.

Do not use routine re-import as a substitute for normal source editing. Existing draft-only sources are
reported in `skippedDraft` and are never replaced or published. A partly existing catalog can retain old
positions instead of exactly inheriting payload ordering, so review ordering before re-importing.

## 4. Read the public API

No token is required:

```bash
# Latest metadata and source summaries
curl --fail-with-body -sS "$KIRA_API_URL/source-config/document/meta" | jq
curl --fail-with-body -sS "$KIRA_API_URL/sources" | jq

# Comma-separated filters
curl --fail-with-body -sS \
  "$KIRA_API_URL/sources?engine=generic&lifecycle=active,disabled" | jq

# One raw canonical source stanza
curl --fail-with-body -sS "$KIRA_API_URL/sources/Azora" | jq

# Save the exact canonical document and response headers
curl --fail-with-body -sS -D /tmp/kira-document.headers \
  -o /tmp/kira-document.json "$KIRA_API_URL/source-config/document"
jq '{schemaVersion, revision, sourceCount: (.sources | length)}' /tmp/kira-document.json
```

Use the strong ETag for conditional polling:

```bash
DOCUMENT_ETAG=$(awk 'tolower($1) == "etag:" {gsub("\r", "", $2); print $2}' \
  /tmp/kira-document.headers)

curl -i -sS "$KIRA_API_URL/source-config/document" \
  -H "If-None-Match: $DOCUMENT_ETAG"
```

An unchanged document returns 304 with no body. `X-Config-Checksum` verifies transport corruption only;
use HTTPS in deployment and do not treat the checksum as proof of authenticity.

## 5. Create and use a normal user

Production registration is disabled, so admins create users:

```bash
USER_EMAIL='reader@example.com'
USER_PASSWORD='replace-with-a-15-plus-character-password'
CREATE_USER_JSON=$(jq -n \
  --arg email "$USER_EMAIL" \
  --arg password "$USER_PASSWORD" \
  '{email: $email, password: $password, role: "USER"}')

USER_ID=$(curl --fail-with-body -sS -X POST "$KIRA_API_URL/admin/users" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data "$CREATE_USER_JSON" | jq -er '.id')
unset CREATE_USER_JSON

USER_LOGIN_JSON=$(jq -n \
  --arg email "$USER_EMAIL" \
  --arg password "$USER_PASSWORD" \
  '{email: $email, password: $password}')
USER_TOKEN=$(curl --fail-with-body -sS "$KIRA_API_URL/auth/login" \
  -H 'Content-Type: application/json' \
  --data "$USER_LOGIN_JSON" | jq -er '.accessToken')
unset USER_LOGIN_JSON
```

Passwords must be at least 15 characters and no more than 72 UTF-8 bytes. Admin user operations are:

```bash
curl --fail-with-body -sS "$KIRA_API_URL/admin/users?page=0&size=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq

curl --fail-with-body -sS -X POST "$KIRA_API_URL/admin/users/$USER_ID/disable" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

curl --fail-with-body -sS -X POST "$KIRA_API_URL/admin/users/$USER_ID/enable" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Disabling a user invalidates their existing token on its next request. The API refuses to disable the
last enabled admin.

## 6. Use the echo completion API

The current provider is a test foundation, not an AI service:

```bash
COMPLETION_JSON=$(curl --fail-with-body -sS -X POST "$KIRA_API_URL/completions" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"prompt":"Hello from Kira","model":"echo-1"}')
printf '%s\n' "$COMPLETION_JSON" | jq
COMPLETION_ID=$(printf '%s\n' "$COMPLETION_JSON" | jq -er '.id')

curl --fail-with-body -sS "$KIRA_API_URL/completions/$COMPLETION_ID" \
  -H "Authorization: Bearer $USER_TOKEN" | jq
curl --fail-with-body -sS "$KIRA_API_URL/completions?page=0&size=20" \
  -H "Authorization: Bearer $USER_TOKEN" | jq
```

A supplied `model` must be 128 characters or fewer; longer input returns 400 `MODEL_TOO_LONG` before
anything is persisted.

## 7. Edit and publish a source

Source content is immutable revision history. The normal workflow is: fetch a published revision,
edit its `config`, create a new draft revision, inspect validation, then publish.

```bash
SOURCE_API='Azora'
SOURCE_HEAD=$(curl --fail-with-body -sS "$KIRA_API_URL/admin/sources/$SOURCE_API" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
PUBLISHED_REVISION=$(printf '%s\n' "$SOURCE_HEAD" | jq -er '.currentPublishedRevisionNumber')

curl --fail-with-body -sS \
  "$KIRA_API_URL/admin/sources/$SOURCE_API/revisions/$PUBLISHED_REVISION" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  | jq '.config | .displayName = "Azora (local edit)"' > /tmp/kira-source-edit.json

REVISION_RESULT=$(curl --fail-with-body -sS -X POST \
  "$KIRA_API_URL/admin/sources/$SOURCE_API/revisions" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/kira-source-edit.json)
printf '%s\n' "$REVISION_RESULT" | jq
NEW_REVISION=$(printf '%s\n' "$REVISION_RESULT" | jq -er '.revisionNumber')

curl --fail-with-body -sS -X POST \
  "$KIRA_API_URL/admin/sources/$SOURCE_API/revisions/$NEW_REVISION/validate" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq

curl --fail-with-body -sS -X POST \
  "$KIRA_API_URL/admin/sources/$SOURCE_API/revisions/$NEW_REVISION/publish" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

The source `api` in the body must exactly equal the path identity. Authored revision content must stay
lifecycle-neutral (`"active"` or omitted); lifecycle changes use the dedicated endpoints. Do not put
credentials in source headers. Header names with surrounding whitespace or other non-token characters
are rejected with `HEADER_NAME_INVALID`.

Rollback copies old content into a new highest revision and publishes it; it never rewrites history:

```bash
curl --fail-with-body -sS -X POST "$KIRA_API_URL/admin/sources/$SOURCE_API/rollback" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data "{\"toRevision\":$PUBLISHED_REVISION}" | jq
```

## 8. Change source lifecycle

The enforced path is `active → disabled → retired → removed`. Removal is terminal and requires the API
identity as confirmation:

> The commands below permanently remove `Azora` from this local catalog. Do not run the full sequence
> against a source you intend to keep.

```bash
curl --fail-with-body -sS -X POST "$KIRA_API_URL/admin/sources/$SOURCE_API/disable" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq

curl --fail-with-body -sS -X POST "$KIRA_API_URL/admin/sources/$SOURCE_API/retire" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq

curl --fail-with-body -sS -X POST "$KIRA_API_URL/admin/sources/$SOURCE_API/remove" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data "{\"confirm\":\"$SOURCE_API\"}" | jq
```

Use `POST .../enable` to restore a disabled source. A retired source can be enabled only when its
published engine is `generic`. Read the grace-window rules before using terminal removal.

## 9. Production checklist

- Run with `SPRING_PROFILES_ACTIVE=prod` and provide `SPRING_DATASOURCE_URL`,
  `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, a random Base64 `KIRA_JWT_SECRET`, and
  the bootstrap admin credentials through the deployment secret store.
- Generate the JWT key with `openssl rand -base64 32`; never use the dev key.
- Terminate HTTPS at a trusted ingress. Enable forwarded-header trust only with an explicit trusted
  proxy allowlist.
- Keep an ingress body limit as defense in depth. The application enforces 256 KiB generally, 5 MiB
  for bundled import, and a separate completion prompt character cap.
- Keep the service single-instance while using the in-memory login throttle, or replace it with a
  shared throttle store before scaling horizontally.
- Verify `kira.config.bundled-revision-floor` against the revision in the shipping app before cutover.
- Run `./gradlew clean build` with Docker available before deployment.
- Do not claim app cutover is complete: remote app fetching and detached config signatures are future
  app/backend work.

## 10. Remaining known limitations

- A re-import into a partly populated catalog may not fully reproduce payload order because existing
  positions are retained. Prefer revision/publish endpoints after the initial import.
- Completion capacity is bounded, but a queued call's provider timeout is still measured from submit
  time, so time spent waiting in the queue counts against the provider timeout.
- The source `visibleWhen` cycle check remains recursive and can overflow on an adversarially deep
  dependency chain.
- Admin source listing still performs extra revision lookups per source and should be batched before
  substantially increasing the catalog beyond its current size.

These are current implementation constraints, not intended final behavior.
