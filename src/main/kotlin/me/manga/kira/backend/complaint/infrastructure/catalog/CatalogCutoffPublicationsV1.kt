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
 * Dormant fixed cutoff resolution, canonical-only PREPARED or an actual held STS/KMS continuation.
 * Genuine CAPTURED/full-B discovery, final validation and canonical storage use the original coordinator.
 * No durable wire, dispatch, verification, scan, checkpoint, activation or W06/legacy import. No slot is cleared or advanced.
 */
internal class CatalogCutoffPublicationsV1 internal constructor(private val coordinator: CatalogCoordinatorPersistence, private val jdbc: JdbcTemplate) {
    private val ownership = coordinator.ownership
    private val manager = coordinator.manager
    private val source = coordinator.dataSource
    private val active = AtomicReference<CatalogCutoffAttemptV1?>()
    private val lanes = AtomicReference<JournalPublicationLanesV1?>()
    private val persistence = CatalogCutoffPersistenceExecutorV1(coordinator, jdbc)

    internal fun resolve(campaign: CatalogCoordinatorLeaseCampaignV1, ordinaryFactory: OwnerDeleteAllJournalPublisherFactoryV1): CapturedCutoffManifestV1 =
        run(campaign, ordinaryFactory, CatalogCutoffCompletionV1.MANIFEST).manifest

    /** No supplied manifest/token/key/SQL/ready flag. The actual resolver continues before its original attempt finishes. */
    internal fun prepareCapturedLive(
        campaign: CatalogCoordinatorLeaseCampaignV1,
        ordinaryFactory: OwnerDeleteAllJournalPublisherFactoryV1,
    ): PreparedEpochSealV1 = checkNotNull(run(campaign, ordinaryFactory, CatalogCutoffCompletionV1.CANONICAL_PREPARED).prepared)

    /** The returned operation is still charged to this issuer and the original caller/J until actual close. */
    internal fun openPreparedSeal(
        campaign: CatalogCoordinatorLeaseCampaignV1,
        ordinaryFactory: OwnerDeleteAllJournalPublisherFactoryV1,
    ): CatalogEpochSealCustodyV1 = checkNotNull(run(campaign, ordinaryFactory, CatalogCutoffCompletionV1.OWNED_SEAL).custody)

    @Suppress("TooGenericExceptionCaught")
    private fun run(
        campaign: CatalogCoordinatorLeaseCampaignV1,
        ordinaryFactory: OwnerDeleteAllJournalPublisherFactoryV1,
        completion: CatalogCutoffCompletionV1,
    ): Outcome {
        var attempt: CatalogCutoffAttemptV1? = null
        var result: Outcome? = null
        var failure: PersistencePhaseException? = null
        try {
            requireResources()
            check(campaign.binding.coordinator === coordinator && campaign.jdbc === jdbc && coordinator.leaseCustody.isActive(campaign))
            // This is a NEW seal budget, not rotation's previously consumed allowance.
            val originalBudget = campaign.binding.startEpochSealBudget()
            campaign.claimCutoffResolution()
            val retained = CatalogCutoffAttemptV1(this, campaign, jdbc, originalBudget, completion)
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
            val manifest = CapturedCutoffManifestV1.fromReleased(final)
            val prepared = if (completion !== CatalogCutoffCompletionV1.MANIFEST) {
                retained.captureCanonical(final) // Uses retained manifest/codec attempt, NEVER the historical DTO above.
                renew(retained)
                val preparation = persistence.prepare(retained)
                retained.acceptPrepared(preparation)
                if (completion === CatalogCutoffCompletionV1.CANONICAL_PREPARED) PreparedEpochSealV1.fromReleased(preparation) else null
            } else {
                null
            }
            val custody = if (completion === CatalogCutoffCompletionV1.OWNED_SEAL) {
                retained.beginSealCustody(this, shared).also { it.acquireAndEncode() }
            } else {
                null
            }
            result = Outcome(manifest, prepared, custody)
        } catch (problem: Throwable) {
            campaign.close()
            attempt?.abort()
            failure = boundedEpochRotationFailure(problem)
        } finally {
            attempt?.let { failure = finishRun(it, result?.custody, failure) }
        }
        failure?.let { throw it }
        return checkNotNull(result)
    }

    private fun finishRun(
        attempt: CatalogCutoffAttemptV1,
        handoff: CatalogEpochSealCustodyV1?,
        priorFailure: PersistencePhaseException?,
    ): PersistencePhaseException? {
        var failure = priorFailure
        if (failure == null && handoff != null) {
            val checked = runCatching(handoff::requireHeld).exceptionOrNull()
            if (checked != null) failure = boundedEpochRotationFailure(checked)
        }
        if (failure != null || handoff == null) {
            val finishing = runCatching(attempt::finish).exceptionOrNull()
            if (finishing != null) {
                attempt.abort()
                if (failure == null) failure = boundedEpochRotationFailure(finishing)
            }
            // A retained seal owner releases this registration ONLY after its actual native close returns.
            if (!attempt.retainsSealCustody) active.compareAndSet(attempt, null)
        }
        return failure
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

    internal fun releaseClosedSeal(attempt: CatalogCutoffAttemptV1, custody: CatalogEpochSealCustodyV1) {
        custody.requireClosed(attempt)
        check(active.compareAndSet(attempt, null))
    }

    private fun requireResources() {
        requireConnectionFree()
        coordinator.requireResources()
        if (coordinator.cutoffPublications !== this || !hasOriginalCoordinatorResources() || jdbc.dataSource !== source) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    private fun hasOriginalCoordinatorResources(): Boolean =
        coordinator.ownership === ownership && coordinator.manager === manager && coordinator.dataSource === source

    override fun toString(): String = "CatalogCutoffPublicationsV1(dormant,original-held-custody,NO-wire-dispatch-checkpoint-or-activation)"

    private class Outcome(val manifest: CapturedCutoffManifestV1, val prepared: PreparedEpochSealV1?, val custody: CatalogEpochSealCustodyV1?)
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
