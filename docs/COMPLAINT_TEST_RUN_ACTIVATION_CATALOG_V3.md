# First TEST-run activation catalog profile (schema3)

This is a **closed declaration/evidence profile**, not a run activator. It adds no
registration, credential, Sign/PUT, JDBC mutation, maintenance gate, projection,
custody or issuer authority. Existing schema1 genesis/rotation and schema2 logical
inventory formats and signature framing are unchanged. Their existing inventory
reader still rejects schema3.

## Envelope and manifest

Both envelope and manifest have `schemaVersion: 3`. The envelope has exactly
`schemaVersion`, `manifest`, `signatures`. Its signature array has exactly one
existing `{keyId,algorithmId,signatureBase64}` record. The existing catalog v1
domain-separated length frame and pinned `RSASSA_PSS_SHA_256` verification are used
unchanged; schema3 does not introduce a new signature algorithm or trust source.

The manifest has exactly these required fields (all are emitted):

```
schemaVersion, profile, canonicalizerId, operation, operationToken,
generation, previousEnvelopeSha256, initialTrustBundleEnvelopeSha256,
catalogWriterGenerationId, requiredSignerPolicy, creation, approvals,
oldestRestoreTimeEpochSecond, initialWriterRegistry, restoreInventory,
inventoryDelta, history, activationRecord
```

`profile` is `NEW_BACKEND_TEST_RUN_ACTIVATION_V1`, `canonicalizerId` is `kcj-1`,
and `operation` is `TEST_RUN_ACTIVATION`. All other existing header field names
retain their existing meanings. The required policy is
`{mode:"SINGLE",threshold:"ALL_MEMBERS",members:[activeSigner]}`. Two distinct
sorted approver IDs must be eligible in both the initial registry and the
independent current reader policy; the creator must be one of them. Creation
cannot precede the preceding generation's latest approval; each approval cannot
precede creation. The original registry and oldest-restore time remain unchanged.

The record has exactly:

```
activationRecord = {
  operationToken, generation, previousEnvelopeSha256,
  run: {
    testRunId, implementationSchema, desiredGeneration, configurationSha256,
    journalConfiguration, firstPublicationEpoch, installationLimit,
    terminalEncoding, accounting, noticeSeeds
  }
}
```

The record's first three fields equal the corresponding manifest fields. There
is no record-owned envelope, signature or history hash; such a field would make
the cumulative history circular and is rejected as unknown.

The run UUID is a canonical lowercase RFC4122-variant UUIDv4. Its actual positive
installation limit N is at most 2,048,000 (the existing terminal chunk ceiling).
`firstPublicationEpoch` is 1. `implementationSchema` and `desiredGeneration`
come from the retained TEST namespace process; neither is the envelope schema.
**Schema3 does not imply catalog generation3.** `terminalEncoding` is exactly
`{profile:"TEST_TERMINAL_V1",schemaVersion:1}`.

`configurationSha256` is the complete full-D SHA256 from the actual retained
`VersionBoundTestNamespaceProcessV1`, not the lower consumer-comparison digest,
a supplied hash or a LIVE conversion. `journalConfiguration` is an embedded
object containing the **unchanged existing flat TEST-J** fields:

```
kind, schemaVersion, canonicalizerId, profile, dataScopeKind, dataScopeId,
writer, journalLocation, ordinaryPrefix, sealTerminalPrefix, authorities,
activeRoutingKeyId, routingKeys, routingRetentionSeconds,
routingMinimumRotationIntervalSeconds, encryption, recovery, limits, protocol
```

Its kind/profile/scope/protocol constants, sorted immutable routing references,
prefixes, limits and nested fields must round-trip through the existing TEST-J
declaration validator to the identical canonical bytes. No JSON string, arbitrary
JSON, nested LIVE-J shape, or relabeling is accepted. Its TEST scope equals the run
UUID; writer/database/restore identities match the unchanged registry.

## Accounting declaration

The accounting object has exactly:

```
{
  profile: "TEST_TERMINAL_ACCOUNTING_V1",
  capacityEncodingVersion: 1,
  activationCatalogPrepareActual: [22 nonnegative Long values],
  activationProjectionActual: [22 nonnegative Long values],
  originalUnusedReserve: [22 nonnegative Long values]
}
```

Each vector uses the existing stored order:

