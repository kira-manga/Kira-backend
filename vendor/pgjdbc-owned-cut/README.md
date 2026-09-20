# Private pgjdbc owned cut — source recipe and production packaging candidate

**SOURCE-ONLY PACKAGING CANDIDATE / NOT PRODUCTION QUALIFIED.** The complete recipe
now reproduces the retained native05 Java sources and authors a distinct private
publication plus normal backend runtime selection. No build, dependency resolution,
publication or runtime test was run for this packaging change. W03 remains incomplete.

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

Only these nine upstream production Java files change:

1. `jdbc/KiraOwnedJdbcCut.java` (new)
2. `core/v3/KiraOwnedParameterBridge.java` (new)
3. `jdbc/PgConnection.java`
4. `jdbc/PgStatement.java`
5. `jdbc/PgPreparedStatement.java`
6. `jdbc/PgResultSet.java`
7. `core/v3/SimpleParameterList.java`
8. `core/v3/CompositeParameterList.java`
9. `jdbc/TypeInfoCache.java`

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
Patch04, previously omitted from `series`, corrects the nested-type annotation.
Patch05 consolidates the already-reviewed retained-state, fault-test and isolation-
getter corrections. The recipe's nine production and four test postimages match native05's
`sources-after.json`; it introduces no new driver behavior. Patch06 changes only
the upstream `pgjdbc/build.gradle.kts` publication configuration and root
`build.gradle.kts` NMCP remote-publication gate.

The finite test authorship uses the existing JUnit/Mockito/TestUtil dependencies. Native
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

## Normal runtime / private publication contract

The proposed, **unpublished and unqualified** coordinate is
`me.manga.kira.internal:postgresql-owned-cut:42.7.12-kira.1`.
`runtime-publication.json` pins the recipe, artifact form and remaining gates.

- Preserve upstream **project group `org.postgresql`**: it controls the existing
  `com.ongres` → `org.postgresql.shaded.com.ongres` relocation. Change Maven
  `groupId`/`artifactId` only, never the driver's package or project group.
- Build with the upstream wrapper in a fresh private source tree using
  `-Ppgjdbc.version=42.7.12-kira.1 -Prelease -Psigning.pgp.enabled=OFF`.
  Patch06 requires that version. The native `:postgresql:osgiJar` output is the
  default (no-classifier) artifact of publication `kiraOwnedCut`; neither the thin
  JAR nor the intermediate shadow JAR is the distribution. No new JAR assembler
  or binary-copy publication is introduced.
- Keep Java8/MR11, `META-INF/services/java.sql.Driver` → `org.postgresql.Driver`,
  OSGi metadata, shaded SCRAM3.2/stringprep2.2 and all upstream/dependency licenses.
  `org.checkerframework:checker-qual:3.55.1` stays external and is declared in both
  the private POM and the backend runtime. Do not add unshaded SCRAM transitives.
- Publication is artifact/POM-only, not the upstream shadow component's Gradle
  variants. The consumer requires the Maven POM and ignores module redirection.
  Patch06 retains upstream publishing repository/task registration, including
  NMCP's `nmcp` file repository (`build/nmcp/m2`). NMCP's publication callback
  requires `publishKiraOwnedCutPublicationToNmcpRepository` to exist; removing
  its repository caused the native06 configuration failure before any JAR/POM.
  The unchanged exact Maven task allowlist disables every inherited/stock/NMCP
  Maven publication task and all Maven-local tasks; only the selected private
  endpoint or explicit run-owned verification staging is eligible.
  Repository retention does **not** enable NMCP Central Portal publication.
  The root build separately gates both pinned NMCP1.4.4 remote task types
  (`NmcpPublishWithPublisherApiTask` and `NmcpPublishFileByFileToSnapshotsTask`)
  across root/subprojects, including Gradle-decorated subclasses. Their `onlyIf`
  predicate throws before execution/cache reuse. The upstream NMCP plugin,
  aggregation declarations and collection/check/local-file task registrations remain
  intact; inherited NMCP Maven staging actions remain disabled by the allowlist.
  The selected ordinary Maven staging task is not an NMCP remote task. This source
  correction has not been evaluated or executed; native06 remains a failed attempt.
- The private Maven HTTPS endpoint must be supplied by the owner as
  `KIRA_OWNED_PG_REPOSITORY_URL`; there is **no guessed/default registry**. Reads use
  explicit `KIRA_PACKAGES_USER`/`KIRA_PACKAGES_READ_TOKEN` only, **not ambient GitHub
  credentials**. URLs reject userInfo, query and fragment. Separately authorized publishing uses
  `KIRA_PACKAGES_USER`/`KIRA_PACKAGES_PUBLISH_TOKEN`; never put values in this recipe.
