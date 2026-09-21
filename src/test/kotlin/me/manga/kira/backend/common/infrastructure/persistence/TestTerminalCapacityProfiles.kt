package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.util.HexFormat

/** Closed current-table expectations, not a schema discovery/pricing service. All SQL runs only in the owned rollback IT. */
internal data class TestTerminalCapacityIndex(val name: String, val keys: String, val predicate: String? = null, val options: String = "0", val access: String = "btree")

internal data class TestTerminalCapacityProfile(val table: String, val columns: String, val indexes: List<TestTerminalCapacityIndex>) {
    val types: Map<String, String> = columns.split(',').associate { it.substringBefore(':') to it.substringAfter(':') }
}

internal fun assertTestTerminalCapacitySchema(sql: JdbcTemplate) {
    TERMINAL_MIGRATION_HASHES.forEach { (name, expected) ->
        val bytes = checkNotNull(TestTerminalCapacityProfile::class.java.getResourceAsStream("/db/migration/$name")).use { it.readBytes() }
        assertEquals(expected, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), name)
    }
    TEST_TERMINAL_CAPACITY_PROFILES.forEach { profile ->
        assertEquals(profile.types.entries.map { it.key to it.value }, sql.query(
            "SELECT attname, format_type(atttypid, atttypmod) FROM pg_attribute " +
                "WHERE attrelid = ?::regclass AND attnum > 0 AND NOT attisdropped ORDER BY attnum",
            { row, _ -> row.getString(1) to row.getString(2) }, profile.table,
        ), profile.table)
        val actual = sql.query(TERMINAL_INDEX_QUERY, { row, _ ->
            val name = row.getString(1)
            assertEquals(name.startsWith("pk_") || name.startsWith("uq_"), row.getBoolean(4), name)
            assertTrue(row.getBoolean(5), "Unpriced INCLUDE attributes: $name")
            TestTerminalCapacityIndex(name, sqlShape(row.getString(2))!!, sqlShape(row.getString(3)), row.getString(6), row.getString(7))
        }, profile.table)
        assertEquals(profile.indexes.sortedBy { it.name }.map { it.copy(keys = sqlShape(it.keys)!!, predicate = sqlShape(it.predicate)) }, actual, profile.table)
    }
}

/** Same detoasted-composite convention as CatalogGenesisCapacityAssertions/OwnerDeleteAllCapacityEnvelopes, not disk sizing. */
internal fun measureTestTerminalCapacity(
    sql: JdbcTemplate, table: String, predicate: String, minimumInlineBytes: Long, price: Long, marginNumerator: Long = 8, marginDenominator: Long = 1,
): Long {
    val profile = TEST_TERMINAL_CAPACITY_PROFILES.single { it.table == table }
    fun inline(name: String): String = when (val type = profile.types.getValue(name)) {
        "bytea" -> "$name || ''::bytea"
        "bigint[]" -> "$name || '{}'::bigint[]"
        else -> if (type == "text" || type.startsWith("character varying")) "$name || ''::text" else name
    }
    val heap = profile.types.keys.joinToString(",", transform = ::inline)
    val indexes = profile.indexes.map { index ->
        if (index.access == "gin") "($TEST_TERMINAL_NOTICE_SEARCH) || ''::tsvector"
        else index.keys.split(',').joinToString(",") { if (it == "1") it else inline(it) }
    }
    val projections = (listOf(heap) + indexes).joinToString(",") { "pg_column_size(ROW($it))" }
    val sizes = checkNotNull(sql.queryForObject("SELECT $projections FROM $table WHERE $predicate", { row, _ -> (1..indexes.size + 1).map { row.getLong(it) } }))
    assertTrue(sizes.first() >= minimumInlineBytes, "Full inlined fields required, never compressed/TOAST pointers: $table")
    assertTrue(sizes.drop(1).all { it in 1L..4096L }, "Unbounded index input: $table")
    val logical = sizes.first() + sizes.drop(1).mapIndexed { index, bytes ->
        if (profile.indexes[index].access == "gin") { assertTrue(bytes <= 256); 256L } else bytes
    }.sum()
    assertTrue(Math.multiplyExact(logical, marginNumerator) <= Math.multiplyExact(price, marginDenominator), "Unqualified logical envelope: $table")
    return logical
}

