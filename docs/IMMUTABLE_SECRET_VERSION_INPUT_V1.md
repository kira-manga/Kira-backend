# Immutable secret-version input V1

This dormant contract supplies a matched secret-version descriptor and one copied
material snapshot. It constructs **neither complete configuration D nor any
activation, credential, key-separation or provider-verification authority**.
There is no SDK adapter, environment/profile lookup, bean, Kubernetes wiring,
network access, default secret, retry, or cache.

## Supported reference and binding

`security.ImmutableSecretVersion.awsSecretsManager(resourceArn, versionId)`
accepts only:

- A full `arn:aws:secretsmanager:REGION:ACCOUNT:secret:NAME-SUFFIX` ARN.
  ACCOUNT is exactly 12 ASCII digits. REGION matches
  `[a-z]{2}(?:-[a-z0-9]+){1,3}-[0-9]{1,2}` and is at most 64 characters.
  NAME has 1–512 characters from `[A-Za-z0-9/_+=.@-]`; SUFFIX is the actual
  six-character ASCII alphanumeric ARN suffix. The whole ARN is at most 640
  characters. This checks syntax, not region availability or account ownership.
- An exact lowercase RFC-4122-variant UUIDv4 VersionId. This is a deliberate
  subset of Secrets Manager's wider version-token grammar. Nothing is normalized.

Stages such as `AWSCURRENT`, `AWSPREVIOUS`, `latest`, names/partial ARNs,
filesystem or environment paths, other partitions, and KMS keys/aliases are not
supported. KMS nonexportable-key identity is a separate contract. A logical key
name containing an alias-like word is not itself a version selector.

`VersionedSecretBinding.of` requires family, purpose, logical key ID and the
reference. Key IDs use `[A-Za-z0-9._-]{1,64}`. DATABASE and REDIS permit password,
TLS-private-key and TLS-key-password purposes. USER_ADMIN_JWT, INSTALLATION_JWT,
COMPLAINT_ADMISSION, COMPLAINT_CURSOR and COMPLAINT_JOURNAL_ROUTING permit only
HMAC_SHA256. These are declared purposes, not grants or automatic conversions.
The installation credential verifier is unkeyed and adds no secret family here.

## Acquisition boundary

`SecretVersionResolver` is a **trusted port**, not a default implementation. A
future adapter must request the exact resource and VersionId and independently
read the response's full ARN and VersionId with its decoded **SecretBinary**
payload. V1 has no SecretString/Base64/text-decoding fallback. The adapter must
provide authenticated, bounded I/O and cannot echo request labels beside
unrelated bytes. The report object cannot prove that the adapter obeyed this rule.

`AcquiredVersionedSecret.acquire` invokes the resolver once and rejects a different
resource or version. It constructs the returned descriptor using the reported
reference and retains a defensive copy of that same report's material. Reports
accept 1–65,536 bytes; algorithm, key-format/strength, entropy and all existing
effective-key/family comparisons remain consumer obligations.

References/descriptors are immutable. Resolver input arrays are copied;
`useMaterial` supplies a fresh temporary copy and clears it on normal or exceptional
return. A consumer such as an existing key-ring constructor must copy anything it
retains. This is not a guarantee of whole-JVM secret erasure. No secret-derived
fingerprint, canonical secret document or plaintext diagnostic is exposed.

All diagnostic rendering is redacted. Ordinary resolver failures lose their
messages, causes and suppressed failures; cancellation remains sanitized
cancellation. Interruption before lookup, thrown by lookup, or present after
lookup refuses acquisition and preserves the flag. Fatal Errors propagate
normally. The contract itself performs no logging and does not sanitize arbitrary
consumer code inside `useMaterial`.

Real provider permissions, immutable-version guarantees, payload encoding and
version retention still need an independently verified adapter/deployment. This
contract does not make the existing fixed-name Kubernetes Secret references
version-bound, replace runtime family checks, construct D, or enable any route.

## Cold installation-JWT composition

