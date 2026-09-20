package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.ComplaintAuditMutation
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintDeletionOperation
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.security.ComplaintGrantConsumption
import me.manga.kira.backend.security.JdbcComplaintGrantConsumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Adds only deletion selection to the existing synthetic rows/counters, shared audit and owned PG/pool fixture. */
internal fun withDeletionComplaintAudit(database: PgLifecycleDatabaseFixture, test: (DeletionComplaintAuditFixture) -> Unit) {
    withDeletionComplaintAudit(database, SystemPersistenceNanoClock, test)
}

internal fun withDeletionComplaintAudit(database: PgLifecycleDatabaseFixture, nanoClock: PersistenceNanoClock, test: (DeletionComplaintAuditFixture) -> Unit) {
    withDeletionComplaintAudit(database, nanoClock, null, test)
}

internal fun withDeletionComplaintAudit(
    database: PgLifecycleDatabaseFixture,
    nanoClock: PersistenceNanoClock,
    candidateEndpoint: ResolvedPersistenceEndpoint?,
    test: (DeletionComplaintAuditFixture) -> Unit,
) {
    withOrdinaryComplaintAudit(database, candidateEndpoint) { base ->
        val ordinary = base.ordinary.ownedPool
        val endpoint = ownedCutField(ordinary.pool, "endpoint") as ResolvedPersistenceEndpoint
        val pool = GuardedDataSource.deletion(ordinary.scope.owner, endpoint, PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY)
        DeletionComplaintAuditFixture(base, pool, nanoClock).use { fixture ->
            assertEquals(PersistenceLifecycleObservation.READY, pool.prepareDeletion())
            test(fixture)
        }
        // Existing outer owner alone proves shared-root/ordinary/Timer cleanup.
    }
}

