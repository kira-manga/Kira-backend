# Explicit desired-configuration installer v1

This is a **non-web, one-shot operator command**, not Spring startup, an Admin API,
Flyway, a deployment controller, readiness, or a restore/writer-succession protocol.
It changes only the closed LIVE desired selection. A result is historical and
grants no catalog, lease, checkpoint, seal, rollout or serving authority.

## Invocation and independent inputs

Invoke `me.manga.kira.backend.database.ComplaintDesiredConfigurationMain` using the
independently qualified backend artifact/runtime classpath (including its exact
owned pgjdbc cut). There is no `bootRun`/HTTP auto-invocation or deployment task.

```text
java -cp <qualified-runtime-classpath> me.manga.kira.backend.database.ComplaintDesiredConfigurationMain \
  bootstrap --manifest /protected/deployment/desired.json
java -cp <qualified-runtime-classpath> me.manga.kira.backend.database.ComplaintDesiredConfigurationMain \
  supersede --expected-generation 1 --manifest /protected/deployment/next.json
```

These examples describe the interface; they are **not authorization to run a
production job**. Only the owner may authorize actual provisioning/deployment.
The job reads a protected regular file, with an absolute normalized path and no
final symlink. Its parent and all ancestors must be independently protected from
untrusted replacement. File/OS/native deadline completion is not attested here.

The bounded (1 MiB) closed JSON schema is
`ComplaintDesiredDeploymentDocumentV1` in `ComplaintDesiredDeploymentJsonV1.kt`.
All fields, including explicit nullable fields, are required. Unknown/duplicate
keys, malformed UTF-8, trailing JSON, floats and oversized/deep input are refused.
There is **no desired hash, supplied effective-D document, credentials, arbitrary
JSON, family/purpose label, current-state snapshot, or launch-mode field**.

| Input group | Required independent intent |
| --- | --- |
| Top level | `schemaVersion: 1`, exact `profile`, `implementationSchema: 1`, positive `desiredGeneration`, canonical v4 database/restore UUIDs |
| `database` | One DNS host/port/database, distinct runtime username, independently versioned runtime/operator password references, ordinary pool capacity, canonical Base64 public PEM, protected absolute trust parent |
| Secret references | Exact logical `keyId`, full Secrets Manager `resourceArn`, immutable `versionId`; no stage/latest/discovery or secret text |
| `jwt` | Actual fixed user key ID (`JwtService.KEY_ID`), issuer/audience, TTL/skew, installation active ID and all retained versioned keys |
| `capacity` | Exact v1 22-counter `hardLimits` and `creationLimits` in `ComplaintCapacityEncoding.vectorOrder()`, daily enrollment limit |
| `admission` | All finite memory/single-instance budgets, explicit forwarding/proxy settings, current/previous admission keys, active/all retained cursor keys |
| `journal` | Full independent INITIAL_LIVE writer/location/authority/encryption/recovery/limits declaration and active/all retained versioned routing keys |
| `catalog` | Explicit `readerProfile` (`G1` or `PROJECTED_CURRENT`), exact T0/Tn public envelopes, root key/digest/ID/algorithm, environment/locations/floor, current writer and approver IDs, genesis envelope digest and complete chain/SDK/attempt/page limits; null for D1 |
| `epochRotation` | Explicit cold same-root rotation inclusion; it does not start rotation |
| `sealer` | Independent stable IAM mapping/origin/version/policy references, optional source-session name and explicit STS limits; otherwise null |
| `livePolicy` | D6-only independent copy age selectors, exact J lock mapping, HMAC/KMS retention and native-late-arrival/UTC policy references; null for D1–D5, never installation/backup-acceptance facts |

Supported shapes: D1 has no reader/rotation/sealer; D2 has the genuine G1 reader;
D3 adds actual rotation; D4 additionally retains the real sealer recipe and shared
J lanes. D5 has the projected-current reader, optional actual rotation and optional
sealer (sealer requires rotation). D6 retains the actual rotation/sealer/shared-lane
and LIVE policy owners. D6 explicitly selects either the genuine G1 reader or the
genuine projected-current reader; changing that choice changes D, never an owner
after construction. D2–D4 require G1, D5 requires projected-current; no D1–D5
relabeling. TEST, Redis/multi-instance and changes of
database/restore/event-writer identity are not installation modes.

The command acquires every exact secret version through the existing bounded AWS
SDK resolver using explicit operator-job session material from:

```text
KIRA_DESIRED_SECRETS_ACCESS_KEY_ID
KIRA_DESIRED_SECRETS_SECRET_ACCESS_KEY
KIRA_DESIRED_SECRETS_SESSION_TOKEN
```

For a sealer profile, independently provide
`KIRA_DESIRED_SEAL_BOOTSTRAP_ACCESS_KEY_ID`,
`KIRA_DESIRED_SEAL_BOOTSTRAP_SECRET_ACCESS_KEY` and
`KIRA_DESIRED_SEAL_BOOTSTRAP_SESSION_TOKEN`. There is no default AWS credential or
region discovery. Sealer material is only retained in the actual cold recipe:
**this job performs no STS, KMS, S3, catalog, publication or provider-policy calls**.
Secret access and the job entry must be independently access-controlled. Do not
put any of this material in the manifest, command arguments, output or review
artifacts. Existing acquisition/JVM/SDK String copies are not claimed zeroized.

Actual acquired consumer/P/J/pool/reader/rotation/sealer owners form the cold
target graph, which computes D itself. The target credential is never replaced
by the operator credential. All referenced secret versions must be distinct;
the two actual DB password values must also differ. The target pools never
prepare/start and its public-trust file is not installed by this job.

