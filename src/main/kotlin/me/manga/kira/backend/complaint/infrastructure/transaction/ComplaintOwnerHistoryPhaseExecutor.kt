package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryPosition
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerHistoryIdentity
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerHistoryReadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerHistoryRows
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerHistoryStore

/** Fixed read operations, never an application callback or a second transaction/pool owner. */
internal class ComplaintOwnerHistoryPhaseExecutor(private val ownership: PersistencePhaseOwnership, private val store: JdbcComplaintOwnerHistoryStore) {
    @Suppress("TooGenericExceptionCaught")
    fun authenticate(identity: ComplaintOwnerHistoryIdentity): Boolean {
        val phase = ownership.enterComplaintOwnerHistoryAuthentication()
        var operation: ComplaintOwnerHistoryReadOperation? = null
        try {
            phase.begin()
            operation = store.authenticate(identity)
            phase.commit()
        } catch (failure: Throwable) {
            phase.recordFailure(failure)
        } finally {
            phase.finish()
        }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result.authorized
    }

    @Suppress("TooGenericExceptionCaught")
    fun page(identity: ComplaintOwnerHistoryIdentity, position: ComplaintOwnerHistoryPosition?, limit: Int): ComplaintOwnerHistoryRows {
        val phase = ownership.enterComplaintOwnerHistoryPage()
        var operation: ComplaintOwnerHistoryReadOperation? = null
        try {
            phase.begin()
            operation = store.page(identity, position, limit)
            phase.commit()
        } catch (failure: Throwable) {
            phase.recordFailure(failure)
        } finally {
            phase.finish()
        }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }

    override fun toString(): String = "ComplaintOwnerHistoryPhaseExecutor(dormant,read-only)"
}
