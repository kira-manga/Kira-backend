package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unused single-worker ownership kernel, not a DataSource or physical cleanup authority.
 * Constructing this object starts nothing. A sealed/broken generation can never be reopened.
 */
internal class PersistenceFactoryWorker<I : Any, R : Any> private constructor(
    private val operations: PersistenceFactoryOperations<I, R>,
    private val loginPolicy: PersistenceLoginPolicy,
    private val clock: PersistenceNanoClock,
    private val probe: PersistenceFactorySchedulingProbe,
    private val rendezvous: PersistenceFactoryRendezvous<I, R>,
    private val ownedBinding: PersistencePhysicalFactoryBinding?,
    private val failedCreationRetirement: ((I) -> Unit)?,
) {
    constructor(
        operations: PersistenceFactoryOperations<I, R>,
        loginPolicy: PersistenceLoginPolicy,
        clock: PersistenceNanoClock = SystemPersistenceNanoClock,
        probe: PersistenceFactorySchedulingProbe = NoPersistenceFactoryProbe,
    ) : this(operations, loginPolicy, clock, probe, PersistenceFactoryRendezvous(), null, null)

    private val startClaimed = AtomicBoolean()
    private val sealClaimed = AtomicBoolean()
    private val observerClaimed = AtomicBoolean()
    private val bodyFailed = AtomicBoolean()
    private val ownedThread = ownedBinding?.let { binding ->
        PersistenceRetainedPlatformThread("kira-persistence-factory", ::runWorker).also(binding::retainOwnedWorker)
    }

    fun start(): PersistenceFactoryStart {
        check(ownedBinding == null)
        if (!startClaimed.compareAndSet(false, true)) return PersistenceFactoryStart.ALREADY_CLAIMED
        var starting = false
        return try {
            runCatching {
                starting = rendezvous.beginStart()
                if (!starting) return PersistenceFactoryStart.CLOSED
                probe.reached(PersistenceFactoryProbePoint.START_CLAIMED)
                val owned = Thread.ofPlatform()
                    .name("kira-persistence-factory")
                    .daemon(true)
                    .inheritInheritableThreadLocals(false)
                    .uncaughtExceptionHandler { _, _ -> }
                    .unstarted(::runWorker)
                rendezvous.retainThread(owned)
                probe.reached(PersistenceFactoryProbePoint.THREAD_RETAINED)
                if (!rendezvous.authorizeStart()) return PersistenceFactoryStart.CLOSED
                // A racing seal cannot report quiescence until this start attempt actually finishes.
                owned.start()
                PersistenceFactoryStart.STARTED
            }.getOrElse { failure ->
                rendezvous.breakGeneration(persistenceFactoryUnexpectedReason(failure))
                finishPersistenceFactoryFailure(failure)
                PersistenceFactoryStart.FAILED
            }
        } finally {
            if (starting) rendezvous.finishStart()
        }
    }

    fun request(input: I): PersistenceFactoryResult<R> {
        check(ownedBinding == null)
        if (Thread.currentThread().isInterrupted) return PersistenceFactoryResult.Refused(PersistenceFactoryFailure.INTERRUPTED)
        val admission = runCatching {
            // One original clock starts before admission. No accepted call retries for a worker slot.
            rendezvous.admit(input, PersistenceTimeBudget.start(loginPolicy.durationMillis, clock))
        }.getOrElse { failure ->
            finishPersistenceFactoryFailure(failure)
            return PersistenceFactoryResult.Refused(PersistenceFactoryFailure.COORDINATION_FAILED)
        }
        val attempt = admission.attempt ?: return requireNotNull(admission.refusal)
        return rendezvous.awaitResult(attempt)
    }

    /** First entry only can wait for the coordination lock. A claim alone is not the seal transition. */
    fun seal(): Boolean {
        check(ownedBinding == null)
        if (!sealClaimed.compareAndSet(false, true)) return false
        runCatching {
            probe.reached(PersistenceFactoryProbePoint.SEAL_CLAIMED)
            rendezvous.seal()
        }.onFailure { failure ->
            rendezvous.breakGeneration(persistenceFactoryUnexpectedReason(failure))
            finishPersistenceFactoryFailure(failure)
        }
        return true
    }

    fun snapshot(): PersistenceFactorySnapshot = rendezvous.snapshot()

    fun awaitReady(allowanceMillis: Long): PersistenceFactoryObservation = observe(allowanceMillis, termination = false)

    fun awaitTermination(allowanceMillis: Long): PersistenceFactoryObservation = observe(allowanceMillis, termination = true)

    /** One actual managed activation; contention is not a start claim or a queued activation. */
    fun startOwned(): PersistenceOwnedFactoryStart {
        val binding = checkNotNull(ownedBinding)
        val retained = checkNotNull(ownedThread)
        val refusal = binding.claimOwnedWorkerStart(startClaimed)
        if (refusal != null) return refusal
        return try {
            runCatching {
                if (binding.isClosed()) retained.forbidStart()
                when (retained.start()) {
                    PersistenceFactoryStart.STARTED -> PersistenceOwnedFactoryStart.STARTED
                    PersistenceFactoryStart.CLOSED -> PersistenceOwnedFactoryStart.CLOSED
                    PersistenceFactoryStart.ALREADY_CLAIMED -> PersistenceOwnedFactoryStart.ALREADY_CLAIMED
                    PersistenceFactoryStart.FAILED -> PersistenceOwnedFactoryStart.FAILED
                }
            }.getOrElse { failure ->
                // The exact failed start extent is already retained; never inspect its Throwable graph.
                finishPersistenceFactoryFailure(failure)
                PersistenceOwnedFactoryStart.FAILED
            }
        } finally {
            // A contended projection remains pending on the retained binding, for the fixed scanner.
            binding.reconcileCallers()
        }
    }

    /** Nonblocking logical seal only. The retained controller/scanner projects the F/G fence. */
    fun requestOwnedStop(): Boolean = checkNotNull(ownedBinding).requestOwnedStop()

    fun isOwnedReceiverReady(): Boolean = checkNotNull(ownedBinding).isOwnedReceiverReady()

    /** This does not certify record disposal or complete managed-root shutdown. */
    fun ownedThreadTermination(): PersistenceThreadTermination = checkNotNull(ownedThread).termination()

    fun ownedBodyFailed(): Boolean = bodyFailed.get() || (checkNotNull(ownedThread).hasBodyEnded() && !checkNotNull(ownedBinding).isClosed())

    private fun runWorker() {
        runCatching {
            workerCheckpoint(PersistenceFactoryProbePoint.WORKER_ENTERED)
            while (true) {
                val attempt = rendezvous.awaitWork() ?: return
                process(attempt)
            }
        }.onFailure { failure ->
            // All actually invoked callbacks/probes have unwound before this terminal job fact.
            bodyFailed.set(true)
            rendezvous.workerFailed(persistenceFactoryUnexpectedReason(failure))
            finishPersistenceFactoryFailure(failure)
        }
    }

    private fun process(attempt: PersistenceFactoryAttempt<I, R>) {
        workerCheckpoint(PersistenceFactoryProbePoint.BEFORE_WORK)
        if (rendezvous.beginWork(attempt) && create(attempt)) {
            workerCheckpoint(PersistenceFactoryProbePoint.RESULT_RETAINED)
            if (rendezvous.offer(attempt)) workerCheckpoint(PersistenceFactoryProbePoint.OFFER_PUBLISHED)
            val late = rendezvous.awaitDisposition(attempt)
            if (late != null) {
                operations.discard(attempt.input, late)
                rendezvous.discardReturned(attempt)
                requirePersistenceFactoryWorkerNotInterrupted()
            }
        } else {
            // No result is not no work: even a never-invoked owned create has an exact dispatched
            // physical entry. The real terminal resource phase must settle before F1 processing.
            failedCreationRetirement?.invoke(attempt.input)
        }
        workerCheckpoint(PersistenceFactoryProbePoint.SETTLING)
        // No per-job hook or callback may be added after this final worker settlement.
        rendezvous.settleWorker(attempt)
    }

    private fun create(attempt: PersistenceFactoryAttempt<I, R>): Boolean {
        val result = runCatching {
            operations.create(attempt.input, attempt.cancellation)
        }.getOrElse { failure ->
            // Only this exact invocation's nonfatal, noninterrupted, no-return path is recoverable.
            if (failure is InterruptedException || failure is Error) throw failure
            requirePersistenceFactoryWorkerNotInterrupted()
            rendezvous.creationFailed(attempt)
            return false
        }
        rendezvous.retainResult(attempt, result)
        requirePersistenceFactoryWorkerNotInterrupted()
        return true
    }

    private fun workerCheckpoint(point: PersistenceFactoryProbePoint) {
        probe.reached(point)
        requirePersistenceFactoryWorkerNotInterrupted()
    }

    private fun observe(allowanceMillis: Long, termination: Boolean): PersistenceFactoryObservation {
        check(ownedBinding == null)
        if (!observerClaimed.compareAndSet(false, true)) return PersistenceFactoryObservation.BUSY
        var interrupted = false
        try {
            return runCatching {
                val budget = PersistenceTimeBudget.start(allowanceMillis, clock)
                interrupted = Thread.interrupted()
                probe.reached(PersistenceFactoryProbePoint.OBSERVER_ENTERED)
                var observation: PersistenceFactoryObservation? = null
                while (observation == null) {
                    try {
                        if (Thread.interrupted()) interrupted = true
                        observation = if (termination) observeTermination(budget) else rendezvous.awaitReady(budget)
                    } catch (_: InterruptedException) {
                        interrupted = true
                        // Observation continues, never with a new allowance or an unlimited zero wait.
                    }
                }
                observation
            }.getOrElse { failure ->
                rendezvous.breakGeneration(persistenceFactoryUnexpectedReason(failure))
                if (failure is InterruptedException) interrupted = true
                if (failure is Error) throw failure
                PersistenceFactoryObservation.FAILED
            }
        } finally {
            observerClaimed.set(false)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun observeTermination(budget: PersistenceTimeBudget): PersistenceFactoryObservation {
        val target = rendezvous.awaitJoinTarget(budget)
        target.refusal?.let { return it }
        val owned = target.thread ?: return PersistenceFactoryObservation.TERMINATED
        if (owned === Thread.currentThread()) return PersistenceFactoryObservation.BUSY
        while (owned.isAlive) {
            val remaining = persistenceFactoryRemainingMillis(budget)
            if (remaining == 0L) return PersistenceFactoryObservation.TIMEOUT
            owned.join(remaining)
        }
        // The rendezvous already proved no pending/future start, including a retained NEW Thread.
        return PersistenceFactoryObservation.TERMINATED
    }

    override fun toString(): String = "PersistenceFactoryWorker(redacted)"

    companion object {
        /** The owned mode has exact record/candidate types and accepts neither a clock nor a probe. */
        fun owned(
            binding: PersistencePhysicalFactoryBinding,
            operations: PersistenceOwnedFactoryOperations,
            loginPolicy: PersistenceLoginPolicy,
        ): PersistenceFactoryWorker<PersistencePhysicalRecord, PersistenceJdbcCandidate> = PersistenceFactoryWorker(
            operations,
            loginPolicy,
            SystemPersistenceNanoClock,
            NoPersistenceFactoryProbe,
            binding.rendezvous,
            binding,
            operations::awaitFailedCreationRetirement,
        )
    }
}
