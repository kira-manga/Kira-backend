package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One retained inert Hikari, its authentic callers/Workers and one shutdown obligation. No second physical registry or waiter. */
internal class PoolLifecycle(private val pool: HikariDataSource, private val owner: PersistenceJdbcLifecycleOwner) {
    private val issuance = Any()
    private val gate = Any()
    private val actors = PoolActorCustody(this, gate)
    private val receipt = ShutdownReceipt.prepare(this)
    private val accepted = AtomicBoolean()
    private val firstClose = AtomicReference<CloseAttempt?>()
    private var shutdownBudget: PersistenceTimeBudget? = null

    @Volatile
    private var businessSealed = false

    @Volatile
    private var installation = Installation.NEW
    private var startup = Startup.NEW
    private var startupFrame: PoolCallFrame? = null
    private var acquisitions = 0L
    private var operations = 0L
    private var futureEntries = 0L

    /** Configure only the exact inert stock Hikari, after its owner is retained and before the first initializing call. */
    fun installThreadFactory(): Boolean {
        if (owner.ownershipLockHeld() || PoolCallFrames.current() != null || PoolActorCustody.currentThreadOwnsActorFrame()) return false
        synchronized(gate) {
            if (installation !== Installation.NEW || businessSealed || acquisitions != 0L) return false
            installation = Installation.INSTALLING
        }
        var installed = false
        try {
            if (!profileSupported(installed = false) || pool.isRunning || pool.isClosed) return false
            actors.installOn(pool)
            synchronized(gate) { installation = Installation.INSTALLED }
            installed = true
            return true
        } finally {
            if (!installed) {
                synchronized(gate) {
                    installation = Installation.REFUSED
                    actors.failLocked(PoolActorFault.UNSUPPORTED_PROFILE)
                }
            }
        }
    }

    /** Actor/profile admission only, not physical readiness or proof of historically immutable process properties. */
    fun businessReady(): Boolean {
        if (owner.ownershipLockHeld()) return false
        val installed = synchronized(gate) { installation === Installation.INSTALLED }
        if (!installed) return false
        if (!profileSupported(installed = true)) return false
        val localStartup = synchronized(gate) {
            startupFrame?.takeIf { PoolCallFrames.current() === it && it.authentic(this, issuance) && it.active() }
        }
        val published = localStartup != null && pool.isRunning // Outside F/G/T and the admission monitor.
        return synchronized(gate) {
            !businessSealed && !actors.failedLocked() && when (startup) {
                Startup.NEW, Startup.READY -> true
                Startup.INITIALIZING -> published && startupFrame === localStartup
                Startup.FAILED -> false
            }
        }
    }

    /** Only a nonblocking one-way seal recheck after full outside-lock readiness, never independent physical/native readiness. */
    fun businessAdmissionOpen(): Boolean = installation === Installation.INSTALLED && !businessSealed

    private fun profileSupported(installed: Boolean): Boolean {
        var supported = false
        try {
            supported = actors.profileSupported(pool, installed)
            return supported
        } finally {
            if (!supported) synchronized(gate) { actors.failLocked(PoolActorFault.UNSUPPORTED_PROFILE) }
        }
    }

    /** Positive provenance only. Core MUST reject a nonnull stale lease credential before considering this separate pool authority. */
    fun isAuthenticPoolCaller(): Boolean {
        if (owner.ownershipLockHeld()) return false
        return synchronized(gate) { installation === Installation.INSTALLED && creatorCompletionLocked() != null }
    }

    /** Caller retains the ticket BEFORE enter/Hikari. This original budget is never replaced or restarted. */
    fun prepareAcquisition(budget: PersistenceTimeBudget): Acquisition {
        check(!owner.ownershipLockHeld())
        return Acquisition.prepare(this, issuance, Thread.currentThread(), budget)
    }

    /** Nonwaiting handoff only. Failure never guesses a close/eviction against a captured ambiguous handle. */
    fun handoff(ticket: Acquisition): ShutdownReceipt? {
        if (!ticket.frame.authentic(this, issuance) || !ticket.frame.hasEntered()) return null
        val shared = requestShutdown(ticket.frame.budget) ?: return null
        ticket.accept(issuance, shared)
        return shared
    }

