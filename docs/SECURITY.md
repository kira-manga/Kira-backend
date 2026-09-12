# Security

Derived from the `security/` package, `SecurityConfig`, the `config/Kira*Properties`, the validation
rules, and the `application*.yml` profiles. Authoritative spec: [`PLAN.md`](PLAN.md) §6 (+ §8 rule 32,
§10). Endpoint-by-endpoint auth levels are in [`API.md`](API.md).

## Authentication (JWT)

- **Scheme:** HS256 (symmetric) via Nimbus (`spring-security-oauth2-jose`). One shared in-process key —
  asymmetric signing is deliberately not introduced without a real multi-service key-distribution need.
  A `kid` header is emitted from day one as the rotation seam (single active key in v1).
- **Claims:** `sub` = user UUID, `email`, `role` (`ADMIN|USER`), `credential_version` (canonical
  nonnegative decimal **string**, not a JSON number), `iss = "kira-backend"`,
  `aud = "kira-api"`, `iat`, `exp = iat + kira.security.access-token-ttl` (default **PT60M**).
- **Verification:** `NimbusJwtDecoder.withSecretKey(...)` explicitly validates the signature, `exp`/`nbf`
  (60s clock skew), `iss`, and `aud`. Issuer/audience/skew/TTL are `kira.security.*` properties.
- **Key handling:** `kira.security.jwt-secret` binds from env `KIRA_JWT_SECRET` and must be **Base64
  that decodes to ≥ 256 bits** (`openssl rand -base64 32`), NOT a human passphrase. Startup fails fast
  if it is missing, not valid Base64, or < 32 decoded bytes — except a documented dev-profile default
  clearly marked insecure (`application-dev.yml`). **Rotation:** issue a new key, bump `kid`, and (when
  needed) run a bounded dual-accept window before retiring the old key.

### DB-backed per-request check (disable and password-reset revocation)

The enabled/credential-version/role check lives **inside the authentication pipeline**, not in a controller argument
resolver (which would only run when a handler injects it). A custom `jwtAuthenticationConverter`
(`Converter<Jwt, AbstractAuthenticationToken>`) registered on `oauth2ResourceServer { jwt {} }` runs for
**every** request that presents a bearer token, on every protected endpoint. After standard JWT
verification it:

1. loads the user by `sub` (indexed PK read);
2. **rejects a missing or `enabled = false` user with 401** (`InvalidBearerTokenException` → the
   resource-server entry point) — so disabling a user takes effect on their next authentication check;
3. requires a raw string `credential_version` exactly equal to the current nonnegative DB version's
   canonical decimal string. Missing/legacy, null, numeric, boolean, array/object, signed, padded,
   leading-zero, fractional/exponent, non-ASCII, overflow and unequal claims all fail with the same
   generic **401**. No coercing string getter, missing-to-zero fallback or time-based grace window;
4. derives granted authorities from the **DB `role`, not the token claim** — a server-side role change
   takes effect on the target's next request, and a stale token role claim can never grant outdated
   access (the claim stays in the token as a diagnostic/client convenience only);
5. exposes the loaded identity as the authentication principal, so `CurrentUser` is a SecurityContext read
   (no second DB query per request).

Sessions are `STATELESS`; CSRF is disabled (pure bearer-token API); HTTP Basic / form login are
disabled. CORS is disabled by default and, when explicitly configured, permits only HTTPS origins
from `kira.security.allowed-origins` (never `*`, never credentials). Method security is on for the
completion ownership check.

### Password-reset semantics and coordinated cutover

- `users.credential_version` is `BIGINT NOT NULL DEFAULT 0 CHECK (credential_version >= 0)`.
  V13.1 (`V13_1__user_credential_version.sql`) backfills existing users to 0; new users start at 0.
  This is not JPA optimistic locking or a second session store. The version is not added to user DTOs.
- Every successful admin reset, even to the same password, updates the hash, increments the **stored**
  version and stamps `updated_at` in one guarded database statement, inside the existing reset/audit
  transaction. Concurrent resets each advance once; rollback restores both fields and audit. At
  `Long.MAX_VALUE`, reset fails with generic **409** `CREDENTIAL_VERSION_EXHAUSTED`, without hash change
  or success audit. Missing targets remain **404**. Passwords, hashes and version values are not audited.
- Role/enabled mutations update only their own field and timestamp, never stale credential fields.
  The bulk writes flush pending work and clear stale JPA state; enable/disable retain the existing
  `security_state`-first lock and last-admin/no-op policy. They do not advance the credential version.
