package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDetailReadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDetailRow
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerHistoryIdentity
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDetailStore
import java.util.UUID

/** One fixed read on the existing ordinary owner, not a generic callback or a second pool/transaction manager. */
internal class ComplaintOwnerDetailPhaseExecutor(private val ownership: PersistencePhaseOwnership, private val store: JdbcComplaintOwnerDetailStore) {
    @Suppress("TooGenericExceptionCaught")
    fun read(identity: ComplaintOwnerHistoryIdentity, id: UUID): ComplaintOwnerDetailRow {
        val phase = ownership.enterComplaintOwnerDetail()
        var operation: ComplaintOwnerDetailReadOperation? = null
        try {
            phase.begin()
            operation = store.read(identity, id)
            phase.commit()
        } catch (failure: Throwable) {
            phase.recordFailure(failure)
        } finally {
            phase.finish()
        }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }

    override fun toString(): String = "ComplaintOwnerDetailPhaseExecutor(dormant,read-only)"
}
