# One-shot catalog-author CLI v1

This is an explicit, non-web process boundary around the existing
[`CatalogGenesisFreezeV1` core](COMPLAINT_CATALOG_AUTHOR_FREEZE_V1.md). The core document describes
the programmatic baseline; this entry adds its fixed acquisition manifest and dedicated owning JVM
retirement. It does not start Spring, register a runner/bean, change grants, substitute TARGET
credentials, invoke first-D, publish/PUT, finalize, deploy or activate runtime.

## Qualified invocation

Use Java 21 on the Linux custody platform, with an independently qualified **ordinary runtime
classpath**: the application classes/plain JAR and all its normally resolved runtime dependencies.
Every entry must be an absolute normalized filesystem path; the application's own code-source
entry must be present. A Spring Boot nested executable JAR, `java -jar`, relative entries or an
unexpanded wildcard are not a substitute. This command does not assemble or qualify that deployment
artifact or download alternate dependencies.

For example, with `QUALIFIED_RUNTIME_CLASSPATH` supplied by the deployment owner:

```sh
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
  "$JAVA_HOME/bin/java" -Xms32m -Xmx256m -XX:MaxMetaspaceSize=128m \
  -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError \
  -cp "$QUALIFIED_RUNTIME_CLASSPATH" \
  me.manga.kira.backend.database.ComplaintCatalogAuthorMain \
  freeze --manifest /absolute/request.json
```

The only argument forms after that main class are:

```text
freeze --manifest /absolute/request.json
resume --manifest /absolute/request.json
resume --manifest /absolute/request.json --genesis-pin /absolute/independent.pin
```

Order is fixed; there are no additional options. A future independent pin is not accepted by
`freeze`. The pin supplied to `resume` is the existing independently released, exact 64-byte
lowercase-hex ASCII file, without a newline. Neither the command manifest nor stored release
history can supply that independent authority. Do not directly invoke the worker main: the public
supervisor, not the worker's halt request, observes owning-process death.

Keep the supervisor free of injected Java agents/options as well. Its worker command is fixed,
uses the supervisor's Java installation and ordinary classpath, and carries only the twelve
explicit session variables below. Worker stdin is `/dev/null`; worker stdout/stderr are discarded.
No credential, parser/provider cause, raw path or stack trace is relayed from worker output.

## Explicit sessions

Supply all twelve variables through the protected process environment, never command arguments,
the public manifest, committed files or command transcripts:

```text
KIRA_CATALOG_AUTHOR_SECRETS_ACCESS_KEY_ID
KIRA_CATALOG_AUTHOR_SECRETS_SECRET_ACCESS_KEY
KIRA_CATALOG_AUTHOR_SECRETS_SESSION_TOKEN
KIRA_CATALOG_AUTHOR_SIGN_ACCESS_KEY_ID
KIRA_CATALOG_AUTHOR_SIGN_SECRET_ACCESS_KEY
KIRA_CATALOG_AUTHOR_SIGN_SESSION_TOKEN
KIRA_CATALOG_AUTHOR_PRIMARY_READ_ACCESS_KEY_ID
KIRA_CATALOG_AUTHOR_PRIMARY_READ_SECRET_ACCESS_KEY
KIRA_CATALOG_AUTHOR_PRIMARY_READ_SESSION_TOKEN
KIRA_CATALOG_AUTHOR_REPLICA_READ_ACCESS_KEY_ID
KIRA_CATALOG_AUTHOR_REPLICA_READ_SECRET_ACCESS_KEY
KIRA_CATALOG_AUTHOR_REPLICA_READ_SESSION_TOKEN
```

These are four explicit AWS sessions for exact-version secret acquisition, signing, primary
namespace reads and replica namespace reads respectively. Missing/empty values are refused; there
is no profile, default credential chain or TARGET fallback. Access-key IDs, secret access keys and
tokens are bounded to 128, 256 and 16,384 printable non-space ASCII characters respectively. This
does not establish the sessions' provenance, permissions or isolation; those remain independently
qualified operator inputs.

## Fixed public acquisition manifest

The manifest is not an approval, signed protocol, publication input, process-death receipt or new
canonical byte authority. It maps only to the existing `CatalogGenesisFreezeRequestV1` acquisition
fields. All fields below are required, with exactly the shown names; there are no defaults or nulls.

