# Complaint effective configuration D, version 1

**Source draft, NOT_TESTED.** One complete configuration commitment for the supported
`INITIAL_LIVE_MEMORY_SINGLE_INSTANCE` profile, not current capability or activation.
No bean/route is registered. TEST/Redis/multiple instances have no conversion or fallback.
One declared instance is not evidence of installed topology. Rebuilding the memory owner
loses its counters and is not a safe rotation or rollout procedure.

## Producer and custody

`VersionBoundComplaintProcessConfiguration.fromRetained(consumers, pools,
implementationSchema, desiredGeneration, databaseIdentity, restoreIdentity)` accepts the
actual acquired consumer owner and actual completed ordinary/deletion/catalog-coordinator
pool composition. It retains those exact instances. No caller D, arbitrary map, standalone
descriptor list, provider observation, callback or readiness boolean is accepted.

The constructor checks schema 1, positive generation, canonical UUIDv4 DB/restore identity,
the identical routing/J owner, J's matching DB/restore identity and INITIAL_LIVE namespace,
memory/one-instance admission, actual acquired user JWT owner and shared create/delete-all
member bounds. Every pool must retain the same actual DATABASE/AUTHENTICATION_PASSWORD
binding; its immutable version cannot also nominate a consumer HMAC family. Complete HMAC
family/reference separation remains enforced by the retained consumer factory.

`canonicalBytes()` and `configurationHashBytes()` return defensive **historical configuration
snapshots**. `desiredSettings()` first rechecks and derives LIVE settings using this D, never
a supplied hash. The resulting domain value by itself is not proof of this process owner.
`requireUnchangedConfiguration()` rechecks fixed graph coherence and the original three
pool descriptors. Their own concrete custody revalidates actual mutable Hikari/lower-source
configuration. It does not serialize/hash again, sample DB state, call providers, check out
a connection or require connection-free context; it may run in an owned phase outside the
short lifecycle ownership monitor. Failures remain bounded; no submitted value is printed.
The factory itself requires connection-free entry.

## Bytes and complete field inventory

Kind `kira-complaint-effective-configuration`, `schemaVersion:1`, `canonicalizerId:"kcj-1"`.
Existing kcj-1 recursively sorts object names by Unicode code point. UTF-8, compact JSON,
no BOM/newline, SHA-256 of those exact bytes. Every described field is required, including
false/zero/empty lists; there are no nullable/omitted default fields or decimal floats.
Time values ending in `Nanos`, `Millis` or `Seconds` use that exact unit. User JWT durations
use `{seconds,nanoAdjustment}` (0–999999999 adjustment), preserving submillisecond values.
UUIDs use lowercase canonical text; hashes are lowercase 64-character hex.

