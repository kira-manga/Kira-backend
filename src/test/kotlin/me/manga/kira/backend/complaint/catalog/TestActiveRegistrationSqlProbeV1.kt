package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceActiveRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
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

/**
 * Passive same-source template only. Real SQL/results/RC holder/M+exclusive-E and physical leases;
 * no phase, registration, boolean result, proof or release is supplied. The after hook installs only
 * actual transaction faults in the focused cases, never an alternate production operation.
 * SOURCE-ONLY addition: NOT_COMPILED / NOT_RUN / NOT_INDEPENDENTLY_REVIEWED.
 */
internal class TestActiveRegistrationSqlProbeV1(
    private val advisory: (Connection, String, String) -> Boolean,
    private val runtime: VersionBoundPersistenceConnectedFixture,
) : JdbcTemplate(runtime.pools.catalogCoordinator.dataSource) {
    constructor(p: ProjectionActivationObservation, runtime: VersionBoundPersistenceConnectedFixture) : this(p::advisory, runtime)
    var original: ComplaintTestNamespaceActiveRegistrationAttemptV1? = null
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    val calls = mutableListOf<TestActiveRegistrationSqlCallV1>()
    var after: (TestActiveRegistrationSqlCallV1) -> Unit = {}
    private val assertion = AtomicReference<AssertionError?>()

    init {
        fetchSize = CatalogTestRunActivationHistoryV1.FETCH_ROWS
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> =
        observed(sql, emptyArray()) { super.query(sql, mapper) }

    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> =
        observed(sql, args) { super.query(sql, mapper, *args) }

    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? =
        observed(sql, args) { super.query(sql, extractor, *args) }

    override fun update(sql: String, vararg args: Any?): Int = unexpectedWrite()
    override fun execute(sql: String): Unit = unexpectedWrite()

    private fun unexpectedWrite(): Nothing {
        val failure = AssertionError("ACTIVE registration must not execute UPDATE/INSERT/DELETE/DDL.")
        assertion.compareAndSet(null, failure)
        throw failure
    }

    private fun <T> observed(sql: String, args: Array<out Any?>, action: () -> T): T = try {
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        assertEquals(PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_ACTIVE_REGISTRATION, ownedCutField(phase, "path"))
        assertSame(checkNotNull(original), ownedCutField(phase, "testActiveRegistration"))
        assertEquals(sql.count { it == '?' }, args.size)
        val source = checkNotNull(dataSource)
        val connection = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
        assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
        assertTrue(advisory(connection, "complaint-maintenance-v1", "ShareLock"))
        assertFalse(advisory(connection, "complaint-maintenance-v1", "ExclusiveLock"))
        assertTrue(advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
        assertFalse(advisory(connection, "complaint-journal-epoch", "ShareLock"))
        val lease = ownedPoolLease(connection)
        val observed = observations.getOrPut(phase) {
            val identity = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { rows ->
                    assertTrue(rows.next())
                    (rows.getInt(1) to rows.getLong(2)).also { assertFalse(rows.next()) }
                }
            }
            StepUpPhaseObservation(phase, lease, identity)
        }
        assertSame(observed.lease, lease)
        assertFalse(lease.completion.quiescent())
        val call = TestActiveRegistrationSqlCallV1(phase, sql, args.map { if (it is ByteArray) it.copyOf() else it })
        calls.add(call)
        action().also { after(call) }
    } catch (failure: AssertionError) {
        assertion.compareAndSet(null, failure)
        throw failure
    }

    fun assertPhysicallyReleased() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
        observations.values.forEach { assertTrue(it.lease.completion.quiescent()) }
        assertNoLostAssertions()
    }

    fun assertCommittedAndReleased() {
        assertPhysicallyReleased()
        observations.keys.forEach { phase ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.testActiveRegistrationCleanupProven(checkNotNull(original)))
        }
    }

    fun assertNoLostAssertions() { assertion.get()?.let { throw it } }
}

internal class TestActiveRegistrationSqlCallV1(val phase: PersistencePhaseContext, val sql: String, val arguments: List<Any?>)
