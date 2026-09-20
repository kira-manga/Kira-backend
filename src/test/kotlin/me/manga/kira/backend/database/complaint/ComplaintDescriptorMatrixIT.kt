package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Connection

class ComplaintDescriptorMatrixIT : ComplaintFixtureTest() {
    @ParameterizedTest(name = "{0}")
    @MethodSource("descriptorCases")
    fun `persisted descriptor sites enforce exact minimum maximum and content hash`(case: DescriptorCase) {
        case.setup(connection)
        for (size in listOf(1, 65536)) {
            val bytes = "decode(repeat('ab',$size),'hex')"
            connection.exec("UPDATE ${case.table} SET ${case.bytes}=$bytes,${case.hash}=sha256($bytes)")
            assertEquals(listOf(size.toString()), connection.strings("SELECT octet_length(${case.bytes}) FROM ${case.table}"))
        }
        for (size in listOf(0, 65537)) {
            val bytes = "decode(repeat('ab',$size),'hex')"
            connection.expectSqlFailure("UPDATE ${case.table} SET ${case.bytes}=$bytes,${case.hash}=sha256($bytes)", constraint = case.constraint)
        }
        connection.expectSqlFailure("UPDATE ${case.table} SET ${case.hash}=$FIXTURE_DIGEST", constraint = case.constraint)
    }

    data class DescriptorCase(val table: String, val bytes: String, val hash: String, val constraint: String, val setup: Connection.() -> Unit = {}) {
        override fun toString(): String = "$table.$bytes"
    }

    companion object {
        @JvmStatic
        fun descriptorCases(): List<DescriptorCase> = listOf(
            DescriptorCase("complaint_journal_publications", "event_bytes", "semantic_hash", "chk_complaint_publication_bytes"),
            DescriptorCase("complaint_journal_publications", "verification_bytes", "verification_hash", "chk_complaint_publication_state") {
                markPublicationVerified()
            },
            DescriptorCase("complaint_deletion_journal_retirements", "authorization_bytes", "authorization_hash", "chk_complaint_retirement_authorization"),
            DescriptorCase("complaint_deletion_journal_retirements", "completion_bytes", "completion_hash", "chk_complaint_retirement_state") {
                completeFixtureRetirement()
            },
            DescriptorCase("complaint_test_runs", "seal_set_bytes", "seal_set_hash", "chk_complaint_run_seals") { markRunTerminal() },
            DescriptorCase("complaint_test_runs", "permanent_denial_bytes", "permanent_denial_hash", "chk_complaint_run_denial") { markRunTerminal() },
            DescriptorCase("complaint_journal_control", "seal_bytes", "seal_hash", "chk_complaint_control_seal") { verifyFixtureSeal() },
            DescriptorCase("complaint_journal_control", "seal_verification_bytes", "seal_verification_hash", "chk_complaint_control_seal") {
                verifyFixtureSeal()
            },
            DescriptorCase("complaint_journal_control", "checkpoint_bytes", "checkpoint_hash", "chk_complaint_control_checkpoint") { markControlCheckpoint() },
            DescriptorCase("complaint_catalog_mutations", "primary_evidence_bytes", "primary_evidence_hash", "chk_complaint_catalog_copies") {
                markCatalogComplete()
            },
            DescriptorCase("complaint_catalog_mutations", "replica_evidence_bytes", "replica_evidence_hash", "chk_complaint_catalog_copies") {
                markCatalogComplete()
            },
            DescriptorCase("complaint_import_staging", "normalized_bytes", "normalized_hash", "chk_complaint_staging_identity"),
            DescriptorCase("complaint_import_artifacts", "primary_evidence_bytes", "primary_evidence_hash", "chk_complaint_artifact_state") {
                markImportArtifactVerified()
            },
            DescriptorCase("complaint_import_artifacts", "replica_evidence_bytes", "replica_evidence_hash", "chk_complaint_artifact_state") {
                markImportArtifactVerified()
            },
        )
    }
}
