package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceComplaintMaintenanceFenceBudgetV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceEpochRotationSession
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcGuardCall
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcGuardCallKind
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcGuardContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePgOwnedCutAccess
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhysicalEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ordinaryCleanupReader
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal enum class EpochMaintenanceGateCut { FRESH_TEST_PENDING, WRONG_ACTUAL_ISOLATION, ROLE_DEFAULT_REPEATABLE_READ }

/** Same existing epoch/TLS fixture and original nonpooled guard. No new root, driver, SQL result or custody authority. */
internal object EpochRotationMaintenanceCases {
    fun closeDeadline(f: EpochRotationTestFixture, clock: EpochMaintenanceClock, resultSet: Boolean, admission: Boolean) {
        f.prepare()
        val leader = f.acquire()
        val request = f.protocol.requestScan(leader.campaign)
        val before = f.row()
        val cut = "resultSet=$resultSet admission=$admission"
        var observed: DirectMaintenanceObservation? = null
        clock.onSample = {
            session(f)?.let { original ->
                if (observed == null) observed = DirectMaintenanceObservation(original, clock, resultSet, admission)
                checkNotNull(observed).sampleCleanupAdmission()
            }
        }
        try {
            val failure = try {
                assertThrows<PersistencePhaseException>("Initial capture: $cut") { f.protocol.captureEpoch(request) }
            } finally {
                // A stored clock-seam failure must surface even when the capture unexpectedly returns normally.
                clock.requireHealthy()
            }
            assertEquals(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED, failure.code)
            val guard = checkNotNull(observed)
            guard.assertClosedAfterDeadline()
            clock.onSample = {}
            f.awaitReclaimed(guard.entry)
            assertEquals(before, f.row())
            assertEquals("FAILED", ownedCutField(guard.session, "maintenanceStage").toString())
            assertTrue(f.entries().isEmpty())
            assertThrows<PersistencePhaseException>("Retry of failed capture: $cut") { f.protocol.captureEpoch(request) }
            assertTrue(f.entries().isEmpty(), "Actual cleanup cannot revive this original failed direct request.")
            f.released()
        } finally {
            clock.onSample = {}
            observed?.close()
        }
    }

