package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPosition
import me.manga.kira.backend.complaint.domain.ComplaintAdminSearchQuery
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadIdentity
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadRows
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminReadStore
import java.util.UUID

/** Named ordinary observation phases, never a generic callback or separate transaction/pool owner. */
internal class ComplaintAdminReadPhaseExecutor(private val ownership: PersistencePhaseOwnership, private val store: JdbcComplaintAdminReadStore) {
    @Suppress("TooGenericExceptionCaught")
    fun authenticate(identity: ComplaintAdminReadIdentity): ComplaintAdminReadRows {
        val phase = ownership.enterComplaintAdminReadAuthentication()
        var operation: ComplaintAdminReadOperation? = null
        try {
            phase.begin()
            operation = store.authenticate(identity)
            phase.commit()
        } catch (failure: Throwable) {
            phase.recordFailure(failure)
        } finally {
            phase.finish()
        }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }

    @Suppress("TooGenericExceptionCaught")
    fun search(identity: ComplaintAdminReadIdentity, query: ComplaintAdminSearchQuery, position: ComplaintAdminReadPosition?): ComplaintAdminReadRows {
        val phase = ownership.enterComplaintAdminSearch()
        var operation: ComplaintAdminReadOperation? = null
        try {
            phase.begin()
            operation = store.search(identity, query, position)
            phase.commit()
        } catch (failure: Throwable) {
            phase.recordFailure(failure)
        } finally {
            phase.finish()
        }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }

    @Suppress("TooGenericExceptionCaught")
    fun detail(identity: ComplaintAdminReadIdentity, id: UUID): ComplaintAdminReadRows {
        val phase = ownership.enterComplaintAdminDetail()
        var operation: ComplaintAdminReadOperation? = null
        try {
            phase.begin()
            operation = store.detail(identity, id)
            phase.commit()
        } catch (failure: Throwable) {
            phase.recordFailure(failure)
        } finally {
            phase.finish()
        }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }

    override fun toString(): String = "ComplaintAdminReadPhaseExecutor(TEST-only,observations)"
}
