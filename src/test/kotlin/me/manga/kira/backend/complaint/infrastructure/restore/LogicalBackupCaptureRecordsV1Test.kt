package me.manga.kira.backend.complaint.infrastructure.restore

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import java.nio.file.Path

/** Pure input/record guards only. No fake command runner, imported=true input or simulated native PASS. */
@ResourceLock("logical-backup-capture")
class LogicalBackupCaptureRecordsV1Test {
    @Test
    fun `snapshot tokens and closed input cannot smuggle SQL service or command options`() {
        for (token in listOf("", "00000004-000000AA-0", "00000004-000000aa-1", "00000004-000000AA-1';SELECT 1", "--snapshot=x")) {
            assertThrows(LogicalCaptureRefusalV1::class.java) { exported(token) }
        }
        for (name in listOf("db'", "db name", "db;SELECT", "postgresql://127.0.0.1/db", "--file=x", "*")) {
            assertThrows(LogicalCaptureRefusalV1::class.java) { input(database = name) }
        }
        assertThrows(LogicalCaptureRefusalV1::class.java) { LogicalCaptureImageV1(Path.of("/must-not-run"), "17.6") }
    }

    @Test
    fun `full xid8 xmin retains epoch and compares canonical low 32 bits`() {
        // Representation boundaries, not a claim that PostgreSQL exports the reserved low xids 0, 1 or 2.
        for ((full, xid) in mapOf(
            "0" to "0", "3" to "3", "4294967295" to "4294967295", "4294967296" to "0",
            "4294967297" to "1", "4294967299" to "3", "8589934595" to "3", "18446744073709551615" to "4294967295",
        )) {
            val record = exported(xmin = full)
            assertEquals(full, record.xmin)
            assertTrue(record.matchesBackendXmin(xid))
            assertFalse(record.matchesBackendXmin("2"))
            if (full != xid) assertFalse(record.matchesBackendXmin(full))
        }
    }

    @Test
    fun `xmin refuses malformed and overflowing unsigned decimals`() {
        for (xmin in listOf("", "00", "03", "+3", "-3", " 3", "3 ", "3\n", "3.0", "1e1", "\u0663",
            "18446744073709551616", "9".repeat(21))) {
            val failure = assertThrows(LogicalCaptureRefusalV1::class.java) { exported(xmin = xmin) }
            assertEquals(LogicalCaptureFailureV1.PROTOCOL, failure.code)
        }
        val record = exported(xmin = "8589934595")
        for (xid in listOf("", "03", "+3", "-3", " 3", "3 ", "3\n", "3.0", "\u0663", "4294967296", "8589934595")) {
            assertFalse(record.matchesBackendXmin(xid))
        }
    }

    @Test
    fun `native replies reject duplicate fields trailing documents coercion and oversized bytes`() {
        for (raw in listOf("{\"kind\":\"wait\",\"kind\":\"export\"}", "{}{}", "[]", "{", " ".repeat(4097))) {
            val failure = assertThrows(LogicalCaptureRefusalV1::class.java) { LogicalCaptureJsonV1.read(raw.toByteArray()) }
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty())
            assertTrue(failure.stackTrace.isEmpty())
        }
        val reply = LogicalCaptureJsonV1.read("{\"pid\":\"1\",\"extra\":true}".toByteArray())
        assertThrows(LogicalCaptureRefusalV1::class.java) { LogicalCaptureJsonV1.number(reply, "pid") }
        assertThrows(LogicalCaptureRefusalV1::class.java) { LogicalCaptureJsonV1.keys(reply, setOf("pid")) }
    }

    @Test
    fun `handoff and dump caps cannot renew the original budget`() {
        var nanos = 0L
        val total = PersistenceTimeBudget.start(100, PersistenceNanoClock { nanos })
        nanos = 80_000_000
        val handoff = total.capped(LogicalCaptureLimitsV1.HANDOFF_MILLIS)
        assertEquals(20L, captureRemaining(handoff))
        nanos = 100_000_000
        val failure = assertThrows(LogicalCaptureRefusalV1::class.java) { captureRemaining(handoff) }
        assertEquals(LogicalCaptureFailureV1.TIMEOUT, failure.code)
    }

    @Test
    fun `only fixed guard and snapshot SQL exists with no epoch mutation or acceptance`() {
        val sql = LogicalBackupCaptureSqlV1.export
        assertTrue(sql.contains("BEGIN ISOLATION LEVEL REPEATABLE READ READ WRITE"))
        assertTrue(sql.contains("LOCK TABLE ONLY public.complaint_journal_control IN ACCESS EXCLUSIVE MODE"))
        assertTrue(sql.contains("pg_catalog.pg_export_snapshot()"))
        assertTrue(sql.toByteArray().size <= LogicalCaptureLimitsV1.MAX_FRAME)
        for (word in listOf("UPDATE", "INSERT", "DELETE", "TRUNCATE", "ALTER", "CREATE", "pg_advisory", "publication_epoch", "lease_token")) {
            assertFalse(sql.contains(word), word)
        }
        val release = LogicalBackupCaptureSqlV1.release(exported())
        assertTrue(release.startsWith("ROLLBACK;"))
        assertTrue(release.contains("guard_absent"))
        assertFalse(release.contains("COMMIT"))
    }

    @Test
    fun `bad cold custody starts no child and the original is never reusable`() {
        // Relative parent is rejected before any filesystem or native command access. These are NOT fake executables.
        val original = ComplaintLogicalBackupCaptureV1.prepare(input())
        val first = original.capture() as LogicalBackupCaptureResultV1.Refused
        assertEquals(LogicalCaptureFailureV1.CUSTODY, first.failure)
        assertEquals(LogicalCaptureCleanupV1.CONFIRMED, first.cleanup)
        assertNull(first.stageName)
        assertEquals(LogicalCaptureFailureV1.ALREADY_USED, (original.capture() as LogicalBackupCaptureResultV1.Refused).failure)
        assertFalse(original.toString().contains("credential"))
        assertFalse(input().toString().contains("must-not-run"))
        assertTrue(LogicalBackupCaptureResultV1::class.java.permittedSubclasses.none { it.simpleName.contains("Accepted") })
    }

    private fun exported(token: String = "00000004-000000AA-1", xmin: String = "4") =
        LogicalCaptureExportV1(token, 10, 20, 30, 1, 2, 3, xmin, 40000)

    private fun input(database: String = "fixture"): LocalLogicalBackupInputV1 {
        val image = LogicalCaptureImageV1(Path.of("/must-not-run"), "1".repeat(64))
        return LocalLogicalBackupInputV1(5432, database, "fixture", Path.of("/credential"), Path.of("/trust"), "2".repeat(64),
            Path.of("/media"), "3".repeat(64), Path.of("relative-refused-before-io"), Pg176CaptureToolsV1(image, image, image, image, Path.of("/helper")))
    }
}
