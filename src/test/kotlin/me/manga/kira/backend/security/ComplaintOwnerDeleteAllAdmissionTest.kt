package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDeleteAllFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationTuple
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightTuple
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport

/** Local quota/identity tests only. These synthetic tuples do not pass the real SQL preflight issuer check. */
class ComplaintOwnerDeleteAllAdmissionTest {
    @Test
    fun `daily actor exhaustion does not burn another IP allowance and an hour does not reset the day`() {
        val f = Counters()
        val actor = admissionTestActor(1)
        repeat(5) { f.admit(tuple(actor), 0) }
        val failure = admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) {
            f.admit(tuple(actor), ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS, ip = 2)
        }
        assertEquals(23 * 3600L, failure.retryAfterSeconds)
        // The refused mixed vector must not have installed or incremented the otherwise-empty IP dimension.
        repeat(20) { f.admit(tuple(admissionTestActor(it + 2)), ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS, ip = 2) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) {
            f.admit(tuple(admissionTestActor(30)), ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS, ip = 2)
        }
        f.admit(tuple(actor), ComplaintAdmissionPolicy.DELETE_ALL_WINDOW_NANOS)
        repeat(4) { f.admit(tuple(actor), ComplaintAdmissionPolicy.DELETE_ALL_WINDOW_NANOS) }
        assertEquals(
            86400L,
            admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) {
                f.admit(tuple(actor), ComplaintAdmissionPolicy.DELETE_ALL_WINDOW_NANOS)
            }.retryAfterSeconds,
        )
    }

    @Test
    fun `IP exhaustion neither charges the daily actor nor records a dedup hit and expires after one hour`() {
        val f = Counters()
        repeat(20) { f.admit(tuple(admissionTestActor(it + 1)), 0) }
        val actor = admissionTestActor(100)
        val denied = tuple(actor)
        assertEquals(3600L, admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { f.admit(denied, 0) }.retryAfterSeconds)
        // Five, not four: an IP refusal must not have charged the daily actor.
        repeat(5) { f.admit(tuple(actor), 0, ip = 2) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { f.admit(denied, 0, ip = 2) }
        val next = tuple(admissionTestActor(101))
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { f.admit(next, 0) }
        val hour = ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS
        f.admit(next, hour)
        repeat(4) { f.admit(tuple(next.installation), hour) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { f.admit(tuple(next.installation), hour) }
    }

    @Test
    fun `both retained HMAC generations are one atomic vector and duplicates retain their original expiry`() {
        val f = Counters(members = 32)
        val actor = admissionTestActor(1)
        val first = tuple(actor)
        f.admit(first, 0)
        repeat(4) { f.admit(tuple(actor), 0) }
        f.ring.rotate(admissionTestKey(2), 1)
        f.admit(first, 2) // Old-generation duplicate must not manufacture a current-generation member or repay quota.
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { f.admit(tuple(actor), 2) }
        val newActorKey = ComplaintAdmissionPseudonyms.ownerDeleteAllActor(f.ring.keys().take(1), actor).single()
        repeat(5) { f.quotas.charge(listOf(ComplaintAdmissionCharge(newActorKey, 5, ComplaintAdmissionWindow.DELETE_ALL_DAY)), 2) }
        admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) {
            f.ring.retirePrevious(ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS)
        }
        val until = ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS + 1
        val retired = f.ring.retirePrevious(until)
        f.members.removeGeneration(retired)
        f.quotas.removeGeneration(retired)
        f.admit(first, until)
        repeat(4) { f.admit(tuple(actor), until) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { f.admit(tuple(actor), until) }
    }

    @Test
    fun `create and delete share one bounded member registry without live eviction or duplicate TTL renewal`() {
        val f = Counters(members = 2, prune = 1)
        val deleted = tuple(admissionTestActor(1))
        val created = ComplaintOwnerOperationTuple(deleted.installation, UUID.randomUUID(), UUID.randomUUID(), ByteArray(32) { 17 })
        val creates = ComplaintOwnerCreateAdmissionStore(ComplaintOwnerCreateAdmissionPolicy.Bounded(f.policy, 12, 2, 1), f.members)
        fun create(now: Long) {
            val keys = f.ring.keys()
            creates.admit(
                ComplaintAdmissionPseudonyms.ownerCreateMember(keys, created),
                ComplaintAdmissionPseudonyms.ownerCreateActor(keys, created.installation),
                ComplaintAdmissionPseudonyms.ownerCreateGlobal(keys),
                f.quotas,
                now,
            )
        }
        f.admit(deleted, 0)
        create(0)
        val expiry = ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS
        repeat(3) {
            f.admit(deleted, expiry - 1)
            create(expiry - 1)
        }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.admit(tuple(deleted.installation), expiry - 1) }
        // One expired member per attempt, bounded backlog refusal. A duplicate above extended neither TTL.
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.admit(tuple(deleted.installation), expiry) }
        f.admit(tuple(deleted.installation), expiry)
        create(expiry)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.admit(tuple(deleted.installation), expiry) }

        val ingress = ownerDeleteAllTestIngress(members = 2, creates = true)
        fun ingressDelete(value: InstallationDeletionPreflightTuple) = ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerDeleteAll(context)
            ingress.admitOwnerDeleteAll(context, value)
        }
        fun ingressCreate() = ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerCreate(context)
            ingress.admitOwnerCreate(context, created)
        }
        ingressDelete(deleted)
        ingressCreate()
        ingressDelete(deleted)
        ingressCreate()
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { ingressDelete(tuple(deleted.installation)) }
    }

    @Test
    fun `shared bucket and event cardinality refusals install no partial mixed-window charge`() {
        val attempt = tuple(admissionTestActor(1))
        for ((buckets, events) in listOf(1 to 128, 128 to 1)) {
            val f = Counters(buckets = buckets, events = events)
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.admit(attempt, 0) }
            val actor = ComplaintAdmissionPseudonyms.ownerDeleteAllActor(f.ring.keys(), attempt.installation).single()
            // Exactly one slot/event remains usable; the rejected two-dimensional vector was not partly installed.
            f.quotas.charge(listOf(ComplaintAdmissionCharge(actor, 5, ComplaintAdmissionWindow.DELETE_ALL_DAY)), 0)
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.admit(attempt, 0) }
        }
        val f = Counters(members = 2)
        f.admit(attempt, 0)
        f.ring.rotate(admissionTestKey(2), 1)
        f.admit(attempt, 2)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { f.admit(tuple(admissionTestActor(2)), 2) }
        val keys = f.ring.keys()
        assertNotEquals(
            ComplaintAdmissionPseudonyms.ownerDeleteAllMember(keys, attempt),
            ComplaintAdmissionPseudonyms.ownerDeleteAllMember(keys, tuple(attempt.installation, version = 2, key = attempt.operationKey)),
        )
    }

    @Test
    fun `P must cover immediate and future hard charges but creation closure never substitutes for privacy exhaustion`() {
        val p = ownerDeleteAllTestCapacityPolicy()
        val bounds = ComplaintOwnerDeleteAllAdmissionPolicy.Bounded(p, 32, 8)
        assertTrue(bounds.matchesLocked(ledger(p, closed = true)))
        val required = OwnerDeleteAllCapacityCharges.AUTHORIZATION + OwnerDeleteAllCapacityCharges.RECOVERY
        assertEquals(113L, OwnerDeleteAllCapacityCharges.RECOVERY[ComplaintCapacityCounter.AUDIT_ROWS])
        val short = p.hardLimit.with(ComplaintCapacityCounter.STORAGE_BYTES, required[ComplaintCapacityCounter.STORAGE_BYTES] - 1)
        val insufficient = ComplaintCapacityPolicyV1.of(short, short, 100)
        assertThrows<IllegalArgumentException> { ComplaintOwnerDeleteAllAdmissionPolicy.Bounded(insufficient, 32, 8) }
        val wrongLimits = ComplaintCapacityBalance(p.hardLimit, p.creationLimit.with(ComplaintCapacityCounter.RESOURCE_IDS, 1), p.hardLimit)
        assertFalse(bounds.matchesLocked(ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(p.digestBytes(), false), wrongLimits)))
        val ingress = ownerCreateTestIngress()
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerDeleteAll(context)
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { ingress.admitOwnerDeleteAll(context, tuple(admissionTestActor(1))) }
        }
    }

    @Test
    fun `registered handoff binds original caller context tuple phase stages and the unrenewed five second deadline`() {
        val ingress = ownerDeleteAllTestIngress()
        val tuple = tuple(admissionTestActor(1))
        val forged = object : ComplaintAdmittedOwnerDeleteAll {}
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.requireOwnerDeleteAllEntry(forged, tuple) }
        lateinit var retained: ComplaintAdmittedOwnerDeleteAll
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerDeleteAll(context)
            retained = ingress.admitOwnerDeleteAll(context, tuple)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ingress.admitOwnerDeleteAll(context, tuple) }
            val copy = object : InstallationDeletionPreflightTuple by tuple {}
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.requireOwnerDeleteAllEntry(retained, copy) }
            val caller = Executors.newSingleThreadExecutor()
            try {
                caller.submit<Unit> {
                    admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.requireOwnerDeleteAllEntry(retained, tuple) }
                }.get()
            } finally {
                caller.shutdownNow()
            }
            ComplaintIngressAdmission.requireOwnerDeleteAllEntry(retained, tuple)
            val phase = Any()
            ComplaintIngressAdmission.bindOwnerDeleteAll(retained, phase)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.requireOwnerDeleteAllEntry(retained, tuple) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerDeleteAll(retained, Any(), tuple) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerDeleteAll(retained, phase, copy) }
            ComplaintIngressAdmission.claimOwnerDeleteAll(retained, phase, tuple)
            ComplaintIngressAdmission.checkOwnerDeleteAllReceipt(retained, phase)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkOwnerDeleteAllWrite(retained, phase) }
            ComplaintIngressAdmission.checkOwnerDeleteAllBounds(retained, phase, ledger(ownerDeleteAllTestCapacityPolicy(), closed = true))
            ComplaintIngressAdmission.checkOwnerDeleteAllWrite(retained, phase)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkOwnerDeleteAllReceipt(retained, phase) }
        }
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.checkOwnerDeleteAllWrite(retained, Any()) }
        ownerDeleteAllTestIngress(clock = MutableAdmissionTestClock()).let { fake ->
            fake.withIngress(historyTestRequest()) { context ->
                fake.startOwnerDeleteAll(context)
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { fake.admitOwnerDeleteAll(context, tuple) }
            }
        }
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerDeleteAll(context)
            val admitted = ingress.admitOwnerDeleteAll(context, tuple)
            val started = System.nanoTime()
            val phase = Any()
            ComplaintIngressAdmission.bindOwnerDeleteAll(admitted, phase)
            while (System.nanoTime() - started < ComplaintAdmissionPolicy.ADMISSION_LIFETIME_NANOS) LockSupport.parkNanos(1_000_000)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.claimOwnerDeleteAll(admitted, phase, tuple) }
        }
    }

    private fun ledger(p: ComplaintCapacityPolicyV1, closed: Boolean) = ComplaintCapacityLedger(
        ComplaintCapacityConfiguration.of(p.digestBytes(), closed),
        ComplaintCapacityBalance(p.hardLimit, p.creationLimit, p.hardLimit),
    )

    private fun tuple(actor: ScopedInstallationId, version: Long = 1, key: UUID = UUID.randomUUID()): InstallationDeletionPreflightTuple {
        val candidate = InstallationDeletionCandidate(InstallationEnrollmentCredentials.prepareSession(actor, ByteArray(32) { 7 }), version, key)
        return object : InstallationDeletionPreflightTuple {
            override val installation = actor
            override val submittedCredentialVersion = version
            override val operationKey = key
            override val fingerprint = ComplaintDeleteAllFingerprint.of(candidate)
        }
    }

    private class Counters(members: Int = 128, prune: Int = 128, buckets: Int = 4096, events: Int = 131072) {
        val policy = ownerDeleteAllTestCapacityPolicy()
        val ring = ComplaintAdmissionKeyRing(admissionTestKeys())
        val members = ComplaintMutationAdmissionMembers(members, prune)
        val quotas =
            ComplaintAdmissionWindowStore(buckets, events, 128, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS, ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS)
        private val store =
            ComplaintOwnerDeleteAllAdmissionStore(ComplaintOwnerDeleteAllAdmissionPolicy.Bounded(policy, this.members.memberLimit, prune), this.members)

        fun admit(tuple: InstallationDeletionPreflightTuple, now: Long, ip: Int = 1) {
            val keys = ring.keys()
            store.admit(
                ComplaintAdmissionPseudonyms.ownerDeleteAllMember(keys, tuple),
                ComplaintAdmissionPseudonyms.ownerDeleteAllActor(keys, tuple.installation),
                ComplaintAdmissionPseudonyms.ownerDeleteAllIp(keys, byteArrayOf(192.toByte(), 0, 2, ip.toByte())),
                quotas,
                now,
            )
        }
    }
}

/** Synthetic P/configuration for local admission and dormant SQL fixtures, never full-D/current capability evidence. */
internal fun ownerDeleteAllTestIngress(
    policy: ComplaintCapacityPolicyV1 = ownerDeleteAllTestCapacityPolicy(),
    members: Int = 1024,
    creates: Boolean = false,
    clock: ComplaintAdmissionNanoClock = SystemComplaintAdmissionNanoClock,
): ComplaintIngressAdmission = ComplaintIngressAdmission(
    ClientIpResolver(KiraSecurityProperties()),
    admissionTestPolicy(),
    admissionTestKeys(),
    clock,
    if (creates) ComplaintOwnerCreateAdmissionPolicy.Bounded(policy, 12, members, 128) else ComplaintOwnerCreateAdmissionPolicy.Disabled,
    ComplaintOwnerDeleteAllAdmissionPolicy.Bounded(policy, members, 128),
)

internal fun ownerDeleteAllTestCapacityPolicy(): ComplaintCapacityPolicyV1 = ComplaintCapacityPolicyV1.of(
    ComplaintCapacityVector.of(LongArray(22) { 20_000_000 }),
    ComplaintCapacityVector.of(LongArray(22) { 18_000_000 }),
    100,
)
