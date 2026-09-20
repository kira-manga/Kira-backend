# Fixed overlap2 raw readback — no delivery authority

`CatalogDualLocationVerifier.Overlap2Readback` is a private-constructor, connection-free
raw-verification handoff only. Its sole factory takes the actual `CatalogReadbackPort`, independent
trust bundles/readback policy and an unvalidated local snapshot. It calls the existing `verifyRaw`
path: bounded local validation, actual version listings/reads, signature/trust/chain verification
and exact reconciliation. No factory accepts a diagnostic result, metadata, Boolean or callback
as a substitute for that round.

Only schema1 `ROTATION_OVERLAP` generation2 is supported, with the pinned G1 predecessor and exact
signed bytes. Inputs must be signed `Prepared(head1, mutation2)` or
`ProjectionPending(head2, projection2)`. Unsigned candidates, other operations/generations,
`Accepted` (including projected2 replay), genesis and mismatched local tuples are rejected.

| State | Raw external tail | Common evidence | Generation2 copy evidence |
|---|---|---|---|
| `PREPARED_UNPUBLISHED` | G1 in both; no successor | G1 only | none |
| `PREPARED_AWAIT_REPLICATION` | exact frozen2 on primary only | **none** | none |
| `PREPARED_DUAL_COPY` | exact frozen2 in both | G2 | exact primary + replica |
| `PROJECTION_PENDING_DUAL_COPY` | exact pending2 in both | G2 | exact primary + replica |

`snapshotHead` always remains the supplied head1/head2; `observedTail` is separately authenticated
external metadata. Seeing tail2 never advances the supplied head or creates current SQL/lease
authority. `requireSnapshot` compares the exact retained snapshot, including operation token,
unsigned/signed bytes and ordered signature slots for PREPARED; it does not read the database.

`frozenEnvelopeBytes()` always returns the retained candidate2; `observedEnvelopeBytes()` returns
the actual raw tail (G1 when unpublished). `generation()` reparses frozen2, not the observed tail.
Object version/retention values belong to `observedTail`. All returned byte arrays are defensive
copies. Dual2 evidence uses the unchanged canonical copy-evidence format; a primary-only round
does not expose the stream's retained G1 pair as generation2 evidence. Existing raw rejection of
extra versions/markers, later tails, differing randomized envelopes, replication/retention
mismatches and provider/body-close failures remains in force.

This lower proves **no** publication arm eligibility, custody ownership/completeness, historical
SQL persistence, actual DB snapshot, current B/lease, provider-root cleanup, PUT permission,
COMPLETE/PROJECT authority, D7 deployment admission or trust-rollout completion. In particular,
unpublished readback is not a retry entitlement, and dual readback does not establish that an arm
ever existed. The separate typed owner and named root are described in
`COMPLAINT_CATALOG_SIGNER_ROTATION_DELIVERY_V1.md`; source presence does not establish acceptance.
Fresh pending-purpose acquisition and ambiguous-COMPLETE/PROJECT reconciliation belong only to
that owner, not this raw proof. Already-projected2 recovery uses the separate unchanged genuine
`ProjectedHeadReadback` Accepted2 fold; it never manufactures a Pending snapshot to make this
verifier accept it. A narrowly named cold-D7 policy check pins that proof to exact overlap2 and
the unchanged trust/time/retention floors. Activation3 remains separate INTERNAL work. No existing
G1/D5 reader profile, raw verifier or ordinary SQL predicate is widened.
