package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.DeleteAllCounter
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJdbcTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.ComplaintTestNamespaceRegistrationCases
import me.manga.kira.backend.complaint.catalog.InitialAdmissionFixture
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture
import me.manga.kira.backend.complaint.catalog.SignedActivationObservation
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.catalog.withInitialAdmission
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.util.concurrent.atomic.AtomicReference

/** Protected born-with input -> actual signed PROJECT/registration -> actual initial identity release.
 * No seal/checkpoint/queue health seed; epoch1 remains ACTIVE. SQL/native execution is source-authored
 * for the parent's later approved lane only: NOT_COMPILED / NOT_RUN / NOT_RUNTIME_ACCEPTED.
 */
internal fun withActiveQueueFixture(tls: VersionBoundPersistenceConnectedFixture, action: (TestActiveOwnerDeleteQueueFixtureV1) -> Unit) {
    val raw = TestActiveOwnerDeleteQueueRawFixtureV1()
    val scanner = TestActiveInitialCheckpointHttpInputV1(
        { error("Queue must not open scanner STS") }, { error("Queue must not open scanner KMS") }, { error("Queue must not open scanner S3") })
    val factories = TestActiveOrdinaryRawHttpV1(
        { error("Queue must not open ordinary STS") }, { error("Queue must not open ordinary KMS") }, { error("Queue must not open ordinary S3") },
        initialCheckpoint = scanner, activeOwnerDeleteQueue = raw.input)
    withInitialAdmission(tls, activeFirstCut = true, ordinaryRawHttp = factories) { initial ->
        assertTrue(raw.order.isEmpty() && raw.sqs.requests.isEmpty(), "Registration is not queue autostart.")
        initial.release()
        assertTrue(raw.order.isEmpty() && raw.sqs.requests.isEmpty(), "Identity release is not queue autostart.")
        assertEquals(PersistenceLifecycleObservation.READY, initial.runtime.pools.deletion.prepareDeletion())
        ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(initial.runtime) { _, audit ->
            TestActiveOwnerDeleteQueueFixtureV1(initial, raw, audit).use(action)
        }
    }
}

