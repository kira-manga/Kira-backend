package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationTestClock
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunProjectedV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.CancellationException

internal enum class TestActivationProjectionSqlCut(val step: String? = null, val auditOrdinal: Int? = null) {
    BEFORE_PROJECT_ARM,
    AFTER_COUNTERS("charge:test_runs"), AFTER_RUN("test-project-run"), AFTER_CONTROL("test-project-control"),
    AFTER_RESOURCES("test-project-resources"), AFTER_NOTICES("test-project-notices"),
    AUDIT_ONE("test-project-audit:0", 1), AUDIT_TWO("test-project-audit:1", 2), AUDIT_THREE("test-project-audit:2", 3), AUDIT_FOUR("test-project-audit:3", 4),
    AFTER_MARKER("test-project"), AFTER_CLEAR_PENDING("test-clear-pending"),
    BEFORE_COMMIT, DEFERRED_COMMIT_ROLLBACK_UNKNOWN, KNOWN_COMMITTED_AFTER_COMMIT,
}
internal enum class TestActivationProjectionReplayCut {
    PREPARED, EXACT_PROJECTED, MISSING_COMPLETION_OUTCOMES, MISSING_PROJECT_ARM, PARTIAL_NOTICE,
    COHERENT_COUNTER_DRIFT, OLD_PENDING_CATALOG_BACKUP, CONTRADICTORY_PROJECT_OUTCOME, PROJECT_LEASE_FLOOR,
}
internal enum class TestActivationProjectionLifetimeCut {
    BEGIN_DEADLINE, SECOND_RAW_DEADLINE, READ_CLIENT_CLOSE, CANCELLATION_AFTER_RUN, ROOT_CLOSE, PHASE_RELEASE,
}

/**
 * Actual DML/PG rollback, known COMMITTED callback failure and retained native/root/phase failures.
 * The separate CatalogTestRunActivationProjectLostCommitCase uses real PostgreSQL through an
 * opaque TLS forwarder. Neither callback failure nor deferred-constraint rollback below is
 * renamed to claim committed-UNKNOWN transport loss.
 */