    fun requestShutdown(): ShutdownReceipt? = requestShutdownWithBudget(null)

    fun requestShutdown(budget: PersistenceTimeBudget): ShutdownReceipt? = requestShutdownWithBudget(budget)

    /** Shutdown admission is not factory sealing: the authentic close still needs its late assassin/inline work. */
    private fun requestShutdownWithBudget(supplied: PersistenceTimeBudget?): ShutdownReceipt? {
        if (owner.ownershipLockHeld()) return null
        val original = synchronized(gate) { shutdownBudget } ?: supplied ?: PersistenceTimeBudget.start(10_000)
        synchronized(gate) {
            businessSealed = true
            if (shutdownBudget == null) shutdownBudget = original
        }
        owner.requestShutdown()
        accepted.set(true)
        return receipt
    }

    /**
     * One synchronous actual close, and no waiter. Lazy construction must have ended BEFORE this claim:
     * Hikari's closed check precedes its lazy-init lock, so closing an active first acquisition can lose the only close.
     */
    fun closePool(): PoolShutdownInvocation {
        val enclosing = PoolCallFrames.current()
        if (enclosing != null) {
            return if (enclosing.kind === PoolCallKind.ACQUISITION) PoolShutdownInvocation.ACTIVE_ACQUISITION else PoolShutdownInvocation.ACTIVE_POOL_FRAME
        }
        if (PoolActorCustody.currentThreadOwnsActorFrame()) return PoolShutdownInvocation.ACTIVE_POOL_ACTOR
        if (owner.ownershipLockHeld()) return PoolShutdownInvocation.OWNERSHIP_LOCK_HELD
        requestShutdown()
        val budget = synchronized(gate) { requireNotNull(shutdownBudget) }
        val frame = PoolCallFrame.prepare(this, issuance, PoolCallKind.SHUTDOWN, Thread.currentThread(), budget)
        val attempt = CloseAttempt(PersistenceOwnedFactoryCaller.capture(), frame)
        check(frame.claimEntry(issuance, null))
        var admitted = false
        var setupReturned = false
        try {
            PoolCallFrames.install(frame)
            val decision = synchronized(gate) {
                when {
                    firstClose.get() != null -> PoolShutdownInvocation.ALREADY_CLAIMED

                    // Conservative for all admitted getConnection frames, including the first potentially constructing one.
                    acquisitions != 0L || startup === Startup.INITIALIZING -> PoolShutdownInvocation.INITIALIZATION_PENDING

                    else -> {
                        check(firstClose.compareAndSet(null, attempt))
                        frame.activate(issuance)
                        admitted = true
                        null
                    }
                }
            }
            setupReturned = true
            if (decision != null) return decision
        } finally {
            try {
                if (!setupReturned) recordBookkeepingFailure()
            } finally {
                if (!admitted) refuseEntry(frame)
            }
        }
        invokeClose(attempt)
        return PoolShutdownInvocation.RETURNED
    }

    private fun invokeClose(attempt: CloseAttempt) {
        var failure: Throwable? = null
        try {
            var returned = false
            runCatching {
                try {
                    attempt.outcome.set(PersistenceTerminalCall.RUNNING)
                    pool.close()
                    attempt.outcome.set(PersistenceTerminalCall.RETURNED)
                    returned = true
                } finally {
                    if (!returned) attempt.outcome.set(PersistenceTerminalCall.THREW)
                }
            }.onFailure { problem ->
                failure = problem
                if (problem is InterruptedException) attempt.interrupted.set(true)
            }
        } finally {
            try {
                captureCloseBookkeepingFailure(attempt) {
                    if (failure is InterruptedException) Thread.currentThread().interrupt()
                    if (attempt.caller.sampleOutsideLocks() != null) attempt.interrupted.set(true)
                }?.let { failure = combine(failure, it) }
            } finally {
                try {
                    captureCloseBookkeepingFailure(attempt) { attempt.caller.restoreAfterFailure() }?.let { failure = combine(failure, it) }
                } finally {
                    finishCloseFrame(attempt)?.let { failure = combine(failure, it) }
                }
            }
        }
        failure?.let { throw it }
    }

