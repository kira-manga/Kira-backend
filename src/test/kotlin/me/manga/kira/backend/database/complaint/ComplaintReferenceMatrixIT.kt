package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Connection

class ComplaintReferenceMatrixIT : ComplaintFixtureTest() {
    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceCases")
    fun `routing signer and external reference sites preserve byte bounds without truncation`(case: ReferenceCase) {
        case.setup(connection)
        val exact = if (case.ascii) "x".repeat(case.limit) else "ع".repeat(case.limit / 2)
        assertEquals(case.limit, exact.toByteArray(Charsets.UTF_8).size)
        for (value in listOf("x", exact)) {
            writeValue(case, value)
            assertEquals(listOf(value), connection.strings("SELECT ${case.column} FROM ${case.table}"), case.toString())
        }
        for (value in listOf("") + if (case.ascii) listOf("ع", "line\nfeed", "\u007f") else emptyList()) {
            connection.withRollbackPoint {
                assertSqlFailure("23514", case.constraint) { writeValue(case, value) }
            }
        }
        connection.withRollbackPoint {
            val error = assertSqlFailure(if (case.varchar) "22001" else "23514", if (case.varchar) null else case.constraint) {
                writeValue(case, exact + "x")
            }
            if (case.varchar) assertTrue(error.message.orEmpty().contains("character varying(${case.limit})"))
        }
        if (case.column.endsWith("object_version")) {
            connection.withRollbackPoint {
                assertSqlFailure("23514", case.constraint) { writeValue(case, "null") }
            }
        }
    }

    private fun writeValue(case: ReferenceCase, value: String) {
        connection.prepareStatement("UPDATE ${case.table} SET ${case.column}=?").use { statement ->
            statement.setString(1, value)
            assertEquals(1, statement.executeUpdate())
        }
    }

    data class ReferenceCase(
        val table: String,
        val column: String,
        val constraint: String,
        val limit: Int = 1024,
        val ascii: Boolean = false,
        val varchar: Boolean = false,
        val setup: Connection.() -> Unit = {},
    ) {
        override fun toString(): String = "$table.$column"
    }

    companion object {
        @JvmStatic
        fun referenceCases(): List<ReferenceCase> = buildList {
            add(ReferenceCase("complaint_journal_publications", "routing_key_id", "chk_complaint_publication_identity", 128, ascii = true, varchar = true))
            for (column in listOf("signer_one_id", "signer_one_algorithm", "signer_two_id", "signer_two_algorithm")) {
                add(
                    ReferenceCase("complaint_catalog_mutations", column, "chk_complaint_catalog_signers", 128, ascii = true, varchar = true) {
                        prepareFixtureSignerRotation()
                    },
                )
            }
            for ((table, constraint) in mapOf(
                "complaint_import_runs" to "chk_complaint_import_identity",
                "complaint_import_staging" to "chk_complaint_staging_identity",
                "complaint_legacy_records" to "chk_complaint_legacy_identity",
            )) {
                for ((column, maximum) in mapOf("collection_code" to 32, "key_id" to 128)) {
                    add(ReferenceCase(table, column, constraint, maximum, ascii = true, varchar = true))
                }
            }
            add(ReferenceCase("complaint_import_runs", "tool_version", "chk_complaint_import_identity", 128, ascii = true, varchar = true))
            add(
                ReferenceCase("complaint_import_staging", "rejection_code", "chk_complaint_staging_state", 64, ascii = true, varchar = true) {
                    exec("UPDATE complaint_import_staging SET state='REJECTED',assigned_id=NULL,reconciliation_code=NULL,rejection_code='FIELD_TOO_LONG'")
                },
            )
            add(ReferenceCase("complaint_idempotency_receipts", "external_object_version", "chk_complaint_receipt_external") { completeFixtureOwnerDeletion() })
            add(ReferenceCase("installation_deletion_receipts", "external_object_version", "chk_installation_receipt_phase") { completeSpecialReceipt() })
            for (column in listOf("terminal_object_key", "terminal_object_version")) {
                add(ReferenceCase("complaint_test_runs", column, "chk_complaint_run_terminal") { markRunTerminal() })
            }
            add(ReferenceCase("complaint_journal_control", "seal_object_key", "chk_complaint_control_seal", ascii = true) { verifyFixtureSeal() })
            add(ReferenceCase("complaint_journal_control", "seal_object_version", "chk_complaint_control_seal") { verifyFixtureSeal() })
            add(ReferenceCase("complaint_catalog_mutations", "object_key", "chk_complaint_catalog_bytes", ascii = true))
            add(ReferenceCase("complaint_catalog_mutations", "object_version", "chk_complaint_catalog_copies") { markCatalogComplete() })
            add(ReferenceCase("complaint_import_artifacts", "object_key", "chk_complaint_artifact_shape", ascii = true))
            add(ReferenceCase("complaint_import_artifacts", "object_version", "chk_complaint_artifact_state") { markImportArtifactVerified() })
        }
    }
}
