package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier

/** Model opening/resource facts exercise the real final handshake; no real Driver success is claimed. */
class PersistenceOwnedFactoryClaimTest {
    @Test
    fun `one exact prepared opaque result is claimed without invoking raw methods or completing processing`() {
        val binding = modelReadyBinding()
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        val raw = PhysicalTestConnection()
        val entry = modelOfferedEntry(binding, control, raw)
        val result = PersistenceFactoryResult.Success(entry.candidate, control.receipt)
        assertTrue(binding.take(entry, result))
        assertEquals(PersistenceOwnedCallerDisposition.TAKEN, control.state())
        assertTrue(requireNotNull(entry.attempt).transferred)
        assertTrue(requireNotNull(entry.attempt).callerDetached)
        assertNull(requireNotNull(entry.attempt).result)
        assertEquals(PersistenceFactoryProcessing.PENDING, result.receipt.state())
        assertSame(entry, binding.ledger.entries.single())
        assertSame(raw.raw, entry.raw.get())
        assertFalse(binding.take(entry, result))
        assertEquals(PersistenceOwnedCallerDisposition.TAKEN, control.state())
        assertNull(control.failureResult())
        assertEquals(0, raw.calls.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["rawMissing", "openingActive", "scopePending", "retiring", "sealed", "unknown", "wrongCandidate", "wrongReceipt"])
    fun `packaging does not authorize LIVE when any final entry or association predicate fails`(mode: String) {
        val binding = modelReadyBinding()
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        val raw = PhysicalTestConnection()
        val entry = modelOfferedEntry(binding, control, raw)
        var candidate = entry.candidate
        var receipt = control.receipt
        when (mode) {
            "rawMissing" -> entry.raw.set(null)
            "openingActive" -> entry.opening = PersistencePhysicalOpeningPhase.ACTIVE
            "scopePending" -> entry.scopeEnded = false
            "retiring" -> entry.retiring = true
            "sealed" -> binding.ledger.sealed = true
            "unknown" -> entry.unknown = true
            "wrongCandidate" -> candidate = PersistenceJdbcCandidate(java.util.concurrent.atomic.AtomicBoolean())
            "wrongReceipt" -> receipt = PersistenceOwnedCallerControl.prepare(5_000).receipt
        }
        val prepared = PersistenceFactoryResult.Success(candidate, receipt)
        assertFalse(binding.take(entry, prepared))
        assertEquals(PersistenceOwnedCallerPhase.ABANDONED, control.state().phase)
        assertFalse(requireNotNull(entry.attempt).transferred)
        assertSame(entry, binding.ledger.entries.single())
        assertEquals(PersistenceFactoryProcessing.PENDING, control.receipt.state())
        assertEquals(0, raw.calls.get())
    }

    @Test
    fun `original budget is rechecked after prepared result allocation and never restarted for final claim`() {
        val binding = modelReadyBinding()
        val control = PersistenceOwnedCallerControl.prepare(500)
        val raw = PhysicalTestConnection()
        val entry = modelOfferedEntry(binding, control, raw)
        val prepared = PersistenceFactoryResult.Success(entry.candidate, control.receipt)
        awaitOwnedTestExpiry(control.budget)
        assertFalse(binding.take(entry, prepared))
        assertEquals(PersistenceOwnedCallerDisposition.ABANDONED_TIMEOUT, control.state())
        assertSame(control.budget, requireNotNull(entry.attempt).budget)
        assertFalse(requireNotNull(entry.attempt).transferred)
        assertEquals(0, raw.calls.get())
    }

    @Test
    fun `opaque retirement request has no JDBC backreference or direct legacy G mutation`() {
        val binding = modelReadyBinding()
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        val entry = modelOfferedEntry(binding, control, PhysicalTestConnection())
        val fields = PersistenceJdbcCandidate::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(listOf(java.util.concurrent.atomic.AtomicBoolean::class.java), fields.map { it.type })
        val methods = PersistenceJdbcCandidate::class.java.declaredMethods
        assertEquals(
            setOf("requestRetirement", "isRetirementRequested", "toString"),
            methods.filter { Modifier.isPublic(it.modifiers) }.map { it.name }.toSet(),
        )
        // JaCoCo 0.8.13 instruments this test JVM with a private static synthetic initializer.
        // No arbitrary nonpublic method is excluded; all consumer-visible methods remain checked above.
        assertTrue(
            methods.filterNot { Modifier.isPublic(it.modifiers) }.all {
                it.name == "\$jacocoInit" && it.isSynthetic && Modifier.isPrivate(it.modifiers) && Modifier.isStatic(it.modifiers)
            },
        )
        assertTrue(entry.candidate.requestRetirement())
        assertFalse(entry.candidate.requestRetirement())
        assertTrue(entry.retirementRequested.get())
        assertFalse(entry.retiring, "Logical retirement is not a physical fence.")
        assertFalse(binding.take(entry, PersistenceFactoryResult.Success(entry.candidate, control.receipt)))
        assertEquals(PersistenceOwnedCallerDisposition.ABANDONED_CLOSED, control.state())
        assertNotSame(entry, entry.candidate)
    }
}

private fun modelOfferedEntry(
    binding: PersistencePhysicalFactoryBinding,
    control: PersistenceOwnedCallerControl,
    raw: PhysicalTestConnection,
): PersistencePhysicalEntry {
    val entry = requireNotNull(binding.reserve(control))
    assertTrue(binding.admit(entry))
    val attempt = requireNotNull(entry.attempt)
    attempt.beginWork()
    entry.opening = PersistencePhysicalOpeningPhase.SETTLED
    entry.scopeEnded = true
    entry.raw.set(raw.raw)
    attempt.retain(entry.candidate)
    attempt.offer()
    return entry
}
