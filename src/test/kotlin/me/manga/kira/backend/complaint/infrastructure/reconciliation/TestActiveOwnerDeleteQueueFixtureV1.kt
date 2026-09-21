package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.DeleteAllCounter
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture
import me.manga.kira.backend.complaint.catalog.SignedActivationObservation
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainFixtureInputsV1
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Genuine C global predecessor -> fresh TEST root -> enrollment/capture/seal/two-pass checkpoint ->
 * real CREATE(s) -> A AUTH/native PUT/readback/VERIFY, stopping before APPLY. Queue recipes (and any
 * terminal-history recipe) are selected before full D. Nothing here fabricates eligibility or a key.
 * Source-authored only; parent owns compilation/execution and runtime acceptance.
 */
internal fun withActiveQueueFixture(
    tls: VersionBoundPersistenceConnectedFixture,
    family: ComplaintJournalDeletionKindV1 = ComplaintJournalDeletionKindV1.OWNER_DELETE,
    verifyPublication: Boolean = true,
    terminalHistory: TestOrdinaryDrainFixtureInputsV1? = null,
    action: (TestActiveOwnerDeleteQueueFixtureV1) -> Unit,
) {
    val raw = TestActiveOwnerDeleteQueueRawFixtureV1()
    withRegisteredInitialCheckpointDeletion(tls, family, queueHttp = raw.input, terminalHistory = terminalHistory) { precursor ->
        assertTrue(raw.order.isEmpty() && raw.sqs.requests.isEmpty(), "The genuine predecessor is not queue autostart.")
        val beforeAuthorization = precursor.counters()
        val event = precursor.authorize()
        val afterAuthorization = precursor.counters()
        val record = precursor.publish()
        assertSame(event, record.event)
        if (verifyPublication) precursor.verify()
        precursor.assertReleased()
        assertEquals(afterAuthorization, precursor.counters(), "Native work and VERIFY do not charge again.")
        assertTrue(raw.order.isEmpty() && raw.sqs.requests.isEmpty(), "AUTH/VERIFY never polls or acks the queue.")
        TestActiveOwnerDeleteQueueFixtureV1(precursor, raw, record, beforeAuthorization, afterAuthorization, verifyPublication).use(action)
    }
}

