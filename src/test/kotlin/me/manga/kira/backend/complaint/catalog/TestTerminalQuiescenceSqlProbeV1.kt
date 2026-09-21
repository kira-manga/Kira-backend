package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceStepV1
import me.manga.kira.backend.security.EpochSealExceptionV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalException
import me.manga.kira.backend.security.TestTerminalCodecExceptionV1
import me.manga.kira.backend.security.aws.EpochSealStsException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.util.concurrent.atomic.AtomicReference

/** Existing observation-only SQL probe pattern: no fabricated rows, native result, commit outcome or authority. */
internal class TestTerminalQuiescenceSqlProbeV1(private val f: TestRunPurgeFixtureV1) :
    JdbcTemplate(f.registration.process.pools.catalogCoordinator.dataSource), AutoCloseable {
    var original: TestRunTerminalQuiescenceV1? = null
    var before: (Call) -> Unit = {}
    var after: (Call) -> Unit = {}
    val calls = arrayListOf<Call>()
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    private val owners = linkedMapOf<PersistencePhaseContext, TestRunTerminalQuiescenceV1>()
    private val assertion = AtomicReference<AssertionError?>()
    private var probePhase = "NOT_ENTERED"
    private var lastReturned = false
    private var probeFailureObserved = false
    private val executor = f.registration.process.pools.catalogCoordinator.testTerminalQuiescence
    private val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
    private val previous = field.get(executor) as JdbcTemplate

    init {
        requireConnectionFree()
        exceptionTranslator = SQLExceptionSubclassTranslator()
        assertSame(previous.dataSource, dataSource)
        field.set(executor, this)
    }

    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = observed(sql, emptyArray()) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql, args) { super.query(sql, mapper, *args) }
    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? =
        if (sql == AUTHENTICATE) observed(sql, args) { super.query(sql, extractor, *args) } else super.query(sql, extractor, *args)
    override fun update(sql: String, vararg args: Any?): Int = observed(sql, args) { super.update(sql, *args) }

    private fun <T> observed(sql: String, args: Array<out Any?>, action: () -> T): T = try {
        lastReturned = false; probePhase = "PROBE"
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val owner = ownedCutField(phase, "testTerminalQuiescence") as TestRunTerminalQuiescenceV1
        val path = ownedCutField(phase, "path") as PersistencePhasePath
        assertSame(original, owner)
        assertSame(f.registration, owner.registration)
        assertEquals(PersistencePhasePath.COMPLAINT_TEST_TERMINAL_QUIESCENCE, path)
        assertEquals(sql.count { it == '?' }, args.size)
        if (owner.step in setOf(TestTerminalQuiescenceStepV1.WITNESS, TestTerminalQuiescenceStepV1.RECYCLE, TestTerminalQuiescenceStepV1.COMPLETE)) {
            f.sealHttp.assertDisposed()
            assertEquals(0L, f.registration.process.publicationLanes.activeOwners().totalOwners,
                "Both actual recovery graphs close before the durable cut or scan recycle authentication.")
        }
        val source = checkNotNull(dataSource)
        val connection = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
        assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
        assertTrue(f.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
        assertTrue(f.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"), "Every scan, cut and recycle phase owns exclusive E; there is no receiptless path.")
        assertFalse(f.p.advisory(connection, "complaint-maintenance-v1", "ExclusiveLock"))
        assertFalse(f.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
        val lease = ownedPoolLease(connection)
        val observation = observations.getOrPut(phase) {
            val identity = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_backend_pid(), txid_current(), current_setting('statement_timeout'), current_setting('transaction_timeout')").use { row ->
                    assertTrue(row.next())
                    for (column in 3..4) assertTrue(timeoutMillis(row.getString(column)) in 1..2_000)
                    row.getInt(1) to row.getLong(2)
                }
            }
            owners[phase] = owner
            StepUpPhaseObservation(phase, lease, identity)
        }
        assertSame(observation.lease, lease)
        assertSame(owners.getValue(phase), owner)
        assertFalse(lease.completion.quiescent())
        val call = Call(phase, owner.step, sql, args.map { if (it is ByteArray) Bytes(it.size, Sha256.hex(it)) else it })
        calls.add(call)
        probePhase = "BEFORE_SQL"
        before(call)
        probePhase = "SQL"
        action().also {
            lastReturned = true; probePhase = "AFTER_SQL"
            after(call)
            probePhase = "RETURNED"
        }
    } catch (problem: Throwable) {
        if (problem is AssertionError) assertion.compareAndSet(null, problem)
        probeFailureObserved = true
        runCatching { reportUnexpectedFailure(problem) } // Actual JDBC/mapper/callback class before redaction.
        throw problem
    }

    /** Existing-probe state only; the boundary argument is a fixed TEST call-site phase, never runtime data. */
    fun reportUnexpectedFailure(problem: Throwable, boundary: String = "SQL_PROBE") {
        System.err.println("TERMINAL_QUIESCENCE_UNEXPECTED class=${problem.javaClass.name} " +
            "phase=${original?.step?.name ?: "NOT_ENTERED"} boundary=$boundary probePhase=$probePhase " +
            "returned=$lastReturned probeFailureObserved=$probeFailureObserved nativeObserved=${f.sealHttp.terminalInventoryRequests.isNotEmpty()} " +
            "observed=${observations.isNotEmpty()} committedObserved=${observations.keys.any { it.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED }} " +
            "leasesQuiescent=${observations.isNotEmpty() && observations.values.all { it.lease.completion.quiescent() }}")
        // Failure-only passive reads. The reader state may already reflect cleanup, not the failing edge.
        val retained = runCatching {
            val owner = original ?: return@runCatching "inventory=NOT_ENTERED"
            val ownCalls = calls.filter { owners[it.phase] === owner }
            val last = ownCalls.lastOrNull()
            val lastFailure = last?.let { (ownedCutField(it.phase, "failure") as AtomicReference<*>).get() as? PersistencePhaseFailureCode }
            val begins = ownCalls.filter { it.step === TestTerminalQuiescenceStepV1.BEGIN_PASS }.map { it.phase }.distinct()
            val appends = ownCalls.drop(ownCalls.indexOfLast { it.step === TestTerminalQuiescenceStepV1.BEGIN_PASS } + 1)
                .filter { it.step === TestTerminalQuiescenceStepV1.APPEND }.map { it.phase }.distinct()
            val sql = "inventoryPass=${owner.inventoryPass} targetCount=${owner.targets.size} " +
                "lastSqlStep=${last?.step?.name ?: "NONE"} lastSqlFailure=${lastFailure?.name ?: "NONE"} " +
                "lastSqlOutcome=${last?.phase?.databaseOutcome()?.name ?: "NONE"} observedPassBegins=${begins.size} " +
                "lastObservedPassAppends=${appends.size} " +
                "lastObservedPassCommittedAppends=${appends.count { it.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED }}"
            val reader = ownedCutField(owner, "native") ?: return@runCatching "$sql reader=NOT_ENTERED"
            val failure = (ownedCutField(reader, "failure") as AtomicReference<*>).get() as? Throwable
            val entries = ownedCutField(reader, "entries") as Array<*>
            "$sql readerStateAtReport=${(ownedCutField(reader, "stage") as Enum<*>).name} " +
                "readerRetainedFailure=${retainedFailureCode(failure)} readerCompletedPasses=${ownedCutField(reader, "completedPasses") as Int} " +
                "readerEntryCounts=${entries.joinToString(",") { (it as List<*>?)?.size?.toString() ?: "NONE" }} " +
                retainedPassAttemptState(reader)
        }.getOrElse { "inventoryDiagnostic=UNAVAILABLE" }
        System.err.println("TERMINAL_QUIESCENCE_RETAINED $retained")
    }

    // Passive scalars only. lastElapsed precedes a rejected local sample; it is not elapsed-at-failure.
    // Never sample a clock, call a budget method or reset an original attempt from diagnostics.
    private fun retainedPassAttemptState(reader: Any): String = runCatching {
        val attempt = ownedCutField(reader, "passAttempt") ?: return@runCatching "passAttempt=NONE"
        "passAttemptCapNanos=${ownedCutField(attempt, "allowanceNanos") as Long} " +
            "passAttemptLastObservedElapsedNanos=${ownedCutField(attempt, "lastElapsed") as Long} " +
            "passAttemptExpired=${ownedCutField(attempt, "expired") as Boolean}"
    }.getOrDefault("passAttemptDiagnostic=UNAVAILABLE")

    private fun retainedFailureCode(problem: Throwable?): String = when (problem) {
        null -> "NONE"
        is JournalPublicationExceptionV1 -> "JOURNAL_PUBLICATION:${problem.code.name}"
        is TestTerminalExceptionV1 -> "TEST_TERMINAL:${problem.code.name}"
        is TestTerminalCodecExceptionV1 -> "TEST_TERMINAL_CODEC:${problem.code.name}"
        is EpochSealExceptionV1 -> "EPOCH_SEAL:${problem.code.name}"
        is EpochSealStsException -> "EPOCH_SEAL_STS:${problem.code.name}"
        is OwnerDeleteAllJournalException -> "OWNER_DELETE_ALL_JOURNAL:${problem.code.name}"
        is PersistencePhaseException -> "PERSISTENCE_PHASE:${problem.code.name}"
        is PersistenceBoundaryException -> "PERSISTENCE_BOUNDARY:${problem.code.name}"
        is TestTerminalQuiescenceExceptionV1 -> "QUIESCENCE"
        else -> "OTHER:${problem.javaClass.name}"
    }

    fun assertReleased(requireCommitted: Boolean = true) {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, f.registration.process.pools.catalogCoordinator.activeSnapshotOwners())
        observations.forEach { (phase, value) ->
            assertTrue(value.lease.completion.quiescent())
            assertTrue(phase.testTerminalQuiescenceResourcesRetired(owners.getValue(phase)))
            if (requireCommitted) {
                assertTrue(phase.testTerminalQuiescenceCleanupProven(owners.getValue(phase)))
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            }
        }
        assertion.get()?.let { throw it }
    }
    override fun close() {
        before = {}; after = {}
        probePhase = "CLOSE"
        try { assertReleased(requireCommitted = false) }
        catch (problem: Throwable) { runCatching { reportUnexpectedFailure(problem, "PROBE_CLOSE") }; throw problem }
        finally { assertSame(this, field.get(executor)); field.set(executor, previous) }
    }
    class Call(val phase: PersistencePhaseContext, val step: TestTerminalQuiescenceStepV1, val sql: String, val arguments: List<Any?>)
    data class Bytes(val size: Int, val sha256: String)
    companion object {
        const val AUTHENTICATE = "SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated"
        private fun timeoutMillis(value: String): Long = when {
            value.endsWith("ms") -> value.removeSuffix("ms").toLong()
            value.endsWith("s") -> value.removeSuffix("s").toLong() * 1_000
            else -> value.toLong()
        }
    }
}
