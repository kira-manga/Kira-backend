# TEST terminal durable storage V1 — partial prerequisite

**Dormant, storage-only and not yet independently qualified.** V21 adds a bounded
sidecar and pure byte/price declarations. It does not add a persistence executor,
accepted TEST activation, terminal producer, provider call, configuration/DI or
post-commit/original-holder-released dispatch authority. The word `WIRE_FROZEN` is
a local storage state, not evidence of any of those facts.

This document describes the storage prerequisite itself. The later
[registered first ordinary seal slice](COMPLAINT_TEST_ORDINARY_SEAL_V1.md) adds a
controlled HTTP-fixture-only writer for EPOCH_SEAL ordinal0, with actual original
SQL/SDK custody and immutable winner reload. Its source is NOT_COMPILED / NOT_RUN;
deployed authenticated intake and all later terminal/settlement writers remain
absent. Storage constructors and `WIRE_FROZEN` alone still confer no authority.

V14, V17 and V19 are unchanged. In particular, V14 publications still contain only
their existing event kinds and terminal events cannot become ordinary `APPLIED`
rows. V19's seven LIVE seal-link fields remain forbidden on TEST publications.
No LIVE codec, key grammar or fixture is widened. EPOCH_SEAL has its own closed
sidecar kind and **no** ordinary publication reference.

## Exact V21 layout and lifecycle

`complaint_test_terminal_intents` has 34 columns:

| Group | Columns and bounds |
|---|---|
| Version/identity | `schema_version` smallint=1; `operation_token`, `data_scope_id`, `writer_generation` v4 UUID; `test_only`=true; `object_kind` varchar(32); `object_ordinal` integer; `object_id` canonical opaque43; `object_key` ASCII <=1024 bytes; `routing_key_id` ASCII reference ID <=64 bytes |
| Epoch/fencing | `epoch_start`, `epoch_end` bigint, positive and ordered; `preparing_fencing_token` positive bigint |
| Captured declarations | `activation_catalog_generation` bigint 1..65536; `activation_catalog_hash`, `configuration_hash`, `journal_configuration_hash`, `terminal_encoding_hash`, each exactly32 bytes |
| Canonical intent | `publication_ref` nullable opaque43; `canonicalizer` varchar(16)=`kcj-1`; `canonical_bytes` 1..65536; `canonical_hash` exact SHA-256; `retention_floor`, `created_at` timestamptz; `state` varchar(16) |
| Frozen fields (nine) | `wire_bytes` 1..98304; `wire_hash` exact SHA-256; `checksum_sha256` canonical padded Base64 of that digest, varchar(44); `content_type` varchar(24)=`application/octet-stream`; `object_lock_mode` varchar(10)=`COMPLIANCE`; `retain_until` timestamptz; `metadata_bytes` 1..512; `metadata_hash` exact SHA-256; `frozen_at` timestamptz |

Every column is NOT NULL except publication_ref and the nine frozen fields. Four
unique B-tree indexes exist: operation-token PK, `(data_scope_id,object_kind,
object_ordinal)`, object key and object ID. A fifth, nonunique B-tree index leads
with `(publication_ref,data_scope_id)` to support that referencing FK. RESTRICT
FKs require an existing run, existing scoped control, and, for events, existing
`(publication_ref,data_scope_id)`.
These are row relations, **not** authenticated correspondence with that run,
publication kind/bytes, configuration, activation, current fence or live authority.

The bounded slots are INSTALLATION_MANIFEST 0..4095, TEST_RUN_PURGE exactly0 and
EPOCH_SEAL 0..15 per run. Both event kinds have equal epoch endpoints and require
publication_ref=object_id. Seals allow positive ordered intervals and require a
NULL publication_ref. A future trusted writer must establish actual manifest
chunk ordering, captured writer lineage, ordinary/terminal seal roles and ranges,
canonical payload identity and all run/publication matches. Slot membership alone
does not establish them.

Keys have exactly the existing TEST grammar:

