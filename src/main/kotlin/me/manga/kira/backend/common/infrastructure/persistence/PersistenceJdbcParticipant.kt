package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One fixed ordinary or cold-deletion generation, entirely retained before any actor starts. */
internal class PersistenceJdbcParticipant(private val root: PersistenceJdbcDriverRoot, capacity: Int, private val deletion: Boolean) {
    private val binding = PersistencePhysicalFactoryBinding(capacity, root.shutdown, this)
    private val loginPolicy = if (deletion) PersistenceNativeSettings.deletionLoginPolicy else root.endpoint.loginPolicy
    private val worker = PersistenceFactoryWorker.owned(binding, PersistenceJdbcFactoryOperations(binding), loginPolicy)
    private val runners = Array(capacity) { PersistenceTerminalRunner(it) }
    private val controller = PersistenceRetainedPlatformThread("kira-persistence-controller", ::run)
    private val preparationEnded = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val ordinary = AtomicReference<PersistencePgDriverOpening?>()
    private val trackedWeak = AtomicReference<PersistencePgDriverOpening?>()
    private val trackedStrong = AtomicReference<PersistencePgDriverOpening?>()

    fun start(): PersistenceFactoryStart = controller.start()

    fun forbidStarts() {
        controller.forbidStart()
        worker.requestOwnedStop()
        runners.forEach(PersistenceTerminalRunner::forbidStart)
    }

    fun isReady(): Boolean = binding.admissionOpen.get() && actorsPermitAdmission() && (!deletion || root.timer.canAcceptStrong())

    fun preparationFinished(): Boolean = preparationEnded.get() || controller.termination() === PersistenceThreadTermination.INERT

    fun request(): PersistenceFactoryResult<PersistenceJdbcCandidate> = PersistenceOwnedFactoryRequest(binding, loginPolicy.durationMillis, this).execute()

    /** Selection happens after the original request budget exists, before any reservation or dispatch. */
    fun selectOpening(): PersistencePgDriverOpening? = if (!isReady()) {
        null
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
        return exact && (!deletion || strong) && (!strong || (opening.timer === root.timer && root.timer.canAcceptStrong()))
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
            if (!binding.isClosed() && (if (deletion) trackedStrong.get() != null else ordinary.get() != null)) {
                startWorkers()
            } else {
                worker.requestOwnedStop()
                runners.forEach(PersistenceTerminalRunner::requestStop)
            }
        } finally {
            preparationEnded.set(true)
            if (!deletion) root.timer.finishMetadataIfAbsent()
        }
    }

    private fun prepare() {
        if (binding.isClosed()) return
        if (deletion) {
            while (!root.ordinary.preparationFinished() && !binding.isClosed()) persistenceLifecyclePark()
            if (binding.isClosed() || !root.retainedDriver.isConstructed()) return
            while (!root.timer.canAcceptStrong() && !root.timer.preparationFailed() && !binding.isClosed()) persistenceLifecyclePark()
            if (!binding.isClosed() && root.timer.canAcceptStrong()) {
                trackedStrong.set(optional { opening(PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION) })
            }
        } else {
            root.retainedDriver.construct()
            ordinary.set(opening(PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER))
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

    private fun opening(policy: PersistenceDriverAttemptPolicy): PersistencePgDriverOpening = PersistencePgDriverOpening.prepareRetained(
        root.retainedDriver,
        root.endpoint,
        policy,
        root.pathStyle,
        if (policy.evidence === PersistenceDriverEvidencePolicy.TRACKED_CONJUNCTION) root.timer else null,
    )

    private inline fun <T> optional(operation: () -> T): T? = runCatching(operation).getOrElse { failure ->
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        if (failure is Error) throw failure
        null
    }

    override fun toString(): String = "PersistenceJdbcParticipant(redacted)"
}
