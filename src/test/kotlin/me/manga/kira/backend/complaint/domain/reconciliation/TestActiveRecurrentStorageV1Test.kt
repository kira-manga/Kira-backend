package me.manga.kira.backend.complaint.domain.reconciliation

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityException
import me.manga.kira.backend.complaint.domain.ComplaintCapacityFailureCode
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Logical envelope/accounting comparisons only, not PostgreSQL physical-size qualification. */
internal class TestActiveRecurrentStorageV1Test {
    @Test fun recurrentIntentAndEveryPhysicalHistoryRowAreSeparateOrdinaryCharges() {
        val s = TestActiveRecurrentStorageV1
        assertEquals(2_097_152L, TestActiveFirstSealStorageV1.STORAGE_BYTES)
        assertEquals(2_097_152L, s.INTENT_STORAGE_BYTES)
        assertEquals(2_097_152L, s.HISTORY_STORAGE_BYTES)
        assertTrue(s.INTENT_LOGICAL_ENVELOPE_BYTES <= s.INTENT_STORAGE_BYTES)
        assertTrue(s.HISTORY_LOGICAL_ENVELOPE_BYTES <= s.HISTORY_STORAGE_BYTES)
        val firstRecurrence = s.INTENT + s.HISTORY.scaled(2) // V26 archive and current V31 history.
        assertEquals(6_291_456L, firstRecurrence[ComplaintCapacityCounter.STORAGE_BYTES])
        assertEquals(4_194_304L, (s.INTENT + s.HISTORY)[ComplaintCapacityCounter.STORAGE_BYTES])
        ComplaintCapacityCounter.entries.filter { it != ComplaintCapacityCounter.STORAGE_BYTES }.forEach {
            assertEquals(0L, firstRecurrence[it], "No terminal reserve or non-storage permanent charge.")
        }
    }

    @Test fun twoRunAndEveryVersionPricesHaveExactRefundArithmeticAndNoNegativeOrThirdRunBranch() {
        val s = TestActiveRecurrentStorageV1
        assertEquals(4_416L, s.SCAN_RUN_STORAGE_BYTES)
        assertEquals(37_696L, s.SCAN_ENTRY_STORAGE_BYTES)
        val paid = s.scanCharge(2, 6)
        assertEquals(2L, paid[ComplaintCapacityCounter.SCAN_RUNS])
        assertEquals(6L, paid[ComplaintCapacityCounter.SCAN_ENTRIES])
        assertEquals(235_008L, paid[ComplaintCapacityCounter.STORAGE_BYTES])
        assertEquals(s.scanCharge(1, 3).scaled(2), paid)
        assertEquals(ComplaintCapacityVector.ZERO, s.scanCharge(0, 0))
        assertThrows<IllegalArgumentException> { s.scanCharge(-1, 0) }
        assertThrows<IllegalArgumentException> { s.scanCharge(3, 0) }
        assertThrows<IllegalArgumentException> { s.scanCharge(1, -1) }
        assertEquals(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW,
            assertThrows<ComplaintCapacityException> { s.scanCharge(2, Long.MAX_VALUE) }.code)
    }

    @Test fun activeBoundaryKeepsBothRequiredTerminalSlotsAndInitialPricesUntouched() {
        assertEquals(14, TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS)
        assertEquals(13, TestActiveRecurrentStorageV1.MAX_RECURRENT_INTENTS)
        assertEquals(16, TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS + 2)
        assertEquals(32, TestActiveRecurrentStorageV1.PAGE_ROWS)
        assertEquals(900_000L, TestActiveRecurrentCheckpointDocumentV1.MAX_REFRESH_MILLIS)
        assertEquals(1_200_000L, TestActiveRecurrentCheckpointDocumentV1.MAX_GATE_AGE_MILLIS)
    }
}
