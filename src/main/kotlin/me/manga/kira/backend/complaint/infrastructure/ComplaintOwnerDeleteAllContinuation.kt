package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllApplyPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllVerificationPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext

/**
 * One synchronous dormant authenticated attempt, not an HTTP handler or background recovery worker.
 * The actual shared J lane, full-D/current runtime authority and stronger horizon remain prerequisites
 * for any live wiring, with real journal-lane admission BEFORE new authorization, not just at
 * publisher open. No local busy flag, result or matching descriptor supplies that permission.
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
        val prepared = preparation.prepare(context, candidate)
        val result = when (prepared) {
            is OwnerDeleteAllPreparation.Durable -> continueDurable(context, prepared.work)
            is OwnerDeleteAllPreparation.Replay -> prepared
            is OwnerDeleteAllPreparation.Rejected -> prepared
        }
        ingress.requireLiveContext(context)
        ingress.requireResponseReady()
        return result
    }

    private fun continueDurable(context: ComplaintIngressContext, work: CommittedOwnerDeleteAllWork): CommittedOwnerDeleteAllApplyV1 {
        ingress.requireLiveContext(context)
        val proof = when (work) {
            is CommittedOwnerDeleteAllWork.Prepared -> {
                // The genuine authorization/reload has already committed and released. Even a
                // valid readback cannot advance to VERIFY if this concrete publisher's close fails.
                val publisher = publishers.open()
                val readback = withJournalPublicationCleanup({ publisher.publish(work) }, publisher::close)
                ingress.requireLiveContext(context)
                verification.verify(readback)
            }

            is CommittedOwnerDeleteAllWork.RecordedVerified -> verificationStore.resume(work)
        }
        ingress.requireLiveContext(context)
        return application.apply(work, proof)
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllContinuation(dormant,no-runtime-authority)"
}

/** Outcome discrimination only: the original privately owned result objects are returned unchanged. */
internal sealed interface OwnerDeleteAllOutcome
