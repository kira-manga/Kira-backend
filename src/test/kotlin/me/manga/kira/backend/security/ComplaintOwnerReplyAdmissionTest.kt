package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreationOperation
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationTuple
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.locks.LockSupport

/** The existing counter/ingress mechanism, not SQL authentication, receipt replay or an accepted write grant simulator. */
class ComplaintOwnerReplyAdmissionTest {
    @Test
    fun `reports and replies share ten actor and one global hourly allowance without repaying exact duplicates`() {
        val ingress = ownerCreateTestIngress()
        val actor = admissionTestActor(1)
        val first = listOf(create(actor), reply(actor))
        first.forEach { admit(ingress, it) }
        repeat(20) { admit(ingress, first[it % 2]) }
        repeat(8) { admit(ingress, if (it % 2 == 0) create(actor) else reply(actor)) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { admit(ingress, reply(actor)) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { admit(ingress, create(actor)) }
        val other = admissionTestActor(2)
        admit(ingress, reply(other))
        admit(ingress, create(other))
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { admit(ingress, reply(other)) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { admit(ingress, create(other)) }
        first.forEach { admit(ingress, it) }
    }

    @Test
    fun `reply dedup member distinguishes operation ordered targets scope actor key and fingerprint`() {
        val keys = ComplaintAdmissionKeyRing(admissionTestKeys()).keys()
        val actor = admissionTestActor(1)
        val original = reply(actor)
        val targets = original.targetIds()
        val fingerprint = original.fingerprintBytes()
        val changed = listOf(
            create(actor, original.key, original.targetId, fingerprint),
            reply(actor, original.key, targets.reversed(), fingerprint),
            reply(actor, original.key, listOf(UUID.randomUUID(), targets.last()), fingerprint),
            reply(actor, original.key, listOf(targets.first(), UUID.randomUUID()), fingerprint),
            reply(ScopedInstallationId(actor.id, ComplaintDataScope.of(UUID.randomUUID())), original.key, targets, fingerprint),
            reply(admissionTestActor(2), original.key, targets, fingerprint),
            reply(actor, UUID.randomUUID(), targets, fingerprint),
            reply(actor, original.key, targets, ByteArray(32) { 44 }),
        )
        val members = (listOf(original) + changed).map { ComplaintAdmissionPseudonyms.ownerCreateMember(keys, it) }
        assertEquals(members.size, members.toSet().size)
        assertTrue(members.all { it.size == 1 })
        assertEquals(members.first(), ComplaintAdmissionPseudonyms.ownerCreateMember(keys, reply(actor, original.key, targets, fingerprint)))
        val legacy = create(actor, original.key, original.targetId, fingerprint)
        val explicit = ComplaintOwnerOperationTuple(actor, original.key, ComplaintOwnerCreationOperation.OWNER_CREATE, listOf(original.targetId), fingerprint)
        assertEquals(ComplaintAdmissionPseudonyms.ownerCreateMember(keys, legacy), ComplaintAdmissionPseudonyms.ownerCreateMember(keys, explicit))
        assertTrue(members.flatten().all { it.toString() == "ComplaintAdmissionBucketKey(redacted)" })
        assertFalse(members.toString().contains(actor.id.toString()))
    }

    @Test
    fun `reply handoff binds exact operation context tuple phase and one use without authorizing create wrappers`() {
        val ingress = ownerCreateTestIngress()
        val tuple = reply(admissionTestActor(1))
        val phase = Any()
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) {
            ComplaintIngressAdmission.bindOwnerCreate(object : ComplaintAdmittedOwnerCreate {}, phase, ComplaintOwnerCreationOperation.OWNER_REPLY)
        }
        lateinit var retained: ComplaintAdmittedOwnerCreate
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerReply(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ingress.admitOwnerReply(ComplaintIngressContext(), tuple) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ingress.admitOwnerCreate(context, tuple) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ingress.admitOwnerReply(context, create(tuple.installation)) }
            retained = ingress.admitOwnerReply(context, tuple)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ingress.admitOwnerReply(context, tuple) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindOwnerCreate(retained, phase) }
            ComplaintIngressAdmission.bindOwnerCreate(retained, phase, ComplaintOwnerCreationOperation.OWNER_REPLY)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) {
                ComplaintIngressAdmission.bindOwnerCreate(retained, phase, ComplaintOwnerCreationOperation.OWNER_REPLY)
            }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerCreate(retained, Any(), tuple) }
            val equalButNotIdentical = reply(tuple.installation, tuple.key, tuple.targetIds(), tuple.fingerprintBytes())
            assertTrue(tuple.matches(equalButNotIdentical))
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) {
                ComplaintIngressAdmission.claimOwnerCreate(retained, phase, equalButNotIdentical)
            }
            val reordered = reply(tuple.installation, tuple.key, tuple.targetIds().reversed(), tuple.fingerprintBytes())
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) {
                ComplaintIngressAdmission.claimOwnerCreate(retained, phase, reordered)
            }
            ComplaintIngressAdmission.claimOwnerCreate(retained, phase, tuple)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerCreate(retained, phase, tuple) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkOwnerCreateWrite(retained, phase) }
            ComplaintIngressAdmission.checkOwnerCreateBounds(retained, phase, ledger())
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkOwnerCreateWrite(retained, Any()) }
            ComplaintIngressAdmission.checkOwnerCreateWrite(retained, phase)
        }
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerReply(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkOwnerCreateWrite(retained, phase) }
        }
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerCreate(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ingress.admitOwnerReply(context, tuple) }
        }
        val fakeClock = ownerCreateTestIngress(clock = MutableAdmissionTestClock())
        fakeClock.withIngress(historyTestRequest()) { context ->
            fakeClock.startOwnerReply(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { fakeClock.admitOwnerReply(context, tuple) }
        }
    }

    @Test
    fun `reply uses the original real five second admission deadline and binding cannot renew it`() {
        val ingress = ownerCreateTestIngress()
        val tuple = reply(admissionTestActor(1))
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerReply(context)
            val admitted = ingress.admitOwnerReply(context, tuple)
            val started = System.nanoTime()
            val phase = Any()
            ComplaintIngressAdmission.bindOwnerCreate(admitted, phase, ComplaintOwnerCreationOperation.OWNER_REPLY)
            while (System.nanoTime() - started < ComplaintAdmissionPolicy.ADMISSION_LIFETIME_NANOS) LockSupport.parkNanos(1_000_000)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerCreate(admitted, phase, tuple) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) {
                ComplaintIngressAdmission.bindOwnerCreate(admitted, Any(), ComplaintOwnerCreationOperation.OWNER_REPLY)
            }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkOwnerCreateWrite(admitted, phase) }
        }
    }

    private fun admit(ingress: ComplaintIngressAdmission, tuple: ComplaintOwnerOperationTuple) {
        ingress.withIngress(historyTestRequest()) { context ->
            when (tuple.operation) {
                ComplaintOwnerCreationOperation.OWNER_CREATE -> {
                    ingress.startOwnerCreate(context)
                    ingress.admitOwnerCreate(context, tuple)
                }

                ComplaintOwnerCreationOperation.OWNER_REPLY -> {
                    ingress.startOwnerReply(context)
                    ingress.admitOwnerReply(context, tuple)
                }
            }
        }
    }

    private fun create(
        actor: ScopedInstallationId,
        key: UUID = UUID.randomUUID(),
        id: UUID = UUID.randomUUID(),
        fingerprint: ByteArray = ByteArray(32) { 43 },
    ): ComplaintOwnerOperationTuple = ComplaintOwnerOperationTuple(actor, key, id, fingerprint)

    private fun reply(
        actor: ScopedInstallationId,
        key: UUID = UUID.randomUUID(),
        targets: List<UUID> = listOf(UUID.randomUUID(), UUID.randomUUID()),
        fingerprint: ByteArray = ByteArray(32) { 43 },
    ): ComplaintOwnerOperationTuple = ComplaintOwnerOperationTuple(actor, key, ComplaintOwnerCreationOperation.OWNER_REPLY, targets, fingerprint)

    private fun ledger(): ComplaintCapacityLedger {
        val policy = ownerCreateTestCapacityPolicy()
        return ComplaintCapacityLedger(
            ComplaintCapacityConfiguration.of(policy.digestBytes(), false),
            ComplaintCapacityBalance(policy.hardLimit, policy.creationLimit, policy.hardLimit),
        )
    }
}
