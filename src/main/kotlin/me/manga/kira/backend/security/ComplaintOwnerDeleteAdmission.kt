package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1

/** Only the ingress owner's private registered handoff can authorize the fixed delete phase. */
internal interface ComplaintAdmittedOwnerDelete

internal sealed interface ComplaintOwnerDeleteAdmissionPolicy {
    data object Disabled : ComplaintOwnerDeleteAdmissionPolicy

    class Bounded(private val capacityPolicy: ComplaintCapacityPolicyV1, val memberLimit: Int, val pruneBatch: Int) : ComplaintOwnerDeleteAdmissionPolicy {
        init {
            require(memberLimit in 2..131072 && pruneBatch in 1..128) { INVALID_ADMISSION_CONFIGURATION }
        }

        internal fun matchesLocked(ledger: ComplaintCapacityLedger): Boolean = capacityPolicy.digestBytes().contentEquals(ledger.configuration.digestBytes()) &&
            capacityPolicy.hardLimit == ledger.balance.hardLimit && capacityPolicy.creationLimit == ledger.balance.creationLimit

        override fun toString(): String = "ComplaintOwnerDeleteAdmissionPolicy.Bounded(redacted)"
    }
}

/** Same bounded member owner and physical hourly store, distinct shared edit/delete 60/hour actor family. */
internal class ComplaintOwnerDeleteAdmissionStore(
    private val policy: ComplaintOwnerDeleteAdmissionPolicy.Bounded,
    private val members: ComplaintMutationAdmissionMembers = ComplaintMutationAdmissionMembers(policy.memberLimit, policy.pruneBatch),
) {
    init {
        require(members.memberLimit == policy.memberLimit && members.pruneBatch == policy.pruneBatch) { INVALID_ADMISSION_CONFIGURATION }
    }

    fun admit(memberKeys: List<ComplaintAdmissionBucketKey>, actorKeys: List<ComplaintAdmissionBucketKey>, quotas: ComplaintAdmissionWindowStore, now: Long) {
        check(memberKeys.size in 1..2 && memberKeys.size == actorKeys.size)
        members.admit(memberKeys, actorKeys.map { ComplaintAdmissionCharge(it, 60) }, quotas, now)
    }

    override fun toString(): String = "ComplaintOwnerDeleteAdmissionStore(bounded,redacted)"
}

/** Fixed member framing; the actor family is the existing shared OWNER_EDIT_DELETE pseudonym. */
internal object ComplaintOwnerDeleteAdmissionFrames {
    fun member(keys: List<ComplaintAdmissionKey>, tuple: me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple): List<ComplaintAdmissionBucketKey> {
        fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)
        fun uuid(value: java.util.UUID) = java.nio.ByteBuffer.allocate(16).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array()
        val fields = listOf(
            ascii("kira-complaint-admission-v1"), ascii("MEMBER"), ascii("INSTALLATION"),
            uuid(tuple.installation.id), uuid(tuple.installation.scope.id), ascii("OWNER_DELETE"),
            uuid(tuple.key), uuid(tuple.targetId), tuple.fingerprintBytes(),
        )
        require(keys.size in 1..2) { INVALID_ADMISSION_CONFIGURATION }
        val frame = java.nio.ByteBuffer.allocate(fields.sumOf { 4 + it.size }).apply { fields.forEach { putInt(it.size).put(it) } }.array()
        return try {
            keys.map { ComplaintAdmissionBucketKey(it.id, it.digest(frame)) }
        } finally {
            frame.fill(0)
            fields.forEach { it.fill(0) }
        }
    }
}