// Preserve quoted values (especially the GIN separator). Current immutable migration hashes also pin exact DDL grouping.
private fun sqlShape(value: String?): String? = value?.replace(Regex("::(character varying|text)(\\[\\])?"), "")?.let { sql ->
    var quoted = false
    buildString {
        sql.forEach { c ->
            if (c == '\'') quoted = !quoted
            if (quoted || c == '\'' || (!c.isWhitespace() && c != '(' && c != ')')) append(if (quoted) c else c.lowercaseChar())
        }
    }
}

internal const val TEST_TERMINAL_NOTICE_SEARCH = "to_tsvector('simple'::regconfig, coalesce(subject, '') || ' ' || coalesce(body, ''))"

private const val TERMINAL_INDEX_QUERY = "SELECT idx.relname, (SELECT string_agg(coalesce(a.attname::text, pg_get_expr(i.indexprs, i.indrelid)), ',' ORDER BY k.n) " +
    "FROM unnest(i.indkey::smallint[]) WITH ORDINALITY k(attnum, n) LEFT JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum " +
    "WHERE k.n <= i.indnkeyatts), pg_get_expr(i.indpred, i.indrelid), i.indisunique, i.indnatts = i.indnkeyatts, i.indoption::text, am.amname " +
    "FROM pg_index i JOIN pg_class idx ON idx.oid = i.indexrelid JOIN pg_am am ON idx.relam = am.oid " +
    "WHERE i.indrelid = ?::regclass ORDER BY idx.relname"

private val TERMINAL_MIGRATION_HASHES = mapOf(
    "V14__backend_owned_complaints.sql" to "68bf2e7e5e5baf80dbaaeeff1ba8dc743c6e473778ab8df6ed8bd4f1f619cb75",
    "V16__partial_recovery_capacity.sql" to "ff2ee252f9e08f17131173314d5cbcf5c245f9b6ceabdba40fc2d1b5b42716e4",
    "V17__journal_rotation_slot.sql" to "6ae3213476dd66bb62cd610505ffb1e0f3200aeb3a2be64e7b7bd5210a8141fe",
    "V18__complaint_cutoff_manifest_index.sql" to "fe2d915de5bf2eb85eab736e0bbd02565a0eae0d6c501816c3beb7b2d350313c",
    "V19__journal_seal_intent_binding.sql" to "12d6c3b2a7afed7ff84a78f1892d8253344e9dac10f4fc9a75870fe34540486a",
)

