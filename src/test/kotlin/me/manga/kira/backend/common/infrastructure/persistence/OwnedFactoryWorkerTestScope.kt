package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/** Actual owned F1 receiver, but deliberately MODEL raw/opening/resource-phase facts, not JDBC proof. */
internal class OwnedFactoryWorkerTestScope(capacity: Int = 4) : AutoCloseable {
    val callers = OwnedCallerTestScope()
    val shutdown = AtomicBoolean()
    val binding = PersistencePhysicalFactoryBinding(capacity, shutdown)
    val createCalls = AtomicInteger()
    val failedCleanupCalls = AtomicInteger()
    val discardCalls = AtomicInteger()
    val callbackThread = AtomicReference<Thread>()
    var createAction: ((PersistencePhysicalRecord, PersistenceFactoryCancellation) -> PersistenceJdbcCandidate)? = null
    var failureAction: ((PersistencePhysicalRecord) -> Unit)? = null
    var discardAction: ((PersistencePhysicalRecord, PersistenceJdbcCandidate) -> Unit)? = null

    val worker = PersistenceFactoryWorker.owned(
        binding,
        object : PersistenceOwnedFactoryOperations {
            override fun create(input: PersistencePhysicalRecord, cancellation: PersistenceFactoryCancellation): PersistenceJdbcCandidate {
                createCalls.incrementAndGet()
                callbackThread.set(Thread.currentThread())
                return createAction?.invoke(input, cancellation) ?: modelOpeningReturn(input)
            }

            override fun discard(input: PersistencePhysicalRecord, result: PersistenceJdbcCandidate) {
                assertEquals(callbackThread.get(), Thread.currentThread())
                discardCalls.incrementAndGet()
                discardAction?.invoke(input, result)
            }

            override fun awaitFailedCreationRetirement(input: PersistencePhysicalRecord) {
                failedCleanupCalls.incrementAndGet()
                failureAction?.invoke(input)
            }
        },
        PersistenceLoginPolicy.resolve(null, 5_000),
    )

    init {
        // Cleanup ownership exists before any Thread starts or a test assertion can fail.
        callers.beforeClose {
            shutdown.set(true)
            worker.requestOwnedStop()
            awaitOwnedTestFact {
                binding.reconcileCallers()
                worker.ownedThreadTermination() in setOf(PersistenceThreadTermination.INERT, PersistenceThreadTermination.TERMINATED)
            }
            val actual = requireNotNull(binding.rendezvous.thread)
            assertFalse(actual.isAlive)
            println("OWNED_WORKER_SCOPE_EXIT thread_id=${actual.threadId()} all_terminated=true proof=REAL_THREAD_MODEL_RESOURCES")
        }
    }

    fun start() {
        assertEquals(PersistenceOwnedFactoryStart.STARTED, worker.startOwned())
        awaitReady()
        awaitQueuedReceiver()
        // MODEL prerequisite publication. No root/driver/terminal or native readiness is claimed.
        binding.admissionOpen.set(true)
    }

    fun modelOpeningReturn(record: PersistencePhysicalRecord): PersistenceJdbcCandidate {
        val raw = PhysicalTestConnection().raw
        return binding.ledger.lock.withLock {
            val entry = requireNotNull(binding.ledger.current(record))
            entry.raw.set(raw)
            entry.opening = PersistencePhysicalOpeningPhase.SETTLED
            entry.scopeEnded = true
            entry.candidate
        }
    }

    fun entry(record: PersistencePhysicalRecord): PersistencePhysicalEntry = binding.ledger.lock.withLock {
        requireNotNull(binding.ledger.current(record))
    }

    fun awaitReady() = awaitOwnedTestFact {
        binding.reconcileCallers() && worker.isOwnedReceiverReady()
    }

    fun awaitQueuedReceiver() = awaitOwnedTestFact {
        binding.rendezvous.lock.withLock {
            // signalAll can move a genuinely ready receiver onto F's acquisition queue. Observe
            // the actual Condition registration again; READY alone is not this stronger witness.
            if (!binding.rendezvous.lock.hasWaiters(binding.rendezvous.changed)) return@withLock false
            assertTrue(binding.rendezvous.lock.hasWaiters(binding.rendezvous.changed))
            assertFalse(binding.rendezvous.startInProgress)
            true
        }
    }

    override fun close() = callers.close()
}
