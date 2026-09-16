# W04c/d — offline catalog genesis evidence v1

These are offline genesis evidence checkers. They do not accept a catalog head,
register a writer, establish membership, issue credentials, or enable complaint capability.
There are no database, network, KMS, S3, Spring, migration, or source-config signing operations.
W06 remains excluded. The original current-T0 API remains unchanged in scope; W04d adds a separate
raw-input historical-T0/current-Tn orchestration, not a standalone allow-stale verifier.

## Independent authority and input

`OfflineCatalogGenesisVerifier.verify` takes raw genesis-envelope bytes, raw current-initial-trust-bundle bytes, and the existing
independent `OfflineTrustBundlePolicy`. It runs the real current bundle verifier, including its
external minimum bundle version, unchanged. The authenticated bundle's minimum catalog-head
generation must be exactly 1. This checker never lowers or copies a caller's policy floor to make
an old bundle pass. No caller-constructed checked-claims object can substitute for those raw inputs.

The complete exact trust-bundle **envelope**, including its root signature, is hashed into genesis.
Two valid PSS root signatures over identical body bytes produce different envelope identities;
one cannot silently replace the T0 envelope pinned by the manifest.

## Historical T0 under independently current Tn

`OfflineTrustBundleVerifier.verifyBootstrapEvidence(genesisEnvelopeBytes, initialBundleBytes,
currentBundleBytes, policy)` returns `CheckedHistoricalGenesisEvidence`. All three documents are
raw inputs; no caller-supplied checked claims substitute for them. The trust verifier owns this
orchestration so its shared authentication primitive remains private. There is no public floor-free
historical verifier, policy copy with a lowered floor, or `allowStale` flag.

After bounding the genesis input, it verifies Tn through the existing current entry point, including
the independent minimum bundle version. It privately authenticates T0 under the **same independent**
root key/algorithm/fingerprint, environment, ordered catalog locations and fixed canonicalizer, with
all existing root-signature, closed-schema, canonical-byte and signer-key checks still mandatory.
Only T0's version-floor comparison is inapplicable to this historical role; direct current
`verify(T0, policy)` and the original current-T0 genesis API still reject an old T0.

The authenticated relationship must satisfy all of these rules:

- T0's minimum catalog-head generation is 1.
- `T0.version <= Tn.version` and `T0.issuedAtEpochSecond <= Tn.issuedAtEpochSecond`.
  Equal versions require identical **complete envelope bytes**, not equal bodies or independently
  valid signatures. These are bounded signed chronology claims, not a trusted wall clock.
- `bootstrapAuthority` is immutable **initial** authority across related releases. Every field,
  including the ordered catalog approvers and exact initial registry hash, must match. It does not
  describe or grant the current writer/approver roles after later chain operations.
- Tn retains the genesis-required signer with the exact key ID, algorithm ID, canonical SPKI and
  fingerprint authenticated in T0. A valid root signature cannot authorize retargeting that old key
  ID to different material. Unrelated public verifier entries may change; this is not a full-chain
  proof that all other historical keys have been retained.
- Every existing genesis check below is reapplied against the authenticated **exact T0**, including
  its envelope hash, registry semantics and actual catalog signature.

Relationship violations use `POLICY_MISMATCH`; malformed, stale-current, signature, key and exact
genesis/T0-binding failures keep the existing specific failure codes. The result contains defensive
genesis evidence plus both authenticated bundles' claims, canonical bytes and hashes. Tn's signed
minimum catalog-head generation is preserved for eventual complete-chain validation, not applied to
historical G1 or treated as a verified head. Tn with head floor42 can yield historical G1 evidence;
it cannot make head1 acceptable.

## Closed wire contract

Every field is required, with no defaults, nulls, extension maps, unknown fields, or floating-point
values. Envelope and manifest require schema version 1.

- Envelope: `schemaVersion`, `manifest`, ordered `signatures`.
- Each signature: `keyId`, `algorithmId`, `signatureBase64`, using the existing bounded key-ID,
  fixed RSA-3072/PSS algorithm and canonical Base64 contract.
- Manifest:
  - `schemaVersion`, `canonicalizerId: "kcj-1"`, `operation: "GENESIS"`;
  - `operationToken`, a canonical lowercase RFC-4122-variant UUIDv4 (syntax, not freshness proof);
  - `generation: 1`, `previousEnvelopeSha256`, exactly 64 ASCII zero characters;
  - `initialTrustBundleEnvelopeSha256`, SHA256 of the exact authenticated T0 envelope;
  - `catalogWriterGenerationId`, exactly the bundle's rooted bootstrap catalog-writer generation;
  - `requiredSignerPolicy`, the same closed `SINGLE` / `ALL_MEMBERS` / one-member policy as the
    root-bound initial registry, not an arbitrary allowed bundle key or a self-nominated signer;
  - `creation: {creatorId, createdAtEpochSecond}` and `approvals`;
  - `oldestRestoreTimeEpochSecond`, exactly `creation.createdAtEpochSecond` for the empty initial inventory;
  - `initialWriterRegistry`, the complete inline closed W04b initial registry;
  - `restoreInventory`, a closed empty-head record;
  - `history`, the closed initial history-head object described below.
