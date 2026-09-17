# Public-only synthetic G1 / expanded-D vector

**Fixture authoring only, NOT_TESTED. Never deployment trust material.** Two fresh disposable
RSA-3072/exponent-65537 keys were generated locally with OpenSSL to author this vector. Only the
root public SPKI, public T0/Tn envelopes and signed G1 remain. Temporary private keys were removed
after signing; no existing host key, provider, service or production data was used.

T0 version7 and Tn version9 both require head1, use the same bootstrap authority/signers, and have
distinct frozen PSS signatures. G1's registry is constructed from the existing `initial-live.json`
J golden (`e1562c3f07c5983ee40f2212c601782dc3bfdc003ee1bf7f7c01b10951ad64f0`), including its real
initial LIVE range fields; it binds **J, not D**. The consumer/pool golden fixture therefore can use
these exact bytes without fabricating a separate journal declaration or catalog success row.

G1's independently selected expected envelope pin is
`306e383768d7924431ed204ab1811f6926bfb96d95e213bf61f99fca20d6da6f`.
Its signed creation is `2024-02-29T12:34:56Z`; the exact ten-UTC-calendar-year boundary is
`2034-02-28T12:34:56Z`. Synthetic readback can use that same retention at the fixed
`2026-09-17T00:00:00Z` evaluation time. No moving now-plus-ten-years floor or re-signing on retry.
Real provider PUT time, IAM, physical retention, approval ceremony and backup/horizon qualification
are not evidenced by these fixtures.

Independent authoring used compact sorted-key UTF-8 JSON without a trailing newline, plus big-endian
uint32-length-framed signature components. Bundle frames are domain `kira.complaints.offline-trust-bundle.v1`,
`kcj-1`, root ID `synthetic-offline-root-1`, `RSASSA_PSS_SHA_256`, raw SHA-256(body). G1 frames are
domain `kira.complaints.catalog-generation.v1`, `kcj-1`, signer ID `catalog-old`, the same algorithm
and raw SHA-256(manifest). OpenSSL used SHA-256/PSS/MGF1-SHA-256/salt-length32. Frozen public PSS
randomness is intentional; do not silently regenerate trust/G1 when an expected-byte test fails.

`generate-golden.py` independently builds expanded-D expected bytes from the unchanged V1 expected
document, these frozen public artifacts and literal effective settings. It does not load production
Kotlin, providers or tests. Re-running it is fixture generation, not verification evidence.
