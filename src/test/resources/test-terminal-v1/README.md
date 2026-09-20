# Independent TEST terminal declaration literals

These are synthetic test inputs, not deployment material, accepted activation,
provider evidence, genuine sealed inventories or execution/qualification results.
The author constructed the literals offline with Python's standard `json`,
`hashlib`, `hmac`, `base64` and `struct` modules. No Kotlin/product encoder,
canonicalizer, routing helper or root helper was executed to obtain expectations.
The test fixture reuses the existing synthetic `ownerDeleteTestJournal`; it does
not introduce a service, resolver adapter, database harness or KMS/S3 client.

## Reproduction rules

All document field names are ASCII. For these closed records:

```python
C = lambda x: json.dumps(x, ensure_ascii=False, sort_keys=True,
                        separators=(',', ':')).encode('utf-8')
H = lambda b: hashlib.sha256(b).hexdigest()
F = lambda xs: b''.join(struct.pack('>I', len(s.encode('utf-8')))
                       + s.encode('utf-8') for s in xs)
```

`canonical-goldens.json` stores the literal canonical UTF-8 string, byte count,
SHA-256 and lexical token count for each document. String values count as one
token regardless of their length; each field name counts once, and each opening
and closing object/array delimiter counts once. No newline belongs to the
embedded canonical bytes. This independent construction is an expectation for
backend `kcj-1`, not an assertion of already-tested parity.

`roots-routing-goldens.json` stores every LP32BE field sequence in order,
framed byte count and root hash. A root hashes the concatenation of its frames,
not an array of per-frame hashes. Preterminal seals instead hash the embedded
canonical SealRef array. No production frame encoder is used by the independent
Java-I/O reference writer in the tests.

The scope is `00000000-0000-4000-8000-000000000001`; writer is
`c3333333-3333-4333-8333-333333333333`. Activation generation is 4, activation
hash is 64 `a` characters, D hash is 64 `b` characters, and the encoding hash
is `H(C({"profile":"TEST_TERMINAL_V1","schemaVersion":1}))`. The two ascending
installation UUIDs end in `002` / `003`, with RETIRED / DELETED dispositions.

The ordinary seal covers [1,2]. Its ciphertext hash is a synthetic declaration,
while its canonical hash is the literal epoch-seal document hash. The six-entry
preterminal inventory contains four **distinct versions of one ordinary key**:
`version-1`, `version-2`, U+E000 and U+10000, followed by the declared seal's
exact `version-1` and its additional `version-2`. This pins Unicode code-point /
unsigned UTF-8 order rather than Java UTF-16 order. Extra versions must count;
they cannot replace the declared seal's exact version and hashes. This is local
declaration accounting, not provider one-version admissibility.

The manifest and purge use epoch 3. The manifest's exact literal hash and its
declared reference feed the chunk root, which then feeds the purge. The purge's
future terminal seal is absent from all preterminal roots, eliminating a cycle.
Nothing here proves that the local list is a complete remote/database list.

Routing keys are **SYNTHETIC ONLY**: retained key index `i` (0..3) has bytes
`byte[n] = 1 + 37*i + n` for `n=0..31`. The IDs are `test-route-01` through
`test-route-04`, with `01` active. The resource records the exact hex bytes.
The separate maximum-width source case uses the same formula modulo 256 for
`n=0..127`; it does not describe a real secret or provider acquisition.

Descriptors are `H(C(payload minus eventId))`, or minus `sealId` for seals.
Each route stores its descriptor bytes/hash, eight ID/key frame fields and
the corresponding frame hex. The result is base64url without padding of
`HMAC-SHA256(key, F(fields))`. Domains are:

- `kira-test-terminal-event-id-v1` / `kira-test-terminal-object-key-v1`
- `kira-test-epoch-seal-id-v1` / `kira-test-epoch-seal-object-key-v1`

The remaining fields are kind, writer, `TEST`, scope, unpadded decimal epoch,
routing ID and descriptor hash. Object keys use the seal-terminal prefix and
`installation-manifest`, `test-run-purge` or `epoch-seal`, ending in `.kjev`.
Routing expectations were computed after descriptors but before substituting
the resulting ID into the final canonical record, so there is no ID cycle.

## Maximum-cardinality literals and actual J limits

These are locally admissible **declarations**, not analytical maxima with
duplicate IDs or impossible count sums. They are not authenticated artifacts.

| Embedded document | Bytes | Tokens |
|---|---:|---:|
| maximumInstallationManifest | 41870 | 3041 |
| maximumSealSet | 45359 | 413 |
| maximumDenialSet | 40303 | 1933 |

- Manifest: 500 distinct ascending UUIDs `10000000-0000-4000-8000-` plus
  twelve-digit decimal 1..500; first250 RETIRED, next250 DELETED; one chunk;
  generation65536, epoch `Long.MAX_VALUE`, zero-byte opaque ID. Its complete
  local 500-entry root is computed with that exact context, not the two-entry
  root. `entriesSha256` hashes its exact canonical array.
- SealSet: 15 ORDINARY records and one final TERMINAL. First range is
  [1, `Long.MAX_VALUE-15`], then successive singleton ranges through
  `Long.MAX_VALUE`. IDs are base64url of 31 zero bytes followed by index0..15;
  routing ID is 64 `r` characters; version is 1024 backslashes (2048 JSON
  bytes); canonical reference hash is SHA-256 of ASCII `max-seal-{index}`.
  Each predecessor is the preceding reference's canonical hash. References
  remain synthetic declarations, not readback-verified seal plaintexts.
- DenialSet: 15 distinct writer UUIDs using the same UUID recipe; role IDs
  are 64 `o` / `t` characters; policy ID is 64 `p` characters; policy version,
  evidence byte counts, witness count/bytes are `Long.MAX_VALUE`. First
  inventory starts at MAX-10 and completes at MAX-9; second starts MAX-4 and
  completes MAX-3 with accepted-request bound5. All required equal witnesses
  and timing inequalities hold locally. None of this is a real drain proof.

The existing J is **not widened**: plaintext65536, tokens4096, fields32,
depth16, string4096, intersected with the terminal profile's stricter limits.
Tests author exact3041 / one-less token cases, downward plaintext/field/depth/
string limits, exact1024 UTF-8 version bytes, 500/501 entries, 16/17 seals,
15/16 denial ranges, checked epoch/count overflow and no truncation. The
4096-chunk syntax ceiling is not qualification of a complete 4096-chunk catalog.

## Not covered by this source slice

No tests or compilation were run by the source author. The parent owns review,
coherent joining, gate admission and execution. No envelope/AEAD/KMS adapter,
accepted full-D or activation, publication producer, provider inventory/drain,
final-seal membership against an authenticated set, or authenticated K+1 event
coverage is implemented or certified by these declarations. Notice defaults,
notice seeding and activation integration are deliberately not tested.

The separate PostgreSQL test is one existing-fixture rollback sizing case,
using actual catalog cap131072, seven tables and all35 current indexes. Its
scalar sizing buffers are not these DTO literals or genuine evidence. It
preserves the installation share32768 plus separate audit65536, publication
262144 and physical RESERVED row16384. Logical detoasted row/index envelopes
and the provisional empty-GIN allowance are not disk/MVCC/physical-GIN pricing
or a full terminal reserve. All such qualification remains NOT_RUN here.
