# Initial signer-rotation author/freeze prerequisite

This source unit stops at **signed PREPARED overlap generation 2, accepted head still G1**.
It is not a complete signer-rotation procedure, activation gate, service, command or bean.
There is no PUT, COMPLETE, PROJECT, overlap acceptance or generation-3 operation here.

## Cold opt-in and actual prerequisite

The actual desired-deployment parser accepts `profile: "D7"` only at desired generation 1,
with a nonprojected G1 reader and a nonnull `catalogSignerRotation` object. That object names
`catalogWriterGenerationId`, the existing `signAuthority`, exactly two `orderedSigningKeys`
(old then new), and `totalAttemptMillis` (1–30,000). Each key supplies `keyId`, `keyArn`,
`algorithmId`, canonical `publicKeySpkiBase64` and `publicKeySha256`. Both public keys must
match retained current trust; the first must be its initial registered signer. D7 is part
of the actual acquired graph and effective D before genuine first-D/G1 bootstrap.

Optional epoch/sealer/live-coverage components must remain coherent. D1–D6 encoders are
unchanged and their parser profiles refuse the new field, including explicit null. D7
cannot supersede an already-selected legacy desired configuration: generation 1 is its
only supported selection. Rebuilding a process wrapper supplies no database authority.

`CatalogSignerRotationFreezeV1.begin(process, campaign)` requires the exact retained D7
runtime process and its genuine same-process G1 refresh/coordinator campaign. A caller
tuple, diagnostic readback result, different process, different coordinator, or fabricated
refresh result is not a substitute. G1 author/finalizer/operator roots do not acquire the
three new phase capabilities. The existing G1 and D5 readers retain their separate limits.

## Inputs, effects and cleanup

The request names explicit approved-intent and approval-array files plus a protected
durable release root. Intent is the existing canonical schema-1 overlap manifest, and the
approval file is its exact canonical ID/time approval array. Software checks include two
distinct sorted approver IDs allowlisted by both predecessor registry and current policy,
creator membership, chronology, exact predecessor/trust/registry/history, registered
writer and ordered old/new signer policy. **Human authentication, approved-input delivery
and trusted durable-root provisioning remain external.** There is no new approval
signature, issuer, schema, approval command or per-rotation pin ceremony.

One original coordinator-clock budget covers input acquisition, custody, all SQL phases,
raw readbacks, both SDK/PSS Sign responses, persistence and cleanup. Phase caps are 2s;
each Sign is additionally capped at 10s. No retry or heartbeat renews the original budget.
The lower Sign adapter remains unchanged and returns signature bytes only after actual
verification and owned cleanup. Native/DNS interruption completion is not a hard guarantee.

The fixed order is locked full-B/current DB-time lease and exact G1 history, actual raw
dual-location G1/no-tail verification, immutable PREPARE arm, then fenced/capacity-charged
V14 PREPARE. Before each Sign, providers are closed and a fresh locked full-B/DB-time
lease/history recheck succeeds. Each randomized signature is frozen write-once before
its SQL CAS. The exact two-signature canonical envelope is frozen before signature-2 SQL.
Only null-to-new or byte-identical signature/envelope states are admitted. No G1 mutation
guard is widened; overlap has a separate maximum-lifecycle capacity charge.

The release root contains only `rotation.lock` and `rotation-overlap-2/`. Immutable content
leaves have `.complete` sidecars. Linux secure-directory/inode/mode/link checks, exclusive
permanent lock custody, CREATE_NEW, fsync and exact reread detect bounded local conflicts;
they do not prove storage hardware, prevent custodian rollback, or authorize another root.
Uncertain files are never repaired, deleted or treated as unattempted effects.

The original SQL phase is retained before permit publication. Its actual finalizer,
Spring settlement, permit refund and acquisition-quiescence facts must positively settle
before the shared refresh slot is released. Later ThreadLocal emptiness, a supplied
exception flag or a replacement phase cannot erase sticky unknown cleanup. All original
provider/file owners are likewise retained through cleanup; existing process pools and
the campaign are not closed by the freeze owner. Fatal/cancellation/interruption signals
cannot become a successful result. The returned result is historical, not future authority.

## Narrow recovery boundary

`resume` never Signs. It supports only completely retained positive PREPARE history, both
actual returned signatures, signature-1 SQL receipt, signature-2 SQL arm and exact envelope,
with fresh actual SQL/raw readback under the still-genuine original campaign. It verifies
and persists only those same bytes; an already-identical SQL state is not charged again.
No absence, unknown response, sparse conflict or missing sidecar grants either Sign.

Continuation of a **known-unattempted** later Sign, broader SQL-outcome reconciliation,
and restart provenance after PREPARED2 are **internal unimplemented recovery states** in
this slice—not a blanket “external recovery” classification. Truly lost provider response
or trusted custody requires explicit recovery resolution. Complete rotation recovery is
not claimed. Genuine overlap publication/projection and immediate activation3 are also
**internal future work**, not capabilities supplied by this historical freeze result.

At author source handoff: compilation, static checks, runtime tests, provider execution,
services, builds, CI, commit and push are **NOT_RUN**. Any later acceptance evidence belongs
to the primary-controlled exact-source gate packet, not to this source document.
