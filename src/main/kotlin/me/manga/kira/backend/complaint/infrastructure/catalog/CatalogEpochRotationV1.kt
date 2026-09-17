package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintEpochRotationPersistencePhaseExecutor
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Dormant fixed LIVE rotation only. No route/bean, scheduling, scan, seal, checkpoint or activation.
 * A failed original campaign stops permanently; only a distinct genuine successor may discover the
 * durable slot. No discovery refunds an unresolved original native owner or revives its result.
 *
 * Chosen V6 next-fenced-leader recovery, not the draft CUTOFF-MAPPING's optional same-attempt retry:
 * UNKNOWN remains UNKNOWN for that operation. A new genuinely acquired campaign starts its own
 * bounded locked discovery of REQUESTED/CAPTURED/EMPTY, retaining the stored request/capture
 * provenance. It cannot claim the previous call committed, rolled back or released. A failed or
 * captured campaign cannot restart through this facade or its custody. EMPTY discovery alone may
 * continue to requestScan on the SAME retained attempt/J deadline/nonce and exact predecessor.
 * Nonempty slots have no replacement/clear transition until a genuine seal/checkpoint owner exists.
 */
internal class CatalogEpochRotationV1 internal constructor(private val coordinator: CatalogCoordinatorPersistence, private val jdbc: JdbcTemplate) {
    private val ownership = coordinator.ownership
    private val manager = coordinator.manager
    private val source = coordinator.dataSource
    private val custody = coordinator.epochRotationCustody
    private val control = ComplaintEpochRotationPersistencePhaseExecutor(coordinator, jdbc)

