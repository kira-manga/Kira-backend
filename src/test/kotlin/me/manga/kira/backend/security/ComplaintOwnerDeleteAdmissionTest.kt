package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationTuple
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Admission/custody tests only; sixty attempts do not claim sixty SQL commits or authenticated TEST activation. */
class ComplaintOwnerDeleteAdmissionTest {
    @Test
    fun `one actual ingress charges mixed edits and deletes against the same sixty per hour actor allowance`() {
        val ingress = ownerDeleteTestIngress()
        val actor = admissionTestActor(1)
        val first = tuple(actor)
        fun deletion(value: ComplaintOwnerDeleteTuple, ip: String = "192.0.2.1") = ingress.withIngress(historyTestRequest(ip = ip)) { context ->
            ingress.startOwnerDelete(context)
            ingress.admitOwnerDelete(context, value)
        }
        fun edit(value: ComplaintOwnerDeleteTuple) = ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerEdit(context)
            ingress.admitOwnerEdit(context, ComplaintOwnerEditTuple(value.installation, value.key, value.targetId, value.fingerprintBytes()))
        }
        deletion(first)
        repeat(10) { deletion(first) }
        repeat(59) { index -> if (index % 2 == 0) edit(tuple(actor)) else deletion(tuple(actor)) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { deletion(tuple(actor), "192.0.2.2") }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { edit(tuple(actor)) }
        deletion(first) // Retained duplicate, not another paid slot.
        deletion(tuple(admissionTestActor(2)))
        repeat(10) {
            ingress.withIngress(historyTestRequest()) { context ->
                ingress.startOwnerCreate(context)
                ingress.admitOwnerCreate(context, ComplaintOwnerOperationTuple(actor, UUID.randomUUID(), UUID.randomUUID(), ByteArray(32)))
            }
        }
    }