| Object | Fields |
|---|---|
| root | `schemaVersion` (integer, exactly `1`), `database`, `files`, `trust`, `capacityPolicySha256` (string), `signing`, `releaseRoot` (path string) |
| `database` | `host` (string), `port` (integer), `name` (string), `password`, `publicTrustPem` (path string), `protectedTrustParent` (path string) |
| `database.password` | `keyId`, `resourceArn`, `versionId` (strings naming the immutable Secrets Manager authentication-password binding; **not password bytes**) |
| `files` | `approvedIntent`, `initialBundle`, `currentBundle`, `approvalInputs` (path strings) |
| `trust` | `rootPublicKeySpkiBase64`, `rootPublicKeySha256`, `rootKeyId`, `rootAlgorithmId`, `expectedEnvironment` (strings); `expectedCatalogLocations` (array); `minimumBundleVersion` (integer); `currentWriterGenerationIds`, `currentApproverIds` (string arrays); `limits` |
| each `trust.expectedCatalogLocations` element | Existing `OfflineCatalogLocationV1`: `role`, `bucket`, `accountId`, `region` (strings) |
| `trust.limits` | `maximumEnvelopeBytes`, `maximumManifestRecords`, `maximumGenerations`, `maximumEncodedBytes` (integers) |
| `signing` | `keyId`, `keyArn`, `algorithmId`, `publicKeySpkiBase64`, `publicKeySha256` (strings naming/pinning the existing immutable KMS signing key) |

The fixed DB login remains `kira_complaint_catalog_operator`. No username, TARGET P/D, credential
bytes or independent pin field is accepted. Public keys use canonical padded base64 of the
existing 422-byte SPKI; the existing trust/key/chain validators remain authoritative.
`capacityPolicySha256` is exactly 64 lowercase hex characters decoded to the existing independent
32-byte comparison digest, **not** raw deployment P or proof of TARGET validation.

The paths are absolute normalized default-filesystem paths. The manifest itself is read once
through one retained, no-follow, bounded descriptor; it must be a regular file with unchanged
file-key, size and modification time across the read. Its actual close precedes core acquisition.
Unknown acquisition/close is sticky, not permission to retry. This observation does not authenticate
the author or protect against a privileged filesystem adversary.

The document must be one UTF-8 JSON object, 1–65,536 bytes. Duplicate/unknown keys, malformed UTF-8,
floating-point numbers, required nulls and trailing documents are refused. Structural bounds are
12 levels, 4,096 tokens, 4,096-character strings, 64-character names and 19-character numbers.
`files` still names actual inputs acquired/verified by the core; it does not embed or pre-approve
them. `approvalInputs` is the existing exact canonical JSON approval list, not authentication of
human approval. The stable independently protected release root and all recovery/fencing
prerequisites in the core document remain required.

## Budget, retirement and observations

The dedicated worker calls genuine `CatalogGenesisFreezeV1.begin()` **before** manifest reading or
session acquisition. That original 60-second budget covers acquisition, existing freeze/resume,
providers/SQL/custody and cleanup; parsing or cleanup does not receive a fresh work budget. The
worker rechecks budget and interruption after cleanup, then calls `Runtime.halt` with a closed
status. An active-phase failure can leave original custody retained in FAILED/QUARANTINED state;
there is no in-process revival, custody repair or repeat close used as success evidence.

The supervisor retains only its exact original child, with a 75-second total allowance including
JVM launch, then force-stops that child if necessary and allows at most five seconds for death
observation. Those outer allowances do not extend core effect eligibility. A shutdown hook also
attempts retirement of that same retained child; it does not kill by name, PID search or process
group. The fixed child limits are `-Xms32m -Xmx256m -XX:MaxMetaspaceSize=128m
-XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError`.

Only an actually observed dead worker with one of the two cleaned core success statuses produces
stdout and shell exit zero:

```text
catalog-author SIGNED_AWAITING_RELEASE; historical-only
catalog-author FROZEN; historical-only
```

The first is still awaiting independent release. The second is a historical frozen observation,
not authority to select D, publish, finalize or activate. Neither output is persisted or accepted
as release input. Internally the worker uses status `10` for `SIGNED_AWAITING_RELEASE`; the public
supervisor maps it to zero only after confirmed retirement.

Failure output is one bounded stderr line, `catalog-author refused: STATUS`, with these shell codes:

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

Priority remains fatal > cancellation > interruption > ordinary failure > success, preserving the
first ordinary failure at equal priority. Interruption is restored even when a stronger signal
wins. Unconfirmed death replaces ordinary/success status with `RETIREMENT_UNCONFIRMED`; fatal,
cancellation or interruption retain their higher-priority status. **Every unconfirmed observation
is nonzero and adds `; retirement=UNCONFIRMED`.** Unknown native construction without a returned
child handle is not reported as known absence.

Observed owning-JVM retirement disposes retained process resources on that path; a return, halt,
destroy request or cooperative deadline alone does not. An unkillable native/OS condition, failed
process construction without a handle, supervisor SIGKILL or catastrophic supervisor/OS failure
cannot be called proven retirement. Keep fencing and recovery restrictions until actual absence
is independently established. Never automatically retry, select another root or infer new Sign
permission from an exit code, missing file, restored SQL or a dead process.

Focused tests exercise real retained custody, an unavailable cross-process kernel lock while the
owner remains alive after main return, production halt/timeout/interruption retirement, then fresh
lock and exact-leaf reacquisition only after death. They are software process-boundary tests, not
hardware durability, approval authentication, cloud policy or production qualification.
