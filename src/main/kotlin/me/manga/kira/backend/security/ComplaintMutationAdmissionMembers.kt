package me.manga.kira.backend.security

import java.util.TreeMap

/** One bounded HMAC-member set for the two concrete mutation admissions; the ingress monitor owns it. */
internal class ComplaintMutationAdmissionMembers(val memberLimit: Int, val pruneBatch: Int) {
    private val members = HashMap<ComplaintAdmissionBucketKey, Long>()
    private val expiry = TreeMap<Long, MutableSet<ComplaintAdmissionBucketKey>>()

    init {
        require(memberLimit in 2..131072 && pruneBatch in 1..128) { INVALID_ADMISSION_CONFIGURATION }
    }

    /** Fixed callers build the complete charge vector. No callback, partial charge or duplicate TTL renewal. */
    fun admit(memberKeys: List<ComplaintAdmissionBucketKey>, charges: List<ComplaintAdmissionCharge>, quotas: ComplaintAdmissionWindowStore, now: Long) {
        check(memberKeys.size in 1..2 && memberKeys.distinct().size == memberKeys.size)
        prune(now)
        if (memberKeys.any { it in members }) return
        if (members.size + memberKeys.size > memberLimit) refuseComplaintAdmission()
        val until = Math.addExact(now, ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS)
        val registration = expiry[until] ?: LinkedHashSet()
        quotas.charge(charges, now)
        memberKeys.forEach { key ->
            check(members.put(key, until) == null)
            check(registration.add(key))
        }
        expiry[until] = registration
    }

    fun removeGeneration(generation: String) {
        members.keys.filter { it.generation == generation }.forEach(::remove)
    }

    private fun prune(now: Long) {
        var removed = 0
        while (removed < pruneBatch) {
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

    override fun toString(): String = "ComplaintMutationAdmissionMembers(bounded,redacted)"
}
