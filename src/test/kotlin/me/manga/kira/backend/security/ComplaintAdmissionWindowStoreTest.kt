package me.manga.kira.backend.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Synthetic pseudonyms test only the counter engine; no actor authentication is inferred. */
class ComplaintAdmissionWindowStoreTest {
    @Test
    fun `session dimensions admit exact limits and denied IP never partially charges its actor`() {
        val store = store()
        (1..3).forEach { actor -> repeat(30) { store.charge(pair(actor, 1000), 0) } }
        repeat(10) { store.charge(pair(4, 1000), second(100)) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { store.charge(pair(4, 1000), second(100)) }
        repeat(20) { store.charge(pair(4, 1001), second(3600)) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { store.charge(pair(4, 1001), second(3600)) }
    }

    @Test
    fun `maximum retry across dimensions is rounded up and exact lower window boundary expires`() {
        val store = store()
        repeat(30) { store.charge(pair(1, 1000), 0) }
        (2..4).forEach { actor -> repeat(30) { store.charge(pair(actor, 1001), second(600)) } }
        repeat(10) { store.charge(pair(5, 1001), second(600)) }
        val denied = admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { store.charge(pair(1, 1001), second(600) + 1) }
        assertEquals(3600L, denied.retryAfterSeconds)
        val later = admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { store.charge(pair(1, 1001), second(3600)) }
        assertEquals(600L, later.retryAfterSeconds)
        store.charge(pair(1, 1001), second(4200))
    }

    @Test
    fun `physical bucket and event ceilings refuse without partial live increments`() {
        val buckets = store(buckets = 2)
        val first = listOf(charge(1, 2), charge(2, 2))
        buckets.charge(first, 0)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { buckets.charge(listOf(charge(3, 2), charge(2, 2)), 0) }
        buckets.charge(first, 0)
        val events = store(events = 3)
        events.charge(first, 0)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { events.charge(first, 0) }
        events.charge(listOf(charge(1, 2)), 0)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { events.charge(listOf(charge(2, 2)), 0) }
    }

    @Test
    fun `expired registry backlog is pruned in bounded batches and never evicts a live entry`() {
        val store = store(buckets = 4, prune = 1)
        (1..4).forEach { store.charge(listOf(charge(it, 2)), 0) }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { store.charge(listOf(charge(5, 2)), second(3599)) }
        repeat(3) { admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { store.charge(listOf(charge(5, 2)), second(3600)) } }
        store.charge(listOf(charge(5, 2)), second(3600))
        store.charge(listOf(charge(5, 2)), second(3600))
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { store.charge(listOf(charge(5, 2)), second(3600)) }
    }

    @Test
    fun `updated expiry registration cannot retain a stale entry that removes live quota`() {
        val store = ComplaintAdmissionWindowStore(1, 1, 1, second(60), second(120))
        val request = listOf(charge(1, 1))
        store.charge(request, 0)
        store.charge(request, second(61))
        val denied = admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { store.charge(request, second(120)) }
        assertEquals(1L, denied.retryAfterSeconds)
        store.charge(request, second(121))
    }

    @Test
    fun `mirrored generations are checked individually and old traffic cannot disappear during overlap`() {
        val store = store()
        val old = pair(1, 1000, "old")
        repeat(30) { store.charge(old, 0) }
        val both = pair(1, 1000, "current") + old
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { store.charge(both, second(1)) }
        repeat(30) { store.charge(both, second(3600)) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { store.charge(both, second(3600)) }
    }

    private fun pair(actor: Int, ip: Int, generation: String = "one"): List<ComplaintAdmissionCharge> =
        listOf(charge(actor, 30, generation), charge(ip, 100, generation))

    private fun charge(id: Int, limit: Int, generation: String = "one"): ComplaintAdmissionCharge =
        ComplaintAdmissionCharge(ComplaintAdmissionBucketKey(generation, id.toString(16).padStart(64, '0')), limit)

    private fun store(buckets: Int = 4096, events: Int = 131072, prune: Int = 128): ComplaintAdmissionWindowStore =
        ComplaintAdmissionWindowStore(buckets, events, prune, second(3600), second(3600))

    private fun second(value: Long): Long = value * ComplaintAdmissionPolicy.SECOND_NANOS
}
