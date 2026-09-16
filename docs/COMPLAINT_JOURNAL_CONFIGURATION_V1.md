# Initial-LIVE journal configuration V1 (J)

`ComplaintJournalConfigurationV1` produces the complete **declared configuration
for this supported initial-LIVE profile**, not a global desired configuration,
journal implementation, accepted registration, secret acquisition, or provider
proof. `of(InitialLiveJournalDeclarationV1)` is its only construction route.
All deployment inputs are required; no environment defaults or observations
are substituted. There is no raw-JSON or caller-supplied J-digest constructor.

## Scope and normative basis

The source contract implements the declaration portion of frozen App29 V6,
subject to the 2026-09-08 clean-start owner amendment (lines 18–33). There
is no obsolete legacy-platform deployment binding or release prerequisite:

| V6 lines | Requirement represented here |
|---|---|
| 2298–2326 | Independently administered journal; isolated writers/recovery; encrypted queue/DLQ; ordinary retention floor and restore margin |
| 2352–2357 | Writer identity, exact namespace/prefix, authority-policy and routing/encryption configuration commitment |
| 2599–2651 | Dedicated retained routing keys; versioned domain framing; fresh AES-256-GCM data key wrapped by KMS; authenticated headers/context; envelope/decoder bounds |
| 2661–2670 | Conditional put, checksum, COMPLIANCE retention, exact-key one-version/no-marker readback |
| 1438–1447, 2821–2827 | Disjoint bounded publication lanes, explicit deadlines, scan cadence and configured failure thresholds |
| 2910–2922 | Declared retained-version/staging ceilings; 5-second publication, 10-second rotation, 10-minute scan maxima |

This profile is commercial AWS, one region/account, one initial event-writer
generation and the LIVE all-zero namespace. Initial `OPEN`, first epoch 1 and
empty seal history are **claims**, not observations or evidence of freshness.
W06/import is excluded. Sizing cannot use excluded legacy-import volume as an
implicit input; callers provide reviewed nonlegacy retained-version/staging
limits explicitly.

## Exact document

Every field is required and serialized, including false values and zero
`maximumRestoreAgeSeconds`. The root fields are:

| Field | Type/value |
|---|---|
| `kind` | `"kira-complaint-journal-configuration"` |
| `schemaVersion`, `canonicalizerId`, `profile` | `1`, `"kcj-1"`, `"INITIAL_LIVE"` |
| `scope` | `{kind:"LIVE",id:"00000000-0000-0000-0000-000000000000"}` |
| `writer` | `{databaseIdentity,restoreIdentity,generationId}`; canonical lowercase UUIDv4 strings |
| `journalLocation` | `{bucket,accountId,region}` using existing bounded bootstrap grammar |
| `ordinaryPrefix`, `sealTerminalPrefix` | Derived below, never supplied paths |
| `authorities` | `{ordinary,sealTerminal,recovery,isolation}` |
| `routing` | `{activeKeyId,keys,retentionSeconds,minimumRotationIntervalSeconds}` |
| `encryption` | Exact journal KMS binding below |
| `recovery` | `{queue,deadLetterQueue}` |
| `limits` | `{retention,deadlines,capacity,decoder}` |
| `protocol` | Fixed versioned selections and required invariants below |

For event generation `G`, prefixes are exactly:

```
complaints/journal/v1/G/live/00000000-0000-0000-0000-000000000000/ordinary/
complaints/journal/v1/G/live/00000000-0000-0000-0000-000000000000/seal-terminal/
```

Each authority is existing `{roleId,credentialId,policy}`. Policy is
`{policyId,version,sha256}`: bounded nonsecret reference ID, positive signed
64-bit version and lowercase SHA-256. The three roles, credential IDs and
role-policy IDs are distinct, with no role/credential aliases.

`isolation` contains required nonsecret `deploymentPrincipalId`,
`bucketAdministratorId`, `kmsAdministratorId`, `journalFailureDomainId`,
`applicationDatabaseFailureDomainId`, `backupFailureDomainId`, and
`administrationPolicy`. Journal domain must differ from each application/
backup domain. Administrators cannot
alias deployment or journal runtime roles; administrative policy cannot be
a runtime role policy. The bucket and KMS administrator may coincide.
These are **declared distinctions only**: names and policy hashes do not
prove real provider principals, permissions, ownership or failure isolation.

