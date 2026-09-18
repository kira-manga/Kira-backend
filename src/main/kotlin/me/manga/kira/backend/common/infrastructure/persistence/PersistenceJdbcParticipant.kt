package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationAttemptV1
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One fixed role generation, entirely retained before any actor starts. Roles never lend their physical slots. */
internal class PersistenceJdbcParticipant(private val root: PersistenceJdbcDriverRoot, capacity: Int, private val role: PersistenceJdbcParticipantRole) {
    constructor(root: PersistenceJdbcDriverRoot, capacity: Int, deletion: Boolean) :
        this(root, capacity, if (deletion) PersistenceJdbcParticipantRole.DELETION else PersistenceJdbcParticipantRole.ORDINARY)

    private val binding = PersistencePhysicalFactoryBinding(capacity, root.shutdown, this)
    private val versionBoundMaterial = if (role === PersistenceJdbcParticipantRole.EPOCH_ROTATION) null else root.versionBoundPools?.material(role)
    private val rotationMaterial = if (role === PersistenceJdbcParticipantRole.EPOCH_ROTATION) root.epochRotationMaterial else null
    private val loginPolicy = rotationMaterial?.opening?.loginPolicy ?: versionBoundMaterial?.loginPolicy ?: when (role) {
        PersistenceJdbcParticipantRole.ORDINARY -> root.endpoint.loginPolicy
        PersistenceJdbcParticipantRole.DELETION -> PersistenceNativeSettings.deletionLoginPolicy
        PersistenceJdbcParticipantRole.CATALOG_COORDINATOR -> PersistenceNativeSettings.catalogCoordinatorLoginPolicy
        PersistenceJdbcParticipantRole.EPOCH_ROTATION -> PersistenceNativeSettings.epochRotationLoginPolicy
    }
    private val strict = role !== PersistenceJdbcParticipantRole.ORDINARY
    private val operatorCoordinator =
        (root.desiredInstallationOperator || root.catalogGenesisAuthoring) && role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR
    private val strictPolicy = when (role) {
        PersistenceJdbcParticipantRole.ORDINARY -> null
        PersistenceJdbcParticipantRole.DELETION -> PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION
        PersistenceJdbcParticipantRole.CATALOG_COORDINATOR -> PersistenceDriverAttemptPolicy.TRACKED_CATALOG_CONJUNCTION
        PersistenceJdbcParticipantRole.EPOCH_ROTATION -> PersistenceDriverAttemptPolicy.TRACKED_EPOCH_ROTATION_CONJUNCTION
    }
    private val worker = PersistenceFactoryWorker.owned(binding, PersistenceJdbcFactoryOperations(binding), loginPolicy)
    private val runners = Array(capacity) { PersistenceTerminalRunner(it) }
    private val controller = PersistenceRetainedPlatformThread("kira-persistence-controller", ::run)
    private val preparationEnded = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val ordinary = AtomicReference<PersistencePgDriverOpening?>()
    private val trackedWeak = AtomicReference<PersistencePgDriverOpening?>()
    private val trackedStrong = AtomicReference<PersistencePgDriverOpening?>()

    fun start(): PersistenceFactoryStart = controller.start()

    fun forbidStarts(): Boolean {
        controller.forbidStart()
        val first = worker.requestOwnedStop()
        runners.forEach(PersistenceTerminalRunner::forbidStart)
        return first
    }

    internal fun shutdownRequested(): Boolean = binding.isClosed()

    /** Fixed ledger observations only; not a sealed native/terminal-completion certificate. */
    internal fun deletionPoolIdle(lifecycle: PoolLifecycle): Boolean = poolIdle(lifecycle, PersistenceJdbcParticipantRole.DELETION, 4)

    internal fun catalogCoordinatorPoolIdle(lifecycle: PoolLifecycle): Boolean = poolIdle(lifecycle, PersistenceJdbcParticipantRole.CATALOG_COORDINATOR, 1)

    internal fun ownsPoolIdentity(identity: PersistenceJdbcPoolIdentity): Boolean = binding.poolIdentity === identity

    private fun poolIdle(lifecycle: PoolLifecycle, expected: PersistenceJdbcParticipantRole, size: Int): Boolean {
        if (role !== expected || !isReady() || ownershipLockHeld()) return false
        if (!binding.rendezvous.lock.tryLock()) return false
        return try {
            if (!binding.ledger.lock.tryLock()) return false
            try {
                binding.ledger.entries.size == size && binding.ledger.entries.all { entry ->
                    entry != null && permits(entry) && entry.policy === strictPolicy &&
                        entry.jdbc.poolIdleEligibleLocked(lifecycle)
                }
            } finally {
                binding.ledger.lock.unlock()
            }
        } finally {
            binding.rendezvous.lock.unlock()
        }
    }

