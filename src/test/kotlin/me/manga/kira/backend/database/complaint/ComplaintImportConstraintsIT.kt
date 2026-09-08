package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Connection

class ComplaintImportConstraintsIT : ComplaintFixtureTest() {
    @Test
    fun `one staged or sealed import and one promoted snapshot prevent overlapping cutovers`() {
        for (state in listOf("STAGING", "SEALED")) {
            connection.withRollbackPoint {
                if (state == "SEALED") connection.markImportSealed()
                connection.expectSqlFailure(copyImport(), "23505", "uq_complaint_import_nonterminal")
            }
        }
        connection.markImportSealed()
        connection.exec(importUpdate("state='PROMOTED',promoted_at=$FIXTURE_INSTANT,catalog_generation=2,catalog_hash=$FIXTURE_DIGEST"))
        connection.expectSqlFailure(copyImport(), "23505", "uq_complaint_import_promoted")
        connection.exec(
            connection.copyRowSql(
                "complaint_import_runs",
                mapOf(
                    "id" to "'$OTHER_IMPORT'",
                    "snapshot_hash" to FIXTURE_DIGEST,
                    "state" to "'ABORTED'",
                    "promoted_at" to "NULL",
                    "aborted_at" to FIXTURE_INSTANT,
                ),
            ),
        )
        assertEquals(listOf("ABORTED", "PROMOTED"), connection.strings("SELECT state FROM complaint_import_runs ORDER BY state"))
    }

    @Test
    fun `import count mapping and promotion gates reject partial or rejected snapshots`() {
        for (set in listOf(
            "document_count=200001",
            "accepted_count=-1",
            "rejected_count=1",
            "accounted_count=0",
            "staged_bytes=-1",
            "staged_bytes=268435457",
        )) {
            connection.expectSqlFailure(importUpdate(set), constraint = "chk_complaint_import_counts")
        }
        connection.exec(importUpdate("document_count=200000,accepted_count=200000,accounted_count=200000,staged_bytes=268435456"))
        connection.expectSqlFailure(importUpdate("mapping_root=$FIXTURE_DIGEST"), constraint = "chk_complaint_import_mapping")
        connection.markImportSealed()
        connection.expectSqlFailure(importUpdate("mapping_count=199999"), constraint = "chk_complaint_import_mapping")
        connection.expectSqlFailure(importUpdate("accepted_count=199999,rejected_count=1,mapping_count=199999"), constraint = "chk_complaint_import_state")
        connection.expectSqlFailure(importUpdate("state='PROMOTED',promoted_at=$FIXTURE_INSTANT"), constraint = "chk_complaint_import_state")
        connection.exec(importUpdate("state='PROMOTED',promoted_at=$FIXTURE_INSTANT,catalog_generation=2,catalog_hash=$FIXTURE_DIGEST"))
        connection.expectSqlFailure(importUpdate("catalog_hash=NULL"), constraint = "chk_complaint_import_catalog")
        connection.expectSqlFailure(importUpdate("sealed_at='-infinity'"), constraint = "chk_complaint_import_times")
    }

    @Test
    fun `staging identity is stable when payload changes and accepted and rejected rows have separate shapes`() {
        val update = "UPDATE complaint_import_staging SET "
        connection.expectSqlFailure(
            connection.copyRowSql(
                "complaint_import_staging",
                mapOf(
                    "record_index" to "1",
                    "assigned_id" to "'$OWNED_COMPLAINT_ID'",
                    "payload_hash" to FIXTURE_DIGEST,
                ),
            ),
            "23505",
            "uq_complaint_staging_identity",
        )
        for (set in listOf("record_index=-1", "record_index=200000", "normalized_hash=$FIXTURE_DIGEST", "document_hmac=decode('00','hex')")) {
            connection.expectSqlFailure(update + set, constraint = "chk_complaint_staging_identity")
        }
        for (set in listOf("assigned_id=NULL", "assigned_parent_id=assigned_id", "reconciliation_code=NULL", "rejection_code='BAD'")) {
            connection.expectSqlFailure(update + set, constraint = "chk_complaint_staging_state")
        }
        connection.exec(update + "normalized_bytes=decode(repeat('ab',65536),'hex'),normalized_hash=sha256(decode(repeat('ab',65536),'hex'))")
        connection.expectSqlFailure(
            update + "normalized_bytes=decode(repeat('ab',65537),'hex')," +
                "normalized_hash=sha256(decode(repeat('ab',65537),'hex'))",
            constraint = "chk_complaint_staging_identity",
        )
        connection.exec(update + "state='REJECTED',assigned_id=NULL,reconciliation_code=NULL,rejection_code='FIELD_TOO_LONG'")
        connection.expectSqlFailure(update + "rejection_code=NULL", constraint = "chk_complaint_staging_state")
        connection.expectSqlFailure(update + "assigned_parent_id='$REPLY_ID'", constraint = "chk_complaint_staging_state")
    }

