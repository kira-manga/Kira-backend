package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.manga.kira.backend.complaint.catalog.ColdSqlObservationV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Passive all-row/xmin comparisons used on BOTH sides of actual process exit; never an owner or proof transport. */
internal object TestActiveSealRecoveryObservationV1 {
    const val PAID = "complaint_test_active_seal_intents"
    private const val CONTROL = "complaint_journal_control"
    val wireColumns = setOf("state", "wire_bytes", "wire_hash", "checksum_sha256", "content_type", "object_lock_mode",
        "retain_until", "metadata_bytes", "metadata_hash", "frozen_at")
    private val controlColumns = setOf("lease_owner", "lease_token", "lease_expires_at", "updated_at", "seal_state", "seal_object_version",
        "seal_ciphertext_hash", "seal_retain_until", "seal_verified_at", "seal_verification_bytes", "seal_verification_hash")

    fun image(jdbc: JdbcTemplate): Map<String, List<String>> = ColdSqlObservationV1.image(jdbc) + (PAID to jdbc.query(
        "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_test_active_seal_intents t ORDER BY data_scope_id LIMIT 2",
        { row, _ -> row.getString(1) }))

    fun completed(before: Map<String, List<String>>, jdbc: JdbcTemplate, scope: UUID) {
        val after = image(jdbc)
        assertEquals(before.keys, after.keys)
        (before.keys - setOf(CONTROL, PAID)).forEach { table ->
            assertTrue(before.getValue(table) == after.getValue(table), "No unrelated row/xmin, P22, enrollment, capacity, receipt, run or catalog write: $table")
        }
        val oldControls = before.getValue(CONTROL).associateBy { text(row(it), "data_scope_id") }
        assertEquals(2, oldControls.size); assertEquals(2, after.getValue(CONTROL).size)
        after.getValue(CONTROL).forEach { bytes ->
            val current = row(bytes); val oldBytes = oldControls.getValue(text(current, "data_scope_id")); val old = row(oldBytes)
            if (text(current, "data_scope_id") != scope.toString()) assertTrue(oldBytes == bytes, "Global control cannot churn.")
            else {
                assertTrue(old.filterKeys { it !in controlColumns } == current.filterKeys { it !in controlColumns })
                assertEquals(number(old, "lease_token") + 1, number(current, "lease_token"))
                assertEquals("SEAL_PREPARED", text(old, "seal_state")); assertEquals("SEAL_VERIFIED", text(current, "seal_state"))
                assertEquals("CAPTURED", text(current, "rotation_state")); assertEquals(1L, number(current, "rotation_sequence"))
                assertEquals(2L, number(current, "publication_epoch")); assertEquals("false", text(current, "scan_requested"))
                assertEquals("false", text(current, "maintenance_closed")); assertEquals("false", text(current, "creation_closed"))
                current.filterKeys { it.startsWith("checkpoint_") }.forEach { (name, value) -> assertEquals(JsonNull, value, name) }
            }
        }
        val oldPaidBytes = before.getValue(PAID).single(); val paidBytes = after.getValue(PAID).single()
        val oldPaid = row(oldPaidBytes); val paid = row(paidBytes)
        assertTrue(oldPaid.filterKeys { it !in wireColumns } == paid.filterKeys { it !in wireColumns }, "Paid birth/capture/price/canonical preparing token is immutable.")
        if (text(oldPaid, "state") == "WIRE_FROZEN") assertTrue(oldPaidBytes == paidBytes, "Existing frozen bytes and xmin never change.")
        else assertEquals("CANONICAL", text(oldPaid, "state"))
        assertEquals("WIRE_FROZEN", text(paid, "state")); assertEquals(2_097_152L, number(paid, "charged_storage_bytes"))
        val control = after.getValue(CONTROL).map(::row).single { text(it, "data_scope_id") == scope.toString() }
        assertEquals(paid["canonical_bytes"], control["seal_bytes"]); assertEquals(paid["canonical_hash"], control["seal_hash"])
        assertEquals(paid["wire_hash"], control["seal_ciphertext_hash"])
        assertTrue(number(paid, "capture_token") < number(paid, "preparing_fencing_token"))
        assertTrue(number(paid, "preparing_fencing_token") < number(control, "lease_token"))
        listOf("complaint_journal_publications", "complaint_test_terminal_intents", "complaint_journal_scan_runs", "complaint_journal_scan_entries").forEach {
            assertTrue(after.getValue(it).isEmpty(), "No ordinary resolver, checkpoint or terminal work: $it")
        }
    }

    fun row(bytes: String): JsonObject = Json.parseToJsonElement(bytes).jsonArray[0].jsonObject
    fun text(row: JsonObject, key: String): String = row.getValue(key).jsonPrimitive.content
    fun number(row: JsonObject, key: String): Long = row.getValue(key).jsonPrimitive.long
}
