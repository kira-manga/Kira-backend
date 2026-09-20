package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Counter-only assertions; the connected PG test proves that real authentication precedes these calls. */
class ComplaintOwnerHistoryAdmissionTest {
    @Test
    fun `owner reads have their own exact minute window and scope dimension without borrowing session quota`() {
        val clock = MutableAdmissionTestClock()
        val guard = historyTestIngress(clock)
        val actor = ScopedInstallationId(UUID.randomUUID(), ComplaintDataScope.of(UUID.randomUUID()))
        repeat(120) { charge(guard, actor) }
        val failure = assertThrows<ComplaintAdmissionRejected> { charge(guard, actor, "192.0.2.2") }
        assertEquals(ComplaintAdmissionFailure.RATE_LIMITED, failure.code)
        assertEquals(60L, failure.retryAfterSeconds)
        charge(guard, ScopedInstallationId(actor.id, ComplaintDataScope.of(UUID.randomUUID())), "192.0.2.3")
        clock.value = 60_000_000_000L
        charge(guard, actor)
        guard.withIngress(historyTestRequest(ip = "192.0.2.4")) { context ->
            guard.startSession(context)
            val identity = Any()
            guard.chargeSession(context, actor, identity)
            guard.consumeSession(context, identity)
        }
    }

    @Test
    fun `history attempt must belong to live ingress with one start charge and consume`() {
        val guard = historyTestIngress()
        val actor = ScopedInstallationId(UUID.randomUUID(), ComplaintDataScope.of(UUID.randomUUID()))
        assertThrows<ComplaintAdmissionRejected> { guard.startOwnerHistory(ComplaintIngressContext()) }
        guard.withIngress(historyTestRequest()) { context ->
            assertThrows<ComplaintAdmissionRejected> { guard.chargeOwnerHistory(context, actor, Any()) }
            guard.startOwnerHistory(context)
            assertThrows<ComplaintAdmissionRejected> { guard.startOwnerHistory(context) }
            val identity = Any()
            guard.chargeOwnerHistory(context, actor, identity)
            assertThrows<ComplaintAdmissionRejected> { guard.chargeOwnerHistory(context, actor, identity) }
            assertThrows<ComplaintAdmissionRejected> { guard.consumeOwnerHistory(context, Any()) }
            guard.consumeOwnerHistory(context, identity)
            assertThrows<ComplaintAdmissionRejected> { guard.consumeOwnerHistory(context, identity) }
        }
    }

    private fun charge(guard: ComplaintIngressAdmission, actor: ScopedInstallationId, ip: String = "192.0.2.1") {
        guard.withIngress(historyTestRequest(ip = ip)) { context ->
            guard.startOwnerHistory(context)
            val identity = Any()
            guard.chargeOwnerHistory(context, actor, identity)
            guard.consumeOwnerHistory(context, identity)
        }
    }
}
