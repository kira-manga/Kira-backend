# kira-backend API

Most endpoints are under `/api/v1`; the incremental source-catalog protocol is under `/api/v2`.
This document is derived from the controllers and DTOs in
`src/main/kotlin`; where it and [`PLAN.md`](PLAN.md) differ, the code wins. Errors use the problem
envelope in [Error model](#error-model). Auth and token semantics are in [`SECURITY.md`](SECURITY.md);
lifecycle semantics in [`SOURCE_CONFIG_LIFECYCLE.md`](SOURCE_CONFIG_LIFECYCLE.md).
Tutorial publishing and its public/ADMIN route inventory are in [`TUTORIALS.md`](TUTORIALS.md).

Auth levels: **anon** (no token), **USER** (current, non-revoked bearer for an enabled user), **ADMIN** (bearer, `ADMIN`
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
| 409 | `ConflictException`; lifecycle/data-integrity conflict | `CONFLICT`, `DATA_INTEGRITY_CONFLICT`, `INVALID_LIFECYCLE_TRANSITION`, `REVISION_SUPERSEDED`, `CREDENTIAL_VERSION_EXHAUSTED`, last-admin guard |
| 410 | `GoneException` | `GONE` (removed source) |
| 413 | body/prompt limit | `PAYLOAD_TOO_LARGE`, `PROMPT_TOO_LARGE` |
| 415 | unsupported request media type | `UNSUPPORTED_MEDIA_TYPE` |
| 422 | `ValidationFailedException` | `VALIDATION_FAILED` (+ `errors[]`) |
| 429 | `TooManyRequestsException` | `TOO_MANY_REQUESTS` |
| 503 | `ServiceUnavailableException` | `SERVICE_UNAVAILABLE` |
| 500 | unexpected (stack trace logged server-side only) | — |

## Cross-cutting HTTP contract

- **Pagination:** `?page=0&size=20`; `size` max **100** (`GET /admin/users`, `GET /completions`).
  `page < 0` or `size < 1`/`size > 100` → 400. Response envelope: `{items, page, size, total}`.
  `GET /sources` is deliberately **not** paginated — it returns the bounded document as a plain array.
  The two admin source/document history lists are an array-compatible **keyset exception**;
  see [Admin history windows](#admin-history-windows). Other pagination contracts are unchanged.
- **Multi-value filters** (`?lifecycle=`, `?engine=`, `?status=`): comma-separated within one query
  param; an unknown token → 400.
- **Request-body size:** every request body is capped at **256 KiB** before MVC parsing, except
  `POST /admin/sources/import-bundled` and multipart `POST /admin/tutorial-media`, which are capped at
  **5 MiB** request size (the media file itself is capped at **4 MiB**). Declared and streamed/chunked
  bodies use the same 413 `PAYLOAD_TOO_LARGE` response. Completion prompts additionally have a
  configurable character cap (default 8000).
- **Responses:** raw source-config routes explicitly send `application/json; charset=UTF-8` and add the
  documented cache/nosniff headers. Jackson-rendered endpoints currently send `application/json`
  without an explicit charset, which is valid JSON but differs from the original normative plan.

---

## 1. App-facing (public, read-only, no auth)

### Source catalog v2

`GET /api/v2/source-config/manifest` returns exact stored signed `kcj-1` manifest bytes:

```json
{
  "schemaVersion": 1,
  "sourceSchemaVersion": 1,
  "catalogRevision": 101,
  "generatedAt": "2026-07-23T00:00:00Z",
  "sources": [{
    "api": "Azora",
    "sourceRevision": 1,
    "checksum": "<sha256>",
    "order": 0,
    "lifecycle": "active",
    "engine": "generic",
    "sourceSigningKeyId": "prod-2026-01",
    "sourceSignature": "<base64-ed25519>"
  }],
  "removedSources": [{"api": "Previously Published", "lifecycle": "removed"}]
}
```

Only `active|disabled|retired` generic entries are allowed. Draft, withheld, removed, and non-generic
sources are absent. `removedSources` contains only identity tombstones for APIs previously published
through v2. Response headers use the existing `X-Config-*` family; `X-Config-Revision` is the catalog
revision. The strong ETag equals the manifest checksum; matching `If-None-Match` returns a bodiless
304. Signature format is `kira-source-catalog-manifest-v1`.

`GET /api/v2/source-config/sources/{api}/revisions/{positiveRevision}` returns exact immutable,
lifecycle-neutral source JSON only when that tuple appeared in a public v2 manifest. It sends
`ETag`, immutable one-year cache control, `X-Source-Api`, `X-Source-Revision`, `X-Source-Checksum`,
and `X-Source-Canon-Version`. Matching `If-None-Match` returns 304. Draft, legacy, withheld-only,
unknown, and guessed revisions all return 404. Source signatures use `kira-source-revision-v1` and
are carried by the signed manifest.

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
Each response uses one catalog generation for stanza fields and source revision/publication metadata;
a concurrent publication may yield the old or new generation, never a mixture.

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

These semantics apply to v1 public/latest and admin/history documents, and v2 manifests/immutable
sources. Emitted ETags stay strong and quoted (`ETag: "a1b2…"`). `If-None-Match` uses **weak comparison**
(RFC 9110 §13.1.2): valid `"<hash>"` and uppercase `W/"<hash>"` tags match the same case-sensitive
opaque value → **304 with no body**, retaining the route's ETag/cache/metadata headers. A standalone
`*` matches an existing selected representation; it never bypasses a missing-artifact 404.

Lists are quote-aware: commas inside opaque tags are not separators, and backslash is literal (not
a quote escape). Only SP/HTAB count as outside optional whitespace; empty list elements are accepted.
The **entire field** must be valid: unquoted/malformed tags, mixed wildcard/list forms, or junk before
or after an otherwise matching entry do not match. Such values reaching the writer return **200**
with the full body. This does not change the strong `If-Match` contract for admin optimistic locking.

The checksum is computed over the exact UTF-8 bytes sent. `X-Config-Checksum` is a **corruption check, not
authenticity** — a hash beside the same payload cannot authenticate it. Authenticity comes from the
`kira-source-signature-v1` Ed25519 metadata returned in `X-Config-Signature-*`, `X-Config-Signing-Key-Id`,
`X-Config-Previous-*`, and `X-Config-Created-At`; `/document/meta` carries the same fields.

---

## 2. Auth

Email identifiers use `trim().lowercase()` and allow at most **320 Unicode code points after
normalization**, not 320 UTF-16 units or UTF-8 bytes. Supplementary characters count once; lowercase
expansion counts in the result. Oversize yields a value-free **400 `EMAIL_TOO_LONG`**. This shared
bound applies to login, registration, admin creation and seeding; it adds no RFC-shape rule or
raw-input size constraint. Existing structural request validation and security gates retain precedence.

### `POST /api/v1/auth/register`  — anon
Gated by `kira.auth.registration-enabled` (default `true` dev / `false` prod). Body `{email, password}`.
Password policy: **min 15 chars, max 72 UTF-8 bytes**, no composition rules, no trimming/normalization
of the password (email is trim + lowercased).

- **201** `{ "id": "<uuid>", "email": "…", "role": "USER" }`
- **409** duplicate email (case-insensitive) · **400** password policy / `EMAIL_TOO_LONG` · **403** `REGISTRATION_DISABLED`
  · **429** per-IP registration throttle.

The registration-enabled gate and per-IP throttle still run before the shared creation check.
Within creation, the email bound precedes password policy, duplicate lookup, hashing and insertion.

### `POST /api/v1/auth/login`  — anon
Body `{email, password}`.

- **200** `{ "accessToken": "<jwt>", "tokenType": "Bearer", "expiresInSeconds": 3600, "role": "USER" }`
- **400** `EMAIL_TOO_LONG` — checked before login throttle, lookup, credential verification, token or audit work.
- **401** `INVALID_CREDENTIALS` — the same generic response body for unknown-user / wrong-password /
  disabled account. All three paths perform one password-hash verification (a decoy hash for an
  unknown/disabled account).
- **429** when either the normalized-email/client-IP identity bucket or aggregate IP bucket is blocked,
  its completed failures plus live attempts fill the threshold, or safe shared-store capacity is unavailable.
  Admission reserves both dimensions before lookup/hash work. Only acknowledged, unexpired successful
  completion permits a JWT. The default 30s attempt lease is not a BCrypt execution timeout; late success
  also returns generic 429. See [SECURITY.md](SECURITY.md#auth-throttling--trusted-client-ip-resolution).
- Shared throttle errors/null/malformed replies on admission **or completion** return **429
  `AUTH_THROTTLE_UNAVAILABLE`**, `Retry-After: 5`, with no local fallback. Ordinary bad-credential
  completion precedes its audit/401, so this dependency error retains precedence over that 401.

### `GET /api/v1/auth/me`  — USER or ADMIN
- **200** `{ "id": "<uuid>", "email": "…", "role": "USER|ADMIN", "createdAt": "<instant>" }` · **401** anon/invalid/revoked bearer.

New JWTs carry a private `credential_version` claim as a canonical decimal **string**, paired with
the exact password snapshot verified at login. Every bearer request checks equality with the current
database version, in addition to enabled/current-role enforcement. Password reset invalidates the
target's older tokens at authentication checks after commit, including admin step-up requests even
with the correct new password. Already-authenticated work is not retroactively cancelled, and this
does not add revocation of previously issued step-up grants. Login racing reset can return an already
stale token, never a refreshed version for an old verified password. Missing pre-upgrade claims and
malformed/wrong-typed/mismatched versions receive the existing generic **401**; users must sign in again
once at cutover. User/login response DTO shapes are unchanged. See the coordinated rollout and
old-image rollback limits in [SECURITY.md](SECURITY.md#password-reset-semantics-and-coordinated-cutover).

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

Static header names and header-target filters' `request.param` must be exact nonempty ASCII RFC HTTP
field-name tokens. Blank, non-token, or whitespace-padded names fail `HEADER_NAME_INVALID`; names are
never trimmed or repaired. `genre[]` remains legal for query/form parameters, not header names.
Both contexts reject `cookie`, `set-cookie` and `proxy-authorization` case-insensitively with
`FORBIDDEN_HEADER`. Header-target filters also reject all sensitive names (`authorization`, `x-api-key`,
`api-key`, `x-auth-token`, or any name containing `token`/`secret`/`password`) with `SECRET_LIKE_HEADER`
at `sources[api].filters[id].request.param`, irrespective of values, defaults, options, visibility,
encoding or toggle mappings. Dynamic credential filters are unsupported, even with `Bearer null`;
the configured exact-value placeholder allowlist applies to **static headers only**.
These remain Tier-2 findings: invalid admin drafts retain their authored content and validation,
but new publication, editor quick-publish and whole-document import reject invalid candidates.
Published configuration is public; never place a real credential in any field.

Filter option/default/condition-value findings and new header-filter findings use fixed explanatory
messages without submitted values. Structural source/filter identifiers remain in paths; this is not
a universal arbitrary-field redaction guarantee. Historical stored diagnostics are not rewritten.

The Source Admin Studio uses a mutable editor workspace that is separate from immutable source
revisions. Autosaves require the current strong editor ETag in `If-Match`; stale writes return
`409 SOURCE_DRAFT_VERSION_CONFLICT`. Invalid/incomplete JSON may be autosaved, but strict parsing and
Tier-1 checks still run before finalization or publication.

| Method & path | Purpose | Codes |
|---|---|---|
| `GET /admin/source-studio/capabilities` | Exact schema, strategy, lifecycle, method/format, limit, and generic-only policy vocabulary used by the editor. | 200 |
| `POST /admin/source-preview` | Run the pinned shared generic engine against a caller-supplied response fixture. It never performs network I/O; request metadata omits values. `sourceJson` ≤ 512 KiB and `responseBody` ≤ 2 MiB. | 200 · 400 · 413 |
| `POST /admin/step-up` | Re-check the authenticated ADMIN password. Returns a short-lived one-time proof scoped to source mutations. | 200 · 401 · 429 |
| `POST /admin/sources` | Create a source (body = full `SourceConfig`; `api` is the identity). Appends to document order (`position = max+1`). | 201 · 409 api exists · 400 strict-parse/Tier-1 |
| `GET /admin/sources` | All sources incl. drafts/retired/removed. Query `?status=`. | 200 |
| `GET /admin/sources/{api}` | Full admin head view. | 200 · 404 |
| `POST /admin/sources/{api}/revisions` | New draft revision (`body.api` must equal `{api}`). | 201 · 404 · 400 |
| `GET /admin/sources/{api}/revisions` | Bounded revision metadata window; optional `size` / `beforeRevision`. | 200 · 400 · 404 |
| `GET /admin/sources/{api}/revisions/{n}` | Full stored config JSON + metadata. | 200 · 404 |
| `POST /admin/sources/{api}/revisions/{n}/validate` | Re-run validation (preview; stores result). | 200 (even when invalid) · 404 |
| `GET /admin/sources/{api}/revisions/{n}/validation` | Latest stored validation result. | 200 · 404 |
| `POST /admin/sources/{api}/revisions/{n}/publish` | Publish (server re-validates in-tx). | 200 (or 200 no-op) · 422 invalid · 409 · 404 |
| `POST /admin/sources/{api}/disable` | `active → disabled`. | 200 · 409 |
| `POST /admin/sources/{api}/enable` | `disabled → active`; `retired → active` (generic only). | 200 · 409 |
| `PUT /admin/sources/{api}/operational-mode` | Idempotently set `{mode:"enabled"|"disabled"|"under_maintenance"}`. Requires `X-Kira-Admin-Step-Up`; publishes exactly one signed catalog revision for a real change. | 200 · 400 · 401 · 404 · 409 |
| `POST /admin/sources/{api}/retire` | `disabled → retired` only. | 200 · 409 |
| `POST /admin/sources/{api}/remove` | `retired → removed` (terminal). Body `{confirm: "<api>"}`. | 200 · 409 · 400 |
| `POST /admin/sources/{api}/rollback` | Body `{toRevision}`. Copies that content into a new highest revision, re-validates, publishes. | 200 · 422 · 409 · 404 |
| `POST /admin/sources/{api}/editor-draft` | Open the one collaborative editor workspace, optionally from `{fromRevision}`. | 200 · 404 |
| `GET /admin/sources/{api}/editor-draft` | Read the workspace and its ETag/version. | 200 · 404 |
| `PUT /admin/sources/{api}/editor-draft` | Autosave `{content}` with `If-Match: "draft-N"`. Does not create a revision. | 200 · 409 · 413 |
| `POST /admin/sources/{api}/editor-draft/validate` | Strictly parse and validate the exact ETag version without creating a revision. | 200 · 400 · 409 |
| `POST /admin/sources/{api}/editor-draft/finalize` | Strictly create an immutable revision and advance the workspace baseline atomically. | 200 · 400 · 409 |
| `POST /admin/sources/{api}/editor-draft/publish` | Create and publish one immutable revision atomically. Requires `X-Kira-Admin-Step-Up`. | 200 · 401 · 409 · 422 |
| `DELETE /admin/sources/{api}/editor-draft` | Discard the exact `If-Match` workspace version. | 204 · 404 · 409 |
| `POST /admin/source-changesets` | Open a server-side multi-source changeset. | 201 |
| `GET /admin/source-changesets[/{id}]` | List or read changesets. A detail response includes `ETag: "changeset-N"`. | 200 · 404 |
| `PUT /admin/source-changesets/{id}` | Autosave the complete operation list using `If-Match`. | 200 · 400 · 409 |
| `POST /admin/source-changesets/{id}/validate` | Read-only preflight of the exact version. Apply repeats validation under locks. | 200 · 400 · 409 · 422 |
| `POST /admin/source-changesets/{id}/apply` | Apply every operation atomically and materialize exactly one snapshot. Requires password step-up. | 200 · 400 · 401 · 409 · 422 |
| `DELETE /admin/source-changesets/{id}` | Discard an open changeset using `If-Match`. | 200 · 409 |
| `GET /admin/audit?page=0&size=50` | Read identifiers-only audit metadata; maximum page size is 100. | 200 · 400 |
| `GET /admin/source-catalog-v2/cutover` | Read-only exact-12/33 preflight. | 200 |
| `POST /admin/source-catalog-v2/cutover` | Atomic audited cutover. Body `{"confirmation":"WITHHOLD_33_LEGACY_SOURCES"}`. Idempotent after success. | 200 · 409 |
| `GET /admin/documents` | Bounded snapshot metadata window; optional `size` / `beforeRevision`. | 200 · 400 |
| `GET /admin/documents/{revision}` | Raw stored canonical bytes of that snapshot (metadata in headers). | 200 · 404 |
| `POST /admin/documents/validate` | Validate the candidate document without publishing. | 200 `{valid, errors[]}` |
| `POST /admin/documents/republish` | Force-materialize a new snapshot from current state (always a new revision). | 200 |
| `POST /admin/sources/import-bundled` | The migration on-ramp — see [4. Import](#4-import-bundled). | 200 · 400 · 413 · 422 |

**Selected response shapes** (Jackson-serialized; lifecycle/revision statuses are lowercase wire
values):

- Create / new revision → `SourceMutationResponse`:
  `{ "api", "status", "revisionNumber", "validation": { "valid", "errors": [{code,path,message}], "warnings": [...] } }`
- `GET /admin/sources` item / `GET /admin/sources/{api}` → `AdminSourceResponse`:
  `{ "api", "displayName", "language", "engine", "status", "siteState"?, "operationalMode"?, "position", "baseUrl", "adult",
     "currentPublishedRevisionNumber"?, "latestRevisionNumber"?, "createdAt", "updatedAt", "publishedAt"? }`
- Revision list item → `{ "revisionNumber", "status", "checksum", "createdBy", "createdAt", "publishedAt"?, "valid"? }`
- Revision detail → `{ "revisionNumber", "status", "config": <raw canonical JSON>, "checksum",
     "canonVersion", "createdBy", "createdAt", "publishedAt"?, "valid"? }` — `config` is the stored
     lifecycle-neutral canonical bytes emitted verbatim.
- Publish / lifecycle transitions → `{ "documentRevision", "checksum" }`. A currently-published
  revision re-published → **200 no-op** (no new snapshot).
- Operational mode → `{ "api", "mode", "sourceRevisionNumber", "documentRevision", "checksum",
  "noOp" }`. `STOPPED`, `ADULT_18_PLUS`, drafts, withheld, retired, removed, and non-generic
  sources are intentionally outside this quick control.
- Rollback → `{ "newRevisionNumber", "documentRevision", "checksum" }`.
- Editor draft → `{ "id", "basedOnRevisionNumber", "content", "version", "createdBy", "updatedBy",
  "createdAt", "updatedAt" }` plus `ETag: "draft-N"`.
- Step-up → `{ "token", "expiresAt", "scope": "source-admin-mutation" }`. The token is secret and
  must remain in server-side/HttpOnly session state; it is never logged or persisted in plaintext.
  Password verification uses the same atomic two-dimension attempt admission as login; successful
  completion must be acknowledged before a proof/grant is created. Throttle/lease denial is 429, and
  shared-store unavailability is 429 `AUTH_THROTTLE_UNAVAILABLE` with `Retry-After: 5`.
- `GET /admin/documents` item → `{ "documentRevision", "schemaVersion", "checksum", "sourceCount", "createdBy", "createdAt" }`.
- `GET /admin/documents/{revision}` → **body = raw stored canonical bytes**; metadata in headers only
  (`ETag: "<checksum>"`, `X-Config-Revision`, `X-Config-Checksum`) — deliberately not a JSON envelope.

### Admin history windows

`GET /admin/sources/{api}/revisions` and `GET /admin/documents` retain their **raw array** bodies and
existing item fields. Both accept `size` (default **20**, range **1..100**) and optional exclusive
`beforeRevision`. The latter is a positive decimal source revision (at most **2147483647**) or
document revision (at most **9223372036854775807**). Only ASCII digits are accepted; leading zeros
are accepted numerically (`size=020`). Empty, repeated (even identical), signed, whitespace-padded,
nondecimal, zero or overflowing recognized values return value-free **400 `INVALID_HISTORY_PAGE`**
before the history service. There is no unlimited mode, total count, or offset/page-number parameter.

Without a cursor, the window contains the newest `size` revisions, returned in **ascending revision
order**. With a cursor, only revisions strictly below it are eligible. Gaps are legal; a cursor
need not identify an existing row. If older rows remain, `X-Kira-History-Next-Before` is the smallest
returned revision in canonical positive decimal. Pass it unchanged as the next `beforeRevision`.
Exactly `size` remaining rows is terminal: no header. Empty histories/windows return `200 []`
without a header; an unknown source still returns 404.

For revisions `[2,5,9,14,20]` and `size=2`, windows are `[14,20]` (cursor `14`), `[5,9]` (cursor `5`),
then `[2]` (no cursor). Refresh without a cursor to see new revisions. Newer inserts do not displace
older seeks, but status/validity may change between requests; this is not a multi-request snapshot.
Missing validity remains omitted, not false. Latest validity retains the existing
`validated_at DESC` semantics, with no promised winner for equal timestamps.

Each data query projects at most `size+1` metadata rows; canonical payloads, notes, signatures and
validation finding arrays are not loaded. Source history uses one head lookup plus one summary
query with bounded latest-validity probes; document history uses one summary query. Stored history,
detail/validation routes, raw bytes/ETags and publication pointers are unchanged. These lists no
longer mean “all history”: older Admin clients still parse the array but need the companion pager
to reach older windows. Do not emulate the old response by automatically fetching every window.

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
| `POST /admin/users` | `{email, password, role}` → create. Password policy and normalized email bound of §2; email case-insensitively unique. Admin authentication/authorization precedes creation. | 201 · 409 duplicate · 400 (including `EMAIL_TOO_LONG`) |
| `GET /admin/users` | Paginated (`?page&size`, size ≤ 100). | 200 |
| `POST /admin/users/{id}/enable` | Re-enable a disabled user. | 200 · 404 |
| `POST /admin/users/{id}/disable` | Disable (bearer rejected at the next authentication check). Refuses to disable the **last enabled ADMIN**. | 200 · 404 · 409 last-admin |
| `POST /admin/users/{id}/reset-password` | `{newPassword}` (policy-checked); atomically changes hash and advances credential version, revoking older tokens. Audit records actor/target only. | 200 · 404 · 400 · 409 `CREDENTIAL_VERSION_EXHAUSTED` |

- Create → **201** `{ "id", "email", "role" }` (from `AdminUserResponse`; `POST` returns the created id).
- List item → `{ "id", "email", "role", "enabled", "createdAt" }` — no password material, ever.
- Reset, even to the same password, advances once; failure or rollback does not partially change
  credentials or create a success audit. Counter exhaustion is a value-free **409** (`Password reset is
  unavailable.`), not wraparound or a hash-only reset. Other users' tokens are unaffected.

---

## 6. Completion  (authenticated: USER or ADMIN)

| Method & path | Purpose | Codes |
|---|---|---|
| `POST /api/v1/completions` | `{prompt, model?}` → run the configured provider (echo only in dev/test) and persist. | 201 · 401 anon · 400 blank prompt or model over 128 chars · 413 prompt too large · 429 rate/quota · 503 admission unavailable |
| `GET /api/v1/completions/{id}` | Fetch one — **owner or ADMIN only** (others → 404, never 403). | 200 · 404 |
| `GET /api/v1/completions` | List the caller's own requests, newest first, paginated. ADMIN may pass `?userId=`. | 200 |

- Request `{ "prompt": "…", "model"?: "…" }`. Blank prompt → **400** `BLANK_PROMPT`; prompt over
  `kira.completion.prompt-max-length` (default 8000) → **413** `PROMPT_TOO_LARGE`. A supplied `model`
  over **128 JVM UTF-16 units** is rejected before persistence with **400** `MODEL_TOO_LONG`, even
  when it consists entirely of whitespace. This is the existing API bound, not PostgreSQL's
  Unicode-character counting rule.
- Omitted, null, empty or whitespace-only `model` uses `kira.completion.default-model` (native
  environment key **`KIRA_COMPLETION_DEFAULTMODEL`**). The configured default must be nonblank and
  at most 128 UTF-16 units; it is validated before the completion executor is created. Only the
  explicit dev/test profiles supply `echo-1`; production has no implicit model fallback. A supplied
  nonblank model remains an optional override. Both the effective default and explicit override
  are persisted and forwarded exactly, without trimming, case folding or other normalization.
- Admission rejects per-user/global minute limits with **429** `COMPLETION_USER_RATE_LIMIT` /
  `COMPLETION_GLOBAL_RATE_LIMIT` (`Retry-After: 60`), and daily quota with **429**
  `COMPLETION_DAILY_QUOTA` (`Retry-After: 86400`). Global concurrency exhaustion is **503**
  `COMPLETION_CONCURRENCY_LIMIT` (`Retry-After: 1`); unavailable or indeterminate Redis
  acquisition is **503** `COMPLETION_COORDINATION_UNAVAILABLE` (`Retry-After: 5`). Retry delays
  are guidance, not recovery guarantees or a promise that arbitrary POST retries are safe.
- Response `CompletionResponse`:

```json
{ "id": "<uuid>", "status": "SUCCEEDED", "model": "echo-1", "provider": "echo",
  "result": "echo: …", "errorCode": null, "error": null, "createdAt": "<instant>" }
```

On success `result` is set and `errorCode`/`error` are null; on failure `errorCode` (a stable §10
catalog value, e.g. `PROVIDER_TIMEOUT`) and `error` (a sanitized, bounded, generic message) are set
and `result` is null. The prompt is never echoed back. The example above uses the dev/test echo
configuration. Error-code catalog: `PROVIDER_TIMEOUT`, `PROVIDER_UNAVAILABLE`, `PROVIDER_REJECTED`,
`INVALID_PROVIDER_RESPONSE`, `RESULT_TOO_LARGE` (reserved, unused in v1), `INTERNAL_COMPLETION_ERROR`
(every unexpected exception maps here). Raw provider exceptions are never returned or stored — secured
server logs only.

Provider work runs on `kira.completion.executor-threads` workers (default 8) with a bounded
`kira.completion.queue-capacity` (default 64). Saturation fails the request outcome as
`PROVIDER_UNAVAILABLE` instead of growing an unbounded in-memory queue.

`kira.completion.queue-timeout` (default2s) includes queue wait and RUNNING persistence through
provider-start authorization. Startup at or after that deadline is rejected. Startup expiry or
saturation returns503 `COMPLETION_OVERLOADED` (`Retry-After: 1`) only if that failure won terminal
publication. The separate provider timed wait (`kira.completion.timeout`, default30s) starts at
authorization; an already observable completed result may win at its wait boundary. Neither timeout
acknowledges termination of an already authorized worker/remote call. Database outcome persistence
is outside these budgets. Terminal outcomes cannot be replaced, and canceled pre-start work cannot
later claim provider entry. GET/list combine request and outcome data from one read-only snapshot
as of the first SELECT; a later independent read may observe a newer terminal outcome.
