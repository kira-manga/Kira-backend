package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceEpochRotationSession
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhysicalEntry
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutSuccessorV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal val FIRST_CUT_SUCCESSOR_READ = PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_READ
internal val FIRST_CUT_SUCCESSOR_LEASE = PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_LEASE
internal val FIRST_CUT_SUCCESSOR_RELEASE = PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_RELEASE

/** Reuses C's actual producer and physical root. The successor input exists before original full-D activation. */
internal fun withTestActiveFirstCutSuccessor(tls: VersionBoundPersistenceConnectedFixture,
    ordinaryRawHttp: TestActiveOrdinaryRawHttpV1? = null,
    action: (TestActiveFirstCutSuccessorFixtureV1) -> Unit) =
    withTestActiveFirstCut(tls, ordinaryRawHttp = ordinaryRawHttp, activeFirstCutSuccessor = true) { original ->
        val executor = original.runtime.pools.catalogCoordinator.testActiveFirstCutSuccessor
        val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
        val previous = field.get(executor) as JdbcTemplate
        assertSame(previous.dataSource, original.probe.dataSource)
        field.set(executor, original.probe) // Passive SQL/actual-holder observer, not a result supplier.
        val before = original.probe.beforeSql
        original.probe.beforeSql = { step ->
            if (original.probe.calls.last().path.testActiveFirstCutSuccessor) {
                val pid = original.p.holder(original.runtime)
                assertTrue(original.p.advisory(pid, "complaint-maintenance-v1", "ShareLock"))
                assertFalse(original.p.advisory(pid, "complaint-maintenance-v1", "ExclusiveLock"))
                assertTrue(original.p.advisory(pid, "complaint-journal-epoch", "ShareLock"))
            } else before(step)
        }
        val fixture = TestActiveFirstCutSuccessorFixtureV1(original)
        try { action(fixture); original.probe.assertNoLostAssertions() }
        finally {
            original.native.onNanoSample = null
            original.native.offsetNanos = 0 // Fixture disposal only; never repairs a failed original.
            Thread.interrupted()
            fixture.awaitNativeReclaimed()
            original.probe.beforeSql = before
            original.probe.afterSql = {}
            assertSame(original.probe, field.get(executor)); field.set(executor, previous)
        }
    }

internal class TestActiveFirstCutSuccessorFixtureV1(val first: TestActiveFirstCutFixtureV1) {
    val nativeSessions = CopyOnWriteArrayList<PersistenceEpochRotationSession>()
    var beforeNativeSample: (PersistenceEpochRotationSession) -> Unit = {}

    fun resume() = TestActiveFirstCutSuccessorV1.withHttpFixture(first.registration, first.assembly,
        first.p.f.http::readClient, SignedActivationObservation.WALL_CLOCK)

    fun recover(original: TestActiveFirstCutSuccessorV1 = resume()): TestActiveFirstCutSuccessorV1.Recovered {
        val caller = Thread.currentThread()
        first.native.onNanoSample = {
            if (caller === Thread.currentThread()) currentNative()?.let {
                if (it !in nativeSessions) nativeSessions.add(it)
                assertTrue(it !== first.observedNative.get(), "Not the original C native producer lineage.")
                beforeNativeSample(it)
            }
        }
        try {
            return original.recover(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials).also {
                original.requireActualCleanup()
                first.assertSqlReleased()
                assertNull(SignedActivationObservation.active(first.runtime.pools.catalogCoordinator))
                awaitNativeReclaimed()
            }
        } catch (problem: Throwable) {
            TestActiveFirstCutSuccessorDiagnosticsV1.report("RECOVER", this, problem, original)
            throw problem
        } finally { first.native.onNanoSample = null; first.native.assertNoLostAssertions() }
    }

    private fun currentNative(): PersistenceEpochRotationSession? {
        val call = (ownedCutField(first.resource, "active") as AtomicReference<*>).get() ?: return null
        return ownedCutField(call, "session") as? PersistenceEpochRotationSession
    }

    fun awaitNativeReclaimed() {
        nativeSessions.forEach { session ->
            val entry = poolTestField<PersistencePhysicalEntry>(session, "entry")
            awaitLifecycleFact { entry.jdbc.terminalCompletion().reclaimed() }
            assertTrue(entry.jdbc.terminalCompletion().reclaimed())
        }
        first.awaitNativeReclaimed()
    }