- Login signs the **same immutable user snapshot whose password was verified**, after successful
  throttle completion. It never rereads the version to upgrade an old-password login racing reset.
  Such a race may return an immediately stale token; its next DB-backed check must reject it.
- Revocation applies when authentication reads the user **after the reset commits**. It does not
  retroactively cancel already-authenticated in-flight work. An old bearer cannot request admin
  step-up even with the correct new password; independently stored, previously issued step-up grants
  have no new generation/revocation protocol in this change.
- **One-time reauthentication is intentional:** pre-upgrade tokens have no version claim and are
  rejected, not accepted as version 0. Clients/operators must sign in again.
- **Mixed old/new nodes and old-image rollback are not revocation-safe.** Old verifiers ignore the
  claim, old issuers omit it, and old reset code does not increment it. Coordinate migration and
  upgraded binaries, drain old nodes before resuming traffic, and do not use an old verifier as a
  security-preserving rollback. The existing deployment receiver's rollback behavior is unchanged.
  Production uses a separate migration job, not application-pod Flyway. Verify the actual installed
  history/checksums first: 13.1 follows 13 and precedes reserved 14; histories already beyond 13.1 need
  separately reviewed forward reconciliation, never out-of-order/repair/baseline or historical edits.
  Installed schema, fleet cutover/rollback and real operator-session behavior are **EXTERNAL
  VERIFICATION REQUIRED**; source tests do not establish those deployment facts.

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
- **Email identifiers:** shared login/creation normalization remains `trim().lowercase()`, followed
  by a **320 Unicode-code-point** bound (400 `EMAIL_TOO_LONG`). Count the normalized result, including
  lowercase expansion; supplementary characters count once. For valid PostgreSQL-representable Unicode
  this matches the column's character bound, not UTF-16 units or UTF-8 bytes. It does not add RFC-shape,
  Unicode-validity or canonical-equivalence checks, or promise JVM/database locale-casing equivalence.

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
must use shared Redis and a `rediss://` URL. Memory uses one lock and Redis uses one atomic Lua
admission/completion operation, with Redis server TIME rather than application-node time. Neither
holds a lock or Redis operation across user lookup or password verification. Redis unavailability,
null/malformed replies and write failures **deny**, with **429 `AUTH_THROTTLE_UNAVAILABLE` and
`Retry-After: 5`**; there is no memory fallback.

- **Login and admin password step-up:** atomically reserve one slot in BOTH the normalized-email/IP
  identity bucket (default threshold 5) and aggregate IP bucket (default 25) **before** credential work.
  In each dimension, completed failures plus live reservations must be below the threshold and any
  block must be over. Policy/capacity rejection acquires neither slot. Distinct emails cannot bypass the
  aggregate IP bound, and there is no global account-only lockout.
- Each opaque attempt is locally once-only and fenced by its unpredictable token and still-live,
  matching deadline in both buckets. Both outcomes complete atomically. Explicit failed completion
  precedes the ordinary rejection audit/401; acknowledged successful completion precedes JWT or
  step-up proof/grant creation. Unexpected exits close as one failure; cleanup errors are suppressed
  behind the original exception. Missing/expired success or duplicate completion denies issuance.
  An ambiguous Redis reply can leave a reservation until its bounded lease expires, but cannot permit
  credential work after failed admission or issuance after failed completion; it is never retried locally.
- A failure releases its reservation and increments each dimension's completed counter. A threshold
  breach resets that counter to zero and arms the existing doubling block (initial 1 minute, cap
  15 minutes). History ages since the **last failure** (default 15 minutes), not admission or successful
  IP activity; an unexpired block or another live reservation is never discarded by idle cleanup.
  Success resets only the identity's completed history/block/escalation and releases its own IP slot;
  it preserves other live attempts and aggregate IP failure history. A failure completed after that
  success is still counted. There is no permanent lockout, and rejection refreshes no history or TTL.
- `login-attempt-ttl` defaults to **30s**, must be positive whole milliseconds and is capped at **5m**.
  Expiry (including equality with the deadline) recovers abandoned capacity and fences late success.
  It is a **lease, not hash preemption**: a stalled/noninterruptible BCrypt may keep running after its
  slot expires, but cannot subsequently authorize a JWT/proof. No executor, heartbeat or hard CPU
  concurrency guarantee beyond the live lease is provided. Normal exceptional exits close promptly.
