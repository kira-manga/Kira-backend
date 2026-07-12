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
disabled; no CORS in v1. Method security is on for the completion ownership check.

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
  prod**. Prod onboarding is via the admin user API (`POST /admin/users`), not open registration.
- **Admin seeding** (`AdminSeeder`, an `ApplicationRunner`): if no `ADMIN` exists, create one from
  `KIRA_ADMIN_EMAIL` + `KIRA_ADMIN_PASSWORD`. Missing env while seeding is enabled → **fail startup**
  with a clear message (dev included; supply them via the gitignored `.env`). An existing admin's
  password is never reset, and the password is never logged.
- **Last-admin guard:** disabling a user refuses (**409**) to disable the last enabled `ADMIN`. The
  guard is serialized via a `SELECT … FOR UPDATE` on the singleton `security_state` row *before*
  counting — a bare count-then-disable is racy under READ COMMITTED (two transactions each see 2 enabled
  admins and disable different ones → zero). Enable and disable both take this lock.

## Auth throttling + trusted client-IP resolution

`AuthThrottleService` is a bounded **in-memory** store. **Single-instance only** — correct for one JVM;
a multi-instance deployment MUST move to a shared backend (Redis) first, and this class is that seam.

- **Login:** keyed by normalized email **and** client IP; `≥ 5` consecutive failures arm a temporary
  block that doubles per breach, capped at 15 minutes; counters reset on success or window expiry. There
  is **no permanent lockout** an attacker could weaponize against a victim account (everything is
  TTL-bounded). Throttled → **429** with the same generic body as an auth failure (no username-exists
  oracle).
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

Tuning lives under `kira.security.throttle.*` (`login-failure-threshold`, `login-initial-block`,
`login-max-block`, `login-failure-window`, `registration-max-per-window`, `registration-window`).

## Secrets policy

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
  private API keys in it.*

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

## Retention & privacy

- **Completion data** (`completion_requests.prompt`, `completion_results.result`/`error`) is the only
  place prompts/results live. In v1 they are **kept indefinitely** and are `ON DELETE RESTRICT`-protected
  evidence; a retention window (and any redaction) is a **future admin policy**, and final log/data
  retention windows are a deployment-platform concern outside this repo. Prompt/result contents never
  appear in audit rows or logs. Provider credentials / `Authorization` are never logged.
- **Audit rows** (`audit_log.detail`, jsonb) contain **identifiers, revision numbers, and checksums
  only** — never config bodies, header values, completion prompts/results, or passwords. This is enforced
  structurally: the audit encoder accepts only scalar values (String/Int/Long/Boolean/null) and throws
  if handed an object body. The `LOGIN_FAILED` row records the normalized email as the login *identifier*
  (never the password); `USER_PASSWORD_RESET` records actor + target only.
- **Payload integrity vs authenticity.** `X-Config-Checksum` (and the ETag) is a **corruption check,
  not authenticity** — a hash delivered beside the same payload cannot authenticate it. Authenticity is
  HTTPS today plus a **future** detached signature (Ed25519 over the canonical bytes; not implemented,
  no `signature_base64` column in v1).

## Operational notes / seams (v1)

- **Actuator:** only `/actuator/health` (+ `/liveness`, `/readiness`) is exposed and public
  (`management.endpoints.web.exposure.include=health`, `show-details: never`). Liveness stays process-only
  (survives a transient DB outage); readiness includes the DB. No other actuator endpoint is exposed.
- **Completion quota** is a no-op seam (`CompletionService.checkQuota`) — not implemented in v1; the
  persisted per-user request history makes a future quota a small count query.
- **Completion error-code catalog** (`completion_results.error_code`): `PROVIDER_TIMEOUT`,
  `PROVIDER_UNAVAILABLE`, `PROVIDER_REJECTED`, `INVALID_PROVIDER_RESPONSE`, `RESULT_TOO_LARGE`
  (**reserved, unused in v1**), `INTERNAL_COMPLETION_ERROR` (every unexpected exception maps here). The
  client `error` message is always a sanitized bounded generic string; the default model is `echo-1`.
- **Refresh tokens** are future work — `POST /auth/refresh` is unregistered (standard 404); the
  `refresh_tokens` table is reserved but not created.
- **Graceful shutdown** is enabled (`server.shutdown=graceful`).