    fun isReady(): Boolean = binding.admissionOpen.get() && actorsPermitAdmission() && (!strict || root.timer.canAcceptStrong())

    fun preparationFinished(): Boolean = preparationEnded.get() || controller.termination() === PersistenceThreadTermination.INERT

    /** Inert handle only: the executing original caller creates its control/budget before selecting an opening. */
    internal fun prepareRequest(): PersistenceOwnedFactoryRequest {
        check(role !== PersistenceJdbcParticipantRole.EPOCH_ROTATION)
        return PersistenceOwnedFactoryRequest(binding, loginPolicy.durationMillis, this)
    }

    internal fun isEpochRotation(): Boolean = role === PersistenceJdbcParticipantRole.EPOCH_ROTATION

    internal fun ownsEpochRotation(resource: EpochRotationPersistence): Boolean = isEpochRotation() && root.epochRotation === resource

    internal fun prepareEpochRotationRequest(
        resource: EpochRotationPersistence,
        attempt: CatalogEpochRotationAttemptV1,
    ): PersistenceEpochRotationFactoryRequest {
        check(ownsEpochRotation(resource))
        return PersistenceEpochRotationFactoryRequest(binding, this, resource, attempt)
    }

    fun request(): PersistenceFactoryResult<PersistenceJdbcCandidate> = prepareRequest().execute()

    fun requestPoolConnection(): PersistenceFactoryResult<PhysicalJdbcFacade> = prepareRequest().executePoolConnection()

    /** Selection happens after the original request budget exists, before any reservation or dispatch. */
    fun selectOpening(): PersistencePgDriverOpening? = if (!isReady()) {
        null
    } else if (strict) {
        trackedStrong.get() // Never degrade a coordinator/deletion attempt to another role or evidence policy.
    } else if (root.timer.canAcceptStrong()) {
        trackedStrong.get() ?: trackedWeak.get() ?: ordinary.get()
    } else {
        trackedWeak.get() ?: ordinary.get()
    }

    /** Only concrete atomic actor facts here: this is also called beneath the F/G admission/LIVE locks. */
    fun permits(entry: PersistencePhysicalEntry): Boolean {
        if (!actorsPermitAdmission() || !binding.admissionOpen.get()) return false
        val opening = entry.driverOpening ?: return false
        val strong = entry.policy.evidence === PersistenceDriverEvidencePolicy.TRACKED_CONJUNCTION
        val exact = opening === ordinary.get() || opening === trackedWeak.get() || opening === trackedStrong.get()
        return exact && (!strict || (strong && entry.policy === strictPolicy)) &&
            (!strong || (opening.timer === root.timer && root.timer.canAcceptStrong()))
    }

    /** The shared scanner only does fixed-size bookkeeping/mailbox work, never driver/close/abort. */
    fun scan() {
        val controllerFailed = controller.hasBodyEnded() && !binding.isClosed()
        if (runners.any(PersistenceTerminalRunner::hasFailed) || worker.ownedBodyFailed() || controllerFailed) {
            failed.set(true)
            worker.requestOwnedStop()
        }
        binding.reconcileCallers()
        for (slot in runners.indices) {
            val work = binding.completion.retirementAt(slot)
            if (work != null && !work.isAssigned()) runners[slot].submit(work)
            binding.completion.scanReclamation(slot)
        }
        if (binding.isClosed() && binding.completion.allBodiesEnded()) runners.forEach(PersistenceTerminalRunner::requestStop)
    }

    /** The scanner may exit only after a conclusive pass made AFTER actual no-future-actor/work drain. */
    fun finishShutdownScan(): Boolean {
        if (!binding.isClosed() || !threadsEnded() || !recordsEnded()) return false
        if (!binding.reconcileCallers()) return false
        return runners.indices.all(binding.completion::scanReclamation)
    }

    fun threadsEnded(): Boolean = controller.termination().ended() && worker.ownedThreadTermination().ended() && runners.all { it.termination().ended() }

    fun recordsEnded(): Boolean = binding.completion.allBodiesEnded()

    fun retainedCount(): Int? = binding.completion.retainedCount()

    internal fun ownershipLockHeld(): Boolean = binding.ownershipLockHeld()

    fun usedWeakEvidence(): Boolean = binding.completion.weakEvidence.get()

    fun cleanupFailed(): Boolean = failed.get() || binding.completion.cleanupFailure.get()

