package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.util.HexFormat
import java.util.UUID

/**
 * Existing owned database only. Maximal legal V14 row/index payloads are synthetic sizing rows, NOT
 * canonical events, verification, apply/retirement authority, physical disk/MVCC sizing or proof of P.
 * All later-state sizing changes roll back; only the real initial authorizer's charge survives.
 */
internal fun assertOwnerDeleteAllCapacityEnvelopes(f: OwnerDeleteAllAuthorizationFixture) {
    val migration = checkNotNull(OwnerDeleteAllAuthorizationFixture::class.java.getResourceAsStream("/db/migration/V14__backend_owned_complaints.sql"))
        .use { it.readBytes() }
    assertEquals("68bf2e7e5e5baf80dbaaeeff1ba8dc743c6e473778ab8df6ed8bd4f1f619cb75", HexFormat.of().formatHex(ownerDeleteAllTestDigest(migration)))
    val candidate = f.enrolled()
    val counters = f.counters()
    val work = f.prepared(candidate)
    f.assertCharged(counters)
    val before = f.state()
    val event = f.store.preparedEvent(work).route.eventId
    val key = "k".repeat(1024)
    val version = "v".repeat(1024)
    val document = ByteArray(65536) { 65 }
    val digest = ownerDeleteAllTestDigest(document)
    val array = LongArray(22) { Long.MAX_VALUE }.joinToString(",", "{", "}")
    checkNotNull(f.observer.dataSource).connection.use { connection ->
        connection.autoCommit = false
        val sql = JdbcTemplate(SingleConnectionDataSource(connection, true))
        try {
            PROFILES.forEach { profile -> assertCatalogue(sql, profile) }
            measureReceiptLifecycle(sql, candidate.installation.id, event, version, digest)
            sql.update(
                "UPDATE complaint_journal_publications SET journal_epoch = ?, target_count = 100, routing_key_id = repeat('r', 128), object_key = ?, " +
                    "event_bytes = ?, semantic_hash = ? WHERE event_id = ?",
                Long.MAX_VALUE,
                key,
                document,
                digest,
                event,
            )
            measure(sql, PUBLICATION, "event_id = '$event'", 65536)
            sql.update(
                "UPDATE complaint_journal_publications SET state = 'VERIFIED', object_version = ?, ciphertext_hash = ?, object_created_at = now(), " +
                    "retain_until = now() + interval '70 days', verified_at = now(), verification_bytes = ?, verification_hash = ? WHERE event_id = ?",
                version,
                digest,
                document,
                digest,
                event,
            )
            measure(sql, PUBLICATION, "event_id = '$event'", 2 * 65536)
            sql.update("UPDATE complaint_journal_publications SET state = 'APPLIED', applied_at = now() WHERE event_id = ?", event)
            measure(sql, PUBLICATION, "event_id = '$event'", 2 * 65536)
            sql.update("UPDATE complaint_recovery_capacity_reservations SET reserved_amounts = ?::bigint[] WHERE event_id = ?", array, event)
            measure(sql, RESERVATION, "event_id = '$event'", 22 * 8)
            sql.update(
                "UPDATE complaint_recovery_capacity_reservations SET state = 'CONVERTED', converted_amounts = reserved_amounts, " +
                    "converted_at = now() WHERE event_id = ?",
                event,
            )
            measure(sql, RESERVATION, "event_id = '$event'", 2 * 22 * 8)
            sql.update("UPDATE complaint_recovery_capacity_reservations SET publication_ref = NULL WHERE event_id = ?", event)
            measure(sql, RESERVATION, "event_id = '$event'", 2 * 22 * 8)
            sql.update(
                "INSERT INTO complaint_deletion_journal_applied (object_key, object_version, event_id, ciphertext_hash, writer_generation, journal_epoch, " +
                    "event_kind, target_count, data_scope_id, test_only, applied_at) SELECT object_key, object_version, event_id, ciphertext_hash, " +
                    "writer_generation, journal_epoch, event_kind, target_count, data_scope_id, test_only, applied_at " +
                    "FROM complaint_journal_publications WHERE event_id = ?",
                event,
            )
            measure(sql, APPLIED, "event_id = '$event'", 2 * 1024)
            sql.update(
                "INSERT INTO complaint_deletion_journal_retirements (object_key, object_version, data_scope_id, test_only, event_kind, state, " +
                    "authorization_catalog_generation, authorization_catalog_hash, restore_floor, authorization_bytes, authorization_hash, authorized_at) " +
                    "SELECT object_key, object_version, data_scope_id, test_only, event_kind, 'AUTHORIZED', ?, ?, now(), ?, ?, now() " +
                    "FROM complaint_deletion_journal_applied WHERE event_id = ?",
                Long.MAX_VALUE - 1,
                digest,
                document,
                digest,
                event,
            )
            measure(sql, RETIREMENT, "object_key = '$key'", 65536)
            sql.update(
                "UPDATE complaint_deletion_journal_retirements SET state = 'COMPLETED', completion_catalog_generation = ?, completion_catalog_hash = ?, " +
                    "completion_bytes = ?, completion_hash = ?, completed_at = now() WHERE object_key = ?",
                Long.MAX_VALUE,
                digest,
                document,
                digest,
                key,
            )
            measure(sql, RETIREMENT, "object_key = '$key'", 2 * 65536)
        } finally {
            connection.rollback()
        }
    }
    assertEquals(before, f.state())
    f.assertCharged(counters)
    assertEquals(113L, OwnerDeleteAllCapacityCharges.RECOVERY[ComplaintCapacityCounter.AUDIT_ROWS])
    assertEquals(4L, OwnerDeleteAllCapacityCharges.RECOVERY[ComplaintCapacityCounter.JOURNAL_APPLIED])
    assertEquals(4L, OwnerDeleteAllCapacityCharges.RECOVERY[ComplaintCapacityCounter.JOURNAL_RETIREMENTS])
    assertEquals(1L, OwnerDeleteAllCapacityCharges.AUTHORIZATION[ComplaintCapacityCounter.RECOVERY_RESERVATIONS])
    assertEquals(0L, OwnerDeleteAllCapacityCharges.RECOVERY[ComplaintCapacityCounter.RECOVERY_RESERVATIONS])
    f.assertReleased()
}

