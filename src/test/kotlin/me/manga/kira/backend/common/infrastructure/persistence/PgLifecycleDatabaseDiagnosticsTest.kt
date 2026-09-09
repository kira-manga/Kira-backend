package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

/** MODEL diagnostic controls only. No database, Driver, transport, timer or native disposal evidence. */
internal class PgLifecycleDatabaseDiagnosticsTest {
    @ParameterizedTest
    @EnumSource(PersistenceFactoryFailure::class)
    fun `MODEL every fixed failure distinguishes accepted receipt from refusal without a receipt`(reason: PersistenceFactoryFailure) {
        val processing = PersistenceFactoryProcessingCell()
        val refused = PgLifecycleDatabaseDiagnostics.resultFields(PersistenceFactoryResult.Refused(reason), processing.receipt, processing.receipt)
        assertTrue(refused.contains("variant=REFUSED reason=${reason.name} receipt_present=false"))
        assertTrue(refused.contains("receipt_state=NOT_APPLICABLE control_receipt=NOT_APPLICABLE attempt_receipt=NOT_APPLICABLE"))
        val failed = PgLifecycleDatabaseDiagnostics.resultFields(
            PersistenceFactoryResult.Failed(reason, processing.receipt),
            processing.receipt,
            processing.receipt,
        )
        assertTrue(failed.contains("variant=FAILED reason=${reason.name} receipt_present=true"))
        assertTrue(failed.contains("receipt_state=PENDING control_receipt=MATCH attempt_receipt=MATCH"))
        assertEquals(PersistenceFactoryProcessing.PENDING, processing.receipt.state())
    }

    @ParameterizedTest
    @EnumSource(PersistenceFactoryProcessing::class)
    fun `MODEL all original receipt states remain separate from success and no candidate is rendered`(state: PersistenceFactoryProcessing) {
        val processing = PersistenceFactoryProcessingCell()
        if (state !== PersistenceFactoryProcessing.PENDING) processing.complete(state === PersistenceFactoryProcessing.PROCESSING_ENDED_UNRESOLVED)
        val renders = AtomicInteger()
        val candidate = object {
            override fun toString(): String {
                renders.incrementAndGet()
                error("Synthetic diagnostic candidate must not be rendered.")
            }
        }
        val result = PersistenceFactoryResult.Success(candidate, processing.receipt)
        val fields = PgLifecycleDatabaseDiagnostics.resultFields(result, processing.receipt, processing.receipt)
        assertTrue(fields.contains("variant=SUCCESS reason=NOT_APPLICABLE receipt_present=true receipt_state=${state.name}"))
        assertTrue(fields.contains("control_receipt=MATCH attempt_receipt=MATCH"))
        val failed = PgLifecycleDatabaseDiagnostics.resultFields(
            PersistenceFactoryResult.Failed(PersistenceFactoryFailure.CREATE_FAILED, processing.receipt),
            processing.receipt,
            processing.receipt,
        )
        assertTrue(failed.contains("variant=FAILED reason=CREATE_FAILED receipt_present=true receipt_state=${state.name}"))
        assertEquals(0, renders.get())
        assertSame(candidate, result.value)
        assertSame(processing.receipt, result.receipt)
        assertEquals(state, result.receipt.state())
        assertThrows(IllegalStateException::class.java) { candidate.toString() } // Positive control for the forbidden rendering path.
        assertEquals(1, renders.get())
    }

    @Test
    fun `MODEL unmatched or unavailable original receipt identity is never silently called a match`() {
        val first = PersistenceFactoryProcessingCell().receipt
        val second = PersistenceFactoryProcessingCell().receipt
        val result = PersistenceFactoryResult.Failed(PersistenceFactoryFailure.CREATE_FAILED, first)
        val mismatched = PgLifecycleDatabaseDiagnostics.resultFields(result, second, second)
        val unavailable = PgLifecycleDatabaseDiagnostics.resultFields(result, null, null)
        assertTrue(mismatched.contains("control_receipt=MISMATCH attempt_receipt=MISMATCH"))
        assertTrue(unavailable.contains("control_receipt=UNAVAILABLE attempt_receipt=UNAVAILABLE"))
        assertEquals(PersistenceFactoryProcessing.PENDING, first.state())
        assertEquals(PersistenceFactoryProcessing.PENDING, second.state())
    }

