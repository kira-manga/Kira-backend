package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.DeleteAllCounter
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceEpochRotationSession
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhysicalEntry
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture
import me.manga.kira.backend.complaint.catalog.SignedActivationObservation
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainFixtureInputsV1
import me.manga.kira.backend.complaint.catalog.withTestActiveFirstCut
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentCheckpointDocumentV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Genuine C global predecessor/fresh TEST assembly -> enrollment -> initial capture/seal/checkpoint
 * -> CREATE -> A AUTH/native ciphertext -> optional VERIFY/genuine B queue APPLY. Recurrent inputs
 * are selected BEFORE D. Only raw HTTP, passive observations and explicit failure injection vary.
 * Opt-in ALL history is ONE actual A producer plus explicitly labeled reference-protocol alias
 * bytes consumed/authenticated by the actual B queue. It is not a second AUTH/PUT producer.
 * The existing raw seal depot's opt-in three-key support does not supply terminal authority.
 */
internal fun withRecurrentFixture(tls: VersionBoundPersistenceConnectedFixture,
    family: ComplaintJournalDeletionKindV1 = ComplaintJournalDeletionKindV1.OWNER_DELETE,
    applied: Boolean = true, maximumVersions: Long = 10_000, maximumBytes: Long? = null, verified: Boolean = true,
    retainedAllAlias: Boolean = false,
    action: (TestActiveRecurrentFixtureV1) -> Unit) {
    require(verified || !applied)
    require(!retainedAllAlias || family == ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL && verified)
    val raw = TestActiveRecurrentRawFixtureV1()
    val native = raw.deletion
    val history = TestOrdinaryDrainFixtureInputsV1(maximumRetainedVersions = maximumVersions, maximumFramedBytes = maximumBytes)
    withTestActiveFirstCut(tls, ordinaryRawHttp = raw.factories, terminalHistory = history, globalScanBeforeActivation = true) { first ->
        first.initial.withExchange { exchange ->
            val owners = List(if (family == ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE) 2 else 1) {
                val candidate = first.initial.candidate()
                candidate.installation to exchange.enroll(candidate).session.accessToken
            }
            exchange.assertReleased()
            val captured = first.capture()
            first.awaitNativeReclaimed()
            TestActiveOrdinarySealFixtureV1(first, captured, native.ordinary).use { sealer ->
                val sealed = sealer.seal(); sealer.assertReleased()
                awaitInitialCheckpointLeaseExpiry(sealer.observer, sealer.scope)
                TestActiveInitialCheckpointFixtureV1(sealer, sealed, native.checkpoint).use { checkpoint ->
                    checkpoint.checkpoint(); checkpoint.assertReleased()
                    val creators = owners.map { (actor, token) -> TestRegisteredInitialCheckpointCreateFixtureV1(checkpoint, exchange, actor, token) }
                    try {
                        val reports = creators.map { creator -> creator.attempt().also { creator.assertApplied(creator.create(it), it) } }
                        assertEquals(PersistenceLifecycleObservation.READY, first.runtime.pools.deletion.prepareDeletion())
                        TestRegisteredInitialCheckpointDeletionFixtureV1(checkpoint, exchange, creators, reports, native, family).use { precursor ->
                            val before = precursor.counters()
                            val event = precursor.authorize()
                            val after = precursor.counters()
                            val record = precursor.publish()
                            assertEquals(event.semanticSha256, record.event.semanticSha256)
                            if (verified) precursor.verify()
                            precursor.assertReleased()
                            TestActiveOwnerDeleteQueueFixtureV1(precursor, raw.queue, record, before, after, verified).use { queue ->
                                val alias = if (retainedAllAlias) {
                                    val primary = queue.domainImage().filterKeys { it in setOf("complaint_journal_publications", "installation_deletion_receipts") }
                                    val nativeBefore = precursor.native.counts()
                                    val historical = raw.queue.protocolHistoricalAllObject()
                                    assertSame(record, queue.record)
                                    raw.queue.selectHistorical(historical); queue.expectAppliedObjects(historical.stored)
                                    val consumed = queue.poll()
                                    assertEquals(1, consumed.primaryAcknowledged); assertEquals(0, consumed.dlqAcknowledged)
                                    queue.assertReleased(); queue.assertNoAuthority(); queue.assertExpectedAppliedObjects(); queue.assertOnlyAuthorizedReportsErased()
                                    assertEquals(primary, queue.domainImage().filterKeys { it in primary.keys }, "Alias consumption does not complete or rewrite the original P/N.")
                                    assertEquals("VERIFIED", precursor.publication()["state"]); assertEquals("AUTHORIZED_DELETE", queue.receipt()["state"])
                                    assertEquals(nativeBefore, precursor.native.counts(), "Protocol-history bytes and B consumption never issue another A AUTH/PUT.")
                                    raw.queue.selectOriginal()
                                    historical
                                } else null
                                if (applied) {
                                    if (alias != null) queue.expectAppliedObjects(alias.stored, record.stored)
                                    queue.poll(); queue.assertReleased(); queue.assertNoAuthority(); queue.assertExpectedAppliedObjects()
                                }
                                assertEquals((if (applied) 1L else 0L) + (if (alias != null) 1L else 0L), queue.count("complaint_deletion_journal_applied"))
                                TestActiveRecurrentFixtureV1(queue, raw, alias).use(action)
                            }
                        }
                    } finally { creators.asReversed().forEach { it.close() } }
                }
            }
        }
    }
}

