package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalPublisherFactoryV1
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.atomic.AtomicReference

/**
 * Dormant step1 only: resolve every committed new-backend <=cutoff row and derive a complete manifest.
 * Genuine CAPTURED/full-B discovery and final validation use the original coordinator. This is NOT a
 * seal producer, scanner, checkpoint, activation or W06/legacy import. No slot is cleared or advanced.
 */
internal class CatalogCutoffPublicationsV1 internal constructor(private val coordinator: CatalogCoordinatorPersistence, private val jdbc: JdbcTemplate) {
    private val ownership = coordinator.ownership
    private val manager = coordinator.manager
    private val source = coordinator.dataSource
    private val active = AtomicReference<CatalogCutoffAttemptV1?>()
    private val lanes = AtomicReference<JournalPublicationLanesV1?>()
    private val persistence = CatalogCutoffPersistenceExecutorV1(coordinator, jdbc)

    @Suppress("TooGenericExceptionCaught")
    internal fun resolve(campaign: CatalogCoordinatorLeaseCampaignV1, ordinaryFactory: OwnerDeleteAllJournalPublisherFactoryV1): CapturedCutoffManifestV1 {
        var attempt: CatalogCutoffAttemptV1? = null
        try {
            requireResources()
            check(campaign.binding.coordinator === coordinator && campaign.jdbc === jdbc && coordinator.leaseCustody.isActive(campaign))
            // This is a NEW seal budget, not rotation's previously consumed allowance.
            val originalBudget = campaign.binding.startEpochSealBudget()
            campaign.claimCutoffResolution()
            val retained = CatalogCutoffAttemptV1(this, campaign, jdbc, originalBudget)
            attempt = retained
            check(active.compareAndSet(null, retained))
            val shared = ordinaryFactory.cutoffLanes(retained.routing)
            lanes.compareAndSet(null, shared)
            check(lanes.get() === shared) // Factory replacement cannot silently reset the actual shared J owner.
            renew(retained)
            retained.acceptControl(persistence.control(retained))
            resolveRows(retained, ordinaryFactory)
            retained.beginManifest()
            manifestPass(retained)
            retained.beginSecondPass()
            manifestPass(retained)
            retained.finishManifest()
            renew(retained)
            val final = persistence.control(retained)
            retained.acceptControl(final)
            return CapturedCutoffManifestV1.fromReleased(final)
        } catch (problem: Throwable) {
            campaign.close()
            attempt?.abort()
            throw boundedEpochRotationFailure(problem)
        } finally {
            attempt?.let {
                try {
                    it.finish()
                } catch (problem: Throwable) {
                    it.abort()
                    throw boundedEpochRotationFailure(problem)
                } finally {
                    active.compareAndSet(it, null)
                }
            }
        }
    }

    private fun resolveRows(attempt: CatalogCutoffAttemptV1, factory: OwnerDeleteAllJournalPublisherFactoryV1) {
        do {
            renew(attempt)
            val page = persistence.page(attempt)
            val rows = attempt.acceptPage(page)
            for (row in rows) {
                val event = row.event(attempt)
                if (row.state == "PREPARED") {
                    val work = ReleasedCutoffPublicationV1.issuedBy(page, row)
                    renew(attempt)
                    val readback = factory.reserveCutoff().publishCutoff(work, attempt.budget)
                    // Only this exact historical immutable evidence CAS may finish after leadership loss.
                    persistence.verify(attempt, work.capture(readback))
                    renew(attempt) // No next work or successful campaign handoff without actual current DB-time lease.
                } else {
                    row.firstProof(event, attempt.routing) // VERIFIED and APPLIED preserve their original proof.
                }
                attempt.recordResolved(row)
            }
        } while (!attempt.endPage())
    }

    private fun manifestPass(attempt: CatalogCutoffAttemptV1) {
        do {
            renew(attempt)
            val page = persistence.page(attempt)
            attempt.acceptPage(page).forEach(attempt::recordManifest)
        } while (!attempt.endPage())
    }

    private fun renew(attempt: CatalogCutoffAttemptV1) {
        requireConnectionFree()
        attempt.requireRunning()
        coordinator.lease.renewForCutoff(attempt)
        attempt.requireRenewalCadence()
    }

    internal fun owns(attempt: CatalogCutoffAttemptV1): Boolean = active.get() === attempt && coordinator.cutoffPublications === this

    private fun requireResources() {
        requireConnectionFree()
        coordinator.requireResources()
        if (coordinator.cutoffPublications !== this || coordinator.ownership !== ownership || coordinator.manager !== manager ||
            coordinator.dataSource !== source || jdbc.dataSource !== source
        ) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    override fun toString(): String = "CatalogCutoffPublicationsV1(dormant,bounded-receiptless-resolution,NO-seal-checkpoint-or-activation)"
}

/** Historical completed manifest only. It cannot supply current fencing, seal evidence or slot-replacement permission. */
internal class CapturedCutoffManifestV1 private constructor(slot: CatalogEpochRotationSlotV1, val eventCount: Long, val eventManifestSha256: String) {
    val rotationId = slot.id
    val sequence = slot.sequence
    val writer = slot.writer
    val epochStartInclusive = 1L
    val epochEndInclusive = slot.epochBefore

    override fun toString(): String = "CapturedCutoffManifestV1(historical,redacted,NOT-a-verified-seal)"

    companion object {
        internal fun fromReleased(operation: CatalogCutoffPersistenceOperationV1): CapturedCutoffManifestV1 {
            val (slot, manifest) = operation.attempt.result(operation)
            return CapturedCutoffManifestV1(slot, manifest.eventCount, manifest.eventManifestSha256)
        }
    }
}
