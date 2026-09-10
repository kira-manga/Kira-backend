package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/** Request/retention controls, not PostgreSQL acceptance. MODEL entries below never invoke Driver.connect or start owner actors. */
@ResourceLock(Resources.SYSTEM_OUT)
internal class PgLifecycleDatabaseRetentionTest {
    @ParameterizedTest
    @ValueSource(strings = ["REFUSED", "FAILED", "TIMEOUT", "SUCCESS"])
    fun `MODEL an unwitnessed original keeps its exact variant and receipt but never grants retention or another call`(kind: String) {
        val original = PgLifecycleDatabaseOriginalOutcome()
        val entry = PersistencePhysicalEntry(PersistencePhysicalRecord(0))
        val receipt = PersistenceFactoryProcessingCell().receipt
        val result = result(kind, entry, receipt)
        val calls = AtomicInteger()
        val actual = original.capture {
            calls.incrementAndGet()
            result
        }
        assertSame(result, actual)
        assertSame(result, requireNotNull(original.observed()).getOrThrow())
        if (kind != "REFUSED") assertSame(receipt, resultReceipt(case(kind), result)) // Even an expected accepted S/F/T still needs its witness.
        val failure = assertThrows(IllegalStateException::class.java) {
            original.awaitWitness { error("A completed original must not require another ledger sample.") }
        }
        assertTrue(failure.message.orEmpty().contains("without the required admitted opening witness"))
        val observation = requireNotNull(original.retentionFailure())
        assertSame(result, requireNotNull(observation.original).getOrThrow())
        assertSame(failure, observation.failure)
        assertThrows(IllegalStateException::class.java) {
            original.capture {
                calls.incrementAndGet()
                result
            }
        }
        assertSame(result, requireNotNull(original.observed()).getOrThrow())
        assertEquals(1, calls.get())
        assertEquals(PersistenceFactoryProcessing.PENDING, receipt.state())
        if (result is PersistenceFactoryResult.Failed) assertSame(receipt, result.receipt)
        if (result is PersistenceFactoryResult.Success) assertSame(receipt, result.receipt)
    }

