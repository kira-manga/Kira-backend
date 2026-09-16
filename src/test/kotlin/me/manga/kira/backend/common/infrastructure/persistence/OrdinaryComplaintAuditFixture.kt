package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.ComplaintAuditActor
import me.manga.kira.backend.audit.domain.ComplaintAuditActorKind
import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.ComplaintAuditMutation
import me.manga.kira.backend.audit.domain.ComplaintAuditResourceSubject
import me.manga.kira.backend.audit.infrastructure.JpaAuditRepositoryAdapter
import me.manga.kira.backend.audit.infrastructure.SpringDataAuditLogRepository
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.security.ComplaintGrantConsumption
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.security.JdbcComplaintGrantConsumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.orm.jpa.SharedEntityManagerCreator
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * Explicit W03 fixture composition, NOT a W04 writer: receipt and protected-state SQL are test-only
 * and make no production accounting/admission claim. Grant/counters/Admin/shared-audit code is real.
 * The existing pool/PG owner and exact synthetic-counter restore owner retain all cleanup custody.
 */
internal fun withOrdinaryComplaintAudit(database: PgLifecycleDatabaseFixture, test: (OrdinaryComplaintAuditFixture) -> Unit) {
    withOrdinaryComplaintAudit(database, null, test)
}

internal fun withOrdinaryComplaintAudit(
    database: PgLifecycleDatabaseFixture,
    candidateEndpoint: ResolvedPersistenceEndpoint?,
    test: (OrdinaryComplaintAuditFixture) -> Unit,
) {
    withOrdinarySourceGrantCleanup(
        database,
        SystemPersistenceNanoClock,
        maximumPoolSize = 2,
        includeAuditEntities = true,
        candidateEndpoint = candidateEndpoint,
    ) { ordinary ->
        SyntheticComplaintCounters(ordinary.foreignTemplate(), ordinary.cutoff).use { counters ->
            counters.seed(1, closed = false)
            OrdinaryComplaintAuditFixture(ordinary, counters).use { fixture ->
                seedComplaintAuditRows(fixture)
                test(fixture)
            }
        }
    }
}

internal class OrdinaryComplaintAuditFixture(val ordinary: OrdinarySourceGrantCleanupFixture, val counters: SyntheticComplaintCounters) : AutoCloseable {
    val observer = ordinary.foreignTemplate()
    val at = ordinary.cutoff
    val scope = ComplaintDataScope.of(UUID.randomUUID())
    val resourceId: UUID = UUID.randomUUID()
    val installationId: UUID = UUID.randomUUID()
    val receiptId: UUID = UUID.randomUUID()
    val grantId: UUID = UUID.randomUUID()
    val token = "synthetic-complaint-proof-${UUID.randomUUID()}"
    val clock: Clock = Clock.fixed(at, ZoneOffset.UTC)
    val jdbc = ComplaintAuditFixtureJdbc(this)
    val consumer = JdbcComplaintGrantConsumer(jdbc, clock)
    val capacity = JdbcComplaintCapacityStore(jdbc, counters.syntheticPolicyDigest())
    val repository = JpaAuditRepositoryAdapter(
        JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(ordinary.entityManagerFactory))
            .getRepository(SpringDataAuditLogRepository::class.java),
    )
    val service = AuditService(repository, CurrentUser(), clock)
    val mutation = statusMutation()
    val observations = mutableListOf<Pair<ComplaintAuditFixtureStep, StepUpPhaseObservation>>()
    var afterStep: (ComplaintAuditFixtureStep) -> Unit = {}
    var allocation: JdbcComplaintCapacityStore.ChargedComplaintAudit? = null
        private set
    private var assertionFailure: AssertionError? = null

    fun statusMutation(
        selectedScope: ComplaintDataScope = scope,
        selectedResource: UUID = resourceId,
        actor: ComplaintAuditActor = ComplaintAuditActor.of(ComplaintAuditActorKind.ADMIN, ordinary.userId),
    ): ComplaintAuditMutation.StatusChanged = ComplaintAuditMutation.StatusChanged(
        ComplaintAuditResourceSubject.of(selectedScope, selectedResource.toString()),
        actor,
        2,
        ComplaintStatus.OPEN,
        ComplaintStatus.IN_PROGRESS,
    )

    fun execute(selectedToken: String? = token, selectedUser: UUID = ordinary.userId): ComplaintGrantConsumption = inPhase {
        claimReceipt()
        val consumed = consumer.consume(selectedUser, selectedToken)
        allocation = consumed.allocateAudit(capacity, mutation)
        updateProtectedFixture()
        writeAudit()
        completeReceipt()
        checkpoint(ComplaintAuditFixtureStep.BEFORE_COMMIT)
        consumed
    }

    /** Test-only orchestration/fault seam. No lambda, receipt or status writer is added to production. */
    fun inPhase(work: () -> ComplaintGrantConsumption?): ComplaintGrantConsumption {
        jdbc.resetCounts()
        assertionFailure = null
        val phase = ordinary.ownership.enterComplaintAdminAudit()
        var consumed: ComplaintGrantConsumption? = null
        var failed = false
        try {
            phase.begin()
            consumed = work()
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
        return checkNotNull(consumed).also { it.requireCommitted() }
    }

    fun claimReceipt() {
        claimComplaintAuditReceipt(this)
        checkpoint(ComplaintAuditFixtureStep.RECEIPT)
    }

    fun updateProtectedFixture() {
        changeComplaintAuditStatus(this)
        checkpoint(ComplaintAuditFixtureStep.DOMAIN)
    }

    fun writeAudit(selectedMutation: ComplaintAuditMutation = mutation, selectedAllocation: ComplaintAuditAllocation = checkNotNull(allocation)) {
        service.recordComplaintMutation(selectedMutation, selectedAllocation, at)
        checkpoint(ComplaintAuditFixtureStep.AUDIT)
    }

    fun completeReceipt() {
        completeComplaintAuditReceipt(this)
        checkpoint(ComplaintAuditFixtureStep.RECEIPT_COMPLETED)
    }

    fun checkpoint(step: ComplaintAuditFixtureStep) {
        preserveAssertions {
            observations.add(step to observeStepUpPhase(ordinary))
            afterStep(step)
        }
    }

    /** A product catch-all may sanitize a test assertion, but it must never turn that assertion into expected-fault evidence. */
    fun preserveAssertions(work: () -> Unit) {
        try {
            work()
        } catch (problem: AssertionError) {
            assertionFailure = assertionFailure ?: problem
            throw problem
        }
    }

    fun state(): ComplaintAuditFixtureState = ComplaintAuditFixtureState(
        rowJson("complaint_idempotency_receipts", "actor_id", ordinary.userId),
        rowJson("complaints", "id", resourceId),
        rowJson("admin_step_up_grants", "user_id", ordinary.userId),
        rowJson("audit_log", "complaint_data_scope_id", scope.id),
        counters.snapshot(),
    )

    fun assertAuditCharge(before: Map<String, CounterSnapshot>) {
        val after = counters.snapshot()
        assertEquals(before.keys, after.keys)
        for ((name, old) in before) {
            val units = when (name) {
                "audit_rows" -> 1L
                "storage_bytes" -> 65_536L
                else -> 0L
            }
            val current = after.getValue(name)
            if (units == 0L) {
                assertEquals(old, current)
            } else {
                assertEquals(old.preserved, current.preserved)
                assertEquals(old.actual + units, current.actual)
                assertEquals(old.free - units, current.free)
            }
        }
    }

    fun assertReleased() {
        assertTrue(observations.all { it.second.lease.completion.quiescent() })
        assertEquals(0, ordinary.admission.activeOwners())
        requireConnectionFree()
    }

    /** Actual PostgreSQL insert refusal, installed/restored only on this synthetic database and scope. */
    fun refuseAuditInsert(): AutoCloseable {
        observer.execute(
            "ALTER TABLE audit_log ADD CONSTRAINT w03_synthetic_audit_refusal " +
                "CHECK (complaint_data_scope_id IS DISTINCT FROM '${scope.id}'::uuid) NOT VALID",
        )
        return AutoCloseable { observer.execute("ALTER TABLE audit_log DROP CONSTRAINT w03_synthetic_audit_refusal") }
    }

    private fun rowJson(table: String, key: String, id: UUID): List<String> =
        observer.queryForList("SELECT to_jsonb(row)::text FROM $table row WHERE $key = ? ORDER BY to_jsonb(row)::text", String::class.java, id)

    override fun close() {
        assertReleased()
        deleteComplaintAuditRows(this)
    }
}

