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
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Authentic owned reserve/admit cuts with deterministic held locks; readiness/occupancy are explicitly MODEL. */
internal class PersistenceFactoryBusyEvidenceTest {
    @ParameterizedTest(name = "{displayName} [{index}] site={0}")
    @EnumSource(PersistenceFactoryBusySite::class)
    fun `MODEL each of the five original refusal branches selects its immutable prebuilt site`(site: PersistenceFactoryBusySite) {
        OwnedCallerTestScope().use { scope ->
            val binding = modelReadyBinding(if (site === PersistenceFactoryBusySite.ADMIT_OCCUPIED) 2 else 1)
            if (site === PersistenceFactoryBusySite.RESERVE_FULL || site === PersistenceFactoryBusySite.ADMIT_OCCUPIED) {
                val occupied = requireNotNull(binding.reserve(PersistenceOwnedCallerControl.prepare(5_000)))
                if (site === PersistenceFactoryBusySite.ADMIT_OCCUPIED) assertTrue(binding.admit(occupied))
            }
            val control = PersistenceOwnedCallerControl.prepare(5_000)
            val budget = control.budget
            val entry = if (site === PersistenceFactoryBusySite.RESERVE_G || site === PersistenceFactoryBusySite.RESERVE_FULL) {
                null
            } else {
                requireNotNull(binding.reserve(control))
            }
            val lock = when (site) {
                PersistenceFactoryBusySite.RESERVE_G, PersistenceFactoryBusySite.ADMIT_G -> binding.ledger.lock
                PersistenceFactoryBusySite.ADMIT_F -> binding.rendezvous.lock
                else -> null
            }
            val release = lock?.let { hold(scope, it) }
            if (entry == null) assertNull(binding.reserve(control)) else assertFalse(binding.admit(entry))
            val result = control.failureResult() as PersistenceFactoryResult.Refused
            assertEquals(PersistenceFactoryFailure.BUSY, result.reason)
            assertEquals(site, result.busySite)
            assertEquals(PersistenceOwnedCallerDisposition.REFUSED_BUSY, control.state())
            assertSame(budget, control.budget)
            assertFalse(control.attach())
            assertFalse(control.take())
            assertFalse(control.fail(PersistenceFactoryFailure.CLOSED))
            assertSame(result, control.failureResult())
            assertEquals(PersistenceFactoryProcessing.PENDING, control.receipt.state())
            if (entry != null) assertFalse(entry.dispatched)
            release?.release()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `MODEL occupied admission distinguishes current presence from ACTIVE without changing the selected site`(current: Boolean) {
        val binding = modelReadyBinding(2)
        val occupied = requireNotNull(binding.reserve(PersistenceOwnedCallerControl.prepare(5_000)))
        assertTrue(binding.admit(occupied))
        binding.rendezvous.lock.withLock {
            if (current) binding.rendezvous.generation = PersistenceFactoryGeneration.WAITING else binding.rendezvous.current = null
        }
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        val entry = requireNotNull(binding.reserve(control))
        assertFalse(binding.admit(entry))
        assertEquals(PersistenceFactoryBusySite.ADMIT_OCCUPIED, (control.failureResult() as PersistenceFactoryResult.Refused).busySite)
    }

    @ParameterizedTest
    @CsvSource("CLOSED,CLOSED", "NOT_READY,NOT_READY", "STALE,COORDINATION_FAILED", "SEALED,CLOSED", "BROKEN,BROKEN")
    fun `MODEL earlier admission predicates are not relabeled as occupied BUSY`(mode: String, reason: PersistenceFactoryFailure) {
        val binding = modelReadyBinding(2)
        assertTrue(binding.admit(requireNotNull(binding.reserve(PersistenceOwnedCallerControl.prepare(5_000)))))
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        val entry = requireNotNull(binding.reserve(control))
        when (mode) {
            "CLOSED" -> binding.requestOwnedStop()
            "NOT_READY" -> binding.admissionOpen.set(false)
            "STALE" -> binding.ledger.entries[entry.record.slotHint] = null
            "SEALED" -> binding.ledger.sealed = true
            "BROKEN" -> binding.rendezvous.generation = PersistenceFactoryGeneration.BROKEN
        }
        assertFalse(binding.admit(entry))
        val result = control.failureResult() as PersistenceFactoryResult.Refused
        assertEquals(reason, result.reason)
        assertNull(result.busySite)
        assertFalse(entry.dispatched)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `MODEL earlier reserve closure or readiness wins over an already full ledger`(closed: Boolean) {
        val binding = modelReadyBinding()
        requireNotNull(binding.reserve(PersistenceOwnedCallerControl.prepare(5_000)))
        if (closed) binding.requestOwnedStop() else binding.admissionOpen.set(false)
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        assertNull(binding.reserve(control))
        val result = control.failureResult() as PersistenceFactoryResult.Refused
        assertEquals(if (closed) PersistenceFactoryFailure.CLOSED else PersistenceFactoryFailure.NOT_READY, result.reason)
        assertNull(result.busySite)
    }

    @ParameterizedTest(name = "{displayName} [{index}] site={0}")
    @EnumSource(PersistenceFactoryBusySite::class)
    fun `MODEL foreign or later transitions cannot publish replace or leak the chosen original site`(site: PersistenceFactoryBusySite) =
        OwnedCallerTestScope().use { scope ->
            val control = PersistenceOwnedCallerControl.prepare(5_000)
            assertFalse(scope.launch { control.fail(PersistenceFactoryFailure.BUSY, site) }.value())
            assertEquals(PersistenceOwnedCallerDisposition.PREPARED, control.state())
            assertNull(control.failureResult())
            assertTrue(control.fail(PersistenceFactoryFailure.BUSY, site))
            val original = control.failureResult() as PersistenceFactoryResult.Refused
            for (later in PersistenceFactoryBusySite.entries) assertFalse(control.fail(PersistenceFactoryFailure.BUSY, later))
            assertFalse(scope.launch { control.fail(PersistenceFactoryFailure.CLOSED) }.value())
            assertSame(original, control.failureResult())
            assertEquals(site, original.busySite)
            val next = PersistenceOwnedCallerControl.prepare(5_000)
            assertTrue(next.fail(PersistenceFactoryFailure.BUSY))
            val fresh = next.failureResult() as PersistenceFactoryResult.Refused
            assertNull(fresh.busySite)
            assertNotSame(original, fresh)
        }

    @Test
    fun `MODEL detached cancellation and semantic failure share exactly one resource-free winning state cell`() {
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        val cancellation = control.cancellationView(AtomicBoolean())
        val cell = lifecycleField(control, "disposition") as AtomicReference<*>
        assertSame(cell, lifecycleField(cancellation, "disposition"))
        assertTrue(control.attach())
        assertFalse(cancellation.isRequested())
        assertTrue(control.fail(PersistenceFactoryFailure.BUSY, PersistenceFactoryBusySite.ADMIT_G))
        assertTrue(cancellation.isRequested())
        assertEquals(PersistenceOwnedCallerDisposition.ABANDONED_BUSY, control.state())
        val result = control.failureResult() as PersistenceFactoryResult.Failed
        assertSame(control.receipt, result.receipt)
        assertSame(result, control.failureResult())
        val state = requireNotNull(cell.get())
        assertEquals(
            setOf(PersistenceOwnedCallerDisposition::class.java, PersistenceFactoryResult.Refused::class.java),
            state.javaClass.declaredFields.map { it.type }.toSet(),
        )
        assertSame(PersistenceOwnedCallerDisposition.ABANDONED_BUSY, lifecycleField(state, "value"))
        assertNull(lifecycleField(state, "refusal"))
        assertEquals(PersistenceFactoryProcessing.PENDING, result.receipt.state())
    }

    @Test
    fun `MODEL transport LIVE contention produces accepted BUSY not a reserve or admission site`() = OwnedCallerTestScope().use { scope ->
        val fixture = PhysicalTransportTestFixture()
        val release = hold(scope, transportTestLock(fixture.owner))
        val reason = requireNotNull(fixture.owner.liveFailure(null))
        assertEquals(PersistenceFactoryFailure.BUSY, reason)
        assertTrue(fixture.control.fail(reason))
        val result = fixture.control.failureResult() as PersistenceFactoryResult.Failed
        assertSame(fixture.control.receipt, result.receipt)
        assertTrue(requireNotNull(fixture.entry.attempt).cancellation.isRequested())
        assertTrue(PgLifecycleDatabaseDiagnostics.resultFields(result, null, null).contains("busy_site=NOT_APPLICABLE"))
        release.release()
    }

    private fun hold(scope: OwnedCallerTestScope, lock: ReentrantLock): OwnedCallerTestGate {
        val gate = scope.gate()
        scope.launch {
            lock.withLock { gate.hold() }
            true
        }
        gate.awaitEntered()
        return gate
    }
}
