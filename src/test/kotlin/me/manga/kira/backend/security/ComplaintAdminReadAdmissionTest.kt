package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** Counter primitives only; the connected IT requires actual current ADMIN and original preflight release before charging. */
class ComplaintAdminReadAdmissionTest {
    private val actor = UUID.randomUUID()
    private val scope = ComplaintDataScope.of(UUID.randomUUID())

    @Test
    fun `search detail and stats share exact60 per ADMIN scope minute without borrowing the owner read bucket`() {
        val clock = MutableAdmissionTestClock()
        val guard = adminReadTestIngress(clock)
        repeat(60) { if (it % 3 == 2) chargeStats(guard) else charge(guard, detail = it % 3 == 1) }
        val denied = admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { charge(guard, ip = "192.0.2.2") }
        assertEquals(60L, denied.retryAfterSeconds)
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { chargeStats(guard) }
        charge(guard, selectedScope = ComplaintDataScope.of(UUID.randomUUID()))
        charge(guard, selectedActor = UUID.randomUUID())
        repeat(120) {
            guard.withIngress(historyTestRequest(ip = "192.0.2.3")) { context ->
                guard.startOwnerHistory(context)
                val identity = Any()
                guard.chargeOwnerHistory(context, ScopedInstallationId(actor, scope), identity)
                guard.consumeOwnerHistory(context, identity)
            }
        }
        clock.value = 59_999_999_999L
        assertEquals(1L, admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { charge(guard) }.retryAfterSeconds)
        clock.value = 60_000_000_000L
        charge(guard)
    }

    @Test
    fun `approved policy is lowerable only and omitted policy remains Disabled`() {
        for (limit in listOf(0, -1, 61, Int.MAX_VALUE)) {
            assertThrows<IllegalArgumentException> { ComplaintAdminReadAdmissionPolicy.Bounded(limit) }
        }
        val clock = MutableAdmissionTestClock()
        val disabled = ComplaintIngressAdmission(ClientIpResolver(KiraSecurityProperties()), admissionTestPolicy(), admissionTestKeys(), clock)
        disabled.withIngress(adminReadSearchRequest(scope)) { context ->
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { disabled.startAdminSearch(context) }
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { disabled.startAdminDetail(context) }
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { disabled.startAdminStats(context) }
        }
        val lower = adminReadTestIngress(clock, adminPolicy = ComplaintAdminReadAdmissionPolicy.Bounded(2))
        charge(lower)
        charge(lower, detail = true)
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { chargeStats(lower) }
    }

