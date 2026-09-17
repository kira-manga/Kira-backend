# W04g — bounded dual-location catalog readback and reconciliation

`CatalogDualLocationVerifier.verifyReadback(provider, initialBundleBytes, currentBundleBytes,
policy, local)` produces **read-only evidence about supplied observations**. It does not publish,
sign, advance an accepted head, project a mutation, accept a backup, restore a database, issue
credentials, promote a replica, or open complaint/traffic admission. It is not a Spring bean.
The provider port has only list and exact-version read operations.

## Independent inputs and provider boundary

Both raw trust inputs are bounded to the existing 128 KiB ceiling and copied before I/O. Current
trust is actually root-authenticated before the first provider invocation. Only its authenticated
PRIMARY/REPLICA location pair is used. The unchanged historical verifier subsequently authenticates
T0, G1, every supplied generation and current-trust continuity. Current writer/approver policy and
the signed current head floor are unchanged.

`CatalogReadbackPolicy` additionally requires an independent exact G1 **envelope** SHA-256,
evaluation epoch second, and absolute required retain-until epoch second. Both timestamps are finite
(0 through 253402300799); retention must be strictly after evaluation. This slice checks the supplied
absolute requirement; it **does not derive the production retention horizon**. That derivation must
be independently implemented and reviewed before production wiring.

The only namespace is `complaints/catalog/v1/`, with exactly twenty decimal generation digits and
`.json`, e.g. `complaints/catalog/v1/00000000000000000001.json`. Generations are 1 through 65536,
subject to any lower chain limit. There is no caller-selected prefix or key normalization.

The reader owns null-start pagination, page size (1–1000), and a finite per-location page budget
(1–65536). It checks supplied list sizes with widened arithmetic before indexed copying, rejects
every delete marker, and requires exactly one listed version for each contiguous key. Duplicate
entries, multiple versions of a key, missing or unexpected keys fail. Version IDs are non-null,
1–1024 non-space printable ASCII characters, and cannot be literal `null`. A truncated page must
advance with entries and provide the exact last `(key,versionId)` continuation tuple; a terminal
page cannot carry a continuation. Strict key progression prevents cursor cycling without retaining
a history of tokens. One bounded page per location is retained.

`CatalogListedVersion.contentLength` means S3 `ListObjectVersions` **Size**, not an imaginary
listing `ContentLength` field. Every GET requests that exact key/version. Its metadata must bind the
request and equal the listed size. Positive lengths and remaining per-location aggregate bytes are
checked as Long values before narrowing/allocation. G1 keeps its 128 KiB cap; later generations keep
the existing 8 MiB envelope / 4096 manifest-object / 65536 generation / 2 GiB chain ceilings, with
lower policy limits permitted. Both physical copies consume their own byte budgets.

Bodies must produce exactly the declared bytes through positive progressing reads, followed by one
EOF probe. Every returned body is closed, including on metadata/read/validation failure. The
provider-visible destination is detached after close, before another provider call or a lazy yield.
Only the current closed readback, bounded pages, local frozen inputs and the existing bounded chain
fold are retained, never an aggregate envelope history.

For each common generation, both locations must have the same version ID and bytes, exact
`COMPLIANCE` mode, equal retain-until at or above policy, PRIMARY `COMPLETED`, and REPLICA `REPLICA`.
Correlation fields are checked, but **echoed request bindings are not independent provider/account
authority**. A production authenticated AWS adapter, strict bounded response decoder and real IAM/
provider evidence are still required. A fake port returning convincing metadata does not prove AWS
retention, completeness or account ownership.

Provider exceptions and ordinary close failures become fixed, content-free typed failures, without
raw causes or suppressed messages. Cancellation remains cancellation; interruption preserves the
thread flag. Fatal JVM Errors are not converted into ordinary failures. A private per-call relay
restores only sanitized producer failures that the unchanged W04f supplier boundary would otherwise
wrap. It aborts immediately: no replay and no successful-prefix fallback.

## Local input validation and exact reconciliation

Local states are explicit `NeverAccepted`, `PreparedGenesis(mutation)`, `Accepted(head)`,
`Prepared(head,mutation)`, and `ProjectionPending(head,projection)`. A head contains a generation
and exact envelope SHA-256, not an opaque accepted Boolean. Local G1 heads must agree with the
independent G1 pin. `PreparedGenesis` has no accepted predecessor; no generation-zero head is
invented and the existing non-genesis `Prepared` contract remains unchanged.

