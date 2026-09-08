# Backend-Owned Complaint Schema — V14

## Status and boundaries

[`V14__backend_owned_complaints.sql`](../src/main/resources/db/migration/V14__backend_owned_complaints.sql)
adds storage for the App #29 complaint/feedback/moderation migration. **Schema support is not an
enabled or production-ready complaint service.** It adds no endpoint, credential, active writer,
accepted catalog or usable capacity. Authentication, locked writers, journal verification,
restore quarantine, mobile/Admin integration and the legacy cutover remain separate gates.

Flyway owns this additive, transactional migration. V1–V13 remain unchanged; Hibernate must not
generate these tables. Do not use `repair`, baseline-on-migrate or destructive schema cleanup to
force a failed upgrade through. A failed transaction must leave the previous schema/data intact;
after a deployed migration, recovery is a reviewed forward migration, not an edited V14.

## Identity and content

Scoped complaint rows carry an explicit scope. The all-zero UUID means live data and requires
`test_only=false`; a test scope is a variant-2/version-4 UUID with `test_only=true`. Composite foreign
keys preserve scope. Capacity counters are global, and global catalog mutations may leave both
scope fields null. SQL UUID columns do not validate incoming wire spelling; API parsers must.

| Table | Stored responsibility and vocabulary |
|---|---|
| `complaint_installation_ids` | Permanent identity reservation: `ACTIVE`, `DELETION_PENDING`, `RECOVERY_RESERVED`, `DELETED`, `RETIRED`. A recovery reservation is not an enrollable credential. |
| `app_installations` | Scoped verifier and positive credential/row versions. `ACTIVE`/`DELETION_PENDING` retain platform, opaque owner reference and activity. `DELETED` clears those fields and retains only replay credential state for 192 elapsed hours. |
| `complaint_resource_ids` | Permanent resource reservation: `LIVE`, `DELETION_PENDING`, `DELETED`. Reply parents reference this table, not erasable parent content. |
| `complaints` | Versioned `REPORT`, `REPLY` or `NOTICE`, with ownership `INSTALLATION`, `LEGACY_UNCLAIMED` or `SYSTEM`. Notices are system-owned, pinned, keyed and contain no prose/diagnostics. |

Ordinary statuses are `OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED`, `PLANNED`, `PINNED` and
`NOT_PLANNED`; `UNKNOWN` is legacy-only. Types are `TECHNICAL`, `LANGUAGES`, `SITES_ADD`,
`SITE_ERROR`, `FEATURES` and `CUSTOM`. Normal closed content requires an Admin actor, reason and
time; only unclaimed legacy content may use incomplete `LEGACY` closure provenance. Leaving
`CLOSED` requires all closure fields to be null.

Stored subject/body/closure limits are respectively 200/1000/500 code points and 800/4000/2000
UTF-8 bytes, with nonempty values and forbidden-control checks. These are persistence limits, not
the narrower create-operation rules. Installation-owned content requires platform, OS,
manufacturer and model fields; the three diagnostic strings may be empty. Missing legacy
diagnostics remain null. Notice-thread replies use a notice key and no subject. Only the explicit
legacy `AMBIGUOUS_NOTICE_PARENT` case permits a reply with no parent.

## Mutation and recovery records

| Table | Stored responsibility and vocabulary |
|---|---|
| `complaint_idempotency_receipts` | Globally unique `(actor_kind, actor_id, idempotency_key)` across operations/scopes. `IN_PROGRESS`, `AUTHORIZED_DELETE`, or `COMPLETED` with `APPLIED`/allowlisted `REJECTED` outcome. |
| `installation_deletion_receipts` | Special `(installation_id, deletion_key)` replay record, submitted credential version and scoped permanent reservation. Completed success is `APPLIED`/204. |
| `complaint_journal_publications` | Durable intent: exact `kcj-1` bytes/hash, deterministic key and writer/epoch bindings; `PREPARED`, `VERIFIED`, `APPLIED`. |
| `complaint_deletion_journal_applied` | Permanent exact `(object_key, object_version)` application evidence. Event ID lookup is deliberately nonunique; no FK requires the compactable publication to survive. |
| `complaint_deletion_journal_retirements` | Live ordinary-event `AUTHORIZED`/`COMPLETED` retirement evidence, retaining both catalog decisions and an exact restrictive applied-evidence FK. |
| `complaint_recovery_capacity_reservations` | Immutable event identity and versioned `RESERVED`/`CONVERTED` charge vectors, with a separate nullable publication reference. |