    @Test
    fun `refused sixty first member is not installed and original one hour reset does not extend duplicate lifetime`() {
        val f = Counters()
        val actor = admissionTestActor(1)
        val first = tuple(actor)
        f.delete(first, 0)
        repeat(59) { index -> if (index % 2 == 0) f.edit(tuple(actor), 1) else f.delete(tuple(actor), 1) }
        val denied = tuple(actor)
        assertEquals(3600L, admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { f.delete(denied, 1) }.retryAfterSeconds)
        val hour = ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS + 1
        f.delete(denied, hour)
        f.delete(first, hour)
        repeat(59) { f.edit(tuple(actor), hour) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { f.delete(tuple(actor), hour) }
    }

    @Test
    fun `delete edit and creation retain one finite dedup owner through bounded pruning and rotation`() {
        val f = Counters(limit = 2, prune = 1)
        val first = tuple(admissionTestActor(1))
        val second = tuple(first.installation)
        f.delete(first, 0)
        f.edit(second, 0)
        val expiry = ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS
        f.delete(first, expiry - 1)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.delete(tuple(first.installation), expiry - 1) }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.delete(tuple(first.installation), expiry) }
        f.delete(first, expiry)
        f.ring.rotate(admissionTestKey(2), expiry + 1)
        f.delete(first, expiry + 2)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.edit(tuple(first.installation), expiry + 2) }
        admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { f.ring.retirePrevious(2 * expiry) }
        val retired = f.ring.retirePrevious(2 * expiry + 1)
        f.members.removeGeneration(retired)
        f.quotas.removeGeneration(retired)
        f.delete(first, 2 * expiry + 1)
        f.edit(second, 2 * expiry + 1)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.delete(tuple(first.installation), 2 * expiry + 1) }

        val ingress = ownerDeleteTestIngress(members = 2, prune = 1)
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerDelete(context)
            ingress.admitOwnerDelete(context, first)
        }
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerCreate(context)
            ingress.admitOwnerCreate(context, ComplaintOwnerOperationTuple(first.installation, UUID.randomUUID(), UUID.randomUUID(), ByteArray(32)))
        }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) {
            ingress.withIngress(historyTestRequest()) { context ->
                ingress.startOwnerEdit(context)
                ingress.admitOwnerEdit(context, ComplaintOwnerEditTuple(first.installation, second.key, second.targetId, second.fingerprintBytes()))
            }
        }
    }

    @Test
    fun `fixed delete member frame binds every dimension while actor quota remains the existing edit delete family`() {
        val keys = ComplaintAdmissionKeyRing(admissionTestKeys()).keys()
        val original = tuple(admissionTestActor(1))
        fun uuid(id: UUID): ByteArray = ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
        val parts = listOf(
            "kira-complaint-admission-v1".toByteArray(), "MEMBER".toByteArray(), "INSTALLATION".toByteArray(),
            uuid(original.installation.id), uuid(original.installation.scope.id), "OWNER_DELETE".toByteArray(),
            uuid(original.key), uuid(original.targetId), original.fingerprintBytes(),
        )
        val frame = ByteBuffer.allocate(parts.sumOf { 4 + it.size }).apply { parts.forEach { putInt(it.size).put(it) } }.array()
        val member = ComplaintOwnerDeleteAdmissionFrames.member(keys, original)
        assertEquals(keys.map { ComplaintAdmissionBucketKey(it.id, it.digest(frame)) }, member)
        val edit = ComplaintOwnerEditTuple(original.installation, original.key, original.targetId, original.fingerprintBytes())
        assertNotEquals(member, ComplaintAdmissionPseudonyms.ownerEditMember(keys, edit))
        val actor = ComplaintAdmissionPseudonyms.ownerEditDeleteActor(keys, original.installation)
        assertNotEquals(actor, ComplaintAdmissionPseudonyms.ownerDeleteAllActor(keys, original.installation))
        assertNotEquals(actor, ComplaintAdmissionPseudonyms.ownerCreateActor(keys, original.installation))
        val changes = listOf(
            tuple(admissionTestActor(2), original.key, original.targetId, original.fingerprintBytes()),
            tuple(ScopedInstallationId(original.installation.id, ComplaintDataScope.of(UUID.randomUUID())), original.key, original.targetId, original.fingerprintBytes()),
            tuple(original.installation, UUID.randomUUID(), original.targetId, original.fingerprintBytes()),
            tuple(original.installation, original.key, UUID.randomUUID(), original.fingerprintBytes()),
            tuple(original.installation, original.key, original.targetId, ByteArray(32)),
        )
        changes.forEach { assertNotEquals(member, ComplaintOwnerDeleteAdmissionFrames.member(keys, it)) }
    }

    @Test
    fun `delete handoff refuses forgery cross thread context operation phase and equal but distinct tuple before writes`() {
        val ingress = ownerDeleteTestIngress()
        val value = tuple(admissionTestActor(1))
        val phase = Any()
        lateinit var admitted: ComplaintAdmittedOwnerDelete
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) {
            ComplaintIngressAdmission.bindOwnerDelete(object : ComplaintAdmittedOwnerDelete {}, phase)
        }
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerDelete(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ingress.admitOwnerDelete(ComplaintIngressContext(), value) }
            admitted = ingress.admitOwnerDelete(context, value)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ingress.admitOwnerDelete(context, value) }
            val worker = Executors.newSingleThreadExecutor()
            try {
                worker.submit<Unit> {
                    admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindOwnerDelete(admitted, phase) }
                }.get(2, TimeUnit.SECONDS)
            } finally {
                worker.shutdownNow()
                assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
            }
            ComplaintIngressAdmission.requireOwnerDeleteEntry(admitted, value)
            ComplaintIngressAdmission.bindOwnerDelete(admitted, phase)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindOwnerDelete(admitted, phase) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerDelete(admitted, Any(), value) }
            val copy = tuple(value.installation, value.key, value.targetId, value.fingerprintBytes())
            assertTrue(value.matches(copy))
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerDelete(admitted, phase, copy) }
            ComplaintIngressAdmission.claimOwnerDelete(admitted, phase, value)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerDelete(admitted, phase, value) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkOwnerDeleteWrite(admitted, phase) }
            val policy = ownerCreateTestCapacityPolicy()
            val altered = ComplaintCapacityPolicyV1.of(policy.hardLimit, policy.creationLimit.with(ComplaintCapacityCounter.RESOURCE_IDS, 1), 100)
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { ComplaintIngressAdmission.checkOwnerDeleteBounds(admitted, phase, ledger(altered)) }
            ComplaintIngressAdmission.checkOwnerDeleteBounds(admitted, phase, ledger(policy))
            ComplaintIngressAdmission.checkOwnerDeleteWrite(admitted, phase)
        }
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkOwnerDeleteWrite(admitted, phase) }
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerEdit(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ingress.admitOwnerDelete(context, value) }
        }
    }

    @Test
    fun `disabled mismatched and synthetic clock admission cannot mint a real delete entry`() {
        val value = tuple(admissionTestActor(1))
        val disabled = ownerEditTestIngress()
        disabled.withIngress(historyTestRequest()) { context ->
            disabled.startOwnerDelete(context)
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { disabled.admitOwnerDelete(context, value) }
        }
        val fakeClock = ownerDeleteTestIngress(clock = MutableAdmissionTestClock())
        fakeClock.withIngress(historyTestRequest()) { context ->
            fakeClock.startOwnerDelete(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { fakeClock.admitOwnerDelete(context, value) }
        }
        assertThrows<IllegalArgumentException> { ownerDeleteTestIngress(members = 1) }
        assertThrows<IllegalArgumentException> { ownerDeleteTestIngress(prune = 129) }
        assertFalse(ComplaintOwnerDeleteAdmissionPolicy.Bounded(ownerCreateTestCapacityPolicy(), 2, 1).matchesLocked(ledger(ownerDeleteAllTestCapacityPolicy())))
    }

    @Test
    fun `delete binding does not renew the original five second monotonic admission deadline`() {
        val ingress = ownerDeleteTestIngress()
        val value = tuple(admissionTestActor(1))
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerDelete(context)
            val admitted = ingress.admitOwnerDelete(context, value)
            val phase = Any()
            val started = System.nanoTime()
            ComplaintIngressAdmission.bindOwnerDelete(admitted, phase)
            while (System.nanoTime() - started < ComplaintAdmissionPolicy.ADMISSION_LIFETIME_NANOS) LockSupport.parkNanos(1_000_000)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerDelete(admitted, phase, value) }
        }
    }

    private fun tuple(actor: ScopedInstallationId, key: UUID = UUID.randomUUID(), target: UUID = UUID.randomUUID(), digest: ByteArray = ByteArray(32) { 43 }) =
        ComplaintOwnerDeleteTuple(actor, key, target, digest)

    private fun ledger(policy: ComplaintCapacityPolicyV1) = ComplaintCapacityLedger(
        ComplaintCapacityConfiguration.of(policy.digestBytes(), false),
        ComplaintCapacityBalance(policy.hardLimit, policy.creationLimit, policy.hardLimit),
    )

    private class Counters(limit: Int = 1024, prune: Int = 128) {
        private val policy = ownerCreateTestCapacityPolicy()
        val ring = ComplaintAdmissionKeyRing(admissionTestKeys())
        val members = ComplaintMutationAdmissionMembers(limit, prune)
        val quotas = ComplaintAdmissionWindowStore(4096, 131072, 128, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS)
        private val deletes = ComplaintOwnerDeleteAdmissionStore(ComplaintOwnerDeleteAdmissionPolicy.Bounded(policy, limit, prune), members)
        private val edits = ComplaintOwnerEditAdmissionStore(ComplaintOwnerEditAdmissionPolicy.Bounded(policy, limit, prune), members)

        fun delete(value: ComplaintOwnerDeleteTuple, now: Long) {
            val keys = ring.keys()
            deletes.admit(ComplaintOwnerDeleteAdmissionFrames.member(keys, value), ComplaintAdmissionPseudonyms.ownerEditDeleteActor(keys, value.installation), quotas, now)
        }

        fun edit(value: ComplaintOwnerDeleteTuple, now: Long) {
            val keys = ring.keys()
            val tuple = ComplaintOwnerEditTuple(value.installation, value.key, value.targetId, value.fingerprintBytes())
            edits.admit(ComplaintAdmissionPseudonyms.ownerEditMember(keys, tuple), ComplaintAdmissionPseudonyms.ownerEditDeleteActor(keys, value.installation), quotas, now)
        }
    }
}

internal fun ownerDeleteTestIngress(
    policy: ComplaintCapacityPolicyV1 = ownerCreateTestCapacityPolicy(),
    members: Int = 1024,
    prune: Int = 128,
    clock: ComplaintAdmissionNanoClock = SystemComplaintAdmissionNanoClock,
): ComplaintIngressAdmission = ComplaintIngressAdmission(
    ClientIpResolver(KiraSecurityProperties()), admissionTestPolicy(enrollment = ComplaintEnrollmentAdmissionPolicy.Bounded(policy, 9)),
    admissionTestKeys(), clock,
    ComplaintOwnerCreateAdmissionPolicy.Bounded(policy, 12, members, prune),
    editPolicy = ComplaintOwnerEditAdmissionPolicy.Bounded(policy, members, prune),
    ownerDeletePolicy = ComplaintOwnerDeleteAdmissionPolicy.Bounded(policy, members, prune),
)
