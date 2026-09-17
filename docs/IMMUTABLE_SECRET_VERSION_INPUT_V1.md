# Immutable secret-version input V1

This dormant contract supplies a matched secret-version descriptor and one copied
material snapshot. It constructs **neither complete configuration D nor any
activation, credential, key-separation or provider-verification authority**.
The explicit dormant AWS SDK adapter described below performs exact-version reads
only when called. There is no environment/profile secret lookup, bean, Kubernetes
wiring, default secret, automatic retry, cache or route activation.

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

`SecretVersionResolver` remains a **trusted port**, with an explicit dormant AWS
implementation rather than an automatically selected default. Every implementation
must request the exact resource and VersionId and independently read the response's
full ARN and VersionId with its decoded **SecretBinary** payload. V1 has no
SecretString/Base64/text-decoding fallback. The adapter must provide authenticated,
bounded I/O and cannot echo request labels beside unrelated bytes. The report object
cannot prove that an arbitrary implementation obeyed this rule.

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

Real provider permissions and version retention still need independent deployment
verification; controlled HTTP fixtures are not live AWS evidence. This
contract does not make the existing fixed-name Kubernetes Secret references
version-bound, replace runtime family checks, construct D, or enable any route.

## Explicit AWS Secrets Manager resolver and persistence bootstrap

`security.aws.AwsSecretsManagerVersionResolver.open(region, credentials, limits)`
constructs an owned synchronous Secrets Manager **2.54.19** client without opening
a connection. Region and `AwsSessionCredentials` are mandatory: no credential or
region provider chain, profile credentials, STS lookup, secret discovery, endpoint
argument or session refresh is supplied. The region must be in the SDK's service
metadata, match the existing syntax and belong to the commercial `aws` partition;
GovCloud/China/ISO, pseudo-FIPS and unknown regions are rejected. Reads reject an
ARN from another region before dispatch. The SDK-derived regional HTTPS endpoint
overrides ambient endpoint URLs. An empty profile, standard defaults mode,
disabled FIPS/dualstack and absence of `AWS_PARTITIONS_FILE` are explicit.

The SDK signs exactly `GetSecretValue(SecretId = full ARN, VersionId = UUIDv4)`.
The owned transport also checks the actual signed POST target, region/service
scope and two-field JSON request; a `VersionStage`, name/partial ARN, second
attempt or redirect cannot replace it. The stock URLConnection implementation
uses `Proxy.NO_PROXY`, no redirects, no interaction and no caching. Defaults are
a 10-second SDK/elapsed request ceiling and 2-second connect/read timeouts, with
bounded explicit lower/upper limits. These do **not** prove a hard DNS/native
completion deadline or successful cancellation. There is one retained exchange
through SDK response decoding and cleanup, not a reusable stream handed to callers.

Before SDK unmarshalling, a fixed allocation bounds the response to at most
131,072 wire bytes (including framing whitespace and JSON escapes). Only a 200
response with AWS JSON content type and no compression/range/redirect metadata
is read; error and redirect bodies are not handed to any SDK decoder. Content
length, progress, EOF and actual byte ceilings are checked. Header limits apply
**after** stock URLConnection header parsing, not before its allocations.

A bounded streaming JSON pass requires one complete UTF-8 object without BOM,
duplicate decoded field names, trailing tokens or unknown fields. Required `ARN`,
`VersionId` and `SecretBinary` must be strings; optional bounded `Name`,
`VersionStages` and numeric `CreatedDate` are metadata only. `SecretString` is
rejected even alongside a binary value. Standard canonical Base64, padding bits
and exact 1–65,536 decoded-byte size are checked **before** the SDK's blob
allocation; a maximum binary value occupies 87,384 encoded characters, so the
wire bound deliberately exceeds 64 KiB. After the actual SDK decodes the response,
its independently returned ARN/VersionId and blob length are checked again.
`SdkBytes` is consumed as bytes, never Base64-decoded a second time. Raw wire and
temporary copied material arrays are cleared on owned cleanup; SDK/String/JVM
copies preclude a whole-process erasure claim.

Creation, dispatch, reads and the post-decode boundary require connection-free
context. Cancellation/interruption remain sanitized and do not become ordinary
lookup failures; fatal Errors retain normal propagation. Native abort, response
close, transport close and SDK close each have explicit custody, including late
responses and construction failure. Cleanup failure retains the failed slot;
closing once or reaching EOF is not proof that an outstanding call ended. No
provider body, cause graph or submitted material is attached to an ordinary
exception or logged by the adapter. SDK/debug logging and launch settings still
require deployment policy.

For the production constructor path, first retain
`AwsVersionBoundPersistenceBootstrap(binding, resolver)`, then explicitly call
its one-shot `bind(host, port, database, username, ordinaryCapacity,
publicTrustPem, protectedTrustParent)`. Construction is inert and accepts only
the existing DATABASE/AUTHENTICATION_PASSWORD binding. `bind` checks
connection-free context, invokes the resolver through `AcquiredVersionedSecret`,
retains that exact acquisition, calls the existing persistence `fromAcquired`,
retains its configuration and binds/retains the existing lifecycle owner. Each
successful stage remains accessible if a later stage fails; an entered bootstrap
cannot retry lookup or bind again. It returns the existing owner **without**
preparing trust, constructing pools or starting actors. The caller independently
owns/closes the resolver, retains the bootstrap/owner, and uses the same owner's
explicit preparation, pool and shutdown operations described below.

