package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.JsonNode
import me.manga.kira.backend.complaint.infrastructure.restore.LocalLogicalBackupInputV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalBackupCaptureSqlV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalCaptureChildV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalCaptureExportV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalCaptureFailureV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalCaptureImportV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalCaptureJsonV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalCaptureLimitsV1
import me.manga.kira.backend.complaint.infrastructure.restore.LogicalCaptureSocketV1
import me.manga.kira.backend.complaint.infrastructure.restore.Pg17LogicalBackupDumpV1
import me.manga.kira.backend.complaint.infrastructure.restore.captureRemaining
import me.manga.kira.backend.complaint.infrastructure.restore.captureRequire
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Quarantine-only operator psql exporter, NOT a JDBC participant or an epoch-rotation session.
 * No application pool, campaign, gate, checkpoint, epoch, raw connection or caller SQL is exposed.
 * The fixed guard lock is only an import-observation aid; it is NOT a V6 journal boundary.
 */
internal class PersistenceLogicalBackupCaptureSessionV1 internal constructor(
    private val input: LocalLogicalBackupInputV1,
    private val dump: Pg17LogicalBackupDumpV1,
    private val handoff: PersistenceTimeBudget,
) {
    private val caller = Thread.currentThread()
    private val replies = ArrayBlockingQueue<ByteArray>(2)
    private val readerEnded = AtomicBoolean()
    private val readerFailed = AtomicBoolean()
    private var reader: Thread? = null
    private var process: Process? = null
    private var child: LogicalCaptureChildV1? = null
    private var exporterSocket: LogicalCaptureSocketV1? = null
    private var dumpSocket: LogicalCaptureSocketV1? = null
    private var exported: LogicalCaptureExportV1? = null
    private var witness: LogicalCaptureImportV1? = null
    private var attempted = false
    private var exportSent = false
    private var released = false
    private var stopped = false
    var localChildrenEnded: Boolean = false
        private set

    fun open() {
        requireCaller()
        captureRequire(!attempted && !stopped, LogicalCaptureFailureV1.PROTOCOL)
        dump.attach(this)
        input.tools.psql.recheck()
        val builder = ProcessBuilder(input.tools.psql.path.toString(), "--no-psqlrc", "--quiet", "--tuples-only", "--no-align",
            "--no-password", "--set=ON_ERROR_STOP=1", "--pset=pager=off")
            .directory(dump.stage.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().clear()
        builder.environment().putAll(dump.exporterEnvironment(this))
        reader = Thread.ofPlatform().daemon().name("kira-quarantine-export-pipe").unstarted(::readReplies)
        captureRemaining(handoff)
        attempted = true
        process = builder.start()
        checkNotNull(reader).start()
        child = input.tools.psql.bind(checkNotNull(process), handoff)
        exportSent = true // Before the command can be delivered, including an ambiguous write.
        val raw = exchange(LogicalBackupCaptureSqlV1.export)
        exported = parseExport(raw)
        exporterSocket = checkNotNull(child).socket(input.port)
        captureRequire(exporterSocket?.clientPort == checkNotNull(exported).clientPort, LogicalCaptureFailureV1.CHILD_IDENTITY)
        captureRemaining(handoff)
    }

    fun snapshotFor(owner: Pg17LogicalBackupDumpV1): LogicalCaptureExportV1 {
        requireCaller()
        captureRequire(owner === dump && !stopped && !released, LogicalCaptureFailureV1.PROTOCOL)
        checkNotNull(child).requireAlive()
        captureRemaining(handoff)
        return checkNotNull(exported)
    }

    /** Native guard wait, twice freshly observed, bracketed by the exact child's image/socket checks. */
    fun awaitImported(): LogicalCaptureImportV1 {
        requireCaller()
        captureRequire(exported != null && witness == null && !stopped && !released, LogicalCaptureFailureV1.PROTOCOL)
        while (true) {
            captureRemaining(handoff)
            requireExporterSocket()
            val socket = dump.socket(this)
            if (socket == null) {
                TimeUnit.MILLISECONDS.sleep(captureRemaining(handoff, 10))
                continue
            }
            val previous = dumpSocket
            captureRequire(previous == null || previous.same(socket), LogicalCaptureFailureV1.CHILD_IDENTITY)
            dumpSocket = socket
            val first = observe(socket)
            if (first == null) {
                TimeUnit.MILLISECONDS.sleep(captureRemaining(handoff, 10))
                continue
            }
            val again = dump.socket(this)
            captureRequire(again != null && socket.same(again), LogicalCaptureFailureV1.CHILD_IDENTITY)
            requireExporterSocket()
            val second = observe(checkNotNull(again))
            captureRequire(second != null && first.dumpBackendPid == second.dumpBackendPid &&
                first.backendStartedMicros == second.backendStartedMicros && first.transactionStartedMicros == second.transactionStartedMicros &&
                second.observedMicros >= first.observedMicros, LogicalCaptureFailureV1.AMBIGUOUS_BACKEND)
            val finalSocket = dump.socket(this)
            captureRequire(finalSocket != null && socket.same(finalSocket), LogicalCaptureFailureV1.CHILD_IDENTITY)
            captureRemaining(handoff)
            witness = checkNotNull(second)
            dump.imported(this, checkNotNull(witness))
            return checkNotNull(witness)
        }
    }

    fun witnessFor(owner: Pg17LogicalBackupDumpV1): LogicalCaptureImportV1 {
        requireCaller()
        captureRequire(owner === dump && !stopped, LogicalCaptureFailureV1.PROTOCOL)
        return checkNotNull(witness)
    }

    /** No release-before-import API. ROLLBACK success, absence of our guard and real psql exit are all required. */
    fun releaseAfterImport() {
        requireCaller()
        captureRequire(witness != null && !released && !stopped, LogicalCaptureFailureV1.PROTOCOL)
        requireExporterSocket()
        val socket = dump.socket(this)
        captureRequire(socket != null && checkNotNull(dumpSocket).same(socket), LogicalCaptureFailureV1.CHILD_IDENTITY)
        val reply = exchange(LogicalBackupCaptureSqlV1.release(checkNotNull(exported)))
        LogicalCaptureJsonV1.keys(reply, setOf("kind", "pid", "guard_absent"))
        captureRequire(LogicalCaptureJsonV1.text(reply, "kind") == "released" &&
            LogicalCaptureJsonV1.number(reply, "pid") == checkNotNull(exported).backendPid.toLong() &&
            LogicalCaptureJsonV1.bool(reply, "guard_absent"), LogicalCaptureFailureV1.HANDOFF)
        val native = checkNotNull(process)
        native.outputStream.close()
        captureRequire(native.waitFor(captureRemaining(handoff), TimeUnit.MILLISECONDS), LogicalCaptureFailureV1.TIMEOUT)
        while (!readerEnded.get()) checkNotNull(reader).join(captureRemaining(handoff, 25))
        if (checkNotNull(reader).isAlive) checkNotNull(reader).join(captureRemaining(handoff))
        captureRequire(native.exitValue() == 0 && !readerFailed.get() && !checkNotNull(reader).isAlive && replies.isEmpty(), LogicalCaptureFailureV1.HANDOFF)
        released = true
        localChildrenEnded = true
        captureRemaining(handoff) // A real cleanup receipt still cannot turn a late original into successful handoff.
    }

    fun requireReleasedFor(owner: Pg17LogicalBackupDumpV1) {
        requireCaller()
        captureRequire(owner === dump && released && localChildrenEnded && witness != null && !stopped, LogicalCaptureFailureV1.HANDOFF)
    }

    fun releasedSocketFor(owner: Pg17LogicalBackupDumpV1): LogicalCaptureSocketV1 {
        requireReleasedFor(owner)
        return checkNotNull(exporterSocket)
    }

    private fun requireExporterSocket() {
        val actual = checkNotNull(child).socket(input.port)
        captureRequire(actual != null && checkNotNull(exporterSocket).same(actual), LogicalCaptureFailureV1.CHILD_IDENTITY)
    }

    private fun parseExport(node: JsonNode): LogicalCaptureExportV1 {
        LogicalCaptureJsonV1.keys(node, setOf("kind", "snapshot", "version", "database", "role", "database_oid", "guard_oid", "guard_valid", "pid",
            "backend_started", "transaction_started", "observed", "xmin", "server_address", "server_port", "client_address", "client_port"))
        captureRequire(LogicalCaptureJsonV1.text(node, "kind") == "export" && LogicalCaptureJsonV1.number(node, "version") == 170006L &&
            LogicalCaptureJsonV1.text(node, "database") == input.database && LogicalCaptureJsonV1.text(node, "role") == input.username &&
            LogicalCaptureJsonV1.bool(node, "guard_valid") && LogicalCaptureJsonV1.text(node, "server_address") == "127.0.0.1" &&
            LogicalCaptureJsonV1.text(node, "client_address") == "127.0.0.1" && LogicalCaptureJsonV1.number(node, "server_port") == input.port.toLong(),
            LogicalCaptureFailureV1.DATABASE)
        return LogicalCaptureExportV1(
            LogicalCaptureJsonV1.text(node, "snapshot"), LogicalCaptureJsonV1.number(node, "database_oid"), LogicalCaptureJsonV1.number(node, "guard_oid"),
            Math.toIntExact(LogicalCaptureJsonV1.number(node, "pid")), LogicalCaptureJsonV1.number(node, "backend_started"),
            LogicalCaptureJsonV1.number(node, "transaction_started"), LogicalCaptureJsonV1.number(node, "observed"), LogicalCaptureJsonV1.text(node, "xmin"),
            Math.toIntExact(LogicalCaptureJsonV1.number(node, "client_port")),
        )
    }

    private fun observe(socket: LogicalCaptureSocketV1): LogicalCaptureImportV1? {
        val original = checkNotNull(exported)
        val node = exchange(LogicalBackupCaptureSqlV1.observe(original, socket, dump.application))
        LogicalCaptureJsonV1.keys(node, setOf("kind", "tag_count", "exact_count", "exporter_pid", "exporter_started", "guard_held", "observed", "backend"))
        captureRequire(LogicalCaptureJsonV1.text(node, "kind") == "wait" && LogicalCaptureJsonV1.number(node, "exporter_pid") == original.backendPid.toLong() &&
            LogicalCaptureJsonV1.number(node, "exporter_started") == original.backendStartedMicros && LogicalCaptureJsonV1.bool(node, "guard_held"),
            LogicalCaptureFailureV1.HANDOFF)
        val tags = LogicalCaptureJsonV1.number(node, "tag_count")
        val exact = LogicalCaptureJsonV1.number(node, "exact_count")
        captureRequire(tags in 0..1 && exact in 0..1 && tags == exact, LogicalCaptureFailureV1.AMBIGUOUS_BACKEND)
        if (tags == 0L) {
            captureRequire(node.get("backend").isNull, LogicalCaptureFailureV1.PROTOCOL)
            return null
        }
        val backend = node.get("backend")
        LogicalCaptureJsonV1.keys(backend, setOf("pid", "backend_started", "transaction_started", "xmin", "active", "client", "waiting", "guard_waits", "blockers"))
        val waits = LogicalCaptureJsonV1.number(backend, "guard_waits")
        captureRequire(waits in 0..1, LogicalCaptureFailureV1.AMBIGUOUS_BACKEND)
        if (waits == 0L) return null // Catalog discovery is bounded by the ORIGINAL deadline, not just lock-wait-timeout.
        val blockers = backend.get("blockers")
        captureRequire(LogicalCaptureJsonV1.bool(backend, "active") && LogicalCaptureJsonV1.bool(backend, "client") &&
            LogicalCaptureJsonV1.bool(backend, "waiting") && blockers.isArray && blockers.size() == 1 &&
            blockers[0].isIntegralNumber && blockers[0].canConvertToInt() && blockers[0].intValue() == original.backendPid &&
            original.matchesBackendXmin(LogicalCaptureJsonV1.text(backend, "xmin")), LogicalCaptureFailureV1.HANDOFF)
        val pid = Math.toIntExact(LogicalCaptureJsonV1.number(backend, "pid"))
        val started = LogicalCaptureJsonV1.number(backend, "backend_started")
        val transaction = LogicalCaptureJsonV1.number(backend, "transaction_started")
        val observed = LogicalCaptureJsonV1.number(node, "observed")
        captureRequire(pid > 0 && pid != original.backendPid && started >= original.observedMicros && transaction >= started && observed >= transaction,
            LogicalCaptureFailureV1.HANDOFF)
        return LogicalCaptureImportV1(pid, started, transaction, observed, socket)
    }

    private fun exchange(sql: String): JsonNode {
        requireCaller()
        captureRemaining(handoff)
        val bytes = sql.toByteArray(Charsets.US_ASCII)
        captureRequire(bytes.isNotEmpty() && bytes.size <= LogicalCaptureLimitsV1.MAX_FRAME, LogicalCaptureFailureV1.PROTOCOL)
        // One <= PIPE_BUF command at a time on Linux; a reply proves consumption before another write.
        // No caller gets this pipe or this private method, and no shell/psql variable interpolation is used.
        checkNotNull(process).outputStream.write(bytes)
        checkNotNull(process).outputStream.flush()
        while (true) {
            captureRequire(!readerFailed.get(), LogicalCaptureFailureV1.PROTOCOL)
            val reply = replies.poll(captureRemaining(handoff, 10), TimeUnit.MILLISECONDS)
            if (reply != null) return LogicalCaptureJsonV1.read(reply)
            captureRequire(!readerEnded.get(), LogicalCaptureFailureV1.PROCESS)
        }
    }

    private fun readReplies() {
        try {
            checkNotNull(process).inputStream.use { stream ->
                val frame = ByteArrayOutputStream(LogicalCaptureLimitsV1.MAX_FRAME)
                while (true) {
                    val byte = stream.read()
                    if (byte < 0) {
                        captureRequire(frame.size() == 0, LogicalCaptureFailureV1.PROTOCOL)
                        break
                    }
                    if (byte == 10) {
                        captureRequire(frame.size() > 0 && replies.offer(frame.toByteArray()), LogicalCaptureFailureV1.PROTOCOL)
                        frame.reset()
                    } else {
                        captureRequire(byte in 32..126 && frame.size() < LogicalCaptureLimitsV1.MAX_FRAME, LogicalCaptureFailureV1.PROTOCOL)
                        frame.write(byte)
                    }
                }
            }
        } catch (_: Throwable) { readerFailed.set(true) } finally { readerEnded.set(true) }
    }

    /** Native exit alone does not certify server guard release after an ambiguous delivered command. */
    fun stop(cleanup: PersistenceTimeBudget): Boolean {
        stopped = true
        val native = process
        if (native == null) {
            localChildrenEnded = !attempted
            return localChildrenEnded
        }
        localChildrenEnded = try {
            if (native.isAlive) native.destroyForcibly()
            native.waitFor(captureRemaining(cleanup), TimeUnit.MILLISECONDS)
            if (reader?.isAlive == true) reader?.join(captureRemaining(cleanup))
            !native.isAlive && readerEnded.get() && reader?.isAlive == false
        } catch (failure: Throwable) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            false
        }
        return localChildrenEnded && child != null && child?.unexpectedDescendantsObserved() == false && (!exportSent || released)
    }

    private fun requireCaller() = captureRequire(Thread.currentThread() === caller, LogicalCaptureFailureV1.PROTOCOL)
    override fun toString(): String = "PersistenceLogicalBackupCaptureSessionV1(operator-only,no-JDBC-or-epoch-authority)"
}