    @Test
    fun `MODEL early one shot completion keeps the exact result without needing a retained entry or rendering its candidate`() {
        val receipt = PersistenceFactoryProcessingCell().receipt
        val renders = AtomicInteger()
        val candidate = object {
            override fun toString(): String {
                renders.incrementAndGet()
                error("Synthetic early-result candidate must not be rendered.")
            }
        }
        val results: List<PersistenceFactoryResult<Any>> = listOf(
            PersistenceFactoryResult.Refused(PersistenceFactoryFailure.BUSY),
            PersistenceFactoryResult.Failed(PersistenceFactoryFailure.CREATE_FAILED, receipt),
            PersistenceFactoryResult.Success(candidate, receipt),
        )
        results.forEach { result ->
            var calls = 0
            val actual = PgLifecycleDatabaseDiagnostics.originalCall(case(), 0, APPLICATION) {
                calls++
                result
            }
            assertSame(result, actual)
            assertEquals(1, calls)
            val line = PgLifecycleDatabaseDiagnostics.originalCallLines(case(), 0, APPLICATION, Result.success(actual)).single()
            assertTrue(line.contains("entry=UNAVAILABLE state=RETURNED"))
            val identity = if (result is PersistenceFactoryResult.Refused) "NOT_APPLICABLE" else "UNAVAILABLE"
            assertTrue(line.contains("control_receipt=$identity attempt_receipt=$identity"))
        }
        assertEquals(0, renders.get())
        assertEquals(PersistenceFactoryProcessing.PENDING, receipt.state())
        assertThrows(IllegalStateException::class.java) { candidate.toString() }
        assertEquals(1, renders.get())
    }

