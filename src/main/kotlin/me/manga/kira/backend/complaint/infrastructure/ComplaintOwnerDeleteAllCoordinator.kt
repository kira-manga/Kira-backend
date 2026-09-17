package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1.OwnerDeleteAllReservation
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationDeletionPreflightPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext

/**
 * Connected, dormant lower composition: real preflight/release, local semantic admission, actual
 * fixed SQL and committed work. No HTTP result, provider permission or enabled-mode bean is minted.
 * The publication-aware path reserves shared J before new AUTH; the old lower API remains dormant.
 * Neither path supplies full-D/current runtime authority.
 */
internal class ComplaintOwnerDeleteAllCoordinator(
    private val ingress: ComplaintIngressAdmission,
    private val preflights: ComplaintInstallationDeletionPreflightPhaseExecutor,
    private val writes: ComplaintOwnerDeleteAllPhaseExecutor,
) {
    fun prepare(context: ComplaintIngressContext, candidate: InstallationDeletionCandidate): OwnerDeleteAllPreparation = prepare(context, candidate, null)

    fun prepareForPublication(
        context: ComplaintIngressContext,
        candidate: InstallationDeletionCandidate,
        publishers: OwnerDeleteAllJournalPublisherFactoryV1,
    ): OwnerDeleteAllPreparation = prepare(context, candidate, publishers)

    private fun prepare(
        context: ComplaintIngressContext,
        candidate: InstallationDeletionCandidate,
        publishers: OwnerDeleteAllJournalPublisherFactoryV1?,
    ): OwnerDeleteAllPreparation {
        ingress.requireLiveContext(context)
        check(candidate.installation.scope == ComplaintDataScope.LIVE)
        ingress.startOwnerDeleteAll(context)
        var publication: OwnerDeleteAllReservation? = null
        val result = runCatching {
            val observed = preflights.preflight(candidate)
            val prepared = when (observed) {
                is InstallationDeletionPreflightResult.Active -> {
                    preflights.requireOwned(observed)
                    val admitted = ingress.admitOwnerDeleteAll(context, observed)
                    // Admission is connection-free and constructs no SDK. Refusal must precede new durable AUTH.
                    publication = publishers?.reserve()
                    val work = writes.authorize(candidate, observed, admitted)
                    if (work !is CommittedOwnerDeleteAllWork.Prepared) {
                        publication?.close()
                        publication = null
                    }
                    OwnerDeleteAllPreparation.Durable(work, publication)
                }

                is InstallationDeletionPreflightResult.Authorized -> {
                    preflights.requireOwned(observed)
                    val work = writes.reload(candidate, observed)
                    if (work is CommittedOwnerDeleteAllWork.Prepared) publication = publishers?.reserve()
                    OwnerDeleteAllPreparation.Durable(work, publication)
                }

                is InstallationDeletionPreflightResult.Completed -> {
                    preflights.requireOwned(observed)
                    OwnerDeleteAllPreparation.Replay(observed)
                }

                is InstallationDeletionPreflightResult.Rejected -> OwnerDeleteAllPreparation.Rejected(observed)
            }
            ingress.requireResponseReady()
            prepared
        }
        if (result.isFailure) return withJournalPublicationCleanup({ result.getOrThrow() }) { publication?.close() }
        return result.getOrThrow()
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllCoordinator(dormant,no-runtime-authority)"
}

/** Internal composition output only, not an activated HTTP status or proof of published/physical erasure. */
internal sealed interface OwnerDeleteAllPreparation {
    class Durable(val work: CommittedOwnerDeleteAllWork, val publication: OwnerDeleteAllReservation? = null) : OwnerDeleteAllPreparation {
        override fun toString(): String = "OwnerDeleteAllPreparation.Durable(custody-only)"
    }

    class Replay(val comparison: InstallationDeletionPreflightResult.Completed) :
        OwnerDeleteAllPreparation,
        OwnerDeleteAllOutcome {
        override fun toString(): String = "OwnerDeleteAllPreparation.Replay(no-runtime-authority)"
    }

    class Rejected(val comparison: InstallationDeletionPreflightResult.Rejected) :
        OwnerDeleteAllPreparation,
        OwnerDeleteAllOutcome {
        override fun toString(): String = "OwnerDeleteAllPreparation.Rejected(redacted)"
    }
}
