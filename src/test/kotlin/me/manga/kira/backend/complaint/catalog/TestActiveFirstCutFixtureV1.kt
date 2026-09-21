package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDriverAttemptPolicy
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceEpochRotationSession
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipant
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhysicalEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhysicalFactoryBinding
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSession
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstSealStorageV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

internal val FIRST_CUT_READ = PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_READ
internal val FIRST_CUT_LEASE = PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_LEASE
internal val FIRST_CUT_REQUEST = PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_REQUEST

/**
 * Actual protected ACTIVE/batch input -> retained native recipes/full D -> signed PROJECT ->
 * registration -> genuine initial identity RELEASE. No ACTIVE SQL seed or supplied capture.
 * This stops at historical CAPTURED, not an actual ordinary seal/checkpoint/SUCCESS producer.
 */
internal fun withTestActiveFirstCut(
    tls: VersionBoundPersistenceConnectedFixture,
    ordinaryRawHttp: TestActiveOrdinaryRawHttpV1? = null,
    activeFirstCutSuccessor: Boolean = false,
    activeSealRecovery: Boolean = false, sealRecoveryHorizon: java.time.Instant? = null,
    terminalHistory: TestOrdinaryDrainFixtureInputsV1? = null,
    globalScanBeforeActivation: Boolean = false,
    action: (TestActiveFirstCutFixtureV1) -> Unit,
) = withInitialAdmission(tls, activeFirstCut = true, ordinaryRawHttp = ordinaryRawHttp, activeFirstCutSuccessor = activeFirstCutSuccessor, activeSealRecovery = activeSealRecovery, sealRecoveryHorizon = sealRecoveryHorizon, terminalHistory = terminalHistory, globalScanBeforeActivation = globalScanBeforeActivation) { identity ->
        identity.release()
        identity.probe.resetObservations()
        val executor = identity.runtime.pools.catalogCoordinator.testActiveFirstCut
        val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
        val original = field.get(executor) as JdbcTemplate
        assertSame(original.dataSource, identity.probe.dataSource)
        field.set(executor, identity.probe) // Passive original SQL/holder observation, never a result/operation replacement.
        val previousBefore = identity.probe.beforeSql
        val f = TestActiveFirstCutFixtureV1(identity)
        identity.probe.beforeSql = { step ->
            if (identity.probe.calls.last().path.testActiveFirstCut) {
                val holder = identity.p.holder(identity.runtime)
                assertTrue(identity.p.advisory(holder, "complaint-maintenance-v1", "ShareLock"))
                assertFalse(identity.p.advisory(holder, "complaint-maintenance-v1", "ExclusiveLock"))
                assertTrue(identity.p.advisory(holder, "complaint-journal-epoch", "ShareLock"))
            } else previousBefore(step)
        }
        try {
            f.prepare()
            identity.p.f.rows.globalPredecessor?.assertPreserved(identity.observer)
            action(f)
            identity.probe.assertNoLostAssertions()
            identity.native.assertNoLostAssertions()
        } finally {
            identity.probe.beforeSql = previousBefore
            identity.probe.afterSql = {}
            identity.native.onNanoSample = null
            identity.native.offsetNanos = 0 // Teardown only; never revive an original.
            Thread.interrupted()
            f.awaitNativeReclaimed()
            assertSame(identity.probe, field.get(executor))
            field.set(executor, original)
            f.fixtureCleanup()
        }
    }

internal class TestActiveFirstCutFixtureV1(val initial: InitialAdmissionFixture) {
    val p = initial.p
    val runtime = initial.runtime
    val registration = initial.registration
    val assembly = initial.assembly
    val process = registration.process
    val scope = p.scope
    val observer = initial.observer
    val probe = initial.probe
    val native = initial.native
    val resource = checkNotNull(process.pools.epochRotation)
    private val participant = poolTestField<PersistenceJdbcParticipant>(runtime.scope.root, "epochRotationParticipant")
    private val binding = poolTestField<PersistencePhysicalFactoryBinding>(participant, "binding")
    val observedNative = AtomicReference<PersistenceEpochRotationSession?>()
    private val observedOriginal = AtomicReference<TestActiveFirstCutV1?>()
    var beforeNativeSample: (PersistenceEpochRotationSession) -> Unit = {}
    /** Observe the original failure return BEFORE this fixture's later native-reclamation wait. */
    var afterFailedCapture: (TestActiveFirstCutV1, Throwable) -> Unit = { _, _ -> }

