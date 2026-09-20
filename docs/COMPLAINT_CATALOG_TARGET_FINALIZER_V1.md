# Fixed G1 TARGET finalizer and one-shot entry (internal)

The **programmatic, non-web core** is not itself an executable release command. It does not Sign, PUT,
select first D, change grants, activate ordinary runtime, approve recovery or qualify a deployment.
The dedicated TARGET executable below is a **source-only, NOT_RUN increment**; its compilation,
connected bridge and shared owned-process retirement verification remain unfinished integration
work. Do not treat source availability or the separately verified author entry as TARGET execution
evidence. The core's safety and external qualification limits below remain unchanged.

## Fixed TARGET executable inputs

Use Java 21 on the Linux custody platform with an independently qualified **ordinary runtime
classpath**, as described for the [AUTHOR CLI](COMPLAINT_CATALOG_AUTHOR_CLI_V1.md#qualified-invocation).
The application classes/plain JAR and normally resolved dependencies must be absolute normalized
filesystem entries, including the application's own code source. A Boot nested executable JAR,
`java -jar`, relative entries or an unexpanded wildcard do not substitute for that artifact. No
Spring context, runner, bean or build-main change is involved.

With `QUALIFIED_RUNTIME_CLASSPATH` independently supplied by the deployment owner, the source entry is:

```sh
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
  "$JAVA_HOME/bin/java" -Xms32m -Xmx256m -XX:MaxMetaspaceSize=128m \
  -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError \
  -cp "$QUALIFIED_RUNTIME_CLASSPATH" \
  me.manga.kira.backend.database.ComplaintCatalogGenesisFinalizeMain \
  finalize --manifest /absolute/original-author-request.json \
  --target-deployment /absolute/desired.json --genesis-pin /absolute/independent.pin
```

Exactly these seven argv tokens follow the main class, in exactly this order. The independent pin
is mandatory and remains the exact 64-byte lowercase-hex ASCII file without a newline. There is
no `resume`, `force`, `repair`, supplied-D, username, endpoint, credential, publisher or multi-stage
option. Every invocation is `finalize`; only the core's fresh checks classify prepared, pending
or already-projected state. Do not invoke `ComplaintCatalogGenesisFinalizeWorkerMain` directly:
that would omit the supervisor's actual child-death observation.

The command reuses **two existing strict documents**, not a new combined manifest/schema:

- `--manifest` is the existing [AUTHOR public acquisition manifest](COMPLAINT_CATALOG_AUTHOR_CLI_V1.md#fixed-public-acquisition-manifest).
  Keep its original AUTHOR DB secret descriptor, signing-key public descriptor, paths, trust,
  capacity digest and release root. They must match the frozen allocation; do not replace them
  with TARGET credentials. The existing duplicate/unknown/null/trailing-input and bounded UTF-8
  parser remains authoritative. The manifest is read through one retained no-follow descriptor
  on the original TARGET budget; its actual close precedes core acquisition.
- `--target-deployment` is the existing strict desired-deployment document. Only the genuine
  TARGET core opens/reads it. The worker does not pre-read it to synthesize D or decide which
  credentials to request. It constructs only `CatalogGenesisFinalizeRequestV1(frozen, path)`.

The command manifest is public provenance, not an approval or new canonical/capability authority.
Neither a stored envelope hash nor arm/outcome bytes can replace the separately supplied pin.

## Explicit TARGET sessions

Provide the following literal families in the protected process environment, each with exactly
the suffixes `_ACCESS_KEY_ID`, `_SECRET_ACCESS_KEY`, and `_SESSION_TOKEN`:

| Family | Use | Required |
|---|---|---|
| `KIRA_CATALOG_TARGET_SECRETS` | Actual TARGET exact-version secret acquisition | All three |
| `KIRA_CATALOG_TARGET_PRIMARY_READ` | Primary catalog LIST/GET | All three |
| `KIRA_CATALOG_TARGET_REPLICA_READ` | Replica catalog LIST/GET | All three |
| `KIRA_CATALOG_TARGET_SEALER` | Retained optional sealer inventory, never a seal request here | All absent or all valid |

Access-key IDs, secret access keys and tokens must contain 1–128, 1–256 and 1–16,384 printable
non-space ASCII characters respectively. A partial or empty optional trio is refused. The complete
optional session is passed unchanged to the actual assembly, which already requires presence
**if and only if** its retained sealer mapping exists. No extra deployment-document read or fallback
session is used. Session provenance, permissions and isolation still need independent qualification.

The supervisor carries only these twelve possible TARGET variables into its fixed child. AUTHOR
Sign/secret sessions, default AWS credentials/profile settings, endpoint overrides and inherited
Java options/agents are not carried. There is no author/operator secret acquisition, Sign or PUT
client on this command path. Keep the supervisor itself free of injected options/agents too.
Never put credentials in argv, the public manifest, source control, output or terminal transcripts.

## Original worker, reporting and failure retirement

The dedicated worker calls genuine `CatalogGenesisFinalizeV1.begin()` before owned manifest or
credential I/O, even for invalid invocations. The same original 60-second owner/budget is retained
through strict parsing, explicit sessions, actual finalization, cleanup and the final time and
interruption check. There is no second begin, renewed cleanup allowance, replay of the failed
owner, replacement slot/root or in-process recovery. Manifest and TARGET exception families are
mapped explicitly so cleanup/time classifications are not lost; fatal > cancellation > interruption
priority is preserved and interruption restored before cleanup. Unknown cleanup cannot succeed.

`CatalogGenesisProcessV1` shares the complete original-child launch/retain/shutdown-hook/wait/force
mechanism with AUTHOR through a **private closed AUTHOR/TARGET_FINALIZE selector**. It is not a
registry, configurable command runner or publisher framework. AUTHOR's external grammar, status
codes and output remain separate and unchanged. The child's Java installation, classpath and
limits are fixed; stdin is `/dev/null` and stdout/stderr are discarded, never relayed as diagnostics.

The outer allowance remains 75 seconds including JVM startup, with at most five seconds for
forced-death observation of that **same original child**. Those are not additional effect time.
A return, halt/destroy request, shutdown hook or cooperative deadline does not prove death. The
supervisor only reports success after observing the original worker dead with TARGET status `11`:

```text
catalog-target-finalize PROJECTED; historical-only
```

The public shell code is then zero. New projection and exact locked no-op have the same output.
AUTHOR's `FROZEN`/`SIGNED_AWAITING_RELEASE` statuses `0`/`10` are refused by TARGET, and TARGET's `11`
is refused by AUTHOR. No stdout is emitted on a reported refusal. Bounded stderr is
`catalog-target-finalize refused: STATUS`, using:

| Status | Code |
|---|---:|
| `INPUT_REFUSED` | 64 |
| `FAILED` | 70 |
| `CLEANUP_UNPROVEN` | 71 |
| `FATAL` | 72 |
| `RETIREMENT_UNCONFIRMED` | 74 |
| `TIME_BUDGET_EXHAUSTED` | 124 |
| `INTERRUPTED` | 130 |
| `CANCELLED` | 131 |

Unconfirmed death is always nonzero and adds `; retirement=UNCONFIRMED`, preserving any stronger
fatal/cancellation/interruption status. Raw paths, credentials, parser/provider exception graphs,
worker output and stack traces are not public diagnostics. Unknown child construction without a
handle, a still-live child, supervisor SIGKILL or OS/native catastrophe cannot be called retirement
proof; retain fencing until actual absence is independently established.

Neither a clean historical output, durable outcome nor dead process is activation/recovery
authority or proof of total effect absence. A failed or armed-without-outcome invocation never
authorizes re-Sign, PUT, deletion/repair, automatic retry, another allocation, renewed time or
in-process revival. Use only the independently authorized original-custody recovery procedure.

Focused source tests add a genuine D4 AUTHOR-freeze/first-D → TARGET worker bridge with the exact
typed finalizer owner, real parser/session path, raw SDK fixtures, original budget, durable barrier,
committed/released SQL projection and actual cleanup. The existing three process tests exercise
both stage mappings using the same synthetic retained-custody child: lock unavailable after main
return, original-child death, then fresh lock and exact-leaf reacquisition. They are **not** live
TARGET SQL-quarantine, cloud, power-loss or fsync-fault qualification; this increment is NOT_RUN.

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
guarantee**; actual owning-process retirement is required. Verification of that boundary in the
source-only TARGET entry above remains a prerequisite to operational use, not an externally
blocked software task or a result inferred from AUTHOR's earlier execution.

Independent approvals, custodian/no-rollback recovery, old-writer fencing, immutable image, cloud/
IAM policy and hardware durability need separate qualification. This software and fixture tests
do not establish those environmental guarantees or complete App29.
