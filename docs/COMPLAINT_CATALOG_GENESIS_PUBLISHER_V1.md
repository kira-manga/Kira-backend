# Bounded G1 primary publisher and read-only recovery

`CatalogGenesisPublishV1` is a programmatic, one-shot delivery composition. It is not a
Spring bean, executable, deployment command, activation grant or App29 completion.
AUTHOR freeze and TARGET finalization remain separate stages. This publisher does not
sign, select a NULL D, write the replica, run SQL COMPLETE/PROJECT, open complaint gates
or issue a current/restore capability.

The dedicated executable below is a **source-only, NOT_RUN increment**. Its compilation,
connected worker bridge and shared owning-process retirement checks remain unverified
integration, not operational acceptance. The core's qualification limits remain unchanged.

## Fixed executable and sessions

Invoke `me.manga.kira.backend.database.ComplaintCatalogGenesisPublishMain` using Java 21
on the Linux custody platform and an independently qualified **ordinary runtime classpath**:
absolute normalized application classes/plain JAR and dependency paths, including the
application's own code source. A Boot nested executable JAR, `java -jar`, relative path or
unexpanded wildcard is not that classpath. This entry does not assemble an artifact or
download dependencies. Use the same qualified launcher/JVM discipline as the
[AUTHOR CLI](COMPLAINT_CATALOG_AUTHOR_CLI_V1.md); keep the supervisor free of injected Java
options/agents. The only argument forms after this main class are, in fixed order:

```text
publish --manifest /absolute/original-author-request.json --target-deployment /absolute/desired.json --genesis-pin /absolute/independent.pin
recover --manifest /absolute/original-author-request.json --target-deployment /absolute/desired.json --genesis-pin /absolute/independent.pin
```

The existing strict AUTHOR manifest maps to the original frozen request; it is not replaced
by TARGET D and contains no credential bytes. The original no-follow, bounded manifest reader
retains its actual descriptor and closes it before core acquisition. Only the core reads the
TARGET desired document. There is no third JSON schema, default manifest, supplied D/version,
credential/endpoint flag, force/repair/reset/retry switch or implicit finalizer. Both commands
require a separately supplied independent pin. Do not invoke the worker main directly: only
the public supervisor observes owning-process death.

Supply these literal families through a protected environment, each with `_ACCESS_KEY_ID`,
`_SECRET_ACCESS_KEY`, `_SESSION_TOKEN`, never through argv, the manifest or transcripts:

| Family | `publish` | `recover` |
|---|---|---|
| `KIRA_CATALOG_PUBLISH_SECRETS` | Required | Required |
| `KIRA_CATALOG_PUBLISH_PRIMARY_READ` | Required | Required |
| `KIRA_CATALOG_PUBLISH_REPLICA_READ` | Required | Required |
| `KIRA_CATALOG_PUBLISH_SEALER` | Optional complete trio | Optional complete trio |
| `KIRA_CATALOG_PUBLISH_PRIMARY_PUT` | Required | **Not retained in the child environment or parsed/passed** |

Bounds are 128/256/16,384 printable non-space ASCII characters respectively. A partial optional
trio refuses; the actual core assembly requires sealer presence iff its mapping exists. SECRETS
must acquire the full actual TARGET inventory **and fixed configuration operator**, unlike the
TARGET finalizer. No AUTHOR password or signing session is acquired. The fixed publisher mode
is validated before child construction; recovery's allowlist omits PUT entirely, even if the
supervisor inherited it. No default AWS chain, AUTHOR/TARGET fallback or inherited Java options
are carried into the child. These checks do not authenticate credential provenance or IAM policy.

## Executable budget, observations and retirement

The worker begins the genuine original60s owner before owned manifest/session I/O, even for
invalid invocations. That same owner/budget spans actual file close, one `publish` or `recover`,
all cleanup and final time/interruption checks. No repeated begin, retry, replacement owner,
reader-cap renewal or in-process revival is available. Recovery calls the core API with **no
PUT credential parameter**; an old arm also makes `publish` read-only despite supplied PUT credentials.

