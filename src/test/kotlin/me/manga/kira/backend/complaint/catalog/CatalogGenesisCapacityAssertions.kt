package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import java.util.HexFormat
import java.util.UUID

/** Synthetic maximal V14/G1 storage shape only. It deliberately is NOT a canonical or genuinely signed genesis document. */
internal fun assertGenesisLifecycleCapacity(observer: JdbcTemplate, token: UUID) {
    assertGenesisColumns(observer)
    assertGenesisIndexes(observer)
    val approvals = ByteArray(4096) { 97 }
    val document = ByteArray(CatalogGenesisCapacity.MAX_DOCUMENT_BYTES) { 98 }
    val evidence = ByteArray(65536) { 99 }
    val signature = ByteArray(1024) { 100 }
    fun hash(bytes: ByteArray): ByteArray = HexFormat.of().parseHex(Sha256.hex(bytes))
    observer.update(
        "UPDATE complaint_catalog_mutations SET approval_bytes = ?, approval_hash = ?, unsigned_bytes = ?, unsigned_hash = ?, " +
            "signer_one_id = repeat('k', 128), signer_one_algorithm = repeat('a', 128), signer_one_signature = ?, " +
            "envelope_bytes = ?, envelope_hash = ?, object_key = repeat('k', 1024), object_version = repeat('v', 1024), " +
            "retain_until = created_at, primary_evidence_bytes = ?, primary_evidence_hash = ?, replica_evidence_bytes = ?, replica_evidence_hash = ?, " +
            "state = 'COMPLETED', completed_at = created_at, projected_at = created_at WHERE operation_token = ?",
        approvals, hash(approvals), document, hash(document), signature, document, hash(document), evidence, hash(evidence), evidence, hash(evidence), token,
    )
    // Explicit concatenation detoasts each varlena before composite sizing. TOAST pointers/compression
    // must not masquerade as evidence for the full future logical row or the object-key index tuple.
    val columns = GENESIS_COLUMNS.keys.joinToString(", ") { name ->
        when (GENESIS_COLUMNS.getValue(name)) {
            "bytea" -> "$name || ''::bytea"
            "text", "character varying(64)", "character varying(16)", "character varying(24)", "character varying(128)" -> "$name || ''::text"
            else -> name
        }
    }
    val sizes = observer.queryForObject(
        "SELECT pg_column_size(ROW($columns)), pg_column_size(ROW(operation_token)), pg_column_size(ROW(successor_generation)), " +
            "pg_column_size(ROW(object_key || ''::text)), pg_column_size(ROW(1)), pg_column_size(ROW(data_scope_id, successor_generation)) " +
            "FROM complaint_catalog_mutations WHERE operation_token = ?",
        { row, _ -> (1..6).map { row.getLong(it) } },
        token,
    )!!
    assertTrue(sizes.first() >= 2L * (CatalogGenesisCapacity.MAX_DOCUMENT_BYTES + 65536), "Both full documents and both evidence buffers must be inlined.")
    assertTrue(sizes.first() <= CatalogGenesisCapacity.maximumHeapTupleBytes)
    sizes.drop(1).zip(CatalogGenesisCapacity.maximumIndexTupleBytes).forEach { (actual, bound) -> assertTrue(actual in 1..bound) }
    assertTrue(CatalogGenesisCapacity.storageBytes >= sizes.sum() * 8)
    assertEquals(33, GENESIS_COLUMNS.size)
}

private fun assertGenesisColumns(observer: JdbcTemplate) {
    val actual = observer.query(
        "SELECT attname, format_type(atttypid, atttypmod) FROM pg_attribute " +
            "WHERE attrelid = 'complaint_catalog_mutations'::regclass AND attnum > 0 AND NOT attisdropped ORDER BY attnum",
        { row, _ -> row.getString(1) to row.getString(2) },
    )
    assertEquals(GENESIS_COLUMNS.entries.map { it.key to it.value }, actual)
}

private fun assertGenesisIndexes(observer: JdbcTemplate) {
    val indexes = observer.query(
        "SELECT idx.relname, string_agg(pg_get_indexdef(i.indexrelid, n, true), ',' ORDER BY n), " +
            "pg_get_expr(i.indpred, i.indrelid), i.indisunique, i.indnatts = i.indnkeyatts, am.amname " +
            "FROM pg_index i JOIN pg_class idx ON idx.oid = i.indexrelid JOIN pg_am am ON idx.relam = am.oid " +
            "CROSS JOIN LATERAL generate_series(1, i.indnkeyatts) n WHERE i.indrelid = 'complaint_catalog_mutations'::regclass " +
            "GROUP BY idx.relname, pg_get_expr(i.indpred, i.indrelid), i.indisunique, i.indnatts, i.indnkeyatts, am.amname ORDER BY idx.relname",
        { row, _ ->
            val name = row.getString(1)
            val predicate = row.getString(3)?.replace("::text", "")?.replace(Regex("[\\s()]"), "")
            val expected = if (name == "uq_complaint_catalog_pending") "state='PREPARED'ORstate='COMPLETED'ANDprojected_atISNULL" else null
            assertEquals(expected, predicate)
            assertEquals(name != "idx_complaint_catalog_scope", row.getBoolean(4))
            assertTrue(row.getBoolean(5), "An unpriced INCLUDE value must not hide in a V14 index.")
            assertEquals("btree", row.getString(6))
            name to row.getString(2).replace("(", "").replace(")", "")
        },
    ).toMap()
    assertEquals(
        mapOf(
            "pk_complaint_catalog_mutations" to "operation_token",
            "uq_complaint_catalog_successor" to "successor_generation",
            "uq_complaint_catalog_object_key" to "object_key",
            "uq_complaint_catalog_pending" to "1",
            "idx_complaint_catalog_scope" to "data_scope_id,successor_generation",
        ),
        indexes,
    )
}

private val GENESIS_COLUMNS = linkedMapOf(
    "operation_token" to "uuid",
    "operation_type" to "character varying(64)",
    "data_scope_id" to "uuid",
    "test_only" to "boolean",
    "predecessor_generation" to "bigint",
    "predecessor_hash" to "bytea",
    "successor_generation" to "bigint",
    "catalog_writer_generation" to "uuid",
    "approval_bytes" to "bytea",
    "approval_hash" to "bytea",
    "canonicalizer" to "character varying(16)",
    "unsigned_bytes" to "bytea",
    "unsigned_hash" to "bytea",
    "signer_policy" to "character varying(24)",
    "signer_one_id" to "character varying(128)",
    "signer_one_algorithm" to "character varying(128)",
    "signer_one_signature" to "bytea",
    "signer_two_id" to "character varying(128)",
    "signer_two_algorithm" to "character varying(128)",
    "signer_two_signature" to "bytea",
    "envelope_bytes" to "bytea",
    "envelope_hash" to "bytea",
    "object_key" to "text",
    "object_version" to "text",
    "retain_until" to "timestamp with time zone",
    "primary_evidence_bytes" to "bytea",
    "primary_evidence_hash" to "bytea",
    "replica_evidence_bytes" to "bytea",
    "replica_evidence_hash" to "bytea",
    "state" to "character varying(16)",
    "created_at" to "timestamp with time zone",
    "completed_at" to "timestamp with time zone",
    "projected_at" to "timestamp with time zone",
)