Each routing key is `{keyId,resourceArn,versionId}`. The API reuses
`security.ImmutableSecretVersion`: exact Secrets Manager resource ARN and
canonical UUIDv4 VersionId, never `AWSCURRENT`, stages, secret values or
ephemeral credentials. There are 1–4 unique logical IDs and unique exact
resource/version pairs; `activeKeyId` selects exactly one. Keys sort by
logical ID before hashing. This profile requires the journal account/region.

Each KMS binding is `{keyId,keyArn,policy}`. `keyArn` must be exactly
`arn:aws:kms:REGION:ACCOUNT:key/UUIDv4`, in the journal account/region.
It identifies a nonexportable KMS key, **not an immutable version of its
backing material**. KMS aliases, multi-Region `mrk-` keys and other partitions
are unsupported. KMS policy and key identity are pinned; automatic backing
material history, decryptability and retirement constraints remain provider
evidence. A repeated KMS binding must agree in all fields; neither one
logical ID naming different keys nor one ARN with conflicting aliases is
accepted. Routing and KMS logical IDs cannot overlap.

Each queue is `{arn,policy,encryption}`. Queue and DLQ ARNs must differ and
have exact `arn:aws:sqs:REGION:ACCOUNT:NAME` syntax, with bounded standard
queue names, in the journal account/region. FIFO queues and cross-account/
cross-region queue configurations are outside this profile. Both queues
have explicit resource-policy references and KMS bindings. No notification,
DLQ redrive, KMS permission or provider encryption setting is verified here.

## Retention, deadlines and bounds

All values below are explicit integer inputs, not inferred current state.

- `retention` has `ordinaryRetentionSeconds >= 400 * 86400` and
  `0 <= maximumRestoreAgeSeconds <= ordinaryRetentionSeconds - 31 * 86400`.
  The latter is the declared maximum supported restore age, not the actual
  oldest backup's age or a restore-floor observation. Ordinary LIVE events
  and their seals use this policy.
- Routing `retentionSeconds` is at least ordinary retention. Declared
  `minimumRotationIntervalSeconds >= ceil(retentionSeconds / 3)` prevents a
  declared regular rotation schedule requiring more than one current plus
  three previous keys. Arithmetic avoids overflow. Neither this inequality
  nor a listed key proves actual object coverage or permits key retirement.
- `deadlines` has required positive `s3CallMillis`, `kmsCallMillis`,
  `queueCallMillis`, `publicationAttemptMillis`, `epochRotationMillis`,
  `epochSealMillis`, `scanMillis`, `scanCadenceMillis`, `queueUnhealthyMillis`,
  `checkpointMaxAgeMillis`. Publication is at most 5000 ms; each S3/KMS call
  is at most publication; rotation at most 10000 ms; scan at most 600000 ms;
  publication <= seal <= scan, rotation <= scan <= cadence <= 900000 ms;
  cadence <= checkpoint age <= 1200000 ms; queue call <= unhealthy threshold
  <= 30000 ms. A shared total deadline still must be enforced by runtime;
  individual call caps do not prove a sequence fits.
- `capacity` has positive `maximumRetainedVersions` and
  `maximumScanStagingBytes`; `maximumPublicationLanes >= 2` and
  `1 <= routinePublicationLanes < maximumPublicationLanes` retain privacy
  publication capacity. These are remote lanes, not the separate four-
  connection deletion pool or database-transaction deadlines.
- `decoder` requires `maximumEnvelopeBytes <= 98304`,
  `maximumPlaintextBytes <= 65536`, plaintext < envelope;
  `maximumJsonDepth <= 32`, `maximumObjectFields <= 64`,
  `maximumJsonTokens` and `maximumStringUtf8Bytes` <= plaintext bytes, and
  `maximumWrappedKeyBytes` <= envelope bytes. All are positive. Depth 32 and
  object-fields 64 are explicit conservative supported-profile caps, not
  numeric limits claimed to appear in V6. Limits may be stricter; their
  usability and actual parser enforcement are not certified by this type.

## Fixed protocol selection

`protocol` contains required `payloadSchemaVersion:1`,
`envelopeSchemaVersion:1`, `routingAlgorithm:"HMAC-SHA-256"`, and:

- `framing`: `version:1`, `encoding:"LP32BE-UTF8"` and distinct
  `routingDomain`, `eventIdDomain`, `epochSealDomain`, `aadDomain`,
  `kmsContextDomain` values `kira-complaint-journal-<purpose>-v1`, where
  purposes are respectively `routing`, `event-id`, `epoch-seal`, `aad`,
  `kms-context`. LP32BE frames each UTF-8 field with its unsigned four-byte
  big-endian byte length, including the first domain field; integer fields
  use canonical decimal and UUID fields canonical lowercase spelling.
  No arbitrary labels or caller-selected framing versions are accepted.
