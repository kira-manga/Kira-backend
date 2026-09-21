package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture
import me.manga.kira.backend.complaint.catalog.SignedActivationObservation
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutFixtureV1
import me.manga.kira.backend.complaint.catalog.withTestActiveFirstCut
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
 * SOURCE ONLY / NOT RUN. C's genuine protected ACTIVE intake, initial identity release and native
 * CAPTURE are not replaced. Immediate C-to-A use leaves the real fractional-second wait to A's
 * unchanged original deadline; no future time, prior checkpoint or health observation is supplied.
 */
internal fun withActiveSealFixture(
    tls: VersionBoundPersistenceConnectedFixture,
    enrolled: Boolean = false,
    action: (TestActiveOrdinarySealFixtureV1) -> Unit,
) {
    val ordinary = TestActiveOrdinaryRawFixtureV1()
    withTestActiveFirstCut(tls, ordinaryRawHttp = ordinary.factories) { first ->
        if (enrolled) first.initial.withExchange { exchange ->
            exchange.enroll(first.initial.candidate())
            exchange.assertReleased()
            assertEquals(1L, first.observer.queryForObject(
                "SELECT enrolled_count FROM complaint_test_runs WHERE data_scope_id = ?", Long::class.java, first.scope))
        }
        val captured = first.capture()
        first.awaitNativeReclaimed()
        TestActiveOrdinarySealFixtureV1(first, captured, ordinary).use(action)
    }
}

internal class TestActiveOrdinarySealFixtureV1(
    val first: TestActiveFirstCutFixtureV1,
    val captured: TestActiveFirstCutV1.Captured,
    val ordinary: TestActiveOrdinaryRawFixtureV1,
) : AutoCloseable {
    val p = first.p
    val runtime = first.runtime
    val registration = first.registration
    val process = first.process
    val native = first.native
    val observer = first.observer
    val scope = first.scope
    val probe = TestActiveSealProbeJdbcV1(this)
    private val executor = runtime.pools.catalogCoordinator.testActiveOrdinarySeal
    private val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
    private val originalJdbc = field.get(executor) as JdbcTemplate
    private val beforeRead = p.f.http.beforeRead

    init {
        assertSame(originalJdbc.dataSource, probe.dataSource)
        field.set(executor, probe) // Passive original SQL/holder observer selected before any A entry.
        ordinary.attach(this)
        native.boundary = ::assertProviderBoundary
        native.nativeBoundary = ::assertSqlReleased
        p.f.http.beforeRead = { beforeRead(); assertProviderBoundary() }
    }

    fun begin(): TestActiveOrdinarySealV1 = TestActiveOrdinarySealV1.withHttpFixture(
        captured, registration, first.assembly, p.f.http::readClient, SignedActivationObservation.WALL_CLOCK,
    ).also { assertNull(probe.original); probe.original = it }

    fun seal(original: TestActiveOrdinarySealV1 = begin()): TestActiveOrdinarySealV1.Verified =
        original.seal(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)

    fun assertSqlReleased() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
        probe.observations.values.forEach { assertTrue(it.lease.completion.quiescent()) }
        probe.assertNoLostAssertions()
    }

    fun assertProviderBoundary() {
        assertSqlReleased()
        probe.observations.keys.forEach { phase ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.testActiveOrdinarySealCleanupProven(checkNotNull(probe.original)))
        }
    }

    fun assertReleased() {
        assertProviderBoundary()
        assertEquals(0L, process.publicationLanes.activeOwners().totalOwners)
        native.assertDisposed()
        ordinary.assertDisposed()
    }

    fun control() = first.control()
    fun paid() = first.paid()

    /** Independent raw observer, including while the original owns its single Spring resource. */
    fun image(): Map<String, List<String>> = checkNotNull(observer.dataSource).connection.use { connection ->
        p.image(connection).toMutableMap().also { image ->
            for (table in listOf("complaint_journal_publications", "complaint_test_active_seal_intents", "complaint_test_terminal_intents",
                "complaint_journal_scan_runs", "complaint_idempotency_receipts", "complaint_recovery_capacity_reservations",
                "complaint_deletion_journal_applied", "complaint_deletion_journal_retirements", "installation_deletion_receipts",
                "app_installations", "complaint_installation_ids")) {
                connection.prepareStatement("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text").use { statement ->
                    statement.queryTimeout = 2; statement.setObject(1, scope)
                    statement.executeQuery().use { rows -> image[table] = buildList { while (rows.next()) add(rows.getString(1)) } }
                }
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaint_journal_control c WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'").use { rows ->
                    image["global-full"] = buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }
    }

    override fun close() {
        probe.before = {}; probe.after = {}
        native.boundary = {}; native.nativeBoundary = {}; native.onNativeClose = {}; native.beforeS3 = {}; native.changeS3 = { _, _ -> }
        p.f.http.beforeRead = beforeRead
        assertSame(probe, field.get(executor)); field.set(executor, originalJdbc)
        ordinary.detach()
        probe.assertNoLostAssertions()
    }
}

/** All SQL/results/commits are real; no successful operation, phase, result or release is injectable. */
internal class TestActiveSealProbeJdbcV1(private val f: TestActiveOrdinarySealFixtureV1) : JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource) {
    var original: TestActiveOrdinarySealV1? = null
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    val calls = mutableListOf<TestActiveSealSqlCallV1>()
    var before: (TestActiveSealSqlCallV1) -> Unit = {}
    var after: (TestActiveSealSqlCallV1) -> Unit = {}
    private val assertion = AtomicReference<AssertionError?>()
    private var observing = false
    init { exceptionTranslator = SQLExceptionSubclassTranslator() }

    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = observed(sql, emptyArray()) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql, args) { super.query(sql, mapper, *args) }
    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? = observed(sql, args) { super.query(sql, extractor, *args) }
    override fun update(sql: String, vararg args: Any?): Int = observed(sql, args) { super.update(sql, *args) }

    private fun <T> observed(sql: String, args: Array<out Any?>, action: () -> T): T {
        if (observing) return action() // JdbcTemplate overload delegation is not a second SQL dispatch.
        observing = true
        try {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val owner = checkNotNull(original)
            val path = poolTestField<PersistencePhasePath>(phase, "path")
            assertEquals(owner.path, path)
            assertEquals(sql.count { it == '?' }, args.size)
            val connection = (TransactionSynchronizationManager.getResource(checkNotNull(dataSource)) as ConnectionHolder).connection
            assertEquals(setOf(dataSource), TransactionSynchronizationManager.getResourceMap().keys)
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
            assertTrue(f.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
            assertFalse(f.p.advisory(connection, "complaint-journal-epoch", "ShareLock"), "A does not reacquire C's retired E.")
            assertFalse(f.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
            val lease = ownedPoolLease(connection)
            val observed = observations.getOrPut(phase) {
                val identity = connection.createStatement().use { statement -> statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { rows ->
                    assertTrue(rows.next()); (rows.getInt(1) to rows.getLong(2)).also { assertFalse(rows.next()) }
                } }
                StepUpPhaseObservation(phase, lease, identity)
            }
            assertSame(observed.lease, lease); assertFalse(lease.completion.quiescent())
            val call = TestActiveSealSqlCallV1(phase, owner.step, sql)
            calls.add(call); before(call)
            return action().also { after(call) }
        } catch (failure: AssertionError) { assertion.compareAndSet(null, failure); throw failure }
        finally { observing = false }
    }
    fun assertNoLostAssertions() { assertion.get()?.let { throw it } }
}

internal class TestActiveSealSqlCallV1(val phase: PersistencePhaseContext, val step: TestActiveOrdinarySealStepV1, val sql: String)