internal val TEST_TERMINAL_CAPACITY_PROFILES = listOf(
    TestTerminalCapacityProfile(
        "complaint_catalog_mutations",
        "operation_token:uuid,operation_type:character varying(64),data_scope_id:uuid,test_only:boolean," +
        "predecessor_generation:bigint,predecessor_hash:bytea,successor_generation:bigint,catalog_writer_generation:uuid," +
        "approval_bytes:bytea,approval_hash:bytea,canonicalizer:character varying(16),unsigned_bytes:bytea," +
        "unsigned_hash:bytea,signer_policy:character varying(24),signer_one_id:character varying(128),signer_one_algorithm:character varying(128)," +
        "signer_one_signature:bytea,signer_two_id:character varying(128),signer_two_algorithm:character varying(128),signer_two_signature:bytea," +
        "envelope_bytes:bytea,envelope_hash:bytea,object_key:text,object_version:text," +
        "retain_until:timestamp with time zone,primary_evidence_bytes:bytea,primary_evidence_hash:bytea,replica_evidence_bytes:bytea," +
        "replica_evidence_hash:bytea,state:character varying(16),created_at:timestamp with time zone,completed_at:timestamp with time zone," +
        "projected_at:timestamp with time zone",
        listOf(
            TestTerminalCapacityIndex("pk_complaint_catalog_mutations", "operation_token"),
            TestTerminalCapacityIndex("uq_complaint_catalog_successor", "successor_generation"),
            TestTerminalCapacityIndex("uq_complaint_catalog_object_key", "object_key"),
            TestTerminalCapacityIndex("uq_complaint_catalog_pending", "1", "state = 'PREPARED' OR (state = 'COMPLETED' AND projected_at IS NULL)"),
            TestTerminalCapacityIndex("idx_complaint_catalog_scope", "data_scope_id,successor_generation", null, "0 0"),
        ),
    ),
    TestTerminalCapacityProfile(
        "complaint_test_runs",
        "data_scope_id:uuid,test_only:boolean,state:character varying(16),configuration_hash:bytea," +
        "accounting_version:smallint,installation_limit:bigint,enrolled_count:bigint,original_reserve:bigint[]," +
        "unused_reserve:bigint[],activation_catalog_generation:bigint,activation_catalog_hash:bytea,created_at:timestamp with time zone," +
        "sealed_at:timestamp with time zone,purging_at:timestamp with time zone,purged_at:timestamp with time zone,final_ordinary_epoch:bigint," +
        "terminal_seal_epoch:bigint,generation_seal_count:integer,generation_seal_root:bytea,seal_set_bytes:bytea," +
        "seal_set_hash:bytea,event_manifest_count:bigint,event_manifest_root:bytea,installation_manifest_count:bigint," +
        "installation_manifest_root:bytea,installation_chunk_count:integer,retired_count:bigint,deleted_count:bigint," +
        "permanent_denial_bytes:bytea,permanent_denial_hash:bytea,terminal_event_id:character varying(43),terminal_object_key:text," +
        "terminal_object_version:text,terminal_ciphertext_hash:bytea,terminal_catalog_generation:bigint,terminal_catalog_hash:bytea,recurrent_erasure_history_hash:bytea",
        listOf(
            TestTerminalCapacityIndex("pk_complaint_test_runs", "data_scope_id"),
            TestTerminalCapacityIndex("uq_complaint_run_nonterminal", "1", "state = ANY (ARRAY['ACTIVE', 'SEALED', 'PURGING'])"),
        ),
    ),
    TestTerminalCapacityProfile(
        "complaint_journal_control",
        "data_scope_id:uuid,test_only:boolean,publication_epoch:bigint,desired_generation:bigint," +
        "implementation_schema:integer,desired_configuration_hash:bytea,database_identity:uuid,restore_identity:uuid," +
        "event_writer_generation:uuid,accepted_catalog_generation:bigint,accepted_catalog_hash:bytea,trust_bundle_hash:bytea," +
        "catalog_writer_generation:uuid,pending_projection_token:uuid,maintenance_closed:boolean,creation_closed:boolean," +
        "scan_requested:boolean,lease_owner:uuid,lease_token:bigint,lease_expires_at:timestamp with time zone," +
        "retention_lease_owner:uuid,retention_lease_token:bigint,retention_lease_expires_at:timestamp with time zone,seal_state:character varying(16)," +
        "seal_epoch:bigint,seal_writer_generation:uuid,seal_operation_token:uuid,seal_object_key:text," +
        "seal_bytes:bytea,seal_hash:bytea,seal_object_version:text,seal_ciphertext_hash:bytea," +
        "seal_retain_until:timestamp with time zone,seal_verified_at:timestamp with time zone,seal_verification_bytes:bytea,seal_verification_hash:bytea," +
        "checkpoint_generation:bigint,checkpoint_fencing_token:bigint,checkpoint_catalog_generation:bigint,checkpoint_catalog_hash:bytea," +
        "checkpoint_writer_generation:uuid,checkpoint_cutoff_epoch:bigint,checkpoint_configuration_hash:bytea,checkpoint_database_identity:uuid," +
        "checkpoint_restore_identity:uuid,checkpoint_schema:integer,checkpoint_started_at:timestamp with time zone,checkpoint_completed_at:timestamp with time zone," +
        "checkpoint_object_count:bigint,checkpoint_byte_count:bigint,checkpoint_result:character varying(16),checkpoint_bytes:bytea," +
        "checkpoint_hash:bytea,updated_at:timestamp with time zone,rotation_sequence:bigint,rotation_id:uuid," +
        "rotation_state:text,rotation_epoch_before:bigint,rotation_implementation_schema:integer,rotation_desired_generation:bigint," +
        "rotation_desired_configuration_hash:bytea,rotation_database_identity:uuid,rotation_restore_identity:uuid,rotation_event_writer_generation:uuid," +
        "rotation_accepted_catalog_generation:bigint,rotation_accepted_catalog_hash:bytea,rotation_trust_bundle_hash:bytea,rotation_catalog_writer_generation:uuid," +
        "rotation_request_owner:uuid,rotation_request_token:bigint,rotation_requested_at:timestamp with time zone,rotation_capture_owner:uuid," +
        "rotation_capture_token:bigint,rotation_captured_at:timestamp with time zone,rotation_epoch_after:bigint,seal_format:integer," +
        "seal_rotation_id:uuid,seal_rotation_sequence:bigint,seal_preparing_fencing_token:bigint,seal_routing_key_id:text," +
        "seal_epoch_start:bigint,seal_preceding_hash:bytea",
        listOf(
            TestTerminalCapacityIndex("pk_complaint_journal_control", "data_scope_id"),
            TestTerminalCapacityIndex("idx_complaint_control_projection", "pending_projection_token"),
        ),
    ),
    TestTerminalCapacityProfile(
        "complaints",
        "id:uuid,data_scope_id:uuid,test_only:boolean,owner_id:uuid," +
        "ownership:character varying(24),kind:character varying(8),type:character varying(16),status:character varying(16)," +
        "notice_key:character varying(96),subject:text,body:text,parent_resource_id:uuid," +
        "app_version:text,platform:character varying(8),os_version:text,manufacturer:text," +
        "device_model:text,closure_reason:text,closed_at:timestamp with time zone,closure_provenance:character varying(8)," +
        "closure_actor_id:uuid,legacy_collection:character varying(32),legacy_key_id:character varying(128),legacy_document_hmac:bytea," +
        "legacy_payload_hash:bytea,legacy_owner_fingerprint:bytea,legacy_reconciliation_code:character varying(64),created_at:timestamp with time zone," +
        "updated_at:timestamp with time zone,version:bigint",
        listOf(
            TestTerminalCapacityIndex("pk_complaints", "id"),
            TestTerminalCapacityIndex("uq_complaints_scope", "id,data_scope_id", null, "0 0"),
            TestTerminalCapacityIndex("uq_complaints_legacy_identity", "legacy_collection,legacy_key_id,legacy_document_hmac", null, "0 0 0"),
            TestTerminalCapacityIndex("uq_complaints_notice_key", "data_scope_id,notice_key", "kind = 'NOTICE'", "0 0"),
            TestTerminalCapacityIndex("idx_complaints_owner_page", "owner_id,data_scope_id,created_at,id", null, "0 0 3 3"),
            TestTerminalCapacityIndex("idx_complaints_parent", "parent_resource_id,data_scope_id", null, "0 0"),
            TestTerminalCapacityIndex("idx_complaints_closure_actor", "closure_actor_id"),
            TestTerminalCapacityIndex("idx_complaints_scope_page", "data_scope_id,updated_at,id", null, "0 3 3"),
            TestTerminalCapacityIndex("idx_complaints_status_page", "data_scope_id,status,updated_at,id", null, "0 0 3 3"),
            TestTerminalCapacityIndex("idx_complaints_type_page", "data_scope_id,type,updated_at,id", null, "0 0 3 3"),
            TestTerminalCapacityIndex("idx_complaints_ownership_page", "data_scope_id,ownership,updated_at,id", null, "0 0 3 3"),
            TestTerminalCapacityIndex("idx_complaints_legacy_owner", "legacy_key_id,legacy_owner_fingerprint", null, "0 0"),
            TestTerminalCapacityIndex("idx_complaints_search", "to_tsvector('simple'::regconfig, coalesce(subject, '') || ' ' || coalesce(body, ''))", null, "0", "gin"),
        ),
    ),
    TestTerminalCapacityProfile(
        "complaint_resource_ids",
        "id:uuid,data_scope_id:uuid,test_only:boolean,state:character varying(24)," +
        "created_at:timestamp with time zone,deleted_at:timestamp with time zone",
        listOf(
            TestTerminalCapacityIndex("pk_complaint_resource_ids", "id"),
            TestTerminalCapacityIndex("uq_complaint_resource_scope", "id,data_scope_id", null, "0 0"),
            TestTerminalCapacityIndex("idx_complaint_resource_scope", "data_scope_id,id", null, "0 0"),
        ),
    ),
    TestTerminalCapacityProfile(
        "complaint_journal_publications",
        "event_id:character varying(43),data_scope_id:uuid,test_only:boolean,writer_generation:uuid," +
        "journal_epoch:bigint,event_kind:character varying(32),target_count:integer,routing_key_id:character varying(128)," +
        "object_key:text,canonicalizer:character varying(16),event_bytes:bytea,semantic_hash:bytea," +
        "state:character varying(16),created_at:timestamp with time zone,object_version:text,ciphertext_hash:bytea," +
        "object_created_at:timestamp with time zone,retain_until:timestamp with time zone,verified_at:timestamp with time zone,verification_bytes:bytea," +
        "verification_hash:bytea,applied_at:timestamp with time zone",
        listOf(
            TestTerminalCapacityIndex("pk_complaint_publications", "event_id"),
            TestTerminalCapacityIndex("uq_complaint_publication_scope", "event_id,data_scope_id", null, "0 0"),
            TestTerminalCapacityIndex("uq_complaint_publication_key", "object_key"),
            TestTerminalCapacityIndex("idx_complaint_publication_pending", "state,created_at,event_id", null, "0 0 0"),
            TestTerminalCapacityIndex("idx_complaint_publication_epoch", "data_scope_id,writer_generation,journal_epoch,event_id", null, "0 0 0 0"),
            TestTerminalCapacityIndex("idx_complaint_publication_manifest", "data_scope_id,writer_generation,object_key", null, "0 0 0"),
        ),
    ),
    TestTerminalCapacityProfile(
        "complaint_recovery_capacity_reservations",
        "event_id:character varying(43),data_scope_id:uuid,test_only:boolean,publication_ref:character varying(43)," +
        "state:character varying(16),accounting_version:smallint,reserved_amounts:bigint[],converted_amounts:bigint[]," +
        "created_at:timestamp with time zone,converted_at:timestamp with time zone",
        listOf(
            TestTerminalCapacityIndex("pk_complaint_recovery_reservations", "event_id,data_scope_id", null, "0 0"),
            TestTerminalCapacityIndex("uq_complaint_recovery_event", "event_id"),
            TestTerminalCapacityIndex("idx_complaint_recovery_publication", "publication_ref,data_scope_id", null, "0 0"),
            TestTerminalCapacityIndex("idx_complaint_recovery_scope", "data_scope_id,event_id", null, "0 0"),
        ),
    ),
)

