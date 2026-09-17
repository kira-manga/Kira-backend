package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationTuple
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport

/** Counter/capability tests only. Actual current authentication and database charges are exercised by the producer IT. */
class ComplaintOwnerCreateAdmissionTest {
    @Test
    fun `global abuse ceiling is strictly below every nonzero create charge threshold in independent P`() {
        val policy = ownerCreateTestCapacityPolicy()
        ComplaintOwnerCreateAdmissionPolicy.Bounded(policy, 17, 100, 8)
        assertThrows<IllegalArgumentException> { ComplaintOwnerCreateAdmissionPolicy.Bounded(policy, 18, 100, 8) }
        for (counter in ComplaintCapacityCounter.entries.filter { ComplaintCapacityCharges.OWNER_CREATE[it] > 0 }) {
            val creation = policy.creationLimit.with(counter, ComplaintCapacityCharges.OWNER_CREATE[counter] * 5)
            val limited = ComplaintCapacityPolicyV1.of(policy.hardLimit, creation, 100)
            assertThrows<IllegalArgumentException> { ComplaintOwnerCreateAdmissionPolicy.Bounded(limited, 5, 100, 8) }
        }
        val limits = ComplaintOwnerCreateAdmissionPolicy.Bounded(policy, 12, 100, 8)
        assertTrue(limits.matchesLocked(ledger(policy)))
        val mismatched = ComplaintCapacityPolicyV1.of(policy.hardLimit, policy.creationLimit.with(ComplaintCapacityCounter.RESOURCE_IDS, 8_000_000), 100)
        assertFalse(limits.matchesLocked(ledger(mismatched)))
    }

