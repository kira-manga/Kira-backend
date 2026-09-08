package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicBoolean

class PersistenceOwnedFactoryGuardsTest {
    @Test
    fun `legacy snapshots remain unavailable on uncontended owned instances`() {
        val binding = PersistencePhysicalFactoryBinding(1, AtomicBoolean())
        // These locks are free: Unavailable must come from the owned guard, not failed tryLock.
        assertSame(PersistencePhysicalSnapshot.Unavailable, binding.legacyRegistry.snapshot())
        assertSame(PersistenceFactorySnapshot.Unavailable, binding.rendezvous.snapshot())
        assertTrue(PersistencePhysicalRegistry(1).snapshot() is PersistencePhysicalSnapshot.Available)
        val unbound = PersistenceFactoryRendezvous<PersistencePhysicalRecord, PersistenceJdbcCandidate>()
        assertTrue(unbound.snapshot() is PersistenceFactorySnapshot.Available)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["reserve", "dispatch", "release", "opening", "retire", "terminal", "raw", "seal", "snapshot"])
    fun `legacy G entrypoints refuse before contention or arbitrary caller flag access`(operation: String) = OwnedCallerTestScope().use { scope ->
        val binding = PersistencePhysicalFactoryBinding(1, AtomicBoolean())
        val behavior = OwnedCallerTestBehavior().apply { sampleFailure = OwnedCallerTestFailure() }
        val registry = binding.legacyRegistry
        val record = PersistencePhysicalRecord(0)
        assertTrue(binding.ledger.lock.tryLock())
        try {
            val call = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) {
                if (operation == "snapshot") {
                    assertSame(PersistencePhysicalSnapshot.Unavailable, registry.snapshot())
                } else {
                    assertThrows(IllegalStateException::class.java) {
                        when (operation) {
                            "reserve" -> registry.tryReserve()
                            "dispatch" -> registry.claimDispatch(record)
                            "release" -> registry.releaseUnused(record)
                            "opening" -> registry.invokeOpening(record) { error("Legacy owned operation must not run.") }
                            "retire" -> registry.requestRetirement(record)
                            "terminal" -> registry.claimTerminal(record)
                            "raw" -> registry.takeTerminalRaw(PersistencePhysicalTerminalClaim(record))
                            "seal" -> registry.seal()
                            else -> error("Unknown owned guard fixture.")
                        }
                    }
                }
                true
            }
            assertTrue(call.value(), "The call must finish while G is still held by this different Thread.")
            assertEquals(0, behavior.samples.get())
            assertEquals(0, behavior.restores.get())
        } finally {
            binding.ledger.lock.unlock()
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(
        strings = [
            "beginStart", "retainThread", "authorizeStart", "finishStart", "seal", "break", "failed", "admit", "awaitResult",
            "awaitWork", "beginWork", "retainResult", "creationFailed", "discardReturned", "offer", "awaitDisposition", "settleWorker",
            "snapshot", "awaitReady", "awaitJoinTarget", "signal",
        ],
    )
    fun `legacy F entrypoints cannot put an arbitrary owned caller in a Condition or AQS queue`(operation: String) = OwnedCallerTestScope().use { scope ->
        val binding = PersistencePhysicalFactoryBinding(1, AtomicBoolean())
        val behavior = OwnedCallerTestBehavior().apply { sampleFailure = OwnedCallerTestFailure() }
        val f = binding.rendezvous
        val input = PersistencePhysicalRecord(0)
        val result = PersistenceJdbcCandidate(AtomicBoolean())
        val budget = PersistenceTimeBudget.start(5_000)
        val attempt = PersistenceFactoryAttempt<PersistencePhysicalRecord, PersistenceJdbcCandidate>(input, budget)
        assertTrue(f.lock.tryLock())
        try {
            val call = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) {
                if (operation == "snapshot") {
                    assertSame(PersistenceFactorySnapshot.Unavailable, f.snapshot())
                } else {
                    assertThrows(IllegalStateException::class.java) { invokeLegacyFactory(operation, f, attempt, result) }
                }
                true
            }
            assertTrue(call.value(), "The call must finish while F is still held by this different Thread.")
            assertEquals(0, behavior.samples.get())
            assertEquals(0, behavior.restores.get())
        } finally {
            f.lock.unlock()
        }
    }

    private fun invokeLegacyFactory(
        operation: String,
        f: PersistenceFactoryRendezvous<PersistencePhysicalRecord, PersistenceJdbcCandidate>,
        attempt: PersistenceFactoryAttempt<PersistencePhysicalRecord, PersistenceJdbcCandidate>,
        result: PersistenceJdbcCandidate,
    ) {
        when (operation) {
            "beginStart" -> f.beginStart()
            "retainThread" -> f.retainThread(Thread.currentThread())
            "authorizeStart" -> f.authorizeStart()
            "finishStart" -> f.finishStart()
            "seal" -> f.seal()
            "break" -> f.breakGeneration(PersistenceFactoryFailure.BROKEN)
            "failed" -> f.workerFailed(PersistenceFactoryFailure.BROKEN)
            "admit" -> f.admit(attempt.input, attempt.budget)
            "awaitResult" -> f.awaitResult(attempt)
            "awaitWork" -> f.awaitWork()
            "beginWork" -> f.beginWork(attempt)
            "retainResult" -> f.retainResult(attempt, result)
            "creationFailed" -> f.creationFailed(attempt)
            "discardReturned" -> f.discardReturned(attempt)
            "offer" -> f.offer(attempt)
            "awaitDisposition" -> f.awaitDisposition(attempt)
            "settleWorker" -> f.settleWorker(attempt)
            "awaitReady" -> f.awaitReady(attempt.budget)
            "awaitJoinTarget" -> f.awaitJoinTarget(attempt.budget)
            "signal" -> f.signalWaiters()
            else -> error("Unknown owned guard fixture.")
        }
    }
}