/** The exact registered A deletion owner/template are retained, not replaced by a similar pair. */
internal class TestActiveOwnerDeleteQueueFixtureV1(
    val precursor: TestRegisteredInitialCheckpointDeletionFixtureV1,
    val raw: TestActiveOwnerDeleteQueueRawFixtureV1,
    val record: TestRegisteredInitialDeletionNativeRecordV1,
    val beforeAuthorization: Map<ComplaintCapacityCounter, DeleteAllCounter>,
    val afterAuthorization: Map<ComplaintCapacityCounter, DeleteAllCounter>,
    val verifiedPublication: Boolean,
) : AutoCloseable {
    val initial = precursor.initial
    val family = precursor.family
    val runtime = precursor.runtime
    val registration = precursor.registration
    val assembly = precursor.assembly
    val process = precursor.process
    val observer = precursor.observer
    val scope = precursor.scope
    val deletionOwner = precursor.deletionOwner
    val deletion = precursor.deletion
    val coordinator = TestActiveQueueProbeJdbcV1(this)
    // Queue-only observations. Prior genuine AUTH/VERIFY phases belong to A, never this queue.
    val deletionObservations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    val calls = CopyOnWriteArrayList<TestActiveQueueSqlCallV1>()
    val countsBeforeQueue = TABLES.associateWith(::count)
    val auditsBeforeQueue = audits()
    val proofBeforeQueue = publicationProof()
    val receiptBeforeQueue = receiptIdentity()
    val identitiesBeforeQueue = identityImage()
    val credentialIdentityBeforeQueue = credentialIdentity()
    val grantBeforeQueue = grantImage()
    private val authoritiesBeforeQueue = authorityImage()
    private val epochPreparation = checkNotNull(process.pools.epochRotation).observePreparation()
    var original: TestActiveOwnerDeleteQueueV1? = null
        private set
    var before: (TestActiveQueueSqlCallV1) -> Unit = {}
    var after: (TestActiveQueueSqlCallV1) -> Unit = {}
    private val executor = runtime.pools.catalogCoordinator.testActiveOwnerDeleteQueue
    private val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
    private val actual = field.get(executor) as JdbcTemplate
    private val beforeRead = initial.p.f.http.beforeRead
    private val beforeDeletion = deletion.before
    private val afterDeletion = deletion.after

    init {
        val author = initial.p.f.rows.evidence.process
        val owned = checkNotNull(process.activeOwnerDeleteQueue)
        val projected = checkNotNull(author.activeOwnerDeleteQueue)
        assertNotSame(owned, projected); assertNotSame(process.pools, author.pools)
        assertEquals(owned.inventory(), projected.inventory()); assertArrayEquals(process.canonicalBytes(), author.canonicalBytes())
        assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { owned.requireRetained(author.consumers.journalRouting, author.pools) }
        assertSame(actual.dataSource, coordinator.dataSource); field.set(executor, coordinator)
        raw.attach(this, record)
        deletion.before = { call -> beforeDeletion(call); observeDeletion(call) }
        deletion.after = { call ->
            afterDeletion(call)
            val observed = calls.last()
            assertSame(call.phase, observed.phase); assertEquals(call.sql, observed.sql)
            after(observed)
        }
        initial.p.f.http.beforeRead = { beforeRead(); assertProviderBoundary() }
    }

    fun begin(): TestActiveOwnerDeleteQueueV1 {
        requireConnectionFree(); assertSqlReleased()
        coordinator.reset(); deletionObservations.clear(); calls.clear()
        return TestActiveOwnerDeleteQueueV1.withHttpFixture(registration, assembly, deletionOwner, deletion, precursor.audit,
            initial.p.f.http::readClient, SignedActivationObservation.WALL_CLOCK).also {
                original = it
                assertSame(deletion, ownedCutField(it, "deletionJdbc"))
                assertSame(deletionOwner, ownedCutField(it, "deletionOwner"))
            }
    }
    fun poll(selected: TestActiveOwnerDeleteQueueV1 = begin()): TestActiveOwnerDeleteQueueV1.Completed =
        selected.poll(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)

    fun control() = observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", scope)
    fun observation() = observer.queryForList("SELECT * FROM complaint_test_active_queue_observations WHERE data_scope_id = ?", scope).singleOrNull()
    fun count(table: String): Long {
        require(table in TABLES)
        return checkNotNull(observer.queryForObject("SELECT count(*) FROM $table WHERE data_scope_id = ?", Long::class.java, scope))
    }
    fun audits() = precursor.audits()
    fun counters() = precursor.counters()
    fun image(): Map<String, List<String>> = TABLES.associateWith { table -> observer.queryForList(
        "SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM $table r WHERE data_scope_id = ? ORDER BY to_jsonb(r)::text COLLATE \"C\"",
        String::class.java, scope) } + ("audit" to observer.queryForList(
        "SELECT to_jsonb(a)::text FROM audit_log a WHERE complaint_data_scope_id = ? ORDER BY id", String::class.java, scope))
    fun domainImage() = image() - "complaint_test_active_queue_observations"
    fun publicationProof(): String = checkNotNull(observer.queryForObject(
        "SELECT (to_jsonb(p) - ARRAY['state','applied_at'])::text FROM complaint_journal_publications p WHERE event_id = ? AND data_scope_id = ?",
        String::class.java, record.event.route.eventId, scope))
    fun receipt() = if (family == ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) observer.queryForMap(
        "SELECT * FROM installation_deletion_receipts WHERE installation_id = ? AND data_scope_id = ?", precursor.actor.id, scope)
    else observer.queryForMap("SELECT * FROM complaint_idempotency_receipts WHERE idempotency_key = ? AND data_scope_id = ?", precursor.key, scope)
    fun receiptIdentity(): String {
        val table = if (family == ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) "installation_deletion_receipts" else "complaint_idempotency_receipts"
        return checkNotNull(observer.queryForObject(
            "SELECT (to_jsonb(n) - ARRAY['state','outcome','response_status','ack_ids','ack_versions','external_event_id','external_epoch'," +
                "'external_object_version','external_ciphertext_hash','completed_at','expires_at'])::text FROM $table n WHERE publication_ref = ? AND data_scope_id = ?",
            String::class.java, record.event.route.eventId, scope))
    }
    fun identityImage() = listOf("app_installations", "complaint_installation_ids").associateWith { table -> observer.queryForList(
        "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t WHERE data_scope_id = ? ORDER BY id", String::class.java, scope) }
    fun credentialIdentity(): List<String> = observer.queryForList(
        "SELECT (to_jsonb(c) - ARRAY['state','credential_version','version','deleted_at','verifier_expires_at','updated_at'])::text " +
            "FROM app_installations c WHERE data_scope_id = ? ORDER BY id", String::class.java, scope)
    fun grantImage(): List<String> = precursor.proof?.let { proof -> observer.queryForList(
        "SELECT jsonb_build_array(to_jsonb(g), g.xmin::text)::text FROM admin_step_up_grants g WHERE id = ?", String::class.java, proof.grantId) } ?: emptyList()
    private fun authorityImage(): List<String> = observer.queryForList(
        "SELECT jsonb_build_object('scope', c.data_scope_id, 'history', " +
            "(SELECT jsonb_object_agg(key, value) FROM jsonb_each(to_jsonb(c)) WHERE key = 'publication_epoch' OR key ~ '^(rotation_|seal_|checkpoint_)'))::text " +
            "FROM complaint_journal_control c ORDER BY data_scope_id", String::class.java) + observer.queryForList(
        "SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM complaint_test_runs r WHERE data_scope_id = ?", String::class.java, scope)

    fun assertSqlReleased() {
        precursor.assertSqlReleased()
        requireConnectionFree(); assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
        (coordinator.observations.values + deletionObservations.values).forEach { assertTrue(it.lease.completion.quiescent()) }
        coordinator.assertNoLostAssertions(); deletion.assertNoLostAssertions(); raw.assertNoLostAssertions()
    }
    fun assertProviderBoundary() {
        assertSqlReleased()
        (coordinator.observations.keys + deletionObservations.keys).forEach { phase ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.testActiveOwnerDeleteQueueCleanupProven(checkNotNull(original)))
        }
    }
    private fun observeDeletion(call: TestRegisteredInitialDeletionSqlCallV1) {
        val owner = checkNotNull(original)
        assertEquals(TestActiveOwnerDeleteQueueStepV1.APPLY, owner.step)
        assertEquals(owner.path, call.path)
        assertEquals(when (family) {
            ComplaintJournalDeletionKindV1.OWNER_DELETE -> PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY
            ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY
        }, call.path)
        val connection = (TransactionSynchronizationManager.getResource(checkNotNull(deletion.dataSource)) as ConnectionHolder).connection
        assertEquals(setOf(deletion.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
        assertTrue(initial.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
        assertTrue(initial.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
        assertFalse(initial.p.advisory(connection, "complaint-maintenance-v1", "ExclusiveLock"))
        assertFalse(initial.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
        val observed = deletion.observations.getValue(call.phase)
        assertSame(observed.lease, ownedPoolLease(connection))
        assertSame(observed, deletionObservations.getOrPut(call.phase) { observed })
        val captured = TestActiveQueueSqlCallV1(call.phase, owner.step, call.sql)
        calls.add(captured); before(captured)
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
        assertEquals(PersistenceLifecycleObservation.READY, epochPreparation, "C's genuine capture already prepared E.")
        assertEquals(epochPreparation, checkNotNull(process.pools.epochRotation).observePreparation())
    }
    fun assertNoAuthority() {
        assertEquals(authoritiesBeforeQueue, authorityImage(), "Queue outcomes do not mint or rewrite the genuine checkpoint/rotation/run history.")
        assertEquals(2L, control()["publication_epoch"])
        assertEquals("CAPTURED", control()["rotation_state"])
        listOf("complaint_journal_scan_runs", "complaint_journal_scan_entries", "complaint_test_terminal_intents").forEach {
            assertEquals(countsBeforeQueue.getValue(it), count(it), it)
        }
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
        deletion.before = beforeDeletion; deletion.after = afterDeletion
        assertSame(coordinator, field.get(executor)); field.set(executor, actual)
        raw.detach(this); coordinator.assertNoLostAssertions(); deletion.assertNoLostAssertions(); raw.assertNoLostAssertions()
        requireConnectionFree()
        // B owns only its observation teardown. A then removes deletion N/P/L; creator/initial
        // nesting owns its genuine domain/identity history. This is not a product refund/purge.
        observer.update("DELETE FROM complaint_test_active_queue_observations WHERE data_scope_id = ?", scope)
    }
    companion object {
        val TABLES = listOf("installation_deletion_receipts", "complaint_idempotency_receipts", "complaint_journal_publications", "complaint_recovery_capacity_reservations",
            "complaint_deletion_journal_applied", "complaint_installation_ids", "app_installations", "complaint_resource_ids", "complaints",
            "complaint_journal_scan_runs", "complaint_journal_scan_entries", "complaint_test_terminal_intents", "complaint_test_active_queue_observations")
    }
}

/** Passive exact SQL/READ_COMMITTED holder probe. It never returns rows, changes COMMIT, or supplies cleanup. */
internal class TestActiveQueueProbeJdbcV1(private val f: TestActiveOwnerDeleteQueueFixtureV1) :
    JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource) {
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
            assertEquals(PersistencePhasePath.COMPLAINT_TEST_ACTIVE_OWNER_DELETE_QUEUE, path)
            assertEquals(sql.count { it == '?' }, args.size)
            val connection = (TransactionSynchronizationManager.getResource(checkNotNull(dataSource)) as ConnectionHolder).connection
            assertEquals(setOf(dataSource), TransactionSynchronizationManager.getResourceMap().keys)
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
            assertTrue(f.initial.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
            assertFalse(f.initial.p.advisory(connection, "complaint-maintenance-v1", "ExclusiveLock"))
            assertFalse(f.initial.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
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
