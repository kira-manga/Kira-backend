package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import java.sql.Connection
import kotlin.concurrent.withLock

/** Request publication and actual callers only; MODEL openings are not database-witness or disposal evidence. */
class PersistenceOwnedRequestPublicationTest {
    @Test
    fun `MODEL negative shared admission publication gate leaves ADMIT_G refusal unpublished under a real foreign G holder`() =
        OwnedCallerTestScope().use { scope ->
            val binding = modelReadyBinding()
            val control = PersistenceOwnedCallerControl.prepare(5_000)
            val entry = requireNotNull(binding.reserve(control))
            val request = PersistenceOwnedFactoryRequest(binding, 5_000)
            val held = scope.gate()
            val holder = scope.launch {
                binding.ledger.lock.withLock { held.hold() }
                true
            }
            held.awaitEntered()
            try {
                // Negative branch only: no positive publication is fabricated and no production
                // timing hook is introduced between the actual request's reserve and admit.
                val gate = PersistenceOwnedFactoryRequest::class.java.getDeclaredMethod("admitAndPublish", PersistencePhysicalEntry::class.java)
                gate.isAccessible = true
                assertFalse(gate.invoke(request, entry) as Boolean)
                val refusal = control.failureResult() as PersistenceFactoryResult.Refused
                assertEquals(PersistenceFactoryFailure.BUSY, refusal.reason)
                assertEquals(PersistenceFactoryBusySite.ADMIT_G, refusal.busySite)
                assertFalse(entry.dispatched)
                assertNull(request.admittedEntry(binding))
            } finally {
                held.release()
            }
            assertTrue(holder.value())
        }

