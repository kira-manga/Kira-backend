package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCutoffAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseBindingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCampaignV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseReceiptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogCoordinatorLeaseStore
import org.springframework.jdbc.core.JdbcTemplate

/** Fixed dormant lease transitions only. No callback/clock/duration, heartbeat, desired-state installer or data writer. */
internal class ComplaintCoordinatorLeasePersistencePhaseExecutor(private val coordinator: CatalogCoordinatorPersistence, private val jdbc: JdbcTemplate) {
    private val ownership = coordinator.ownership
    private val manager = coordinator.manager
    private val source = coordinator.dataSource
    private val custody = coordinator.leaseCustody

    @Suppress("TooGenericExceptionCaught")
    fun acquire(binding: CatalogCoordinatorLeaseBindingV1): CatalogCoordinatorLeaseAcquisitionV1 {
        var attempt: CatalogCoordinatorLeaseCustodyV1.Attempt? = null
        try {
            requireEntryResources()
            val retained = custody.acquire(binding, jdbc)
            attempt = retained
            try {
                return CatalogCoordinatorLeaseAcquisitionV1.issuedBy(persist(retained))
            } finally {
                retained.finish()
            }
        } catch (problem: Throwable) {
            throw attempt?.failure(problem) ?: bounded(problem)
        }
    }

    fun renew(campaign: CatalogCoordinatorLeaseCampaignV1): CatalogCoordinatorLeaseReceiptV1 = renew(campaign, null)

    internal fun renewForCutoff(original: CatalogCutoffAttemptV1): CatalogCoordinatorLeaseReceiptV1 {
        original.requirePersistence(ownership, jdbc, PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW)
        return renew(original.campaign, original).also { original.requireRunning() }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun renew(campaign: CatalogCoordinatorLeaseCampaignV1, original: CatalogCutoffAttemptV1?): CatalogCoordinatorLeaseReceiptV1 {
        var attempt: CatalogCoordinatorLeaseCustodyV1.Attempt? = null
        try {
            requireEntryResources()
            val retained = custody.renew(campaign, jdbc)
            attempt = retained
            try {
                return persist(retained, original).renewedReceipt()
            } finally {
                retained.finish()
            }
        } catch (problem: Throwable) {
            campaign.close() // Includes nested/cold/foreign/changed-resource refusal BEFORE an Attempt even exists.
            throw attempt?.failure(problem) ?: bounded(problem)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun relinquish(campaign: CatalogCoordinatorLeaseCampaignV1): CatalogCoordinatorLeaseReceiptV1 {
        campaign.close() // Locally stop first; SQL is a single authority-reducing attempt against this exact owner/token/B.
        var attempt: CatalogCoordinatorLeaseCustodyV1.Attempt? = null
        try {
            requireEntryResources()
            val retained = custody.relinquish(campaign, jdbc)
            attempt = retained
            try {
                return persist(retained).relinquishedReceipt()
            } finally {
                retained.finish()
            }
        } catch (problem: Throwable) {
            throw attempt?.failure(problem) ?: bounded(problem)
        }
    }

    private fun requireEntryResources() {
        requireConnectionFree()
        coordinator.requireResources()
        if (!hasOriginalCoordinatorResources() || coordinator.leaseCustody !== custody || jdbc.dataSource !== source) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    private fun hasOriginalCoordinatorResources(): Boolean =
        coordinator.ownership === ownership && coordinator.manager === manager && coordinator.dataSource === source

    @Suppress("TooGenericExceptionCaught")
    private fun persist(attempt: CatalogCoordinatorLeaseCustodyV1.Attempt, original: CatalogCutoffAttemptV1? = null): CatalogCoordinatorLeaseOperation {
        attempt.requireRunning(jdbc)
        val store = JdbcCatalogCoordinatorLeaseStore(jdbc)
        val phase = when (attempt.path) {
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE -> ownership.enterComplaintCoordinatorLeaseAcquire()
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW -> original?.let(ownership::enterComplaintCutoffRenew)
                ?: ownership.enterComplaintCoordinatorLeaseRenew()
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH -> ownership.enterComplaintCoordinatorLeaseRelinquish()
            else -> error("Unsupported coordinator lease phase.")
        }
        var completed: CatalogCoordinatorLeaseOperation? = null
        try {
            phase.begin() // Limits and original holder only: these three named paths deliberately have NO epoch fence.
            completed = when (attempt.path) {
                PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE -> store.acquire(attempt)
                PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW -> store.renew(attempt)
                PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH -> store.relinquish(attempt)
                else -> error("Unsupported coordinator lease phase.")
            }
            attempt.requireRunning(jdbc)
            phase.commit()
        } catch (problem: Throwable) {
            attempt.abort()
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return completed ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "ComplaintCoordinatorLeasePersistencePhaseExecutor(fixed30s,no-heartbeat-or-data-work)"

    private fun bounded(problem: Throwable): PersistencePhaseException =
        problem as? PersistencePhaseException ?: PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
}