Receipts store minimal typed acknowledgements, versions, ETag/Location or closed problem codes,
not response prose/content snapshots. Completed receipt expiry is exactly completion + **192
hours**, including DST boundaries. `IN_PROGRESS` is transaction-local by the future writer
contract; the database alone cannot prevent committing it. Authorization records have no result
or expiry. Authentication, parsing, throttling and infrastructure failures are not stored as
terminal rejected mutations.

Event target bounds are: owner/Admin single delete 1; Admin batch, retention and installation
retirement 1–50; owner delete-all 0–100. Test-only installation manifests allow 1–500 and test-run
purge 0. Neither is an ordinary `APPLIED` event. Epoch seals live in control/catalog records rather
than the publication event set they seal. Event IDs use canonical 43-character unpadded Base64url
SHA-256 spelling, including zero padding bits.

## Control, capacity and import

| Table | Stored responsibility and vocabulary |
|---|---|
| `complaint_capacity_counters` | 22 fixed version-1 counters; nonnegative hard/creation/free/actual/recovery/test reservations. Durable daily enrollment bucket exists only on `installation_ids`. |
| `complaint_test_runs` | `ACTIVE`, `SEALED`, `PURGING`, `PURGED`, reserved capacity and complete terminal manifest/seal/denial/catalog evidence. At most one nonterminal run. |
| `complaint_catalog_mutations` | `PREPARED`/`COMPLETED` catalog intent; frozen ordered `SINGLE`/`ROTATION_OVERLAP` signer policy, partial signatures, complete envelope and dual-copy evidence. At most one prepared or completed-unprojected mutation. |
| `complaint_journal_control` | Scope/epoch/configuration/restore bindings, separate scan and retention leases, closed gates, seal intent and full checkpoint descriptor. |
| `complaint_journal_scan_runs` | Two separately bounded passes; `SCANNING`, `COMPLETE`, `ABANDONED`, fencing and count/byte totals. |
| `complaint_journal_scan_entries` | Exact scoped scan/pass/key/version identity; `PENDING`, `APPLIED`, `VERIFIED_ONLY`, `RETIRED`. |
| `complaint_import_runs` | One frozen live legacy snapshot; `STAGING`, `SEALED`, `PROMOTED`, `ABORTED`. At most one staging/sealed run and one promoted snapshot. |
| `complaint_import_staging` | Bounded normalized `ACCEPTED`/`REJECTED` records, assigned identities and HMAC-based source mapping; no raw legacy-name/device-ID column. |
| `complaint_import_artifacts` | `PREPARED`/`VERIFIED` encrypted export/restore-map references and exact expected/verified-copy descriptors, not full encrypted exports in database rows. |
| `complaint_legacy_records` | `PROMOTED`/`RESTORED` identity mapping, separate nullable content reference and cutoff + 13 UTC calendar-month expiry. |

Counter/vector ordinals are fixed schema encoding, not inferred dynamically:

```text
 1 app_installations       2 audit_rows           3 catalog_mutations
 4 complaint_rows          5 import_artifacts     6 import_runs
 7 import_staging          8 installation_ids     9 installation_receipts
10 journal_applied        11 journal_control     12 journal_publications
13 journal_retirements    14 legacy_records      15 moderation_grants
16 normal_receipts        17 recovery_reservations
18 resource_ids           19 scan_entries        20 scan_runs
21 storage_bytes          22 test_runs
```

Each row enforces `hard = free + actual + recovery_reserved + test_reserved`, casting every term
to `numeric` before addition. Vectors have exactly 22 nonnull, nonnegative elements, one dimension
and lower bound 1; conversion/unused vectors cannot exceed their originals componentwise.
Changing encoding requires a migration. `storage_bytes` is a conservative logical charge, **not
measured physical PostgreSQL/MVCC/index usage**. Charge calculation, aggregate reconciliation,
lock ordering and physical-size/vacuum operational thresholds still require implementation/proof.

Only 22 closed zero-capacity rows and one live control row are seeded. The control row has
maintenance/creation closed, scan requested, epoch/desired generation/schema 1, lease tokens 0,
and no writer, configuration hash, catalog or checkpoint. Fixed baseline rows are accounted
separately from variable rows. Do not enable service by manually changing these seeds: opening
requires authenticated configuration and drained reconciliation.

## Exact bytes, foreign keys and indexes

- Hashes/verifiers are 32-byte `bytea`. Canonical event/manifest/seal and most evidence descriptors
  are independently size/hash-bound at 64 KiB; approval/context descriptors use 4 KiB. Catalog
  unsigned bytes and complete envelope each have an independent 8 MiB cap. Hash equality and a
  `kcj-1` label do **not** prove canonical syntax, signatures, provider provenance or authority.
