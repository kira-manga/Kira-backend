package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1

/** Only the original ingress owner's private registered handoff admits this fixed atomic STATUS batch. */
internal interface ComplaintAdmittedAdminBatchStatus

internal sealed interface ComplaintAdminBatchStatusAdmissionPolicy {
    data object Disabled : ComplaintAdminBatchStatusAdmissionPolicy

    class Bounded(
        private val capacityPolicy: ComplaintCapacityPolicyV1,
        val memberLimit: Int,
        val pruneBatch: Int,
        val perHour: Int = 60,
    ) : ComplaintAdminBatchStatusAdmissionPolicy {
        init {
            require(memberLimit in 2..131072 && pruneBatch in 1..128 && perHour in 1..60) { INVALID_ADMISSION_CONFIGURATION }
        }

        internal fun matchesLocked(ledger: ComplaintCapacityLedger): Boolean = capacityPolicy.digestBytes().contentEquals(ledger.configuration.digestBytes()) &&
            capacityPolicy.hardLimit == ledger.balance.hardLimit && capacityPolicy.creationLimit == ledger.balance.creationLimit

        override fun toString(): String = "ComplaintAdminBatchStatusAdmissionPolicy.Bounded(redacted)"
    }
}

/** One separately domain-separated batch quota in the existing hourly store; one admission per batch, no extra global budget. */
internal class ComplaintAdminBatchStatusAdmissionStore(
    private val policy: ComplaintAdminBatchStatusAdmissionPolicy.Bounded,
    private val members: ComplaintMutationAdmissionMembers = ComplaintMutationAdmissionMembers(policy.memberLimit, policy.pruneBatch),
) {
    init {
        require(members.memberLimit == policy.memberLimit && members.pruneBatch == policy.pruneBatch) { INVALID_ADMISSION_CONFIGURATION }
    }

    fun admit(memberKeys: List<ComplaintAdmissionBucketKey>, actorKeys: List<ComplaintAdmissionBucketKey>, quotas: ComplaintAdmissionWindowStore, now: Long) {
        check(memberKeys.size in 1..2 && memberKeys.size == actorKeys.size)
        members.admit(memberKeys, actorKeys.map { ComplaintAdmissionCharge(it, policy.perHour) }, quotas, now)
    }

    override fun toString(): String = "ComplaintAdminBatchStatusAdmissionStore(bounded,redacted)"
}