`VersionBoundInstallationJwtConfiguration.fromAcquired` takes the explicit active
installation key ID, user issuer/audience and both complete supplied retained-key
lists: 1–8 `INSTALLATION_JWT` and 1–8 `USER_ADMIN_JWT` acquisitions, all HMAC_SHA256.
It derives the existing installation ring, forbidden user family and immutable
descriptors from those same acquisitions, never parallel material/label inputs.
Repeated full ARN/VersionId pairs are rejected across both lists; the existing
key types enforce ID, 32–128-byte material, active-key and effective-HMAC separation
checks against every supplied retained key. Temporary material copies are cleared
by `useMaterial`; descriptor copies are ordered user/admin then installation,
each by logical ID. Descriptors and diagnostics expose no plaintext or
secret-derived commitment.

This is a cold composition, not proof that the supplied user family matches the
deployed `JwtService`, that no retained key was omitted, or that acquisition,
rotation and provider permissions are genuine. It performs no lookup and creates
neither complete desired configuration D nor runtime/installation authority.

## Cold version-bound persistence password and public-trust custody

`VersionBoundPersistenceConfiguration.fromAcquired` accepts one
`DATABASE / AUTHENTICATION_PASSWORD` acquisition. The same acquisition supplies
the actual pgjdbc password (strict UTF-8, no replacement, trimming or NUL) and its
immutable version binding. There is no separate password/label pair or lookup.
The retained JVM `String` is necessary for pgjdbc; whole-JVM erasure is not claimed.

This deliberately narrow endpoint profile accepts one lowercase ASCII DNS host,
an explicit port, 1–63-character `[A-Za-z0-9_][A-Za-z0-9_.-]*` database/user names
and 1–64 ordinary physical slots. It fixes stock `LibPQFactory` and hostname
verification, `verify-full`, password/SCRAM only, disabled GSS, bounded SCRAM
iterations, no client certificate/key, a two-second external login budget,
disabled pgjdbc login thread and 1/2/1-second connect/socket/cancel settings.
There is no arbitrary property map, alternate factory, TLS identity or plaintext
fallback. This is a settings subset, not evidence of a TLS handshake or suitable
server trust policy.

Public trust is 1–262,144 captured bytes containing 1–16 complete X.509 certificate
PEM blocks only (ASCII, LF/CRLF, canonical Base64 lines of at most 76 characters;
no keys, headers, comments, other PEM types or unparsed trailing material).
Certificate validity, CA policy and the actual peer chain remain separate checks.
The descriptor derives the explicit original-provider endpoint input and binds
the captured public PEM bytes with SHA-256; it excludes the password and
generation-local pathname. Identical public bytes therefore have the same trust
identity across pods even when their protected filenames differ. This is **not**
a finalized per-role/Hikari pool descriptor, complete D or activation authority.

Call `bindLifecycleOwner()` once, retain that inert owner, then explicitly call
`preparePublicTrust()`. Its exact root owns the captured material and reserved
pathname before any filesystem work. Preparation creates an exclusive generation
directory (`0700`) and a certificate-only file (`0400` after writing), verifies
the actual written bytes and retains concrete file identities. New-profile starts
refuse until preparation is ready. No second root can adopt that configuration;
the legacy null-trust/source-only composition is not switched to this profile.

The supplied parent must already be a private `0700` directory on the default
POSIX filesystem, with no symlink ancestors and a trusted root/service-account
ancestry (write-exposed ancestors require root-owned sticky directories). Parent,
directory and file identities are checked; no recursive deletion is used. This
assumes the service account and privileged host remain trusted. It does not defend
against the same account replacing public files, mount changes, hostile filesystem
providers or machine compromise. Filesystem calls can block: the caller must own
that work outside request/phase/ownership locks; no I/O deadline is claimed here.

Cleanup is a separate explicit `releasePublicTrustAfterShutdown()` call, outside
observers/scanner/F/G. It consults its **own** root's permanent shutdown seal and
exact `TRACKED_LOCAL_ENDED` result across ordinary, deletion, coordinator, worker,
opener, scanner and shared Timer custody. Local drain, `close`, counters, unsealed
or uncertain work and `DRIVER_CONTRACT_ONLY_ENDED` never authorize release. The
INERT Timer case is valid only as part of that full root result. Preparation and
cleanup serialize; a disposed generation cannot later write or be re-adopted.
Partial/ambiguous creation or failed deletion retains custody and reports a
sanitized result, not an inferred successful cleanup. No automatic reprepare or
process-exit/crash cleanup is supplied. No provider-provenance, production wiring,
native qualification, complete-D or runtime-route activation claim is added.
