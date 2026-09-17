package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1.OwnerDeleteAllReservation
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllApplyPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllVerificationPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext

/**
 * One synchronous dormant authenticated attempt, not an HTTP handler or background recovery worker.
 * Uses the explicit shared in-process J owner BEFORE new AUTH and closes it before VERIFY/APPLY.
 * Full-D/current authority, stronger horizon, deployment aggregate caps and hard-native deadline
 * qualification remain prerequisites for live wiring; local J accounting supplies none of them.
 */
internal class ComplaintOwnerDeleteAllContinuation(
    private val ingress: ComplaintIngressAdmission,
    private val preparation: ComplaintOwnerDeleteAllCoordinator,
    private val publishers: OwnerDeleteAllJournalPublisherFactoryV1,
    private val verificationStore: JdbcComplaintOwnerDeleteAllVerificationStore,
    private val verification: ComplaintOwnerDeleteAllVerificationPhaseExecutor,
    private val application: ComplaintOwnerDeleteAllApplyPhaseExecutor,
) {
    fun complete(context: ComplaintIngressContext, candidate: InstallationDeletionCandidate): OwnerDeleteAllOutcome {
        ingress.requireLiveContext(context)
        val prepared = preparation.prepareForPublication(context, candidate, publishers)
        val result = when (prepared) {
            is OwnerDeleteAllPreparation.Durable -> withJournalPublicationCleanup(
                { continueDurable(context, prepared.work, prepared.publication) },
                { prepared.publication?.close() },
            )
            is OwnerDeleteAllPreparation.Replay -> prepared
            is OwnerDeleteAllPreparation.Rejected -> prepared
        }
        ingress.requireLiveContext(context)
        ingress.requireResponseReady()
        return result
    }

    private fun continueDurable(
        context: ComplaintIngressContext,
        work: CommittedOwnerDeleteAllWork,
        publication: OwnerDeleteAllReservation?,
    ): CommittedOwnerDeleteAllApplyV1 {
        ingress.requireLiveContext(context)
        val proof = when (work) {
            is CommittedOwnerDeleteAllWork.Prepared -> {
                // The genuine authorization/reload has already committed and released. Even a
                // valid readback cannot advance to VERIFY if this actual owned lifetime's close fails.
                val readback = checkNotNull(publication).publish(work)
                ingress.requireLiveContext(context)
                verification.verify(readback)
            }

            is CommittedOwnerDeleteAllWork.RecordedVerified -> verificationStore.resume(work)
            else -> throw PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
        }
        ingress.requireLiveContext(context)
        return application.apply(work, proof)
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllContinuation(dormant,no-runtime-authority)"
}

/** Outcome discrimination only: the original privately owned result objects are returned unchanged. */
internal sealed interface OwnerDeleteAllOutcome
