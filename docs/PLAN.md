# kira-backend — Architecture and implementation record

> This began as the implementation plan and is retained as the detailed architecture record.
> Production-hardening changes are recorded in `PRODUCTION_WORK_LOG.md`; all adversarial findings are
> dispositioned in `REVIEW_RESOLUTION_2026-07-18.md`. The current implementation includes shared
> Redis admission, a production HTTPS completion provider, signed delivery, bounded iterative
> validation, deployment automation, and the sibling application's fail-closed remote client.

**Status:** Implemented and production-hardened for release `1.0.0` (2026-07-19). Sections that retain
phase language describe construction order, not missing functionality.
**Revision:** REVIEWED & AMENDED (2026-07-11, second-pass review). 24 proposed amendments were adjudicated against the real mobile-app code; the outcomes are recorded in **Appendix A** at the end of this document. This revision supersedes the first draft wherever they differ. **THIRD-PASS final consistency review (2026-07-11):** 9 implementation-level findings + 7 smaller consistency items were verified against the mobile-app code and PostgreSQL/Spring-Security/Flyway semantics and incorporated; outcomes in **Appendix B**. **FOURTH-PASS pre-implementation amendments (2026-07-11):** 4 owner-directed amendments (publish-state rules, completion error taxonomy, logging & diagnostics, trusted client-IP resolution for throttling) incorporated; outcomes in **Appendix C**. Where appendices differ, the latest appendix (and the amended body text) wins.
**Target location:** `/Users/abdelrahman/Projects/kira/kira-backend/` — a standalone Gradle project.
**Mobile parity reference:** `/Users/abdelrahman/Projects/kira/Kira manga` — the production-hardening
campaign updated its signed remote-delivery integration on a separate app branch; backend and app
remain independent repositories and builds.

## Hard constraints (restated up front — non-negotiable)

1. **JWT signing secret and ALL secrets come from env/config** (`KIRA_JWT_SECRET`, DB credentials, admin seed credentials). Never hardcoded, never committed. `application.yml` holds only `${ENV_VAR}` placeholders and dev-profile defaults that are obviously non-production.
2. **Passwords are BCrypt-hashed.** No plaintext, no reversible encoding, anywhere (including tests — tests hash too).
3. **No real secrets committed.** `docker-compose.yml` may carry a throwaway local dev password (`kira`/`kira` style) clearly marked local-only; `.gitignore` excludes `.env`.
4. **The completion provider is an abstraction.** Controllers never select providers. Echo is
   dev/test-only; production uses the configured credential-bearing HTTPS adapter or keeps completion
   disabled.
5. **Standalone project.** The backend has no build dependency on the mobile app. Cross-project
   contract work is implemented and tested in each repository independently.
6. **Remote delivery fails closed.** The app verifies the exact signed envelope with pinned Ed25519
   public keys and falls back to its last verified cache or bundled document.

### Source-catalog v2 amendment (2026-07-23; overrides older whole-document assumptions)

- Public artifacts contain **generic sources only**. `WITHHELD` is a sixth, server-only lifecycle:
  admin-visible and publishable for reviewed generic revisions, but absent from all public routes.
- The catalog-v2 application bundle is revision **5**. The backend's
  `kira.config.bundled-revision-floor` default is therefore **5**; older revision-4/default-4
  statements later in this historical record are superseded.
- The initial reviewed cutover is exactly 12 active generic sources and 33 withheld legacy sources.
  The dry-run/confirmed admin operation validates the exact inventory and performs one audited,
  globally locked publication transaction.
- `GET /api/v2/source-config/manifest` serves exact signed `kcj-1` manifest bytes with strong ETag/304.
  Entries commit to immutable per-source revisions, checksums, order, lifecycle, engine, key id, and
  detached source signature. Removed v2 sources are identity-only tombstones.
- `GET /api/v2/source-config/sources/{api}/revisions/{revision}` serves a lifecycle-neutral immutable
  generic source revision only if that exact tuple appeared in a public v2 manifest. Draft, legacy,
  withheld-only, and guessed revisions return 404.
- v1 document materialization and v2 manifest materialization share the existing catalog revision,
  global publication lock, transaction, and latest pointer. The pointer moves only after both exact
  artifacts and their mappings are durable.
- Existing paragraphs that describe legacy sources as publicly served or the app merging a missing
  remote source back from the bundle, or that name `RemoteSourceConfigManager` as the shipping
  client, are superseded by this amendment.

---

## 1. Overview & goals

kira-backend is a Spring Boot / Kotlin / PostgreSQL service that is the remote authority for the Kira
Manga app's **source configuration**. It authors and validates the mirrored `SourceConfigDocument`
contract, publishes immutable signed revisions, serves exact canonical bytes with ETag/checksum
support, manages source lifecycle and rollback, and records an audit trail. It also provides JWT
ADMIN/USER authorization and an optional authenticated completion service behind a provider
abstraction with shared admission controls. The app's bundled document remains its offline trust and
availability floor. Remote documents are semantically contract-equivalent and deterministic under
`kcj-1`; byte identity with the hand-authored bundle is not claimed. Invalid or unsigned publication
is impossible in production, and invalid remote delivery cannot replace the app's last good state.

---

## 2. Architecture

Clean layered architecture, one dependency direction, per feature package:

```
api (controllers, request/response DTOs, mappers DTO↔domain)
  ↓
application (transactional services, orchestration, use-case methods)
  ↓
domain (pure Kotlin: models, value objects, repository PORTS as interfaces,
        domain rules — for sourceconfig this includes the `validation` package)
  ↑ implemented by
infrastructure (JPA entities, Spring Data repositories, adapters implementing
                the domain ports, entity↔domain mappers, external clients)
```

Rules stated explicitly (the implementation agent must not violate them):

- **api → application → domain.** `api` never touches `infrastructure` or JPA types. `application` depends on domain ports, never on Spring Data interfaces directly.
- **infrastructure implements domain repository ports.** Domain declares e.g. `SourceConfigRepository` (interface, pure Kotlin); infrastructure provides `JpaSourceConfigRepositoryAdapter` wrapping a Spring Data repository and mapping entities to domain models.
- **DTOs never leak into persistence; entities never leak into controllers.** Three model families per feature: API DTOs (`api/dto`), domain models (`domain`), JPA entities (`infrastructure/entity`). Mapping is explicit (simple Kotlin functions, no MapStruct — the models are small).
- **`sourceconfig/domain` + `sourceconfig/validation` stay framework-free** (kotlin-stdlib + kotlinx-serialization only, no Spring/JPA/Jackson imports) so they can later be extracted into a shared Kotlin module consumed by both backend and mobile app (see §8).
- **Errors**: domain/application throw typed exceptions (`ValidationFailedException`, `InvalidLifecycleTransitionException`, `NotFoundException`, `ConflictException`); a single `@RestControllerAdvice` in `common` maps them to a uniform problem envelope (RFC-9457 `application/problem+json` style: `type`, `title`, `status`, `detail`, `errors[]`).
- **Serialization split (deliberate):** Spring MVC / API DTOs use Jackson (+ `jackson-module-kotlin`, the Boot default). The **source-config model uses kotlinx-serialization** — the same library, same data-class-with-defaults style as the app's `sources/contracts/.../model/SourceConfig.kt` — so default-omission behaves identically to the app. The document endpoints return the stored pre-serialized canonical JSON bytes directly (`produces = application/json`, raw-bytes writer, not a String message converter), bypassing Jackson entirely — the bytes served are byte-identical to the bytes checksummed and stored (§5 canonicalization defines those bytes). Inbound source-config parsing has **two modes** (§7): a STRICT authoring parser for admin endpoints and a COMPATIBILITY parser for bundled import.

---

## 3. Package / module structure

Single Gradle module (no multi-module yet — extraction is a future step; the package discipline above keeps it cheap):

```
kira-backend/
  build.gradle.kts            # Kotlin DSL; Spring Boot; java 21 toolchain
  settings.gradle.kts         # rootProject.name = "kira-backend"
  gradle/ , gradlew, gradlew.bat
  docker-compose.yml          # local postgres:17-alpine, port 5433 (avoid clashing with any local 5432)
  .gitignore                  # .env, build/, .gradle/, *.iml, .idea/ (keep runConfigs out)
  README.md
  docs/
    PLAN.md                   # this file
    API.md, SOURCE_CONFIG_LIFECYCLE.md, SECURITY.md, LOCAL_DEV.md,
    MIGRATION_BUNDLED_TO_REMOTE.md          # written in Phase 10 (docs phase)
  src/main/kotlin/me/manga/kira/backend/
    KiraBackendApplication.kt
    config/                   # Spring config: OpenAPI bean, Jackson tweaks, async/clock beans,
                              # @ConfigurationProperties (KiraSecurityProperties, KiraCompletionProperties,
                              # KiraAdminSeedProperties)
    common/                   # ApiError/problem envelope, GlobalExceptionHandler, typed app exceptions,
                              # PageResponse<T>, Sha256 util, CanonicalJson (the kotlinx Json instance)
    security/                 # SecurityFilterChain config, JwtService (issue), JwtDecoder wiring (verify),
                              # Roles (ADMIN, USER), DB-backed jwtAuthenticationConverter (per-request
                              # enabled-check + DB-derived authorities — §6) + CurrentUser context resolver,
                              # PasswordEncoder bean (DelegatingPasswordEncoder), AdminSeeder (ApplicationRunner),
                              # AuthThrottleService (in-memory login/registration throttling — §6)
    user/
      api/                    # AuthController (register/login/me), AdminUsersController (§4.4), dto/
      domain/                 # User, Role, UserRepository (port), exceptions
      application/            # UserService, AuthService, UserAdminService
      infrastructure/         # UserEntity, SpringDataUserRepository, adapter
    sourceconfig/
      api/                    # public: SourceDocumentController, SourcesController
                              # admin: AdminSourcesController, AdminDocumentsController; dto/
      domain/                 # model/ (the mirrored config data classes — §7),
                              # SourceLifecycleStatus, RevisionStatus, state machine (§9),
                              # ports: SourceConfigRepository, RevisionRepository,
                              #        PublishedDocumentRepository, ValidationResultRepository
      validation/             # PURE: SourceConfigValidator, ValidationError, ValidationResult,
                              # StrategyCatalog (server mirror of DefaultStrategyRegistry), rule impls (§8)
      application/            # SourceAdminService (draft/validate/publish/lifecycle/rollback/import),
                              # DocumentAssemblyService (materialize snapshot, checksum, revision counter),
                              # SourceQueryService (public reads)
      infrastructure/         # entities, Spring Data repos, adapters, jsonb converters
    completion/
      api/                    # CompletionController, dto/
      domain/                 # CompletionRequest, CompletionStatus, CompletionProvider (port),
                              # CompletionRepository (port)
      application/            # CompletionService
      infrastructure/         # EchoCompletionProvider, entities, repos, adapters
    audit/
      domain/                 # AuditEntry, AuditRepository (port)
      application/            # AuditService (called by mutating admin/auth services)
      infrastructure/         # entity + repo
  src/main/resources/
    application.yml           # common config; ${ENV} placeholders
    application-dev.yml       # local dev conveniences (docker-compose DB coords)
    db/migration/             # Flyway SQL (§5)
  src/test/kotlin/me/manga/kira/backend/
    ...mirrors main packages; support/ (Testcontainers base class, JWT test helpers, fixture JSON)
    resources/fixtures/       # valid-document.json, invalid-*.json, bundled-sample.json (trimmed),
                              # bundled-full.json (the COMPLETE real CONFIG_BACKED_SOURCES_JSON — §11 FullBundledParityIT)
```

**Stack pins:** Spring Boot **3.5.x line** (do NOT auto-switch to 4.x just because it is newer — any major upgrade is a deliberate separate change with a full test pass), Kotlin **2.1+** (with `kotlin("plugin.spring")` and `kotlin("plugin.jpa")`), Java **21 toolchain**, kotlinx-serialization-json **1.7+** (+ `kotlin("plugin.serialization")`), springdoc-openapi-starter-webmvc-ui **2.x**, Flyway (`flyway-core` + `flyway-database-postgresql`), PostgreSQL driver, Spring Data JPA, spring-boot-starter-security + **spring-security-oauth2-resource-server + oauth2-jose** (Nimbus — JWT without a third-party lib), spring-boot-starter-validation, **spring-boot-starter-actuator** (health/liveness/readiness — §6), Testcontainers (postgresql + junit-jupiter), spring-security-test.

**Version pinning rule (Phase 1 deliverable):** the ranges above exist only to *select* versions once. During scaffold the implementation agent verifies mutual compatibility, then records the **exact** chosen versions (Gradle wrapper, Spring Boot, Kotlin, kotlinx-serialization, springdoc, Testcontainers, PostgreSQL Docker image tag — e.g. `postgres:17.5-alpine`, optionally by digest — and the Java toolchain version) in `gradle/libs.versions.toml` + README so builds are reproducible. After Phase 1, versions change only as deliberate commits, never by floating ranges.

**Why Flyway over Liquibase (brief):** plain versioned SQL files reviewable in diffs, zero XML/YAML changelog indirection, first-class Spring Boot autoconfig, and we only ever roll forward (new migration to undo), so Liquibase's rollback/abstraction machinery buys nothing here.

---

## 4. API design

All endpoints under `/api/v1`. Errors use the problem envelope from §2. Pagination: `?page=0&size=20`, response `PageResponse{items, page, size, total}`.

### 4.1 App-facing (public, read-only, no auth)

| Method & path | Purpose | Notes / status codes |
|---|---|---|
| `GET /api/v1/source-config/document` | **The** app document — **latest** published snapshot, exact stored canonical `SourceConfigDocument` bytes (§5/§7). Query: `appVersion` (optional, validated semver-ish, recorded; **no filtering in v1** — see Open Q6). **No public historical retrieval**: the app only ever needs the latest document plus its own cached previous-good (verified: `RemoteSourceConfigManager.refresh()` fetches only the latest, no revision parameter); historical snapshots are admin-only (`GET /admin/documents/{revision}`) — this avoids permanently exposing retired/removed config and makes emergency un-publication of an accidentally published secret possible. | 200 with headers `ETag: "<sha256-hex>"` (strong, quoted, = document checksum), `Content-Type: application/json; charset=UTF-8`, `Cache-Control: public, max-age=300, no-transform`, `X-Content-Type-Options: nosniff`, `X-Config-Revision: <n>`, `X-Config-Checksum: <sha256>`. `If-None-Match` match → **304** (no body). No document published yet → 404. |
| `GET /api/v1/source-config/document/meta` | Cheap poll: `{revision, schemaVersion, checksum, publishedAt}` of latest. | 200; 404 if none. Lets the app check "is there anything newer" without the body. |
| `GET /api/v1/sources` | Summaries of sources in the current document: `{api, displayName, language, engine, lifecycle, siteState, adult, baseUrl, iconRemoteUrl, revisionNumber, publishedAt}`. Query: `?lifecycle=active,disabled` (comma-separated multi-value; default = everything in the document incl. retired-as-removed), `?engine=`. Ordered by the normative document order (§5 source ordering: `position ASC, api ASC`). | 200. Draft-only and removed sources never appear. |
| `GET /api/v1/sources/{api}` | The single published `SourceConfig` stanza (exact app shape), **consistent with the document content**: `active` → 200 (`lifecycle:"active"`); `disabled` → 200 (`lifecycle:"disabled"`); `retired` → **200 with `lifecycle:"removed"`** (the stanza is still in the served document during the grace window — returning 410 while the document still carries it would be self-contradictory); `removed` → **410 Gone**; unknown/draft-only → 404. | 200 / 404 / 410 as listed. |

**ETag semantics (normative):** strong quoted ETags (`ETag: "a1b2…"`); `If-None-Match: *` matches when any document exists → 304; a comma-separated `If-None-Match` list is parsed and each entry compared (strong comparison, quotes stripped); **weak validators never strongly match**: an entry of the form `W/"…"` fails RFC 9110 §8.8.3.2 strong comparison even when the opaque hash is identical → 200 with full body; match → 304 **with no body** and the same ETag/Cache-Control headers; the checksum is computed over the **exact UTF-8 bytes sent** (the stored canonical bytes, written raw — never re-serialized by a message converter). The `X-Config-Checksum` header is a **corruption check only, not authenticity** — a hash delivered beside the same payload cannot authenticate it; authenticity = HTTPS today + the future detached signature (§9).

### 4.2 Auth

| Method & path | Auth | Request → Response | Codes |
|---|---|---|---|
| `POST /api/v1/auth/register` | none (gated by `kira.auth.registration-enabled`, default `true` dev / `false` prod — prod onboarding is via `/admin/users`, §4.5) | `{email, password}` → `{id, email, role:"USER"}`. Password policy: **min 15 chars, max 72 UTF-8 bytes** (BCrypt input limit — documented, enforced, never silently truncated), no composition rules, no trimming/normalization of the password (email IS trim+lowercased). | 201; 409 duplicate email (case-insensitive); 400 policy violation; 403 registration disabled; **429** registration throttle (per-IP) |
| `POST /api/v1/auth/login` | none | `{email, password}` → `{accessToken, tokenType:"Bearer", expiresInSeconds, role}` | 200; 401 bad credentials (identical generic message for unknown-user / wrong-password / disabled account); **429** when throttled (per normalized email AND per IP — §6) |
| `GET /api/v1/auth/me` | Bearer (USER or ADMIN) | → `{id, email, role, createdAt}` | 200; 401 |
| `POST /api/v1/auth/refresh` | **NOT registered in v1** — no handler, no 501 stub: requests get the standard 404 handling. OpenAPI/docs list it as planned-not-implemented. (§6 refresh seam.) | — | — |

### 4.3 Admin — source management (all `ROLE_ADMIN`; every mutation writes `audit_log`)

