package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.atomic.AtomicReference

/** One logical rotation per genuine campaign. This does not own/refund the nonpooled physical slot. */
internal class CatalogEpochRotationCustodyV1(private val coordinator: CatalogCoordinatorPersistence) {
    private val active = AtomicReference<CatalogEpochRotationAttemptV1?>()
    private val inFlight = AtomicReference<CatalogEpochRotationAttemptV1?>()
    private val lastCampaign = AtomicReference<CatalogCoordinatorLeaseCampaignV1?>()

    @Suppress("TooGenericExceptionCaught")
    internal fun begin(campaign: CatalogCoordinatorLeaseCampaignV1, jdbc: JdbcTemplate, path: PersistencePhasePath): CatalogEpochRotationAttemptV1 {
        try {
            requireConnectionFree()
            requireCampaign(campaign, jdbc)
            check(path === PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST || path === PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME)
            val prior = active.get()
            if (prior != null && prior.campaign === campaign) {
                // Discovery of EMPTY may continue as a new request, but it cannot buy another J allowance.
                if (path !== PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST || !inFlight.compareAndSet(null, prior)) refuse()
                try {
                    prior.continueAfterNoPending(jdbc)
                    return prior
                } catch (problem: Throwable) {
                    prior.abort()
                    prior.finishCall()
                    throw problem
                }
            }
            // A genuinely distinct current campaign means the old local campaign is no longer active.
            if (prior != null && !coordinator.leaseCustody.isActive(prior.campaign) && inFlight.get() !== prior) {
                prior.abort()
                active.compareAndSet(prior, null)
            }
            if (active.get() != null || lastCampaign.get() === campaign) refuse()
            val budget = campaign.binding.startEpochRotationBudget() // Before nonce, buffers, entry or SQL; never restarted.
            val attempt = CatalogEpochRotationAttemptV1(this, coordinator, campaign, jdbc, path, budget)
            if (!active.compareAndSet(null, attempt)) {
                attempt.abort()
                refuse()
            }
            if (!inFlight.compareAndSet(null, attempt)) {
                attempt.abort()
                active.compareAndSet(attempt, null)
                refuse()
            }
            lastCampaign.set(campaign)
            try {
                attempt.requireRunning()
                return attempt
            } catch (problem: Throwable) {
                attempt.abort()
                attempt.finishCall()
                throw problem
            }
        } catch (problem: Throwable) {
            campaign.close()
            throw problem
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun capture(attempt: CatalogEpochRotationAttemptV1, jdbc: JdbcTemplate) {
        try {
            requireConnectionFree()
            requireCampaign(attempt.campaign, jdbc)
            if (!hasRetainedAttempt(attempt, jdbc) || !inFlight.compareAndSet(null, attempt)) refuse()
            try {
                attempt.beginCapture()
            } catch (problem: Throwable) {
                attempt.abort()
                attempt.finishCall()
                throw problem
            }
        } catch (problem: Throwable) {
            attempt.abort()
            throw problem
        }
    }

    private fun hasRetainedAttempt(attempt: CatalogEpochRotationAttemptV1, jdbc: JdbcTemplate): Boolean =
        attempt.custody === this && attempt.jdbc === jdbc && active.get() === attempt

    private fun requireCampaign(campaign: CatalogCoordinatorLeaseCampaignV1, jdbc: JdbcTemplate) {
        if (!hasOriginalCampaignResources(campaign, jdbc) || campaign.binding.coordinator !== coordinator ||
            !coordinator.leaseCustody.isActive(campaign)
        ) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        campaign.binding.requirePersistence(coordinator.ownership, jdbc)
    }

    private fun hasOriginalCampaignResources(campaign: CatalogCoordinatorLeaseCampaignV1, jdbc: JdbcTemplate): Boolean =
        coordinator.epochRotationCustody === this && campaign.custody === coordinator.leaseCustody && campaign.jdbc === jdbc

    internal fun ownsCall(attempt: CatalogEpochRotationAttemptV1): Boolean =
        coordinator.epochRotationCustody === this && active.get() === attempt && inFlight.get() === attempt

    /** Local call retirement only. Failed JDBC/native custody remains with the original core owners. */
    internal fun endCall(attempt: CatalogEpochRotationAttemptV1) {
        if (attempt.terminal()) active.compareAndSet(attempt, null)
        inFlight.compareAndSet(attempt, null)
    }

    override fun toString(): String = "CatalogEpochRotationCustodyV1(original-coordinator,no-physical-refund-authority)"

    private fun refuse(): Nothing = throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
}
