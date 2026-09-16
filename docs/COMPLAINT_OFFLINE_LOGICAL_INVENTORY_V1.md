# W04f — signed append-only logical inventory evidence

This closed profile extends a **supplied** genesis/rotation sequence with logical-backup source
registration and complete-copy additions. It does not fetch provider inventories or accept a backup,
catalog head, restore, credential or destructive operation. `ACCEPTED` is only the signed writer's
claim; `CheckedOfflineCatalogInventoryChain` is evidence, not an acceptance capability.

## Exact artifact contract

The inspected repaired Backend29 reference is commit
`e2293085c062bf0610f328845b6bdfb4f4ae8eca`, `scripts/db/backup_bundle.py` and
`docs/DISASTER_RECOVERY.md`. It defines `kira.backup-bundle.v1` with exactly `schema`, `dump` and
`media`; each payload descriptor has `name`, positive `bytes` and lowercase SHA-256. The matched
files are `STEM.bundle.json`, `STEM.dump` (PostgreSQL custom dump), and `STEM.media.tar.gz`
(tutorial media), where STEM matches `[A-Za-z0-9][A-Za-z0-9_-]{0,95}`. Diagnostic TOC and checksum
sidecars are not selection authority. Those backup tools remain a separate, unchanged deliverable.

**The backup manifest is not kcj-1.** Its descriptor pins its exact raw byte length (1–4096) and
SHA-256, including any producer newline. This catalog neither reconstructs that external hash from
a generic JSON interpretation nor claims to have read the artifact. The catalog's separate
`bundleSha256` is SHA256 of kcj-1 encoding of `CatalogLogicalBundleV1`, whose exact fields are
`schema`, `manifest`, `dump`, `media`; each named role contains `{name,bytes,sha256}`. It commits to
the three exact-byte descriptions, not to a reformatted backup manifest. The raw chain verifier
recomputes this catalog commitment; the pure reducer only validates its syntax and bindings.

## Closed DTOs and wire version

The new non-genesis envelope is `{schemaVersion,manifest,signatures}` with schemaVersion **2**.
Its manifest has schemaVersion 2, profile `NEW_BACKEND_LOGICAL_BUNDLE_V1`, and canonicalizer `kcj-1`.
It retains the existing manifest's operation/token, generation, exact predecessor-envelope/T0 hashes,
writer, required signer policy, creation/approvals, fixed initial registry, oldest-restore floor and
seven history heads. It replaces `restoreInventory` with the full typed inventory and adds
`inventoryDelta: {addedSourceIds,addedCopyIds}`. All 17 fields are required. There are no defaults,
nulls, floating point fields, unknown keys, opaque records or generic-kind fallback.

`CatalogRestoreInventoryV1` has full `sources` and `copies` arrays, strictly sorted by their own
canonical UUIDv4 IDs. A source contains:

`sourceId`, `kind`, `databaseIdentity`, `restoreIdentity`, `restorePointEpochSecond`, `state`,
`bundleSha256`, `bundle`.

Only kind `KIRA_BACKUP_BUNDLE_V1` and state `ACCEPTED` are supported. Database and restore identities
are canonical UUIDv4 values equal to the immutable genesis registry. The source's claimed restore
point must be between the inherited floor and generation creation time, inclusive. These claims do
not prove capture consistency, successful exported-snapshot import, journal reconciliation or a
real restore point. Physical snapshots, base backups, WAL/PITR, legacy/Firestore dependencies and
expired sources fail closed instead of being silently omitted.

A copy contains `copyId`, `sourceId`, `locationClass`, `state`, `bundleSha256`, `manifest`, `dump`,
`media`. Its three named roles each contain:

`accountId`, `region`, `bucket`, `key`, `versionId`, `bytes`, `sha256`.

Allowed location classes are `PRIMARY`, `REPLICA`, `OPERATOR`, `OFFSITE`; state is the same
`ACCEPTED` claim, not provider evidence. Each complete copy binds all three source role lengths and
hashes and the source's catalog bundle commitment. Its roles share one exact account, region,
bucket and nonempty key prefix, followed by their source role filenames. Account/region/bucket use
the existing bounded grammar. Keys are 1–1024 ASCII characters from `[A-Za-z0-9._/-]`, with no empty,
`.` or `..` path segments; version IDs are 1–1024 non-space printable ASCII characters other than
literal `null`. The full `(account,region,bucket,key,versionId)` coordinate is globally unique across
the inventory. Real replicas may share a version ID in different locations; versionId alone is not
an alias key. No URLs, credentials, live clients, mutable byte arrays or provider-verified flags
appear in the inventory evidence.

