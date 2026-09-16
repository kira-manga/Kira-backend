# Public-only synthetic W04a golden vector

These are **test fixtures, never deployment trust material**. Three fresh RSA-3072/exponent-65537
keys were generated with OpenSSL solely to author this vector. Only the root public SPKI, catalog
public SPKIs, body/envelope, frame/digests and signature are retained; the temporary private keys
were removed after signing. No host key was read.

All document fields are ASCII, required, integer/string/array/object values without defaults/nulls.
Their independent fixture encoding uses lexically sorted object keys and compact JSON; the Kotlin
tests must establish byte equality with the unchanged normative `kcj-1` implementation. The JSON
files deliberately have **no trailing newline**. Other text files have one newline, removed when
reading their encoded value.

Framing was authored independently as big-endian uint32 length plus each component:
`kira.complaints.offline-trust-bundle.v1`, `kcj-1`, `synthetic-offline-root-1`,
`RSASSA_PSS_SHA_256`, then the **32 raw bytes** in `body.sha256` (not its hex text).
`frame.hex` and `frame.sha256` pin both framing and its SHA-256 digest.

OpenSSL signing command for the disposable root was:

```
openssl dgst -sha256 -sign root.pem -sigopt rsa_padding_mode:pss \
  -sigopt rsa_pss_saltlen:32 -sigopt rsa_mgf1_md:sha256 -out signature.bin frame.bin
```

`signature.base64` equals the envelope's signature. PSS randomness is frozen in this public vector;
tests also generate fresh in-memory synthetic keys/signatures and adversarial variants.

Fixture authoring is **not** a test execution or production approval receipt. No catalog head,
two-person ceremony, live storage identity, or complaint capability is asserted by these files.
