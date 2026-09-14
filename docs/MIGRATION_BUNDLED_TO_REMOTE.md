# Migration: bundled JSON → remote

How the Kira Manga app moves from serving its **bundled** `SourceConfigDocument` (a compiled-in string
constant) to fetching signed catalogs from this backend — without activating a partial, unverified
or downgraded config on a client. This expands [`PLAN.md`](PLAN.md) §12 into the operational narrative.
Server-side mechanics live in `GenericV2CutoverService`, `BundledImportService` and
`DocumentAssemblyService`; the app-side acceptance chain is `IncrementalSourceCatalogManager`.

## 1. The approved bundled catalog is the final availability floor

The app keeps a read-only bundled fallback trusted through the application binary. The v2 client
selects one complete tier (latest synchronized catalog, last-known-good cache, then bundle); it never
unions entries from different tiers. The fallback contains only explicitly approved generic sources,
so an outage cannot reactivate an absent legacy adapter.

### Exact initial bootstrap

**Bootstrap before ordinary authoring** on an eligible `PENDING` catalog. Submit one owner-reviewed,
frozen 45-source raw JSON file, not an ordinary import followed by confirmation:

```text
POST /api/v1/admin/source-catalog-v2/cutover/import-bundled
Content-Type: application/json
X-Kira-Bootstrap-Confirmation: WITHHOLD_33_LEGACY_SOURCES
```

ADMIN authorization, exactly one unpadded confirmation header, and a **5,242,880-byte (5 MiB)** actual
body limit apply. Only PENDING performs strict UTF-8 decoding/strict JSON parsing and initial policy
admission. GET on `/api/v1/admin/source-catalog-v2/cutover` is advisory phase/receipt information:
`ready` with no source heads does not approve a payload or reserve admission. The old POST at that
path is a controlled nonmutating 409, never bootstrap.

Policy **`app-bundle-v6-initial-catalog-v1`** pins the ordered 12 generic projection at
`src/main/resources/source-config/bootstrap/app-bundle-v6-generic.json` (30,742 bytes; SHA-256
`42a26ca29182a0c8c1150196ff55979fc41a8d828ed60556e9dcf6062b8b9095`). Its source is public App commit
`2af1178734ae8d6bac35f588332aa30704a1d84d`, unchanged at
`4b4f9539bce70e7179385ce61ab035282bc5ac75`; that `BundledSourcesConfig.kt` has SHA-256
`d4cc1de96901ced254170d594e7e7f33bb6002fa64ac9c15135cbacd9f0f5702`.
This is reviewed **source-data provenance**, not a released-binary attestation or `kcj-1` bytes.
Backend canonicalization/signing remains backend-owned.

Admission requires schema 1 and exactly the reviewed 45 unique APIs. Compare **every default-expanded
field and relative order** of the 12 generic models, including raw active lifecycle and priority 0;
do not project away arbitrary fields. Object-key order does not matter for this initial semantic
comparison. Legacy-33 content and the complete 45-list order are not reference-pinned, but normal
validation and effective-head/lifecycle checks still apply. Incoming document revision/generatedAt
are provenance, not admission equality or revision-allocation gates. The 33 legacy entries are
**source-catalog definitions**, never W06 complaint/Firestore records. The historical
`src/test/resources/fixtures/bundled-full.json` now has revision-6-compatible generic content, including
Azora's chapter opt-in, but retains revision-4 input provenance and all 33 legacy definitions/order.
Neither that test fixture nor a test builder replaces owner review of the actual frozen request.
Operator input is raw JSON, not asserted canonical bytes or an attestation of the current App binary.

One globally locked transaction stages import/reorder without publication, checks effective heads,
withholds the 33 legacy definitions, rechecks exactly 12 ACTIVE generic / 33 WITHHELD, and creates
one v1 snapshot plus one signed v2 catalog. It records the immutable origin receipt/audits and only
then atomically completes the still-PENDING singleton and moves the pointer. Failure rolls back
rows/artifacts/audits/pointer; sequence gaps are allowed. No draft/extra head is silently adopted.