- `encryption`: `algorithm:"AES-256-GCM"`,
  `dataKeyMode:"FRESH_PER_OBJECT_KMS_WRAPPED"`, `dataKeyBytes:32`,
  `nonceBytes:12`, `tagBytes:16`, `envelopeHeadersAuthenticated:true`.
  V6 requires authenticated AAD and KMS context binding bucket/key, object
  kind/schema, writer, namespace/scope, epoch, routing-key ID and event/root
  identifier. This descriptor selects that contract; it does not implement
  event serialization, frame producers, KMS operations or decryption.
- `storage`: `retentionMode:"COMPLIANCE"`,
  `putCondition:"If-None-Match:*"`, `checksumAlgorithm:"SHA-256"`,
  `endToEndChecksumRequired:true`, `requiredObjectVersions:1`,
  `exactKeyReadbackMaximumEntries:2`, `versioningRequired:true`,
  `deleteMarkersAllowed:false`, `nativeExpirationAllowed:false`,
  `runtimeListingScope:"EXACT_COMMITTED_KEY"`,
  `recoveryListingScope:"DECLARED_LIVE_RANGE_ALL_VERSIONS"`.
  These are required behavior selections, never observed provider flags.

## Bytes, factory and remaining authority

Existing backend `CanonicalJson` kcj-1 recursively sorts keys. Required
constructor fields have no defaults; arrays preserve the explicitly sorted
routing order. Output is compact UTF-8 with no trailing newline. `sha256`
and defensive `digestBytes()` are SHA-256 over those exact bytes;
`canonicalBytes()` and `declaration()` return defensive copies of mutable
containers. No secret resolver is called. Top-level diagnostics are redacted.

The independent synthetic golden fixture `initial-live.json` is **5046
bytes**, SHA-256
`e1562c3f07c5983ee40f2212c601782dc3bfdc003ee1bf7f7c01b10951ad64f0`.
It is fixture data, not a recommended deployment configuration.

`InitialLiveRangeFactory.fromJournalConfiguration(J)` derives the existing
`InitialLiveRangeV1` location, prefixes, authorities, active routing/key IDs
and configuration digest from the same object; initial scope/state/epoch/
empty seal are fixed. Existing wire fields and raw verifier APIs are unchanged.
It does not produce or authenticate the enclosing writer registry; its
database/restore/writer bindings still must agree with independently checked
deployment and signed-registry evidence.

No accepted catalog/trust envelope hash, P, desired D/generation, mutable
checkpoint/epoch/lease, current health, STS token/session expiry or observed
oldest object enters J. This prevents catalog/desired hash cycles. Existing
wire fields and golden bytes remain unchanged. TEST P-versus-run/desired
equalities remain separate work.

## Dormant G1 declaration binding

`CatalogGenesisInitialLiveBinding.fromDeclarations(Configured, J, P)` accepts
only initial-LIVE desired declarations with matching schema and positive
desired generation, database/restore identities and J writer identity. It
retains defensive copies of opaque D and the digest computed from actual P.
Before any completion/projection phase, genuine `GenesisReadback` must also
match the complete J-derived initial event writer and LIVE range, including
every location, prefix, authority, key ID, J digest, epoch and empty seal.
The raw verifier remains the sole readback-handoff producer; matching a raw
registry declaration alone cannot authorize persistence.

`resumeGenesis`, `completeGenesis` and `projectGenesis` consume this binding.
Their fixed control SQL compares expected schema/generation and D against
D, never the signed J against D. A NULL initial desired hash remains allowed
under the same closed gates and declared schema/generation; G1 never writes
D, opens a gate, supplies a checkpoint or grants activation/restore authority.

D is still an independently supplied opaque digest. This binding does not
define or compute the complete D document, verify that D commits to J/P, or
establish deployment provenance. That producer/acquisition contract remains
required before process-computed configuration or activation can be claimed.
Focused fixtures use explicitly synthetic D distinct from genuinely computed
J/P over synthetic declarations, not a fabricated complete-D producer.

Unsupported work includes TEST namespaces and their ten-year terminal
retention, cross-generation restore/reader configuration, key-ring mutation
or retirement authority, KMS-key replacement migration, writer succession,
activation and W06. Constructing a different descriptor is not permission
to change any of those states. Real immutable secret acquisition, policy/
failure-domain verification, actual oldest-restorable/object coverage,
conditional/versioned retention readback and benchmarked declared maxima
remain required external/runtime evidence. No database, provider, controller,
bean, environment loader or admission mode is changed by this slice.