- Keys/opaque versions are bounded to 1024 UTF-8 bytes and use `C` collation; generated object
  keys require printable ASCII. Versions are opaque, not UUIDs, and reject the literal `null`.
  Routing/signer IDs are bounded ASCII. Individual signatures are capped at 1024 bytes as a
  supported-algorithm limit, not a cloud provider maximum; larger algorithms require a reviewed
  migration and charge update before use. Never truncate a provider value.
- Populated timestamps must be finite. Helpers use parsed SQL `RETURN` bodies, binding dependencies
  at creation so dump/restore works with an empty `search_path`, without hardcoding `public`.
- Content/credential/resource/parent/closure and evidence links are restrictive, not cascading
  erasure. The special receipt's reservation FK permits parent creation before commit, but
  `ON DELETE RESTRICT` remains immediate even when the FK is deferrable.
- Only recovery `publication_ref` and legacy `complaint_ref` detach via column-specific
  `SET NULL`, preserving scope and permanent identities. A present reference must equal its
  retained event/assigned identity. Reserved recovery rows cannot detach. SQL does not authorize
  compaction: completed authenticated retirement must be checked by the writer.
- Owner pagination indexes order `(created_at DESC,id DESC)` under owner/scope. Admin scope,
  status/type/ownership indexes order `(updated_at DESC,id DESC)`; `simple` GIN search uses the
  exact coalesced subject/body expression. Parent/closure/legacy FK lookups have supporting indexes.
- Partial completed-receipt expiry indexes include the complete tie-breaker tuple. Pending
  publication and scan/replay/scope indexes support bounded worker traversal. Preserve all cursor
  members, including object **version**; equal timestamps/keys are not unique cursors. Prepared
  generic/custom plans must be tested on populated statistics; a small synthetic plan is not a
  production latency/capacity benchmark. Large byte descriptors are not B-tree key/INCLUDE values.

V14 also extends `audit_log` with nullable complaint scope/actor columns and a closed
`COMPLAINT_*` action vocabulary. Complaint Admin actions require a user; installation/system
actions forbid one. Other actions keep both new fields null. Existing scalar audit-detail rules
still apply. `admin_step_up_grants` accepts both `source-admin-mutation` and
`complaint-moderation-mutation`; accepting a stored scope does not issue proof or authorize a route.

## Verification and remaining writer obligations

The synthetic suites in `src/test/kotlin/me/manga/kira/backend/database/complaint/` cover fresh
PostgreSQL 17.6 UTF8 and every V1–V13 upgrade, historical checksums, populated preservation,
transaction rollback, exact constraint failures, finite/byte/array boundaries and prepared queries.
`DatabaseBackupRestoreIT` covers V14 dump/restore/no-op migration and V13 dump/restore/V14 upgrade
using committed fixtures and independent connections. Exact byte/row/sequence and structural
comparisons supplement explicit nonempty state witnesses and negative controls.

`v14-backup-states.sql` is an opt-in backup fixture, not part of the one-row constraint fixture.
It includes scoped pending content, cleared replay credentials, receipts/outbox phases, compacted
permanent evidence, partial signatures and terminal/scoped control states. These are synthetic
storage specimens, not authenticated recovery events. `fixtures/complaint/reset.sql` is destructive
**disposable-test-only** cleanup, never a production retention/purge procedure.

From the backend directory, with disposable Docker available:

```sh
./gradlew test --tests 'me.manga.kira.backend.database.*' \
  --tests 'me.manga.kira.backend.complaint.domain.*' \
  --tests 'me.manga.kira.backend.sourceconfig.Flyway*'
./gradlew --stop
```

Preserve required test reports, then run `./gradlew clean` and `./gradlew --stop` again. Stop owned
test services; never prune unrelated containers, source, secrets, signing material or global caches.
Run the full backend `check` gate before acceptance; test presence alone is not a passing result.

Subsequent work must prove authenticated installation/Admin boundaries, same-connection capacity/
audit/grant transactions, consistent receipt/installation/resource lock order, epoch/fencing rules,
no external I/O under database ownership, durable-before-network deletion, exact-version verified
evidence, retention/compaction authority, import reconciliation and restore replay convergence.
Rows labelled `VERIFIED`, `APPLIED`, `SUCCESS` or `PURGED` do not themselves establish those facts.
Physical devices, deployed PostgreSQL/Redis/storage, cloud fencing, legacy freeze/denial, stores
and privacy approval remain external gates. Backend #28/#29 remain independent deployment/recovery
prerequisites; this schema migration is not permission to deploy or cut over Firebase.
