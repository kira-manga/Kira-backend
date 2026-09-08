package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ComplaintDailyAdmissionTest {
    @Test
    fun `first admission establishes the supplied database day and reaches the exact limit`() {
        val empty = ComplaintDailyAdmission(null, 0, 2)
        val first = empty.admitNewIdentity(17)
        assertEquals(ComplaintDailyAdmission(17, 1, 2), first)
        val second = first.admitNewIdentity(17)
        assertEquals(ComplaintDailyAdmission(17, 2, 2), second)
        assertFailure(ComplaintCapacityFailureCode.DAILY_LIMIT_REACHED) { second.admitNewIdentity(17) }
        assertEquals(ComplaintDailyAdmission(null, 0, 2), empty)
        assertEquals(ComplaintDailyAdmission(17, 1, 2), first)
    }

    @Test
    fun `a later day resets a full bucket but an equal or earlier day never does`() {
        val full = ComplaintDailyAdmission(17, 2, 2)
        for (day in listOf(17L, 16L, 0L, -1L, Long.MIN_VALUE)) {
            assertFailure(ComplaintCapacityFailureCode.DAILY_LIMIT_REACHED) { full.admitNewIdentity(day) }
        }
        assertEquals(ComplaintDailyAdmission(18, 1, 2), full.admitNewIdentity(18))
        assertEquals(ComplaintDailyAdmission(Long.MAX_VALUE, 1, 2), full.admitNewIdentity(Long.MAX_VALUE))
        assertEquals(ComplaintDailyAdmission(17, 2, 2), full)
    }

    @Test
    fun `repeated clock rollback retains both the highest date and accumulated admissions`() {
        var bucket = ComplaintDailyAdmission(30, 0, 4)
        for ((index, day) in listOf(29L, 28L, -1L, 30L).withIndex()) {
            bucket = bucket.admitNewIdentity(day)
            assertEquals(30L, bucket.utcEpochDay)
            assertEquals(index.toLong() + 1, bucket.count)
        }
        assertFailure(ComplaintCapacityFailureCode.DAILY_LIMIT_REACHED) { bucket.admitNewIdentity(29) }
        assertEquals(ComplaintDailyAdmission(31, 1, 4), bucket.admitNewIdentity(31))
    }

    @Test
    fun `negative epoch days are valid and compared without adding durations`() {
        val first = ComplaintDailyAdmission(null, 0, 2).admitNewIdentity(Long.MIN_VALUE)
        assertEquals(ComplaintDailyAdmission(Long.MIN_VALUE, 1, 2), first)
        assertEquals(ComplaintDailyAdmission(Long.MIN_VALUE, 2, 2), first.admitNewIdentity(Long.MIN_VALUE))
        val later = first.admitNewIdentity(-1)
        assertEquals(ComplaintDailyAdmission(-1, 1, 2), later)
        val final = later.admitNewIdentity(Long.MAX_VALUE)
        assertEquals(ComplaintDailyAdmission(Long.MAX_VALUE, 1, 2), final)
        assertEquals(ComplaintDailyAdmission(Long.MAX_VALUE, 2, 2), final.admitNewIdentity(Long.MIN_VALUE))
    }

    @Test
    fun `zero daily limit never admits even when the date advances`() {
        for (day in listOf<Long?>(null, 17)) {
            val bucket = ComplaintDailyAdmission(day, 0, 0)
            assertFailure(ComplaintCapacityFailureCode.DAILY_LIMIT_REACHED) { bucket.admitNewIdentity(18) }
            assertEquals(0L, bucket.count)
        }
    }

    @Test
    fun `invalid initial buckets reject negative values overlimit count and undated nonzero count`() {
        for (negative in listOf(-1L, Long.MIN_VALUE)) {
            assertFailure(ComplaintCapacityFailureCode.INVALID_DAILY_BUCKET) { ComplaintDailyAdmission(17, negative, 1) }
            assertFailure(ComplaintCapacityFailureCode.INVALID_DAILY_BUCKET) { ComplaintDailyAdmission(17, 0, negative) }
        }
        assertFailure(ComplaintCapacityFailureCode.INVALID_DAILY_BUCKET) { ComplaintDailyAdmission(null, 1, 2) }
        assertFailure(ComplaintCapacityFailureCode.INVALID_DAILY_BUCKET) { ComplaintDailyAdmission(17, 2, 1) }
        val valid = ComplaintDailyAdmission(17, 1, 1)
        assertFailure(ComplaintCapacityFailureCode.INVALID_DAILY_BUCKET) { valid.copy(utcEpochDay = null) }
        assertFailure(ComplaintCapacityFailureCode.INVALID_DAILY_BUCKET) { valid.copy(dailyLimit = 0) }
    }

    @Test
    fun `long maximum limit admits its final unit and rejects one more before overflow`() {
        val near = ComplaintDailyAdmission(17, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val full = near.admitNewIdentity(17)
        assertEquals(Long.MAX_VALUE, full.count)
        assertFailure(ComplaintCapacityFailureCode.DAILY_LIMIT_REACHED) { full.admitNewIdentity(17) }
        assertEquals(ComplaintDailyAdmission(18, 1, Long.MAX_VALUE), full.admitNewIdentity(18))
        assertEquals(Long.MAX_VALUE - 1, near.count)
    }

    @Test
    fun `daily diagnostics are value free`() {
        val bucket = ComplaintDailyAdmission(987654321, 123456789, 123456789)
        assertEquals("ComplaintDailyAdmission(redacted)", bucket.toString())
        val error = assertThrows(ComplaintCapacityException::class.java) { bucket.admitNewIdentity(987654321) }
        assertEquals("Complaint capacity rejected: DAILY_LIMIT_REACHED.", error.message)
    }

    private fun assertFailure(code: ComplaintCapacityFailureCode, action: () -> Unit) {
        assertEquals(code, assertThrows(ComplaintCapacityException::class.java, action).code)
    }
}
