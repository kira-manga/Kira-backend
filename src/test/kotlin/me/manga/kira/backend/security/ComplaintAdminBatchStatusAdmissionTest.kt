package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusRequest
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusTarget
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusTuple
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusPrecondition
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusOperation
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusTuple
import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** Counter/custody primitives, not authentication or mutation evidence. No caller-selected weight or new global store. */
class ComplaintAdminBatchStatusAdmissionTest {
    private val actor = UUID.randomUUID()
    private val scope = ComplaintDataScope.of(UUID.randomUUID())
    private val policy = ownerCreateTestCapacityPolicy()

    @Test
    fun oneMemberPerBatchUsesLowerable60HourLimitAndCurrentPreviousDedupWithoutAWeight() {
        assertEquals(60, ComplaintAdminBatchStatusAdmissionPolicy.Bounded(policy, 2, 1).perHour)
        for (limit in listOf(-1, 0, 61, Int.MAX_VALUE)) assertThrows<IllegalArgumentException> {
            ComplaintAdminBatchStatusAdmissionPolicy.Bounded(policy, 2, 1, limit)
        }
        val default = Counters()
        repeat(60) { default.admit(tuple(), 0) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { default.admit(tuple(), 0) }
        val counters = Counters(perHour = 1)
        val many = tuple(ids = List(50) { UUID.randomUUID() })
        counters.admit(many, 0)
        repeat(5) { counters.admit(many, 1) }
        assertEquals(3600L, admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { counters.admit(tuple(), 1) }.retryAfterSeconds)
        counters.ring.rotate(admissionTestKey(3), 2)
        counters.admit(many, 3)
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { counters.admit(tuple(), 3) }
        val nextHour = ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS
        counters.admit(tuple(), nextHour + 1)
        counters.admit(tuple(selectedActor = UUID.randomUUID()), nextHour + 1)
        counters.admit(tuple(selectedScope = ComplaintDataScope.of(UUID.randomUUID())), nextHour + 1)

        val keys = counters.ring.keys()
        val batchActor = ComplaintAdmissionPseudonyms.adminBatchStatusActor(keys, actor, scope)
        assertNotEquals(batchActor, ComplaintAdmissionPseudonyms.adminStatusActor(keys, actor, scope))
        assertNotEquals(batchActor, ComplaintAdmissionPseudonyms.adminContentActor(keys, actor, scope))
        assertNotEquals(batchActor, ComplaintAdmissionPseudonyms.adminDeleteActor(keys, actor, scope))
        val variants = listOf(
            many, tuple(key = many.key, ids = many.targetIds(), version = 2),
            tuple(key = many.key, ids = many.targetIds(), status = ComplaintStatus.PLANNED), tuple(ids = many.targetIds()),
            tuple(key = many.key, ids = many.targetIds().dropLast(1)), tuple(key = many.key, ids = many.targetIds(), selectedScope = ComplaintDataScope.of(UUID.randomUUID())),
            tuple(key = many.key, ids = many.targetIds(), selectedActor = UUID.randomUUID()),
        ).map { ComplaintAdmissionPseudonyms.adminBatchStatusMember(keys, it) }
        assertEquals(variants.size, variants.toSet().size)
        assertEquals(variants.first(), ComplaintAdmissionPseudonyms.adminBatchStatusMember(keys, tuple(key = many.key, ids = many.targetIds().reversed())))
    }

    @Test
    fun batchAndSingleStatusShareFiniteGlobalMemberEventAndBucketCapacitiesButNotActorAllowance() {
        val many = tuple(ids = List(50) { UUID.randomUUID() })
        val single = ComplaintAdminStatusTuple(actor, scope, UUID.randomUUID(), UUID.randomUUID(), ComplaintAdminStatusOperation.ADMIN_STATUS, ByteArray(32))
        val guard = adminBatchStatusTestIngress(perHour = 1, members = 2, prune = 1)
        admit(guard, many)
        guard.withIngress(historyTestRequest()) { context -> guard.startAdminStatus(context); guard.admitAdminStatus(context, single) }
        admit(guard, many)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { admit(guard, tuple(selectedActor = UUID.randomUUID())) }

        val events = adminBatchStatusTestIngress(policy = admissionTestPolicy(semanticBuckets = 2, semanticEvents = 3))
        admit(events, many)
        events.withIngress(historyTestRequest()) { context -> events.startAdminStatus(context); events.admitAdminStatus(context, single) }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { admit(events, tuple(selectedActor = UUID.randomUUID())) }
        admit(events, tuple())
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { admit(events, tuple()) }
        assertThrows<IllegalArgumentException> {
            adminBatchStatusTestIngress(members = 2, batch = ComplaintAdminBatchStatusAdmissionPolicy.Bounded(policy, 3, 128))
        }
        val disabled = adminBatchStatusTestIngress(batch = ComplaintAdminBatchStatusAdmissionPolicy.Disabled)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { admit(disabled, many) }
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { admit(adminBatchStatusTestIngress(clock = MutableAdmissionTestClock()), many) }
    }

    @Test
    fun onlyOriginalLiveThreadContextTuplePhaseAndLockedPolicyCanAdvanceThePrivateBatchHandoff() {
        val guard = adminBatchStatusTestIngress()
        val original = tuple()
        val phase = Any()
        lateinit var retained: ComplaintAdmittedAdminBatchStatus
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) {
            ComplaintIngressAdmission.bindAdminBatchStatus(object : ComplaintAdmittedAdminBatchStatus {}, phase)
        }
        guard.withIngress(historyTestRequest()) { context ->
            guard.startAdminBatchStatus(context)
            val foreignResource = Any()
            TransactionSynchronizationManager.bindResource(foreignResource, Any())
            try { assertThrows<PersistencePhaseException> { guard.admitAdminBatchStatus(context, original) } } finally {
                TransactionSynchronizationManager.unbindResource(foreignResource)
            }
            retained = guard.admitAdminBatchStatus(context, original)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.admitAdminBatchStatus(context, original) }
            OwnedCallerTestScope().use { callers ->
                callers.launch { admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindAdminBatchStatus(retained, phase) } }.value()
            }
            ComplaintIngressAdmission.bindAdminBatchStatus(retained, phase)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindAdminBatchStatus(retained, phase) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkAdminBatchStatusClaim(retained, phase) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimAdminBatchStatus(retained, Any(), original) }
            val copy = ComplaintAdminBatchStatusTuple(actor, scope, original.key, original.targetIds(), original.fingerprintBytes())
            assertTrue(original.matches(copy))
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimAdminBatchStatus(retained, phase, copy) }
            ComplaintIngressAdmission.claimAdminBatchStatus(retained, phase, original)
            ComplaintIngressAdmission.checkAdminBatchStatusClaim(retained, phase)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkAdminBatchStatusWrite(retained, phase) }
            val different = ComplaintCapacityPolicyV1.of(policy.hardLimit, policy.creationLimit.with(ComplaintCapacityCounter.RESOURCE_IDS, 8_000_000), 100)
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { ComplaintIngressAdmission.checkAdminBatchStatusBounds(retained, phase, ledger(different)) }
            ComplaintIngressAdmission.checkAdminBatchStatusBounds(retained, phase, ledger(policy))
            ComplaintIngressAdmission.checkAdminBatchStatusWrite(retained, phase)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimAdminBatchStatus(retained, phase, original) }
        }
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkAdminBatchStatusWrite(retained, phase) }
        guard.withIngress(historyTestRequest()) { context ->
            guard.startAdminStatus(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.admitAdminBatchStatus(context, original) }
        }
    }

    private fun tuple(
        ids: List<UUID> = listOf(UUID.randomUUID()), key: UUID = UUID.randomUUID(), version: Long = 1,
        status: ComplaintStatus = ComplaintStatus.RESOLVED, selectedActor: UUID = actor, selectedScope: ComplaintDataScope = scope,
    ): ComplaintAdminBatchStatusTuple {
        val request = ComplaintAdminBatchStatusRequest.normalize(ComplaintAdminBatchStatusInput(selectedScope, key, status, ids.map {
            ComplaintAdminBatchStatusTarget(it, ComplaintAdminStatusPrecondition.parse(it, "\"complaint-$it-v$version\""))
        }))
        return ComplaintAdminBatchStatusTuple(selectedActor, selectedScope, key, request.targets.map { it.id }, ComplaintAdminBatchStatusFingerprint.of(request).bytes())
    }

    private fun admit(guard: ComplaintIngressAdmission, tuple: ComplaintAdminBatchStatusTuple) = guard.withIngress(historyTestRequest()) { context ->
        guard.startAdminBatchStatus(context)
        guard.admitAdminBatchStatus(context, tuple)
    }

    private fun ledger(policy: ComplaintCapacityPolicyV1): ComplaintCapacityLedger = ComplaintCapacityLedger(
        ComplaintCapacityConfiguration.of(policy.digestBytes(), false), ComplaintCapacityBalance(policy.hardLimit, policy.creationLimit, policy.hardLimit),
    )

    private class Counters(perHour: Int = 60) {
        val ring = ComplaintAdmissionKeyRing(admissionTestKeys())
        private val members = ComplaintMutationAdmissionMembers(1024, 128)
        private val quotas = ComplaintAdmissionWindowStore(4096, 131072, 128, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS)
        private val batch = ComplaintAdminBatchStatusAdmissionStore(ComplaintAdminBatchStatusAdmissionPolicy.Bounded(ownerCreateTestCapacityPolicy(), 1024, 128, perHour), members)

        fun admit(tuple: ComplaintAdminBatchStatusTuple, now: Long) {
            val keys = ring.keys()
            batch.admit(ComplaintAdmissionPseudonyms.adminBatchStatusMember(keys, tuple), ComplaintAdmissionPseudonyms.adminBatchStatusActor(keys, tuple.actor, tuple.scope), quotas, now)
        }
    }
}
