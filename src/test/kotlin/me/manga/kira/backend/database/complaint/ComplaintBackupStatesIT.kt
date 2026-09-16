package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Opt-in rich-state tests; the shared constraint fixture deliberately remains unchanged. */
class ComplaintBackupStatesIT : ComplaintFixtureTest() {
    @BeforeEach
    fun addBackupStateOverlay() {
        connection.exec(complaintResource("fixtures/complaint/v14-backup-states.sql"))
    }

    @Test
    fun `explicit coverage rejects missing relationships and reassigned deletion evidence`() {
        connection.assertComplaintBackupStates()
        val before = connection.tableSnapshots()
        for (mutation in listOf(
            "DELETE FROM complaints WHERE id='61000000-0000-4000-8000-000000000010'",
            "DELETE FROM complaint_recovery_capacity_reservations WHERE event_id=repeat('B',42)||'A'",
            "UPDATE complaint_idempotency_receipts r SET publication_ref=p.event_id,external_event_id=p.event_id," +
                "external_epoch=p.journal_epoch,external_object_version=p.object_version,external_ciphertext_hash=p.ciphertext_hash " +
                "FROM complaint_journal_publications p WHERE p.event_id=repeat('E',42)||'A' " +
                "AND r.idempotency_key='62000000-0000-4000-8000-000000000020'",
        )) {
            connection.withRollbackPoint {
                connection.exec(mutation)
                assertThrows(AssertionError::class.java) { connection.assertComplaintBackupStates() }
            }
            connection.assertPreserved(before)
            connection.assertComplaintBackupStates()
        }
    }

    @Test
    fun `each rich fixture reset failure restores terminal partial and scoped evidence`() {
        connection.assertComplaintBackupStates()
        val before = connection.tableSnapshots()
        val sequences = connection.sequenceValues()
        val statements = complaintResource("fixtures/complaint/reset.sql").lineSequence()
            .filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(';').map(String::trim).filter(String::isNotEmpty)
        assertTrue(statements.size > ComplaintMigrationIT.expectedTables.size)
        for (stopAfter in statements.indices) {
            connection.withRollbackPoint {
                for (statement in statements.take(stopAfter + 1)) connection.exec(statement)
                assertSqlFailure("22012") { connection.exec("SELECT 1/0") }
            }
            connection.assertPreserved(before)
            assertEquals(sequences, connection.sequenceValues(), "Rich reset failure boundary $stopAfter")
            connection.assertComplaintBackupStates()
        }
        connection.withRollbackPoint {
            connection.exec(complaintResource("fixtures/complaint/reset.sql"))
            connection.assertClosedComplaintSeeds()
            for (table in ComplaintMigrationIT.expectedTables - listOf("complaint_journal_control", "complaint_capacity_counters")) {
                assertEquals(listOf("0"), connection.strings("SELECT count(*) FROM $table"), table)
            }
        }
        connection.assertPreserved(before)
        connection.assertComplaintBackupStates()
    }
}
