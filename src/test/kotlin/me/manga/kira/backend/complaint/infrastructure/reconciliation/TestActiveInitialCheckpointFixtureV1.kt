package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.CounterSnapshot
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
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.catalog.withColdActiveRoot
import me.manga.kira.backend.complaint.catalog.withTestActiveFirstCut
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
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
import java.sql.Timestamp
import java.util.concurrent.atomic.AtomicReference

/**
 * Genuine protected cold inputs -> registered ACTIVE -> C capture -> actual A EMPTY seal. No SQL
 * seed for any seal/checkpoint or accepted pass. A's live30s lease expires on the ACTUAL DB clock
 * outside the new original; only the older fixture's closed PROJECT setup handoffs use its existing
 * explicit synthetic expiry. This is source-authored, NOT_RUN / NOT_RUNTIME_ACCEPTED.
 */
internal fun withInitialCheckpointFixture(tls: VersionBoundPersistenceConnectedFixture, enrolled: Boolean = false,
    waitForSealLease: Boolean = true, action: (TestActiveInitialCheckpointFixtureV1) -> Unit) {
    val ordinary = TestActiveOrdinaryRawFixtureV1()
    val raw = TestActiveInitialCheckpointRawFixtureV1()
    val factories = ordinary.factories.let { TestActiveOrdinaryRawHttpV1(it.sts, it.kms, it.s3, raw.input) }
    withTestActiveFirstCut(tls, ordinaryRawHttp = factories) { first ->
        assertTrue(raw.requests.isEmpty() && raw.sts.requests.isEmpty() && raw.kms.requests.isEmpty(), "Scanner is born-with but still cold.")
        if (enrolled) first.initial.withExchange { exchange -> exchange.enroll(first.initial.candidate()); exchange.assertReleased() }
        val captured = first.capture()
        first.awaitNativeReclaimed()
        TestActiveOrdinarySealFixtureV1(first, captured, ordinary).use { sealer ->
            val verified = sealer.seal()
            sealer.assertReleased()
            if (waitForSealLease) awaitInitialCheckpointLeaseExpiry(sealer.observer, sealer.scope)
            TestActiveInitialCheckpointFixtureV1(sealer, verified, raw).use(action)
        }
    }
}

/** Observation only, outside every original and SQL transaction. Never UPDATE a live lease or reset a budget. */
internal fun awaitInitialCheckpointLeaseExpiry(observer: JdbcTemplate, scope: java.util.UUID) {
    requireConnectionFree()
    val before = observer.queryForMap("SELECT lease_owner, lease_token, lease_expires_at FROM complaint_journal_control WHERE data_scope_id = ?", scope)
    val expiry = before["lease_expires_at"] as Timestamp? ?: return
    val stop = System.nanoTime() + 35_000_000_000L
    while (true) {
        requireConnectionFree()
        val current = observer.queryForMap("SELECT lease_owner, lease_token, lease_expires_at, clock_timestamp() >= lease_expires_at AS expired " +
            "FROM complaint_journal_control WHERE data_scope_id = ?", scope)
        assertEquals(before["lease_owner"], current["lease_owner"]); assertEquals(before["lease_token"], current["lease_token"])
        assertEquals(expiry, current["lease_expires_at"])
        if (current["expired"] == true) return
        check(System.nanoTime() - stop < 0) { "Actual predecessor lease did not expire within bounded fixture wait." }
        Thread.sleep(50)
    }
}

