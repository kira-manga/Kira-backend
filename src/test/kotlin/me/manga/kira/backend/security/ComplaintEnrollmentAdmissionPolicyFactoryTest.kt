package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Pure declaration comparisons against synthetic ledgers, not proof of locked rows or activation authority. */
class ComplaintEnrollmentAdmissionPolicyFactoryTest {
    @Test
    fun `factory derives digest installation threshold and daily limit from one validated policy`() {
        val capacity = capacityPolicy()
        val enrollment = ComplaintEnrollmentAdmissionPolicyFactory.fromCapacityPolicy(capacity, globalPerHour = 120)
        assertEquals(120, enrollment.globalPerHour)
        assertTrue(enrollment.matchesLocked(ledger(capacity), ComplaintDailyAdmission(null, 0L, 941L)))
        assertTrue(enrollment.matchesLocked(ledger(capacity), ComplaintDailyAdmission(123L, 55L, 941L)))
        assertEquals("ComplaintEnrollmentAdmissionPolicy.Bounded(redacted)", enrollment.toString())
    }

    @Test
    fun `derived enrollment binding refuses different digest installation threshold or daily limit`() {
        val capacity = capacityPolicy()
        val enrollment = ComplaintEnrollmentAdmissionPolicyFactory.fromCapacityPolicy(capacity, globalPerHour = 120)
        val daily = ComplaintDailyAdmission(null, 0L, capacity.dailyEnrollmentLimit)
        val differentDigest = capacity.digestBytes().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(enrollment.matchesLocked(ledger(capacity, digest = differentDigest), daily))
        val differentThreshold = capacity.creationLimit.with(ComplaintCapacityCounter.INSTALLATION_IDS, 257L)
        assertFalse(enrollment.matchesLocked(ledger(capacity, creationLimit = differentThreshold), daily))
        assertFalse(enrollment.matchesLocked(ledger(capacity), ComplaintDailyAdmission(null, 0L, 942L)))
    }

    @Test
    fun `changing another counter invalidates the old binding even when enrollment limits are unchanged`() {
        val original = capacityPolicy()
        val changed = ComplaintCapacityPolicyV1.of(
            original.hardLimit,
            original.creationLimit.with(ComplaintCapacityCounter.AUDIT_ROWS, 1L),
            original.dailyEnrollmentLimit,
        )
        val oldEnrollment = ComplaintEnrollmentAdmissionPolicyFactory.fromCapacityPolicy(original, globalPerHour = 120)
        val newEnrollment = ComplaintEnrollmentAdmissionPolicyFactory.fromCapacityPolicy(changed, globalPerHour = 120)
        val daily = ComplaintDailyAdmission(null, 0L, original.dailyEnrollmentLimit)
        assertFalse(oldEnrollment.matchesLocked(ledger(changed), daily))
        assertFalse(newEnrollment.matchesLocked(ledger(original), daily))
        assertTrue(newEnrollment.matchesLocked(ledger(changed), daily))
    }

    @Test
    fun `global quota has no default or clamp and must be strictly below both derived limits`() {
        listOf(-1, 0, 121, Int.MAX_VALUE).forEach { global ->
            assertThrows<IllegalArgumentException> {
                ComplaintEnrollmentAdmissionPolicyFactory.fromCapacityPolicy(capacityPolicy(), global)
            }
        }
        listOf(0L, 1L, 119L, 120L).forEach { bound ->
            assertThrows<IllegalArgumentException> {
                ComplaintEnrollmentAdmissionPolicyFactory.fromCapacityPolicy(capacityPolicy(installationLimit = bound), 120)
            }
            assertThrows<IllegalArgumentException> {
                ComplaintEnrollmentAdmissionPolicyFactory.fromCapacityPolicy(capacityPolicy(dailyLimit = bound), 120)
            }
        }
        listOf(1, 120).forEach { global ->
            val enrollment = ComplaintEnrollmentAdmissionPolicyFactory.fromCapacityPolicy(capacityPolicy(121L, 121L), global)
            assertEquals(global, enrollment.globalPerHour)
        }
        val maximum = capacityPolicy(Long.MAX_VALUE, Long.MAX_VALUE)
        val enrollment = ComplaintEnrollmentAdmissionPolicyFactory.fromCapacityPolicy(maximum, 120)
        assertTrue(enrollment.matchesLocked(ledger(maximum), ComplaintDailyAdmission(null, 0L, Long.MAX_VALUE)))
        assertThrows<IllegalArgumentException> {
            ComplaintEnrollmentAdmissionPolicyFactory.fromCapacityPolicy(
                ComplaintCapacityPolicyV1.of(ComplaintCapacityVector.ZERO, ComplaintCapacityVector.ZERO, 0L),
                1,
            )
        }
    }

    @Test
    fun `P binding refuses every declaration drift even when the stored digest is unchanged`() {
        val capacity = capacityPolicy()
        val enrollment = ComplaintEnrollmentAdmissionPolicyFactory.fromCapacityPolicy(capacity, 120)
        val daily = ComplaintDailyAdmission(null, 0L, capacity.dailyEnrollmentLimit)
        ComplaintCapacityCounter.entries.forEach { counter ->
            val hard = capacity.hardLimit.with(counter, Long.MAX_VALUE - 1L)
            assertFalse(enrollment.matchesLocked(ledger(capacity, hardLimit = hard), daily), "hard $counter")
            val creation = capacity.creationLimit.with(counter, capacity.creationLimit[counter] + 1L)
            assertFalse(enrollment.matchesLocked(ledger(capacity, creationLimit = creation), daily), "creation $counter")
        }
    }

    @Test
    fun `P binding excludes balanced consumption day and creation closed observations`() {
        val capacity = capacityPolicy()
        val enrollment = ComplaintEnrollmentAdmissionPolicyFactory.fromCapacityPolicy(capacity, 120)
        val unit = ComplaintCapacityVector.units(ComplaintCapacityCounter.AUDIT_ROWS, 1L)
        val observed = ComplaintCapacityBalance(
            capacity.hardLimit,
            capacity.creationLimit,
            capacity.hardLimit - unit - unit - unit,
            actual = unit,
            recoveryReserved = unit,
            testReserved = unit,
        )
        listOf(false, true).forEach { closed ->
            val ledger = ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(capacity.digestBytes(), closed), observed)
            assertTrue(enrollment.matchesLocked(ledger, ComplaintDailyAdmission(123L, 941L, capacity.dailyEnrollmentLimit)))
        }
    }

    private fun capacityPolicy(installationLimit: Long = 256L, dailyLimit: Long = 941L): ComplaintCapacityPolicyV1 = ComplaintCapacityPolicyV1.of(
        hardLimit = ComplaintCapacityVector.of(LongArray(22) { Long.MAX_VALUE }),
        creationLimit = ComplaintCapacityVector.ZERO.with(ComplaintCapacityCounter.INSTALLATION_IDS, installationLimit),
        dailyEnrollmentLimit = dailyLimit,
    )

    private fun ledger(
        capacity: ComplaintCapacityPolicyV1,
        creationLimit: ComplaintCapacityVector = capacity.creationLimit,
        digest: ByteArray = capacity.digestBytes(),
        hardLimit: ComplaintCapacityVector = capacity.hardLimit,
    ): ComplaintCapacityLedger = ComplaintCapacityLedger(
        ComplaintCapacityConfiguration.of(digest, creationClosed = true),
        ComplaintCapacityBalance(hardLimit, creationLimit, hardLimit),
    )
}
