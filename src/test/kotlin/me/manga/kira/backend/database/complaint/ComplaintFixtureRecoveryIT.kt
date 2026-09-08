package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Transactional synthetic erasure proof, not implementation of the W04 authorized purge worker. */
class ComplaintFixtureRecoveryIT : ComplaintFixtureTest() {
    @Test
    fun `failure at every reset statement restores all fixture rows relationships and sequence values`() {
        val before = connection.tableSnapshots()
        val sequences = connection.sequenceValues()
        // This fixed fixture contains only simple DELETE/INSERT statements; discard full-line
        // comments before splitting, including the safety comment's prose semicolon.
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
            assertEquals(sequences, connection.sequenceValues(), "Failure boundary $stopAfter")
        }
    }

    @Test
    fun `completed scoped reset can be rolled back without losing retained publication or mapping evidence`() {
        val before = connection.tableSnapshots()
        connection.withRollbackPoint {
            connection.exec(complaintResource("fixtures/complaint/reset.sql"))
            connection.assertClosedComplaintSeeds()
            for (table in ComplaintMigrationIT.expectedTables - listOf("complaint_journal_control", "complaint_capacity_counters")) {
                assertEquals(listOf("0"), connection.strings("SELECT count(*) FROM $table"), table)
            }
        }
        connection.assertPreserved(before)
    }
}
