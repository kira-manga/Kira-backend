package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.EpochRotationPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceOwnedFactoryCaller
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Private retained nonce/predecessor and the ONE original J deadline. A constructor alone grants
 * nothing: the original coordinator must retain this exact attempt and call before any operation.
 */
internal class CatalogEpochRotationAttemptV1 internal constructor(
    internal val custody: CatalogEpochRotationCustodyV1,
    private val coordinator: CatalogCoordinatorPersistence,
    internal val campaign: CatalogCoordinatorLeaseCampaignV1,
    internal val jdbc: JdbcTemplate,
    initialPath: PersistencePhasePath,
    internal val budget: PersistenceTimeBudget,
) {
    private val caller = PersistenceOwnedFactoryCaller.capture()
    private val ownership = coordinator.ownership
    private val binding = campaign.binding
    private val window = campaign.requireLocalWindow()
    private val arguments = binding.arguments()
    private val resource = binding.epochRotationResource()
    private val nonce = UUID.randomUUID() // Budget already exists, including on discovery-first attempts.
    internal val owner = campaign.owner
    internal val token = campaign.token
    internal var path: PersistencePhasePath = initialPath
        private set
    private val failed = AtomicBoolean()
    private var stage = Stage.CONTROL
    private var control: CatalogEpochRotationControlOperation? = null
    private var capture: CatalogEpochRotationCaptureOperation? = null
    private var predecessor: CatalogEpochRotationRowV1? = null
    private var handoff: CatalogEpochRotationControlOperation? = null
    private var callSucceeded = false

    internal fun requireRunning() {
        if (!caller.isCurrent() || failed.get() || !custody.ownsCall(this)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        if (caller.sampleActualFlag() != null) refuse(PersistencePhaseFailureCode.INTERRUPTED)
        try {
            budget.remainingMillis(10_000)
        } catch (problem: PersistenceBoundaryException) {
            throw boundedEpochRotationFailure(problem)
        }
        campaign.requireSameWindow(window)
        binding.requirePersistence(ownership, jdbc)
        binding.requireEpochRotation(resource)
    }

    /** Identity/configuration checks only; deliberately safe inside a pooled or nonpooled phase. */
    internal fun requirePersistence(selected: PersistencePhaseOwnership, selectedJdbc: JdbcTemplate) {
        requireRunning()
        if (selected !== ownership || selectedJdbc !== jdbc) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        binding.requirePersistence(selected, selectedJdbc)
    }

    internal fun requireCore(selected: EpochRotationPersistence) {
        requireRunning()
        if (selected !== resource || stage !== Stage.CAPTURING) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        binding.requireEpochRotation(selected)
        checkNotNull(handoff).requireSealedHandoff() // Pure known commit/release check; no global connection-free check here.
    }

    internal fun captureResource(): EpochRotationPersistence {
        requireCore(resource)
        return resource
    }

    internal fun matchesBinding(observation: CatalogEpochRotationBindingRowV1): Boolean = observation.matchesLeaseArguments(arguments)

    internal fun retain(operation: CatalogEpochRotationControlOperation) {
        requireRunning()
        check(stage === Stage.CONTROL && control == null && operation.belongsTo(this))
        control = operation
    }

    internal fun requireControl(operation: CatalogEpochRotationControlOperation) {
        requireRunning()
        check(control === operation && operation.belongsTo(this))
    }

    /** The entire locked predecessor is frozen before mutation (or during EMPTY discovery), not after UPDATE returns. */
    internal fun retainPredecessor(operation: CatalogEpochRotationControlOperation, row: CatalogEpochRotationRowV1) {
        requireControl(operation)
        check(stage === Stage.CONTROL && row.slot == null && row.sequence == 0L)
        val prior = predecessor
        if (prior == null) predecessor = row else check(prior.sameState(row))
    }

    internal fun requestArguments(operation: CatalogEpochRotationControlOperation): Array<Any?> {
        requireControl(operation)
        check(stage === Stage.CONTROL && path === PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST)
        val before = checkNotNull(predecessor)
        check(before.slot == null && before.sequence == 0L && before.publicationEpoch in 1 until Long.MAX_VALUE && before.historyEmpty)
        Math.addExact(before.sequence, 1L)
        return arrayOf(
            nonce, *arguments, owner, token, before.sequence, before.slot?.id, before.slot?.state?.name,
            before.publicationEpoch, before.scanRequested, Timestamp.from(before.updatedAt),
        )
    }

    internal fun requireRequestedMutation(operation: CatalogEpochRotationControlOperation, row: CatalogEpochRotationRowV1) {
        requireControl(operation)
        val before = checkNotNull(predecessor)
        val slot = checkNotNull(row.slot)
        check(before.sameAuthorityAndGates(row) && row.publicationEpoch == before.publicationEpoch && row.scanRequested)
        check(row.sequence == Math.addExact(before.sequence, 1L) && slot.id == nonce && slot.epochBefore == before.publicationEpoch)
        check(slot.state === CatalogEpochRotationStateV1.REQUESTED && slot.request.owner == owner && slot.request.token == token)
        check(slot.request.at == row.sampledAt && row.updatedAt == row.sampledAt)
    }

    internal fun acceptRequest(operation: CatalogEpochRotationControlOperation) {
        val row = operation.requireReleasedRow()
        requireControl(operation)
        check(stage === Stage.CONTROL && row.slot?.state === CatalogEpochRotationStateV1.REQUESTED)
        handoff = operation
        stage = Stage.HANDOFF
        callSucceeded = true
    }

    internal fun acceptNoPending(operation: CatalogEpochRotationControlOperation) {
        val row = operation.requireReleasedRow()
        requireControl(operation)
        check(stage === Stage.CONTROL && path === PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME && row.slot == null)
        check(checkNotNull(predecessor).sameState(row))
        stage = Stage.EMPTY
        callSucceeded = true // Retain this attempt/budget so requestScan may continue without a new allowance.
    }

    internal fun continueAfterNoPending(selected: JdbcTemplate) {
        requirePersistence(ownership, selected)
        check(stage === Stage.EMPTY && path === PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME && callSucceeded)
        val observation = checkNotNull(control).requireReleasedRow()
        check(observation.slot == null && checkNotNull(predecessor).sameState(observation))
        path = PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST
        control = null
        stage = Stage.CONTROL
        callSucceeded = false
    }

    internal fun requireHandoff(operation: CatalogEpochRotationControlOperation) {
        requireRunning()
        check(handoff === operation && control === operation && (stage === Stage.HANDOFF || stage === Stage.CAPTURING))
    }

    internal fun beginCapture() {
        requireRunning()
        check(stage === Stage.HANDOFF && callSucceeded)
        checkNotNull(handoff).requireSealedHandoff()
        stage = Stage.CAPTURING
        callSucceeded = false
    }

    internal fun retain(operation: CatalogEpochRotationCaptureOperation) {
        requireRunning()
        check(stage === Stage.CAPTURING && capture == null && operation.belongsTo(this))
        capture = operation
    }

    internal fun requireCapture(operation: CatalogEpochRotationCaptureOperation) {
        requireRunning()
        check(stage === Stage.CAPTURING && capture === operation && operation.belongsTo(this))
    }

    internal fun requireExactHandoff(operation: CatalogEpochRotationCaptureOperation, slot: CatalogEpochRotationSlotV1) {
        requireCapture(operation)
        val requested = checkNotNull(checkNotNull(handoff).requireSealedHandoff().slot)
        check(requested.state === CatalogEpochRotationStateV1.REQUESTED && requested.sameRequest(slot))
    }

    internal fun captureArguments(operation: CatalogEpochRotationCaptureOperation, locked: CatalogEpochRotationRowV1): Array<Any?> {
        requireCapture(operation)
        val slot = checkNotNull(locked.slot)
        requireExactHandoff(operation, slot)
        check(slot.state === CatalogEpochRotationStateV1.REQUESTED && locked.publicationEpoch == slot.epochBefore && locked.scanRequested)
        Math.addExact(slot.epochBefore, 1L)
        return arrayOf(
            *arguments, owner, token, slot.id, slot.sequence, slot.epochBefore, slot.request.owner, slot.request.token,
            Timestamp.from(slot.request.at), Timestamp.from(locked.updatedAt),
        )
    }

    internal fun acceptCaptured(operation: CatalogEpochRotationControlOperation) {
        val row = operation.requireReleasedRow()
        requireControl(operation)
        check(stage === Stage.CONTROL && path === PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME)
        check(row.slot?.state === CatalogEpochRotationStateV1.CAPTURED)
        stage = Stage.CAPTURED
        callSucceeded = true
    }

    internal fun acceptCaptured(operation: CatalogEpochRotationCaptureOperation) {
        operation.requireReleasedRow()
        requireCapture(operation)
        stage = Stage.CAPTURED
        callSucceeded = true
    }

    /** Irreversible and nonthrowing. It neither resolves UNKNOWN nor releases/refunds any JDBC/native owner. */
    internal fun abort() {
        failed.set(true)
        campaign.close()
    }

    internal fun terminal(): Boolean = failed.get() || stage === Stage.CAPTURED

    @Suppress("TooGenericExceptionCaught")
    internal fun finishCall() {
        try {
            if (!callSucceeded || failed.get()) abort() else requireRunning() // Charge final result construction/return delay to original J/window.
        } catch (problem: Throwable) {
            abort()
            throw problem
        } finally {
            try {
                caller.restoreAfterFailure() // Only after definitive refusal; never under F/G/T or a JDBC dispatch.
            } finally {
                custody.endCall(this)
            }
        }
    }

    internal fun controlFailure(problem: Throwable): PersistencePhaseException {
        abort()
        return control?.returnFailure(problem) ?: boundedEpochRotationFailure(problem)
    }

    internal fun captureFailure(problem: Throwable): PersistencePhaseException {
        abort()
        // Never classify a failed capture using the old request's COMMITTED outcome.
        return capture?.returnFailure(problem) ?: boundedEpochRotationFailure(problem)
    }

    override fun toString(): String = "CatalogEpochRotationAttemptV1(original-J,retained-nonce-and-predecessor,no-retry-authority)"

    private enum class Stage { CONTROL, EMPTY, HANDOFF, CAPTURING, CAPTURED }

    private fun refuse(code: PersistencePhaseFailureCode): Nothing = throw PersistencePhaseException(code)
}

/** No supplied driver/SQL/provider exception or caller data crosses this dormant protocol boundary. */
internal fun boundedEpochRotationFailure(problem: Throwable): PersistencePhaseException = when (problem) {
    is PersistencePhaseException -> problem
    is PersistenceBoundaryException -> PersistencePhaseException(
        if (problem.code === PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED
        else PersistencePhaseFailureCode.RESOURCE_REFUSED,
    )
    is InterruptedException -> {
        Thread.currentThread().interrupt()
        PersistencePhaseException(PersistencePhaseFailureCode.INTERRUPTED)
    }
    else -> PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
}
