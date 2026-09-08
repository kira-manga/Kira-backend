package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Test command loop only, not the future managed root, producer-drain admission or native-disposal proof. */
internal class PgTimerProbeScope(access: PersistencePgTimerAccess) : AutoCloseable {
    private val stop = Runnable {}
    private val commands = LinkedBlockingQueue<Runnable>(2)
    private val controller = PersistenceRetainedPlatformThread("synthetic-timer-controller", ::runCommands)
    val calls = requireNotNull(access.bind(controller))

    init {
        check(access.bind(controller) == null) { "Metadata was bound twice." }
        check(!controller.hasEntered() && !controller.thread.isAlive)
        check(calls.acquireReference() === PersistenceTimerAction.REFUSED)
        check(calls.captureThread() === PersistenceTimerAction.REFUSED)
        check(calls.releaseOwnedReference() === PersistenceTimerAction.REFUSED)
        check(!calls.observe().guard.entered)
    }

    fun start() {
        check(controller.start() === PersistenceFactoryStart.STARTED)
        check(controller.start() === PersistenceFactoryStart.ALREADY_CLAIMED)
    }

    fun <T> call(action: (PersistenceTimerReferenceCalls) -> T): T {
        val task = FutureTask {
            try {
                action(calls)
            } finally {
                // A fixture command observes the interrupted outcome before the next queue wait.
                Thread.interrupted()
            }
        }
        check(commands.offer(task, 1, TimeUnit.SECONDS))
        return try {
            task.get(5, TimeUnit.SECONDS)
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    private fun runCommands() {
        while (true) {
            val command = commands.take()
            if (command === stop) return
            command.run()
        }
    }

    override fun close() {
        val release = runCatching { releaseIfOwned() }
        val end = runCatching {
            if (controller.startPhase() === PersistenceThreadStartPhase.NEW) controller.forbidStart() else check(commands.offer(stop, 1, TimeUnit.SECONDS))
            awaitPgFixtureFact { controller.termination() in setOf(PersistenceThreadTermination.INERT, PersistenceThreadTermination.TERMINATED) }
        }
        release.getOrThrow()
        end.getOrThrow()
        check(!controller.thread.isAlive)
        check(calls.acquireReference() === PersistenceTimerAction.REFUSED && calls.releaseOwnedReference() === PersistenceTimerAction.REFUSED)
        println("PG_TIMER_CONTROLLER_CLEANUP thread_id=${controller.thread.threadId()} all_terminated=true")
    }

    private fun releaseIfOwned() {
        val state = calls.observe()
        if (!state.referenceReturned || state.release.entered) return
        if (state.scheduling.entered) awaitPgFixtureFact { calls.observe().capture.publicationReceived && calls.observe().scheduling.extentEnded }
        val outcome = call { it.releaseOwnedReference() }
        check(outcome !== PersistenceTimerAction.REFUSED && calls.observe().release.extentEnded)
    }
}
