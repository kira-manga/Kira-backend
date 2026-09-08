package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Connection

class ComplaintJournalConstraintsIT : ComplaintFixtureTest() {
    @Test
    fun `canonical event encoding rejects nonzero pad bits and hash or byte-bound mismatches`() {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
        val lastCharacters = "AEIMQUYcgkosw048"
        for (last in alphabet) {
            assertEquals(
                listOf((last in lastCharacters).toString()),
                connection.strings(
                    "SELECT complaint_event_id_valid(repeat('A',42)||'$last')::text",
                ),
            )
        }
        for (value in listOf("NULL", "repeat('A',42)", "repeat('A',44)", "repeat('A',42)||'='")) {
            assertEquals(listOf("false"), connection.strings("SELECT complaint_event_id_valid($value)::text"))
        }
        for (size in listOf(0, 65537)) {
            connection.expectSqlFailure(
                publicationUpdate(
                    "event_bytes=decode(repeat('ab',$size),'hex')," +
                        "semantic_hash=sha256(decode(repeat('ab',$size),'hex'))",
                ),
                constraint = "chk_complaint_publication_bytes",
            )
        }
        connection.exec(publicationUpdate("event_bytes=decode(repeat('ab',65536),'hex'),semantic_hash=sha256(decode(repeat('ab',65536),'hex'))"))
        connection.expectSqlFailure(publicationUpdate("semantic_hash=$FIXTURE_DIGEST"), constraint = "chk_complaint_publication_bytes")
        connection.expectSqlFailure(publicationUpdate("canonicalizer='jsonb'"), constraint = "chk_complaint_publication_bytes")
    }

    @Test
    fun `publication kinds have exact target bounds including empty delete all and test-only terminal objects`() {
        val bounds = mapOf(
            "OWNER_DELETE" to (1..1),
            "ADMIN_DELETE" to (1..1),
            "ADMIN_BATCH_DELETE" to (1..50),
            "RETENTION" to (1..50),
            "INSTALLATION_RETIREMENT" to (1..50),
            "OWNER_DELETE_ALL" to (0..100),
        )
        for ((kind, range) in bounds) {
            connection.withRollbackPoint {
                for (count in listOf(range.first, range.last)) connection.exec(publicationUpdate("event_kind='$kind',target_count=$count"))
                for (count in listOf(range.first - 1, range.last + 1)) {
                    connection.expectSqlFailure(publicationUpdate("target_count=$count"), constraint = "chk_complaint_publication_identity")
                }
            }
        }
        connection.exec("DELETE FROM complaint_recovery_capacity_reservations")
        for ((kind, count) in listOf("INSTALLATION_MANIFEST" to 500, "TEST_RUN_PURGE" to 0)) {
            connection.withRollbackPoint {
                connection.exec(publicationUpdate("data_scope_id='$TEST_SCOPE',test_only=true,event_kind='$kind',target_count=$count"))
                connection.markPublicationVerified()
                connection.expectSqlFailure(publicationUpdate("state='APPLIED',applied_at=$FIXTURE_INSTANT"), constraint = "chk_complaint_publication_state")
                connection.expectSqlFailure(publicationUpdate("data_scope_id='$LIVE_SCOPE',test_only=false"), constraint = "chk_complaint_publication_identity")
            }
        }
    }

    @Test
    fun `publication phases cannot claim verification with missing external evidence`() {
        connection.expectSqlFailure(publicationUpdate("state='VERIFIED'"), constraint = "chk_complaint_publication_state")
        connection.markPublicationVerified()
        for (column in listOf(
            "object_version",
            "ciphertext_hash",
            "object_created_at",
            "retain_until",
            "verified_at",
            "verification_bytes",
            "verification_hash",
        )) {
            connection.expectSqlFailure(publicationUpdate("$column=NULL"), constraint = "chk_complaint_publication_state")
        }
        connection.expectSqlFailure(publicationUpdate("verification_hash=$FIXTURE_DIGEST"), constraint = "chk_complaint_publication_state")
        connection.expectSqlFailure(publicationUpdate("retain_until='infinity'"), constraint = "chk_complaint_publication_times")
        connection.exec(publicationUpdate("state='APPLIED',applied_at=$FIXTURE_INSTANT"))
        connection.expectSqlFailure(publicationUpdate("applied_at=NULL"), constraint = "chk_complaint_publication_state")
    }