Frozen mutation/projection constructors bound and copy their raw buffers; getters return copies.
Before provider access, the verifier validates lowered bounds, hashes, operation tokens, closed
schema-1/schema-2 canonical bytes, unsigned/signed agreement, exact predecessor/successor tuples,
initial registry claims, writer/approver policy, available chronology/history claims, retained
signer membership and any actual supplied signatures. G1 projection uses the existing real
historical genesis verifier. Neither a restored row nor a caller-supplied hash becomes trust.

`PreparedGenesis` supports **already persisted complete signed G1 only**. Its exact envelope must
match the independent G1 pin and its stored unsigned bytes, hashes, token and complete signature
slot. The unchanged raw historical genesis verifier checks G1 under authenticated T0/current Tn;
prospective current writer/approver policy must also pass. Current Tn's signed head floor must be
one: a higher floor cannot turn empty listings into genesis-publication evidence. No historical
authentication primitive is exposed, no policy floor is lowered and no signature is synthesized.
Unsigned/sparse genesis remains unsupported by this pinned readback entry point; the separate
pin-free preparation path below does not widen it. Independently pinning a randomized PSS envelope
requires the actual complete signed bytes, not merely the unsigned manifest or its hash.

The paired lazy sequence is fed once to the existing inventory-chain verifier, including the only
permitted primary-only tail. Every body is closed before yield. The signed trust floor is always
checked on that complete supplied sequence, never lowered to authenticate a replica prefix.

| Observed state and exact local match | Evidence only |
|---|---|
| Both namespaces empty; never accepted | `EmptyClosed` |
| Both namespaces empty; exact signed PREPARED genesis | `PreparedGenesisUnpublished` (no common head) |
| Exact signed PREPARED genesis only in primary, `PENDING` or `COMPLETED` | `GenesisAwaitReplication` (no predecessor/common head) |
| Exact signed PREPARED genesis in both locations | `PreparedCompletionEvidence` for its original token |
| Only independently pinned G1 in both; never accepted | `BootstrapObserved` |
| Common head equals the accepted local head | `CurrentHeadObserved` |
| PREPARED successor absent at its exact predecessor; unsigned | `NeedsSignaturePersistence` |
| Same absent successor; exact signed bytes already frozen | `NeedsConditionalPublication` |
| Exact signed PREPARED final successor only in primary, `PENDING` or `COMPLETED` | `AwaitReplication` |
| Exact signed PREPARED successor in both complete chains | `PreparedCompletionEvidence` |
| Exact pending-projection envelope/head/token in both complete chains | `ProjectionResumeEvidence` |

`AwaitReplication` and `GenesisAwaitReplication` deliberately carry no common-head evidence and
cannot advance acceptance or promote the replica. Replica-only objects, unexplained primary-only objects, unsigned attempts to
explain a remote successor, later generations, different exact PSS envelopes, mismatched local heads,
and arbitrary forward-head adoption fail closed. Re-signing the same manifest is not recovery of
the same frozen envelope. An accepted local head cannot reset to an empty namespace.

For an **absent** candidate, the signature/publication outcomes establish local integrity and the
observed predecessor only. They do not establish that the unpublished operation is valid against
that predecessor or that its real-world preconditions remain true. Publication integration must
validate operation-specific successor semantics, predecessor continuity and current preconditions;
these outcomes are not permission to sign or PUT. Externally observed candidates undergo the full
unchanged chain semantic/signature fold before any result.

`PreparedGenesisUnpublished` likewise proves only locally authenticated frozen bytes and the
current empty all-version listings. It proves neither a never-used namespace, absence of a reset,
two-person ceremony nor permission to sign, PUT, accept a head or activate complaint capability.

## W04i — dormant V14 storage observation adapter

`CatalogCoordinatorPersistence.snapshot.load(initialBundleBytes, currentBundleBytes, policy)` adds
an explicit **read-only observation path**, not a writer, projection executor, Spring bean or
admission decision. The independently owned capacity-one coordinator requires explicit preparation
and shutdown. The two bounded raw trust inputs are copied and current trust is root-authenticated
while connection-free, before entering its one named database phase.

`JdbcCatalogSnapshotReader` uses one MVCC statement for the LIVE control row and the **global**
pending mutation, including PREPARED rows that have no control projection token. The CTEs and result
are capped at two rows; a second row is corruption, not a choice of winner. Every variable-width
selected field is SQL `CASE`-gated before pgjdbc materializes it: 32-byte digests, 4 KiB approvals,
128 KiB unsigned/envelope bytes for generation one (8 MiB for later profiles), 1024-byte signature slots/object coordinates, 64 KiB per stored copy
evidence and the declared short text fields. Explicit bounds flags prevent an oversized optional
value from becoming an accepted null. Finite timestamp and state/presence checks remain fail-closed.