The shared `CatalogGenesisProcessV1` retains the full launch/hook/wait/retirement mechanism once
for closed AUTHOR, TARGET-finalize, publisher and publisher-recovery entries. No configurable
worker, validator, session registry or workflow runner is exposed. The publisher uses the same
qualified classpath, fixed `-Xms32m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ActiveProcessorCount=2
-XX:+ExitOnOutOfMemoryError` child limits and UTC timezone. Child stdin is `/dev/null`; child
stdout/stderr are discarded. Only the exact retained original child can be retired, never a
process selected by PID search/name/group. Outer75s includes JVM startup; forced retirement
allows at most5s to observe death. Neither allowance extends the original60s effect budget.

After actual cleaned core return **and observed original child death**, bounded stdout is:

```text
catalog-genesis-publish AWAIT_REPLICATION; historical-only
catalog-genesis-publish DUAL_COPY_OBSERVED; historical-only
```

Both use shell0; internal worker statuses are12 and13, distinct from AUTHOR0/10 and TARGET11.
Wrong-stage success codes refuse. AWAIT is not completed delivery; DUAL does not finalize SQL
or activate runtime. Bounded failures use `catalog-genesis-publish refused: STATUS` and the
existing codes: INPUT_REFUSED64, FAILED70, CLEANUP_UNPROVEN71, FATAL72,
RETIREMENT_UNCONFIRMED74, TIME_BUDGET_EXHAUSTED124, INTERRUPTED130, CANCELLED131.
Fatal > cancellation > interruption > ordinary precedence and interruption restoration remain;
publisher and manifest cleanup/time classifications are preserved without raw causes or paths.
Every unconfirmed death stays nonzero and adds `; retirement=UNCONFIRMED`, retaining stronger signals.

Return, halt or destroy requests are not death evidence. Unknown construction without a child
handle, still-live native/OS conditions or supervisor catastrophe keep fencing/recovery restrictions.
Neither stdout, stored outcomes nor dead processes authorize re-PUT/re-sign, evidence deletion,
root replacement or retry. Later read-only recovery requires independent authorization and fresh
original-custody/pin/raw/SQL/readback checks. FINALIZE records remain in the separate finalizer lane.

Focused source tests join the real worker to existing freeze/first-D/publisher raw-provider/PG
fixtures and extend the existing three retained-lock child tests. **Active publisher readback
interruption with retained public PEM and original-child retirement remains INTERNAL outstanding
qualification.** A genuine interrupted pool first-close can keep trust release RETAINED; clearing
the caller flag cannot reset that proof. Generic signal tests and synthetic child-lock tests do
not cover that composed boundary. Process death does not itself delete persistent public PEM or
turn the original failed owner into a clean RELEASED result. No new cleanup/recovery framework or
in-process release guarantee is claimed. These source tests are NOT_RUN, not hardware/fsync/cloud
qualification or completion of the ordered G1 operational sequence.

## Independent inputs and original custody

Call `begin()` before the stage's file/secret/custody acquisition, then either:

- `publish(request, secretCredentials, primaryPutCredentials, primaryReadCredentials,
  replicaReadCredentials, sealerCredentials?)`; or
- `recover(request, secretCredentials, primaryReadCredentials, replicaReadCredentials,
  sealerCredentials?)`, which cannot supply a PUT credential.

`CatalogGenesisPublishRequestV1` contains the original `CatalogGenesisFreezeRequestV1`
and an absolute TARGET desired-deployment JSON path. A fresh `frozen.independentPin`
is mandatory. Input channels are bounded, no-follow, identity/size/mtime rechecked,
and actually closed before progression. Only explicit session credentials are used.

The existing release allocation and permanent lock must already exist. The publisher
opens them through strict no-create custody, verifies every original input and freeze
arm, the positive committed/released unsigned preparation receipt, signature-persistence
records, raw signature/envelope, independent pin, and `FREEZE_OUTCOME`. It repeats the
existing raw trust/intent/signature verification; stored hashes are not verifier handles.
Missing/partial/conflicting state is not repaired or reconstructed from SQL.

`PUBLIC_TARGET_BINDINGS` retains its original **AUTHOR** database reference/version and
all other public descriptors. The publisher neither replaces these bytes with TARGET D
nor acquires the AUTHOR password or signing credentials. Its separate publication arm
binds the actual independently acquired TARGET's D/public binding. Historical first-D
results/record slots do not admit this invocation.

## Real current selected-D admission