    @Test
    fun `same semantic event under different exact keys and versions retains independent applied evidence`() {
        val prefix = "INSERT INTO complaint_deletion_journal_applied SELECT "
        val suffix = ",event_id,ciphertext_hash,writer_generation,journal_epoch,event_kind,target_count,data_scope_id,test_only,applied_at " +
            "FROM complaint_deletion_journal_applied WHERE object_key='fixture/applied' AND object_version='version-A'"
        connection.exec(prefix + "'fixture/other','version-A'" + suffix)
        connection.exec(prefix + "'fixture/applied','version-B'" + suffix)
        connection.expectSqlFailure(prefix + "'fixture/applied','version-A'" + suffix, "23505", "pk_complaint_journal_applied")
        assertEquals(listOf("3"), connection.strings("SELECT count(*) FROM complaint_deletion_journal_applied WHERE event_id=repeat('B',42)||'A'"))
        connection.exec("UPDATE complaint_recovery_capacity_reservations SET state='CONVERTED',converted_amounts=$ZERO_VECTOR,converted_at=$FIXTURE_INSTANT")
        connection.exec("DELETE FROM complaint_journal_publications")
        assertEquals(listOf("3"), connection.strings("SELECT count(*) FROM complaint_deletion_journal_applied"))
    }

    @Test
    fun `retirement remains exact scoped live ordinary evidence with both authorization and completion retained`() {
        val update = "UPDATE complaint_deletion_journal_retirements SET "
        connection.expectSqlFailure(update + "object_version='wrong-version'", "23503", "fk_complaint_retirement_applied")
        connection.expectSqlFailure(update + "data_scope_id='$TEST_SCOPE',test_only=true", constraint = "chk_complaint_retirement_scope")
        connection.expectSqlFailure(update + "event_kind='INSTALLATION_MANIFEST'", constraint = "chk_complaint_retirement_scope")
        connection.expectSqlFailure(update + "state='COMPLETED'", constraint = "chk_complaint_retirement_state")
        connection.exec(
            update + "state='COMPLETED',completion_catalog_generation=3,completion_catalog_hash=$FIXTURE_DIGEST," +
                "completion_bytes=$FIXTURE_BYTES,completion_hash=$FIXTURE_HASH,completed_at=$FIXTURE_INSTANT",
        )
        for (column in listOf("completion_catalog_generation", "completion_catalog_hash", "completion_bytes", "completion_hash", "completed_at")) {
            connection.expectSqlFailure(update + "$column=NULL", constraint = "chk_complaint_retirement_state")
        }
        connection.expectSqlFailure("DELETE FROM complaint_deletion_journal_applied", "23503", "fk_complaint_retirement_applied")
        assertEquals(
            listOf("true"),
            connection.strings(
                "SELECT (authorization_catalog_generation=2 AND authorization_bytes IS NOT NULL)::text " +
                    "FROM complaint_deletion_journal_retirements",
            ),
        )
    }

    @Test
    fun `reserved detachment fails and converted detachment preserves permanent scope and event`() {
        connection.expectSqlFailure("DELETE FROM complaint_journal_publications", constraint = "chk_complaint_recovery_state")
        val update = "UPDATE complaint_recovery_capacity_reservations SET "
        connection.expectSqlFailure(update + "publication_ref=repeat('B',42)||'A'", constraint = "chk_complaint_recovery_identity")
        connection.expectSqlFailure(
            update + "state='CONVERTED',converted_amounts=array_fill(11::bigint,ARRAY[22]),converted_at=$FIXTURE_INSTANT",
            constraint = "chk_complaint_recovery_state",
        )
        connection.exec(update + "state='CONVERTED',converted_amounts=array_fill(10::bigint,ARRAY[22]),converted_at=$FIXTURE_INSTANT")
        connection.exec("DELETE FROM complaint_journal_publications")
        assertEquals(
            listOf("true"),
            connection.strings(
                "SELECT (event_id=repeat('A',43) AND data_scope_id='$LIVE_SCOPE' AND NOT test_only " +
                    "AND publication_ref IS NULL AND converted_amounts=reserved_amounts)::text FROM complaint_recovery_capacity_reservations",
            ),
        )
    }

