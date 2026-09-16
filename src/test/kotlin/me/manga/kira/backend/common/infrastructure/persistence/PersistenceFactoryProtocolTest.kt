package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PersistenceFactoryProtocolTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["RETURN_INTERRUPTED", "THROW_INTERRUPTED", "THROW_FATAL", "THROW_NORMAL_INTERRUPTED"])
    fun `interruption and fatal create exits permanently close without losing an untransferred return`(mode: String) = FactoryWorkerTestScope().use { scope ->
        val resource = FactoryTestValue()
        val raw = FactoryHostileError()
        val token = AtomicReference<PersistenceFactoryCancellation>()
        val discards = AtomicInteger()
        val worker = scope.worker(
            create = { _, cancellation ->
                token.set(cancellation)
                when (mode) {
                    "RETURN_INTERRUPTED" -> {
                        Thread.currentThread().interrupt()
                        resource
                    }

                    "THROW_INTERRUPTED" -> throw InterruptedException("Synthetic creation interruption.")

                    "THROW_FATAL" -> throw raw

                    else -> {
                        Thread.currentThread().interrupt()
                        error("Synthetic interrupted creation failure.")
                    }
                }
            },
            discard = { _, _ -> discards.incrementAndGet() },
        )
        startFactory(worker)
        val failure = factoryFailure(worker.request(FactoryTestValue()))
        assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(5_000))
        val snapshot = factorySnapshot(worker)
        assertEquals(PersistenceFactoryGeneration.BROKEN, snapshot.generation)
        assertTrue(snapshot.attemptRetained)
        assertEquals(mode == "RETURN_INTERRUPTED", snapshot.resultRetained)
        assertTrue(snapshot.workerSettled)
        assertTrue(token.get().isRequested())
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED_UNRESOLVED, failure.receipt.state())
        assertEquals(0, discards.get())
        assertEquals(0, raw.reads.get())
        assertFactoryRefused(worker.request(FactoryTestValue()), PersistenceFactoryFailure.BROKEN)
        assertEquals(PersistenceFactoryStart.ALREADY_CLAIMED, worker.start())
        raw.toString() // Same hostile Error positive control, only after the zero-read assertions.
        assertEquals(1, raw.reads.get())
        assertEquals(0, resource.renders.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["BEFORE_WORK", "RESULT_RETAINED", "SETTLING"])
    fun `unexpected job probe failure uses total sticky ownership path`(boundary: String) = FactoryWorkerTestScope().use { scope ->
        val raw = FactoryHostileFailure()
        val discards = AtomicInteger()
        val worker = scope.worker(
            create = { input, _ -> if (boundary == "SETTLING") error("Synthetic no-return failure.") else input },
            discard = { _, _ -> discards.incrementAndGet() },
            probe = { if (it.name == boundary) throw raw },
        )
        startFactory(worker)
        val failure = factoryFailure(worker.request(FactoryTestValue()))
        awaitFactoryTestFact { factorySnapshot(worker).generation == PersistenceFactoryGeneration.BROKEN }
        assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(5_000))
        val snapshot = factorySnapshot(worker)
        assertTrue(snapshot.attemptRetained)
        assertEquals(boundary == "RESULT_RETAINED", snapshot.resultRetained)
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED_UNRESOLVED, failure.receipt.state())
        assertEquals(0, discards.get())
        assertEquals(0, raw.reads.get())
        assertNull(raw.cause)
        assertEquals(1, raw.reads.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["THROW", "RETURN_INTERRUPTED"])
    fun `failed or interrupted late discard retains result and never retries or reports clean completion`(mode: String) =
        FactoryWorkerTestScope().use { scope ->
            val gate = scope.gate()
            val clock = FactoryTestClock()
            val resource = FactoryTestValue()
            val input = FactoryTestValue()
            val raw = FactoryHostileFailure()
            val discards = AtomicInteger()
            val worker = scope.worker(
                clock = clock,
                create = { _, _ ->
                    gate.hold()
                    resource
                },
                discard = { actual, late ->
                    assertSame(input, actual)
                    assertSame(resource, late)
                    discards.incrementAndGet()
                    if (mode == "THROW") throw raw
                    Thread.currentThread().interrupt()
                },
            )
            startFactory(worker)
            val caller = scope.launch { worker.request(input) }
            gate.awaitEntered()
            clock.advanceNanos(20_000_000_000)
            gate.release()
            val failure = factoryFailure(caller.join())
            awaitFactoryTestFact { factorySnapshot(worker).generation == PersistenceFactoryGeneration.BROKEN }
            assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(5_000))
            assertTrue(factorySnapshot(worker).resultRetained)
            assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED_UNRESOLVED, failure.receipt.state())
            worker.seal()
            assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(5_000))
            assertFactoryRefused(worker.request(FactoryTestValue()), PersistenceFactoryFailure.BROKEN)
            assertEquals(1, discards.get())
            assertEquals(0, raw.reads.get())
            assertEquals(0, resource.renders.get())
        }

    @Test
    fun `recoverable hostile failure is not inspected or rendered in outcome status or diagnostics`() = FactoryWorkerTestScope().use { scope ->
        val raw = FactoryHostileFailure()
        val input = FactoryTestValue()
        val worker = scope.worker(create = { _, _ -> throw raw })
        startFactory(worker)
        val failure = factoryFailure(worker.request(input))
        assertEquals(PersistenceFactoryFailure.CREATE_FAILED, failure.reason)
        assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
        assertEquals("PersistenceFactoryResult.Failed(CREATE_FAILED)", failure.toString())
        worker.toString()
        worker.snapshot().toString()
        failure.receipt.toString()
        assertEquals(0, raw.reads.get())
        assertEquals(0, input.renders.get())
        assertEquals(0, input.comparisons.get())
        assertEquals("synthetic-factory-failure", raw.message)
        raw.toString()
        assertNull(raw.cause)
        input.toString()
        input.hashCode()
        assertEquals(3, raw.reads.get())
        assertEquals(1, input.renders.get())
        assertEquals(1, input.comparisons.get())
    }

    @Test
    fun `unexpected admitted caller clock failure closes without losing provenance or leaking its graph`() = FactoryWorkerTestScope().use { scope ->
        val callerThread = AtomicReference<Thread>()
        val callerReads = AtomicInteger()
        val raw = FactoryHostileFailure()
        val gate = scope.gate()
        val clock = PersistenceNanoClock {
            if (Thread.currentThread() === callerThread.get() && callerReads.incrementAndGet() == 3) throw raw
            System.nanoTime()
        }
        val worker = scope.worker(clock = clock, probe = { if (it == PersistenceFactoryProbePoint.BEFORE_WORK) gate.hold() })
        startFactory(worker)
        val caller = scope.launch {
            callerThread.set(Thread.currentThread())
            worker.request(FactoryTestValue())
        }
        gate.awaitEntered()
        val failure = factoryFailure(caller.join())
        assertEquals(PersistenceFactoryFailure.COORDINATION_FAILED, failure.reason)
        assertEquals(PersistenceFactoryProcessing.PENDING, failure.receipt.state())
        assertTrue(factorySnapshot(worker).attemptRetained)
        gate.release()
        assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(5_000))
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED_UNRESOLVED, failure.receipt.state())
        assertEquals(0, raw.reads.get())
    }

    @Test
    fun `detached receipt and token have only value cells and no consumer mutation or resource backreference`() = FactoryWorkerTestScope().use { scope ->
        val token = AtomicReference<PersistenceFactoryCancellation>()
        val input = FactoryTestValue()
        val worker = scope.worker(create = { actual, cancellation ->
            token.set(cancellation)
            actual
        })
        startFactory(worker)
        val success = factorySuccess(worker.request(input))
        assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
        assertEquals(setOf("state"), PersistenceFactoryReceipt::class.java.declaredMethods.map { it.name }.toSet())
        assertEquals(setOf("isRequested"), PersistenceFactoryCancellation::class.java.declaredMethods.map { it.name }.toSet())
        val receiptFields = success.receipt.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        val tokenFields = token.get().javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(listOf(java.util.concurrent.atomic.AtomicReference::class.java), receiptFields.map { it.type })
        assertEquals(listOf(java.util.concurrent.atomic.AtomicBoolean::class.java), tokenFields.map { it.type })
        assertTrue(receiptFields.all { Modifier.isPrivate(it.modifiers) })
        assertTrue(tokenFields.all { Modifier.isPrivate(it.modifiers) })
        assertFalse(AutoCloseable::class.java.isInstance(worker))
        assertFalse(java.util.concurrent.Future::class.java.isInstance(success.receipt))
        assertEquals("PersistenceFactoryResult.Success(redacted)", success.toString())
        assertEquals(0, input.renders.get())
        worker.seal()
        assertFalse(token.get().isRequested())
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, success.receipt.state())
    }
}

private class FactoryHostileFailure : RuntimeException(null, null, false, false) {
    val reads = AtomicInteger()
    override val message: String
        get() {
            reads.incrementAndGet()
            return "synthetic-factory-failure"
        }
    override val cause: Throwable?
        get() {
            reads.incrementAndGet()
            return null
        }

    override fun toString(): String {
        reads.incrementAndGet()
        return "synthetic-factory-failure"
    }
}

private class FactoryHostileError : Error(null, null, false, false) {
    val reads = AtomicInteger()
    override val message: String
        get() {
            reads.incrementAndGet()
            return "synthetic-factory-error"
        }
    override val cause: Throwable?
        get() {
            reads.incrementAndGet()
            return null
        }

    override fun toString(): String {
        reads.incrementAndGet()
        return "synthetic-factory-error"
    }
}
