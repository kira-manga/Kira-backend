package me.manga.kira.backend.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Exact forward migration source guard; the SQL constraint behavior belongs to the real PG cases. */
class ComplaintAdminDeleteMigrationTest {
    @Test
    fun `V23 adds only the single rejected Admin DELETE association and preserves every other result branch`() {
        fun source(name: String): String = checkNotNull(javaClass.classLoader.getResourceAsStream("db/migration/$name"))
            .bufferedReader().use { it.readText() }.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").trim()
        val previous = source("V22__admin_receipt_grant_association.sql").substringBefore("ALTER TABLE complaint_idempotency_receipts DROP CONSTRAINT chk_complaint_receipt_external;").trim()
        val actual = source("V23__admin_delete_rejected_grant_association.sql")
        val expected = previous.replaceFirst("operation IN ('ADMIN_EDIT', 'ADMIN_STATUS', 'ADMIN_CLOSURE') AND complaint_is_v4(consumed_grant_id)",
            "operation IN ('ADMIN_EDIT', 'ADMIN_STATUS', 'ADMIN_CLOSURE', 'ADMIN_DELETE') AND complaint_is_v4(consumed_grant_id)")
        assertEquals(expected, actual)
        assertFalse(actual.contains("UPDATE complaint_idempotency_receipts"))
        assertFalse(actual.contains("chk_complaint_receipt_external"))
    }
}
