# TEST terminal durable storage V1 — partial prerequisite

**Dormant, storage-only and not yet independently qualified.** V21 adds a bounded
sidecar and pure byte/price declarations. It does not add a persistence executor,
accepted TEST activation, terminal producer, provider call, configuration/DI or
post-commit/original-holder-released dispatch authority. The word `WIRE_FROZEN` is
a local storage state, not evidence of any of those facts.

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
object_ordinal)`, object key and object ID. RESTRICT FKs require an existing run,
existing scoped control, and, for events, existing `(publication_ref,data_scope_id)`.
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

INSERT accepts only CANONICAL, with all nine frozen fields NULL. A current-row
trigger permits exactly one CANONICAL→WIRE_FROZEN transition without changing any
canonical identity/declaration/content column. WIRE_FROZEN requires every frozen
field, exact wire checksum and exact metadata. No rewrite, back-transition,
delete or replacement of retained canonical bytes is allowed. Exact same-state
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
| Sum indexes | 1288 |
| **ROW = 8 × (heap + indexes)** | **1339968** |

The variable sum is `d(32)+d(43)+d(1024)+d(64)+7*d(32)+d(43)+d(16)`
`+d(65536)+d(16)+d(98304)+d(44)+d(24)+d(10)+d(512)`. The fixed padding is
`8+3*16+8+8+4*8+4*8` for schema, UUIDs, boolean, ordinal, bigints and timestamps.

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
sidecars /5511288384 logical bytes. These ceilings neither establish the real
enrollment limit nor prove that a complete activation reserve fits policy P.

Every retained row keeps its actual storage charge after run purge. Only genuine
unused reserve may be refunded; no GC/deletion permission or slot release is
implied. Future admission must atomically convert the matching run reserve to
actual storage on first canonical insertion under genuine ordered ownership,
avoid charging replay/conflict twice, preserve the whole frozen lifecycle envelope
and keep charge with the winning durable row across restart and takeover.

**Not total terminal or activation reserve.** Canonical publications and their
physical reservation rows, run/control growth, activation and terminal catalogs,
notices and resource IDs, every audit, both scan rows and all staging, denial and
session witnesses, and terminal settlement remain separate paid obligations. No
missing inventory or price is treated as zero. Schema3 activation/projected
registration, authenticated content freeze, durable winner reload, original-holder
release/custody, provider publication and terminal settlement remain unimplemented
and require their own complete bounded producer and independent gates.
