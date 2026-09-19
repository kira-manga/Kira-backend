package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentTuple
import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditTuple
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.locks.LockSupport

/** Counter/custody primitives, not authenticated writes. Deterministic time is confined to the lower bounded stores. */
class ComplaintAdminContentAdmissionTest {
    private val actor = UUID.randomUUID()
    private val scope = ComplaintDataScope.of(UUID.randomUUID())
    private val policy = ownerCreateTestCapacityPolicy()

    @Test
    fun lowerableHourlyAdminScopeDedupAndRotationUseTheSameFiniteOwnerMutationStoresWithoutRefunds() {
        assertEquals(60, ComplaintAdminContentAdmissionPolicy.Bounded(policy, 2, 1).perHour)
        for (limit in listOf(-1, 0, 61, Int.MAX_VALUE)) {
            assertThrows<IllegalArgumentException> { ComplaintAdminContentAdmissionPolicy.Bounded(policy, 2, 1, limit) }
        }
        val defaultLimit = Counters()
        repeat(60) { defaultLimit.admin(tuple(), 0) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { defaultLimit.admin(tuple(), 0) }
        val counters = Counters(perHour = 2)
        val first = tuple()
        counters.admin(first, 0)
        repeat(20) { counters.admin(first, 1) }
        counters.admin(tuple(), 1)
        val denied = tuple()
        assertEquals(3600L, admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { counters.admin(denied, 1) }.retryAfterSeconds)
        assertEquals(3540L, admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { counters.admin(denied, 60_000_000_000) }.retryAfterSeconds)
        counters.admin(tuple(selectedScope = ComplaintDataScope.of(UUID.randomUUID())), 60_000_000_000)
        counters.admin(tuple(selectedActor = UUID.randomUUID()), 60_000_000_000)
        val hour = ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS
        assertEquals(1L, admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { counters.admin(denied, hour - 1) }.retryAfterSeconds)
        counters.admin(denied, hour) // A refused intent installed no dedup member; this pays the first newly free rolling slot.
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { counters.admin(tuple(), hour) }
        counters.admin(tuple(), hour + 1)

        val keys = counters.ring.keys()
        val variants = listOf(
            first, tuple(selectedActor = UUID.randomUUID(), key = first.key, target = first.targetId),
            tuple(selectedScope = ComplaintDataScope.of(UUID.randomUUID()), key = first.key, target = first.targetId),
            tuple(key = UUID.randomUUID(), target = first.targetId), tuple(key = first.key, target = UUID.randomUUID()),
            tuple(key = first.key, target = first.targetId, digest = ByteArray(32) { 9 }),
        ).map { ComplaintAdmissionPseudonyms.adminContentMember(keys, it) }
        assertEquals(variants.size, variants.toSet().size)
        val adminActor = ComplaintAdmissionPseudonyms.adminContentActor(keys, actor, scope)
        assertNotEquals(adminActor, ComplaintAdmissionPseudonyms.adminReadActor(keys, actor, scope))
        assertNotEquals(adminActor, ComplaintAdmissionPseudonyms.ownerEditDeleteActor(keys, ScopedInstallationId(actor, scope)))
        assertTrue(variants.flatten().all { it.toString() == "ComplaintAdmissionBucketKey(redacted)" })

        val rotatedQuota = Counters(perHour = 1)
        rotatedQuota.admin(first, 0)
        rotatedQuota.ring.rotate(admissionTestKey(3), 1)
        rotatedQuota.admin(first, 2)
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { rotatedQuota.admin(tuple(), 2) }

        val bounded = Counters(memberLimit = 2, prune = 1)
        val owner = ComplaintOwnerEditTuple(ScopedInstallationId(actor, scope), UUID.randomUUID(), UUID.randomUUID(), ByteArray(32))
        bounded.admin(first, 0)
        bounded.owner(owner, 0)
        val retention = ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS
        bounded.admin(first, retention - 1)
        bounded.owner(owner, retention - 1)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { bounded.admin(tuple(), retention - 1) }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { bounded.admin(tuple(), retention) }
        bounded.admin(first, retention) // Pruning was bounded; duplicate accesses did not renew either 25-hour member.
        bounded.ring.rotate(admissionTestKey(2), retention + 1)
        bounded.admin(first, retention + 2)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { bounded.admin(tuple(), retention + 2) }
        admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { bounded.ring.retirePrevious(2 * retention) }
        val now = 2 * retention + 1
        val retired = bounded.ring.retirePrevious(now)
        bounded.members.removeGeneration(retired)
        bounded.quotas.removeGeneration(retired)
        bounded.admin(first, now)
        bounded.admin(tuple(), now)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { bounded.admin(tuple(), now) }

        // Actual co-composition, not just two independently constructed stores with similar limits.
        val sharedMembers = adminContentTestIngress(members = 2, prune = 1)
        admit(sharedMembers, first)
        ownerAdmit(sharedMembers, owner)
        admit(sharedMembers, first)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { admit(sharedMembers, tuple()) }
        val sharedEvents = adminContentTestIngress(policy = admissionTestPolicy(semanticBuckets = 2, semanticEvents = 3))
        admit(sharedEvents, first)
        ownerAdmit(sharedEvents, owner)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { admit(sharedEvents, tuple(selectedActor = UUID.randomUUID())) }
        admit(sharedEvents, tuple())
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { admit(sharedEvents, tuple()) }
    }

    @Test
    fun defaultDisabledAndOriginalOneUseHandoffRejectForgeryDifferentOperationThreadPhaseTupleAndDeadlineRenewal() {
        val original = tuple()
        val phase = Any()
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) {
            ComplaintIngressAdmission.bindAdminContent(object : ComplaintAdmittedAdminContent {}, phase)
        }
        val disabled = ComplaintIngressAdmission(ClientIpResolver(KiraSecurityProperties()), admissionTestPolicy(), admissionTestKeys(), SystemComplaintAdmissionNanoClock)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { admit(disabled, original) }
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { admit(adminContentTestIngress(clock = MutableAdmissionTestClock()), original) }
        assertThrows<IllegalArgumentException> { adminContentTestIngress(members = 1) }
        assertThrows<IllegalArgumentException> {
            adminContentTestIngress(members = 2, content = ComplaintAdminContentAdmissionPolicy.Bounded(policy, 3, 128))
        }
        val guard = adminContentTestIngress()
        val foreign = adminContentTestIngress()
        lateinit var retained: ComplaintAdmittedAdminContent
        guard.withIngress(adminContentTestRequest(scope, original.targetId)) { context ->
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { foreign.startAdminContent(context) }
            val resource = Any()
            TransactionSynchronizationManager.bindResource(resource, Any())
            try { assertThrows<PersistencePhaseException> { guard.startAdminContent(context) } } finally {
                TransactionSynchronizationManager.unbindResource(resource)
            }
            guard.startAdminContent(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.admitAdminContent(ComplaintIngressContext(), original) }
            TransactionSynchronizationManager.bindResource(resource, Any())
            try { assertThrows<PersistencePhaseException> { guard.admitAdminContent(context, original) } } finally {
                TransactionSynchronizationManager.unbindResource(resource)
            }
            retained = guard.admitAdminContent(context, original)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.admitAdminContent(context, original) }
            OwnedCallerTestScope().use { callers ->
                callers.launch { admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindAdminContent(retained, phase) } }.value()
            }
            ComplaintIngressAdmission.bindAdminContent(retained, phase)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindAdminContent(retained, phase) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkAdminContentClaim(retained, phase) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimAdminContent(retained, Any(), original) }
            val copied = tuple(key = original.key, target = original.targetId, digest = original.fingerprintBytes())
            assertTrue(original.matches(copied))
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimAdminContent(retained, phase, copied) }
            ComplaintIngressAdmission.claimAdminContent(retained, phase, original)
            ComplaintIngressAdmission.checkAdminContentClaim(retained, phase)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimAdminContent(retained, phase, original) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkAdminContentWrite(retained, phase) }
            val mismatched = ComplaintCapacityPolicyV1.of(policy.hardLimit, policy.creationLimit.with(ComplaintCapacityCounter.RESOURCE_IDS, 8_000_000), 100)
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { ComplaintIngressAdmission.checkAdminContentBounds(retained, phase, ledger(mismatched)) }
            ComplaintIngressAdmission.checkAdminContentBounds(retained, phase, ledger(policy))
            ComplaintIngressAdmission.checkAdminContentWrite(retained, phase)
        }
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkAdminContentWrite(retained, phase) }
        guard.withIngress(historyTestRequest()) { context ->
            guard.startOwnerEdit(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.admitAdminContent(context, original) }
        }
        val timed = adminContentTestIngress(perHour = 1)
        timed.withIngress(adminContentTestRequest(scope, original.targetId)) { context ->
            timed.startAdminContent(context)
            val admitted = timed.admitAdminContent(context, original)
            val issuedAt = System.nanoTime()
            ComplaintIngressAdmission.bindAdminContent(admitted, phase)
            while (System.nanoTime() - issuedAt < ComplaintAdmissionPolicy.ADMISSION_LIFETIME_NANOS) LockSupport.parkNanos(1_000_000)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimAdminContent(admitted, phase, original) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindAdminContent(admitted, Any()) }
        }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { admit(timed, tuple()) }
        admit(timed, original) // Expired/unused handoff does not refund or renew the already charged exact member.
    }

    private fun tuple(
        selectedActor: UUID = actor,
        selectedScope: ComplaintDataScope = scope,
        key: UUID = UUID.randomUUID(),
        target: UUID = UUID.randomUUID(),
        digest: ByteArray = ByteArray(32) { 43 },
    ): ComplaintAdminContentTuple = ComplaintAdminContentTuple(selectedActor, selectedScope, key, target, digest)

    private fun admit(guard: ComplaintIngressAdmission, tuple: ComplaintAdminContentTuple) =
        guard.withIngress(adminContentTestRequest(tuple.scope, tuple.targetId)) { context ->
            guard.startAdminContent(context)
            guard.admitAdminContent(context, tuple)
        }

    private fun ownerAdmit(guard: ComplaintIngressAdmission, tuple: ComplaintOwnerEditTuple) = guard.withIngress(historyTestRequest()) { context ->
        guard.startOwnerEdit(context)
        guard.admitOwnerEdit(context, tuple)
    }

    private fun ledger(selected: ComplaintCapacityPolicyV1) = ComplaintCapacityLedger(
        ComplaintCapacityConfiguration.of(selected.digestBytes(), false),
        ComplaintCapacityBalance(selected.hardLimit, selected.creationLimit, selected.hardLimit),
    )

    private class Counters(perHour: Int = 60, memberLimit: Int = 1024, prune: Int = 128) {
        val ring = ComplaintAdmissionKeyRing(admissionTestKeys())
        val members = ComplaintMutationAdmissionMembers(memberLimit, prune)
        val quotas = ComplaintAdmissionWindowStore(4096, 131072, 128, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS)
        private val admin = ComplaintAdminContentAdmissionStore(ComplaintAdminContentAdmissionPolicy.Bounded(ownerCreateTestCapacityPolicy(), memberLimit, prune, perHour), members)
        private val owner = ComplaintOwnerEditAdmissionStore(ComplaintOwnerEditAdmissionPolicy.Bounded(ownerCreateTestCapacityPolicy(), memberLimit, prune), members)

        fun admin(tuple: ComplaintAdminContentTuple, now: Long) {
            val keys = ring.keys()
            admin.admit(ComplaintAdmissionPseudonyms.adminContentMember(keys, tuple), ComplaintAdmissionPseudonyms.adminContentActor(keys, tuple.actor, tuple.scope), quotas, now)
        }

        fun owner(tuple: ComplaintOwnerEditTuple, now: Long) {
            val keys = ring.keys()
            owner.admit(ComplaintAdmissionPseudonyms.ownerEditMember(keys, tuple), ComplaintAdmissionPseudonyms.ownerEditDeleteActor(keys, tuple.installation), quotas, now)
        }
    }
}
