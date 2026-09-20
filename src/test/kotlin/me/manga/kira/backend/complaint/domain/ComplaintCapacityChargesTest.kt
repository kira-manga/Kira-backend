package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ComplaintCapacityChargesTest {
    @Test
    fun `owner create pays normal receipt resource report and audit from actual capacity not test reserve`() {
        val expected = LongArray(22).also {
            it[ComplaintCapacityCounter.NORMAL_RECEIPTS.storedOrdinal - 1] = 1
            it[ComplaintCapacityCounter.RESOURCE_IDS.storedOrdinal - 1] = 1
            it[ComplaintCapacityCounter.COMPLAINT_ROWS.storedOrdinal - 1] = 1
            it[ComplaintCapacityCounter.AUDIT_ROWS.storedOrdinal - 1] = 1
            it[ComplaintCapacityCounter.STORAGE_BYTES.storedOrdinal - 1] = 475136
        }
        assertArrayEquals(expected, ComplaintCapacityCharges.OWNER_CREATE.toLongArray())
        assertEquals(131072L, ComplaintCapacityCharges.NORMAL_RECEIPT[ComplaintCapacityCounter.STORAGE_BYTES])
        assertEquals(16384L, ComplaintCapacityCharges.RESOURCE_ID[ComplaintCapacityCounter.STORAGE_BYTES])
        assertEquals(262144L, ComplaintCapacityCharges.REPORT_CONTENT[ComplaintCapacityCounter.STORAGE_BYTES])
        ComplaintCapacityCharges.OWNER_CREATE.toLongArray().fill(0)
        assertArrayEquals(expected, ComplaintCapacityCharges.OWNER_CREATE.toLongArray())
    }

    @Test
    fun `terminal create rejection retains only the same maximum normal receipt charge`() {
        val digest = ByteArray(32) { 7 }
        val limit = ComplaintCapacityVector.of(LongArray(22) { 1000000 })
        val initial = ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(digest, false), ComplaintCapacityBalance(limit, limit, limit))
        val retained = initial.chargeCreation(digest, ComplaintCapacityCharges.OWNER_CREATE)
            .refundActual(digest, ComplaintCapacityCharges.OWNER_CREATE - ComplaintCapacityCharges.NORMAL_RECEIPT)
        assertEquals(ComplaintCapacityCharges.NORMAL_RECEIPT, retained.balance.actual)
        assertEquals(initial.balance.testReserved, retained.balance.testReserved)
        assertEquals(initial.balance.recoveryReserved, retained.balance.recoveryReserved)
        assertEquals(initial.balance, retained.refundActual(digest, ComplaintCapacityCharges.NORMAL_RECEIPT).balance)
    }

    @Test
    fun `version one grant charges exactly one moderation row and sixteen KiB`() {
        val expected = LongArray(22).also {
            it[14] = 1
            it[20] = 16384
        }
        assertArrayEquals(expected, ComplaintCapacityCharges.MODERATION_GRANT.toLongArray())
    }

    @Test
    fun `version one audit charges exactly one audit row and sixty four KiB`() {
        val expected = LongArray(22).also {
            it[1] = 1
            it[20] = 65536
        }
        assertArrayEquals(expected, ComplaintCapacityCharges.AUDIT.toLongArray())
        assertEquals(2048, ComplaintCapacityCharges.MAX_AUDIT_PAYLOAD_BYTES)
    }

    @Test
    fun `enrollment pays for one permanent identity one lifecycle max credential and one enrolled audit`() {
        val expected = LongArray(22).also {
            it[0] = 1
            it[1] = 1
            it[7] = 1
            it[20] = 98304
        }
        assertArrayEquals(expected, ComplaintCapacityCharges.INSTALLATION_ENROLLMENT.toLongArray())
        assertEquals(16384L, ComplaintCapacityCharges.INSTALLATION_ID[ComplaintCapacityCounter.STORAGE_BYTES])
        assertEquals(16384L, ComplaintCapacityCharges.INSTALLATION_CREDENTIAL[ComplaintCapacityCounter.STORAGE_BYTES])
        ComplaintCapacityCharges.INSTALLATION_ENROLLMENT.toLongArray().fill(0)
        assertArrayEquals(expected, ComplaintCapacityCharges.INSTALLATION_ENROLLMENT.toLongArray())
    }

    @Test
    fun `future credential physical removal cannot refund the permanent identity or enrolled audit`() {
        val digest = ByteArray(32) { 1 }
        val limit = ComplaintCapacityVector.of(LongArray(22) { 1000000 })
        val initial = ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(digest, false), ComplaintCapacityBalance(limit, limit, limit))
        val retained = initial.chargeCreation(digest, ComplaintCapacityCharges.INSTALLATION_ENROLLMENT)
            .refundActual(digest, ComplaintCapacityCharges.INSTALLATION_CREDENTIAL)
        assertEquals(ComplaintCapacityCharges.INSTALLATION_ID + ComplaintCapacityCharges.AUDIT, retained.balance.actual)
    }

    @Test
    fun `bounded cleanup batch charges multiply every affected dimension without adding other rows`() {
        val grants = LongArray(22).also {
            it[14] = 50
            it[20] = 819200
        }
        val audits = LongArray(22).also {
            it[1] = 50
            it[20] = 3276800
        }
        assertArrayEquals(grants, ComplaintCapacityCharges.MODERATION_GRANT.scaled(50).toLongArray())
        assertArrayEquals(audits, ComplaintCapacityCharges.AUDIT.scaled(50).toLongArray())
    }

    @Test
    fun `maximum representable batch respects storage dimension and one more is rejected`() {
        for ((charge, rowIndex, storageBytes) in listOf(
            Triple(ComplaintCapacityCharges.MODERATION_GRANT, 14, 16384L),
            Triple(ComplaintCapacityCharges.AUDIT, 1, 65536L),
        )) {
            val maximumCount = Long.MAX_VALUE / storageBytes
            val expected = LongArray(22).also {
                it[rowIndex] = maximumCount
                it[20] = maximumCount * storageBytes
            }
            assertArrayEquals(expected, charge.scaled(maximumCount).toLongArray())
            assertEquals(
                ComplaintCapacityFailureCode.AMOUNT_OVERFLOW,
                assertThrows(ComplaintCapacityException::class.java) { charge.scaled(maximumCount + 1) }.code,
            )
        }
    }

    @Test
    fun `charge snapshots cannot be modified through an exported array`() {
        ComplaintCapacityCharges.MODERATION_GRANT.toLongArray().fill(0)
        ComplaintCapacityCharges.AUDIT.toLongArray().fill(0)
        assertEquals(16384L, ComplaintCapacityCharges.MODERATION_GRANT[ComplaintCapacityCounter.STORAGE_BYTES])
        assertEquals(65536L, ComplaintCapacityCharges.AUDIT[ComplaintCapacityCounter.STORAGE_BYTES])
    }

    @Test
    fun `physical deletion refund uses the same catalogue and returns every charged unit`() {
        val digest = ByteArray(32) { 1 }
        val limit = ComplaintCapacityVector.of(LongArray(22) { 1000000 })
        val initial = ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(digest, false), ComplaintCapacityBalance(limit, limit, limit))
        val charged = initial.chargeCreation(digest, ComplaintCapacityCharges.MODERATION_GRANT)
            .chargeCreation(digest, ComplaintCapacityCharges.AUDIT)
        assertEquals(81920L, charged.balance.actual[ComplaintCapacityCounter.STORAGE_BYTES])
        val refunded = charged.refundActual(digest, ComplaintCapacityCharges.MODERATION_GRANT)
            .refundActual(digest, ComplaintCapacityCharges.AUDIT)
        assertEquals(initial.balance, refunded.balance)
        assertEquals(ComplaintCapacityVector.ZERO, refunded.balance.actual)
    }
}
