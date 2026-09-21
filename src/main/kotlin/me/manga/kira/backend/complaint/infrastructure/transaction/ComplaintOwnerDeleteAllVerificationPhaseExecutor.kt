package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllVerificationV1
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllVerificationOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllVerificationStore
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllVerificationInputV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalReadbackV1

/** One short named path on the existing deletion holder, after connection-free genuine readback. */
internal class ComplaintOwnerDeleteAllVerificationPhaseExecutor(
    private val ownership: PersistencePhaseOwnership,
    private val store: JdbcComplaintOwnerDeleteAllVerificationStore,
) {
    fun verify(readback: OwnerDeleteAllJournalReadbackV1): CommittedOwnerDeleteAllVerificationV1 = execute(store.capture(readback))

    fun verify(readback: TestOwnerDeleteJournalReadbackV1): CommittedOwnerDeleteAllVerificationV1 = execute(store.capture(readback))

    fun verify(work: CommittedOwnerDeleteAllWork.Prepared, readback: TestOwnerDeleteJournalReadbackV1,
        lane: JournalPublicationLanesV1.TestOwnerDeleteAllReservation): CommittedOwnerDeleteAllVerificationV1 {
        val original = checkNotNull(store.initialDeletion)
        original.requireEntry(ownership, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY)
        return execute(store.captureInitial(work, readback, lane))
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execute(captured: OwnerDeleteAllVerificationInputV1): CommittedOwnerDeleteAllVerificationV1 {
        val phase = ownership.enterComplaintOwnerDeleteAllVerify()
        var operation: ComplaintOwnerDeleteAllVerificationOperation? = null
        try {
            store.initialDeletion?.let { phase.bindInitialAllDeleteVerify(it, store, captured) }
            phase.begin()
            operation = store.verify(captured)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        // UNKNOWN COMMIT or unresolved original-holder cleanup cannot release a success result.
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllVerificationPhaseExecutor(dormant,no-apply-authority)"
}
