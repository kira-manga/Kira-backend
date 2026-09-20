package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteAllExchange
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteAllResponse
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightRejection
import me.manga.kira.backend.complaint.domain.rejectInstallationHttp
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.OwnerDeleteAllJournalException

/** Dormant LIVE graph only; desired hashes and this adapter supply no full-D/current or activation authority. */
internal class ComplaintOwnerDeleteAllExchangeAdapter(
    private val ingress: ComplaintIngressAdmission,
    private val continuation: ComplaintOwnerDeleteAllContinuation,
) : ComplaintOwnerDeleteAllExchange {
    @Suppress("SwallowedException") // Only finite lower refusals are mapped, never a diagnostic graph or an inferred pending result.
    override fun deleteAll(context: ComplaintInstallationRequestContext, candidate: InstallationDeletionCandidate): ComplaintOwnerDeleteAllResponse {
        val request = context as? ComplaintIngressContext ?: unavailable()
        ingress.requireLiveContext(request)
        // The TEST deletion/test-run lifecycle is not implemented by this LIVE continuation.
        if (candidate.installation.scope != ComplaintDataScope.LIVE) unavailable()
        val outcome = try {
            continuation.complete(request, candidate)
        } catch (failure: PersistencePhaseException) {
            unavailable()
        } catch (failure: JournalPublicationExceptionV1) {
            unavailable()
        } catch (failure: OwnerDeleteAllJournalException) {
            unavailable()
        }
        ingress.requireLiveContext(request)
        ingress.requireResponseReady()
        return when (outcome) {
            is CommittedOwnerDeleteAllApplyV1 -> outcome

            is BoundOwnerDeleteAllReplayV1 -> outcome

            is OwnerDeleteAllReconciliationPendingV1 -> outcome

            is OwnerDeleteAllPreparation.Rejected -> rejectInstallationHttp(rejection(outcome.comparison.reason))

            // A raw Completed comparison, publication label or caller output view cannot select HTTP success.
            else -> unavailable()
        }
    }

    private fun unavailable(): Nothing {
        ingress.requireResponseReady()
        rejectInstallationHttp(ComplaintInstallationHttpFailure.UNAVAILABLE)
    }

    private fun rejection(reason: InstallationDeletionPreflightRejection): ComplaintInstallationHttpFailure = when (reason) {
        InstallationDeletionPreflightRejection.INSTALLATION_NOT_FOUND -> ComplaintInstallationHttpFailure.INSTALLATION_NOT_FOUND
        InstallationDeletionPreflightRejection.INSTALLATION_CREDENTIAL_REJECTED -> ComplaintInstallationHttpFailure.INSTALLATION_CREDENTIAL_REJECTED
        InstallationDeletionPreflightRejection.INSTALLATION_SCOPE_MISMATCH -> ComplaintInstallationHttpFailure.INSTALLATION_SCOPE_MISMATCH
        InstallationDeletionPreflightRejection.INSTALLATION_SCOPE_RETIRED -> ComplaintInstallationHttpFailure.INSTALLATION_SCOPE_RETIRED
        InstallationDeletionPreflightRejection.INSTALLATION_DELETION_PENDING -> ComplaintInstallationHttpFailure.INSTALLATION_DELETION_PENDING
        InstallationDeletionPreflightRejection.INSTALLATION_DELETED -> ComplaintInstallationHttpFailure.INSTALLATION_DELETED
        InstallationDeletionPreflightRejection.INSTALLATION_RETIRED -> ComplaintInstallationHttpFailure.INSTALLATION_RETIRED
        InstallationDeletionPreflightRejection.IDEMPOTENCY_KEY_REUSED -> ComplaintInstallationHttpFailure.IDEMPOTENCY_KEY_REUSED
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllExchangeAdapter(dormant,no-runtime-authority)"
}
