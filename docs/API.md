# kira-backend API

Every endpoint is under `/api/v1`. This document is derived from the controllers and DTOs in
`src/main/kotlin`; where it and [`PLAN.md`](PLAN.md) differ, the code wins. Errors use the problem
envelope in [Error model](#error-model). Auth and token semantics are in [`SECURITY.md`](SECURITY.md);
lifecycle semantics in [`SOURCE_CONFIG_LIFECYCLE.md`](SOURCE_CONFIG_LIFECYCLE.md).

Auth levels: **anon** (no token), **USER** (bearer, any enabled user), **ADMIN** (bearer, `ADMIN`
role). Authorization is enforced in the security filter chain before dispatch; authorities are
derived from the **DB role**, not the token claim.

## Error model

Errors are RFC-9457 `application/problem+json`:

```json
{ "type": "about:blank", "title": "Bad Request", "status": 400,
  "detail": "prompt must not be blank.",
  "errors": [ { "code": "BLANK_PROMPT", "path": "prompt", "message": "..." } ] }
```

`type`/`detail`/`errors` are omitted when empty (`NON_EMPTY` inclusion). `errors[]` carries
`{code, path?, message}` field-level pinpoints (e.g. a validation result). Errors never echo a submitted
config body, header value, password, or token; some source/revision path identifiers currently appear
in 404/409 details. Typed-exception → status mapping:

| Status | Exception / source | Typical `code` |
|---|---|---|
| 400 | `BadRequestException`; malformed body; bad param type; bean-validation | `BAD_REQUEST`, `BLANK_PROMPT`, Tier-1 gate codes, `INVALID_*_FILTER`, `INVALID_PAGE*` |
| 401 | security pipeline (missing/invalid bearer); `UnauthorizedException` (bad login) | `INVALID_CREDENTIALS` |
| 403 | security pipeline (role); `ForbiddenException` | `REGISTRATION_DISABLED` |
| 404 | `NotFoundException` (+ subclasses); unmatched route | `NOT_FOUND`, `*_NOT_FOUND`, `NO_PUBLISHED_DOCUMENT` |
| 405 | unsupported method | `METHOD_NOT_ALLOWED` |
| 406 | unsupported response media type | `NOT_ACCEPTABLE` |
| 409 | `ConflictException`; lifecycle/data-integrity conflict | `CONFLICT`, `DATA_INTEGRITY_CONFLICT`, `INVALID_LIFECYCLE_TRANSITION`, `REVISION_SUPERSEDED`, last-admin guard |
| 410 | `GoneException` | `GONE` (removed source) |
| 413 | body/prompt limit | `PAYLOAD_TOO_LARGE`, `PROMPT_TOO_LARGE` |
| 415 | unsupported request media type | `UNSUPPORTED_MEDIA_TYPE` |
| 422 | `ValidationFailedException` | `VALIDATION_FAILED` (+ `errors[]`) |
| 429 | `TooManyRequestsException` | `TOO_MANY_REQUESTS` |
| 500 | unexpected (stack trace logged server-side only) | — |

## Cross-cutting HTTP contract

- **Pagination:** `?page=0&size=20`; `size` max **100** (`GET /admin/users`, `GET /completions`).
  `page < 0` or `size < 1`/`size > 100` → 400. Response envelope: `{items, page, size, total}`.
  `GET /sources` is deliberately **not** paginated — it returns the bounded document as a plain array.
- **Multi-value filters** (`?lifecycle=`, `?engine=`, `?status=`): comma-separated within one query
  param; an unknown token → 400.
- **Request-body size:** every request body is capped at **256 KiB** before MVC parsing, except
  `POST /admin/sources/import-bundled`, which is capped at **5 MiB**. Declared and streamed/chunked
  bodies use the same 413 `PAYLOAD_TOO_LARGE` response. Completion prompts additionally have a
  configurable character cap (default 8000).
- **Responses:** raw source-config routes explicitly send `application/json; charset=UTF-8` and add the
  documented cache/nosniff headers. Jackson-rendered endpoints currently send `application/json`
  without an explicit charset, which is valid JSON but differs from the original normative plan.

---

## 1. App-facing (public, read-only, no auth)

### `GET /api/v1/source-config/document`
The app document — the **latest** published snapshot, served as the exact stored canonical
`SourceConfigDocument` bytes (never re-serialized).

- Query: `appVersion` (optional; validated semver-ish `\d+(\.\d+){0,3}([-+]…)?`, max 64 chars;
  recorded/logged, **no filtering in v1**; invalid → 400 `INVALID_APP_VERSION`).
- Request header: `If-None-Match` (optional; see [ETag semantics](#etag-semantics)).
- **200** with body + headers: `ETag: "<sha256-hex>"` (strong, quoted, = document checksum),
  `Cache-Control: public, max-age=300, no-transform`, `X-Content-Type-Options: nosniff`,
  `X-Config-Revision: <n>`, `X-Config-Checksum: <sha256>`.
- **304** (no body, same ETag/Cache-Control) when `If-None-Match` matches.
- **404** `NO_PUBLISHED_DOCUMENT` when nothing has been published.

### `GET /api/v1/source-config/document/meta`
Cheap poll — is there anything newer, without the body.

- **200** `{ "revision": <long>, "schemaVersion": <int>, "checksum": "<sha256>", "publishedAt": "<instant>" }`
  (plus `Cache-Control`/nosniff headers). **404** when nothing published.

### `GET /api/v1/sources`
Summaries of the sources in the current document, ordered by the normative document order
(`position ASC, api ASC`). Plain JSON **array** (no pagination). No document → `[]`.

- Query filters (comma-separated, unknown value → 400): `lifecycle` ∈ {`active`,`disabled`,`removed`}
  (`INVALID_LIFECYCLE_FILTER`); `engine` ∈ {`generic`,`legacy`} (`INVALID_ENGINE_FILTER`).
- **200** — each item:

```json
{ "api": "Azora", "displayName": "Azora", "language": "ar", "engine": "generic",
  "lifecycle": "active", "siteState": "WORKING", "adult": false,
  "baseUrl": "https://azoramoon.co", "iconRemoteUrl": "https://…",
  "revisionNumber": 1, "publishedAt": "<instant>" }
```

`iconRemoteUrl` is omitted when the stanza has none. `lifecycle` is the **app vocabulary** — a
server-`retired` source appears as `"removed"`. Draft-only and server-`removed` sources never appear.

### `GET /api/v1/sources/{api}`
The single published `SourceConfig` stanza, served as raw canonical bytes, **consistent with the
document**:

| Server status | Result |
|---|---|
| `active` | **200** (stanza `lifecycle:"active"` = key omitted) |
| `disabled` | **200** (`lifecycle:"disabled"`) |
| `retired` | **200** (`lifecycle:"removed"` — still in the served document during the grace window) |
| `removed` | **410 Gone** |
| unknown / draft-only | **404** |

### ETag semantics (normative)

Strong quoted ETags (`ETag: "a1b2…"`). `If-None-Match: *` matches whenever any document exists → 304.
A comma-separated `If-None-Match` list is parsed and each entry compared with **strong comparison**
(quotes stripped); a match → **304 with no body**. **Weak validators never match**: `W/"<hash>"` fails
strong comparison even when the opaque hash is identical → **200** with the full body. The checksum is
computed over the exact UTF-8 bytes sent. `X-Config-Checksum` is a **corruption check, not
authenticity** — a hash beside the same payload cannot authenticate it; authenticity is HTTPS today +
a future detached signature.

---

## 2. Auth

### `POST /api/v1/auth/register`  — anon
Gated by `kira.auth.registration-enabled` (default `true` dev / `false` prod). Body `{email, password}`.
Password policy: **min 15 chars, max 72 UTF-8 bytes**, no composition rules, no trimming/normalization
of the password (email is trim + lowercased).

- **201** `{ "id": "<uuid>", "email": "…", "role": "USER" }`
- **409** duplicate email (case-insensitive) · **400** policy violation · **403** `REGISTRATION_DISABLED`
  · **429** per-IP registration throttle.

### `POST /api/v1/auth/login`  — anon
Body `{email, password}`.

- **200** `{ "accessToken": "<jwt>", "tokenType": "Bearer", "expiresInSeconds": 3600, "role": "USER" }`
- **401** `INVALID_CREDENTIALS` — the same generic response body for unknown-user / wrong-password /
  disabled account. All three paths perform one password-hash verification (a decoy hash for an
  unknown/disabled account).
- **429** when either the normalized-email/client-IP identity bucket or the aggregate client-IP spray
  bucket is temporarily blocked.

### `GET /api/v1/auth/me`  — USER or ADMIN
- **200** `{ "id": "<uuid>", "email": "…", "role": "USER|ADMIN", "createdAt": "<instant>" }` · **401** anon.

### `POST /api/v1/auth/refresh`  — not registered
No handler in v1 (refresh tokens are future work). Anonymous → **401** (the `anyRequest authenticated`
catch-all); an authenticated caller passes security and gets the standard **404** — proving it is
genuinely unregistered, not a 501 stub.

---

## 3. Admin — source management  (ADMIN only; every mutation writes `audit_log`)

Authoring bodies are parsed with the **STRICT** parser: unknown keys, duplicate keys, and trailing
garbage are 400s with the offending token named. Every create/new-revision runs the **Tier-1
structural gate** *before* any row is created — a violation is a 400 that persists nothing:
`API_ID_MISMATCH` (`body.api != {api}`), `LIFECYCLE_NOT_AUTHORABLE` (payload `lifecycle` not the
neutral `"active"`), `API_IDENTIFIER_INVALID` (blank / > 128 chars / control chars / `/` or `\` /
edge whitespace), `FIELD_TOO_LONG` (identity/denormalized value over a DB column limit). Semantic
(Tier-2) validation is stored on the draft and returned inline even when invalid.

Header names must be valid RFC HTTP field-name tokens. Blank, non-token, or leading/trailing-whitespace
names fail validation with `HEADER_NAME_INVALID`, so padded sensitive names cannot bypass the public-
credential rules. Published configuration is public; never place a real credential in any header.

| Method & path | Purpose | Codes |
|---|---|---|
| `POST /admin/sources` | Create a source (body = full `SourceConfig`; `api` is the identity). Appends to document order (`position = max+1`). | 201 · 409 api exists · 400 strict-parse/Tier-1 |
| `GET /admin/sources` | All sources incl. drafts/retired/removed. Query `?status=`. | 200 |
| `GET /admin/sources/{api}` | Full admin head view. | 200 · 404 |
| `POST /admin/sources/{api}/revisions` | New draft revision (`body.api` must equal `{api}`). | 201 · 404 · 400 |
| `GET /admin/sources/{api}/revisions` | Revision list. | 200 · 404 |
| `GET /admin/sources/{api}/revisions/{n}` | Full stored config JSON + metadata. | 200 · 404 |
| `POST /admin/sources/{api}/revisions/{n}/validate` | Re-run validation (preview; stores result). | 200 (even when invalid) · 404 |
| `GET /admin/sources/{api}/revisions/{n}/validation` | Latest stored validation result. | 200 · 404 |
| `POST /admin/sources/{api}/revisions/{n}/publish` | Publish (server re-validates in-tx). | 200 (or 200 no-op) · 422 invalid · 409 · 404 |
| `POST /admin/sources/{api}/disable` | `active → disabled`. | 200 · 409 |
| `POST /admin/sources/{api}/enable` | `disabled → active`; `retired → active` (generic only). | 200 · 409 |
| `POST /admin/sources/{api}/retire` | `disabled → retired` only. | 200 · 409 |
| `POST /admin/sources/{api}/remove` | `retired → removed` (terminal). Body `{confirm: "<api>"}`. | 200 · 409 · 400 |
| `POST /admin/sources/{api}/rollback` | Body `{toRevision}`. Copies that content into a new highest revision, re-validates, publishes. | 200 · 422 · 409 · 404 |
| `GET /admin/documents` | Published snapshots (metadata list). | 200 |
| `GET /admin/documents/{revision}` | Raw stored canonical bytes of that snapshot (metadata in headers). | 200 · 404 |
| `POST /admin/documents/validate` | Validate the candidate document without publishing. | 200 `{valid, errors[]}` |
| `POST /admin/documents/republish` | Force-materialize a new snapshot from current state (always a new revision). | 200 |
| `POST /admin/sources/import-bundled` | The migration on-ramp — see [4. Import](#4-import-bundled). | 200 · 400 · 413 · 422 |

**Selected response shapes** (Jackson-serialized; lifecycle/revision statuses are lowercase wire
values):

- Create / new revision → `SourceMutationResponse`:
  `{ "api", "status", "revisionNumber", "validation": { "valid", "errors": [{code,path,message}], "warnings": [...] } }`
- `GET /admin/sources` item / `GET /admin/sources/{api}` → `AdminSourceResponse`:
  `{ "api", "displayName", "language", "engine", "status", "position", "baseUrl", "adult",
     "currentPublishedRevisionNumber"?, "latestRevisionNumber"?, "createdAt", "updatedAt", "publishedAt"? }`
- Revision list item → `{ "revisionNumber", "status", "checksum", "createdBy", "createdAt", "publishedAt"?, "valid"? }`
- Revision detail → `{ "revisionNumber", "status", "config": <raw canonical JSON>, "checksum",
     "canonVersion", "createdBy", "createdAt", "publishedAt"?, "valid"? }` — `config` is the stored
     lifecycle-neutral canonical bytes emitted verbatim.
- Publish / lifecycle transitions → `{ "documentRevision", "checksum" }`. A currently-published
  revision re-published → **200 no-op** (no new snapshot).
- Rollback → `{ "newRevisionNumber", "documentRevision", "checksum" }`.
- `GET /admin/documents` item → `{ "documentRevision", "schemaVersion", "checksum", "sourceCount", "createdBy", "createdAt" }`.
- `GET /admin/documents/{revision}` → **body = raw stored canonical bytes**; metadata in headers only
  (`ETag: "<checksum>"`, `X-Config-Revision`, `X-Config-Checksum`) — deliberately not a JSON envelope.

**Publishable-revision rules** (409 codes): re-publish the current published revision → 200 no-op;
a `superseded` revision → `REVISION_SUPERSEDED`; a draft older than the published revision →
`REVISION_OLDER_THAN_PUBLISHED`; any revision of a `retired`/`removed` source → 409. Restoring old
content always goes through `rollback`.

### 4. import-bundled

`POST /api/v1/admin/sources/import-bundled` — body = the app's bundled document JSON (max **5 MiB**,
parsed with the **COMPATIBILITY** parser). Validates the whole document (any error → **422**, nothing
persisted), applies per-source create/update/no-op with server-controlled revisions, and materializes
**exactly one** snapshot (all-or-nothing). Incoming `revision`/`generatedAt` are ignored (recorded for
provenance only); each stanza's `lifecycle` is read separately and normalized away before storage.

- **200** `ImportBundledResponse`:

```json
{ "created": ["…"], "updated": ["…"], "unchanged": ["…"],
  "skippedRemoved": ["…"], "skippedRetired": ["…"], "skippedDraft": ["…"],
  "lifecycleConflicts": [ { "api": "…", "payloadLifecycle": "disabled", "serverLifecycle": "active" } ],
  "warnings": [ { "code": "…", "path": "…", "message": "…" } ],
  "documentRevision": 101 }
```

`documentRevision` is **absent** on the pure no-op case (nothing changed → no new snapshot). Full
semantics: [`MIGRATION_BUNDLED_TO_REMOTE.md`](MIGRATION_BUNDLED_TO_REMOTE.md).

Existing draft-only sources are never replaced or published by import; they are returned in
`skippedDraft`. Importing into a partly existing catalog can still retain old positions rather than
reproduce payload order exactly, so review ordering before later re-imports.

---

## 5. Admin — user management  (ADMIN only; every mutation audited)

Prod onboarding (registration disabled): admins create users. Responses never echo password material.

| Method & path | Purpose | Codes |
|---|---|---|
| `POST /admin/users` | `{email, password, role}` → create. Password policy of §2; email case-insensitively unique. | 201 · 409 duplicate · 400 |
| `GET /admin/users` | Paginated (`?page&size`, size ≤ 100). | 200 |
| `POST /admin/users/{id}/enable` | Re-enable a disabled user. | 200 · 404 |
| `POST /admin/users/{id}/disable` | Disable (in-flight tokens die at the next request). Refuses to disable the **last enabled ADMIN**. | 200 · 404 · 409 last-admin |
| `POST /admin/users/{id}/reset-password` | `{newPassword}` (policy-checked); audited, never logs the password. | 200 · 404 · 400 |

- Create → **201** `{ "id", "email", "role" }` (from `AdminUserResponse`; `POST` returns the created id).
- List item → `{ "id", "email", "role", "enabled", "createdAt" }` — no password material, ever.

---

## 6. Completion  (authenticated: USER or ADMIN)

| Method & path | Purpose | Codes |
|---|---|---|
| `POST /api/v1/completions` | `{prompt, model?}` → run the configured provider (echo in v1) and persist. | 201 · 401 anon · 400 blank prompt or model over 128 chars · 413 prompt too large |
| `GET /api/v1/completions/{id}` | Fetch one — **owner or ADMIN only** (others → 404, never 403). | 200 · 404 |
| `GET /api/v1/completions` | List the caller's own requests, newest first, paginated. ADMIN may pass `?userId=`. | 200 |

- Request `{ "prompt": "…", "model"?: "…" }`. Blank prompt → **400** `BLANK_PROMPT`; prompt over
  `kira.completion.prompt-max-length` (default 8000) → **413** `PROMPT_TOO_LARGE`. A supplied `model`
  over **128 characters** is rejected before persistence with **400** `MODEL_TOO_LONG`.
- Response `CompletionResponse`:

```json
{ "id": "<uuid>", "status": "SUCCEEDED", "model": "echo-1", "provider": "echo",
  "result": "echo: …", "errorCode": null, "error": null, "createdAt": "<instant>" }
```

On success `result` is set and `errorCode`/`error` are null; on failure `errorCode` (a stable §10
catalog value, e.g. `PROVIDER_TIMEOUT`) and `error` (a sanitized, bounded, generic message) are set
and `result` is null. The prompt is never echoed back. The default model when `model` is omitted is
`echo-1`. Error-code catalog: `PROVIDER_TIMEOUT`, `PROVIDER_UNAVAILABLE`, `PROVIDER_REJECTED`,
`INVALID_PROVIDER_RESPONSE`, `RESULT_TOO_LARGE` (reserved, unused in v1), `INTERNAL_COMPLETION_ERROR`
(every unexpected exception maps here). Raw provider exceptions are never returned or stored — secured
server logs only.

Provider work runs on `kira.completion.executor-threads` workers (default 8) with a bounded
`kira.completion.queue-capacity` (default 64). Saturation fails the request outcome as
`PROVIDER_UNAVAILABLE` instead of growing an unbounded in-memory queue.
