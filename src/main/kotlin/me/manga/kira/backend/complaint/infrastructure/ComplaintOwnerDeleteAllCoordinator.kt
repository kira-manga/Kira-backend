package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationDeletionPreflightPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext

/**
 * Connected, dormant lower composition: real preflight/release, local semantic admission, actual
 * fixed SQL and committed work. No HTTP result, provider permission or enabled-mode bean is minted.
 * Full-D/current runtime authority and the separate publication lane remain genuinely unavailable.
 */
internal class ComplaintOwnerDeleteAllCoordinator(
    private val ingress: ComplaintIngressAdmission,
    private val preflights: ComplaintInstallationDeletionPreflightPhaseExecutor,
    private val writes: ComplaintOwnerDeleteAllPhaseExecutor,
) {
    fun prepare(context: ComplaintIngressContext, candidate: InstallationDeletionCandidate): OwnerDeleteAllPreparation {
        ingress.requireLiveContext(context)
        check(candidate.installation.scope == ComplaintDataScope.LIVE)
        ingress.startOwnerDeleteAll(context)
        val observed = preflights.preflight(candidate)
        val result = when (observed) {
            is InstallationDeletionPreflightResult.Active -> {
                preflights.requireOwned(observed)
                val admitted = ingress.admitOwnerDeleteAll(context, observed)
                OwnerDeleteAllPreparation.Durable(writes.authorize(candidate, observed, admitted))
            }

            is InstallationDeletionPreflightResult.Authorized -> {
                preflights.requireOwned(observed)
                OwnerDeleteAllPreparation.Durable(writes.reload(candidate, observed))
            }

            is InstallationDeletionPreflightResult.Completed -> {
                preflights.requireOwned(observed)
                OwnerDeleteAllPreparation.Replay(observed)
            }

            is InstallationDeletionPreflightResult.Rejected -> OwnerDeleteAllPreparation.Rejected(observed)
        }
        ingress.requireResponseReady()
        return result
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllCoordinator(dormant,no-runtime-authority)"
}

/** Internal composition output only, not an activated HTTP status or proof of published/physical erasure. */
internal sealed interface OwnerDeleteAllPreparation {
    class Durable(val work: CommittedOwnerDeleteAllWork) : OwnerDeleteAllPreparation {
        override fun toString(): String = "OwnerDeleteAllPreparation.Durable(custody-only)"
    }

    class Replay(val comparison: InstallationDeletionPreflightResult.Completed) : OwnerDeleteAllPreparation, OwnerDeleteAllOutcome {
        override fun toString(): String = "OwnerDeleteAllPreparation.Replay(no-runtime-authority)"
    }

    class Rejected(val comparison: InstallationDeletionPreflightResult.Rejected) : OwnerDeleteAllPreparation, OwnerDeleteAllOutcome {
        override fun toString(): String = "OwnerDeleteAllPreparation.Rejected(redacted)"
    }
}
