package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/** MODEL positive controls isolate each Completion gate. Actual producer ends/F1 termination are not native-disposal evidence. */
class PersistencePostOpeningCompletionTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["FOREGROUND", "CANCELLATION", "UNSEALED_ZERO"])
    fun `all five completion predicates independently require post-opening drain even after actual F1 death`(mode: String) =
        OwnedCallerTestScope().use { scope ->
            val binding = PersistencePhysicalFactoryBinding(1, AtomicBoolean())
            val factory = PersistenceRetainedPlatformThread("kira-ownership-model-factory") { }
            scope.beforeClose {
                factory.forbidStart()
                awaitOwnedTestFact { factory.termination().ended() }
            }
            binding.retainOwnedWorker(factory)
            assertEquals(PersistenceFactoryStart.STARTED, factory.start())
            awaitOwnedTestFact { factory.termination() === PersistenceThreadTermination.TERMINATED }
            binding.rendezvous.generation = PersistenceFactoryGeneration.WAITING // MODEL readiness, not a live F1 assertion.
            binding.admissionOpen.set(true)
            val fixture = PersistenceOwnershipTestFixture(binding)
            val epoch = fixture.install()
            val call = when (mode) {
                "FOREGROUND" -> requireNotNull(epoch.enterForeground())
                "CANCELLATION" -> requireNotNull(epoch.enterCancellation())
                else -> null
            }
            fixture.modelProcessingEnded()
            if (call != null) fixture.retire()
            modelOtherCompletionFacts(fixture)
            assertTrue(binding.actualFactoryThreadEnded())
            assertFalse(binding.completion.producersEnded(fixture.work))
            assertEquals(PersistenceTerminalDisposition.PENDING, binding.completion.resourceDisposition(fixture.work))
            assertFalse(completionPredicate(fixture, "scanWorkEnded"))
            assertFalse(completionPredicate(fixture, "canReclaimLocked"))
            assertFalse(binding.completion.scanReclamation(0))
            assertSame(fixture.entry, binding.ledger.entries.single())
            assertFalse(binding.completion.allBodiesEnded())

            if (call != null) assertTrue(call.finish(PersistenceJdbcCallOutcome.RETURNED))
            fixture.retire()
            assertTrue(binding.completion.producersEnded(fixture.work))
            assertEquals(PersistenceTerminalDisposition.DRIVER_CLOSE_RETURNED, binding.completion.resourceDisposition(fixture.work))
            assertTrue(completionPredicate(fixture, "scanWorkEnded"))
            assertTrue(completionPredicate(fixture, "canReclaimLocked"))
            assertTrue(binding.completion.allBodiesEnded())
            assertTrue(binding.completion.scanReclamation(0))
            assertNull(binding.ledger.entries.single())
        }

    @Test
    fun `MODEL raw abort precedes authentic post-opening drain and consuming close waits for the actual tail`() = OwnedCallerTestScope().use { scope ->
        val aborts = AtomicInteger()
        val closes = AtomicInteger()
        val raw = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "abort" -> {
                    aborts.incrementAndGet()
                    null
                }

                "close" -> {
                    closes.incrementAndGet()
                    null
                }

                else -> error("Unexpected MODEL terminal JDBC call.")
            }
        } as Connection
        val fixture = PersistenceOwnershipTestFixture(raw = raw)
        val epoch = fixture.install()
        val tail = requireNotNull(epoch.enterForeground())
        fixture.modelProcessingEnded()
        val runner = PersistenceTerminalRunner(0)
        scope.beforeClose {
            tail.finish(PersistenceJdbcCallOutcome.RETURNED)
            fixture.retire()
            runner.requestStop()
            awaitOwnedTestFact { runner.termination().ended() }
        }
        val work = fixture.retire()
        assertEquals(PersistenceFactoryStart.STARTED, runner.start())
        awaitOwnedTestFact(runner::isReady)
        assertTrue(runner.submit(work))
        awaitOwnedTestFact { aborts.get() == 1 }
        assertNull(epoch.enterForeground())
        assertEquals(0, closes.get(), "Transport-first unblock must not be a consuming close while a producer is active.")
        assertFalse(work.producerDrainProven())
        assertFalse(work.bodyExited())
        assertTrue(tail.finish(PersistenceJdbcCallOutcome.RETURNED))
        awaitOwnedTestFact(work::bodyExited)
        assertTrue(work.producerDrainProven())
        assertEquals(1, aborts.get())
        assertEquals(1, closes.get())
        assertEquals(PersistenceTerminalDisposition.DRIVER_CLOSE_RETURNED, work.disposition())
    }
}

/** Deliberately MODEL all other gates as positive, including saved drain/disposition/body facts, so no conjunct hides another. */
@Suppress("UNCHECKED_CAST")
private fun modelOtherCompletionFacts(fixture: PersistenceOwnershipTestFixture) {
    fixture.binding.ledger.lock.withLock {
        fixture.entry.retiring = true
        fixture.entry.terminal = fixture.work.claim
        fixture.entry.decisionDelivered = true
        // Only authentic F1 death now satisfies openingCallsEnded; it must not satisfy post-opening calls.
        fixture.entry.openingFacts.factoryEnded.set(false)
        fixture.entry.openingFacts.driverEnded.set(false)
        fixture.entry.openingFacts.scopeCallEnded.set(false)
    }
    (lifecycleField(fixture.work, "producerDrain") as AtomicBoolean).set(true)
    (lifecycleField(fixture.work, "close") as AtomicReference<PersistenceTerminalCall>).set(PersistenceTerminalCall.RETURNED)
    (lifecycleField(fixture.work, "phase") as AtomicReference<PersistenceTerminalDisposition>).set(PersistenceTerminalDisposition.DRIVER_CLOSE_RETURNED)
    val body = requireNotNull(lifecycleField(fixture.work, "bodyExit"))
    (lifecycleField(body, "exited") as AtomicBoolean).set(true)
}

/** Own-project private predicates only, under their actual F→G locks; no reflection into a driver or the JDK. */
private fun completionPredicate(fixture: PersistenceOwnershipTestFixture, name: String): Boolean {
    val method = PersistencePhysicalCompletion::class.java.getDeclaredMethod(
        name,
        PersistencePhysicalEntry::class.java,
        PersistenceTerminalWork::class.java,
        Boolean::class.javaPrimitiveType,
    ).apply { isAccessible = true }
    return fixture.binding.rendezvous.lock.withLock {
        fixture.binding.ledger.lock.withLock { method.invoke(fixture.binding.completion, fixture.entry, fixture.work, true) as Boolean }
    }
}
