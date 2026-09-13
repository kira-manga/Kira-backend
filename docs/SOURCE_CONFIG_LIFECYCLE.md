# Source-config lifecycle

How a source moves through its states, how those map to what the app sees, which revisions are
publishable, and how a whole-document snapshot is materialized safely. Derived from
`sourceconfig/domain` (the state machine), `SourceAdminService`, `DocumentAssemblyService`, and the
startup validators. Authoritative spec: [`PLAN.md`](PLAN.md) §5, §8 (rule 29), §9, §12.

## Durable initial-publication phase

The `document_publication_state` singleton holds both the latest pointer and bootstrap admission.
This phase is separate from each source's lifecycle; a matching roster, signature or revision never
establishes completion:

| Phase | Meaning |
|---|---|
| `PENDING` | Eligible for the raw initial-bootstrap transaction; no ordinary publication/import. |
| `COMPLETE` | Successful bootstrap has an immutable origin receipt; ordinary evolution is permitted. |
| `RECONCILIATION_REQUIRED` | Retain coherent existing reads, but refuse bootstrap and ordinary publication/import pending separately authorized reconciliation. |

V13.2 requires exactly one existing singleton with id 1; missing/multiple state fails, never seeds a
replacement. At migration, `PENDING` requires a null pointer and empty source heads, revisions,
validation results, editor drafts, changesets (including independent changeset-only state), v1
documents, and v2 catalogs/membership/removal history. Users/auth history does not disqualify it.
Any populated/history-bearing source state becomes `RECONCILIATION_REQUIRED`, without rewriting
source content/status, artifacts, pointer or sequence, and without automatic COMPLETE adoption.

**Bootstrap before ordinary authoring.** `PENDING` draft authoring may remain available, but
conflicting draft/extra source heads are not silently adopted; there is no repair API. The migration
classification is not a perpetual all-table-emptiness rule: new independent, unapplied changesets
with no source heads may remain in `PENDING`; bootstrap neither applies, adopts nor rewrites them.
GET cutover readiness checks phase/source heads, not payload approval or all authoring state.

The only initial publication path is
`POST /api/v1/admin/source-catalog-v2/cutover/import-bundled`:

1. Bound/authenticate/confirm/hash the **actual request bytes** before acquiring the global lock G.
   Under G, coherent `COMPLETE` returns the original receipt for identical bytes before UTF-8,
   parsing or current reference policy; different bytes conflict. Reconciliation state refuses.
2. Only `PENDING` strictly decodes UTF-8, strictly parses schema 1 and admits the exact reviewed 45
   unique APIs and full ordered/default-expanded 12 generic models, including active lifecycle and
   priority 0. Legacy-33 content and the full 45-list order are not reference-pinned; normal
   validation and effective-head checks still apply.
3. Stage the existing import/reorder behavior **without publication**, check all effective heads,
   withhold exactly the 33 reviewed legacy **source definitions**, then recheck 12 ACTIVE generic /
   33 WITHHELD. These are not complaint/Firestore records.
4. Use the same private assembly body and **one instant** for one v1 snapshot, one signed v2 catalog,
   origin receipt and audits. Only after both artifacts exist does an exactly-one still-PENDING
   completion update store the receipt, move the pointer and set COMPLETE. No early COMPLETE.