Success returns the [nine-field origin receipt](API.md#initial-catalog-bootstrap), not an import
summary. Its equal origin revisions identify separate v1/v2 checksums; time and actor belong to the
original transaction. Retain the exact request file: after COMPLETE, identical original bytes return
the unchanged receipt **before current UTF-8/parser/reference policy**, even after catalog evolution.
Different bytes, including whitespace-only changes, conflict. Replay does not re-stage, revalidate,
allocate, audit or undo evolution. Later reviewed additions, edits, lifecycle changes and **empty
catalogs** remain legal; the origin 12/33 rule is not permanent inventory or a Store-release gate.

The fixture correction changes raw request bytes and their `payloadSha256`. It cannot replace a
previous COMPLETE origin's original file, even when a semantic correction is wanted. Preserve that
original request/receipt for exact-byte retry; use a separately reviewed later publication for repair.

## 2. Ordinary `import-bundled` contract — after COMPLETE

`POST /api/v1/admin/sources/import-bundled` (ADMIN; body ≤ 5 MiB) is a compatibility re-import path,
**not initial seeding**. It requires durable bootstrap COMPLETE before staging, even for a no-op;
PENDING and RECONCILIATION_REQUIRED refuse with the cutover 409. Ordinary materialization, including
republish, is also COMPLETE-gated. Once admitted, existing semantics remain all-or-nothing:

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
6. **Reorder payload-listed heads first**, retaining omitted heads afterward; ordering changes count
   as mutations. **Materialize exactly ONE snapshot** after the batch when state changes (the §9
   global-lock sequence, whole-document validation). If nothing changed at all → no-op: **200** with
   all-`unchanged` and **no new document revision**.

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

### Correcting an installed stale Azora revision

First inspect the actual installed phase, source head/content, signed catalog/chain, client floors
and trusted keys. Source defaults and this repository's corrected fixture do not prove which content
an installed backend or App accepted.

- **COMPLETE:** start from the CURRENT published Azora config, not the bundled/test fixture. Review
  only the required details-request correction (`{itemUrl}&includeChapters=true` for the matching
  stale descriptor), preserving all legitimate unrelated fields, source identity, lifecycle/order
  and other-source edits. Use the existing [draft → validate → publish workflow](USAGE.md#7-edit-and-publish-a-source)
  to create a new immutable source revision and a higher catalog revision; retain compatible chain
  and floor semantics. A republish of unchanged stale content does not repair it. Verify the actual
  signed manifest and every required member, then actual App activation and the opt-in request/chapter
  behavior separately. Later inventory need not remain 12/33.
- **PENDING:** ordinary repair/publication is not eligible. A genuinely fresh eligible catalog uses
  the reviewed initial-bootstrap procedure above, before ordinary source authoring.
- **RECONCILIATION_REQUIRED:** stop at the publication hold. Existing reads remain, but even an Azora
  correction needs a separately owner-reviewed reconciliation/upgrade plan, real Flyway-history/
  checksum eligibility and old-writer drain. Matching inventory, keys or a test result does not
  establish COMPLETE, and this change adds no reconciliation/adoption API or migration.

Do not blind-reimport the historical fixture, overwrite immutable artifacts, reset receipt/pointer/
sequence/client floors or caches, or force bundled fallback. Restart/outage cannot repair a previously
accepted stale cache. Retain origin evidence and higher-revision history; deployment and installed
activation evidence remain separate operator responsibilities.

## 3. The two-floor revision model

Two `kira.config.*` properties bound backend allocation; their defaults do not attest the revision
shipped in an app binary:

| Floor | Default | Rule |
|---|---|---|
| `bundled-revision-floor` | **6** (backend source default) | Every published **server** revision must be **strictly `>`** this configured value, which must be checked against the actually shipped app bundle. |
| `minimum-server-revision` | **100** | The smallest revision the backend may ever publish (= the `seq_document_revision` seed). The sequence's next value must be **`>=`** it (inclusive — the first generated value IS 100). |

The accepted public App commit `4b4f9539bce70e7179385ce61ab035282bc5ac75` bundles
`SourceConfigDocument` revision **6**. `SourcesGenericModule` passes that JSON through
`RoomSourceCatalogStore` to `IncrementalSourceCatalogManager`, which uses `bundled.revision`.
The backend source default **6** is aligned with that source bundle, not proof of a deployed binary's
revision or an override already installed in its backend.

The client requires `manifest.catalogRevision > bundled.revision` and no rollback below its durable
accepted floor. A server minimum of 100 does not by itself prove signed-bootstrap/chain compatibility
or app activation. Verify the actual deployed bundle, backend configuration and complete acceptance
chain separately. Raising the source default from 5 to 6 leaves minimum 100 and the startup comparisons
unchanged and does not rewrite explicit installed settings. A custom minimum 6 with no explicit floor
now correctly fails `minimum-server-revision > bundled-revision-floor`; review configuration rather
than weakening that bound. No runtime configuration change is authorized by this source alignment.
Revisions are unique and strictly increasing but **may contain gaps** (Postgres sequence values consumed
by rolled-back transactions are not returned) — nothing may assume contiguity. Backend startup
validators check configured bounds and publication state, not deployed App compatibility
(see [`SOURCE_CONFIG_LIFECYCLE.md`](SOURCE_CONFIG_LIFECYCLE.md)).

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

1. **Establish owner rollout eligibility.** Verify the actual installed schema/Flyway history and
   checksums for V13.2 (after 13.1, before reserved V14), and stop/drain old writers that ignore the
   gate. V13.2 classifies only a null-pointer, source-authoring/history-empty catalog as PENDING;
   populated state, including changeset-only state, becomes RECONCILIATION_REQUIRED. Users/auth
   history is unrelated. See [phase rules](SOURCE_CONFIG_LIFECYCLE.md#durable-initial-publication-phase).
   Existing coherent reads remain available, but matching roster/signatures/revisions do not prove
   COMPLETE. Already-beyond-insertion schemas and populated-state reconciliation/adoption need
   separately reviewed owner decisions, not out-of-order/repair/baseline, backfill or pointer/sequence
   resets. No deployment eligibility is established by a local source review.
2. **Re-verify the live bundled revision and configured backend floor.** The accepted public App
   source uses bundled revision 6, aligned with the backend source default 6. Confirm the actual shipped binary
   and deployed configuration at each re-bundle, then separately verify signed-bootstrap/activation
   compatibility. Source observations are not binary attestations or authorization to change runtime
   properties.
3. **Freeze and review the input before mutation.** Use an owner-reviewed 45-source raw JSON file;
   compare the full ordered/default-expanded generic models to the pinned reference and record the
   exact payload hash before any release-helper/GitHub/backend mutation. Submit only those frozen
   bytes to the new raw endpoint, with the exact header (see [`LOCAL_DEV.md`](LOCAL_DEV.md)). Check
   the 200 receipt's policy/reference/payload identities and preserve it with the request file for
   identical-byte retry. Do not use ordinary import or the retired confirmation-only POST.
4. **Verify signed origin delivery, not just a success status/key id.** Fetch the v2 manifest and all
   immutable members; cryptographically verify signatures, checksums, identities, full generic
   content/order and origin catalog revision/checksum against the receipt/reference. The origin
   v1 snapshot at `GET /api/v1/admin/documents/{revision}` has its **own** document checksum; neither
   that artifact nor semantic parity with all 45 stanzas substitutes for signed v2 proof. Canonical
   backend bytes are not claimed identical to hand-authored JSON. If latest has advanced and the
   historical signed origin cannot be fetched, report **delivery proof unavailable**; do not reimport,
   reset or fabricate verification. A failed post-read check is not database rollback.
5. Build the app with `KIRA_SOURCE_CONFIG_BASE_URL` set to the credential-free HTTPS backend origin
   and `KIRA_SOURCE_CONFIG_PINNED_KEYS` containing the active/overlap public keys. The implemented
   client is wired in DI and release builds reject missing or malformed trust configuration.
6. Roll out only after the separate owner/environment gates and app activation proof. Manage later
   source evolution server-side; origin receipt replay never resets it. Zero active sources alone is
   not a Store-release block.

## 7. Deliberately deferred product features
- **`minAppVersion` filtering** — the field is stored and served today but the app engine does not
  enforce it yet; per-version response variants (which complicate ETag) are deferred.
- **Staged rollout / percentage targeting** — requires stable client identity + bucketing; not built.
- **Public historical retrieval** — the public API serves only the latest document; historical snapshots
  stay admin-only (`GET /admin/documents/{revision}`).