/** Real coordinator/deletion owners; templates only observe original SQL, holder identity and release. */
internal class TestActiveOwnerDeleteQueueFixtureV1(
    val initial: InitialAdmissionFixture,
    val raw: TestActiveOwnerDeleteQueueRawFixtureV1,
    private val audit: AuditService,
) : AutoCloseable {
    val runtime = initial.runtime
    val registration = initial.registration
    val assembly = initial.assembly
    val process = registration.process
    val observer = initial.observer
    val scope = process.consumers.journalConfiguration.scope.id
    val deletionOwner = PersistencePhaseOwnership.deletion(DeletionPersistenceAdmission(),
        GuardedJdbcTransactionManager(runtime.pools.deletion), PersistenceNanoClock(initial.native::nanos))
    val coordinator = TestActiveQueueProbeJdbcV1(this, deletion = false)
    val deletion = TestActiveQueueProbeJdbcV1(this, deletion = true)
    val calls = mutableListOf<TestActiveQueueSqlCallV1>()
    var original: TestActiveOwnerDeleteQueueV1? = null
        private set
    var before: (TestActiveQueueSqlCallV1) -> Unit = {}
    var after: (TestActiveQueueSqlCallV1) -> Unit = {}
    private val executor = runtime.pools.catalogCoordinator.testActiveOwnerDeleteQueue
    private val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
    private val actual = field.get(executor) as JdbcTemplate
    private val beforeRead = initial.p.f.http.beforeRead

    init {
        val author = initial.p.f.rows.evidence.process
        val owned = checkNotNull(process.activeOwnerDeleteQueue)
        val projected = checkNotNull(author.activeOwnerDeleteQueue)
        assertNotSame(owned, projected); assertNotSame(process.pools, author.pools)
        assertEquals(owned.inventory(), projected.inventory()); assertArrayEquals(process.canonicalBytes(), author.canonicalBytes())
        assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { owned.requireRetained(author.consumers.journalRouting, author.pools) }
        assertSame(actual.dataSource, coordinator.dataSource); field.set(executor, coordinator)
        raw.attach(this)
        initial.p.f.http.beforeRead = { beforeRead(); assertProviderBoundary() }
    }

    fun begin(): TestActiveOwnerDeleteQueueV1 {
        requireConnectionFree(); assertSqlReleased()
        coordinator.reset(); deletion.reset(); calls.clear()
        return TestActiveOwnerDeleteQueueV1.withHttpFixture(registration, assembly, deletionOwner, deletion, audit,
            initial.p.f.http::readClient, SignedActivationObservation.WALL_CLOCK).also { original = it }
    }
    fun poll(selected: TestActiveOwnerDeleteQueueV1 = begin()): TestActiveOwnerDeleteQueueV1.Completed =
        selected.poll(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)

    fun control() = observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", scope)
    fun observation() = observer.queryForList("SELECT * FROM complaint_test_active_queue_observations WHERE data_scope_id = ?", scope).singleOrNull()
    fun count(table: String): Long {
        require(table in TABLES)
        return checkNotNull(observer.queryForObject("SELECT count(*) FROM $table WHERE data_scope_id = ?", Long::class.java, scope))
    }
    fun audits(): Map<String, Long> = observer.query("SELECT action, count(*) AS n FROM audit_log WHERE complaint_data_scope_id = ? GROUP BY action",
        { row, _ -> row.getString("action") to row.getLong("n") }, scope).toMap()
    fun counters(): Map<ComplaintCapacityCounter, DeleteAllCounter> = observer.query(
        "SELECT name, free_units, actual_units, recovery_reserved_units, test_reserved_units, " +
            "(to_jsonb(c) - ARRAY['free_units','actual_units','recovery_reserved_units','updated_at'])::text AS preserved " +
            "FROM complaint_capacity_counters c ORDER BY ordinal",
        { row, _ -> ComplaintCapacityCounter.entries.single { it.storedName == row.getString("name") } to
            DeleteAllCounter(row.getLong(2), row.getLong(3), row.getLong(4), row.getLong(5), row.getString(6)) }).toMap()
    fun image(): Map<String, List<String>> = TABLES.associateWith { table -> observer.queryForList(
        "SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM $table r WHERE data_scope_id = ? ORDER BY to_jsonb(r)::text COLLATE \"C\"",
        String::class.java, scope) } + ("audit" to observer.queryForList(
        "SELECT to_jsonb(a)::text FROM audit_log a WHERE complaint_data_scope_id = ? ORDER BY id", String::class.java, scope))

    fun assertSqlReleased() {
        requireConnectionFree(); assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
        listOf(coordinator, deletion).forEach { probe ->
            probe.observations.values.forEach { assertTrue(it.lease.completion.quiescent()) }
            probe.assertNoLostAssertions()
        }
        raw.assertNoLostAssertions()
    }
    fun assertProviderBoundary() {
        assertSqlReleased()
        listOf(coordinator, deletion).flatMap { it.observations.keys }.forEach { phase ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.testActiveOwnerDeleteQueueCleanupProven(checkNotNull(original)))
        }
    }
    fun assertAckBoundary(deadLetter: Boolean) {
        assertProviderBoundary()
        val owner = checkNotNull(original)
        assertEquals(TestActiveOwnerDeleteQueueStepV1.ACK, owner.step)
        assertEquals(0, if (deadLetter) owner.dlqAcked else owner.primaryAcked)
        val applied = calls.last { it.step === TestActiveOwnerDeleteQueueStepV1.APPLY }.phase
        val check = calls.last { it.step === TestActiveOwnerDeleteQueueStepV1.RECHECK }.phase
        assertEquals(check, calls.last().phase, "The fresh recheck immediately precedes each DeleteMessage.")
        listOf(applied, check).forEach { phase ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.testActiveOwnerDeleteQueueCleanupProven(owner))
        }
        assertEquals(1L, count("complaint_deletion_journal_applied"))
        assertEquals(raw.sts.createdClients, raw.sts.returnedClientCloses)
        assertEquals(raw.kms.createdClients, raw.kms.returnedClientCloses)
        assertEquals(raw.s3Created, raw.s3CloseReturned)
        assertEquals(raw.sqs.createdClients - 1, raw.sqs.returnedClientCloses,
            "Only this DeleteMessage client is live; the ReceiveMessage graph really retired before APPLY.")
    }
    fun assertReleased() {
        assertSqlReleased(); raw.assertDisposed()
        assertEquals(0L, process.publicationLanes.activeOwners().totalOwners)
        assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, checkNotNull(process.pools.epochRotation).observePreparation())
    }
    fun assertNoAuthority() {
        assertNull(control()["checkpoint_result"])
        assertEquals(1L, control()["publication_epoch"])
        assertNull(control()["rotation_state"])
        assertEquals(0L, count("complaint_journal_scan_runs")); assertEquals(0L, count("complaint_journal_scan_entries"))
        assertEquals(0L, count("complaint_test_terminal_intents"))
        assertFalse(observation()?.get("state") == "HEALTHY")
    }
    fun assertSameOriginalRefused(selected: TestActiveOwnerDeleteQueueV1) {
        val rows = image(); val sql = calls.size; val native = raw.order.toList(); val ack = raw.ackRequests.toList()
        assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { poll(selected) }
        assertEquals(rows, image()); assertEquals(sql, calls.size); assertEquals(native, raw.order); assertEquals(ack, raw.ackRequests)
    }
    override fun close() {
        before = {}; after = {}; raw.resetFaults()
        initial.p.f.http.beforeRead = beforeRead
        assertSame(coordinator, field.get(executor)); field.set(executor, actual)
        raw.detach(this); coordinator.assertNoLostAssertions(); deletion.assertNoLostAssertions(); raw.assertNoLostAssertions()
        requireConnectionFree()
        // Exact TEST scope teardown AFTER assertions, not product purge/refund or queue completion.
        listOf("complaint_test_active_queue_observations", "complaint_idempotency_receipts", "complaint_deletion_journal_applied",
            "complaint_recovery_capacity_reservations", "complaint_journal_publications", "complaint_resource_ids", "complaint_installation_ids")
            .forEach { observer.update("DELETE FROM $it WHERE data_scope_id = ?", scope) }
        observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED'", scope)
    }
    companion object {
        val TABLES = listOf("complaint_idempotency_receipts", "complaint_journal_publications", "complaint_recovery_capacity_reservations",
            "complaint_deletion_journal_applied", "complaint_installation_ids", "app_installations", "complaint_resource_ids", "complaints",
            "complaint_journal_scan_runs", "complaint_journal_scan_entries", "complaint_test_terminal_intents", "complaint_test_active_queue_observations")
    }
}