```
1 app_installations      2 audit_rows             3 catalog_mutations
4 complaint_rows        5 import_artifacts       6 import_runs
7 import_staging        8 installation_ids       9 installation_receipts
10 journal_applied      11 journal_control       12 journal_publications
13 journal_retirements  14 legacy_records        15 moderation_grants
16 normal_receipts      17 recovery_reservations 18 resource_ids
19 scan_entries         20 scan_runs             21 storage_bytes
22 test_runs
```

All three vectors are recomputed component-for-component using
`TestTerminalAccountingPlanV1(N, R)`, where R is **this exact TEST-J's** positive
`maximumRetainedVersions`. Overflow rejects the declaration. The calculator's
N=0 arithmetic case is not a legal activation. The scan-pool PG row/index charge
is not J's LP32 framed staging-byte ceiling B. Arithmetic fit alone does not prove
deployment/provider limits, sufficient current DB capacity or a paid reservation.
The `Actual` field names describe the intended transaction accounting, not proof
that any transaction has happened here. Storage/accounting qualification remains
an independent gate.

Both the unsigned manifest and the **actual complete signed envelope** must fit
131,072 bytes, or the reader's stricter cap. This is the selected scoped catalog
price, not the general 8-MiB catalog profile. Assembly checks the exact fixed
signature-width envelope size without signing, but that estimate is not evidence:
the raw parser/authenticator independently caps the actual supplied envelope and
verifies its one fixed-width signature. No capacity is admitted by either helper.

## Notice definitions and deterministic IDs

Exactly two entries, sorted by key, are allowed. Each is exactly
`{noticeKey,definitionVersion,resourceId,definitionSha256}` with version1:

| Key | Default subject | Default body |
| --- | --- | --- |
| `complaints.notice.content-policy` | Adult content policy | References to adult / 18+ content aren't allowed here. Please keep submissions consistent with our community guidelines. |
| `complaints.notice.source-requirements` | New manga site requirements | Any new manga site must offer at least 200 titles, have no bot verification steps, and be worth the setup effort. Adding a site takes significant time and work. |

For each definition, hash the canonical UTF-8 array
`["kira-test-notice-id-v1",runUUID,noticeKey,definitionVersion]` with SHA256.
Take the first16 bytes; set byte6 to `(x & 15) | 64` and byte8 to
`(x & 63) | 128`; render the resulting UUID in canonical lowercase form.
`definitionSha256` is SHA256 of the exact canonical object
`{noticeKey,definitionVersion,defaultSubject,defaultBody}` above.

These are not raw source/ledger hashes and do not depend on translated prose.
The inert definition ledger is
`review/working/app-29-backend-notice-catalog-next-20260918-01/NOTICE-DEFINITION-LEDGER-20260918-01.json`
(SHA256 `6e2b0349856612ff6f2a3a7b23b489339df2fa5fa0991fc58c1522557116ea7a`).
No notice rows are inserted here. SYSTEM content fields remain required NULL;
these definition hashes do not authorize storing default prose in SYSTEM rows.

## History and supported prefixes

`restoreInventory` equals the authenticated predecessor's complete inventory,
including array order. `inventoryDelta` is exactly
`{addedSourceIds:[],addedCopyIds:[]}`.

The distinct history DTO has the same seven names:

```
expiredRestoreSources, testRunActivations, testRunTerminals,
installationManifests, epochSeals, retirementAuthorizations,
retirementCompletions
```

Every head except `testRunActivations` is
`{count:0,sha256:SHA256(kcj-1([]))}`. `testRunActivations` is
`{count:1,sha256:SHA256(kcj-1([activationRecord]))}`.
`GenesisEmptyHeadV1` is not reused for count1. No old zero-only semantics change.

The separate TEST chain entry accepts any prefix already supported by the old
reader: genesis; schema1 empty rotations before inventory starts; schema2 logical
source registration, copy addition and schema2 rotations. It uses those existing
reducers and their original bounds. At the activation, the actual folded rotation
state must be `Stable(active)` and the SINGLE member must equal **that active
signer**, not merely a current-bundle key or the original genesis signer. Awaiting
overlap activation, a stale original signer, unsupported transitions, modified
inventory/history, or a second/trailing generation fail closed. The current trust
bundle's head floor is checked on the complete chain.

This supports **one first TEST activation as the final generation after an
empty-history prefix**, not repeated TEST activations, terminal history or an
arbitrary nonempty-history append.

## Parsing, retained declarations and raw observations