    private fun finishCloseFrame(attempt: CloseAttempt): Throwable? = captureCloseBookkeepingFailure(attempt) {
        check(attempt.frame.claimEnd(issuance))
        PoolCallFrames.restore(attempt.frame)
        synchronized(gate) {
            attempt.frame.finish(issuance)
            attempt.ended.set(true) // Only after actual flag/TL/bookkeeping tails. A failed restoration leaves this pending.
        }
    }

    private inline fun captureCloseBookkeepingFailure(attempt: CloseAttempt, operation: () -> Unit): Throwable? {
        var returned = false
        return runCatching {
            try {
                operation()
                returned = true
            } finally {
                if (!returned) {
                    attempt.bookkeepingFailed.set(true)
                    synchronized(gate) { actors.failLocked(PoolActorFault.BOOKKEEPING_FAILED) }
                }
            }
        }.exceptionOrNull()
    }

    fun acquisitionsEnded(): Boolean = synchronized(gate) { businessSealed && acquisitions == 0L }

    fun activeAcquisitions(): Long = synchronized(gate) { acquisitions }

    fun firstCloseOutcome(): PersistenceTerminalCall = firstClose.get()?.outcome?.get() ?: PersistenceTerminalCall.NOT_INVOKED

    fun closeInterruptionObserved(): Boolean = firstClose.get()?.interrupted?.get() == true

    fun actorSnapshot(): PoolActorSnapshot = synchronized(gate) { actors.snapshotLocked(futureEntries, operations) }

    internal fun ownershipLockHeld(): Boolean = owner.ownershipLockHeld()

    internal fun sealBusinessForActorFaultLocked() {
        check(Thread.holdsLock(gate))
        businessSealed = true
    }

    internal fun creatorCompletionLocked(): PoolCreatorCompletion? {
        check(Thread.holdsLock(gate))
        val frame = PoolCallFrames.current()
        if (frame != null) return frame.completion.takeIf { frame.authentic(this, issuance) && frame.active() }
        return actors.actualCreatorLocked()
    }

    internal fun closedPopulationReadyLocked(): Boolean {
        check(Thread.holdsLock(gate))
        return businessSealed && accepted.get() && acquisitions == 0L && operations == 0L && futureEntries == 0L && firstClose.get()?.ended?.get() == true
    }

    private fun enterAcquisition(ticket: Acquisition): Boolean {
        val frame = ticket.frame
        if (!frame.authentic(this, issuance) || owner.ownershipLockHeld()) return false
        val installed = synchronized(gate) { installation === Installation.INSTALLED }
        if (installed && !businessReady()) return false
        return enterFrame(frame, null)
    }

    private fun enterOperation(operation: Operation): Boolean {
        if (!operation.frame.authentic(this, issuance) || owner.ownershipLockHeld()) return false
        return enterFrame(operation.frame, operation)
    }

    private fun enterFrame(frame: PoolCallFrame, operation: Operation?): Boolean {
        if (!frame.claimEntry(issuance, PoolCallFrames.current())) return false
        var entered = false
        var setupReturned = false
        try {
            PoolCallFrames.install(frame) // All fallible caller-local publication precedes counted/native admission.
            val admitted = synchronized(gate) {
                if (persistenceFactoryRemainingMillis(frame.budget) == 0L) return@synchronized false
                if (operation == null) {
                    if (businessSealed || installation === Installation.INSTALLING) return@synchronized false
                    if (startup === Startup.INITIALIZING || startup === Startup.FAILED) return@synchronized false
                    val count = Math.addExact(acquisitions, 1L)
                    if (installation === Installation.INSTALLED && startup === Startup.NEW) {
                        startup = Startup.INITIALIZING
                        startupFrame = frame
                    }
                    acquisitions = count
                } else {
                    if (!operation.entitlement.canEnterLocked(issuance, operation)) return@synchronized false
                    val count = Math.addExact(operations, 1L)
                    check(futureEntries > 0L)
                    operation.entitlement.consumeLocked(issuance)
                    futureEntries--
                    operations = count
                }
                frame.activate(issuance)
                entered = true
                true
            }
            setupReturned = true
            return admitted
        } finally {
            try {
                if (!setupReturned) recordBookkeepingFailure()
            } finally {
                if (!entered) refuseEntry(frame)
            }
        }
    }