Normal desired-process assembly acquires the full TARGET secret inventory **and** the
fixed configuration-operator password, retaining the existing separate actual password
material check. Only bootstrap-capable G1 profiles are eligible. Reserved AUTHOR/operator
runtime usernames are refused before secret acquisition. The full TARGET graph, including
its actual reader/rotation/sealer/live-policy inventory, stays UNKNOWN/cold. Only the
separate configuration operator is prepared; TARGET ordinary/deletion/rotation pools do
not start.

The named `COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK` phase is reachable only through
this original publisher attempt and the actual fixed operator coordinator. It:

1. Acquires the genuine shared epoch fence and authenticates actual `session_user`,
   `current_user` and database, without SET ROLE or a supplied principal/result.
2. Locks the LIVE control row, then acquires the catalog advisory lock.
3. Uses a later **READ COMMITTED** all-history query: exactly one signed PREPARED G1
   must match the independently raw-verified token, writer, approvals, intent, signer,
   signature, envelope/hash, key and creation time. No predicate filters competitors
   away before cardinality is checked. All completion/copy fields remain NULL and
   other activity is excluded.
4. Requires already nonnull matching actual D and pristine closed initial control:
   schema/generation/epoch one, scan requested, no head/pending projection, identities,
   lease/checkpoint/rotation/seal activity. It repeats history/other-state and exact
   control rereads, preserving even `updated_at` and opaque fields.

The operation contains SELECT statements only. The transaction is not marked read-only
because PostgreSQL must permit the required `FOR UPDATE`; there is no UPDATE/CAS, grant,
counter or catalog writer in this new operation. Actual known commit **and** complete
holder/permit release are necessary before any delivery I/O. Later stages keep consulting
that original private operation, not an equal attempt, caller Boolean or historical
first-D result. Only after its exact current SQL comparison is the verified frozen
PREPARED snapshot used as the local input to raw provider readback.

Pending-G1 exclusion of ordinary D supersession and independent old-writer/restore
fencing remain necessary between the released SQL cut and later delivery observations.
Privileged SQL/restore/DDL bypass is not prevented by advisory locks.

## One arm, one conditional PRIMARY PUT

For a genuinely unarmed release, `publish` makes fresh terminal-empty BOTH-namespace
version/delete-marker probes through the real readback SDK and finishes actual provider
cleanup. Absence is only an observation; it does not certify a never-used/no-reset
namespace or independently establish writer fencing.

The create-once `PUBLISH_ARMED` record is forced and exactly reread before opening the
actual PUT lower. It binds the original allocation, operation token, envelope length/hash,
independent pin, ordered route pair, G1 key, signed creation time, frozen retention,
COMPLIANCE/SHA256/checksum, and actual TARGET D/public binding. Only a **newly created**
arm in this invocation permits its one effect. An identical old arm is read-only recovery,
even when `publish` was called with PUT credentials. `recover` refuses an unarmed release.

`AwsCatalogPrimaryPutAdapterV1` performs the actual explicit-session, regional,
`If-None-Match: *` PRIMARY PUT of the exact frozen bytes, with expected owner, checksum,
COMPLIANCE mode and creation + ten calendar years retain-until. There is no re-sign,
replica PUT or SDK retry. Its original construction owner is retained before either
HTTP/SDK constructor. A lost/failed/late acknowledgement or unknown cleanup cannot
restore effect eligibility.

`PUBLISH_OUTCOME` is forced and reread after the genuine returned acknowledgement and
actual PUT cleanup. Its fixed canonical list is:

```text
["catalog-genesis-freeze-v1", kind, allocationSha256, publishArmSha256,
 objectVersion, envelopeSha256, checksumSha256Base64]
```

`kind` is `publish-acknowledged` only for that actual returned SDK acknowledgement.
Fresh genuine readback may instead create `publish-readback-recovered`; it never invents
an acknowledgement that was lost. Existing variants stay byte-identical and every fresh
observation must match the retained version. Neither retry timestamps nor changing
PENDING/COMPLETED replication status enter this record.

## Readback, stable evidence and recovery

The publisher retains three distinct one-open two-location constructions before I/O:
empty-namespace probe, diagnostic readback, and separate full dual-copy verification.
It uses bounded complete LIST/exact-version GET/raw verification, not latest GET/HEAD,
a fake S3 client or a supplied diagnostic result.

