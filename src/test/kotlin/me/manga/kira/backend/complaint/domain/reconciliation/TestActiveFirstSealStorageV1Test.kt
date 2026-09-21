package me.manga.kira.backend.complaint.domain.reconciliation

import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityException
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Independent closed-source sizing/accounting, not PostgreSQL/index/TOAST/disk qualification. */
internal class TestActiveFirstSealStorageV1Test {
    @Test
    fun `entire forty nine column slot and all four indexes fit the independently paid two MiB envelope`() {
        val fixed = linkedMapOf(
            "schema_version" to 2, "operation_token" to 16, "data_scope_id" to 16, "test_only" to 1,
            "run_created_at" to 8, "implementation_schema" to 4, "desired_generation" to 8,
            "database_identity" to 16, "restore_identity" to 16, "writer_generation" to 16,
            "activation_catalog_generation" to 8, "accepted_catalog_generation" to 8, "catalog_writer_generation" to 16,
            "rotation_sequence" to 8, "epoch_start" to 8, "epoch_end" to 8, "request_owner" to 16, "request_token" to 8,
            "requested_at" to 8, "charged_storage_bytes" to 8, "capture_owner" to 16, "capture_token" to 8, "captured_at" to 8,
            "epoch_after" to 8, "preparing_fencing_token" to 8, "retention_floor" to 8, "created_at" to 8,
            "retain_until" to 8, "frozen_at" to 8,
        )
        val variable = linkedMapOf(
            "configuration_hash" to 32, "journal_configuration_hash" to 32, "activation_catalog_hash" to 32,
            "accepted_catalog_hash" to 32, "trust_bundle_hash" to 32, "state" to 16,
            "object_id" to 43, "object_key" to 1024, "routing_key_id" to 64, "seal_encoding_hash" to 32,
            "canonicalizer" to 16, "canonical_bytes" to 65536, "canonical_hash" to 32,
            "wire_bytes" to 98304, "wire_hash" to 32, "checksum_sha256" to 44, "content_type" to 24,
            "object_lock_mode" to 10, "metadata_bytes" to 512, "metadata_hash" to 32,
        )
        val sql = checkNotNull(javaClass.getResourceAsStream("/db/migration/V26__test_active_first_seal_slot.sql")).use { it.readBytes().decodeToString() }
        val declaration = sql.substringAfter("CREATE TABLE complaint_test_active_seal_intents (").substringBefore("    CONSTRAINT")
        val lines = declaration.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val columns = lines.associate { it.substringBefore(' ') to it.substringAfter(' ').substringBefore(' ').removeSuffix(",") }
        assertEquals(29, fixed.size); assertEquals(20, variable.size)
        assertEquals(49, lines.size); assertEquals(fixed.keys + variable.keys, columns.keys)
        val timestamps = setOf("run_created_at", "requested_at", "captured_at", "retention_floor", "created_at", "retain_until", "frozen_at")
        fixed.forEach { (name, bytes) ->
            assertEquals(when {
                name in timestamps -> "timestamptz"
                bytes == 16 -> "uuid"
                bytes == 8 -> "bigint"
                bytes == 4 -> "integer"
                bytes == 2 -> "smallint"
                else -> "boolean"
            }, columns[name], name)
        }
        variable.forEach { (name, bytes) ->
            assertEquals(when {
                name.endsWith("_hash") || name.endsWith("_bytes") -> "bytea"
                name == "object_key" -> "text"
                else -> "varchar($bytes)"
            }, columns[name], name)
        }
        listOf(
            "complaint_ascii_valid(object_key, 1024)",
            "complaint_bytes_match(canonical_bytes, canonical_hash, 65536)",
            "complaint_bytes_match(wire_bytes, wire_hash, 98304)",
            "complaint_bytes_match(metadata_bytes, metadata_hash, 512)",
        ).forEach { assertTrue(sql.contains(it), "Every unbounded SQL byte/text type needs the exact closed checked ceiling: $it") }
        val padded = fixed.values.sumOf { pad(it.toLong()) }
        val varying = variable.values.sumOf { pad(it + 4L) }
        assertEquals(296L, padded); assertEquals(166_032L, varying)
        assertEquals(166_360L, 32 + padded + varying)
        val indexes = listOf(24 + 16 + 8L, 24 + 16 + 8L, 24 + pad(1024 + 4L) + 8, 24 + pad(43 + 4L) + 8)
        assertEquals(listOf(48L, 48L, 1064L, 80L), indexes)
        assertEquals(1, Regex("PRIMARY KEY \\(").findAll(sql).count())
        assertEquals(3, Regex("UNIQUE \\(").findAll(sql).count())
        val envelope = 8 * (32 + padded + varying + indexes.sum())
        assertEquals(1_340_800L, envelope)
        assertEquals(envelope, TestActiveFirstSealStorageV1.LOGICAL_ENVELOPE_BYTES)
        assertEquals(756_352L, TestActiveFirstSealStorageV1.STORAGE_BYTES - envelope)
        assertTrue(envelope < TestActiveFirstSealStorageV1.STORAGE_BYTES)
        assertEquals(TestTerminalProfileV1.encodingSha256, TestActiveFirstSealStorageV1.sealEncodingSha256)
    }

    @Test
    fun `full price uses ordinary headroom and leaves both reserves and all other dimensions untouched`() {
        val hash = ByteArray(32) { 7 }
        val price = TestActiveFirstSealStorageV1.ROW
        val storage = ComplaintCapacityCounter.STORAGE_BYTES
        val hard = ComplaintCapacityVector.of(LongArray(22) { 10_000_000 })
        val actual = ComplaintCapacityVector.of(LongArray(22) { 101 })
        val recovery = ComplaintCapacityVector.of(LongArray(22) { 202 })
        val reserve = ComplaintCapacityVector.of(LongArray(22) { 303 })
        val used = actual + recovery + reserve
        val limit = hard.with(storage, used[storage] + TestActiveFirstSealStorageV1.STORAGE_BYTES)
        val before = ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(hash, false),
            ComplaintCapacityBalance(hard, limit, hard - used, actual, recovery, reserve))
        val after = before.chargeCreation(hash, price).balance
        assertEquals(22, price.toLongArray().size)
        assertEquals(1, price.toLongArray().count { it != 0L })
        assertEquals(2_097_152L, price[storage])
        assertEquals(before.balance.free - price, after.free)
        assertEquals(before.balance.actual + price, after.actual)
        assertEquals(recovery, after.recoveryReserved); assertEquals(reserve, after.testReserved)
        assertEquals(hard, after.hardLimit); assertEquals(limit, after.creationLimit)
        val short = ComplaintCapacityLedger(before.configuration, before.balance.copy(creationLimit = limit.with(storage, limit[storage] - 1)))
        assertThrows<ComplaintCapacityException> { short.chargeCreation(hash, price) }
        assertThrows<ComplaintCapacityException> {
            ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(hash, true), before.balance).chargeCreation(hash, price)
        }
    }

    private fun pad(bytes: Long): Long = ((bytes + 7) / 8) * 8
}