    private fun refuseEntry(frame: PoolCallFrame) {
        var restored = false
        try {
            PoolCallFrames.restoreUnadmitted(frame)
            restored = true
        } finally {
            try {
                if (!restored) recordBookkeepingFailure()
            } finally {
                frame.refuse(issuance)
            }
        }
    }

    private fun recordBookkeepingFailure() = synchronized(gate) { actors.failLocked(PoolActorFault.BOOKKEEPING_FAILED) }

    private fun endFrame(frame: PoolCallFrame): Boolean {
        if (!frame.authentic(this, issuance) || PoolCallFrames.current() !== frame || owner.ownershipLockHeld()) return false
        if (!frame.claimEnd(issuance)) return false
        var sampled = false
        var initialized = false
        var restored = false
        var ended = false
        try {
            try {
                val initializing = synchronized(gate) { startupFrame === frame }
                // Exact stock publication observation, outside F/G/T and the admission monitor.
                initialized = initializing && pool.isRunning
                sampled = true
            } finally {
                try {
                    if (!sampled) recordBookkeepingFailure()
                } finally {
                    // Even a failing publication sample cannot bypass restoration of the authentic enclosing lineage.
                    PoolCallFrames.restore(frame)
                    restored = true
                }
            }
        } finally {
            try {
                if (restored) {
                    finishRestoredFrame(frame, sampled && initialized)
                    ended = true
                }
            } finally {
                // Failed restoration retains ENDING and its count. A successful end with another failure stays sticky UNKNOWN.
                if (!ended) recordBookkeepingFailure()
            }
        }
        return true
    }

    private fun finishRestoredFrame(frame: PoolCallFrame, initialized: Boolean) = synchronized(gate) {
        if (frame.kind === PoolCallKind.ACQUISITION) {
            check(acquisitions > 0L)
            if (startupFrame === frame) {
                startup = if (initialized) Startup.READY else Startup.FAILED
                if (!initialized) actors.failLocked(PoolActorFault.INITIALIZATION_FAILED)
                startupFrame = null
            }
            acquisitions--
        } else {
            check(operations > 0L)
            operations--
        }
        frame.finish(issuance)
    }

    private fun failBeforeEnd(frame: PoolCallFrame): Boolean {
        if (!frame.authentic(this, issuance) || !frame.active() || PoolCallFrames.current() !== frame) return false
        synchronized(gate) { actors.failLocked(PoolActorFault.BOOKKEEPING_FAILED) }
        return true
    }

    private fun recordReturnIncidentBeforeEnd(operation: Operation): Boolean {
        if (owner.ownershipLockHeld()) return false
        return synchronized(gate) {
            val frame = operation.frame
            val entitlement = operation.entitlement
            if (!frame.authentic(this, issuance) || frame.kind !== PoolCallKind.RETURN || !frame.active()) {
                return@synchronized false
            }
            if (PoolCallFrames.current() !== frame || !entitlement.authentic(this, issuance) || !entitlement.preparedForLocked(issuance, operation)) {
                return@synchronized false
            }
            // Only the exact prepared current RETURN can reveal broken retained accounting.
            if (!entitlement.consumedLocked(issuance) || operations <= 0L) {
                actors.failLocked(PoolActorFault.BOOKKEEPING_FAILED)
                return@synchronized false
            }
            actors.recordCallerIncidentLocked()
            true // No holder/count/frame end, new allowance or native/epoch disposition.
        }
    }

    private fun prepareLeaseEntitlement(ticket: Acquisition): LeaseEntitlement? {
        if (!ticket.frame.authentic(this, issuance) || owner.ownershipLockHeld()) return null
        val entitlement = LeaseEntitlement.prepare(this, issuance, Thread.currentThread())
        return synchronized(gate) {
            if (businessSealed || !ticket.frame.active() || PoolCallFrames.current() !== ticket.frame) return null
            if (!ticket.hasCaptured() || ticket.entitlement != null) return null
            val count = Math.addExact(futureEntries, 1L)
            ticket.entitlement = entitlement
            futureEntries = count
            entitlement
        }
    }

