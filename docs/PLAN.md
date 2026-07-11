# kira-backend — Implementation Plan

**Status:** Planning document (sole spec for the implementation agent). Nothing has been scaffolded yet.
**Revision:** REVIEWED & AMENDED (2026-07-11, second-pass review). 24 proposed amendments were adjudicated against the real mobile-app code; the outcomes are recorded in **Appendix A** at the end of this document. This revision supersedes the first draft wherever they differ.
**Target location:** `/Users/abdelrahman/Projects/kira-backend/` — a standalone Gradle project, fully outside the mobile app repos.
**Mobile parity reference (READ-ONLY):** `/Users/abdelrahman/Projects/Kira manga` — all config shapes and validation rules in this plan were read from that repo on 2026-07-11 (file paths cited inline).

## Hard constraints (restated up front — non-negotiable)

1. **JWT signing secret and ALL secrets come from env/config** (`KIRA_JWT_SECRET`, DB credentials, admin seed credentials). Never hardcoded, never committed. `application.yml` holds only `${ENV_VAR}` placeholders and dev-profile defaults that are obviously non-production.
2. **Passwords are BCrypt-hashed.** No plaintext, no reversible encoding, anywhere (including tests — tests hash too).
3. **No real secrets committed.** `docker-compose.yml` may carry a throwaway local dev password (`kira`/`kira` style) clearly marked local-only; `.gitignore` excludes `.env`.
4. **The completion provider is an abstraction** (interface + fake/echo implementation). No provider SDK call and no provider name is ever hardcoded into a controller; selection is by Spring configuration property.
5. **Standalone project.** No dependency on, reference to, or modification of the mobile app's Gradle modules. The mobile repo is inspected read-only for shape parity only.
6. **The mobile-app remote-fetch client is OUT OF SCOPE.** This backend serves the document; wiring the app's `RemoteConfigSource` to it is a separate future task in the app repo.

---

## 1. Overview & goals

