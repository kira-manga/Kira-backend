package me.manga.kira.backend.complaint.domain.reconciliation

import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityException
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Logical source arithmetic, not a claim that PostgreSQL/TOAST/index pages or durable pricing ran. */
internal class TestActiveInitialCheckpointStorageV1Test {
    @Test fun allNineteenPopulatedColumnsAndFourIndexesPriceExactly4416WithoutChangingLegacy3776() {
        val v14 = resource("V14__backend_owned_complaints.sql")
        val v27 = resource("V27__test_active_initial_checkpoint_scan_ownership.sql")
        val fixed = mapOf("scan_id" to 16, "pass" to 2, "data_scope_id" to 16, "test_only" to 1,
            "restore_identity" to 16, "desired_generation" to 8, "fencing_token" to 8, "writer_generation" to 16,
            "cutoff_epoch" to 8, "maximum_entries" to 8, "maximum_bytes" to 8, "entry_count" to 8, "entry_bytes" to 8,
            "started_at" to 8, "finished_at" to 8)
        val oldColumns = v14.substringAfter("CREATE TABLE complaint_journal_scan_runs (").substringBefore("    CONSTRAINT")
            .lineSequence().map(String::trim).filter(String::isNotEmpty).map { it.substringBefore(' ') }.toSet()
        assertEquals(fixed.keys + setOf("state", "manifest_hash"), oldColumns)
        assertEquals(17, oldColumns.size)
        val oldFixed = fixed.values.sumOf { pad(it.toLong()) }
        val variable = pad(16 + 4L) + pad(32 + 4L)
        val oldIndexes = listOf(32 + 16 + 8L, 32 + 16 + 8 + 16L, 32 + 16 + pad(16 + 4L) + 16 + 8)
        val newHeap = 32 + oldFixed + 16 + 8 + variable
        val newIndexes = oldIndexes.sum() + 32 + 16 + 8
        assertEquals(152L, oldFixed); assertEquals(64L, variable)
        assertEquals(248L, 32 + oldFixed + variable); assertEquals(224L, oldIndexes.sum())
        assertEquals(3776L, 8 * (32 + oldFixed + variable + oldIndexes.sum()))
        assertEquals(272L, newHeap); assertEquals(280L, newIndexes)
        assertEquals(4416L, 8 * (newHeap + newIndexes)); assertEquals(8832L, 2 * TestActiveInitialCheckpointStorageV1.STORAGE_BYTES)
        assertEquals(newHeap, TestActiveInitialCheckpointStorageV1.MAX_HEAP_ROW_BYTES)
        assertEquals(newIndexes, TestActiveInitialCheckpointStorageV1.MAX_INDEX_BYTES)
        assertTrue(v27.contains("ADD COLUMN active_initial_seal_token uuid"))
        assertTrue(v27.contains("ADD COLUMN active_initial_storage_bytes bigint"))
        assertTrue(v27.contains("WHERE active_initial_seal_token IS NOT NULL"))
        assertTrue(v27.contains("REFERENCES complaint_test_active_seal_intents (operation_token)"))
        assertTrue(v27.contains("active_initial_storage_bytes = 4416"))
    }

    @Test fun exactPairHeadroomChargesAndRefundsOnlyOrdinaryActualWithNoTerminalReserveBorrowing() {
        val hash = ByteArray(32) { 9 }
        val pair = TestActiveInitialCheckpointStorageV1.rows(2)
        val hard = ComplaintCapacityVector.of(LongArray(22) { 10_000_000 })
        val actual = ComplaintCapacityVector.of(LongArray(22) { 101 })
        val recovery = ComplaintCapacityVector.of(LongArray(22) { 202 })
        val terminal = ComplaintCapacityVector.of(LongArray(22) { 303 })
        val used = actual + recovery + terminal
        val limit = hard.with(ComplaintCapacityCounter.SCAN_RUNS, used[ComplaintCapacityCounter.SCAN_RUNS] + 2)
            .with(ComplaintCapacityCounter.STORAGE_BYTES, used[ComplaintCapacityCounter.STORAGE_BYTES] + 8832)
        val before = ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(hash, false),
            ComplaintCapacityBalance(hard, limit, hard - used, actual, recovery, terminal))
        val first = before.chargeCreation(hash, TestActiveInitialCheckpointStorageV1.ROW)
        val second = first.chargeCreation(hash, TestActiveInitialCheckpointStorageV1.ROW)
        assertEquals(actual + pair, second.balance.actual); assertEquals(before.balance.free - pair, second.balance.free)
        assertEquals(terminal, second.balance.testReserved); assertEquals(recovery, second.balance.recoveryReserved)
        assertEquals(before.balance, second.refundActual(hash, pair).balance)
        assertEquals(2, pair.toLongArray().count { it != 0L })
        assertEquals(0L, pair[ComplaintCapacityCounter.SCAN_ENTRIES])
        assertEquals(ComplaintCapacityVector.ZERO, TestActiveInitialCheckpointStorageV1.rows(0))
        for (dimension in listOf(ComplaintCapacityCounter.SCAN_RUNS, ComplaintCapacityCounter.STORAGE_BYTES)) {
            val short = ComplaintCapacityLedger(before.configuration, before.balance.copy(creationLimit = limit.with(dimension, limit[dimension] - 1)))
            assertThrows<ComplaintCapacityException> { short.chargeCreation(hash, pair) }
        }
        assertThrows<IllegalArgumentException> { TestActiveInitialCheckpointStorageV1.rows(-1) }
        assertThrows<IllegalArgumentException> { TestActiveInitialCheckpointStorageV1.rows(3) }
        assertThrows<ComplaintCapacityException> { before.refundActual(hash, pair) }
    }

    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/db/migration/$name")).use { it.readBytes().decodeToString() }
    private fun pad(value: Long): Long = 8 * ((value + 7) / 8)
}
