package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunAdminDeleteContinuationV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

/** Receipt-located exact ADMIN primary only. No new grant, current role check, ETag or authorization. */
internal class ComplaintAdminDeleteRegisteredReloadOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val store: JdbcComplaintAdminDeleteStore,
    private val jdbc: JdbcTemplate,
    private val graph: TestOwnerDeleteLocalGraphV1,
    internal val original: TestRunAdminDeleteContinuationV1,
) : ComplaintAdminDeletePhaseOperation {
    private var stage = Stage.RETAINED
    private var event: TestOwnerDeleteJournalEventV1? = null
    private var publication: OwnerDeleteRows.Publication? = null
    private var reservation: OwnerDeleteRows.Recovery? = null

    override fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean =
        phase === selected && expected === PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD
    override fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean =
        belongsTo(selected, expected) && stage === Stage.COMPLETE

    /** Only the existing store's private issuer consumes this positively committed/released snapshot. */
    internal fun released(selected: JdbcComplaintAdminDeleteStore): Pair<TestOwnerDeleteJournalEventV1, OwnerDeleteRows.Publication> {
        check(selected === store)
        phase.adminDelete.requireCommitted(this)
        requireConnectionFree()
        original.requireReleasedReload()
        return checkNotNull(event) to checkNotNull(publication)
    }

    private fun execute(capacity: JdbcComplaintCapacityStore) {
        retained()
        val controls = TestOwnerDeleteControlBindingV1(graph)
        val control = controls.lock(jdbc, authorizing = false)
        stage = Stage.RECEIPT
        // Actor/key are locators only. Scope comes from registration; the event is selected by THIS locked receipt.
        val receipt = jdbc.query(AdminDeletePersistenceSql.LOCK_RECEIPT, { row, _ -> AdminDeleteRows.Receipt(row) },
            original.actorId, original.operationKey).single()
        check(receipt.valid && receipt.state in setOf("AUTHORIZED_DELETE", "COMPLETED"))
        stage = Stage.PUBLICATION
        val row = jdbc.query(AdminDeletePersistenceSql.LOCK_PUBLICATION, { result, _ -> OwnerDeleteRows.Publication.adminErasure(result) },
            checkNotNull(receipt.publication)).single()
        val prepared = row.state == "PREPARED"
        if (prepared) original.requirePreparedReload() else check(row.state in setOf("VERIFIED", "APPLIED"))
        val canonical = TestOwnerDeleteJournalCodecV1.restoreAdminErasureCanonical(graph.routing, row.bytes, row.routingKey, row.kind)
        row.requireAdminErasureEvent(canonical)
        val tuple = AdminDeleteRows.tuple(canonical)
        check(receipt.matches(tuple) && receipt.consumedGrantId == canonical.adminComparison.consumedGrantId && row.writer == graph.writer && row.createdAt == receipt.authorizedAt)
        control.requireContinuation(row.epoch, prepared)
        val proof = if (prepared) null else TestOwnerDeleteVerificationCodecV1.forAdminErasure(graph.routing).parse(checkNotNull(row.verificationBytes), canonical).also {
            ComplaintAdminDeleteVerificationOperation.requireColumns(it, row)
        }
        if (row.state != "APPLIED") check(receipt.state == "AUTHORIZED_DELETE") else {
            check(receipt.state == "COMPLETED"); AdminDeleteRows.requireApplied(receipt.completed(), canonical)
            check(receipt.externalEvent == row.eventId && receipt.externalEpoch == row.epoch &&
                receipt.externalVersion == row.objectVersion && receipt.externalHash.contentEquals(row.ciphertextHash))
        }
        event = canonical
        publication = row
        stage = Stage.RESERVATION
        reservation = jdbc.query(AdminDeletePersistenceSql.LOCK_RECOVERY, { result, _ ->
            OwnerDeleteRows.Recovery(result, canonical.adminComparison.scope, row.eventId, AdminDeleteRows.recovery(canonical))
        }, row.eventId).single()
        stage = Stage.COUNTERS_READY
        capacity.lockForRegisteredAdminDeleteReload(this)
        check(stage === Stage.COUNTERS)
        stage = Stage.RUN
        controls.lockRun(jdbc, authorizing = false)
        original.requireEarlierAuthorization(row.createdAt)
        val now = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { result, _ -> result.getTimestamp(1).toInstant() }))
        check(!row.createdAt.isAfter(now))
        proof?.let { check(!Instant.parse(it.verifiedAt).isAfter(now) && Instant.parse(it.retainUntil).isAfter(now)) }
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
        check(checkNotNull(reservation).remaining.fitsWithin(ledger.balance.recoveryReserved))
        phase.adminDelete.checkCapacity(this, jdbc, ledger)
    }

    private fun retained() {
        phase.adminDelete.requireRetained(this, jdbc)
        phase.requireTestRunAdminDeleteReload(original, graph, jdbc)
        graph.requireDeletion(jdbc)
    }

    override fun toString(): String = "ComplaintAdminDeleteRegisteredReloadOperation(original-released-primary-only,redacted)"
    private enum class Stage { RETAINED, RECEIPT, PUBLICATION, RESERVATION, COUNTERS_READY, COUNTERS, RUN, COMPLETE }

    companion object {
        internal fun capture(store: JdbcComplaintAdminDeleteStore, jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore,
            graph: TestOwnerDeleteLocalGraphV1, original: TestRunAdminDeleteContinuationV1): ComplaintAdminDeleteRegisteredReloadOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.adminDelete.requireOperation(jdbc, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD)
                phase.requireTestRunAdminDeleteReload(original, graph, jdbc)
                return ComplaintAdminDeleteRegisteredReloadOperation(phase, store, jdbc, graph, original).also {
                    phase.adminDelete.retain(it, jdbc)
                    it.execute(capacity)
                }
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}
