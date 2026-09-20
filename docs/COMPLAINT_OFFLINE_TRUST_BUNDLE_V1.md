# W04 — offline complaint-catalog trust bundle and initial registry v1

This is a local parser/cryptographic verifier, not catalog acceptance, authenticated run membership,
live writer/approver authorization, or permission to enable complaints. It has no Spring registration,
network, S3, KMS, database, feature-enable, or source-config signing dependency. W06 migration remains
excluded. Production root/key provisioning and two-person release evidence remain external.

## Closed wire contract

Both envelope and body require `schemaVersion: 1`; every field below is required, with no defaults,
nulls, extension maps, floating-point values, or unknown fields.

- Envelope: `schemaVersion`, `body`, `signature`.
- Signature: `keyId`, `algorithmId`, `signatureBase64`.
- Body: `schemaVersion`, positive monotonic `version`, `environment`, `catalogLocations`,
  `canonicalizerId` (`kcj-1`), positive `minimumCatalogHeadGeneration`, `issuedAtEpochSecond`,
  `approvals`, `signers`, `bootstrapAuthority`.
- `catalogLocations`: exactly two ordered records, `PRIMARY` then `REPLICA`, each with `role`,
  `bucket`, `accountId`, `region`. Buckets, 12-digit accounts, and regions must be distinct and
  exactly match the out-of-band policy. These are identity claims, not proof that storage exists.
- `approvals`: exactly two ordered records with distinct `approverId`, each with `approvedAtEpochSecond`.
  Times are integer UTC epoch seconds, 0 through 253402300799, with approval no later than issuance.
  These signed claims do **not** establish that two independently authorized people approved release.
- `signers`: 1–16 ordered records with `keyId`, `algorithmId`, `publicKeySpkiBase64`,
  `publicKeySha256`. IDs and public-key fingerprints are unique. The offline root cannot be a
  catalog signer. Retained old public keys are not removed because a newer signer becomes active.
- `bootstrapAuthority`: `catalogWriterGenerationId`, `requiredSigner` (`keyId`, `algorithmId`),
  `initialWriterRegistrySha256`, and ordered `catalogApproverIds` (2–16 distinct identities using
  the approver grammar below). The writer ID is a canonical lowercase RFC-4122 variant UUIDv4;
  the required signer must exactly reference one authenticated bundle signer. The registry hash
  is SHA256 of the exact canonical **typed initial registry** described below. Release `approvals`
  do not supply these roles. This is immutable **initial** authority across related releases, never
  a replacement grant for the current writer or approvers. W04d historical evidence compares it
  exactly between initial T0 and current Tn, retaining the exact genesis key identity and material.
  This is one unreleased V1 evolution: old key-only bundles are rejected,
  not given default authority or routed through a compatibility reader.

Environment is a 1–64-character lowercase ASCII slug; key IDs are 1–64 ASCII letters/digits/`._-`.
Approver IDs are 1–256 printable non-space ASCII identity characters. Bucket/region names are
bounded ASCII identifiers (63/64 characters), not URLs. Hashes are 64 lowercase hex characters.
Base64 is canonical RFC 4648 basic Base64, including padding when necessary, with no whitespace.
Public keys are canonical DER SubjectPublicKeyInfo using rsaEncryption OID and explicit NULL
parameters, exactly RSA-3072 with exponent 65537. Signature length is exactly 384 bytes.

## Independent policy and signatures

The caller must supply the root SPKI, its SHA-256 fingerprint, root key ID and algorithm, expected
environment and ordered locations, and a positive minimum **bundle version** from independent
deployment/recovery material. No production defaults exist. The verifier checks the pin, binding,
and version floor; it does not persist or advance that floor. A minimum **catalog head** generation
is signed data for a future complete-chain verifier, not grounds to reject historical generations
individually or to accept any head. No root rotation is implemented.

The only implemented algorithm ID is `RSASSA_PSS_SHA_256`: RSA-3072, SHA-256, MGF1-SHA-256,
32-byte salt, trailer field 1. The production suite/material still require independent provisioning
and approval. Source-config Ed25519 keys/serialization are not reused.

For `F(x) = uint32-big-endian(byteLength(x)) || x`, the exact signed frame is:

```
F(UTF8("kira.complaints.offline-trust-bundle.v1")) ||
F(UTF8("kcj-1")) || F(UTF8(rootKeyId)) || F(UTF8("RSASSA_PSS_SHA_256")) ||
F(SHA256(canonicalBodyBytes))
```

The final component is the **32 raw digest bytes**, not the body or hex text. JCA SHA-256/PSS
signs/verifies this frame; a future KMS DIGEST signer would receive SHA256(frame), not hash that
digest again. Domain, algorithm and key IDs are bound; wire values cannot select arbitrary JCA
algorithms. Existing `CanonicalJson` and `Sha256` remain unchanged.

## Bounds and result

Reject input above 128 KiB before copying/decoding. Strict UTF-8 and a streaming structural pass
reject duplicates, trailing JSON, unknown DTO fields, nulls and floats. Additional pre-decode
limits: nesting 16, tokens 4096, array elements 16, object fields 32, field-name UTF-8 bytes 64,
string UTF-8 bytes 4096, integer lexeme length 19. RSA SPKIs are exactly 422 bytes. Both submitted
envelope bytes and its nested body must correspond to the unchanged `kcj-1` closed-DTO encoding;
noncanonical whitespace, number spelling or escaping is rejected rather than normalized silently.