- `OfflineCatalogTestRunActivationParser.parse` / `parseManifest` prove only
  bounded canonical syntax and internal declaration relationships. They cannot
  establish that a supplied full-D hash came from real owners or verify signatures.
- `CatalogTestRunActivationCanonicalV3.fromRetained(process, installationLimit)`
  has no hash-only constructor. It retains the actual cold process and snapshots
  full D, exact J, registry and selected signing policy. `run()` returns a defensive
  declaration. `assemble(...)` returns unsigned canonical bytes; predecessor
  checked-inventory metadata remains non-authoritative input.
- `OfflineCatalogInventoryChainVerifier.verifyTestRunActivationChain(..., expected)`
  requires that retained declaration and actually folds the raw prefix/signatures.
  It returns a separate defensive checked TEST chain, not an accepted head or run.
  Initial/current bundle bytes and reader policy must match retained owners;
  explicitly stricter inspection bounds/trust-version floors are permitted.
- `CatalogTestRunActivationReadbackV3.verify(..., expectedHead, expected)` is the
  only constructor of the TEST dual-copy observation. It uses
  `CatalogReadbackStream` to list **both** locations from null markers, GET exact
  listed versions, close each body before yielding, and compare exact version,
  length, bytes, compliance retention and replication status. Both complete chains
  must end at the declared byte-address. The expected head is not proof of local
  DB acceptance: an internal existing Accepted-shape adapter selects only the
  stream's exact-head/no-missing-replica behavior. No scoped snapshot bridge exists.
- Readback also checks the retained reader's remaining-retention floor and every
  signed creation's ten-calendar-year floor. It keeps at most the current raw row,
  listings and bounded inventory, not the aggregate chain. It does not manufacture
  common-head evidence from a missing replica, truncate an extra tail, or erase
  sanitized readback/close/cancellation failures behind the iterator boundary.

The schema3 parser rejects unknown/duplicate fields, trailing input, malformed
UTF-8, nulls, booleans/floats, noncanonical bytes and integer overflow. Limits are
depth16, object fields32,262144 tokens,4096 UTF-8 bytes/string,64 bytes/name and
19 integer characters. Array bounds are path-specific: three exact22 integer
vectors, exactly two notices/approvals, one SINGLE signature/member, at most four
TEST routing keys, and existing bounded inventory/initial-approver arrays. Every
manifest object (including its root and all nested TEST-J objects) counts toward
the at-most4096 manifest-record budget. Existing limits of65536 generations and
2GiB total encoded envelopes remain; no existing prefix-reader limit is widened
or globally lowered to the schema3 document cap.

## Deferred producers and verification

The separate, dormant `CatalogTestRunActivationV1` owner now has source paths for
PREPARE/signature custody, conditional PRIMARY publication, COMPLETE/pending and
`projectCompleted`. PROJECT starts a fresh original owner, verifies two genuinely
read and cleaned schema3 dual-copy rounds around locked capture/recheck, and spends
a private one-use grant. Its single transaction creates one run, one closed TEST
control, two resource IDs, two SYSTEM notices and four fixed SYSTEM audits; it pays
the exact projection charge and unused terminal reserve, marks the mutation and
clears its pending token. PREPARE is not charged again. Exact cold reconciliation
requires original custody, the full effect and exact capacity balances; it does not
rewrite domain/catalog/counter rows or manufacture an older missing outcome.

These are **unvalidated source paths**, not activation or release readiness. The
original schema1/2 readers (`CatalogFrozenManifestParser`, `CatalogLocalSnapshotVerifier`
and `JdbcCatalogSnapshotReader`) are not generalized into schema3 authority.
Namespace/current-state registration, credential issuance, routing, reopening and
runtime composition remain separate unfinished producers. Both maintenance and
creation gates stay closed after PROJECT. An ACTIVE row or historical diagnostic
receipt grants none of these capabilities and cannot repair an original uncertain
commit or cleanup failure. Legacy migration remains excluded; new-backend backup,
installation-credential recovery and journal recovery obligations remain.

Focused projection/recovery test source accompanies the implementation, but no
compilation, test, analyzer, runtime or provider PASS is asserted here. Independent
actual-diff review and the pending predecessor/projection checks remain required.
Genuine committed-UNKNOWN PROJECT transport loss is still an unauthored material
test gap; deferred-constraint rollback and known-COMMITTED callback failure are
not substitutes. Platform/provider qualification is not inferred from fixtures.
