package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Connection

class ComplaintControlConstraintsIT : ComplaintFixtureTest() {
    @Test
    fun `closed control cannot open with missing configuration catalog or projection state`() {
        connection.expectSqlFailure(controlUpdate("maintenance_closed=false"), constraint = "chk_complaint_control_closed")
        connection.expectSqlFailure(controlUpdate("creation_closed=false"), constraint = "chk_complaint_control_closed")
        connection.configureControl()
        connection.exec(controlUpdate("pending_projection_token='63000000-0000-4000-8000-000000000001'"))
        connection.expectSqlFailure(controlUpdate("creation_closed=false"), constraint = "chk_complaint_control_closed")
        connection.exec(controlUpdate("pending_projection_token=NULL,maintenance_closed=false,creation_closed=false"))
        for (column in listOf("desired_configuration_hash", "database_identity", "event_writer_generation")) {
            connection.expectSqlFailure(controlUpdate("$column=NULL"), constraint = "chk_complaint_control_closed")
        }
        connection.exec(controlUpdate("maintenance_closed=true,creation_closed=true"))
        connection.expectSqlFailure(
            controlUpdate("pending_projection_token='63000000-0000-4000-8000-000000000009'"),
            "23503",
            "fk_complaint_control_projection",
        )
        connection.exec(controlUpdate("pending_projection_token='63000000-0000-4000-8000-000000000001'"))
        connection.expectSqlFailure("DELETE FROM complaint_catalog_mutations", "23503", "fk_complaint_control_projection")
    }

    @Test
    fun `configuration and catalog binding tuples cannot be partially populated`() {
        for ((set, constraint) in listOf(
            "database_identity='$WRITER'" to "chk_complaint_control_configuration",
            "restore_identity='$WRITER'" to "chk_complaint_control_configuration",
            "event_writer_generation='$LIVE_SCOPE'" to "chk_complaint_control_configuration",
            "desired_configuration_hash=decode('00','hex')" to "chk_complaint_control_configuration",
            "accepted_catalog_generation=1" to "chk_complaint_control_catalog",
            "accepted_catalog_hash=$FIXTURE_DIGEST" to "chk_complaint_control_catalog",
            "trust_bundle_hash=$FIXTURE_DIGEST" to "chk_complaint_control_catalog",
            "catalog_writer_generation='$WRITER'" to "chk_complaint_control_catalog",
        )) {
            connection.expectSqlFailure(controlUpdate(set), constraint = constraint)
        }
        connection.configureControl()
        for (column in listOf("accepted_catalog_generation", "accepted_catalog_hash", "trust_bundle_hash", "catalog_writer_generation")) {
            connection.expectSqlFailure(controlUpdate("$column=NULL"), constraint = "chk_complaint_control_catalog")
        }
    }

    @Test
    fun `scan and retention leases keep independent positive fencing and all-or-none owners`() {
        for (prefix in listOf("lease", "retention_lease")) {
            connection.withRollbackPoint {
                connection.expectSqlFailure(controlUpdate("${prefix}_owner='$WRITER'"), constraint = "chk_complaint_control_lease")
                connection.expectSqlFailure(controlUpdate("${prefix}_expires_at=$FIXTURE_INSTANT"), constraint = "chk_complaint_control_lease")
                connection.exec(controlUpdate("${prefix}_owner='$WRITER',${prefix}_token=1,${prefix}_expires_at=$FIXTURE_INSTANT"))
                for (set in listOf("${prefix}_owner=NULL", "${prefix}_expires_at=NULL", "${prefix}_token=0", "${prefix}_token=-1")) {
                    connection.expectSqlFailure(controlUpdate(set), constraint = "chk_complaint_control_lease")
                }
                connection.expectSqlFailure(controlUpdate("${prefix}_expires_at='infinity'"), constraint = "chk_complaint_control_times")
                connection.exec(controlUpdate("${prefix}_owner=NULL,${prefix}_expires_at=NULL"))
                assertEquals(listOf("1"), connection.strings("SELECT ${prefix}_token FROM complaint_journal_control"))
            }
        }
    }

