# Fresh-process exact PREPARED2 no-Sign recovery

This fixed programmatic slice resumes only an existing first-overlap **PREPARED generation 2,
accepted head still G1**, with both actual returned signatures and intact independent durable
custody. It does not require the prior in-memory process, refresh result or campaign. It does
require the same reconstructed D7/generation-1/nonprojected runtime inventory, effective D,
database/restore identities, journal/catalog writer, capacity policy and retained trust.

## Construction and authority

Retain a real `ComplaintDesiredProcessAssemblyV1` before construction. Use
`assembleTargetSignerRotationRecovery(inputs, acquiredTargetBindings, sealerCredentials)`
and `prepareTargetSignerRotationRecovery(preparationBudget)`, then invoke:

```kotlin
CatalogSignerRotationPreparedRecoveryV1.begin(assembly.target).resume(
    request,
    primaryReadCredentials,
    replicaReadCredentials,
)
```

The caller owns eventual retirement of that same assembly, including partial preparation,
original pools/actors and trust-file release. No operator password/composition is acquired.
Optional epoch/sealer/coverage inventory is retained unchanged, not silently dropped from D.
The recovery invocation starts one original allowance on the retained coordinator clock;
its snapshot, raw reads, lease acquisition, replay and cleanup consume that same allowance.
Preparation has its separately bounded caller-owned lifecycle allowance.

The root's immutable, mutually exclusive `catalogSignerRotationRecovery` purpose binds the
same runtime endpoint/principal with `UNKNOWN`, not `CONTROLLED_TEST_ONLY`. Ordinary/deletion
starts and optional epoch capture are permanently sealed at construction. Only the original
scanner/Timer/coordinator may prepare; only snapshot, lease and rotation executors are built.
Phase entry requires both the fixed whitelist and the actual concrete recovery owner (or its
exact replay child) **before publication or permits**. Generic startup, unscoped checkout,
ordinary Freeze/PREPARE, G1/desired/projected/epoch/cutoff paths cannot use this purpose.
It is not the separate initial D7-author launch route or a production-image qualification.

## Historical binding versus fresh leadership

Existing-only discovery takes the original permanent custody lock. It requires the allocation,
all present completeness pairs, exact canonical original binding/allocation records and the
original intent, ID/time approval array, T0/Tn, PREPARE and returned-signature history. Missing
custody is neither created nor repaired. The first signature's committed SQL receipt, second
signature's SQL arm and exact frozen two-signature envelope are mandatory.

The historical BINDING retains nine full-B fields, capacity digest and the **old owner/token**.
Those bytes and the original allocation hash links remain unchanged. They are historical
facts, not a lease. The fresh owner first obtains an actual released PREPARED snapshot and
genuine raw dual-location G1/no-tail readback with provider cleanup. A private recovery-purpose
binding then uses the existing row-only, unfenced lease ACQUIRE and real DB time. The locked
counter must be at least the historical token **before CAS**; a live lease, regressed counter
or `Long.MAX_VALUE` refuses. The successful new owner differs and its token advances. No
public campaign, general binding, supplied tuple or reusable admission result escapes.

Only after known ACQUIRE commit/release does the exact replay child perform existing fenced
full-B/current-lease/complete-history/capacity READ, another genuine raw round and fenced exact
recheck. It may then persist only the original returned signatures and canonical envelope.
SQL signature 1 must already match; signature 2 may be null or identical. The original
allocation, PREPARED tuple, capacity charge, head and pending-projection token are not rebased.
Successful replay records only the existing signature-2 SQL receipt/freeze outcome leaves.

## Cleanup and boundaries

The concrete parent owns one shared refresh slot and one original budget, the original input,
custody and raw-provider owners, and its exact snapshot/ACQUIRE contexts retained before effects.
Its direct replay child retains the original rotation contexts, not a nested Freeze invocation
or deadline. Result construction requires the exact completed product and proven original
cleanup. No positive exception flag, ThreadLocal absence or replacement phase proves release.
An ambiguous ACQUIRE outcome stays independently sticky even if physical retirement later
settles; no result, retry/refund inference or shared-slot laundering follows. The private
campaign is stopped locally, never guessed SQL-relinquished. Fatal/cancel/interruption and
late/uncertain cleanup cannot become success. The returned result is historical only.

No Sign credentials or Sign client are supplied on this route. There is no new PREPARE,
operation token/generation, capacity charge, Sign, PUT, COMPLETE, PROJECT, activation, command,
service, bean, approval issuer/schema, custody protocol or per-rotation pin. Existing canonical
manifest/trust and its exact ID/time approval array remain authoritative software inputs;
human authentication, input delivery, independent custody and credentials remain external.

This supplements the original same-campaign `CatalogSignerRotationFreezeV1.resume` boundary;
it does not relax known-second-Sign eligibility. Missing/lost returned evidence, changed restore
identities, published overlap/later external tails, delivery/projection/activation recovery and
the initial D7-author launch gap remain outside this slice. G1/D5 readers are unchanged.

At source freeze: compilation, static checks, tests, provider execution, services, builds, CI,
commit and push are **NOT_RUN**. D7 is an unvalidated dependency; this document is not acceptance.