```
complaints/journal/v1/{writer-v4}/test/{run-v4}/seal-terminal/
{epochEnd}/{routingReference}/{installation-manifest|test-run-purge|epoch-seal}/{opaque43}.kjev
```

The display wraps; stored keys do not. UUIDs use canonical lower-case spelling,
epoch spelling has no zero padding, and the basename has canonical Base64url
padding bits. The basename is a routing token, not necessarily object_id. The
canonicalizer column and digest **do not** validate canonical JSON semantics or
authenticate KJEV. Actual J may require narrower limits than the storage ceilings.

The sidecars are **temporary local terminal bookkeeping**, not additional permanent
recovery evidence. They remain present and paid throughout ordinary PURGING batches;
only the future authenticated atomic final-PURGED transaction may remove/refund them,
before removing their publication/control parents. Accepted catalog/WORM evidence
and the retained run/installation-ID/audit records carry the required recovery
semantics. A PURGED replay must not recreate these sidecars or other mutable rows.

INSERT accepts only CANONICAL, with all nine frozen fields NULL. A current-row
trigger permits exactly one CANONICAL→WIRE_FROZEN transition without changing any
canonical identity/declaration/content column. WIRE_FROZEN requires every frozen
field, exact wire checksum and exact metadata. No rewrite, back-transition or
replacement of retained canonical bytes is allowed. DELETE of CANONICAL is rejected;
WIRE_FROZEN is only **local DELETE eligibility**, not proof of catalog acceptance,
verified publication, completed purge or released accounting. The required future
fixed settlement writer must establish those facts; the trigger does not query or
authenticate another row to manufacture authority. Exact same-state
no-op UPDATE is suppressed by the BEFORE trigger (affected-row count **zero**),
avoiding a new tuple for retries. This does not provide a commit/release proof or
make UPDATE count a winner-selection API. No JDBC executor is supplied here.

SQL helpers use parsed SQL bodies for dependency-safe dump/restore under an empty
search_path; the PL/pgSQL transition guard performs only direct NEW/OLD comparison
and raises a fixed content-free error. It has no late-bound table/helper query.
These guards are not a defense against a database owner disabling constraints or
triggers. Backup restore and rollback behavior require independent qualification.

## Metadata, retention and byte owners

Metadata is exactly the existing four-key journal convention, encoded as compact
UTF-8 with sorted keys and no trailing newline:

```
{"kira-journal-ciphertext-sha256":"{lowercase wire SHA256}","kira-journal-event-id":"{objectId}","kira-journal-retain-until":"{UTC second}","kira-journal-schema":"1"}
```

For seals the event-id metadata value is the seal ID, as in the existing seal
transport. No object version, provider LastModified or readback-success column is
invented; a frozen candidate is not verified provider evidence.

All four times are whole-second instants from Unix epoch through
9999-12-31T23:59:59Z. The declared floor must be at least created_at plus **ten UTC
calendar years**, including February-29 calendar adjustment, not a fixed day or
second duration. Frozen rows additionally require retain_until>=floor and
retain_until>frozen_at>=created_at. These shape checks **cannot establish** the
independent last-pre-run restore horizon plus31days or ten years from the first
provider creation. A future trusted retention producer must supply that coverage,
including publication-attempt timing margin and actual LastModified verification.

For the later manifest publication source slice (qualification remains separate),
the frozen date **M** remains the immutable metadata minimum. After an empty exact LIST, actual
publication custody captures one conditional-PUT lock **L** as the maximum of M
and the current attempt's creation/retention bound. L may be stronger than M;
canonical/wire bytes, hashes, key, metadata, M and first timestamps never change.
An acknowledged PUT must read back the same acknowledged version with actual
COMPLIANCE retention **A>=L**. An existing object or unknown/412/409 outcome cannot
prove L was installed: actual readback must instead cover M, ten UTC calendar
years from real provider LastModified **C**, and the restore horizon plus31days,
alongside all unchanged exact-object, authentication and current-retention checks.

