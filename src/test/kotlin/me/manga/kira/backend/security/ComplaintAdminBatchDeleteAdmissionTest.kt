package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.complaint.domain.AdminBatchDeleteCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeleteFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeleteTarget
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeletePrecondition
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRequest
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteTuple
import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Bounded primitive/custody checks only; no supplied admission is authentication or journal work. */
class ComplaintAdminBatchDeleteAdmissionTest {
    private val actor = UUID.randomUUID()
    private val scope = ComplaintDataScope.of(UUID.randomUUID())
    private val policy = ownerCreateTestCapacityPolicy()

    @Test
    fun oneBatchMemberHasDistinctLowerable60HourlyQuotaWithCurrentPreviousDedupAndNoTargetWeight() {
        assertEquals(60, ComplaintAdminBatchDeleteAdmissionPolicy.Bounded(policy, 2, 1).perHour)
        for (n in listOf(-1, 0, 61)) assertThrows<IllegalArgumentException> { ComplaintAdminBatchDeleteAdmissionPolicy.Bounded(policy, 2, 1, n) }
        val default = Counters()
        repeat(60) { default.admit(tuple(), 0) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { default.admit(tuple(), 0) }
        val limited = Counters(1)
        val many = tuple(List(50) { UUID.randomUUID() })
        limited.admit(many, 0); repeat(3) { limited.admit(many, 1) }
        assertEquals(3600L, admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { limited.admit(tuple(), 1) }.retryAfterSeconds)
        limited.ring.rotate(admissionTestKey(3), 2); limited.admit(many, 3)
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { limited.admit(tuple(), 3) }
        limited.admit(tuple(), ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS + 1)
        val keys = limited.ring.keys()
        val bucket = ComplaintAdmissionPseudonyms.adminBatchDeleteActor(keys, actor, scope)
        assertNotEquals(bucket, ComplaintAdmissionPseudonyms.adminDeleteActor(keys, actor, scope))
        assertNotEquals(bucket, ComplaintAdmissionPseudonyms.adminBatchStatusActor(keys, actor, scope))
        val member = ComplaintAdmissionPseudonyms.adminBatchDeleteMember(keys, many)
        assertEquals(member, ComplaintAdmissionPseudonyms.adminBatchDeleteMember(keys, tuple(many.targetIds().reversed(), many.key)))
        assertNotEquals(member, ComplaintAdmissionPseudonyms.adminBatchDeleteMember(keys, tuple(many.targetIds(), many.key, version = 2)))
    }

    @Test
    fun batchUsesSharedFiniteMembersAndPrivateOriginalTupleAndPhaseRatherThanAScalarOrForgedHandoff() {
        val guard = adminBatchDeleteTestIngress(members = 2, prune = 1)
        val original = tuple()
        guard.withIngress(historyTestRequest()) { context -> guard.startAdminBatchDelete(context); guard.admitAdminBatchDelete(context, original) }
        val single = ComplaintAdminDeleteTuple(actor, scope, UUID.randomUUID(), UUID.randomUUID(), ByteArray(32))
        guard.withIngress(historyTestRequest()) { context -> guard.startAdminDelete(context); guard.admitAdminDelete(context, single) }
        guard.withIngress(historyTestRequest()) { context -> guard.startAdminBatchDelete(context); guard.admitAdminBatchDelete(context, original) }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) {
            guard.withIngress(historyTestRequest()) { context -> guard.startAdminBatchDelete(context); guard.admitAdminBatchDelete(context, tuple()) }
        }
        assertThrows<IllegalArgumentException> {
            adminBatchDeleteTestIngress(members = 2, batch = ComplaintAdminBatchDeleteAdmissionPolicy.Bounded(policy, 3, 128))
        }
        val fresh = adminBatchDeleteTestIngress()
        val phase = Any()
        lateinit var retained: ComplaintAdmittedAdminBatchDelete
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) {
            ComplaintIngressAdmission.bindAdminErasure(object : ComplaintAdmittedAdminBatchDelete {}, phase)
        }
        fresh.withIngress(historyTestRequest()) { context ->
            fresh.startAdminBatchDelete(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { fresh.admitAdminBatchDelete(context, single) }
            retained = fresh.admitAdminBatchDelete(context, original)
            ComplaintIngressAdmission.bindAdminErasure(retained, phase)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimAdminErasure(retained, Any(), original) }
            val copy = ComplaintAdminDeleteTuple.batch(actor, scope, original.key, original.targetIds(), original.fingerprintBytes())
            assertTrue(original.matches(copy))
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimAdminErasure(retained, phase, copy) }
            ComplaintIngressAdmission.claimAdminErasure(retained, phase, original)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkAdminErasureWrite(retained, phase) }
            ComplaintIngressAdmission.checkAdminErasureBounds(retained, phase, ComplaintCapacityLedger(
                ComplaintCapacityConfiguration.of(policy.digestBytes(), false), ComplaintCapacityBalance(policy.hardLimit, policy.creationLimit, policy.hardLimit)))
            ComplaintIngressAdmission.checkAdminErasureWrite(retained, phase)
        }
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkAdminErasureWrite(retained, phase) }
    }

    @Test
    fun exactFiftyTargetFiftyOwnerPromiseAndForwardMigrationChangeOnlyTheRejectedGrantPredicate() {
        val c = OwnerDeleteLiteralCharges
        assertEquals(c.receipt + c.publication + c.reservation + c.audit.scaled(50), AdminBatchDeleteCapacityCharges.authorization(50))
        assertEquals(c.installation.scaled(50) + c.resource.scaled(50) + c.audit.scaled(54) + c.appliedOnly.scaled(4),
            AdminBatchDeleteCapacityCharges.recovery(50, 50))
        assertEquals(c.installation.scaled(2) + c.resource.scaled(3) + c.audit.scaled(7) + c.appliedOnly.scaled(4),
            AdminBatchDeleteCapacityCharges.recovery(2, 3))
        for ((owners, targets) in listOf(0 to 1, 2 to 1, 1 to 0, 50 to 51))
            assertThrows<IllegalArgumentException> { AdminBatchDeleteCapacityCharges.recovery(owners, targets) }
        fun migration(name: String): String = checkNotNull(javaClass.classLoader.getResourceAsStream("db/migration/$name")).use { it.readBytes().decodeToString() }
            .lineSequence().filterNot { it.startsWith("--") }.joinToString("\n")
        val old = migration("V24__admin_batch_status_grant_association.sql")
        val current = migration("V25__admin_batch_delete_rejected_grant_association.sql")
        val before = "'ADMIN_DELETE', 'ADMIN_BATCH_STATUS') AND complaint_is_v4(consumed_grant_id)"
        assertEquals(1, old.split(before).size - 1)
        val resultConstraint = old.substringBefore("\n\nALTER TABLE complaint_idempotency_receipts DROP CONSTRAINT chk_complaint_receipt_external;")
        assertTrue(resultConstraint.length < old.length, "V24 also touched the independent external constraint; V25 must leave it intact.")
        assertEquals(resultConstraint.replace(before, "'ADMIN_DELETE', 'ADMIN_BATCH_STATUS', 'ADMIN_BATCH_DELETE') AND complaint_is_v4(consumed_grant_id)"), current.trimEnd())
    }

    private fun tuple(ids: List<UUID> = listOf(UUID.randomUUID()), key: UUID = UUID.randomUUID(), version: Long = 1): ComplaintAdminDeleteTuple {
        val input = ComplaintAdminBatchDeleteInput(scope, key, ids.map { ComplaintAdminBatchDeleteTarget(it, ComplaintAdminDeletePrecondition.parse(it, "\"complaint-$it-v$version\"")) })
        return ComplaintAdminDeleteTuple.batch(actor, scope, key, input.targets.map { it.id }, ComplaintAdminBatchDeleteFingerprint.of(ComplaintAdminDeleteRequest.normalize(input)))
    }
    private class Counters(perHour: Int = 60) {
        val ring = ComplaintAdmissionKeyRing(admissionTestKeys())
        private val quotas = ComplaintAdmissionWindowStore(4096, 131072, 128, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS)
        private val store = ComplaintAdminBatchDeleteAdmissionStore(ComplaintAdminBatchDeleteAdmissionPolicy.Bounded(ownerCreateTestCapacityPolicy(), 1024, 128, perHour))
        fun admit(tuple: ComplaintAdminDeleteTuple, now: Long) {
            val keys = ring.keys()
            store.admit(ComplaintAdmissionPseudonyms.adminBatchDeleteMember(keys, tuple), ComplaintAdmissionPseudonyms.adminBatchDeleteActor(keys, tuple.actor, tuple.scope), quotas, now)
        }
    }
}