internal class TestActiveInitialCheckpointFixtureV1(
    val sealer: TestActiveOrdinarySealFixtureV1,
    val verified: TestActiveOrdinarySealV1.Verified?,
    val raw: TestActiveInitialCheckpointRawFixtureV1,
    val runtime: VersionBoundPersistenceConnectedFixture = sealer.runtime,
    val registration: ComplaintTestNamespaceRegistrationV1 = sealer.registration,
    val assembly: ComplaintTestProcessAssemblyV1 = sealer.first.assembly,
) : AutoCloseable {
    val process = registration.process
    val observer = sealer.observer
    val scope = sealer.scope
    val probe = TestInitialCheckpointProbeJdbcV1(this)
    private val executor = runtime.pools.catalogCoordinator.testActiveInitialCheckpoint
    private val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
    private val actual = field.get(executor) as JdbcTemplate
    private val beforeRead = sealer.p.f.http.beforeRead

    init {
        assertSame(actual.dataSource, probe.dataSource)
        field.set(executor, probe) // Passive holder/SQL probe before the original; every query/update is real.
        raw.attach(this)
        sealer.p.f.http.beforeRead = { beforeRead(); assertProviderBoundary() }
    }

    fun begin(restart: Boolean = verified == null): TestActiveInitialCheckpointV1 {
        probe.reset()
        return TestActiveInitialCheckpointV1.withHttpFixture(if (restart) null else checkNotNull(verified), registration, assembly,
            sealer.p.f.http::readClient, SignedActivationObservation.WALL_CLOCK).also { probe.original = it }
    }
    fun checkpoint(original: TestActiveInitialCheckpointV1 = begin()): TestActiveInitialCheckpointV1.Completed =
        original.checkpoint(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
    fun control() = observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", scope)
    fun scanRows(): List<Map<String, Any?>> = observer.queryForList(
        "SELECT * FROM complaint_journal_scan_runs WHERE data_scope_id = ? ORDER BY pass, scan_id", scope)
    fun counters(): Map<String, CounterSnapshot> = sealer.first.counters()
    fun image(): Map<String, List<String>> = sealer.image()

    fun assertSqlReleased() {
        requireConnectionFree(); assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
        probe.observations.values.forEach { assertTrue(it.lease.completion.quiescent()) }
        probe.assertNoLostAssertions(); raw.assertNoLostAssertions()
    }
    fun assertProviderBoundary() {
        assertSqlReleased()
        probe.observations.keys.forEach { phase ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.testActiveInitialCheckpointCleanupProven(checkNotNull(probe.original)))
        }
    }
    fun assertReleased() {
        assertSqlReleased(); raw.assertDisposed()
        assertEquals(0L, process.publicationLanes.activeOwners().totalOwners, "The scanner never borrows the sealer's publication lane.")
    }

    /** All-new original AND process/registration/native owners. Same JVM only; not a true two-JVM object-depot test. */
    fun withFreshAssembly(action: (TestActiveInitialCheckpointFixtureV1) -> Unit) {
        assertReleased()
        val previous = process
        val d = process.canonicalBytes()
        raw.detach(this)
        sealer.p.f.http.beforeRead = beforeRead
        try {
            withColdActiveRoot(sealer.first.initial) { cold ->
                assertTrue(d.contentEquals(cold.assembly.target.canonicalBytes()))
                assertNotSame(previous.initialCheckpoint, cold.assembly.target.initialCheckpoint)
                val original = cold.begin()
                cold.register(original).use { registered ->
                    cold.assertSuccessful(original, registered)
                    TestActiveInitialCheckpointFixtureV1(sealer, null, raw, cold.runtime, registered, cold.assembly).use(action)
                }
            }
        } finally {
            raw.attach(this)
            sealer.p.f.http.beforeRead = { beforeRead(); assertProviderBoundary() }
        }
    }

    override fun close() {
        probe.before = {}; probe.after = {}; raw.resetFaults()
        sealer.p.f.http.beforeRead = beforeRead
        assertSame(probe, field.get(executor)); field.set(executor, actual)
        raw.detach(this)
        probe.assertNoLostAssertions(); raw.assertNoLostAssertions()
        requireConnectionFree()
        // Explicit test teardown only, AFTER assertions. This is neither product cleanup/refund nor checkpoint proof.
        observer.update("DELETE FROM complaint_journal_scan_entries WHERE data_scope_id = ?", scope)
        observer.update("DELETE FROM complaint_journal_scan_runs WHERE data_scope_id = ?", scope)
    }
}

/** Original phase/holder/SQL observations only. No successful result, commit or physical cleanup is supplied. */
internal class TestInitialCheckpointProbeJdbcV1(private val f: TestActiveInitialCheckpointFixtureV1) : JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource) {
    var original: TestActiveInitialCheckpointV1? = null
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    val calls = mutableListOf<TestInitialCheckpointSqlCallV1>()
    var before: (TestInitialCheckpointSqlCallV1) -> Unit = {}
    var after: (TestInitialCheckpointSqlCallV1) -> Unit = {}
    private val assertion = AtomicReference<AssertionError?>()
    private var observing = false
    init { exceptionTranslator = SQLExceptionSubclassTranslator() }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = observed(sql, emptyArray()) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql, args) { super.query(sql, mapper, *args) }
    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? = observed(sql, args) { super.query(sql, extractor, *args) }
    override fun update(sql: String, vararg args: Any?): Int = observed(sql, args) { super.update(sql, *args) }
    fun reset() {
        requireConnectionFree(); assertNoLostAssertions(); observations.values.forEach { assertTrue(it.lease.completion.quiescent()) }
        observations.clear(); calls.clear(); original = null
    }
    private fun <T> observed(sql: String, args: Array<out Any?>, action: () -> T): T {
        if (observing) return action()
        observing = true
        try {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val owner = checkNotNull(original)
            assertEquals(PersistencePhasePath.COMPLAINT_TEST_ACTIVE_INITIAL_CHECKPOINT, poolTestField<PersistencePhasePath>(phase, "path"))
            assertEquals(sql.count { it == '?' }, args.size)
            val connection = (TransactionSynchronizationManager.getResource(checkNotNull(dataSource)) as ConnectionHolder).connection
            assertEquals(setOf(dataSource), TransactionSynchronizationManager.getResourceMap().keys)
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
            assertTrue(f.sealer.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
            assertFalse(f.sealer.p.advisory(connection, "complaint-maintenance-v1", "ExclusiveLock"))
            assertFalse(f.sealer.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
            assertFalse(f.sealer.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
            val lease = ownedPoolLease(connection)
            val observed = observations.getOrPut(phase) {
                val identity = connection.createStatement().use { statement -> statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { rows ->
                    assertTrue(rows.next()); (rows.getInt(1) to rows.getLong(2)).also { assertFalse(rows.next()) }
                } }
                StepUpPhaseObservation(phase, lease, identity)
            }
            assertSame(observed.lease, lease); assertFalse(lease.completion.quiescent())
            val call = TestInitialCheckpointSqlCallV1(phase, owner.step, owner.passNumber, sql)
            calls.add(call); before(call)
            return action().also { after(call) }
        } catch (failure: AssertionError) { assertion.compareAndSet(null, failure); throw failure }
        finally { observing = false }
    }
    fun assertNoLostAssertions() { assertion.get()?.let { throw it } }
}

internal class TestInitialCheckpointSqlCallV1(val phase: PersistencePhaseContext, val step: TestActiveInitialCheckpointStepV1,
    val pass: Int, val sql: String)