/** Passive exact SQL/READ_COMMITTED holder probe. It never returns rows, changes COMMIT, or supplies cleanup. */
internal class TestActiveQueueProbeJdbcV1(private val f: TestActiveOwnerDeleteQueueFixtureV1, private val deletion: Boolean) :
    JdbcTemplate(if (deletion) f.runtime.pools.deletion else f.runtime.pools.catalogCoordinator.dataSource) {
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    private val assertion = AtomicReference<AssertionError?>()
    private var observing = false
    init { exceptionTranslator = SQLExceptionSubclassTranslator() }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = observed(sql, emptyArray()) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql, args) { super.query(sql, mapper, *args) }
    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? = observed(sql, args) { super.query(sql, extractor, *args) }
    override fun update(sql: String, vararg args: Any?): Int = observed(sql, args) { super.update(sql, *args) }
    fun reset() { assertNoLostAssertions(); observations.values.forEach { assertTrue(it.lease.completion.quiescent()) }; observations.clear() }
    private fun <T> observed(sql: String, args: Array<out Any?>, action: () -> T): T {
        if (observing) return action()
        observing = true
        try {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val path = poolTestField<PersistencePhasePath>(phase, "path")
            assertEquals(if (deletion) PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY else PersistencePhasePath.COMPLAINT_TEST_ACTIVE_OWNER_DELETE_QUEUE, path)
            assertEquals(sql.count { it == '?' }, args.size)
            val connection = (TransactionSynchronizationManager.getResource(checkNotNull(dataSource)) as ConnectionHolder).connection
            assertEquals(setOf(dataSource), TransactionSynchronizationManager.getResourceMap().keys)
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
            assertTrue(f.initial.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
            assertFalse(f.initial.p.advisory(connection, "complaint-maintenance-v1", "ExclusiveLock"))
            assertEquals(deletion, f.initial.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
            assertFalse(f.initial.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
            val lease = ownedPoolLease(connection)
            val observed = observations.getOrPut(phase) {
                val identity = connection.createStatement().use { statement -> statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { rows ->
                    assertTrue(rows.next()); (rows.getInt(1) to rows.getLong(2)).also { assertFalse(rows.next()) }
                } }
                StepUpPhaseObservation(phase, lease, identity)
            }
            assertSame(observed.lease, lease); assertFalse(lease.completion.quiescent())
            val call = TestActiveQueueSqlCallV1(phase, checkNotNull(f.original).step, sql)
            f.calls.add(call); f.before(call)
            return action().also { f.after(call) }
        } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }
        finally { observing = false }
    }
    fun assertNoLostAssertions() { assertion.get()?.let { throw it } }
}

internal class TestActiveQueueSqlCallV1(val phase: PersistencePhaseContext, val step: TestActiveOwnerDeleteQueueStepV1, val sql: String)
