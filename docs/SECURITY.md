# Security

Derived from the `security/` package, `SecurityConfig`, the `config/Kira*Properties`, the validation
rules, and the `application*.yml` profiles. Authoritative spec: [`PLAN.md`](PLAN.md) §6 (+ §8 rule 32,
§10). Endpoint-by-endpoint auth levels are in [`API.md`](API.md).

## Authentication (JWT)

- **Scheme:** HS256 (symmetric) via Nimbus (`spring-security-oauth2-jose`). One shared in-process key —
  asymmetric signing is deliberately not introduced without a real multi-service key-distribution need.
  A `kid` header is emitted from day one as the rotation seam (single active key in v1).
- **Claims:** `sub` = user UUID, `email`, `role` (`ADMIN|USER`), `iss = "kira-backend"`,
  `aud = "kira-api"`, `iat`, `exp = iat + kira.security.access-token-ttl` (default **PT60M**).
- **Verification:** `NimbusJwtDecoder.withSecretKey(...)` explicitly validates the signature, `exp`/`nbf`
  (60s clock skew), `iss`, and `aud`. Issuer/audience/skew/TTL are `kira.security.*` properties.
- **Key handling:** `kira.security.jwt-secret` binds from env `KIRA_JWT_SECRET` and must be **Base64
  that decodes to ≥ 256 bits** (`openssl rand -base64 32`), NOT a human passphrase. Startup fails fast
  if it is missing, not valid Base64, or < 32 decoded bytes — except a documented dev-profile default
  clearly marked insecure (`application-dev.yml`). **Rotation:** issue a new key, bump `kid`, and (when
  needed) run a bounded dual-accept window before retiring the old key.

### DB-backed per-request check (why in-flight tokens die on disable)

The enabled/role check lives **inside the authentication pipeline**, not in a controller argument
resolver (which would only run when a handler injects it). A custom `jwtAuthenticationConverter`
(`Converter<Jwt, AbstractAuthenticationToken>`) registered on `oauth2ResourceServer { jwt {} }` runs for
**every** request that presents a bearer token, on every protected endpoint. After standard JWT
verification it:

1. loads the user by `sub` (indexed PK read);
2. **rejects a missing or `enabled = false` user with 401** (`InvalidBearerTokenException` → the
   resource-server entry point) — so disabling a user takes effect on their next request everywhere,
   with no token-version bookkeeping and no logout;
3. derives granted authorities from the **DB `role`, not the token claim** — a server-side role change
   takes effect on the target's next request, and a stale token role claim can never grant outdated
   access (the claim stays in the token as a diagnostic/client convenience only);
4. exposes the loaded user as the authentication principal, so `CurrentUser` is a SecurityContext read
   (no second DB query per request).

Sessions are `STATELESS`; CSRF is disabled (pure bearer-token API); HTTP Basic / form login are
disabled. CORS is disabled by default and, when explicitly configured, permits only HTTPS origins
from `kira.security.allowed-origins` (never `*`, never credentials). Method security is on for the
completion ownership check.

## Passwords

- **Policy:** minimum **15 characters** (NIST SP 800-63B single-factor guidance; an operator API where
  password managers are assumed), maximum **72 UTF-8 bytes** (the BCrypt input limit, enforced with a
  clear 400 — never silently truncated). No composition rules, no expiry, no trimming/normalization of
  the password itself (email is trim + lowercased). The byte cap is documented as encoder-derived;
  moving to Argon2 later via the delegating encoder lifts it.
- **Hashing:** the `PasswordEncoder` bean is a `DelegatingPasswordEncoder` with `{bcrypt}` as the
  initial id, so the stored hash carries its `{id}` prefix and the schema (`password_hash varchar(255)`)
  is not frozen to BCrypt. BCrypt cost is calibrated on real deployment hardware at setup (target
  ≈ 100 ms), not hardcoded forever.
- Passwords are never echoed in any response and **never written to any log at any level**.

## Onboarding, registration gating, and the last-admin guard

- `POST /auth/register` is gated by `kira.auth.registration-enabled` — **`true` in dev, `false` in
  prod and false by default in every unspecified profile**. Prod onboarding is via the admin user API
  (`POST /admin/users`), not open registration.
- **Admin seeding** (`AdminSeeder`, an `ApplicationRunner`): if no `ADMIN` exists, create one from
  `KIRA_ADMIN_EMAIL` + `KIRA_ADMIN_PASSWORD`. Missing env while seeding is enabled → **fail startup**
  with a clear message (dev included; export them directly or source the gitignored `.env` first — it
  is not loaded automatically). An existing admin's password is never reset, and the password is never
  logged.
- **Last-admin guard:** disabling a user refuses (**409**) to disable the last enabled `ADMIN`. The
  guard is serialized via a `SELECT … FOR UPDATE` on the singleton `security_state` row *before*
  counting — a bare count-then-disable is racy under READ COMMITTED (two transactions each see 2 enabled
  admins and disable different ones → zero). Enable and disable both take this lock.

