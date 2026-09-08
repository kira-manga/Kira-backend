package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Connection

class ComplaintOptionalTimestampIT : ComplaintFixtureTest() {
    @ParameterizedTest(name = "{0}")
    @MethodSource("timeCases")
    fun `each populated optional timestamp rejects both infinities without masking phase validation`(case: TimeCase) {
        case.setup(connection)
        val populated = case.columns.joinToString(" AND ") { "$it IS NOT NULL AND isfinite($it)" }
        assertEquals(listOf("1"), connection.strings("SELECT count(*) FROM ${case.table} WHERE ${case.predicate} AND $populated"), case.toString())
        // Exact-duration partners must move together: infinity plus a finite interval is infinity.
        // This isolates the finite-time CHECK instead of accidentally testing the duration CHECK.
        for (value in listOf("infinity", "-infinity")) {
            val set = case.columns.joinToString(",") { "$it='$value'::timestamptz" }
            connection.expectSqlFailure("UPDATE ${case.table} SET $set WHERE ${case.predicate}", constraint = case.constraint)
        }
    }

    @Test
    fun `the explicit populated phase matrix covers every new nullable timestamp column`() {
        val expected = timeCases().flatMap { case -> case.columns.map { "${case.table}.$it" } }.toSet()
        val tables = ComplaintMigrationIT.expectedTables.joinToString(",", transform = ::sqlText)
        val actual = connection.strings(
            "SELECT table_name||'.'||column_name FROM information_schema.columns WHERE table_schema=current_schema() " +
                "AND table_name IN ($tables) AND data_type='timestamp with time zone' AND is_nullable='YES' ORDER BY 1",
        ).toSet()
        assertEquals(expected, actual, "Every optional instant needs a valid populated-phase witness")
    }

    data class TimeCase(val table: String, val columns: List<String>, val constraint: String, val predicate: String, val setup: Connection.() -> Unit) {
        override fun toString(): String = "$table.${columns.joinToString("+")}"
    }

