# W04h — concrete, bounded AWS SDK catalog readback

`S3CatalogReadbackAdapter` implements the existing read-only `CatalogReadbackPort` with AWS SDK
Java **2.54.19**, using its supported synchronous URLConnection transport. It is dormant: no bean,
scheduler, endpoint, publisher, KMS operation, database mutation or admission wiring is installed.
Its observations become evidence only through the unchanged W04g dual-chain verifier; construction
or a convincing provider response does not itself authenticate a catalog or advance an accepted head.

## Authentication and request binding

Raw current trust is root-authenticated against the independent existing policy before either
client factory runs. Only its exact PRIMARY/REPLICA role, bucket, account and region tuples are
usable. Each client receives an explicit `AwsSessionCredentials` value through a static provider;
there is no default credential chain, profile lookup or implicit refresh. Credential acquisition,
expiration/rotation and real IAM authorization remain caller/deployment responsibilities.

Endpoints come from the pinned SDK's supported service/region metadata, not hand-built partition
DNS strings. Unsupported regions, external partition metadata overrides and nonregional ambient
us-east-1 routing are refused. Clients pin empty profiles, the SDK-derived HTTPS endpoint, region,
path-style S3, standard defaults, no FIPS/dualstack/acceleration/ARN-region/cross-region routing,
one attempt and an explicit `Proxy.NO_PROXY`. Request checks enforce owner header, regional SigV4
scope, session token, fixed namespace, exact version/cursor parameters and no Range/body. Redirects,
automatic pagination, latest-version fallback and alternate transports are not enabled.

LIST uses `ListObjectVersions` with `ExpectedBucketOwner`, exact prefix, finite page size and both
cursor fields. Its observed Name, Prefix, MaxKeys, markers, VersionId and **Size** must bind the
request. The SDK decodes URL-encoded key/prefix/key-marker fields once; version IDs are not decoded
a second time. Null-start S3 XML empty markers normalize to absent markers. The adapter returns
delete markers as observations; W04g rejects them, owns pagination and proves contiguous inventory.

GET names the exact key and version. A positive bounded Content-Length, actual matching VersionId,
COMPLIANCE lock, finite whole-second retain-until and role-appropriate replication status are
required. Missing evidence is never filled from the request. PRIMARY may report PENDING or COMPLETED;
W04g, not this adapter, decides when pending replication can only yield `AwaitReplication`.
The independently required retention horizon is still supplied by W04g policy, not derived here.

## Bounded decoding and body ownership

Defaults are 10 seconds per SDK call/attempt, 2 seconds per socket connect/read, 2 MiB LIST,
64 KiB error and 8 MiB object. Configuration may lower these; hard ceilings are 60 seconds,
4 MiB LIST, 64 KiB error and the existing 8 MiB object ceiling. Headers are capped at 64 names,
256 characters per name, four values per name and 64 KiB total characters. Evidence headers must
be single-valued. These header checks occur **after stock URLConnection has parsed headers**.

LIST and every non-200 body are fully bounded, EOF/declared-length checked and closed before the
SDK decoder receives detached bytes. Optional lengths are not trusted to bypass the byte cap.
The SDK XML tree parser is recursive, so a byte ceiling alone is not depth protection. A Java 21
`XMLInputFactory.newDefaultFactory()` StAX preflight refuses DTD/entity declarations/references and
external resolution, limits depth to 16, elements to `128 + 32 * requestedPageSize` (128 for GET
errors), tokens to eight times that bound, and caps element names/attribute/namespace counts.
The reader is always closed; the genuine SDK decoder still maps the same bytes afterward.

GET remains streamed. Each read must progress within the declared/object limit; the next read after
the exact length probes EOF and rejects extra bytes. Truncation, zero progress, malformed metadata,
read failures, cancellation and partial client construction all retain checked cleanup. One active
request/body per client remains owned through SDK decoding or explicit body close. A failed cleanup
retains that slot. Native abort and raw-stream close are issued at most once and completion is
serialized; a late response arriving after abort is also closed. Provider/close exceptions cross
the existing content-free boundary; interruption/cancellation and fatal-error precedence are kept.

## Important transport and verification limits

URLConnection's response-stream abort may be a no-op. This adapter therefore retains the original
`ExecutableHttpRequest.abort()` (disconnect) **and checked raw-stream close**; it does not infer
resource-release proof from that no-op callback. SDK `ResponseTransformer.toInputStream` bodies
are outside SDK API-call timeouts after return. The adapter disables the hidden first-read timer,
checks monotonic elapsed time around reads from the original HTTP exchange, and sets finite
socket timeouts. This is **not a hard native whole-body, DNS, cancellation or close-completion
deadline**. Blocking native behavior and TLS/header parsing require later deployment evidence.

The test transport exercises actual S3Client signing/marshalling/XML and header decoding with
synthetic explicit credentials and the existing genuine signed W04g chain. It cannot certify
real account identity, IAM permissions, AWS retention/completeness, endpoint reachability,
maximum-capacity performance or native cancellation. No AWS/provider calls are part of these tests.
Compilation, tests, statics and dependency locking are separately owned by the primary gate;
authored tests alone are not a pass claim.

W04 remains unfinished: durable fenced PREPARED/head/projection integration, KMS/signature
persistence, conditional publication and pre-PUT probe, retention-floor derivation, history,
capture/journal/restore and deployment qualification remain product work. W06 is owner-excluded,
not a pending gate. No integration/package or production qualification is granted by this slice.