/** Separate scan qualification inventory: the older capacity list and its existing callers remain unchanged. */
internal val TEST_TERMINAL_SCAN_PROFILES = listOf(
    TestTerminalCapacityProfile(
        "complaint_journal_scan_runs",
        "scan_id:uuid,pass:smallint,data_scope_id:uuid,test_only:boolean,restore_identity:uuid," +
            "desired_generation:bigint,fencing_token:bigint,writer_generation:uuid,cutoff_epoch:bigint," +
            "maximum_entries:bigint,maximum_bytes:bigint,entry_count:bigint,entry_bytes:bigint," +
            "state:character varying(16),manifest_hash:bytea,started_at:timestamp with time zone,finished_at:timestamp with time zone",
        listOf(
            TestTerminalCapacityIndex("pk_complaint_scan_runs", "scan_id,pass", null, "0 0"),
            TestTerminalCapacityIndex("uq_complaint_scan_scope", "scan_id,pass,data_scope_id", null, "0 0 0"),
            TestTerminalCapacityIndex("idx_complaint_scan_scope", "data_scope_id,state,scan_id,pass", null, "0 0 0 0"),
        ),
    ),
    TestTerminalCapacityProfile(
        "complaint_journal_scan_entries",
        "scan_id:uuid,pass:smallint,data_scope_id:uuid,test_only:boolean,object_key:text,object_version:text," +
            "ciphertext_hash:bytea,semantic_hash:bytea,event_id:character varying(43),event_kind:character varying(32)," +
            "writer_generation:uuid,journal_epoch:bigint,entry_bytes:bigint,replay_state:character varying(16)",
        listOf(
            TestTerminalCapacityIndex("pk_complaint_scan_entries", "scan_id,pass,object_key,object_version", null, "0 0 0 0"),
            TestTerminalCapacityIndex("idx_complaint_scan_entry_run", "scan_id,pass,data_scope_id", null, "0 0 0"),
            TestTerminalCapacityIndex("idx_complaint_scan_entry_replay", "scan_id,pass,replay_state", null, "0 0 0"),
            TestTerminalCapacityIndex("idx_complaint_scan_entry_scope", "data_scope_id,scan_id,pass", null, "0 0 0"),
        ),
    ),
)

