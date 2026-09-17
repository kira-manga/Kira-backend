# OWNER_DELETE_ALL journal codec v1 — adopted bytes

Primary approved this narrow wire profile on 2026-09-17 from the private decision
note SHA-256 `1af73211da50713b0f8b57625d519044f97d2c8aa5693aa81ae1c1c6b73d827c`.
It implements ordinary **LIVE OWNER_DELETE_ALL only**, using the actual acquired
`VersionBoundComplaintJournalRouting` owner and its same J declaration. It does
not introduce another routing/configuration authority or alter J/global kcj-1.

The result is canonical content or an authenticated encrypted **candidate**,
never authorization, committed PREPARED evidence, a verified durable object,
retention/version proof, capability activation, or D. There is no SDK/provider
adapter, route, migration, outbox writer, application/recovery worker, or
retirement/TEST/admin/seal codec here. Source authoring is not a successful
build/test/provider-qualification claim.

## Required plaintext

The closed kcj-1 object has exactly these 14 required fields, with no nulls or
serializer defaults (including for `1` and empty arrays):

| Field | Value/constraint |
| --- | --- |
| `schemaVersion` | JSON integer `1` |
| `eventKind` | `"OWNER_DELETE_ALL"` |
| `eventId` | Selected routing owner's canonical unpadded Base64url32 event ID |
| `publicationEpoch` | Positive signed-64-bit assigned epoch |
| `writerGeneration` | Exact canonical UUID from J |
| `actorKind` | `"INSTALLATION"` |
| `actorId` | Canonical UUIDv4 |
| `credentialVersion` | Positive submitted/pre-deletion signed-64-bit version |
| `operationKey` | Canonical UUIDv4 |
| `requestFingerprint` | Canonical unpadded Base64url32 |
| `ownerInstallationIds` | Exactly `[actorId]` |
| `dataScopeKind` | `"LIVE"` |
| `dataScopeId` | `"00000000-0000-0000-0000-000000000000"` |
| `complaintIds` | Required 0–100 canonical UUIDs, strictly ASCII-sorted and unique |

Complaint UUIDs are not restricted to UUIDv4. Arrays are never silently sorted
or deduplicated on receipt. No timestamp, prose, diagnostic, IP, secret,
verifier, admin grant or other field is admitted. The caller still must prove
authorization, normalization, ownership/snapshot and epoch assignment; tuple
syntax and canonicalization do not do that. Later delete-all application must
erase all then-owned rows, not merely the earlier journal snapshot.

## Outer bytes and header

J selects LP32BE cryptographic framing but previously selected no outer byte
encoding. Binary blobs avoid incorrectly exempting a huge JSON Base64 string
from J's string limits. The one permitted encoding is:

```text
ASCII "KJEV" | U32BE(1) | U32BE(header length) | canonical UTF-8 header
             | U32BE(wrapped-key length) | raw wrapped key
             | U32BE(ciphertext-and-tag length) | raw ciphertext || tag | EOF
```

The tag is 16 bytes. All lengths are unsigned, with overflow-safe total/bounds
checks before allocating/slicing. There is no compression, extension section,
padding, alternate encoding or trailing byte.

The header contains exactly these 18 fields. This is also the ordered list of
**values** `H` used below; JSON object keys are independently sorted by kcj-1:

```text
envelopeSchemaVersion=1, payloadSchemaVersion=1, canonicalizerId="kcj-1",
objectKind="OWNER_DELETE_ALL", encryptionAlgorithm="AES-256-GCM",
dataKeyMode="FRESH_PER_OBJECT_KMS_WRAPPED", kmsKeyId, kmsKeyArn,
bucket, objectKey, writerGeneration, ordinaryPrefix,
dataScopeKind="LIVE", dataScopeId=zeroUUID, publicationEpoch,
routingKeyId, eventId, nonce
```

Versions and epoch are JSON integers; everything else is a string. Nonce is
canonical unpadded Base64url of exactly 12 bytes (16 characters). Key identity,
bucket, writer and complete ordinary prefix are from the same J owner. The
selected retained routing ID/key/event ID are not relabelled to today's active
ID. A new authorization selects active; historical verification keeps its
frozen selection. Exact expected external bucket/key are independently required
when opening. AES plaintext key material is never serialized.

## AES-GCM associated data and KMS context