| Method & path | Purpose | Codes |
|---|---|---|
| `POST /api/v1/admin/sources` | Create a source: body = full `SourceConfig` JSON (its `api` is the identity). Parsed with the **STRICT authoring parser** (§7 — unknown keys, duplicate keys, malformed JSON all → 400). Then the **Tier-1 structural gate** (§8 two-tier model) runs BEFORE any row is created — violations → 400, **nothing persisted**: `api` blank/over-length/unsafe (`API_IDENTIFIER_INVALID`), payload `lifecycle` not the neutral default `"active"` (`LIFECYCLE_NOT_AUTHORABLE` — lifecycle is server-managed, §9), identity/denormalized field over DB column limits (`FIELD_TOO_LONG`). Only past that gate: creates the `source_configs` row (status `draft`, `position` = current max+1 → appended to the document order, §5) + revision 1 (`draft`), runs Tier-2 validation immediately, stores the result (invalid drafts ARE stored — inspectable), returns it inline: `{api, status, revisionNumber, validation:{valid, errors[], warnings[]}}`. | 201 (even when Tier-2-invalid — the stored result reports it); 409 api already exists; 400 unparseable/strict-parse-rejected/structural-gate JSON |
| `GET /api/v1/admin/sources` | All sources incl. drafts/retired/removed; filter `?status=`. | 200 |
| `GET /api/v1/admin/sources/{api}` | Full admin view: current status, current published revision no., latest revision no., timestamps. | 200; 404 |
| `POST /api/v1/admin/sources/{api}/revisions` | New draft revision (body = full `SourceConfig`, **STRICT authoring parser**, then the same **Tier-1 structural gate** as create — nothing persisted on a gate failure). `body.api` **must equal** the path `{api}` → mismatch is 400 `API_ID_MISMATCH` (Tier-1); the api identity is immutable after creation. Payload `lifecycle` must be `"active"` (else 400 `LIFECYCLE_NOT_AUTHORABLE`, Tier-1). Revision number allocated **under a `SELECT … FOR UPDATE` lock on the source head row** (§5 — concurrent creations must not collide). Auto-validates (Tier-2) + stores result, returns it. | 201; 404; 400 |
| `GET /api/v1/admin/sources/{api}/revisions` | Revision list: `{revisionNumber, status, checksum, createdBy, createdAt, publishedAt, valid}`. | 200; 404 |
| `GET /api/v1/admin/sources/{api}/revisions/{n}` | Full stored config JSON of that revision + metadata. | 200; 404 |
| `POST /api/v1/admin/sources/{api}/revisions/{n}/validate` | Re-run validation (validation preview — no state change beyond storing the result). Returns `{valid, errors:[{code, path, message}], warnings:[{code, path, message}]}`. Also validates the source **in candidate-document context** (unique-api etc., §8). | 200 (even when invalid — the *result* reports invalid); 404 |
| `GET /api/v1/admin/sources/{api}/revisions/{n}/validation` | Latest stored validation result for the revision. | 200; 404 |
| `POST /api/v1/admin/sources/{api}/revisions/{n}/publish` | Publish: validation MUST pass (server re-validates inside the same transaction — a stale stored "valid" is not trusted). Marks revision `published` (previous published one → `superseded`), sets `publishedAt`, **materializes a new document snapshot under the global publication lock** (§9). **Status effect (explicit, no implicit re-enable):** `draft` + first valid publish → `active`; `active` + publish → **remains `active`**; `disabled` + publish → **remains `disabled`** (publishing content never re-enables); `retired`/`removed` + publish → **409 forbidden**. **Publishable revision states (normative, §9):** the currently-`published` revision again → **200 idempotent no-op** (no new snapshot); a `superseded` revision → **409 `REVISION_SUPERSEDED`** (restoring old content ALWAYS goes through `rollback`, which copies it into a new higher revision); a `draft` revision → publishable **only when its `revision_number` is greater than the currently published revision number** for that source (an older draft → 409 `REVISION_OLDER_THAN_PUBLISHED` — same restore-via-rollback rule). Supersede-then-publish ordering inside the transaction keeps the one-published-per-source partial unique index valid at every statement (§9). Concurrent publishes for the same source serialize on the §9 locks — exactly one deterministic winner. | 200 with new `{documentRevision, checksum}` (or 200 no-op); **422** with `errors[]` if invalid; 409 retired/removed/superseded/older-draft; 404 |
| `POST /api/v1/admin/sources/{api}/disable` | `active → disabled`. Stanza stays in the document with `lifecycle:"disabled"`. New snapshot. Repeating on an already-`disabled` source → 409 (strict state machine, not idempotent — a 409 tells the operator their mental model of current state is wrong). | 200; 409 invalid transition |
| `POST /api/v1/admin/sources/{api}/enable` | `disabled → active`; also `retired → active` **for `engine="generic"` sources only** (un-retire — §9 explains why legacy is excluded, with app evidence). Repeat on `active` → 409. New snapshot. | 200; 409 |
| `POST /api/v1/admin/sources/{api}/retire` | `disabled → retired` **only** (direct `active → retired` is 409 — the mandatory soft-disable stage is enforced, honoring the "no silent deletion: disabled in the document before ever dropped" contract in §12.4). Stanza stays in document as `lifecycle:"removed"` (app vocabulary — §9 mapping). New snapshot. | 200; 409 |
| `POST /api/v1/admin/sources/{api}/remove` | `retired → removed` (terminal). Stanza dropped from the document entirely. New snapshot. Body `{confirm: "<api>"}` required (foot-gun guard). | 200; 409 (must pass through `disabled` then `retired` first) |
| `POST /api/v1/admin/sources/{api}/rollback` | Body `{toRevision: n}`. Copies revision *n*'s **content** into a **new** revision (number = latest+1), validates, publishes it. History is never mutated; revision numbers only grow. **Rollback copies content only — it does NOT restore the source's server lifecycle from that era** (status follows the publish rules above: active stays active, disabled stays disabled). | 200 with `{newRevisionNumber, documentRevision}`; 422 if the old config no longer validates (rules may have tightened); 409 retired/removed; 404 |
| `GET /api/v1/admin/documents` | Published document snapshots: `{documentRevision, schemaVersion, checksum, sourceCount, createdBy, createdAt}`. | 200 |
| `GET /api/v1/admin/documents/{revision}` | **Body = the raw stored canonical bytes of that snapshot** (same raw-bytes writer as the public endpoint — never re-serialized), metadata in headers only (`ETag: "<checksum>"`, `X-Config-Revision`, `X-Config-Checksum`). Deliberately NOT a JSON metadata envelope — the list endpoint above is the metadata view; wrapping would break the serve-stored-bytes/checksum guarantee. | 200; 404 |
| `POST /api/v1/admin/documents/validate` | Validate the **candidate** document (assembled from current published revisions + lifecycle states) without publishing — whole-document preview. | 200 `{valid, errors[]}` |
| `POST /api/v1/admin/documents/republish` | Force-materialize a new snapshot from current state (recovery / after canonicalization changes). **Always creates a new snapshot with a new document revision, even when the canonical content is unchanged** — that is its purpose (deliberate recovery tool; the caller decides). | 200 |
| `POST /api/v1/admin/sources/import-bundled` | Body = the app's bundled document JSON (`CONFIG_BACKED_SOURCES_JSON` contents). **Fully specified semantics in §12.2 (bundled-import contract)** — parses with the COMPATIBILITY parser (§7), validates the whole document, applies per-source create/update/no-op with server-controlled revisions (content stored **lifecycle-neutral**, §9), never replaces/publishes an existing draft-only source (`skippedDraft`), and materializes exactly ONE snapshot when published state changes, all-or-nothing. Response: `{created[], updated[], unchanged[], skippedRemoved[], skippedRetired[], skippedDraft[], lifecycleConflicts[], warnings[], documentRevision?}`. Request-body size limit 5 MiB (the real document is well under 1 MiB today). This is the migration on-ramp (§12). | 200 (incl. the no-op case); 422 with full error list |

### 4.4 Admin — user management (all `ROLE_ADMIN`; minimal surface, NOT an IdM platform; every mutation audited)

Prod onboarding mechanism (registration is disabled in prod): admins create users. This is the concrete API backing that statement.

| Method & path | Purpose | Codes |
|---|---|---|
| `POST /api/v1/admin/users` | `{email, password, role}` → create user (password policy of §4.2 applies; email trim+lowercased, case-insensitively unique). Response never echoes the password. | 201; 409 duplicate; 400 |
| `GET /api/v1/admin/users` | Paginated list: `{id, email, role, enabled, createdAt}` — **no password material, ever**. | 200 |
| `POST /api/v1/admin/users/{id}/enable` | Re-enable a disabled user. | 200; 404 |
| `POST /api/v1/admin/users/{id}/disable` | Disable: user can no longer log in; in-flight tokens die at the next request because the authentication pipeline re-checks `enabled` (§6). **Guard: refuses (409) to disable the last enabled ADMIN** — recovery from an all-admins-disabled state would otherwise require manual SQL. The guard is **serialized via the `security_state` singleton row lock** (§5): a bare count-then-disable check is racy — two concurrent transactions each observe 2 enabled admins and disable different ones → zero. Enable/disable (and any future role mutation) lock `security_state FOR UPDATE` first, then count. `ConcurrentLastAdminDisableIT` proves it. | 200; 404; 409 last-admin guard |
| `POST /api/v1/admin/users/{id}/reset-password` | `{newPassword}` (policy-checked). Explicit, audited (`USER_PASSWORD_RESET` — the audit row records actor + target, never the password). | 200; 404; 400 |

### 4.5 Cross-cutting HTTP contract (normative)

- **Pagination:** `?page=0&size=20`; `size` max **100** (larger → 400), `page`/`size` negative or non-numeric → 400. Applies to every paginated endpoint.
- **Request-body limits:** a pre-MVC replayable-body filter enforces default max **256 KiB** and
  `import-bundled` max **5 MiB** for declared or streamed/chunked bodies; completion prompt over its max
  length → **413** (one consistent status, not sometimes-400).
- **Multi-value filters** (`?lifecycle=`): comma-separated within a single query param; unknown enum value → 400.
- **Responses:** JSON endpoints send `Content-Type: application/json; charset=UTF-8`; the public document endpoints add `Cache-Control: public, max-age=300, no-transform` + `X-Content-Type-Options: nosniff`; error responses use the §2 problem envelope and never echo submitted config/header/password values back verbatim.

### 4.6 Completion (authenticated: USER or ADMIN)

| Method & path | Purpose | Codes |
|---|---|---|
| `POST /api/v1/completions` | `{prompt, model?}` → creates `completion_requests` row (`PENDING`), invokes the configured `CompletionProvider` **outside any DB transaction** (§10 — three short transactions, provider call between them), persists sanitized result/error (`SUCCEEDED`/`FAILED`), returns `{id, status, model, provider, result?, errorCode?, error?, createdAt}` — on failure both `errorCode` (stable §10 catalog value, e.g. `PROVIDER_TIMEOUT`) and `error` (sanitized bounded generic message) are set; on success both are null. Prompt max length via property (default 8 000 chars → **413** over); a supplied model over the 128-character persistence limit is rejected before storage with 400 `MODEL_TOO_LONG`. Provider invocation has a timeout (`kira.completion.timeout`, default 30s), a bounded executor queue, and a max stored result length; provider exceptions are never surfaced raw to clients (details only in secured server logs, request-id-correlated). | 201; 401 anon; 400 blank prompt/model too long; 413 prompt too large |
| `GET /api/v1/completions/{id}` | Fetch one — **owner or ADMIN only** (others → 404, not 403, to avoid ID probing). | 200; 404 |
| `GET /api/v1/completions` | List caller's own requests, paginated, newest first. ADMIN may pass `?userId=`. | 200 |

---

## 5. Persistence model

PostgreSQL 17. Flyway migrations in `src/main/resources/db/migration/`, naming `V<version>__<snake_case_description>.sql`:

```
V1__users.sql
V2__source_config.sql
V3__published_documents.sql
V4__audit_log.sql
V5__completions.sql
```

**Migration order matches build order (deliberate):** the phased build (§15) creates audit in Phase 6 and completions in Phase 9 — audit therefore MUST have the lower version number, or a fresh environment migrated mid-campaign would see V5 applied before V4 exists (Flyway out-of-order hazard). `outOfOrder` stays **false** (default); versions are appended strictly in build order. `FlywayMigrationIT` asserts the history contains exactly V1..V<latest> in order.