internal class TestActiveRecurrentFixtureV1(val queue: TestActiveOwnerDeleteQueueFixtureV1,
    val raw: TestActiveRecurrentRawFixtureV1, val historicalAll: TestActiveQueueHistoricalAllObjectV1? = null) : AutoCloseable {
    val precursor = queue.precursor
    val first = precursor.first
    val registration = precursor.registration
    val assembly = precursor.assembly
    val process = registration.process
    val runtime = precursor.runtime
    val observer = precursor.observer
    val scope = precursor.scope
    val record = queue.record
    val probe = TestActiveRecurrentProbeJdbcV1(this)
    val applyObservations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    val applyCalls = mutableListOf<TestRegisteredInitialDeletionSqlCallV1>()
    var beforeApply: (TestRegisteredInitialDeletionSqlCallV1) -> Unit = {}
    var afterApply: (TestRegisteredInitialDeletionSqlCallV1) -> Unit = {}
    val nativeSessions = CopyOnWriteArrayList<PersistenceEpochRotationSession>()
    var beforeNativeSample: (PersistenceEpochRotationSession) -> Unit = {}
    var original: TestActiveRecurrentV1? = null
        private set
    private val executor = runtime.pools.catalogCoordinator.testActiveRecurrent
    private val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
    private val actual = field.get(executor) as JdbcTemplate
    private val beforeRead = first.p.f.http.beforeRead
    private val beforeNative = first.native.onNanoSample
    private val beforeDeletion = precursor.deletion.before
    private val afterDeletion = precursor.deletion.after

    init {
        val author = first.p.f.rows.evidence.process
        assertNotSame(process.pools, author.pools)
        assertNotSame(process.activeRecurrent, author.activeRecurrent)
        assertEquals(checkNotNull(process.activeRecurrent).inventory(), checkNotNull(author.activeRecurrent).inventory())
        assertArrayEquals(process.canonicalBytes(), author.canonicalBytes())
        assertSame(actual.dataSource, probe.dataSource); field.set(executor, probe)
        raw.attach(this)
        // Reuse A's actual registered deletion template and passive holder probe. Its enclosing
        // queue fixture is idle here; do not run that fixture's queue-original assertion hooks.
        precursor.deletion.before = { call -> observeApply(call); beforeApply(call) }
        precursor.deletion.after = { call -> afterApply(call) }
        first.p.f.http.beforeRead = { beforeRead(); assertSqlReleased() }
        first.native.onNanoSample = {
            beforeNative?.invoke()
            // Passive observation of the actual new NONPOOLED capture; no clock advances here.
            if (Thread.currentThread() === probe.caller) currentNative()?.let { session ->
                if (!nativeSessions.contains(session)) nativeSessions.add(session)
                assertNotSame(first.observedNative.get(), session)
                beforeNativeSample(session)
            }
        }
    }
    private fun currentNative(): PersistenceEpochRotationSession? {
        val active = (ownedCutField(first.resource, "active") as AtomicReference<*>).get() ?: return null
        return ownedCutField(active, "session") as? PersistenceEpochRotationSession
    }
    fun begin(): TestActiveRecurrentV1 {
        assertSqlReleased(); probe.reset(); applyObservations.clear(); applyCalls.clear()
        return TestActiveRecurrentV1.withHttpFixture(registration, assembly, first.p.f.http::readClient, SignedActivationObservation.WALL_CLOCK)
            .also { original = it; probe.original = it }
    }
    fun checkpoint(selected: TestActiveRecurrentV1 = begin()): TestActiveRecurrentV1.Result =
        selected.checkpoint(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
    fun apply(result: TestActiveRecurrentV1.RecoveryRequired): TestActiveRecurrentV1.Result =
        result.apply(precursor.deletionOwner, precursor.deletion, precursor.audit)
    private fun observeApply(call: TestRegisteredInitialDeletionSqlCallV1) {
        val selected = ownedCutField(call.phase, "testRecurrentApply") as TestActiveRecurrentApplyV1
        assertSame(original, selected.original)
        assertSame(selected, ownedCutField(checkNotNull(original), "applying"))
        assertSame(checkNotNull(original).budget, selected.budget)
        assertEquals(selected.path, call.path)
        assertNull(ownedCutField(call.phase, "testRecurrent"))
        assertNull(ownedCutField(call.phase, "testActiveQueue")); assertNull(ownedCutField(call.phase, "testOrdinaryDrain"))
        val connection = (TransactionSynchronizationManager.getResource(checkNotNull(precursor.deletion.dataSource)) as ConnectionHolder).connection
        assertTrue(first.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
        assertTrue(first.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
        assertFalse(first.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
        val observation = precursor.deletion.observations.getValue(call.phase)
        assertSame(observation.lease, ownedPoolLease(connection))
        assertSame(observation, applyObservations.getOrPut(call.phase) { observation })
        raw.assertDisposed() // Every native exchange/key/client from capture has really retired.
        applyCalls.add(call)
    }
    fun passNumber(): Int = original?.let { (ownedCutField(it, "scan") as? TestActiveRecurrentScanV1)?.passNumber } ?: 0
    fun control() = observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", scope)
    fun document() = TestActiveRecurrentCheckpointDocumentV1.parse(control().getValue("checkpoint_bytes") as ByteArray)
    fun scans() = observer.queryForList("SELECT * FROM complaint_journal_scan_runs WHERE data_scope_id = ? ORDER BY pass", scope)
    fun entries() = observer.queryForList("SELECT * FROM complaint_journal_scan_entries WHERE data_scope_id = ? ORDER BY pass, object_key, object_version", scope)
    fun intents() = observer.queryForList("SELECT * FROM complaint_test_active_recurrent_seal_intents WHERE data_scope_id = ? ORDER BY rotation_sequence", scope)
    fun history() = observer.queryForList("SELECT * FROM complaint_test_active_checkpoint_history WHERE data_scope_id = ? ORDER BY ordinal", scope)
    fun counters() = precursor.counters()
    fun immutableImage() = listOf("complaint_test_active_seal_intents", "complaint_test_active_recurrent_seal_intents",
        "complaint_test_active_checkpoint_history").associateWith { table -> observer.queryForList(
            "SELECT to_jsonb(t)::text FROM $table t WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text", String::class.java, scope) }
    fun domainImage() = queue.domainImage().filterKeys { it !in setOf("complaint_journal_scan_runs", "complaint_journal_scan_entries") }
    fun image() = immutableImage() + domainImage() + mapOf(
        "control" to observer.queryForList("SELECT to_jsonb(c)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, scope),
        "scans" to observer.queryForList("SELECT to_jsonb(s)::text FROM complaint_journal_scan_runs s WHERE data_scope_id = ? ORDER BY pass", String::class.java, scope),
        "entries" to observer.queryForList("SELECT to_jsonb(e)::text FROM complaint_journal_scan_entries e WHERE data_scope_id = ? ORDER BY pass,object_key,object_version", String::class.java, scope))

    fun assertCharge(before: Map<ComplaintCapacityCounter, DeleteAllCounter>, charge: ComplaintCapacityVector) {
        val after = counters()
        before.forEach { (counter, value) ->
            val current = after.getValue(counter)
            assertEquals(value.actual + charge[counter], current.actual, counter.name)
            assertEquals(value.free - charge[counter], current.free, counter.name)
            assertEquals(value.recovery, current.recovery); assertEquals(value.test, current.test)
            assertEquals(value.preserved, current.preserved, "No reserve/limit/configuration change.")
        }
    }
    fun assertSqlReleased() {
        requireConnectionFree(); assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
        (probe.observations.values + precursor.deletion.observations.values).forEach { assertTrue(it.lease.completion.quiescent()) }
        probe.assertNoLostAssertions(); precursor.deletion.assertNoLostAssertions(); raw.assertNoLostAssertions()
    }
    fun assertReleased() {
        assertSqlReleased(); raw.assertDisposed()
        assertEquals(0L, process.publicationLanes.activeOwners().totalOwners)
        nativeSessions.forEach {
            val entry = poolTestField<PersistencePhysicalEntry>(it, "entry")
            awaitLifecycleFact { entry.jdbc.terminalCompletion().reclaimed() }
            assertTrue(entry.jdbc.terminalCompletion().reclaimed())
        }
    }
    fun assertSuccessful() {
        assertReleased()
        probe.observations.keys.forEach {
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, it.databaseOutcome())
            assertTrue(it.testActiveRecurrentCleanupProven(checkNotNull(probe.original)))
        }
        applyObservations.forEach { (phase, value) ->
            val selected = ownedCutField(phase, "testRecurrentApply") as TestActiveRecurrentApplyV1
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.testActiveRecurrentApplyCleanupProven(selected)); assertTrue(value.lease.completion.quiescent())
        }
        assertTrue(scans().isEmpty() && entries().isEmpty())
        assertNull(control()["lease_owner"]); assertNull(control()["lease_expires_at"])
        assertEquals("SUCCESS", control()["checkpoint_result"])
        assertEquals(0L, queue.count("complaint_test_terminal_intents"))
    }
    override fun close() {
        beforeNativeSample = {}; probe.before = {}; probe.after = {}; beforeApply = {}; afterApply = {}; raw.resetFaults()
        // A deliberately unfinished RecoveryRequired is failed/closed, never silently checkpointed.
        original?.let { runCatching(it::close) }
        precursor.deletion.before = beforeDeletion; precursor.deletion.after = afterDeletion
        first.native.onNanoSample = beforeNative
        first.p.f.http.beforeRead = beforeRead
        assertSame(probe, field.get(executor)); field.set(executor, actual)
        raw.detach(this); probe.assertNoLostAssertions()
        requireConnectionFree()
        // Disposable fixture teardown only, AFTER all result/payment/cleanup assertions. Not product
        // refund or recovery. Remove the cyclic source/history chain newest-first, then restore guards.
        checkNotNull(observer.dataSource).connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM complaint_journal_scan_entries WHERE data_scope_id=?").use { it.setObject(1, scope); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM complaint_journal_scan_runs WHERE data_scope_id=?").use { it.setObject(1, scope); it.executeUpdate() }
                connection.createStatement().use {
                    it.execute("ALTER TABLE complaint_test_active_checkpoint_history DISABLE TRIGGER complaint_test_active_history_immutable")
                    it.execute("ALTER TABLE complaint_test_active_recurrent_seal_intents DISABLE TRIGGER complaint_test_active_recurrent_seal_immutable")
                }
                for (ordinal in 14 downTo 1) {
                    connection.prepareStatement("DELETE FROM complaint_test_active_checkpoint_history WHERE data_scope_id=? AND ordinal=?").use {
                        it.setObject(1, scope); it.setInt(2, ordinal); it.executeUpdate()
                    }
                    connection.prepareStatement("DELETE FROM complaint_test_active_recurrent_seal_intents WHERE data_scope_id=? AND rotation_sequence=?").use {
                        it.setObject(1, scope); it.setInt(2, ordinal); it.executeUpdate()
                    }
                }
                connection.createStatement().use {
                    it.execute("ALTER TABLE complaint_test_active_checkpoint_history ENABLE TRIGGER complaint_test_active_history_immutable")
                    it.execute("ALTER TABLE complaint_test_active_recurrent_seal_intents ENABLE TRIGGER complaint_test_active_recurrent_seal_immutable")
                }
                connection.commit()
            } finally { connection.rollback() }
        }
    }
}