| Retained/fresh state | Result or permitted progression |
|---|---|
| No arm/outcome/copies, current selected-G1 admission, fresh empty probes | `publish` alone may create one arm and issue one PRIMARY PUT. |
| Existing exact arm, with or without outcome | Fresh reads only, even through `publish`. No second PUT. |
| Exact primary, replica absent, primary PENDING **or COMPLETED** | Stable version outcome; historical `AWAIT_REPLICATION`. No final-copy evidence. |
| Complete exact dual copies, PRIMARY COMPLETED and secondary REPLICA | Separate fresh `GenesisReadback.verify`, settings/time/frozen-retention checks and actual provider close, then immutable final-copy evidence. Historical `DUAL_COPY_OBSERVED`. |
| Empty after arm, conflicts, wrong version/body/retention, duplicates, delete markers, replica-only inventory | Refusal/recovery-required, not delivery success or retry permission. |
| One copy-evidence leaf, malformed/out-of-order records or a conflicting binding | Refuse; no fill-in, overwrite, deletion or reset-directory repair. |
| `FINALIZE_ARMED` or `FINALIZE_OUTCOME` | Refuse into the separate finalizer recovery lane. |

Only private byte getters from the fresh full dual verifier populate
`PRIMARY_READBACK_EVIDENCE` and `SECONDARY_READBACK_EVIDENCE`, after actual provider close.
These stable COMPLETED/REPLICA tuples omit evaluation times and policy floors. Diagnostic
primary PENDING metadata never enters an immutable final-copy slot. Repeated recovery
compares exact stable bytes; it does not mutate an earlier provenance variant.

The TARGET finalizer independently rechecks its actual current D, performs its own fresh
readback and uses its own durable pre-effect barrier before SQL finalization. Publisher
history is not a replacement for any of those checks.

## Deadline, cleanup and remaining work

One original 60-second monotonic budget starts before owned input/secret/filesystem I/O.
The named SQL phase retains a two-second cap under it, including original-budget
zero-remaining cleanup handling. One unchanged reader cap is added after recheck and
spans probes, PUT, both readbacks, durable records and cleanup; no request/retry/second
verifier renews either allowance. Current retention checks cover the whole original
remaining attempt and reject backward wall time/future creation without changing frozen
creation or retention.

The publisher retains fixed native HTTP construction slots beneath the existing SDK
lowers. Returned clients, requests and response/outbound bodies have custody before any
throwable post-constructor/prepare/call check. The lower elapsed clock stays the pure
system clock; the typed publisher transport checks both original budgets before actual
prepare/call and each body read. A late constructor or prepare cannot dispatch a request.
The same cap is rechecked between sibling publication writes and their exact rereads;
expiry cannot start the next record. Actual cleanup is still attempted after expiry.

All real partial constructors/descriptors/providers stay retained through failure. The
owner stops both original roots before awaiting shared Timer shutdown, attempts actual
provider/file cleanup, checks original release and deadline, and only then returns a
history state. Fatal/cancellation/interruption take precedence over ordinary failure
mapping; raw provider/SQL/path diagnostics are not returned. Uncertain cleanup is sticky
on the original one-shot owner. An unresolved active phase can prevent filesystem
cleanup dispatch; a later equal graph or empty Spring observation cannot fabricate it.

Finite software/SDK bounds do not prove hard DNS/native cancellation or physical wire-once
execution. The source-only executable above and its active-interruption/retained-PEM boundary
still need internal qualification; generic owning-child tests are not composed publisher proof.
Operational use also still needs independently provisioned no-rollback
custody/backups/durability, genuine approvals/current trust/released pin, least-privilege
fixed credentials and AWS account/routes, real versioning/Object Lock/replication policy,
writer/restore fencing, clocks, networking and native cleanup qualification. Source/tests
do not certify those external prerequisites.

Related: [AUTHOR freeze](COMPLAINT_CATALOG_AUTHOR_FREEZE_V1.md),
[TARGET finalizer](COMPLAINT_CATALOG_TARGET_FINALIZER_V1.md),
[AWS readback](COMPLAINT_CATALOG_AWS_READBACK.md), and
[readback reconciliation](COMPLAINT_CATALOG_READBACK_RECONCILIATION.md).