- Backend `runtimeOnly` supplies this same driver to ordinary runtime, `bootJar`
  and tests. Stock `org.postgresql:postgresql` is excluded from all configurations.
  The owned module is excluded from Maven Central and even opt-in Maven local;
  an absent private endpoint/publication fails resolution, with **no stock or
  retained-test-JAR fallback**. No `Test.classpath` overlay is a shipping path.

The already-declared `KiraSourceEngine` repository permits only group
`me.manga.kira.source`; it **cannot resolve this proposed internal group under its
current filter**. No credentials or package availability were probed. Do not launch
a runtime resolution expecting an unprovisioned remote publication to exist.

### Explicit local verification, without remote publication

The source proposal also supports
`-PkiraOwnedPgVerificationRepository=/absolute/run-owned/maven-directory` in both
the native and backend builds. This is not Maven local and has no default path.
It is mutually exclusive with `KIRA_OWNED_PG_REPOSITORY_URL`. A directory-existence
check is **not ownership proof**: primary must own/inventory the fresh staging path,
use isolated Gradle user homes/project caches, and authorize the bounded native
build and local staging step before execution.

That later step builds the frozen recipe through native `osgiJar` and the explicit
artifact/POM publication task
`:postgresql:publishKiraOwnedCutPublicationToKiraOwnedPgVerificationRepository`.
It must not copy/rename the retained native05 test JAR. The POM is marked
`kira.qualification=UNQUALIFIED_RUN_OWNED_VERIFICATION`. The backend resolves the
**same normal runtime GAV/POM**, through an exclusive content-filtered file Maven
repository, for affected runtime/`bootJar`/ordinary checks. No classpath overlay,
stock substitution, shared-cache replacement or remote credentials are involved.

In verification mode, `bootJar` **fails unless**
`-PkiraOwnedPgAllowNonShippingBootJar=true` explicitly authorizes a nonshipping
capture. This predicate runs even for otherwise cached/up-to-date tasks. The
resulting backend JAR has classifier `unqualified-owned-driver-verification`
and manifest `Kira-Owned-Driver-Provenance: UNQUALIFIED-RUN-OWNED-MAVEN`.
`bootBuildImage`/backend publication tasks refuse this mode. These labels are not
qualification: outputs remain private run-owned evidence, never a distribution.
No staging directory, local publication or resolution was created/run by this
author slice. Actual runtime/profile/provenance qualification and any private or
public distribution authorization remain separate holds.

This author slice changes `gradle.lockfile` only for the owned coordinate and
external checker-qual, in the same three runtime configurations. Those entries
are explicitly **source-authored, not a resolver-produced lock capture**. The
existing unrelated lock entries are unchanged.

## Remaining qualification / release holds

Reuse `review/working/app-29-driver-cut-native-build-05/` at workspace root:
native05 retained a new OSGi candidate and three passing isolation-getter methods;
its status is `FOCUSED3_PASS_NEW_CANDIDATE_UNQUALIFIED`. It does not grant Core47,
old73/10 replay, W03/App29 acceptance or private publication. Its JAR remains
test-only evidence and **must not be renamed/promoted as this publication**.

Only primary schedules the next authorized work. This recipe still needs native
packaging/POM evaluation, exact output provenance and private publication/access,
then one affected runtime lock resolution and the actual backend `bootJar`/ordinary
composition checks. Verify exactly one driver provider/ABI, retained MR11/SCRAM/
services/licenses and the external checker dependency in the resulting classpath.
The explicit local-verification lane can exercise those packaging/ordinary checks
without claiming that the remote publication already exists. Do not run
generic/public `publish`, overwrite stock caches, accept unrelated lock
drift or substitute a test overlay. Actual private publication, if separately
authorized, uses only
`:postgresql:publishKiraOwnedCutPublicationToKiraOwnedPgRepository`.

Historical review packets and binaries remain untouched. Reconstructing source
postimages and authoring this recipe is not evidence that these gates have run.

The source code deliberately does **not** infer native child disposition from
Connection.close, erase failed cleanup, replay/repair queues, upgrade ORIGINAL_PROVIDER,
or supply producer admission. Normal closed callbacks remain core guarded. Unknown
ordinary inputs/unsupported representations do not receive strict evidence. Successful
native integration and these contracts' actual runtime behavior remain unproved.
