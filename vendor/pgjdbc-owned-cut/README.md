# Private pgjdbc owned cut — source-only correction and finite tests

**UNCOMPILED / UNRUN / NOT ACCEPTED.** This directory is an ordered source patch,
not a qualified binary or a backend dependency change. W03 remains incomplete.

## Inputs and delivery

- Full upstream commit: `77df98e4e66c12936ded3478a0954f6f580bad99` (42.7.12).
- Archive identity and workspace checkout: `upstream.lock.json` (path relative
  to this directory). The archive was downloaded once and extracted without
  retaining another archive copy. The complete checkout and upstream licenses remain.
- Apply `series` in order from that upstream tree's root. Paths in the patch are
  upstream paths, not backend paths. `source-manifest.json` pins before/after bytes.
- `ABI.md` is the exact native/core handshake; it is not artifact qualification.
- `LICENSE.pgjdbc` preserves the upstream BSD-2-Clause notice; existing source
  headers are retained. Both new helpers use the same distribution license.

Only these eight upstream production Java files change:

1. `jdbc/KiraOwnedJdbcCut.java` (new)
2. `core/v3/KiraOwnedParameterBridge.java` (new)
3. `jdbc/PgConnection.java`
4. `jdbc/PgStatement.java`
5. `jdbc/PgPreparedStatement.java`
6. `jdbc/PgResultSet.java`
7. `core/v3/SimpleParameterList.java`
8. `core/v3/CompositeParameterList.java`

Paths above are relative to `pgjdbc/src/main/java/org/postgresql/`.
PgCallableStatement is an inspection dependency, unchanged: its pre-super borrow
is escrowed in the corresponding PgConnection factory/borrow reservation.

Patch01 is the preserved initial implementation; patch02 changes only the native
helper for the independently rereviewed unarmed-retention/hidden-child correction.
Patch03 adds exactly four test-source files and does not change production bytes:

- `pgjdbc/src/test/java/org/postgresql/core/v3/KiraOwnedParameterBridgeTest.java`
- `pgjdbc/src/test/java/org/postgresql/jdbc/KiraOwnedJdbcCutBatchTest.java`
- `pgjdbc/src/test/java/org/postgresql/jdbc/KiraOwnedJdbcCutRowsAndCloseTest.java`
- `pgjdbc-mockito-test/src/test/java/org/postgresql/jdbc/KiraOwnedJdbcCutFaultTest.java`

`test-source-manifest.json` pins these files and their exact module/FQCN selectors.
The finite authorship uses the existing JUnit/Mockito/TestUtil dependencies. Native
JDBC receivers are real; scoped faults affect lower dependencies or suppress an
actual after-add publication. No shipping callback was added. See
`review/working/app-29-driver-cut-native-tests-01/AUTHOR_REPORT.md` from the workspace
root for the authored matrix and exact unexercised fault windows. These are test
sources, not executed results or proof of backend GuardCall/Entry admission.

## Implemented source boundaries

- Entry-retained opening capsule, constructor-first connection ledger, actual
  executor/action/cleanable progress, and phase-safe terminal cleanup of unreturned
  records. Partial connections never become Entry.raw. Returned raw exclusion and
  constructor correspondence are separate facts. The existing post-producer,
  pre-timer cleanup really closes successfully returned hidden children; no child
  count is discharged from parent-close inference. Unarmed ordinary connections
  do not retain the owned child/construction history.
- All five native child factory reservations; immediate returned-query escrow;
  canonical native Life/counts and distinct immutable public Owner tuples.
- Actual parameter stores, explicit retaining stream lineage, eager conversion,
  OUT/copy/clear/append ranges, concrete composite metadata and ownerless empty content.
  Actual current content is validated/pinned before prepared copy/execution; queued
  content is independently validated at its final native drain.
- Actual base-column row stores/clears/OID replacement and exact row-to-hidden-
  prepared/list/translated-slot transfer frames. Inner eager binding does not erase
  the outer row's dependencies.
- Independent query/parameter append occurrences and rewrite installs. Native
  batch entry/exit covers prepared-to-base delegation, empty/PRE and real reentry.
  Actual arrays are escrowed immediately. A final closed image/Life walk precedes
  the two real native clears; added instructions between them are prepared stores.
  A survives native return through core bookkeeping/actualEnd; subsequent Q is separate.
- Native Statement CAS winner and full ResultSet public/internal close extents.
  Hidden cleanup failure is sticky before native swallowing, without replacing SQL
  exceptions. Revocation never fabricates first-close success or all-child disposal.
- Missing mutation/dispatch correspondence retains uncertainty. Cleanup-ended live
  child custody is visible even without a Root. Only outermost original-thread end
  compacts successful records; foreign cancel never mutates that graph.

## Acceptance gates (still incomplete)

The initial connected source review and focused native correction rereview are
recorded separately; the focused review found R1/R2 addressed at source level, not
accepted at runtime. The four-file test-only freeze still needs independent review.
Only primary schedules the private driver build plus affected native/core checks.
The correction-only evidence is preserved under
`review/working/app-29-driver-cut-native-correction-01/`; its historical hashes are
not overwritten by this test-source delivery.

The intended packaging lane is upstream `:postgresql:osgiJar`, preserving internal
relocation, Java8/MR11, services, OSGi, shaded SCRAM and licenses. A later build must
give the binary a distinct private publication identity and pin actual inputs/output;
never overwrite the stock driver cache or publicly publish this patch by default.
No artifact, dependency, build configuration, service, CI, commit or publication was
changed/run in this native author slice. No compiler, test, static check or build
was executed. Only the authorized four test-source files were added after the
production correction freeze.

The source code deliberately does **not** infer native child disposition from
Connection.close, erase failed cleanup, replay/repair queues, upgrade ORIGINAL_PROVIDER,
or supply producer admission. Normal closed callbacks remain core guarded. Unknown
ordinary inputs/unsupported representations do not receive strict evidence. Successful
native integration and these contracts' actual runtime behavior remain unproved.
