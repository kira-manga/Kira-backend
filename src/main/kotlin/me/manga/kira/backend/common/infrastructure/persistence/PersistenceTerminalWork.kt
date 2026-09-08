package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** One preowned work identity per Entry; only the matching physical-slot runner executes it. */
internal class PersistenceTerminalWork(private val binding: PersistencePhysicalFactoryBinding, private val entry: PersistencePhysicalEntry) {
    val claim = PersistencePhysicalTerminalClaim(entry.record)
    private val runner = AtomicReference<PersistenceRetainedPlatformThread?>()
    private val phase = AtomicReference(PersistenceTerminalDisposition.PENDING)
    private val bodyExit = BodyExit()
    private val producerDrain = AtomicBoolean()
    private val fatalFailure = AtomicBoolean()
    private val abort = AtomicReference(PersistenceTerminalCall.NOT_INVOKED)
    private val close = AtomicReference(PersistenceTerminalCall.NOT_INVOKED)
    private val boundary = AtomicReference<PersistenceTimerBoundary?>()
    private val timerEndedWithoutBoundary = AtomicBoolean()
    private val completion = binding.completion
    private var fatal: Error? = null // Only the authentic runner reads/writes the deferred failure.
    private var interrupted = false

    fun bindRunner(retained: PersistenceRetainedPlatformThread): Boolean {
        if (!runner.compareAndSet(null, retained)) return false
        bodyExit.bind(retained.thread)
        return true
    }

    fun isAssigned(): Boolean = runner.get() != null

    fun isRunnerThread(): Boolean = runner.get()?.thread === Thread.currentThread()

    fun producerDrainProven(): Boolean = producerDrain.get()

    fun acknowledgedBoundary(): PersistenceTimerBoundary? = boundary.get()?.takeIf { producerDrain.get() && it.acknowledged() }

    fun failedTimerWorkEnded(): Boolean = producerDrain.get() && timerEndedWithoutBoundary.get()

    fun disposition(): PersistenceTerminalDisposition = phase.get()

    fun bodyExited(): Boolean = bodyExit.hasExited()

    fun closeState(): PersistenceTerminalCall = close.get()

    fun hasFatalFailure(): Boolean = fatalFailure.get()

    fun hasCleanupFailure(): Boolean = abort.get() === PersistenceTerminalCall.THREW || close.get() === PersistenceTerminalCall.THREW || fatalFailure.get()

    /** Get the detached final cell before running the body, not after clearing the old-record payload. */
    fun exitPublication(): BodyExit {
        check(isRunnerThread())
        return bodyExit
    }

    fun run() {
        check(isRunnerThread())
        try {
            finishResources()
            // A dead F1 cannot publish another receipt. Retain its broken/unresolved record, without
            // parking this otherwise-ended terminal body for a callback that can never happen.
            while (entry.control?.receipt?.state() === PersistenceFactoryProcessing.PENDING && !binding.actualFactoryThreadEnded()) park()
            fatal?.let { throw it }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun finishResources() {
        val raw = awaitRaw()
        if (raw != null) call(abort) { raw.abort(DIRECT_EXECUTOR) }
        while (!completion.producersEnded(this)) {
            closeTransports()
            park()
        }
        producerDrain.set(true)
        if (entry.openingFacts.driverEntered.get() && entry.policy.evidence === PersistenceDriverEvidencePolicy.TRACKED_CONJUNCTION) {
            runCatching {
                val prepared = entry.driverOpening?.timer?.newBoundary(requireNotNull(runner.get()))
                boundary.set(prepared) // Fully retained before scheduling; a failed preparation enqueued nothing.
                prepared?.schedule()
            }.onFailure(::recordFailure)
        }
        // A failed abort never skips the first final close. No timer-producing/finally scope can follow it.
        if (raw != null) call(close) { raw.close() }
        var result = PersistenceTerminalDisposition.PENDING
        while (result === PersistenceTerminalDisposition.PENDING) {
            closeTransports()
            observeFailedTimerDrain() // Actual Thread observation occurs outside F/G/T.
            result = completion.resourceDisposition(this)
            if (result === PersistenceTerminalDisposition.PENDING) park()
        }
        phase.set(result) // F1 may finish discard/create-failure now; terminal body must not exit earlier.
        if (hasCleanupFailure()) completion.cleanupFailure.set(true)
    }

    private fun awaitRaw(): Connection? {
        while (true) {
            closeTransports()
            when (val decision = completion.rawDecision(this)) {
                is PersistencePhysicalRawDecision.Granted -> return decision.raw
                PersistencePhysicalRawDecision.NoRawReturned -> return null
                PersistencePhysicalRawDecision.WaitingForOpening -> park()
                else -> error("Persistence terminal raw decision was not owned.")
            }
        }
    }

    private fun closeTransports() {
        runCatching { entry.transports?.closeTerminalTransports(this) }.onFailure(::recordFailure)
        rememberInterruption()
    }

    private inline fun call(state: AtomicReference<PersistenceTerminalCall>, operation: () -> Unit) {
        check(state.compareAndSet(PersistenceTerminalCall.NOT_INVOKED, PersistenceTerminalCall.RUNNING))
        var returned = false
        runCatching {
            try {
                operation()
                returned = true
            } finally {
                state.set(if (returned) PersistenceTerminalCall.RETURNED else PersistenceTerminalCall.THREW)
            }
        }.onFailure(::recordFailure)
        rememberInterruption()
    }

    private fun recordFailure(failure: Throwable) {
        if (failure is InterruptedException) {
            interrupted = true
            binding.requestOwnedStop()
        }
        if (failure is Error) {
            fatalFailure.set(true)
            fatal = failure
        }
    }

    private fun observeFailedTimerDrain() {
        val timer = entry.driverOpening?.timer ?: return
        val scheduling = boundary.get()?.observation()?.scheduling
        val schedulingEnded = scheduling == null || !scheduling.entered || scheduling.extentEnded
        if (schedulingEnded && timer.capturedThreadEnded()) {
            timerEndedWithoutBoundary.set(true)
        }
    }

    /** Cleanup is not canceled by its own actor's interrupt. Clear only this trusted caller and restore on exit. */
    private fun rememberInterruption() {
        if (Thread.interrupted()) {
            interrupted = true
            binding.requestOwnedStop()
            completion.cleanupFailure.set(true)
        }
    }

    private fun park() {
        rememberInterruption()
        LockSupport.parkNanos(1_000_000)
        rememberInterruption()
    }

    override fun toString(): String = "PersistenceTerminalWork(redacted)"

    /** No Entry, root, actor callback or raw resource. Only the bound authentic runner can publish. */
    internal class BodyExit {
        private val thread = AtomicReference<Thread?>()
        private val exited = AtomicBoolean()

        fun bind(value: Thread) {
            check(thread.compareAndSet(null, value))
        }

        fun hasExited(): Boolean = exited.get()

        fun publish() {
            check(Thread.currentThread() === thread.get())
            exited.set(true)
        }

        override fun toString(): String = "PersistenceTerminalBodyExit(redacted)"
    }

    companion object {
        private val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}
