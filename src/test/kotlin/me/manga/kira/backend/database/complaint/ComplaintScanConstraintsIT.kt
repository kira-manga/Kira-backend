package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ComplaintScanConstraintsIT : ComplaintFixtureTest() {
    @Test
    fun `scan pass identity fencing and configured capacity stay finite and bounded`() {
        val update = "UPDATE complaint_journal_scan_runs SET "
        for (set in listOf("pass=0", "pass=3", "desired_generation=0", "fencing_token=0", "cutoff_epoch=0", "restore_identity='$LIVE_SCOPE'")) {
            connection.expectSqlFailure(update + set, constraint = "chk_complaint_scan_identity")
        }
        for (set in listOf("maximum_entries=0", "maximum_bytes=0", "entry_count=101", "entry_count=-1", "entry_bytes=65537", "entry_bytes=-1")) {
            connection.expectSqlFailure(update + set, constraint = "chk_complaint_scan_capacity")
        }
        connection.exec(update + "entry_count=maximum_entries,entry_bytes=maximum_bytes")
    }

    @Test
    fun `scan completion and abandonment have distinct complete evidence tuples`() {
        val update = "UPDATE complaint_journal_scan_runs SET "
        connection.expectSqlFailure(update + "state='COMPLETE'", constraint = "chk_complaint_scan_state")
        connection.exec(update + "state='COMPLETE',manifest_hash=$FIXTURE_DIGEST,finished_at=$FIXTURE_INSTANT")
        for (column in listOf("manifest_hash", "finished_at")) {
            connection.expectSqlFailure(update + "$column=NULL", constraint = "chk_complaint_scan_state")
        }
        connection.expectSqlFailure(update + "finished_at='infinity'", constraint = "chk_complaint_scan_times")
        connection.expectSqlFailure(update + "state='ABANDONED'", constraint = "chk_complaint_scan_state")
        connection.exec(update + "state='ABANDONED',manifest_hash=NULL")
    }

    @Test
    fun `scan entries retain exact pass scope and version identity until explicit child cleanup`() {
        connection.expectSqlFailure("DELETE FROM complaint_journal_scan_runs", "23503", "fk_complaint_scan_entry_run")
        connection.expectSqlFailure(connection.copyRowSql("complaint_journal_scan_entries", emptyMap()), "23505", "pk_complaint_scan_entries")
        connection.expectSqlFailure("UPDATE complaint_journal_scan_entries SET pass=2", "23503", "fk_complaint_scan_entry_run")
        connection.expectSqlFailure(
            "UPDATE complaint_journal_scan_entries SET data_scope_id='$TEST_SCOPE',test_only=true",
            "23503",
            "fk_complaint_scan_entry_run",
        )
        connection.exec(connection.copyRowSql("complaint_journal_scan_runs", mapOf("pass" to "2")))
        connection.exec(connection.copyRowSql("complaint_journal_scan_entries", mapOf("pass" to "2")))
        assertEquals(listOf("2"), connection.strings("SELECT count(*) FROM complaint_journal_scan_entries"))
        connection.exec("DELETE FROM complaint_journal_scan_entries; DELETE FROM complaint_journal_scan_runs")
        assertEquals(listOf("0"), connection.strings("SELECT count(*) FROM complaint_journal_scan_runs"))
    }

    @Test
    fun `seal entries have no event id and test terminal evidence never enters live scans`() {
        val update = "UPDATE complaint_journal_scan_entries SET "
        connection.expectSqlFailure(update + "event_id=NULL", constraint = "chk_complaint_scan_entry_kind")
        connection.exec(update + "event_kind='EPOCH_SEAL',event_id=NULL")
        connection.expectSqlFailure(update + "event_id=$FIXTURE_EVENT", constraint = "chk_complaint_scan_entry_kind")
        for (kind in listOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE", "UNKNOWN")) {
            connection.expectSqlFailure(update + "event_kind='$kind',event_id=$FIXTURE_EVENT", constraint = "chk_complaint_scan_entry_kind")
        }
        for (set in listOf("entry_bytes=0", "object_version='null'", "semantic_hash=decode('00','hex')", "journal_epoch=0")) {
            connection.expectSqlFailure(update + set, constraint = "chk_complaint_scan_entry_identity")
        }
        for (state in listOf("PENDING", "APPLIED", "VERIFIED_ONLY", "RETIRED")) connection.exec(update + "replay_state='$state'")
        connection.expectSqlFailure(update + "replay_state='SKIPPED'", constraint = "chk_complaint_scan_entry_replay")
    }
}