    @Suppress("TooGenericExceptionCaught")
    internal fun requestScan(currentCampaign: CatalogCoordinatorLeaseCampaignV1): Request {
        var attempt: CatalogEpochRotationAttemptV1? = null
        try {
            requireEntryResources()
            val retained = custody.begin(currentCampaign, jdbc, PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST)
            attempt = retained
            try {
                return Request.issuedBy(control.request(retained))
            } finally {
                retained.finishCall()
            }
        } catch (problem: Throwable) {
            currentCampaign.close()
            throw attempt?.controlFailure(problem) ?: boundedEpochRotationFailure(problem)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun captureEpoch(request: Request): Cutoff {
        val attempt = request.attempt
        try {
            requireEntryResources()
            custody.capture(attempt, jdbc)
            try {
                request.requireHandoff()
                return Cutoff.issuedBy(attempt.captureResource().capture(attempt))
            } finally {
                attempt.finishCall()
            }
        } catch (problem: Throwable) {
            throw attempt.captureFailure(problem) // Never borrow the old request phase's database outcome.
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun resume(currentCampaign: CatalogCoordinatorLeaseCampaignV1): ResumeResult {
        var attempt: CatalogEpochRotationAttemptV1? = null
        try {
            requireEntryResources()
            val retained = custody.begin(currentCampaign, jdbc, PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME)
            attempt = retained
            try {
                val operation = control.resume(retained)
                return when (operation.requireReleasedRow().slot?.state) {
                    null -> ResumeResult.NoPending.issuedBy(operation)
                    CatalogEpochRotationStateV1.REQUESTED -> ResumeResult.Requested.issuedBy(operation)
                    CatalogEpochRotationStateV1.CAPTURED -> ResumeResult.Captured.issuedBy(operation)
                }
            } finally {
                retained.finishCall()
            }
        } catch (problem: Throwable) {
            currentCampaign.close()
            throw attempt?.controlFailure(problem) ?: boundedEpochRotationFailure(problem)
        }
    }

    private fun requireEntryResources() {
        requireConnectionFree()
        coordinator.requireResources()
        if (!hasOriginalRotationOwnership() || !hasOriginalJdbcResources()) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    private fun hasOriginalRotationOwnership(): Boolean =
        coordinator.epochRotation === this && coordinator.epochRotationCustody === custody && coordinator.ownership === ownership

    private fun hasOriginalJdbcResources(): Boolean =
        coordinator.manager === manager && coordinator.dataSource === source && jdbc.dataSource === source

    override fun toString(): String = "CatalogEpochRotationV1(dormant-fixed-LIVE,no-seal-checkpoint-or-deployment-authority)"

    /** Opaque same-attempt handoff; cannot be constructed from a UUID, epoch, supplied leader or commit flag. */
    internal class Request private constructor(private val operation: CatalogEpochRotationControlOperation) {
        internal val attempt: CatalogEpochRotationAttemptV1 get() = operation.attempt

        init {
            check(operation.requireReleasedRow().slot?.state === CatalogEpochRotationStateV1.REQUESTED)
            attempt.acceptRequest(operation)
        }

        internal fun requireHandoff() {
            operation.requireSealedHandoff()
        }

        override fun toString(): String = "CatalogEpochRotationV1.Request(opaque,released-control-only)"

        companion object {
            internal fun issuedBy(operation: CatalogEpochRotationControlOperation): Request = Request(operation)
        }
    }

    /** Stored historical cutoff only, never current leadership, verified seal, checkpoint or replay permission. */
    internal class Cutoff private constructor(slot: CatalogEpochRotationSlotV1) {
        val rotationId = slot.id
        val sequence = slot.sequence
        val writer = slot.writer
        val epoch = slot.epochBefore
        val epochAfter = checkNotNull(slot.epochAfter)
        val requestOwner = slot.request.owner
        val requestToken = slot.request.token
        val requestedAt = slot.request.at
        private val capture = checkNotNull(slot.capture)
        val captureOwner = capture.owner
        val captureToken = capture.token
        val capturedAt = capture.at

        override fun toString(): String = "CatalogEpochRotationV1.Cutoff(historical,no-seal-checkpoint-or-current-authority)"

        companion object {
            internal fun issuedBy(operation: CatalogEpochRotationCaptureOperation): Cutoff {
                val stored = checkNotNull(operation.requireReleasedRow().slot)
                check(stored.state === CatalogEpochRotationStateV1.CAPTURED)
                val result = Cutoff(stored)
                operation.acceptResult()
                return result
            }

            internal fun issuedBy(operation: CatalogEpochRotationControlOperation): Cutoff {
                check(operation.path === PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME)
                val stored = checkNotNull(operation.requireReleasedRow().slot)
                check(stored.state === CatalogEpochRotationStateV1.CAPTURED)
                val result = Cutoff(stored)
                operation.attempt.acceptCaptured(operation)
                return result
            }
        }
    }

    internal sealed class ResumeResult private constructor() {
        internal class Requested private constructor(operation: CatalogEpochRotationControlOperation) : ResumeResult() {
            init {
                check(operation.path === PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME)
            }

            val request = Request.issuedBy(operation)

            override fun toString(): String = "CatalogEpochRotationV1.ResumeResult.Requested(released-discovery)"

            companion object {
                internal fun issuedBy(operation: CatalogEpochRotationControlOperation): Requested = Requested(operation)
            }
        }

        internal class Captured private constructor(operation: CatalogEpochRotationControlOperation) : ResumeResult() {
            val cutoff = Cutoff.issuedBy(operation)

            override fun toString(): String = "CatalogEpochRotationV1.ResumeResult.Captured(historical-cutoff-only)"

            companion object {
                internal fun issuedBy(operation: CatalogEpochRotationControlOperation): Captured = Captured(operation)
            }
        }

        internal class NoPending private constructor(operation: CatalogEpochRotationControlOperation) : ResumeResult() {
            init {
                check(operation.path === PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME && operation.requireReleasedRow().slot == null)
                operation.attempt.acceptNoPending(operation)
            }

            override fun toString(): String = "CatalogEpochRotationV1.ResumeResult.NoPending(released-empty-observation,no-authority)"

            companion object {
                internal fun issuedBy(operation: CatalogEpochRotationControlOperation): NoPending = NoPending(operation)
            }
        }
    }
}
