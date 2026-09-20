package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
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

/** Observe/inject failures on the actual SQL/holder only; never manufacture a producer or successful completion. */
internal class TestInstallationManifestSqlProbeV1(private val f: TestRunOrdinaryDrainFixtureV1) : JdbcTemplate(f.registration.process.pools.catalogCoordinator.dataSource), AutoCloseable {
    var original: TestRunInstallationManifestV1? = null
    var before: (Call) -> Unit = {}
    var after: (Call) -> Unit = {}
    val calls = ArrayList<Call>()
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    private val owners = linkedMapOf<PersistencePhaseContext, TestRunInstallationManifestV1>()
    private val assertion = AtomicReference<AssertionError?>()
    private val executor = f.registration.process.pools.catalogCoordinator.testInstallationManifest
    private val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
    private val previous = field.get(executor)
    init {
        requireConnectionFree()
        exceptionTranslator = SQLExceptionSubclassTranslator()
        assertSame(dataSource, (previous as JdbcTemplate).dataSource)
        field.set(executor, this)
    }

    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = observed(sql, emptyArray()) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql, args) { super.query(sql, mapper, *args) }
    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? =
        if (sql == "SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated") observed(sql, args) { super.query(sql, extractor, *args) }
        else super.query(sql, extractor, *args)
    override fun update(sql: String, vararg args: Any?): Int = observed(sql, args) { super.update(sql, *args) }

    private fun <T> observed(sql: String, args: Array<out Any?>, action: () -> T): T = try {
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val owner = ownedCutField(phase, "testInstallationManifest") as TestRunInstallationManifestV1
        assertSame(original, owner)
        assertSame(f.registration, owner.registration)
        assertEquals(PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PREPARE, ownedCutField(phase, "path"))
        assertEquals(sql.count { it == '?' }, args.size)
        val source = checkNotNull(dataSource)
        val connection = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
        assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
        assertTrue(f.history.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
        assertTrue(f.history.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
        val lease = ownedPoolLease(connection)
        val observation = observations.getOrPut(phase) {
            val identity = connection.createStatement().use { s -> s.executeQuery("SELECT pg_backend_pid(), txid_current()").use { r ->
                assertTrue(r.next()); r.getInt(1) to r.getLong(2)
            } }
            owners[phase] = owner
            StepUpPhaseObservation(phase, lease, identity)
        }
        assertSame(observation.lease, lease); assertSame(owners.getValue(phase), owner)
        assertFalse(lease.completion.quiescent())
        val call = Call(phase, owner.step, sql, args.map { if (it is ByteArray) it.copyOf() else it })
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
            assertTrue(phase.testInstallationManifestCleanupProven(owners.getValue(phase)))
            if (requireCommitted) assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        }
        assertion.get()?.let { throw it }
    }
    fun reset() { assertReleased(requireCommitted = false); calls.clear(); observations.clear(); owners.clear() }
    override fun close() {
        before = {}; after = {}
        try { assertReleased(requireCommitted = false) }
        finally { assertSame(this, field.get(executor)); field.set(executor, previous) }
    }
    class Call(val phase: PersistencePhaseContext, val step: TestInstallationManifestStepV1, val sql: String, val arguments: List<Any?>)
}
