# Complaint effective configuration D, version 2 — retained G1 reader

**Source draft, NOT_TESTED.** Explicit opt-in dormant inventory, not deployable configuration
authority, current catalog proof, checkpoint, activation or restore clearance. No route/bean is
registered by this producer. The old no-catalog profile and its exact version1 bytes remain intact.

## Retained producer

`VersionBoundComplaintProcessConfiguration.fromRetained(..., catalogReadback = reader)` retains
the same acquired consumer owner, original three pools and exact immutable
`VersionBoundCatalogReadbackConfigurationV1`. Nonnull opt-in selects D `schemaVersion:2`, profile
`INITIAL_LIVE_MEMORY_SINGLE_INSTANCE_G1_READBACK`. The encoder includes the entire actual V1
consumer/J/P/persistence inventory unchanged and a `catalogReadback` object. It does not accept a
caller D, standalone fragment digest or arbitrary configuration map. The DB implementation schema
is still1; this is a D inventory version, not a migration or change to J/P.

`fromIndependentInputs` defensively retains raw T0/Tn, an independent chain policy, expected G1
pin, SDK limits, total attempt milliseconds and pagination bounds. It authenticates actual current
Tn against the supplied public root/environment/locations/minimum version and refuses a head floor
other than1. Historical T0 is **not** verified by relaxing the current floor: only the existing
closed raw G1/T0/Tn path authenticates it. A settings object never proves an approval ceremony or
provider custody. The real refresh must construct its adapter using these same retained settings,
not a substitute policy/provider, and anchor attempt custody to the original coordinator owner.
Reconstructing a process wrapper must not create another refresh slot over the same pools.

## Required catalog inventory

All fields are present, compact UTF-8 kcj-1, integer time units as named, lowercase SHA-256. Full D
is hashed after including this object; there is no independently supplied catalog hash fragment.

| Object | Contents derived from the retained reader |
|---|---|
| Root | `profileVersion:1`, `profile:"G1_EMPTY_ACCEPTED_INVENTORY"`, objects below, `expectedGenesisEnvelopeSha256`, `totalAttemptMillis` (1–600000). |
| `initialTrustBundle`, `currentTrustBundle` | `{sha256, byteCount}` calculated over the exact retained public envelope bytes; their signer/bootstrap/approval/head-floor claims are thereby committed without duplicate caller claims. |
| `trust` | Protocol1; `rootPublicKey:{sha256,byteCount}` calculated from actual retained SPKI (verified against independent fingerprint); root key ID/algorithm; expected environment; minimum bundle version; exact ordered PRIMARY/REPLICA role/bucket/account/region objects. |
| Current selections | Sorted unique `currentWriterGenerationIds` and `currentApproverIds` from the independent chain policy, not read from restored control or selected from observations. |
| `chain` | Protocol1/kcj-1/fixed catalog prefix; effective maximum envelope bytes, manifest records, generations, aggregate encoded bytes; page size and maximum pages per location. |
| `sdk` | Protocol1; actual supported credential selection mode `EXPLICIT_PRIMARY_REPLICA_SESSIONS`; effective request/connect/read timeouts and maximum LIST/error/object bytes. No ambient chain, alternate endpoint or provider override is implied. |
| `retention` | Protocol1, UTC, `CEILING_WHOLE_SECOND`, `SIGNED_G1_CREATION`, creation minimum10 calendar years; remaining anchor `EVALUATION_PLUS_ATTEMPT`, remaining minimum2 years, whole-attempt coverage, future creation refused. |

Protocol1 freezes closed raw trust/G1 verification, complete bounded version/marker listings,
exact dual-copy/version/retention agreement, local signature validation, fixed request and strict
decoder semantics, and the initial empty accepted-inventory restriction. Changing behavior must
version this inventory and its independent golden; an immutable image is separate rollout evidence.

Session secret/token/expiry, clock samples, derived retain-until, remaining budget, provider versions,
accepted head/projection/checkpoint or restored control do not enter D. The current code has no
acquired catalog-role/STS owner; the fixed explicit-input mode is **not** an invented role ARN,
permission proof or complete deployment credential-selection inventory. Real supported selection
owners must join an appropriately versioned profile before stronger authority is claimed.

## Expected bootstrap pin and no cycle

`InitialLiveRangeFactory` commits J as the initial registry's `configurationSha256`, not D. Current
G1 contains that registry/T0 but no D. Thus `J → registry → T0 → signed G1 → independent expected
bootstrap pin → D-v2` is acyclic. The pin is a desired immutable bootstrap constraint, not the
observed accepted/current head. Persist exact randomized signature/envelope bytes, independently
fix the pin before first PUT, then compose this profile with the original owners. Never recover or
reset the pin from S3/restored rows or rewrite T0 to hash G1. No live quota-owner rebuilding follows.

## Calendar policy and remaining horizon limits

The actual refresh samples its own clock; `policyAt(Instant)` is its internal derivation seam, not
a public caller-date refresh. Add the entire configured attempt budget to evaluation first, then
two UTC calendar years, then take the whole-second ceiling. The two-year margin must still hold at
the latest bounded attempt end, not merely at its start.
Invalid/range-overflow dates fail with bounded retention errors. After genuine raw G1 readback,
`verifyGenesis` requires the exact retained bundle hashes/pin and derived policy times, the selected
writer/approvers, empty G1 inventory/history, no future signed creation, and retention through signed
creation plus ten UTC calendar years. February29 follows UTC calendar-year arithmetic, not a
365-day approximation. This preserves exact legitimate G1 retry; it is **not** `now + ten years`.

Physical PUT time is absent from catalog metadata. Signed intent time does not prove retention
starting at the actual PUT. Empty accepted G1 inventory does not prove absence of outside backups,
an effective backup/journal horizon, retention extension or decommission coverage. J/ordinary
retention declarations are not missing horizon producers. This source mints no authority handle and
does not install D, open gates or turn null-D bootstrap progress into current-D capability.

The public-only synthetic fixtures under `fixtures/complaint-effective-configuration-v2/` share
the existing actual consumer fixture's J. Their independent stdlib generator produces expected D
bytes without running Kotlin/providers; authoring a fixture is not a verification receipt.
**W06 remains SKIPPED—OWNER EXCLUDED; new-backend-data journal/recovery remains required.**
