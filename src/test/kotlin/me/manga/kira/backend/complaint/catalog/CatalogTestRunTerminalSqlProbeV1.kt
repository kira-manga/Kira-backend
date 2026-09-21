package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplySql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllVerificationSql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalActiveHistorySqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalPreflightSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalProjectionSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointDeletionFixtureV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialDeletionSqlCallV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteAllContinuationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteAllExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteContinuationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPreparedOwnerDeleteAllV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingSqlV1
import me.manga.kira.backend.complaint.journal.JournalPublisherObject
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import software.amazon.awssdk.http.SdkHttpClient
import java.sql.Connection
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/** Two distinct exact-source JdbcTemplates. Observes real holders/SQL; never returns fabricated rows or outcomes. */
internal class CatalogTestRunTerminalSqlProbeV1(private val f: TestRunPurgeFixtureV1,
    private val runtime: VersionBoundPersistenceConnectedFixture) : AutoCloseable {
    var before: (Call) -> Unit = {}
    var after: (Call) -> Unit = {}
    val calls = arrayListOf<Call>()
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    private val owners = linkedMapOf<PersistencePhaseContext, CatalogTestRunTerminalV1>()
    private val assertion = AtomicReference<AssertionError?>()
    private val coordinator = runtime.pools.catalogCoordinator
    private val templates = listOf(coordinator.testRunTerminalCatalog to false, coordinator.testRunTerminalCatalogPreflight to true).map { (executor, preflight) ->
        val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
        val previous = field.get(executor) as JdbcTemplate
        val probe = Probe(preflight).apply { fetchSize = previous.fetchSize }
        assertSame(previous.dataSource, probe.dataSource)
        field.set(executor, probe)
        Triple(executor, field, previous)
    }

    private inner class Probe(private val preflight: Boolean) : JdbcTemplate(coordinator.dataSource) {
        private var dispatching = false
        init { exceptionTranslator = SQLExceptionSubclassTranslator() }
        // RowMapper overloads can delegate virtually to an extractor overload. Observe the outer
        // dispatch once without hiding standalone history extractors or changing any JDBC result.
        private fun <T> once(sql: String, args: Array<out Any?>, action: () -> T): T {
            if (dispatching) return action()
            dispatching = true
            return try { observed(sql, args, preflight, action) } finally { dispatching = false }
        }
        override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = once(sql, emptyArray()) { super.query(sql, mapper) }
        override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = once(sql, args) { super.query(sql, mapper, *args) }
        override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>): T? = once(sql, emptyArray()) { super.query(sql, extractor) }
        override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? =
            once(sql, args) { super.query(sql, extractor, *args) }
        override fun update(sql: String, vararg args: Any?): Int = once(sql, args) { super.update(sql, *args) }
    }

    private fun <T> observed(sql: String, args: Array<out Any?>, preflight: Boolean, action: () -> T): T = try {
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val owner = ownedCutField(phase, "testRunTerminalCatalog") as CatalogTestRunTerminalV1
        val path = ownedCutField(phase, "path") as PersistencePhasePath
        assertSame(coordinator, owner.process.pools.catalogCoordinator)
        assertEquals(preflight, path === PersistencePhasePath.COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PREFLIGHT)
        assertTrue(preflight || path.catalogTestRunTerminal)
        assertEquals(sql.count { it == '?' }, args.size)
        val source = coordinator.dataSource
        val connection = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
        assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
        assertTrue(f.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
        val controlOnly = path in setOf(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_ACQUIRE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_RELEASE)
        assertEquals(!controlOnly, f.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
        assertFalse(f.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"), "E catalog/preflight is shared E; D quiescence is a distinct exclusive path.")
        val lease = ownedPoolLease(connection)
        val observation = observations.getOrPut(phase) {
            val identity = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_backend_pid(), txid_current(), current_setting('statement_timeout'), current_setting('transaction_timeout')").use { row ->
                    assertTrue(row.next()); for (column in 3..4) assertTrue(timeoutMillis(row.getString(column)) in 1..2_000)
                    row.getInt(1) to row.getLong(2)
                }
            }
            owners[phase] = owner
            StepUpPhaseObservation(phase, lease, identity)
        }
        assertSame(observation.lease, lease); assertSame(owners.getValue(phase), owner); assertFalse(lease.completion.quiescent())
        val call = Call(phase, path, sql, args.map { if (it is ByteArray) Bytes(it.size, Sha256.hex(it)) else it })
        calls.add(call); before(call); action().also { after(call) }
    } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }

    fun assertReleased(requireCommitted: Boolean = true) {
        requireConnectionFree(); assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty()); assertEquals(0, coordinator.activeSnapshotOwners())
        observations.forEach { (phase, value) ->
            assertTrue(value.lease.completion.quiescent()); assertTrue(phase.testRunTerminalCatalogResourcesRetired(owners.getValue(phase)))
            if (requireCommitted) {
                assertTrue(phase.testRunTerminalCatalogCleanupProven(owners.getValue(phase)))
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            }
        }
        assertion.get()?.let { throw it }
    }

    /** New history reads never introduce a lock class, or smuggle a run read into lease-only work. */
    fun assertHistoryOrder(active: Boolean) {
        val materialized = setOf(CatalogTestRunTerminalActiveHistorySqlV1.initialSeal, CatalogTestRunTerminalActiveHistorySqlV1.queue)
        val physical = setOf(CatalogTestRunTerminalActiveHistorySqlV1.sealIdentity, CatalogTestRunTerminalActiveHistorySqlV1.queueIdentity)
        calls.groupBy { it.phase }.values.forEach { phaseCalls ->
            val sql = phaseCalls.map { it.sql }
            val lastControl = sql.indexOfLast { it == CatalogTestRunTerminalSqlV1.lockControl }
            val controlOnly = phaseCalls.first().path in setOf(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_ACQUIRE,
                PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_RELEASE)
            if (controlOnly) {
                // The unchanged control predicate already joins the run's identity. No added
                // full-run/history materializer or run lock is permitted in these two phases.
                assertTrue(sql.none { it in materialized || it == CatalogTestRunTerminalProjectionSqlV1.lockRun ||
                    it == CatalogTestRunTerminalProjectionSqlV1.readRun })
                physical.forEach { assertEquals(2, sql.count { candidate -> it == candidate }) }
            } else {
                val run = sql.indexOfFirst { it == CatalogTestRunTerminalProjectionSqlV1.lockRun || it == CatalogTestRunTerminalProjectionSqlV1.readRun }
                assertTrue(run > lastControl)
                materialized.forEach { statement ->
                    val indexes = sql.withIndex().filter { it.value == statement }.map { it.index }
                    assertTrue(indexes.size >= 2 && indexes.all { it > run })
                }
                if (phaseCalls.first().path === PersistencePhasePath.COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PREFLIGHT) {
                    assertTrue((if (active) CatalogTestRunTerminalPreflightSqlV1.controlWithActiveHistory else CatalogTestRunTerminalPreflightSqlV1.control) in sql)
                }
            }
            phaseCalls.filter { it.sql in materialized || it.sql in physical }.forEach {
                assertFalse(it.sql.contains("FOR UPDATE")); assertFalse(it.sql.contains("FOR SHARE"))
                assertTrue(sql.indexOf(it.sql) > lastControl)
                assertEquals(when (it.sql) {
                    CatalogTestRunTerminalActiveHistorySqlV1.initialSeal -> 14
                    CatalogTestRunTerminalActiveHistorySqlV1.queue -> 13
                    CatalogTestRunTerminalActiveHistorySqlV1.sealIdentity -> 2
                    else -> 3
                }, it.arguments.size)
            }
        }
    }
    override fun close() {
        before = {}; after = {}
        try { assertReleased(requireCommitted = false) }
        finally { templates.forEach { (executor, field, previous) -> field.set(executor, previous) } }
    }
    class Call(val phase: PersistencePhaseContext, val path: PersistencePhasePath, val sql: String, val arguments: List<Any?>)
    data class Bytes(val size: Int, val sha256: String)
    companion object {
        private fun timeoutMillis(value: String): Long = when {
            value.endsWith("ms") -> value.removeSuffix("ms").toLong()
            value.endsWith("s") -> value.removeSuffix("s").toLong() * 1_000
            else -> value.toLong()
        }
    }
}

