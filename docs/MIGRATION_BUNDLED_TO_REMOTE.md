# Migration: bundled JSON → remote

How the Kira Manga app moves from serving its **bundled** `SourceConfigDocument` (a compiled-in string
constant) to fetching the document from this backend — without ever risking a blank or downgraded
config on a client. This expands [`PLAN.md`](PLAN.md) §12 into the operational narrative. The
server-side mechanics live in `BundledImportService` + `DocumentAssemblyService`; the app-side
acceptance chain is `IncrementalSourceCatalogManager`.

## 1. The approved bundled catalog is the final availability floor

The app keeps a read-only bundled fallback trusted through the application binary. The v2 client
selects one complete tier (latest synchronized catalog, last-known-good cache, then bundle); it never
unions entries from different tiers. The fallback contains only explicitly approved generic sources,
so an outage cannot reactivate an absent legacy adapter.

### Exact initial cutover

Import the reviewed 45-source authoring fixture, then call the cutover preflight. Apply only when it
reports exactly 12 active generic candidates and 33 legacy candidates:

```text
GET  /api/v1/admin/source-catalog-v2/cutover
POST /api/v1/admin/source-catalog-v2/cutover
{"confirmation":"WITHHOLD_33_LEGACY_SOURCES"}
```

The confirmed operation runs under the global publication lock, changes all 33 legacy sources to
`WITHHELD`, and atomically publishes matching v1/v2 artifacts containing only the approved 12. It is
idempotent after success.

## 2. Server-side on-ramp — the `import-bundled` contract

Seed the backend with the current bundled document via
`POST /api/v1/admin/sources/import-bundled` (ADMIN; body ≤ 5 MiB). This is the one operation that turns
the app's hand-authored constant into server-managed per-source revisions + a served snapshot. Exact
semantics (all-or-nothing — any failure rolls the entire import back):

