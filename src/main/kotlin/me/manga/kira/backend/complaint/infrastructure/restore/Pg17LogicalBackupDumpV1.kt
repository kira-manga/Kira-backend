package me.manga.kira.backend.complaint.infrastructure.restore

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLogicalBackupCaptureSessionV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One concrete pg_dump and the fixed v1 inspection commands, NOT a general process launcher.
 * Dump stdout is drained into a CREATE_NEW/0600 bounded file; stderr is never retained or logged.
 * All started native handles and pump bodies stay with this original, including failed starts.
 */
internal class Pg17LogicalBackupDumpV1 internal constructor(
    private val input: LocalLogicalBackupInputV1,
    val stage: Path,
    val token: String,
    private val total: PersistenceTimeBudget,
) {
    val application = "kira-qcap-d-$token"
    val dumpPath: Path = stage.resolve("capture.dump")
    val mediaPath: Path = stage.resolve("capture.media.tar.gz")
    val bundlePath: Path = stage.resolve("capture.bundle.json")
    private val shorts = ArrayList<ShortCommand>(6)
    private var exporter: PersistenceLogicalBackupCaptureSessionV1? = null
    private var dumpBudget: PersistenceTimeBudget? = null
    private var process: Process? = null
    private var child: LogicalCaptureChildV1? = null
    private var output: FileChannel? = null
    private var pump: Thread? = null
    private val pumpEnded = AtomicBoolean()
    private val pumpFailed = AtomicBoolean()
    private var launchAttempted = false
    private var stopped = false
    private var imported: LogicalCaptureImportV1? = null
    private var media: LogicalCaptureFileV1? = null
    private var passCustody = PassFileCustody.UNATTEMPTED
    private var passFacts: Map<String, Any>? = null
    private var trustFacts: Map<String, Any>? = null

    /** All expensive file/build checks and media selection happen before the guard-lock deadline begins. */
    fun prepare() {
        captureRequire(!stopped && exporter == null && process == null, LogicalCaptureFailureV1.PROTOCOL)
        listOf(input.tools.psql, input.tools.pgDump, input.tools.pgRestore, input.tools.python).forEach { it.prepare(total) }
        val helper = LogicalCaptureFilesV1.hash(input.tools.bundleHelper, 256 * 1024L, total)
        captureRequire(helper.sha256 == LogicalCaptureLimitsV1.BUNDLE_HELPER_SHA256, LogicalCaptureFailureV1.IMAGE)
        prepareCredentials()
        version(input.tools.psql, "psql")
        version(input.tools.pgDump, "pg_dump")
        version(input.tools.pgRestore, "pg_restore")
        media = LogicalCaptureFilesV1.copyMedia(input.selectedMediaArchive, mediaPath, input.selectedMediaSha256, total)
    }

    private fun prepareCredentials() {
        captureRequire(passCustody == PassFileCustody.UNATTEMPTED, LogicalCaptureFailureV1.PROTOCOL)
        val pass = LogicalCaptureFilesV1.read(input.passFile, 4096, total, requirePrivate = true)
        try {
            val prefix = "127.0.0.1:${input.port}:${input.database}:${input.username}:".toByteArray(Charsets.US_ASCII)
            val end = if (pass.last() == 10.toByte()) pass.size - 1 else pass.size
            captureRequire(end > prefix.size && prefix.indices.all { pass[it] == prefix[it] } &&
                (0 until end).none { pass[it] == 0.toByte() || pass[it] == 10.toByte() || pass[it] == 13.toByte() }, LogicalCaptureFailureV1.INPUT)
            // No wildcard credentials, password hash, log, environment password or command-line password.
            // Retain possible creation BEFORE write/fsync/post-write identity capture can fail.
            passCustody = PassFileCustody.MAY_EXIST
            LogicalCaptureFilesV1.write(stage.resolve(".pgpass"), pass)
            passFacts = LogicalCaptureFilesV1.checkedPath(stage.resolve(".pgpass"), requirePrivate = true)
        } finally {
            pass.fill(0)
        }
        val trust = LogicalCaptureFilesV1.read(input.trustFile, 65536, total)
        val sha = with(LogicalCaptureFilesV1) { MessageDigest.getInstance("SHA-256").digest(trust).hex() }
        captureRequire(sha == input.trustSha256, LogicalCaptureFailureV1.INPUT)
        LogicalCaptureFilesV1.write(stage.resolve("trust.pem"), trust)
        trustFacts = LogicalCaptureFilesV1.checkedPath(stage.resolve("trust.pem"), requirePrivate = true)
    }

    /** The only libpq profile: same-netns, direct numeric loopback TCP, verify-full/SCRAM, no ambient settings. */
    fun exporterEnvironment(owner: PersistenceLogicalBackupCaptureSessionV1): Map<String, String> {
        captureRequire(exporter === owner, LogicalCaptureFailureV1.PROTOCOL)
        return environment("kira-qcap-e-$token")
    }

    private fun environment(application: String): Map<String, String> {
        captureRequire(passFacts != null && passFacts == LogicalCaptureFilesV1.checkedPath(stage.resolve(".pgpass"), requirePrivate = true) &&
            trustFacts != null && trustFacts == LogicalCaptureFilesV1.checkedPath(stage.resolve("trust.pem"), requirePrivate = true), LogicalCaptureFailureV1.CUSTODY)
        return mapOf(
            "PATH" to "/nonexistent", "HOME" to stage.toString(), "LANG" to "C", "LC_ALL" to "C", "TZ" to "UTC",
            "PGHOST" to "127.0.0.1", "PGHOSTADDR" to "127.0.0.1", "PGPORT" to input.port.toString(),
            "PGDATABASE" to input.database, "PGUSER" to input.username, "PGPASSFILE" to stage.resolve(".pgpass").toString(),
            "PGSSLMODE" to "verify-full", "PGSSLROOTCERT" to stage.resolve("trust.pem").toString(),
            "PGGSSENCMODE" to "disable", "PGCHANNELBINDING" to "require", "PGREQUIREAUTH" to "scram-sha-256",
            "PGCONNECT_TIMEOUT" to "2", "PGCLIENTENCODING" to "UTF8", "PGTARGETSESSIONATTRS" to "read-write",
            "PGAPPNAME" to application,
            "PGOPTIONS" to "-c statement_timeout=1000 -c idle_in_transaction_session_timeout=5000 -c idle_session_timeout=5000 -c client_min_messages=error",
        )
    }

    fun attach(owner: PersistenceLogicalBackupCaptureSessionV1) {
        captureRequire(exporter == null && media != null && !stopped, LogicalCaptureFailureV1.PROTOCOL)
        exporter = owner
    }

    fun start(owner: PersistenceLogicalBackupCaptureSessionV1, handoff: PersistenceTimeBudget) {
        captureRequire(exporter === owner && !launchAttempted && !stopped, LogicalCaptureFailureV1.PROTOCOL)
        val snapshot = owner.snapshotFor(this)
        input.tools.pgDump.recheck()
        captureRemaining(handoff)
        dumpBudget = total.capped(LogicalCaptureLimitsV1.DUMP_MILLIS)
        output = LogicalCaptureFilesV1.newFile(dumpPath)
        // No filters, jobs, user options or output pathname: the guard must be included and the pipe is byte-bounded.
        val builder = ProcessBuilder(
            input.tools.pgDump.path.toString(), "--no-password", "--format=custom", "--compress=9", "--no-owner", "--no-acl",
            "--lock-wait-timeout=1000ms", "--snapshot=${snapshot.snapshot}",
        ).directory(stage.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().clear()
        builder.environment().putAll(environment(application))
        pump = Thread.ofPlatform().daemon().name("kira-quarantine-dump-pipe").unstarted(::drainDump)
        captureRemaining(handoff)
        launchAttempted = true
        process = builder.start() // Retain the actual Process before any inspection/stream call.
        checkNotNull(process).outputStream.close()
        checkNotNull(pump).start()
        child = input.tools.pgDump.bind(checkNotNull(process), handoff)
    }

    private fun drainDump() {
        try {
            val stream = checkNotNull(process).inputStream
            val channel = checkNotNull(output)
            var bytes = 0L
            stream.use { source ->
                val block = ByteArray(65536)
                while (true) {
                    captureRemaining(checkNotNull(dumpBudget))
                    val count = source.read(block)
                    if (count < 0) break
                    bytes += count
                    captureRequire(bytes <= LogicalCaptureLimitsV1.MAX_DUMP, LogicalCaptureFailureV1.SIZE)
                    val buffer = ByteBuffer.wrap(block, 0, count)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
            }
            captureRequire(bytes > 0, LogicalCaptureFailureV1.SIZE)
            channel.force(true)
        } catch (_: Throwable) {
            pumpFailed.set(true) // Never retain or log raw archive/OS exceptions.
        } finally {
            try { output?.close() } catch (_: Throwable) { pumpFailed.set(true) }
            pumpEnded.set(true)
        }
    }

    fun socket(owner: PersistenceLogicalBackupCaptureSessionV1): LogicalCaptureSocketV1? {
        captureRequire(exporter === owner && !stopped && !pumpFailed.get(), LogicalCaptureFailureV1.PROTOCOL)
        captureRemaining(checkNotNull(dumpBudget))
        return checkNotNull(child).socket(input.port)
    }

    fun imported(owner: PersistenceLogicalBackupCaptureSessionV1, witness: LogicalCaptureImportV1) {
        captureRequire(exporter === owner && imported == null && !stopped && owner.witnessFor(this) === witness, LogicalCaptureFailureV1.PROTOCOL)
        imported = witness
    }

    fun finish(owner: PersistenceLogicalBackupCaptureSessionV1): Triple<LogicalCaptureFileV1, LogicalCaptureFileV1, LogicalCaptureFileV1> {
        captureRequire(exporter === owner && imported != null && !stopped, LogicalCaptureFailureV1.PROTOCOL)
        owner.requireReleasedFor(this)
        val native = checkNotNull(process)
        val budget = checkNotNull(dumpBudget)
        while (!native.waitFor(captureRemaining(budget, 25), TimeUnit.MILLISECONDS)) {
            captureRequire(!pumpFailed.get(), LogicalCaptureFailureV1.PROCESS)
        }
        while (!pumpEnded.get()) checkNotNull(pump).join(captureRemaining(budget, 25))
        if (checkNotNull(pump).isAlive) checkNotNull(pump).join(captureRemaining(budget))
        captureRequire(native.exitValue() == 0 && !pumpFailed.get() && !checkNotNull(pump).isAlive, LogicalCaptureFailureV1.PROCESS)
        input.tools.pgDump.recheck()
        val dump = LogicalCaptureFilesV1.hash(dumpPath, LogicalCaptureLimitsV1.MAX_DUMP, total, requirePrivate = true)
        runShort(ShortKind.TOC)
        val rawPin = runShort(ShortKind.CREATE)
        val pin = rawPin.toString(Charsets.US_ASCII)
        captureRequire(Regex("[0-9a-f]{64}\\n").matches(pin), LogicalCaptureFailureV1.BUNDLE)
        val manifest = LogicalCaptureFilesV1.hash(bundlePath, 4096, total, requirePrivate = true)
        captureRequire(pin == manifest.sha256 + "\n", LogicalCaptureFailureV1.BUNDLE)
        captureRequire(runShort(ShortKind.VERIFY, manifest.sha256).contentEquals(rawPin), LogicalCaptureFailureV1.BUNDLE)
        val selectedMedia = LogicalCaptureFilesV1.hash(mediaPath, LogicalCaptureLimitsV1.MAX_MEDIA, total, requirePrivate = true)
        val retainedMedia = checkNotNull(media)
        captureRequire(selectedMedia.bytes == retainedMedia.bytes && selectedMedia.sha256 == retainedMedia.sha256, LogicalCaptureFailureV1.CUSTODY)
        LogicalCaptureFilesV1.syncDirectory(stage)
        return Triple(manifest, dump, selectedMedia)
    }

    private fun version(image: LogicalCaptureImageV1, name: String) {
        val kind = when (name) { "psql" -> ShortKind.PSQL_VERSION; "pg_dump" -> ShortKind.DUMP_VERSION; else -> ShortKind.RESTORE_VERSION }
        val raw = runShort(kind).toString(Charsets.US_ASCII)
        captureRequire(Regex("${Regex.escape(name)} \\(PostgreSQL\\) 17\\.6(?: \\([^\\r\\n]{1,160}\\))?\\n").matches(raw), LogicalCaptureFailureV1.IMAGE)
        image.recheck()
    }

    /** Six fixed short commands at most; there is no callable raw-command/SQL launch interface. */
    private fun runShort(kind: ShortKind, manifestPin: String? = null): ByteArray {
        captureRequire(!stopped && shorts.size < 6, LogicalCaptureFailureV1.PROTOCOL)
        val image = when (kind) {
            ShortKind.PSQL_VERSION -> input.tools.psql
            ShortKind.DUMP_VERSION -> input.tools.pgDump
            ShortKind.RESTORE_VERSION, ShortKind.TOC -> input.tools.pgRestore
            ShortKind.CREATE, ShortKind.VERIFY -> input.tools.python
        }
        image.recheck()
        val arguments = when (kind) {
            ShortKind.PSQL_VERSION, ShortKind.DUMP_VERSION, ShortKind.RESTORE_VERSION -> listOf("--version")
            ShortKind.TOC -> listOf("--list")
            ShortKind.CREATE, ShortKind.VERIFY -> {
                captureRequire(LogicalCaptureFilesV1.hash(input.tools.bundleHelper, 256 * 1024L, total).sha256 ==
                    LogicalCaptureLimitsV1.BUNDLE_HELPER_SHA256, LogicalCaptureFailureV1.IMAGE)
                if (kind == ShortKind.VERIFY) captureRequire(manifestPin != null && SHA256.matches(manifestPin), LogicalCaptureFailureV1.BUNDLE)
                listOf("-I", "-B", input.tools.bundleHelper.toString(), if (kind == ShortKind.CREATE) "create" else "verify",
                    "--bundle", bundlePath.toString(), "--dump", dumpPath.toString(), "--media", mediaPath.toString()) +
                    if (kind == ShortKind.VERIFY) listOf("--expected-sha256", checkNotNull(manifestPin)) else emptyList()
            }
        }
        val command = ShortCommand(kind)
        shorts.add(command) // Before launch, including a native start whose result is unknown.
        val builder = ProcessBuilder(listOf(image.path.toString()) + arguments).directory(stage.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().clear()
        builder.environment().putAll(environment("kira-qcap-inspect-$token"))
        if (kind == ShortKind.TOC) {
            LogicalCaptureFilesV1.checkedPath(dumpPath, requirePrivate = true)
            builder.redirectInput(dumpPath.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        }
        captureRemaining(total)
        command.launch(builder)
        val result = command.await(total)
        captureRemaining(total)
        image.recheck()
        return result
    }

    /** Cleanup time never revives work. Unknown native starts/exits or pump bodies remain retained. */
    fun stop(cleanup: PersistenceTimeBudget): Boolean {
        stopped = true
        var proven = true
        for (command in shorts) if (!command.stop(cleanup)) proven = false
        val native = process
        if (launchAttempted && native == null) proven = false
        if (launchAttempted && (child == null || child?.unexpectedDescendantsObserved() == true)) proven = false
        if (native != null) {
            try {
                if (native.isAlive) native.destroyForcibly()
                if (!native.waitFor(captureRemaining(cleanup), TimeUnit.MILLISECONDS)) proven = false
                if (pump?.isAlive == true) pump?.join(captureRemaining(cleanup))
                if (!pumpEnded.get() || pump?.isAlive != false) proven = false
            } catch (failure: Throwable) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                proven = false
            }
        } else {
            try { output?.close() } catch (_: Throwable) { proven = false }
        }
        return proven
    }

    fun removePrivateAuthentication() {
        if (passCustody != PassFileCustody.MAY_EXIST) return
        val path = stage.resolve(".pgpass")
        val expected = passFacts
        // No fresh ownership claim or deletion when creation may have happened without retained facts.
        captureRequire(expected != null, LogicalCaptureFailureV1.CUSTODY)
        captureRequire(expected == LogicalCaptureFilesV1.checkedPath(path, requirePrivate = true), LogicalCaptureFailureV1.CUSTODY)
        Files.delete(path)
        LogicalCaptureFilesV1.syncDirectory(stage)
        passFacts = null
        passCustody = PassFileCustody.REMOVED
    }

    override fun toString(): String = "Pg17LogicalBackupDumpV1(owned-quarantined-process,redacted)"

    private enum class ShortKind { PSQL_VERSION, DUMP_VERSION, RESTORE_VERSION, TOC, CREATE, VERIFY }
    private enum class PassFileCustody { UNATTEMPTED, MAY_EXIST, REMOVED }

    private class ShortCommand(private val kind: ShortKind) {
        private var attempted = false
        private var process: Process? = null
        private var reader: Thread? = null
        private val ended = AtomicBoolean()
        private val failed = AtomicBoolean()
        private var descendantsObserved = false
        private var output = ByteArray(0)

        fun launch(builder: ProcessBuilder) {
            attempted = true
            process = builder.start()
            checkNotNull(process).outputStream.close()
            if (kind == ShortKind.TOC) {
                ended.set(true)
            } else {
                reader = Thread.ofPlatform().daemon().name("kira-quarantine-inspection-pipe").unstarted {
                    try {
                        output = checkNotNull(process).inputStream.use { it.readNBytes(LogicalCaptureLimitsV1.MAX_FRAME + 1) }
                        if (output.size > LogicalCaptureLimitsV1.MAX_FRAME) failed.set(true)
                    } catch (_: Throwable) { failed.set(true) } finally { ended.set(true) }
                }
                checkNotNull(reader).start()
            }
        }

        fun await(budget: PersistenceTimeBudget): ByteArray {
            val native = checkNotNull(process)
            while (!native.waitFor(captureRemaining(budget, 25), TimeUnit.MILLISECONDS)) {
                native.descendants().use { if (it.limit(1).count() != 0L) descendantsObserved = true }
                captureRequire(!failed.get() && !descendantsObserved, LogicalCaptureFailureV1.PROTOCOL)
            }
            while (!ended.get()) checkNotNull(reader).join(captureRemaining(budget, 25))
            captureRequire(native.exitValue() == 0 && !failed.get(), LogicalCaptureFailureV1.PROCESS)
            return output
        }

        fun stop(budget: PersistenceTimeBudget): Boolean {
            val native = process ?: return !attempted
            return try {
                if (native.isAlive) native.destroyForcibly()
                native.waitFor(captureRemaining(budget), TimeUnit.MILLISECONDS)
                if (reader?.isAlive == true) reader?.join(captureRemaining(budget))
                !native.isAlive && ended.get() && reader?.isAlive != true && !descendantsObserved
            } catch (failure: Throwable) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                false
            }
        }
    }
}
