package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1

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
internal class ComplaintOwnerCreateAdmissionStore(
    private val policy: ComplaintOwnerCreateAdmissionPolicy.Bounded,
    private val members: ComplaintMutationAdmissionMembers = ComplaintMutationAdmissionMembers(policy.memberLimit, policy.pruneBatch),
) {
    init {
        require(members.memberLimit == policy.memberLimit && members.pruneBatch == policy.pruneBatch) { INVALID_ADMISSION_CONFIGURATION }
    }

    /** All inputs are internally derived HMACs. Caller holds the same lock as quota admission. */
    fun admit(
        memberKeys: List<ComplaintAdmissionBucketKey>,
        actorKeys: List<ComplaintAdmissionBucketKey>,
        globalKeys: List<ComplaintAdmissionBucketKey>,
        quotas: ComplaintAdmissionWindowStore,
        now: Long,
    ) {
        check(memberKeys.size in 1..2 && memberKeys.size == actorKeys.size && memberKeys.size == globalKeys.size)
        val charges = actorKeys.map { ComplaintAdmissionCharge(it, 10) } +
            globalKeys.map { ComplaintAdmissionCharge(it, policy.globalPerHour) }
        members.admit(memberKeys, charges, quotas, now)
    }

    fun removeGeneration(generation: String) {
        members.removeGeneration(generation)
    }

    override fun toString(): String = "ComplaintOwnerCreateAdmissionStore(bounded,redacted)"
}
