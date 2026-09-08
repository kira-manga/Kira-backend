package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.withLock

/** Actual lifecycle-owner activation/retirement only. Read-only own-project reflection is assertion evidence, not admission authority. */
internal class PgLifecycleTestScope(endpoint: ResolvedPersistenceEndpoint, capacity: Int = 1) : AutoCloseable {
    val owner = PersistenceJdbcLifecycleOwner(endpoint, capacity, PersistencePathStyle.POSIX)
    val root = lifecycleField(owner, "root") as PersistenceJdbcDriverRoot
    var expectedUnknown = false

    fun start(waitTimer: Boolean = true) {
        check(owner.start() === PersistenceLifecycleActivation.STARTED)
        awaitLifecycleFact { owner.snapshot().ordinaryReady }
        check(owner.observeOrdinaryPreparation() === PersistenceLifecycleObservation.READY)
        if (waitTimer) awaitLifecycleFact { owner.snapshot().timerReady }
    }

    fun prepareDeletion() {
        check(owner.prepareDeletion() === PersistenceLifecycleActivation.STARTED)
        awaitLifecycleFact { owner.snapshot().deletionReady }
        check(owner.observeDeletionPreparation() === PersistenceLifecycleObservation.READY)
    }

    fun binding(deletion: Boolean = false): PersistencePhysicalFactoryBinding =
        lifecycleField(if (deletion) root.deletion else root.ordinary, "binding") as PersistencePhysicalFactoryBinding

    fun entries(deletion: Boolean = false): List<PersistencePhysicalEntry> {
        val binding = binding(deletion)
        return binding.ledger.lock.withLock { binding.ledger.entries.filterNotNull() }
    }

    fun request(deletion: Boolean = false): PersistenceFactoryResult.Success<PersistenceJdbcCandidate> {
        awaitLifecycleFact { binding(deletion).isOwnedReceiverReady() }
        val result = if (deletion) owner.requestDeletion() else owner.requestOrdinary()
        check(result is PersistenceFactoryResult.Success) { "Managed lifecycle request did not succeed: $result" }
        awaitLifecycleFact { result.receipt.state() !== PersistenceFactoryProcessing.PENDING }
        check(result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED)
        check(entries(deletion).single().candidate === result.value)
        return result
    }

    fun retire(result: PersistenceFactoryResult.Success<PersistenceJdbcCandidate>, deletion: Boolean = false): PersistencePhysicalEntry {
        val entry = entries(deletion).single { it.candidate === result.value }
        check(result.value.requestRetirement())
        awaitLifecycleFact { entries(deletion).isEmpty() }
        val work = requireNotNull(entry.terminalWork)
        check(work.bodyExited() && work.disposition() !== PersistenceTerminalDisposition.PENDING)
        check(result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED && entry.scopeEnded)
        return entry
    }

    override fun close() {
        owner.requestShutdown()
        val result = owner.observeShutdown()
        val allowed = if (expectedUnknown) {
            setOf(PersistenceLifecycleObservation.UNKNOWN)
        } else {
            setOf(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, PersistenceLifecycleObservation.DRIVER_CONTRACT_ONLY_ENDED)
        }
        check(result in allowed) { "Managed shutdown did not satisfy expected evidence: $result" }
        val threads = actors()
        check(threads.all { it.termination().ended() && !it.thread.isAlive })
        println("PG_LIFECYCLE_ROOT_CLEANUP result=$result actors=${threads.size} all_terminated=true proof=REAL_COMPOSITION")
    }

    fun actors(): List<PersistenceRetainedPlatformThread> {
        val shared = listOf(
            lifecycleField(root, "scanner") as PersistenceRetainedPlatformThread,
            lifecycleField(root.timer, "actor") as PersistenceRetainedPlatformThread,
        )
        return shared + listOf(root.ordinary, root.deletion).flatMap { participant ->
            val controller = lifecycleField(participant, "controller") as PersistenceRetainedPlatformThread
            val worker = lifecycleField(participant, "worker") as PersistenceFactoryWorker<*, *>
            val factory = lifecycleField(worker, "ownedThread") as PersistenceRetainedPlatformThread
            val terminals = lifecycleField(participant, "runners") as Array<*>
            listOf(controller, factory) + terminals.map { lifecycleField(requireNotNull(it), "actor") as PersistenceRetainedPlatformThread }
        }
    }
}

internal fun lifecycleField(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(owner)

internal fun awaitLifecycleFact(allowanceMillis: Long = 8_000, predicate: () -> Boolean) {
    val budget = PersistenceTimeBudget.start(allowanceMillis)
    while (!predicate()) LockSupport.parkNanos(budget.remainingMillis(1) * 1_000_000)
}
