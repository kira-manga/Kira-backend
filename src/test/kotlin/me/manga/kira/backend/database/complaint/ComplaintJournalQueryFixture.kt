package me.manga.kira.backend.database.complaint

import java.sql.Connection
import java.util.UUID

data class QueryScanRun(val number: Int, val pass: Short) {
    val id: UUID get() = fixtureUuid(0x720, number)
    val scope: UUID get() = if (number > 28) QUERY_TEST else QUERY_LIVE
    val state: String get() = if (number <= 8) "ABANDONED" else "SCANNING"
    val key: String get() = "$id:$pass"
}

// Entries 50 and 51 share a key, so the page boundary needs the provider-version suffix.
fun scanObjectKey(scan: Int, entry: Int): String = "query/scan/%02d/%04d".format(scan, if (entry == 51) 50 else entry)

class ComplaintJournalQueryFixture {
    val publications = (0 until 8192).map { number ->
        QueryPublication(
            "publication-%05d".format(number),
            state = when {
                number < 64 -> "PREPARED"
                number < 128 -> "VERIFIED"
                else -> "APPLIED"
            },
            scope = if (number % 8 == 7) QUERY_TEST else QUERY_LIVE,
            writer = fixtureUuid(0x714, number / 2048),
            epoch = 1L + number / 8 % 32,
            created = QUERY_TIME.plusSeconds(number.toLong()),
        )
    }
    val runs = (1..32).flatMap { number -> (1..2).map { QueryScanRun(number, it.toShort()) } }

    fun seed(connection: Connection) {
        println("W02_JOURNAL_FIXTURE " + queryDigest(publications.joinToString("\n") + runs.joinToString("\n")))
        connection.exec(freshRunInsert(TEST_SCOPE))
        connection.insertQueryPublications(publications)
        connection.insertQueryFixture(
            "complaint_journal_scan_runs",
            "scan_id,pass,data_scope_id,test_only,restore_identity,desired_generation,fencing_token,writer_generation,cutoff_epoch," +
                "maximum_entries,maximum_bytes,entry_count,entry_bytes,state,started_at,finished_at",
            runs.map { run ->
                listOf(
                    run.id, run.pass, run.scope, run.scope == QUERY_TEST, fixtureUuid(0x721, 1), 1L, 1L, fixtureUuid(0x714, 1), 32L,
                    1000L, 1048576L, 256L, 256L * 512, run.state, QUERY_TIME,
                    if (run.state == "ABANDONED") QUERY_TIME.plusSeconds(1000) else null,
                )
            },
        )
        for (run in runs) {
            connection.insertQueryFixture(
                "complaint_journal_scan_entries",
                "scan_id,pass,data_scope_id,test_only,object_key,object_version,ciphertext_hash,semantic_hash,event_id,event_kind," +
                    "writer_generation,journal_epoch,entry_bytes,replay_state",
                (1..256).map { number ->
                    listOf(
                        run.id, run.pass, run.scope, run.scope == QUERY_TEST, scanObjectKey(run.number, number), "version-$number",
                        queryBytes("scan-${run.number}-$number"), queryBytes("semantic-${run.number}-$number"),
                        QueryPublication("scan-${run.number}-$number").event, "OWNER_DELETE", fixtureUuid(0x714, 1), 1L, 512L,
                        if (number <= 8) "PENDING" else "APPLIED",
                    )
                },
            )
        }
        connection.analyzeFixture(listOf("complaint_journal_publications", "complaint_journal_scan_runs", "complaint_journal_scan_entries"))
    }
}