    @Test
    fun `legacy content erasure preserves scoped HMAC mapping assigned identity and UTC retention`() {
        val before = connection.strings("SELECT assigned_id::text||'|'||data_scope_id::text||'|'||encode(document_hmac,'hex') FROM complaint_legacy_records")
        connection.expectSqlFailure("UPDATE complaint_legacy_records SET complaint_ref='$OWNED_COMPLAINT_ID'", constraint = "chk_complaint_legacy_identity")
        connection.exec("DELETE FROM complaints WHERE id='$LEGACY_COMPLAINT_ID'")
        assertEquals(
            before,
            connection.strings("SELECT assigned_id::text||'|'||data_scope_id::text||'|'||encode(document_hmac,'hex') FROM complaint_legacy_records"),
        )
        assertEquals(listOf("true"), connection.strings("SELECT (complaint_ref IS NULL AND NOT test_only)::text FROM complaint_legacy_records"))
        connection.exec("SET LOCAL TimeZone='America/New_York'")
        connection.exec("UPDATE complaint_legacy_records SET cutoff_at='2026-02-28T12:00:00Z',expires_at='2027-03-28T12:00:00Z',state='RESTORED'")
        connection.expectSqlFailure(
            "UPDATE complaint_legacy_records SET expires_at=cutoff_at+interval '13 months'",
            constraint = "chk_complaint_legacy_expiry",
        )
        connection.expectSqlFailure("UPDATE complaint_legacy_records SET expires_at='infinity'", constraint = "chk_complaint_legacy_expiry")
        connection.expectSqlFailure(
            connection.copyRowSql(
                "complaint_legacy_records",
                mapOf(
                    "assigned_id" to "'$OWNED_COMPLAINT_ID'",
                    "payload_hash" to FIXTURE_DIGEST,
                ),
            ),
            "23505",
            "pk_complaint_legacy_records",
        )
    }

    @Test
    fun `artifact intent and exact dual-copy verification are bounded separately from encrypted export data`() {
        val update = "UPDATE complaint_import_artifacts SET "
        for (set in listOf("chunk_index=1", "plaintext_bytes=268435457", "plaintext_bytes=-1", "artifact_kind='RAW'")) {
            connection.expectSqlFailure(update + set, constraint = "chk_complaint_artifact_shape")
        }
        connection.exec(update + "artifact_kind='RESTORE_MAP',chunk_index=262143,plaintext_bytes=262144")
        for (set in listOf("chunk_index=262144", "plaintext_bytes=262145", "plaintext_bytes=0")) {
            connection.expectSqlFailure(update + set, constraint = "chk_complaint_artifact_shape")
        }
        for ((column, hash) in listOf("context_bytes" to "context_hash", "expected_copies_bytes" to "expected_copies_hash")) {
            connection.exec(update + "$column=decode(repeat('ab',4096),'hex'),$hash=sha256(decode(repeat('ab',4096),'hex'))")
            connection.expectSqlFailure(
                update + "$column=decode(repeat('ab',4097),'hex'),$hash=sha256(decode(repeat('ab',4097),'hex'))",
                constraint = "chk_complaint_artifact_shape",
            )
        }
        connection.markImportArtifactVerified()
        for (column in listOf(
            "object_version",
            "ciphertext_hash",
            "retain_until",
            "primary_evidence_bytes",
            "primary_evidence_hash",
            "replica_evidence_bytes",
            "replica_evidence_hash",
            "verified_at",
        )) {
            connection.expectSqlFailure(update + "$column=NULL", constraint = "chk_complaint_artifact_state")
        }
        connection.expectSqlFailure(update + "object_version='null'", constraint = "chk_complaint_artifact_state")
        connection.expectSqlFailure(update + "retain_until='infinity'", constraint = "chk_complaint_artifact_times")
    }

    @Test
    fun `each import child restricts parent deletion and cannot be moved to an unrelated run`() {
        val tables = mapOf(
            "complaint_import_staging" to "fk_complaint_staging_run",
            "complaint_import_artifacts" to "fk_complaint_artifact_run",
            "complaint_legacy_records" to "fk_complaint_legacy_run",
        )
        for ((table, constraint) in tables) {
            connection.withRollbackPoint {
                for (other in tables.keys - table) connection.exec("DELETE FROM $other")
                connection.expectSqlFailure("DELETE FROM complaint_import_runs", "23503", constraint)
                connection.expectSqlFailure("UPDATE $table SET run_id='$OTHER_IMPORT'", "23503", constraint)
            }
        }
    }

    private fun copyImport(): String = connection.copyRowSql(
        "complaint_import_runs",
        mapOf("id" to "'$OTHER_IMPORT'", "snapshot_hash" to FIXTURE_DIGEST),
    )

    companion object {
        private const val OTHER_IMPORT = "67000000-0000-4000-8000-000000000002"
    }
}

fun importUpdate(set: String): String = "UPDATE complaint_import_runs SET $set WHERE id='67000000-0000-4000-8000-000000000001'"

fun Connection.markImportSealed() = exec(
    importUpdate(
        "state='SEALED',sealed_at=$FIXTURE_INSTANT,mapping_root=$FIXTURE_DIGEST,mapping_count=accepted_count",
    ),
)

fun Connection.markImportArtifactVerified() = exec(
    "UPDATE complaint_import_artifacts SET state='VERIFIED',object_version='fixture-version',ciphertext_hash=$FIXTURE_DIGEST," +
        "retain_until=$FIXTURE_INSTANT,primary_evidence_bytes=$FIXTURE_BYTES,primary_evidence_hash=$FIXTURE_HASH," +
        "replica_evidence_bytes=$FIXTURE_BYTES,replica_evidence_hash=$FIXTURE_HASH,verified_at=$FIXTURE_INSTANT",
)