    private fun prepareOperation(entitlement: LeaseEntitlement, kind: PoolCallKind, budget: PersistenceTimeBudget): Operation? {
        if (!entitlement.authentic(this, issuance) || owner.ownershipLockHeld()) return null
        val frame = PoolCallFrame.prepare(this, issuance, kind, Thread.currentThread(), budget)
        val operation = Operation.prepare(this, frame, entitlement)
        return synchronized(gate) { if (entitlement.prepareLocked(issuance, operation)) operation else null }
    }

    private fun revoke(entitlement: LeaseEntitlement): Boolean {
        if (!entitlement.authentic(this, issuance) || owner.ownershipLockHeld()) return false
        return synchronized(gate) {
            if (!entitlement.revokeLocked(issuance)) return false
            check(futureEntries > 0L)
            futureEntries--
            true
        }
    }

    private fun observe(candidate: ShutdownReceipt): PoolShutdownObservation {
        if (candidate !== receipt || !accepted.get()) return PoolShutdownObservation.UNACCEPTED
        val frame = PoolCallFrames.current()
        if (frame != null) {
            return when (frame.kind) {
                PoolCallKind.ACQUISITION -> PoolShutdownObservation.ACTIVE_ACQUISITION
                PoolCallKind.SHUTDOWN -> PoolShutdownObservation.ACTIVE_SHUTDOWN_FRAME
                else -> PoolShutdownObservation.ACTIVE_POOL_FRAME
            }
        }
        if (PoolActorCustody.currentThreadOwnsActorFrame()) return PoolShutdownObservation.ACTIVE_POOL_ACTOR
        if (owner.ownershipLockHeld()) return PoolShutdownObservation.OWNERSHIP_LOCK_HELD
        val attempt = firstClose.get()
        return when {
            attempt == null -> PoolShutdownObservation.PENDING
            !attempt.ended.get() -> PoolShutdownObservation.PENDING
            !synchronized(gate) { closedPopulationReadyLocked() } -> PoolShutdownObservation.PENDING
            else -> observeEndedPool(attempt)
        }
    }

    private fun observeEndedPool(attempt: CloseAttempt): PoolShutdownObservation {
        val budget = synchronized(gate) { requireNotNull(shutdownBudget) }
        if (persistenceFactoryRemainingMillis(budget) == 0L) return PoolShutdownObservation.PENDING
        actors.observeTerminations()
        val actorState = synchronized(gate) { actors.observationLocked() }
        if (actorState === PoolActorObservation.PENDING) return PoolShutdownObservation.PENDING
        // This existing owner observer may wait, but only outside F/G/T and every counted caller/Worker extent.
        val native = owner.observeShutdown(budget)
        if (native === PersistenceLifecycleObservation.PENDING) return PoolShutdownObservation.PENDING
        return when {
            native !== PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED && native !== PersistenceLifecycleObservation.DRIVER_CONTRACT_ONLY_ENDED ->
                PoolShutdownObservation.UNKNOWN

            attempt.outcome.get() !== PersistenceTerminalCall.RETURNED || attempt.interrupted.get() || attempt.bookkeepingFailed.get() ->
                PoolShutdownObservation.UNKNOWN

            actorState === PoolActorObservation.UNKNOWN -> PoolShutdownObservation.UNKNOWN

            synchronized(gate) { installation !== Installation.INSTALLED } || actorState === PoolActorObservation.UNPROVEN ->
                PoolShutdownObservation.POOL_ACTORS_UNPROVEN

            native === PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED -> PoolShutdownObservation.TRACKED_LOCAL_ENDED

            else -> PoolShutdownObservation.DRIVER_CONTRACT_ONLY_ENDED
        }
    }

    private fun combine(original: Throwable?, failure: Throwable): Throwable {
        if (original == null) return failure
        if (original !== failure) original.addSuppressed(failure)
        return original
    }

