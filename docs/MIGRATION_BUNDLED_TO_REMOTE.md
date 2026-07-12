# Migration: bundled JSON → remote

How the Kira Manga app moves from serving its **bundled** `SourceConfigDocument` (a compiled-in string
constant) to fetching the document from this backend — without ever risking a blank or downgraded
config on a client. This expands [`PLAN.md`](PLAN.md) §12 into the operational narrative. The
server-side mechanics live in `BundledImportService` + `DocumentAssemblyService`; the app-side
acceptance chain is the app's `RemoteSourceConfigManager` (read-only reference).

## 1. The bundled document is the floor, forever

The app **keeps its bundled document forever** as the always-present floor, trusted via the app
binary's own signature. The backend is an **upgrade tier**, never a replacement for that floor. On
every refresh the app folds the bundled document in first and merges per-`api`, so a missing, empty, or
rejected remote document changes nothing the app already has — the worst case is "no upgrade this time."

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
   - **Present:** compare the **lifecycle-neutral canonical** content against the currently published
     revision. Identical → `unchanged` (no new revision). Different → create + publish exactly ONE new
     per-source revision — **except** a source currently `retired`/`removed` never gets content imported
     (publish on those statuses is 409 by the state machine; import must not bypass it) → reported under
     `skippedRetired` / `skippedRemoved`, nothing stored. A `disabled` source's content DOES import and
     stays disabled. The payload lifecycle **never overrides an existing source's server lifecycle**
     (that goes through the lifecycle endpoints); a differing payload lifecycle is reported under
     `lifecycleConflicts`, content still imports (subject to the retired/removed exception).
   - A server-side terminally **`removed`** source is never revived by import (`skippedRemoved`).
6. **Materialize exactly ONE snapshot** after the batch (the §9 global-lock sequence, whole-document
   validation). If nothing changed at all → no-op: **200** with all-`unchanged` and **no new document
   revision**.

**Response** (`200`; the `documentRevision` field is absent on the pure no-op case):

```json
{ "created": ["…"], "updated": ["…"], "unchanged": ["…"],
  "skippedRemoved": ["…"], "skippedRetired": ["…"],
  "lifecycleConflicts": [ { "api": "…", "payloadLifecycle": "disabled", "serverLifecycle": "active" } ],
  "warnings": [ { "code": "…", "path": "…", "message": "…" } ],
  "documentRevision": 101 }
```

Re-importing the identical document is idempotent: everything reports `unchanged`, zero new per-source
revisions, zero new snapshots — even when stanzas carry explicit non-neutral `lifecycle` values (the
lifecycle-neutral normalization is what makes this hold).

## 3. The two-floor revision model

The app's anti-rollback rule and the server's allocation are decoupled by two exact floors
(`kira.config.*`):

| Floor | Default | Rule |
|---|---|---|
| `bundled-revision-floor` | **4** | The highest document revision shipped in any released app binary. Every published **server** revision must be **strictly `>`** it. |
| `minimum-server-revision` | **100** | The smallest revision the backend may ever publish (= the `seq_document_revision` seed). The sequence's next value must be **`>=`** it (inclusive — the first generated value IS 100). |

Why these compose safely: the app's own acceptance rule is the **inclusive** `revision >=
bundledDocument.revision` (verified in `RemoteSourceConfigManager.refresh()`:
`.takeIf { it.revision >= acceptedRevision }`, seeded from the bundled revision). Because the server
publishes strictly **above** the bundled floor (≥ 100 ≫ 4), any served document trivially clears the
app's inclusive bar, and the app can never accept a server document that is older than what it bundles.
Revisions are unique and strictly increasing but **may contain gaps** (Postgres sequence values consumed
by rolled-back transactions are not returned) — nothing may assume contiguity. Startup fail-fast
validators enforce all of this (see [`SOURCE_CONFIG_LIFECYCLE.md`](SOURCE_CONFIG_LIFECYCLE.md)).

## 4. App-side acceptance chain (already implemented in the app)

A fetched remote document must pass, in order — any failure **silently drops** the remote document and
keeps the previous good one (cache, else bundled):

1. **Signature** verification (currently `DenyRemoteSignatureVerifier` rejects all — remote is not yet
   enabled in the app; see Future work).
2. **Parse** (lenient).
3. **Validate** (the full validator — the same rules the server enforces at publish).
4. **Revision floor** — `revision >=` the accepted floor (inclusive).

The backend's job is to make steps 2–4 always succeed (validate-before-publish, monotonic revisions
well above the floor) and to be ready for step 1.

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
   binary and set `kira.config.bundled-revision-floor` to it (default 4 — re-check every release that
   re-bundles; never rely forever on "4").
2. **Seed the backend** with that exact bundled document via `POST /admin/sources/import-bundled`
   (see [`LOCAL_DEV.md`](LOCAL_DEV.md) for a curl walkthrough). Confirm the response: expected `created`
   set, empty error path.
3. **Verify served parity.** Fetch `GET /source-config/document` and confirm it parses, re-checksums to
   its `ETag`/`X-Config-Checksum`, and is **semantically equivalent** to the bundled document (same
   source set, same `api` identities, same stanza content). Byte-identity with the hand-authored bundled
   text is explicitly NOT claimed (the bundled constant is pretty-printed; the server serves canonical
   `kcj-1` bytes) — semantic equivalence is the contract.
4. **Point the app's remote at the backend** and enable its `remote` config source. **This is app-repo
   work and is explicitly OUT OF SCOPE for the backend** — implementing the app's `RemoteConfigSource`
   HTTP client, enabling `remote` in the app's DI, and the signing-key ceremony all live in the app repo.
5. Roll out, watch, and — once confident — manage sources entirely server-side (a host move is now a
   config edit + a publish, not an app release).

## 7. Future work (not in v1)

- **Ed25519 detached signature** over the canonical bytes (app step 1). Not implemented; the
  `signature_base64` column is **not created** in v1 — a future migration adds it when the key ceremony
  is decided. Until then, authenticity is HTTPS and `X-Config-Checksum` is a corruption check only.
- **`minAppVersion` filtering** — the field is stored and served today but the app engine does not
  enforce it yet; per-version response variants (which complicate ETag) are deferred.
- **Staged rollout / percentage targeting** — requires stable client identity + bucketing; not built.
- **Public historical retrieval** — the public API serves only the latest document; historical snapshots
  stay admin-only (`GET /admin/documents/{revision}`).