The result contains defensive copies of the signed claims, exact canonical body/envelope bytes,
and their hashes. It grants no accepted-head, genesis, membership or capability authority. There is
no chain/rotation/history fold, dual-location evidence, activation/projection, offline approval
validation, or deployment wiring in W04a/b. The separate narrow
[genesis evidence contract](COMPLAINT_OFFLINE_CATALOG_GENESIS_V1.md) adds the W04c current-T0
checker and W04d historical-T0/current-Tn orchestration. Neither accepts a catalog head; the latter
always authenticates a current independently floored release and exposes no standalone floor-free
historical-bundle API. The ordinary current bundle verifier's floor remains unchanged.
The [W04e rotation-chain reader](COMPLAINT_OFFLINE_CATALOG_ROTATION_CHAIN_V1.md) composes a separate
independent current writer/approver policy without changing this closed bundle schema or treating
initial bootstrap authority as a current grant.

Tests use fresh synthetic in-memory keys and committed **public-only** golden fixture material.
No host signing material is consumed. Source/tests are authored before any separately admitted
build or test run; their presence alone is not execution evidence. The original public-only golden
files remain byte-for-byte unchanged: they prove the unchanged low-level framing/crypto contract,
but their old key-only envelope no longer passes current closed-schema verification.

## Closed initial writer registry

The registry is a second typed document, not a JSON extension or a self-authorizing manifest. All
fields are required. It uses the same 128 KiB byte, depth, token, field and canonical-input bounds.
Checking takes raw registry bytes, raw current bundle bytes, and the independent policy, and runs
the real bundle verifier internally before binding the registry hash. It does not accept a caller-
constructed "verified authority" object or a flag asserting that a signature was checked.

- Registry: `schemaVersion: 1`, `canonicalizerId: "kcj-1"`, `databaseIdentity`, `restoreIdentity`,
  one `catalogWriter`, and one `eventWriter`. All four database/restore/writer identity fields use
  canonical lowercase UUIDv4 grammar: `xxxxxxxx-xxxx-4xxx-[89ab]xxx-xxxxxxxxxxxx`. UUID syntax and
  distinct writer IDs are **not proof of freshness, never-reuse, or current ACTIVE authority**.
- `catalogWriter`: `generationId`, `registration: "ACTIVE"`, `requiredSignerPolicy`,
  `catalogApproverIds`, `putAuthority`, `signAuthority`. Its generation ID and ordered approver
  IDs exactly equal the root-authenticated bootstrap authority. `requiredSignerPolicy` is
  `{mode:"SINGLE", threshold:"ALL_MEMBERS", members:[{keyId,algorithmId}]}` with exactly one
  member equal to the bootstrap required signer.
- Each catalog authority is `{principalId, policy}`; `policy` is `{policyId, version, sha256}`.
  `version` is a positive signed-64-bit integer and `sha256` is lowercase SHA-256. These are
  immutable nonsecret deployment-policy references, not policies or credentials themselves.
- `eventWriter`: `generationId` distinct from the catalog writer, matching `databaseIdentity`
  and `restoreIdentity`, `registration:"ACTIVE"`, and exactly one `liveRange`.
- `liveRange`: `scope:{kind:"LIVE",id:"00000000-0000-0000-0000-000000000000"}`, `state:"OPEN"`,
  `journalLocation:{bucket,accountId,region}`, `ordinaryPrefix`, `sealTerminalPrefix`,
  `ordinaryAuthority`, `sealTerminalAuthority`, `routingKeyId`, `encryptionKeyId`,
  `configurationSha256`, `firstEpoch:1`, `sealHistory:{count:0,sha256:SHA256(UTF8("[]"))}`.
  There is no last epoch, seal record, denial proof, terminal state, test namespace or migration field.
- Each event authority is `{roleId, credentialId, policy}`. The four catalog/event principal/role
  IDs must all differ, including cross-pair aliases. All four `policyId` values must differ.
  The two credential IDs must differ from each other and from the four principal/role IDs.
  Referencing a policy digest does not establish what permissions its deployed policy actually has.

Nonsecret principal, role, credential, policy, routing-key and encryption-key references use
`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`. They are deployment-inventory reference IDs, never inline tokens,
passwords, secret keys, URLs, or provider policy JSON. Provider-object mapping and actual policy
permissions must be independently evidenced at deployment; this parser does not invent them.
Journal location uses the bundle's bounded bucket/account/region grammar, independently of the
catalog bucket pair. The root-signed registry hash binds its exact identity, not proof it exists.

For the validated event-writer UUID `G`, prefixes are **exactly**, including trailing slash:

```
complaints/journal/v1/G/live/00000000-0000-0000-0000-000000000000/ordinary/
complaints/journal/v1/G/live/00000000-0000-0000-0000-000000000000/seal-terminal/
```

`G` is replaced with its canonical 36-character UUID; nothing else is interpolated. No arbitrary
paths, URL escaping, traversal, omitted generation, overlapping prefix or normalization is accepted.
The root bundle signs this registry's hash. The registry embeds neither a bundle nor genesis hash,
avoiding a bootstrap hash cycle. Results are defensive checked registry **claims only**, not usable
credentials, accepted registration, a catalog head, or membership. Genesis acceptance, full-chain
verification, offline ceremonies, namespace emptiness and dual-copy acceptance remain separate.