    override fun toString(): String = "PoolLifecycle(redacted)"

    /** Captured Hikari handle and entitlement remain private/caller-owned; there is no raw getter. */
    internal class Acquisition private constructor(private val pool: PoolLifecycle, internal val frame: PoolCallFrame) {
        private var handle: Connection? = null
        private var shared: ShutdownReceipt? = null
        private var refusedBeforeEntry = false
        internal var entitlement: LeaseEntitlement? = null

        fun enter(): Boolean = pool.enterAcquisition(this).also { admitted ->
            // Only a returned refusal proves the unadmitted TL/restoration path actually ended.
            refusedBeforeEntry = !admitted && !frame.hasEntered()
        }

        internal fun entered(): Boolean = frame.hasEntered()

        internal fun completionProven(): Boolean = frame.ended() || refusedBeforeEntry

        fun capture(returned: Connection): Boolean {
            if (!frame.actualCaller() || !frame.active() || PoolCallFrames.current() !== frame) return false
            if (handle != null) return false
            handle = returned
            return true
        }

        fun prepareLeaseEntitlement(): LeaseEntitlement? = pool.prepareLeaseEntitlement(this)

        fun handoff(): ShutdownReceipt? = pool.handoff(this)

        /** Only authentic wrapping/attachment/bookkeeping failure; not every ordinary acquisition SQL failure. */
        fun failBeforeEnd(): Boolean = pool.failBeforeEnd(frame)

        fun end(): Boolean = pool.endFrame(frame)

        fun actualFrameEnded(): Boolean = frame.ended()

        fun disposition(): PoolAcquisitionDisposition = when {
            !frame.hasEntered() -> PoolAcquisitionDisposition.NOT_ENTERED
            shared != null -> PoolAcquisitionDisposition.ROOT_PENDING
            else -> PoolAcquisitionDisposition.CALLER_OWNED
        }

        internal fun hasCaptured(): Boolean = handle != null

        internal fun accept(authority: Any, receipt: ShutdownReceipt) {
            check(frame.authentic(pool, authority) && frame.hasEntered())
            shared = receipt
        }

        override fun toString(): String = "PoolAcquisition(redacted)"

        companion object {
            internal fun prepare(pool: PoolLifecycle, issuance: Any, caller: Thread, budget: PersistenceTimeBudget): Acquisition =
                Acquisition(pool, PoolCallFrame.prepare(pool, issuance, PoolCallKind.ACQUISITION, caller, budget))
        }
    }

    /** One pre-exposure future return OR eviction right, not a native/epoch credential. No historical lease registry is retained. */
    internal class LeaseEntitlement private constructor(private val pool: PoolLifecycle, private val issuance: Any, private val caller: Thread) {
        private var phase = EntitlementPhase.AVAILABLE
        private var prepared: Operation? = null

        fun prepareReturn(budget: PersistenceTimeBudget): Operation? = pool.prepareOperation(this, PoolCallKind.RETURN, budget)

        fun prepareEviction(budget: PersistenceTimeBudget): Operation? = pool.prepareOperation(this, PoolCallKind.EVICTION, budget)

        /** Outside F/G/T. Consuming a real operation already revokes future ingress; its current outer tail remains counted. */
        fun revoke(): Boolean = pool.revoke(this)

        internal fun completionProven(): Boolean = synchronized(pool.gate) {
            phase === EntitlementPhase.REVOKED || (phase === EntitlementPhase.CONSUMED && prepared?.completionProven() == true)
        }

        internal fun authentic(owner: PoolLifecycle, authority: Any): Boolean = pool === owner && issuance === authority && caller === Thread.currentThread()

        internal fun prepareLocked(authority: Any, operation: Operation): Boolean {
            check(issuance === authority)
            if (phase !== EntitlementPhase.AVAILABLE || prepared != null) return false
            prepared = operation
            return true
        }

        internal fun canEnterLocked(authority: Any, operation: Operation): Boolean = issuance === authority &&
            phase === EntitlementPhase.AVAILABLE && prepared === operation

