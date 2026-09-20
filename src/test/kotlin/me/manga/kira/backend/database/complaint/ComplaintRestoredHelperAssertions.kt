package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import java.sql.Connection

/** Contract-derived probes, not just source-equals-target checks of potentially broken helpers. */
fun Connection.assertComplaintRestoredHelpers() {
    val schema = strings("SELECT current_schema()").single()
    require(schema.matches(Regex("[a-z][a-z0-9_]*")))
    val previousPath = strings("SHOW search_path").single()
    exec("SET search_path TO ''")
    try {
        prepareStatement("SELECT $schema.complaint_text_valid(?,1,1000,4000)").use { statement ->
            val cases = listOf(
                null to false,
                "" to false,
                "?\t\nعربي" to true,
                "😀".repeat(1000) to true,
                "😀".repeat(1001) to false,
                "a\r\nb" to false,
            ) +
                ((1..8) + (11..31) + (127..159)).map { "a${it.toChar()}b" to false }
            for ((value, expected) in cases) {
                statement.setString(1, value)
                statement.executeQuery().use { result ->
                    check(result.next())
                    assertEquals(expected, result.getBoolean(1), "Restored helper case length=${value?.length}")
                    assertFalse(result.wasNull(), "Required-value helper must return a concrete boolean")
                    assertFalse(result.next())
                }
            }
        }
        val probes = listOf(
            "complaint_scope_valid('$LIVE_SCOPE',false)" to "true",
            "complaint_scope_valid('$LIVE_SCOPE',true)" to "false",
            "complaint_scope_valid(NULL,NULL)" to "false",
            "complaint_bytes_match($FIXTURE_BYTES,$FIXTURE_HASH,65536)" to "true",
            "complaint_bytes_match($FIXTURE_BYTES,$FIXTURE_DIGEST,65536)" to "false",
            "complaint_vector_lte($ZERO_VECTOR,$ZERO_VECTOR)" to "true",
            "complaint_vector_valid(ARRAY[0]::bigint[])" to "false",
            "complaint_event_id_valid(repeat('A',43))" to "true",
            "complaint_event_id_valid(repeat('A',42)||'B')" to "false",
            "complaint_finite_times('infinity'::timestamptz)" to "false",
            "complaint_finite_times(NULL::timestamptz)" to "true",
        )
        for ((probe, expected) in probes) assertEquals(listOf(expected), strings("SELECT $schema.$probe::text"), probe)
    } finally {
        exec("SELECT set_config('search_path',${sqlText(previousPath)},false)")
    }
}