private fun measureReceiptLifecycle(sql: JdbcTemplate, installationId: UUID, event: String, version: String, digest: ByteArray) {
    sql.update(
        "UPDATE installation_deletion_receipts SET state = 'IN_PROGRESS', publication_ref = NULL, authorized_at = NULL WHERE installation_id = ?",
        installationId,
    )
    measure(sql, RECEIPT, "installation_id = '$installationId'", 0)
    sql.update(
        "UPDATE installation_deletion_receipts SET state = 'AUTHORIZED_DELETE', publication_ref = ?, " +
            "authorized_at = clock_timestamp() WHERE installation_id = ?",
        event,
        installationId,
    )
    measure(sql, RECEIPT, "installation_id = '$installationId'", 0)
    sql.update(
        "UPDATE installation_deletion_receipts SET state = 'COMPLETED', outcome = 'APPLIED', response_status = 204, " +
            "external_event_id = publication_ref, external_epoch = ?, external_object_version = ?, external_ciphertext_hash = ?, " +
            "completed_at = now(), expires_at = now() + interval '192 hours' WHERE installation_id = ?",
        Long.MAX_VALUE,
        version,
        digest,
        installationId,
    )
    measure(sql, RECEIPT, "installation_id = '$installationId'", 1024)
}

private fun assertCatalogue(sql: JdbcTemplate, profile: DeleteAllEnvelopeProfile) {
    val columns = sql.query(
        "SELECT attname, format_type(atttypid, atttypmod) FROM pg_attribute " +
            "WHERE attrelid = ?::regclass AND attnum > 0 AND NOT attisdropped ORDER BY attnum",
        { row, _ -> row.getString(1) + ":" + row.getString(2) },
        profile.table,
    )
    assertEquals(profile.columns.split(','), columns)
    val indexes = sql.query(
        "SELECT idx.relname, string_agg(pg_get_indexdef(i.indexrelid, n, true), ',' ORDER BY n), i.indpred IS NOT NULL, " +
            "i.indisunique, i.indnatts = i.indnkeyatts, am.amname FROM pg_index i JOIN pg_class idx ON idx.oid = i.indexrelid " +
            "JOIN pg_am am ON idx.relam = am.oid CROSS JOIN LATERAL generate_series(1, i.indnkeyatts) n " +
            "WHERE i.indrelid = ?::regclass GROUP BY idx.relname, i.indpred IS NOT NULL, i.indisunique, " +
            "i.indnatts, i.indnkeyatts, am.amname ORDER BY idx.relname",
        { row, _ ->
            assertEquals(row.getString(1) == "idx_installation_receipt_expiry", row.getBoolean(3))
            assertEquals(row.getString(1).startsWith("pk_") || row.getString(1).startsWith("uq_"), row.getBoolean(4))
            assertTrue(row.getBoolean(5), "No unmeasured INCLUDE payload may hide in an index")
            assertEquals("btree", row.getString(6))
            row.getString(1) to row.getString(2)
        },
        profile.table,
    ).toMap()
    assertEquals(profile.indexes, indexes)
}

