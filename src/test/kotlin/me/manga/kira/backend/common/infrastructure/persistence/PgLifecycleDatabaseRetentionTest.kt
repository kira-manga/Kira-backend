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
import java.lang.reflect.Proxy
import java.sql.Driver
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/** Request/retention controls, not PostgreSQL acceptance. Real request admission with MODEL opening facts never invokes Driver.connect. */
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
            val original = model.original
            var expected: PersistenceFactoryResult<PersistenceJdbcCandidate>? = null
            var captured: PgLifecycleDatabaseWitness? = null
            val witness = original.awaitWitness {
                captured = requireNotNull(model.capture()) // Complete actual certificate reader, not a fabricated witness object.
                expected = model.finish(kind)
                model.remove()
                captured
            }
            assertSame(captured, witness)
            assertSame(model.entry, witness.entry)
            assertSame(model.primaryRecord(), witness.primary)
            assertSame(expected, requireNotNull(original.observed()).getOrThrow())
            assertSame(model.control.receipt, resultReceipt(model.case, requireNotNull(expected)))
            assertNull(original.retentionFailure())
            assertTrue(model.binding.ledger.lock.withLock { model.binding.ledger.entries.all { it == null } })
        }

    @Test
    fun `MODEL pending original waits for a real admission sample and does not invent a terminal result`() =
        RetentionModel(case(originalProvider = true)).use { model ->
            model.prepare()
            model.entry.openingFacts.driverEntered.set(false)
            val original = model.original
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
                val witness = selector.value()
                assertSame(model.entry, witness.entry)
                assertNull(witness.primary)
                assertNull(witness.entry.transports)
                assertNull(witness.entry.driverScope)
                assertTrue(attempts.get() >= 2)
                assertNull(original.observed())
                assertNull(original.retentionFailure())
            }
        }

    @Test
    fun `MODEL completion racing a missing sample fails before another sample and preserves the accepted failure`() =
        RetentionModel(case("FAILED")).use { model ->
            model.prepare()
            val original = model.original
            var expected: PersistenceFactoryResult<PersistenceJdbcCandidate>? = null
            var samples = 0
            val failure = assertThrows(IllegalStateException::class.java) {
                original.awaitWitness {
                    samples++
                    expected = model.finish("FAILED")
                    model.remove()
                    val missing = model.capture()
                    assertNull(missing)
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
        assertSame(model.entry, requireNotNull(model.capture()).entry)
        model.entry.opening = PersistencePhysicalOpeningPhase.SETTLED
        assertNull(model.capture(), "Entered flags cannot replace the actual ACTIVE bracket.")
        assertTrue(model.entry.openingFacts.driverEntered.get() && !model.entry.openingFacts.driverEnded.get())
        assertNull(model.original.observed())
        assertNull(model.original.retentionFailure())
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
        OwnedCallerTestScope().use { callers ->
            val lock = transportTestLock(physicalTransportTestOwner(requireNotNull(model.entry.transports)))
            val constructor = lock.withLock {
                val call = callers.launch {
                    model.returnPrimary()
                    true
                }
                awaitLifecycleFact { lock.hasQueuedThread(call.thread) && primary.raw.get() != null }
                assertSame(PersistenceTransportInvocation.RETURNED, primary.invocation.get())
                assertSame(PersistenceTransportConstruction.ACTIVE, primary.construction)
                assertNull(model.capture(), "A raw constructor return before T settlement is not enough.")
                call
            }
            assertTrue(constructor.value())
        }
        assertSame(model.primaryRecord(), requireNotNull(model.capture()).primary)
    }

    @ParameterizedTest
    @ValueSource(strings = ["F", "G", "T", "F_G_T"])
    fun `MODEL complete certificate reader neither acquires nor waits on held ownership locks`(held: String) = RetentionModel(case()).use { model ->
        model.prepare()
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val f = model.binding.rendezvous.lock
            val g = model.binding.ledger.lock
            val t = transportTestLock(physicalTransportTestOwner(requireNotNull(model.entry.transports)))
            val locks = when (held) {
                "F" -> listOf(f)
                "G" -> listOf(g)
                "T" -> listOf(t)
                else -> listOf(f, g, t)
            }
            val holder = callers.launch {
                locks.forEach { it.lock() }
                try {
                    gate.hold()
                } finally {
                    locks.asReversed().forEach { it.unlock() }
                }
                true
            }
            gate.awaitEntered()
            val reader = callers.launch { requireNotNull(model.capture()) }
            val witness = reader.value()
            assertSame(model.entry, witness.entry)
            assertSame(model.primaryRecord(), witness.primary)
            assertTrue(holder.thread.isAlive && locks.all { it.isLocked })
            assertTrue(locks.none { it.hasQueuedThread(reader.thread) })
            assertSame(PersistenceOwnedCallerDisposition.ATTACHED, model.control.state())
            gate.release()
            assertTrue(holder.value())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["FACTORY_NOT_ENTERED", "DRIVER_NOT_ENTERED", "DRIVER_ENDED", "FACTORY_ENDED", "RETIRED", "SCOPE_LEFT", "ABANDONED", "RAW_RETURNED"])
    fun `MODEL absent or departed opening facts never certify the retained request`(fact: String) = RetentionModel(case()).use { model ->
        model.prepare()
        assertSame(model.entry, requireNotNull(model.capture()).entry)
        val raw = PhysicalTestConnection()
        when (fact) {
            "FACTORY_NOT_ENTERED" -> model.entry.openingFacts.factoryEntered.set(false)

            "DRIVER_NOT_ENTERED" -> model.entry.openingFacts.driverEntered.set(false)

            "DRIVER_ENDED" -> model.entry.openingFacts.driverEnded.set(true)

            "FACTORY_ENDED" -> model.entry.openingFacts.factoryEnded.set(true)

            "RETIRED" -> assertTrue(model.entry.candidate.requestRetirement())

            "SCOPE_LEFT" -> requireNotNull(model.scopePhase()).set(PersistencePgScopePhase.ENDED)

            "ABANDONED" -> {
                assertTrue(model.abandon() is PersistenceFactoryResult.Failed)
                assertSame(PersistenceOwnedCallerPhase.ABANDONED, model.control.state().phase)
                assertSame(PersistencePhysicalOpeningPhase.ACTIVE, model.entry.opening)
            }

            else -> model.entry.raw.set(raw.raw)
        }
        assertNull(model.capture())
        assertEquals(0, raw.calls.get())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ATTEMPT_RECORD", "ATTEMPT_CONTROL", "ATTEMPT_BUDGET", "ATTEMPT_RECEIPT", "CONTROL_RECORD", "POLICY", "OPENING_POLICY", "DRIVER", "DISPATCHED",
        ],
    )
    fun `MODEL immutable admission contradictions stay assertion failures not pending samples`(identity: String) = RetentionModel(case()).use { model ->
        model.prepare()
        assertSame(model.entry, requireNotNull(model.capture()).entry)
        val attempt = requireNotNull(model.entry.attempt)
        val opening = requireNotNull(model.entry.driverOpening)
        val driverCalls = AtomicInteger()
        val contradiction: Triple<Any, String, Any> = when (identity) {
            "ATTEMPT_RECORD" -> Triple(attempt, "input", PersistencePhysicalRecord(model.entry.record.slotHint))

            "ATTEMPT_CONTROL" -> Triple(attempt, "ownedControl", PersistenceOwnedCallerControl.prepare(6_000))

            "ATTEMPT_BUDGET" -> Triple(attempt, "budget", PersistenceTimeBudget.start(6_000))

            "ATTEMPT_RECEIPT" -> Triple(attempt, "receipt", PersistenceFactoryProcessingCell().receipt)

            "CONTROL_RECORD" -> Triple(model.control, "record", PersistencePhysicalRecord(model.entry.record.slotHint))

            "POLICY" -> Triple(model.entry, "policy", PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT)

            "OPENING_POLICY" -> Triple(opening, "policy", PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT)

            "DRIVER" -> Triple(
                opening,
                "driver",
                Proxy.newProxyInstance(Driver::class.java.classLoader, arrayOf(Driver::class.java)) { _, _, _ ->
                    driverCalls.incrementAndGet()
                    error("No driver operation belongs to the certificate reader.")
                },
            )

            else -> Triple(model.entry, "dispatched", false)
        }
        val field = contradiction.first.javaClass.getDeclaredField(contradiction.second).apply { isAccessible = true }
        val previous = field.get(contradiction.first)
        try {
            field.set(contradiction.first, contradiction.third) // Negative MODEL corruption only, never fake positive publication.
            assertThrows(IllegalStateException::class.java) { model.capture() }
            assertEquals(0, driverCalls.get())
        } finally {
            field.set(contradiction.first, previous)
        }
    }

    @Test
    fun `MODEL same admitted opening keeps its settings checks and a foreign binding publishes nothing`() = RetentionModel(case()).use { model ->
        model.prepare()
        val opening = requireNotNull(model.entry.driverOpening)
        assertNull(PgLifecycleDatabaseAssertions.captureWitness(model.scope, model.case, APPLICATION, model.scope.binding(), model.prepared))
        assertThrows(IllegalStateException::class.java) {
            PgLifecycleDatabaseAssertions.captureWitness(model.scope, model.case, "$APPLICATION-wrong", model.binding, model.prepared)
        }
        assertThrows(IllegalStateException::class.java) {
            PgLifecycleDatabaseAssertions.captureWitness(model.scope, model.case.copy(queryTimeout = 1), APPLICATION, model.binding, model.prepared)
        }
        assertSame(opening, requireNotNull(model.capture()).entry.driverOpening)
    }

    @ParameterizedTest
    @ValueSource(strings = ["PRIMARY_REPLACED", "OPENING_SETTLED"])
    fun `MODEL a controlled change after the first acquire invalidates the exact certificate suffix`(change: String) = RetentionModel(case()).use { model ->
        model.prepare(boundPrimary = true)
        assertSame(model.entry, requireNotNull(model.capture()).entry)
        val entry = requireNotNull(model.prepared.admittedEntry(model.binding))
        assertTrue(entry.openingFacts.factoryEntered.get() && entry.openingFacts.driverEntered.get())
        assertSame(PersistencePhysicalOpeningPhase.ACTIVE, entry.opening)
        val first = requireNotNull(entry.transports).currentReturnedPrimary()
        assertSame(model.primaryRecord(), first)
        if (change == "PRIMARY_REPLACED") {
            model.replacePrimary() // Real owner-bound close/rotation/constructor/settlement transitions; no publication writes by the test.
        } else {
            entry.opening = PersistencePhysicalOpeningPhase.SETTLED
        }
        // Resume the actual private suffix at a deterministic MODEL scheduling cut, without adding an observer callback.
        val completed = PgLifecycleDatabaseAssertions::class.java.getDeclaredMethod(
            "finishWitness",
            PgLifecycleDatabaseCase::class.java,
            String::class.java,
            PersistencePhysicalEntry::class.java,
            PersistencePgDriverOpening::class.java,
            PersistenceTransportRecord::class.java,
        ).apply { isAccessible = true }.invoke(PgLifecycleDatabaseAssertions, model.case, APPLICATION, entry, entry.driverOpening, first)
        assertNull(completed)
        if (change == "PRIMARY_REPLACED") assertSame(model.primaryRecord(), requireNotNull(model.capture()).primary)
    }

    @Test
    fun `MODEL reused capacity one binds each sample to its exact request ordinal not the current slot`() {
        val binding = modelReadyBinding()
        RetentionModel(case(), binding).use { first ->
            first.prepare()
            val previous = first.retain()
            first.finish("FAILED")
            first.remove()
            RetentionModel(case(), binding).use { next ->
                next.prepare()
                val current = next.retain()
                assertEquals(previous.entry.record.slotHint, current.entry.record.slotHint)
                assertTrue(previous.entry !== current.entry && previous.entry.record !== current.entry.record)
                assertSame(previous.entry, first.prepared.admittedEntry(binding))
                assertSame(current.entry, next.prepared.admittedEntry(binding))
                assertNull(first.capture(), "A lingering prior publication cannot borrow the successor's ACTIVE facts.")
            }
        }
    }

    @Test
    fun `MODEL retention validation failure keeps the already observed original and same failure after G unlock`() = RetentionModel(case()).use { model ->
        model.prepare()
        val original = model.original
        var expected: PersistenceFactoryResult<PersistenceJdbcCandidate>? = null
        val validation = PhysicalHostileFailure()
        val caught = assertThrows(PhysicalHostileFailure::class.java) {
            original.awaitWitness {
                expected = model.finish("FAILED")
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
        val original = model.original
        val witness = model.retain()
        val expected = model.finish("FAILED")
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
                                PgLifecycleDatabaseAssertions.retain(scope, case(), APPLICATION, request)
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

    /** Real request/admission publication; MODEL receiver/opening/scope, guarded Driver metadata and an unconnected PRIMARY only. */
    private class RetentionModel(val case: PgLifecycleDatabaseCase, val binding: PersistencePhysicalFactoryBinding = modelReadyBinding()) : AutoCloseable {
        val scope = PgLifecycleTestScope(PgLifecycleDatabaseSettings.endpoint(case, 1, APPLICATION))
        val original = PgLifecycleDatabaseOriginalOutcome()
        lateinit var control: PersistenceOwnedCallerControl
        lateinit var entry: PersistencePhysicalEntry
        lateinit var prepared: PersistenceOwnedFactoryRequest
        private val callers = OwnedCallerTestScope()
        private val afterAdmission = callers.gate()
        private var caller: OwnedCallerTestCall<PersistenceFactoryResult<PersistenceJdbcCandidate>>? = null
        private var construction: PersistencePhysicalTransportBinding.Construction? = null
        private var boundConstruction: PersistenceTransportConstructionTicket<TrackedPersistenceSocket>? = null

        fun prepare(completePrimary: Boolean = true, boundPrimary: Boolean = false) {
            scope.root.retainedDriver.construct()
            val policy = if (case.originalProvider) {
                PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER
            } else if (case.lane.deleting) {
                PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION
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
            prepared = PersistenceOwnedFactoryRequest(binding, opening)
            val behavior = OwnedCallerTestBehavior().apply {
                sampleGate = afterAdmission
                gateAtSample = 2 // Existing caller metadata seam: first await-outcome sample, after real admission publication.
            }
            caller = callers.launch(OwnedCallerTestKind.OVERRIDING, behavior) { original.capture { prepared.execute() } }
            afterAdmission.awaitEntered()
            entry = requireNotNull(prepared.admittedEntry(binding))
            control = requireNotNull(entry.control)
            binding.ledger.lock.withLock {
                entry.opening = PersistencePhysicalOpeningPhase.ACTIVE
                entry.openingFacts.factoryEntered.set(true)
                entry.openingFacts.driverEntered.set(true)
                scopePhase()?.set(PersistencePgScopePhase.ACTIVE)
            }
            if (!case.originalProvider) {
                if (boundPrimary) {
                    check(completePrimary)
                    replacePrimary()
                } else {
                    val prepared = requireNotNull(entry.transports).prepare(PersistenceTransportRole.PRIMARY)
                    construction = prepared // Retained before reservation/native construction, including any failing preparation tail.
                    check(prepared.reserve() == null)
                    if (completePrimary) returnPrimary()
                }
            }
        }

        fun returnPrimary() {
            check(requireNotNull(construction).construct() is PersistenceTransportCreation.Created)
        }

        fun primaryRecord(): PersistenceTransportRecord = boundConstruction?.record ?: requireNotNull(construction).record

        /** MODEL source identity and private own-project Socket constructor, matching the existing rotation controls. No pgjdbc contact is claimed. */
        @Suppress("UNCHECKED_CAST")
        fun replacePrimary() {
            val owner = physicalTransportTestOwner(requireNotNull(entry.transports)) as PersistenceTransportOwner<TrackedPersistenceSocket>
            val source = requireNotNull(entry.driverScope).extentSource
            val ticket = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.PRIMARY))
            val binding = PersistenceTransportBinding(owner, ticket.record)
            val constructor = TrackedPersistenceSocket::class.java.getDeclaredConstructor(PersistenceTransportBinding::class.java).apply { isAccessible = true }
            check(owner.prepareBoundReservation(ticket) == null)
            ticket.predecessor?.let { check(owner.requestClose(it) === PersistenceTransportCloseRequest.REQUESTED) }
            check(owner.reserveBoundConstruction(ticket) == null)
            boundConstruction = ticket // Own the installed ticket before the actual raw constructor can return.
            check(owner.constructReserved(ticket) { constructor.newInstance(binding) } is PersistenceTransportCreation.Created)
        }

        fun capture(): PgLifecycleDatabaseWitness? = PgLifecycleDatabaseAssertions.captureWitness(scope, case, APPLICATION, binding, prepared)

        fun retain(): PgLifecycleDatabaseWitness = original.awaitWitness(::capture)

        fun finish(kind: String): PersistenceFactoryResult<PersistenceJdbcCandidate> {
            binding.rendezvous.lock.withLock {
                binding.ledger.lock.withLock {
                    entry.opening = PersistencePhysicalOpeningPhase.SETTLED
                    entry.openingFacts.driverEnded.set(true)
                    entry.openingFacts.factoryEnded.set(true)
                    scopePhase()?.set(PersistencePgScopePhase.ENDED)
                    val attempt = requireNotNull(entry.attempt)
                    if (kind == "SUCCESS") {
                        entry.raw.set(PhysicalTestConnection().raw) // MODEL raw identity; no Connection method may be called.
                        entry.scopeEnded = true
                        attempt.beginWork()
                        attempt.retain(entry.candidate)
                        attempt.offer()
                    } else {
                        attempt.abandon(if (kind == "TIMEOUT") PersistenceFactoryFailure.TIMEOUT else PersistenceFactoryFailure.CREATE_FAILED)
                    }
                }
            }
            afterAdmission.release()
            val result = requireNotNull(caller).value() // Only the original caller can take/fail its own control.
            check(control.receipt.state() === PersistenceFactoryProcessing.PENDING) // No actual F1 exists to finish processing in this MODEL.
            return result
        }

        fun abandon(): PersistenceFactoryResult<PersistenceJdbcCandidate> {
            binding.rendezvous.lock.withLock { requireNotNull(entry.attempt).abandon(PersistenceFactoryFailure.CREATE_FAILED) }
            afterAdmission.release()
            return requireNotNull(caller).value()
        }

        @Suppress("UNCHECKED_CAST")
        fun scopePhase(): AtomicReference<PersistencePgScopePhase>? =
            entry.driverScope?.let { lifecycleField(it, "phase") as AtomicReference<PersistencePgScopePhase> }

        fun remove() = binding.rendezvous.lock.withLock {
            binding.ledger.lock.withLock {
                if (::entry.isInitialized && binding.ledger.current(entry.record) === entry) {
                    binding.ledger.entries[entry.record.slotHint] = null
                    binding.rendezvous.current = null
                    binding.rendezvous.generation = PersistenceFactoryGeneration.WAITING
                }
            }
        }

        override fun close() {
            scope.use {
                try {
                    callers.use { caller?.thread?.takeIf { it.isAlive }?.interrupt() }
                } finally {
                    remove() // MODEL removal only, after original caller exit; no F1/processing/disposal completion is invented.
                    if (construction != null || boundConstruction != null) {
                        physicalTransportTestOwner(requireNotNull(entry.transports)).requestClose(primaryRecord())
                    }
                }
            }
        }
    }

    companion object {
        private const val APPLICATION = "w03c_11111111-1111-1111-1111-111111111111"
    }
}
