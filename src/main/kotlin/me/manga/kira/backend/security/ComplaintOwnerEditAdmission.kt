package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1

/** Only the ingress owner's private registered handoff can authorize the fixed edit phase. */
internal interface ComplaintAdmittedOwnerEdit

internal sealed interface ComplaintOwnerEditAdmissionPolicy {
    data object Disabled : ComplaintOwnerEditAdmissionPolicy

    class Bounded(private val capacityPolicy: ComplaintCapacityPolicyV1, val memberLimit: Int, val pruneBatch: Int) : ComplaintOwnerEditAdmissionPolicy {
        init {
            require(memberLimit in 2..131072 && pruneBatch in 1..128) { INVALID_ADMISSION_CONFIGURATION }
        }

        internal fun matchesLocked(ledger: ComplaintCapacityLedger): Boolean = capacityPolicy.digestBytes().contentEquals(ledger.configuration.digestBytes()) &&
            capacityPolicy.hardLimit == ledger.balance.hardLimit && capacityPolicy.creationLimit == ledger.balance.creationLimit

        override fun toString(): String = "ComplaintOwnerEditAdmissionPolicy.Bounded(redacted)"
    }
}

/** Same bounded member owner and physical hourly store, distinct shared edit/delete 60/hour actor family. */
internal class ComplaintOwnerEditAdmissionStore(
    private val policy: ComplaintOwnerEditAdmissionPolicy.Bounded,
    private val members: ComplaintMutationAdmissionMembers = ComplaintMutationAdmissionMembers(policy.memberLimit, policy.pruneBatch),
) {
    init {
        require(members.memberLimit == policy.memberLimit && members.pruneBatch == policy.pruneBatch) { INVALID_ADMISSION_CONFIGURATION }
    }

    fun admit(memberKeys: List<ComplaintAdmissionBucketKey>, actorKeys: List<ComplaintAdmissionBucketKey>, quotas: ComplaintAdmissionWindowStore, now: Long) {
        check(memberKeys.size in 1..2 && memberKeys.size == actorKeys.size)
        members.admit(memberKeys, actorKeys.map { ComplaintAdmissionCharge(it, 60) }, quotas, now)
    }

    override fun toString(): String = "ComplaintOwnerEditAdmissionStore(bounded,redacted)"
}