internal fun assertTestTerminalScanCapacitySchema(sql: JdbcTemplate) {
    val migration = "V14__backend_owned_complaints.sql"
    val bytes = checkNotNull(TestTerminalCapacityProfile::class.java.getResourceAsStream("/db/migration/$migration")).use { it.readBytes() }
    assertEquals(TERMINAL_MIGRATION_HASHES.getValue(migration), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
    assertEquals(listOf(17, 14), TEST_TERMINAL_SCAN_PROFILES.map { it.types.size })
    TEST_TERMINAL_SCAN_PROFILES.forEach { profile ->
        val nullable = if (profile.table == "complaint_journal_scan_runs") setOf("manifest_hash", "finished_at") else setOf("event_id")
        assertEquals(profile.types.map { (name, type) -> Triple(name, type, name !in nullable) }, sql.query(
            "SELECT attname,format_type(atttypid,atttypmod),attnotnull FROM pg_attribute " +
                "WHERE attrelid = ?::regclass AND attnum > 0 AND NOT attisdropped ORDER BY attnum",
            { row, _ -> Triple(row.getString(1), row.getString(2), row.getBoolean(3)) }, profile.table,
        ))
        val actual = sql.query(TERMINAL_INDEX_QUERY, { row, _ ->
            val name = row.getString(1)
            assertEquals(name.startsWith("pk_") || name.startsWith("uq_"), row.getBoolean(4), name)
            assertTrue(row.getBoolean(5), "Unpriced scan INCLUDE column: $name")
            TestTerminalCapacityIndex(name, sqlShape(row.getString(2))!!, sqlShape(row.getString(3)), row.getString(6), row.getString(7))
        }, profile.table)
        assertEquals(profile.indexes.sortedBy { it.name }.map { it.copy(keys = sqlShape(it.keys)!!) }, actual)
        assertEquals(profile.indexes.size.toLong(), sql.queryForObject(
            "SELECT count(*) FROM pg_index WHERE indrelid = ?::regclass AND indisvalid AND indisready " +
                "AND indpred IS NULL AND indexprs IS NULL AND indnatts = indnkeyatts", Long::class.java, profile.table,
        ))
    }
    assertEquals(listOf("fk_complaint_scan_entry_run:complaint_journal_scan_runs:a:r:false:false"), sql.query(
        "SELECT conname,confrelid::regclass::text,confupdtype::text,confdeltype::text,condeferrable,condeferred " +
            "FROM pg_constraint WHERE conrelid = 'complaint_journal_scan_entries'::regclass AND contype = 'f' ORDER BY conname",
        { row, _ -> (1..4).joinToString(":") { row.getString(it) } + ":${row.getBoolean(5)}:${row.getBoolean(6)}" },
    ))
    assertEquals(listOf("scan_id,pass,data_scope_id" to "scan_id,pass,data_scope_id"), sql.query(
        "SELECT (SELECT string_agg(a.attname::text,',' ORDER BY k.n) FROM unnest(f.conkey) WITH ORDINALITY k(attnum,n) " +
            "JOIN pg_attribute a ON a.attrelid = f.conrelid AND a.attnum = k.attnum), " +
            "(SELECT string_agg(a.attname::text,',' ORDER BY k.n) FROM unnest(f.confkey) WITH ORDINALITY k(attnum,n) " +
            "JOIN pg_attribute a ON a.attrelid = f.confrelid AND a.attnum = k.attnum) " +
            "FROM pg_constraint f WHERE f.conrelid = 'complaint_journal_scan_entries'::regclass " +
            "AND f.conname = 'fk_complaint_scan_entry_run' AND f.convalidated",
        { row, _ -> row.getString(1) to row.getString(2) },
    ))
    assertEquals(true, sql.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM pg_index i WHERE i.indexrelid = 'idx_complaint_scan_entry_run'::regclass " +
            "AND (SELECT array_agg(k.attnum ORDER BY k.n) FROM unnest(i.indkey::smallint[]) WITH ORDINALITY k(attnum,n)) = f.conkey) " +
            "FROM pg_constraint f WHERE f.conrelid = 'complaint_journal_scan_entries'::regclass AND f.conname = 'fk_complaint_scan_entry_run'",
        Boolean::class.java,
    ))
}

