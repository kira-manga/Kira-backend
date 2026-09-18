package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/** Published by the lifecycle owner before activation. Every participant and actor exists before any start. */
internal class PersistenceJdbcDriverRoot(
    val endpoint: ResolvedPersistenceEndpoint,
    capacity: Int,
    val pathStyle: PersistencePathStyle,
    internal val sourceOnly: Boolean = false,
    versionBound: VersionBoundPersistenceConfiguration? = null,
    epochRotationEnabled: Boolean = false,
    internal val desiredInstallationOperator: Boolean = false,
    internal val catalogGenesisAuthoring: Boolean = false,
) {
    init {
        check(!desiredInstallationOperator || (!sourceOnly && versionBound != null && !epochRotationEnabled))
        check(!catalogGenesisAuthoring || (!desiredInstallationOperator && !sourceOnly && versionBound != null && !epochRotationEnabled))
    }

    private val publicTrust = versionBound?.adopt(this, endpoint, capacity, pathStyle, sourceOnly, desiredInstallationOperator, catalogGenesisAuthoring)
    val shutdown = AtomicBoolean()
    internal val versionBoundPools = versionBound?.createPools(this)
    internal val epochRotationMaterial = if (epochRotationEnabled) {
        check(!sourceOnly && pathStyle === PersistencePathStyle.POSIX)
        checkNotNull(versionBound).createEpochRotationMaterial(this)
    } else {
        null
    }
    val retainedDriver = PersistenceRetainedPgDriver()
    val ordinary = PersistenceJdbcParticipant(this, capacity, deletion = false)
    val deletion = PersistenceJdbcParticipant(this, 4, deletion = true)
    val catalogCoordinator = PersistenceJdbcParticipant(this, 1, PersistenceJdbcParticipantRole.CATALOG_COORDINATOR)
    private val epochRotationParticipant = epochRotationMaterial?.let {
        PersistenceJdbcParticipant(this, it.descriptor.capacity, PersistenceJdbcParticipantRole.EPOCH_ROTATION)
    }
    internal val epochRotation = epochRotationParticipant?.let {
        EpochRotationPersistence.create(this, it, checkNotNull(epochRotationMaterial))
    }
    val timer = PersistenceDriverTimer(this)
    private val startClaimed = AtomicBoolean()
    private val deletionClaimed = AtomicBoolean()
    private val catalogCoordinatorClaimed = AtomicBoolean()
    private val epochRotationClaimed = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val scanner = PersistenceRetainedPlatformThread("kira-persistence-scanner", ::scan)

    init {
        if (desiredInstallationOperator || catalogGenesisAuthoring) {
            // Seal even direct/later requests on these original participants, not merely the public start facade.
            ordinary.forbidStarts()
            deletion.forbidStarts()
        }
    }

    fun start(): PersistenceLifecycleActivation = if (desiredInstallationOperator ||
        catalogGenesisAuthoring
    ) {
        PersistenceLifecycleActivation.CLOSED
    } else {
        startRoot()
    }

    internal fun startDesiredInstallationOperatorInfrastructure(): PersistenceLifecycleActivation =
        if (desiredInstallationOperator) startRoot() else PersistenceLifecycleActivation.CLOSED

    internal fun startCatalogGenesisAuthoringInfrastructure(): PersistenceLifecycleActivation =
        if (catalogGenesisAuthoring) startRoot() else PersistenceLifecycleActivation.CLOSED

    private fun startRoot(): PersistenceLifecycleActivation {
        if (shutdown.get()) return PersistenceLifecycleActivation.CLOSED
        if (publicTrust?.readyFor(this) == false) return PersistenceLifecycleActivation.FAILED
        if (!startClaimed.compareAndSet(false, true)) return PersistenceLifecycleActivation.ALREADY_CLAIMED
        return runCatching { startActors() }.getOrElse { failure ->
            failed.set(true)
            requestShutdown()
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure is Error) throw failure
            PersistenceLifecycleActivation.FAILED
        }
    }

    private fun startActors(): PersistenceLifecycleActivation {
        val scannerStart = scanner.start()
        if (shutdown.get() && scannerStart === PersistenceFactoryStart.CLOSED) return PersistenceLifecycleActivation.CLOSED
        check(scannerStart === PersistenceFactoryStart.STARTED)
        if (shutdown.get()) return PersistenceLifecycleActivation.CLOSED
        // Timer-only failure never changes the ordinary endpoint or installs a second controller.
        if (!sourceOnly) {
            runCatching { timer.start() }.onFailure { failure ->
                if (failure is Error) throw failure
                if (failure is InterruptedException) Thread.currentThread().interrupt()
            }
        }
        if (shutdown.get()) return PersistenceLifecycleActivation.CLOSED
        // CoordinatorPoolPreparation owns its one actual start/initialization ticket. Never start an ordinary helper.
        if (desiredInstallationOperator || catalogGenesisAuthoring) return PersistenceLifecycleActivation.STARTED
        val ordinaryStart = ordinary.start()
        if (shutdown.get() && ordinaryStart === PersistenceFactoryStart.CLOSED) return PersistenceLifecycleActivation.CLOSED
        check(ordinaryStart === PersistenceFactoryStart.STARTED)
        return PersistenceLifecycleActivation.STARTED
    }

    fun prepareDeletion(): PersistenceLifecycleActivation {
        if (sourceOnly || desiredInstallationOperator || catalogGenesisAuthoring) return PersistenceLifecycleActivation.CLOSED
        if (shutdown.get() || !startClaimed.get()) return PersistenceLifecycleActivation.CLOSED
        if (!deletionClaimed.compareAndSet(false, true)) return PersistenceLifecycleActivation.ALREADY_CLAIMED
        return runCatching {
            when (deletion.start()) {
                PersistenceFactoryStart.STARTED -> PersistenceLifecycleActivation.STARTED
                PersistenceFactoryStart.CLOSED -> PersistenceLifecycleActivation.CLOSED
                else -> PersistenceLifecycleActivation.FAILED
            }
        }.getOrElse { failure ->
            deletion.forbidStarts()
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure is Error) throw failure
            PersistenceLifecycleActivation.FAILED
        }
    }

    fun requestShutdown(): Boolean {
        val first = shutdown.compareAndSet(false, true) // Permanent before any other lifecycle action.
        ordinary.forbidStarts()
        deletion.forbidStarts()
        catalogCoordinator.forbidStarts()
        epochRotationParticipant?.forbidStarts()
        epochRotation?.seal()
        timer.forbidStart()
        scanner.forbidStart()
        return first
    }

    /** Explicit caller-owned filesystem work; never called by a scanner, phase or observer. */
    internal fun preparePublicTrust(): PersistencePublicTrustPreparation = publicTrust?.prepare(this) ?: PersistencePublicTrustPreparation.NOT_REQUIRED

    /** Only this exact root's permanent, strong all-role drain can authorize its trust-file disposal. */
    internal fun releasePublicTrustAfterShutdown(): PersistencePublicTrustRelease = publicTrust?.release(this) ?: PersistencePublicTrustRelease.NOT_REQUIRED

    /** Exact driver-root conjunction AND every retained bound pool's own local custody; never a recursive pool receipt. */
    internal fun publicTrustReleaseReady(): Boolean = shutdown.get() &&
        shutdownObservation() === PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED && versionBoundPools?.poolsEndedForTrust() != false &&
        epochRotation?.endedForTrust() != false

    /** Stop only the existing deletion participant; the shared scanner/Timer remain owned by this root. */
    fun requestDeletionShutdown(): Boolean = deletion.forbidStarts()

    fun prepareCatalogCoordinator(): PersistenceLifecycleActivation {
        if (sourceOnly || shutdown.get() || !startClaimed.get()) return PersistenceLifecycleActivation.CLOSED
        if (!catalogCoordinatorClaimed.compareAndSet(false, true)) return PersistenceLifecycleActivation.ALREADY_CLAIMED
        return runCatching {
            when (catalogCoordinator.start()) {
                PersistenceFactoryStart.STARTED -> PersistenceLifecycleActivation.STARTED
                PersistenceFactoryStart.CLOSED -> PersistenceLifecycleActivation.CLOSED
                else -> PersistenceLifecycleActivation.FAILED
            }
        }.getOrElse { failure ->
            catalogCoordinator.forbidStarts()
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure is Error) throw failure
            PersistenceLifecycleActivation.FAILED
        }
    }

    fun requestCatalogCoordinatorShutdown(): Boolean = catalogCoordinator.forbidStarts()

    internal fun prepareEpochRotation(): PersistenceLifecycleActivation {
        if (desiredInstallationOperator || catalogGenesisAuthoring) return PersistenceLifecycleActivation.CLOSED
        val participant = epochRotationParticipant ?: return PersistenceLifecycleActivation.CLOSED
        if (shutdown.get() || !startClaimed.get()) return PersistenceLifecycleActivation.CLOSED
        if (!epochRotationClaimed.compareAndSet(false, true)) return PersistenceLifecycleActivation.ALREADY_CLAIMED
        return runCatching {
            when (participant.start()) {
                PersistenceFactoryStart.STARTED -> PersistenceLifecycleActivation.STARTED
                PersistenceFactoryStart.CLOSED -> PersistenceLifecycleActivation.CLOSED
                else -> PersistenceLifecycleActivation.FAILED
            }
        }.getOrElse { failure ->
            participant.forbidStarts()
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure is Error) throw failure
            PersistenceLifecycleActivation.FAILED
        }
    }

    internal fun requestEpochRotationShutdown(): Boolean {
        epochRotation?.seal()
        return epochRotationParticipant?.forbidStarts() ?: false
    }

    internal fun epochRotationPreparationObservation(): PersistenceLifecycleObservation {
        val participant = epochRotationParticipant ?: return PersistenceLifecycleObservation.UNAVAILABLE
        if (shutdown.get()) return PersistenceLifecycleObservation.UNAVAILABLE
        if (!startClaimed.get() || !epochRotationClaimed.get()) return PersistenceLifecycleObservation.NOT_REQUESTED
        return when {
            participant.isReady() -> PersistenceLifecycleObservation.READY
            participant.preparationFinished() -> PersistenceLifecycleObservation.UNAVAILABLE
            else -> PersistenceLifecycleObservation.PENDING
        }
    }

    internal fun epochRotationShutdownObservation(): PersistenceLifecycleObservation {
        val participant = epochRotationParticipant ?: return PersistenceLifecycleObservation.UNAVAILABLE
        if (!participant.shutdownRequested()) return PersistenceLifecycleObservation.NOT_REQUESTED
        if (!participant.finishShutdownScan() || epochRotation?.endedForTrust() != true) return PersistenceLifecycleObservation.PENDING
        val retained = participant.retainedCount() ?: return PersistenceLifecycleObservation.PENDING
        return if (participant.cleanupFailed() || participant.usedWeakEvidence() || retained != 0) {
            PersistenceLifecycleObservation.UNKNOWN
        } else {
            PersistenceLifecycleObservation.EPOCH_ROTATION_LOCAL_ENDED
        }
    }

    fun catalogCoordinatorShutdownObservation(): PersistenceLifecycleObservation {
        if (!catalogCoordinator.shutdownRequested()) return PersistenceLifecycleObservation.NOT_REQUESTED
        if (!catalogCoordinator.finishShutdownScan()) return PersistenceLifecycleObservation.PENDING
        val retained = catalogCoordinator.retainedCount() ?: return PersistenceLifecycleObservation.PENDING
        return if (catalogCoordinator.cleanupFailed() || catalogCoordinator.usedWeakEvidence() || retained != 0) {
            PersistenceLifecycleObservation.UNKNOWN
        } else {
            PersistenceLifecycleObservation.CATALOG_COORDINATOR_LOCAL_ENDED
        }
    }

    fun catalogCoordinatorPreparationObservation(): PersistenceLifecycleObservation {
        if (shutdown.get()) return PersistenceLifecycleObservation.UNAVAILABLE
        if (!startClaimed.get() || !catalogCoordinatorClaimed.get()) return PersistenceLifecycleObservation.NOT_REQUESTED
        return when {
            catalogCoordinator.isReady() -> PersistenceLifecycleObservation.READY
            catalogCoordinator.preparationFinished() -> PersistenceLifecycleObservation.UNAVAILABLE
            else -> PersistenceLifecycleObservation.PENDING
        }
    }

    fun deletionShutdownObservation(): PersistenceLifecycleObservation {
        if (!deletion.shutdownRequested()) return PersistenceLifecycleObservation.NOT_REQUESTED
        if (!deletion.finishShutdownScan()) return PersistenceLifecycleObservation.PENDING
        val retained = deletion.retainedCount() ?: return PersistenceLifecycleObservation.PENDING
        return if (deletion.cleanupFailed() || deletion.usedWeakEvidence() || retained != 0) {
            PersistenceLifecycleObservation.UNKNOWN
        } else {
            PersistenceLifecycleObservation.DELETION_LOCAL_ENDED
        }
    }

    fun scannerReady(): Boolean = scanner.startPhase() === PersistenceThreadStartPhase.RETURNED && scanner.hasEntered() && !scanner.hasBodyEnded()

    internal fun ownershipLockHeld(): Boolean = ordinary.ownershipLockHeld() || deletion.ownershipLockHeld() || catalogCoordinator.ownershipLockHeld() ||
        epochRotationParticipant?.ownershipLockHeld() == true

    fun canReleaseTimer(): Boolean = shutdown.get() && participantsEnded() && scanner.termination().ended()

    private fun participantsEnded(): Boolean = ordinary.recordsEnded() && deletion.recordsEnded() && catalogCoordinator.recordsEnded() &&
        ordinary.threadsEnded() && deletion.threadsEnded() && catalogCoordinator.threadsEnded() &&
        epochRotationParticipant?.recordsEnded() != false && epochRotationParticipant?.threadsEnded() != false && epochRotation?.endedForTrust() != false

    fun preparationObservation(deleting: Boolean): PersistenceLifecycleObservation {
        if (desiredInstallationOperator || catalogGenesisAuthoring) return PersistenceLifecycleObservation.UNAVAILABLE
        if (shutdown.get()) return PersistenceLifecycleObservation.UNAVAILABLE
        if (!startClaimed.get() || (deleting && !deletionClaimed.get())) return PersistenceLifecycleObservation.NOT_REQUESTED
        val participant = if (deleting) deletion else ordinary
        return when {
            participant.isReady() -> PersistenceLifecycleObservation.READY
            participant.preparationFinished() -> PersistenceLifecycleObservation.UNAVAILABLE
            else -> PersistenceLifecycleObservation.PENDING
        }
    }

    fun shutdownObservation(): PersistenceLifecycleObservation {
        if (!shutdown.get()) return PersistenceLifecycleObservation.NOT_REQUESTED
        if (!canReleaseTimer() || !timer.threadEnded()) return PersistenceLifecycleObservation.PENDING
        val timerState = timer.shutdownObservation()
        return if (timerState !== PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED) timerState else retainedShutdownObservation()
    }

    private fun retainedShutdownObservation(): PersistenceLifecycleObservation {
        val retained = ordinary.retainedCount() ?: return PersistenceLifecycleObservation.PENDING
        val retainedDeletion = deletion.retainedCount() ?: return PersistenceLifecycleObservation.PENDING
        val retainedCatalog = catalogCoordinator.retainedCount() ?: return PersistenceLifecycleObservation.PENDING
        val retainedRotation = if (epochRotationParticipant == null) {
            0
        } else {
            epochRotationParticipant.retainedCount() ?: return PersistenceLifecycleObservation.PENDING
        }
        return when {
            failed.get() || ordinary.cleanupFailed() || deletion.cleanupFailed() || catalogCoordinator.cleanupFailed() ||
                retained != 0 || retainedDeletion != 0 || retainedCatalog != 0 || retainedRotation != 0 || epochRotationParticipant?.cleanupFailed() == true ->
                PersistenceLifecycleObservation.UNKNOWN

            ordinary.usedWeakEvidence() || deletion.usedWeakEvidence() || catalogCoordinator.usedWeakEvidence() ||
                epochRotationParticipant?.usedWeakEvidence() == true ->
                PersistenceLifecycleObservation.DRIVER_CONTRACT_ONLY_ENDED

            else -> PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED
        }
    }

    fun snapshot(): PersistenceLifecycleSnapshot = PersistenceLifecycleSnapshot(
        shutdown.get(), ordinary.isReady(), deletionClaimed.get(), deletion.isReady(), timer.canAcceptStrong(),
        ordinary.retainedCount(), deletion.retainedCount(),
        ordinary.usedWeakEvidence() || deletion.usedWeakEvidence() || catalogCoordinator.usedWeakEvidence() ||
            epochRotationParticipant?.usedWeakEvidence() == true,
        failed.get() || ordinary.cleanupFailed() || deletion.cleanupFailed() || catalogCoordinator.cleanupFailed() ||
            epochRotationParticipant?.cleanupFailed() == true,
        catalogCoordinatorClaimed.get(), catalogCoordinator.isReady(), catalogCoordinator.retainedCount(),
        epochRotationClaimed.get(), epochRotationParticipant?.isReady() == true,
        if (epochRotationParticipant == null) 0 else epochRotationParticipant.retainedCount(),
    )

    private fun scan() {
        runCatching {
            while (true) {
                ordinary.scan()
                deletion.scan()
                catalogCoordinator.scan()
                epochRotationParticipant?.scan()
                epochRotation?.reconcile()
                if (shutdown.get() && participantsEnded() && shutdownScanFinished()) return
                persistenceLifecyclePark()
            }
        }.onFailure { failure ->
            failed.set(true)
            requestShutdown()
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure is Error) throw failure
        }
    }

    private fun shutdownScanFinished(): Boolean = ordinary.finishShutdownScan() && deletion.finishShutdownScan() && catalogCoordinator.finishShutdownScan() &&
        epochRotationParticipant?.finishShutdownScan() != false

    override fun toString(): String = "PersistenceJdbcDriverRoot(redacted)"
}
