# ACTIVE TEST initial EMPTY checkpoint — bounded source slice

**Source-authored: NOT_COMPILED / NOT_RUN / NOT_RUNTIME_ACCEPTED.** This is only
the first EMPTY epoch1 checkpoint after genuine ACTIVE first-cut capture and native
seal verification. It is not general reconciliation or a service-health claim.

## Born-with owner and entry points

The raw profile is
`PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_INITIAL_EMPTY_CHECKPOINT_V1`; full D uses
`PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_INITIAL_EMPTY_CHECKPOINT_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER`.
Its optional `initialCheckpoint` inventory retains the independent recovery/read
principal, credentials, STS/KMS/S3 recipes, routing and seal-retention declaration
**before D/registration**. Absent profiles omit the field. No owner is retrofitted
onto an already registered graph, and the scanner does not borrow sealer or ordinary
publication credentials/lanes. The local reader slot is claimed only at invocation.

`TestActiveInitialCheckpointV1.afterSeal(verified, registration, assembly)` treats
A's closed result as historical identity only. `restartInitial(registration,
assembly)` requires current V14 `SEAL_VERIFIED` plus V26 `WIRE_FROZEN`; it never
reconstructs A's result, repairs/publishes a seal, reuses old pass proof or adopts
an existing SUCCESS. Both entries create a fresh, one-use original.

## Fixed protocol and completion

Each fixed READ, ACQUIRE, RENEW, START_PASS, COMPLETE_PASS or SUCCESS phase uses
the normal coordinator, M/shared (no E/rotation), current full D/J, ACTIVE run,
database/restore, authenticated catalog/sole-writer and exact V17/V26 preimages.
Accounting phases lock global -> scope -> counters in stored ordinal order ->
run/V26 -> every scope scan row. Native work never runs under a SQL phase.

The fresh lease is actual current-token+1 with the existing 30-second DB-time
duration and <=10-second local renewal windows, bounded by the original J scan
deadline. A live preceding A lease refuses without waiting or replacing it. The
authored fixture observes its real expiry outside the new original; it does not
UPDATE that lease or reset an original budget.

After actual catalog readback/release, the scanner checks regional raw+SDK STS
identity, exact-key all-version seal LIST and versioned GET, frozen wire/canonical
bytes, checksum, metadata, COMPLIANCE, retention and existing AEAD/KMS decrypt.
It has no PUT, AssumeRole or GenerateDataKey route. Each subsequent pass issues a
new whole ordinary-prefix all-version LIST (`maxKeys=2`). Any object, newer epoch,
unknown namespace, extra version, delete marker, truncation or continuation refuses;
there is no filtering or pagination toward an empty answer.

Each pass closes its native construction before SCANNING -> COMPLETE. The empty
manifest is the exact `EpochSealFramesV1` LP32 header digest for writer/prefix/TEST/
scope/range1..1/count0, **not SHA256(empty bytes)**. All native bodies, clients,
codec/key/buffer custody and physical JDBC ownership must be released before the
closed historical completion can be returned. UNKNOWN commit, cancellation,
deadline or cleanup failure cannot rehabilitate an original; uncertain native
cleanup keeps its local slot poisoned.

## Durable staging, ordinary capacity and SUCCESS syntax

Additive V27 leaves V14/V17/V26 unchanged. Initial scan rows have immutable
`active_initial_seal_token`/`active_initial_storage_bytes`, a partial scope/pass
unique index, no-entry guard and ACTIVE-run transition guard. Each row costs
**4416 STORAGE_BYTES +1 SCAN_RUNS** ordinary free -> actual; maximum pair **8832
STORAGE_BYTES +2 SCAN_RUNS**, zero entries. Legacy all-NULL rows retain the 3776
logical price. Terminal/unused/recovery reserves are not spent or refunded.

A fresh original may atomically delete/refund only the exact superseded own prefix
{1} or {1,2}, with complete bindings/price, one previous token and a strictly newer
current fence. All scope rows are examined; legacy, foreign, mixed-token or partial
ownership refuses. Prior COMPLETE rows remain locators, not native proof. Both
passes are repeated after cleanup.

Canonical `kcj-1` schema1 `kira-complaint-reconciliation-checkpoint`, profile
`TEST_INITIAL_EMPTY_EPOCH1`, binds D/J, scope/generation/fence, database/restore,
catalog/trust/writers, first seal operation/key/version/canonical+ciphertext hashes,
empty manifest, ordered pass times/counts and SUCCESS. One checked scalar document
supplies all 17 V14 checkpoint columns within the existing 65536-byte limit. The final
current fenced transaction deletes/refunds the pair, writes those fields and
releases only its lease. V17/V26 and current epoch2 are unchanged.

## Authored coverage and remaining gates

The four new test classes contain 23 authored methods (17 TLS/PG/raw-HTTP IT and 6
domain/cold-input methods); none were discovered or run for this delta. They cover
genuine unused/enrolled EMPTY history, native/current-binding refusals, exact
accounting, paid-prefix restart, guards, final commit/release cuts and logical row/
index-envelope observation. A fresh assembly test remains **same JVM**. Deferred
commit failure and afterCommit callbacks are **not a true lost TLS COMMIT reply**.
Temporary-copy sizing is **not PostgreSQL/TOAST/WAL/index-page qualification**.

General nonempty/mixed all-version replay, recurring scans/15-minute freshness and
health, mounted content/Admin issuers, real two-JVM checkpoint restart and lost-reply
qualification remain open. Pre-VERIFY recovery belongs to the separate recovery
owner; a reviewed combined born-with profile is a separate join. Terminal closure/
finality, NEW backend-data restore, LIVE/mobile/Firebase cutover and external IAM,
retained-key/restore-horizon verification are not established by initial SUCCESS.
