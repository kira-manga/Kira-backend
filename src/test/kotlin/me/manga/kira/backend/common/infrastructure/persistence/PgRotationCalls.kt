package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference

/** Fixed registered test actors. On a failed protocol, unblock owned peers before waiting for actual actor exit. */
internal class PgRotationCalls(private val peer: PgRotationPeer) : AutoCloseable {
    private val calls = arrayOfNulls<Call>(3)

    fun start(action: () -> Unit): Thread {
        val index = calls.indexOfFirst { it == null }
        check(index >= 0)
        val call = Call(action)
        calls[index] = call
        call.thread.start()
        return call.thread
    }

    fun await(thread: Thread) {
        val call = requireNotNull(calls.firstOrNull { it?.thread === thread })
        call.await()
    }

    override fun close() {
        val unblock = runCatching { if (calls.any { it?.thread?.isAlive == true }) peer.unblock() }
        val exits = calls.filterNotNull().map { runCatching { it.await() } }
        unblock.getOrThrow()
        exits.forEach { it.getOrThrow() }
        println("PG_ROTATION_ACTORS_CLEANUP started=${calls.count { it != null }} all_terminated=true")
    }

    private class Call(action: () -> Unit) {
        private val problem = AtomicReference<Throwable?>()
        val thread: Thread = Thread.ofPlatform().name("kira-pg-rotation-cancel").inheritInheritableThreadLocals(false).unstarted {
            runCatching(action).onFailure(problem::set)
        }

        fun await() {
            awaitPgFixtureFact { !thread.isAlive }
            problem.get()?.let { throw it }
        }
    }
}
