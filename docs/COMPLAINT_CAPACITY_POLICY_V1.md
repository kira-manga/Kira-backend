# Complaint capacity policy V1 (P)

`ComplaintCapacityPolicyV1` is the immutable, complete preimage for **capacity
policy P**. It does not represent the complete desired configuration **D** or
journal configuration **J**, and is not a signature, trusted provenance,
locked-row proof or activation permission.

## Construction and exact fields

The sole constructor is `ComplaintCapacityPolicyV1.of(hardLimit, creationLimit,
dailyEnrollmentLimit)`. It accepts two immutable `ComplaintCapacityVector`
values and the aggregate daily enrollment limit. No digest, raw JSON, field
names, ordinals, kind or version is accepted from the caller.

The canonical document contains exactly these required root fields:

| Field | V1 value |
|---|---|
| `kind` | `"kira-complaint-capacity-policy"` |
| `schemaVersion` | integer `1` |
| `canonicalizerId` | `"kcj-1"` |
| `accountingVersion` | integer `1` |
| `counters` | the 22 rows below, in stored ordinal order |
| `dailyEnrollmentLimit` | declared aggregate daily limit, integer `0..Long.MAX_VALUE` |

Each counter row has exactly `name`, `ordinal`, `hardLimit` and `creationLimit`.
Names and ordinals come from the existing V1 `ComplaintCapacityEncoding`
mapping, not enum ordinals or caller iteration order:

| Ordinal | Name |
|---|---|
| 1 | `app_installations` |
| 2 | `audit_rows` |
| 3 | `catalog_mutations` |
| 4 | `complaint_rows` |
| 5 | `import_artifacts` |
| 6 | `import_runs` |
| 7 | `import_staging` |
| 8 | `installation_ids` |
| 9 | `installation_receipts` |
| 10 | `journal_applied` |
| 11 | `journal_control` |
| 12 | `journal_publications` |
| 13 | `journal_retirements` |
| 14 | `legacy_records` |
| 15 | `moderation_grants` |
| 16 | `normal_receipts` |
| 17 | `recovery_reservations` |
| 18 | `resource_ids` |
| 19 | `scan_entries` |
| 20 | `scan_runs` |
| 21 | `storage_bytes` |
| 22 | `test_runs` |

Every row must satisfy `0 <= creationLimit <= hardLimit <= Long.MAX_VALUE`.
The daily limit must be nonnegative. Zero policies are valid descriptors;
they do not admit positive enrollment quotas. Limits are not summed, clamped
or converted through floating point. Exact maximum values remain valid.
Invalid creation limits use `INVALID_CREATION_LIMIT`; invalid daily limits
use `INVALID_CONFIGURATION`. Vector width, negative amounts and unsupported
accounting versions retain the vector/encoding validation categories.

## Canonical bytes and digest

P uses the existing backend `CanonicalJson` **kcj-1** implementation and
`Sha256.hex`. The V1 constructor pins accounting version 1 and canonicalizer
identifier `kcj-1`; a future incompatible helper version is not silently
relabeled V1. Changing this schema requires an explicit versioned contract.

All serialized fields are required, with no DTO defaults. Thus every zero
limit and every domain/version field is emitted despite kcj-1's
`encodeDefaults=false`. Keys are recursively sorted by Unicode code point;
the counter array remains in stored ordinal order. The result is compact
UTF-8 without a BOM, insignificant whitespace or trailing newline. SHA-256
is computed over exactly those bytes, not a `jsonb` round trip.

`canonicalBytes()` returns a defensive copy. `sha256` is lowercase hex and
`digestBytes()` returns a fresh 32-byte value. Input vectors already own
defensive copies of their arrays, and no mutable input is retained.
Diagnostics are redacted.

The independent integer/ASCII golden fixture is
`src/test/resources/fixtures/complaint-capacity-policy-v1/full-policy.json`:
hard limits `1000 + ordinal * 17`, creation limits `200 + ordinal * 7`, daily
limit `941`, exactly **1829 bytes**, SHA-256
`f2ae2d3224e75f6b5ca20ab2dc6de84174d819be5a482abd6b95ec9f95ab759e`.
The fixture intentionally has no final newline.

