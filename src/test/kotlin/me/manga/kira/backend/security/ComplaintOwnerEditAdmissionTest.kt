package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
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
import java.util.concurrent.locks.LockSupport

/** Lower counter/capability evidence only; sixty admissions are not sixty SQL edit/accounting successes. */
class ComplaintOwnerEditAdmissionTest {
    @Test
    fun `sixty edit attempts share one actor hourly family distinct from ten creations and never repay duplicates`() {
        val f = Counters()
        val actor = admissionTestActor(1)
        val first = editTuple(actor)
        f.edit(first, 0)
        repeat(20) { f.edit(first, 1) }
        repeat(59) { f.edit(editTuple(actor), 1) }
        val denied = editTuple(actor)
        assertEquals(3600L, admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { f.edit(denied, 1) }.retryAfterSeconds)
        repeat(10) { f.create(ComplaintOwnerOperationTuple(actor, UUID.randomUUID(), UUID.randomUUID(), ByteArray(32)), 1) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) {
            f.create(ComplaintOwnerOperationTuple(actor, UUID.randomUUID(), UUID.randomUUID(), ByteArray(32)), 1)
        }
        f.edit(first, 1)
        f.edit(editTuple(admissionTestActor(2)), 1)
        val hour = ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS + 1
        f.edit(denied, hour) // Refusal installed no dedup member: this is the first paid slot of the new hour.
        repeat(59) { f.edit(editTuple(actor), hour) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { f.edit(editTuple(actor), hour) }
    }

    @Test
    fun `edit pseudonyms bind every tuple dimension and fix the future single delete shared rate family`() {
        val keys = ComplaintAdmissionKeyRing(admissionTestKeys()).keys()
        val actor = admissionTestActor(1)
        val original = editTuple(actor)
        val changed = listOf(
            editTuple(admissionTestActor(2), original.key, original.targetId, original.fingerprintBytes()),
            editTuple(ScopedInstallationId(actor.id, ComplaintDataScope.of(UUID.randomUUID())), original.key, original.targetId, original.fingerprintBytes()),
            editTuple(actor, UUID.randomUUID(), original.targetId, original.fingerprintBytes()),
            editTuple(actor, original.key, UUID.randomUUID(), original.fingerprintBytes()),
            editTuple(actor, original.key, original.targetId, ByteArray(32) { 9 }),
        )
        val members = (listOf(original) + changed).map { ComplaintAdmissionPseudonyms.ownerEditMember(keys, it) }
        assertEquals(members.size, members.toSet().size)
        assertEquals(members.first(), ComplaintAdmissionPseudonyms.ownerEditMember(keys, editTuple(actor, original.key, original.targetId, original.fingerprintBytes())))
        val rate = ComplaintAdmissionPseudonyms.ownerEditDeleteActor(keys, actor)
        assertNotEquals(rate, ComplaintAdmissionPseudonyms.ownerCreateActor(keys, actor))
        assertNotEquals(rate, ComplaintAdmissionPseudonyms.ownerDeleteAllActor(keys, actor))
        val parts = listOf(
            "kira-complaint-admission-v1".toByteArray(), "ACTOR".toByteArray(), "INSTALLATION".toByteArray(),
            ByteBuffer.allocate(16).putLong(actor.id.mostSignificantBits).putLong(actor.id.leastSignificantBits).array(),
            ByteBuffer.allocate(16).putLong(actor.scope.id.mostSignificantBits).putLong(actor.scope.id.leastSignificantBits).array(),
            "OWNER_EDIT_DELETE".toByteArray(),
        )
        val frame = ByteBuffer.allocate(parts.sumOf { 4 + it.size }).apply { parts.forEach { putInt(it.size).put(it) } }.array()
        assertEquals(keys.map { ComplaintAdmissionBucketKey(it.id, it.digest(frame)) }, rate)
        assertTrue(members.flatten().all { it.toString() == "ComplaintAdmissionBucketKey(redacted)" })
    }

    @Test
    fun `edit and creation share finite members bounded pruning and unextended twenty five hour expiry through rotation`() {
        val f = Counters(limit = 2, prune = 1)
        val first = editTuple(admissionTestActor(1))
        val creation = ComplaintOwnerOperationTuple(first.installation, UUID.randomUUID(), UUID.randomUUID(), ByteArray(32))
        f.edit(first, 0)
        f.create(creation, 0)
        val expiry = ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS
        f.edit(first, expiry - 1)
        f.create(creation, expiry - 1)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.edit(editTuple(first.installation), expiry - 1) }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.edit(editTuple(first.installation), expiry) }
        f.edit(first, expiry)
        f.ring.rotate(admissionTestKey(2), expiry + 1)
        f.edit(first, expiry + 2) // Old-generation duplicate must not publish another member.
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.edit(editTuple(first.installation), expiry + 2) }
        admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { f.ring.retirePrevious(2 * expiry) }
        val now = 2 * expiry + 1
        val retired = f.ring.retirePrevious(now)
        f.members.removeGeneration(retired)
        f.quotas.removeGeneration(retired)
        f.edit(first, now)
        f.edit(editTuple(first.installation), now)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.edit(editTuple(first.installation), now) }

        val ingress = ownerEditTestIngress(members = 2, prune = 1)
        fun ingressEdit(value: ComplaintOwnerEditTuple) = ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerEdit(context)
            ingress.admitOwnerEdit(context, value)
        }
        ingressEdit(first)
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerCreate(context)
            ingress.admitOwnerCreate(context, creation)
        }
        ingressEdit(first)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { ingressEdit(editTuple(first.installation)) }
    }

    @Test
    fun `edit handoff refuses forged cross operation thread context phase tuple reuse and mismatched P`() {
        val ingress = ownerEditTestIngress()
        val tuple = editTuple(admissionTestActor(1))
        val phase = Any()
        lateinit var retained: ComplaintAdmittedOwnerEdit
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindOwnerEdit(object : ComplaintAdmittedOwnerEdit {}, phase) }
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerEdit(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ingress.admitOwnerEdit(ComplaintIngressContext(), tuple) }
            retained = ingress.admitOwnerEdit(context, tuple)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ingress.admitOwnerEdit(context, tuple) }
            val thread = Executors.newSingleThreadExecutor()
            try {
                thread.submit<Unit> {
                    admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindOwnerEdit(retained, phase) }
                }.get()
            } finally {
                thread.shutdownNow()
            }
            ComplaintIngressAdmission.bindOwnerEdit(retained, phase)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindOwnerEdit(retained, phase) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerEdit(retained, Any(), tuple) }
            val copy = editTuple(tuple.installation, tuple.key, tuple.targetId, tuple.fingerprintBytes())
            assertTrue(tuple.matches(copy))
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerEdit(retained, phase, copy) }
            ComplaintIngressAdmission.claimOwnerEdit(retained, phase, tuple)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerEdit(retained, phase, tuple) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkOwnerEditWrite(retained, phase) }
            val policy = ownerCreateTestCapacityPolicy()
            val mismatch = ComplaintCapacityPolicyV1.of(policy.hardLimit, policy.creationLimit.with(ComplaintCapacityCounter.RESOURCE_IDS, 8_000_000), 100)
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { ComplaintIngressAdmission.checkOwnerEditBounds(retained, phase, ledger(mismatch)) }
            ComplaintIngressAdmission.checkOwnerEditBounds(retained, phase, ledger(policy))
            ComplaintIngressAdmission.checkOwnerEditWrite(retained, phase)
        }
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkOwnerEditWrite(retained, phase) }
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerCreate(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ingress.admitOwnerEdit(context, tuple) }
        }
        val disabled = ownerCreateTestIngress()
        disabled.withIngress(historyTestRequest()) { context ->
            disabled.startOwnerEdit(context)
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { disabled.admitOwnerEdit(context, tuple) }
        }
        val fakeClock = ownerEditTestIngress(clock = MutableAdmissionTestClock())
        fakeClock.withIngress(historyTestRequest()) { context ->
            fakeClock.startOwnerEdit(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { fakeClock.admitOwnerEdit(context, tuple) }
        }
        assertThrows<IllegalArgumentException> { ownerEditTestIngress(members = 1) }
        assertFalse(ComplaintOwnerEditAdmissionPolicy.Bounded(ownerCreateTestCapacityPolicy(), 2, 1).matchesLocked(ledger(ownerDeleteAllTestCapacityPolicy())))
    }

    @Test
    fun `edit binding cannot renew the original genuine five second monotonic deadline`() {
        val ingress = ownerEditTestIngress()
        val tuple = editTuple(admissionTestActor(1))
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerEdit(context)
            val admitted = ingress.admitOwnerEdit(context, tuple)
            val started = System.nanoTime()
            val phase = Any()
            ComplaintIngressAdmission.bindOwnerEdit(admitted, phase)
            while (System.nanoTime() - started < ComplaintAdmissionPolicy.ADMISSION_LIFETIME_NANOS) LockSupport.parkNanos(1_000_000)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerEdit(admitted, phase, tuple) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindOwnerEdit(admitted, Any()) }
        }
    }

    @Test
    fun `edit status uses the same one hundred twenty owner reads rather than another allowance`() {
        val ingress = ownerEditTestIngress(clock = MutableAdmissionTestClock())
        val actor = admissionTestActor(1)
        repeat(120) { index ->
            ingress.withIngress(historyTestRequest()) { context ->
                val identity = Any()
                if (index % 2 == 0) {
                    ingress.startOwnerHistory(context)
                    ingress.chargeOwnerHistory(context, actor, identity)
                    ingress.consumeOwnerHistory(context, identity)
                } else {
                    ingress.startOwnerStatus(context)
                    ingress.chargeOwnerStatus(context, actor, identity)
                    ingress.consumeOwnerStatus(context, identity)
                }
            }
        }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) {
            ingress.withIngress(historyTestRequest(ip = "192.0.2.2")) { context ->
                ingress.startOwnerStatus(context)
                ingress.chargeOwnerStatus(context, actor, Any())
            }
        }
    }

    private fun editTuple(actor: ScopedInstallationId, key: UUID = UUID.randomUUID(), target: UUID = UUID.randomUUID(), digest: ByteArray = ByteArray(32) { 43 }) =
        ComplaintOwnerEditTuple(actor, key, target, digest)

    private fun ledger(policy: ComplaintCapacityPolicyV1) = ComplaintCapacityLedger(
        ComplaintCapacityConfiguration.of(policy.digestBytes(), false),
        ComplaintCapacityBalance(policy.hardLimit, policy.creationLimit, policy.hardLimit),
    )

    private class Counters(limit: Int = 1024, prune: Int = 128) {
        private val policy = ownerCreateTestCapacityPolicy()
        val ring = ComplaintAdmissionKeyRing(admissionTestKeys())
        val members = ComplaintMutationAdmissionMembers(limit, prune)
        val quotas = ComplaintAdmissionWindowStore(4096, 131072, 128, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS)
        private val edits = ComplaintOwnerEditAdmissionStore(ComplaintOwnerEditAdmissionPolicy.Bounded(policy, limit, prune), members)
        private val creates = ComplaintOwnerCreateAdmissionStore(ComplaintOwnerCreateAdmissionPolicy.Bounded(policy, 12, limit, prune), members)

        fun edit(tuple: ComplaintOwnerEditTuple, now: Long) {
            val keys = ring.keys()
            edits.admit(ComplaintAdmissionPseudonyms.ownerEditMember(keys, tuple), ComplaintAdmissionPseudonyms.ownerEditDeleteActor(keys, tuple.installation), quotas, now)
        }

        fun create(tuple: ComplaintOwnerOperationTuple, now: Long) {
            val keys = ring.keys()
            creates.admit(
                ComplaintAdmissionPseudonyms.ownerCreateMember(keys, tuple), ComplaintAdmissionPseudonyms.ownerCreateActor(keys, tuple.installation),
                ComplaintAdmissionPseudonyms.ownerCreateGlobal(keys), quotas, now,
            )
        }
    }
}

/** Same bounded ingress/counter implementation and synthetic P as existing fixtures, with explicit optional edit policy. */
internal fun ownerEditTestIngress(
    policy: ComplaintCapacityPolicyV1 = ownerCreateTestCapacityPolicy(),
    clock: ComplaintAdmissionNanoClock = SystemComplaintAdmissionNanoClock,
    members: Int = 1024,
    prune: Int = 128,
    edits: ComplaintOwnerEditAdmissionPolicy = ComplaintOwnerEditAdmissionPolicy.Bounded(policy, members, prune),
): ComplaintIngressAdmission = ComplaintIngressAdmission(
    ClientIpResolver(KiraSecurityProperties()),
    admissionTestPolicy(enrollment = ComplaintEnrollmentAdmissionPolicy.Bounded(policy, 9)),
    admissionTestKeys(), clock,
    ComplaintOwnerCreateAdmissionPolicy.Bounded(policy, 12, members, prune),
    editPolicy = edits,
)
