package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightTuple
import me.manga.kira.backend.complaint.infrastructure.BoundOwnerDeleteAllReplayV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationDeletionPreflightOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintInstallationDeletionPreflightStore
import me.manga.kira.backend.security.OwnerDeleteAllJournalCodecV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting

/** One ordinary read-only entry on the existing owner, before semantic admission or any deletion permit/fence. */
internal class ComplaintInstallationDeletionPreflightPhaseExecutor(
    private val ownership: PersistencePhaseOwnership,
    private val store: JdbcComplaintInstallationDeletionPreflightStore,
) {
    @Suppress("TooGenericExceptionCaught")
    fun preflight(candidate: InstallationDeletionCandidate): InstallationDeletionPreflightResult {
        val phase = ownership.enterComplaintInstallationDeletionPreflight()
        var operation: ComplaintInstallationDeletionPreflightOperation? = null
        try {
            phase.begin()
            operation = store.read(candidate)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        // The retained operation requires this phase's known commit AND actual release, not a returned SQL flag.
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }

    /**
     * Connection-free handoff check for future fixed authorization/resumption consumers. This is NOT
     * semantic admission or a locked-state check; it cannot be used as a write/apply capability.
     */
    fun requireOwned(comparison: InstallationDeletionPreflightTuple) {
        requireConnectionFree()
        store.requireOwned(comparison, ownership.installationDeletionIdentity)
    }

    /**
     * Local same-J binding of this owner's original committed/released Completed snapshot only.
     * No new phase, semantic/deletion/J admission, provider call, mutation or current authority.
     */
    fun bindReplay(
        completed: InstallationDeletionPreflightResult.Completed,
        routing: VersionBoundComplaintJournalRouting,
        codec: OwnerDeleteAllJournalCodecV1,
    ): BoundOwnerDeleteAllReplayV1 {
        requireConnectionFree()
        return store.bindReplay(completed, ownership.installationDeletionIdentity, routing, codec)
    }

    override fun toString(): String = "ComplaintInstallationDeletionPreflightPhaseExecutor(read-only)"
}
