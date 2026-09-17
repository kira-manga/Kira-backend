package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.security.ComplaintAdmittedOwnerDeleteAll

/** Two fixed named paths on the original deletion holder. No lambda, pool fallback or ambient transaction. */
internal class ComplaintOwnerDeleteAllPhaseExecutor(
    private val ownership: PersistencePhaseOwnership,
    private val store: JdbcComplaintOwnerDeleteAllStore,
    private val preflights: ComplaintInstallationDeletionPreflightPhaseExecutor,
) {
    @Suppress("TooGenericExceptionCaught")
    fun authorize(
        candidate: InstallationDeletionCandidate,
        preflight: InstallationDeletionPreflightResult.Active,
        admission: ComplaintAdmittedOwnerDeleteAll,
    ): CommittedOwnerDeleteAllWork {
        preflights.requireOwned(preflight)
        ComplaintOwnerDeleteAllOperation.requireTuple(candidate, preflight)
        val phase = ownership.enterComplaintOwnerDeleteAllAuthorize(admission, preflight)
        var operation: ComplaintOwnerDeleteAllOperation? = null
        try {
            phase.ownerDeleteAll.bindAuthorize(admission)
            phase.begin()
            operation = store.authorize(candidate, preflight)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }

    @Suppress("TooGenericExceptionCaught")
    fun reload(candidate: InstallationDeletionCandidate, preflight: InstallationDeletionPreflightResult.Authorized): CommittedOwnerDeleteAllWork {
        preflights.requireOwned(preflight)
        ComplaintOwnerDeleteAllOperation.requireTuple(candidate, preflight)
        val phase = ownership.enterComplaintOwnerDeleteAllReload()
        var operation: ComplaintOwnerDeleteAllOperation? = null
        try {
            phase.begin()
            operation = store.reload(candidate, preflight)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllPhaseExecutor(dormant,no-runtime-authority)"
}
