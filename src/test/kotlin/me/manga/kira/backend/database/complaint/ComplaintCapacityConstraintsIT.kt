package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ComplaintCapacityConstraintsIT : ComplaintFixtureTest() {
    @Test
    fun `counter names ordinals and encoding are pinned independently of the migration`() {
        assertEquals(
            COUNTERS.mapIndexed { index, name -> "$name|${index + 1}|1" },
            connection.strings("SELECT name||'|'||ordinal::text||'|'||accounting_version::text FROM complaint_capacity_counters ORDER BY ordinal"),
        )
        for (set in listOf("accounting_version=2", "ordinal=0", "ordinal=23", "ordinal=1", "name='unregistered'")) {
            connection.expectSqlFailure(counterUpdate(set), constraint = "chk_complaint_capacity_encoding")
        }
    }

    @Test
    fun `counter equation accepts Long MAX and rejects overflow rather than wrapping`() {
        val maximum = Long.MAX_VALUE.toString()
        connection.exec(
            counterUpdate(
                "hard_limit=$maximum,creation_limit=$maximum,free_units=0,actual_units=$maximum," +
                    "recovery_reserved_units=0,test_reserved_units=0",
            ),
        )
        assertEquals(
            listOf(maximum),
            connection.strings(
                "SELECT (free_units::numeric+actual_units::numeric+recovery_reserved_units::numeric+test_reserved_units::numeric)::text " +
                    "FROM complaint_capacity_counters WHERE name='storage_bytes'",
            ),
        )
        for (set in listOf(
            "free_units=1", "recovery_reserved_units=1", "test_reserved_units=1", "hard_limit=0",
            "creation_limit=-1", "actual_units=-1", "free_units=-1", "recovery_reserved_units=-1", "test_reserved_units=-1",
        )) {
            connection.expectSqlFailure(counterUpdate(set), constraint = "chk_complaint_capacity_equation")
        }
        connection.expectSqlFailure(counterUpdate("hard_limit=9223372036854775808"), "22003")
        connection.exec(counterUpdate("hard_limit=0,creation_limit=0,free_units=0,actual_units=0"))
        connection.expectSqlFailure(counterUpdate("creation_limit=1"), constraint = "chk_complaint_capacity_equation")
    }

    @Test
    fun `unconfigured counters stay closed and never accept a malformed configuration hash`() {
        connection.exec(counterUpdate("configuration_hash=NULL,configuration_closed=true"))
        connection.expectSqlFailure(counterUpdate("configuration_closed=false"), constraint = "chk_complaint_capacity_configuration")
        for (size in listOf(0, 31, 33)) {
            connection.expectSqlFailure(
                counterUpdate("configuration_hash=decode(repeat('ab',$size),'hex')"),
                constraint = "chk_complaint_capacity_configuration",
            )
        }
        connection.exec(counterUpdate("configuration_hash=$FIXTURE_DIGEST,configuration_closed=false"))
    }

    @Test
    fun `durable UTC bucket exists only on the installation counter and has bounded finite values`() {
        val update = "UPDATE complaint_capacity_counters SET "
        val where = " WHERE name='installation_ids'"
        connection.exec(update + "admission_utc_date='2026-03-08',admission_count=10,admission_daily_limit=10" + where)
        for (set in listOf(
            "admission_count=11",
            "admission_count=-1",
            "admission_daily_limit=9",
            "admission_daily_limit=NULL",
            "admission_count=NULL",
            "admission_utc_date=NULL",
            "admission_utc_date='infinity'",
            "admission_utc_date='-infinity'",
        )) {
            connection.expectSqlFailure(update + set + where, constraint = "chk_complaint_capacity_admission")
        }
        for (set in listOf("admission_utc_date='2026-03-08'", "admission_count=0", "admission_daily_limit=0")) {
            connection.expectSqlFailure(counterUpdate(set), constraint = "chk_complaint_capacity_admission")
        }
        connection.exec(update + "admission_count=0,admission_utc_date=NULL" + where)
    }

    @Test
    fun `reserve and conversion vectors require exact one based nonnegative nonnull shape`() {
        val update = "UPDATE complaint_recovery_capacity_reservations SET "
        for (vector in INVALID_VECTORS) {
            assertEquals(listOf("false"), connection.strings("SELECT complaint_vector_valid($vector)::text"))
            connection.expectSqlFailure(update + "reserved_amounts=$vector", constraint = "chk_complaint_recovery_amounts")
        }
        connection.expectSqlFailure(update + "accounting_version=2", constraint = "chk_complaint_recovery_amounts")
        connection.exec(update + "state='CONVERTED',converted_amounts=$ZERO_VECTOR,converted_at=$FIXTURE_INSTANT")
        for (vector in INVALID_VECTORS + "NULL" + "array_fill(11::bigint,ARRAY[22])") {
            connection.expectSqlFailure(update + "converted_amounts=$vector", constraint = "chk_complaint_recovery_state")
            assertEquals(
                listOf("false"),
                connection.strings(
                    "SELECT complaint_vector_lte($vector,array_fill(10::bigint,ARRAY[22]))::text",
                ),
            )
        }
        connection.exec(update + "converted_amounts=reserved_amounts")
        connection.expectSqlFailure(update + "converted_amounts[22]=11", constraint = "chk_complaint_recovery_state")
        connection.expectSqlFailure(update + "converted_at=NULL", constraint = "chk_complaint_recovery_state")
        connection.expectSqlFailure(update + "converted_at='infinity'", constraint = "chk_complaint_recovery_times")
    }

    @Test
    fun `run original and unused reserve vectors use the same versioned componentwise encoding`() {
        for (vector in INVALID_VECTORS) {
            connection.expectSqlFailure("UPDATE complaint_test_runs SET original_reserve=$vector", constraint = "chk_complaint_run_configuration")
            connection.expectSqlFailure("UPDATE complaint_test_runs SET unused_reserve=$vector", constraint = "chk_complaint_run_configuration")
        }
        connection.expectSqlFailure("UPDATE complaint_test_runs SET unused_reserve[1]=21", constraint = "chk_complaint_run_configuration")
        connection.exec("UPDATE complaint_test_runs SET unused_reserve=$ZERO_VECTOR")
    }

    companion object {
        val COUNTERS = listOf(
            "app_installations", "audit_rows", "catalog_mutations", "complaint_rows", "import_artifacts", "import_runs",
            "import_staging", "installation_ids", "installation_receipts", "journal_applied", "journal_control", "journal_publications",
            "journal_retirements", "legacy_records", "moderation_grants", "normal_receipts", "recovery_reservations", "resource_ids",
            "scan_entries", "scan_runs", "storage_bytes", "test_runs",
        )
        private val INVALID_VECTORS = listOf(
            "ARRAY[]::bigint[]",
            "array_fill(0::bigint,ARRAY[21])",
            "array_fill(0::bigint,ARRAY[23])",
            "array_fill(0::bigint,ARRAY[2,11])",
            "array_fill(0::bigint,ARRAY[22],ARRAY[0])",
            "array_fill(-1::bigint,ARRAY[22])",
            "array_prepend(NULL::bigint,array_fill(0::bigint,ARRAY[21]))",
        )
        private fun counterUpdate(set: String): String = "UPDATE complaint_capacity_counters SET $set WHERE name='storage_bytes'"
    }
}