    private fun run() {
        runCatching {
            prepareAndStart()
            while (!binding.isClosed()) persistenceLifecyclePark()
            drainActors()
        }.onFailure { failure ->
            failed.set(true)
            worker.requestOwnedStop()
            runners.forEach(PersistenceTerminalRunner::forbidStart)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure is Error) throw failure
        }
    }

    private fun drainActors() {
        do {
            // A failed scanner must not strand an empty F1 on its Condition. The controller only
            // projects the existing stop/caller facts; it never replaces terminal dispatch/reclaim.
            binding.reconcileCallers()
            if (binding.completion.allBodiesEnded()) runners.forEach(PersistenceTerminalRunner::requestStop)
            if (!worker.ownedThreadTermination().ended() || !runners.all { it.termination().ended() }) persistenceLifecyclePark()
        } while (!worker.ownedThreadTermination().ended() || !runners.all { it.termination().ended() })
    }

    private fun prepareAndStart() {
        try {
            prepare()
            if (!binding.isClosed() && (if (strict) trackedStrong.get() != null else ordinary.get() != null)) {
                startWorkers()
            } else {
                worker.requestOwnedStop()
                runners.forEach(PersistenceTerminalRunner::requestStop)
            }
        } finally {
            preparationEnded.set(true)
            if (!strict || operatorCoordinator) root.timer.finishMetadataIfAbsent()
        }
    }

    private fun prepare() {
        if (binding.isClosed()) return
        if (strict) {
            if (operatorCoordinator) {
                // Same retained bootstrap, globals/logging/defaults/ABI checks as ordinary preparation, on this original controller.
                // No ordinary opening or worker is constructed to make an operator-only root reachable.
                root.retainedDriver.construct()
                root.timer.publishMetadata(optional { root.retainedDriver.timerAccess() })
            } else {
                while (!root.ordinary.preparationFinished() && !binding.isClosed()) persistenceLifecyclePark()
            }
            if (binding.isClosed() || !root.retainedDriver.isConstructed()) return
            while (!root.timer.canAcceptStrong() && !root.timer.preparationFailed() && !binding.isClosed()) persistenceLifecyclePark()
            if (!binding.isClosed() && root.timer.canAcceptStrong()) {
                trackedStrong.set(optional { opening(checkNotNull(strictPolicy)) })
            }
        } else {
            root.retainedDriver.construct()
            ordinary.set(opening(PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER))
            // A healthy source-only runtime never silently substitutes a tracked socket/provider
            // or treats successful ABI linkage as native/deployment qualification.
            if (root.sourceOnly) return
            root.timer.publishMetadata(optional { root.retainedDriver.timerAccess() })
            trackedWeak.set(optional { opening(PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT) })
            if (trackedWeak.get() != null) trackedStrong.set(opening(PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION))
        }
    }

    private fun startWorkers() {
        for (runner in runners) {
            if (binding.isClosed()) return
            val result = runner.start()
            check(result === PersistenceFactoryStart.STARTED || (binding.isClosed() && result === PersistenceFactoryStart.CLOSED))
        }
        while ((!runners.all(PersistenceTerminalRunner::isReady) || !root.scannerReady()) && !binding.isClosed()) persistenceLifecyclePark()
        if (binding.isClosed()) return
        var start = worker.startOwned()
        // CONTENDED has made no actual start claim. Never retry an entered/failed/uncertain Thread.start.
        while (start === PersistenceOwnedFactoryStart.CONTENDED && !binding.isClosed()) {
            persistenceLifecyclePark()
            start = worker.startOwned()
        }
        check(start === PersistenceOwnedFactoryStart.STARTED || (binding.isClosed() && start === PersistenceOwnedFactoryStart.CLOSED))
        while (!worker.isOwnedReceiverReady() && !binding.isClosed()) persistenceLifecyclePark()
        if (!binding.isClosed() && root.scannerReady() && runners.all(PersistenceTerminalRunner::isReady)) binding.admissionOpen.set(true)
    }

    private fun actorsPermitAdmission(): Boolean = !binding.isClosed() && !failed.get() &&
        controller.startPhase() === PersistenceThreadStartPhase.RETURNED && !controller.hasBodyEnded() &&
        root.scannerReady() && !worker.ownedBodyFailed() && runners.all(PersistenceTerminalRunner::isReady)

    private fun opening(policy: PersistenceDriverAttemptPolicy): PersistencePgDriverOpening {
        val timer = if (policy.evidence === PersistenceDriverEvidencePolicy.TRACKED_CONJUNCTION) root.timer else null
        val configured = rotationMaterial?.opening?.also { check(it.policy === policy) } ?: versionBoundMaterial?.opening(policy)
        return if (configured == null) {
            PersistencePgDriverOpening.prepareRetained(root.retainedDriver, root.endpoint, policy, root.pathStyle, timer)
        } else {
            PersistencePgDriverOpening.prepareRetained(root.retainedDriver, configured, timer)
        }
    }

    private inline fun <T> optional(operation: () -> T): T? = runCatching(operation).getOrElse { failure ->
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        if (failure is Error) throw failure
        null
    }

    override fun toString(): String = "PersistenceJdbcParticipant(redacted)"
}

internal enum class PersistenceJdbcParticipantRole { ORDINARY, DELETION, CATALOG_COORDINATOR, EPOCH_ROTATION }