private fun measure(sql: JdbcTemplate, profile: DeleteAllEnvelopeProfile, predicate: String, minimumInlineBytes: Int) {
    val columnTypes = profile.columns.split(',').associate { it.substringBefore(':') to it.substringAfter(':') }
    fun inline(column: String): String = when (val type = columnTypes.getValue(column)) {
        "bytea" -> "$column || ''::bytea"
        "bigint[]" -> "$column || '{}'::bigint[]"
        else -> if (type == "text" || type.startsWith("character varying")) "$column || ''::text" else column
    }
    val row = columnTypes.keys.joinToString(",", transform = ::inline)
    val keys = profile.indexes.values.map { columns -> columns.split(',').joinToString(",", transform = ::inline) }
    // Concatenation detoasts the full fields: compressed repeated bytes and TOAST pointers are not a logical envelope.
    val projections = (listOf(row) + keys).joinToString(",") { "pg_column_size(ROW($it))" }
    val sizes = checkNotNull(
        sql.queryForObject(
            "SELECT $projections FROM ${profile.table} WHERE $predicate",
            { result, _ -> (1..keys.size + 1).map { result.getLong(it) } },
        ),
    )
    assertTrue(sizes.first() >= minimumInlineBytes)
    assertTrue(sizes.drop(1).all { it in 1L..4096L })
    assertTrue(profile.charge > sizes.sum() * 3 / 2, "Every full heap/index tuple needs a conservative logical margin: ${profile.table}")
}

private class DeleteAllEnvelopeProfile(val table: String, val charge: Long, val columns: String, val indexes: Map<String, String>)

private val RECEIPT = DeleteAllEnvelopeProfile(
    "installation_deletion_receipts",
    OwnerDeleteAllCapacityCharges.RECEIPT[ComplaintCapacityCounter.STORAGE_BYTES],
    "installation_id:uuid,deletion_key:uuid,submitted_credential_version:bigint,fingerprint:bytea,data_scope_id:uuid,test_only:boolean," +
        "state:character varying(24),outcome:character varying(8),response_status:integer,publication_ref:character varying(43)," +
        "external_event_id:character varying(43),external_epoch:bigint,external_object_version:text,external_ciphertext_hash:bytea," +
        "created_at:timestamp with time zone,authorized_at:timestamp with time zone,completed_at:timestamp with time zone,expires_at:timestamp with time zone",
    linkedMapOf(
        "pk_installation_deletion_receipts" to "installation_id,deletion_key",
        "idx_installation_receipt_reservation" to "installation_id,data_scope_id",
        "idx_installation_receipt_publication" to "publication_ref,data_scope_id",
        "idx_installation_receipt_expiry" to "expires_at,installation_id,deletion_key",
        "idx_installation_receipt_scope" to "data_scope_id,installation_id,deletion_key",
    ),
)

