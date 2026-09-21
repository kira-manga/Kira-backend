package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1

/** Closed Admin erasure handoffs; only the original ingress owner's private registered instance authorizes work. */
internal sealed interface ComplaintAdmittedAdminErasure

internal interface ComplaintAdmittedAdminDelete : ComplaintAdmittedAdminErasure

internal sealed interface ComplaintAdminDeleteAdmissionPolicy {
    data object Disabled : ComplaintAdminDeleteAdmissionPolicy

    class Bounded(
        private val capacityPolicy: ComplaintCapacityPolicyV1,
        val memberLimit: Int,
        val pruneBatch: Int,
        val perHour: Int = 60,
    ) : ComplaintAdminDeleteAdmissionPolicy {
        init {
            require(memberLimit in 2..131072 && pruneBatch in 1..128 && perHour in 1..60) { INVALID_ADMISSION_CONFIGURATION }
        }

        internal fun matchesLocked(ledger: ComplaintCapacityLedger): Boolean = capacityPolicy.digestBytes().contentEquals(ledger.configuration.digestBytes()) &&
            capacityPolicy.hardLimit == ledger.balance.hardLimit && capacityPolicy.creationLimit == ledger.balance.creationLimit

        override fun toString(): String = "ComplaintAdminDeleteAdmissionPolicy.Bounded(redacted)"
    }
}

/** Same hourly store and same finite mutation-member owner as co-composed owner writes; no Admin-read minute bucket. */
internal class ComplaintAdminDeleteAdmissionStore(
    private val policy: ComplaintAdminDeleteAdmissionPolicy.Bounded,
    private val members: ComplaintMutationAdmissionMembers = ComplaintMutationAdmissionMembers(policy.memberLimit, policy.pruneBatch),
) {
    init {
        require(members.memberLimit == policy.memberLimit && members.pruneBatch == policy.pruneBatch) { INVALID_ADMISSION_CONFIGURATION }
    }

    fun admit(memberKeys: List<ComplaintAdmissionBucketKey>, actorKeys: List<ComplaintAdmissionBucketKey>, quotas: ComplaintAdmissionWindowStore, now: Long) {
        check(memberKeys.size in 1..2 && memberKeys.size == actorKeys.size)
        members.admit(memberKeys, actorKeys.map { ComplaintAdmissionCharge(it, policy.perHour) }, quotas, now)
    }

    override fun toString(): String = "ComplaintAdminDeleteAdmissionStore(bounded,redacted)"
}