Only detached bounded scalars/bytes are copied while the phase owns JDBC. The exact read operation
is retained before SQL and cannot complete at mapper return; the query's actual result-set and
statement cleanup must first return through the guarded resource. Its rows getter requires that
same operation, a known committed outcome and completed resource release. Canonical parsing,
hashing, tuple comparisons and signature verification happen **after** finish, with a further
connection-free check. A validation failure does not write or repair anything.

The following is a **new, explicit read-only bridge contract**, not a claim that the previously
independent SQL and manifest vocabularies were already identical:

| V14 `operation_type` | Actual closed manifest operation |
|---|---|
| `GENESIS`, only PREPARED before any accepted head | Complete signed schema-1 `GENESIS`, exact independent G1 pin |
| `SIGNER_ROTATION_OVERLAP` | `ROTATION_OVERLAP`, schema 1 or 2 |
| `SIGNER_ROTATION_ACTIVATION` | `ROTATION_ACTIVATE`, schema 1 or 2 |
| `RESTORE_SOURCE_ACCEPTANCE` | Schema 2 `REGISTER_SOURCE` (one source + one copy delta) or `ADD_COPY` (no source + one copy delta) |

The manifest's actual strict operation/delta distinguishes the last pair; SQL never supplies a
default operation. Schema 2 retains `NEW_BACKEND_LOGICAL_BUNDLE_V1`; schema 1 stays closed. V14 has
no manifest schema column, so the selector is read from the actual root `unsigned_bytes` field and
then checked by the existing full canonical parser. All other SQL operations and any non-global
mutation scope reject. GENESIS support is limited to the new signed PREPARED observation with
successor1, predecessor0 and the zero predecessor digest. V14 requires **all five** control catalog
fields absent before acceptance; the adapter does not invent control writer/trust/head values.
Instead it validates the actual mutation writer against the authenticated genesis and independent
current policy. The signed `load` still rejects unsigned/sparse GENESIS. Separate preparation
observation is described below; COMPLETED-genesis adapter projection remains unsupported.
The older supplied-observation G1 projection verifier is unchanged, not newly wired by this adapter.

Checks bind the control head/hash/current trust-envelope hash/current allowed writer, pending token,
predecessor/successor, writer, exact generation key, canonical unsigned bytes/hash and complete
envelope agreement. `approval_bytes` must be the exact `kcj-1` array of manifest approvals, with its
actual stored hash. Ordered stored signer identities/policy must equal the manifest members. In
authenticated `load`, every present sparse signature is verified against copied current trust even if no envelope
exists; either overlap slot may be absent independently. An existing complete envelope must contain
the exact stored signature bytes, not another valid randomized PSS signature. Slot arrays and the
slot collection are defensively owned. Stored copy-evidence bytes/hashes and version/retention/state
shape are checked, but **not authenticated as provider evidence**.

The `load` result remains `LocalCatalogSnapshot`. The signed-genesis branch authenticates its exact
historical G1 relationship but grants no bootstrap authority. The result does not prove external
head agreement, complete historical chains, full inventory successor semantics, external head-floor satisfaction, retention,
provider uniqueness, source acceptance, or permission to publish/project/restore. Those unchanged
independent checks remain necessary. Focused tests cover sparse signatures and real V14 snapshot,
cleanup/custody, concurrent update and SQL bounds behavior using the existing PG fixture; test
source alone is not execution evidence or production qualification.

## Pin-free unsigned genesis preparation and post-signing exact pin

Unsigned bootstrap is a sequencing step, not a requirement to know a future randomized signature.
The offline owner already has the legitimate initial intent, approved canonical manifest, T0 and
independently current Tn. A locally derived unsigned hash binds those frozen bytes; it is **not** a
new external pin ceremony. The exact G1 envelope pin is fixed only after signing and signature
persistence, before the first PUT. T0 is never rewritten to include a G1 hash: G1 already binds T0.