    @Test
    fun `only live original thread registry named operation and one use admit the opaque identity`() {
        val guard = adminReadTestIngress()
        val foreign = adminReadTestIngress()
        var escaped: ComplaintIngressContext? = null
        guard.withIngress(adminReadSearchRequest(scope)) { context ->
            escaped = context
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.chargeAdminRead(context, actor, scope, Any()) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { foreign.startAdminSearch(context) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.startAdminSearch(ComplaintIngressContext()) }
            guard.startAdminSearch(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.startAdminDetail(context) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.startAdminStats(context) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.chargeAdminRead(context, actor, ComplaintDataScope.LIVE, Any()) }
            val identity = Any()
            guard.chargeAdminRead(context, actor, scope, identity)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.chargeAdminRead(context, actor, scope, identity) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.consumeAdminRead(context, Any()) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.consumeOwnerHistory(context, identity) }
            OwnedCallerTestScope().use { callers ->
                callers.launch { admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.consumeAdminRead(context, identity) } }.value()
            }
            guard.consumeAdminRead(context, identity)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.consumeAdminRead(context, identity) }
        }
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.startAdminDetail(checkNotNull(escaped)) }
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.startAdminStats(checkNotNull(escaped)) }
        guard.withIngress(adminReadSearchRequest(scope)) { context ->
            guard.startOwnerHistory(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.chargeAdminRead(context, actor, scope, Any()) }
        }
    }

    @Test
    fun `five second lifetime cannot renew through charge or consume and a spent charge is not refunded`() {
        val clock = MutableAdmissionTestClock()
        val guard = adminReadTestIngress(clock, adminPolicy = ComplaintAdminReadAdmissionPolicy.Bounded(2))
        guard.withIngress(adminReadSearchRequest(scope)) { context ->
            guard.startAdminSearch(context)
            val identity = Any()
            guard.chargeAdminRead(context, actor, scope, identity)
            clock.value = 4_999_999_999L
            guard.consumeAdminRead(context, identity)
        }
        guard.withIngress(adminReadSearchRequest(scope)) { context ->
            guard.startAdminStats(context)
            val identity = Any()
            guard.chargeAdminRead(context, actor, scope, identity)
            clock.value += 5_000_000_000L
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.consumeAdminRead(context, identity) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.chargeAdminRead(context, actor, scope, identity) }
        }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { charge(guard) }
    }

    @Test
    fun `rotation cannot reset a charged Admin quota and overlap uses the same bounded store`() {
        val clock = MutableAdmissionTestClock()
        val guard = adminReadTestIngress(clock, adminPolicy = ComplaintAdminReadAdmissionPolicy.Bounded(2))
        charge(guard)
        charge(guard, detail = true)
        guard.withIngress(adminReadSearchRequest(scope)) {
            admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { guard.rotate(admissionTestKey(3)) }
        }
        guard.rotate(admissionTestKey(3))
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { charge(guard) }
        val other = UUID.randomUUID()
        charge(guard, selectedActor = other)
        charge(guard, selectedActor = other, detail = true)
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { charge(guard, selectedActor = other) }
        clock.value = ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS - 1
        admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { guard.retirePrevious() }
        clock.value++
        guard.retirePrevious()
        charge(guard)
    }

    @Test
    fun `Admin and owner rows share finite cardinality and event budgets with no live eviction or new actor map`() {
        val clock = MutableAdmissionTestClock()
        val guard = adminReadTestIngress(clock, admissionTestPolicy(semanticBuckets = 2, semanticEvents = 3))
        charge(guard)
        guard.withIngress(historyTestRequest()) { context ->
            guard.startOwnerHistory(context)
            val identity = Any()
            guard.chargeOwnerHistory(context, ScopedInstallationId(actor, scope), identity)
            guard.consumeOwnerHistory(context, identity)
        }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { charge(guard, selectedActor = UUID.randomUUID()) }
        charge(guard, detail = true)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { charge(guard) }
        clock.value = 60_000_000_000L
        charge(guard, selectedActor = UUID.randomUUID())
    }

    @Test
    fun `minute expiry backlog is bounded and cannot evict live Admin quotas to admit extra actors`() {
        val clock = MutableAdmissionTestClock()
        val guard = adminReadTestIngress(clock, admissionTestPolicy(semanticBuckets = 3, prune = 1))
        repeat(3) { charge(guard, selectedActor = UUID.randomUUID()) }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { charge(guard, selectedActor = UUID.randomUUID()) }
        clock.value = 60_000_000_000L
        repeat(2) { admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { charge(guard) } }
        charge(guard)
    }

    @Test
    fun `Admin semantic start charge and consume all refuse retained Spring resources before mutating quota`() {
        val guard = adminReadTestIngress(adminPolicy = ComplaintAdminReadAdmissionPolicy.Bounded(1))
        guard.withIngress(adminReadSearchRequest(scope)) { context ->
            val resource = Any()
            TransactionSynchronizationManager.bindResource(resource, Any())
            try {
                assertThrows<PersistencePhaseException> { guard.startAdminStats(context) }
            } finally {
                TransactionSynchronizationManager.unbindResource(resource)
            }
            guard.startAdminStats(context)
            val identity = Any()
            TransactionSynchronizationManager.bindResource(resource, Any())
            try {
                val failure = assertThrows<PersistencePhaseException> { guard.chargeAdminRead(context, actor, scope, identity) }
                assertNull(failure.cause)
            } finally {
                TransactionSynchronizationManager.unbindResource(resource)
            }
            guard.chargeAdminRead(context, actor, scope, identity)
            TransactionSynchronizationManager.bindResource(resource, Any())
            try {
                assertThrows<PersistencePhaseException> { guard.consumeAdminRead(context, identity) }
            } finally {
                TransactionSynchronizationManager.unbindResource(resource)
            }
            guard.consumeAdminRead(context, identity)
        }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { charge(guard) }
    }

    private fun charge(
        guard: ComplaintIngressAdmission,
        selectedActor: UUID = actor,
        selectedScope: ComplaintDataScope = scope,
        detail: Boolean = false,
        ip: String = "192.0.2.1",
    ) {
        guard.withIngress(adminReadSearchRequest(scope, ip = ip)) { context ->
            if (detail) guard.startAdminDetail(context) else guard.startAdminSearch(context)
            val identity = Any()
            guard.chargeAdminRead(context, selectedActor, selectedScope, identity)
            guard.consumeAdminRead(context, identity)
        }
    }

    private fun chargeStats(guard: ComplaintIngressAdmission) {
        guard.withIngress(adminReadStatsRequest(scope)) { context ->
            guard.startAdminStats(context)
            val identity = Any()
            guard.chargeAdminRead(context, actor, scope, identity)
            guard.consumeAdminRead(context, identity)
        }
    }
}
