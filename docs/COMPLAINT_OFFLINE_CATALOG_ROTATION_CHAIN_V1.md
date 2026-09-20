# W04e — bounded offline genesis/rotation-chain evidence v1

This is a single-pass local reader for a **supplied** G1 -> overlap -> activation sequence,
including multiple rotations. It is not a general catalog reader, externally complete inventory,
accepted head, namespace/no-reset proof, or permission to issue credentials or disable a signer.
There are no database, network, KMS, S3, Spring, source-config, or W06 operations.

## Raw input and independent current policy

`OfflineCatalogRotationChainVerifier.verifyRotationChain(envelopes: Sequence<ByteArray>,
initialBundleBytes, currentBundleBytes, policy: OfflineCatalogChainReaderPolicy)` returns
`CheckedOfflineCatalogRotationChain`. It consumes exactly one iterator and authenticates the first
raw envelope using [W04d historical T0/current Tn checking](COMPLAINT_OFFLINE_CATALOG_GENESIS_V1.md).
It accepts no caller-constructed checked genesis or bundle claims.

The immutable reader policy composes the existing independent `OfflineTrustBundlePolicy` with:

- `currentWriterGenerationIds`: 1-16 distinct, ASCII-sorted canonical lowercase UUIDv4 IDs;
- `currentApproverIds`: 2-16 distinct, ASCII-sorted IDs using the existing bounded non-space ASCII
  approver grammar;
- explicit immutable `OfflineCatalogChainLimits`.

Lists are checked and defensively copied on construction and access. There are no production
defaults or implicit bootstrap-identity grants. Policy provenance is an external obligation, not
proved by these syntax checks; the reader never consults a restored database for authority.

G1 still uses its independently rooted initial authority. **Every non-genesis generation** must
use the predecessor's unchanged registered catalog writer and actual approvers AND be allowed by
the independent current writer/approver lists. Either side alone is insufficient. Current policy
does not nominate a replacement into predecessor authority; immutable `bootstrapAuthority` does
not silently grant current permission. A G1-only evidence result grants no non-genesis privilege.

## Closed non-genesis wire profile

All fields are required and canonical, with no defaults, nulls, floats, unknown fields, extension
maps, generic history records or fallback operation. Schema version remains 1. The envelope is
`{schemaVersion,manifest,signatures}`; signatures reuse the exact existing catalog tuple format.

The rotation manifest has these 15 fields:

`schemaVersion`, `canonicalizerId`, `operation`, `operationToken`, `generation`,
`previousEnvelopeSha256`, `initialTrustBundleEnvelopeSha256`, `catalogWriterGenerationId`,
`requiredSignerPolicy`, `creation`, `approvals`, `oldestRestoreTimeEpochSecond`,
`initialWriterRegistry`, `restoreInventory`, `history`.

The supported operations are exactly `ROTATION_OVERLAP` and `ROTATION_ACTIVATE`. The operation
token has the existing canonical UUIDv4 syntax; this is not proof of nonce freshness. Each next
generation is exactly its predecessor plus one and binds SHA256 of the complete preceding exact
envelope, including its signatures. Every manifest also retains the exact T0-envelope hash.

The complete **initial** registry is repeated unchanged from checked G1. Its writer/event IDs,
approvers, roles, policy references, prefixes, initial live state and INITIAL SINGLE signer policy
cannot be replaced. The separate `CatalogSignerPolicyV1 {mode,threshold,members}` and rotation fold
describe subsequent signing state; the embedded bootstrap snapshot is not misrepresented as a
mutable current registry. This profile implements no registry replacement or new namespace.

Restore inventory and all seven named empty history heads must equal G1 exactly, and the empty
oldest-restore floor remains unchanged. Nonempty inventory/history or a changed commitment is
rejected, even if its envelope has genuine signatures. This is not a full history-fold implementation.

Creation must be no earlier than **either** predecessor approval. Each generation has exactly two
distinct ASCII-sorted approval IDs and the creator must belong to that actual tuple. Every approval
is no earlier than its own creation and no later than epoch second 253402300799. These are signed
claims, not proof of two-person ceremony or a trusted clock.

## Rotation and retained keys

The active signer starts with G1's root-bound required signer. Only these transitions exist:

1. Stable old -> `ROTATION_OVERLAP`: policy mode `ROTATION_OVERLAP`, ordered members `[old,new]`,
   threshold `ALL_MEMBERS`, and exactly those two signatures in that order. New must not have
   appeared previously in the chain.
2. Awaiting activation -> immediate `ROTATION_ACTIVATE`: mode `SINGLE`, members `[new]`, threshold
   `ALL_MEMBERS`, and exactly the new signature. No unrelated/interleaved operation is permitted.

Every required key/algorithm must exist in authenticated **current Tn**, and every signature is
verified using its retained canonical public material. The unchanged catalog domain/framing and
RSA-3072/PSS suite are reused. A retained old public key is not permission to reactivate it. Seen
signer IDs are bounded to 16 and retained in the fold even after activation. Missing historical
intermediate keys, retargeted material, direct jumps, reversed/partial/extra signature sets, repeated
overlap/activation or a return to an earlier signer fail closed.

A sequence ending at a valid overlap returns `CatalogRotationState.AwaitingActivation(previous,next)`.
It is not stable completion, an admitted mutation or permission to disable the previous signer.
After EOF the reader independently requires tail generation >= Tn's signed minimum catalog head.
That floor is not applied to historical G1 or each intermediate generation.

## Single-pass budgets and failure behavior

Configured limits must be positive and no higher than 8 MiB/envelope, 4,096 manifest records,
65,536 generations and 2 GiB of raw encoded **generation envelopes**. Lower limits are supported
for real operational profiles and small tests; T0/Tn remain separately bounded by their existing
128 KiB limits. G1 also retains its original 128 KiB bound.

The next generation count is checked before requesting `next()`. Its raw byte length and remaining
aggregate byte budget are checked before copying/parsing. Aggregate accounting uses a subtraction
guard, not an overflow-prone unchecked addition. Supplier-owned allocation/iterator work remains
the supplier's responsibility; the verifier does not aggregate the sequence into a collection.

For this narrow profile, **every JSON object inside `manifest`, including the manifest itself,
structural wrappers, signer-policy members and empty heads, counts as one manifest record**.
The streaming structural pass enforces that conservative per-document count before typed decoding.
It excludes envelope/signature objects outside the manifest and resets for each generation.
Existing strict UTF-8, duplicate, trailing-input, depth16, tokens4096, array16, field32, name/string,
integer and exact-canonical-byte checks remain. Original bundle/registry/genesis parser entry points
keep their old byte limits and behavior. This is not a general 8 MiB/4,096-record catalog parser.

Only one bounded current envelope and its bounded parse/canonical representation are processed at
a time. Retained fold state consists of the fixed small initial registry/empty heads, at most 16
current public keys and seen IDs, previous hash/chronology and current rotation state. Previous
payloads and the whole encoded history are not retained or returned.

Exceptions from iterator creation, `hasNext()` or `next()` abort with `SUPPLIER_FAILURE`, without
provider text or causes; interruption restores the thread's interrupt flag. Fatal JVM errors are
not masked. No failure returns the already checked prefix as a partial success. Bounds use
`LIMIT_EXCEEDED`; current permission/missing-key/head-floor failures use `POLICY_MISMATCH`;
signature, parser/schema and exact T0-binding failures retain their existing specific codes;
invalid chain/rotation/unchanged-state rules use `INVALID_DOCUMENT`.

## Evidence result and remaining implementation

The result contains immutable tail generation, manifest/envelope hashes and writer ID; exact
genesis/T0/Tn hashes, current bundle version and signed head floor; explicit rotation state; and
the total encoded envelope byte count. It contains no raw payload history, iterator or capability.
An externally truncated or incomplete listing cannot be ruled out merely by verifying a supplied
sequence whose tail meets a release floor. Neither this result nor the current policy has an
independent genesis/namespace pin establishing uniqueness or absence of reset.

Still PRODUCT WORK: full restore-source/copy inventory and destruction deltas, monotonic restore
floor folding, general writer/range transitions, typed history batches and cumulative-head folds,
provider-proof validation, dual-copy all-version/retention verification, accepted-head projection
and admission wiring. Actual provider artifacts, independent policy maintenance and offline
ceremony records are external inputs; they do not replace that missing code. Root rotation and W06
remain excluded. Small focused tests are not a 2 GiB/65,536-signature capacity benchmark or W04,
integration, deployment, or production-acceptance evidence.