The manifest proof's `requestedRetainUntil` is exactly M: the requested minimum
committed in metadata, **not the last PUT header or a request transcript**.
`retainUntil` is observed A. Exact VERIFIED replay keeps the original proof bytes
and verifiedAt, not a newly proposed lock or observation time. The existing M>now,
M>C, denial-evidence, ordinary-seal and current-authority expiry guards still
apply. This permits a timed retry with valid predecessor/current authority, not
indefinite retry, first publication after M expires, or cold adoption. It changes
no V21 schema or immutable-row rule and supplies no execution/acceptance evidence.

`TestTerminalDurableBindingV1` keeps immutable checked strings/instants and the
existing declaration-only `TestTerminalRunContextV1`; publicationRef is derived
from kind. `TestTerminalDurableRowV1.canonical(binding,bytes)` and
`.frozen(canonicalRow,wireBytes,retainUntil,frozenAt)` snapshot bytes defensively,
derive all digests/checksum/metadata, and expose only defensive bytes/maps. Frozen
owners copy rather than borrow the canonical owner, which is unchanged and not
closed by the derived owner. Closing is idempotent, wipes owned byte arrays and
rejects subsequent byte/map access. Diagnostics redact bindings and content.
These are byte-owner declarations, **not** committed rows or reload capabilities.
Multiple local randomized candidates may exist; a real future writer must select
one in the database and reload that exact winner after uncertainty, never encrypt
again as a substitute for a committed frozen winner.

## Proposed logical sidecar charge and high-water obligations

`TestTerminalDurableStorageProfileV1` exposes **storage-only** input to future
accounting. It does not change the fixed22 capacity encoding, claim a publication
or APPLIED counter slot, allocate actual usage, reserve capacity or open a run.

The conservative proposed envelope uses `d(n)=8*ceil((n+4)/8)` for each bounded
variable datum, a 32-byte heap header, 136 bytes of separately padded fixed fields,
and 32-byte conservative overhead per index tuple:

| Component | Bytes |
|---|---:|
| Heap header + fixed fields | 32+136 |
| Variable fields (all declared varchar ceilings, seven 32-byte hashes, canonical/wire/metadata) | 166040 |
| Heap bound | 166208 |
| operation-token index | 48 |
| scope/kind/ordinal index | 96 |
| object-key index | 1064 |
| object-ID index | 80 |
| publication/scope FK index | 96 |
| Sum indexes | 1384 |
| **ROW = 8 × (heap + indexes)** | **1340736** |

The variable sum is `d(32)+d(43)+d(1024)+d(64)+7*d(32)+d(43)+d(16)`
`+d(65536)+d(16)+d(98304)+d(44)+d(24)+d(10)+d(512)`. The fixed padding is
`8+3*16+8+8+4*8+4*8` for schema, UUIDs, boolean, ordinal, bigints and timestamps.
The publication/scope supporting-index tuple is `32+d(43)+16=96` bytes.

This is **pending independent PostgreSQL/index/TOAST sizing and acceptance**,
not a promise about physical pages, MVCC, WAL, replication, backup or vacuum.
The required qualification must cover populated canonical→frozen growth,
incompressible maximum wire/canonical bytes, every index, TOAST overhead,
rollback-preserved preexisting rows and exact no-op suppression. A finite logical
charge is not an unlimited physical-update/history budget.

The charge intentionally includes a duplicate canonical copy for event sidecars
**in addition to** the existing publication row. Full canonical+future-wire+metadata
coexistence is prepaid once at canonical insertion. Freeze does not charge a second
sidecar, refund canonical bytes or turn two owners into two paid storage rows.

The existing `TestTerminalProfileV1` defines500 entries per chunk,4096 chunks,
15 pre-terminal seals and16 seals including the terminal seal. Thus for declared
N in0..2048000, `maximumIntentCount(N)=ceil(N/500)+1+16` and
`sidecarStorageHighWater(N)=ROW*maximumIntentCount(N)`. Zero installations means
zero manifest chunks, **not** zero purge/seal obligation. The maximum is4113
sidecars /5514447168 logical bytes. These ceilings neither establish the real
enrollment limit nor prove that a complete activation reserve fits policy P.

