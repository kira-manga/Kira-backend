package me.manga.kira.backend.complaint.infrastructure.reconciliation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Mapper cleanup only: these synthetic ResultSets never issue released work/provider/COMMIT proof. */
class TestActiveDetachedRowCleanupV1Test {
    @Test fun failedPreparedRowMappingWipesEarlierCanonicalAllocation() {
        val canonical = ByteArray(40) { 7 }
        val row = publication("PREPARED", mapOf("event_bytes" to canonical), "semantic_hash")
        assertThrows<SQLException> { TestActiveCutoffPublicationRowV1.read(row) }
        assertTrue(canonical.all { it == 0.toByte() })
    }

    @Test fun failedProofMappingWipesDetachedCanonicalSemanticAndProofBuffers() {
        val canonical = ByteArray(40) { 7 }; val semantic = ByteArray(32) { 8 }
        val cipher = ByteArray(32) { 9 }; val proof = ByteArray(80) { 10 }
        val row = publication("VERIFIED", mapOf("event_bytes" to canonical, "semantic_hash" to semantic,
            "ciphertext_hash" to cipher, "verification_bytes" to proof), "verification_hash")
        assertThrows<SQLException> { TestActiveCutoffPublicationRowV1.read(row) }
        listOf(canonical, semantic, cipher, proof).forEach { bytes -> assertTrue(bytes.all { it == 0.toByte() }) }
    }

    @Test fun failedControlMappingWipesAlreadyAllocatedCanonicalAndWireDigests() {
        val canonical = ByteArray(40) { 7 }; val hash = ByteArray(32) { 8 }; val wire = ByteArray(32) { 9 }
        val row = resultSet(mapOf("valid" to true, "seal_state" to "SEAL_VERIFIED", "seal_epoch" to 1L,
            "seal_writer_generation" to UUID.randomUUID(), "seal_operation_token" to UUID.randomUUID(), "seal_object_key" to "comparison-only",
            "seal_bytes" to canonical, "seal_hash" to hash, "seal_object_version" to "comparison-only",
            "seal_ciphertext_hash" to wire, "seal_retain_until" to TIME, "seal_verified_at" to TIME), "seal_verification_bytes")
        assertThrows<SQLException> { TestActiveOrdinarySealRowsV1.Control.read(row) }
        listOf(canonical, hash, wire).forEach { bytes -> assertTrue(bytes.all { it == 0.toByte() }) }
    }

    private fun publication(state: String, buffers: Map<String, ByteArray>, failure: String) = resultSet(mapOf(
        "valid" to true, "test_only" to true, "state" to state, "event_id" to "A".repeat(43),
        "data_scope_id" to UUID.randomUUID(), "writer_generation" to UUID.randomUUID(), "journal_epoch" to 1L,
        "event_kind" to "OWNER_DELETE", "target_count" to 1, "routing_key_id" to "comparison-only", "object_key" to "comparison-only",
        "created_at" to TIME, "object_version" to "comparison-only", "object_created_at" to TIME, "retain_until" to TIME, "verified_at" to TIME,
    ) + buffers, failure)

    private fun resultSet(values: Map<String, Any>, failure: String): ResultSet = Proxy.newProxyInstance(
        ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java),
    ) { _, method, args ->
        if (method.name == "wasNull") false
        else {
            val key = args?.firstOrNull() as? String ?: error("Unexpected mapper-only JDBC call.")
            if (key == failure) throw SQLException("Synthetic row mapping failure.")
            when (method.name) {
                "getString", "getLong", "getInt", "getBoolean", "getBytes", "getTimestamp", "getObject" -> checkNotNull(values[key])
                else -> error("Unexpected mapper-only JDBC method.")
            }
        }
    } as ResultSet

    companion object { private val TIME = Timestamp.from(Instant.parse("2030-01-01T00:00:00Z")) }
}