kira-backend is a Spring Boot / Kotlin / PostgreSQL service that becomes the remote authority for the Kira Manga app's **source configuration**: the same validated, versioned `SourceConfigDocument` JSON the app currently bundles as a string constant (`CONFIG_BACKED_SOURCES_JSON` in `composeApp/.../sources/runtime/BundledSourcesConfig.kt`, currently `schemaVersion 1, revision 4`, 12 generic + 33 legacy stanzas) will be authored, validated server-side with the exact same rules the app's `DefaultSourceConfigValidator` enforces, published as immutable revisions, and served over a stable public read API with ETag/checksum support — with lifecycle management (draft → active → disabled → retired → removed), per-source rollback, and an audit trail. Around that core, the project lays three small foundations: JWT auth with ADMIN/USER roles, admin source-management endpoints, and an authenticated completion-request service behind a provider abstraction (fake/echo provider only for now). The design goal throughout: the app must eventually be able to fetch from this backend a document that is **contract-equivalent, deterministically canonical, and byte-stable** with respect to what it bundles today (the app's parser is key-order-insensitive and lenient — semantic equivalence is the contract, byte identity with the hand-authored bundled text is explicitly NOT claimed; see §5 canonicalization), and the server must make publishing an invalid config impossible.

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
                              # Roles (ADMIN, USER), CurrentUser resolver (DB-backed, rejects disabled — §6),
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
| `GET /api/v1/sources` | Summaries of sources in the current document: `{api, displayName, language, engine, lifecycle, siteState, adult, baseUrl, iconRemoteUrl, revisionNumber, publishedAt}`. Query: `?lifecycle=active,disabled` (comma-separated multi-value; default = everything in the document incl. retired-as-removed), `?engine=`. | 200. Draft-only and removed sources never appear. |
| `GET /api/v1/sources/{api}` | The single published `SourceConfig` stanza (exact app shape), **consistent with the document content**: `active` → 200 (`lifecycle:"active"`); `disabled` → 200 (`lifecycle:"disabled"`); `retired` → **200 with `lifecycle:"removed"`** (the stanza is still in the served document during the grace window — returning 410 while the document still carries it would be self-contradictory); `removed` → **410 Gone**; unknown/draft-only → 404. | 200 / 404 / 410 as listed. |

**ETag semantics (normative):** strong quoted ETags (`ETag: "a1b2…"`); `If-None-Match: *` matches when any document exists → 304; a comma-separated `If-None-Match` list is parsed and each entry compared (strong comparison, quotes stripped); match → 304 **with no body** and the same ETag/Cache-Control headers; the checksum is computed over the **exact UTF-8 bytes sent** (the stored canonical bytes, written raw — never re-serialized by a message converter). The `X-Config-Checksum` header is a **corruption check only, not authenticity** — a hash delivered beside the same payload cannot authenticate it; authenticity = HTTPS today + the future detached signature (§9).

### 4.2 Auth

| Method & path | Auth | Request → Response | Codes |
|---|---|---|---|
| `POST /api/v1/auth/register` | none (gated by `kira.auth.registration-enabled`, default `true` dev / `false` prod — prod onboarding is via `/admin/users`, §4.5) | `{email, password}` → `{id, email, role:"USER"}`. Password policy: **min 15 chars, max 72 UTF-8 bytes** (BCrypt input limit — documented, enforced, never silently truncated), no composition rules, no trimming/normalization of the password (email IS trim+lowercased). | 201; 409 duplicate email (case-insensitive); 400 policy violation; 403 registration disabled; **429** registration throttle (per-IP) |
| `POST /api/v1/auth/login` | none | `{email, password}` → `{accessToken, tokenType:"Bearer", expiresInSeconds, role}` | 200; 401 bad credentials (identical generic message for unknown-user / wrong-password / disabled account); **429** when throttled (per normalized email AND per IP — §6) |
| `GET /api/v1/auth/me` | Bearer (USER or ADMIN) | → `{id, email, role, createdAt}` | 200; 401 |
| `POST /api/v1/auth/refresh` | **NOT implemented in v1.** Documented seam only (§6). | — | — |

### 4.3 Admin — source management (all `ROLE_ADMIN`; every mutation writes `audit_log`)

| Method & path | Purpose | Codes |
|---|---|---|
| `POST /api/v1/admin/sources` | Create a source: body = full `SourceConfig` JSON (its `api` is the identity). Parsed with the **STRICT authoring parser** (§7 — unknown keys, duplicate keys, malformed JSON all → 400). Payload `lifecycle` must be the neutral default `"active"` (anything else → 400 `LIFECYCLE_NOT_AUTHORABLE` — lifecycle is server-managed, §9). Creates the `source_configs` row (status `draft`) + revision 1 (`draft`). Runs validation immediately, stores the result, returns it inline: `{api, status, revisionNumber, validation:{valid, errors[], warnings[]}}`. | 201; 409 api already exists; 400 unparseable/strict-parse-rejected JSON |
| `GET /api/v1/admin/sources` | All sources incl. drafts/retired/removed; filter `?status=`. | 200 |
| `GET /api/v1/admin/sources/{api}` | Full admin view: current status, current published revision no., latest revision no., timestamps. | 200; 404 |
| `POST /api/v1/admin/sources/{api}/revisions` | New draft revision (body = full `SourceConfig`, **STRICT authoring parser**). `body.api` **must equal** the path `{api}` → mismatch is 400 `API_ID_MISMATCH`; the api identity is immutable after creation. Payload `lifecycle` must be `"active"` (else 400 `LIFECYCLE_NOT_AUTHORABLE`). Revision number allocated **under a `SELECT … FOR UPDATE` lock on the source head row** (§5 — concurrent creations must not collide). Auto-validates + stores result, returns it. | 201; 404; 400 |
| `GET /api/v1/admin/sources/{api}/revisions` | Revision list: `{revisionNumber, status, checksum, createdBy, createdAt, publishedAt, valid}`. | 200; 404 |
| `GET /api/v1/admin/sources/{api}/revisions/{n}` | Full stored config JSON of that revision + metadata. | 200; 404 |
| `POST /api/v1/admin/sources/{api}/revisions/{n}/validate` | Re-run validation (validation preview — no state change beyond storing the result). Returns `{valid, errors:[{code, path, message}], warnings:[{code, path, message}]}`. Also validates the source **in candidate-document context** (unique-api etc., §8). | 200 (even when invalid — the *result* reports invalid); 404 |
| `GET /api/v1/admin/sources/{api}/revisions/{n}/validation` | Latest stored validation result for the revision. | 200; 404 |
| `POST /api/v1/admin/sources/{api}/revisions/{n}/publish` | Publish: validation MUST pass (server re-validates inside the same transaction — a stale stored "valid" is not trusted). Marks revision `published` (previous published one → `superseded`), sets `publishedAt`, **materializes a new document snapshot under the global publication lock** (§9). **Status effect (explicit, no implicit re-enable):** `draft` + first valid publish → `active`; `active` + publish → **remains `active`**; `disabled` + publish → **remains `disabled`** (publishing content never re-enables); `retired`/`removed` + publish → **409 forbidden**. Publishing the currently-published revision again → **200 no-op** (idempotent; no new snapshot). | 200 with new `{documentRevision, checksum}`; **422** with `errors[]` if invalid; 409 retired/removed; 404 |
| `POST /api/v1/admin/sources/{api}/disable` | `active → disabled`. Stanza stays in the document with `lifecycle:"disabled"`. New snapshot. Repeating on an already-`disabled` source → 409 (strict state machine, not idempotent — a 409 tells the operator their mental model of current state is wrong). | 200; 409 invalid transition |
| `POST /api/v1/admin/sources/{api}/enable` | `disabled → active`; also `retired → active` **for `engine="generic"` sources only** (un-retire — §9 explains why legacy is excluded, with app evidence). Repeat on `active` → 409. New snapshot. | 200; 409 |
| `POST /api/v1/admin/sources/{api}/retire` | `disabled → retired` **only** (direct `active → retired` is 409 — the mandatory soft-disable stage is enforced, honoring the "no silent deletion: disabled in the document before ever dropped" contract in §12.4). Stanza stays in document as `lifecycle:"removed"` (app vocabulary — §9 mapping). New snapshot. | 200; 409 |
| `POST /api/v1/admin/sources/{api}/remove` | `retired → removed` (terminal). Stanza dropped from the document entirely. New snapshot. Body `{confirm: "<api>"}` required (foot-gun guard). | 200; 409 (must pass through `disabled` then `retired` first) |
| `POST /api/v1/admin/sources/{api}/rollback` | Body `{toRevision: n}`. Copies revision *n*'s **content** into a **new** revision (number = latest+1), validates, publishes it. History is never mutated; revision numbers only grow. **Rollback copies content only — it does NOT restore the source's server lifecycle from that era** (status follows the publish rules above: active stays active, disabled stays disabled). | 200 with `{newRevisionNumber, documentRevision}`; 422 if the old config no longer validates (rules may have tightened); 409 retired/removed; 404 |
| `GET /api/v1/admin/documents` | Published document snapshots: `{documentRevision, schemaVersion, checksum, sourceCount, createdBy, createdAt}`. | 200 |
| `GET /api/v1/admin/documents/{revision}` | Full snapshot JSON + metadata. | 200; 404 |
| `POST /api/v1/admin/documents/validate` | Validate the **candidate** document (assembled from current published revisions + lifecycle states) without publishing — whole-document preview. | 200 `{valid, errors[]}` |
| `POST /api/v1/admin/documents/republish` | Force-materialize a new snapshot from current state (recovery / after canonicalization changes). **Always creates a new snapshot with a new document revision, even when the canonical content is unchanged** — that is its purpose (deliberate recovery tool; the caller decides). | 200 |
| `POST /api/v1/admin/sources/import-bundled` | Body = the app's bundled document JSON (`CONFIG_BACKED_SOURCES_JSON` contents). **Fully specified semantics in §12.2 (bundled-import contract)** — parses with the COMPATIBILITY parser (§7), validates the whole document, applies per-source create/update/no-op with server-controlled revisions, materializes exactly ONE snapshot, all-or-nothing. Response: `{created[], updated[], unchanged[], skippedRemoved[], lifecycleConflicts[], documentRevision?}`. Request-body size limit 5 MiB (the real document is well under 1 MiB today). This is the migration on-ramp (§12). | 200 (incl. the no-op case); 422 with full error list |

### 4.4 Admin — user management (all `ROLE_ADMIN`; minimal surface, NOT an IdM platform; every mutation audited)

Prod onboarding mechanism (registration is disabled in prod): admins create users. This is the concrete API backing that statement.

| Method & path | Purpose | Codes |
|---|---|---|
| `POST /api/v1/admin/users` | `{email, password, role}` → create user (password policy of §4.2 applies; email trim+lowercased, case-insensitively unique). Response never echoes the password. | 201; 409 duplicate; 400 |
| `GET /api/v1/admin/users` | Paginated list: `{id, email, role, enabled, createdAt}` — **no password material, ever**. | 200 |
| `POST /api/v1/admin/users/{id}/enable` | Re-enable a disabled user. | 200; 404 |
| `POST /api/v1/admin/users/{id}/disable` | Disable: user can no longer log in; in-flight tokens die at the next request because `CurrentUser` resolution re-checks `enabled` (§6). **Guard: refuses (409) to disable the last enabled ADMIN** — recovery from an all-admins-disabled state would otherwise require manual SQL. | 200; 404; 409 last-admin guard |
| `POST /api/v1/admin/users/{id}/reset-password` | `{newPassword}` (policy-checked). Explicit, audited (`USER_PASSWORD_RESET` — the audit row records actor + target, never the password). | 200; 404; 400 |

### 4.5 Cross-cutting HTTP contract (normative)

- **Pagination:** `?page=0&size=20`; `size` max **100** (larger → 400), `page`/`size` negative or non-numeric → 400. Applies to every paginated endpoint.
- **Request-body limits:** default max **256 KB**; `import-bundled` max **5 MiB**; completion prompt over its max length → **413** (one consistent status, not sometimes-400).
- **Multi-value filters** (`?lifecycle=`): comma-separated within a single query param; unknown enum value → 400.
- **Responses:** JSON endpoints send `Content-Type: application/json; charset=UTF-8`; the public document endpoints add `Cache-Control: public, max-age=300, no-transform` + `X-Content-Type-Options: nosniff`; error responses use the §2 problem envelope and never echo submitted config/header/password values back verbatim.

### 4.6 Completion (authenticated: USER or ADMIN)

| Method & path | Purpose | Codes |
|---|---|---|
| `POST /api/v1/completions` | `{prompt, model?}` → creates `completion_requests` row (`PENDING`), invokes the configured `CompletionProvider` **outside any DB transaction** (§10 — three short transactions, provider call between them), persists sanitized result/error (`SUCCEEDED`/`FAILED`), returns `{id, status, model, provider, result?, error?, createdAt}`. Prompt max length via property (default 8 000 chars → **413** over). Provider invocation has a timeout (`kira.completion.timeout`, default 30s) and a max stored result length; provider exceptions are never surfaced raw to clients (sanitized message + stable internal error code; details only in secure server logs). | 201; 401 anon; 400 blank prompt; 413 too large |
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

**Canonicalization (load-bearing, fully specified):** Postgres `jsonb` does NOT preserve key order/whitespace, so checksums are never computed from `jsonb` round-trips — in fact **no `jsonb` column participates in the canonical path at all** (revisions store canonical TEXT, below). Declaration-order encoding alone is also NOT sufficient: kotlinx parses JSON maps into `LinkedHashMap` (insertion order), so the same semantic config authored with different `headers`/`endpoints`/`fields`/`formBody`/`vars`/transform-`args` key orders would yield different bytes — and an innocent data-class field reorder would silently change every checksum. Canonical form is therefore defined as a deterministic **RFC 8785 (JCS)-style algorithm**:

1. Encode the Kotlin model with `CanonicalJson = Json { encodeDefaults = false; prettyPrint = false; explicitNulls = false }` to a `JsonElement` (default-omission identical to the app's bundled style).
2. Recursively **sort every object's keys** lexicographically by Unicode code point (covers both data-class fields and maps — immune to authoring order and to model declaration order).
3. Serialize compact: UTF-8, no insignificant whitespace, no pretty print, no trailing newline.

Number determinism is trivial here — the model contains only `Int`/`Long`/`Boolean`/`String` fields (no floating point). Timestamps the server authors (`generatedAt`) are ISO-8601 UTC with fixed seconds precision (`YYYY-MM-DDThh:mm:ssZ`). Checksum = SHA-256 hex over the canonical UTF-8 bytes. **Document identity is canonical semantic content — NOT the authoring text's whitespace/key order**; re-serializing the bundled document is *not* claimed to reproduce its hand-authored bytes (it will not — the bundled constant is pretty-printed); the guarantee is "contract-equivalent, deterministically canonical, byte-stable": parse(canonical(x)) is semantically equal to parse(x), and canonical(x) is stable across processes and releases. The app parses leniently and key-order-insensitively (`ignoreUnknownKeys = true; isLenient = true` in `SourceConfigParser`), so JCS ordering is transparent to it.

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

**`source_configs`** (V2) — one row per API id (identity + lifecycle head)

| column | type | constraints |
|---|---|---|
| id | uuid | PK |
| api | varchar(128) | NOT NULL, UNIQUE (`uq_source_configs_api`) — the app-side stable key; uniqueness of api-per-document falls out of this |
| display_name | varchar(256) | NOT NULL |
| language | varchar(32) | NOT NULL |
| engine | varchar(64) | NOT NULL (`generic` / `legacy` / `kotlin:<id>`) |
| status | varchar(16) | NOT NULL, CHECK IN `('draft','active','disabled','retired','removed')` |
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
| config_canonical_json | text | NOT NULL — the stanza's **canonical bytes (§5 canonicalization), the immutable source of truth**. NOT `jsonb` (jsonb destroys key order/whitespace → checksum bytes would be unreproducible from storage). There is deliberately **no** parallel `jsonb` projection column: every queryable attribute (displayName, language, engine, baseUrl, adult) is already denormalized onto `source_configs`, so a second representation would only be a divergence risk. Revisions are fetched whole, by id or (source, number). |
| checksum | char(64) | NOT NULL — SHA-256 hex of `config_canonical_json`'s UTF-8 bytes; both generated from the same validated parsed model in one operation |
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
| document_revision | bigint | NOT NULL, UNIQUE — from sequence `seq_document_revision` (monotonic, never reused; **seed START WITH 100** to sit safely above the bundled document's current `revision 4`). **Gaps are normal and documented:** Postgres sequence values consumed by rolled-back transactions are not returned, so revisions are unique and strictly increasing but NOT contiguous — prose, clients, and tests must never assume contiguity. Startup floor check: property `kira.config.revision-floor` (default 100); on startup the service asserts the sequence's next value exceeds the floor and fails fast otherwise. **At production cutover, ops verifies the floor against the revision actually shipped in the live app binary** — never relies forever on "bundled == 4" (the app-side rule is `revision >= bundled` — verified in `RemoteSourceConfigManager.refresh()`). |
| schema_version | int | NOT NULL (=1 for now) |
| document_json | text | NOT NULL — the EXACT canonical bytes served (not jsonb, deliberately) |
| checksum | char(64) | NOT NULL — SHA-256 of `document_json` = the ETag |
| source_count | int | NOT NULL |
| created_by | uuid | NOT NULL FK users |
| created_at | timestamptz | NOT NULL |
| notes | text | NULL |

Latest document = `MAX(document_revision)`. Index implied by UNIQUE.

**`document_publication_state`** (V3) — the **global publication serialization lock** (amendment #2; §9 explains the lost-update hazard it prevents)

| column | type | constraints |
|---|---|---|
| id | int | PK, CHECK (`id = 1`) — singleton row, seeded by V3 |
| latest_document_revision | bigint | NULL until first publish |
| updated_at | timestamptz | NOT NULL |

Every state-visible document mutation begins by locking this row with `SELECT … FOR UPDATE`; it also serves as the authoritative latest-revision pointer (updated in the same transaction that inserts the snapshot), making "latest" a single-row read instead of a `MAX()` scan.

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
| error | text | NULL |
| latency_ms | int | NULL |
| created_at | timestamptz | NOT NULL |

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

- **Auth flow:** `POST /auth/login` → `AuthService` loads user by email → BCrypt verify → `JwtService` issues an **HS256 JWT** via Nimbus (`spring-security-oauth2-jose`): claims `sub` = user UUID, `email`, `role` (`ADMIN`|`USER`), `iss = "kira-backend"`, **`aud = "kira-api"`**, `iat`, `exp = iat + kira.security.access-token-ttl` (default **PT60M**). Resource-server side: `NimbusJwtDecoder.withSecretKey(...)` configured to **explicitly validate signature, `exp`, `nbf` when present, issuer, and audience** (default validators + `JwtIssuerValidator` + `JwtClaimValidator("aud")`; clock skew 60s), plus a converter mapping the `role` claim to `ROLE_<role>` authority. No custom servlet filter — standard `oauth2ResourceServer { jwt {} }`. HS256 with one shared in-process key is deliberate for a single service — asymmetric signing is NOT introduced without a real multi-service key-distribution need; a `kid` header is emitted from day one as the rotation seam (single active key in v1).
- **JWT key handling:** `kira.security.jwt-secret` bound from env `KIRA_JWT_SECRET` = **Base64-encoded cryptographically random key that decodes to ≥ 256 bits** (generate: `openssl rand -base64 32`), NOT an arbitrary human passphrase. Startup fails fast if missing, not valid Base64, or < 32 decoded bytes (except a documented dev-profile default clearly marked insecure). Documented in SECURITY.md: key format, issuer, audience, TTL, clock skew, rotation procedure (new key + `kid` bump + bounded dual-accept window when needed).
- **Disabled-user revocation (decided, no token-version machinery):** tokens stay stateless, but the `CurrentUser` resolver on every authenticated request loads the user row (indexed PK lookup) and **rejects `enabled = false` with 401** — disabling a user is therefore effective on their next request, on every endpoint, without token-version bookkeeping. Login is likewise refused. (`DisabledUserAuthIT` proves both.)
- **Secret handling:** DB creds via `SPRING_DATASOURCE_*` env. `.env` gitignored; `.env.example` committed with placeholders only; `docker-compose.yml` carries only the throwaway local DB password.
- **Password policy & hashing:** min **15 chars** (NIST SP 800-63B guidance for single-factor password auth; this is a small-audience operator API where password managers are assumed — usability cost ≈ 0), max **72 UTF-8 bytes** (the BCrypt input limit, enforced explicitly with a clear 400 rather than silent truncation; the byte cap is documented as encoder-derived — moving to Argon2 later via the delegating encoder lifts it), **no composition rules, no expiry, no silent trimming/normalization** of the password itself. Encoder bean = `DelegatingPasswordEncoder` with `{bcrypt}` as the initial id (hash format stays portable; BCrypt cost calibrated on real deployment hardware at setup — target ≥ ~100 ms — not hardcoded at strength 10 forever). Optional-but-recommended: a small embedded top-common-passwords blocklist check.
- **Auth throttling (v1, mandatory):** `AuthThrottleService` — bounded in-memory (single-instance v1; the interface is the seam for Redis/distributed later). Login: progressive delay + temporary throttle keyed by **normalized account identifier AND client IP** (e.g. ≥ 5 consecutive failures → 1-minute temporary throttle, doubling and capped at 15 minutes; counters reset on success or window expiry — **no permanent lockout an attacker can weaponize against a victim account**). Registration: per-IP rate limit. Throttled → 429 with the same generic body as auth failures (no username-exists oracle). `AuthenticationRateLimitIT` proves throttle + reset.
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
- **Operational hardening:** request/correlation ID (accept inbound `X-Request-Id` else generate; echo in responses; include in every log line via MDC); structured JSON logs in prod profile; **no request-body logging** for auth, config-authoring, or completion endpoints; `Authorization` header never logged; error responses carry bounded detail (no stack traces, no echoed secrets); startup config validation (`@ConfigurationProperties` + JSR-380 fail-fast); graceful shutdown enabled (`server.shutdown=graceful`). Liveness must NOT fail solely because Postgres is temporarily down (liveness = process health; readiness = DB + migrations applied). No Redis, no distributed tracing, no further observability stack in v1.
- **Refresh-token-READY seam (not built):** `AuthService.issueTokens(user)` returns a `TokenPair(access, refresh = null)`; token TTLs are properties; the `refresh_tokens` table design is reserved in §5; `POST /auth/refresh` is documented as 501-until-implemented and left out of the router. Nothing else to do now — deliberately.

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

**Outbound canonical** serialization uses the §5 JCS-style canonicalization — omitted defaults, recursively sorted keys, compact UTF-8. Because the app ignores unknown keys, the server COULD add fields; the rule is: **don't** — serve exactly this model.

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

**Document snapshot — globally serialized (the per-mutation transaction alone is NOT enough):** without global ordering, two concurrent mutations (publish source A ∥ publish source B) each assemble a candidate from a snapshot that predates the other's commit — the later document revision silently *loses* the earlier source's change from the served snapshot (a classic lost update that read-committed isolation does not prevent). Therefore **ALL state-visible document mutations — publish, disable, enable, retire, remove, rollback, bundled import, and `republish` — run this exact sequence inside one transaction**:

1. Lock the singleton `document_publication_state` row (`SELECT … FOR UPDATE`) — the **global publication lock**; concurrent mutators queue here.
2. Lock the affected `source_configs` row(s) (`FOR UPDATE`) where the mutation touches per-source state (always lock in a deterministic order: global lock first, then source rows — no deadlock is possible because every writer takes the global lock first).
3. Apply the mutation (revision insert / status change / import batch).
4. Read the authoritative current state (all published revisions + statuses) **under the lock** — it cannot be stale, because every other writer is queued behind step 1.
5. Assemble the full candidate document (all sources in `active|disabled|retired`, each rendered from its published revision with the lifecycle mapping applied).
6. Validate it whole (§8 rule 29).
7. Serialize canonically (§5) and compute SHA-256.
8. Insert the `published_documents` row with the next `document_revision` from the sequence.
9. Update `document_publication_state.latest_document_revision`.
10. Commit (lock released; the new snapshot becomes the served latest atomically).

Failure anywhere rolls the whole mutation back — the served document can never be invalid, torn, or missing a concurrent change. A Postgres advisory transaction lock (`pg_advisory_xact_lock`) would be an acceptable equivalent for step 1, but the singleton row is preferred: it is visible in the schema, testable, and doubles as the latest-revision pointer. `ConcurrentDifferentSourcePublishIT` proves the invariant: two truly concurrent publications to two different sources → the final latest snapshot contains BOTH changes.

**Checksum & ETag:** ETag = strong quoted document checksum (`ETag: "a1b2…"`); `If-None-Match` containing it → 304. Checksum also surfaces in `X-Config-Checksum` and `/document/meta` so the app (future) can verify payload integrity independently of HTTP caching. `generatedAt` is set to the snapshot's `created_at` (ISO-8601) — provenance only, exactly as the app treats it.

**Signature (future-ready, not implemented):** the app's remote path requires a detached signature (`ConfigSignatureVerifier.verify(payload, signatureBase64)`; currently `DenyRemoteSignatureVerifier` rejects all). The snapshot table can gain a nullable `signature_base64` column + an Ed25519 signing key from env when the owner decides the scheme (Open Q1). Nothing in v1 depends on it.

---

## 10. Completion foundation

Deliberately small — a persistence + abstraction skeleton, not an AI platform.

- **Port (domain):** `interface CompletionProvider { val name: String; fun complete(prompt: String, model: String): CompletionOutcome }` where `CompletionOutcome` = `Success(result: String, latencyMs: Int)` | `Failure(error: String)`. No Spring types in the interface.
- **Fake provider (infrastructure):** `EchoCompletionProvider` — `name = "echo"`, returns `"echo: $prompt"` (with the model name recorded), never fails except on blank input; used by all tests and as the default runtime provider.
- **Selection:** property `kira.completion.provider=echo` (default) picks the bean by name from the injected `List<CompletionProvider>`; unknown name → startup failure. Controllers know only `CompletionService`; `CompletionService` knows only the port. A future real provider = one new class + its API key from env (`KIRA_COMPLETION_API_KEY`) — **secrets stay server-side; no provider secret ever appears in an API response or client**.
- **Transaction & failure boundaries (normative — a DB transaction is NEVER held open across a provider call):** (1) short tx: insert `completion_requests` row `PENDING` → commit; (2) short tx: update to `RUNNING` → commit; (3) invoke the provider **outside any DB transaction**, wrapped in a timeout (`kira.completion.timeout`, default 30s) — a slow/hung provider must not pin a connection-pool slot or a row lock; (4) short tx: store the sanitized outcome — `SUCCEEDED` with the result truncated to `kira.completion.max-result-length` (default 100 000 chars, truncation recorded), or `FAILED` with a **sanitized client-visible message + stable internal error code** (raw provider exceptions/stack traces go to secure server logs only, request-id-correlated; never to the client). A crash between (2) and (4) leaves a `RUNNING` row — harmless, visible, and exactly why the status exists. The **echo provider goes through this same orchestration path** (timeout, truncation, sanitization) so a future real provider changes zero orchestration code.
- **Data hygiene & retention:** prompts/results live only in the two completion tables (retention: kept indefinitely in v1; a retention window is a documented future admin policy, and the tables are RESTRICT-protected evidence until then). Prompt/result contents never appear in audit rows or logs; provider credentials/`Authorization` never logged.
- **Persistence:** every call writes `completion_requests` (user, provider, model, prompt, status) and, on completion, `completion_results` (result | error, latency). Synchronous execution in v1 within the request; the `RUNNING` status also enables async execution later without schema change.
- **Rate limits / quotas:** NOT implemented; the seam is `CompletionService.checkQuota(user)` (no-op v1, single call site) + the per-user request history already persisted (a quota needs only a count query). Documented future work.

---

## 11. Testing plan

**Tooling:** JUnit 5 + Spring Boot Test + MockMvc (+ spring-security-test's `jwt()` post-processor) + **Testcontainers PostgreSQL** — recommended over H2 because the schema leans on Postgres-specific behavior (jsonb columns, partial unique indexes, identity columns, timestamptz semantics); H2's Postgres compatibility mode diverges exactly where this schema is interesting, so H2 green would prove nothing. One shared static container (singleton pattern) keeps the suite fast; `@ServiceConnection` wires it. Pure-unit tests (validator, JWT service, state machine) run without Spring context and form the bulk.

**Unit tests (no Spring context):**
1. `SourceConfigValidatorTest` — a fully valid document (fixture modeled on the real Azora stanza) passes with zero errors.
2. Validator rejections, one test per rule family (ports of the app's engine tests): unsupported `schemaVersion`; blank/duplicate `api`; blank `language`; non-http `baseUrl`; unknown `engine`; unknown `siteState`/`lifecycle`; non-bare host in each of the three host lists (incl. the `host:8080` port case); icon: bad `resourceKey`, non-https `remoteUrl`, empty block; filters on a legacy engine.
3. Generic-source rejections: unknown pagination type; missing home+featured; blank endpoint url; raw `{query}` in url and in jsonBody; unknown method/format; unknown listFilter op/mode; unknown transform fn; unknown dateStrategy; **any** imageStrategy (empty whitelist); each filter rule §8 items 16–27 — invalid id regex, duplicate id, type pinning for `sort`/`genres`, options presence/absence, duplicate option values, every defaults case (multiselect `default` misuse, non-option default, non-boolean toggle, non-numeric number, required-without-default), every encode↔target and encode↔type incompatibility, reserved-var shadowing, path-target-without-default, appliesTo to a missing endpoint, form-target collision with formBody, body-json missing placeholder, query param hardcoded, visibleWhen unknown/self/empty-anyOf/out-of-vocabulary, excludeOf non-multiselect/chained/overlapping-defaults, and a visibleWhen **dependency cycle**.
4. `LifecycleStateMachineTest` — every allowed transition succeeds; every disallowed one (esp. `active → removed` skipping retired, and anything from `removed`) throws.
5. `CanonicalJsonTest` — canonical bytes are deterministic; defaults are omitted; **two semantically-equal documents authored with different map key orders (headers/endpoints/fields) canonicalize to identical bytes** (the JCS key-sort guarantee, §5); checksum is stable across parse→serialize round-trips; a re-parsed canonical document is semantically equal to the input model (shape-parity guarantee); no trailing newline, no insignificant whitespace.
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
17. `ImportBundledIT` — import a trimmed real bundled fixture (2 generic + 2 legacy stanzas) → sources created + published + document serves them; re-import of the identical payload → no-op per §12.2 (zero new per-source revisions, zero new snapshots); import with one bad stanza → 422, nothing persisted; incoming `revision`/`generatedAt` do NOT drive server revision allocation; a terminally `removed` source is not revived by import.
18. `CompletionIT` — anon → 401; USER posts prompt → 201, row in both tables, provider `echo`, result `echo: …`; owner can `GET` it; a different USER gets 404 for it; ADMIN can read it.
19. `AuditLogIT` — publish + disable write audit rows with actor, action, entity; audit `detail` contains **no config bodies, header values, or prompts**.
20. `FlywayMigrationIT` — context boots against a clean container (implicitly validates all migrations); `flyway_schema_history` has the expected versions in order (V1 users, V2 source_config, V3 published_documents, V4 audit_log, V5 completions).

**Amendment-mandated tests (each names the invariant it protects):**

21. `ConcurrentDifferentSourcePublishIT` — *no lost document updates*: two concurrent publications to two different sources (real parallel threads/transactions) → the final latest snapshot contains BOTH stanzas (§9 global publication lock).
22. `ConcurrentSameSourceRevisionIT` — *per-source revision numbers unique & increasing under concurrency*: N concurrent revision creations for one source → N distinct consecutive numbers, no constraint violation surfaced to the caller (§5 source-row lock).
23. `ImportCreatesSingleSnapshotIT` — *one import = at most one snapshot*: importing a multi-source document creates exactly ONE `published_documents` row, not one per source (§12.2).
24. `ImportNoChangesIsNoOpIT` — *idempotent re-import*: importing an identical document again creates zero revisions and zero snapshots and reports everything `unchanged` (§12.2).
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
35. `IfNoneMatchVariantsIT` — *conditional-GET correctness*: `If-None-Match: *` → 304; multiple comma-separated ETags including the current → 304; non-matching list → 200; 304 responses carry no body (§4.1).
36. `RealBearerTokenIT` — *the real decoder path works end-to-end*: obtain a token via actual `POST /auth/login`, call a protected endpoint with it through the real `NimbusJwtDecoder` (not the mocked `jwt()` post-processor); tampered audience/issuer/expiry variants → 401 (§6).
37. `DisabledUserAuthIT` — *disable is effective immediately*: disable a user; their previously-issued valid-signature token → 401 on next request; login refused with the generic message (§6).
38. `AuthenticationRateLimitIT` — *throttling engages and resets*: repeated failed logins for one account/IP → 429 with generic body; window expiry or success resets; a different account+IP is unaffected (no cross-account lockout) (§6).
39. `FlywayIncrementalOrderIT` — *migrations apply in phase order without `outOfOrder`*: migrate V1..V4 only (audit exists, completions absent — the Phase-6 world), then apply V5 on top — Flyway accepts the append; the reverse (a lower version appearing after a higher one is applied) is the hazard this ordering rule prevents (§5).

---

## 12. Migration plan (bundled JSON → remote) — summary

Full doc to be written as `docs/MIGRATION_BUNDLED_TO_REMOTE.md` in Phase 10 (the docs phase). Summary of the contract it will expand:

1. **The app keeps its bundled document forever** as the always-present floor (trusted via the app binary's own signature) — the backend is an *upgrade tier*, never a replacement for the floor.
2. **Server-side on-ramp — bundled-import contract (complete, normative):** seed the backend via `POST /admin/sources/import-bundled` with the current `CONFIG_BACKED_SOURCES_JSON`; first published document must carry `document_revision > 4` (the bundled revision — hence the sequence seed of 100, re-verified against the live binary at cutover, §5). Exact semantics:
   - Parse the full document with the COMPATIBILITY parser (§7); validate the WHOLE document (§8, incl. server-additional rules) — any error → 422, nothing persisted.
   - **Ignore the incoming `revision` and `generatedAt`** — the server exclusively controls document-revision allocation; the payload's values are recorded in the response/audit detail for provenance only.
   - Per source, by `api`: **absent** → create; initial server status maps from the payload's lifecycle (`"active"` → `active`, `"disabled"` → `disabled`, `"removed"` → not created at all, reported `skippedRemoved` — creating a terminal husk is pointless). **Present** → compare **canonical** content (§5) against the currently published revision: identical → `unchanged`, no new revision; different → create + publish exactly ONE new per-source revision. The payload lifecycle **never overrides an existing source's server lifecycle** (lifecycle changes go through the lifecycle endpoints); a differing payload lifecycle is reported under `lifecycleConflicts`, content still imports.
   - A server-side terminally **`removed` source is never revived** by import (reported `skippedRemoved`).
   - All per-source changes apply **without intermediate whole-document snapshots**; after the batch, materialize **exactly ONE** snapshot via the §9 sequence (global lock, whole-doc validation). If nothing changed at all → no-op: 200 with all-`unchanged` summary and **no new document revision**.
   - Any failure rolls back the entire import. Response: `{created[], updated[], unchanged[], skippedRemoved[], lifecycleConflicts[], warnings[], documentRevision?}`.
3. **App-side acceptance chain (already implemented, verified in `RemoteSourceConfigManager`):** fetched remote must (a) pass signature verification, (b) parse, (c) pass the full validator, (d) have `revision >=` the accepted floor — any failure drops the document silently and the previous good document (cache, else bundled) stays active. The backend's job is to make (b)–(d) always true and to be ready for (a).
4. **Failure semantics restated:** failed fetch → bundled/cache; unsupported `schemaVersion` → ignored (validator gate); failed checksum/signature → ignored; revisions are stable and monotonic; **no silent deletion** — a source is `disabled` in the document before it is ever `retired`/dropped.
5. **Explicitly OUT OF SCOPE for the backend task:** implementing the app's `RemoteConfigSource` HTTP client, enabling `remote` in the app's DI, the signing key ceremony, and staged rollout. Those are app-repo work items listed as future.

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
| **Auth rate limiting (login/registration throttling)** | **Foundation now** | Single-factor password auth without brute-force protection is not shippable; bounded in-memory impl, Redis seam later (§6) |
| **Minimal admin user management** (§4.4) | **Foundation now** | Prod disables registration — without admin-created users there is no lawful onboarding path at all |
| Completion rate limits / per-user quota | Future (seam now) | `checkQuota()` no-op seam + persisted history make it a small later change (distinct from auth throttling above, which IS v1) |
| API keys (machine auth) | Future | JWT covers v1 consumers; key mgmt is its own surface |
| Refresh tokens | Future (seam now) | §6 seam; table design reserved in §5 |
| Document signing (Ed25519) | Future | Blocked on Open Q1 (key ceremony); nullable column ready |

---

## 14. Explicit non-goals

This task will NOT build: the mobile app's remote-fetch client or any change in the mobile repos; any admin web UI; a real AI/completion provider integration (or any provider SDK dependency); **completion** rate limiting/quotas (seam only — **auth throttling IS in scope**, §6); refresh-token issuance/rotation (seam only); document signing (column-ready only); staged rollout / percentage targeting; source health probing; client telemetry/popularity; multi-tenancy; API keys; deployment manifests (K8s/Terraform/CI pipelines) beyond `docker-compose.yml` for local dev; extraction of the validation package into an actual shared module (structured-for, not done). Also explicitly NOT introduced without a concrete present need: replacing JPA (jOOQ/Exposed/raw JDBC), Redis/Caffeine caching, a generic domain-event system (publication/validation/snapshot/audit stay direct transactional orchestration), a source dependency graph, Gradle multi-module split, documents as an independent bounded context, asymmetric JWT signing.

---

## 15. Phased build order (implementation checklist — each phase compiles & tests green, one commit-sized step each)

1. **Scaffold** — `settings.gradle.kts`, `build.gradle.kts` (Boot 3.5.x, Kotlin plugins incl. serialization/spring/jpa, Java 21 toolchain, dependency set from §3 incl. actuator), **verify version compatibility once and record the EXACT versions** (§3 pinning rule: Gradle wrapper, Boot, Kotlin, kotlinx-serialization, springdoc, Testcontainers, Postgres image tag, Java toolchain), `KiraBackendApplication`, `application.yml` + `application-dev.yml` (incl. `spring.jpa.open-in-view=false`, `ddl-auto=validate`, graceful shutdown, actuator exposure per §6), `docker-compose.yml` (pinned postgres 17 image on 5433, named volume), `.gitignore` + `.env.example`, README stub, Flyway wired with **V1__users.sql** (incl. `uq_users_email_lower`), Testcontainers base class + a `contextLoads` IT. *Green: `./gradlew build` with Docker running.*
2. **Common layer** — problem envelope + `GlobalExceptionHandler`, typed exceptions, `PageResponse`, `Sha256`, **JCS canonicalization** (§5) + `CanonicalJson`, correlation-ID filter + logging config (§6 hygiene), OpenAPI config, `@ConfigurationProperties` classes with validation. Tests: 5 (unit).
3. **User + auth + security** — user domain/entity/repo (V1 already in place), `DelegatingPasswordEncoder`, `JwtService` + Nimbus encoder/decoder (issuer + audience + skew validation), DB-backed `CurrentUser` resolver (disabled-user rejection), `SecurityFilterChain` per §6 matrix, `AuthThrottleService`, `AuthController` (register/login/me, password policy), **`AdminUsersController` + `UserAdminService` (§4.4, last-admin guard)**, `AdminSeeder` (env-required, no password logging). Tests: 6, 7 (unit), 9, 10, 11, 36, 37, 38 (IT).
4. **Source-config model + validation (pure)** — the §7 data classes, STRICT + COMPATIBILITY parsers (§7), canonical outbound, `StrategyCatalog` + `ServerStrategyCatalog` whitelists, `PackagedIconCatalog` (§8 rule 33), the full §8 validator (incl. rules 31–33) with stable error codes, fixtures (valid Azora-style + invalid set + **`bundled-full.json`**). Tests: 1, 2, 3, 8b (unit) — the largest single phase; may split model/validator into two commits.
5. **Source-config persistence** — **V2__source_config.sql** (canonical TEXT column, composite FK via ALTER TABLE, RESTRICT FKs) + **V3__published_documents.sql** (+ sequence + **`document_publication_state`** singleton), entities, Spring Data repos, port adapters, domain↔entity mapping, `LifecycleStateMachine` (§9 incl. engine-gated un-retire). Tests: 4 (unit), 20, 33, 39 (IT).
6. **Admin management** — `SourceAdminService` + `DocumentAssemblyService` (global publication lock + the 10-step §9 sequence, candidate assembly, whole-doc validation, snapshot materialization), all §4.3 endpoints except import (incl. `API_ID_MISMATCH` / `LIFECYCLE_NOT_AUTHORABLE` guards), **V4__audit_log.sql** + `AuditService` wired into every mutation. Tests: 12, 13, 14, 15, 19, 21, 22, 26, 27, 28, 29, 30, 31 (IT).
7. **App-facing API** — `SourceDocumentController` (latest + `/meta`, raw-bytes writer, ETag/304/Cache-Control/X-headers; **no public historical revisions**), `SourcesController` (list + by-api per §4.1 status mapping). Tests: 16, 34, 35 + the visibility halves of 14 (IT).
8. **Bundled import** — `import-bundled` endpoint on top of the Phase-6 services implementing the §12.2 contract, trimmed + full real fixtures. Tests: 17, 23, 24, 25, 32 (IT).
9. **Completion foundation** — **V5__completions.sql**, port + `EchoCompletionProvider` + property selection, `CompletionService` (three-transaction orchestration + timeout + sanitization per §10, no-op `checkQuota`), `CompletionController`. Tests: 8 (unit), 18 (IT).
10. **Hardening + docs** — full-suite pass, security-matrix sweep against every route, log-hygiene sweep (no bodies/Authorization/passwords in logs), then write `README.md`, `docs/API.md`, `docs/SOURCE_CONFIG_LIFECYCLE.md`, `docs/SECURITY.md`, `docs/LOCAL_DEV.md`, `docs/MIGRATION_BUNDLED_TO_REMOTE.md`.

(Phases 4–5 could swap order internally, but keep validation before persistence so entity design is informed by the final model. Phases 7 and 8 depend on 6; 9 is independent after 3 and can run any time after it.)

---

## 16. Open questions for the owner (each with a safe default so implementation is NOT blocked)

1. **Document signature scheme** — Ed25519 detached signature over the canonical bytes, key from env? *Default: do not sign in v1 (app-side remote requires a verifier that currently denies everything anyway); keep the nullable column out until decided. Recommend Ed25519 when built.*
2. **Real completion provider choice** (Anthropic? other? none?) — *Default: echo provider only; interface is provider-agnostic; no SDK dependency added.*
3. **Access-token TTL** — *Default: 60 minutes (`kira.security.access-token-ttl=PT60M`), configurable; refresh tokens remain future work.*
4. **Admin seeding when env vars are absent** — **RESOLVED by amendment #13 (no longer open):** startup fails with a clear message in every profile where seeding is enabled; local dev supplies `KIRA_ADMIN_EMAIL`/`KIRA_ADMIN_PASSWORD` via the gitignored `.env` (`.env.example` documents it). Plaintext passwords are never logged, in any profile.
5. **Open registration** — should `POST /auth/register` be publicly enabled? *Default: property `kira.auth.registration-enabled`, `true` in dev, `false` in prod.* **The prod onboarding mechanism is now concrete: admin-created users via §4.4** (amendment #14).
6. **Server-side `minAppVersion` filtering** — filter stanzas per `?appVersion` (per-variant ETags) or serve one document to all clients? *Default: serve one document; store/serve `minAppVersion` as data; accept + log `appVersion`. Matches the app engine, which does not enforce the field yet.*
7. **Document-revision seed value** — **RESOLVED by amendment #18:** sequence starts at 100 (dev default); `kira.config.revision-floor` (default 100) is asserted at startup; at production cutover ops verifies the next server revision exceeds the revision actually shipped in the live app binary (currently 4 — never assumed to stay 4). Revisions are unique and strictly increasing but **may contain gaps** (sequence semantics) — nothing may assume contiguity.
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