Every retained row keeps its actual storage charge until its physical removal in
the genuine final-PURGED transaction. That transaction must delete sidecars before
their RESTRICT-linked publication/control parents, decrement each exact actual
sidecar charge once, and separately release only the proved-unused reserve. This
is not permission for ordinary PURGING batches, generic GC, a row-shaped DTO or a
WIRE_FROZEN label to delete anything. No slot is released early or reused to replace
a frozen winner. Future admission must atomically convert the matching run reserve
to actual storage on first canonical insertion under genuine ordered ownership,
avoid charging replay/conflict twice, preserve the whole frozen lifecycle envelope
and keep charge with the winning durable row across restart and takeover. Any
failed final transaction rolls back sidecar/parent deletes and all counter changes;
after PURGED, replay verifies retained evidence without recreating the sidecars.

**Not total terminal or activation reserve.** Canonical publications and their
physical reservation rows, run/control growth, activation and terminal catalogs,
notices and resource IDs, every audit, both scan rows and all staging, denial and
session witnesses, and terminal settlement remain separate paid obligations. No
missing inventory or price is treated as zero. Schema3 activation/projected
registration and the controlled first ordinary seal now have separate source
slices; they are not supplied by these storage types. Complete terminal content,
lineage/denial evidence, later seals, deployed publication intake and terminal
settlement still require their own bounded producers and independent gates.


## Atomic Admin batch ordinary family (source candidate)

The explicit new J profiles `LOWER_TEST_ADMIN_BATCH_ERASURE` and
`REGISTERED_TEST_ADMIN_BATCH_ERASURE` include OWNER_DELETE, OWNER_DELETE_ALL, ADMIN_DELETE and
ADMIN_BATCH_DELETE. Their deployment/effective cases are
`PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_ORDINARY_DRAIN_V1` and
`PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER`.
Existing J/D profile bytes and scalar decoders stay unchanged; retained activations are never
relabelled. These declarations confer no request, run, provider or LIVE authority.

The closed ADMIN_BATCH_DELETE payload binds one Admin/grant/key/fingerprint/scope/epoch plus
1–50 sorted distinct complaint IDs and their1–50 sorted distinct resolved owner IDs (owners≤targets).
Owners are discovered and fully rechecked by AUTH, never supplied by HTTP. Owner, ALL and scalar
Admin decoders reject the batch; registered header dispatch chooses the configured family before
KMS. Plaintext64KiB and wire96KiB bounds are unchanged.

For n targets and m owners, the one operation pays:

```text
AUTH = NORMAL_RECEIPT + PUBLICATION + RECOVERY_RESERVATION + n*AUDIT
P    = m*INSTALLATION_ID + n*RESOURCE_ID + (n+4)*AUDIT + 4*APPLIED
U    = actual rebuilt identities + actual removal/summary audits + new exact-version APPLIED
```

Normal APPLY emits n removal audits and no summary. Recovery adds one event-scoped SYSTEM
summary per newly applied version, never one per target. Four is the TOTAL primary+same-key-copy+
retained-key-alias limit, not a per-key allowance. Exact duplicates spend nothing; aliases cannot
move the original primary/grant/receipt/proof. Credentials remain intact; an absent owner identity
may only become RECOVERY_RESERVED without manufactured credentials.

Registered primary continuation completes before ordinary inventory, without renewed user role,
proof or tags. Drain and manifest CAPTURE/COMPLETE compare exact receipt/primary/alias/P−U/audit
and domain facts, with scope-or-prefix detection of unsupported rows. A later DELETED owner
requires the genuine separately checked ALL companion, not a terminal-state shortcut. Missing
post-drain evidence refuses before manifest providers and cannot be repaired by a successor.
Batch→purge publication composition/qualification remains required internal follow-on work;
this tranche alone does not claim complete terminal activation, deployment or runtime verification.