## Append-only reducer and authenticated sequence

`CatalogLogicalInventoryReducer.reduce(previous, proposed, operation, delta, context)` is pure
semantic validation, returning a defensively copied inventory with immutable element values.
It does not authenticate inputs or grant acceptance. The permitted deltas are:

- `REGISTER_SOURCE`: exactly one never-before-listed source and one complete initial copy of it;
- `ADD_COPY`: exactly one never-before-listed complete copy of a predecessor source;
- `ROTATION_OVERLAP` / `ROTATION_ACTIVATE`: empty addition lists and exactly unchanged inventory.

Every predecessor source and every extant copy must be retained byte-for-value unchanged. The full
successor must equal that predecessor plus exactly the declared additions: no missing records,
silent extra copy, retarget, reused ID, state change or removal. Every source has at least one
complete copy. No expiration/destruction, floor advancement, writer/registry change or nonempty
history is supported. The inherited floor remains constant and older-source additions fail.

`OfflineCatalogInventoryChainVerifier.verifyInventoryChain(envelopes, initialBundleBytes,
currentBundleBytes, policy)` authenticates raw G1 with the existing historical T0/current Tn path,
then folds the supplied sequence once. Original schema-1 empty rotations may precede the first
schema-2 generation. After any schema-2 generation a schema-1 downgrade fails, even if its inventory
would be empty. The original genesis/rotation wire DTOs, parser limits and rotation-only public API
remain closed and unchanged; the old API never admits inventory operations.

Both readers share bounded input handling and authentication, not a second cryptographic
implementation. Every non-genesis writer and actual approver satisfies predecessor authority AND
independent current policy. All required signatures bind the complete exact canonical manifest
using the unchanged catalog framing/RSA suite and retained current Tn keys. A stable inventory
operation requires SINGLE/current-active signing. Overlap requires ordered `[old,new]`, both real
signatures and ALL_MEMBERS; its immediate successor must activate single new, without an inventory
addition. Seen signers cannot return. A qualifying overlap tail remains `AwaitingActivation`, not
signer-disable permission. The current signed head floor is checked separately after EOF.

## Bounds, evidence and remaining product work

The separate inventory parser caps raw envelopes at 8 MiB and counts **every JSON object inside the
whole manifest**, including registry/history, inventory wrappers, bundles, artifact descriptors,
copy versions and delta, before typed decoding. The configured maximum cannot exceed 4,096. Only
the two inventory arrays receive the larger bound; other arrays remain capped at 16. Depth16,
field32, name64 bytes, string4096 bytes, integer19 characters/Long range, a 262144-token ceiling,
strict UTF-8, duplicate/unknown/trailing/type rejection and exact canonical-byte equality apply.
Schema-1 prefixes still go through the original narrow parser, not a widened generic decoder.

The reducer separately checks its inventory-object count `1 + 5*sources + 4*copies` with Long
arithmetic before and after defensive copies. That subset bound does not replace the stricter
whole-manifest structural bound. The shared reader checks generation count before `next()` and
envelope/remaining aggregate bytes before copying/parsing; G1/T0/Tn keep their original 128 KiB
bounds. Total ceilings remain 65,536 generations and 2 GiB, with lower configured limits allowed.
Only bounded current inventory/authentication state and the current envelope are retained, never
the whole chain. Supplier failure aborts without returning a valid prefix or leaking its cause.

Results carry bound tail/trust metadata, explicit signing state, total encoded bytes and a defensive
current inventory snapshot. Signatures do not prove the external list complete, namespace unique,
objects readable/retained, capture valid or claims true. Missing product work still includes real
capture/reconciliation evidence, provider/all-version/replication/retention verification, all other
source types, expiration/destruction, monotonic floor advancement, history folds, accepted-head
projection and admission wiring. W06 is excluded. Small focused tests are neither configured-maximum
capacity benchmarks nor W04/integration/deployment/production acceptance.
