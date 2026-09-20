package me.manga.kira.backend.database.complaint

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Index-only upgrade on the existing owned server. Synthetic rows are not authenticated events. */
internal fun assertCutoffManifestMigration(dataSource: DataSource, populated: Boolean) {
    val schema = "cutoff_manifest_v18_" + UUID.randomUUID().toString().replace("-", "")
    fun connection(): Connection = dataSource.connection.also {
        try {
            it.exec("SET search_path TO $schema")
        } catch (failure: SQLException) {
            it.close()
            throw failure
        }
    }
    fun flyway(target: Int): Flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
        .locations("classpath:db/migration").target(MigrationVersion.fromVersion(target.toString())).cleanDisabled(true).load()
    dataSource.connection.use { it.exec("CREATE SCHEMA $schema") }
    try {
        assertEquals(19, flyway(17).migrate().migrationsExecuted) // Includes V13.1 and V13.2.
        connection().use { sql ->
            sql.assertClosedComplaintSeeds()
            if (populated) populateManifestIndexRows(sql)
        }
        val before = connection().use { it.tableSnapshots() }
        val declarations = connection().use { it.schemaSnapshot() }
        val sequences = connection().use { it.sequenceValues() }
        assertEquals(1, flyway(18).migrate().migrationsExecuted)
        assertTrue(flyway(18).validateWithResult().validationSuccessful)
        assertEquals(0, flyway(18).migrate().migrationsExecuted)
        connection().use { sql ->
            sql.assertPreserved(before)
            sql.assertClosedComplaintSeeds()
            assertEquals(sequences, sql.sequenceValues())
            val after = sql.schemaSnapshot()
            assertEquals(declarations, after.filterNot(::manifestIndexAddition))
            assertEquals(2, after.size - declarations.size) // Definition and index-state only.
            assertEquals(
                "index-state|$MANIFEST_INDEX|true|true|true",
                after.single { it.startsWith("index-state|$MANIFEST_INDEX|") },
            )
            assertEquals(
                "index|complaint_journal_publications|$MANIFEST_INDEX|CREATE INDEX $MANIFEST_INDEX " +
                    "ON <schema>.complaint_journal_publications USING btree (data_scope_id, writer_generation, object_key)",
                after.single { it.startsWith("index|complaint_journal_publications|$MANIFEST_INDEX|") },
            )
            if (populated) assertManifestIndexMembership(sql)
        }
    } finally {
        dataSource.connection.use { it.exec("DROP SCHEMA $schema CASCADE") }
    }
}

private fun manifestIndexAddition(line: String): Boolean =
    line.startsWith("index|complaint_journal_publications|$MANIFEST_INDEX|") || line.startsWith("index-state|$MANIFEST_INDEX|")

/** Row-shape witnesses only. No actual journal dispatch, proof verification, erasure or W06 import. */
private fun populateManifestIndexRows(sql: Connection) {
    for ((index, key) in listOf("fixture/Z", "fixture/a", "fixture/z").withIndex()) {
        val id = listOf("A", "B", "C")[index]
        sql.exec(
            "INSERT INTO complaint_journal_publications (event_id,data_scope_id,test_only,writer_generation,journal_epoch,event_kind," +
                "target_count,routing_key_id,object_key,canonicalizer,event_bytes,semantic_hash,state,created_at) VALUES " +
                "(repeat('$id',42)||'A','$LIVE_SCOPE',false,'$MANIFEST_WRITER',1,'OWNER_DELETE',1,'fixture-route','$key','kcj-1'," +
                "$FIXTURE_BYTES,$FIXTURE_HASH,'PREPARED',$FIXTURE_INSTANT)",
        )
    }
    sql.exec(
        "UPDATE complaint_journal_publications SET state='VERIFIED',object_version='fixture-version',ciphertext_hash=$FIXTURE_HASH," +
            "object_created_at=$FIXTURE_INSTANT,retain_until='2028-01-01T00:00:00Z',verified_at=$FIXTURE_INSTANT," +
            "verification_bytes=$FIXTURE_BYTES,verification_hash=$FIXTURE_HASH WHERE object_key IN ('fixture/a','fixture/z')",
    )
    sql.exec("UPDATE complaint_journal_publications SET state='APPLIED',applied_at=$FIXTURE_INSTANT WHERE object_key='fixture/z'")
}

private fun assertManifestIndexMembership(sql: Connection) {
    val query = "SELECT object_key || '|' || state FROM complaint_journal_publications " +
        "WHERE data_scope_id='$LIVE_SCOPE' AND writer_generation='$MANIFEST_WRITER' AND journal_epoch<=1 "
    assertEquals(
        listOf("fixture/Z|PREPARED", "fixture/a|VERIFIED", "fixture/z|APPLIED"),
        sql.strings(query + "ORDER BY object_key LIMIT 3"),
    )
    assertEquals(
        listOf("fixture/a|VERIFIED", "fixture/z|APPLIED"),
        sql.strings(query + "AND object_key>'fixture/Z' ORDER BY object_key LIMIT 2"),
    )
}

private const val MANIFEST_INDEX = "idx_complaint_publication_manifest"
private const val MANIFEST_WRITER = "64000000-0000-4000-8000-000000000001"