internal object CatalogTestRunActivationProjectionRecoveryCases {
    fun sqlCut(
        tls: VersionBoundPersistenceConnectedFixture,
        clock: DesiredInstallationTestClock,
        cut: TestActivationProjectionSqlCut,
    ) = withPendingProjectionRows(tls) { p ->
        p.fresh(clock = clock) { failing ->
            val original = p.begin(failing)
            val budget = original.budget
            val before = p.image()
            val probe = p.f.signed.probe(failing)
            var injected = false
            var phase: PersistencePhaseContext? = null
            var afterCommit = false
            clock.onSample = {
                if (cut == TestActivationProjectionSqlCut.BEFORE_PROJECT_ARM && !injected && PersistencePhaseOwnership.current() == null &&
                    p.releasedProjectionReloadCount(probe) == 2 &&
                    !p.f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED)) {
                    injected = true
                    throw IOException("Synthetic cut after the released recheck, before an original PROJECT arm.")
                }
            }
            probe.afterSql = { step -> if (p.currentProjectPhase()) {
                phase = checkNotNull(PersistencePhaseOwnership.current())
                if (step == cut.step) {
                    injected = true
                    cut.auditOrdinal?.let { ordinal -> assertEquals(6L + ordinal, p.effectCount(p.holder(failing)), "The original JDBC audit stage really inserted before the cut.") }
                    assertEquals(before, p.image(), "The actual intermediate DML is not yet externally committed.")
                    throw IOException("Synthetic cut after an actual PROJECT statement.")
                }
                if (step == "test-clear-pending") when (cut) {
                    TestActivationProjectionSqlCut.BEFORE_COMMIT -> TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) {
                            injected = true
                            assertEquals(10L, p.effectCount(p.holder(failing)))
                            assertEquals(before, p.image())
                            throw IOException("Synthetic PROJECT failure before native commit.")
                        }
                    })
                    TestActivationProjectionSqlCut.DEFERRED_COMMIT_ROLLBACK_UNKNOWN -> {
                        injected = true
                        val jdbc = JdbcTemplate(failing.pools.catalogCoordinator.dataSource)
                        jdbc.execute("CREATE TEMP TABLE kira_projection_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, jdbc.update("INSERT INTO kira_projection_commit_cut VALUES (1), (1)"))
                    }
                    TestActivationProjectionSqlCut.KNOWN_COMMITTED_AFTER_COMMIT -> TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() {
                            injected = true
                            afterCommit = true
                            throw IOException("Synthetic PROJECT completion callback failed after an acknowledged commit.")
                        }
                    })
                    else -> Unit
                }
            } }
            try {
                assertThrows<CatalogTestRunActivationExceptionV1> { p.project(original) }
            } finally {
                clock.onSample = {}
                probe.afterSql = {}
            }
            assertTrue(injected, cut.name)
            assertFalse(poolTestField<Boolean>(original, "allowedProjectedResult"))
            clock.assertNoLostAssertions()
            p.f.assertNoLostAssertions()
            val committed = cut == TestActivationProjectionSqlCut.KNOWN_COMMITTED_AFTER_COMMIT
            assertEquals(committed, afterCommit)
            if (committed) p.assertProjected() else assertEquals(before, p.image(), "Every inserted row, audit, counter and marker rolls back together.")
            if (cut != TestActivationProjectionSqlCut.BEFORE_PROJECT_ARM) {
                val expected = when (cut) {
                    TestActivationProjectionSqlCut.KNOWN_COMMITTED_AFTER_COMMIT -> PersistenceDatabaseOutcome.COMMITTED
                    TestActivationProjectionSqlCut.DEFERRED_COMMIT_ROLLBACK_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
                    else -> PersistenceDatabaseOutcome.ROLLED_BACK
                }
                assertEquals(expected, checkNotNull(phase).databaseOutcome())
            }
            if (cut == TestActivationProjectionSqlCut.DEFERRED_COMMIT_ROLLBACK_UNKNOWN) {
                assertTrue(poolTestField<Boolean>(original, "outcomeUncertain"))
                p.assertSticky(original, failing)
            }
            assertEquals(cut != TestActivationProjectionSqlCut.BEFORE_PROJECT_ARM, p.f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED))
            assertFalse(p.f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME))
            val leaves = p.f.signed.leaves()
            val oldPhase = ownedCutField(original, "originalPhase")
            val oldSlot = SignedActivationObservation.active(failing.pools.catalogCoordinator)
            val afterFailure = p.image()
            p.fresh(previous = failing) { fresh ->
                val retry = p.begin(fresh)
                p.assertProjected(p.project(retry))
                p.assertReleased(retry, fresh)
                val retried = p.f.signed.probe(fresh)
                assertEquals(if (committed) 0 else 1, retried.steps.count { it == "test-project" })
                if (committed) {
                    assertEquals(afterFailure, p.image(), "Known-committed cold reconciliation must not churn xmin/timestamps or charge again.")
                    p.assertNoProjectDml(retried)
                }
                leaves.forEach { (path, value) -> assertEquals(value, p.f.signed.leaves()[path]) }
                assertEquals(cut == TestActivationProjectionSqlCut.BEFORE_PROJECT_ARM,
                    p.f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME),
                    "A fresh success writes only its own arm's outcome, never an interrupted older acquisition's outcome.")
                assertSame(budget, original.budget)
                assertSame(oldPhase, ownedCutField(original, "originalPhase"))
                assertSame(oldSlot, SignedActivationObservation.active(failing.pools.catalogCoordinator))
                assertFalse(poolTestField<Boolean>(original, "allowedProjectedResult"))
                if (cut == TestActivationProjectionSqlCut.DEFERRED_COMMIT_ROLLBACK_UNKNOWN) {
                    assertEquals(PersistenceDatabaseOutcome.UNKNOWN, checkNotNull(phase).databaseOutcome())
                    p.assertSticky(original, failing)
                }
                p.assertReadOnlyProviders()
            }
        }
    }

    fun coldReplay(
        tls: VersionBoundPersistenceConnectedFixture,
        clock: DesiredInstallationTestClock,
        cut: TestActivationProjectionReplayCut,
    ) {
        when (cut) {
            TestActivationProjectionReplayCut.PREPARED -> withCompletionActivationRows(tls) { f ->
                val before = f.rows.preparedRow()
                val counters = f.rows.counters.snapshot()
                val leaves = f.signed.leaves()
                f.signed.withFreshOwner { fresh ->
                    val original = f.begin(fresh)
                    assertThrows<CatalogTestRunActivationExceptionV1> {
                        original.projectCompleted(f.signed.root, f.rows.intent, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
                    }
                    f.assertCleanFailure(original, fresh)
                    assertFalse(poolTestField<Boolean>(original, "allowedProjectedResult"))
                    f.assertPrepared()
                    assertEquals(before, f.rows.preparedRow())
                    assertEquals(counters, f.rows.counters.snapshot())
                    assertEquals(leaves, f.signed.leaves())
                    assertEquals(0, f.http.put.createdClients)
                    assertEquals(1, f.signed.signing.createdClients)
                    assertEquals(1, f.signed.signing.requests.size)
                    assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED))
                }
            }
            TestActivationProjectionReplayCut.MISSING_COMPLETION_OUTCOMES -> withMissingCompletionOutcomes(tls, clock) { p ->
                p.fresh { fresh ->
                    val original = p.begin(fresh)
                    p.assertProjected(p.project(original))
                    p.assertReleased(original, fresh)
                    assertFalse(p.f.signed.exists(CatalogTestRunActivationReleaseLeafV1.COMPLETE_OUTCOME))
                    assertFalse(p.f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PENDING_RELOAD_OUTCOME))
                    assertTrue(p.f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME))
                    p.assertReadOnlyProviders()
                }
            }
            else -> withPendingProjectionRows(tls) { p ->
                p.fresh { projecting ->
                    val original = p.begin(projecting)
                    val projected = p.project(original)
                    p.assertProjected(projected)
                    p.assertReleased(original, projecting)
                    val oldLease = p.f.rows.lease()
                    p.fresh(previous = projecting) { cold ->
                        corruptProjectedInput(p, cut, projected, oldLease)
                        val negative = p.image()
                        val leaves = p.f.signed.leaves()
                        val lease = p.f.rows.lease()
                        val replay = p.begin(cold)
                        if (cut == TestActivationProjectionReplayCut.EXACT_PROJECTED) {
                            val receipt = p.project(replay)
                            p.assertProjected(receipt)
                            assertEquals(projected.projectedAt, receipt.projectedAt)
                            p.assertReleased(replay, cold)
                            assertEquals((oldLease["lease_token"] as Long) + 1L, p.f.rows.lease()["lease_token"])
                        } else {
                            assertThrows<CatalogTestRunActivationExceptionV1> { p.project(replay) }
                            p.assertCleanFailure(replay, cold)
                            if (cut in setOf(TestActivationProjectionReplayCut.MISSING_PROJECT_ARM, TestActivationProjectionReplayCut.CONTRADICTORY_PROJECT_OUTCOME,
                                    TestActivationProjectionReplayCut.PROJECT_LEASE_FLOOR)) {
                                assertEquals(lease, p.f.rows.lease(), "Missing/contradictory custody and a historical PROJECT token floor cannot mint a replacement lease.")
                            }
                        }
                        assertEquals(negative, p.image(), "Cold exact replay or refusal is read-only for every effect/counter/catalog byte and xmin.")
                        assertEquals(leaves, p.f.signed.leaves(), "No old outcome, partial effect, notice, backup or lease floor is repaired.")
                        p.assertNoProjectDml(p.f.signed.probe(cold))
                        p.assertReadOnlyProviders()
                    }
                }
            }
        }
    }

    fun originalLifetime(
        tls: VersionBoundPersistenceConnectedFixture,
        clock: DesiredInstallationTestClock,
        cut: TestActivationProjectionLifetimeCut,
    ) = withPendingProjectionRows(tls) { p ->
        p.fresh(clock = clock) { failing ->
            val original = p.begin(failing)
            val budget = original.budget
            val before = p.image()
            val probe = p.f.signed.probe(failing)
            var injected = false
            var fileClosed: (() -> Boolean)? = null
            var phase: PersistencePhaseContext? = null
            val key = Any()
            val sentinel = Any()
            var bound = false
            if (cut == TestActivationProjectionLifetimeCut.BEGIN_DEADLINE) {
                clock.extraNanos += 30_000_000_000L
                injected = true
            }
            p.f.http.beforeRead = {
                p.f.signed.releasedSql()
                if (cut == TestActivationProjectionLifetimeCut.SECOND_RAW_DEADLINE && !injected && probe.calls.any { it.path == PROJECT_RELOAD }) {
                    clock.extraNanos += 10_000_000_001L // Expire this <=10s raw round, not a fabricated wall/DB lease.
                    injected = true
                }
            }
            p.f.http.afterReadClientClose = {
                p.f.signed.releasedSql()
                if (cut == TestActivationProjectionLifetimeCut.READ_CLIENT_CLOSE && !injected) {
                    injected = true
                    throw IOException("Synthetic read client physically closed but lost its original cleanup return.")
                }
            }
            probe.afterSql = { step -> if (p.currentProjectPhase()) {
                phase = checkNotNull(PersistencePhaseOwnership.current())
                if (step == "test-project-run" && cut == TestActivationProjectionLifetimeCut.CANCELLATION_AFTER_RUN) {
                    injected = true
                    throw CancellationException("Synthetic cancellation after actual run insertion.")
                }
                if (step == "test-clear-pending" && cut == TestActivationProjectionLifetimeCut.ROOT_CLOSE) {
                    fileClosed = p.f.signed.failOriginalFileClose(original)
                    injected = true
                }
                if (step == "test-clear-pending" && cut == TestActivationProjectionLifetimeCut.PHASE_RELEASE) {
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() {
                            TransactionSynchronizationManager.bindResource(key, sentinel)
                            bound = true
                            injected = true
                            throw IOException("Synthetic original PROJECT phase completion failed with a retained resource.")
                        }
                    })
                }
            } }
            try {
                if (cut == TestActivationProjectionLifetimeCut.CANCELLATION_AFTER_RUN) assertThrows<CancellationException> { p.project(original) }
                else assertThrows<CatalogTestRunActivationExceptionV1> { p.project(original) }
                if (cut == TestActivationProjectionLifetimeCut.PHASE_RELEASE) {
                    assertTrue(bound)
                    assertSame(phase, PersistencePhaseOwnership.current())
                    assertTrue(checkNotNull(phase).quarantined())
                }
            } finally {
                p.f.http.beforeRead = p.f.signed::releasedSql
                p.f.http.afterReadClientClose = {}
                probe.afterSql = {}
                if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
                requireConnectionFree() // Physical same-phase retirement is not a repaired original success.
            }
            assertTrue(injected)
            assertFalse(poolTestField<Boolean>(original, "allowedProjectedResult"))
            assertSame(budget, original.budget)
            clock.assertNoLostAssertions()
            p.f.assertNoLostAssertions()
            val committed = cut in setOf(TestActivationProjectionLifetimeCut.ROOT_CLOSE, TestActivationProjectionLifetimeCut.PHASE_RELEASE)
            if (committed) p.assertProjected() else assertEquals(before, p.image())
            if (cut == TestActivationProjectionLifetimeCut.ROOT_CLOSE) assertTrue(checkNotNull(fileClosed).invoke())
            if (cut == TestActivationProjectionLifetimeCut.BEGIN_DEADLINE) {
                assertThrows<PersistenceBoundaryException> { budget.remainingMillis(1) }
                assertNull(SignedActivationObservation.active(failing.pools.catalogCoordinator))
                assertTrue(probe.calls.isEmpty())
            } else if (cut != TestActivationProjectionLifetimeCut.SECOND_RAW_DEADLINE) p.assertSticky(original, failing)
            if (cut == TestActivationProjectionLifetimeCut.CANCELLATION_AFTER_RUN) {
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, checkNotNull(phase).databaseOutcome())
                assertFalse(p.f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME))
            }
            if (cut == TestActivationProjectionLifetimeCut.PHASE_RELEASE) {
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(phase).databaseOutcome())
                assertTrue(checkNotNull(phase).failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
                assertTrue(poolTestField<Boolean>(original, "sqlCleanupUnproven"))
                assertFalse(p.f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME))
            }
            val calls = probe.calls.size
            val reads = p.f.http.read.createdClients
            val leaves = p.f.signed.leaves()
            val oldSlot = SignedActivationObservation.active(failing.pools.catalogCoordinator)
            val oldPhase = ownedCutField(original, "originalPhase")
            assertThrows<Exception> { p.project(original) }
            assertThrows<Exception> { p.f.reloadPending(original) }
            assertEquals(calls, probe.calls.size)
            assertEquals(reads, p.f.http.read.createdClients)
            val failedImage = p.image()
            p.fresh(previous = failing) { fresh ->
                val retry = p.begin(fresh)
                p.assertProjected(p.project(retry))
                p.assertReleased(retry, fresh)
                if (committed) {
                    assertEquals(failedImage, p.image())
                    p.assertNoProjectDml(p.f.signed.probe(fresh))
                }
                assertSame(budget, original.budget)
                assertSame(oldSlot, SignedActivationObservation.active(failing.pools.catalogCoordinator))
                assertSame(oldPhase, ownedCutField(original, "originalPhase"))
                assertFalse(poolTestField<Boolean>(original, "allowedProjectedResult"))
                leaves.forEach { (path, value) -> assertEquals(value, p.f.signed.leaves()[path]) }
                if (cut == TestActivationProjectionLifetimeCut.BEGIN_DEADLINE) assertThrows<PersistenceBoundaryException> { budget.remainingMillis(1) }
                else if (cut != TestActivationProjectionLifetimeCut.SECOND_RAW_DEADLINE) p.assertSticky(original, failing)
                p.assertReadOnlyProviders()
            }
        }
    }

    private fun withMissingCompletionOutcomes(
        tls: VersionBoundPersistenceConnectedFixture,
        clock: DesiredInstallationTestClock,
        action: (ProjectionActivationObservation) -> Unit,
    ) = withCompletionActivationRows(tls) { f ->
        f.rows.retainProjectionRows()
        f.http.replicateOnPut = true
        f.signed.withFreshOwner(nanoClock = clock) { publishing ->
            val original = f.begin(publishing)
            val probe = f.signed.probe(publishing)
            val publishingCaller = Thread.currentThread()
            var injected = false
            clock.onSample = sample@{
                // The scanner samples this clock too; this post-release cut belongs only to the publishing caller.
                if (Thread.currentThread() !== publishingCaller) return@sample
                val complete = probe.calls.lastOrNull { it.step == "test-mark-pending" }?.phase
                if (!injected && PersistencePhaseOwnership.current() == null && complete?.databaseOutcome() == PersistenceDatabaseOutcome.COMMITTED &&
                    !f.signed.exists(CatalogTestRunActivationReleaseLeafV1.COMPLETE_OUTCOME)) {
                    injected = true
                    throw IOException("Synthetic completed SQL cut before writing the original COMPLETE/PENDING outcomes.")
                }
            }
            try { assertThrows<CatalogTestRunActivationExceptionV1> { f.deliver(original) } } finally { clock.onSample = {} }
            assertTrue(injected)
            clock.assertNoLostAssertions()
            f.assertPending()
            assertTrue(f.signed.complete(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED))
            assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.COMPLETE_OUTCOME))
            assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PENDING_RELOAD_OUTCOME))
            action(ProjectionActivationObservation(f, publishing))
        }
    }

    private fun corruptProjectedInput(
        p: ProjectionActivationObservation,
        cut: TestActivationProjectionReplayCut,
        projected: CatalogTestRunProjectedV1,
        oldLease: Map<String, Any>,
    ) {
        // Only explicitly owned negative fixture inputs, after the actual original process has closed and its DB lease expired.
        when (cut) {
            TestActivationProjectionReplayCut.MISSING_PROJECT_ARM -> {
                Files.delete(p.f.signed.path(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED))
                Files.delete(p.f.signed.marker(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED))
            }
            TestActivationProjectionReplayCut.PARTIAL_NOTICE -> assertEquals(1, p.f.rows.observer.update(
                "DELETE FROM complaints WHERE id = ? AND data_scope_id = ?", UUID.fromString(p.run.noticeSeeds[0].resourceId), p.scope,
            ))
            TestActivationProjectionReplayCut.COHERENT_COUNTER_DRIFT -> {
                assertEquals(1, p.f.rows.observer.update(
                    "UPDATE complaint_capacity_counters SET actual_units = actual_units + 1, free_units = free_units - 1 " +
                        "WHERE name = 'storage_bytes' AND free_units >= 1",
                ))
                assertEquals(true, p.f.rows.observer.queryForObject(
                    "SELECT bool_and(hard_limit::numeric = free_units::numeric + actual_units::numeric + recovery_reserved_units::numeric + test_reserved_units::numeric) " +
                        "FROM complaint_capacity_counters", Boolean::class.java,
                )) // Exact reserve and every actual lower bound still pass; the original arm's exact settlement must differ.
            }
            TestActivationProjectionReplayCut.OLD_PENDING_CATALOG_BACKUP -> {
                assertEquals(1, p.f.rows.observer.update("UPDATE complaint_catalog_mutations SET projected_at = NULL WHERE operation_token = ?", p.f.signed.token))
                assertEquals(1, p.f.rows.observer.update("UPDATE complaint_journal_control SET pending_projection_token = ? WHERE data_scope_id = ?",
                    p.f.signed.token, ComplaintDataScope.LIVE.id))
            }
            TestActivationProjectionReplayCut.CONTRADICTORY_PROJECT_OUTCOME -> {
                val leaf = CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME
                val values = CanonicalJson.json.decodeFromString(ListSerializer(String.serializer()), p.f.signed.read(leaf).decodeToString())
                assertEquals(1, values.count { it == projected.projectedAt.toString() })
                val changed = values.map { if (it == projected.projectedAt.toString()) projected.projectedAt.plusNanos(1_000).toString() else it }
                val bytes = CanonicalJson.canonicalize(ListSerializer(String.serializer()), changed).toByteArray(Charsets.UTF_8)
                rewriteSealed(p.f.signed.path(leaf), bytes)
                rewriteSealed(p.f.signed.marker(leaf), ByteBuffer.allocate(68).putInt(bytes.size).put(Sha256.hex(bytes).toByteArray(Charsets.US_ASCII)).array())
                assertTrue(p.f.signed.complete(leaf), "A valid complete-file checksum does not cure a semantically contradictory historical outcome.")
            }
            TestActivationProjectionReplayCut.PROJECT_LEASE_FLOOR -> {
                val floor = oldLease["lease_token"] as Long
                assertTrue(floor > 1L)
                assertEquals(1, p.f.rows.observer.update("UPDATE complaint_journal_control SET lease_token = ? WHERE data_scope_id = ?",
                    floor - 1L, ComplaintDataScope.LIVE.id))
            }
            TestActivationProjectionReplayCut.EXACT_PROJECTED -> Unit
            else -> error("Wrong projected replay case.")
        }
    }

    private fun rewriteSealed(path: Path, bytes: ByteArray) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        Files.write(path, bytes)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"))
    }
}