JWT consumers use `AcquiredVersionedSecret.acquire(binding, resolver)` followed
by the existing `VersionBoundInstallationJwtConfiguration.fromAcquired`, retaining
those acquisitions before construction. Neither composition re-resolves or
substitutes material on failure. AWS immutable-version semantics do not guarantee
continued availability of an old version: operator IAM (including applicable
KMS decrypt permission), retention, credential expiry and rollout remain separate.

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

## Cold version-bound role/pool construction

After retaining the version-bound owner, `owner.bindVersionBoundPools()` constructs
one fixed ordinary/deletion/catalog-coordinator composition. No caller supplies a
second endpoint, capacity, Hikari configuration, descriptor, or role label. The
ordinary capacity comes from the adopted configuration; deletion has four slots
and catalog coordination has one. The actual catalog datasource/manager/phase
tuple uses the same existing catalog-owner binding. The returned resources are
guarded sources and the catalog tuple, never raw Hikari pools or lower factories.

Each actual physical participant retains its root's exact immutable role material.
That material resolves the effective endpoint recipes through the existing native
settings derivation, and those same objects supply `driver.connect` properties:
ordinary original-provider/weak-tracked/strong-tracked choices, strong deletion,
and strong coordinator. The descriptor distinguishes original and tracked socket
factory settings instead of pretending the original endpoint describes every
attempt. Runtime selection and evidence fallback rules are unchanged. Password
bytes still come from the original acquisition; public trust still identifies the
captured bytes, not the generation-local path.

The composition configures and validates each **actual retained cold Hikari
shell**, then captures its effective scalar settings. Ordinary minimum-idle is
zero; deletion/coordinator minimum-idle equal their fixed capacities. Checkout
budgets are 2000/500/250 ms and validation budgets are 2000/250/250 ms. This narrow
profile fixes initialization-fail-timeout at -1, idle-timeout at 600000 ms,
max-lifetime at 1800000 ms, keepalive at 120000 ms, leak detection off, auto-commit
on, read-only/internal-query isolation off, and no custom schema/catalog/isolation
or init/validation SQL. There is no independent URL, credential, datasource class,
JNDI, property map, metrics/health callback, scheduler, exception override, JMX or
pool suspension. The existing actor owner installs its exact thread factory.
`hikaricp.configurationFile` must be absent before Hikari construction, not merely
cleared afterward. These are current-value checks, not immutable-launch provenance.

`pools.descriptors()` reads only a successfully completed composition and rechecks
the retained actual scalar settings, lower-source identity and actor profile;
pool admission repeats the same binding check. Returned nested values
and copied public properties contain no password, password fingerprint, trust
filename, raw resource, mutable HikariConfig or generated pool name. Descriptors
are **non-authoritative input for a future complete-D producer**, not a digest or
proof of a deployed process's configuration. No complete-D schema is defined here.

The default launch profile remains `UNKNOWN`: construction installs custody but
does not start Hikari/JDBC or make the sources usable. `CONTROLLED_TEST_ONLY`
retains its existing fixture-only meaning, never production/native qualification.
The ordinary Boot `sourceOnly` path and legacy test path remain selected as before.
Their constructors refuse a version-bound owner, preventing an alternate pool
with an independently swapped endpoint/capacity/configuration. There is no new
bean, environment switch, route activation or production wiring.

Raw `PoolLifecycle` constructors also refuse that owner: only its exact retained
shell can receive the version-bound lifecycle. An unregistered legacy lifecycle
therefore cannot create an undisclosed sibling pool under the same trust custody.

Partial-shell custody begins before the inert Hikari constructor is entered. The
actual shell is retained before lifecycle/lower-source construction and validation;
the lifecycle is retained before further configuration can throw. This also covers
catalog construction before the enclosing tuple can be returned. Failed or pending
construction cannot be retried, replaced, described as a complete composition, or
silently treated as absence. The owner retains `versionBoundPools` even if binding
throws; its explicit `close()` attempts every retained lifecycle, including partial
catalog custody. It never directly closes a raw shell or claims that a close return
completed its workers. Ambiguous construction remains a material-retention hold.

Trust release now requires the exact sealed driver-root `TRACKED_LOCAL_ENDED`
conjunction **and** every actually bound pool's independent local shutdown proof:
the genuine owned close frame and bookkeeping must have ended successfully, the
installed actor factory's creator/return-entitlement population must be closed,
and every retained worker must have conclusively ended. A raw Hikari `isClosed`
flag, zero counters, a different role's close, or the native root result alone is
insufficient. This local-only observation performs no managed/native observation,
so there is no root-to-pool-to-root recursion. An unentered composition is inert
only after the same root's permanent seal makes future construction impossible;
an entered incomplete composition remains retained. Observers still do no material
filesystem work and only the explicit release operation can remove owned files.

Actual new-profile TLS/native execution, provider and immutable-launch provenance,
full configuration D, connected complaint consumers and deployment activation are
still separate requirements. Cold construction/close checks do not satisfy them.