`snapshot.loadGenesisPreparation(currentBundleBytes, chainPolicy)` uses the same one-statement,
sealed-until-commit-and-release observation path, without requiring a nonexistent G1 envelope pin.
Current Tn is root-authenticated and must permit floor one before entering the coordinator. All five
LIVE control catalog fields must be null; the global pending row must be PREPARED GENESIS, schema1,
successor1/predecessor0/zero hash, with exact canonical unsigned bytes, approvals, ordered SINGLE
slot and deterministic key. Generation-one document bounds are enforced before JDBC materialization; finite-state checks remain unchanged. It returns a distinct
`UnverifiedGenesisPreparation`, **not** `LocalCatalogSnapshot`. `NO_SIGNATURE`,
`SIGNATURE_BYTES_PRESENT` and `ENVELOPE_BYTES_PRESENT` describe presence only: even a forged retained
signature can be observed for diagnosis, never promoted into authentication by its presence.

`CatalogGenesisPreparationVerifier` is a concrete connection-free bridge:

1. `signingInput` compares the exact frozen bytes with independently held raw offline-intent bytes,
   not a hash nominated by the restored row. Raw T0/Tn root authentication, unchanged private
   historical continuity, current version/head floors, initial genesis semantics and current
   prospective writer/approver requirements must pass. Only an absent slot/envelope yields the
   existing fixed signing frame. This is signing **input**, not permission or a signer invocation.
2. `proposeSignature` fills only a missing slot, or reuses the exact retained signature without a
   signer call. It verifies actual RSA-PSS cryptography under raw authenticated T0/Tn, derives the
   closed canonical envelope and preserves any existing envelope byte-for-byte. Another valid PSS
   signature cannot replace a retained value. The result contains exact frozen before/after bytes,
   **not** a durable receipt or a complete V14 lock/lease/CAS implementation.
3. After the real fenced persistence phase, `verifyPinnedReadback` requires an exact complete reread
   and the existing independently provisioned `CatalogReadbackPolicy` G1 pin. It reauthenticates raw
   trust and calls the unchanged signed-genesis validation path, returning only a signed PREPARED
   observation suitable for existing independent external readback. It cannot create, update or
   infer a pin from the database or proposal, and a matching reread does not prove fenced custody.

Before any signature is persisted and any exact pin is committed, unchanged intent may be signed
again if its earlier signature was lost. Once either is frozen it must be reused exactly; after
pin commitment the offline owner must recover the identical retained envelope or quarantine,
never change the pin to accommodate another valid PSS signature.

Actual two-person offline release handling, independently verified empty two-bucket namespaces,
mandatory pre-sign/pre-PUT probes, old-writer fencing and durable pin release remain required product/owner
work. None is represented by a boolean or fake capability here. No KMS, PUT, projection, accepted
head, route, Spring bean, admission or credential activation is added. W06 remains excluded.

## Dormant G1 PREPARED and write-once persistence

`CatalogCoordinatorPersistence.genesis` adds two explicit named **write** phases:
`prepareGenesis` and `persistGenesisSignature`. Both use the same capacity-one coordinator slot
as the read-only snapshot, not ordinary/deletion admission or writes hidden in a read phase.
Independent offline intent, T0/Tn, prospective writer/approver checks, canonical/hash work and
actual RSA-PSS verification finish while connection-free. The capacity-policy digest is a separately
supplied binding, not authentication inferred from agreement between database rows.

Both short transactions use this fixed order:

1. Actual nonblocking shared `complaint-journal-epoch` fence on the retained holder, with the
   existing 100 ms sub-budget/75 ms call cap inside the original two-second phase.
2. Lock LIVE/global control; require maintenance **and** creation closed, publication epoch 1,
   and all five catalog fields null. Normally scan must not be requested; the sole exception
   permits `scan_requested=true` while database identity, restore identity, and event-writer
   generation are also null. This narrow initial-state exception preserves the scan request;
   it is deliberately **not** the deletion availability predicate or a reconciliation acknowledgment.
3. Nonblocking `complaint-catalog-mutation` advisory lock, then lock existing catalog history
   (`LIMIT 2 FOR UPDATE`) **before counters**, including on exact replay. No history permits a
   new G1; one exact PREPARED G1 permits replay. Completed history, another token/intent or an
   externally advanced/preexisting local head is never absence and never causes a rebase.
4. Lock the existing counter catalogue in ascending name order. New PREPARED charges one
   `catalog_mutations` unit and the complete G1 lifecycle reservation atomically with INSERT.
   Replay and signature persistence validate the bound ledger but neither recharge nor require
   free capacity or OPEN creation configuration.
5. A signature update compares the entire immutable tuple and exact nullable signature/envelope
   preimage, using `IS NOT DISTINCT FROM`. Only a missing slot/envelope is filled; exact complete
   reuse is a no-op, and another valid randomized PSS can never replace stored bytes.