**Document-vs-per-source decision (justified):** authoring truth is **per-source revisions** (`source_configs` + `source_config_revisions`) because every admin operation (draft, validate, publish, rollback, lifecycle) is per-source and needs per-source history; the served artifact is a **materialized whole-document snapshot** (`published_documents`) because (a) the app consumes one `SourceConfigDocument` with one monotonic `revision` and one checksum → stable strong ETag requires stable bytes, (b) serving stored canonical bytes makes what-the-app-got auditable and reproducible per revision, (c) whole-document anti-rollback (`revision` must only grow — the app's `RemoteSourceConfigManager` rejects any document with `revision <` accepted floor) becomes a single DB sequence. Assembling on the fly from per-source rows would make ETag/checksum recomputed-per-request and history unreproducible.

**Canonicalization (load-bearing, fully specified):** Postgres `jsonb` does NOT preserve key order/whitespace, so checksums are never computed from `jsonb` round-trips — in fact **no `jsonb` column participates in the canonical path at all** (revisions store canonical TEXT, below). Declaration-order encoding alone is also NOT sufficient: kotlinx parses JSON maps into `LinkedHashMap` (insertion order), so the same semantic config authored with different `headers`/`endpoints`/`fields`/`formBody`/`vars`/transform-`args` key orders would yield different bytes — and an innocent data-class field reorder would silently change every checksum. Canonical form is therefore a deterministic algorithm named **Kira Canonical JSON v1 (identifier `kcj-1`)** — *inspired by* RFC 8785 (JCS) but deliberately NOT claiming full RFC 8785 compliance: the RFC's number-serialization rules are vacuous here (the model has no floating-point fields) and its string-escaping fine print is not re-implemented — kotlinx-serialization's compact escaping is the normative behavior. The identifier is stored with every revision and snapshot (`canon_version` columns) so any future algorithm change is explicit, versioned, and auditable (`republish` is the recovery tool after such a change):

1. Encode the Kotlin model with `CanonicalJson = Json { encodeDefaults = false; prettyPrint = false; explicitNulls = false }` to a `JsonElement` (default-omission identical to the app's bundled style).
2. Recursively **sort every object's keys** lexicographically by Unicode code point (covers both data-class fields and maps — immune to authoring order and to model declaration order).
3. Serialize compact: UTF-8, no insignificant whitespace, no pretty print, no trailing newline.

Number determinism is trivial here — the model contains only `Int`/`Long`/`Boolean`/`String` fields (no floating point). Timestamps the server authors (`generatedAt`) are ISO-8601 UTC with fixed seconds precision (`YYYY-MM-DDThh:mm:ssZ`). Checksum = SHA-256 hex over the canonical UTF-8 bytes. **Document identity is canonical semantic content — NOT the authoring text's whitespace/key order**; re-serializing the bundled document is *not* claimed to reproduce its hand-authored bytes (it will not — the bundled constant is pretty-printed); the guarantee is "contract-equivalent, deterministically canonical, byte-stable": parse(canonical(x)) is semantically equal to parse(x), and canonical(x) is stable across processes and releases. The app parses leniently and key-order-insensitively (`ignoreUnknownKeys = true; isLenient = true` in `SourceConfigParser`), so the key sorting is transparent to it.

**Source ordering (normative — key sorting does NOT order arrays):** `SourceConfigDocument.sources` is a JSON **array**; `kcj-1` sorts object keys only and never reorders arrays, so without an explicit rule the assembled document's bytes/checksum/ETag would depend on nondeterministic DB result ordering. **Verified in the app (2026-07-11): source list order carries real behavioral weight.** Home tabs sort by the DB row's `priority` (`SourcesDao.getAllSources()` = `SELECT * FROM sources ORDER BY priority`, no secondary key; `HomeFeedRepositoryImpl.observeSourceTabs` re-sorts with Kotlin's *stable* `sortedBy { it.priority }`), ALL 45 bundled stanzas omit `priority` (default 0), and rows are seeded by `SourceCatalogSyncRepositoryImpl.syncFromConfig` iterating `document.sources` **in list order** — so on a fresh install the de-facto tab order IS the document's stanza order (equal keys fall back to insertion/rowid order). `ConfigMerger.merge` likewise preserves first-seen list order into the merged document. Therefore: (a) `source_configs` carries an explicit **`position`** column — the single normative order; (b) document assembly and `GET /sources` ALWAYS order by **`position ASC, api ASC`** (api, being unique, keeps the comparator total even if positions ever duplicate) and never rely on DB result order; (c) bundled import assigns `position` from **payload order** for created sources (existing sources keep theirs) — so the imported document serves in exactly the bundled order and the app's observable tab order is preserved; (d) admin-created sources append (`max(position)+1`; concurrent creates may duplicate a position — harmless, see (b)); (e) a reorder endpoint is deliberately future work. `DocumentOrderDeterminismIT` proves identical canonical bytes + checksum when the same source set is returned by the repository in shuffled orders.

### Tables

**`users`** (V1)

| column | type | constraints |
|---|---|---|
| id | uuid | PK, default `gen_random_uuid()` |
| email | varchar(320) | NOT NULL; case-insensitive uniqueness enforced in the DB: `CREATE UNIQUE INDEX uq_users_email_lower ON users(lower(email));` (app layer ALSO trims + lowercases before store — belt and braces) |
| password_hash | varchar(255) | NOT NULL — sized for `DelegatingPasswordEncoder` `{id}hash` format, so the schema is not coupled to BCrypt forever (initial encoder IS `{bcrypt}`; cost calibrated on deployment hardware, not frozen at 10) |
| role | varchar(16) | NOT NULL, CHECK (`role IN ('ADMIN','USER')`) |
| enabled | boolean | NOT NULL default true |
| created_at / updated_at | timestamptz | NOT NULL default now() |

**`security_state`** (V1) — singleton row serializing admin-account mutations (the last-admin guard, §4.4, and any future role-changing)

| column | type | constraints |
|---|---|---|
| id | int | PK, CHECK (`id = 1`) — singleton, seeded by V1 |
| updated_at | timestamptz | NOT NULL |

User enable/disable (and future role mutations) begin with `SELECT … FROM security_state WHERE id = 1 FOR UPDATE`, THEN count enabled admins — concurrent disables serialize, so two transactions can never each see "2 enabled admins" and disable different ones. Deliberately a **separate** lock from `document_publication_state`: (a) user management and config publication are unrelated subsystems — sharing one lock creates false contention; (b) `document_publication_state` doesn't exist until V3/Phase 5 while this guard ships with V1/Phase 3. A `pg_advisory_xact_lock` would be an acceptable equivalent, but the singleton row matches the established schema-visible pattern and needs no global advisory-key registry.

**`source_configs`** (V2) — one row per API id (identity + lifecycle head)

| column | type | constraints |
|---|---|---|
| id | uuid | PK |
| api | varchar(128) | NOT NULL, UNIQUE (`uq_source_configs_api`) — the app-side stable key; uniqueness of api-per-document falls out of this |
| display_name | varchar(256) | NOT NULL |
| language | varchar(32) | NOT NULL |
| engine | varchar(64) | NOT NULL (`generic` / `legacy` / `kotlin:<id>`) |
| status | varchar(16) | NOT NULL, CHECK IN `('draft','active','disabled','retired','removed')` |
| position | int | NOT NULL — the normative document order (§5 source ordering). Assigned `max(position)+1` at creation; bundled import assigns payload order to created sources. Duplicates tolerated (the `(position ASC, api ASC)` comparator stays total via unique `api`); no reorder endpoint in v1 |
| base_url | varchar(512) | NOT NULL (denormalized from current revision for listing) |
| adult | boolean | NOT NULL default false (denormalized: `siteState == 'ADULT_18_PLUS'`) |
| current_published_revision_id | uuid | NULL. **Composite FK so the pointer can never reference another source's revision:** `source_config_revisions` carries `UNIQUE (id, source_config_id)`, and this table declares `FOREIGN KEY (current_published_revision_id, id) REFERENCES source_config_revisions (id, source_config_id)` — a cross-source pointer is unrepresentable. |
| created_at / updated_at | timestamptz | NOT NULL |
| published_at | timestamptz | NULL (first publish) |

Index: `idx_source_configs_status (status)`.

**Circular-FK creation order (V2):** `source_configs` and `source_config_revisions` reference each other. V2 creates both tables **without** the `current_published_revision_id` FK, then adds it via `ALTER TABLE source_configs ADD CONSTRAINT …` at the end of the migration.

**Concurrency (normative):** per-source revision numbers are allocated as max+1 **only under a row lock**: `SELECT … FROM source_configs WHERE api = ? FOR UPDATE` first, then compute the next number — two concurrent revision creations for the same source must serialize, not collide (`ConcurrentSameSourceRevisionIT`). `revision_number` stays `int` (a per-source authoring counter will not approach 2^31; `bigint` would buy nothing).

**`source_config_revisions`** (V2) — immutable per-source history

| column | type | constraints |
|---|---|---|
| id | uuid | PK; also `UNIQUE (id, source_config_id)` (target of the composite FK above) |
| source_config_id | uuid | NOT NULL, FK → source_configs(id) ON DELETE RESTRICT |
| revision_number | int | NOT NULL; UNIQUE `(source_config_id, revision_number)` (`uq_revision_per_source`); strictly increasing per source, assigned as max+1 **under the source-row lock** (above) |
| config_canonical_json | text | NOT NULL — the stanza's **canonical bytes (§5 canonicalization), the immutable source of truth**. NOT `jsonb` (jsonb destroys key order/whitespace → checksum bytes would be unreproducible from storage). There is deliberately **no** parallel `jsonb` projection column: every queryable attribute (displayName, language, engine, baseUrl, adult) is already denormalized onto `source_configs`, so a second representation would only be a divergence risk. Revisions are fetched whole, by id or (source, number). **Content is lifecycle-NEUTRAL (§9 normative rule):** the stanza's `lifecycle` is always the neutral model default `"active"` — which `kcj-1` default-omission renders as the key being **absent** from the stored bytes; the served value is injected only at document assembly |
| checksum | char(64) | NOT NULL — SHA-256 hex of `config_canonical_json`'s UTF-8 bytes; both generated from the same validated parsed model in one operation |
| canon_version | varchar(16) | NOT NULL (= `'kcj-1'` in v1) — the canonicalization algorithm that produced the bytes (§5) |
| status | varchar(16) | NOT NULL, CHECK IN `('draft','published','superseded')` |
| created_by | uuid | NOT NULL, FK → users(id) |
| notes | text | NULL (e.g. "rollback of r7 to r4") |
| created_at | timestamptz | NOT NULL |
| published_at | timestamptz | NULL |

Partial unique index: at most one published revision per source — `CREATE UNIQUE INDEX uq_one_published_per_source ON source_config_revisions(source_config_id) WHERE status = 'published';`
Index: `idx_revisions_source (source_config_id, revision_number DESC)`.

**`source_validation_results`** (V2)

| column | type | constraints |
|---|---|---|
| id | uuid | PK |
| revision_id | uuid | NOT NULL, FK → source_config_revisions(id) |
| valid | boolean | NOT NULL |
| errors | jsonb | NOT NULL default `'[]'` — array of `{code, path, message}` |
| warnings | jsonb | NOT NULL default `'[]'` — array of `{code, path, message}` (advisory, §8 rule 33 — never blocks publish) |
| rules_version | varchar(32) | NOT NULL (e.g. `"schema1/rules-2026.07"` — lets a stored "valid" be recognized as stale after rule changes) |
| validated_at | timestamptz | NOT NULL |

Index: `idx_validation_revision (revision_id, validated_at DESC)`. (History kept; publish always re-validates live.)

**`published_documents`** (V3) — immutable served snapshots

| column | type | constraints |
|---|---|---|
| id | uuid | PK |
| document_revision | bigint | NOT NULL, UNIQUE — from sequence `seq_document_revision` (monotonic, never reused; **seed START WITH 100**). **Gaps are normal and documented:** Postgres sequence values consumed by rolled-back transactions are not returned, so revisions are unique and strictly increasing but NOT contiguous — prose, clients, and tests must never assume contiguity. **Two-floor model (exact comparisons, no ambiguous "exceeds"):** property `kira.config.bundled-revision-floor` (default **4**) = the highest document revision shipped in any released app binary — every published server revision must be **strictly `>`** it (equal would let two different documents share a revision number; the app's own acceptance rule is the *inclusive* `revision >= bundledDocument.revision` — verified in `RemoteSourceConfigManager.refresh()` (`.takeIf { it.revision >= acceptedRevision }`), so strictly-greater composes safely with it). Property `kira.config.minimum-server-revision` (default **100**) = the smallest revision the backend may ever publish — the sequence's next value must be **`>=`** it (inclusive: the very first generated value IS 100 and is legal). Startup asserts (fail-fast): `minimum-server-revision > bundled-revision-floor`; sequence-next `>=` `minimum-server-revision`; and, when snapshots exist, sequence-next `>` the latest published revision (sequence state read via `pg_sequences.last_value` — NULL ⇒ next = START value). **At production cutover (and at every app release that re-bundles), ops re-verifies `bundled-revision-floor` against the revision actually shipped in the live binary** — never relies forever on "bundled == 4". `StartupConsistencyIT` covers: empty DB (ok), existing snapshots (ok), sequence gaps (ok), misconfigured floors (fail). |
| schema_version | int | NOT NULL (=1 for now) |
| document_json | text | NOT NULL — the EXACT canonical bytes served (not jsonb, deliberately) |
| checksum | char(64) | NOT NULL — SHA-256 of `document_json` = the ETag |
| canon_version | varchar(16) | NOT NULL (= `'kcj-1'` in v1) — the canonicalization algorithm that produced the bytes (§5) |
| source_count | int | NOT NULL |
| created_by | uuid | NOT NULL FK users |
| created_at | timestamptz | NOT NULL — **no DB default, deliberately**: set by the application to the SAME injected-Clock instant (truncated to ISO-8601 UTC seconds, §5) that is serialized as the document's `generatedAt` — one snapshot timestamp everywhere (§9; `SnapshotTimestampConsistencyIT` proves JSON == DB) |
| notes | text | NULL |

**Latest lookup is the `document_publication_state` pointer (below) — the single authoritative mechanism.** `MAX(document_revision)` is NOT a read path anywhere; it appears only inside the startup consistency check, where pointer and MAX are compared. Index implied by UNIQUE.

**`document_publication_state`** (V3) — the **global publication serialization lock** (amendment #2; §9 explains the lost-update hazard it prevents)

| column | type | constraints |
|---|---|---|
| id | int | PK, CHECK (`id = 1`) — singleton row, seeded by V3 |
| latest_document_revision | bigint | NULL until first publish; **FK → `published_documents(document_revision)`** (that column is UNIQUE, so referenceable) — the pointer can never dangle; the snapshot row is inserted (§9 step 8) before the pointer moves (step 9), same transaction |
| updated_at | timestamptz | NOT NULL |

Every state-visible document mutation begins by locking this row with `SELECT … FOR UPDATE`; it is ALSO **the one authoritative latest-document pointer** (updated in the same transaction that inserts the snapshot) — every "latest" read (public `/document`, `/document/meta`, `/sources`, admin views) resolves through this single-row read; no code path may use `MAX(document_revision)` as a read strategy. **Startup consistency validation** (`PublicationStateStartupValidator`, fail-fast, runs with the §5 floor checks): (a) pointer NULL ⇒ zero snapshot rows exist (fresh install); (b) pointer non-NULL ⇒ it references an existing snapshot (the FK already guarantees this) AND equals `MAX(document_revision)` — no snapshot may sit above the pointer; (c) the sequence's next value `>` the pointer. **Recovery procedure (documented in SOURCE_CONFIG_LIFECYCLE.md — NEVER silently auto-repaired):** on detected inconsistency the app refuses to start (readiness stays red) and the error message names the runbook: a human inspects `published_documents` vs the pointer, decides which snapshot is truly latest, repairs with a single manual audited SQL `UPDATE document_publication_state SET latest_document_revision = <verified>` (and/or `ALTER SEQUENCE … RESTART` when the sequence lags), records the action in `audit_log` — then restarts.

**ON DELETE policy (global, explicit):** every FK in the schema is `ON DELETE RESTRICT` (spelled out in the DDL, never left implicit) — historical revisions, snapshots, validation results, completions, and audit rows are evidence; nothing may cascade-delete them. There are no delete endpoints in v1; retention/redaction is a deliberate future admin operation.

**JPA discipline (since JPA stays — no concrete reason to replace it):** `spring.jpa.open-in-view=false`; `spring.jpa.hibernate.ddl-auto=validate` (Flyway owns the schema); no lazy entity graphs crossing service boundaries (fetch what a use case needs explicitly); pessimistic locks expressed explicitly (`@Lock(PESSIMISTIC_WRITE)` / native `FOR UPDATE`) where §5/§9 require them.

**`completion_requests`** (V5)

| column | type | constraints |
|---|---|---|
| id | uuid | PK |
| user_id | uuid | NOT NULL FK users |
| provider | varchar(64) | NOT NULL (e.g. `echo`) |
| model | varchar(128) | NOT NULL (requested or provider default) |
| prompt | text | NOT NULL |
| status | varchar(16) | NOT NULL CHECK IN `('PENDING','RUNNING','SUCCEEDED','FAILED')` |
| created_at / updated_at | timestamptz | NOT NULL |

Index: `idx_completions_user (user_id, created_at DESC)`.

**`completion_results`** (V5)

| column | type | constraints |
|---|---|---|
| id | uuid | PK |
| request_id | uuid | NOT NULL, UNIQUE FK → completion_requests(id) |
| result | text | NULL |
| error | text | NULL — the SANITIZED client-visible message only; **never a stack trace, never a raw provider exception** (those go to secured server logs, request-id-correlated) |
| error_code | varchar(64) | NULL — stable machine code from the §10 catalog; CHECK `(error_code IS NULL) = (error IS NULL)` (a failure always carries both, a success neither) |
| latency_ms | int | NULL |
| created_at | timestamptz | NOT NULL |

CHECK `chk_result_xor_error`: `NOT (result IS NOT NULL AND error IS NOT NULL)` — a row can never carry both a result and an error (success ⇒ `result` set + both error fields NULL; failure ⇒ `error` + `error_code` set + `result` NULL).

**`audit_log`** (V4) — yes, useful: every admin mutation and auth event

| column | type | constraints |
|---|---|---|
| id | bigint | PK, `GENERATED ALWAYS AS IDENTITY` |
| actor_user_id | uuid | NULL FK users (NULL = system, e.g. admin seeder) |
| action | varchar(64) | NOT NULL (`SOURCE_CREATED`, `REVISION_CREATED`, `REVISION_PUBLISHED`, `SOURCE_DISABLED/ENABLED/RETIRED/REMOVED`, `SOURCE_ROLLBACK`, `DOCUMENT_PUBLISHED`, `BUNDLED_IMPORTED`, `USER_REGISTERED`, `USER_CREATED`, `USER_DISABLED/ENABLED`, `USER_PASSWORD_RESET`, `LOGIN_FAILED`) |
| entity_type / entity_id | varchar(32) / varchar(128) | NOT NULL (api id / revision uuid / user uuid) |
| detail | jsonb | NOT NULL default `'{}'` — **identifiers, revision numbers, and checksums ONLY. Never full config bodies, never header values, never completion prompts/results, never passwords** (log-hygiene rule, §6) |
| created_at | timestamptz | NOT NULL |

Indexes: `(entity_type, entity_id)`, `(created_at)`.

**Reserved, NOT created in v1:** `refresh_tokens` (id, user_id, token_hash, expires_at, revoked_at) — designed here so the auth seam has a landing spot; migration added only when refresh tokens are actually built.

---

## 6. Security model

- **Auth flow:** `POST /auth/login` → `AuthService` loads user by email → BCrypt verify → `JwtService` issues an **HS256 JWT** via Nimbus (`spring-security-oauth2-jose`): claims `sub` = user UUID, `email`, `role` (`ADMIN`|`USER`), `iss = "kira-backend"`, **`aud = "kira-api"`**, `iat`, `exp = iat + kira.security.access-token-ttl` (default **PT60M**). Resource-server side: `NimbusJwtDecoder.withSecretKey(...)` configured to **explicitly validate signature, `exp`, `nbf` when present, issuer, and audience** (default validators + `JwtIssuerValidator` + `JwtClaimValidator("aud")`; clock skew 60s). **The DB-backed enabled-check lives INSIDE the authentication pipeline** — a custom `jwtAuthenticationConverter` (`Converter<Jwt, AbstractAuthenticationToken>`) registered on `oauth2ResourceServer { jwt {} }`, which Spring invokes for EVERY request that presents a bearer token, on every protected endpoint (a controller **argument resolver is NOT the enforcement point** — it only runs when a handler injects that argument): after standard JWT verification the converter (1) loads the user by `sub` (indexed PK read); (2) missing or `enabled = false` → throws an `AuthenticationException` subtype (e.g. `InvalidBearerTokenException`) → the resource-server entry point returns **401**; (3) derives the granted authorities from the **DB `role`, not the token claim** — server-side role and enabled changes take effect on the target's next request, and a stale token role claim can never grant outdated access (the claim stays in the token as diagnostic/client convenience only, never trusted for authorization); (4) exposes the loaded domain user as the authentication principal, so the `CurrentUser` resolver is a SecurityContext read — no second DB query per request. Still no custom servlet **filter** — the converter is the standard extension point of `oauth2ResourceServer { jwt {} }`. HS256 with one shared in-process key is deliberate for a single service — asymmetric signing is NOT introduced without a real multi-service key-distribution need; a `kid` header is emitted from day one as the rotation seam (single active key in v1).
- **JWT key handling:** `kira.security.jwt-secret` bound from env `KIRA_JWT_SECRET` = **Base64-encoded cryptographically random key that decodes to ≥ 256 bits** (generate: `openssl rand -base64 32`), NOT an arbitrary human passphrase. Startup fails fast if missing, not valid Base64, or < 32 decoded bytes (except a documented dev-profile default clearly marked insecure). Documented in SECURITY.md: key format, issuer, audience, TTL, clock skew, rotation procedure (new key + `kid` bump + bounded dual-accept window when needed).
- **Disabled-user revocation (decided, no token-version machinery):** tokens stay stateless, but the `jwtAuthenticationConverter` above loads the user row on every token-authenticated request and **rejects missing/`enabled = false` with 401** — disabling a user is therefore effective on their next request, on **every** protected endpoint (auth, completion read/write, admin — not just handlers that inject `CurrentUser`), without token-version bookkeeping. Login is likewise refused. (`DisabledUserAuthIT` proves all of: `/auth/me`, completion read+write, and admin endpoints rejecting a disabled user's still-valid token; plus DB-role-change taking effect against a token carrying the old role claim.)
- **Secret handling:** DB creds via `SPRING_DATASOURCE_*` env. `.env` gitignored; `.env.example` committed with placeholders only; `docker-compose.yml` carries only the throwaway local DB password.
- **Password policy & hashing:** min **15 chars** (NIST SP 800-63B guidance for single-factor password auth; this is a small-audience operator API where password managers are assumed — usability cost ≈ 0), max **72 UTF-8 bytes** (the BCrypt input limit, enforced explicitly with a clear 400 rather than silent truncation; the byte cap is documented as encoder-derived — moving to Argon2 later via the delegating encoder lifts it), **no composition rules, no expiry, no silent trimming/normalization** of the password itself. Encoder bean = `DelegatingPasswordEncoder` with `{bcrypt}` as the initial id (hash format stays portable; BCrypt cost calibrated on real deployment hardware at setup — target ≥ ~100 ms — not hardcoded at strength 10 forever). Optional-but-recommended: a small embedded top-common-passwords blocklist check.
- **Auth throttling (v1, mandatory):** `AuthThrottleService` — bounded in-memory (single-instance v1; the interface is the seam for Redis/distributed later — **documented explicitly: the in-memory store is correct ONLY for a single instance; a multi-instance deployment MUST move to a shared backend first**). Login uses both a **normalized-account+client-IP identity bucket** (default threshold 5) and a separate **aggregate client-IP spray bucket** (default threshold 25), with temporary blocks doubling and capped at 15 minutes; counters expire after inactivity and no permanent lockout exists. A successful login clears only its identity bucket. Unknown/disabled accounts verify against a startup-generated decoy hash so every credential path performs one password-hash check. Registration remains per-IP. Throttled → 429 with a generic body. `AuthenticationRateLimitIT` proves identity throttling, reset, and cross-account IP spraying.
- **Trusted client-IP resolution (normative — the throttle must not trust spoofable headers):** the throttle's client address is the **server-observed remote address (`request.remoteAddr`) by default**. `X-Forwarded-For` / `Forwarded` are honored **only** in an explicit trusted-proxy mode: `kira.security.trust-forwarded-headers=false` (default) + `kira.security.trusted-proxies` (CIDR/address list, empty by default) — and only when the direct peer's address is in that list, in which case the effective client IP is the last hop **appended by the trusted proxy** (rightmost non-trusted entry). With the mode disabled, forwarding headers are **completely ignored** — a spoofed `X-Forwarded-For` can neither dodge its own throttle bucket nor poison someone else's. Malformed, oversized (> 1 KB), or unparseable forwarding headers are safely ignored (fall back to the remote address) — never an exception path. **Bounded store (normative):** maximum entry count (`kira.security.throttle.max-entries`, default 100 000); TTL expiry on every entry (a bucket older than its window is dead); deterministic eviction when full (expired entries first, then oldest-by-last-update); bounded keys (normalized email capped at the 320-char column bound and hashed into the key; IPs are fixed-width); each entry stores ONLY the counter, window timestamps, and the throttle-until instant — no credentials, no request payloads. No permanent lockout state exists by construction (everything expires). `ClientIpResolutionIT` (§11 test 50) proves: spoofed forwarded headers are ignored in default mode; trusted-proxy mode resolves the real client; malformed headers fall back safely; eviction + expiry + reset-after-success + max-entries hold.
- **Roles → endpoint matrix:**

| Endpoint group | anonymous | USER | ADMIN |
|---|---|---|---|
| `GET /api/v1/source-config/**`, `GET /api/v1/sources/**` | ✅ | ✅ | ✅ |
| `POST /auth/register`, `POST /auth/login` | ✅ | ✅ | ✅ |
| `GET /auth/me` | ❌ 401 | ✅ | ✅ |
| `POST/GET /api/v1/completions/**` | ❌ 401 | ✅ (own only) | ✅ |
| `/api/v1/admin/**` (incl. `/admin/users/**`) | ❌ 401 | ❌ 403 | ✅ |
| `/v3/api-docs/**`, `/swagger-ui/**` | dev profile ✅ / prod ❌ (property-gated) | | ✅ |
| `/actuator/health/liveness`, `/actuator/health/readiness` | ✅ (status only, `show-details: never` for anonymous) | ✅ | ✅ |
| any other `/actuator/**` | ❌ (not exposed — explicit allowlist `management.endpoints.web.exposure.include=health`) | ❌ | ❌ |

- **Config:** stateless sessions (`SessionCreationPolicy.STATELESS`), CSRF disabled (pure bearer-token API), no CORS config in v1 (server-to-server/curl; Open Q8), method-security on (`@PreAuthorize` where the matrix isn't purely path-shaped, e.g. completion ownership check in service layer).
- **Admin seeding (no plaintext password ever logged):** `AdminSeeder` (`ApplicationRunner`): if no `role='ADMIN'` user exists, create one from `KIRA_ADMIN_EMAIL` + `KIRA_ADMIN_PASSWORD` env. Missing env → **fail startup with a clear message in EVERY profile where seeding is enabled** (dev included — local dev supplies them via the gitignored `.env`, `.env.example` documents the shape). The previous "generate a random password and log it at WARN" idea is **rejected**: a plaintext credential in the log stream survives into CI logs and log collectors and contradicts the no-plaintext-secret posture. Passwords are never written to any log at any level. Never reset an existing admin's password.
- **Operational hardening:** request/correlation ID — an inbound `X-Request-Id` is accepted **only when it matches `[A-Za-z0-9._-]{1,64}`**; anything else (control chars, over-length, absent) is discarded and a server UUID generated instead — unvalidated header values must never reach MDC/log lines (log-injection + unbounded-value guard); echo the effective id in responses; include in every log line via MDC; error responses carry bounded detail (no stack traces, no echoed secrets); startup config validation (`@ConfigurationProperties` + JSR-380 fail-fast); graceful shutdown enabled (`server.shutdown=graceful`). Liveness must NOT fail solely because Postgres is temporarily down (liveness = process health; readiness = DB + migrations applied). No Redis, no distributed tracing, no further observability stack in v1.
- **Logging & diagnostics (normative):** production profile emits **structured JSON logs** (logstash-style encoder); dev profile emits readable console logs — same event catalog, different format. Every log line carries, where applicable: timestamp, level, request ID (MDC), HTTP method, **normalized route pattern** (`/api/v1/sources/{api}` — never the raw URL, which is uncontrolled input), response status, request duration, authenticated user ID + role, source `api`, per-source revision number, document revision, completion request ID, and the stable error code. **Event catalog (each logged once, at the boundary that owns it — a handled domain error is NOT re-logged at every layer):**
  - `INFO` — application startup + active profile; Flyway migration state (versions applied); startup consistency-check results (§5 floors + publication-state validator); admin seeding outcome (created / already-present — **never the password**); login success; user enable/disable; source creation + revision creation; source validation summaries (counts + codes, §8-bounded); publish + rollback (api, revision numbers, document revision); lifecycle transitions (from→to); bundled-import summaries (created/updated/unchanged/skipped counts); document publication (revision + checksum); ETag 304 cache hits (at debug-volume sites, may be sampled); completion request state transitions (`PENDING→RUNNING→SUCCEEDED/FAILED`); graceful shutdown begin/end.
  - `WARN` — rejected auth attempts (generic reason category, no credential echo); throttling activation + expiry; validation rejection summaries; recoverable provider failures (`PROVIDER_TIMEOUT`/`PROVIDER_UNAVAILABLE` with the stable code).
  - `ERROR` — unexpected failures requiring operator attention (unhandled exceptions, startup-validation failures, `INTERNAL_COMPLETION_ERROR` causes — full stack trace HERE and only here, in the secured server log).
  - `DEBUG` — bounded development diagnostics, no secrets; off in prod by default.
  **Non-negotiable log-hygiene rules (superset of the earlier hard constraints):** never log passwords or password hashes; never log JWTs or the `Authorization` header; never log cookies/session values; never log complete source-config bodies; **never log source header VALUES** (they may carry credential-like material — log header *names* only when needed); never log completion prompts/results by default; never log raw provider responses/exceptions to any client-visible channel; never place unvalidated user input into structured log **field names**; sanitize newline/control characters in any user-influenced value that reaches a log message; validation-error logging is bounded to codes + paths (never the submitted document). No request-body logging for auth, config-authoring, or completion endpoints. **`SECURITY.md` (Phase 10) documents retention + privacy expectations** for logs and completion data (what is collected, what is never collected, that final retention windows are a deployment-platform concern outside this repo).
- **Refresh-token-READY seam (not built):** `AuthService.issueTokens(user)` returns a `TokenPair(access, refresh = null)`; token TTLs are properties; the `refresh_tokens` table design is reserved in §5; `POST /auth/refresh` is **not registered at all** — requests get the standard 404 (no 501 stub handler: dead code with its own security-matrix row buys nothing); docs/OpenAPI mark it planned. Nothing else to do now — deliberately.

---

## 7. Config model & shape mirroring

The backend defines, in `sourceconfig/domain/model/`, **kotlinx-`@Serializable` data classes copied field-for-field (names, types, defaults) from the app's** `sources/contracts/src/commonMain/kotlin/me/manga/kira/sources/contracts/model/SourceConfig.kt` (read 2026-07-11). This is the exact inventory the implementation agent must reproduce:

**`SourceConfigDocument`** — `schemaVersion: Int`; `generatedAt: String? = null` (ISO-8601, provenance only); `revision: Long = 0` (monotonic, higher-wins on the app's merge); `sources: List<SourceConfig> = emptyList()`.

**`SourceConfig`** — `api: String` (stable key = legacy `MangaSource.API`); `language: String`; `displayName: String = api`; `baseUrl: String`; `imageBase: String = ""`; `enabled: Boolean = false` (first-seed enablement only); `priority: Int = 0` (merge tiebreak); `engine: String = "legacy"` (`"generic"` | `"legacy"` | `"kotlin:<id>"`); `minAppVersion: String? = null` (reserved, NOT enforced by app engine yet); `headers: Map<String,String> = emptyMap()`; `usesCapturedHeaders: Boolean = true`; `pagination: PaginationSpec = PaginationSpec()`; `endpoints: Map<String, EndpointSpec> = emptyMap()` (verbs: `home`,`featured`,`search`,`details`,`chapters`,`pages`); `fields: Map<String, FieldSpec> = emptyMap()` (dotted paths, e.g. `item.title`, `chapter.url`, `page.image`); `blacklistGenres: List<String> = emptyList()`; `siteState: String = "WORKING"` (`WORKING`|`UNDER_MAINTENANCE`|`STOPPED`|`ADULT_18_PLUS` — the "adult/content flag" is `ADULT_18_PLUS`); `lifecycle: String = "active"` (`active`|`disabled`|`removed` — the APP's 3-state vocabulary; server's 5 states map onto it, §9); `previousHosts: List<String> = emptyList()`; `previousImageHosts: List<String> = emptyList()`; `trustedHosts: List<String> = emptyList()` (all three: BARE hosts, e.g. `"azoramoon.co"`); `icon: IconSpec? = null`; `filters: List<FilterDefinition> = emptyList()` (declaration order = UI order = request-composition order).

**`IconSpec`** — `resourceKey: String = ""` (regex `[a-z0-9_]{1,64}` into the app's packaged-drawable registry); `remoteUrl: String = ""` (absolute **HTTPS-only**).

**`PaginationSpec`** — `type: String = "page-number"`; `param: String = "page"`; `start: Int = 1`. (Only `page-number` exists in the app registry.)

**`EndpointSpec`** — `url: String` (template with `{baseUrl}`,`{imageBase}`,`{page}`,`{queryEncoded}`,`{itemUrl}`,`{chapterUrl}`,`{id}` placeholders); `method: String = "get"`; `format: String = ""` (`json`|`html`|`script-json`|`""`); `scriptId: String = ""`; `root: String = ""` (JSONPath; may be comma-separated coalesce candidates); `rootDirs: List<String> = emptyList()`; `listSelector: String = ""`; `formBody: Map<String,String> = emptyMap()`; `jsonBody: String = ""`; `listFilters: List<FilterSpec> = emptyList()`; `pageParam: String = ""`; `lastPageLocator: String = ""`.

**`FilterSpec`** (list-item predicate) — `path: String`; `op: String` (`equals`|`notEquals`|`contains`|`notNull`|`isNull`); `value: String = ""`; `mode: String = "exclude"` (`include`|`exclude`).

**`FieldSpec`** — `path=""`, `selector=""`, `attr="text"`, `fallbackPath=""`, `fallbackSelectors=[]`, `lazyAttrChain=[]`, `template=""`, `vars={}`, `listPath=""`, `listSelector=""`, `imageStrategy=""`, `dateStrategy=""`, `transform: List<TransformSpec> = []`.

**`TransformSpec`** — `fn: String`; `args: Map<String,String> = emptyMap()`; `list: List<String> = emptyList()`.

**`FilterDefinition`** (user-facing search filter) — `id: String` (`[a-z0-9_]{1,64}`); `label: String`; `type: String` (`select`|`multiselect`|`toggle`|`text`|`number`; `range`/`date` reserved-rejected); `options: List<FilterOptionSpec> = []`; `default: String = ""`; `defaults: List<String> = []` (multiselect only); `required: Boolean = false`; `request: FilterRequestSpec` (REQUIRED, no default); `visibleWhen: List<FilterConditionSpec> = []`; `excludeOf: String = ""`; `appliesTo: List<String> = listOf("search")` (v1 whitelist: `search` only).

**`FilterOptionSpec`** — `value: String`; `label: String = ""`.

**`FilterRequestSpec`** — `target: String` (`query`|`path`|`form`|`header`|`body-json`); `param: String`; `encode: String = "single"` (`single`|`csv`|`repeat`|`json-array`); `delimiter: String = ","`; `omitIfEmpty: Boolean = true`; `trueValue: String = "true"`; `falseValue: String = ""`.

**`FilterConditionSpec`** — `filter: String`; `anyOf: List<String>`.

**Parsing — two deliberate modes (the app's leniency is right for CONSUMING config, wrong for AUTHORING it):**

- **STRICT authoring parser** — used by `POST /admin/sources`, `POST /admin/sources/{api}/revisions` (and any future authoring input): kotlinx `Json { ignoreUnknownKeys = false; isLenient = false }`, preceded by a **Jackson structural pre-pass** (`StreamReadFeature.STRICT_DUPLICATE_DETECTION` + `DeserializationFeature.FAIL_ON_TRAILING_TOKENS`, parse-and-discard) because kotlinx-serialization has no duplicate-key or trailing-garbage switch — this is the documented, feasible way to reject duplicate object keys and malformed/ambiguous JSON. Net effect: an authoring typo like `usesCaptureHeaders` (for `usesCapturedHeaders`) is a 400 with the offending key named, never a silently-ignored no-op. Rollback does NOT re-parse (it copies an already-stored validated model), so no parser applies there.
- **COMPATIBILITY import parser** — used ONLY by `import-bundled`: mirrors the app's `SourceConfigParser` settings (`ignoreUnknownKeys = true; isLenient = true`) so the real bundled document imports exactly as the app reads it. The import response includes a `warnings[]` listing unknown keys encountered (detected by diffing the lenient parse against a strict re-parse attempt) so suspicious structures are visible, not silent.

**Outbound canonical** serialization uses the §5 `kcj-1` canonicalization — omitted defaults, recursively sorted keys, compact UTF-8. Because the app ignores unknown keys, the server COULD add fields; the rule is: **don't** — serve exactly this model.

**Reference values for fixtures:** the current production document is `CONFIG_BACKED_SOURCES_JSON` (`composeApp/src/commonMain/kotlin/me/manga/kira/sources/runtime/BundledSourcesConfig.kt`): `schemaVersion 1`, `revision 4`, 12 `engine:"generic"` stanzas (Azora, Mangamello, Mangamello Plus, SwatManga, Lekmanga, Team X, DilarV2, 3asq, Demonicscans, Mangabuddy, Zazamanga, Tapas) + 33 metadata-only `engine:"legacy"` stanzas. Test fixtures should include a trimmed real stanza (Azora is fully documented in that file's KDoc).

---

## 8. Validation strategy

Package `sourceconfig/validation` — **pure Kotlin** (stdlib + kotlinx-serialization only; no Spring/JPA/Jackson imports), structured for later extraction into a shared Kotlin module used by both app and backend:

```kotlin
class SourceConfigValidator(private val strategies: StrategyCatalog) {
    fun validate(document: SourceConfigDocument): ValidationResult          // whole-document
    fun validateSource(source: SourceConfig, otherApis: Set<String>): List<ValidationError>  // one stanza in doc context
}
data class ValidationError(val code: String, val path: String, val message: String)
data class ValidationWarning(val code: String, val path: String, val message: String)   // advisory only — never blocks publish
data class ValidationResult(val isValid: Boolean, val errors: List<ValidationError>, val warnings: List<ValidationWarning> = emptyList())
interface StrategyCatalog { // mirror of the app's StrategyRegistry port
    fun hasTransform(name: String): Boolean
    fun hasImageStrategy(name: String): Boolean
    fun hasDateStrategy(name: String): Boolean
    fun hasPagination(name: String): Boolean
}
```

`ServerStrategyCatalog` is a data-driven implementation holding the whitelists the app build ships (below). Error `code`s are stable machine identifiers (e.g. `DUPLICATE_API`, `UNKNOWN_TRANSFORM`, `FILTER_CYCLE`); `path` is the pinpoint (`sources[Azora].filters[genres].request.encode`). Errors are **collected, not fail-fast** (except the schema-version gate), and acceptance is **all-or-nothing** per document — both exactly like the app (`Validation.kt` KDoc: a document with any error is dropped wholesale).

### Rule inventory — 1:1 mirror of the app's `DefaultSourceConfigValidator` (`sources/engine/.../DefaultSourceConfigValidator.kt`, read 2026-07-11)

**Document level**
1. `schemaVersion` must equal **1** (`SUPPORTED_SCHEMA_VERSION`); otherwise fail immediately without probing further.
2. Unique `api` across the document (server also enforces via the `uq_source_configs_api` DB constraint).

**Every source (any engine)**
3. `api` non-blank.
4. `language` non-blank.
5. `baseUrl` starts with `"http"` (absolute http(s) URL).
6. `engine` ∈ {`generic`, `legacy`, `kotlin:<id>` prefix} — anything else rejected.
7. `siteState` ∈ {`WORKING`, `UNDER_MAINTENANCE`, `STOPPED`, `ADULT_18_PLUS`}.
8. `lifecycle` ∈ {`active`, `disabled`, `removed`}.
9. `previousHosts` / `previousImageHosts` / `trustedHosts`: every entry is a **bare host** — non-blank, no `://`, no `/`, no `:` (no port!), no whitespace.
10. `icon` (when present): `resourceKey` matches `[a-z0-9_]{1,64}` if non-empty; `remoteUrl` must start `https://` if non-empty (**HTTPS-only icons**); the block must not be empty (at least one of the two set).
11. Non-generic engines: `filters` must be empty (filters are a generic-engine capability); strategy/endpoint/field checks are **skipped** for legacy/kotlin engines (their behavior lives in code, not config).

**Generic-engine sources only**
12. `pagination.type` known to the catalog — app registry ships exactly `{"page-number"}`.
13. At least one of the `home` / `featured` endpoints must exist.
14. Per endpoint (every verb present): `url` non-blank; `url` must NOT contain raw `{query}` (must use `{queryEncoded}`); `jsonBody` must NOT contain raw `{query}` (must use `{queryJson}`); `method` (when non-empty, lowercased) ∈ {`get`, `post-form`, `post_form`, `postform`, `post-json`, `post_json`, `postjson`}; `format` (when non-empty) ∈ {`json`, `html`, `script-json`}; each `listFilters[]` entry: `op` ∈ {`equals`,`notEquals`,`contains`,`notNull`,`isNull`}, `mode` ∈ {`include`,`exclude`}.
15. Per field spec: every `transform[].fn` ∈ the transform whitelist — app ships exactly: `trim, lowercase, uppercase, strip-html, clean-html, regex-replace, regex-extract, replace, remove, prepend, append, substring-before, substring-after, default, enum-map, format-number, decimal` (`sources/engine/.../internal/Transforms.kt`); `dateStrategy` (when non-empty) ∈ {`iso`, `epoch-seconds`, `epoch-millis`} (`DateStrategies.kt`); `imageStrategy` (when non-empty) — **always rejected**: the app's image-strategy set is intentionally EMPTY (fail-closed; Stage-1 capability).

**Search filters (generic only; error paths `source '<api>': filters: filter '<id>': <field>: <msg>`)**
16. `id` non-blank, matches `[a-z0-9_]{1,64}`, unique within the source.
17. `label` non-blank.
18. `type` ∈ {`select`,`multiselect`,`toggle`,`text`,`number`} (`range`/`date` rejected as reserved).
19. Standard-id type pinning: `sort` must be `select`; `genres`/`status`/`language`/`type` must be `select` or `multiselect`.
20. Options: required (≥1) for select/multiselect; forbidden for toggle/text/number; option `value`s non-blank and unique within the filter.
21. Defaults: multiselect uses `defaults` (each ∈ declared option values) and must NOT set `default`; non-multiselect must NOT set `defaults`; select `default` ∈ option values; toggle `default` ∈ {`""`,`"true"`,`"false"`}; number `default` must parse as a double; `required=true` demands a usable default (non-empty `default`, or non-empty `defaults` for multiselect).
22. Request spec: `target` ∈ {`query`,`path`,`form`,`header`,`body-json`}; `param` non-blank; `encode` ∈ {`single`,`csv`,`repeat`,`json-array`}. Compatibility: `repeat` only for query/form; `json-array` only for body-json; body-json allows only single/json-array; `csv`/`repeat`/`json-array` require type=multiselect.
23. Placeholder targets (`path`/`body-json`): `param` matches `[a-zA-Z0-9_]+` and must not shadow a reserved engine template var — `baseUrl, imageBase, page, pageOffset, query, queryEncoded, queryJson, itemUrl, chapterUrl, id`; `path` target additionally requires a guaranteed non-empty default (a URL placeholder cannot be omitted).
24. `appliesTo` non-empty; every verb ∈ {`search`} (v1); the referenced endpoint must exist. Per verb: `form` target ⇒ endpoint method is a post-form variant AND `param` doesn't collide with a static `formBody` key; `body-json` ⇒ post-json method AND the endpoint `jsonBody` contains `{param}`; `path` ⇒ endpoint `url` contains `{param}`; `query` ⇒ `param` not already hardcoded in the url (`?param=` / `&param=`).
25. `visibleWhen`: referenced filter id exists; no self-reference; `anyOf` non-empty; when the referenced filter's value vocabulary is enumerable (select/multiselect option values; toggle `true`/`false`) every `anyOf` value must be within it (text/number are uncheckable).
26. `excludeOf`: only on multiselect; not self; referenced filter exists and is multiselect; no chained exclusion (the referenced filter must not itself set `excludeOf`); the two filters' `defaults` must not overlap.
27. **Dependency-cycle detection** over the `visibleWhen` graph (DFS, in-stack back-edge = cycle; one report per document is sufficient) — composition needs a single deterministic pass.

**Server-additional rules (clearly labeled; the server may be stricter than the app, never looser — and rules 31–33 are verified against the FULL real bundled document, which they must never reject)**
28. Invalid lifecycle transitions rejected at the service layer (§9 state machine) — the app has no notion of these.
29. Whole-document publish gate: the candidate document (current published revisions + lifecycle mapping) must validate before a snapshot materializes; a `removed` source's stanza must be absent; a `retired` source's stanza must carry `lifecycle:"removed"` — i.e., "removed is never silently active" is asserted at assembly time, not just at transition time.
30. `revision` monotonicity: a new snapshot's `document_revision` strictly exceeds the previous (DB sequence) — mirrors the app-side anti-rollback floor in `RemoteSourceConfigManager` (`revision >= accepted floor` or the document is dropped).
31. **Endpoint completeness for `engine="generic"` (publish-blocking).** The app validator requires only home|featured, because historically an omitted verb fell back to the legacy Kotlin scraper — but that floor is GONE: `FallbackSourceClient` is "RETAINED-BUT-UNWIRED (2026-06) … config-backed sources are now served by the bare generic client (generic-ONLY)" (its own KDoc), and `GenericSourceClient` returns `AppError.Validation.Required("endpoint:details"/"endpoint:pages"/"endpoint:<verb>")` at runtime for any missing verb. A generic source missing a verb is therefore a shipped broken feature. Server rule (generic engine only; legacy/kotlin engines unaffected): at least one of `home`/`featured` (`GENERIC_MISSING_HOME_OR_FEATURED`), `search` required (`GENERIC_MISSING_SEARCH`), `details` required (`GENERIC_MISSING_DETAILS`), `pages` required (`GENERIC_MISSING_PAGES`). **`chapters` is deliberately NOT required** — verified in `GenericSourceClient.details()`: the chapter list parses inline from the details response; a declared `chapters` endpoint is the optional two-request "SeparatedDetailsSites" pattern (4 of the 12 real generic stanzas — Azora, Lekmanga, Demonicscans, Zazamanga — ship without it). All 12 bundled generic stanzas satisfy this rule (verified 2026-07-11).
32. **Public-config secret safety (publish-blocking).** The served document is public and cacheable; nothing credential-like may be published. (a) Hard-denied header names, case-insensitive: `cookie`, `set-cookie`, `proxy-authorization` (`FORBIDDEN_HEADER`) — the real bundled document contains none of these (verified). (b) Sensitive-name headers — `authorization`, `x-api-key`, `api-key`, `x-auth-token`, and any name containing `token`/`secret`/`password` — are allowed **only when the value is on the explicit public-placeholder allowlist** (server property `kira.validation.public-header-placeholder-values`, default exactly `["Bearer null"]`); any other value → `SECRET_LIKE_HEADER`. This is the precise reconciliation with reality: the bundled document's Mangamello / Mangamello Plus stanzas legitimately carry `authorization: "Bearer null"` — a literal non-secret placeholder the upstream API requires — so a blanket `Authorization` denylist would reject the production document and break the import; the value-allowlist keeps that legal while still rejecting every real credential. Extending the allowlist is an explicit reviewed server-config change. (c) URLs: `baseUrl`, non-empty `imageBase`, and non-empty `icon.remoteUrl` must parse as real absolute URIs — scheme exactly `http`/`https` (`https` only for icons, app rule 10), non-empty host, **no user-info** (`https://user:pass@host` → `URL_USERINFO_FORBIDDEN`), no fragment, valid port. This is strictly *tighter* than the app's `startsWith("http")` and verified compatible: every URL in the real bundled document parses cleanly under it (all https, no user-info, no fragments — checked 2026-07-11). Endpoint `url` **templates** (`{baseUrl}/…`, `{itemUrl}`) are NOT URI-parsed — they are templates, covered by the existing rule-14 checks. Prominent doc rule (API.md + SECURITY.md): *"Every value published in a SourceConfig is public application configuration. Never place credentials, session cookies, tokens, or private API keys in it."*
33. **Icon-catalog advisory (warning, never rejection).** `PackagedIconCatalog` = a manually versioned copy of the key set in the app's `SourceIconRegistry` (44+ keys as of 2026-07-11; update procedure documented: when the app adds a packaged icon, add the key here in the same release train — the catalog lives in server code/config, the backend never depends on the mobile Gradle project). An `icon.resourceKey` not in the catalog AND with no `remoteUrl` fallback → `ValidationWarning UNKNOWN_ICON_KEY`, surfaced in validation results and the admin UI response — **not** an error, because the app degrades gracefully by design (verified in `SourceIconRegistry` KDoc: "A missing/unknown key resolves to `null` (the UI falls through to the remote URL, then the deterministic initials avatar) — never a crash"), and a hard reject would let a stale catalog block valid publishes. Precedence when both are set (app behavior): packaged key wins, `remoteUrl` is the fallback. The blocking rules stay exactly the app's: key regex + HTTPS-only remoteUrl + non-empty block (rule 10).

**Two-tier authoring error model (normative — structural identity rejection vs stored draft validation):** the API deliberately stores invalid drafts so admins can inspect them, but fields that are DB-identity/routing requirements are NOT ordinary validation findings — they gate whether a row can exist at all.

- **Tier 1 — structural 400 gate (checked before ANY persistence; violation → 400, no source row, no revision, no stored validation result):** (a) malformed / STRICT-parse-rejected JSON (§7); (b) `API_ID_MISMATCH` — `body.api != {api}` on later revisions; (c) `LIFECYCLE_NOT_AUTHORABLE` — authoring payload `lifecycle` not the neutral `"active"` (omitted = the model default = also fine; explicit `"active"` and omitted canonicalize identically under default-omission); (d) `API_IDENTIFIER_INVALID` — `api` blank, longer than 128 characters (the `varchar(128)` column limit — Postgres counts characters, so non-ASCII is safe), containing control characters (U+0000–U+001F, U+007F), containing `/` or `` \ `` (path-routing breakers — servlet containers reject encoded slashes by default), or with leading/trailing whitespace. **The format is deliberately permissive beyond that** — verified against the real bundled document (2026-07-11): production apis include embedded spaces (`"Team X"`, `"Komik Cast"`, `"Mangamello Plus"`, `"Taurus Fansub"`, `"Manga Origine"`) and non-ASCII script (`"مانجا بارك"`), so an ASCII-slug regex would reject live production identifiers; all 45 bundled apis pass this gate (re-proven by the `bundled-full.json` fixture); (e) `FIELD_TOO_LONG` — any identity/denormalized value over its DB column limit (`displayName` > 256, `language` > 32, `engine` > 64, `baseUrl` > 512): a draft row physically cannot hold it, so it must be a controlled 400, never a persistence exception surfacing as 500.
- **Tier 2 — stored validation (draft row + revision ARE created; result stored and returned):** every §8 semantic rule (endpoints, fields, transforms, filters, strategy references, icon rules, secret safety, endpoint completeness…). Overlap note: rule 3 (`api` non-blank) exists in BOTH tiers by design — the authoring gate pre-empts it structurally; the validator keeps it because whole-document/import validation and the future shared-module extraction need the pure rule regardless of the HTTP tier.
- **Import:** the whole-document 422 all-or-nothing gate (§12.2) subsumes both tiers — a stanza failing ANY Tier-1 check fails the import's validation phase and nothing persists.

`StructuralGateIT` proves: each Tier-1 violation → 400 with the named code and zero rows persisted; an oversized `displayName` → 400 (not 500); a Tier-2-invalid draft IS stored with its inspectable validation result; every real bundled api passes the identifier gate.

**Extraction posture:** rules live as small pure functions/classes grouped by concern (`DocumentRules`, `SourceRules`, `EndpointRules`, `FieldRules`, `FilterRules`, `LifecycleMetadataRules`), each `(input, ctx) -> List<ValidationError>`, composed by `SourceConfigValidator`. When the shared-module extraction happens, this package + the model package move verbatim; only `ServerStrategyCatalog`'s whitelist data stays configurable per consumer. Port the app's validator tests (`DefaultSourceConfigValidatorTest`, `DefaultSourceConfigValidatorFilterTest` in `sources/engine/src/commonTest/`) as the seed test suite to prove rule parity.

---

## 9. Source-config lifecycle

### Server state machine (5 states) and app-vocabulary mapping (3 values)

| Server status | In served document? | Stanza `lifecycle` value | Meaning |
|---|---|---|---|
| `draft` | No | — | Authored, never published |
| `active` | Yes | `"active"` | Normal operation |
| `disabled` | Yes | `"disabled"` | App force-disables the source row every sync but keeps it on disk (saved-entry reads still work) — the mandatory "soft-off before deletion" stage |
| `retired` | Yes | `"removed"` | App deletes the `sources` row (saved library untouched); stanza stays in the document for a grace window so every client observes the removal. Re-seeding on a later reappearance is **engine-dependent** (verified in `SourceCatalogSyncRepositoryImpl`): a returning **generic** stanza IS re-seeded (`seedIfGeneric` — as a fresh row, `isEnabled = enabled && lifecycle=="active"`, i.e. disabled-by-default; user re-enables); a **legacy** stanza is NEVER re-seeded ("a legacy source with no row stays invisible") — this asymmetry drives the un-retire rule below |
| `removed` | **No** | — | Terminal. Stanza dropped from the document. Never silently active again (server refuses `removed → *`) |

The app understands only `active|disabled|removed` (validated whitelist, `SUPPORTED_LIFECYCLES`); `draft` and `retired` are server-side statuses, mapped as above at document-assembly time.

**Stored revision content is lifecycle-NEUTRAL (normative — one strict rule):** the canonical content in `source_config_revisions` ALWAYS carries the neutral `lifecycle = "active"` — which, under `kcj-1` default-omission, means the `lifecycle` key is **absent** from the stored canonical bytes (`"active"` is the model default; an explicitly-authored `"active"` normalizes to the same bytes). Server lifecycle lives ONLY in `source_configs.status`; `DocumentAssemblyService` substitutes the real served value (`active` → omitted / `disabled` / retired-as-`removed`) at materialization. Rationale: without normalization, the same semantic content imported with `lifecycle:"disabled"` would compare different from its stored-neutral twin → false "updated" diffs, non-idempotent re-imports, and server state leaking into immutable content history. (The real bundled document carries ZERO explicit `lifecycle` keys — verified 2026-07-11 — so today this is invariant-protection; it becomes load-bearing the moment a served document, which DOES carry explicit `disabled`/`removed` values, is round-tripped back through import.) Bundled import reads the incoming lifecycle **separately** and uses it ONLY to (a) set the initial server status of NEWLY created sources, or (b) report `lifecycleConflicts` on existing ones — it is normalized away before canonical comparison, checksum, and revision storage (§12.2). `LifecycleNeutralStorageIT` proves: stored canonical bytes contain no `lifecycle` key; import of a `disabled` stanza stores neutral content + initial status `disabled`; re-import is a no-op; the assembled document injects the real values.

**Allowed transitions** (anything else → 409 `INVALID_LIFECYCLE_TRANSITION`):

```
draft    --publish(valid revision)-->  active
active   --disable-->                  disabled
disabled --enable-->                   active
disabled --retire-->                   retired        (direct active→retired REJECTED: soft-disable is mandatory,
                                                       honoring §12.4 "disabled in the document before ever dropped")
retired  --remove(confirm)-->          removed        (terminal — removed → * is always refused)
retired  --enable-->                   active         (un-retire: **engine="generic" ONLY**, see below)
draft    --(new revisions freely)-->   draft
```

**Publish × status (explicit — publishing content NEVER implicitly re-enables):** `draft` + first valid publish → `active`; publish on `active` → stays `active`; publish on `disabled` → stays `disabled`; publish on `retired` or `removed` → **409**.

**Un-retire is engine-conditional (proved against the real app sync, not assumed):** `SourceCatalogSyncRepositoryImpl` deletes the row on `lifecycle:"removed"`; when the stanza later returns to `"active"`, a **generic** source is re-seeded by `seedIfGeneric` (fresh disabled-by-default row — degraded UX: users who had it enabled must re-enable — but fully functional), while a **legacy** stanza is never seeded from config, so an un-retired legacy source would stay permanently invisible on every client that synced during the retirement window — a silent, unfixable client inconsistency. Therefore `retired → active` is allowed only when the source's published revision has `engine == "generic"` (documented reseed consequence in SOURCE_CONFIG_LIFECYCLE.md + covered by `RetiredSourceVisibilityIT`); for legacy/kotlin engines it is 409 `UNRETIRE_UNSUPPORTED_FOR_ENGINE`, and their only path out of `retired` is `removed`.

**Revision semantics:** per-source `revision_number` starts at 1, strictly increases, is never reused or mutated. Exactly one `published` revision per source (partial unique index); publishing supersedes the previous one. **Rollback = forward-roll:** copying revision *n*'s payload into a new revision (latest+1), re-validating (rules may have tightened since), and publishing it — history is immutable and revision numbers only grow, which keeps the app-side "higher revision wins" contract honest.

**Which revision states are publishable (normative):**

- The **currently published** revision → `200` idempotent no-op; no state change, no new document snapshot.
- A **`superseded`** revision → **409 `REVISION_SUPERSEDED`**, always. Old content is restored ONLY via the rollback endpoint (forward-roll above) — directly re-publishing a superseded row would rewind per-source history and break the strictly-increasing contract.
- A **`draft`** revision → publishable only when `revision_number` > the currently published revision number (drafts created before the current publication are stale by definition → **409 `REVISION_OLDER_THAN_PUBLISHED`**; their content is restorable via rollback, which re-validates it as a fresh revision).
- Any revision of a `retired`/`removed` source → 409 (the §9 publish × status matrix above).

**In-transaction ordering (normative — protects `uq_one_published_per_source`):** inside the single publish transaction, the previous revision is flipped `published → superseded` FIRST, and only then is the candidate flipped `draft → published`. Postgres enforces the partial unique index per statement (it is not deferrable here), so the reverse order would fail with a constraint violation while two rows are momentarily `published`. Concurrent publishes of two drafts for the same source serialize on the §9 lock order (global publication lock → source row lock): the first committer wins; the second then re-reads state under the lock and either succeeds against the new baseline (its draft is still newer) or gets the deterministic 409 above — the partial unique index is never violated and there is exactly one successful publication path per race. `PublishStateRulesIT` + `ConcurrentSameSourcePublishIT` prove all of this (§11 tests 47–48).

**Document snapshot — globally serialized (the per-mutation transaction alone is NOT enough):** without global ordering, two concurrent mutations (publish source A ∥ publish source B) each assemble a candidate from a snapshot that predates the other's commit — the later document revision silently *loses* the earlier source's change from the served snapshot (a classic lost update that read-committed isolation does not prevent). Therefore **ALL state-visible document mutations — publish, disable, enable, retire, remove, rollback, bundled import, and `republish` — run this exact sequence inside one transaction**:

1. Lock the singleton `document_publication_state` row (`SELECT … FOR UPDATE`) — the **global publication lock**; concurrent mutators queue here.
2. Lock the affected `source_configs` row(s) (`FOR UPDATE`) where the mutation touches per-source state (always lock in a deterministic order: global lock first, then source rows — no deadlock is possible because every writer takes the global lock first).
3. Apply the mutation (revision insert / status change / import batch).
4. Read the authoritative current state (all published revisions + statuses) **under the lock** — it cannot be stale, because every other writer is queued behind step 1.
5. Assemble the full candidate document — all sources in `active|disabled|retired`, **ordered by `(position ASC, api ASC)`** (§5 source ordering; never DB result order), each rendered from its published revision's **lifecycle-neutral** content with the real lifecycle value injected per the mapping above.
6. Validate it whole (§8 rule 29).
7. Take **one instant** from the injected Clock, truncated to ISO-8601 UTC seconds (§5 precision); set it as `generatedAt`; serialize canonically (§5, `kcj-1`) and compute SHA-256.
8. Insert the `published_documents` row with the next `document_revision` from the sequence and `created_at` = **that same instant** (the column has no DB default — application time and DB time cannot diverge; the same instant also goes into the publication audit detail).
9. Update `document_publication_state.latest_document_revision` (the FK to the just-inserted snapshot holds).
10. Commit (lock released; the new snapshot becomes the served latest atomically).

Failure anywhere rolls the whole mutation back — the served document can never be invalid, torn, or missing a concurrent change. A Postgres advisory transaction lock (`pg_advisory_xact_lock`) would be an acceptable equivalent for step 1, but the singleton row is preferred: it is visible in the schema, testable, and doubles as the latest-revision pointer. `ConcurrentDifferentSourcePublishIT` proves the invariant: two truly concurrent publications to two different sources → the final latest snapshot contains BOTH changes.

**Checksum & ETag:** ETag = strong quoted document checksum (`ETag: "a1b2…"`); `If-None-Match` containing it → 304 (weak `W/"…"` validators never match — §4.1). Checksum also surfaces in `X-Config-Checksum` and `/document/meta`; the app recomputes it before signature and rollback acceptance. `generatedAt` and the snapshot row's `created_at` are **the same application-controlled instant** (steps 7–8 above; injected Clock, ISO-8601 UTC, seconds precision; no DB `now()` default anywhere in the snapshot path) and are included in the signed metadata.

**Empty document — allowed, consequences documented:** publishing a document with ZERO sources (after removing the final published source) is legal — it is the truthful terminal state, and reaching it already requires walking every source through `disabled → retired → removed(confirm)`, so no extra destructive confirmation is invented. Whole-document validation (§8 rule 29) accepts zero sources; under `kcj-1` default-omission the empty `sources` list is simply absent from the canonical bytes (it equals the model default `emptyList()`), and the app parses that back to an empty list. **App-side impact (verified 2026-07-11):** the app can never be blanked by an empty remote document — `RemoteSourceConfigManager.refresh()` ALWAYS folds the bundled document in first (`mutableListOf(bundledDocument)`), `ConfigMerger.merge` unions per-api (an empty higher-precedence document overrides nothing), and the catalog sync's `forceDisableNonConfigRows` is guarded on a non-empty generic set. The only effect is the revision ratchet: the app's accepted floor rises to the empty document's revision. `EmptyDocumentPublishIT` covers it.

**Signature (implemented in V7):** every new snapshot carries an Ed25519 detached signature over the
versioned `kira-source-signature-v1` input: revision, predecessor revision/checksum, current checksum,
creation time, and exact `kcj-1` bytes. V7 adds the signature, signing-key id, and predecessor metadata
without rewriting prior migrations. Production startup requires a matching PKCS#8 private key and
X.509 public-key entry. The public document endpoint, metadata endpoint, and discovery endpoint expose
verifiable metadata; discovery is not a trust root. The app selects a locally pinned public key by id,
recomputes the checksum, verifies the signature, and rejects tampering, unknown keys, replay, and
rollback. Rotation keeps old and new public keys in an explicit overlap window. See
`SOURCE_DOCUMENT_SIGNING.md`.

---

## 10. Completion service

The service remains provider-agnostic, but its admission, lifecycle, and retention paths are complete
for production use. It is disabled by default.

- **Port (domain):** `interface CompletionProvider { val name: String; fun complete(prompt: String, model: String): CompletionOutcome }` where `CompletionOutcome` = `Success(result: String, latencyMs: Int)` | `Failure(error: String)`. No Spring types in the interface.
- **Providers:** `EchoCompletionProvider` is available only in explicit `dev`/`test` profiles.
  Production uses the generic HTTPS provider adapter, configured through environment-only endpoint,
  model, and API-key values. Enabling completion in production without a valid HTTPS provider fails
  startup; disabling the feature requires no provider credential.
- **Selection:** `kira.completion.provider` selects a bean from the injected provider set; unknown or
  unsafe production selection fails startup. Controllers know only `CompletionService`;
  `CompletionService` knows only the port. Provider secrets never appear in an API response, stored
  error, audit entry, or application log.
- **Transaction & failure boundaries (normative — a DB transaction is NEVER held open across a provider call):** (1) short tx: insert `completion_requests` row `PENDING` → commit; (2) short tx: update to `RUNNING` → commit; (3) invoke the provider **outside any DB transaction** on a bounded executor (`kira.completion.executor-threads`, default 8; `queue-capacity`, default 64), wrapped in a timeout (`kira.completion.timeout`, default 30s) — a slow/hung provider must not pin a connection-pool slot or a row lock, and executor saturation becomes a stored `FAILED` result with `PROVIDER_UNAVAILABLE`; (4) short tx: store the sanitized outcome — `SUCCEEDED` with the result truncated to `kira.completion.max-result-length` (default 100 000 chars, truncation recorded), or `FAILED` with a **sanitized client-visible message + stable error code** (below). A crash between (2) and (4) leaves a `RUNNING` row — harmless, visible, and exactly why the status exists. The request timeout includes time spent waiting in the bounded queue. The **echo provider goes through this same orchestration path** (queue, timeout, truncation, sanitization, error mapping) so a future real provider changes zero orchestration code.
- **Stable error-code catalog (normative — persisted in `completion_results.error_code`, exposed in the API):** a bounded enum, not free text: `PROVIDER_TIMEOUT` (the §10 timeout elapsed), `PROVIDER_UNAVAILABLE` (connect/transport failure), `PROVIDER_REJECTED` (the provider refused the request), `INVALID_PROVIDER_RESPONSE` (unparseable/contract-violating provider output), `RESULT_TOO_LARGE` (result over the max even for truncation policy — when truncation is disallowed), `INTERNAL_COMPLETION_ERROR` (anything else — **every unknown/unexpected exception maps here**, never to a leaked message). Failure responses expose both fields: `{"errorCode": "PROVIDER_TIMEOUT", "error": "The completion request could not be completed."}` — the `error` message is sanitized, bounded, and generic; the `errorCode` is the machine-actionable part. **Raw provider exceptions are never returned to clients and never stored** — stack traces and provider internals appear only in secured server logs (request-id-correlated, §6 logging). Successful requests carry `error_code = NULL` and `error = NULL` (DB CHECK-enforced, §5); a failed request never carries a `result` (CHECK `chk_result_xor_error`). `CompletionErrorTaxonomyIT` (§11 test 49) proves timeout, provider rejection, unexpected-exception mapping, and response sanitization.
- **Data hygiene & retention:** prompts/results live only in the two completion tables and are never
  written to audit rows or logs. A scheduled, bounded cleanup expires abandoned in-flight work and
  deletes terminal prompt/result rows after `kira.completion.retention` (seven days by default).
- **Persistence:** every accepted call records its request and terminal result/error in short
  transactions; no database transaction spans a provider call. Cancellation and timeout transitions
  are compare-and-set guarded so late workers cannot overwrite terminal state.
- **Admission:** atomic per-user/global rolling limits, daily per-user quota, global concurrency,
  executor capacity, and separate queue/provider timeouts reject overload predictably with 429 or 503
  plus retry guidance. Redis Lua coordination is mandatory for multiple instances; a bounded in-memory
  implementation is permitted only for an explicitly declared single-instance topology. Coordination
  failure denies new work.

---

## 11. Testing plan

**Tooling:** JUnit 5 + Spring Boot Test + MockMvc (+ spring-security-test's `jwt()` post-processor) + **Testcontainers PostgreSQL** — recommended over H2 because the schema leans on Postgres-specific behavior (jsonb columns, partial unique indexes, identity columns, timestamptz semantics); H2's Postgres compatibility mode diverges exactly where this schema is interesting, so H2 green would prove nothing. One shared static container (singleton pattern) keeps the suite fast; `@ServiceConnection` wires it. Pure-unit tests (validator, JWT service, state machine) run without Spring context and form the bulk.

**Unit tests (no Spring context):**
1. `SourceConfigValidatorTest` — a fully valid document (fixture modeled on the real Azora stanza) passes with zero errors.
2. Validator rejections, one test per rule family (ports of the app's engine tests): unsupported `schemaVersion`; blank/duplicate `api`; blank `language`; non-http `baseUrl`; unknown `engine`; unknown `siteState`/`lifecycle`; non-bare host in each of the three host lists (incl. the `host:8080` port case); icon: bad `resourceKey`, non-https `remoteUrl`, empty block; filters on a legacy engine.
3. Generic-source rejections: unknown pagination type; missing home+featured; blank endpoint url; raw `{query}` in url and in jsonBody; unknown method/format; unknown listFilter op/mode; unknown transform fn; unknown dateStrategy; **any** imageStrategy (empty whitelist); each filter rule §8 items 16–27 — invalid id regex, duplicate id, type pinning for `sort`/`genres`, options presence/absence, duplicate option values, every defaults case (multiselect `default` misuse, non-option default, non-boolean toggle, non-numeric number, required-without-default), every encode↔target and encode↔type incompatibility, reserved-var shadowing, path-target-without-default, appliesTo to a missing endpoint, form-target collision with formBody, body-json missing placeholder, query param hardcoded, visibleWhen unknown/self/empty-anyOf/out-of-vocabulary, excludeOf non-multiselect/chained/overlapping-defaults, and a visibleWhen **dependency cycle**.
4. `LifecycleStateMachineTest` — every allowed transition succeeds; every disallowed one (esp. `active → removed` skipping retired, and anything from `removed`) throws.
5. `CanonicalJsonTest` — canonical bytes are deterministic; defaults are omitted; **two semantically-equal documents authored with different map key orders (headers/endpoints/fields) canonicalize to identical bytes** (the `kcj-1` key-sort guarantee, §5); checksum is stable across parse→serialize round-trips; a re-parsed canonical document is semantically equal to the input model (shape-parity guarantee); no trailing newline, no insignificant whitespace.
6. `JwtServiceTest` — issue→decode round-trip; expired token rejected; tampered signature rejected; role claim mapped.
7. `PasswordHashingTest` — BCrypt hash verifies; hash ≠ plaintext; two hashes of same password differ (salt).
8. `EchoCompletionProviderTest` — echoes prompt, records model.
8b. `ContractInventoryTest` — parity protection for the mirrored contract: field-name/type inventory of every §7 data class (via serialization of a fully-populated instance), default values, enum vocabularies (siteState, lifecycle, filter types/targets/encodes, methods, formats), the transform whitelist, date-strategy whitelist, pagination whitelist, and the **empty** image-strategy whitelist — any drift from the app's `SourceConfig.kt`/`DefaultStrategyRegistry` fails loudly. Seed the validator suite by **porting the app's real tests** (`DefaultSourceConfigValidatorTest`, `DefaultSourceConfigValidatorFilterTest` in `sources/engine/src/commonTest/`) rather than recreating approximate coverage.

**Spring integration tests (Testcontainers, MockMvc):**
9. `AuthFlowIT` — register → login success returns a decodable token with USER role; login with wrong password → 401; duplicate register → 409.
10. `SecurityMatrixIT` — anon `GET /source-config/document` → 200 (once published) ; anon `POST /completions` → 401; USER token on `/admin/sources` → 403; ADMIN token → 200; anon `/auth/me` → 401.
11. `AdminSeederIT` — with seed env set, exactly one ADMIN exists after startup; idempotent on restart.
12. `SourcePublishFlowIT` — create source (valid fixture) → validate → publish → 200; the stanza appears in `GET /source-config/document` with `lifecycle:"active"`; document revision incremented; checksum matches SHA-256 of body bytes.
13. `PublishInvalidFailsIT` — publish a revision with an invalid endpoint (raw `{query}`) → 422 with the pinpoint error; document unchanged (same revision + ETag as before).
14. `DisableRemoveVisibilityIT` — disable → stanza present with `lifecycle:"disabled"` (NOT absent, NOT active); retire (from disabled) → stanza `lifecycle:"removed"`; remove → stanza absent from document AND `GET /sources/{api}` → 410; attempt `active → retired` directly → 409; attempt `active → removed` directly → 409.
15. `RollbackIT` — publish r1, publish r2 (changed baseUrl), rollback to 1 → new r3 created with r1's payload, published; document serves r1's baseUrl; revision numbers strictly increased; document revision increased; rollback of a *disabled* source leaves it disabled (no lifecycle restoration).
16. `ETagIT` — `GET document` → 200 + quoted strong ETag; repeat with `If-None-Match` → 304 empty body with the same ETag; after any publish → new ETag, conditional GET → 200 again. (Historical snapshots are admin-only: `GET /admin/documents/{revision}` returns the exact stored bytes + that snapshot's checksum; the public endpoint has no `revision` param.)
17. `ImportBundledIT` — import a trimmed real bundled fixture (2 generic + 2 legacy stanzas) → sources created + published + document serves them **in payload order** (positions assigned per §12.2); re-import of the identical payload → no-op per §12.2 (zero new per-source revisions, zero new snapshots); import with one bad stanza → 422, nothing persisted; incoming `revision`/`generatedAt` do NOT drive server revision allocation; a terminally `removed` source is not revived by import; changed content for a `retired` source → `skippedRetired`, nothing stored; an existing draft-only source → `skippedDraft`, with no new revision or snapshot and no accidental publication.
18. `CompletionIT` — anon → 401; USER posts prompt → 201, row in both tables, provider `echo`, result `echo: …`; owner can `GET` it; a different USER gets 404 for it; ADMIN can read it.
19. `AuditLogIT` — publish + disable write audit rows with actor, action, entity; audit `detail` contains **no config bodies, header values, or prompts**.
20. `FlywayMigrationIT` — context boots against a clean container (implicitly validates all migrations); `flyway_schema_history` contains **exactly the migrations that exist at the current phase, in version order** (the expectation grows with the build: V1..V3 when it first runs in Phase 5, V1..V4 from Phase 6, and its FINAL form — exactly V1 users, V2 source_config, V3 published_documents, V4 audit_log, V5 completions, in order, `outOfOrder=false` — from Phase 9 onward). It must never reference a migration its phase hasn't created.

**Amendment-mandated tests (each names the invariant it protects):**

21. `ConcurrentDifferentSourcePublishIT` — *no lost document updates*: two concurrent publications to two different sources (real parallel threads/transactions) → the final latest snapshot contains BOTH stanzas (§9 global publication lock).
22. `ConcurrentSameSourceRevisionIT` — *per-source revision numbers unique & increasing under concurrency*: N concurrent revision creations for one source → N distinct consecutive numbers, no constraint violation surfaced to the caller (§5 source-row lock).
23. `ImportCreatesSingleSnapshotIT` — *one import = at most one snapshot*: importing a multi-source document creates exactly ONE `published_documents` row, not one per source (§12.2).
24. `ImportNoChangesIsNoOpIT` — *idempotent re-import*: importing an identical document again creates zero revisions and zero snapshots and reports everything `unchanged` (§12.2) — including when stanzas carry explicit non-neutral `lifecycle` values (the §9 lifecycle-neutral normalization is what makes this idempotency hold).
25. `StrictAdminParserIT` — *authoring typos cannot silently pass*: unknown field (e.g. `usesCaptureHeaders`), duplicate JSON key, trailing garbage, malformed JSON → 400 each with the offending token named; the same payloads pass the COMPATIBILITY import parser where the app would accept them (§7).
26. `ServerManagedLifecycleIT` — *payload lifecycle never controls server lifecycle*: authoring bodies with `lifecycle:"disabled"|"removed"` → 400 `LIFECYCLE_NOT_AUTHORABLE`; import payload lifecycle maps only per §12.2; rollback does not restore an old lifecycle (§9).
27. `PathApiMismatchIT` — *api identity is immutable and path-bound*: `body.api != {api}` → 400 `API_ID_MISMATCH` on revision creation.
28. `EndpointCompletenessIT` — *no broken generic source can publish*: generic stanza missing `search`/`details`/`pages`/both-home-and-featured → 422 with the matching `GENERIC_MISSING_*` code; a stanza WITHOUT `chapters` publishes fine; legacy stanzas unaffected (§8 rule 31).
29. `PublicConfigSecretsRejectedIT` — *no credential material can be published*: `Cookie` header → rejected; `authorization: "Bearer real-token-xyz"` → rejected; `authorization: "Bearer null"` → ACCEPTED (the bundled placeholder); URL with user-info → rejected; and the FULL real bundled document passes all secret-safety rules (§8 rule 32).
30. `RetiredSourceVisibilityIT` — *retired is visible-as-removed, and un-retire is engine-gated*: retired stanza stays in the document as `lifecycle:"removed"` and `GET /sources/{api}` → 200 with that stanza; `retired → active` succeeds for a generic source and → 409 `UNRETIRE_UNSUPPORTED_FOR_ENGINE` for a legacy source (§9).
31. `RemovedCannotReturnIT` — *removed is terminal*: every transition from `removed` → 409; import cannot revive it; its stanza never reappears in any later snapshot.
32. `FullBundledParityIT` — *the real production document survives the whole pipeline*: parse the FULL `bundled-full.json` (45 sources: 12 generic + 33 legacy, schemaVersion 1, revision 4) → validate whole (zero errors) → canonicalize → re-parse canonical → semantic equality; source count and every `api` identity preserved; import it transactionally; serve it; served bytes re-checksum correctly (§12).
33. `HistoricalRevisionChecksumIT` — *stored canonical bytes are the reproducible source of truth*: persist a revision, reload `config_canonical_json` cold, recompute SHA-256 → equals the stored checksum byte-for-byte (§5).
34. `RawBytesChecksumIT` — *what is served is what was checksummed*: fetch the public document, hash the raw response bytes → equals the `ETag`/`X-Config-Checksum` (no message-converter re-serialization drift) (§4.1).
35. `IfNoneMatchVariantsIT` — *conditional-GET correctness*: `If-None-Match: *` → 304; multiple comma-separated ETags including the current → 304; non-matching list → 200; a **weak validator `W/"<current-hash>"` → 200** (strong comparison never matches weak validators, §4.1); 304 responses carry no body (§4.1).
36. `RealBearerTokenIT` — *the real decoder path works end-to-end*: obtain a token via actual `POST /auth/login`, call a protected endpoint with it through the real `NimbusJwtDecoder` (not the mocked `jwt()` post-processor); tampered audience/issuer/expiry variants → 401 (§6).
37. `DisabledUserAuthIT` — *disable is effective immediately, on EVERY protected endpoint*: disable a user; their previously-issued valid-signature token → 401 on the next request to **each** of `/auth/me`, completion read AND write, and an admin endpoint (for a disabled ADMIN) — proving the check lives in the authentication pipeline (jwtAuthenticationConverter), not in a per-controller argument resolver; login refused with the generic message; and a DB-side role change takes effect against a token still carrying the old role claim (authorities come from the DB, §6).
38. `AuthenticationRateLimitIT` — *throttling engages and resets*: repeated failed logins for one account/IP → 429 with generic body; window expiry or success resets; a different account+IP is unaffected (no cross-account lockout) (§6).
39. `FlywayIncrementalOrderIT` — *migrations apply in phase order without `outOfOrder`, from every meaningful baseline*: (a) migrate V1..V3 only (the Phase-5 world), then apply V4+V5 on top — accepted; (b) migrate V1..V4 only (the Phase-6 world), then apply V5 — accepted; the reverse (a lower version appearing after a higher one is applied) is the hazard this ordering rule prevents (§5). **Runs from Phase 9 onward** (it needs V5 to exist — it cannot be listed in an earlier phase's gate).

40. `DocumentOrderDeterminismIT` — *one normative source order*: the same source set returned by the repository in multiple shuffled orders assembles to byte-identical canonical documents with identical checksums/ETags; assembled order is `(position ASC, api ASC)`; a bundled import serves stanzas in payload order (§5 source ordering).
41. `StartupConsistencyIT` — *the revision floors and the latest pointer are validated at startup, never silently repaired*: fresh empty DB (pointer NULL, no snapshots, sequence at seed) → starts; existing snapshots with consistent pointer → starts; `minimum-server-revision <= bundled-revision-floor` → fails fast; sequence-next < `minimum-server-revision` → fails fast; pointer ≠ MAX(document_revision), a snapshot above the pointer, pointer NULL with snapshots present, or sequence-next ≤ latest revision → fails fast with the recovery-runbook message (§5).
42. `ConcurrentLastAdminDisableIT` — *the last-admin guard holds under concurrency*: with exactly two enabled admins, two truly concurrent disable requests (one per admin) → exactly one succeeds and one gets 409; at least one enabled ADMIN always remains (§4.4 `security_state` lock).
43. `SnapshotTimestampConsistencyIT` — *one snapshot timestamp everywhere*: publish → the document's `generatedAt`, the `published_documents.created_at` row value, and the publication audit detail all carry the identical instant (ISO-8601 UTC seconds); the column has no DB default (§9 steps 7–8).
44. `StructuralGateIT` — *identity defects are 400s that persist nothing; semantic defects are stored drafts*: each Tier-1 violation (`API_IDENTIFIER_INVALID` variants incl. control chars / slash / 129 chars / trailing space, `LIFECYCLE_NOT_AUTHORABLE`, `API_ID_MISMATCH`, `FIELD_TOO_LONG` per column) → 400 with zero rows persisted; oversized `displayName` → 400 not 500; a Tier-2-invalid draft IS created with its stored validation result; every real bundled `api` (incl. `"Team X"`, `"مانجا بارك"`) passes the gate (§8 two-tier model).
45. `LifecycleNeutralStorageIT` — *stored content never carries server lifecycle*: stored canonical revision bytes contain no `lifecycle` key; importing a stanza with `lifecycle:"disabled"` stores neutral content + initial status `disabled`; re-importing it is a no-op; the assembled document injects `disabled`/retired-as-`removed` at materialization only (§9, §12.2).
46. `EmptyDocumentPublishIT` — *removing the last source yields a valid, served, empty document*: walk the only published source through disable→retire→remove → a new snapshot publishes with zero sources (canonical bytes omit the default-empty `sources`), `/document` serves it with a fresh ETag/revision, `/document/meta` reports it, and re-parsing yields an empty source list (§9 empty-document rule).
47. `PublishStateRulesIT` — *only the right revisions are publishable*: re-publishing the currently published revision → 200 no-op with NO new document snapshot; publishing a `superseded` revision → 409 `REVISION_SUPERSEDED`; publishing a draft older than the current published revision → 409 `REVISION_OLDER_THAN_PUBLISHED`; rollback restores that older content as a NEW highest revision (never revives the old row); the one-published-per-source partial unique index holds throughout (§4.3/§9 publishable-states rules).
48. `ConcurrentSameSourcePublishIT` — *concurrent publishes for one source serialize with one deterministic winner*: two truly concurrent publish calls for two drafts of the same source → both serialize on the §9 locks, the partial unique index is never violated, exactly one publication path succeeds per race (the second either publishes against the new baseline or receives the deterministic 409), and the final served document reflects the winner (§9 in-transaction ordering).
49. `CompletionErrorTaxonomyIT` — *failures map to stable codes and sanitized messages*: a provider timeout → `FAILED` + `error_code = PROVIDER_TIMEOUT`; a provider rejection → `PROVIDER_REJECTED`; an unexpected provider exception → `INTERNAL_COMPLETION_ERROR` with a generic bounded `error` message, no stack trace or exception class name in the response OR the row; success rows carry NULL `error_code`/`error`; no row ever has both `result` and `error` (DB CHECK) (§10 catalog).
50. `ClientIpResolutionIT` — *throttling cannot be spoofed or unbounded*: with trusted-proxy mode OFF (default), a spoofed `X-Forwarded-For` neither escapes the sender's throttle bucket nor pollutes the spoofed victim's; with trusted-proxy mode ON and the peer in `trusted-proxies`, the forwarded client IP is used; malformed/oversized forwarding headers fall back safely to the remote address; the store enforces max-entries with deterministic eviction, TTL expiry, and reset-after-success (§6 trusted client-IP resolution).

---

## 12. Migration plan (bundled JSON → remote) — summary

Full doc to be written as `docs/MIGRATION_BUNDLED_TO_REMOTE.md` in Phase 10 (the docs phase). Summary of the contract it will expand:

1. **The app keeps its bundled document forever** as the always-present floor (trusted via the app binary's own signature) — the backend is an *upgrade tier*, never a replacement for the floor.
2. **Server-side on-ramp — bundled-import contract (complete, normative):** seed the backend via `POST /admin/sources/import-bundled` with the current `CONFIG_BACKED_SOURCES_JSON`; the §5 two-floor model guarantees every published document carries a revision strictly above `kira.config.bundled-revision-floor` (default 4 = today's bundled revision, re-verified against the live binary at cutover) and at/above `kira.config.minimum-server-revision` (default 100 = the sequence seed). Exact semantics:
   - Parse the full document with the COMPATIBILITY parser (§7); validate the WHOLE document (§8, incl. server-additional rules AND the Tier-1 structural checks) — any error → 422, nothing persisted.
   - **Ignore the incoming `revision` and `generatedAt`** — the server exclusively controls document-revision allocation; the payload's values are recorded in the response/audit detail for provenance only.
   - **Read each stanza's `lifecycle` separately, then normalize the content to lifecycle-NEUTRAL (§9)** before any canonical comparison, checksum, or revision storage — the incoming lifecycle never enters stored content.
   - Per source, by `api`: **absent** → create, with `position` assigned from **payload order** (§5 source ordering — the served document preserves the bundled stanza order, which the app's tab ordering de-facto follows); initial server status maps from the payload's lifecycle (`"active"` → `active`, `"disabled"` → `disabled`, `"removed"` → not created at all, reported `skippedRemoved` — creating a terminal husk is pointless). **Present with a published revision** → compare **lifecycle-neutral canonical** content (§5/§9) against the currently published revision: identical → `unchanged`, no new revision; different → create + publish exactly ONE new per-source revision — **except**: a source currently `retired` or `removed` never gets content imported (publish on those statuses is 409 by the §9 state machine; silently updating what admin rules forbid is the same defect) — a content difference there is reported under `skippedRetired` / `skippedRemoved` respectively, nothing stored; a `disabled` source's content DOES import (publish-on-disabled is legal and keeps it disabled). **Present with only draft revisions** → `skippedDraft`; import never replaces or publishes that draft and stores nothing. The payload lifecycle **never overrides an existing source's server lifecycle** (lifecycle changes go through the lifecycle endpoints); a differing payload lifecycle is reported under `lifecycleConflicts`, content still imports (subject to the retired/removed/draft exceptions above).
   - A server-side terminally **`removed` source is never revived** by import (reported `skippedRemoved`).
   - All per-source changes apply **without intermediate whole-document snapshots**; after the batch, materialize **exactly ONE** snapshot via the §9 sequence (global lock, whole-doc validation). If nothing changed at all → no-op: 200 with all-`unchanged` summary and **no new document revision**.
   - Any failure rolls back the entire import. Response: `{created[], updated[], unchanged[], skippedRemoved[], skippedRetired[], skippedDraft[], lifecycleConflicts[], warnings[], documentRevision?}`.
3. **App-side acceptance chain (implemented and verified):** fetched remote must (a) have complete,
   bounded metadata from a credential-free HTTPS origin, (b) match its SHA-256 checksum, (c) pass
   pinned-key Ed25519 verification, (d) parse and pass the full validator, and (e) advance the accepted
   revision/chain without rollback. Any failure keeps the previous verified cache or bundled document.
4. **Failure semantics restated:** failed fetch → bundled/cache; unsupported `schemaVersion` → ignored (validator gate); failed checksum/signature → ignored; revisions are stable and monotonic; **no silent deletion** — a source is `disabled` in the document before it is ever `retired`/dropped.
5. **Cross-repository delivery status:** the sibling app branch implements `KtorRemoteConfigSource`,
   enables it in DI, verifies the complete signed envelope, persists the verified envelope, and safely
   falls back. A real HTTPS deployment origin and the protected production signing ceremony are
   release-environment inputs, not values that can be committed. Percentage rollout remains future
   product work.

---

## 13. Extra features bucketing

| Candidate | Bucket | Justification |
|---|---|---|
| OpenAPI/Swagger (springdoc) | **Foundation now** | One dependency + one config bean; the admin API's only "UI"; dev-profile exposure |
| Admin audit log | **Foundation now** | One table + one service call per mutation; retrofitting audit later loses history forever |
| Import-from-bundled-JSON | **Foundation now** | The migration on-ramp; without it the backend starts empty and untestable against real data |
| Export in exact app JSON shape | **Foundation now** | It IS the document endpoint (§4.1) — zero extra cost by design |
| Validation preview (validate without publish) | **Foundation now** | Already required by responsibility 4; a read-only re-run of the validator |
| ETag/checksum caching | **Foundation now** | Core of responsibility 5 |
| Remote kill switch (per source) | **Foundation now (de facto)** | `disable` transition IS the per-source kill switch; a *global* switch is future |
| Availability status | **Partial now** | `siteState` is authored data served today; *automated* status detection is future |
| Source health checks (server probes sources) | Future | Outbound scraping infra, scheduling, false-positive policy — high risk, own project |
| App-version compatibility filtering (`minAppVersion` server-side) | Future | Field is stored/served now; the app engine itself doesn't enforce it yet (reserved per KDoc); per-version response variants complicate ETag — decide with Open Q6 |
| Popularity counters | Future | Needs client telemetry design first |
| Rollout percentage / staged rollout | Future | Requires stable client identity + bucketing; owner decision |
| Feature flags | Future | No consumer yet |
| **Auth rate limiting (login/registration throttling)** | **Implemented** | Atomic TTL-bounded Redis counters for multiple instances; bounded memory backend only for declared single-instance operation; coordination failure is fail-closed |
| **Minimal admin user management** (§4.4) | **Foundation now** | Prod disables registration — without admin-created users there is no lawful onboarding path at all |
| Completion rate limits / per-user quota | **Implemented** | Per-user/global rolling windows, daily quota, global concurrency, bounded queue, independent queue/provider timeouts, and Redis multi-instance coordination |
| API keys (machine auth) | Future | JWT covers v1 consumers; key mgmt is its own surface |
| Refresh tokens | Future (seam now) | §6 seam; table design reserved in §5 |
| Document signing (Ed25519) | **Implemented** | V7 signed metadata, deterministic versioned input, key ids/rotation overlap, production startup validation, public/meta endpoints, scripts, and app-pinned verification (§9) |

---

## 14. Explicit non-goals

The released scope intentionally does not include: an admin web UI; a provider-specific AI SDK;
refresh-token issuance/rotation; staged rollout or percentage targeting; source health probing; client
telemetry/popularity; multi-tenancy; machine API keys; managed-cloud infrastructure creation; or
extraction of the validation package into a shared binary module. Kubernetes delivery manifests,
provider-neutral database recovery automation, GitHub CI/release pipelines, signed remote delivery, and
the generic production HTTPS completion adapter are implemented. Also not introduced without a
concrete need: replacing JPA (jOOQ/Exposed/raw JDBC), application data caching, a generic domain-event
system, a source dependency graph, a Gradle multi-module backend split, documents as an independent
bounded context, or asymmetric JWT signing.

---

## 15. Phased build order (implementation checklist — each phase compiles & tests green, one commit-sized step each)

1. **Scaffold** — `settings.gradle.kts`, `build.gradle.kts` (Boot 3.5.x, Kotlin plugins incl. serialization/spring/jpa, Java 21 toolchain, dependency set from §3 incl. actuator), **verify version compatibility once and record the EXACT versions** (§3 pinning rule: Gradle wrapper, Boot, Kotlin, kotlinx-serialization, springdoc, Testcontainers, Postgres image tag, Java toolchain), `KiraBackendApplication`, `application.yml` + `application-dev.yml` (incl. `spring.jpa.open-in-view=false`, `ddl-auto=validate`, graceful shutdown, actuator exposure per §6), `docker-compose.yml` (pinned postgres 17 image on 5433, named volume), `.gitignore` + `.env.example`, README stub, Flyway wired with **V1__users.sql** (incl. `uq_users_email_lower` + the **`security_state`** singleton seed, §5), Testcontainers base class + a `contextLoads` IT. *Green: `./gradlew build` with Docker running.*
2. **Common layer** — problem envelope + `GlobalExceptionHandler`, typed exceptions, `PageResponse`, `Sha256`, **`kcj-1` canonicalization** (§5) + `CanonicalJson`, correlation-ID filter + the **§6 logging & diagnostics foundation** (structured-JSON prod / console dev config, MDC fields, route-pattern + duration access logging, log-hygiene rules — the per-feature event catalog then lands with each feature phase), OpenAPI config, `@ConfigurationProperties` classes with validation. Tests: 5 (unit).
3. **User + auth + security** — user domain/entity/repo (V1 already in place), `DelegatingPasswordEncoder`, `JwtService` + Nimbus encoder/decoder (issuer + audience + skew validation), the **DB-backed `jwtAuthenticationConverter`** (per-request enabled-check + DB-derived authorities, §6) + the `CurrentUser` SecurityContext resolver, `SecurityFilterChain` per §6 matrix, `AuthThrottleService` **+ the §6 trusted client-IP resolution (forwarded headers off by default, trusted-proxy mode, bounded store)**, `AuthController` (register/login/me, password policy), **`AdminUsersController` + `UserAdminService` (§4.4, last-admin guard under the `security_state` lock)**, `AdminSeeder` (env-required, no password logging). Tests: 6, 7 (unit), 9, 10, 11, 36, 37, 38, 42, 50 (IT).
4. **Source-config model + validation (pure)** — the §7 data classes, STRICT + COMPATIBILITY parsers (§7), canonical outbound, `StrategyCatalog` + `ServerStrategyCatalog` whitelists, `PackagedIconCatalog` (§8 rule 33), the full §8 validator (incl. rules 31–33) with stable error codes, fixtures (valid Azora-style + invalid set + **`bundled-full.json`**). Tests: 1, 2, 3, 8b (unit) — the largest single phase; may split model/validator into two commits.
5. **Source-config persistence** — **V2__source_config.sql** (canonical TEXT column, `position`, `canon_version`, composite FK via ALTER TABLE, RESTRICT FKs) + **V3__published_documents.sql** (+ sequence + `canon_version` + **`document_publication_state`** singleton with the pointer FK), entities, Spring Data repos, port adapters, domain↔entity mapping, `LifecycleStateMachine` (§9 incl. engine-gated un-retire), the startup validators (§5 two-floor checks + `PublicationStateStartupValidator`). Tests: 4 (unit), 20 (V1..V3 expectation — §11), 33, 41 (IT). **Tests 20-final and 39 do NOT run here — V4/V5 don't exist yet (§11).**
6. **Admin management** — `SourceAdminService` + `DocumentAssemblyService` (global publication lock + the 10-step §9 sequence: `(position, api)` ordering, lifecycle-neutral content + injected lifecycle, the single Clock instant for `generatedAt`/`created_at`, empty-document support), all §4.3 endpoints except import (incl. the full Tier-1 structural gate §8, and the **§9 publishable-revision-states rules with supersede-then-publish ordering**), **V4__audit_log.sql** + `AuditService` wired into every mutation; test 20's expectation extended to V1..V4. Tests: 12, 13, 14, 15, 19, 21, 22, 26, 27, 28, 29, 30, 31, 40, 43, 44, 45 (non-import halves), 46, 47, 48 (IT).
7. **App-facing API** — `SourceDocumentController` (latest + `/meta`, raw-bytes writer, ETag/304/Cache-Control/X-headers; **no public historical revisions**), `SourcesController` (list + by-api per §4.1 status mapping). Tests: 16, 34, 35 + the visibility halves of 14 (IT).
8. **Bundled import** — `import-bundled` endpoint on top of the Phase-6 services implementing the §12.2 contract (lifecycle-separately-read + neutral normalization, payload-order positions, `skippedRetired`/`skippedRemoved`/`skippedDraft`), trimmed + full real fixtures. Tests: 17, 23, 24, 25, 32, 45 (import halves) (IT).
9. **Completion foundation (original phase)** — **V5__completions.sql** (incl. `error_code` + the
   `chk_result_xor_error`/error-pairing CHECKs, §5), provider port, dev/test echo, property selection,
   three-transaction orchestration, bounded executor, timeouts, sanitization, stable error codes, and
   controller. Production hardening subsequently added the HTTPS provider, Redis/in-memory atomic
   admission implementations, rate/quota/concurrency controls, independent queue/provider timeouts,
   cancellation guards, and scheduled retention. The current Flyway gate validates all migrations,
   including the later signing migration.
10. **Hardening + docs** — full-suite pass, security-matrix sweep against every route, log-hygiene sweep (no bodies/Authorization/passwords/header-values/prompts in logs, §6 rules), then write `README.md`, `docs/API.md`, `docs/SOURCE_CONFIG_LIFECYCLE.md`, `docs/SECURITY.md` (**incl. the §6 log/completion-data retention + privacy expectations**), `docs/LOCAL_DEV.md`, `docs/MIGRATION_BUNDLED_TO_REMOTE.md`.

(Phases 4–5 could swap order internally, but keep validation before persistence so entity design is informed by the final model. Phases 7 and 8 depend on 6; 9 is independent after 3 and can run any time after it.)

---

## 16. Open questions for the owner (each with a safe default so implementation is NOT blocked)

1. **Document signature scheme** — Ed25519 detached signature over the canonical bytes, key from env? *Default: do not sign in v1 (app-side remote requires a verifier that currently denies everything anyway); keep the nullable column out until decided. Recommend Ed25519 when built.*
2. **Real completion provider choice** (Anthropic? other? none?) — *Default: echo provider only; interface is provider-agnostic; no SDK dependency added.*
3. **Access-token TTL** — *Default: 60 minutes (`kira.security.access-token-ttl=PT60M`), configurable; refresh tokens remain future work.*
4. **Admin seeding when env vars are absent** — **RESOLVED by amendment #13 (no longer open):** startup fails with a clear message in every profile where seeding is enabled; local dev supplies `KIRA_ADMIN_EMAIL`/`KIRA_ADMIN_PASSWORD` via the gitignored `.env` (`.env.example` documents it). Plaintext passwords are never logged, in any profile.
5. **Open registration** — should `POST /auth/register` be publicly enabled? *Default: property `kira.auth.registration-enabled`, `true` in dev, `false` in prod.* **The prod onboarding mechanism is now concrete: admin-created users via §4.4** (amendment #14).
6. **Server-side `minAppVersion` filtering** — filter stanzas per `?appVersion` (per-variant ETags) or serve one document to all clients? *Default: serve one document; store/serve `minAppVersion` as data; accept + log `appVersion`. Matches the app engine, which does not enforce the field yet.*
7. **Document-revision seed value** — **RESOLVED (amendment #18, re-resolved by third-pass Finding 1 into the two-floor model, §5):** sequence starts at 100; `kira.config.bundled-revision-floor` (default 4 = the revision shipped in the live binary, re-verified at every cutover/app release) and `kira.config.minimum-server-revision` (default 100) with exact comparisons — published revisions strictly `>` the bundled floor; sequence-next `>=` the server minimum (the first generated value IS 100 and is legal); startup fails fast on any violation (`StartupConsistencyIT`). The app-side acceptance rule is the inclusive `revision >= bundledDocument.revision` (verified in `RemoteSourceConfigManager.refresh()`), so the strict server floor composes safely. Revisions are unique and strictly increasing but **may contain gaps** (sequence semantics) — nothing may assume contiguity.
8. **CORS / callers of the admin API** — any browser-based admin tooling planned? *Default: no CORS headers (curl/server-to-server only); adding a locked-down allowlist later is one config bean.*
9. **Retired-stanza grace window** — how long does a `retired` source stay in the document as `lifecycle:"removed"` before the terminal `remove`? *Default: manual/admin-judged (no automatic expiry); document the recommendation of ≥ 2 app-release cycles.*
10. **Postgres hosting/versions for prod** — *Default: develop and test against PostgreSQL 17 (docker-compose + Testcontainers pin the same major).*

---

## Appendix A — Amendment review outcomes (second-pass review, 2026-07-11)

24 amendments were adjudicated against this plan and the real mobile-app code at `/Users/abdelrahman/Projects/Kira manga` (read-only). Legend: **ACCEPTED** (incorporated as proposed), **MODIFIED** (incorporated with substantive corrections), **REJECTED** (not incorporated).

| # | Outcome | Summary |
|---|---|---|
| 1 | ACCEPTED | Flyway reordered: V4 audit_log (Phase 6), V5 completions (Phase 9) — versions now append in build order; `outOfOrder` stays false; MIGRATION doc corrected to Phase 10. §5, §15, tests 20/39. |
| 2 | ACCEPTED | Global publication serialization via the `document_publication_state` singleton row (`FOR UPDATE`), 10-step normative sequence in §9; advisory-lock noted as acceptable equivalent, singleton row preferred (schema-visible, doubles as latest pointer). Test 21. |
| 3 | MODIFIED | All four contradictions resolved: publish preserves status (draft→active on first publish only; retired/removed publish 409); direct active→retired rejected (soft-disable mandatory, per §12.4's own contract); un-retire NOT removed wholesale but **engine-gated** — `retired→active` allowed only for `engine="generic"` because `SourceCatalogSyncRepositoryImpl.seedIfGeneric` provably re-seeds a returning generic stanza while a legacy stanza is never re-seeded ("a legacy source with no row stays invisible") — evidence quoted in §9; `GET /sources/{api}` made document-consistent (retired → 200 with `lifecycle:"removed"`, removed → 410). |
| 4 | ACCEPTED | Full bundled-import contract in §12.2: server-controlled revisions, canonical-content comparison, one snapshot per import, no-op re-import, no revival of removed, all-or-nothing, structured response. Payload-lifecycle mapping for NEW sources defined (active/disabled honored; removed → skipped). Tests 23/24 + updated 17. |
| 5 | MODIFIED | Endpoint completeness adopted for `engine="generic"` (home\|featured + search + details + pages), justified by app evidence: `FallbackSourceClient` is retained-but-UNWIRED (generic-only serving, no legacy floor) and `GenericSourceClient` fails at runtime on missing verbs. **`GENERIC_MISSING_CHAPTERS` dropped** — `chapters` is provably optional (inline chapter parsing in `details()`; 4 of 12 real generic stanzas ship without it). All 12 bundled generic stanzas pass. §8 rule 31, test 28. |
| 6 | MODIFIED | Canonical JSON fully specified as RFC 8785 (JCS)-style: model → JsonElement → recursive lexicographic key sort → compact UTF-8 — chosen over declaration-order because kotlinx maps preserve authoring insertion order (same semantic config, different bytes) and a data-class field reorder would silently change every checksum. Number edge cases vacuous (no floats in the model). "Byte-identical to bundled input" claims removed; identity = canonical semantic content. §1, §2, §5, test 5. |
| 7 | MODIFIED | Canonical bytes preserved as `config_canonical_json TEXT NOT NULL`; chose the amendment's **simpler alternative** — the parallel `jsonb` projection column is dropped entirely (all queryable attributes already denormalized on `source_configs`; a second representation is only divergence risk). Test 33. |
| 8 | ACCEPTED | Two parser modes in §7. Duplicate-key/trailing-garbage rejection specified concretely as a Jackson structural pre-pass (`STRICT_DUPLICATE_DETECTION`, `FAIL_ON_TRAILING_TOKENS`) since kotlinx has no such switch — the feasibility question the amendment raised, answered. Test 25. |
| 9 | ACCEPTED | `API_ID_MISMATCH`; api immutable; authoring payload lifecycle must be neutral `"active"` else 400 `LIFECYCLE_NOT_AUTHORABLE` (explicit rejection chosen over silent normalization); only `DocumentAssemblyService` renders served lifecycle; rollback copies content, never lifecycle; the import nuance resolved in §12.2 (payload lifecycle sets INITIAL status of new sources only). Tests 26/27. |
| 10 | MODIFIED (carry-forward reconciled) | Verified: the real bundled document carries `authorization: "Bearer null"` (Mangamello ×2) — a blanket Authorization denylist WOULD break the import. Final rule (§8 rule 32): hard-deny `cookie`/`set-cookie`/`proxy-authorization`; sensitive-name headers allowed only with values on an explicit public-placeholder allowlist (default exactly `["Bearer null"]`); URL user-info rejected; strict URI parsing for `baseUrl`/`imageBase`/`icon.remoteUrl` only (endpoint URLs are templates, exempt) — verified to pass every URL in the real bundled document (all https, no user-info, no fragments). Log-hygiene rules added (§5 audit, §6, §10). Test 29 includes "Bearer null accepted + full bundled doc passes". |
| 11 | ACCEPTED | Public surface reduced to latest `/document` + `/meta`; historical snapshots admin-only. Verified app-side: `RemoteSourceConfigManager.refresh()` fetches only the latest (no revision parameter) — no app requirement for public history exists. §4.1, test 16 updated. |
| 12 | ACCEPTED (refined) | Min 15 chars (NIST 800-63B single-factor guidance; operator API, password managers assumed); max bounded at **72 UTF-8 bytes** — a discovered correction: BCrypt truncates/rejects beyond 72 bytes, so "max 128" would silently truncate or error; the delegating encoder is the path to lifting the cap later. `password_hash varchar(255)` + `DelegatingPasswordEncoder`. Auth throttling moved into v1 (in-memory, account+IP, progressive, no permanent lockout). §4.2, §6, §13, test 38. |
| 13 | ACCEPTED | Generated-password WARN logging removed; seed env required wherever seeding is enabled; `.env`/`.env.example` flow; resolves Open Q4. |
| 14 | ACCEPTED | Minimal §4.4 admin user API (create/list/enable/disable/reset-password) with last-admin guard, case-insensitive uniqueness, full audit, no password echo; resolves the unsupported "admin-created users" assumption behind prod registration-off. |
| 15 | MODIFIED | Audience `kira-api`, explicit validator list, Base64 ≥256-bit key with startup validation, `kid` rotation seam. Disabled-user question resolved with a **simpler mechanism than token-versioning**: the DB-backed `CurrentUser` resolver re-checks `enabled` per request (indexed PK read) → revocation effective on next request with zero token machinery. Tests 36/37. |
| 16 | ACCEPTED | Three-transaction completion orchestration, provider invoked outside any tx, timeout + result-length caps, sanitized client errors, retention + log-hygiene rules; echo provider runs the identical path. §4.6, §10. |
| 17 | ACCEPTED | Per-source revision allocation under `SELECT … FOR UPDATE` on the source head row (needed in addition to the global publication lock — draft creation doesn't take the global lock). §5, test 22. |
| 18 | ACCEPTED | Sequence gaps documented as normal; `kira.config.revision-floor` startup assertion; cutover verifies against the live binary's actual bundled revision (app rule confirmed: `revision >= bundled` in `RemoteSourceConfigManager`). Resolves Open Q7. |
| 19 | ACCEPTED (e: kept INT, justified) | `lower(email)` unique index; composite FK making cross-source `current_published_revision_id` unrepresentable; circular FK via end-of-migration `ALTER TABLE`; global explicit `ON DELETE RESTRICT`; per-source `revision_number` stays `int` (authoring counter, nowhere near 2^31); `open-in-view=false` + `ddl-auto=validate` + explicit lock modes. JPA retained (no concrete reason to replace). |
| 20 | ACCEPTED | §4.5 normative HTTP contract: pagination bounds, multi-value filter encoding, body-size limits, 413 for oversized prompts (one status, consistently), publish-idempotency (200 no-op), strict-state-machine 409 for repeated disable/enable, republish always snapshots (its documented purpose), full ETag semantics (quoted strong, `If-None-Match: *`, lists, bodiless 304, checksum over exact sent bytes, raw-bytes writer), response headers, and the explicit "checksum ≠ authenticity" statement. Tests 34/35. |
| 21 | ACCEPTED | `bundled-full.json` fixture = the complete real 45-source document; `FullBundledParityIT` (test 32) covers parse→validate→canonicalize→re-parse→import→serve; `ContractInventoryTest` (8b) pins field names/defaults/enums/whitelists; app validator tests ported, not approximated. |
| 22 | MODIFIED (demoted to advisory) | Icon catalog adopted as `PackagedIconCatalog` (manually versioned, documented update procedure, zero mobile-Gradle dependency) but **warning-only, never rejection** — verified app behavior: `SourceIconRegistry` resolves unknown keys to null and the UI falls through remoteUrl → initials avatar, "never a crash"; a hard reject would let a stale catalog block valid publishes for a purely cosmetic risk. Blocking rules remain exactly the app's (regex, HTTPS, non-empty block). `warnings[]` added to validation results. |
| 23 | ACCEPTED | `spring-boot-starter-actuator` added to §3 (it was referenced by the §6 matrix but absent from dependencies — real inconsistency); liveness/readiness split (liveness survives DB outage), details never public, explicit exposure allowlist; correlation IDs, structured logs, no-body/no-Authorization logging, bounded errors, startup config validation, graceful shutdown. No Redis/tracing. |
| 24 | ACCEPTED | §3 version-pinning rule: verify compatibility once at scaffold, record exact versions (incl. Postgres image tag and Gradle wrapper) in the version catalog + README; major upgrades (Boot 4) are deliberate separate changes. |

**Rejected as proposed: none in full** — every amendment addressed a real defect or gap; four (#3, #5, #6/#7 in part, #22) required substantive correction against mobile-app evidence before incorporation, and #10 as written (blanket Authorization denylist) would have broken the production bundled import and was reconciled per the carry-forward finding.

---

## Appendix B — Final consistency review outcomes (third-pass, 2026-07-11)

9 implementation-level findings + 7 smaller consistency items were adjudicated against this plan, the real mobile-app code at `/Users/abdelrahman/Projects/Kira manga` (read-only), and PostgreSQL/Spring-Security/Flyway semantics. Legend as Appendix A. Where a body-text statement predating this pass conflicts with these outcomes, the amended body text (which incorporates them) wins.

| # | Outcome | Summary |
|---|---|---|
| 1 | ACCEPTED | Revision-floor contradiction ("START WITH 100" + floor 100 + "next value must exceed the floor" — the first value IS 100) replaced with the two-floor model (§5): `kira.config.bundled-revision-floor` (default 4; published revisions strictly `>`) vs `kira.config.minimum-server-revision` (default 100; sequence-next `>=`, inclusive). App floor rule verified INCLUSIVE: `RemoteSourceConfigManager.refresh()` uses `.takeIf { it.revision >= acceptedRevision }` seeded from the bundled revision — so the strict server floor composes safely. Startup assertion matrix + `StartupConsistencyIT` (test 41); Open Q7 re-resolved. |
| 2 | ACCEPTED | Source-list order verified BEHAVIORALLY SIGNIFICANT in the app: `SourcesDao.getAllSources()` = `ORDER BY priority` with no secondary key; all 45 bundled stanzas omit `priority` (all 0); `HomeFeedRepositoryImpl.observeSourceTabs` stable-sorts by priority; rows are seeded by `SourceCatalogSyncRepositoryImpl` iterating `document.sources` in list order; `ConfigMerger` preserves first-seen order → fresh-install tab order IS document order. Therefore an explicit `position` column (normative order `position ASC, api ASC`), payload-order assignment on import (bundled order preserved end-to-end), max+1 append on create, no reliance on DB result order, `DocumentOrderDeterminismIT` (test 40). Alphabetical-only ordering was REJECTED as it would scramble the app's observable tab order. |
| 3 | ACCEPTED | Stored revision content is lifecycle-NEUTRAL (§9): `lifecycle` always the neutral default — rendered as an ABSENT key under `kcj-1` default-omission; served value injected only at assembly; import reads lifecycle separately (initial status of new sources / `lifecycleConflicts` only) and normalizes before comparison/checksum/storage. Changed content for `retired`/`removed` sources is never imported (`skippedRetired`/`skippedRemoved` — publish is 409 on those statuses, import must not bypass it). Existing draft-only sources are also never overwritten or published (`skippedDraft`). Verified: the real bundled document carries zero explicit `lifecycle` keys, so current-doc parity is unaffected; the rule protects round-trips of served documents (which DO carry `disabled`/`removed`). Consistent with §12.2 and Appendix A #9 (extended, not contradicted). Test 45; test 24 re-scoped. |
| 4 | ACCEPTED | `document_publication_state.latest_document_revision` is THE authoritative latest pointer (single-row read); `MAX(document_revision)` removed as a read path (survives only inside the startup consistency comparison); FK pointer → `published_documents(document_revision)` added; startup validation (pointer↔snapshot↔sequence coherence, NULL-pointer ⇒ zero snapshots) + documented manual recovery runbook — never silent auto-repair. Test 41. |
| 5 | ACCEPTED-WITH-MODIFICATION | One application-controlled Clock instant = `generatedAt` = `published_documents.created_at` (column has NO DB default) = publication audit detail; §9 steps 7–8. Precision: ISO-8601 UTC **seconds** (kept from §5's existing canonical-timestamp spec — the finding's "e.g. millis" example was not adopted to avoid contradicting §5). Verified app-side: `generatedAt` has zero readers outside the model declaration (provenance-only confirmed). `SnapshotTimestampConsistencyIT` (test 43). |
| 6 | ACCEPTED | Phase gates made executable: test 20 is phase-aware (V1..V3 at Phase 5 → V1..V4 at Phase 6 → final exactly-V1..V5 form from Phase 9); test 39 re-specified with BOTH incremental baselines (V1..V3→V4+V5; V1..V4→V5) and moved from Phase 5 to Phase 9 (it referenced V4/V5 before they existed — the exact defect found). §11/§15 updated. |
| 7 | ACCEPTED | Count-then-disable is racy under READ COMMITTED (two tx each observe 2 enabled admins → zero remain). Dedicated `security_state` singleton row (V1) locked `FOR UPDATE` before counting; applies to enable/disable and future role changes. Reusing `document_publication_state` REJECTED with justification: unrelated subsystem (false contention) + it doesn't exist until V3/Phase 5 while the guard ships in Phase 3; advisory lock noted as acceptable equivalent, row preferred (schema-visible, matches the established pattern). `ConcurrentLastAdminDisableIT` (test 42). |
| 8 | ACCEPTED | An argument resolver only runs when a controller injects it — insufficient. Enforcement moved into the authentication pipeline: custom `jwtAuthenticationConverter` (`Converter<Jwt, AbstractAuthenticationToken>`) — verify JWT normally, load user by `sub`, missing/disabled → `AuthenticationException` → 401 on EVERY protected endpoint; authorities derived from the **DB role** (immediate effect for server-side role changes; token role claim is informational, never trusted); loaded user exposed as principal (no second DB read). "No custom servlet filter" wording reconciled (a converter is not a filter). Test 37 extended across /auth/me, completion read/write, admin, + DB-role-change immediacy. |
| 9 | ACCEPTED-WITH-MODIFICATION | Two-tier model added (§8): Tier-1 structural 400 gate (nothing persisted) = strict-parse failures, `API_ID_MISMATCH`, `LIFECYCLE_NOT_AUTHORABLE` (both were already 400s — now formally tiered), `API_IDENTIFIER_INVALID`, `FIELD_TOO_LONG` (all DB column limits → controlled 400, never a 500 from a persistence exception); Tier-2 = all semantic §8 rules stored on inspectable drafts. MODIFICATION (decisive, evidence-driven): the proposed "safe path/identifier format" CANNOT be an ASCII slug — real production apis contain embedded spaces (`"Team X"`, `"Komik Cast"`, `"Mangamello Plus"`, `"Taurus Fansub"`, `"Manga Origine"`) and Arabic script (`"مانجا بارك"`); the gate instead rejects blank/over-128-chars/control-chars/`/`+`\`/edge-whitespace and all 45 bundled apis pass. `StructuralGateIT` (test 44). |
| S1 | ACCEPTED | `/auth/refresh` is NOT registered — standard 404, no 501 stub; docs mark it planned. §4.2 + §6 both aligned (the "501-until-implemented" phrasing removed). |
| S2 | ACCEPTED, THEN IMPLEMENTED | The initial schema did not speculate about signatures. Production hardening later added the decided Ed25519 contract and nullable historical migration through V7, with fail-closed production signing (§9). |
| S3 | ACCEPTED | `GET /admin/documents/{revision}` = raw stored canonical bytes as body (same raw-bytes writer as public) + metadata headers; explicitly NOT a JSON envelope (would break the serve-stored-bytes/checksum guarantee); consistent with test 16's existing wording. |
| S4 | ACCEPTED | Explicit: weak validators (`W/"…"`) never strongly match (RFC 9110 §8.8.3.2) → 200; §4.1 + §9 + test 35. |
| S5 | ACCEPTED | Inbound `X-Request-Id` accepted only when matching `[A-Za-z0-9._-]{1,64}`; otherwise a server UUID is generated — unvalidated header values never reach MDC/logs (§6). |
| S6 | ACCEPTED | Canonical format named **Kira Canonical JSON v1 (`kcj-1`)** — RFC 8785-inspired, full compliance explicitly NOT claimed (number rules vacuous: no float fields; kotlinx escaping is normative). `canon_version varchar(16)` stored on both `source_config_revisions` and `published_documents` so a future algorithm change is explicit; `republish` documented as the post-change recovery tool. |
| S7 | ACCEPTED | Publishing an empty document is ALLOWED (truthful terminal state; the per-source disable→retire→remove(confirm) chain is the guard — no extra confirmation invented). App impact verified safe: `RemoteSourceConfigManager` always folds the bundled document in, `ConfigMerger` unions per-api (empty overrides nothing), `forceDisableNonConfigRows` is guarded on a non-empty generic set; only effect is the revision ratchet. Canonical detail documented: default-empty `sources` is omitted from the bytes. `EmptyDocumentPublishIT` (test 46). |

**Rejected: none.** Two findings were accepted with modifications forced by evidence (Finding 5's precision kept at the §5 seconds spec; Finding 9's identifier format widened for real production apis). All 15 preserved strengths listed in the review charter are intact — every change refines the existing mechanisms (locks, canonical bytes, two-parser split, lifecycle mapping, floor model) without regressing any.

---

## Appendix C — Pre-implementation amendments (fourth pass, 2026-07-11)

4 owner-directed amendments, applied as specified (no adjudication disputes — all four addressed genuine gaps):

| # | Outcome | Summary |
|---|---|---|
| 1 | ACCEPTED | **Publishable revision states fully specified** (§4.3 publish row + §9): currently-published → 200 idempotent no-op (no snapshot); `superseded` → 409 `REVISION_SUPERSEDED` (restore = rollback only); draft older than the published revision → 409 `REVISION_OLDER_THAN_PUBLISHED`; retired/removed → 409 unchanged. Supersede-then-publish statement ordering inside the single transaction keeps `uq_one_published_per_source` valid at every statement (Postgres checks the partial unique index per statement). Concurrent same-source publishes serialize on the §9 lock order with exactly one deterministic winner. Tests 47 (`PublishStateRulesIT`) + 48 (`ConcurrentSameSourcePublishIT`); Phase 6 updated. |
| 2 | ACCEPTED | **Stable completion error taxonomy** (§5 + §10 + §4.6): `completion_results.error_code varchar(64) NULL` added with CHECKs — `chk_result_xor_error` (never both `result` and `error`) and error/error_code pairing (both set on failure, both NULL on success). Bounded catalog: `PROVIDER_TIMEOUT`, `PROVIDER_UNAVAILABLE`, `PROVIDER_REJECTED`, `INVALID_PROVIDER_RESPONSE`, `RESULT_TOO_LARGE`, `INTERNAL_COMPLETION_ERROR` (all unknown exceptions map to the last). API failure shape `{errorCode, error}` with sanitized bounded messages; raw provider exceptions/stack traces never stored, never returned — secured logs only. Test 49 (`CompletionErrorTaxonomyIT`); Phase 9 updated. |
| 3 | ACCEPTED | **Logging & diagnostics section added** (§6): structured JSON in prod / console in dev; normative per-line fields (request ID, method, normalized route pattern — never raw URLs, status, duration, user ID/role, api, source/document revision, completion ID, error code); full INFO/WARN/ERROR/DEBUG event catalog (startup, Flyway state, consistency checks, seeding, login, throttling, lifecycle, import, publication+checksum, 304 hits, completion transitions, shutdown); log-once-at-the-owning-boundary rule; non-negotiable never-log list extended (no header VALUES, no prompts/results, no field-name injection, control-char sanitization, bounded validation logging); SECURITY.md gains retention/privacy expectations (Phase 10). Phase 2 lays the foundation; each feature phase lands its own events. |
| 4 | ACCEPTED, THEN HARDENED | **Trusted client-IP resolution for throttling** (§6): server-observed remote address by default; forwarded headers are honored only when explicitly enabled through trusted proxies. Malformed/oversized headers fail safely. The bounded memory store remains single-instance-only; atomic TTL-bounded Redis throttling is mandatory for multiple production instances and fails closed when unavailable. |