    @Test
    fun `seal intent and verification retain complete bounded exact evidence`() {
        connection.exec(
            controlUpdate(
                "seal_state='SEAL_PREPARED',seal_epoch=1,seal_writer_generation='$WRITER'," +
                    "seal_operation_token='63000000-0000-4000-8000-000000000001',seal_object_key='fixture/seal'," +
                    "seal_bytes=$FIXTURE_BYTES,seal_hash=$FIXTURE_HASH",
            ),
        )
        for (column in listOf("seal_state", "seal_epoch", "seal_writer_generation", "seal_operation_token", "seal_object_key", "seal_bytes", "seal_hash")) {
            connection.expectSqlFailure(controlUpdate("$column=NULL"), constraint = "chk_complaint_control_seal")
        }
        connection.expectSqlFailure(controlUpdate("seal_state='SEAL_VERIFIED'"), constraint = "chk_complaint_control_seal")
        connection.exec(
            controlUpdate(
                "seal_state='SEAL_VERIFIED',seal_object_version='version-S',seal_ciphertext_hash=$FIXTURE_DIGEST," +
                    "seal_retain_until=$FIXTURE_INSTANT,seal_verified_at=$FIXTURE_INSTANT,seal_verification_bytes=$FIXTURE_BYTES," +
                    "seal_verification_hash=$FIXTURE_HASH",
            ),
        )
        for (column in listOf(
            "seal_object_version",
            "seal_ciphertext_hash",
            "seal_retain_until",
            "seal_verified_at",
            "seal_verification_bytes",
            "seal_verification_hash",
        )) {
            connection.expectSqlFailure(controlUpdate("$column=NULL"), constraint = "chk_complaint_control_seal")
        }
        for (set in listOf("seal_hash=$FIXTURE_DIGEST", "seal_object_version='null'", "seal_epoch=0")) {
            connection.expectSqlFailure(controlUpdate(set), constraint = "chk_complaint_control_seal")
        }
        connection.expectSqlFailure(controlUpdate("seal_verified_at='-infinity'"), constraint = "chk_complaint_control_times")
    }

    @Test
    fun `checkpoint descriptor keeps every binding count hash and finite timestamp`() {
        connection.markControlCheckpoint()
        val columns = listOf(
            "checkpoint_generation", "checkpoint_fencing_token", "checkpoint_catalog_generation",
            "checkpoint_catalog_hash", "checkpoint_writer_generation", "checkpoint_cutoff_epoch", "checkpoint_configuration_hash",
            "checkpoint_database_identity", "checkpoint_restore_identity", "checkpoint_schema", "checkpoint_started_at",
            "checkpoint_completed_at", "checkpoint_object_count", "checkpoint_byte_count", "checkpoint_result", "checkpoint_bytes", "checkpoint_hash",
        )
        for (column in columns) {
            connection.expectSqlFailure(controlUpdate("$column=NULL"), constraint = "chk_complaint_control_checkpoint")
        }
        for (set in listOf(
            "checkpoint_object_count=-1",
            "checkpoint_byte_count=-1",
            "checkpoint_result='FAILED'",
            "checkpoint_schema=2",
            "checkpoint_hash=$FIXTURE_DIGEST",
            "checkpoint_fencing_token=0",
        )) {
            connection.expectSqlFailure(controlUpdate(set), constraint = "chk_complaint_control_checkpoint")
        }
        for (column in listOf("checkpoint_started_at", "checkpoint_completed_at")) {
            connection.expectSqlFailure(controlUpdate("$column='infinity'"), constraint = "chk_complaint_control_times")
        }
        // No CHECK claims that a structurally valid descriptor authenticates a successful scan.
        assertEquals(listOf("SUCCESS"), connection.strings("SELECT checkpoint_result FROM complaint_journal_control"))
    }

    companion object {
        private const val WRITER = "64000000-0000-4000-8000-000000000001"
    }
}

fun controlUpdate(set: String): String = "UPDATE complaint_journal_control SET $set WHERE data_scope_id='$LIVE_SCOPE'"

fun Connection.configureControl() = exec(
    controlUpdate(
        "desired_configuration_hash=$FIXTURE_DIGEST,database_identity='64000000-0000-4000-8000-000000000002'," +
            "restore_identity='64000000-0000-4000-8000-000000000003',event_writer_generation='64000000-0000-4000-8000-000000000001'," +
            "accepted_catalog_generation=1,accepted_catalog_hash=$FIXTURE_DIGEST,trust_bundle_hash=$FIXTURE_DIGEST," +
            "catalog_writer_generation='64000000-0000-4000-8000-000000000001'",
    ),
)

fun Connection.markControlCheckpoint() = exec(
    controlUpdate(
        "checkpoint_generation=1,checkpoint_fencing_token=1,checkpoint_catalog_generation=1,checkpoint_catalog_hash=$FIXTURE_DIGEST," +
            "checkpoint_writer_generation='64000000-0000-4000-8000-000000000001',checkpoint_cutoff_epoch=1," +
            "checkpoint_configuration_hash=$FIXTURE_DIGEST,checkpoint_database_identity='64000000-0000-4000-8000-000000000002'," +
            "checkpoint_restore_identity='64000000-0000-4000-8000-000000000003',checkpoint_schema=1," +
            "checkpoint_started_at=$FIXTURE_INSTANT,checkpoint_completed_at=$FIXTURE_INSTANT,checkpoint_object_count=0," +
            "checkpoint_byte_count=0,checkpoint_result='SUCCESS',checkpoint_bytes=$FIXTURE_BYTES,checkpoint_hash=$FIXTURE_HASH",
    ),
)
