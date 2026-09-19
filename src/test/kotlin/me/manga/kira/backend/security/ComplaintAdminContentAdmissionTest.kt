package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentTuple
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusOperation
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusTuple
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
    fun statusAndClosureShareOneLowerableHourButNotContentQuotaWhileAllThreeUseTheOriginalFiniteStores() {
        assertEquals(60, ComplaintAdminStatusAdmissionPolicy.Bounded(policy, 2, 1).perHour)
        for (limit in listOf(-1, 0, 61, Int.MAX_VALUE)) {
            assertThrows<IllegalArgumentException> { ComplaintAdminStatusAdmissionPolicy.Bounded(policy, 2, 1, limit) }
        }
        val defaults = Counters()
        repeat(60) { defaults.status(statusTuple(if (it % 2 == 0) STATUS else CLOSURE), 0) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { defaults.status(statusTuple(), 0) }
        val counters = Counters(perHour = 2)
        val content = tuple()
        val transition = statusTuple(original = content)
        val closure = statusTuple(CLOSURE, content)
        counters.status(transition, 0)
        repeat(20) { counters.status(transition, 1) }
        counters.status(closure, 1) // Same key/target/digest, different operation: never dedup as the first intent.
        val denied = statusTuple()
        assertEquals(3600L, admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { counters.status(denied, 1) }.retryAfterSeconds)
        counters.admin(content, 1)
        counters.admin(tuple(), 1)
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { counters.admin(tuple(), 1) }
        counters.status(statusTuple(original = tuple(selectedActor = UUID.randomUUID())), 1)
        counters.status(statusTuple(original = tuple(selectedScope = ComplaintDataScope.of(UUID.randomUUID()))), 1)
        val hour = ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS
        assertEquals(1L, admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { counters.status(denied, hour - 1) }.retryAfterSeconds)
        counters.status(denied, hour)
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { counters.status(statusTuple(), hour) }
        counters.status(statusTuple(), hour + 1)

        val keys = counters.ring.keys()
        val variants = listOf(
            transition, closure,
            statusTuple(original = tuple(selectedActor = UUID.randomUUID(), key = content.key, target = content.targetId)),
            statusTuple(original = tuple(selectedScope = ComplaintDataScope.of(UUID.randomUUID()), key = content.key, target = content.targetId)),
            statusTuple(original = tuple(key = UUID.randomUUID(), target = content.targetId)),
            statusTuple(original = tuple(key = content.key, target = UUID.randomUUID())),
            statusTuple(original = tuple(key = content.key, target = content.targetId, digest = ByteArray(32) { 9 })),
        ).map { ComplaintAdmissionPseudonyms.adminStatusMember(keys, it) }
        assertEquals(variants.size, variants.toSet().size)
        assertNotEquals(variants.first(), ComplaintAdmissionPseudonyms.adminContentMember(keys, content))
        val family = ComplaintAdmissionPseudonyms.adminStatusActor(keys, actor, scope)
        assertNotEquals(family, ComplaintAdmissionPseudonyms.adminContentActor(keys, actor, scope))
        assertNotEquals(family, ComplaintAdmissionPseudonyms.adminReadActor(keys, actor, scope))
        assertNotEquals(family, ComplaintAdmissionPseudonyms.ownerEditDeleteActor(keys, ScopedInstallationId(actor, scope)))
        assertTrue(variants.flatten().all { it.toString() == "ComplaintAdmissionBucketKey(redacted)" })
        val rotated = Counters(perHour = 1)
        rotated.status(transition, 0)
        rotated.ring.rotate(admissionTestKey(3), 1)
        rotated.status(transition, 2)
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { rotated.status(closure, 2) }

        val owner = ComplaintOwnerEditTuple(ScopedInstallationId(actor, scope), UUID.randomUUID(), UUID.randomUUID(), ByteArray(32))
        val sharedMembers = adminStatusTestIngress(members = 3, prune = 1)
        admit(sharedMembers, content)
        ownerAdmit(sharedMembers, owner)
        statusAdmit(sharedMembers, transition)
        statusAdmit(sharedMembers, transition)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { statusAdmit(sharedMembers, closure) }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { admit(sharedMembers, tuple()) }
        val sharedEvents = adminStatusTestIngress(policy = admissionTestPolicy(semanticBuckets = 2, semanticEvents = 3))
        statusAdmit(sharedEvents, transition)
        admit(sharedEvents, content)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { ownerAdmit(sharedEvents, owner) }
        statusAdmit(sharedEvents, closure)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { admit(sharedEvents, tuple()) }
        statusAdmit(sharedEvents, transition)
    }

    @Test
    fun moderationHandoffIsDefaultDisabledAndBoundToItsOriginalIngressThreadPhaseOperationTupleAndDeadline() {
        val original = statusTuple()
        val phase = Any()
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) {
            ComplaintIngressAdmission.bindAdminStatus(object : ComplaintAdmittedAdminStatus {}, phase)
        }
        val disabled = adminContentTestIngress()
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { statusAdmit(disabled, original) }
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { statusAdmit(adminStatusTestIngress(clock = MutableAdmissionTestClock()), original) }
        assertThrows<IllegalArgumentException> { adminStatusTestIngress(members = 1) }
        assertThrows<IllegalArgumentException> {
            adminStatusTestIngress(members = 2, status = ComplaintAdminStatusAdmissionPolicy.Bounded(policy, 3, 128))
        }
        val guard = adminStatusTestIngress()
        val foreign = adminStatusTestIngress()
        lateinit var retained: ComplaintAdmittedAdminStatus
        guard.withIngress(adminStatusTestRequest(STATUS, scope, original.targetId)) { context ->
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { foreign.startAdminStatus(context) }
            val resource = Any()
            TransactionSynchronizationManager.bindResource(resource, Any())
            try { assertThrows<PersistencePhaseException> { guard.startAdminStatus(context) } } finally {
                TransactionSynchronizationManager.unbindResource(resource)
            }
            guard.startAdminStatus(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.admitAdminContent(context, tuple()) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.admitAdminStatus(ComplaintIngressContext(), original) }
            retained = guard.admitAdminStatus(context, original)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.admitAdminStatus(context, original) }
            OwnedCallerTestScope().use { callers ->
                callers.launch { admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindAdminStatus(retained, phase) } }.value()
            }
            ComplaintIngressAdmission.bindAdminStatus(retained, phase)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindAdminStatus(retained, phase) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkAdminStatusClaim(retained, phase) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimAdminStatus(retained, Any(), original) }
            val copied = ComplaintAdminStatusTuple(original.actor, original.scope, original.key, original.targetId, original.operation, original.fingerprintBytes())
            assertTrue(original.matches(copied))
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimAdminStatus(retained, phase, copied) }
            val wrongOperation = ComplaintAdminStatusTuple(original.actor, original.scope, original.key, original.targetId, CLOSURE, original.fingerprintBytes())
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimAdminStatus(retained, phase, wrongOperation) }
            ComplaintIngressAdmission.claimAdminStatus(retained, phase, original)
            ComplaintIngressAdmission.checkAdminStatusClaim(retained, phase)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimAdminStatus(retained, phase, original) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkAdminStatusWrite(retained, phase) }
            val mismatched = ComplaintCapacityPolicyV1.of(policy.hardLimit, policy.creationLimit.with(ComplaintCapacityCounter.RESOURCE_IDS, 8_000_000), 100)
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { ComplaintIngressAdmission.checkAdminStatusBounds(retained, phase, ledger(mismatched)) }
            ComplaintIngressAdmission.checkAdminStatusBounds(retained, phase, ledger(policy))
            ComplaintIngressAdmission.checkAdminStatusWrite(retained, phase)
        }
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkAdminStatusWrite(retained, phase) }
        guard.withIngress(adminContentTestRequest(scope, original.targetId)) { context ->
            guard.startAdminContent(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.admitAdminStatus(context, original) }
        }
        val timed = adminStatusTestIngress(perHour = 1)
        timed.withIngress(adminStatusTestRequest(STATUS, scope, original.targetId)) { context ->
            timed.startAdminStatus(context)
            val admitted = timed.admitAdminStatus(context, original)
            val issuedAt = System.nanoTime()
            ComplaintIngressAdmission.bindAdminStatus(admitted, phase)
            while (System.nanoTime() - issuedAt < ComplaintAdmissionPolicy.ADMISSION_LIFETIME_NANOS) LockSupport.parkNanos(1_000_000)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimAdminStatus(admitted, phase, original) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.bindAdminStatus(admitted, Any()) }
        }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { statusAdmit(timed, statusTuple(CLOSURE)) }
        statusAdmit(timed, original)
    }

    private fun statusTuple(operation: ComplaintAdminStatusOperation = STATUS, original: ComplaintAdminContentTuple = tuple()): ComplaintAdminStatusTuple =
        ComplaintAdminStatusTuple(original.actor, original.scope, original.key, original.targetId, operation, original.fingerprintBytes())

    private fun statusAdmit(guard: ComplaintIngressAdmission, tuple: ComplaintAdminStatusTuple) =
        guard.withIngress(adminStatusTestRequest(tuple.operation, tuple.scope, tuple.targetId)) { context ->
            guard.startAdminStatus(context)
            guard.admitAdminStatus(context, tuple)
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
        private val status = ComplaintAdminStatusAdmissionStore(ComplaintAdminStatusAdmissionPolicy.Bounded(ownerCreateTestCapacityPolicy(), memberLimit, prune, perHour), members)
        private val owner = ComplaintOwnerEditAdmissionStore(ComplaintOwnerEditAdmissionPolicy.Bounded(ownerCreateTestCapacityPolicy(), memberLimit, prune), members)

        fun admin(tuple: ComplaintAdminContentTuple, now: Long) {
            val keys = ring.keys()
            admin.admit(ComplaintAdmissionPseudonyms.adminContentMember(keys, tuple), ComplaintAdmissionPseudonyms.adminContentActor(keys, tuple.actor, tuple.scope), quotas, now)
        }

        fun owner(tuple: ComplaintOwnerEditTuple, now: Long) {
            val keys = ring.keys()
            owner.admit(ComplaintAdmissionPseudonyms.ownerEditMember(keys, tuple), ComplaintAdmissionPseudonyms.ownerEditDeleteActor(keys, tuple.installation), quotas, now)
        }

        fun status(tuple: ComplaintAdminStatusTuple, now: Long) {
            val keys = ring.keys()
            status.admit(ComplaintAdmissionPseudonyms.adminStatusMember(keys, tuple), ComplaintAdmissionPseudonyms.adminStatusActor(keys, tuple.actor, tuple.scope), quotas, now)
        }
    }

    private companion object {
        val STATUS = ComplaintAdminStatusOperation.ADMIN_STATUS
        val CLOSURE = ComplaintAdminStatusOperation.ADMIN_CLOSURE
    }
}