/**
 * The legacy registration probe has a closed SQL-name classifier. Observe A's actual two-phase
 * sealer through its exact retained template instead; no SQL/result/owner is manufactured here.
 */
internal class CatalogTerminalHistorySealingProbeV1(private val f: TestRunPurgeFixtureV1) :
    JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource), AutoCloseable {
    private val executor = f.runtime.pools.catalogCoordinator.testRunSealing
    private val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
    private val previous = field.get(executor) as JdbcTemplate
    private var original: TestRunSealingV1? = null
    private var dispatching = false
    private val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    private val assertion = AtomicReference<AssertionError?>()

    init { requireConnectionFree(); exceptionTranslator = SQLExceptionSubclassTranslator(); assertSame(previous.dataSource, dataSource); field.set(executor, this) }
    fun begin(): TestRunSealingV1 = TestRunSealingV1.begin(f.registration).also { check(original == null); original = it }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = observed(sql, emptyArray()) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql, args) { super.query(sql, mapper, *args) }
    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? = observed(sql, args) { super.query(sql, extractor, *args) }
    override fun update(sql: String, vararg args: Any?): Int = observed(sql, args) { super.update(sql, *args) }
    private fun <T> observed(sql: String, args: Array<out Any?>, action: () -> T): T {
        if (dispatching) return action()
        dispatching = true
        try {
            val phase = checkNotNull(PersistencePhaseOwnership.current()); val owner = checkNotNull(original)
            val path = ownedCutField(phase, "path") as PersistencePhasePath
            assertSame(owner, ownedCutField(phase, "testRunSealer")); assertSame(f.registration, owner.registration)
            assertTrue(path.testRunSealing); assertEquals(sql.count { it == '?' }, args.size)
            val source = checkNotNull(dataSource)
            val connection = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
            assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
            assertTrue(f.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
            assertEquals(path === PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT, f.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
            assertFalse(f.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
            val lease = ownedPoolLease(connection)
            val observed = observations.getOrPut(phase) {
                val identity = connection.createStatement().use { statement -> statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { row ->
                    assertTrue(row.next()); row.getInt(1) to row.getLong(2)
                } }
                StepUpPhaseObservation(phase, lease, identity)
            }
            assertSame(lease, observed.lease); assertFalse(lease.completion.quiescent())
            return action()
        } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }
        finally { dispatching = false }
    }
    fun assertReleased() {
        requireConnectionFree(); assertNull(PersistencePhaseOwnership.current()); assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        observations.forEach { (phase, value) ->
            assertTrue(value.lease.completion.quiescent()); assertTrue(phase.testRunSealingCleanupProven(checkNotNull(original)))
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        }
        assertEquals(0, f.runtime.pools.catalogCoordinator.activeSnapshotOwners()); assertion.get()?.let { throw it }
    }
    override fun close() { try { assertReleased() } finally { assertSame(this, field.get(executor)); field.set(executor, previous) } }
}

/**
 * Scoped observer for genuine A -> D and explicit registered primaries. Registration pins A's deletion owner/template;
 * unlike the legacy empty helper, this cannot substitute a new pair. Only passive callbacks on
 * that exact JdbcTemplate change. B's queue-only callbacks are restored, not reused for continuation SQL.
 * Reflection observes actual originals/holders and installs the coordinator's passive template;
 * it never writes completion, phase authority, native proof or an admitted input. Explicit negative
 * callbacks may change database comparison facts on the already-held original template only.
 */
internal class CatalogTerminalHistoryDrainProbeV1(private val f: TestRunPurgeFixtureV1,
    private val a: TestRegisteredInitialCheckpointDeletionFixtureV1, private val preparedPrimary: Boolean = false) : AutoCloseable {
    private val coordinator = TestOrdinaryDrainSqlProbeV1(f.p, f.runtime)
    private val beforeDeletion = a.deletion.before
    private val afterDeletion = a.deletion.after
    private val calls = mutableListOf<TestRegisteredInitialDeletionSqlCallV1>()
    private val returnedCalls = mutableListOf<TestRegisteredInitialDeletionSqlCallV1>()
    private val owners = linkedMapOf<PersistencePhaseContext, TestRunOwnerDeleteContinuationV1>()
    private val refusedPrimaries = mutableSetOf<PersistencePhaseContext>()
    var afterPrimary: (TestRegisteredInitialDeletionSqlCallV1, JdbcTemplate) -> Unit = { _, _ -> }
    private val allOwners = linkedMapOf<PersistencePhaseContext, TestRunOwnerDeleteAllContinuationV1>()
    private val inventoryOwners = linkedMapOf<PersistencePhaseContext, TestRunOrdinaryDrainV1>()
    private val inventoryLocators = linkedMapOf<PersistencePhaseContext, Pair<String, String>>()
    private val refusedAll = linkedMapOf<TestRunOwnerDeleteAllContinuationV1, PersistencePhasePath>()
    private val preparedAll = mutableSetOf<TestRunOwnerDeleteAllContinuationV1>()
    private var pendingApplyFault: ((JdbcTemplate) -> Unit)? = null
    private val mutation = Regex("\\b(?:UPDATE|INSERT|DELETE)\\s+(?:INTO\\s+|FROM\\s+)?(?:complaint_|installation_|app_|audit_)")
    private val assertion = AtomicReference<AssertionError?>()
    private var original: TestRunOrdinaryDrainV1? = null
    private var allOriginal: TestRunOwnerDeleteAllContinuationV1? = null
    private val templates = listOf<Any>(f.runtime.pools.catalogCoordinator.testOrdinaryDrain,
        f.runtime.pools.catalogCoordinator.testOrdinarySeal).map { executor ->
        val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
        Triple(executor, field, field.get(executor) as JdbcTemplate)
    }
    init {
        requireConnectionFree(); a.assertReleased(); assertSame(a.runtime, f.runtime); assertSame(a.registration, f.registration)
        templates.forEach { (executor, field, previous) -> assertSame(coordinator.dataSource, previous.dataSource); field.set(executor, coordinator) }
        a.deletion.before = ::observeDeletion
        a.deletion.after = { call ->
            returnedCalls.add(call) // The actual JDBC mapper/update returned, never a substituted result.
            if (call.phase in owners) {
                val source = checkNotNull(a.deletion.dataSource)
                val holder = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
                afterPrimary(call, a.deletion)
                assertSame(call.phase, PersistencePhaseOwnership.current())
                assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
                assertSame(holder, (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection)
            }
        }
    }

    /** Internal registered privacy caller, not an authenticated HTTP request or a new primary. */
    fun replayAll() {
        val start = calls.size
        val replay = freshAllReplay()
        assertEquals(204, replay.complete().responseStatus) // Internal completion metadata only.
        assertReleased()
        assertEquals(listOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY),
            calls.drop(start).map { it.phase to it.path }.distinct().map { it.second })
        assertNoAllReplayMutation(start); assertConsumed(replay)
    }

    /** An actual pending primary, not a replay: optional own native VERIFY, then one N/P/E/U
     * completion. Raw audit insertion uses this same holder; cases compare its row and xmin. */
    fun completePendingAll(prepared: Boolean, s3: () -> SdkHttpClient, kms: () -> SdkHttpClient) {
        val start = calls.size
        val owner = freshAll(s3, kms, prepared)
        assertEquals(204, owner.complete().responseStatus)
        assertReleased()
        val expected = listOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD) +
            (if (prepared) listOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY) else emptyList()) +
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY
        assertEquals(expected, calls.drop(start).map { it.phase to it.path }.distinct().map { it.second })
        val sql = OwnerDeleteAllApplySql.test(a.process.consumers.journalConfiguration.scope)
        val verified = OwnerDeleteAllVerificationSql.test(a.process.consumers.journalConfiguration.scope).RECORD_VERIFIED
        val counter = """
            UPDATE complaint_capacity_counters
            SET free_units = ?, actual_units = ?, recovery_reserved_units = ?, updated_at = now()
            WHERE name = ? AND free_units = ? AND actual_units = ? AND recovery_reserved_units = ? AND test_reserved_units = ?
        """.trimIndent()
        val writes = calls.drop(start).filter { mutation.containsMatchIn(it.sql) }
        assertEquals((if (prepared) listOf(verified) else emptyList()) + List(3) { counter } +
            listOf(sql.RECORD_PROGRESS, sql.COMPLETE_RECEIPT, sql.MARK_APPLIED, sql.INSERT_APPLIED), writes.map { it.sql },
            "Only own VERIFY, three literal changed counters and exact original L/N/P/E may mutate; never domain, refund, new Y or queue SQL.")
        writes.forEach { assertEquals(if (it.sql == verified) PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY
            else PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY, it.path) }
        assertConsumed(owner)
    }

    /** Negative inputs only. APPLY faults execute through the already-held original template,
     * after a proven released/COMMITTED RELOAD. They roll back with that APPLY, not a new owner. */
    fun refusePendingAll(expectedRead: String, s3: () -> SdkHttpClient, kms: () -> SdkHttpClient,
        atApply: ((JdbcTemplate) -> Unit)? = null) {
        val start = calls.size; val returnedStart = returnedCalls.size
        val failedPath = if (atApply == null) PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD else PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY
        val owner = freshAll(s3, kms).also { refusedAll[it] = failedPath }
        var injected = 0
        check(pendingApplyFault == null)
        if (atApply != null) pendingApplyFault = { jdbc -> injected++; atApply(jdbc) }
        try { assertThrows<TestRunOwnerDeleteAllExceptionV1> { owner.complete() } }
        finally { pendingApplyFault = null }
        assertEquals(if (atApply == null) 0 else 1, injected)
        val selected = calls.drop(start)
        assertEquals(listOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD) +
            (if (atApply == null) emptyList() else listOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY)),
            selected.map { it.phase to it.path }.distinct().map { it.second })
        assertTrue(selected.any { it.path === failedPath && it.sql == expectedRead }, "The actual failed phase must reach the intended retained lookup/comparison.")
        assertEquals(selected, returnedCalls.drop(returnedStart),
            "Every actual SQL dispatch returned normally; an SQL/mapper error is not the refusal oracle.")
        assertTrue(selected.none { mutation.containsMatchIn(it.sql) }, "The refused original cannot complete, spend or repair anything.")
        assertReleased(); assertConsumed(owner)
    }

    /** A distinct failed original: missing credential is read under the normal N/P/L/counter/domain
     * order. Only its RELOAD rolls back; this never weakens successful-original COMMITTED checks. */
    fun refuseMissingAllVerifier() {
        val start = calls.size
        val replay = freshAllReplay().also { refusedAll[it] = PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD }
        assertThrows<TestRunOwnerDeleteAllExceptionV1> { replay.complete() }
        assertReleased()
        assertEquals(listOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD),
            calls.drop(start).map { it.phase to it.path }.distinct().map { it.second })
        assertTrue(calls.drop(start).any { it.sql == OwnerDeleteAllApplySql.test(a.process.consumers.journalConfiguration.scope).LOCK_CREDENTIAL },
            "Refusal must reach the actual retained credential lookup, not stop at an unrelated earlier check.")
        assertNoAllReplayMutation(start); assertConsumed(replay)
    }

    private fun freshAllReplay(): TestRunOwnerDeleteAllContinuationV1 = freshAll(
        { error("Completed registered ALL replay must not open S3 or republish.") },
        { error("Completed registered ALL replay must not open KMS or re-encrypt.") })

    private fun freshAll(s3: () -> SdkHttpClient, kms: () -> SdkHttpClient, prepared: Boolean = false): TestRunOwnerDeleteAllContinuationV1 {
        requireConnectionFree(); check(original == null)
        assertEquals(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, a.family)
        return TestRunPreparedOwnerDeleteAllV1.withHttpFixture(a.registration, a.deletionOwner, a.deletion, a.audit,
            a.actor.id, a.key, AwsJournalKmsFixture.CREDENTIALS, s3, kms, Clock.systemUTC(), System::nanoTime).also {
            allOriginal = it
            if (prepared) preparedAll.add(it)
        }
    }

    private fun assertNoAllReplayMutation(start: Int) {
        assertTrue(calls.drop(start).none { mutation.containsMatchIn(it.sql) },
            "Retained completed replay performs comparison SQL only.")
    }
    private fun assertConsumed(replay: TestRunOwnerDeleteAllContinuationV1) {
        val count = calls.size
        assertThrows<TestRunOwnerDeleteAllExceptionV1> { replay.complete() }
        assertEquals(count, calls.size, "A new owner may reload retained facts; the consumed original cannot be reset.")
        assertReleased()
    }

    fun begin(s3: () -> SdkHttpClient, kms: () -> SdkHttpClient,
        primaryS3: (() -> SdkHttpClient)? = null, primaryKms: (() -> SdkHttpClient)? = null): TestRunOrdinaryDrainV1 {
        requireConnectionFree(); check(original == null)
        check((primaryS3 == null) == (primaryKms == null))
        // Test raw transports split only by the actual retained D step. Both readers see the
        // same original object; neither supplier issues work, a readback or phase authority.
        val selectedS3 = { if (checkNotNull(original).step === TestOrdinaryDrainStepV1.PRIMARIES && primaryS3 != null) primaryS3() else s3() }
        val selectedKms = { if (checkNotNull(original).step === TestOrdinaryDrainStepV1.PRIMARIES && primaryKms != null) primaryKms() else kms() }
        return TestRunOrdinaryDrainV1.withHttpFixture(a.registration, a.deletionOwner, a.deletion, a.audit,
            Clock.systemUTC(), System::nanoTime, selectedS3, selectedKms).also {
            original = it; coordinator.original = it
            assertSame(a.deletionOwner, ownedCutField(it, "deletionOwner")); assertSame(a.deletion, ownedCutField(it, "deletionJdbc"))
        }
    }
    private fun observeDeletion(call: TestRegisteredInitialDeletionSqlCallV1) = try {
        val inventory = ownedCutField(call.phase, "testOrdinaryDrain") as TestRunOrdinaryDrainV1?
        val all = ownedCutField(call.phase, "testRunOwnerDeleteAll") as TestRunOwnerDeleteAllContinuationV1?
        val primary = ownedCutField(call.phase, "testRunOwnerDelete") as TestRunOwnerDeleteContinuationV1?
        assertEquals(1, listOfNotNull(inventory, all, primary).size, "Exactly one actual original owns the deletion holder.")
        assertNull(ownedCutField(call.phase, "testActiveQueue"))
        if (inventory != null) {
            assertSame(checkNotNull(original), inventory); assertEquals(TestOrdinaryDrainStepV1.RECOVERY_APPLY, inventory.step)
            assertEquals(if (a.family === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY else PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY, call.path)
            assertSame(a.registration, inventory.registration)
            assertSame(a.deletionOwner, ownedCutField(inventory, "deletionOwner")); assertSame(a.deletion, ownedCutField(inventory, "deletionJdbc"))
            val entry = ownedCutField(inventory, "currentEntry") as TestOrdinaryDrainRowsV1.Entry
            assertEquals(a.family.name, entry.kind); assertEquals("PENDING", entry.replay)
            assertEquals(entry.locator, inventoryLocators.getOrPut(call.phase) { entry.locator })
            assertSame(inventory, inventoryOwners.getOrPut(call.phase) { inventory })
            if (mutation.containsMatchIn(call.sql)) assertEquals(TestOrdinaryDrainSqlV1.markApplied, call.sql,
                "Already-applied inventory versions may mark only the two paid scan rows, never E/domain/U again.")
        } else if (all != null) {
            assertSame(checkNotNull(allOriginal), all); assertNull(original)
            assertNull(ownedCutField(all, "drainBy")); assertSame(a.registration, ownedCutField(all, "registration"))
            assertSame(a.deletionOwner, ownedCutField(all, "ownership")); assertSame(a.deletion, ownedCutField(all, "jdbc"))
            assertSame(checkNotNull(allOriginal).budget, all.budget)
            assertTrue(call.path in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY) ||
                all in preparedAll && call.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY)
            assertNull(ownedCutField(call.phase, "testRunOwnerDelete"))
            assertSame(all, allOwners.getOrPut(call.phase) { all })
        } else {
            val drain = checkNotNull(original)
            assertEquals(TestOrdinaryDrainStepV1.PRIMARIES, drain.step)
            assertTrue(call.path in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY) ||
                preparedPrimary && call.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY,
                "Only the explicit PREPARED case adds its own native readback and SQL VERIFY in PRIMARIES.")
            val owner = checkNotNull(primary)
            val selectedBy = ownedCutField(owner, "selectedBy") as TestRunOwnerDeleteContinuationV1?
            val selecting = selectedBy ?: owner
            assertNull(ownedCutField(selecting, "selectedBy")); assertSame(drain, ownedCutField(selecting, "drainBy"))
            assertNull(ownedCutField(selecting, "locator"))
            for (retained in listOf(selecting, owner)) {
                assertSame(a.registration, ownedCutField(retained, "registration"))
                assertSame(a.deletionOwner, ownedCutField(retained, "ownership")); assertSame(a.deletion, ownedCutField(retained, "jdbc"))
            }
            if (selectedBy != null) {
                assertSame(owner, ownedCutField(selectedBy, "selectedChild"))
                assertEquals(ownedCutField(owner, "locator"), ownedCutField(selectedBy, "selectedLocator"))
                assertNull(ownedCutField(owner, "drainBy"))
            } else assertEquals(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD, call.path)
            assertSame(drain.budget, owner.budget)
            assertSame(owner, owners.getOrPut(call.phase) { owner })
        }
        val source = checkNotNull(a.deletion.dataSource)
        val connection = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
        assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
        assertTrue(f.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
        assertFalse(f.p.advisory(connection, "complaint-maintenance-v1", "ExclusiveLock"))
        val verifying = call.path in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY)
        assertEquals(inventory == null && !verifying, f.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
        assertEquals(inventory != null, f.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"),
            "Primary/ALL RELOAD/APPLY use shared E; VERIFY uses no E; actual inventory APPLY uses exclusive E.")
        val observation = a.deletion.observations.getValue(call.phase)
        assertSame(observation.lease, ownedPoolLease(connection)); assertFalse(observation.lease.completion.quiescent())
        calls.add(call)
        if (all != null && call.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY &&
            call.sql == OwnerDeleteAllApplySql.test(a.process.consumers.journalConfiguration.scope).LOCK_RECEIPTS) {
            pendingApplyFault?.let { fault ->
                pendingApplyFault = null
                val reload = calls.filter { allOwners[it.phase] === all && it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD }.map { it.phase }.distinct().single()
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, reload.databaseOutcome())
                assertTrue(reload.testRunOwnerDeleteAllCleanupProven(all)); assertTrue(a.deletion.observations.getValue(reload).lease.completion.quiescent())
                fault(a.deletion) // Existing observer's reentrancy guard: real fault SQL on this holder, no fake product result.
                assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
                assertSame(connection, (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection)
            }
        }
        Unit
    } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }

    fun assertReleased() {
        requireConnectionFree(); a.assertSqlReleased(); f.assertDatabaseReleased(); coordinator.assertReleased()
        owners.forEach { (phase, owner) ->
            assertTrue(a.deletion.observations.getValue(phase).lease.completion.quiescent())
            assertTrue(phase.testRunOwnerDeleteCleanupProven(owner))
            assertEquals(if (phase in refusedPrimaries) PersistenceDatabaseOutcome.ROLLED_BACK else PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        }
        allOwners.forEach { (phase, owner) ->
            assertTrue(a.deletion.observations.getValue(phase).lease.completion.quiescent())
            assertTrue(phase.testRunOwnerDeleteAllCleanupProven(owner))
            val failed = refusedAll[owner] === ownedCutField(phase, "path")
            assertEquals(if (failed) PersistenceDatabaseOutcome.ROLLED_BACK else PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        }
        inventoryOwners.forEach { (phase, drain) ->
            assertTrue(a.deletion.observations.getValue(phase).lease.completion.quiescent())
            assertTrue(phase.testOrdinaryDrainCleanupProven(drain)); assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        }
        assertion.get()?.let { throw it }
    }

    fun isSelectedReload(call: TestRegisteredInitialDeletionSqlCallV1): Boolean =
        call.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD && ownedCutField(owners.getValue(call.phase), "selectedBy") != null

    /** Records an expected outcome of this actual held phase; never changes product completion. */
    fun expectPrimaryRollback(call: TestRegisteredInitialDeletionSqlCallV1) {
        assertSame(call.phase, PersistencePhaseOwnership.current()); assertTrue(call.phase in owners)
        owners.filterKeys { it !== call.phase }.forEach { (prior, owner) ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, prior.databaseOutcome())
            assertTrue(prior.testRunOwnerDeleteCleanupProven(owner)); assertTrue(a.deletion.observations.getValue(prior).lease.completion.quiescent())
        }
        assertTrue(refusedPrimaries.add(call.phase))
    }

    fun observedCallCounts(): Pair<Int, Int> = calls.size to coordinator.calls.size

    fun assertRetainedPrimarySequence(expected: List<String>) {
        assertReleased()
        val phases = owners.keys.toList()
        assertEquals(expected, phases.map { phase ->
            if (ownedCutField(owners.getValue(phase), "selectedBy") == null) "SELECT"
            else (ownedCutField(phase, "path") as PersistencePhasePath).name.removePrefix("COMPLAINT_OWNER_DELETE_")
        })
        assertEquals(calls.filter { it.phase in owners }, returnedCalls.filter { it.phase in owners },
            "All intended SQL/mapper calls returned; a mapper or callback error is not a lease/control refusal oracle.")
        phases.forEach { phase ->
            val statements = calls.filter { it.phase === phase }.map { it.sql }
            if (phase !in refusedPrimaries) {
                assertTrue(TestOrdinaryDrainSqlV1.readControlWithActiveHistory in statements)
                assertEquals(TestOrdinarySealSqlV1.lease, statements.last(),
                    "Each successful page/child finishes with its same-holder live-D lease comparison before commit.")
            }
            val path = ownedCutField(phase, "path") as PersistencePhasePath
            val writes = statements.filter { it == OwnerDeletePersistenceSql.DELETE_CONTENT || mutation.containsMatchIn(it) }
            when (path) {
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD -> assertTrue(writes.isEmpty())
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY -> assertEquals(listOf(OwnerDeletePersistenceSql.RECORD_VERIFIED), writes)
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY -> {
                    assertEquals(4, writes.count { it.startsWith("UPDATE complaint_capacity_counters") })
                    assertEquals(listOf(OwnerDeletePersistenceSql.DELETE_CONTENT, OwnerDeletePersistenceSql.DELETE_RESOURCE,
                        OwnerDeletePersistenceSql.INSERT_APPLIED, OwnerDeletePersistenceSql.SPEND_RECOVERY,
                        OwnerDeletePersistenceSql.MARK_APPLIED, OwnerDeletePersistenceSql.COMPLETE_RECEIPT),
                        writes.filterNot { it.startsWith("UPDATE complaint_capacity_counters") })
                }
                else -> error("Unexpected retained-primary phase.")
            }
            if (path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY) {
                assertTrue(statements.none { it in setOf(TestRunSealingSqlV1.lockGlobalControl, TestRunSealingSqlV1.lockScopeControl,
                    TestOrdinaryDrainSqlV1.controlWithActiveHistory, TestRunSealingSqlV1.lockRun) })
                assertEquals(2, statements.count { it == TestOrdinaryDrainSqlV1.readControlWithActiveHistory },
                    "Actual VERIFY compares full D/A before N/P and again after its real proof write, without control/run locks.")
            }
        }
        val selection = calls.filter { it.phase === phases.first() }.map { it.sql }
        val page = selection.indexOf(OwnerDeletePersistenceSql.SELECT_REGISTERED_PRIMARY_PAGE)
        assertTrue(page >= 0 && selection.indexOf(TestOrdinaryDrainSqlV1.controlWithActiveHistory) < page &&
            selection.lastIndexOf(TestOrdinaryDrainSqlV1.controlWithActiveHistory) > page,
            "The unfiltered page stays bracketed by actual current D/A comparisons.")
    }

    fun assertPrimaryRefusal(lastRead: String, reached: String, absent: String? = null) {
        val phase = refusedPrimaries.single()
        val statements = calls.filter { it.phase === phase }.map { it.sql }
        assertTrue(reached in statements); assertEquals(lastRead, statements.last())
        absent?.let { assertFalse(it in statements) }
        assertEquals(listOf(TestOrdinaryDrainStepV1.OPEN), coordinator.calls.map { it.step }.distinct(),
            "A refused primary cannot proceed to CAPTURE, inventory, conversion, a seal or terminal work.")
    }
    fun assertPrimaryApplied(expected: Boolean) {
        assertReleased()
        assertEquals(if (expected) 1 else 0, calls.filter { it.phase in owners && it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY }.map { it.phase }.distinct().size,
            "B's actual SETTLED APPLY must not be repeated by D; a VERIFIED A/POLLING primary is completed by D itself.")
        if (a.family === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) {
            assertFalse(expected); assertTrue(allOwners.isNotEmpty()); assertTrue(refusedAll.isEmpty())
            assertTrue(allOwners.values.groupingBy { it }.eachCount().values.all { it == 2 },
                "D excludes the completed ALL primary; each fresh explicit original owns only its RELOAD/APPLY.")
        }
    }

    /** APPEND always starts PENDING. D's real exact-version reread then compares the retained E
     * in its own inventory holder and marks BOTH scan copies; that is not another primary apply. */
    fun assertRetainedInventoryRecovery(vararg objects: JournalPublisherObject) {
        assertReleased()
        val expected = objects.sortedWith(compareBy<JournalPublisherObject>({ it.key }, { it.version })).map { it.key to it.version }
        assertEquals(expected, inventoryLocators.values.toList()); assertEquals(expected.size, inventoryOwners.size)
        inventoryOwners.keys.forEach { phase ->
            assertEquals(listOf(TestOrdinaryDrainSqlV1.markApplied), calls.filter { it.phase === phase && mutation.containsMatchIn(it.sql) }.map { it.sql })
        }
    }

    /** Literal P-U release observed in the actual CONVERT SQL, never a calculator as oracle. */
    fun assertAllResidualConversion(used: ComplaintCapacityVector = TerminalCatalogAllLiteralChargesV1.applied) {
        assertReleased(); assertEquals(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, a.family)
        val conversion = coordinator.calls.filter { it.step === TestOrdinaryDrainStepV1.CONVERT }
        val mark = conversion.single { it.sql == TestOrdinaryDrainSqlV1.convert }
        assertEquals(checkNotNull(a.event).route.eventId, mark.arguments[0]); assertEquals(a.scope, mark.arguments[1])
        assertEquals(TerminalCatalogAllLiteralChargesV1.promise.toLongArray().joinToString(",", "{", "}"), mark.arguments[2])
        assertEquals(used.toLongArray().joinToString(",", "{", "}"), mark.arguments[3])
        val remainder = TerminalCatalogAllLiteralChargesV1.promise - used
        val updates = conversion.filter { it.sql.startsWith("UPDATE complaint_capacity_counters") }
        assertEquals(ComplaintCapacityCounter.entries.filter { remainder[it] != 0L }.map { it.storedName }.toSet(), updates.map { it.arguments[4] }.toSet())
        assertEquals(updates.size, updates.map { it.arguments[4] }.distinct().size)
        updates.forEach { call ->
            val args = call.arguments; assertEquals(9, args.size)
            val counter = ComplaintCapacityCounter.entries.single { it.storedName == args[4] }
            assertEquals((args[5] as Long) + remainder[counter], args[0]); assertEquals(args[6], args[1])
            assertEquals((args[7] as Long) - remainder[counter], args[2]); assertEquals(args[8], args[3])
        }
    }
    override fun close() {
        try { assertReleased() }
        finally {
            afterPrimary = { _, _ -> }
            a.deletion.before = beforeDeletion; a.deletion.after = afterDeletion
            templates.forEach { (executor, field, previous) -> assertSame(coordinator, field.get(executor)); field.set(executor, previous) }
        }
    }
}
