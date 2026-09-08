package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real retained platform worker, but synthetic scope/Entry association, not a driver invocation. */
internal class PgScopeTestSupport : AutoCloseable {
    val calls = OwnedCallerTestScope()
    val binding = PersistencePhysicalFactoryBinding(1, AtomicBoolean())
    private val failure = AtomicReference<Throwable?>()
    private val child = AtomicReference<Thread?>()
    private val worker = PersistenceRetainedPlatformThread("kira-pg-scope-fixture") {
        runCatching { requireNotNull(action).invoke() }.onFailure(failure::set)
    }
    private var action: (() -> Unit)? = null
    private val image = pgTestDriverImage()
    private val entry = PersistencePhysicalEntry(
        PersistencePhysicalRecord(0),
        policy = PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT,
        physical = binding,
    )
    val scope = newScope()

    init {
        binding.retainOwnedWorker(worker)
        calls.beforeClose {
            worker.forbidStart()
            val workerExit = runCatching {
                awaitOwnedTestFact { worker.termination() in setOf(PersistenceThreadTermination.INERT, PersistenceThreadTermination.TERMINATED) }
            }
            val childExit = runCatching { child.get()?.let(::joinTransportTestThread) }
            workerExit.getOrThrow()
            childExit.getOrThrow()
            assertFalse(worker.thread.isAlive)
            println("RETAINED_ACTOR_SCOPE_EXIT thread_id=${worker.thread.threadId()} all_terminated=true")
        }
    }

    fun newScope(): PersistencePgFactoryScope = PersistencePgFactoryScope(image, binding, requireNotNull(entry.transports))

    fun start(body: () -> Unit) {
        check(action == null)
        action = body
        assertEquals(PersistenceFactoryStart.STARTED, worker.start())
    }

    fun await() {
        awaitOwnedTestFact { worker.termination() == PersistenceThreadTermination.TERMINATED }
        failure.get()?.let { throw it }
    }

    fun runInheritingChild(body: () -> Unit) {
        val problem = AtomicReference<Throwable?>()
        val prepared = Thread.ofPlatform().inheritInheritableThreadLocals(true).unstarted(body)
        prepared.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, thrown -> problem.set(thrown) }
        check(child.compareAndSet(null, prepared))
        prepared.start()
        joinTransportTestThread(prepared)
        problem.get()?.let { throw it }
    }

    override fun close() = calls.close()
}

/** Only the project's own synthetic objects are inspected; no JDK/driver private access. */
internal fun pgScopePhase(scope: PersistencePgFactoryScope): AtomicReference<*> =
    PersistencePgFactoryScope::class.java.getDeclaredField("phase").also { it.isAccessible = true }.get(scope) as AtomicReference<*>

internal fun pgCurrentScope(): ThreadLocal<*> =
    PersistencePgFactoryScope::class.java.getDeclaredField("current").also { it.isAccessible = true }.get(null) as ThreadLocal<*>