## Enrollment integration and limits of authority

`ComplaintEnrollmentAdmissionPolicyFactory.fromCapacityPolicy(P, globalPerHour)`
constructs the existing `Bounded` admission policy. It derives P's digest,
the `installation_ids` creation limit and the aggregate daily limit from
that same validated object. The caller supplies only the explicit global
hourly quota; there is no default, clamping or duplicated threshold input.
Existing validation requires `1..120`, strictly below both derived limits.

P-derived policies retain the same immutable P object and compare all 22
hard limits and all 22 creation limits, plus digest and daily limit, with
the supplied ledger. Keeping the stored digest while changing another
counter therefore fails. Usage, reservations, day/count and creation-closed
observations do not enter this declaration comparison; their existing
accounting/admission checks remain responsible for whether work can proceed.

The existing raw/opaque constructor remains a compatibility seam for dormant
callers; it checks only the supplied digest and two enrollment thresholds,
not a complete P. This factory does not replace every consumer. A trusted
producer must still establish configuration provenance and genuine locked
row ownership. Even full row agreement alone is not authority. This slice
performs no database writes, initialization,
seed opening, bean/environment wiring, activation or admission-mode change.

P contains declarations only. Current usage, free units, reservations,
daily count/date, `creationClosed`, health, observed state, runtime
identities, scope identifiers, credentials, clocks and quota bucket contents
do not enter the preimage. The separate local global hourly quota also
does not enter this capacity-policy document.

W06 migration/import remains excluded. Retained `import_*` and
`legacy_records` slots are compatibility identifiers, not permission to
allocate legacy data or implement import. Including a slot's declaration
does not enable any operation.

## Dormant G1 consumer and remaining follow-ups

`CatalogGenesisInitialLiveBinding.fromDeclarations(Configured, J, P)` keeps
the independent opaque desired digest D separate from the actual J and P
documents. G1's finalization executor derives its capacity digest from this
P; it no longer takes an additional caller-supplied P digest. The signed
initial event writer and entire LIVE range must match the J-derived claims,
while locked control compares D with D and the expected schema/generation.
No desired hash is filled or cleared, and NULL bootstrap/closed-gate checks
remain. Prepare/signature and other raw digest-taking compatibility seams
are not replaced by this bounded correction.

This is not complete D composition: `Configured` still width-validates an
independently supplied digest without proving its preimage or its relation
to J/P. The complete D document/byte contract and independent acquisition
remain unavailable, as do activation and deployment/restore authority.

## Dormant TEST installation comparison

Explicit TEST constructors capture immutable `Configured(PRE_CUTOVER_TEST)`
with an independently supplied opaque **D**. Enrollment, session and
`JdbcComplaintTestReserveStore` compare the exact run's configuration hash
with this D; counter configuration and ledger arithmetic still compare
**P with P**. The original installation constructors remain LIVE-only and
invent no default D. Neither equal hashes nor matching ACTIVE rows prove
signed activation, full reserve sizing, restore clearance or HTTP authority.

TEST enrollment retains the ordinary phase and locks all counters before
the exact run, then permanent identity and credential. Only a new pair
converts the fixed ID+credential share from unused TEST reserve; its audit
is charged ordinarily and its aggregate daily bucket is admitted once.
Run count/remainder, counter transfer, pair and audit commit atomically.
An exact ACTIVE replay spends no further slot/share/daily admission. Session
preflight remains one released read-only snapshot; refresh locks and rechecks
the exact ACTIVE run before the pair and never takes counters afterward.
Absent/non-ACTIVE requested TEST scopes are terminal on the explicitly bound
TEST path; a different ACTIVE scope is a mismatch, never a LIVE fallback.

This is lower-store comparison support only. Full-D composition, cataloged
activation/projection, terminal/purge writers and deployment/recovery
authority remain absent. No migration, mode activation or HTTP wiring is
introduced; current-state matching remains `PROVENANCE_REQUIRED`. W06 stays
excluded and new-data recovery remains required.
