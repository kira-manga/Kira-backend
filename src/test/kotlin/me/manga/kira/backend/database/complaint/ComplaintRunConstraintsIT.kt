package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Connection

class ComplaintRunConstraintsIT : ComplaintFixtureTest() {
    @Test
    fun `one nonterminal run is enforced across active sealed and purging states`() {
        for (state in listOf("ACTIVE", "SEALED", "PURGING")) {
            connection.withRollbackPoint {
                when (state) {
                    "SEALED" -> connection.exec(runUpdate("state='SEALED',sealed_at=$FIXTURE_INSTANT"))
                    "PURGING" -> connection.markRunTerminal()
                }
                connection.expectSqlFailure(
                    connection.copyRowSql("complaint_test_runs", mapOf("data_scope_id" to "'$OTHER_RUN'")),
                    "23505",
                    "uq_complaint_run_nonterminal",
                )
            }
        }
    }

    @Test
    fun `fully evidenced purged history permits a fresh run but cannot reuse its permanent scope`() {
        connection.markRunTerminal()
        connection.expectSqlFailure(runUpdate("state='PURGED',purged_at=$FIXTURE_INSTANT"), constraint = "chk_complaint_run_state")
        connection.exec(runUpdate("state='PURGED',purged_at=$FIXTURE_INSTANT,unused_reserve=$ZERO_VECTOR"))
        connection.exec(freshRunInsert(OTHER_RUN))
        assertEquals(listOf("ACTIVE", "PURGED"), connection.strings("SELECT state FROM complaint_test_runs ORDER BY state"))
        connection.expectSqlFailure(
            connection.copyRowSql("complaint_test_runs", emptyMap(), "data_scope_id='$TEST_SCOPE'"),
            "23505",
            "pk_complaint_test_runs",
        )
    }

    @Test
    fun `every required terminal tuple member is present rather than passed through SQL null`() {
        connection.markRunTerminal()
        val groups = mapOf(
            "chk_complaint_run_seals" to listOf(
                "final_ordinary_epoch",
                "terminal_seal_epoch",
                "generation_seal_count",
                "generation_seal_root",
                "seal_set_bytes",
                "seal_set_hash",
            ),
            "chk_complaint_run_manifests" to listOf(
                "event_manifest_count",
                "event_manifest_root",
                "installation_manifest_count",
                "installation_manifest_root",
                "installation_chunk_count",
                "retired_count",
                "deleted_count",
            ),
            "chk_complaint_run_denial" to listOf("permanent_denial_bytes", "permanent_denial_hash"),
            "chk_complaint_run_terminal" to listOf(
                "terminal_object_key",
                "terminal_object_version",
                "terminal_ciphertext_hash",
                "terminal_catalog_generation",
                "terminal_catalog_hash",
            ),
            "chk_complaint_run_state" to listOf("sealed_at", "purging_at", "terminal_event_id"),
        )
        for ((constraint, columns) in groups) {
            for (column in columns) {
                connection.expectSqlFailure(runUpdate("$column=NULL"), constraint = constraint)
            }
        }
        for (column in listOf("sealed_at", "purging_at")) {
            connection.expectSqlFailure(runUpdate("$column='infinity'"), constraint = "chk_complaint_run_times")
        }
    }