- Unknown and disabled login accounts still perform one startup-decoy hash check; eligible accounts
  perform one real check. Step-up retains its existing absent/disabled/non-ADMIN short circuit.
  Throttling and lease-expired success return generic **429**, without a credential-state oracle.
- **Registration:** a per-IP window cap, sharing the same global bucket capacity. An exhausted but
  unexpired registration window is protected from eviction just like an active login block.
- **Trusted client-IP:** the client address is the server-observed `request.remoteAddr` by **default**.
  `X-Forwarded-For` / `Forwarded` are honored **only** when `kira.security.trust-forwarded-headers=true`
  AND the direct peer is in `kira.security.trusted-proxies` (CIDR/address list, empty by default), in
  which case the effective client is the rightmost non-trusted hop. With the mode off, forwarding
  headers are completely ignored — a spoofed `X-Forwarded-For` can neither dodge its own bucket nor
  poison someone else's. Numeric addresses are canonicalized (IPv4-mapped IPv6 becomes IPv4; other
  IPv6 uses eight lowercase unpadded groups); parsing and CIDR matching never perform DNS lookups.
  Malformed/oversized (> 1 KB), non-ASCII, duplicated or partly invalid chains fall back to the peer.
  A present `X-Forwarded-For` selects that protocol even when invalid/all-trusted: it never falls
  through to `Forwarded`. Only absent XFF permits a wholly valid `Forwarded` chain. Generic numeric
  IPv4/IPv6 proxy CIDRs, rightmost-hop semantics and valid address/port forms remain supported.
- **Bounded store:** `max-entries` (default 100 000, minimum 2) is ONE logical-bucket bound across
  login identity, login IP and registration. Targets, live attempts, active blocks and exhausted
  registration windows are ineligible eviction victims. Capacity is preflighted before any reservation
  or victim removal; expired candidates precede oldest eligible activity, with lexical key tie-breaking.
  Memory prunes on access/capacity checks. Redis preselects at most **64** oldest index candidates
  outside Lua, declares all metadata/token/index keys in `KEYS`, then atomically rechecks membership,
  score and protection. A stale/inadequate shortlist or no safe room conservatively yields 429; there
  is no unbounded scan/retry. Dead index members may await bounded reclamation, counting toward the cap.
- Keys retain bounded hashed identifiers; values contain counters/timestamps and opaque attempt tokens,
  never credentials or request payloads. Each token set is threshold-bounded and expires no earlier
  than its latest live deadline. Metadata survives every lease/block/history horizon; the global index
  expiry can only extend to cover its members, never shorten with a smaller registration/login window.
  Eviction removes the metadata, token sidecar and index membership together.

Tuning lives under `kira.security.throttle.*`: `login-failure-threshold`, `login-ip-failure-threshold`,
`login-attempt-ttl`, `login-initial-block`, `login-max-block`, `login-failure-window`,
`registration-max-per-window`, `registration-window`, and `max-entries`.

### Redis operating assumptions and auth-state cutover

Use **`noeviction`**, or an equivalent guarantee that Redis cannot independently evict this security
state. `allkeys-*`/`volatile-*` eviction can delete a live reservation/block behind the application's
safe-eviction policy. Under `noeviction`, memory-pressure write errors deny admission/issuance; do not
interpret them as permission to fall back or retry a completion. All auth nodes must use the same
policy/configuration and shared Redis authority. The protocol requires **Redis 7+** (absolute expiry
inspection via `PEXPIRETIME`; the integration fixture is Redis 7.4.7). These multi-key scripts do
**not** support Redis Cluster sharding.

This attempt protocol uses the **`kira:auth-throttle:v2`** transient namespace and deliberately starts
fresh; it does not migrate old failure counters/blocks or old registration strings/index entries.
A coordinated maintenance transition is required: stop auth admission, drain all old login/step-up
work (or stop those processes), remove all old auth nodes, and only then enable new nodes together.
**No mixed old/new auth nodes and no ordinary rolling auth upgrade**: old check/hash/record nodes
cannot participate in reservations. Record the deliberate transient-history reset and retire the old
namespace through a separately approved operational procedure. No deployment/reset is performed by
this source change. Rollback also requires stopped admission, drained work and an explicit state choice.

Bounds are not promised uninterrupted through arbitrary Redis state loss/restart/failover or wall-clock
jumps. Redis TIME removes application-node clock disagreement, not wall-clock discontinuities. Loss of
reservation state cannot make an already-held handle authorize success; it can admit new work once the
store is available again. Account for this, lease duration and verifier latency in deployment planning.