- `approvals`: exactly two records `{approverId, approvedAtEpochSecond}`, with distinct IDs in
  ascending ASCII order. Both IDs must be in the rooted catalog approver set, and the creator must
  be one of these two people. Bundle-release approval claims do not grant a catalog role.
- All timestamps are integer UTC epoch seconds from 0 through 253402300799. The authenticated
  bundle issuance is no later than creation; creation is no later than either approval.
- Every empty-head record is exactly `{count: 0, sha256: SHA256(UTF8("[]"))}`. The history object
  has seven required named heads: `expiredRestoreSources`, `testRunActivations`, `testRunTerminals`,
  `installationManifests`, `epochSeals`, `retirementAuthorizations`, `retirementCompletions`.
  Separating terminal and installation-manifest heads makes both evidence categories explicit.
  These are empty genesis commitments, not general history DTOs or omitted/nonempty record lists.

The inline registry is canonically encoded with its closed W04b serializer. Its exact SHA256 must
match the root-signed bootstrap registry digest, and every W04b identity, signer, approver, role,
prefix, configuration, and initial-state check is reapplied. A shared Unit-returning validation-only
helpers grant no authority; the standalone registry and both genesis entry points root-verify their
own raw bundle input. Neither genesis nor its registry may nominate a new writer, signer, or approver
merely by naming one. The registry still embeds no bundle or genesis digest, avoiding a hash cycle.

## Signatures and exact bytes

The ordered signature tuple set must equal the complete ordered required signer set: exactly one
matching key/algorithm member. Missing, extra, duplicate, or substituted members are rejected.
The public key is resolved only from the already root-authenticated bundle signers. No inline key,
provider-name fallback, overlap rotation, or trust-root substitution is supported.

For `F(x) = uint32-big-endian(byteLength(x)) || x`, a catalog signature covers:

```
F(UTF8("kira.complaints.catalog-generation.v1")) ||
F(UTF8("kcj-1")) || F(UTF8(keyId)) || F(UTF8("RSASSA_PSS_SHA_256")) ||
F(SHA256(canonicalManifestBytes))
```

The final component is the 32 raw digest bytes, not the manifest or hex string. This domain is
distinct from the offline-bundle domain. Existing fixed RSA-3072/PSS SHA256, MGF1-SHA256, salt32,
trailer1 public-key/signature checking is reused without changing the original crypto or fixtures.

The current shared strict parser accepts only exact `kcj-1` bytes and applies the existing 128 KiB,
depth16, tokens4096, array16, object-fields32, UTF-8/name/string and integer-lexeme bounds before
closed decoding. A numeric string that the typed decoder could read still fails the exact canonical
byte comparison; it is not admitted through coercion. This deliberately narrow bootstrap profile
is **not** the complete 8 MiB catalog reader or a whole-chain capacity qualification.

Results contain defensive manifest claims, exact canonical manifest/envelope bytes, and their
SHA256 hashes only. They are neither an accepted head nor a capability. Signed identity/time claims
do not prove two-person ceremony, a trusted clock, fresh IDs, empty namespaces, or never-reset state.

## Still separate

Production keys/provider mappings, offline approval evidence, independent empty two-bucket/no-reset
proof, conditional puts, exact-version dual-copy retention, accepted-head projection, credential
eligibility, full history/chain folding, rotation and disaster handoff
remain external or unimplemented. A checked genesis candidate is not permission to reset a catalog,
does not complete W04, and does not authorize a deployment or W06 work.

The current independent policy has no pinned genesis hash. Neither this historical relationship
check nor an authentic root signature proves unique genesis, a never-reused namespace, an empty
two-bucket namespace, or absence of a reset. Independently supplied recovery-policy provenance and
version advancement, complete contiguous chains/history, and both all-version inventories remain
necessary external obligations; these evidence objects cannot replace them.

The separate [W04e genesis/rotation-chain reader](COMPLAINT_OFFLINE_CATALOG_ROTATION_CHAIN_V1.md)
uses this historical raw-input API internally, adds explicit independent current writer/approver
policy and bounded contiguous rotation checking, and still grants no accepted-head authority.
