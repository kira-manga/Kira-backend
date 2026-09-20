package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteContinuationV1
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Read-only registered RELOAD variant. Even a released result contains locators, never work or a drained-range proof. */
internal class ComplaintOwnerDeleteRegisteredSelectionOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val store: JdbcComplaintOwnerDeleteStore,
    private val jdbc: JdbcTemplate,
    private val graph: TestOwnerDeleteLocalGraphV1,
    internal val original: TestRunOwnerDeleteContinuationV1,
) : ComplaintOwnerDeletePhaseOperation {
    private var stage = Stage.RETAINED
    private var locators: List<Pair<UUID, UUID>>? = null

    override fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean =
        phase === selected && expected === PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD
    override fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean =
        belongsTo(selected, expected) && stage === Stage.COMPLETE

    internal fun released(selected: JdbcComplaintOwnerDeleteStore): List<Pair<UUID, UUID>> {
        check(selected === store)
        phase.ownerDelete.requireCommitted(this)
        requireConnectionFree()
        original.requireReleasedSelection()
        return checkNotNull(locators).toList()
    }

    private fun execute(capacity: JdbcComplaintCapacityStore) {
        retained()
        val controls = TestOwnerDeleteControlBindingV1(graph)
        controls.lock(jdbc, authorizing = false)
        stage = Stage.COUNTERS_READY
        capacity.lockForRegisteredOwnerDeleteSelection(this)
        check(stage === Stage.COUNTERS)
        stage = Stage.RUN
        controls.lockRun(jdbc, authorizing = false)
        stage = Stage.SELECTING
        // No valid-only join, timestamp cutoff, OFFSET or SKIP LOCKED. An orphan, foreign link or
        // unexpected pending shape is returned and refuses the page instead of disappearing from it.
        val found = jdbc.query(OwnerDeletePersistenceSql.SELECT_REGISTERED_PRIMARY_PAGE, { row, _ ->
            check(row.getBoolean("valid") && !row.wasNull())
            original.requireEarlierAuthorization(checkNotNull(row.getTimestamp("created_at")).toInstant())
            checkNotNull(row.getObject("actor_id", UUID::class.java)) to checkNotNull(row.getObject("idempotency_key", UUID::class.java))
        }, graph.writer, graph.routing.journalConfiguration.scope.id)
        check(found.size <= OwnerDeletePersistenceSql.REGISTERED_PRIMARY_PAGE_LIMIT + 1 && found.distinct().size == found.size)
        locators = found
        retained()
        stage = Stage.COMPLETE
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        retained(); check(selected === jdbc && stage === Stage.COUNTERS_READY)
        stage = Stage.COUNTERS
    }

    internal fun requireCapacityPolicy(ledger: ComplaintCapacityLedger, selected: JdbcTemplate) {
        retained(); check(selected === jdbc && stage === Stage.COUNTERS)
        check(graph.policy.digestBytes().contentEquals(ledger.configuration.digestBytes()) &&
            graph.policy.hardLimit == ledger.balance.hardLimit && graph.policy.creationLimit == ledger.balance.creationLimit)
        phase.ownerDelete.checkCapacity(this, jdbc, ledger)
    }

    private fun retained() {
        phase.ownerDelete.requireRetained(this, jdbc)
        phase.requireTestRunOwnerDeleteReload(original, graph, jdbc)
        graph.requireDeletion(jdbc)
    }

    override fun toString(): String = "ComplaintOwnerDeleteRegisteredSelectionOperation(bounded-locators-only,redacted)"
    private enum class Stage { RETAINED, COUNTERS_READY, COUNTERS, RUN, SELECTING, COMPLETE }

    companion object {
        internal fun capture(store: JdbcComplaintOwnerDeleteStore, jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore,
            graph: TestOwnerDeleteLocalGraphV1, original: TestRunOwnerDeleteContinuationV1): ComplaintOwnerDeleteRegisteredSelectionOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.ownerDelete.requireOperation(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD)
                phase.requireTestRunOwnerDeleteReload(original, graph, jdbc)
                return ComplaintOwnerDeleteRegisteredSelectionOperation(phase, store, jdbc, graph, original).also {
                    phase.ownerDelete.retain(it, jdbc)
                    it.execute(capacity)
                }
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}