    @Test
    fun `MODEL pending and thrown original calls never invent a result or inspect a hostile failure`() {
        val pending = PgLifecycleDatabaseDiagnostics.originalCallLines(case(), 0, APPLICATION, null).single()
        assertTrue(pending.contains("entry=UNAVAILABLE state=PENDING result=UNOBSERVED"))
        val failure = PhysicalHostileFailure()
        var calls = 0
        val caught = assertThrows(PhysicalHostileFailure::class.java) {
            PgLifecycleDatabaseDiagnostics.originalCall<Any>(case(), 0, APPLICATION) {
                calls++
                throw failure
            }
        }
        assertSame(failure, caught)
        assertEquals(1, calls)
        val line = PgLifecycleDatabaseDiagnostics.originalCallLines(case(), 0, APPLICATION, Result.failure(failure)).single()
        assertTrue(line.contains("entry=UNAVAILABLE state=THREW result=UNOBSERVED"))
        assertEquals(0, failure.reads.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["F", "G"])
    fun `MODEL contended bookkeeping stays unavailable and preserves caller budget`(lockName: String) = OwnedCallerTestScope().use { scope ->
        val model = Model()
        val lock = if (lockName == "F") model.binding.rendezvous.lock else model.binding.ledger.lock
        val hold = scope.gate()
        val locker = scope.launch {
            lock.withLock { hold.hold() }
            true
        }
        hold.awaitEntered()
        val originalBudget = model.control.budget
        val originalState = model.control.state()
        val lines = PgLifecycleDatabaseDiagnostics.resultLines(case(), 0, APPLICATION, model.binding, model.entry, model.result)
        assertTrue(lines[0].contains("variant=FAILED reason=CREATE_FAILED"))
        assertTrue(lines[0].contains("control_receipt=MATCH attempt_receipt=UNAVAILABLE"))
        assertTrue(lines[1].contains("allowance_ms=2000"))
        assertTrue(lines[2].contains("fg_sample=UNAVAILABLE entry_present=UNAVAILABLE"))
        assertTrue(locker.thread.isAlive && lock.isLocked)
        assertSame(originalBudget, model.control.budget)
        assertEquals(originalState, model.control.state())
        hold.release()
        assertTrue(locker.value())
    }

    @ParameterizedTest
    @ValueSource(strings = ["F", "G"])
    fun `MODEL preheld bookkeeping stays unavailable without acquiring or releasing the callers lock`(lockName: String) {
        val model = Model()
        val lock = if (lockName == "F") model.binding.rendezvous.lock else model.binding.ledger.lock
        val other = if (lockName == "F") model.binding.ledger.lock else model.binding.rendezvous.lock
        lock.withLock {
            val lines = PgLifecycleDatabaseDiagnostics.resultLines(case(), 0, APPLICATION, model.binding, model.entry, model.result)
            assertTrue(lines[0].contains("control_receipt=MATCH attempt_receipt=UNAVAILABLE"))
            assertTrue(lines[2].contains("fg_sample=UNAVAILABLE entry_present=UNAVAILABLE"))
            assertEquals(1, lock.holdCount)
            assertFalse(other.isLocked)
        }
        assertFalse(lock.isLocked || other.isLocked)
        assertEquals(PersistenceOwnedCallerDisposition.PREPARED, model.control.state())
        assertEquals(PersistenceFactoryProcessing.PENDING, model.result.receipt.state())
    }

    @Test
    fun `MODEL absent optional binding keeps original result and atomic facts available`() {
        val model = Model()
        val lines = PgLifecycleDatabaseDiagnostics.resultLines(case(), 0, APPLICATION, null, model.entry, model.result)
        assertTrue(lines[0].contains("variant=FAILED reason=CREATE_FAILED receipt_present=true"))
        assertTrue(lines[0].contains("control_receipt=MATCH attempt_receipt=UNAVAILABLE"))
        assertTrue(lines[1].contains("raw_present=false fatal=false retirement_requested=false allowance_ms=2000"))
        assertTrue(lines[2].contains("fg_sample=UNAVAILABLE entry_present=UNAVAILABLE"))
        assertEquals(PersistenceOwnedCallerDisposition.PREPARED, model.control.state())
    }

    @Test
    fun `MODEL available bookkeeping compares exact entry attempt and original budget without advancing any fact`() {
        val model = Model()
        val before = model.control.state()
        val lines = PgLifecycleDatabaseDiagnostics.resultLines(case(), 0, APPLICATION, model.binding, model.entry, model.result)
        assertTrue(lines[0].contains("control_receipt=MATCH attempt_receipt=MATCH"))
        assertTrue(lines[1].contains("driver_entered=false driver_ended=false factory_entered=false factory_ended=false"))
        assertTrue(lines[1].contains("raw_present=false fatal=false retirement_requested=false allowance_ms=2000"))
        assertTrue(lines[2].contains("fg_sample=AVAILABLE entry_present=true dispatched=false opening_phase=UNCLAIMED"))
        assertTrue(lines[2].contains("attempt_phase=ASSIGNED attempt_failure=NONE worker_settled=false budget_same=true"))
        assertEquals(before, model.control.state())
        assertFalse(model.entry.retirementRequested.get())
        assertEquals(PersistenceFactoryProcessing.PENDING, model.result.receipt.state())
    }

    @Test
    fun `MODEL baseline and supplemental labels and both ordinals remain ASCII and below the unchanged child output cap`() {
        val model = Model()
        val rows = PgLifecycleDatabaseLane.entries.flatMap { lane ->
            (0..1).flatMap { timeout -> PgLifecycleDatabaseRecipe.entries.map { PgLifecycleDatabaseCase(it, timeout, lane) } }
        } + PgLifecycleDatabaseLane.entries.flatMap { lane ->
            listOf(PgLifecycleDatabaseMode.REUSE, PgLifecycleDatabaseMode.WRONG_PASSWORD).map { mode ->
                PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, lane, mode)
            }
        } +
            PersistenceJdbcDatabaseLifecycleTest.establishmentRows().use { stream -> stream.map { it.get()[0] as PgLifecycleDatabaseCase }.toList() } +
            PersistenceJdbcDatabaseLifecycleTest.originalProviderRows().use { stream -> stream.map { it.get()[0] as PgLifecycleDatabaseCase }.toList() }
        check(rows.distinct().size == 83)
        rows.forEach { row ->
            var total = 0
            var progressTotal = 0
            repeat(row.attempts) { ordinal ->
                val lines = PgLifecycleDatabaseDiagnostics.resultLines(row, ordinal, APPLICATION, model.binding, model.entry, model.result)
                val bytes = lines.sumOf { it.toByteArray(Charsets.US_ASCII).size + 1 }
                assertTrue(lines.all { line -> line.all { it.code in 32..126 } })
                assertTrue(bytes <= PgLifecycleDatabaseDiagnostics.MAX_CHARACTERS)
                total += bytes
                val progress = PgLifecycleDatabaseChildStage.entries.map { PgLifecycleDatabaseDiagnostics.childStageLine(row, ordinal, APPLICATION, it) } +
                    PgLifecycleDatabaseDiagnostics.originalCallLines(row, ordinal, APPLICATION, null) +
                    PgLifecycleDatabaseDiagnostics.originalCallLines(row, ordinal, APPLICATION, Result.success(model.result)) +
                    PgLifecycleDatabaseDiagnostics.originalCallLines(row, ordinal, APPLICATION, Result.failure(PhysicalHostileFailure()))
                assertTrue(progress.all { line -> line.all { it.code in 32..126 } })
                progressTotal += progress.sumOf { it.toByteArray(Charsets.US_ASCII).size + 1 }
            }
            assertTrue(total <= 4_096) // Diagnostic contribution only; the existing 16KiB total child cap is unchanged.
            assertTrue(total + progressTotal <= 8_192) // Includes both possible completion variants; leaves half the cap for other child records.
        }
        assertThrows(IllegalStateException::class.java) {
            PgLifecycleDatabaseDiagnostics.resultLines(case(), 0, "untrusted\nidentity", model.binding, model.entry, model.result)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `MODEL reached gate is unreleased until genuine parent or cleanup release and observation never releases it`(cleanup: Boolean) =
        OwnedCallerTestScope().use { scope ->
            val state = PgLifecycleDatabaseRelayState(case())
            scope.beforeClose(state::cleanupRelease)
            val cold = state.diagnostic()
            assertTrue(cold.contains("held_reached=false release_signalled=false unreleased=false parent_released=false fixture_closing=false"))
            val holding = scope.launch { state.hold(PgLifecycleDatabaseGate.AUTHENTICATED_READY) }
            awaitLifecycleFact { state.isHeld() }
            val held = state.diagnostic()
            assertTrue(held.contains("held_reached=true release_signalled=false unreleased=true parent_released=false fixture_closing=false"))
            assertTrue(holding.thread.isAlive)
            if (cleanup) state.cleanupRelease() else state.release()
            assertEquals(!cleanup, holding.value())
            val released = state.diagnostic()
            assertTrue(released.contains("held_reached=true release_signalled=true unreleased=false parent_released=${!cleanup} fixture_closing=$cleanup"))
        }

    @Test
    fun `MODEL relay diagnostics classify unknown wire error without rendering its text or Throwable`() {
        val state = PgLifecycleDatabaseRelayState(case())
        val failure = PhysicalHostileFailure()
        state.authentication.addAll(listOf(10, 11, 12, 0))
        state.errorState.set("synthetic-untrusted-wire-text\nnot-a-code")
        state.failure.set(failure)
        val diagnostic = state.diagnostic()
        assertTrue(diagnostic.contains("auth_count=4 auth=SASL,CONTINUE,FINAL,OK"))
        assertTrue(diagnostic.contains("error_category=OTHER"))
        assertTrue(diagnostic.contains("failure_present=true"))
        assertFalse(diagnostic.contains("synthetic-untrusted"))
        assertEquals(0, failure.reads.get())
        assertFalse(state.isHeld() || state.fixtureClosing.get())
    }

    @Test
    fun `MODEL reporter failure cannot replace original failure inspect its graph or report success`() {
        val original = PhysicalHostileFailure()
        val reporter = PhysicalHostileFailure()
        val reports = AtomicInteger()
        val caught = assertThrows(PhysicalHostileFailure::class.java) {
            PgLifecycleDatabaseDiagnostics.preservingFailure(
                diagnostic = {
                    reports.incrementAndGet()
                    throw reporter
                },
            ) { throw original }
        }
        assertSame(original, caught)
        assertEquals(1, reports.get())
        assertEquals(0, original.reads.get())
        assertEquals(0, reporter.reads.get())
        val value = PgLifecycleDatabaseDiagnostics.preservingFailure(diagnostic = { reports.incrementAndGet() }) { 17 }
        assertEquals(17, value)
        assertEquals(1, reports.get())
    }

    @Test
    fun `MODEL failure diagnostic precedes owned cleanup and never releases the gate itself`() {
        val state = PgLifecycleDatabaseRelayState(case())
        val original = PhysicalHostileFailure()
        var beforeCleanup: String? = null
        val resource = AutoCloseable { state.cleanupRelease() }
        val caught = assertThrows(PhysicalHostileFailure::class.java) {
            resource.use {
                PgLifecycleDatabaseDiagnostics.preservingFailure(diagnostic = { beforeCleanup = state.diagnostic() }) { throw original }
            }
        }
        assertSame(original, caught)
        assertTrue(requireNotNull(beforeCleanup).contains("release_signalled=false unreleased=false parent_released=false fixture_closing=false"))
        assertTrue(state.fixtureClosing.get())
        assertTrue(state.diagnostic().contains("release_signalled=true unreleased=false parent_released=false fixture_closing=true"))
        assertEquals(0, original.reads.get())
    }

    @Test
    fun `MODEL parent observation resets only its local attempt markers not a relay gate`() {
        val observation = PgLifecycleDatabaseObservation()
        assertEquals(-1, observation.ordinal)
        assertEquals(PgLifecycleDatabaseObservationPhase.START_RELAY, observation.phase)
        observation.begin(0)
        observation.retainedConsumed = true
        observation.gateReturned = true
        observation.arrivalCompleted = true
        observation.parentReleaseCompleted = true
        observation.phase = PgLifecycleDatabaseObservationPhase.RETIRE_AND_ABSENCE
        observation.begin(1)
        assertEquals(1, observation.ordinal)
        assertEquals(PgLifecycleDatabaseObservationPhase.SAMPLE_BEFORE, observation.phase)
        assertFalse(observation.retainedConsumed)
        assertFalse(observation.gateReturned)
        assertFalse(observation.arrivalCompleted)
        assertFalse(observation.parentReleaseCompleted)
    }

    private fun case(): PgLifecycleDatabaseCase =
        PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.QUALIFIED_NONBINARY_BOX, 1, PgLifecycleDatabaseLane.DELETION)

    /** Inert, explicitly fabricated bookkeeping for renderer tests; it never activates a real lifecycle. */
    private class Model {
        val binding = PersistencePhysicalFactoryBinding(1, AtomicBoolean())
        val control = PersistenceOwnedCallerControl.prepare(2_000)
        val entry = PersistencePhysicalEntry(PersistencePhysicalRecord(0), control)
        val result = PersistenceFactoryResult.Failed(PersistenceFactoryFailure.CREATE_FAILED, control.receipt)

        init {
            binding.rendezvous.lock.withLock {
                binding.ledger.lock.withLock {
                    entry.attempt = PersistenceFactoryAttempt(entry.record, control.budget, control)
                    binding.ledger.entries[0] = entry
                }
            }
        }
    }

    companion object {
        private const val APPLICATION = "w03c_11111111-1111-1111-1111-111111111111"
    }
}