### Server3 ingress (not a generic deployment default)

The `prod,server3` profile requires exactly two distinct provisioned numeric peers:
`KIRA_SERVER3_HOST_PEER` and `KIRA_SERVER3_ADMIN_ADDRESS`. The committed edge overwrites forwarding
metadata; Admin's isolated BFF link forwards one validated `X-Real-IP` as fresh XFF on password login
and step-up only. Missing/invalid metadata safely shares the BFF peer bucket; that is degraded
isolation, not proof of distinct clients. Never enable BFF trust on a publicly reachable/unisolated server.

A web-server-factory customizer checks the actual bound profile/security/peer/ServerProperties before
any listener is created. It rejects broad/extra/wrong peers, disabled trust, non-prod/dev combinations,
framework forwarding other than `NONE`, and Tomcat remote-IP/protocol-header overrides that could
otherwise rewrite the observed servlet peer even with `NONE`. Generic profiles are unaffected.
See [`../deploy/server3/README.md`](../deploy/server3/README.md) for the required Docker/Nginx topology,
provisioning and preflight. Installed routing/firewall isolation, peer observation and two-client
smoke checks remain external deployment verification; source tests do not prove them.

## Secrets policy

The `prod` profile has an explicit startup policy and fails before serving traffic when it is mixed
with `dev`, registration is enabled, a known development/test JWT key is used, the public/CORS origin
is not a credential-free HTTPS origin, the datasource is not PostgreSQL, or PostgreSQL does not use
`sslmode=verify-full`. JWT issuer and audience must differ, token TTL is positive and no more than 24
hours, clock skew is shorter than the TTL, and invalid trusted-proxy entries fail startup.

- **All secrets come from the environment**, never hardcoded, never committed: `KIRA_JWT_SECRET`, DB
  creds (`SPRING_DATASOURCE_*`), admin seed creds (`KIRA_ADMIN_EMAIL`/`KIRA_ADMIN_PASSWORD`), and any
  provider key (`KIRA_COMPLETION_API_KEY`) or document-signing private key (`KIRA_SIGNING_PRIVATE_KEY`).
  `application.yml` holds only environment mappings and obviously-non-production dev defaults;
  `.env` is gitignored; `.env.example` carries
  placeholders only; `docker-compose.yml` carries only a throwaway local DB password.