`F` concatenates UTF-8 fields, each preceded by its unsigned four-byte big-endian
byte length, including the first domain. Integer fields use canonical decimal;
UUID/Base64url spellings are canonical. `B64U` is unpadded Base64url. Choose a
fresh CSPRNG nonce **before** requesting a fresh 32-byte data key.

```text
KMS context = exactly one entry:
  "kira-complaint-journal-context-v1" ->
    B64U(F("kira-complaint-journal-kms-context-v1", "1", H...))

AES-GCM AAD = F("kira-complaint-journal-aad-v1", "1",
  "KJEV", "1", header length, H...,
  wrapped-key length, B64U(wrapped key), ciphertext-and-tag length)
```

Ciphertext-and-tag length is plaintext length + 16. KMS context cannot contain
its own wrapped-key output. It binds every semantic header including nonce;
AAD additionally binds wrapped bytes and every outer decoding field. Closed
canonical header encoding means `H` uniquely binds its exact bytes. KMS context
contains no actor, operation key, fingerprint or target UUID. A KMS key ARN is
an exact configured identity, not a pinned immutable backing-material version.

The trusted port must generate fresh AES-256 keys, authenticate unwrap with the
exact requested ARN/context, enforce the requested bounded timeout and bound
SDK/transport responses before materialization. The codec checks returned ARN,
32-byte key size and wrapped size; those checks are not independent provider
qualification. Port-transferred buffers and codec-owned intermediates are
cleared on exit, including before provider lease cleanup. Ordinary exceptions
are redacted; cancellation/interruption classifications and fatal errors are
preserved. No whole-JVM/String/provider erasure guarantee is made.

## Bounds and opening order

- Whole wire <= J envelope cap <= 98,304. Header <=
  `min(4096, J.maximumPlaintextBytes)`. Wrapped key is nonempty and <= J wrapped
  cap. Ciphertext/tag length is `17..J.maximumPlaintextBytes+16`.
- Header and authenticated payload each enforce J depth, per-object fields,
  total tokens and UTF-8 string-byte limits; plaintext <= J cap <= 65,536.
  Strict UTF-8, unknown/duplicate/missing/null fields, noninteger numbers,
  noncanonical JSON/Base64url and trailing input are rejected. An unusable J
  profile is never silently widened. Context key+value UTF-8 bytes <= 8192.
- Before KMS: bound/copy wire, validate fixed structure/header, compare expected
  external location and J identity, validate positive epoch/retained routing
  ID/event grammar/path shape, then require a connection-free calling phase.
- After exact-key unwrap: authenticate with JDK `AES/GCM/NoPadding`, 128-bit tag
  and 12-byte nonce before releasing/parsing plaintext. Enforce the closed
  payload, match header fields and locally rederive the tuple's selected
  retained event ID/object key. Never skip a malformed/unsupported object.

Canonical plaintext SHA-256 and whole-wire SHA-256 are separate digests of
their exact respective bytes; neither is D or a storage observation. Call
timeout input is bounded by J's KMS cap; a future publisher additionally owns
its shared attempt deadline and durable authorization before any provider use.

## Publication and retries remain a separate owner

Frozen V6 2647–2665 freezes PREPARED semantics, selected key/epoch/object key,
not necessarily unpublished randomized ciphertext. There is no mandatory
envelope-CAS migration. V14 `event_bytes` remains plaintext and PREPARED's
ciphertext/verification fields remain null.

Only after actual receipt/PREPARED commit may a publisher probe the frozen key
or conditionally PUT. Existing exact semantic matches are verified/adopted from
their actual bytes. An absent-key candidate uses fresh key/nonce; concurrent or
restarted candidates may differ. `If-None-Match: *`, checksum, COMPLIANCE
retention and bounded same-key readback must resolve success/412/409/ambiguity
to exactly one non-null immutable version, no delete marker, exact metadata and
authenticated frozen fields. Failed checks/timeouts are not absence. Never
replace/re-key an existing object or apply before verified durable publication.
Only verified stored bytes supply observed ciphertext hash/version evidence.

The separate future-writer race/restart/readback regression is **not covered by
codec tests** and remains unimplemented. Codec tests concern independent byte
vectors, actual cryptography, tampering/bounds, cleanup/redaction, retained-key
verification and the closed owner-delete-all payload only.
