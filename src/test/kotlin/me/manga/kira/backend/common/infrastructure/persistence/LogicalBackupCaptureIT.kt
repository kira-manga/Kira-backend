package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.restore.ComplaintLogicalBackupCaptureV1
import me.manga.kira.backend.complaint.infrastructure.restore.LocalLogicalBackupInputV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalBackupCaptureResultV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalCaptureCleanupV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalCaptureExportV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalCaptureFailureV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalCaptureFilesV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalCaptureImageV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalCaptureImportV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalCaptureJsonV1
import me.manga.kira.backend.complaint.infrastructure.restore.Pg176CaptureToolsV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.parallel.ResourceLock
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Driver
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

/**
 * NOT_RUN. Explicit disposable host-local PG17.6 qualification, not Docker/NAT or an existing app DB.
 * No fake process/pg_stat_activity replies. Guard failure and pre-ACK exporter loss permanently retain
 * the JVM capture slot: select either alone in a fresh isolated test JVM, never enable it implicitly.
 */
@EnabledIfEnvironmentVariable(named = "KIRA_QCAP_IT", matches = "disposable-local-17\\.6")
@TestMethodOrder(OrderAnnotation::class)
@ResourceLock("logical-backup-capture")
class LogicalBackupCaptureIT {
    @Test
    @Order(1)
    fun `different actual pg dump bytes refuse before a child or guard is started`() {
        val fixture = Fixture()
        fixture.withEmptySource {
            val input = fixture.input(wrongDumpPin = true)
            val original = ComplaintLogicalBackupCaptureV1.prepare(input)
            val result = original.capture() as LogicalBackupCaptureResultV1.Refused
            assertEquals(LogicalCaptureFailureV1.IMAGE, result.failure)
            assertEquals(LogicalCaptureCleanupV1.CONFIRMED, result.cleanup)
            assertEquals(0L, fixture.scalar("SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE application_name LIKE 'kira-qcap-%'"))
            assertEquals(LogicalCaptureFailureV1.ALREADY_USED, (original.capture() as LogicalBackupCaptureResultV1.Refused).failure)
        }
    }