    @ParameterizedTest(name = "{displayName} [{index}] typed={0}")
    @ValueSource(booleans = [false, true])
    fun `one pending original publishes its exact stable associations without F or G observation and rejects both duplicate forms`(typed: Boolean) =
        OwnedCallerTestScope().use { scope ->
            val binding = modelReadyBinding()
            val request = PersistenceOwnedFactoryRequest(binding, 5_000) // Prepared on this observer, never executed here.
            assertNull(request.admittedEntry(binding))
            assertNull(binding.ledger.entries.single())
            val gate = scope.gate()
            val behavior = OwnedCallerTestBehavior().apply {
                sampleGate = gate
                gateAtSample = 2 // First accepted observation, after the shared successful-admission publication.
            }
            val original = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) {
                try {
                    execute(request, typed).also {
                        assertTrue(requireNotNull(request.admittedEntry(binding)?.control).caller.isCurrent())
                    }
                } finally {
                    Thread.interrupted()
                }
            }
            gate.awaitEntered()
            val entry = requireNotNull(request.admittedEntry(binding))
            val control = requireNotNull(entry.control)
            val attempt = requireNotNull(entry.attempt)
            assertTrue(entry.dispatched && control.matchesRecord(entry.record))
            assertSame(entry.record, attempt.input)
            assertSame(control, attempt.ownedControl)
            assertSame(control.budget, attempt.budget)
            assertSame(control.receipt, attempt.receipt)
            assertFalse(control.caller.isCurrent(), "Inert preparation must not capture the observer as original caller.")
            assertEquals(PersistenceOwnedCallerDisposition.ATTACHED, control.state())
            binding.rendezvous.lock.withLock {
                binding.ledger.lock.withLock {
                    assertSame(entry, scope.launch { requireNotNull(request.admittedEntry(binding)) }.value())
                    assertNull(request.admittedEntry(modelReadyBinding()))
                    val duplicateBehavior = OwnedCallerTestBehavior().apply { sampleFailure = OwnedCallerTestFatal() }
                    val duplicates = scope.launch(OwnedCallerTestKind.OVERRIDING, duplicateBehavior) {
                        listOf(request.execute(), request.executePoolConnection())
                    }.value()
                    duplicates.forEach { assertRefusal(it, PersistenceFactoryFailure.COORDINATION_FAILED) }
                    assertEquals(0, duplicateBehavior.samples.get(), "A duplicate cannot reach another control's caller sampling.")
                    assertSame(entry, request.admittedEntry(binding))
                    assertSame(control, entry.control)
                    assertEquals(PersistenceOwnedCallerDisposition.ATTACHED, control.state())
                }
            }
            (original.thread as OverridingCallerThread).setActualFlag()
            gate.release()
            val outcome = original.value() as PersistenceFactoryResult.Failed
            assertEquals(PersistenceFactoryFailure.INTERRUPTED, outcome.reason)
            assertSame(control.receipt, outcome.receipt)
            assertSame(entry, request.admittedEntry(binding), "Publication is retained identity, not an assertion that the original remains pending.")
            assertEquals(PersistenceOwnedCallerDisposition.ABANDONED_INTERRUPTED, control.state())
            assertRefusal(execute(request, !typed), PersistenceFactoryFailure.COORDINATION_FAILED)
        }

    @ParameterizedTest(name = "{displayName} [{index}] typed={0} refusal={1}")
    @CsvSource(
        "false,RESERVE_G", "true,RESERVE_G", "false,RESERVE_FULL", "true,RESERVE_FULL",
        "false,ADMIT_F", "true,ADMIT_F", "false,ADMIT_OCCUPIED", "true,ADMIT_OCCUPIED",
        "false,CLOSED", "true,CLOSED", "false,RESERVE_NOT_READY", "true,RESERVE_NOT_READY",
        "false,ADMIT_NOT_READY", "true,ADMIT_NOT_READY", "false,BROKEN", "true,BROKEN",
        "false,INTERRUPTED", "true,INTERRUPTED",
    )
    fun `reserve and admission refusals never publish an Entry or permit a replacement execution`(typed: Boolean, mode: String) =
        OwnedCallerTestScope().use { scope ->
            val binding = modelReadyBinding(if (mode == "ADMIT_OCCUPIED") 2 else 1)
            when (mode) {
                "RESERVE_FULL" -> requireNotNull(binding.reserve(PersistenceOwnedCallerControl.prepare(5_000)))
                "ADMIT_OCCUPIED" -> assertTrue(binding.admit(requireNotNull(binding.reserve(PersistenceOwnedCallerControl.prepare(5_000)))))
                "CLOSED" -> binding.requestOwnedStop()
                "RESERVE_NOT_READY" -> binding.admissionOpen.set(false)
                "ADMIT_NOT_READY" -> binding.rendezvous.generation = PersistenceFactoryGeneration.NEW
                "BROKEN" -> binding.rendezvous.generation = PersistenceFactoryGeneration.BROKEN
            }
            val request = PersistenceOwnedFactoryRequest(binding, 5_000)
            val held = when (mode) {
                "RESERVE_G" -> binding.ledger.lock
                "ADMIT_F" -> binding.rendezvous.lock
                else -> null
            }
            held?.lock()
            try {
                val result = scope.launch {
                    if (mode == "INTERRUPTED") Thread.currentThread().interrupt()
                    try {
                        execute(request, typed)
                    } finally {
                        Thread.interrupted()
                    }
                }.value() as PersistenceFactoryResult.Refused
                val expected = when (mode) {
                    "CLOSED" -> PersistenceFactoryFailure.CLOSED
                    "RESERVE_NOT_READY", "ADMIT_NOT_READY" -> PersistenceFactoryFailure.NOT_READY
                    "BROKEN" -> PersistenceFactoryFailure.BROKEN
                    "INTERRUPTED" -> PersistenceFactoryFailure.INTERRUPTED
                    else -> PersistenceFactoryFailure.BUSY
                }
                assertEquals(expected, result.reason)
                val site = PersistenceFactoryBusySite.entries.singleOrNull { it.name == mode }
                assertEquals(site, result.busySite)
                assertNull(request.admittedEntry(binding))
                assertRefusal(request.execute(), PersistenceFactoryFailure.COORDINATION_FAILED)
                assertRefusal(request.executePoolConnection(), PersistenceFactoryFailure.COORDINATION_FAILED)
                assertNull(request.admittedEntry(binding))
            } finally {
                held?.unlock()
            }
        }

    @ParameterizedTest(name = "{displayName} [{index}] firstTyped={0}")
    @ValueSource(booleans = [false, true])
    fun `different genuine F1 requests keep distinct admitted identity and original result form without replacing either publication`(firstTyped: Boolean) =
        OwnedFactoryWorkerTestScope().use { scope ->
            scope.start()
            val firstRequest = PersistenceOwnedFactoryRequest(scope.binding, 5_000)
            val first = execute(firstRequest, firstTyped) as PersistenceFactoryResult.Success<*>
            scope.awaitReady()
            val firstEntry = requireNotNull(firstRequest.admittedEntry(scope.binding))
            val secondRequest = PersistenceOwnedFactoryRequest(scope.binding, 5_000)
            assertNull(secondRequest.admittedEntry(scope.binding))
            val second = execute(secondRequest, !firstTyped) as PersistenceFactoryResult.Success<*>
            scope.awaitReady()
            val secondEntry = requireNotNull(secondRequest.admittedEntry(scope.binding))
            assertNotSame(firstEntry, secondEntry)
            assertNotSame(firstEntry.record, secondEntry.record)
            assertNotSame(first.receipt, second.receipt)
            assertSame(firstEntry, firstRequest.admittedEntry(scope.binding))
            assertSame(firstEntry.control?.receipt, first.receipt)
            assertSame(secondEntry.control?.receipt, second.receipt)
            assertResultValue(first, firstEntry, firstTyped)
            assertResultValue(second, secondEntry, !firstTyped)
            assertRefusal(execute(firstRequest, !firstTyped), PersistenceFactoryFailure.COORDINATION_FAILED)
            assertEquals(2, scope.createCalls.get())
            assertEquals(0, scope.discardCalls.get())
            assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, first.receipt.state())
            assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, second.receipt.state())
        }

    @ParameterizedTest(name = "{displayName} [{index}] typed={0}")
    @ValueSource(booleans = [false, true])
    fun `owner prepares fresh ordinary and deletion handles without selecting opening constructing driver or starting actors`(typed: Boolean) =
        PgLifecycleTestScope(pgProbeEndpoint(1)).use { scope ->
            val ordinary = scope.owner.prepareOrdinaryRequest()
            val deletion = scope.owner.prepareDeletionRequest()
            assertNotSame(ordinary, scope.owner.prepareOrdinaryRequest())
            assertNotSame(deletion, scope.owner.prepareDeletionRequest())
            assertNull(ordinary.admittedEntry(scope.binding()))
            assertNull(deletion.admittedEntry(scope.binding(true)))
            assertFalse(scope.root.retainedDriver.isFinished())
            assertTrue(scope.actors().all { it.thread.state === Thread.State.NEW })
            assertRefusal(execute(ordinary, typed), PersistenceFactoryFailure.NOT_READY)
            assertRefusal(execute(deletion, typed), PersistenceFactoryFailure.NOT_READY)
            assertNull(ordinary.admittedEntry(scope.binding()))
            assertNull(deletion.admittedEntry(scope.binding(true)))
            assertFalse(scope.root.retainedDriver.isFinished())
            assertTrue(scope.actors().all { it.thread.state === Thread.State.NEW })
            assertTrue(Modifier.isVolatile(PersistencePhysicalEntry::class.java.getDeclaredField("opening").modifiers))
        }

    private fun execute(request: PersistenceOwnedFactoryRequest, typed: Boolean): PersistenceFactoryResult<*> =
        if (typed) request.executePoolConnection() else request.execute()

    private fun assertRefusal(result: PersistenceFactoryResult<*>, expected: PersistenceFactoryFailure) {
        val refusal = result as PersistenceFactoryResult.Refused
        assertEquals(expected, refusal.reason)
        assertNull(refusal.busySite)
    }

    private fun assertResultValue(result: PersistenceFactoryResult.Success<*>, entry: PersistencePhysicalEntry, typed: Boolean) {
        if (typed) {
            val connection = result.value as PhysicalJdbcFacade
            assertSame(connection, connection.unwrap(Connection::class.java))
        } else {
            assertSame(entry.candidate, result.value)
        }
    }
}