## Fixed authenticated database operator

Separately provision the LOGIN `kira_complaint_config_operator`. A distinct cold
root uses its acquired password and the target's same exact endpoint/database/
verify-full public trust. The named operator route starts only the existing
scanner/shared Timer and original one-slot coordinator. Ordinary/deletion starts
are permanently sealed, rotation is absent, default/UNKNOWN target roots remain
closed, and only the three installer phase paths can use the operator root.
Unscoped business checkout on this operator DataSource is refused.

Each phase verifies the **actual** authenticated session:
`session_user = current_user = 'kira_complaint_config_operator'` and the actual
database name. `SET ROLE`, a manifest principal name, JWT, route selection, or a
configuration object cannot establish this condition. No operator executor is
constructed on a normal root, and no catalog/lease executor is constructed on the
operator root. There is no new raw connection, worker/registry or shutdown model.

The following are provisioning requirements, **not SQL executed by the job**:

- LOGIN, NOSUPERUSER, NOCREATEDB, NOCREATEROLE, NOREPLICATION, NOBYPASSRLS;
  no runtime/catalog-role memberships, ownership/DDL privileges or ability to
  administer other principals. Restrict its job and secret to the operator.
- CONNECT to the exact target database and USAGE on the protected `public` schema.
- SELECT on `public.complaint_journal_control`,
  `public.complaint_catalog_mutations`, `public.complaint_test_runs`,
  `public.complaint_journal_publications`, `public.complaint_journal_scan_runs`.
- Only column UPDATE on `public.complaint_journal_control` for:
  `desired_generation`, `desired_configuration_hash`, `maintenance_closed`,
  `creation_closed`, `scan_requested`, `lease_owner`, `lease_token`,
  `lease_expires_at`, `updated_at`.
- No INSERT/DELETE, identity/writer/head/trust/checkpoint/retention/slot UPDATE,
  schema/function/trigger ownership, or inherited broader access.
- Runtime/catalog principals **cannot update desired columns**. Revoke any
  table-level/PUBLIC/inherited UPDATE before granting their separately needed
  column rights; a column REVOKE alone does not negate table-level UPDATE.

Provision real SCRAM/password credentials, role settings, trusted schema/functions,
TLS, public-trust parent and grants out of band. The source does not attest this
environment merely by successfully authenticating a session.

## Transitions and failure semantics

**Bootstrap** requires generation 1, a genuine initial-reader D2/D3/D4 or G1-D6 graph and
the exact pristine V14 LIVE seed, including empty identity/head/lease/slot/
checkpoint state and no catalog/test/publication/scan work. It writes only D and
server `updated_at`. Signed G1 projection still owns the initial identities and
head/trust/writer pointers. The scan request remains true through the strictly
initial, epoch-1 G1 preparation/signature/projection path: G1 is a prerequisite to
reconciliation, not its acknowledgment. D1, D5 and projected-current D6 bootstrap
are refused before provider calls. A projected legacy NULL-D database is not a
bootstrap/reset path.

**Supersede** requires target generation = explicit expected old + 1, checked for
overflow, and an already-projected old B of the same LIVE/schema/database/restore/
event-writer identity. It cannot replace a pre-G1 selection:

1. Lock only LIVE control; capture exact old D/head/trust/catalog-writer facts;
   close maintenance and creation and request a scan. Commit **and actually
   release** this holder before any second phase.
2. In a distinct <=2s phase, require the exact captured old B and closed gates,
   no pending mutation/projection or populated rotation/seal slot. Replace only
   D/generation, increment the **current** lease token with overflow refusal and
   clear its owner/expiry. Full nullable current lease/gates/update-time preimage
   CAS and exact reread preserve every other field, including epoch, retention
   lease, head/trust, checkpoint and slots.

These named phases pin READ COMMITTED rather than inheriting a database/role
isolation default. Pending/pristine absence checks take a later statement snapshot
after the actual control lock, including a preceding writer's committed history.

No epoch/advisory/history/counter/domain lock or control→fence path exists. The
existing full-B/token checks fence durable old work, **not native provider calls
or serving pods**. Failure after phase 1 leaves closure durable. Populated slots
are intentionally refused until their real completion/replacement protocol exists.

A fresh independently authenticated retry can observe an exact already-selected
target without another token increment or clearing a subsequently acquired lease.
A competing different D or stale old binding is refusal, not success. No unknown
commit, afterCommit failure, late result or unproven release becomes success; the
original attempt cannot be revived. Retry is a new authorized command, not an
automatic loop. Known phase commit **and actual release**, followed by all provider/
root/Timer/trust cleanup, are necessary before bounded result output.

One original 60s command budget covers input, all acquisition/construction, phases
and cleanup. Each provider request is capped at 5s and the remaining command time;
each phase is capped at 2s on that same parent. The unchanged coordinator preparer
retains its own 10s local cap: admission requires more than that much original time,
and its complete return tail is checked again. These are finite application
budgets, not proof of hard OS/native call interruption. All roots are stopped and
all owned pools closed before waiting for their shared-Timer termination conjunction.
Cleanup failure remains retained and suppresses output success.

## Qualification still required

This source path is genuine, but a named launch route is **not immutable-launch
qualification**. Existing globals/logging/resource/loader/ABI/current-property checks
remain unchanged; they prove only the facts they actually observe. Independently
qualify the exact artifact/JVM no-agent/no-mutation/provider provenance, installed
privileges and environment. This change neither deploys nor certifies them.
Actual connected tests, all-pod rollout, current catalog/lease authority, matching
scan/checkpoint, provider-policy acceptance and final LIVE/store readiness remain
separate evidence. Do not infer any of those gates from installer output.
