package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import org.springframework.jdbc.core.JdbcTemplate
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.time.Clock
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * Fixed fresh-process recovery of the original PREPARED overlap2, and BOTH-returned no-Sign replay only.
 * No caller tuple, refresh Result, campaign or reusable admission escapes. Historical B/allocation keep
 * the original lease owner/token; only the existing SQL phases use the new actual full-B/DB-time lease.
 * The caller separately retains/prepares/retires its real TARGET-only D7 assembly and durable root.
 */
@Suppress("TooManyFunctions") // One concrete owner keeps the fixed phase/resource guards; no generic authority carrier.
internal class CatalogSignerRotationPreparedRecoveryV1 private constructor(
    internal val process: VersionBoundComplaintProcessConfiguration,
    internal val budget: PersistenceTimeBudget,
    readbackHttpFactory: (() -> SdkHttpClient)?,
    private val clock: Clock,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val coordinator = process.pools.catalogCoordinator
    private val ownership = coordinator.ownership
    private val manager = coordinator.manager
    private val source = coordinator.dataSource
    private val desired = process.desiredSettings()
    private val assembly = CatalogSignerRotationFreezeAssemblyV1(this, readbackHttpFactory, clock)
    private var stage = Stage.NEW
    private var entered = false
    private var failed = false
    private var reserved = false
    private var released = false
    private var closed = false
    private var cleanupProven = false
    private var closeFailure: CatalogSignerRotationFreezeExceptionV1? = null
    private var request: CatalogSignerRotationFreezeRequestV1? = null
    private var custody: CatalogSignerRotationReleaseCustodyV1? = null
    private var history: CatalogSignerRotationPreparedRecoveryInputsV1? = null
    private var selectedInputs: CatalogSignerRotationInputsV1? = null
    internal val inputs: CatalogSignerRotationInputsV1 get() = checkNotNull(selectedInputs)
    private var release: CatalogSignerRotationFreezeReleaseV1? = null
    private var signatures: List<ByteArray>? = null
    private var snapshot: LocalCatalogSnapshot.Prepared? = null
    private var readback: CatalogDualLocationVerifier.SignerRotationAuthorReadback? = null
    private var binding: CatalogCoordinatorLeaseBindingV1? = null
    private var campaign: CatalogCoordinatorLeaseCampaignV1? = null
    private var acquireIssued = false
    private var replay: CatalogSignerRotationFreezeAttemptV1? = null
    private var replayReserved = false
    private var replayReleased = false
    private var admitted: CatalogSignerRotationObservationV1? = null
    private var persisted: CatalogSignerRotationObservationV1? = null
    private var signatureReplayIssued = false
    private var completedProduct: CatalogSignerRotationFrozenProductV1? = null
    private var resultAllowed = false
    private var selectedPath: PersistencePhasePath? = null
    private var phaseEntered = false
    private var phaseRetainedForEntry = false
    private var originalPhase: PersistencePhaseContext? = null
    private var sqlCleanupUnproven = false
    private var leaseOutcomeUncertain = false
    private val originalSignal = AtomicReference<Throwable?>()

    init {
        requireConnectionFree()
        requireProcess()
    }

    @Suppress("TooGenericExceptionCaught")
    fun resume(
        request: CatalogSignerRotationFreezeRequestV1,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogSignerRotationFreezeResultV1 {
        requireSignerRotation(caller === Thread.currentThread(), CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        var product: CatalogSignerRotationFrozenProductV1? = null
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireSignerRotation(!entered, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
            entered = true
            this.request = request
            coordinator.catalogRefreshCustody.reserveSignerRotationRecovery(this)
            reserved = true
            stage = Stage.INPUTS
            val held = CatalogSignerRotationReleaseCustodyV1.retainExisting(request.releaseRoot, budget)
            custody = held // Original discovery owner before any filesystem/native effect, including uncertain open.
            val allocation = held.discoverExisting()
            history = CatalogSignerRotationPreparedRecoveryInputsV1.read(this, held, allocation)
            selectedInputs = assembly.acquire(request)
            val retained = CatalogSignerRotationFreezeReleaseV1(this, inputs, held)
            release = retained
            signatures = retained.requireBothReturnedSignatures()

            stage = Stage.SNAPSHOT
            val local = loadSnapshot()
            requireSignerRotation(local is LocalCatalogSnapshot.Prepared, CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
            val prepared = local as LocalCatalogSnapshot.Prepared
            requirePreparedTuple(prepared)
            snapshot = prepared // Only the genuine committed/released snapshot is retained, never a caller-supplied observation.
            stage = Stage.READBACK
            readback = assembly.observePreparedSnapshot(prepared, primaryReadCredentials, replicaReadCredentials)

            stage = Stage.ACQUIRE
            val freshBinding = CatalogCoordinatorLeaseBindingV1.fromPreparedRecovery(this, process, checkNotNull(readback))
            binding = freshBinding
            acquireLease(freshBinding)
            stage = Stage.REPLAY
            val child = CatalogSignerRotationFreezeAttemptV1(this, process, checkNotNull(campaign), budget)
            replay = child
            child.reserve() // Retains a child under THIS original slot; never a nested reservation or deadline.
            child.bind(inputs)
            product = replayBothReturned(child, retained, primaryReadCredentials, replicaReadCredentials)
            completedProduct = product
            requireRunning()
        } catch (problem: Throwable) {
            observeFailure(problem)
            failure = signerRotationSignal(problem)
            abort()
        } finally {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure = preferSignerRotationCleanup(failure, cleanup)
            }
        }
        failure?.let { throw boundedSignerRotationFailure(it) }
        resultAllowed = true
        return CatalogSignerRotationFreezeResultV1.completed(this, checkNotNull(product))
    }

    private fun replayBothReturned(
        child: CatalogSignerRotationFreezeAttemptV1,
        retained: CatalogSignerRotationFreezeReleaseV1,
        primary: AwsSessionCredentials,
        replica: AwsSessionCredentials,
    ): CatalogSignerRotationFrozenProductV1 {
        var local = child.readPrepared() // Existing fenced full-B/lease/history/capacity READ; never PREPARE or another charge.
        requireOriginalObservation(local)
        val raw = assembly.observe(local, primary, replica)
        local = child.recheck(local, raw)
        requireOriginalObservation(local)
        admitted = local // Only this actual fenced + committed/released exact recheck permits the one no-Sign SQL replay.
        val after = inputs.withBothSignatures(local, checkNotNull(signatures), raw)
        retained.requireFrozenEnvelope(after)
        local = child.persistSignature(local, after)
        persisted = local
        retained.signaturePersisted(1, local)
        return retained.signedPrepared(local)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadSnapshot(): LocalCatalogSnapshot {
        selectPhase(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT)
        return try {
            coordinator.snapshot.loadPreparedRecovery(this, inputs.reader.policyAt(clock.instant()))
        } catch (problem: Throwable) {
            phaseFailed(problem)
        } finally {
            selectedPath = null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun acquireLease(selected: CatalogCoordinatorLeaseBindingV1) {
        requireSignerRotation(!acquireIssued, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        acquireIssued = true // Even an ambiguous commit has no retry/relinquishment inference in this owner.
        selectPhase(PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE)
        try {
            val actual = coordinator.lease.acquirePreparedRecovery(this, selected)
            campaign = actual.campaign // Retain BEFORE post-return budget, identity or historical comparisons.
            requireRunning()
            val prior = checkNotNull(history)
            requireSignerRotation(
                actual.receipt.transition === CatalogCoordinatorLeaseTransitionV1.ACQUIRED && actual.receipt.owner != prior.historicalOwner &&
                    actual.receipt.token > prior.historicalToken && actual.campaign.owner == actual.receipt.owner &&
                    actual.campaign.token == actual.receipt.token && actual.campaign.binding === selected,
                CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED,
            )
            actual.campaign.requireRotationContinuity(budget)
        } catch (problem: Throwable) {
            campaign?.close()
            phaseFailed(problem)
        } finally {
            selectedPath = null
        }
    }

    private fun requirePreparedTuple(local: LocalCatalogSnapshot.Prepared) {
        val returned = checkNotNull(signatures)
        requireSignerRotation(
            local.head.generation == 1L && local.head.envelopeSha256 == historicalPredecessorHash(),
            CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED,
        )
        inputs.requireMutation(local.mutation)
        val slots = local.mutation.signatureSlots
        requireSignerRotation(slots[0].signatureBytes.contentEquals(returned[0]), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        slots[1].signatureBytes?.let {
            requireSignerRotation(it.contentEquals(returned[1]), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        }
        local.mutation.signedEnvelopeBytes?.let {
            requireSignerRotation(it.contentEquals(inputs.signedBytes(returned)), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        }
    }

    private fun requireOriginalObservation(local: CatalogSignerRotationObservationV1) {
        requireRunning()
        checkNotNull(readback).requireSnapshot(local.localSnapshot)
        requireSignerRotation(
            local.genesis.signedEnvelopeBytes.contentEquals(checkNotNull(readback).genesisBytes()),
            CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED,
        )
        inputs.requireObservation(local)
        requirePreparedTuple(local.localSnapshot as LocalCatalogSnapshot.Prepared)
    }

    internal fun requireHistoricalCustody(selected: CatalogSignerRotationReleaseCustodyV1) {
        requireRunning()
        requireSignerRotation(
            stage === Stage.INPUTS && custody === selected && reserved,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requireRecoveryReleaseInputs(selected: CatalogSignerRotationInputsV1, held: CatalogSignerRotationReleaseCustodyV1) {
        requireHistoricalCustody(held)
        requireSignerRotation(selectedInputs === selected && release == null, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
    }

    internal fun requireInputAcquisition(selected: CatalogSignerRotationFreezeRequestV1) {
        requireRunning()
        requireSignerRotation(
            stage === Stage.INPUTS && request === selected && history != null && selectedInputs == null,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun historicalBindingRecord(): ByteArray = checkNotNull(history).bindingBytes()
    internal fun historicalPredecessorHash(): String = checkNotNull(history).predecessorHash

    internal fun requireInputHistory(selected: CatalogSignerRotationInputsV1) {
        requireInputAcquisition(selected.request)
        checkNotNull(history).requireInputs(process, selected)
    }

    internal fun requireObservedSnapshot(selected: LocalCatalogSnapshot.Prepared) {
        requireRunning()
        requireSignerRotation(stage === Stage.READBACK && snapshot === selected, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
    }

    internal fun requirePredecessor(selected: CatalogDualLocationVerifier.SignerRotationAuthorReadback) {
        requireConnectionFree()
        requireRunning()
        selected.requireSnapshot(checkNotNull(snapshot))
        checkNotNull(history).requireReadback(selected)
    }

    internal fun requireBindingInputs(selected: VersionBoundComplaintProcessConfiguration, raw: CatalogDualLocationVerifier.SignerRotationAuthorReadback) {
        requireRunning()
        requireSqlCleanup()
        requireSignerRotation(
            stage === Stage.ACQUIRE && !acquireIssued && binding == null && selected === process && raw === readback,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        requirePredecessor(raw)
    }

    internal fun requireBoundProcess(selected: CatalogCoordinatorLeaseBindingV1, candidate: VersionBoundComplaintProcessConfiguration) {
        requireRunning()
        requireSignerRotation(
            binding === selected && candidate === process && stage === Stage.REPLAY && campaign?.binding === selected,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requireSnapshotSelection(candidate: PersistencePhaseOwnership) {
        requireRunning()
        requireSignerRotation(
            candidate === ownership && stage === Stage.SNAPSHOT && selectedPath === PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requireLeaseSelection(candidate: PersistencePhaseOwnership, jdbc: JdbcTemplate, selected: CatalogCoordinatorLeaseBindingV1) {
        requireConnectionFree()
        requireLeaseAttempt(selected)
        requireSignerRotation(candidate === ownership && jdbc.dataSource === source, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        selected.requirePersistence(candidate, jdbc)
    }

    internal fun requireLeaseAttempt(selected: CatalogCoordinatorLeaseBindingV1) {
        requireRunning()
        requireSignerRotation(
            stage === Stage.ACQUIRE && acquireIssued && binding === selected &&
                selectedPath === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        selected.requireRecoveryPurpose(this)
    }

    /** Scalars retained from validated original custody; called before CAS while the actual lease-control row is locked. */
    internal fun requireHistoricalLeaseFloor(selected: CatalogCoordinatorLeaseBindingV1, lockedToken: Long) {
        requireLeaseAttempt(selected)
        requireSignerRotation(lockedToken >= checkNotNull(history).historicalToken, CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
    }

    internal fun ownsReplay(selected: CatalogSignerRotationFreezeAttemptV1): Boolean =
        caller === Thread.currentThread() && !closed && !failed && stage === Stage.REPLAY && replay === selected && !replayReleased

    internal fun requireReplayConstruction(
        candidate: VersionBoundComplaintProcessConfiguration,
        current: CatalogCoordinatorLeaseCampaignV1,
        allowance: PersistenceTimeBudget,
    ) {
        requireRunning()
        requireSignerRotation(
            stage === Stage.REPLAY && replay == null && candidate === process && current === campaign && allowance === budget,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requireReplayInputs(selected: CatalogSignerRotationFreezeAttemptV1, value: CatalogSignerRotationInputsV1) {
        requireRunning()
        requireSignerRotation(
            ownsReplay(selected) && replayReserved && value === selectedInputs,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun retainReplay(
        selected: CatalogSignerRotationFreezeAttemptV1,
        candidate: VersionBoundComplaintProcessConfiguration,
        current: CatalogCoordinatorLeaseCampaignV1,
        allowance: PersistenceTimeBudget,
    ) {
        requireRunning()
        requireSignerRotation(
            ownsReplay(selected) && !replayReserved && candidate === process && current === campaign && allowance === budget,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        replayReserved = true
    }

    internal fun requireReplayPhase(selected: CatalogSignerRotationFreezeAttemptV1, candidate: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireRunning()
        requireSqlCleanup()
        requireSignerRotation(
            ownsReplay(selected) && replayReserved && candidate === ownership,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        requireSignerRotation(
            path === PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ ||
                (path === PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE && admitted != null && signatureReplayIssued),
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requireSignatureReplay(
        selected: CatalogSignerRotationFreezeAttemptV1,
        before: CatalogSignerRotationObservationV1,
        after: CatalogFrozenMutation,
    ) {
        requireRunning()
        requireSignerRotation(
            ownsReplay(selected) && before === admitted && !signatureReplayIssued,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        requireOriginalObservation(before)
        val returned = checkNotNull(signatures)
        requireSignerRotation(
            after.signatureSlots.indices.all { after.signatureSlots[it].signatureBytes.contentEquals(returned[it]) },
            CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED,
        )
        requireSignerRotation(
            after.signedEnvelopeBytes.contentEquals(inputs.signedBytes(returned)),
            CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED,
        )
        signatureReplayIssued = true
    }

    internal fun requirePersistedReplay(selected: CatalogSignerRotationObservationV1) {
        requireRunning()
        requireSignerRotation(
            stage === Stage.REPLAY && signatureReplayIssued && persisted === selected,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    private fun selectPhase(path: PersistencePhasePath) {
        requireRunning()
        requireSqlCleanup()
        requireSignerRotation(selectedPath == null, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        selectedPath = path
        phaseEntered = false
        phaseRetainedForEntry = false
    }

    internal fun requirePhaseEntry(candidate: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireRunning()
        requireSignerRotation(
            candidate === ownership && selectedPath === path && !phaseEntered,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        requireSignerRotation(
            path === PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT || path === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        phaseEntered = true
    }

    internal fun retainPhase(phase: PersistencePhaseContext) {
        requireSignerRotation(
            caller === Thread.currentThread() && phaseEntered && selectedPath != null && originalPhase == null &&
                !phaseRetainedForEntry && !sqlCleanupUnproven && !leaseOutcomeUncertain,
            CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN,
        )
        originalPhase = phase // BEFORE publication, permits or any manager/checkout side effect.
        phaseRetainedForEntry = true
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun observePhaseCleanup(phase: PersistencePhaseContext) {
        if (caller !== Thread.currentThread() || originalPhase !== phase) {
            sqlCleanupUnproven = true
            return
        }
        try {
            if (!phase.signerRotationRecoveryCleanupProven(this)) sqlCleanupUnproven = true
            if (selectedPath === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE &&
                phase.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN
            ) {
                leaseOutcomeUncertain = true // Physical retirement is not resolution of an ambiguous lease CAS/commit.
            }
            if (!sqlCleanupUnproven && !leaseOutcomeUncertain) originalPhase = null
        } catch (problem: Throwable) {
            sqlCleanupUnproven = true
            observeFailure(problem)
        }
    }

    private fun phaseFailed(problem: Throwable): Nothing {
        if (!phaseRetainedForEntry && problem is PersistencePhaseException && !problem.cleanupProven) sqlCleanupUnproven = true
        observeFailure(problem)
        throwIfSignalled()
        throw problem
    }

    private fun requireSqlCleanup() = requireSignerRotation(
        caller === Thread.currentThread() && !sqlCleanupUnproven && !leaseOutcomeUncertain && originalPhase == null,
        CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN,
    )

    internal fun observeFailure(problem: Throwable) {
        val signal = when {
            problem is Error -> problem

            problem is CancellationException -> CancellationException("Catalog signer rotation recovery cancelled.")

            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ||
                (problem is CatalogSignerRotationFreezeExceptionV1 && problem.code === CatalogSignerRotationFreezeFailureV1.INTERRUPTED) ->
                InterruptedException("Catalog signer rotation recovery interrupted.")

            else -> return
        }
        while (true) {
            val before = originalSignal.get()
            val retainBefore = before is Error || (before is CancellationException && signal !is Error) ||
                (before is InterruptedException && signal is InterruptedException)
            if (retainBefore) return
            if (originalSignal.compareAndSet(before, signal)) return
        }
    }

    internal fun throwIfSignalled() {
        originalSignal.get()?.let { throw signerRotationSignal(it) }
    }

    internal fun requireCustody(selected: CatalogReadbackRefreshCustodyV1) = requireSignerRotation(
        caller === Thread.currentThread() && coordinator.catalogRefreshCustody === selected,
        CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
    )

    internal fun requireRunning() {
        throwIfSignalled()
        requireSignerRotation(
            caller === Thread.currentThread() && !closed && !failed && !released,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        requireSignerRotation(!sqlCleanupUnproven && !leaseOutcomeUncertain, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        requireSignerRotation(!Thread.currentThread().isInterrupted, CatalogSignerRotationFreezeFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
        requireProcess()
        if (reserved) coordinator.catalogRefreshCustody.requireSignerRotationRecovery(this)
    }

    private fun requireProcess() {
        process.requireUnchangedConfiguration()
        requireSignerRotation(
            process.pools.catalogCoordinator === coordinator && coordinator.ownership === ownership &&
                coordinator.manager === manager && coordinator.dataSource === source &&
                coordinator.catalogSignerRotationRecovery && process.catalogReadback?.projectedCurrent == false && process.catalogSignerRotation != null &&
                desired.mode === ComplaintInstallationMode.LIVE && desired.scope == ComplaintDataScope.LIVE &&
                desired.implementationSchema == 1 && desired.desiredGeneration == 1L,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        checkNotNull(process.catalogSignerRotation).requireRetained(process.pools, checkNotNull(process.catalogReadback))
        coordinator.requireResources()
    }

    private fun abort() {
        failed = true
        replay?.abort()
        campaign?.close() // Local permanent stop only: no guessed SQL release, replacement lease, refund or retry.
    }

    override fun close() {
        requireSignerRotation(caller === Thread.currentThread(), CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        if (closed) {
            closeFailure?.let { throw it }
            return
        }
        closed = true
        stage = Stage.CLOSED
        closeFailure = CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        abort()
        val outcomes = listOf(
            runCatching(assembly::close),
            runCatching { custody?.close() },
            runCatching(::requireConnectionFree),
            runCatching(::requireSqlCleanup),
            runCatching { replay?.requireSqlCleanup() },
            runCatching(::throwIfSignalled),
            runCatching { replay?.throwIfSignalled() },
            runCatching {
                requireSignerRotation(!Thread.currentThread().isInterrupted, CatalogSignerRotationFreezeFailureV1.INTERRUPTED)
                budget.remainingMillis(1)
            },
        )
        requireSignerRotationCleanup(outcomes)
        cleanupProven = true
        replay?.releaseAfterCleanup()
        if (reserved) coordinator.catalogRefreshCustody.releaseSignerRotationRecoveryAfterCleanup(this)
        released = true
        closeFailure = null
    }

    internal fun requireReplayCleanup(selected: CatalogSignerRotationFreezeAttemptV1) {
        requireActualCleanup()
        requireSignerRotation(replay === selected && !replayReleased, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        selected.requireSqlCleanup()
    }

    internal fun releaseReplayAfterCleanup(selected: CatalogSignerRotationFreezeAttemptV1) {
        requireReplayCleanup(selected)
        replayReleased = true
    }

    internal fun requireActualCleanup() {
        requireSignerRotation(caller === Thread.currentThread() && closed && cleanupProven, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        requireSqlCleanup()
        requireConnectionFree()
    }

    internal fun requireCleanedResult(product: CatalogSignerRotationFrozenProductV1) {
        requireActualCleanup()
        requireSignerRotation(
            released && closeFailure == null && resultAllowed && completedProduct === product,
            CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN,
        )
        budget.remainingMillis(1)
    }

    override fun toString(): String = "CatalogSignerRotationPreparedRecoveryV1(fresh-D7,existing-PREPARED2,both-returned-no-Sign,redacted)"
    private enum class Stage { NEW, INPUTS, SNAPSHOT, READBACK, ACQUIRE, REPLAY, CLOSED }

    companion object {
        fun begin(process: VersionBoundComplaintProcessConfiguration): CatalogSignerRotationPreparedRecoveryV1 =
            CatalogSignerRotationPreparedRecoveryV1(process, startBudget(process), null, Clock.systemUTC())

        /** Raw HTTP and wall-time seams only. Original process clock, SDK, SQL, signatures and Linux custody remain real. */
        internal fun withHttpFixture(
            process: VersionBoundComplaintProcessConfiguration,
            readback: () -> SdkHttpClient,
            clock: Clock,
        ): CatalogSignerRotationPreparedRecoveryV1 = CatalogSignerRotationPreparedRecoveryV1(process, startBudget(process), readback, clock)

        private fun startBudget(process: VersionBoundComplaintProcessConfiguration): PersistenceTimeBudget {
            requireConnectionFree()
            val writer = process.catalogSignerRotation
                ?: throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
            return PersistenceTimeBudget.start(writer.deployment.totalAttemptMillis, process.pools.catalogCoordinator.ownership.nanoClock)
        }
    }
}
