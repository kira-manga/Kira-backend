package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteTuple
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteObservation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteReadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadRows
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadIdentity
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteReceiptStore

/** One named ordinary phase. Nothing is released until commit and physical holder cleanup succeed. */
internal class ComplaintAdminDeleteReadPhaseExecutor(private val ownership: PersistencePhaseOwnership, private val store: JdbcComplaintAdminDeleteReceiptStore) {
    @Suppress("TooGenericExceptionCaught")
    fun authenticate(identity: ComplaintAdminReadIdentity): ComplaintAdminReadRows {
        val phase = ownership.enterComplaintAdminReadAuthentication()
        var operation: ComplaintAdminReadOperation? = null
        try { phase.begin(); operation = store.authenticate(identity); phase.commit() }
        catch (problem: Throwable) { phase.recordFailure(problem) }
        finally { phase.finish() }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }
    fun requirePreflight(observation: ComplaintAdminDeleteObservation, identity: ComplaintAdminReadIdentity, tuple: ComplaintAdminDeleteTuple) =
        store.requirePreflight(observation, identity, tuple)
    @Suppress("TooGenericExceptionCaught")
    fun preflight(identity: ComplaintAdminReadIdentity, tuple: ComplaintAdminDeleteTuple): ComplaintAdminDeleteObservation {
        val phase = ownership.enterComplaintAdminDeletePreflight()
        var operation: ComplaintAdminDeleteReadOperation? = null
        try { phase.begin(); operation = store.preflight(identity, tuple); phase.commit() }
        catch (problem: Throwable) { phase.recordFailure(problem) }
        finally { phase.finish() }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }
}