All steps after locking are in one transaction. Failure rolls back source rows, artifacts, audits,
receipt and pointer changes; PostgreSQL sequence gaps remain legal. Receipt revisions are equal,
but v1/v2 checksums identify separate artifacts. Replays retain the original time and actor, do not
stage/revalidate/allocate/audit again, and never undo later additions, edits, lifecycle changes or
empty catalogs. Current reference loading is PENDING-only: a missing/changed reference refuses new
admission, not a coherent retained origin replay/read. See [API receipt/bytes](API.md#initial-catalog-bootstrap)
and [reference/rollout](MIGRATION_BUNDLED_TO_REMOTE.md#exact-initial-bootstrap).

## Server states (6), v1 mapping, and v2 mapping

Authoring truth is **per-source** (`source_configs` head + immutable `source_config_revisions`); the
served artifact is a **materialized whole-document snapshot** (`published_documents`). The server has
six statuses. v1 retains its three-value compatibility vocabulary; v2 represents retirement
explicitly and reserves `removed` for identity-only tombstones.

| Server status | In served document? | Stanza `lifecycle` | Meaning |
|---|---|---|---|
| `draft` | No | — | Authored, never published. |
| `withheld` | **No** | — | Admin-only quarantine. It can accept reviewed generic revisions and requires explicit enable before becoming public. |
| `active` | Yes | `"active"` (key omitted) | Normal operation. |
| `disabled` | Yes | `"disabled"` | App force-disables the source row every sync but keeps it on disk (saved-entry reads still work). The mandatory soft-off stage. |
| `retired` | Yes | `"removed"` | App deletes the `sources` row (saved library untouched); the stanza stays in the document for a grace window so every client observes the removal. |
| `removed` | **No** | — | Terminal. Stanza dropped from the document. `GET /sources/{api}` → **410**. Never silently active again. |

The app's `GET /sources/{api}` contract follows this exactly: `active`/`disabled` → 200 with that
lifecycle; `retired` → **200 with `lifecycle:"removed"`** (returning 410 while the document still
carries the stanza would be self-contradictory); `removed` → **410**; unknown/draft-only → **404**.

### Admin Studio operational mode

`PUT /api/v1/admin/sources/{api}/operational-mode` is the password-step-up-protected quick control
for `enabled`, `disabled`, and `under_maintenance`. It is idempotent. `enabled` and `disabled`
normalize `siteState` to `WORKING`; maintenance keeps the server lifecycle active and publishes
`siteState:"UNDER_MAINTENANCE"`. A site-state change is recorded as a new immutable source
revision; a lifecycle-only change reuses the current revision. Every real change materializes one
document and v2 catalog revision under the global publication lock. `STOPPED`, `ADULT_18_PLUS`,
draft, withheld, retired, removed, and non-generic sources remain outside this quick control.

## Transitions

Any transition not shown is **409 `INVALID_LIFECYCLE_TRANSITION`**.

```
draft    --publish(valid revision)-->  active     (first valid publish only)
withheld --publish(valid generic revision)--> withheld
withheld --enable(generic only)-->     active
active   --disable-->                  disabled
disabled --enable-->                   active
disabled --retire-->                   retired     (direct active->retired is REJECTED:
                                                     soft-disable is mandatory)
retired  --remove(confirm)-->          removed     (terminal; removed -> * is always refused)
retired  --enable-->                   active       (un-retire: engine="generic" ONLY — see below)
draft    --(new draft revisions freely)--> draft
```

**Publish × status (publishing content NEVER implicitly re-enables):** `draft` + first valid publish
→ `active`; publish on `withheld` → stays `withheld`; publish on `active` → stays `active`; publish on `disabled` → stays `disabled`; publish on
`retired`/`removed` → **409**.

Repeating a transition that does not apply to the current state is a **409**, not a no-op — a strict
state machine tells the operator their mental model of the current state is wrong (e.g. `disable` on an
already-`disabled` source → 409).

### Un-retire is engine-conditional

`retired → active` is allowed **only when the source's published revision has `engine == "generic"`**;
for legacy/kotlin engines it is **409 `UNRETIRE_UNSUPPORTED_FOR_ENGINE`**, and their only path out of
`retired` is `removed`. Why (proved against the app's `SourceCatalogSyncRepositoryImpl`): the app
deletes the row on `lifecycle:"removed"`; when the stanza later returns to `"active"`, a **generic**
source is re-seeded (fresh, disabled-by-default row — the user re-enables), but a **legacy** stanza is
never re-seeded from config, so an un-retired legacy source would stay permanently invisible on every
client that synced during the retirement window. That asymmetry is unfixable client-side, so the server
refuses it.

**Grace window** before the terminal `remove` is admin-judged (no automatic expiry); the recommendation
is ≥ 2 app-release cycles so every client syncs the removal.

## Lifecycle-neutral storage, lifecycle injected at materialization

Stored revision content in `source_config_revisions.config_canonical_json` is **always
lifecycle-neutral**: the `lifecycle` field is the neutral model default `"active"`, which under `kcj-1`
default-omission means the key is **absent** from the stored bytes. Server lifecycle lives ONLY in
`source_configs.status`; `DocumentAssemblyService` substitutes the real served value (`active` → key
omitted, `disabled`, retired-as-`removed`) at materialization time.

Why this is load-bearing: without normalization, the same semantic content imported once as
`lifecycle:"disabled"` and once as neutral would produce different canonical bytes → false "updated"
diffs, non-idempotent re-imports, and server state leaking into immutable content history. Bundled
import reads the incoming lifecycle separately and uses it only to (a) set the initial server status of
newly created sources or (b) report `lifecycleConflicts` on existing ones — never to drive stored
content, checksums, or comparison.

## Which revisions are publishable

`source_config_revisions.status` ∈ {`draft`, `published`, `superseded`}; exactly one `published`
revision per source (partial unique index `uq_one_published_per_source`).

- The **currently published** revision → **200 idempotent no-op** (no state change, no new snapshot).
- A **`superseded`** revision → **409 `REVISION_SUPERSEDED`**. Old content is restored ONLY via
  `rollback` (below) — re-publishing a superseded row directly would rewind per-source history.
- A **`draft`** revision → publishable only when its `revision_number` is **greater than** the currently
  published revision number; an older draft → **409 `REVISION_OLDER_THAN_PUBLISHED`** (its content is
  still restorable via rollback, which re-validates it as a fresh revision).
- Any revision of a `retired`/`removed` source → **409** (the publish × status matrix above).

**Publish re-validates live** inside the transaction — a stale stored "valid" is never trusted; an
invalid revision → **422** with `errors[]`. **In-transaction ordering** protects the unique index: the
previous `published` revision is flipped to `superseded` **first**, then the candidate to `published`
(Postgres checks the partial unique index per statement, so the reverse order would momentarily have
two `published` rows and fail).

**Rollback = forward-roll.** `POST /admin/sources/{api}/rollback {toRevision: n}` copies revision *n*'s
**content** into a **new** revision (number = latest + 1), re-validates it (rules may have tightened →
422), and publishes it. History is never mutated and revision numbers only grow. Rollback restores
**content only** — it does NOT restore the source's server lifecycle from that era (an `active` source
stays `active`, a `disabled` one stays `disabled`).

## Revision numbering

- **Per-source `revision_number`** starts at 1, strictly increases, is never reused or mutated.
  Allocation is `max(revision_number)+1` under a `SELECT … FOR UPDATE` lock on the source head row, so
  concurrent creations for the same source serialize instead of colliding.
- **Whole-document `document_revision`** comes from the Postgres sequence `seq_document_revision`
  (**seeded START WITH 100**), is monotonic and never reused. **Gaps are normal and documented** —
  values consumed by rolled-back transactions are not returned, so revisions are unique and strictly
  increasing but NOT contiguous. Nothing may assume contiguity.

### Two-floor model + startup checks

Two properties (`kira.config.*`) bound the sequence with **exact** comparisons:

- `bundled-revision-floor` (retained backend default **5**) — the configured publication bound, to be
  checked against the actually shipped app bundle. Every published server revision must be
  **strictly `>`** this configured value; the default is not a verified app-binary revision.
- `minimum-server-revision` (default **100**) — the smallest revision the backend may ever publish (=
  the sequence seed). The sequence's next value must be **`>=`** it (inclusive: the first value IS 100).

The accepted App source bundle is revision **6**, used as `bundled.revision` by its catalog-v2 client.
This is distinct from the retained backend default, not a separate client floor of 5. Deployed-binary
floors and signed-bootstrap/activation compatibility require [separate verification](MIGRATION_BUNDLED_TO_REMOTE.md#3-the-two-floor-revision-model).

At boot, fail-fast validators assert (never silently repaired):

1. `minimum-server-revision > bundled-revision-floor`.
2. sequence-next `>=` `minimum-server-revision`.
3. when snapshots exist, sequence-next `>` the latest published revision.
4. the `document_publication_state` pointer is coherent: NULL ⇒ zero snapshots (not itself proof of
   a pristine or bootstrap-approved catalog);
   non-NULL ⇒ references a snapshot AND equals `MAX(document_revision)` (nothing above the pointer);
   sequence-next `>` the pointer.
5. exactly one coherent phase/receipt singleton exists. COMPLETE requires matching immutable origin
   artifacts/checksums/time/actor and a latest pointer at or above the origin revision; other phases
   have no receipt. Missing/incoherent state fails closed, not as an empty catalog. This check does
   not load today's initial reference policy.

The `document_publication_state.latest_document_revision` singleton pointer is **the** authoritative
"latest" mechanism — every latest read resolves through it; `MAX(document_revision)` is never a read
path (it survives only inside the startup comparison).

> **Ops:** at production cutover — and at every app release that re-bundles — re-verify
> `bundled-revision-floor` against the revision actually shipped in the live binary. Never rely forever
> on "bundled == 4".

## The 10-step publication sequence (globally serialized)

Every ordinary state-visible document mutation — publish, operational-mode change, disable, enable,
retire, remove, rollback, bundled import, and `republish` — requires bootstrap **COMPLETE** for
materialization and runs this sequence inside **one** transaction. Ordinary import checks COMPLETE
before staging, even for a no-op. Initial bootstrap uses the separate admission/finalization above,
not a bypass of the ordinary guard. The per-mutation transaction alone is not enough: without global
ordering, two concurrent mutations each assemble a candidate from a snapshot that predates the
other's commit, and the later revision silently loses the earlier change (a lost update read-committed
does not prevent).

1. Lock the singleton `document_publication_state` row `FOR UPDATE` — the **global publication lock**;
   concurrent mutators queue here. Ordinary materialization reasserts this exact-one lock and COMPLETE.
2. Lock the affected `source_configs` row(s) `FOR UPDATE` (always global lock first, then source rows —
   no deadlock is possible because every writer takes the global lock first).
3. Apply the mutation (revision insert / status change / import batch).
4. Read the authoritative current state (all published revisions + statuses) **under the lock**.
5. Assemble the candidate document — all **generic** `active|disabled|retired` sources, ordered by
   `(position ASC, api ASC)`, each rendered from its published revision's lifecycle-neutral content with
   the real lifecycle injected.
6. Validate it whole (§8 rule 29): a `removed` source's stanza must be absent, a `retired` source's
   stanza must carry `lifecycle:"removed"`.
7. Take **one** instant from the injected Clock, truncated to ISO-8601 UTC seconds; set it as
   `generatedAt`; serialize canonically (`kcj-1`) and compute the SHA-256 checksum.
8. Insert the `published_documents` row and matching signed v2 manifest/entries with the next shared
   catalog revision from the sequence and
   `created_at` = **that same instant** (the column has no DB default — application time and DB time
   cannot diverge; the same instant also goes into the publication audit detail).
9. Update `document_publication_state.latest_document_revision` only after both artifacts exist.
10. Commit — the lock releases and the new snapshot becomes the served latest atomically.

Any failure rolls the whole mutation back; the served document can never be invalid, torn, or missing a
concurrent change. Concurrent publishes for the same source serialize on the lock order and produce
exactly one deterministic winner; the loser re-reads state under the lock and either publishes against
the new baseline or gets the deterministic 409.

Every inventory creator also takes G **before existence checks, position allocation or insertion**,
including draft creation. Global-before-source-row ordering is preserved; an absent row is not a
substitute for the singleton lock when serializing creators with bootstrap.

## Empty document

After **COMPLETE**, publishing a document with **zero** active sources is legal — the initial 12/33
roster is an origin admission rule, not a permanent inventory requirement. Zero active sources alone
is not a Store-release block, and empty publication is not a way around PENDING bootstrap.
Whole-document validation accepts zero sources;
under `kcj-1` default-omission the empty `sources` list is absent from the canonical bytes. The v2 app
does not union the bundle into a verified remote catalog: it atomically activates the empty active
projection so an older source cannot survive a kill switch. If that candidate fails authentication,
validation, download, or persistence, the complete previous tier remains active.

## `republish`

After bootstrap **COMPLETE**, `POST /admin/documents/republish` force-materializes a new snapshot from
current state, **always** creating a new `document_revision` even when the canonical content is
unchanged. That is its purpose: a deliberate recovery tool (e.g. after a `canon_version` algorithm
change). `canon_version` (`kcj-1` in v1) is stored on every revision and snapshot so a future
canonicalization change is explicit and `republish` is the documented recovery path.

## Startup-inconsistency recovery runbook

The startup validators **never auto-repair**. Incoherent singleton/pointer/receipt/artifact state
refuses startup. Coherent `RECONCILIATION_REQUIRED` is instead a publication hold with existing reads
retained; restarting or matching the initial roster does not qualify it as COMPLETE.

Inspect the actual installed schema/Flyway history and checksums, source authoring/history, both
artifact families, origin receipt, pointer and sequence without rewriting them. V13.2 follows 13.1
and precedes reserved V14; an installation already beyond that insertion point needs a separately
reviewed upgrade path, not out-of-order/repair/baseline flags or edited old SQL. Drain/stop old writers
that ignore the gate before rollout. Reconciliation, adoption or corruption recovery requires an
owner-approved plan and separate verification; no automatic reset/backfill or new repair API is
provided. **Do not reset pointers/sequences or fabricate a receipt to force bootstrap eligibility.**
See the [cutover checklist](MIGRATION_BUNDLED_TO_REMOTE.md#6-cutover-checklist) for the external gates.
