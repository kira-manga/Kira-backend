package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunVerifiedOwnerDeleteV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

/** Existing exact primary only. No current bearer, new claim, missing-row reconstruction or provider. */
internal class ComplaintOwnerDeleteVerifiedReloadOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val store: JdbcComplaintOwnerDeleteStore,
    private val jdbc: JdbcTemplate,
    private val graph: TestOwnerDeleteLocalGraphV1,
    internal val original: TestRunVerifiedOwnerDeleteV1,
) : ComplaintOwnerDeletePhaseOperation {
    private var stage = Stage.RETAINED
    private var event: TestOwnerDeleteJournalEventV1? = null
    private var publication: OwnerDeleteRows.Publication? = null
    private var reservation: OwnerDeleteRows.Recovery? = null

    override fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean =
        phase === selected && expected === PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD
    override fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean =
        belongsTo(selected, expected) && stage === Stage.COMPLETE

    /** Only the existing store's private issuer consumes this positively committed/released snapshot. */
    internal fun released(selected: JdbcComplaintOwnerDeleteStore): Pair<TestOwnerDeleteJournalEventV1, OwnerDeleteRows.Publication> {
        check(selected === store)
        phase.ownerDelete.requireCommitted(this)
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
        val receipt = jdbc.query(OwnerDeletePersistenceSql.LOCK_REGISTERED_RECEIPT, { row, _ -> OwnerDeleteRows.Receipt(row) },
            original.actorId, original.operationKey, graph.routing.journalConfiguration.scope.id).single()
        check(receipt.valid && receipt.state in setOf("AUTHORIZED_DELETE", "COMPLETED"))
        stage = Stage.PUBLICATION
        val row = jdbc.query(OwnerDeletePersistenceSql.LOCK_PUBLICATION, { result, _ -> OwnerDeleteRows.Publication(result) },
            checkNotNull(receipt.publication)).single()
        check(row.state in setOf("VERIFIED", "APPLIED")) // Never turn PREPARED into proof or start publication.
        val canonical = TestOwnerDeleteJournalCodecV1.restoreCanonical(graph.routing, row.bytes, row.routingKey)
        row.requireEvent(canonical)
        val tuple = ComplaintOwnerDeleteTuple(ScopedInstallationId(canonical.tuple.actorId, canonical.tuple.scope),
            canonical.tuple.operationKey, canonical.complaintIds().single(), canonical.tuple.fingerprintBytes())
        check(receipt.matches(tuple) && row.writer == graph.writer && row.createdAt == receipt.authorizedAt)
        control.requireContinuation(row.epoch, prepared = false)
        val proof = TestOwnerDeleteVerificationCodecV1(graph.routing).parse(checkNotNull(row.verificationBytes), canonical)
        ComplaintOwnerDeleteVerificationOperation.requireColumns(proof, row)
        if (row.state == "VERIFIED") check(receipt.state == "AUTHORIZED_DELETE") else {
            check(receipt.state == "COMPLETED" && receipt.completed() === ComplaintOwnerDeleteReceipt.Applied)
            check(receipt.externalEvent == row.eventId && receipt.externalEpoch == row.epoch &&
                receipt.externalVersion == row.objectVersion && receipt.externalHash.contentEquals(row.ciphertextHash))
        }
        event = canonical
        publication = row
        stage = Stage.RESERVATION
        reservation = jdbc.query(OwnerDeletePersistenceSql.LOCK_RECOVERY, { result, _ ->
            OwnerDeleteRows.Recovery(result, canonical.tuple.scope, row.eventId)
        }, row.eventId).single()
        stage = Stage.COUNTERS_READY
        capacity.lockForVerifiedOwnerDeleteReload(this)
        check(stage === Stage.COUNTERS)
        stage = Stage.RUN
        controls.lockRun(jdbc, authorizing = false)
        original.requireEarlierAuthorization(row.createdAt)
        val now = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { result, _ -> result.getTimestamp(1).toInstant() }))
        check(!row.createdAt.isAfter(now) && !Instant.parse(proof.verifiedAt).isAfter(now) && Instant.parse(proof.retainUntil).isAfter(now))
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
        phase.ownerDelete.checkCapacity(this, jdbc, ledger)
    }

    private fun retained() {
        phase.ownerDelete.requireRetained(this, jdbc)
        phase.requireTestVerifiedOwnerDeleteReload(original, graph, jdbc)
        graph.requireDeletion(jdbc)
    }

    override fun toString(): String = "ComplaintOwnerDeleteVerifiedReloadOperation(original-released-primary-only,redacted)"
    private enum class Stage { RETAINED, RECEIPT, PUBLICATION, RESERVATION, COUNTERS_READY, COUNTERS, RUN, COMPLETE }

    companion object {
        internal fun capture(store: JdbcComplaintOwnerDeleteStore, jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore,
            graph: TestOwnerDeleteLocalGraphV1, original: TestRunVerifiedOwnerDeleteV1): ComplaintOwnerDeleteVerifiedReloadOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.ownerDelete.requireOperation(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD)
                phase.requireTestVerifiedOwnerDeleteReload(original, graph, jdbc)
                return ComplaintOwnerDeleteVerifiedReloadOperation(phase, store, jdbc, graph, original).also {
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