- **Document signing is mandatory in every running profile**, including `dev`, before the signer
  bean can be used. Common configuration maps the four documented signing aliases. Disabled signing,
  invalid ids, missing/malformed material or a mismatched pair refuses initialization, including
  with global lazy initialization. Diagnostics and their causes name properties and the local setup
  recipe, never configured ids, key bytes or raw key-parser messages. Local keys stay in ignored
  `.secrets/` and must never enter production or shipping App trust; see
  [`LOCAL_DEV.md`](LOCAL_DEV.md#local-document-signing) and [`SOURCE_DOCUMENT_SIGNING.md`](SOURCE_DOCUMENT_SIGNING.md).
- **No secrets in the published config.** The served document is public and cacheable, so validation
  rule 32 (publish-blocking) rejects credential-like material: hard-denied header names `cookie`,
  `set-cookie`, `proxy-authorization`; sensitive-name **static** headers (`authorization`, `x-api-key`, `api-key`,
  `x-auth-token`, any name containing `token`/`secret`/`password`) are allowed **only** when the value
  is on the explicit public-placeholder allowlist (`kira.validation.public-header-placeholder-values`,
  default exactly `["Bearer null"]` — the literal placeholder the real bundled document requires); URLs
  (`baseUrl`, `imageBase`, `icon.remoteUrl`) must be real absolute URIs with **no user-info**
  (`https://user:pass@host` → rejected), no fragment, valid port. *Every value published in a
  `SourceConfig` is public application configuration — never place credentials, cookies, tokens, or
  private API keys in it.* Header names must also be ASCII RFC field-name tokens with no surrounding
  whitespace; invalid names are publication-blocking `HEADER_NAME_INVALID` findings before sensitive-
  name evaluation. The same name classifier applies to `target=header` filter parameters, but
  **all sensitive header-target filters are unsupported**, even when optional, hidden, empty or
  configured with `Bearer null`. The static placeholder allowlist never exempts a dynamic filter's
  default, options, CSV delimiter or toggle wire values. This gate matches the consumers' filter-name
  policy; their pre-existing static-header policy is not made identical to the Backend's value gate.
  Filter option/default/enumerable-condition findings use fixed value-free messages and retain
  collect-all validation. Structural identifiers remain in paths; arbitrary-field secret detection
  is not promised. Invalid admin drafts deliberately retain authored content, but cannot be newly
  published. Whole imports fail before writes, and failed editor quick-publish rolls back its new
  revision. Historical public bytes and stored findings are not rewritten: installed-cache cleanup,
  exposure assessment and any credential rotation remain external operations, not automatic recall.
- **High-impact Source Admin Studio publication is password-stepped-up.** `POST /api/v1/admin/step-up`
  verifies the already-authenticated ADMIN account password through the existing throttled password
  path and returns a 256-bit random proof. The proof is scoped to source mutations, expires after five
  minutes by default (maximum configurable value 15 minutes), and is consumed exactly once. Only its
  SHA-256 hash is stored in `admin_step_up_grants`; the plaintext proof and submitted password are
  never logged. The dashboard keeps both JWT and step-up proof out of browser-readable storage.
- **Multi-source publication is fail-closed and atomic.** An open changeset uses an optimistic ETag.
  Apply consumes a one-time step-up proof, validates every operation before the first mutation, locks
  source heads in stable API order, and commits one catalog snapshot. A failed validation, lifecycle
  transition, stale version, or persistence error rolls back every source, revision, order, snapshot,
  and audit mutation.
- **Source preview is deterministic and cannot act as an SSRF proxy.** The ADMIN supplies inert
  response bytes to the pinned shared engine. The backend records the generated request only in the
  response as URL, method, header names, and body-presence flags; it never sends that request, returns
  header values, or logs fixture contents. Source and response fixture sizes are bounded.

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
provider endpoint, API key and nonblank `kira.completion.default-model` of at most 128 JVM UTF-16
units. The native environment key is **`KIRA_COMPLETION_DEFAULTMODEL`**, not an additional YAML
alias. Service construction validates the default before allocating its executor, including outside
production. Disabled completion needs no default model or provider credentials. Echo and its
configured `echo-1` default exist only in explicit `dev`/`test` profiles; there is no implicit
production default. Null/blank request models use the configured value; nonblank request overrides
and configured defaults are preserved exactly, not trimmed or normalized. Operators must verify
model availability/authorization with their provider separately; startup does not query a catalog.

Admission checks per-user/global minute limits, a per-user daily quota, and logical concurrent permits
before the bounded executor. Multiple instances require Redis; single-instance memory coordination
must be declared explicitly. Redis retains its existing fixed per-counter windows and earlier-counter
charges when a later dimension rejects; this is not an atomic all-or-nothing rolling-window claim
(Backend14 remains separate). Rate/quota rejection stays 429 with Retry-After 60/86400; concurrency
capacity stays 503/1, and indeterminate acquisition stays 503/5. Release failures remain visible 429/5
and are not retried locally; preserving an already committed caller response is still Backend15 work.

The queue timeout covers queue wait plus RUNNING persistence through invocation authorization;
the separate provider timed wait begins only after that commit and authorization. Startup at the
original deadline or later is rejected. PENDING→RUNNING, PENDING/RUNNING→FAILED and RUNNING→SUCCEEDED
are checked conditional writes; one terminal status/outcome wins atomically. Pre-authorization
cancellation prevents provider entry even if startup ignores interruption. Later Future cancellation
requests interruption, not confirmed worker or remote termination; permit close is not such proof
either. Database failure can still delay/prevent terminal persistence. GET/list use one read-only
REPEATABLE READ snapshot across request and outcome queries, not a promise of the freshest data.
Prompt/result sizes, executor threads, queue capacity, limits, timeouts, retention, and cleanup batch
size are bounded configuration. Separate admission/release and physical-lifetime obligations remain.

Redis concurrency counts **unexpired, unreleased UUIDv4 token leases**, not physical provider calls.
The existing `kira:completion-admission:concurrency` key is a sorted set of tokens/deadlines. Acquisition
validates state before writes, prunes deadlines `<= Redis TIME`, and reserves only a new token. Release
removes only its captured token; missing/expired/replayed tokens cannot decrement successor permits.
The allowance is twice queue-plus-provider timeout, with positive whole-millisecond durations and
checked exact arithmetic; timestamps/deadlines are limited to `2^53 - 1`. The **Redis-only** capacity
guard is **1..4096** (default8), bounding token inspection; it neither clamps configuration nor changes the
memory backend. Key expiry covers the greatest member deadline, even across different valid lease
durations. Wrong types, malformed tokens/scores, too many members, or missing/short key expiry deny
without destructive repair. Unknown replies deny, never authorize speculative anonymous release.

**Provider-lifetime obligation remains unresolved.** Queue/provider waits do not bound the earlier
`createPending` database work; an acquired lease can expire before submission. Backend12 prevents
authorization after its startup deadline or a recorded pre-authorization cancellation, but that is not
a Redis-lease validity check. A timely authorized worker can still be paused before provider entry or
continue after release/expiry. Caller timeout requests `Future.cancel(true)` and then exits `use`
without joining actual worker termination, so release can precede termination even before lease
expiry. Redis cannot distinguish a crashed holder from a paused/live one or stop remote work.
Backend12's terminal-state/startup guards alone do not prove a physical concurrency cap. A strict
physical-provider bound still requires a connected termination/fencing and late-start/early-release
design, review and validation; this logical protocol is no waiver or enablement approval. Completion
remains disabled by default.

**Redis assumptions and stopped/drained cutover:** all completion nodes need one shared, non-evicting
Redis authority (`noeviction` or equivalent), consistent capacity/protocol, and controlled clock/state
continuity. These multi-key scripts do not support Redis Cluster or guarantee bounds through arbitrary
clock jumps, state loss or asynchronous failover. Redis 7+ is required; the fixture uses 7.4.7. A legacy
integer is rejected, not imported/deleted, and there is no parallel old/new semaphore namespace.
Stop admission and every old writer, drain/resolve actual outstanding work, and prevent old-version
restarts before cutover. Waiting one TTL is not a verified drain. Any confirmed-stale-state reset or
rollback requires an explicit stopped/drained operational procedure; installed enforcement remains
external. Auth coordination keys and scripts are unaffected.

## Retention & privacy

- **Completion data** (`completion_requests.prompt`, `completion_results.result`/`error`) is the only
  place prompts/results live. The scheduled bounded retention job expires stale in-flight requests and
  deletes terminal prompt/result rows older than `kira.completion.retention` (default seven days).
  One stable ordered batch holds request locks before outcome writes/deletes; concurrent publishers
  cannot invert that lock order or recreate a deleted request. This is not a historical-data repair.
  Prompt/result contents never appear in audit rows or logs. Provider credentials / `Authorization`
  are never logged.
- **Audit rows** (`audit_log.detail`, jsonb) contain **identifiers, revision numbers, and checksums
  only** — never config bodies, header values, completion prompts/results, or passwords. This is enforced
  structurally: the audit encoder accepts only scalar values (String/Int/Long/Boolean/null) and throws
  if handed an object body. `USER_PASSWORD_RESET` records actor + target only.
- **Failed-login audit identifiers:** new `LOGIN_FAILED` rows use `entity_type = login_identifier`,
  null actor, empty detail, and `entity_id = email-sha256-v1:<full lowercase 64-hex SHA-256 of the
  submitted normalized UTF-8 email>` (80 characters), identically for unknown, wrong-password and
  disabled-account failures. This unkeyed fingerprint is dictionary-guessable, correlatable personal
  data — **not anonymity, encryption, authentication or identity proof**. Restricted audit access and
  retention obligations are unchanged; the fingerprint represents the submitted identifier, not every
  alias for an account.
  Legacy `LOGIN_FAILED` / `user` / `<raw normalized identifier>` rows remain unchanged, alongside user
  mutation rows using `user` / `<UUID>`; no schema migration or historical rewrite is performed.
  Consumers must distinguish `(action, entity_type, entity_id)`, not a prefix heuristic (a legacy raw
  identifier may itself look like `email-sha256-v1:…`). The audit API only paginates; operators/export
  consumers must account for both namespaces across cutover, including historical raw personal data.
- **Payload integrity and authenticity.** `X-Config-Checksum` and the ETag detect corruption but are
  not trust roots. Every new snapshot is authenticated by an Ed25519 detached signature over
  versioned metadata and the exact canonical bytes. The app selects an in-binary pinned X.509 public
  key by key id, verifies the signature/checksum/chain, and rejects replay or rollback. Every running
  profile refuses missing or mismatched signing material; production private keys remain secret-manager-only.
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