## Auth throttling + trusted client-IP resolution

Authentication throttling is selected explicitly with `kira.security.throttle.backend`. The bounded
in-memory implementation is accepted only when `instance-count=1`; production with multiple replicas
must use the shared Redis implementation and a `rediss://` URL. The Redis path uses an atomic Lua
operation, server time, expiring bounded counters, and hashed identities. Redis errors fail closed with
503 instead of silently bypassing throttling.

- **Login:** an identity bucket covers each normalized-email/client-IP pair (`≥ 5` failures by default),
  and a separate aggregate IP bucket covers attempts spread across emails (`≥ 25` by default). Blocks
  double per breach, cap at 15 minutes, and reset after the idle window; successful login clears only
  its identity bucket, not aggregate spray history. There is **no permanent lockout**. Throttled calls
  return **429** with a generic body. Unknown and disabled accounts verify against a startup-generated
  decoy hash, so every credential path performs one password-hash check.
- **Registration:** a per-IP rate limit within a window.
- **Trusted client-IP:** the client address is the server-observed `request.remoteAddr` by **default**.
  `X-Forwarded-For` / `Forwarded` are honored **only** when `kira.security.trust-forwarded-headers=true`
  AND the direct peer is in `kira.security.trusted-proxies` (CIDR/address list, empty by default), in
  which case the effective client is the rightmost non-trusted hop. With the mode off, forwarding
  headers are completely ignored — a spoofed `X-Forwarded-For` can neither dodge its own bucket nor
  poison someone else's. Malformed/oversized (> 1 KB) headers fall back safely to the remote address.
- **Bounded store:** `kira.security.throttle.max-entries` (default 100 000); TTL expiry on every entry;
  deterministic eviction when full (dead entries first, then oldest-by-last-update); keys hash the
  (capped) email; each entry stores only counters/timestamps — no credentials, no payloads.

Both implementations preserve the same policy. Tuning lives under `kira.security.throttle.*`
(`login-failure-threshold`, `login-initial-block`,
`login-max-block`, `login-failure-window`, `registration-max-per-window`, `registration-window`).

## Secrets policy

The `prod` profile has an explicit startup policy and fails before serving traffic when it is mixed
with `dev`, registration is enabled, a known development/test JWT key is used, the public/CORS origin
is not a credential-free HTTPS origin, the datasource is not PostgreSQL, or PostgreSQL does not use
`sslmode=verify-full`. JWT issuer and audience must differ, token TTL is positive and no more than 24
hours, clock skew is shorter than the TTL, and invalid trusted-proxy entries fail startup.

- **All secrets come from the environment**, never hardcoded, never committed: `KIRA_JWT_SECRET`, DB
  creds (`SPRING_DATASOURCE_*`), admin seed creds (`KIRA_ADMIN_EMAIL`/`KIRA_ADMIN_PASSWORD`), and any
  future provider key (`KIRA_COMPLETION_API_KEY`). `application.yml` holds only relaxed-binding env
  mappings and obviously-non-production dev defaults; `.env` is gitignored; `.env.example` carries
  placeholders only; `docker-compose.yml` carries only a throwaway local DB password.
- **No secrets in the published config.** The served document is public and cacheable, so validation
  rule 32 (publish-blocking) rejects credential-like material: hard-denied header names `cookie`,
  `set-cookie`, `proxy-authorization`; sensitive-name headers (`authorization`, `x-api-key`, `api-key`,
  `x-auth-token`, any name containing `token`/`secret`/`password`) are allowed **only** when the value
  is on the explicit public-placeholder allowlist (`kira.validation.public-header-placeholder-values`,
  default exactly `["Bearer null"]` — the literal placeholder the real bundled document requires); URLs
  (`baseUrl`, `imageBase`, `icon.remoteUrl`) must be real absolute URIs with **no user-info**
  (`https://user:pass@host` → rejected), no fragment, valid port. *Every value published in a
  `SourceConfig` is public application configuration — never place credentials, cookies, tokens, or
  private API keys in it.* Header names must also be ASCII RFC field-name tokens with no surrounding
  whitespace; invalid names are publication-blocking `HEADER_NAME_INVALID` findings before sensitive-
  name evaluation.

## Logging & diagnostics (§6, normative)

- **Format:** the `prod` profile emits **structured JSON** (Spring Boot's native `logstash` console
  format — no external encoder dependency); dev/base emit readable console logs. Same event catalog,
  different format. DEBUG is off in prod by default (root INFO).
- **Correlation:** an inbound `X-Request-Id` is accepted **only** when it matches `[A-Za-z0-9._-]{1,64}`;
  anything else (control chars, over-length, absent) is discarded and a server UUID is generated —
  unvalidated header values never reach the MDC or a log line. The effective id is echoed in the
  `X-Request-Id` response header.