    fun gateAndIsolation(f: EpochRotationTestFixture, clock: EpochMaintenanceClock, cut: EpochMaintenanceGateCut) {
        f.prepare()
        val leader = f.acquire()
        val request = f.protocol.requestScan(leader.campaign)
        val before = f.row()
        var observed: DirectMaintenanceObservation? = null
        var initialObserved = false
        var changed = false
        var settingsObserved = false
        var pid = 0
        val token = UUID.randomUUID()
        clock.onSample = {
            session(f)?.let { original ->
                if (observed == null) observed = DirectMaintenanceObservation(original, clock)
                val guard = checkNotNull(observed)
                val connection = poolTestField<Connection>(original, "connection")
                if (!initialObserved && ownedCutField(original, "stage").toString() == "PREPARED") {
                    initialObserved = true
                    // Actual first-delivery connection, still auto-commit and before begin's isolation setter.
                    if (cut === EpochMaintenanceGateCut.ROLE_DEFAULT_REPEATABLE_READ) {
                        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.transactionIsolation)
                    }
                }
                if (cut === EpochMaintenanceGateCut.WRONG_ACTUAL_ISOLATION && !changed && guard.isolationSetterReturned()) {
                    changed = true
                    // Real SQL on the SAME guarded original session; no setter/getter or transaction result proxy.
                    connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
                    assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.transactionIsolation)
                }
                val stage = ownedCutField(original, "maintenanceStage").toString()
                if (!settingsObserved && stage == "SETTINGS_RETURNED") {
                    settingsObserved = true
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT pg_backend_pid(), current_setting('transaction_isolation'), (SELECT count(*) FROM complaint_catalog_mutations WHERE operation_type = 'TEST_RUN_ACTIVATION')").use { row ->
                            assertTrue(row.next())
                            pid = row.getInt(1)
                            assertEquals("read committed", row.getString(2))
                            assertEquals(0L, row.getLong(3)) // This genuine earlier snapshot predates the fault writer.
                            assertFalse(row.next())
                        }
                    }
                }
                if (cut === EpochMaintenanceGateCut.FRESH_TEST_PENDING && !changed && stage == "OBSERVED") {
                    changed = true
                    assertEquals(true, ownedCutField(original, "observedMaintenanceLock"))
                    assertTrue(advisory(f.observer, pid, "complaint-maintenance-v1", "ShareLock"))
                    assertFalse(advisory(f.observer, pid, "complaint-journal-epoch", "ExclusiveLock"))
                    // Deliberately out-of-protocol committed SQL fault, not a TEST publisher or full-D assertion.
                    assertEquals(1, f.observer.update(
                        """INSERT INTO complaint_catalog_mutations (operation_token, operation_type, data_scope_id, test_only,
                            predecessor_generation, predecessor_hash, successor_generation, catalog_writer_generation,
                            approval_bytes, approval_hash, canonicalizer, unsigned_bytes, unsigned_hash,
                            signer_policy, signer_one_id, signer_one_algorithm, object_key, state, created_at)
                            SELECT ?, 'TEST_RUN_ACTIVATION', ?::uuid, true, successor_generation, envelope_hash, successor_generation + 1,
                            catalog_writer_generation, approval_bytes, approval_hash, canonicalizer, unsigned_bytes, unsigned_hash,
                            signer_policy, signer_one_id, signer_one_algorithm, ?, 'PREPARED', created_at
                            FROM complaint_catalog_mutations WHERE operation_token = ?""",
                        token, UUID.randomUUID(), "synthetic-direct-maintenance/$token/2", f.genesis.token,
                    ))
                }
            }
        }
        try {
            if (cut === EpochMaintenanceGateCut.ROLE_DEFAULT_REPEATABLE_READ) {
                val cutoff = f.protocol.captureEpoch(request)
                assertEquals(before.epoch + 1, cutoff.epochAfter)
                assertTrue(settingsObserved && initialObserved)
            } else {
                val failure = assertThrows<PersistencePhaseException> { f.protocol.captureEpoch(request) }
                clock.requireHealthy()
                assertEquals(if (cut === EpochMaintenanceGateCut.WRONG_ACTUAL_ISOLATION) PersistencePhaseFailureCode.RESOURCE_REFUSED else PersistencePhaseFailureCode.ENTRY_REFUSED, failure.code)
                assertTrue(changed)
                if (cut === EpochMaintenanceGateCut.WRONG_ACTUAL_ISOLATION) {
                    assertFalse(settingsObserved)
                    assertNull(ownedCutField(checkNotNull(observed).session, "observedMaintenanceLock"))
                } else assertTrue(settingsObserved)
            }
            clock.requireHealthy()
            clock.onSample = {}
            val guard = checkNotNull(observed)
            f.awaitReclaimed(guard.entry)
            if (cut !== EpochMaintenanceGateCut.ROLE_DEFAULT_REPEATABLE_READ) assertEquals(before, f.row())
            f.released()
        } finally {
            clock.onSample = {}
            observed?.let { f.awaitReclaimed(it.entry); it.close() }
            if (changed && cut === EpochMaintenanceGateCut.FRESH_TEST_PENDING) {
                assertEquals(1, f.observer.update("DELETE FROM complaint_catalog_mutations WHERE operation_token = ?", token))
            }
        }
    }

    fun withRoleRepeatableRead(tls: VersionBoundPersistenceConnectedFixture, action: () -> Unit) {
        val jdbc = JdbcTemplate(ordinaryCleanupReader(tls.database))
        val old = jdbc.queryForList("SELECT option_value FROM pg_options_to_table((SELECT rolconfig FROM pg_roles WHERE rolname = ?)) WHERE option_name = 'default_transaction_isolation'", String::class.java, PgLifecycleDatabaseSettings.CANDIDATE).singleOrNull()
        check(old == null || old in setOf("read committed", "read uncommitted", "repeatable read", "serializable"))
        try {
            jdbc.execute("ALTER ROLE ${PgLifecycleDatabaseSettings.CANDIDATE} SET default_transaction_isolation TO 'repeatable read'")
            action()
        } finally {
            jdbc.execute(if (old == null) "ALTER ROLE ${PgLifecycleDatabaseSettings.CANDIDATE} RESET default_transaction_isolation"
                else "ALTER ROLE ${PgLifecycleDatabaseSettings.CANDIDATE} SET default_transaction_isolation TO '$old'")
        }
    }

    private fun session(f: EpochRotationTestFixture): PersistenceEpochRotationSession? {
        val active = (ownedCutField(f.resource, "active") as AtomicReference<*>).get() ?: return null
        return ownedCutField(active, "session") as? PersistenceEpochRotationSession
    }

    private fun advisory(jdbc: JdbcTemplate, pid: Int, name: String, mode: String): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND mode = ? AND granted " +
            "AND classid::bigint = (hashtextextended(?, 0) >> 32 & 4294967295) AND objid::bigint = (hashtextextended(?, 0) & 4294967295))",
        Boolean::class.java, pid, mode, name, name,
    ) == true
}