    companion object {
        @JvmStatic
        fun timeCases(): List<TimeCase> = identityTimes() + journalTimes() + controlAndRunTimes() + importTimes()

        private fun identityTimes(): List<TimeCase> = buildList {
            addAll(
                phase(
                    "complaint_installation_ids",
                    "chk_complaint_installation_times",
                    "terminal_at",
                    predicate = "state='RETIRED'",
                    setup = {
                        exec("UPDATE complaint_installation_ids SET state='RETIRED',terminal_at=$FIXTURE_INSTANT WHERE state='RECOVERY_RESERVED'")
                    },
                ),
            )
            addAll(phase("app_installations", "chk_app_installations_times", "last_authenticated_at"))
            addAll(phase("app_installations", "chk_app_installations_times", "deleted_at,verifier_expires_at", setup = { deleteFixtureCredential() }))
            addAll(
                phase(
                    "complaint_resource_ids",
                    "chk_complaint_resource_times",
                    "deleted_at",
                    predicate = "id='$LEGACY_COMPLAINT_ID'",
                    setup = {
                        exec(
                            "DELETE FROM complaints WHERE id='$LEGACY_COMPLAINT_ID'; " +
                                "UPDATE complaint_resource_ids SET state='DELETED',deleted_at=$FIXTURE_INSTANT WHERE id='$LEGACY_COMPLAINT_ID'",
                        )
                    },
                ),
            )
            addAll(phase("complaints", "chk_complaints_times", "closed_at", predicate = "id='$OWNED_COMPLAINT_ID'"))
        }

        private fun journalTimes(): List<TimeCase> = buildList {
            addAll(
                phase(
                    "complaint_journal_publications",
                    "chk_complaint_publication_times",
                    "object_created_at",
                    "retain_until",
                    "verified_at",
                    "applied_at",
                    setup = {
                        markPublicationVerified()
                        exec(publicationUpdate("state='APPLIED',applied_at=$FIXTURE_INSTANT"))
                    },
                ),
            )
            addAll(
                phase(
                    "complaint_idempotency_receipts",
                    "chk_complaint_receipt_times",
                    "authorized_at",
                    "completed_at,expires_at",
                    setup = { completeFixtureOwnerDeletion() },
                ),
            )
            addAll(
                phase(
                    "installation_deletion_receipts",
                    "chk_installation_receipt_times",
                    "authorized_at",
                    "completed_at,expires_at",
                    setup = { completeSpecialReceipt() },
                ),
            )
            addAll(phase("complaint_deletion_journal_retirements", "chk_complaint_retirement_times", "completed_at", setup = { completeFixtureRetirement() }))
            addAll(
                phase("complaint_recovery_capacity_reservations", "chk_complaint_recovery_times", "converted_at", setup = {
                    exec("UPDATE complaint_recovery_capacity_reservations SET state='CONVERTED',converted_amounts=$ZERO_VECTOR,converted_at=$FIXTURE_INSTANT")
                }),
            )
        }

        private fun controlAndRunTimes(): List<TimeCase> = buildList {
            addAll(
                phase("complaint_test_runs", "chk_complaint_run_times", "sealed_at", "purging_at", "purged_at", setup = {
                    markRunTerminal()
                    exec(runUpdate("state='PURGED',purged_at=$FIXTURE_INSTANT,unused_reserve=$ZERO_VECTOR"))
                }),
            )
            addAll(
                phase("complaint_catalog_mutations", "chk_complaint_catalog_times", "retain_until", "completed_at", "projected_at", setup = {
                    markCatalogComplete()
                    exec(catalogUpdate("projected_at=$FIXTURE_INSTANT"))
                }),
            )
            addAll(
                phase(
                    "complaint_journal_control", "chk_complaint_control_times", "lease_expires_at", "retention_lease_expires_at",
                    "seal_retain_until", "seal_verified_at", "checkpoint_started_at", "checkpoint_completed_at", setup = {
                        verifyFixtureSeal()
                        markControlCheckpoint()
                        exec(
                            controlUpdate(
                                "lease_owner='64000000-0000-4000-8000-000000000001',lease_token=1,lease_expires_at=$FIXTURE_INSTANT," +
                                    "retention_lease_owner='64000000-0000-4000-8000-000000000001'," +
                                    "retention_lease_token=2,retention_lease_expires_at=$FIXTURE_INSTANT",
                            ),
                        )
                    },
                ),
            )
            addAll(
                phase("complaint_journal_scan_runs", "chk_complaint_scan_times", "finished_at", setup = {
                    exec("UPDATE complaint_journal_scan_runs SET state='COMPLETE',manifest_hash=$FIXTURE_DIGEST,finished_at=$FIXTURE_INSTANT")
                }),
            )
        }

        private fun importTimes(): List<TimeCase> = buildList {
            addAll(
                phase("complaint_import_runs", "chk_complaint_import_times", "sealed_at", "promoted_at", setup = {
                    markImportSealed()
                    exec(importUpdate("state='PROMOTED',promoted_at=$FIXTURE_INSTANT,catalog_generation=2,catalog_hash=$FIXTURE_DIGEST"))
                }),
            )
            addAll(
                phase("complaint_import_runs", "chk_complaint_import_times", "aborted_at", setup = {
                    exec(importUpdate("state='ABORTED',aborted_at=$FIXTURE_INSTANT"))
                }),
            )
            addAll(phase("complaint_import_artifacts", "chk_complaint_artifact_times", "retain_until", "verified_at", setup = { markImportArtifactVerified() }))
        }

        private fun phase(
            table: String,
            constraint: String,
            vararg columnGroups: String,
            predicate: String = "true",
            setup: Connection.() -> Unit = {},
        ): List<TimeCase> = columnGroups.map { TimeCase(table, it.split(','), constraint, predicate, setup) }
    }
}