    /** Genuine failed original native E wait: actual paid REQUEST survives, not a SQL-built request/capture. */
    fun requestedAfterRealTimeout(requireSameProcessCleanup: Boolean = false) {
        val counters = first.counters()
        val before = first.afterFailedCapture
        val settledAtReturn = AtomicBoolean()
        if (requireSameProcessCleanup) first.afterFailedCapture = { original, problem ->
            before(original, problem)
            try {
                original.requireActualCleanup()
                assertTrue(checkNotNull(first.observedNative.get()).failure().cleanupProven)
                assertNull(SignedActivationObservation.active(first.runtime.pools.catalogCoordinator))
                settledAtReturn.set(true)
            } catch (failure: Throwable) {
                TestActiveFirstCutSuccessorDiagnosticsV1.report("ORIGINAL_FAILED_RETURN", this, failure)
                throw failure
            }
        }
        val result = try {
            first.captureWhileShared { _, entry ->
                awaitLifecycleFact(3_000) { entry.jdbc.terminalCompletion().reclaimed() }
            }
        } finally { first.afterFailedCapture = before }
        assertTrue(result.isFailure)
        if (requireSameProcessCleanup) assertTrue(settledAtReturn.get(),
            "Same-process successor requires original native settlement before sticky close, not a later fixture wait. " +
                TestActiveFirstCutSuccessorDiagnosticsV1.snapshot(this, result.exceptionOrNull()))
        first.assertCharge(counters)
        assertEquals("REQUESTED", first.control()["rotation_state"])
        assertEquals("RESERVED", first.paid()["state"])
        assertNull(first.paid()["capture_owner"])
        first.awaitNativeReclaimed(); first.assertSqlReleased()
    }

    /** Natural DB-time wait BEFORE constructing the next original/J budget. No lease reset or clock substitution. */
    fun waitForNaturalExpiry() {
        requireConnectionFree()
        val control = first.controlImage()
        val slot = first.paidImage()
        val counters = first.counters()
        awaitLifecycleFact(35_000) {
            first.observer.queryForObject(
                "SELECT lease_owner IS NOT NULL AND lease_expires_at <= clock_timestamp() FROM complaint_journal_control WHERE data_scope_id = ?",
                Boolean::class.java, first.scope) == true
        }
        assertEquals(control, first.controlImage())
        assertEquals(slot, first.paidImage()); assertEquals(counters, first.counters())
    }

    /** Each pooled prefix and P/run/slot lock has ended before the separately observed native exclusive E wait. */
    fun recoverWhileShared(whileWaiting: (PersistencePhysicalEntry) -> Unit = {}): Result<TestActiveFirstCutSuccessorV1.Recovered> =
        first.independentTransaction { blocker, jdbc, holderPid ->
            jdbc.execute("SELECT pg_advisory_xact_lock_shared(hashtextextended('complaint-journal-epoch', 0))")
            OwnedCallerTestScope().use { callers ->
                val worker = callers.launch {
                    runCatching { recover() }.also { if (it.isFailure) { awaitNativeReclaimed(); first.assertSqlReleased() } }
                }
                try {
                    var pid: Int? = null
                    awaitLifecycleFact(2_000) {
                        pid = first.observer.queryForList(
                            "SELECT a.pid FROM pg_stat_activity a WHERE a.datname = current_database() AND a.usename = ? " +
                                "AND ? = ANY(pg_blocking_pids(a.pid)) AND EXISTS " +
                                "(SELECT 1 FROM pg_locks l WHERE l.pid = a.pid AND l.locktype = 'advisory' AND NOT l.granted)",
                            Int::class.java, PgLifecycleDatabaseSettings.CANDIDATE, holderPid).singleOrNull()
                        if (pid == null && !worker.thread.isAlive) {
                            // Inspect only an actually exited worker; value() cannot add a live-worker wait here.
                            val problem = runCatching { worker.value().getOrThrow() }.exceptionOrNull()
                            throw AssertionError("Successor worker exited before an exclusive-E waiter was observed. " +
                                TestActiveFirstCutSuccessorDiagnosticsV1.snapshot(this, problem))
                        }
                        pid != null
                    }
                    val selected = checkNotNull(pid)
                    val entry = first.entries().single()
                    assertFalse(entry.jdbc.terminalCompletion().reclaimed())
                    assertEquals(selected, first.runtime.observeTlsPid(selected))
                    first.probe.observations.forEach { (phase, observed) ->
                        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
                        assertTrue(observed.lease.completion.quiescent())
                    }
                    assertEquals(0, first.runtime.pools.catalogCoordinator.activeSnapshotOwners())
                    assertEquals(0L, jdbc.queryForObject(
                        "SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation WHERE l.pid = ? " +
                            "AND c.relname IN ('complaint_journal_control','complaint_catalog_mutations','complaint_capacity_counters','complaint_test_runs','complaint_test_active_seal_intents') " +
                            "AND NOT (l.locktype = 'relation' AND l.mode = 'AccessShareLock' " +
                            "AND c.relname IN ('complaint_journal_control','complaint_catalog_mutations'))", Long::class.java, selected))
                    assertTrue(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND mode = 'ShareLock' AND granted " +
                            "AND classid::bigint = (hashtextextended('complaint-maintenance-v1', 0) >> 32 & 4294967295) " +
                            "AND objid::bigint = (hashtextextended('complaint-maintenance-v1', 0) & 4294967295))", Boolean::class.java, selected) == true)
                    whileWaiting(entry)
                } finally { blocker.rollback() }
                worker.value().also { awaitNativeReclaimed() }
            }
        }
}
