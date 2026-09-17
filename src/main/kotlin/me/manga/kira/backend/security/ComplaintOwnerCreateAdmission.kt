package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import java.util.TreeMap

/** Private ingress implementation is the only producer; implementing this view grants no write. */
internal interface ComplaintAdmittedOwnerCreate

/** Independent P-based ceilings; this declaration grants neither mode nor TEST-run authority. */
internal sealed interface ComplaintOwnerCreateAdmissionPolicy {
    data object Disabled : ComplaintOwnerCreateAdmissionPolicy

    class Bounded(private val capacityPolicy: ComplaintCapacityPolicyV1, val globalPerHour: Int, val memberLimit: Int, val pruneBatch: Int) :
        ComplaintOwnerCreateAdmissionPolicy {
        init {
            require(globalPerHour in 1..120 && memberLimit in 2..131072 && pruneBatch in 1..128) { INVALID_ADMISSION_CONFIGURATION }
            // A full bounded create (including receipt/audit/storage) fits strictly more than the abuse ceiling.
            require(
                ComplaintCapacityCounter.entries.all { counter ->
                    val unit = ComplaintCapacityCharges.OWNER_CREATE[counter]
                    unit == 0L || globalPerHour.toLong() < capacityPolicy.creationLimit[counter] / unit
                },
            ) { INVALID_ADMISSION_CONFIGURATION }
        }

        internal fun matchesLocked(ledger: ComplaintCapacityLedger): Boolean = capacityPolicy.digestBytes().contentEquals(ledger.configuration.digestBytes()) &&
            capacityPolicy.hardLimit == ledger.balance.hardLimit && capacityPolicy.creationLimit == ledger.balance.creationLimit

        override fun toString(): String = "ComplaintOwnerCreateAdmissionPolicy.Bounded(redacted)"
    }
}

/**
 * One bounded global HMAC-member set; the owning ingress serializes it with the EXISTING hourly
 * quota store. No raw tuple, prose or one-map-per-idempotency-key storage. A refusal never evicts
 * live state or refunds an earlier admission. There is deliberately no shared/Redis fallback.
 */
internal class ComplaintOwnerCreateAdmissionStore(private val policy: ComplaintOwnerCreateAdmissionPolicy.Bounded) {
    private val members = HashMap<ComplaintAdmissionBucketKey, Long>()
    private val expiry = TreeMap<Long, MutableSet<ComplaintAdmissionBucketKey>>()

    /** All inputs are internally derived HMACs. Caller holds the same lock as quota admission. */
    fun admit(
        memberKeys: List<ComplaintAdmissionBucketKey>,
        actorKeys: List<ComplaintAdmissionBucketKey>,
        globalKeys: List<ComplaintAdmissionBucketKey>,
        quotas: ComplaintAdmissionWindowStore,
        now: Long,
    ) {
        check(memberKeys.size in 1..2 && memberKeys.size == actorKeys.size && memberKeys.size == globalKeys.size)
        prune(now)
        // No new TTL or charge on a duplicate, including a previous-generation-only hit.
        if (memberKeys.any { it in members }) return
        if (members.size + memberKeys.size > policy.memberLimit) refuseComplaintAdmission()
        val until = Math.addExact(now, ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS)
        val registration = expiry[until] ?: LinkedHashSet()
        val charges = actorKeys.map { ComplaintAdmissionCharge(it, 10) } +
            globalKeys.map { ComplaintAdmissionCharge(it, policy.globalPerHour) }
        quotas.charge(charges, now) // Checks every dimension before incrementing any one.
        memberKeys.forEach { key ->
            check(members.put(key, until) == null)
            check(registration.add(key))
        }
        expiry[until] = registration
    }

    fun removeGeneration(generation: String) {
        // Drained key retirement only. Traversal is bounded by the declared global member ceiling.
        members.keys.filter { it.generation == generation }.forEach(::remove)
    }

    private fun prune(now: Long) {
        var removed = 0
        while (removed < policy.pruneBatch) {
            val first = expiry.firstEntry() ?: break
            if (first.key > now) break
            remove(first.value.first())
            removed += 1
        }
        if (expiry.firstEntry()?.key?.let { it <= now } == true) refuseComplaintAdmission()
    }

    private fun remove(key: ComplaintAdmissionBucketKey) {
        val until = checkNotNull(members.remove(key))
        val registered = checkNotNull(expiry[until])
        check(registered.remove(key))
        if (registered.isEmpty()) expiry.remove(until)
    }

    override fun toString(): String = "ComplaintOwnerCreateAdmissionStore(bounded,redacted)"
}
