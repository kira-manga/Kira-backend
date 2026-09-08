package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ComplaintEventKindMatrixIT : ComplaintFixtureTest() {
    @ParameterizedTest(name = "{0}")
    @MethodSource("ordinaryBounds")
    fun `applied evidence enforces its own event kind count endpoints`(case: EventBounds) {
        connection.exec("DELETE FROM complaint_deletion_journal_retirements")
        for (count in listOf(case.minimum, case.maximum)) {
            connection.exec("UPDATE complaint_deletion_journal_applied SET event_kind='${case.kind}',target_count=$count")
            assertEquals(listOf(count.toString()), connection.strings("SELECT target_count FROM complaint_deletion_journal_applied"))
        }
        for (count in listOf(case.minimum - 1, case.maximum + 1)) {
            connection.expectSqlFailure("UPDATE complaint_deletion_journal_applied SET target_count=$count", constraint = "chk_complaint_applied_kind")
        }
    }

    @Test
    fun `test-only manifest and terminal publication endpoints cannot appear in applied evidence`() {
        connection.exec("DELETE FROM complaint_recovery_capacity_reservations; DELETE FROM complaint_deletion_journal_retirements")
        for ((kind, minimum, maximum) in listOf(EventBounds("INSTALLATION_MANIFEST", 1, 500), EventBounds("TEST_RUN_PURGE", 0, 0))) {
            for (count in setOf(minimum, maximum)) {
                connection.exec(publicationUpdate("data_scope_id='$TEST_SCOPE',test_only=true,event_kind='$kind',target_count=$count"))
                connection.expectSqlFailure(
                    "UPDATE complaint_deletion_journal_applied SET data_scope_id='$TEST_SCOPE',test_only=true,event_kind='$kind',target_count=$count",
                    constraint = "chk_complaint_applied_kind",
                )
            }
            for (count in listOf(minimum - 1, maximum + 1)) {
                connection.expectSqlFailure(publicationUpdate("target_count=$count"), constraint = "chk_complaint_publication_identity")
            }
        }
        connection.expectSqlFailure(publicationUpdate("event_kind='EPOCH_SEAL'"), constraint = "chk_complaint_publication_identity")
        connection.expectSqlFailure("UPDATE complaint_deletion_journal_applied SET event_kind='EPOCH_SEAL'", constraint = "chk_complaint_applied_kind")
    }

    data class EventBounds(val kind: String, val minimum: Int, val maximum: Int) {
        override fun toString(): String = "$kind $minimum..$maximum"
    }

    companion object {
        @JvmStatic
        fun ordinaryBounds(): List<EventBounds> = listOf(
            EventBounds("OWNER_DELETE", 1, 1),
            EventBounds("ADMIN_DELETE", 1, 1),
            EventBounds("ADMIN_BATCH_DELETE", 1, 50),
            EventBounds("RETENTION", 1, 50),
            EventBounds("INSTALLATION_RETIREMENT", 1, 50),
            EventBounds("OWNER_DELETE_ALL", 0, 100),
        )
    }
}
