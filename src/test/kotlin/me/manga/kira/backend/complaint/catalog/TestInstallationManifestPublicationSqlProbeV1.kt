package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestPublicationSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestPublicationStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationV1
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

/** Actual JDBC/phase observation only. No supplied work, proof, commit outcome or release receipt. */
internal class TestInstallationManifestPublicationSqlProbeV1(private val f: TestRunOrdinaryDrainFixtureV1) :
    JdbcTemplate(f.registration.process.pools.catalogCoordinator.dataSource), AutoCloseable {
    var original: TestRunInstallationManifestPublicationV1? = null
    var before: (Call) -> Unit = {}
    var after: (Call) -> Unit = {}
    val calls = arrayListOf<Call>()
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    private val owners = linkedMapOf<PersistencePhaseContext, TestRunInstallationManifestPublicationV1>()
    private val assertion = AtomicReference<AssertionError?>()
    private val executor = f.registration.process.pools.catalogCoordinator.testInstallationManifestPublication
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
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val owner = ownedCutField(phase, "testInstallationManifestPublication") as TestRunInstallationManifestPublicationV1
        val path = ownedCutField(phase, "path") as PersistencePhasePath
        assertSame(original, owner)
        assertSame(f.registration, owner.registration)
        val verify = owner.step === TestInstallationManifestPublicationStepV1.VERIFY
        assertEquals(if (verify) PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_VERIFY
            else PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PUBLICATION, path)
        assertEquals(sql.count { it == '?' }, args.size)
        if (verify) {
            assertTrue(sql in setOf(AUTHENTICATE, "SELECT clock_timestamp()", TestInstallationManifestPublicationSqlV1.publication,
                TestInstallationManifestPublicationSqlV1.verify), "Receiptless VERIFY must touch only its publication, not sidecar/control/run/receipt/counters.")
            f.sealHttp.assertDisposed()
            assertEquals(0L, f.registration.process.publicationLanes.activeOwners().totalOwners, "Returned native cleanup and shared-J release precede even VERIFY authentication.")
        }
        val source = checkNotNull(dataSource)
        val connection = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
        assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
        assertEquals(!verify, f.history.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
        assertEquals(!verify, f.history.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
        assertFalse(f.history.p.advisory(connection, "complaint-maintenance-v1", "ExclusiveLock"))
        assertFalse(f.history.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
        val lease = ownedPoolLease(connection)
        val observation = observations.getOrPut(phase) {
            val identity = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_backend_pid(), txid_current(), current_setting('statement_timeout'), current_setting('transaction_timeout')").use { row ->
                    assertTrue(row.next())
                    for (column in 3..4) assertTrue(timeoutMillis(row.getString(column)) in 1..2_000, "Original SQL caps remain at most two seconds.")
                    row.getInt(1) to row.getLong(2)
                }
            }
            owners[phase] = owner
            StepUpPhaseObservation(phase, lease, identity)
        }
        assertSame(observation.lease, lease)
        assertSame(owners.getValue(phase), owner)
        assertFalse(lease.completion.quiescent())
        val call = Call(phase, owner.step, owner.chunkIndex, sql, args.map { if (it is ByteArray) Bytes(it.size, Sha256.hex(it)) else it })
        calls.add(call)
        before(call)
        action().also { after(call) }
    } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }

    fun assertReleased(requireCommitted: Boolean = true) {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, f.registration.process.pools.catalogCoordinator.activeSnapshotOwners())
        observations.forEach { (phase, value) ->
            assertTrue(value.lease.completion.quiescent())
            assertTrue(phase.testInstallationManifestPublicationResourcesRetired(owners.getValue(phase)))
            if (requireCommitted) {
                assertTrue(phase.testInstallationManifestPublicationCleanupProven(owners.getValue(phase)))
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            }
        }
        assertion.get()?.let { throw it }
    }
    fun reset() { assertReleased(requireCommitted = false); calls.clear(); observations.clear(); owners.clear() }
    override fun close() {
        before = {}; after = {}
        try { assertReleased(requireCommitted = false) }
        finally { assertSame(this, field.get(executor)); field.set(executor, previous) }
    }
    class Call(val phase: PersistencePhaseContext, val step: TestInstallationManifestPublicationStepV1, val chunkIndex: Int,
        val sql: String, val arguments: List<Any?>)
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
