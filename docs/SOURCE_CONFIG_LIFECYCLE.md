# Source-config lifecycle

How a source moves through its states, how those map to what the app sees, which revisions are
publishable, and how a whole-document snapshot is materialized safely. Derived from
`sourceconfig/domain` (the state machine), `SourceAdminService`, `DocumentAssemblyService`, and the
startup validators. Authoritative spec: [`PLAN.md`](PLAN.md) §5, §8 (rule 29), §9, §12.

## Server states (5) and app-vocabulary mapping (3)

Authoring truth is **per-source** (`source_configs` head + immutable `source_config_revisions`); the
served artifact is a **materialized whole-document snapshot** (`published_documents`). The server has
five statuses; the app understands only three lifecycle values.

| Server status | In served document? | Stanza `lifecycle` | Meaning |
|---|---|---|---|
| `draft` | No | — | Authored, never published. |
| `active` | Yes | `"active"` (key omitted) | Normal operation. |
| `disabled` | Yes | `"disabled"` | App force-disables the source row every sync but keeps it on disk (saved-entry reads still work). The mandatory soft-off stage. |
| `retired` | Yes | `"removed"` | App deletes the `sources` row (saved library untouched); the stanza stays in the document for a grace window so every client observes the removal. |
| `removed` | **No** | — | Terminal. Stanza dropped from the document. `GET /sources/{api}` → **410**. Never silently active again. |

The app's `GET /sources/{api}` contract follows this exactly: `active`/`disabled` → 200 with that
lifecycle; `retired` → **200 with `lifecycle:"removed"`** (returning 410 while the document still
carries the stanza would be self-contradictory); `removed` → **410**; unknown/draft-only → **404**.

## Transitions

Any transition not shown is **409 `INVALID_LIFECYCLE_TRANSITION`**.

```
draft    --publish(valid revision)-->  active     (first valid publish only)
active   --disable-->                  disabled
disabled --enable-->                   active
disabled --retire-->                   retired     (direct active->retired is REJECTED:
                                                     soft-disable is mandatory)
retired  --remove(confirm)-->          removed     (terminal; removed -> * is always refused)
retired  --enable-->                   active       (un-retire: engine="generic" ONLY — see below)
draft    --(new draft revisions freely)--> draft
```

**Publish × status (publishing content NEVER implicitly re-enables):** `draft` + first valid publish
→ `active`; publish on `active` → stays `active`; publish on `disabled` → stays `disabled`; publish on
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

- `bundled-revision-floor` (default **4**) — the highest document revision shipped in any released app
  binary. Every published server revision must be **strictly `>`** it.
- `minimum-server-revision` (default **100**) — the smallest revision the backend may ever publish (=
  the sequence seed). The sequence's next value must be **`>=`** it (inclusive: the first value IS 100).

At boot, fail-fast validators assert (never silently repaired):

1. `minimum-server-revision > bundled-revision-floor`.
2. sequence-next `>=` `minimum-server-revision`.
3. when snapshots exist, sequence-next `>` the latest published revision.
4. the `document_publication_state` pointer is coherent: NULL ⇒ zero snapshots (fresh install);
   non-NULL ⇒ references a snapshot AND equals `MAX(document_revision)` (nothing above the pointer);
   sequence-next `>` the pointer.

The `document_publication_state.latest_document_revision` singleton pointer is **the** authoritative
"latest" mechanism — every latest read resolves through it; `MAX(document_revision)` is never a read
path (it survives only inside the startup comparison).

> **Ops:** at production cutover — and at every app release that re-bundles — re-verify
> `bundled-revision-floor` against the revision actually shipped in the live binary. Never rely forever
> on "bundled == 4".

## The 10-step publication sequence (globally serialized)

Every state-visible document mutation — publish, disable, enable, retire, remove, rollback, bundled
import, and `republish` — runs this exact sequence inside **one** transaction. The per-mutation
transaction alone is not enough: without global ordering, two concurrent mutations each assemble a
candidate from a snapshot that predates the other's commit, and the later revision silently loses the
earlier change (a lost update read-committed does not prevent).

1. Lock the singleton `document_publication_state` row `FOR UPDATE` — the **global publication lock**;
   concurrent mutators queue here.
2. Lock the affected `source_configs` row(s) `FOR UPDATE` (always global lock first, then source rows —
   no deadlock is possible because every writer takes the global lock first).
3. Apply the mutation (revision insert / status change / import batch).
4. Read the authoritative current state (all published revisions + statuses) **under the lock**.
5. Assemble the candidate document — all `active|disabled|retired` sources, ordered by
   `(position ASC, api ASC)`, each rendered from its published revision's lifecycle-neutral content with
   the real lifecycle injected.
6. Validate it whole (§8 rule 29): a `removed` source's stanza must be absent, a `retired` source's
   stanza must carry `lifecycle:"removed"`.
7. Take **one** instant from the injected Clock, truncated to ISO-8601 UTC seconds; set it as
   `generatedAt`; serialize canonically (`kcj-1`) and compute the SHA-256 checksum.
8. Insert the `published_documents` row with the next `document_revision` from the sequence and
   `created_at` = **that same instant** (the column has no DB default — application time and DB time
   cannot diverge; the same instant also goes into the publication audit detail).
9. Update `document_publication_state.latest_document_revision` (FK to the just-inserted snapshot).
10. Commit — the lock releases and the new snapshot becomes the served latest atomically.

Any failure rolls the whole mutation back; the served document can never be invalid, torn, or missing a
concurrent change. Concurrent publishes for the same source serialize on the lock order and produce
exactly one deterministic winner; the loser re-reads state under the lock and either publishes against
the new baseline or gets the deterministic 409.

## Empty document

Publishing a document with **zero** sources is legal — it is the truthful terminal state, reached only
by walking every source through `disabled → retired → remove(confirm)`, so no extra destructive
confirmation is invented. Whole-document validation accepts zero sources; under `kcj-1` default-omission
the empty `sources` list is simply absent from the canonical bytes, and the app parses that back to an
empty list. The app can never be blanked by an empty remote document — it always folds its bundled
document in first and unions per-api, so an empty higher-precedence document overrides nothing; the only
effect is the revision ratchet.

## `republish`

`POST /admin/documents/republish` force-materializes a new snapshot from current state, **always**
creating a new `document_revision` even when the canonical content is unchanged. That is its purpose: a
deliberate recovery tool (e.g. after a `canon_version` algorithm change). `canon_version` (`kcj-1` in
v1) is stored on every revision and snapshot so a future canonicalization change is explicit and
`republish` is the documented recovery path.

## Startup-inconsistency recovery runbook

The startup validators **never auto-repair**. On a detected inconsistency the app refuses to start
(readiness stays red) and the error names this runbook. To recover:

1. Inspect `published_documents` versus `document_publication_state.latest_document_revision` and the
   `seq_document_revision` state (`SELECT last_value FROM pg_sequences WHERE sequencename =
   'seq_document_revision'`).
2. Decide which snapshot is truly the latest.
3. Repair with a single audited SQL statement — e.g.
   `UPDATE document_publication_state SET latest_document_revision = <verified>, updated_at = now() WHERE id = 1;`
   and/or `ALTER SEQUENCE seq_document_revision RESTART WITH <n>;` when the sequence lags.
4. Record the manual action in `audit_log`.
5. Restart.