/** No production erasure/policy/provenance claim: the protected row mutation below is explicitly test-only. */
internal class DeletionComplaintAuditFixture(
    val base: OrdinaryComplaintAuditFixture,
    val pool: GuardedDataSource,
    nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
) : AutoCloseable {
    val admission = DeletionPersistenceAdmission()
    val manager = GuardedJdbcTransactionManager(pool)
    val ownership = PersistencePhaseOwnership.deletion(admission, manager, nanoClock)
    val jdbc = DeletionAuditFixtureJdbc(this)
    val consumer = JdbcComplaintGrantConsumer(jdbc, base.clock)
    val capacity = JdbcComplaintCapacityStore(jdbc, base.counters.syntheticPolicyDigest())
    val at: Instant = Instant.parse("2011-02-03T04:05:06.123456Z")
    val observations = mutableListOf<Pair<DeletionAuditStep, StepUpPhaseObservation>>()
    var afterStep: (DeletionAuditStep) -> Unit = {}
    var operation: ComplaintDeletionOperation? = null
        private set
    var allocation: JdbcComplaintCapacityStore.ChargedComplaintAudit? = null
        private set
    private var assertionFailure: AssertionError? = null

    fun execute(mutation: ComplaintAuditMutation = base.mutation): ComplaintDeletionOperation = inPhase {
        prepare(mutation)
        allocate()
        mutateProtectedFixture()
        writeAudit()
        checkpoint(DeletionAuditStep.BEFORE_COMMIT)
        operation
    }

    /** Real grant/counters/current Admin/audit only. Protected rows are synthetic; no receipt or W04 authority is supplied. */
    fun executeAdmin(selectedToken: String? = base.token, selectedUser: UUID = base.ordinary.userId): ComplaintGrantConsumption = inAdminPhase {
        val consumed = consumer.consume(selectedUser, selectedToken)
        allocation = consumed.allocateAudit(capacity, base.mutation)
        changeProtectedFixtureRows()
        checkpoint(DeletionAuditStep.PROTECTED)
        writeAudit(mutation = base.mutation, timestamp = base.at)
        checkpoint(DeletionAuditStep.BEFORE_COMMIT)
        consumed
    }

    /** Same fixture owner/finalizer, with the explicit grant-consumption path instead of the accounting cursor. */
    fun inAdminPhase(work: () -> ComplaintGrantConsumption?): ComplaintGrantConsumption {
        observations.clear()
        jdbc.resetCounts()
        assertionFailure = null
        val phase = ownership.enterComplaintDeletionAdminAudit()
        var completed: ComplaintGrantConsumption? = null
        var failed = false
        try {
            phase.begin()
            checkpoint(DeletionAuditStep.BEGIN)
            completed = work()
            phase.commit()
        } catch (problem: Throwable) {
            if (problem is AssertionError) assertionFailure = assertionFailure ?: problem
            failed = true
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        assertionFailure?.let { throw it }
        if (failed) throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        return checkNotNull(completed).also { it.requireCommitted() }
    }

    /** Existing fixture convention: injected checked faults are test-only, never a product execution callback. */
    fun inPhase(work: () -> ComplaintDeletionOperation?): ComplaintDeletionOperation {
        observations.clear()
        jdbc.resetCounts()
        assertionFailure = null
        val phase = ownership.enterComplaintDeletionMutation()
        var completed: ComplaintDeletionOperation? = null
        var failed = false
        try {
            phase.begin()
            checkpoint(DeletionAuditStep.BEGIN)
            completed = work()
            phase.commit()
        } catch (problem: Throwable) {
            if (problem is AssertionError) assertionFailure = assertionFailure ?: problem
            failed = true
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        assertionFailure?.let { throw it }
        if (failed) throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        return checkNotNull(completed).also { it.requireCommitted() }
    }

    fun prepare(mutation: ComplaintAuditMutation = base.mutation): ComplaintDeletionOperation =
        ComplaintDeletionOperation.prepare(jdbc, mutation, at).also { operation = it }

    fun allocate(): JdbcComplaintCapacityStore.ChargedComplaintAudit = checkNotNull(operation).allocateAudit(capacity).also { allocation = it }

    fun mutateProtectedFixture() {
        val selected = checkNotNull(operation)
        selected.beginProtectedWork(jdbc)
        changeProtectedFixtureRows()
        selected.protectedWorkReturned(jdbc)
        checkpoint(DeletionAuditStep.PROTECTED)
    }

    /** Test-only row SQL shared by the two explicit fixture compositions, never a second production cursor. */
    fun changeProtectedFixtureRows() {
        assertEquals(
            listOf("OPEN" to 1L),
            jdbc.query(
                "SELECT status, version FROM complaints WHERE id = ? AND data_scope_id = ? FOR UPDATE",
                { result, _ -> result.getString(1) to result.getLong(2) },
                base.resourceId,
                base.scope.id,
            ),
        )
        assertEquals(
            1,
            jdbc.update(
                "UPDATE complaints SET status = 'IN_PROGRESS', version = 2, updated_at = ? " +
                    "WHERE id = ? AND data_scope_id = ? AND status = 'OPEN' AND version = 1",
                Timestamp.from(base.at),
                base.resourceId,
                base.scope.id,
            ),
        )
    }

    fun writeAudit(
        mutation: ComplaintAuditMutation = checkNotNull(operation).mutation,
        selected: ComplaintAuditAllocation = checkNotNull(allocation),
        timestamp: Instant = at,
    ) {
        base.service.recordComplaintMutation(mutation, selected, timestamp)
        checkpoint(DeletionAuditStep.AUDIT)
    }

    fun resetProtectedFixture() {
        assertReleased()
        assertEquals(
            1,
            base.observer.update(
                "UPDATE complaints SET status = 'OPEN', version = 1, updated_at = ? WHERE id = ? AND data_scope_id = ?",
                Timestamp.from(base.at),
                base.resourceId,
                base.scope.id,
            ),
        )
    }

    fun checkpoint(step: DeletionAuditStep) = preserveAssertions {
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val holder = TransactionSynchronizationManager.getResource(pool) as ConnectionHolder
        assertEquals(setOf(pool), TransactionSynchronizationManager.getResourceMap().keys)
        val lease = ownedPoolLease(holder.connection)
        val scope = base.ordinary.ownedPool.scope
        assertEquals(1, scope.entries(deletion = true).count { it.jdbc.currentPoolState(lease.state) })
        assertTrue(scope.entries().none { it.jdbc.currentPoolState(lease.state) })
        val identity = checkNotNull(jdbc.queryForObject("SELECT pg_backend_pid(), txid_current()", { result, _ -> result.getInt(1) to result.getLong(2) }))
        assertSame(holder, TransactionSynchronizationManager.getResource(pool))
        observations.add(step to StepUpPhaseObservation(phase, lease, identity))
        afterStep(step)
    }

    fun preserveAssertions(work: () -> Unit) {
        try {
            work()
        } catch (problem: AssertionError) {
            assertionFailure = assertionFailure ?: problem
            throw problem
        }
    }

    fun assertReleased() {
        assertTrue(observations.all { it.second.lease.completion.quiescent() })
        assertEquals(0, admission.activeOwners().totalOwners)
        base.assertReleased()
        requireConnectionFree()
    }

    override fun close() {
        try {
            assertReleased()
            // SYSTEM/INSTALLATION have no Admin FK; clean only this fixture's exact synthetic scope.
            base.observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ?", base.scope.id)
        } finally {
            val receipt = checkNotNull(pool.requestShutdown())
            assertTrue(pool.shutdownInvocation() in setOf(PoolShutdownInvocation.RETURNED, PoolShutdownInvocation.ALREADY_CLAIMED))
            var observed = PoolShutdownObservation.PENDING
            awaitLifecycleFact {
                observed = receipt.observe()
                observed !== PoolShutdownObservation.PENDING
            }
            assertEquals(PoolShutdownObservation.DELETION_LOCAL_ENDED, observed)
            assertFalse(base.ordinary.ownedPool.scope.owner.snapshot().shutdownRequested)
        }
    }
}

internal enum class DeletionAuditStep { BEGIN, GRANT, LOCKED_COUNTERS, FIRST_COUNTER, COUNTERS, ADMIN, PROTECTED, AUDIT, BEFORE_COMMIT }

/** Observe actual SQL results, never fabricate a successful count or original-holder identity. */
internal class DeletionAuditFixtureJdbc(private val fixture: DeletionComplaintAuditFixture) : JdbcTemplate(fixture.pool) {
    val counterResults = mutableListOf<Int>()
    var counterLocks = 0
        private set
    var beforeCounterUpdate: (Int) -> Unit = {}
    var beforeAdminLock: () -> Unit = {}
    var afterGrantLock: () -> Unit = {}

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    fun resetCounts() {
        counterResults.clear()
        counterLocks = 0
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> = super.query(sql, rowMapper).also { rows ->
        if (sql.startsWith("SELECT name, ordinal, accounting_version")) {
            fixture.preserveAssertions { assertEquals(ComplaintCapacityEncoding.WIDTH, rows.size) }
            counterLocks++
            fixture.checkpoint(DeletionAuditStep.LOCKED_COUNTERS)
        }
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
        val admin = sql.startsWith("SELECT id, enabled, role FROM users")
        if (admin) fixture.preserveAssertions(beforeAdminLock)
        return super.query(sql, rowMapper, *args).also {
            if (sql.startsWith("SELECT id FROM admin_step_up_grants")) fixture.preserveAssertions(afterGrantLock)
            if (sql.startsWith("UPDATE admin_step_up_grants SET used_at")) fixture.checkpoint(DeletionAuditStep.GRANT)
            if (admin) fixture.checkpoint(DeletionAuditStep.ADMIN)
        }
    }

    override fun update(sql: String, vararg args: Any?): Int {
        val counter = sql.startsWith("UPDATE complaint_capacity_counters")
        if (counter) fixture.preserveAssertions { beforeCounterUpdate(counterResults.size + 1) }
        return super.update(sql, *args).also { count ->
            if (counter) {
                counterResults.add(count)
                if (count == 1) fixture.checkpoint(if (counterResults.size == 1) DeletionAuditStep.FIRST_COUNTER else DeletionAuditStep.COUNTERS)
            }
        }
    }
}