- **Bounded MDC fields:** `requestId` (validated/generated), `httpMethod`, `route` (the matched **route
  pattern** `/api/v1/sources/{api}` — never the raw URL, which is uncontrolled input; `(unmatched)` when
  none), `status`, `durationMs`, and once authenticated `userId` (UUID) + `role` (enum). All bounded and
  server-controlled.
- **Event catalog (logged once, at the owning boundary):**
  - `INFO` — startup + active profile; Flyway migration state; startup consistency-check results; admin
    seeding outcome (created / already-present — never the password); login success; user
    enable/disable; source + revision creation; validation summaries (counts + codes); publish/rollback
    (api, revision numbers, document revision); lifecycle transitions (from→to); bundled-import summaries
    (counts); document publication (revision + checksum); completion state transitions
    (`PENDING→RUNNING→SUCCEEDED/FAILED`, with request id, model, error code, latency).
  - `WARN` — rejected auth attempts (generic category, no credential echo); throttling activation;
    validation rejection summaries; recoverable provider failures (with the stable code).
  - `ERROR` — unexpected failures needing operator attention (unhandled exceptions,
    startup-validation failures, `INTERNAL_COMPLETION_ERROR` causes — full stack trace HERE and only
    here, in the secured server log, never returned).
- **Never logged (non-negotiable):** passwords or password hashes; JWTs or the `Authorization` header;
  cookies/session values; complete source-config bodies; source header **values**; completion
  prompts/results; raw provider responses/exceptions on any client-visible channel; unvalidated user
  input as a log **field name**. Newline/control characters in any user-influenced value that reaches a
  log message are sanitized (e.g. the client-supplied completion `model`); validation-error logging is
  bounded to codes + paths. There is no request-body logging for auth, config-authoring, or completion
  endpoints, and no SQL/parameter logging in any profile.

## Completion admission, provider, and retention

Completions are disabled by default. Production startup fails if they are enabled without the HTTPS
provider endpoint and API key. Echo exists only in explicit `dev`/`test` profiles. Admission applies
atomic per-user/global minute limits, a per-user daily quota, and a global concurrency lease before a
request can enter the bounded executor. A multi-instance deployment must use Redis coordination;
single-instance memory coordination must be declared explicitly. Overload returns 429 for rate/quota
limits or 503 with `Retry-After` for queue/concurrency/provider availability failures.

Queue wait and provider execution have separate timeouts. A request is marked running only after the
worker begins, canceled work cannot later overwrite its terminal state, and every acquired concurrency
lease is released. Prompt/result sizes, executor threads, queue capacity, limits, timeouts, retention,
and cleanup batch size are bounded configuration.

## Retention & privacy

- **Completion data** (`completion_requests.prompt`, `completion_results.result`/`error`) is the only
  place prompts/results live. The scheduled bounded retention job expires stale in-flight requests and
  deletes terminal prompt/result rows older than `kira.completion.retention` (default seven days).
  Prompt/result contents never appear in audit rows or logs. Provider credentials / `Authorization`
  are never logged.
- **Audit rows** (`audit_log.detail`, jsonb) contain **identifiers, revision numbers, and checksums
  only** — never config bodies, header values, completion prompts/results, or passwords. This is enforced
  structurally: the audit encoder accepts only scalar values (String/Int/Long/Boolean/null) and throws
  if handed an object body. The `LOGIN_FAILED` row records the normalized email as the login *identifier*
  (never the password); `USER_PASSWORD_RESET` records actor + target only.
- **Payload integrity and authenticity.** `X-Config-Checksum` and the ETag detect corruption but are
  not trust roots. Every production snapshot is authenticated by an Ed25519 detached signature over
  versioned metadata and the exact canonical bytes. The app selects an in-binary pinned X.509 public
  key by key id, verifies the signature/checksum/chain, and rejects replay or rollback. Production
  startup refuses missing or mismatched signing material; private keys remain secret-manager-only.
  See `SOURCE_DOCUMENT_SIGNING.md`.

## Operational notes / seams (v1)

- **Actuator:** production exposes health and Prometheus only on the internal management port 9090.
  Liveness stays process-only; readiness includes PostgreSQL and Redis. The Kubernetes service and
  network policy allow metrics only from the monitoring namespace.
- **Completion error-code catalog** (`completion_results.error_code`): `PROVIDER_TIMEOUT`,
  `PROVIDER_UNAVAILABLE`, `PROVIDER_REJECTED`, `INVALID_PROVIDER_RESPONSE`, `RESULT_TOO_LARGE`
  `REQUEST_EXPIRED`, `INTERNAL_COMPLETION_ERROR` (every unexpected exception maps here). The client
  `error` message is always a sanitized bounded generic string. Production has no echo default model.
- **Refresh tokens** are future work — `POST /auth/refresh` is unregistered (standard 404); the
  `refresh_tokens` table is reserved but not created.
- **Graceful shutdown** is enabled (`server.shutdown=graceful`).