/** Same injected monotonic-clock seam as maintenance tests; only the original caller can advance its retained time. */
internal class EpochMaintenanceClock : PersistenceNanoClock {
    private val caller = Thread.currentThread()
    @Volatile private var now = System.nanoTime()
    var onSample: () -> Unit = {}
    private var sampling = false
    private var failure: Throwable? = null
    override fun nanoTime(): Long {
        if (Thread.currentThread() === caller && !sampling) {
            sampling = true
            try { onSample() } catch (problem: Throwable) { failure = failure ?: problem } finally { sampling = false }
        }
        return now
    }
    fun advanceMillis(millis: Long) { check(Thread.currentThread() === caller); now += millis * 1_000_000L }
    fun requireHealthy() { failure?.let { throw it } }
}

/** Delegates the original own-project TL; observes real driver/core returns, never changes completion or JDBC results. */
private class DirectMaintenanceObservation(
    val session: PersistenceEpochRotationSession,
    private val clock: EpochMaintenanceClock,
    private val resultSet: Boolean? = null,
    private val admission: Boolean = false,
) : ThreadLocal<PersistenceJdbcGuardCall?>(), AutoCloseable {
    val entry = poolTestField<PersistencePhysicalEntry>(session, "entry")
    private val caller = Thread.currentThread()
    private val context = poolTestField<PersistenceJdbcGuardContext>(session, "context")
    private val field = context.javaClass.getDeclaredField("frames").apply { check(trySetAccessible()) }
    @Suppress("UNCHECKED_CAST") private val delegate = field.get(context) as ThreadLocal<PersistenceJdbcGuardCall?>
    private var selected: Pair<PersistenceJdbcGuardCall, PersistencePgOwnedCutAccess.Invocation>? = null
    private var statement: Pair<PersistenceJdbcGuardCall, PersistencePgOwnedCutAccess.Invocation>? = null
    private var result: PersistenceJdbcGuardCall? = null
    private var isolationSetter: PersistenceJdbcGuardCall? = null
    private var acceptance: PersistenceTimeBudget? = null
    private var execution: PersistenceTimeBudget? = null
    private var entryRemaining: Long? = null
    private var acceptanceRemaining: Long? = null
    private var prefixRemaining: Long? = null
    private var advanced = false
    private var resultReturned = false
    private var failure: Throwable? = null
    init { assertNull(delegate.get()); field.set(context, this) }

    override fun get(): PersistenceJdbcGuardCall? = delegate.get().also { call ->
        if (Thread.currentThread() === caller && call != null) observe { capture(call) }
    }
    override fun set(value: PersistenceJdbcGuardCall?) { val previous = delegate.get(); delegate.set(value); returned(previous, value) }
    override fun remove() { val previous = delegate.get(); delegate.remove(); returned(previous, null) }

    private fun capture(call: PersistenceJdbcGuardCall) {
        val native = ownedCutField(call, "driver") as? PersistencePgOwnedCutAccess.Invocation ?: return
        if (ownedCutField(native.cell, "operation") == "setTransactionIsolation") isolationSetter = call
        if (resultSet == null || ownedCutField(session, "maintenanceStage").toString() != "READING_GATE" ||
            ownedCutField(native.cell, "operation") != "close") return
        val receiver = ownedCutField(native.cell, "receiver")
        if (receiver is ResultSet && result == null) result = call
        if (receiver is Statement && statement == null) statement = call to native
        if (selected != null || !(if (resultSet == true) receiver is ResultSet else receiver is Statement)) return
        selected = call to native
        execution = call.budget
        entryRemaining = checkNotNull(execution).remainingMillis(2_000)
        acceptance = ownedCutField(call, "maintenanceAcceptanceBudget") as? PersistenceTimeBudget
        acceptanceRemaining = acceptance?.remainingMillis(2_000)
    }

    fun isolationSetterReturned(): Boolean = isolationSetter?.returnedAfterFinalizers() == true && delegate.get() == null

    fun sampleCleanupAdmission() {
        if (!admission || advanced || delegate.get() != null || (resultSet == false && !resultReturned) ||
            ownedCutField(session, "maintenanceStage").toString() != "READING_GATE") return
        if (!Thread.currentThread().stackTrace.any { it.className == PersistenceEpochRotationSession::class.java.name && it.methodName == "maintenanceCleanupBudget" }) return
        observe {
            assertNull(selected)
            assertNull((ownedCutField(session, "problem") as AtomicReference<*>).get())
            advanced = true
            clock.advanceMillis(101)
        }
    }

    private fun returned(previous: PersistenceJdbcGuardCall?, restored: PersistenceJdbcGuardCall?) {
        if (Thread.currentThread() !== caller || previous == null) return
        if (previous === result) resultReturned = true
        if (admission || previous !== selected?.first || advanced) return
        observe {
            val native = checkNotNull(selected).second
            assertSame(previous.parentFrame(), restored)
            assertEquals(true, ownedCutField(native.cell, "ended"))
            assertEquals(false, ownedCutField(previous, "ended"))
            advanced = true
            clock.advanceMillis(80)
            prefixRemaining = poolTestField<PersistenceComplaintMaintenanceFenceBudgetV1>(session, "maintenanceBudget").remainingMillis()
        }
    }

    fun assertClosedAfterDeadline() {
        failure?.let { throw it }
        assertTrue(advanced)
        assertSame(ownedCutField(session, "work"), execution)
        assertEquals(if (admission) 1_899L else 2_000L, entryRemaining)
        if (admission) assertNull(acceptance) else {
            assertEquals(75L, acceptanceRemaining)
            assertEquals(20L, prefixRemaining)
            assertThrows<PersistenceBoundaryException> { checkNotNull(acceptance).remainingMillis(2_000) }
        }
        listOf(checkNotNull(selected), checkNotNull(statement)).distinctBy { it.first }.forEach { (call, native) ->
            assertSame(ownedCutField(session, "work"), call.budget)
            assertSame(call, native.callKey)
            assertEquals(PersistenceJdbcGuardCallKind.CLEANUP, ownedCutField(call, "kind"))
            assertTrue(call.returnedAfterFinalizers())
            assertEquals(true, ownedCutField(call, "finishReturned"))
            assertEquals(true, ownedCutField(native.cell, "armed"))
            assertEquals(true, ownedCutField(native.cell, "disarmed"))
            assertEquals(true, ownedCutField(native.cell, "ended"))
            assertEquals(false, ownedCutField(native.cell, "cleanupFailed"))
            assertEquals(false, ownedCutField(native.cell, "uncertain"))
        }
        assertEquals(0L, context.liveChildren())
    }

    private inline fun observe(action: () -> Unit) { try { action() } catch (problem: Throwable) { failure = failure ?: problem } }
    override fun close() { assertSame(this, field.get(context)); field.set(context, delegate); failure?.let { throw it } }
}