    fun prepare() {
        assertTrue(process.consumers.journalConfiguration.registeredAdminBatchDelete)
        assertSame(assembly.target, process)
        assertSame(runtime.owner.epochRotation, resource)
        assertSame(runtime.pools, process.pools)
        assertFalse(p.f.signed.tls.pools.ordinary.businessReady())
        assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, resource.observePreparation())
        assertTrue(entries().isEmpty())
        assertEquals(PersistenceLifecycleObservation.READY, resource.prepare())
        assertTrue(entries().isEmpty(), "Cold prepare opens no fourth pool/idle connection.")
        initial.gates(open = true)
    }

    fun begin() = TestActiveFirstCutV1.withHttpFixture(registration, assembly, p.f.http::readClient, SignedActivationObservation.WALL_CLOCK)

    fun capture(original: TestActiveFirstCutV1 = begin()): TestActiveFirstCutV1.Captured {
        val caller = Thread.currentThread()
        assertTrue(observedOriginal.compareAndSet(null, original))
        native.onNanoSample = {
            if (Thread.currentThread() === caller) currentNative()?.let {
                val previous = observedNative.get()
                if (previous == null) assertTrue(observedNative.compareAndSet(null, it)) else assertSame(previous, it)
                beforeNativeSample(it)
            }
        }
        try {
            val captured = try {
                original.capture(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
            } catch (problem: Throwable) {
                afterFailedCapture(original, problem)
                throw problem
            }
            return captured.also {
                original.requireActualCleanup()
                assertSqlReleased()
                assertNull(SignedActivationObservation.active(runtime.pools.catalogCoordinator))
                assertTrue(checkNotNull(observedNative.get()).failure().cleanupProven)
            }
        } finally {
            native.onNanoSample = null
            native.assertNoLostAssertions()
        }
    }

    fun entries(): List<PersistencePhysicalEntry> = binding.ledger.lock.withLock { binding.ledger.entries.filterNotNull() }
    fun nativeEntry(): PersistencePhysicalEntry = poolTestField(checkNotNull(observedNative.get()), "entry")
    private fun currentNative(): PersistenceEpochRotationSession? {
        val selected = (ownedCutField(resource, "active") as AtomicReference<*>).get() ?: return null
        return ownedCutField(selected, "session") as? PersistenceEpochRotationSession
    }
    fun awaitNativeReclaimed() {
        observedNative.get()?.let {
            val entry = poolTestField<PersistencePhysicalEntry>(it, "entry")
            awaitLifecycleFact { entry.jdbc.terminalCompletion().reclaimed() }
            assertTrue(entry.jdbc.terminalCompletion().reclaimed())
        }
        awaitLifecycleFact { entries().isEmpty() }
        requireConnectionFree()
    }
    fun assertSqlReleased() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
        probe.observations.forEach { (_, observed) -> assertTrue(observed.lease.completion.quiescent()) }
        probe.assertNoLostAssertions()
    }

    fun control(): Map<String, Any?> = observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", scope)
    fun controlImage(): String = checkNotNull(observer.queryForObject(
        "SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, scope))
    fun otherCounterImage(): List<String> = observer.queryForList(
        "SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaint_capacity_counters c WHERE name <> 'storage_bytes' ORDER BY ordinal", String::class.java)
    fun paid(): Map<String, Any?> = observer.queryForMap("SELECT * FROM complaint_test_active_seal_intents WHERE data_scope_id = ?", scope)
    fun paidImage(): List<String> = observer.queryForList(
        "SELECT jsonb_build_array(to_jsonb(i), i.xmin::text)::text FROM complaint_test_active_seal_intents i WHERE data_scope_id = ?", String::class.java, scope)
    fun globalImage(): String = checkNotNull(observer.queryForObject(
        "SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, UUID(0L, 0L)))
    fun outsideCut(): Map<String, List<String>> = p.image().filterKeys { it !in setOf("complaint_journal_control", "counters") }
    fun counters() = p.f.rows.counters.snapshot()
    fun assertCharge(before: Map<String, me.manga.kira.backend.common.infrastructure.persistence.CounterSnapshot>) {
        val after = counters()
        assertEquals(22, after.size)
        before.forEach { (name, old) ->
            val current = after.getValue(name)
            if (name == "storage_bytes") {
                assertEquals(old.preserved, current.preserved, "No reserve/limit/configuration delta.")
                assertEquals(old.free - TestActiveFirstSealStorageV1.STORAGE_BYTES, current.free)
                assertEquals(old.actual + TestActiveFirstSealStorageV1.STORAGE_BYTES, current.actual)
            } else assertEquals(old, current, "Every other counter, timestamp and admission value is unchanged.")
        }
    }

    fun <T> independentTransaction(action: (Connection, JdbcTemplate, Int) -> T): T =
        checkNotNull(observer.dataSource).connection.use { connection ->
            connection.autoCommit = false
            val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
            val pid = checkNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java))
            try { action(connection, jdbc, pid) } finally { connection.rollback() }
        }

    /** Paid REQUEST and every pooled holder have really ended before this observed exclusive-E wait. */
    fun captureWhileShared(whileWaiting: (PgLifecycleDatabaseSession, PersistencePhysicalEntry) -> Unit = { _, _ -> }): Result<TestActiveFirstCutV1.Captured> =
        independentTransaction { blocker, jdbc, holderPid ->
            jdbc.execute("SELECT pg_advisory_xact_lock_shared(hashtextextended('complaint-journal-epoch', 0))")
            OwnedCallerTestScope().use { callers ->
                val worker = callers.launch {
                    runCatching { capture() }.also { if (it.isFailure) { awaitNativeReclaimed(); assertSqlReleased() } }
                }
                try {
                    var session: PgLifecycleDatabaseSession? = null
                    awaitLifecycleFact(2_000) {
                        session = observer.query(
                            "SELECT a.pid, a.backend_start FROM pg_stat_activity a WHERE a.datname = current_database() AND a.usename = ? " +
                                "AND ? = ANY(pg_blocking_pids(a.pid)) AND EXISTS " +
                                "(SELECT 1 FROM pg_locks l WHERE l.pid = a.pid AND l.locktype = 'advisory' AND NOT l.granted)",
                            { row, _ -> PgLifecycleDatabaseSession(row.getInt(1), row.getTimestamp(2).toInstant()) },
                            PgLifecycleDatabaseSettings.CANDIDATE, holderPid).singleOrNull()
                        session != null
                    }
                    val selected = checkNotNull(session)
                    val entry = entries().single()
                    assertEquals(PersistenceDriverAttemptPolicy.TRACKED_EPOCH_ROTATION_CONJUNCTION, entry.policy)
                    assertFalse(entry.jdbc.terminalCompletion().reclaimed())
                    assertEquals(selected.pid, runtime.observeTlsPid(selected.pid))
                    assertFalse(selected.pid in probe.observations.values.map { it.identity.first })
                    assertEquals("REQUESTED", control()["rotation_state"])
                    assertNull(paid()["capture_owner"])
                    assertEquals(listOf(FIRST_CUT_READ, FIRST_CUT_LEASE, FIRST_CUT_REQUEST),
                        probe.observations.keys.map { poolTestField<PersistencePhasePath>(it, "path") })
                    probe.observations.forEach { (phase, observed) ->
                        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
                        assertTrue(observed.lease.completion.quiescent())
                    }
                    assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
                    assertEquals(0L, jdbc.queryForObject(
                        "SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation WHERE l.pid = ? " +
                            "AND c.relname IN ('complaint_journal_control','complaint_catalog_mutations','complaint_capacity_counters','complaint_test_runs','complaint_test_active_seal_intents') " +
                            "AND NOT (l.locktype = 'relation' AND l.mode = 'AccessShareLock' " +
                            "AND c.relname IN ('complaint_journal_control','complaint_catalog_mutations'))", Long::class.java, selected.pid),
                        "No row-lock-capable or counter/run/slot relation holder crosses E; only the M-gate read is allowed.")
                    assertTrue(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND mode = 'ShareLock' AND granted " +
                            "AND classid::bigint = (hashtextextended('complaint-maintenance-v1', 0) >> 32 & 4294967295) " +
                            "AND objid::bigint = (hashtextextended('complaint-maintenance-v1', 0) & 4294967295))", Boolean::class.java, selected.pid) == true)
                    whileWaiting(selected, entry)
                } finally { blocker.rollback() }
                worker.value().also { awaitNativeReclaimed() }
            }
        }

    /** Exact owned row teardown AFTER assertions/actual native retirement. This is not product purge/refund authority. */
    fun fixtureCleanup() {
        requireConnectionFree()
        checkNotNull(observer.dataSource).connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("ALTER TABLE complaint_test_active_seal_intents DISABLE TRIGGER complaint_test_active_seal_immutable") }
                connection.prepareStatement("DELETE FROM complaint_test_active_seal_intents WHERE data_scope_id = ? AND test_only").use {
                    it.setObject(1, scope); it.executeUpdate()
                }
                connection.createStatement().use { it.execute("ALTER TABLE complaint_test_active_seal_intents ENABLE TRIGGER complaint_test_active_seal_immutable") }
                connection.commit()
            } catch (problem: Throwable) { connection.rollback(); throw problem }
        }
    }
}