        internal fun preparedForLocked(authority: Any, operation: Operation): Boolean = issuance === authority && prepared === operation

        internal fun consumedLocked(authority: Any): Boolean = issuance === authority && phase === EntitlementPhase.CONSUMED

        internal fun consumeLocked(authority: Any) {
            check(issuance === authority && phase === EntitlementPhase.AVAILABLE)
            phase = EntitlementPhase.CONSUMED
        }

        internal fun revokeLocked(authority: Any): Boolean {
            check(issuance === authority)
            if (phase !== EntitlementPhase.AVAILABLE) return false
            phase = EntitlementPhase.REVOKED
            return true
        }

        override fun toString(): String = "PoolLeaseEntitlement(redacted)"

        companion object {
            internal fun prepare(pool: PoolLifecycle, issuance: Any, caller: Thread): LeaseEntitlement = LeaseEntitlement(pool, issuance, caller)
        }
    }

    /** RETURN includes failed-return eviction, caller-runs and all post-consent tails; core separately denies stale Entry/epoch rights. */
    internal class Operation private constructor(
        private val pool: PoolLifecycle,
        internal val frame: PoolCallFrame,
        internal val entitlement: LeaseEntitlement,
    ) {
        private var refusedBeforeEntry = false

        fun enter(): Boolean = pool.enterOperation(this).also { admitted ->
            refusedBeforeEntry = !admitted && !frame.hasEntered()
        }

        internal fun completionProven(): Boolean = frame.ended() || refusedBeforeEntry

        fun failBeforeEnd(): Boolean = pool.failBeforeEnd(frame)

        /** An accounted RETURN incident keeps its original outer extent and existing creator authority, never new business. */
        fun recordReturnIncidentBeforeEnd(): Boolean = pool.recordReturnIncidentBeforeEnd(this)

        fun end(): Boolean = pool.endFrame(frame)

        fun actualFrameEnded(): Boolean = frame.ended()

        override fun toString(): String = "PoolOperation(redacted)"

        companion object {
            internal fun prepare(pool: PoolLifecycle, frame: PoolCallFrame, entitlement: LeaseEntitlement): Operation = Operation(pool, frame, entitlement)
        }
    }

    internal class ShutdownReceipt private constructor(private val pool: PoolLifecycle) {
        fun observe(): PoolShutdownObservation = pool.observe(this)

        override fun toString(): String = "PoolShutdownReceipt(redacted)"

        companion object {
            internal fun prepare(pool: PoolLifecycle): ShutdownReceipt = ShutdownReceipt(pool)
        }
    }

    private class CloseAttempt(val caller: PersistenceOwnedFactoryCaller, val frame: PoolCallFrame) {
        val outcome = AtomicReference(PersistenceTerminalCall.NOT_INVOKED)
        val interrupted = AtomicBoolean()
        val bookkeepingFailed = AtomicBoolean()
        val ended = AtomicBoolean()
    }

    private enum class Installation { NEW, INSTALLING, INSTALLED, REFUSED }

    private enum class Startup { NEW, INITIALIZING, READY, FAILED }

    private enum class EntitlementPhase { AVAILABLE, CONSUMED, REVOKED }
}

internal enum class PoolAcquisitionDisposition { NOT_ENTERED, CALLER_OWNED, ROOT_PENDING }

internal enum class PoolShutdownInvocation {
    RETURNED,
    ALREADY_CLAIMED,
    INITIALIZATION_PENDING,
    ACTIVE_ACQUISITION,
    ACTIVE_POOL_FRAME,
    ACTIVE_POOL_ACTOR,
    OWNERSHIP_LOCK_HELD,
}

internal enum class PoolShutdownObservation {
    UNACCEPTED,
    PENDING,
    UNKNOWN,
    POOL_ACTORS_UNPROVEN,
    TRACKED_LOCAL_ENDED,
    DRIVER_CONTRACT_ONLY_ENDED,
    ACTIVE_ACQUISITION,
    ACTIVE_SHUTDOWN_FRAME,
    ACTIVE_POOL_FRAME,
    ACTIVE_POOL_ACTOR,
    OWNERSHIP_LOCK_HELD,
}