6. Actual bounded SQL reread, commit, and actual release. The retained operation's observation
   remains sealed until **both** commit and cleanup are proven. An UNKNOWN outcome, rollback,
   stale preimage, SQL failure or failed completion tail never yields a success observation.

These primitives stop before object-version/retention/copy evidence exists. They return copied
`UnverifiedGenesisPreparation` bytes, not an accepted head, durable pin, provider receipt or
permission to publish. A PREPARED replay may diagnostically return retained signature bytes; its
mere presence still does not authenticate them. The existing connection-free exact/pinned bridge
is used after signature persistence and independent offline pin handling, without introducing a
pin setter or making the database the pin authority. No JSON, cryptographic verification, signer,
network or application callback executes under these locks; existing V14 hash constraints remain
database invariants. Initial scan reconciliation/opening is separate: the V14 seed still has
`scan_requested=true`; the desired installer changes only D/updated-at at bootstrap, and G1
preserves that request through preparation, signature persistence, completion, and projection.

### G1-specific full-lifecycle charge

`CatalogGenesisCapacity` bounds **both** unsigned and signed documents to 128 KiB, including SQL
pre-materialization on creation/replay/signature reread and the existing snapshot. This is lower
than V14's general 8 MiB limit, not a claim that the latter has this price. G1 keeps null scope/test
fields, one SINGLE signer, and no second slot. A future G1 completion/projection writer must
preserve these immutable bounds; other catalog profiles need separately reviewed charges.

The deterministic component bounds use a 32-byte tuple header/null bitmap, independent 8-byte
alignment and conservative 4-byte varlena headers:

| Full lifecycle component | Bytes |
|---|---:|
| Header; two UUIDs; two generations; four timestamps | 112 |
| Six digests | 240 |
| Bounded operation/canonicalizer/policy/signer ID/algorithm/state text | 424 |
| Both 128 KiB documents | 262160 |
| 4 KiB approvals; one 1024-byte signature | 5136 |
| Both 64 KiB copy-evidence blobs | 131088 |
| 1024-byte object key and version | 2064 |
| **Heap bound** | **401224** |
| PK, successor, object-key, pending-expression and scope/successor indexes | 48 + 40 + 1064 + 40 + 56 = **1248** |
| **Eightfold logical reservation** | **3219776** |

The charge reserves future evidence and all five index memberships before PREPARED commits; a
mandatory signature transition can therefore succeed at zero free capacity. It is not measured
disk/MVCC/TOAST/vacuum capacity. New G1 establishes its allocation through the atomic row/counter
transaction. On replay, exact catalog actual-count agreement and a storage lower bound are sanity
checks only: storage also charges other classes and cannot prove a restored row's historical
accounting. Authenticated configuration, drained aggregate reconciliation and erasure headroom
remain separate required work; there is no fabricated per-row accounting receipt here.

Eight focused leaves reuse `JdbcCatalogSnapshotIT`'s existing owned real-PG fixture. They cover
charge/max-shape/schema and all five index bounds (explicit detoasting, not just TOAST pointers),
replay/history and both oversized documents, genuine PSS persistence/reuse, stale/different PSS,
rollback after every charge/write and an actual deferred COMMIT failure, fence/scan/row contention,
closed/exact/one-over capacity, sealed results, completion-tail failure and unchanged snapshot/
one-slot boundaries. Synthetic maximal rows and synthetic capacity bindings are **not** signed
bootstrap/provider/configuration authority. Added test source is not execution or release evidence.

## Remaining product work and verification scope

Unfinished product work includes the real AWS adapter and authenticated observations, retention-floor
derivation, KMS and non-G1 signature persistence, conditional publication with its mandatory pre-PUT probe,
non-G1 preparation and durable fenced head/projection transactions, capacity/admission wiring, operation-specific
publication checks, history folds, capture/journal reconciliation and declared-restore tooling.
Ordinary-runtime no-forward-adoption is not a restore implementation or an operator quarantine
override. Missing product work cannot be relabeled as a deployment-only gate.

Synthetic tests exercise signatures, local-state outcomes, pagination/budgets, exact metadata and
body ownership, interruption/cancellation and fail-closed conflicts. They are not real provider
observations, maximum-capacity benchmarks, integration/package evidence or production acceptance.
W06/legacy migration is owner-excluded, not a pending gate. V6 section13.1 remains normative subject
to the 2026-09-08 clean-start owner amendment.
