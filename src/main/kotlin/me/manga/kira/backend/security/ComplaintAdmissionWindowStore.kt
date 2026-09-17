package me.manga.kira.backend.security

import java.util.TreeMap

/** Only the existing store window or the one fixed delete-all daily dimension; no supplied duration. */
internal enum class ComplaintAdmissionWindow { STORE, DELETE_ALL_DAY }

internal class ComplaintAdmissionCharge(
    val key: ComplaintAdmissionBucketKey,
    val limit: Int,
    val window: ComplaintAdmissionWindow = ComplaintAdmissionWindow.STORE,
)

/**
 * Local counter engine only, not authentication. The owning admission object serializes access.
 * One expiry registration per bucket; no stale heap records or live-state eviction. All live
 * dimensions are checked before any increment. Expired-state pruning is permitted on refusal.
 */
internal class ComplaintAdmissionWindowStore(
    private val bucketLimit: Int,
    private val eventLimit: Int,
    private val pruneBatch: Int,
    private val windowNanos: Long,
    private val idleNanos: Long,
) {
    private val buckets = HashMap<ComplaintAdmissionBucketKey, Bucket>()
    private val expiry = TreeMap<Long, MutableSet<ComplaintAdmissionBucketKey>>()
    private var events = 0

    init {
        require(bucketLimit in 1..4096 && eventLimit in 1..245760 && pruneBatch in 1..128) { INVALID_ADMISSION_CONFIGURATION }
        require(windowNanos in 1..ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS) { INVALID_ADMISSION_CONFIGURATION }
        require(idleNanos in windowNanos..ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS) { INVALID_ADMISSION_CONFIGURATION }
    }

    fun charge(charges: List<ComplaintAdmissionCharge>, now: Long) {
        check(charges.size in 1..4 && charges.map { it.key }.distinct().size == charges.size)
        check(charges.all { it.limit in 1..120 })
        check(charges.none { it.window === ComplaintAdmissionWindow.DELETE_ALL_DAY } || windowNanos == ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS)
        val selected = charges.map { charge ->
            val daily = charge.window === ComplaintAdmissionWindow.DELETE_ALL_DAY
            val window = if (daily) ComplaintAdmissionPolicy.DELETE_ALL_WINDOW_NANOS else windowNanos
            val idle = if (daily) window else idleNanos
            PendingCharge(charge, window, Math.addExact(now, idle))
        }
        prune(now)
        var retryNanos = 0L
        selected.forEach { pending ->
            val charge = pending.charge
            val bucket = buckets[charge.key]
            if (bucket != null) {
                check(bucket.limit == charge.limit && bucket.window == pending.window)
                events -= bucket.prune(now, pending.window)
                if (bucket.size == bucket.limit) retryNanos = maxOf(retryNanos, pending.window - (now - bucket.first()))
            }
        }
        if (retryNanos > 0) {
            // Idle means last observed ingress attempt, including an exhausted existing bucket.
            if (idleNanos > windowNanos) selected.forEach { pending ->
                buckets[pending.charge.key]?.let { touch(pending.charge.key, it, pending.expiresAt) }
            }
            val seconds = (retryNanos + ComplaintAdmissionPolicy.SECOND_NANOS - 1) / ComplaintAdmissionPolicy.SECOND_NANOS
            throw ComplaintAdmissionRejected(ComplaintAdmissionFailure.RATE_LIMITED, seconds)
        }
        val missing = charges.count { it.key !in buckets }
        if (buckets.size + missing > bucketLimit || events + charges.size > eventLimit) refuseComplaintAdmission()
        // Allocate bounded new buckets before publishing or charging any live dimension.
        val allocated = selected.map { pending -> pending to (buckets[pending.charge.key] ?: Bucket(pending.charge.limit, pending.window)) }
        allocated.forEach { (pending, bucket) ->
            val charge = pending.charge
            buckets[charge.key] = bucket
            bucket.add(now)
            events += 1
            touch(charge.key, bucket, pending.expiresAt)
        }
    }

    fun removeGeneration(generation: String) {
        // Drained administrative retirement only; total traversal is bounded by bucketLimit.
        val selected = buckets.keys.filter { it.generation == generation }
        selected.forEach { key ->
            val bucket = checkNotNull(buckets.remove(key))
            unregister(key, bucket.expiresAt)
            events -= bucket.size
        }
    }

    private fun prune(now: Long) {
        var removed = 0
        while (removed < pruneBatch) {
            val oldest = expiry.firstEntry() ?: break
            if (oldest.key > now) break
            val key = oldest.value.first()
            val bucket = checkNotNull(buckets.remove(key))
            unregister(key, bucket.expiresAt)
            events -= bucket.size
            removed += 1
        }
        if (expiry.firstKeyOrNull()?.let { it <= now } == true) refuseComplaintAdmission()
    }

    private fun touch(key: ComplaintAdmissionBucketKey, bucket: Bucket, until: Long) {
        unregister(key, bucket.expiresAt)
        bucket.expiresAt = until
        expiry.getOrPut(until, ::LinkedHashSet).add(key)
    }

    private fun unregister(key: ComplaintAdmissionBucketKey, at: Long?) {
        if (at == null) return
        val registered = checkNotNull(expiry[at])
        check(registered.remove(key))
        if (registered.isEmpty()) expiry.remove(at)
    }

    private fun TreeMap<Long, MutableSet<ComplaintAdmissionBucketKey>>.firstKeyOrNull(): Long? = firstEntry()?.key

    override fun toString(): String = "ComplaintAdmissionWindowStore(redacted)"

    private class PendingCharge(val charge: ComplaintAdmissionCharge, val window: Long, val expiresAt: Long)

    private class Bucket(val limit: Int, val window: Long) {
        private val times = LongArray(limit)
        private var head = 0
        var size = 0
            private set
        var expiresAt: Long? = null

        fun first(): Long {
            check(size > 0)
            return times[head]
        }

        fun add(now: Long) {
            check(size < limit)
            times[(head + size) % limit] = now
            size += 1
        }

        fun prune(now: Long, window: Long): Int {
            var removed = 0
            while (size > 0 && now - first() >= window) {
                times[head] = 0
                head = (head + 1) % limit
                size -= 1
                removed += 1
            }
            return removed
        }
    }
}