| Object | Required contents and actual producer |
|---|---|
| Root | `kind`, `schemaVersion`, `canonicalizerId`, fixed `profile`, `identity`, `capacityPolicy`, `journalConfiguration`, `consumers`, `persistence`. |
| `identity` | `mode:"LIVE"`, `implementationSchema`, `desiredGeneration`, `scopeKind:"LIVE"`, zero `scopeId`, `databaseIdentity`, `restoreIdentity`, J's `writerGeneration`. Independently loaded intent is checked against actual J, never read from control. |
| P/J commitments | Each has `kind`, `schemaVersion`, `canonicalizerId`, `sha256`. Identity fields are read from the actual canonical owner document; SHA-256 is computed over its entire canonical bytes. P binds all 22 hard/creation counters, accounting version and daily enrollment limit. J binds its complete writer/namespace, roles/policies/isolation, routing references/rotation, KMS/queues, retention/deadline/capacity/decoder and frozen protocol inventory. Their existing contracts remain unchanged; neither commitment accepts a supplied digest. |
| `consumers.secretBindings` | Every retained USER_ADMIN_JWT, INSTALLATION_JWT, COMPLAINT_ADMISSION, COMPLAINT_CURSOR and COMPLAINT_JOURNAL_ROUTING binding, sorted by family then logical ID. Each has `family`, `purpose`, `logicalKeyId`, full `resourceArn`, exact `versionId`. No key material/fingerprint is committed. |
| `consumers.userJwt` | Protocol 1/HS256, actual singleton `activeKeyId` and `verificationKeyIds`, actual owner's `issuer`, `audience`, `accessTokenTtl`, `clockSkew`. The four scalar accessors are from the same captured settings checked by the real signer/decoder, not another properties object. |
| `consumers.installationJwt` | Protocol 1/HS256, actual ring active/sorted verifier IDs; actual codec `type`, `issuer`, `audience`, `role`, `ttlSeconds`, `clockSkewSeconds`, `maximumCompactBytes`; fixed parser bounds `maximumNestingDepth:3`, `maximumStringCharacters:4096`, `maximumNumberCharacters:20`, `signatureBytes:32`. |
| `consumers.admission` | Protocol 1, actual `coordinationMode`, `declaredInstances`, current ID and zero/one-element `previousKeyIds`; `rotationAllowed:false`, `retirementAllowed:false`, `previousRetentionNanos`, `admissionLifetimeNanos`, actual `concurrentLimit`; the subobjects below. |
| `admission.trustedIp` | Protocol 1; actual `trustForwardedHeaders` and sorted `trustedProxies` (duplicates preserved); fixed `maximumForwardedHeaderBytes:1024`, `selection:"RIGHTMOST_UNTRUSTED"`, header precedence X-Forwarded-For then Forwarded, `invalidChain:"REMOTE_ADDRESS"`. Address/CIDR strings are not normalized differently from the real owner. |
| `admission.ingress` | Actual bucket limit, derived event limit = bucket limit × actual per-minute limit, actual prune batch; actual fixed ingress window/idle durations. `admission.ingressPerMinute` retains the exact rate separately. |
| `admission.semantics` | Actual shared bucket/event/prune limits, hourly window/idle durations and fixed daily delete-all window. Daily and hourly dimensions share this one physical budget. |
| `admission.ownerReads` | The actual semantic bucket/event/prune limits used by the separate read store; fixed minute window/idle and `actorPerMinute:120`. |
| `admission.quotas` | Fixed bootstrap IP/hour120, session actor/hour30 and IP/hour100, enrollment IP/hour10; actual enrollment global/hour and create global/hour (**outside P**); create actor/hour10, delete-all actor/day5 and IP/hour20. Enrollment/create/delete-all are explicitly enabled by this actual retained graph. |
| `admission.mutationMembers` | `sharedCreateDeleteAll:true`, actual member limit/prune batch (checked equal across both retained policies), fixed retention duration25h. P remains the independent actual locked-capacity policy, not these local quotas. |
| `consumers.ownerCursor` | Actual active/sorted verifier IDs and actual codec protocol: envelope/selection/MAC domains, actor kind, route/direction, TTL/future skew, page/cursor/payload/signature limits. |
| `persistence` | `profileVersion:1`, exact `pools` order ORDINARY, DELETION, CATALOG_COORDINATOR. No separate persistence hash/fragment protocol. |
| Each pool | `role`; its actual full `authenticationPassword` descriptor using the five secret-binding fields; actual public trust SHA-256/byte count/certificate count; actual `hikari`; exact retained `openings` in construction order. |
| Pool `hikari` | maximum size/minimum idle; connection/validation/initialization-failure/idle/max-lifetime/keepalive/leak-detection milliseconds; autoCommit/readOnly/isolateInternalQueries booleans. These are rechecked against the actual configured Hikari instance. |
| Each opening | Actual `recipe`, `evidencePolicy`, `transportRoute`, `driverUrl`, `loginBudgetMillis`, full public driver-property name/string-value object. Ordinary original-provider/weak/strong recipes stay distinct; deletion and coordinator retain their actual strict recipes. Password/sslrootcert are excluded by the real projection; public trust content replaces the private file path. |

Protocol/profile version 1 also freezes existing structural semantics, not caller knobs:
JWT claims/framing and strict parsing; admission HMAC-purpose framing, atomic charging,
bounded fail-closed pruning/OOM behavior and no live-member eviction/TTL renewal; fixed
read/create/delete-all rates above; numeric-IP/header selection; persistence's one-root
private lower source, owned actor factories and closed optional Hikari inputs (no custom
SQL/isolation/catalog/schema/JNDI/URL/credential overrides, MBeans or suspension). A future
behavior change must update its version/inventory and golden tests, not silently reuse D.
The separately identified immutable runtime image remains part of rollout evidence.

## Boundaries and evidence

Do not hash accepted catalog/checkpoint/projection/activation/restore/image observations
back into D: they separately bind and verify D, avoiding self-referential catalog cycles.
No private trust filename, generated pool name, secret bytes/password/HMAC fingerprint,
PID, clock reading, health flag, quota consumption or temporary credential enters D.
Public configuration/ARNs may still be operationally sensitive; no logging is added.

Equal D is necessary, never sufficient. Every eventual handling process must compare its
own D to current control at capability decisions, including exact completed replay; stale D
must return503. Consumer SQL/current-authority work is separate and must join this producer
before acceptance. Positive current catalog/projection/checkpoint/retention/recovery,
TEST activation/profile, complaint Redis and installed topology/rollout proof remain open.
W06 legacy import/export remains excluded; new-backend-data recovery remains required.

The independent stdlib fixture generator in `fixtures/complaint-effective-configuration-v1/`
constructs expected bytes without invoking production Kotlin or providers. A fixture hash
is not test execution evidence. New tests use actual acquired consumers and all three real
cold pools; no synthetic descriptor/result can select a successful producer outcome.