internal data class ComplaintAuditFixtureState(
    val receipts: List<String>,
    val domain: List<String>,
    val grants: List<String>,
    val audits: List<String>,
    val counters: Map<String, CounterSnapshot>,
)

internal enum class ComplaintAuditFixtureStep { RECEIPT, GRANT, FIRST_COUNTER, COUNTERS, ADMIN, DOMAIN, AUDIT, RECEIPT_COMPLETED, BEFORE_COMMIT }

/** Observes/faults only after actual SQL; no fabricated grant result, count, transaction identity or authorization. */
internal class ComplaintAuditFixtureJdbc(private val fixture: OrdinaryComplaintAuditFixture) : JdbcTemplate(fixture.ordinary.pool) {
    var counterUpdates = 0
        private set
    var beforeAdminLock: () -> Unit = {}
    var afterGrantLock: () -> Unit = {}
    var beforeCounterUpdate: (Int) -> Unit = {}
    val counterResults = mutableListOf<Int>()

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    fun resetCounts() {
        counterUpdates = 0
        counterResults.clear()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
        val admin = sql.startsWith("SELECT id, enabled, role FROM users")
        if (admin) fixture.preserveAssertions(beforeAdminLock)
        return super.query(sql, rowMapper, *args).also {
            if (sql.startsWith("SELECT id FROM admin_step_up_grants")) fixture.preserveAssertions(afterGrantLock)
            if (sql.startsWith("UPDATE admin_step_up_grants SET used_at")) fixture.checkpoint(ComplaintAuditFixtureStep.GRANT)
            if (admin) fixture.checkpoint(ComplaintAuditFixtureStep.ADMIN)
        }
    }

    override fun update(sql: String, vararg args: Any?): Int {
        val counter = sql.startsWith("UPDATE complaint_capacity_counters")
        if (counter) fixture.preserveAssertions { beforeCounterUpdate(counterResults.size + 1) }
        return super.update(sql, *args).also { updated ->
            if (counter) {
                counterResults.add(updated)
                if (updated == 1) {
                    counterUpdates++
                    fixture.checkpoint(if (counterUpdates == 1) ComplaintAuditFixtureStep.FIRST_COUNTER else ComplaintAuditFixtureStep.COUNTERS)
                }
            }
        }
    }
}

internal class SyntheticComplaintAuditCheckedFailure : Exception("Synthetic W03 checked failure.")