1. **Parse** the whole document with the COMPATIBILITY parser (lenient, unknown-key-tolerant — mirrors
   the app's own parser). Unknown keys encountered are surfaced in `warnings[]`, not silently dropped.
2. **Validate** the whole document with every §8 rule (including the server-additional rules and the
   Tier-1 structural checks). Any error → **422**, nothing persisted.
3. **Ignore the incoming `revision` and `generatedAt`** — the server exclusively controls document-
   revision allocation; the payload's values are recorded for provenance only.
4. **Read each stanza's `lifecycle` separately, then normalize content to lifecycle-neutral** before any
   canonical comparison, checksum, or storage — the incoming lifecycle never enters stored content.
5. **Per source, by `api`:**
   - **Absent (new):** create it, with `position` assigned from **payload order** (so the served document
     preserves the bundled stanza order, which the app's tab ordering follows). Initial server status
     maps from the payload lifecycle: `"active"` → `active`, `"disabled"` → `disabled`, `"removed"` →
     **not created**, reported under `skippedRemoved` (a terminal husk is pointless).
   - **Present:** a draft-only source is never replaced or implicitly approved by import; it is reported
     under `skippedDraft`, with no revision or snapshot caused by that stanza. Otherwise compare the
     **lifecycle-neutral canonical** content against the currently published revision. Identical →
     `unchanged` (no new revision). Different → create + publish exactly ONE new per-source revision —
     **except** a source currently `retired`/`removed` never gets content imported (publish on those
     statuses is 409 by the state machine; import must not bypass it) → reported under `skippedRetired` /
     `skippedRemoved`, nothing stored. A `disabled` source's content DOES import and stays disabled. The
     payload lifecycle **never overrides an existing source's server lifecycle** (that goes through the
     lifecycle endpoints); a differing payload lifecycle is reported under `lifecycleConflicts`, content
     still imports (subject to the draft/retired/removed exceptions).
   - A server-side terminally **`removed`** source is never revived by import (`skippedRemoved`).
6. **Materialize exactly ONE snapshot** after the batch (the §9 global-lock sequence, whole-document
   validation). If nothing changed at all → no-op: **200** with all-`unchanged` and **no new document
   revision**.

**Response** (`200`; the `documentRevision` field is absent on the pure no-op case):

```json
{ "created": ["…"], "updated": ["…"], "unchanged": ["…"],
  "skippedRemoved": ["…"], "skippedRetired": ["…"], "skippedDraft": ["…"],
  "lifecycleConflicts": [ { "api": "…", "payloadLifecycle": "disabled", "serverLifecycle": "active" } ],
  "warnings": [ { "code": "…", "path": "…", "message": "…" } ],
  "documentRevision": 101 }
```

Re-importing the identical document is idempotent: published sources report `unchanged`, draft-only
sources report `skippedDraft`, and there are zero new per-source revisions or snapshots — even when
stanzas carry explicit non-neutral `lifecycle` values (the lifecycle-neutral normalization is what
makes this hold).

## 3. The two-floor revision model

The app's anti-rollback rule and the server's allocation are decoupled by two exact floors
(`kira.config.*`):

| Floor | Default | Rule |
|---|---|---|
| `bundled-revision-floor` | **5** | The exact-12 catalog-v2 bundle revision. Every published **server** revision must be **strictly `>`** it. |
| `minimum-server-revision` | **100** | The smallest revision the backend may ever publish (= the `seq_document_revision` seed). The sequence's next value must be **`>=`** it (inclusive — the first generated value IS 100). |

Why these compose safely: `IncrementalSourceCatalogManager` accepts a remote manifest only when its
catalog revision is strictly greater than the bundled revision and not below the durable accepted
floor. Because the server publishes strictly **above** the bundled floor (≥ 100 ≫ 5), any served
catalog clears the binary floor and cannot roll back what the app bundles.
Revisions are unique and strictly increasing but **may contain gaps** (Postgres sequence values consumed
by rolled-back transactions are not returned) — nothing may assume contiguity. Startup fail-fast
validators enforce all of this (see [`SOURCE_CONFIG_LIFECYCLE.md`](SOURCE_CONFIG_LIFECYCLE.md)).

## 4. App-side v2 acceptance chain

A fetched manifest and all required immutable sources must pass, in order. Any failure keeps the
previous complete catalog (cache, else bundled):

1. Verify manifest checksum/signature, schema, chain, catalog revision, unique order, and lifecycle.
2. Diff every `(api, sourceRevision, checksum)` against verified immutable local rows.
3. Fetch only missing or changed source revisions.
4. Verify each source checksum, detached signature, identity, schema, generic engine, and complete content.
5. Persist every required member, then move the active pointer atomically. Partial candidates never activate.

The backend signs the exact canonical bytes and metadata; the app also re-verifies its cached signed
envelope after restart. Any failure preserves the last verified cache or bundled floor.

## 5. Failure semantics (no silent deletion)

- Failed fetch / parse / validation / checksum → the app keeps its previous good document (cache, then
  bundled). Nothing is lost.
- Unsupported `schemaVersion` → dropped by the validator gate.
- Revisions are stable and monotonic; a lower-revision document is never accepted.
- **No silent deletion.** A source is `disabled` in the document before it is ever `retired`, and
  `retired` (served as `lifecycle:"removed"` for a grace window) before it is ever `removed` (dropped).
  Every client observes each stage; nothing vanishes without warning.

## 6. Cutover checklist

1. **Re-verify the live bundled revision.** Confirm the `revision` actually shipped in the current app
   binary and set `kira.config.bundled-revision-floor` to it (default 5 — re-check every release that
   re-bundles; never rely forever on "4").
2. **Seed the backend** with that exact bundled document via `POST /admin/sources/import-bundled`
   (see [`LOCAL_DEV.md`](LOCAL_DEV.md) for a curl walkthrough). Confirm the response: expected `created`
   set, empty error path.
3. **Verify served parity.** Fetch `GET /source-config/document` and confirm it parses, re-checksums to
   its `ETag`/`X-Config-Checksum`, and is **semantically equivalent** to the bundled document (same
   source set, same `api` identities, same stanza content). Byte-identity with the hand-authored bundled
   text is explicitly NOT claimed (the bundled constant is pretty-printed; the server serves canonical
   `kcj-1` bytes) — semantic equivalence is the contract.
4. Build the app with `KIRA_SOURCE_CONFIG_BASE_URL` set to the credential-free HTTPS backend origin
   and `KIRA_SOURCE_CONFIG_PINNED_KEYS` containing the active/overlap public keys. The implemented
   client is wired in DI and release builds reject missing or malformed trust configuration.
5. Roll out, watch, and — once confident — manage sources entirely server-side (a host move is now a
   config edit + a publish, not an app release).

## 7. Deliberately deferred product features
- **`minAppVersion` filtering** — the field is stored and served today but the app engine does not
  enforce it yet; per-version response variants (which complicate ETag) are deferred.
- **Staged rollout / percentage targeting** — requires stable client identity + bucketing; not built.
- **Public historical retrieval** — the public API serves only the latest document; historical snapshots
  stay admin-only (`GET /admin/documents/{revision}`).