private val PUBLICATION = DeleteAllEnvelopeProfile(
    "complaint_journal_publications",
    OwnerDeleteAllCapacityCharges.PUBLICATION[ComplaintCapacityCounter.STORAGE_BYTES],
    "event_id:character varying(43),data_scope_id:uuid,test_only:boolean,writer_generation:uuid,journal_epoch:bigint,event_kind:character varying(32)," +
        "target_count:integer,routing_key_id:character varying(128),object_key:text,canonicalizer:character varying(16)," +
        "event_bytes:bytea,semantic_hash:bytea,state:character varying(16),created_at:timestamp with time zone," +
        "object_version:text,ciphertext_hash:bytea,object_created_at:timestamp with time zone,retain_until:timestamp with time zone," +
        "verified_at:timestamp with time zone,verification_bytes:bytea,verification_hash:bytea,applied_at:timestamp with time zone",
    linkedMapOf(
        "pk_complaint_publications" to "event_id",
        "uq_complaint_publication_scope" to "event_id,data_scope_id",
        "uq_complaint_publication_key" to "object_key",
        "idx_complaint_publication_pending" to "state,created_at,event_id",
        "idx_complaint_publication_epoch" to "data_scope_id,writer_generation,journal_epoch,event_id",
        "idx_complaint_publication_manifest" to "data_scope_id,writer_generation,object_key",
    ),
)

private val RESERVATION = DeleteAllEnvelopeProfile(
    "complaint_recovery_capacity_reservations",
    OwnerDeleteAllCapacityCharges.RESERVATION[ComplaintCapacityCounter.STORAGE_BYTES],
    "event_id:character varying(43),data_scope_id:uuid,test_only:boolean,publication_ref:character varying(43),state:character varying(16)," +
        "accounting_version:smallint,reserved_amounts:bigint[],converted_amounts:bigint[]," +
        "created_at:timestamp with time zone,converted_at:timestamp with time zone",
    linkedMapOf(
        "pk_complaint_recovery_reservations" to "event_id,data_scope_id",
        "uq_complaint_recovery_event" to "event_id",
        "idx_complaint_recovery_publication" to "publication_ref,data_scope_id",
        "idx_complaint_recovery_scope" to "data_scope_id,event_id",
    ),
)

private val APPLIED = DeleteAllEnvelopeProfile(
    "complaint_deletion_journal_applied",
    OwnerDeleteAllCapacityCharges.APPLIED[ComplaintCapacityCounter.STORAGE_BYTES],
    "object_key:text,object_version:text,event_id:character varying(43),ciphertext_hash:bytea,writer_generation:uuid,journal_epoch:bigint," +
        "event_kind:character varying(32),target_count:integer,data_scope_id:uuid,test_only:boolean,applied_at:timestamp with time zone",
    linkedMapOf(
        "pk_complaint_journal_applied" to "object_key,object_version",
        "uq_complaint_applied_scope_kind" to "object_key,object_version,data_scope_id,event_kind",
        "idx_complaint_applied_event" to "event_id",
        "idx_complaint_applied_scope" to "data_scope_id,writer_generation,journal_epoch",
    ),
)

private val RETIREMENT = DeleteAllEnvelopeProfile(
    "complaint_deletion_journal_retirements",
    OwnerDeleteAllCapacityCharges.RETIREMENT[ComplaintCapacityCounter.STORAGE_BYTES],
    "object_key:text,object_version:text,data_scope_id:uuid,test_only:boolean,event_kind:character varying(32),state:character varying(16)," +
        "authorization_catalog_generation:bigint,authorization_catalog_hash:bytea,restore_floor:timestamp with time zone,authorization_bytes:bytea," +
        "authorization_hash:bytea,authorized_at:timestamp with time zone,completion_catalog_generation:bigint,completion_catalog_hash:bytea," +
        "completion_bytes:bytea,completion_hash:bytea,completed_at:timestamp with time zone",
    linkedMapOf(
        "pk_complaint_retirements" to "object_key,object_version",
        "idx_complaint_retirement_applied" to "object_key,object_version,data_scope_id,event_kind",
        "idx_complaint_retirement_state" to "state,authorized_at",
    ),
)

private val PROFILES = listOf(RECEIPT, PUBLICATION, RESERVATION, APPLIED, RETIREMENT)