    @Test
    fun `MODEL original RuntimeException Error and direct Throwable are retained and rethrown without graph inspection`() {
        val runtime = PhysicalHostileFailure()
        val fatal = PhysicalHostileError()
        listOf(runtime, fatal, PhysicalDirectFailure()).forEach { failure ->
            val original = PgLifecycleDatabaseOriginalOutcome()
            assertSame(failure, assertThrows(Throwable::class.java) { original.capture { throw failure } })
            assertSame(failure, requireNotNull(original.observed()).exceptionOrNull())
            assertSame(failure, assertThrows(Throwable::class.java) { original.awaitWitness { error("No admitted witness exists.") } })
            val observation = requireNotNull(original.retentionFailure())
            assertSame(failure, requireNotNull(observation.original).exceptionOrNull())
            assertSame(failure, observation.failure)
            assertEquals("PgLifecycleDatabaseOriginalOutcome(redacted)", original.toString())
            assertEquals("PgLifecycleDatabaseRetentionObservation(redacted)", observation.toString())
        }
        assertEquals(0, runtime.reads.get())
        assertEquals(0, fatal.reads.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["SUCCESS", "FAILED", "TIMEOUT"])
    fun `MODEL fully checked captured witness wins concurrent accepted S F or T even after settlement and removal`(kind: String) =
        RetentionModel(case(kind)).use { model ->
            model.prepare()
            val original = PgLifecycleDatabaseOriginalOutcome()
            val expected = result(kind, model.entry, model.control.receipt)
            var captured: PgLifecycleDatabaseWitness? = null
            val witness = original.awaitWitness {
                captured = requireNotNull(model.capture()) // Actual unchanged admitted() checks, not just a fabricated witness object.
                model.settle(kind)
                model.remove()
                assertSame(expected, original.capture { expected })
                captured
            }
            assertSame(captured, witness)
            assertSame(model.entry, witness.entry)
            assertSame(model.primaryRecord(), witness.primary)
            assertSame(expected, requireNotNull(original.observed()).getOrThrow())
            assertSame(model.control.receipt, resultReceipt(model.case, expected))
            assertNull(original.retentionFailure())
            assertTrue(model.scope.entries().isEmpty())
        }

    @Test
    fun `MODEL pending original waits for a real admission sample and does not invent a terminal result`() =
        RetentionModel(case(originalProvider = true)).use { model ->
            model.prepare()
            model.entry.openingFacts.driverEntered.set(false)
            val original = PgLifecycleDatabaseOriginalOutcome()
            OwnedCallerTestScope().use { callers ->
                val sampled = callers.gate()
                val attempts = AtomicInteger()
                val selector = callers.launch {
                    original.awaitWitness {
                        val witness = model.capture()
                        if (attempts.incrementAndGet() == 1) sampled.hold()
                        witness
                    }
                }
                sampled.awaitEntered()
                assertNull(original.observed())
                assertTrue(selector.thread.isAlive)
                model.binding.ledger.lock.withLock { model.entry.openingFacts.driverEntered.set(true) }
                sampled.release()
                assertSame(model.entry, selector.value().entry)
                assertTrue(attempts.get() >= 2)
                assertNull(original.observed())
                assertNull(original.retentionFailure())
            }
        }

    @Test
    fun `MODEL completion racing a missing sample fails before another sample and preserves the accepted failure`() =
        RetentionModel(case("FAILED")).use { model ->
            model.prepare()
            val original = PgLifecycleDatabaseOriginalOutcome()
            val expected = result("FAILED", model.entry, model.control.receipt)
            var samples = 0
            val failure = assertThrows(IllegalStateException::class.java) {
                original.awaitWitness {
                    samples++
                    model.remove()
                    val missing = model.capture()
                    assertNull(missing)
                    original.capture { expected }
                    missing
                }
            }
            assertEquals(1, samples)
            assertSame(failure, requireNotNull(original.retentionFailure()).failure)
            assertSame(expected, requireNotNull(requireNotNull(original.retentionFailure()).original).getOrThrow())
        }

    @Test
    fun `MODEL a settled entry cannot become a witness and an unobserved outcome stays unobserved`() = RetentionModel(case()).use { model ->
        model.prepare()
        model.entry.opening = PersistencePhysicalOpeningPhase.SETTLED
        val original = PgLifecycleDatabaseOriginalOutcome()
        val failure = assertThrows(IllegalStateException::class.java) {
            PgLifecycleDatabaseAssertions.retain(model.scope, model.case, APPLICATION, original)
        }
        assertSame(failure, requireNotNull(original.retentionFailure()).failure)
        assertNull(requireNotNull(original.retentionFailure()).original)
        assertNull(original.observed())
        assertFalse(model.binding.ledger.lock.isHeldByCurrentThread)
    }

    @Test
    fun `MODEL tracked admission needs both actual primary return and returned construction before capture`() = RetentionModel(case()).use { model ->
        model.prepare(completePrimary = false)
        val primary = pgRotationEntry(model.entry, PersistenceTransportRole.PRIMARY)
        assertNull(model.capture())
        primary.construction = PersistenceTransportConstruction.RETURNED
        assertNull(model.capture(), "A RETURNED label without a raw constructor return is not enough.")
        primary.construction = PersistenceTransportConstruction.ACTIVE
        model.returnPrimary()
        primary.construction = PersistenceTransportConstruction.ACTIVE
        assertNull(model.capture(), "A raw constructor result with an active construction is not enough.")
        primary.construction = PersistenceTransportConstruction.RETURNED
        assertSame(model.primaryRecord(), requireNotNull(model.capture()).primary)
    }

    @Test
    fun `MODEL retention validation failure keeps the already observed original and same failure after G unlock`() = RetentionModel(case()).use { model ->
        model.prepare()
        val original = PgLifecycleDatabaseOriginalOutcome()
        val expected = result("FAILED", model.entry, model.control.receipt)
        val validation = PhysicalHostileFailure()
        val caught = assertThrows(PhysicalHostileFailure::class.java) {
            original.awaitWitness {
                original.capture { expected }
                model.binding.ledger.lock.withLock { throw validation }
            }
        }
        assertSame(validation, caught)
        assertFalse(model.binding.ledger.lock.isHeldByCurrentThread)
        val observation = requireNotNull(original.retentionFailure())
        assertSame(validation, observation.failure)
        assertSame(expected, requireNotNull(observation.original).getOrThrow())
        assertEquals(0, validation.reads.get())
    }

    @Test
    fun `MODEL a captured witness cannot make unexpected accepted F pass an expected S row`() = RetentionModel(case()).use { model ->
        model.prepare()
        val original = PgLifecycleDatabaseOriginalOutcome()
        val witness = PgLifecycleDatabaseAssertions.retain(model.scope, model.case, APPLICATION, original)
        val expected = result("FAILED", model.entry, model.control.receipt)
        assertSame(expected, original.capture { expected })
        val failure = assertThrows(InvocationTargetException::class.java) { resultReceipt(model.case, expected) }
        assertTrue(failure.cause is IllegalStateException)
        assertSame(model.entry, witness.entry)
        assertSame(expected, requireNotNull(original.observed()).getOrThrow())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `actual request publishes its original before a held or throwing reporter without using Future completion`(reportThrows: Boolean) =
        PgLifecycleTestScope(PgLifecycleDatabaseSettings.endpoint(case(), 1, APPLICATION)).use { scope ->
            OwnedCallerTestScope().use { callers ->
                val heldReport = callers.gate()
                val heldLedger = callers.gate()
                val binding = scope.binding()
                val reportFailure = PhysicalHostileFailure()
                val output = object : PrintStream(OutputStream.nullOutputStream()) {
                    // Kotlin println(Any?) uses PrintStream's Object overload even when the diagnostic record is a String.
                    override fun println(value: Any?) {
                        if (value is String && value.startsWith("PG_DATABASE_ORIGINAL_CALL ") && value.contains("state=RETURNED")) {
                            heldReport.hold()
                            if (reportThrows) throw reportFailure
                        }
                    }
                }
                withOutput(output) {
                    PgLifecycleDatabaseRequest(scope, case(), APPLICATION, 0).use { request ->
                        try {
                            request.start() // The actual unstarted owner refuses; no Driver/connection or second request is injected.
                            heldReport.awaitEntered()
                            val original = requireNotNull(request.original.observed()).getOrThrow()
                            assertTrue(original is PersistenceFactoryResult.Refused)
                            assertFalse((lifecycleField(request, "task") as FutureTask<*>).isDone)
                            val holder = callers.launch {
                                binding.ledger.lock.withLock { heldLedger.hold() }
                                true
                            }
                            heldLedger.awaitEntered()
                            assertThrows(IllegalStateException::class.java) {
                                PgLifecycleDatabaseAssertions.retain(scope, case(), APPLICATION, request.original)
                            }
                            assertTrue(holder.thread.isAlive && binding.ledger.lock.isLocked)
                            heldLedger.release()
                            assertTrue(holder.value())
                            heldReport.release()
                            assertSame(original, request.result())
                            assertSame(original, requireNotNull(request.original.observed()).getOrThrow())
                            assertEquals(0, reportFailure.reads.get())
                        } finally {
                            heldLedger.release()
                            heldReport.release()
                        }
                    }
                }
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["RETENTION", "VALIDATION", "THREW"])
    fun `MODEL request use cleanup suppresses onto the same primary failure rather than replacing it`(stage: String) {
        val original = PgLifecycleDatabaseOriginalOutcome()
        val result = PersistenceFactoryResult.Refused(PersistenceFactoryFailure.BUSY)
        val primary = IllegalStateException("MODEL original assertion or throw.")
        val earlier = IllegalArgumentException("MODEL prior suppressed failure.")
        val cleanup = IllegalStateException("MODEL request cleanup failure.")
        primary.addSuppressed(earlier)
        val caught = assertThrows(IllegalStateException::class.java) {
            // Same suppression-preserving use shape as Cases.verify; the injected close fault is MODEL, not an actor-termination claim.
            AutoCloseable { throw cleanup }.use {
                when (stage) {
                    "THREW" -> original.capture { throw primary }

                    "VALIDATION" -> original.awaitWitness {
                        original.capture { result }
                        throw primary
                    }

                    else -> {
                        original.capture { result }
                        original.awaitWitness { error("The completed request must not sample.") }
                    }
                }
            }
        }
        if (stage == "RETENTION") {
            assertSame(caught, requireNotNull(original.retentionFailure()).failure)
            assertSame(result, requireNotNull(requireNotNull(original.retentionFailure()).original).getOrThrow())
            assertSame(cleanup, caught.suppressed.single())
        } else {
            assertSame(primary, caught)
            assertSame(earlier, caught.suppressed[0])
            assertSame(cleanup, caught.suppressed[1])
        }
    }

    @Test
    fun `MODEL cleanup alone still fails after a normal request scope`() {
        val cleanup = IllegalStateException("MODEL unhealthy request cleanup.")
        assertSame(cleanup, assertThrows(IllegalStateException::class.java) { AutoCloseable { throw cleanup }.use { true } })
    }

    private fun result(kind: String, entry: PersistencePhysicalEntry, receipt: PersistenceFactoryReceipt): PersistenceFactoryResult<PersistenceJdbcCandidate> =
        when (kind) {
            "REFUSED" -> PersistenceFactoryResult.Refused(PersistenceFactoryFailure.BUSY)
            "FAILED" -> PersistenceFactoryResult.Failed(PersistenceFactoryFailure.CREATE_FAILED, receipt)
            "TIMEOUT" -> PersistenceFactoryResult.Failed(PersistenceFactoryFailure.TIMEOUT, receipt)
            else -> PersistenceFactoryResult.Success(entry.candidate, receipt)
        }

    /** Exact existing case oracle, not a second expected-outcome implementation. */
    private fun resultReceipt(case: PgLifecycleDatabaseCase, result: PersistenceFactoryResult<*>): PersistenceFactoryReceipt =
        PgLifecycleDatabaseCases::class.java.getDeclaredMethod("resultReceipt", PgLifecycleDatabaseCase::class.java, PersistenceFactoryResult::class.java)
            .apply { isAccessible = true }.invoke(PgLifecycleDatabaseCases, case, result) as PersistenceFactoryReceipt

    private fun case(kind: String = "SUCCESS", originalProvider: Boolean = false): PgLifecycleDatabaseCase {
        val recipe = if (kind == "FAILED") PgLifecycleDatabaseRecipe.READ_ONLY_TRUE_ALWAYS else PgLifecycleDatabaseRecipe.DEFAULT
        val mode = when {
            originalProvider -> PgLifecycleDatabaseMode.ORIGINAL_MATRIX
            kind == "TIMEOUT" -> PgLifecycleDatabaseMode.PROGRESS_DEADLINE
            else -> PgLifecycleDatabaseMode.MATRIX
        }
        return PgLifecycleDatabaseCase(recipe, if (kind == "FAILED") 1 else 0, PgLifecycleDatabaseLane.ORDINARY, mode)
    }

    private fun withOutput(output: PrintStream, operation: () -> Unit) {
        val previous = System.out
        try {
            System.setOut(output)
            operation()
        } finally {
            System.setOut(previous)
            output.close()
        }
    }

    /** MODEL active opening/scope only. Guarded real Driver metadata and an unconnected PRIMARY, never a driver callback or native-drain proof. */
    private class RetentionModel(val case: PgLifecycleDatabaseCase) : AutoCloseable {
        val scope = PgLifecycleTestScope(PgLifecycleDatabaseSettings.endpoint(case, 1, APPLICATION))
        val binding = scope.binding(case.lane.deleting)
        lateinit var control: PersistenceOwnedCallerControl
        lateinit var entry: PersistencePhysicalEntry
        private var construction: PersistencePhysicalTransportBinding.Construction? = null

        fun prepare(completePrimary: Boolean = true) {
            scope.root.retainedDriver.construct()
            val policy = if (case.originalProvider) {
                PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER
            } else {
                PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION
            }
            val opening = PersistencePgDriverOpening.prepareRetained(
                scope.root.retainedDriver,
                scope.root.endpoint,
                policy,
                PersistencePathStyle.POSIX,
                if (case.originalProvider) null else scope.root.timer,
            )
            control = PersistenceOwnedCallerControl.prepare(case.lane.allowanceMillis)
            val record = PersistencePhysicalRecord(0)
            check(control.bindRecord(record) && control.attach())
            entry = PersistencePhysicalEntry(record, control, policy, binding, opening)
            entry.attempt = PersistenceFactoryAttempt(record, control.budget, control)
            entry.dispatched = true
            entry.opening = PersistencePhysicalOpeningPhase.ACTIVE
            entry.openingFacts.driverEntered.set(true)
            entry.openingFacts.factoryEntered.set(true)
            entry.driverScope?.let { driverScope ->
                PersistencePgFactoryScope::class.java.getDeclaredField("phase").apply { isAccessible = true }
                    .set(driverScope, AtomicReference(PersistencePgScopePhase.ACTIVE))
            }
            binding.ledger.lock.withLock { binding.ledger.entries[0] = entry }
            if (!case.originalProvider) {
                val prepared = requireNotNull(entry.transports).prepare(PersistenceTransportRole.PRIMARY)
                construction = prepared // Retained before reservation/native construction, including any failing preparation tail.
                check(prepared.reserve() == null)
                if (completePrimary) returnPrimary()
            }
        }

        fun returnPrimary() {
            check(requireNotNull(construction).construct() is PersistenceTransportCreation.Created)
        }

        fun primaryRecord(): PersistenceTransportRecord = requireNotNull(construction).record

        fun capture(): PgLifecycleDatabaseWitness? = PgLifecycleDatabaseAssertions.captureWitness(scope, case, APPLICATION, binding)

        fun settle(kind: String) {
            val changed = if (kind == "SUCCESS") {
                control.take()
            } else {
                val reason = if (kind == "TIMEOUT") PersistenceFactoryFailure.TIMEOUT else PersistenceFactoryFailure.CREATE_FAILED
                control.fail(reason)
            }
            check(changed)
            binding.ledger.lock.withLock {
                entry.opening = PersistencePhysicalOpeningPhase.SETTLED
                entry.openingFacts.driverEnded.set(true)
                entry.openingFacts.factoryEnded.set(true)
            }
        }

        fun remove() = binding.ledger.lock.withLock { binding.ledger.entries[0] = null }

        override fun close() {
            scope.use {
                remove() // Remove only the fabricated ledger member before observing the otherwise-unstarted root.
                if (construction != null) pgRotationEntry(entry, PersistenceTransportRole.PRIMARY).raw.get()?.close()
            }
        }
    }

    companion object {
        private const val APPLICATION = "w03c_11111111-1111-1111-1111-111111111111"
    }
}
