package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.TRY_CATALOG_LOCK
import me.manga.kira.backend.complaint.infrastructure.catalog.requiredTestActivationBoolean
import me.manga.kira.backend.complaint.infrastructure.catalog.requiredTestActivationLong
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.util.HexFormat
import java.util.UUID

/** Fixed M -> exclusive E -> global/scope/catalog/history/counters/run -> identity counts. No writes or lease takeover. */
internal class TestNamespaceActiveRegistrationOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val original: ComplaintTestNamespaceActiveRegistrationAttemptV1,
) {
    private val process = original.process
    private val journal = process.consumers.journalConfiguration
    private val scope = journal.scope.id
    private val writer = UUID.fromString(journal.declaration().writer.generationId)
    private val catalogWriter = UUID.fromString(process.catalogActivation.initialWriterRegistry().catalogWriter.generationId)
    private val maximumGenerations = process.catalogReadback.chainPolicy.limits.maximumGenerations
    private var historyLocked = false
    private var countersClaimed = false
    private var completed = false
    private var result: TestNamespaceActiveRegistrationSnapshotV1? = null

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && completed
    internal fun requireReleased() { phase.testNamespaceActiveRegistration.requireCommitted(this); requireConnectionFree() }
    internal val snapshot: TestNamespaceActiveRegistrationSnapshotV1 get() { requireReleased(); return checkNotNull(result) }

    private fun run() {
        requireRetained()
        val global = control(UUID(0L, 0L))
        val scoped = control(scope)
        requireRegistration(scoped.generation == global.generation && scoped.hash.contentEquals(global.hash))
        requireRegistration(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> row.requiredTestActivationBoolean("locked") }).single())
        requireRetained()
        val tail = jdbc.query(TestNamespaceActiveRegistrationSqlV1.tail, { row, _ -> TestNamespaceRecoveryRegistrationTailV1(row, scope) },
            scope, global.generation, global.hash, catalogWriter).single()
        original.requireCapturedGate(tail)
        val history = checkNotNull(jdbc.query(CatalogTestRunActivationSqlV1.lockRecoveryRegistrationHistory, ResultSetExtractor { rows ->
            CatalogTestRunActivationHistoryV1.readRecoveryRegistration(rows, tail.generation, maximumGenerations)
        }, tail.token, scope, tail.generation, tail.envelopeHash, maximumGenerations + 1))
        requireRetained()
        historyLocked = true
        val counters = JdbcComplaintCapacityStore(jdbc, process.consumers.capacityPolicy.digestBytes()).lockForTestNamespaceActiveRegistration(this)
        val policy = process.consumers.capacityPolicy
        requireRegistration(counters.balance.hardLimit == policy.hardLimit && counters.balance.creationLimit == policy.creationLimit &&
            counters.daily.dailyLimit == policy.dailyEnrollmentLimit)
        requireRetained()
        val limit = jdbc.query(TestNamespaceActiveRegistrationSqlV1.lockRunIdentity, { row, _ ->
            requireRegistration(row.getObject("data_scope_id", UUID::class.java) == scope)
            row.requiredTestActivationLong("installation_limit")
        }).single()
        val binding = TestNamespaceActiveRegistrationBindingV1(process, tail, limit, global)
        val run = jdbc.query(TestNamespaceActiveRegistrationSqlV1.run, { row, _ -> TestNamespaceActiveRegistrationRunV1(row, binding) },
            *binding.runArguments()).single()
        requireRetained()
        val installations = jdbc.query(TestNamespaceActiveRegistrationSqlV1.installationCounts, { row, _ ->
            row.requiredTestActivationLong("ids") to row.requiredTestActivationLong("credentials")
        }, scope, scope).single()
        run.requireAccounting(counters, installations.first, installations.second)
        // Re-read only already-held controls, never a backwards acquisition. Exact xmin also detects a replaced preimage.
        control(UUID(0L, 0L)).requireSame(global)
        control(scope).requireSame(scoped)
        requireRetained()
        result = TestNamespaceActiveRegistrationSnapshotV1(tail, binding, history, global, scoped, counters, run, installations)
        completed = true
    }

    private fun control(selected: UUID): TestNamespaceActiveRegistrationControlV1 {
        requireRetained()
        return jdbc.query(TestNamespaceActiveRegistrationSqlV1.lockControl, { row, _ -> TestNamespaceActiveRegistrationControlV1(row) },
            scope, process.desiredGeneration, process.implementationSchema, process.configurationHashBytes(), process.databaseIdentity, process.restoreIdentity,
            writer, catalogWriter, HexFormat.of().parseHex(process.catalogReadback.currentTrustBundleSha256), maximumGenerations.toLong(), selected)
            .single().also { requireRegistration(it.scope == selected); requireRetained() }
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireRetained(); requireRegistration(selected === jdbc && historyLocked && !countersClaimed && !completed); countersClaimed = true
    }
    internal fun requireCounterRead(selected: JdbcTemplate) {
        requireRetained(); requireRegistration(selected === jdbc && historyLocked && countersClaimed && !completed)
    }
    private fun requireRetained() = phase.testNamespaceActiveRegistration.requireRetained(this, jdbc)
    override fun toString(): String = "TestNamespaceActiveRegistrationOperationV1(read-only-current-ACTIVE,redacted)"

    companion object {
        internal fun execute(jdbc: JdbcTemplate, original: ComplaintTestNamespaceActiveRegistrationAttemptV1): TestNamespaceActiveRegistrationOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.testNamespaceActiveRegistration.requireOperation(original, jdbc)
                val operation = TestNamespaceActiveRegistrationOperationV1(phase, jdbc, original)
                phase.testNamespaceActiveRegistration.retain(operation, jdbc)
                operation.run()
                return operation
            } catch (problem: Throwable) {
                original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}