    @Test
    fun `terminal manifests disposition counts and epoch chain have exact boundaries`() {
        connection.markRunTerminal()
        for (set in listOf(
            "installation_manifest_count=1",
            "event_manifest_count=-1",
            "installation_chunk_count=1",
            "retired_count=1",
            "deleted_count=1",
            "event_manifest_root=decode('00','hex')",
        )) {
            connection.expectSqlFailure(runUpdate(set), constraint = "chk_complaint_run_manifests")
        }
        connection.exec(runUpdate("enrolled_count=2,installation_manifest_count=2,installation_chunk_count=1,retired_count=1,deleted_count=1"))
        connection.expectSqlFailure(runUpdate("installation_chunk_count=3"), constraint = "chk_complaint_run_manifests")
        connection.expectSqlFailure(runUpdate("installation_chunk_count=0"), constraint = "chk_complaint_run_manifests")
        for (set in listOf("terminal_seal_epoch=final_ordinary_epoch", "generation_seal_count=0", "seal_set_hash=$FIXTURE_DIGEST")) {
            connection.expectSqlFailure(runUpdate(set), constraint = "chk_complaint_run_seals")
        }
        for (set in listOf(
            "terminal_catalog_generation=activation_catalog_generation",
            "terminal_catalog_generation=65537",
            "terminal_object_version='null'",
            "terminal_event_id=repeat('B',43)",
        )) {
            connection.expectSqlFailure(runUpdate(set), constraint = "chk_complaint_run_terminal")
        }
        connection.expectSqlFailure(runUpdate("state='ACTIVE',sealed_at=NULL,purging_at=NULL"), constraint = "chk_complaint_run_state")
    }

    @Test
    fun `run configuration rejects live scope unsupported encoding and over enrollment`() {
        connection.expectSqlFailure(runUpdate("data_scope_id='$LIVE_SCOPE',test_only=false"), constraint = "chk_complaint_run_scope")
        for (set in listOf(
            "accounting_version=2",
            "installation_limit=0",
            "enrolled_count=-1",
            "enrolled_count=11",
            "activation_catalog_generation=0",
            "activation_catalog_generation=65537",
        )) {
            connection.expectSqlFailure(runUpdate(set), constraint = "chk_complaint_run_configuration")
        }
        connection.exec(runUpdate("enrolled_count=installation_limit"))
        // Global ID reservation cannot be reused in a new run, even without a credential/content row.
        connection.expectSqlFailure(
            connection.copyRowSql(
                "complaint_installation_ids",
                mapOf("data_scope_id" to "'$TEST_SCOPE'", "test_only" to "true"),
                "state='RECOVERY_RESERVED'",
            ),
            "23505",
            "pk_complaint_installation_ids",
        )
    }

    companion object {
        private const val OTHER_RUN = "65000000-0000-4000-8000-000000000002"
    }
}

fun runUpdate(set: String): String = "UPDATE complaint_test_runs SET $set WHERE data_scope_id='$TEST_SCOPE'"

/** Storage sentinel only; no actual provider/catalog authority is inferred from these bytes. */
fun Connection.markRunTerminal() = exec(
    runUpdate(
        "state='PURGING',sealed_at=$FIXTURE_INSTANT,purging_at=$FIXTURE_INSTANT,final_ordinary_epoch=1,terminal_seal_epoch=2," +
            "generation_seal_count=1,generation_seal_root=$FIXTURE_DIGEST,seal_set_bytes=$FIXTURE_BYTES,seal_set_hash=$FIXTURE_HASH," +
            "event_manifest_count=0,event_manifest_root=$FIXTURE_DIGEST,installation_manifest_count=0," +
            "installation_manifest_root=$FIXTURE_DIGEST,installation_chunk_count=0,retired_count=0,deleted_count=0," +
            "permanent_denial_bytes=$FIXTURE_BYTES,permanent_denial_hash=$FIXTURE_HASH,terminal_event_id=$FIXTURE_EVENT," +
            "terminal_object_key='fixture/terminal',terminal_object_version='version-T',terminal_ciphertext_hash=$FIXTURE_DIGEST," +
            "terminal_catalog_generation=3,terminal_catalog_hash=$FIXTURE_DIGEST",
    ),
)

fun freshRunInsert(scope: String): String =
    "INSERT INTO complaint_test_runs(data_scope_id,test_only,state,configuration_hash,accounting_version,installation_limit," +
        "enrolled_count,original_reserve,unused_reserve,activation_catalog_generation,activation_catalog_hash,created_at) " +
        "VALUES ('$scope',true,'ACTIVE',$FIXTURE_DIGEST,1,10,0,$ZERO_VECTOR,$ZERO_VECTOR,4,$FIXTURE_DIGEST,$FIXTURE_INSTANT)"