    @Test
    fun `duplicates never repay actor or global quota and denials install no dedup member`() {
        val f = CounterFixture(global = 12)
        val actor = f.actor
        val first = tuple(actor)
        f.admit(first, 0)
        repeat(20) { f.admit(first, 1) }
        repeat(9) { f.admit(tuple(actor), 1) }
        val denied = tuple(actor)
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { f.admit(denied, 1) }
        val other = ScopedInstallationId(UUID.randomUUID(), actor.scope)
        f.admit(tuple(other), 1)
        f.admit(tuple(other), 1)
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { f.admit(tuple(other), 1) }
        // A denied member is not later a dedup hit: it consumes the first slot after the window.
        f.admit(denied, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS + 1)
        repeat(9) { f.admit(tuple(actor), ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS + 1) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { f.admit(tuple(actor), ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS + 1) }
    }

    @Test
    fun `twenty five hour dedup has finite membership bounded pruning and no duplicate TTL extension`() {
        val f = CounterFixture(members = 2, prune = 1)
        val first = tuple(f.actor)
        f.admit(first, 0)
        f.admit(tuple(f.actor), 0)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.admit(tuple(f.actor), 1) }
        val expiry = ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS
        f.admit(first, expiry - 1)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.admit(tuple(f.actor), expiry) }
        f.admit(tuple(f.actor), expiry) // Second bounded prune drained the backlog; neither old member was extended.
        f.admit(tuple(f.actor), expiry)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.admit(tuple(f.actor), expiry) }
    }

    @Test
    fun `previous generation duplicate does not republish a current member or repay before safe retirement`() {
        val f = CounterFixture(members = 2)
        val first = tuple(f.actor)
        f.admit(first, 0)
        f.ring.rotate(admissionTestKey(2), 1)
        f.admit(first, 2)
        // Only one old member remains. A genuinely new dual-key attempt cannot fit two slots.
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.admit(tuple(f.actor), 2) }
        admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { f.ring.retirePrevious(ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS) }
        val old = f.ring.retirePrevious(ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS + 1)
        f.store.removeGeneration(old)
        f.quotas.removeGeneration(old)
        f.admit(first, ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS + 1)
        f.admit(tuple(f.actor), ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS + 1)
    }

    @Test
    fun `status and history share exactly one owner read bucket without an extra status allowance`() {
        val clock = MutableAdmissionTestClock()
        val ingress = ownerCreateTestIngress(clock = clock)
        val actor = ScopedInstallationId(UUID.randomUUID(), ComplaintDataScope.of(UUID.randomUUID()))
        repeat(120) { index -> chargeRead(ingress, actor, status = index % 2 == 0) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { chargeRead(ingress, actor, status = true, ip = "192.0.2.2") }
        clock.value = ComplaintAdmissionPolicy.INGRESS_WINDOW_NANOS
        chargeRead(ingress, actor, status = true)
    }

    @Test
    fun `create handoff requires exact live context thread tuple phase and monotonic production clock`() {
        val ingress = ownerCreateTestIngress()
        val tuple = tuple(ScopedInstallationId(UUID.randomUUID(), ComplaintDataScope.of(UUID.randomUUID())))
        assertThrows<ComplaintAdmissionRejected> { ComplaintIngressAdmission.bindOwnerCreate(object : ComplaintAdmittedOwnerCreate {}, Any()) }
        lateinit var retained: ComplaintAdmittedOwnerCreate
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerCreate(context)
            retained = ingress.admitOwnerCreate(context, tuple)
            assertThrows<ComplaintAdmissionRejected> { ingress.admitOwnerCreate(context, tuple) }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val failure = executor.submit<ComplaintAdmissionRejected> {
                    assertThrows<ComplaintAdmissionRejected> { ComplaintIngressAdmission.bindOwnerCreate(retained, Any()) }
                }.get()
                assertEquals(ComplaintAdmissionFailure.INVALID_CONTEXT, failure.code)
            } finally {
                executor.shutdownNow()
            }
            val phase = Any()
            ComplaintIngressAdmission.bindOwnerCreate(retained, phase)
            assertThrows<ComplaintAdmissionRejected> { ComplaintIngressAdmission.bindOwnerCreate(retained, phase) }
            assertThrows<ComplaintAdmissionRejected> { ComplaintIngressAdmission.claimOwnerCreate(retained, Any(), tuple) }
            assertThrows<ComplaintAdmissionRejected> {
                ComplaintIngressAdmission.claimOwnerCreate(
                    retained,
                    phase,
                    ComplaintOwnerOperationTuple(tuple.installation, tuple.key, tuple.targetId, tuple.fingerprintBytes()),
                )
            }
            ComplaintIngressAdmission.claimOwnerCreate(retained, phase, tuple)
            assertThrows<ComplaintAdmissionRejected> { ComplaintIngressAdmission.checkOwnerCreateWrite(retained, phase) }
            ComplaintIngressAdmission.checkOwnerCreateBounds(retained, phase, ledger(ownerCreateTestCapacityPolicy()))
            ComplaintIngressAdmission.checkOwnerCreateWrite(retained, phase)
        }
        assertThrows<ComplaintAdmissionRejected> { ComplaintIngressAdmission.checkOwnerCreateWrite(retained, Any()) }
        val fakeClockIngress = ownerCreateTestIngress(clock = MutableAdmissionTestClock())
        fakeClockIngress.withIngress(historyTestRequest()) { context ->
            fakeClockIngress.startOwnerCreate(context)
            assertThrows<ComplaintAdmissionRejected> { fakeClockIngress.admitOwnerCreate(context, tuple) }
        }
    }

    @Test
    fun `real monotonic five second deadline cannot be renewed by binding the same handoff`() {
        val ingress = ownerCreateTestIngress()
        val tuple = tuple(ScopedInstallationId(UUID.randomUUID(), ComplaintDataScope.of(UUID.randomUUID())))
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerCreate(context)
            val admitted = ingress.admitOwnerCreate(context, tuple)
            val started = System.nanoTime()
            val phase = Any()
            ComplaintIngressAdmission.bindOwnerCreate(admitted, phase)
            while (System.nanoTime() - started < ComplaintAdmissionPolicy.ADMISSION_LIFETIME_NANOS) LockSupport.parkNanos(1_000_000)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerCreate(admitted, phase, tuple) }
        }
    }

    private fun chargeRead(ingress: ComplaintIngressAdmission, actor: ScopedInstallationId, status: Boolean, ip: String = "192.0.2.1") {
        ingress.withIngress(historyTestRequest(ip = ip)) { context ->
            val identity = Any()
            if (status) {
                ingress.startOwnerStatus(context)
                ingress.chargeOwnerStatus(context, actor, identity)
                ingress.consumeOwnerStatus(context, identity)
            } else {
                ingress.startOwnerHistory(context)
                ingress.chargeOwnerHistory(context, actor, identity)
                ingress.consumeOwnerHistory(context, identity)
            }
        }
    }

    private fun ledger(policy: ComplaintCapacityPolicyV1) = ComplaintCapacityLedger(
        ComplaintCapacityConfiguration.of(policy.digestBytes(), false),
        ComplaintCapacityBalance(policy.hardLimit, policy.creationLimit, policy.hardLimit),
    )

    private fun tuple(actor: ScopedInstallationId) = ComplaintOwnerOperationTuple(actor, UUID.randomUUID(), UUID.randomUUID(), ByteArray(32) { 43 })

    private class CounterFixture(global: Int = 12, members: Int = 128, prune: Int = 128) {
        val actor = ScopedInstallationId(UUID.randomUUID(), ComplaintDataScope.of(UUID.randomUUID()))
        val ring = ComplaintAdmissionKeyRing(admissionTestKeys())
        val store = ComplaintOwnerCreateAdmissionStore(ComplaintOwnerCreateAdmissionPolicy.Bounded(ownerCreateTestCapacityPolicy(), global, members, prune))
        val quotas =
            ComplaintAdmissionWindowStore(4096, 131072, 128, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS)

        fun admit(tuple: ComplaintOwnerOperationTuple, now: Long) {
            val keys = ring.keys()
            store.admit(
                ComplaintAdmissionPseudonyms.ownerCreateMember(keys, tuple),
                ComplaintAdmissionPseudonyms.ownerCreateActor(keys, tuple.installation),
                ComplaintAdmissionPseudonyms.ownerCreateGlobal(keys),
                quotas,
                now,
            )
        }
    }
}

/** Synthetic P used with the existing counter fixture; never activation or physical sizing evidence. */
internal fun ownerCreateTestCapacityPolicy(): ComplaintCapacityPolicyV1 = ComplaintCapacityPolicyV1.of(
    ComplaintCapacityVector.of(LongArray(22) { 10_000_000 }),
    ComplaintCapacityVector.of(LongArray(22) { 9_000_000 }),
    100,
)

internal fun ownerCreateTestIngress(
    policy: ComplaintCapacityPolicyV1 = ownerCreateTestCapacityPolicy(),
    clock: ComplaintAdmissionNanoClock = SystemComplaintAdmissionNanoClock,
    creates: ComplaintOwnerCreateAdmissionPolicy = ComplaintOwnerCreateAdmissionPolicy.Bounded(policy, 12, 1024, 128),
): ComplaintIngressAdmission = ComplaintIngressAdmission(
    ClientIpResolver(KiraSecurityProperties()),
    admissionTestPolicy(enrollment = ComplaintEnrollmentAdmissionPolicy.Bounded(policy, 9)),
    admissionTestKeys(),
    clock,
    creates,
)