/** Actual JDBC/results/COMMIT with passive holder observations. Negative cuts never return fake rows. */
internal class TestActiveRecurrentProbeJdbcV1(private val f: TestActiveRecurrentFixtureV1) : JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource) {
    val caller = Thread.currentThread()
    var original: TestActiveRecurrentV1? = null
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    val calls = mutableListOf<TestActiveRecurrentSqlCallV1>()
    var before: (TestActiveRecurrentSqlCallV1) -> Unit = {}
    var after: (TestActiveRecurrentSqlCallV1) -> Unit = {}
    private var observing = false
    private val assertion = AtomicReference<AssertionError?>()
    init { exceptionTranslator = SQLExceptionSubclassTranslator() }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = observed(sql, emptyArray()) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql, args) { super.query(sql, mapper, *args) }
    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? = observed(sql, args) { super.query(sql, extractor, *args) }
    override fun update(sql: String, vararg args: Any?): Int = observed(sql, args) { super.update(sql, *args) }
    private fun <T> observed(sql: String, args: Array<out Any?>, action: () -> T): T {
        if (observing) return action()
        observing = true
        try {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val selected = checkNotNull(original)
            assertEquals(PersistencePhasePath.COMPLAINT_TEST_ACTIVE_RECURRENT, poolTestField<PersistencePhasePath>(phase, "path"))
            assertEquals(sql.count { it == '?' }, args.size)
            val connection = (TransactionSynchronizationManager.getResource(checkNotNull(dataSource)) as ConnectionHolder).connection
            assertEquals(setOf(dataSource), TransactionSynchronizationManager.getResourceMap().keys)
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
            assertTrue(f.first.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
            assertFalse(f.first.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
            val lease = ownedPoolLease(connection)
            val observation = observations.getOrPut(phase) {
                val identity = connection.createStatement().use { it.executeQuery("SELECT pg_backend_pid(), txid_current()").use { rows ->
                    assertTrue(rows.next()); (rows.getInt(1) to rows.getLong(2)).also { assertFalse(rows.next()) }
                } }
                StepUpPhaseObservation(phase, lease, identity)
            }
            assertSame(lease, observation.lease); assertFalse(lease.completion.quiescent())
            val call = TestActiveRecurrentSqlCallV1(phase, selected.step, sql)
            calls.add(call); before(call)
            return action().also { after(call) }
        } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }
        finally { observing = false }
    }
    fun assertNoLostAssertions() { assertion.get()?.let { throw it } }
    fun reset() { assertNoLostAssertions(); observations.values.forEach { assertTrue(it.lease.completion.quiescent()) }; observations.clear(); calls.clear(); original = null }
}
internal class TestActiveRecurrentSqlCallV1(val phase: PersistencePhaseContext, val step: TestActiveRecurrentStepV1, val sql: String)
