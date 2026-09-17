package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges

/** Only the ingress owner's private registered instance is recognized; not runtime or journal authority. */
internal interface ComplaintAdmittedOwnerDeleteAll

/** Independent P and bounded local memory only. No full D, deployment topology or LIVE capability claim. */
internal sealed interface ComplaintOwnerDeleteAllAdmissionPolicy {
    data object Disabled : ComplaintOwnerDeleteAllAdmissionPolicy

    class Bounded(private val capacityPolicy: ComplaintCapacityPolicyV1, val memberLimit: Int, val pruneBatch: Int) : ComplaintOwnerDeleteAllAdmissionPolicy {
        init {
            require(memberLimit in 2..131072 && pruneBatch in 1..128) { INVALID_ADMISSION_CONFIGURATION }
            require((OwnerDeleteAllCapacityCharges.AUTHORIZATION + OwnerDeleteAllCapacityCharges.RECOVERY).fitsWithin(capacityPolicy.hardLimit)) {
                INVALID_ADMISSION_CONFIGURATION
            }
        }

        internal fun matchesLocked(ledger: ComplaintCapacityLedger): Boolean = capacityPolicy.digestBytes().contentEquals(ledger.configuration.digestBytes()) &&
            capacityPolicy.hardLimit == ledger.balance.hardLimit && capacityPolicy.creationLimit == ledger.balance.creationLimit

        override fun toString(): String = "ComplaintOwnerDeleteAllAdmissionPolicy.Bounded(no-activation,redacted)"
    }
}

/** Fixed 5/installation/day + 20/trusted-IP/hour, sharing the existing atomic quota/member owners. */
internal class ComplaintOwnerDeleteAllAdmissionStore(
    policy: ComplaintOwnerDeleteAllAdmissionPolicy.Bounded,
    private val members: ComplaintMutationAdmissionMembers,
) {
    init {
        require(members.memberLimit == policy.memberLimit && members.pruneBatch == policy.pruneBatch) { INVALID_ADMISSION_CONFIGURATION }
    }

    fun admit(
        memberKeys: List<ComplaintAdmissionBucketKey>,
        actorKeys: List<ComplaintAdmissionBucketKey>,
        ipKeys: List<ComplaintAdmissionBucketKey>,
        quotas: ComplaintAdmissionWindowStore,
        now: Long,
    ) {
        check(memberKeys.size in 1..2 && memberKeys.size == actorKeys.size && memberKeys.size == ipKeys.size)
        val charges = actorKeys.map { ComplaintAdmissionCharge(it, 5, ComplaintAdmissionWindow.DELETE_ALL_DAY) } +
            ipKeys.map { ComplaintAdmissionCharge(it, 20) }
        members.admit(memberKeys, charges, quotas, now)
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllAdmissionStore(bounded,redacted)"
}
