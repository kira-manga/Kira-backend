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
import java.lang.reflect.Modifier

/** MODEL policy storage only. No setting, bridge, timer or runtime readiness is certified here. */
class PersistenceDriverAttemptPolicyTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @CsvSource(
        "ORIGINAL,ORIGINAL_PROVIDER,DRIVER_CONTRACT_ONLY,ORDINARY",
        "ORDINARY_WEAK,TRACKED_STANDARD,DRIVER_CONTRACT_ONLY,ORDINARY",
        "ORDINARY_STRONG,TRACKED_STANDARD,TRACKED_CONJUNCTION,ORDINARY",
        "DELETION,TRACKED_STANDARD,TRACKED_CONJUNCTION,APPROVED_DIRECT",
    )
    fun `MODEL independent recipe evidence and route are retained before dispatch`(name: String, recipe: String, evidence: String, route: String) {
        val policy = physicalTransportTestPolicy(name)
        val fixture = PhysicalTransportTestFixture(policy, admit = false)
        assertSame(policy, fixture.entry.policy)
        assertEquals(recipe, policy.recipe.name)
        assertEquals(evidence, policy.evidence.name)
        assertEquals(route, policy.route.name)
        assertEquals(name == "ORIGINAL", fixture.entry.transports == null)
        assertFalse(fixture.entry.dispatched)
        assertNull(fixture.binding.rendezvous.current)
        fixture.control.fail(PersistenceFactoryFailure.BUSY)
        assertTrue(fixture.binding.releaseRefused(fixture.entry))
        assertNull(fixture.binding.ledger.entries.single())
        assertSame(policy, fixture.entry.policy)
    }

    @Test
    fun `MODEL later ordinary weak selection cannot downgrade an earlier strong attempt`() {
        val binding = modelReadyBinding(2)
        val strong = PhysicalTransportTestFixture(PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION, binding, admit = false)
        val weak = PhysicalTransportTestFixture(PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT, binding, admit = false)
        assertSame(PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION, strong.entry.policy)
        assertSame(PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT, weak.entry.policy)
        assertNotSame(strong.transports, weak.transports)
        strong.control.fail(PersistenceFactoryFailure.CREATE_FAILED)
        assertSame(PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION, strong.entry.policy)
        assertEquals(PersistenceDriverEvidencePolicy.TRACKED_CONJUNCTION, strong.entry.policy.evidence)
        assertTrue(binding.releaseRefused(strong.entry))
        weak.control.fail(PersistenceFactoryFailure.CLOSED)
        assertTrue(binding.releaseRefused(weak.entry))
    }

    @Test
    fun `MODEL original provider is explicitly untracked and metadata has no mutable fields`() {
        val fixture = PhysicalTransportTestFixture(PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER)
        assertNull(fixture.entry.transports, "Absent ownership is not an empty tracked-ledger success.")
        val fields = PersistenceDriverAttemptPolicy::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(3, fields.size)
        assertTrue(fields.all { Modifier.isFinal(it.modifiers) && Modifier.isPrivate(it.modifiers) })
        fixture.retire()
        assertTrue(fixture.entry.retiring)
        assertNull(fixture.entry.transports)
        assertSame(PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER, fixture.entry.policy)
        assertSame(fixture.entry, fixture.binding.ledger.entries.single(), "A retirement fence is not capacity release.")
    }
}