/** Independent field/index envelopes, not aliases of the MAIN constants being qualified. */
internal fun measureTestTerminalScanCapacity(sql: JdbcTemplate, table: String, pass: Int): List<Long> {
    val profile = TEST_TERMINAL_SCAN_PROFILES.single { it.table == table }
    fun inline(name: String): String = when (val type = profile.types.getValue(name)) {
        "bytea" -> "$name || ''::bytea"
        else -> if (type == "text" || type.startsWith("character varying")) "$name || ''::text" else name
    }
    val fields = listOf(profile.types.keys.toList()) + profile.indexes.map { it.keys.split(',') }
    val projections = fields.joinToString(",") { columns -> "pg_column_size(ROW(${columns.joinToString(",", transform = ::inline)}))" }
    val sizes = sql.query("SELECT $projections FROM $table WHERE scan_id = ? AND pass = ?", { row, _ ->
        fields.indices.map { row.getLong(it + 1) }
    }, TEST_TERMINAL_SCAN_PAIR, pass)
    assertTrue(sizes.isNotEmpty())
    val heapBound = if (table == "complaint_journal_scan_runs") 32L + 152 + 64 else 32L + 80 + 2256
    val indexBounds = if (table == "complaint_journal_scan_runs") listOf(56L, 72L, 96L) else listOf(2120L, 72L, 80L, 72L)
    val minimum = if (table == "complaint_journal_scan_runs") 128L else 2048L + 64
    sizes.forEach { row ->
        assertTrue(row.first() in minimum..heapBound, "Full detoasted maximum scan fields, not compressed/TOAST pointers: $table")
        row.drop(1).zip(indexBounds).forEachIndexed { index, (bytes, bound) ->
            assertTrue(bytes in 1L..bound, "Unqualified scan index input: ${profile.indexes[index].name}")
        }
    }
    // Return component-wise maxima over the independent kind variants; each component is bounded above separately.
    return fields.indices.map { index -> sizes.maxOf { it[index] } }
}