    @Test
    fun `reserve can precede publication only when exact scoped reference exists at commit`() {
        for (mode in listOf("exact", "missing", "wrong-scope")) {
            database.schema { isolated ->
                isolated.flyway().migrate()
                isolated.connection().use { transaction ->
                    transaction.autoCommit = false
                    transaction.exec(
                        "INSERT INTO complaint_recovery_capacity_reservations(event_id,data_scope_id,test_only,publication_ref,state," +
                            "accounting_version,reserved_amounts,created_at) VALUES ($FIXTURE_EVENT,'$LIVE_SCOPE',false,$FIXTURE_EVENT,'RESERVED',1," +
                            "$ZERO_VECTOR,$FIXTURE_INSTANT)",
                    )
                    if (mode != "missing") transaction.exec(publicationInsert(if (mode == "exact") LIVE_SCOPE else TEST_SCOPE))
                    if (mode == "exact") {
                        transaction.commit()
                    } else {
                        assertSqlFailure("23503", "fk_complaint_recovery_publication") { transaction.commit() }
                        transaction.rollback()
                    }
                }
                assertEquals(listOf(if (mode == "exact") "1" else "0"), isolated.strings("SELECT count(*) FROM complaint_recovery_capacity_reservations"))
            }
        }
    }

    @Test
    fun `simultaneous incompressible maximum key and version fit every composite B tree and foreign key`() {
        val key = connection.strings("SELECT string_agg(md5(n::text||'key'),'' ORDER BY n) FROM generate_series(1,32) n").single()
        val version = connection.strings("SELECT string_agg(md5(n::text||'version'),'' ORDER BY n) FROM generate_series(1,32) n").single()
        assertEquals(1024, key.length)
        assertEquals(1024, version.length)
        connection.exec("DELETE FROM complaint_deletion_journal_retirements")
        connection.exec("UPDATE complaint_deletion_journal_applied SET object_key=${sqlText(key)},object_version=${sqlText(version)}")
        connection.exec(
            "INSERT INTO complaint_deletion_journal_retirements(object_key,object_version,data_scope_id,test_only,event_kind,state," +
                "authorization_catalog_generation,authorization_catalog_hash,restore_floor,authorization_bytes,authorization_hash,authorized_at) " +
                "VALUES (${sqlText(key)},${sqlText(version)},'$LIVE_SCOPE',false,'OWNER_DELETE','AUTHORIZED',2,$FIXTURE_DIGEST," +
                "$FIXTURE_INSTANT,$FIXTURE_BYTES,$FIXTURE_HASH,$FIXTURE_INSTANT)",
        )
        connection.exec("UPDATE complaint_journal_scan_entries SET object_key=${sqlText(key)},object_version=${sqlText(version)}")
        connection.exec(publicationUpdate("object_key=${sqlText(key)}"))
        connection.markPublicationVerified()
        connection.exec(publicationUpdate("object_version=${sqlText(version)}"))
        for (table in listOf("complaint_deletion_journal_applied", "complaint_journal_scan_entries", "complaint_journal_publications")) {
            for (column in listOf("object_key", "object_version")) {
                connection.expectSqlFailure("UPDATE $table SET $column=repeat('x',1025)")
            }
        }
    }
}

fun publicationUpdate(set: String): String = "UPDATE complaint_journal_publications SET $set"

fun Connection.markPublicationVerified() = exec(
    publicationUpdate(
        "state='VERIFIED',object_version='version-A',ciphertext_hash=$FIXTURE_DIGEST,object_created_at=$FIXTURE_INSTANT," +
            "retain_until=$FIXTURE_INSTANT+interval '13 months',verified_at=$FIXTURE_INSTANT," +
            "verification_bytes=$FIXTURE_BYTES,verification_hash=$FIXTURE_HASH",
    ),
)

fun publicationInsert(scope: String): String =
    "INSERT INTO complaint_journal_publications(event_id,data_scope_id,test_only,writer_generation,journal_epoch,event_kind," +
        "target_count,routing_key_id,object_key,canonicalizer,event_bytes,semantic_hash,state,created_at) " +
        "VALUES ($FIXTURE_EVENT,'$scope',${scope != LIVE_SCOPE},'64000000-0000-4000-8000-000000000001',1,'OWNER_DELETE',1," +
        "'fixture-route','fixture/new','kcj-1',$FIXTURE_BYTES,$FIXTURE_HASH,'PREPARED',$FIXTURE_INSTANT)"