    @Test
    @Order(2)
    fun `real native import releases exporter while dump continues and excludes later rows`() {
        val fixture = Fixture()
        fixture.withEmptySource {
            fixture.execute("CREATE TABLE public.complaint_journal_control (marker integer NOT NULL)")
            fixture.execute("INSERT INTO public.complaint_journal_control VALUES (7)")
            fixture.execute("CREATE TABLE public.capture_rows (id integer NOT NULL)")
            fixture.execute("INSERT INTO public.capture_rows VALUES (1)")
            // Created last: PG17.6 getTables visits OIDs in order. This second, test-owned table lock
            // keeps the genuine importer alive AFTER the product has released the fixed guard/exporter.
            fixture.execute("CREATE TABLE public.zz_capture_tail (id integer NOT NULL)")
            val executor = Executors.newSingleThreadExecutor()
            fixture.connect().use { tail ->
                tail.autoCommit = false
                tail.createStatement().use { it.execute("LOCK TABLE public.zz_capture_tail IN ACCESS EXCLUSIVE MODE") }
                val original = ComplaintLogicalBackupCaptureV1.prepare(fixture.input())
                val running = executor.submit<LogicalBackupCaptureResultV1> { original.capture() }
                try {
                    fixture.awaitTailWait()
                    // The product's own exporter has really exited, while that *same* native dump still waits.
                    fixture.awaitExporterGone()
                    fixture.execute("INSERT INTO public.capture_rows VALUES (2)")
                    tail.rollback() // Keep this short: pg_dump's entire initial table-lock statement is bounded.
                    val result = running.get(125, TimeUnit.SECONDS) as LogicalBackupCaptureResultV1.Quarantined
                    val record = result.record
                    assertEquals("QUARANTINED", record.state)
                    assertEquals("NOT_ACCEPTED", record.acceptance)
                    assertEquals("NOT_IMPLEMENTED", record.epochBoundary)
                    assertEquals("NOT_IMPLEMENTED", record.prePostReconciliation)
                    assertEquals("NOT_PROVED", record.mediaWriterExclusionAndDrain)
                    assertTrue(record.imported.dumpBackendPid > 0 && record.imported.socket.childPid > 0)
                    assertTrue(record.imported.socket.inode > 0 && record.imported.socket.serverPort == fixture.port)
                    assertEquals(7L, fixture.scalar("SELECT marker FROM public.complaint_journal_control"))
                    assertEquals(2L, fixture.scalar("SELECT count(*) FROM public.capture_rows"))
                    val stage = fixture.output.resolve(record.stageName)
                    assertFalse(Files.exists(stage.resolve(".pgpass")))
                    assertTrue(Files.exists(stage.resolve("capture.json")))
                    // Use the real pinned pg_restore to inspect actual custom-dump bytes, not a simulated receipt.
                    val rows = fixture.dumpRows(stage.resolve(record.dump.name), "capture_rows")
                    assertEquals(listOf("1"), rows)
                    assertEquals(listOf("7"), fixture.dumpRows(stage.resolve(record.dump.name), "complaint_journal_control"))
                    val manifest = LogicalCaptureFilesV1.read(stage.resolve(record.manifest.name), 4096, PersistenceTimeBudget.start(1000), requirePrivate = true)
                    assertEquals(10.toByte(), manifest.last()) // Preserve v1 producer newline, never kcj-1 reserialization.
                    val json = LogicalCaptureJsonV1.read(manifest)
                    LogicalCaptureJsonV1.keys(json, setOf("schema", "dump", "media"))
                    assertEquals("kira.backup-bundle.v1", LogicalCaptureJsonV1.text(json, "schema"))
                    assertEquals(0L, fixture.scalar("SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE application_name LIKE 'kira-qcap-%'"))
                    assertEquals(LogicalCaptureFailureV1.ALREADY_USED, (original.capture() as LogicalBackupCaptureResultV1.Refused).failure)
                } finally {
                    tail.rollback()
                    if (!running.isDone) running.cancel(true)
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test
    @Order(3)
    fun `real native dump timeout after exporter release refuses and keeps failed stage`() {
        val fixture = Fixture()
        fixture.withEmptySource {
            fixture.execute("CREATE TABLE public.complaint_journal_control (marker integer NOT NULL)")
            fixture.execute("INSERT INTO public.complaint_journal_control VALUES (7)")
            fixture.execute("CREATE TABLE public.capture_rows (id integer NOT NULL)")
            fixture.execute("INSERT INTO public.capture_rows VALUES (1)")
            fixture.execute("CREATE TABLE public.zz_capture_tail (id integer NOT NULL)")
            val executor = Executors.newSingleThreadExecutor()
            try {
                fixture.connect().use { tail ->
                    tail.autoCommit = false
                    tail.createStatement().use { it.execute("LOCK TABLE public.zz_capture_tail IN ACCESS EXCLUSIVE MODE") }
                    val original = ComplaintLogicalBackupCaptureV1.prepare(fixture.input())
                    val running = executor.submit<LogicalBackupCaptureResultV1> { original.capture() }
                    try {
                        fixture.awaitTailWait()
                        fixture.awaitExporterGone()
                        // Unlike the successful selector, retain this real blocker until pg_dump's
                        // unchanged 1000ms initial table-lock timeout ends the original native child.
                        val result = running.get(125, TimeUnit.SECONDS) as LogicalBackupCaptureResultV1.Refused
                        assertEquals(LogicalCaptureFailureV1.PROCESS, result.failure)
                        assertEquals(LogicalCaptureCleanupV1.CONFIRMED, result.cleanup)
                        assertReleasedExporterAndFailedDump(original)
                        val stage = fixture.output.resolve(checkNotNull(result.stageName))
                        assertTrue(Files.isDirectory(stage, NOFOLLOW_LINKS))
                        assertTrue(Files.isRegularFile(stage.resolve("capture.dump"), NOFOLLOW_LINKS))
                        assertTrue(Files.isRegularFile(stage.resolve("capture.media.tar.gz"), NOFOLLOW_LINKS))
                        assertTrue(Files.notExists(stage.resolve(".pgpass"), NOFOLLOW_LINKS))
                        assertTrue(Files.notExists(stage.resolve("capture.bundle.json"), NOFOLLOW_LINKS))
                        assertTrue(Files.notExists(stage.resolve("capture.json"), NOFOLLOW_LINKS))
                        // A real independent lock acquisition checks parent-lock release, not a reset
                        // or a second capture that could hide this failed original's cleanup.
                        FileChannel.open(fixture.output.resolve(".logical-backup-capture.lock"), WRITE, NOFOLLOW_LINKS).use { channel ->
                            checkNotNull(channel.tryLock()).use { assertTrue(it.isValid) }
                        }
                        assertEquals(7L, fixture.scalar("SELECT marker FROM public.complaint_journal_control"))
                        assertEquals(1L, fixture.scalar("SELECT count(*) FROM public.capture_rows"))
                        assertEquals(0L, fixture.scalar("SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE application_name LIKE 'kira-qcap-%'"))
                        assertEquals(LogicalCaptureFailureV1.ALREADY_USED, (original.capture() as LogicalBackupCaptureResultV1.Refused).failure)
                    } finally {
                        try { tail.rollback() } finally { if (!running.isDone) running.cancel(true) }
                    }
                }
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    @Order(4)
    fun `blocked guard is bounded retained unknown and never refunded by elapsed time`() {
        val fixture = Fixture()
        fixture.withEmptySource {
            fixture.execute("CREATE TABLE public.complaint_journal_control (marker integer NOT NULL)")
            fixture.execute("INSERT INTO public.complaint_journal_control VALUES (7)")
            fixture.connect().use { blocker ->
                blocker.autoCommit = false
                blocker.createStatement().use { it.execute("LOCK TABLE public.complaint_journal_control IN ACCESS SHARE MODE") }
                try {
                    val original = ComplaintLogicalBackupCaptureV1.prepare(fixture.input())
                    val result = original.capture() as LogicalBackupCaptureResultV1.Refused
                    // A killed psql does not prove that a possibly delivered lock/transaction has been rolled back.
                    assertEquals(LogicalCaptureCleanupV1.RETAINED_UNKNOWN, result.cleanup)
                    assertFalse(Files.exists(fixture.output.resolve(checkNotNull(result.stageName)).resolve("capture.json")))
                    val next = ComplaintLogicalBackupCaptureV1.prepare(fixture.input()).capture() as LogicalBackupCaptureResultV1.Refused
                    assertEquals(LogicalCaptureFailureV1.BUSY, next.failure)
                } finally { blocker.rollback() }
            }
            assertEquals(7L, fixture.scalar("SELECT marker FROM public.complaint_journal_control"))
        }
    }

    @Test
    @Order(5)
    fun `real native exporter loss before import acknowledgment retains unknown custody`() {
        val fixture = Fixture()
        fixture.withEmptySource {
            // PG17.6 imports the snapshot BEFORE these table locks, but cannot reach the product's
            // guard-wait acknowledgment while this earlier table remains blocked.
            fixture.execute("CREATE TABLE public.capture_rows (id integer NOT NULL)")
            fixture.execute("INSERT INTO public.capture_rows VALUES (1)")
            fixture.execute("CREATE TABLE public.complaint_journal_control (marker integer NOT NULL)")
            fixture.execute("INSERT INTO public.complaint_journal_control VALUES (7)")
            assertEquals(1L, fixture.scalar("SELECT ('public.capture_rows'::regclass::oid < 'public.complaint_journal_control'::regclass::oid)::int"))
            val executor = Executors.newSingleThreadExecutor()
            try {
                fixture.connect().use { blocker ->
                    blocker.autoCommit = false
                    blocker.createStatement().use { it.execute("LOCK TABLE public.capture_rows IN ACCESS EXCLUSIVE MODE") }
                    // Pre-open the observer: no new TLS connection during the unchanged 1000ms lock wait.
                    fixture.connect().use { observer ->
                        val original = ComplaintLogicalBackupCaptureV1.prepare(fixture.input())
                        val running = executor.submit<LogicalBackupCaptureResultV1> { original.capture() }
                        try {
                            val lost = fixture.loseExporterBeforeAck(observer, blocker, running)
                            // Keep the blocker through fault delivery AND the original's completed cleanup.
                            val result = running.get(125, TimeUnit.SECONDS) as LogicalBackupCaptureResultV1.Refused
                            blocker.rollback()
                            assertEquals(LogicalCaptureCleanupV1.RETAINED_UNKNOWN, result.cleanup)
                            val exporter = checkNotNull(ownedCutField(original, "session"))
                            val dump = checkNotNull(ownedCutField(original, "dump"))
                            val exported = ownedCutField(exporter, "exported") as LogicalCaptureExportV1
                            assertEquals(lost.exporterPid, exported.backendPid)
                            assertEquals(lost.exporterStarted, exported.backendStartedMicros)
                            assertEquals(lost.exporterTransaction, exported.transactionStartedMicros)
                            assertEquals("kira-qcap-d-${lost.token}", ownedCutField(dump, "application"))
                            assertEquals(true, ownedCutField(exporter, "exportSent"))
                            assertNull(ownedCutField(exporter, "witness"))
                            assertNull(ownedCutField(dump, "imported"))
                            assertEquals(false, ownedCutField(exporter, "released"))
                            assertEquals(true, ownedCutField(exporter, "localChildrenEnded"))
                            val exporterProcess = ownedCutField(exporter, "process") as Process
                            val dumpProcess = ownedCutField(dump, "process") as Process
                            assertFalse(exporterProcess.isAlive)
                            assertFalse(dumpProcess.isAlive)
                            assertNotEquals(0, exporterProcess.exitValue())
                            assertNotEquals(0, dumpProcess.exitValue())
                            assertTrue((ownedCutField(exporter, "readerEnded") as AtomicBoolean).get())
                            assertFalse((ownedCutField(exporter, "reader") as Thread).isAlive)
                            assertTrue((ownedCutField(dump, "pumpEnded") as AtomicBoolean).get())
                            assertFalse((ownedCutField(dump, "pump") as Thread).isAlive)
                            assertFalse((ownedCutField(dump, "output") as FileChannel).isOpen)
                            assertEquals(3, (ownedCutField(dump, "shorts") as List<*>).size, "No TOC or bundle helper after exporter loss.")
                            assertEquals(".logical-backup-q-${lost.token}", result.stageName)
                            val stage = fixture.output.resolve(checkNotNull(result.stageName))
                            assertTrue(Files.isDirectory(stage, NOFOLLOW_LINKS))
                            assertTrue(Files.isRegularFile(stage.resolve("capture.dump"), NOFOLLOW_LINKS))
                            assertTrue(Files.isRegularFile(stage.resolve("capture.media.tar.gz"), NOFOLLOW_LINKS))
                            assertTrue(Files.notExists(stage.resolve(".pgpass"), NOFOLLOW_LINKS))
                            assertTrue(Files.notExists(stage.resolve("capture.bundle.json"), NOFOLLOW_LINKS))
                            assertTrue(Files.notExists(stage.resolve("capture.json"), NOFOLLOW_LINKS))
                            val parentLock = ownedCutField(original, "parentLock") as FileLock
                            val lockChannel = ownedCutField(original, "lockChannel") as FileChannel
                            assertTrue(parentLock.isValid && lockChannel.isOpen)
                            assertSame(lockChannel, parentLock.channel())
                            assertSame(original, (ownedCutField(original, "retained") as AtomicReference<*>).get())
                            assertEquals(0L, fixture.scalar("SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE datname=current_database() AND application_name LIKE 'kira-qcap-%'"))
                            val next = ComplaintLogicalBackupCaptureV1.prepare(fixture.input()).capture() as LogicalBackupCaptureResultV1.Refused
                            assertEquals(LogicalCaptureFailureV1.BUSY, next.failure)
                            assertNull(next.stageName)
                            assertEquals(LogicalCaptureFailureV1.ALREADY_USED, (original.capture() as LogicalBackupCaptureResultV1.Refused).failure)
                            assertTrue(parentLock.isValid && lockChannel.isOpen)
                            assertSame(original, (ownedCutField(original, "retained") as AtomicReference<*>).get())
                            assertEquals(7L, fixture.scalar("SELECT marker FROM public.complaint_journal_control"))
                            assertEquals(1L, fixture.scalar("SELECT count(*) FROM public.capture_rows"))
                        } finally {
                            try { blocker.rollback() } finally { if (!running.isDone) running.cancel(true) }
                        }
                    }
                }
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    private data class PreAckBackends(
        val token: String, val exporterPid: Int, val exporterStarted: Long, val exporterTransaction: Long,
        val dumpPid: Int, val dumpStarted: Long, val dumpTransaction: Long,
    )

    /** Passive reads only after Future.get publishes the original's completed work and cleanup. */
    private fun assertReleasedExporterAndFailedDump(original: ComplaintLogicalBackupCaptureV1) {
        val exporter = checkNotNull(ownedCutField(original, "session"))
        val dump = checkNotNull(ownedCutField(original, "dump"))
        val witness = ownedCutField(exporter, "witness") as LogicalCaptureImportV1
        assertSame(witness, ownedCutField(dump, "imported"))
        assertTrue(witness.dumpBackendPid > 0)
        assertEquals(true, ownedCutField(exporter, "released"))
        assertEquals(true, ownedCutField(exporter, "localChildrenEnded"))
        val exporterProcess = ownedCutField(exporter, "process") as Process
        assertFalse(exporterProcess.isAlive)
        assertEquals(0, exporterProcess.exitValue())
        assertTrue((ownedCutField(exporter, "readerEnded") as AtomicBoolean).get())
        assertFalse((ownedCutField(exporter, "reader") as Thread).isAlive)
        val dumpProcess = ownedCutField(dump, "process") as Process
        assertEquals(witness.socket.childPid, dumpProcess.pid())
        assertFalse(dumpProcess.isAlive)
        assertNotEquals(0, dumpProcess.exitValue())
        assertTrue((ownedCutField(dump, "pumpEnded") as AtomicBoolean).get())
        assertFalse((ownedCutField(dump, "pump") as Thread).isAlive)
        assertFalse((ownedCutField(dump, "output") as FileChannel).isOpen)
        assertEquals(3, (ownedCutField(dump, "shorts") as List<*>).size, "Only the original three version checks ran; no TOC or bundle helper.")
        assertNull(ownedCutField(original, "parentLock"))
        assertNull(ownedCutField(original, "lockChannel"))
        assertNull((ownedCutField(original, "retained") as AtomicReference<*>).get())
    }

    private class Fixture {
        val port = required("PORT").toInt()
        private val database = required("DATABASE").also { require(Regex("kira_qcap_it_[a-z0-9_]{1,40}").matches(it)) }
        private val username = required("USER")
        private val pass = Path.of(required("PASSFILE"))
        private val trust = Path.of(required("TRUST_FILE"))
        private val root = Path.of(required("ROOT"))
        private val fixture = root.resolve("qcap-fixture-${UUID.randomUUID()}")
        val output: Path = fixture.resolve("output")
        private val archive = fixture.resolve("selected.media.tar.gz")
        private val tools = Pg176CaptureToolsV1(image("PSQL"), image("PG_DUMP"), image("PG_RESTORE"), image("PYTHON"), Path.of(required("BUNDLE_HELPER")))

        init {
            LogicalCaptureFilesV1.checkedPath(root, directory = true, requirePrivate = true)
            LogicalCaptureFilesV1.createDirectory(fixture)
            LogicalCaptureFilesV1.createDirectory(output)
            val buffer = java.io.ByteArrayOutputStream()
            GZIPOutputStream(buffer).use { it.write(ByteArray(1024)) } // Genuine bounded empty tar archive, not a media-drain claim.
            LogicalCaptureFilesV1.write(archive, buffer.toByteArray())
        }

        fun input(wrongDumpPin: Boolean = false): LocalLogicalBackupInputV1 = LocalLogicalBackupInputV1(port, database, username, pass, trust,
            required("TRUST_SHA256"), archive, sha(Files.readAllBytes(archive)), output,
            if (wrongDumpPin) Pg176CaptureToolsV1(tools.psql, LogicalCaptureImageV1(tools.pgDump.path, "0".repeat(64)), tools.pgRestore, tools.python, tools.bundleHelper)
            else tools)

        fun connect(): Connection {
            val raw = LogicalCaptureFilesV1.read(pass, 4096, PersistenceTimeBudget.start(1000), requirePrivate = true)
            val password = try {
                val prefix = "127.0.0.1:$port:$database:$username:"
                val text = raw.toString(Charsets.UTF_8).removeSuffix("\n")
                require(text.startsWith(prefix) && !text.contains('\n'))
                val encoded = text.substring(prefix.length)
                val decoded = StringBuilder()
                var index = 0
                while (index < encoded.length) {
                    if (encoded[index] == '\\') {
                        index++
                        require(index < encoded.length && encoded[index] in "\\:")
                    }
                    decoded.append(encoded[index++])
                }
                decoded.toString()
            } finally { raw.fill(0) }
            val properties = Properties().apply {
                setProperty("PGHOST", "127.0.0.1"); setProperty("PGPORT", port.toString()); setProperty("PGDBNAME", database)
                setProperty("user", username); setProperty("password", password); setProperty("sslmode", "verify-full")
                setProperty("sslrootcert", trust.toString()); setProperty("sslcert", ""); setProperty("sslkey", "")
                setProperty("gssEncMode", "disable"); setProperty("channelBinding", "require"); setProperty("requireAuth", "scram-sha-256")
                setProperty("loginTimeout", "0"); setProperty("connectTimeout", "2"); setProperty("socketTimeout", "5")
            }
            val driver = Class.forName("org.postgresql.Driver").asSubclass(Driver::class.java)
                .getDeclaredConstructor().newInstance()
            return checkNotNull(driver.connect("jdbc:postgresql://", properties))
        }

        fun withEmptySource(body: () -> Unit) {
            assertEquals(170006L, scalar("SELECT current_setting('server_version_num')::bigint"))
            assertEquals(0L, scalar("SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'"))
            try { body() } finally {
                // Fixed fixture tables only, in the explicitly named initially empty disposable database.
                execute("DROP TABLE IF EXISTS public.zz_capture_tail, public.capture_rows, public.complaint_journal_control")
                // Keep private artifact/failed stages for review. Never recursively delete them in a test finalizer.
            }
        }

        fun execute(sql: String) = connect().use { connection -> connection.createStatement().use { it.execute(sql); Unit } }

        fun scalar(sql: String): Long = connect().use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { row -> check(row.next()); val value = row.getLong(1); check(!row.wasNull() && !row.next()); value }
        } }

        fun awaitTailWait() = await(10_000) {
            scalar("""SELECT count(*) FROM pg_catalog.pg_stat_activity a JOIN pg_catalog.pg_locks l ON l.pid=a.pid
                WHERE a.datname=current_database() AND a.usename=current_user AND a.application_name LIKE 'kira-qcap-d-%'
                  AND a.wait_event_type='Lock' AND l.locktype='relation' AND l.relation='public.zz_capture_tail'::regclass
                  AND l.mode='AccessShareLock' AND NOT l.granted""") == 1L
        }

        fun awaitExporterGone() = await(500) {
            scalar("SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE datname=current_database() AND application_name LIKE 'kira-qcap-e-%'") == 0L
        }

        /** One destructive fixture cut, never a general backend killer or a product release receipt. */
        fun loseExporterBeforeAck(observer: Connection, blocker: Connection, running: Future<*>): PreAckBackends {
            assertTrue(observer.autoCommit) // Each observation is fresh, not an open stats transaction.
            observer.createStatement().use { it.execute("SET stats_fetch_consistency = 'none'") }
            val blockerPid = blocker.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_catalog.pg_backend_pid()").use { row ->
                    check(row.next()); val pid = row.getInt(1); check(!row.next()); pid
                }
            }
            val budget = PersistenceTimeBudget.start(10_000)
            var token: String? = null
            await(budget.remainingMillis(10_000)) {
                assertFalse(running.isDone, "Capture ended before the fixture cut.")
                Files.list(output).use { paths ->
                    val stages = paths.filter { Regex("\\.logical-backup-q-[0-9a-f]{32}").matches(it.fileName.toString()) }.toList()
                    check(stages.size <= 1)
                    token = stages.singleOrNull()?.also { check(Files.isDirectory(it, NOFOLLOW_LINKS)) }
                        ?.fileName?.toString()?.removePrefix(".logical-backup-q-")
                }
                token != null
            }
            val run = checkNotNull(token)
            // Exact token comes from this sole private stage, not LIKE or precompletion private-field reads.
            // Reuse the full predicate at the signal, including both backend starts/transactions and locks.
            val cut = """WITH tagged AS MATERIALIZED (
                SELECT * FROM pg_catalog.pg_stat_activity WHERE application_name IN ('kira-qcap-e-$run','kira-qcap-d-$run')
              ), pair AS MATERIALIZED (
                SELECT e.pid AS exporter_pid, (extract(epoch FROM e.backend_start)*1000000)::bigint AS exporter_started,
                  (extract(epoch FROM e.xact_start)*1000000)::bigint AS exporter_transaction,
                  d.pid AS dump_pid, (extract(epoch FROM d.backend_start)*1000000)::bigint AS dump_started,
                  (extract(epoch FROM d.xact_start)*1000000)::bigint AS dump_transaction
                FROM tagged e, tagged d
                WHERE e.application_name='kira-qcap-e-$run' AND d.application_name='kira-qcap-d-$run'
                  AND e.datname=? AND e.usename=? AND e.datname=current_database() AND e.usename=current_user
                  AND d.datid=e.datid AND d.usename=e.usename AND e.pid<>d.pid AND e.pid<>pg_catalog.pg_backend_pid()
                  AND e.backend_type='client backend' AND d.backend_type='client backend'
                  AND e.client_addr='127.0.0.1'::inet AND d.client_addr=e.client_addr
                  AND e.xact_start>=e.backend_start AND d.backend_start>=e.xact_start AND d.xact_start>=d.backend_start
                  AND e.backend_xmin IS NOT NULL AND d.backend_xmin=e.backend_xmin
                  AND d.state='active' AND d.wait_event_type='Lock' AND d.wait_event='relation'
                  AND pg_catalog.pg_blocking_pids(d.pid)=ARRAY[$blockerPid]
                  AND EXISTS (SELECT 1 FROM pg_catalog.pg_locks l WHERE l.pid=$blockerPid AND l.database=e.datid
                    AND l.locktype='relation' AND l.relation='public.capture_rows'::regclass AND l.mode='AccessExclusiveLock' AND l.granted)
                  AND EXISTS (SELECT 1 FROM pg_catalog.pg_locks l WHERE l.pid=d.pid AND l.database=e.datid
                    AND l.locktype='relation' AND l.relation='public.capture_rows'::regclass AND l.mode='AccessShareLock' AND NOT l.granted)
                  AND EXISTS (SELECT 1 FROM pg_catalog.pg_locks l WHERE l.pid=e.pid AND l.database=e.datid
                    AND l.locktype='relation' AND l.relation='public.complaint_journal_control'::regclass AND l.mode='AccessExclusiveLock' AND l.granted)
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_locks l WHERE l.pid=d.pid AND l.database=e.datid
                    AND l.locktype='relation' AND l.relation='public.complaint_journal_control'::regclass)
              )"""
            var observed: PreAckBackends? = null
            observer.prepareStatement("""$cut SELECT
                (SELECT count(*) FROM tagged WHERE application_name='kira-qcap-e-$run') AS exporters,
                (SELECT count(*) FROM tagged WHERE application_name='kira-qcap-d-$run') AS dumps, p.*
                FROM (SELECT 1) one LEFT JOIN pair p ON true""").use { statement ->
                statement.setString(1, database); statement.setString(2, username)
                await(budget.remainingMillis(10_000)) {
                    assertFalse(running.isDone, "Capture ended before the exact earlier-table wait.")
                    statement.executeQuery().use { row ->
                        check(row.next())
                        check(row.getLong("exporters") in 0L..1L && row.getLong("dumps") in 0L..1L) { "Ambiguous fixture run tags." }
                        val pid = row.getInt("exporter_pid")
                        if (!row.wasNull()) observed = PreAckBackends(run, pid, row.getLong("exporter_started"), row.getLong("exporter_transaction"),
                            row.getInt("dump_pid"), row.getLong("dump_started"), row.getLong("dump_transaction"))
                        check(!row.next())
                    }
                    observed != null
                }
            }
            val exact = checkNotNull(observed)
            observer.prepareStatement("""$cut SELECT pg_catalog.pg_terminate_backend(exporter_pid) FROM pair
                WHERE exporter_pid=? AND exporter_started=? AND exporter_transaction=?
                  AND dump_pid=? AND dump_started=? AND dump_transaction=?
                  AND (SELECT count(*) FROM tagged WHERE application_name='kira-qcap-e-$run')=1
                  AND (SELECT count(*) FROM tagged WHERE application_name='kira-qcap-d-$run')=1""").use { statement ->
                statement.setString(1, database); statement.setString(2, username)
                listOf(exact.exporterPid.toLong(), exact.exporterStarted, exact.exporterTransaction,
                    exact.dumpPid.toLong(), exact.dumpStarted, exact.dumpTransaction).forEachIndexed { index, value -> statement.setLong(index + 3, value) }
                assertFalse(running.isDone, "Capture ended before the exact exporter-loss signal.")
                statement.executeQuery().use { row ->
                    check(row.next()) { "Exact pre-ACK fixture identities/locks changed; no signal admitted." }
                    check(row.getBoolean(1) && !row.wasNull() && !row.next())
                }
            }
            // A signal Boolean is not termination. Match PID/start even if other metadata changed,
            // and require actual disappearance BEFORE Future completion; a missed window fails.
            observer.prepareStatement("""SELECT count(*) FROM pg_catalog.pg_stat_activity
                WHERE pid=? AND (extract(epoch FROM backend_start)*1000000)::bigint=?""").use { statement ->
                statement.setInt(1, exact.exporterPid); statement.setLong(2, exact.exporterStarted)
                await(500) {
                    assertFalse(running.isDone, "Capture completed before exporter-loss observation.")
                    statement.executeQuery().use { row -> check(row.next()); val count = row.getLong(1); check(!row.next()); count == 0L }
                }
                assertFalse(running.isDone, "Capture completed before exporter-loss observation.")
            }
            return exact
        }

        fun dumpRows(dump: Path, table: String): List<String> {
            require(table in setOf("capture_rows", "complaint_journal_control"))
            val selected = fixture.resolve("inspection-$table.sql")
            LogicalCaptureFilesV1.write(selected, ByteArray(0))
            val builder = ProcessBuilder(tools.pgRestore.path.toString(), "--file=-", "--data-only", "--no-owner", "--no-acl", "--table=$table", dump.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(selected.toFile())
            builder.environment().clear()
            builder.environment().putAll(mapOf("LANG" to "C", "LC_ALL" to "C", "PATH" to "/nonexistent", "HOME" to fixture.toString()))
            val process = builder.start()
            try {
                process.outputStream.close()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
                val raw = LogicalCaptureFilesV1.read(selected, 65536, PersistenceTimeBudget.start(1000), requirePrivate = true).toString(Charsets.UTF_8)
                val lines = raw.lines()
                val start = lines.indexOfFirst { it.startsWith("COPY public.$table (") }
                assertTrue(start >= 0)
                return lines.drop(start + 1).takeWhile { it != "\\." }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            }
        }

        private fun await(millis: Long, predicate: () -> Boolean) {
            val budget = PersistenceTimeBudget.start(millis)
            while (!predicate()) TimeUnit.MILLISECONDS.sleep(budget.remainingMillis(5))
        }

        private fun image(name: String) = LogicalCaptureImageV1(Path.of(required(name)), required("${name}_SHA256"))
        private fun required(name: String) = checkNotNull(System.getenv("KIRA_QCAP_IT_$name")) { "Missing explicit local capture IT configuration." }
        private fun sha(bytes: ByteArray): String = with(LogicalCaptureFilesV1) { MessageDigest.getInstance("SHA-256").digest(bytes).hex() }
    }
}