internal data class TestTerminalScanPhysical(
    val table: String,
    val heapMain: Long,
    val heapAuxiliary: Long,
    val indexes: Map<String, Long>,
    val toastHeapMain: Long,
    val toastAuxiliary: Long,
    val toastIndexes: Map<String, Long>,
    val total: Long,
    val toastValues: Long,
    val toastChunks: Long,
    val toastPayload: Long,
) {
    fun record(stage: String) {
        println("TEST_TERMINAL_SCAN_PROFILE_V1 table=$table stage=$stage heap=$heapMain heap_aux=$heapAuxiliary indexes=$indexes " +
            "toast_heap=$toastHeapMain toast_aux=$toastAuxiliary toast_indexes=$toastIndexes total=$total " +
            "toast_values=$toastValues toast_chunks=$toastChunks toast_payload=$toastPayload")
    }
}

/** Physical pages are observations, NOT the 3776/37696 logical prices or an unlimited update-history allowance. */
internal fun measureTestTerminalScanPhysical(sql: JdbcTemplate, table: String): TestTerminalScanPhysical {
    val profile = TEST_TERMINAL_SCAN_PROFILES.single { it.table == table }
    val toast = checkNotNull(sql.queryForObject("SELECT reltoastrelid::regclass::text FROM pg_class WHERE oid = ?::regclass", String::class.java, table))
    require(toast.matches(Regex("pg_toast\\.pg_toast_[0-9]+")))
    fun relation(name: String): List<Long> = checkNotNull(sql.queryForObject(
        "SELECT pg_relation_size(?::regclass),pg_table_size(?::regclass),pg_total_relation_size(?::regclass)",
        { row, _ -> (1..3).map { row.getLong(it) } }, name, name, name,
    ))
    fun indexes(name: String): Map<String, Long> = sql.query(
        "SELECT idx.relname,pg_relation_size(idx.oid)+pg_relation_size(idx.oid,'fsm')+pg_relation_size(idx.oid,'vm')+pg_relation_size(idx.oid,'init') " +
            "FROM pg_index i JOIN pg_class idx ON idx.oid = i.indexrelid WHERE i.indrelid = ?::regclass ORDER BY idx.relname",
        { row, _ -> row.getString(1) to row.getLong(2) }, name,
    ).toMap()
    val heap = relation(table)
    val toasted = relation(toast)
    val parentIndexes = indexes(table)
    val toastIndexes = indexes(toast)
    val chunks = checkNotNull(sql.queryForObject(
        "SELECT count(DISTINCT chunk_id),count(*),coalesce(sum(octet_length(chunk_data)),0) FROM $toast",
        { row, _ -> (1..3).map { row.getLong(it) } },
    ))
    assertEquals(profile.indexes.map { it.name }.sorted(), parentIndexes.keys.toList())
    assertEquals(1, toastIndexes.size)
    assertEquals(heap[2], heap[1] + parentIndexes.values.sum())
    assertEquals(toasted[2], toasted[1] + toastIndexes.values.sum())
    return TestTerminalScanPhysical(table, heap[0], heap[1] - heap[0] - toasted[2], parentIndexes,
        toasted[0], toasted[1] - toasted[0], toastIndexes, heap[2], chunks[0], chunks[1], chunks[2]).also { measured ->
        assertTrue(listOf(measured.heapMain, measured.heapAuxiliary, measured.toastHeapMain, measured.toastAuxiliary).all { it >= 0 })
        assertEquals(measured.total, measured.heapMain + measured.heapAuxiliary + measured.indexes.values.sum() +
            measured.toastHeapMain + measured.toastAuxiliary + measured.toastIndexes.values.sum())
    }
}
