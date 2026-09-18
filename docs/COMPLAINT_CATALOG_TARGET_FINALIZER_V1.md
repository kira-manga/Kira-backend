# Fixed G1 TARGET finalizer core (internal)

This is a **programmatic, non-web core**, not an executable release command. It does not Sign, PUT,
select first D, change grants, activate ordinary runtime, approve recovery or qualify a deployment.
The TARGET one-shot executable and owned-process failure retirement remain unfinished internal
integration work. The separate author executable does not supply this TARGET boundary.

## Entry, original allocation and actual TARGET

Call `CatalogGenesisFinalizeV1.begin()` before the stage's owned acquisition, then exactly one
`finalize(request, secretCredentials, primaryReadCredentials, replicaReadCredentials, sealerCredentials)`.
Credentials are explicit AWS sessions, with no default provider chain. The optional sealer session
is required exactly when the real deployment recipe retains that inventory; finalization never
uses it for a seal request. `CatalogGenesisFinalizeRequestV1` contains:

- `frozen`: the original `CatalogGenesisFreezeRequestV1`, including its original AUTHOR public
  descriptors and a **required independent pin file**. Intent, T0, Tn, approval bytes, signature,
  envelope and the complete freeze records are reread and raw-verified, not promoted from hashes.
- `targetDeployment`: the actual desired-deployment JSON path, read inside this owner. It is not
  a supplied D hash, assembled graph or successful first-D receipt.

The existing `PUBLIC_TARGET_BINDINGS` leaf includes the **AUTHOR** database secret reference and
version. Those bytes are immutable freeze provenance, not TARGET D. Reconstructing that leaf with
TARGET credentials would change the allocation and is refused. Public comparison acquires neither
the author secret nor the config-operator secret.

TARGET secrets are acquired at their exact immutable versions. The existing target assembly builds
the actual consumers, all three cold pools and any rotation/sealer/lane/live-coverage inventory.
The named restriction changes no D encoding, descriptor, credential or inventory. Actual canonical
D is checked unchanged across preparation; J, P, reader inputs, endpoint and frozen raw release
bindings must match. Only G1-reader D2/D3/D4 and eligible D6 profiles are accepted; no D1 or projected
reader retrofit is provided. The normal installer retains its separate-password/material checks.

## Fixed launch and SQL boundary

The named TARGET root starts only its original scanner/shared Timer and catalog participant,
bootstrapping through that same coordinator. Ordinary, deletion and optional rotation starts and
requests are permanently barred; the dormant rotation descriptor remains part of actual D.
The config/author roots and reserved usernames cannot be substituted or relabeled as this root.

Only SNAPSHOT, GENESIS_COMPLETE and GENESIS_PROJECT are permitted, each with the exact original
finalizer attempt. Legacy no-attempt entries, supplied-port mutation inputs, unrelated phases,
foreign/equal-D processes and unscoped checkout cannot enter through this root. Each actual phase
checks `session_user`, `current_user` and database against its retained TARGET opening descriptors.
No caller-selected role, test launch profile or reconstructed durable receipt grants entry.

The original coordinator reserves its one readback slot. The existing real S3 LIST/GET adapter and
raw verifier classify current G1, then the actual provider is closed. Only after unchanged process,
reader and time/retention checks does the connection-free durable barrier below run. Inside SQL,
only retained private identities are compared; filesystem/provider work cannot run under a phase.

COMPLETE and its final reread both require **nonnull current locked D matching actual TARGET D**.
PROJECT retains the same requirement. The existing epoch → control → catalog → all-history →
counters lock order, SQL and capacity accounting are unchanged. Legacy non-finalizer NULL-D
COMPLETE behavior remains separate and unchanged.

## Durable evidence and recovery

The independently provisioned Linux release root must be outside SQL restore/deployment/temporary
custody and independently bind this one release and namespace pair. Original no-follow/owner/mode/
file-key checks, exclusive lock, immutable complete leaves, file+directory force and exact reread
remain in use. The named `openExisting()` requires the existing lock and allocation before opening
the lock: neither TARGET finalization nor AUTHOR resume creates missing custody. Author `openNew()`
retains its creation behavior. Missing, partial or conflicting history is not repaired.

Before either COMPLETE or PROJECT, the finalizer writes or observes, then exactly rereads:

1. `PRIMARY_READBACK_EVIDENCE` and `SECONDARY_READBACK_EVIDENCE`, using the exact bytes from the
   freshly raw-verified, provider-closed readback. A one-sided preexisting pair is refused.
2. `FINALIZE_ARMED`, binding the original allocation, actual TARGET D/J/P, frozen envelope pin,
   object version, exact original creation-plus-ten-calendar-years retention, and both evidence
   hashes. The original frozen retention is not silently extended or weakened.

Only after all durability/reread checks succeed is the private original-attempt/readback/custody
barrier installed. Stored bytes cannot reconstruct a verifier result, process-bound projection or
fresh barrier. Shared evidence slots contain fully verified COMPLETED/REPLICA copies, not transient
PENDING observations. Stable evidence deliberately omits evaluation time and policy floor: exact
historical equality is **not proof of when verification occurred**.

Every invocation freshly LIST/GETs and verifies current provider and SQL state:

- PREPARED runs COMPLETE, then PROJECT.
- COMPLETED_PENDING skips COMPLETE and freshly runs PROJECT.
- PROJECTED permits only the exact locked no-op; timestamps and counters are preserved.

An arm without outcome does not permit re-Sign or re-PUT. The exact freshly confirmed projection
must be both committed **and released** before `FINALIZE_OUTCOME` is written and reread. That stable
record is identical for PROJECTED and ALREADY_PROJECTED and is not total-cleanup proof. This core
neither requires nor manufactures `FIRST_D_*`/`PUBLISH_*` observations as effect authority.

## One budget, cleanup and qualification limits

One original 60-second budget starts before stage file/secret/custody acquisition. The reader's
unchanged total allowance and each typed phase cap retain that parent; checkout, work, commit,
release and final cleanup cannot restart stage time. Expired phase cleanup uses a zero-remaining
system-clock snapshot so actual original close attempts are not skipped and no fresh work/wait
budget is granted. Finite SDK/cooperative ceilings are not hard DNS/native/filesystem cancellation.

The historical result is returned only after actual provider, files, pools, roots, shared Timer,
public trust and release-custody cleanup, with remaining original time. Cleanup failure is sticky;
fatal/cancellation/interruption signals retain priority without exposing provider diagnostics.
Failed/uncertain finalization keeps the original readback slot unavailable, never frees it for a
replacement wrapper. An already durable outcome cannot make failed cleanup a successful stage.

Active JDBC quarantine can prevent connection-free file/custody cleanup from dispatching at all.
Original handles and lock then remain retained. This is **not an eventual in-process cleanup
guarantee**; actual owning-process retirement is required. Enforcing that in a TARGET one-shot
executable is a prerequisite to operational use, not an externally blocked software task.

Independent approvals, custodian/no-rollback recovery, old-writer fencing, immutable image, cloud/
IAM policy and hardware durability need separate qualification. This software and fixture tests
do not establish those environmental guarantees or complete App29.
