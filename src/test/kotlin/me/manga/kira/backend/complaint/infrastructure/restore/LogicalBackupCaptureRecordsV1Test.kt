package me.manga.kira.backend.complaint.infrastructure.restore

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.parallel.ResourceLock
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** Input/record and local-file guards only. No command runner, imported=true input or simulated native PASS. */
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

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `interrupted credential write creates a file but cannot refund cleanup without retained facts`() {
        val fixtures = ArrayList<Pair<Path, Any>>()
        var primaryFailure: Throwable? = null
        val caller = Thread.currentThread()
        val interruptedOnEntry = Thread.interrupted()
        try {
            fun own(path: Path): Path {
                val key = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                fixtures.add(path to checkNotNull(key))
                return path
            }
            fun mode(value: String) = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(value))

            // Default /tmp ancestry is deliberately forbidden. Refuse an unsafe checkout, never relax custody.
            val repository = Path.of("").toAbsolutePath().normalize()
            LogicalCaptureFilesV1.checkedPath(repository, directory = true)
            val root = own(Files.createTempDirectory(repository, "kira-credential-write-", mode("rwx------")))
            val stage = own(Files.createDirectory(root.resolve("stage"), mode("rwx------")))
            val passFile = own(Files.createFile(root.resolve("input.pgpass"), mode("rw-------")))
            Files.write(passFile, "127.0.0.1:5432:fixture:fixture:unused-synthetic-value\n".toByteArray(Charsets.US_ASCII), WRITE)
            val writerClass = FileChannel.open(passFile, READ, NOFOLLOW_LINKS).use { it.javaClass.name }

            // Hashed bytes for image preflight only, NOT a simulated executable or native success.
            val imageBytes = byteArrayOf(0)
            val imageFile = own(Files.createFile(root.resolve("unexecuted.image"), mode("rwx------")))
            Files.write(imageFile, imageBytes, WRITE)
            val imageHash = with(LogicalCaptureFilesV1) { MessageDigest.getInstance("SHA-256").digest(imageBytes).hex() }
            val image = LogicalCaptureImageV1(imageFile, imageHash)
            val trustFile = root.resolve("absent-trust.pem")
            val selected = LocalLogicalBackupInputV1(5432, "fixture", "fixture", passFile, trustFile, "2".repeat(64),
                root.resolve("absent-media.tar.gz"), "3".repeat(64), root,
                Pg176CaptureToolsV1(image, image, image, image, repository.resolve("scripts/db/backup_bundle.py")))
            // Even a missed interrupt must fail credential preparation before any version/process dispatch.
            assertTrue(Files.notExists(trustFile, NOFOLLOW_LINKS))

            var credentialReadSamples = 0
            val clock = PersistenceNanoClock {
                // Count only read()'s two samples, not image/helper hash loops. The second follows stream close.
                if (caller.stackTrace.any { it.className == LogicalCaptureFilesV1::class.java.name && it.methodName == "read" }) {
                    if (++credentialReadSamples == 2) caller.interrupt()
                }
                0L
            }
            val dump = Pg17LogicalBackupDumpV1(selected, stage, "1".repeat(32),
                PersistenceTimeBudget.start(LogicalCaptureLimitsV1.TOTAL_MILLIS, clock))
            val target = stage.resolve(".pgpass")
            assertTrue(Files.notExists(target, NOFOLLOW_LINKS))
            val failure = runCatching { dump.prepare() }.exceptionOrNull()
            val interruptedAfterWrite = Thread.interrupted() // Cleanup refusal must not be an interrupt refusal.
            // Fixture-only teardown identity; these freshly observed facts never enter the production owner.
            if (Files.exists(target, NOFOLLOW_LINKS)) own(target)
            assertEquals(ClosedByInterruptException::class.java, failure?.javaClass)
            assertEquals(2, credentialReadSamples)
            assertTrue(interruptedAfterWrite)
            for ((type, method) in listOf(writerClass to "write", LogicalCaptureFilesV1::class.java.name to "write",
                Pg17LogicalBackupDumpV1::class.java.name to "prepareCredentials")) {
                assertTrue(checkNotNull(failure).stackTrace.any { it.className == type && it.methodName == method }, "$type.$method failure point")
            }
            val created = LogicalCaptureFilesV1.checkedPath(target, requirePrivate = true)
            assertEquals(0L, created.getValue("size")) // CREATE_NEW happened; no credential bytes were written.
            assertTrue(dump.stop(PersistenceTimeBudget.start(LogicalCaptureLimitsV1.CLEANUP_MILLIS)))
            assertFalse(caller.isInterrupted)
            val cleanup = assertThrows(LogicalCaptureRefusalV1::class.java) { dump.removePrivateAuthentication() }
            assertEquals(LogicalCaptureFailureV1.CUSTODY, cleanup.code)
            assertEquals(created, LogicalCaptureFilesV1.checkedPath(target, requirePrivate = true))
            // Lower credential custody only: this does not qualify fsync faults or top-level capture-slot retention.
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            Thread.interrupted()
            try {
                var problem = primaryFailure
                for ((path, key) in fixtures.asReversed()) {
                    try {
                        check(Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == key) {
                            "Refuse deletion of a changed test fixture"
                        }
                        Files.delete(path) // Exact recorded fixtures only; never recursive or ambient-path cleanup.
                    } catch (cleanup: Throwable) {
                        if (problem == null) problem = cleanup else problem.addSuppressed(cleanup)
                    }
                }
                if (primaryFailure == null && problem != null) throw problem
            } finally {
                if (interruptedOnEntry) caller.interrupt()
            }
        }
    }

    private fun exported(token: String = "00000004-000000AA-1", xmin: String = "4") =
        LogicalCaptureExportV1(token, 10, 20, 30, 1, 2, 3, xmin, 40000)

    private fun input(database: String = "fixture"): LocalLogicalBackupInputV1 {
        val image = LogicalCaptureImageV1(Path.of("/must-not-run"), "1".repeat(64))
        return LocalLogicalBackupInputV1(5432, database, "fixture", Path.of("/credential"), Path.of("/trust"), "2".repeat(64),
            Path.of("/media"), "3".repeat(64), Path.of("relative-refused-before-io"), Pg176CaptureToolsV1(image, image, image, image, Path.of("/helper")))
    }
}
