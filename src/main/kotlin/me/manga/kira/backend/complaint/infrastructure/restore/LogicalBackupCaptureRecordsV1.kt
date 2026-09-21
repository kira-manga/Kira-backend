package me.manga.kira.backend.complaint.infrastructure.restore

import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant

/** Cold, explicit operator pins. A version string or a discovered PATH executable is not a build pin. */
internal class Pg176CaptureToolsV1(
    val psql: LogicalCaptureImageV1,
    val pgDump: LogicalCaptureImageV1,
    val pgRestore: LogicalCaptureImageV1,
    val python: LogicalCaptureImageV1,
    val bundleHelper: Path,
) {
    override fun toString(): String = "Pg176CaptureToolsV1(cold-operator-pins,not-qualified)"
}

/** No URL, service, socket directory, extra options, credential text or caller-selected SQL. */
internal class LocalLogicalBackupInputV1(
    val port: Int,
    val database: String,
    val username: String,
    val passFile: Path,
    val trustFile: Path,
    val trustSha256: String,
    val selectedMediaArchive: Path,
    val selectedMediaSha256: String,
    val privateParent: Path,
    val tools: Pg176CaptureToolsV1,
) {
    init {
        captureRequire(port in 1..65535 && NAME.matches(database) && NAME.matches(username), LogicalCaptureFailureV1.INPUT)
        captureRequire(SHA256.matches(trustSha256) && SHA256.matches(selectedMediaSha256), LogicalCaptureFailureV1.INPUT)
    }

    override fun toString(): String = "LocalLogicalBackupInputV1(loopback-only,redacted)"

    companion object {
        private val NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_-]{0,62}")
    }
}

internal enum class LogicalCaptureFailureV1 {
    INPUT, BUSY, ALREADY_USED, CUSTODY, IMAGE, CHILD_IDENTITY, PROCESS, PROTOCOL, DATABASE,
    AMBIGUOUS_BACKEND, HANDOFF, TIMEOUT, INTERRUPTED, SIZE, BUNDLE, CLEANUP, INTERNAL,
}

internal enum class LogicalCaptureCleanupV1 { CONFIRMED, RETAINED_UNKNOWN }

/** Deliberately no accepted/signed/registered/traffic-open result variant. These records are not capabilities. */
internal sealed interface LogicalBackupCaptureResultV1 {
    class Quarantined internal constructor(val record: QuarantinedLogicalCaptureV1) : LogicalBackupCaptureResultV1 {
        override fun toString(): String = "LogicalBackupCaptureResultV1(QUARANTINED,NOT_ACCEPTED)"
    }

    class Refused internal constructor(
        val failure: LogicalCaptureFailureV1,
        val cleanup: LogicalCaptureCleanupV1,
        val stageName: String?,
    ) : LogicalBackupCaptureResultV1 {
        override fun toString(): String = "LogicalBackupCaptureResultV1(REFUSED,$failure,$cleanup)"
    }
}

internal class LogicalCaptureFileV1(val name: String, val bytes: Long, val sha256: String) {
    init {
        captureRequire(Regex("[a-z0-9][a-z0-9_.-]{0,95}").matches(name) && bytes > 0 && SHA256.matches(sha256), LogicalCaptureFailureV1.BUNDLE)
    }

    override fun toString(): String = "LogicalCaptureFileV1(selected-bytes-only)"
}

internal class LogicalCaptureExportV1 internal constructor(
    val snapshot: String,
    val databaseOid: Long,
    val guardOid: Long,
    val backendPid: Int,
    val backendStartedMicros: Long,
    val transactionStartedMicros: Long,
    val observedMicros: Long,
    /** Full PostgreSQL xid8 decimal, including its epoch; not pg_stat_activity's 32-bit xid text. */
    val xmin: String,
    val clientPort: Int,
) {
    private val backendXminXid: String

    init {
        captureRequire(Regex("[0-9A-F]{8}-[0-9A-F]{8}-[1-9][0-9]{0,9}").matches(snapshot), LogicalCaptureFailureV1.PROTOCOL)
        captureRequire(databaseOid > 0 && guardOid > 0 && backendPid > 0 && clientPort in 1..65535, LogicalCaptureFailureV1.PROTOCOL)
        captureRequire(backendStartedMicros > 0 && transactionStartedMicros >= backendStartedMicros && observedMicros >= transactionStartedMicros,
            LogicalCaptureFailureV1.PROTOCOL)
        captureRequire(Regex("0|[1-9][0-9]{0,19}").matches(xmin), LogicalCaptureFailureV1.PROTOCOL)
        val fullXmin = xmin.toULongOrNull()
        captureRequire(fullXmin != null, LogicalCaptureFailureV1.PROTOCOL)
        backendXminXid = (checkNotNull(fullXmin) and 0xffff_ffffuL).toString()
    }

    /** Supplemental consistency only. Equality with derived decimal also refuses noncanonical/out-of-range xids. */
    fun matchesBackendXmin(xid: String): Boolean = xid == backendXminXid

    override fun toString(): String = "LogicalCaptureExportV1(historical-snapshot,no-epoch-authority)"
}

internal class LogicalCaptureImportV1 internal constructor(
    val dumpBackendPid: Int,
    val backendStartedMicros: Long,
    val transactionStartedMicros: Long,
    val observedMicros: Long,
    val socket: LogicalCaptureSocketV1,
) {
    override fun toString(): String = "LogicalCaptureImportV1(native-lock-witness,not-acceptance)"
}

internal class QuarantinedLogicalCaptureV1 internal constructor(
    val stageName: String,
    val exported: LogicalCaptureExportV1,
    val exporterNative: LogicalCaptureSocketV1,
    val imported: LogicalCaptureImportV1,
    val manifest: LogicalCaptureFileV1,
    val dump: LogicalCaptureFileV1,
    val media: LogicalCaptureFileV1,
    val psqlSha256: String,
    val pgDumpSha256: String,
    val pgRestoreSha256: String,
) {
    val state = "QUARANTINED"
    val acceptance = "NOT_ACCEPTED"
    val epochBoundary = "NOT_IMPLEMENTED"
    val prePostReconciliation = "NOT_IMPLEMENTED"
    val mediaWriterExclusionAndDrain = "NOT_PROVED"

    override fun toString(): String = "QuarantinedLogicalCaptureV1(NOT_ACCEPTED,NO_EPOCH_BOUNDARY,MEDIA_PAIR_UNPROVED)"
}

internal class LogicalCaptureRefusalV1(val code: LogicalCaptureFailureV1) : RuntimeException("Logical capture refused: ${code.name}.", null, false, false)

internal fun captureRequire(condition: Boolean, failure: LogicalCaptureFailureV1) {
    if (!condition) throw LogicalCaptureRefusalV1(failure)
}

internal fun captureRemaining(budget: PersistenceTimeBudget, cap: Long = Long.MAX_VALUE): Long {
    captureRequire(!Thread.currentThread().isInterrupted, LogicalCaptureFailureV1.INTERRUPTED)
    return try {
        budget.remainingMillis(cap)
    } catch (_: me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException) {
        throw LogicalCaptureRefusalV1(LogicalCaptureFailureV1.TIMEOUT)
    }
}

internal object LogicalCaptureLimitsV1 {
    const val TOTAL_MILLIS = 120_000L
    const val HANDOFF_MILLIS = 5_000L
    const val DUMP_MILLIS = 60_000L
    const val CLEANUP_MILLIS = 3_000L
    const val MAX_FRAME = 4_096
    const val MAX_IMAGE = 64L * 1024 * 1024
    const val MAX_DUMP = 2L * 1024 * 1024 * 1024
    const val MAX_MEDIA = 512L * 1024 * 1024
    const val MAX_PROC_BYTES = 256 * 1024
    const val MAX_FDS = 64
    // Exact unchanged v1 public-ABI implementation at this slice's base. Not a manifest hash or kcj-1 pin.
    const val BUNDLE_HELPER_SHA256 = "a5a85576b144e3b57cf98e25f24ecbe6a195d47ccfa793c638c3023b30e553fa"
}

internal val SHA256 = Regex("[0-9a-f]{64}")

/** Fixed bounded native reply/record codec, not a parser for caller-authored SQL or acceptance input. */
internal object LogicalCaptureJsonV1 {
    val mapper: JsonMapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

    fun read(raw: ByteArray): JsonNode {
        captureRequire(raw.isNotEmpty() && raw.size <= LogicalCaptureLimitsV1.MAX_FRAME, LogicalCaptureFailureV1.PROTOCOL)
        return try {
            mapper.readTree(raw).also { captureRequire(it != null && it.isObject, LogicalCaptureFailureV1.PROTOCOL) }
        } catch (_: Exception) {
            throw LogicalCaptureRefusalV1(LogicalCaptureFailureV1.PROTOCOL)
        }
    }

    fun keys(node: JsonNode, expected: Set<String>) = captureRequire(node.isObject && node.fieldNames().asSequence().toSet() == expected,
        LogicalCaptureFailureV1.PROTOCOL)

    fun text(node: JsonNode, key: String): String {
        val value = node.get(key)
        captureRequire(value != null && value.isTextual && value.textValue().length <= 128, LogicalCaptureFailureV1.PROTOCOL)
        return value.textValue()
    }

    fun number(node: JsonNode, key: String): Long {
        val value = node.get(key)
        captureRequire(value != null && value.isIntegralNumber && value.canConvertToLong(), LogicalCaptureFailureV1.PROTOCOL)
        return value.longValue()
    }

    fun bool(node: JsonNode, key: String): Boolean {
        val value = node.get(key)
        captureRequire(value != null && value.isBoolean, LogicalCaptureFailureV1.PROTOCOL)
        return value.booleanValue()
    }
}

/** Private Linux files under exclusive operator custody; no hostile-root/same-uid-racer claim. */
internal object LogicalCaptureFilesV1 {
    private val privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    private val privateDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    private fun uid(): Int = Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int

    fun checkedPath(path: Path, directory: Boolean = false, requirePrivate: Boolean = false): Map<String, Any> {
        captureRequire(System.getProperty("os.name") == "Linux" && path.isAbsolute && path.normalize() == path && path.toString().length <= 4096 &&
            path.toString().none { it.code < 32 || it.code == 127 }, LogicalCaptureFailureV1.CUSTODY)
        var current = path.root
        for (part in path) {
            current = current.resolve(part)
            val info = Files.readAttributes(current, "unix:mode,uid,ino,dev,size,nlink,lastModifiedTime,ctime", NOFOLLOW_LINKS)
            val mode = info.getValue("mode") as Int
            val last = current == path
            val wantedType = if (!last || directory) 0x4000 else 0x8000
            captureRequire((mode and 0xf000) == wantedType && (mode and 0x12) == 0 && (mode and 0xc00) == 0,
                LogicalCaptureFailureV1.CUSTODY)
            captureRequire(info["uid"] == 0 || info["uid"] == uid(), LogicalCaptureFailureV1.CUSTODY)
            if (last && requirePrivate) {
                captureRequire(info["uid"] == uid() && (mode and 0x1ff) == (if (directory) 0x1c0 else 0x180), LogicalCaptureFailureV1.CUSTODY)
                if (!directory) captureRequire(info["nlink"] == 1, LogicalCaptureFailureV1.CUSTODY)
            }
        }
        return Files.readAttributes(path, "unix:mode,uid,ino,dev,size,nlink,lastModifiedTime,ctime", NOFOLLOW_LINKS)
    }

    fun createDirectory(path: Path) {
        checkedPath(path.parent, directory = true, requirePrivate = true)
        Files.createDirectory(path, privateDirectory)
        checkedPath(path, directory = true, requirePrivate = true)
        syncDirectory(path.parent)
    }

    fun newFile(path: Path): FileChannel {
        checkedPath(path.parent, directory = true, requirePrivate = true)
        return FileChannel.open(path, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS), privateFile)
    }

    fun write(path: Path, bytes: ByteArray) {
        newFile(path).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        syncDirectory(path.parent)
    }

    fun read(path: Path, maximum: Long, budget: PersistenceTimeBudget, requirePrivate: Boolean = false): ByteArray {
        val before = checkedPath(path, requirePrivate = requirePrivate)
        val size = before.getValue("size") as Long
        captureRequire(size in 1..maximum, LogicalCaptureFailureV1.SIZE)
        val result = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { stream ->
            captureRemaining(budget)
            stream.readNBytes(Math.toIntExact(size + 1))
        }
        captureRequire(result.size.toLong() == size && before == checkedPath(path, requirePrivate = requirePrivate), LogicalCaptureFailureV1.CUSTODY)
        captureRemaining(budget)
        return result
    }

    fun hash(path: Path, maximum: Long, budget: PersistenceTimeBudget, requirePrivate: Boolean = false): LogicalCaptureFileV1 {
        val before = checkedPath(path, requirePrivate = requirePrivate)
        val size = before.getValue("size") as Long
        captureRequire(size in 1..maximum, LogicalCaptureFailureV1.SIZE)
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { stream ->
            val block = ByteArray(65536)
            while (true) {
                captureRemaining(budget)
                val count = stream.read(block)
                if (count < 0) break
                bytes += count
                captureRequire(bytes <= size, LogicalCaptureFailureV1.CUSTODY)
                digest.update(block, 0, count)
            }
        }
        captureRequire(bytes == size && before == checkedPath(path, requirePrivate = requirePrivate), LogicalCaptureFailureV1.CUSTODY)
        return LogicalCaptureFileV1(path.fileName.toString(), bytes, digest.digest().hex())
    }

    fun copyMedia(source: Path, target: Path, expected: String, budget: PersistenceTimeBudget): LogicalCaptureFileV1 {
        val before = checkedPath(source, requirePrivate = true)
        val size = before.getValue("size") as Long
        captureRequire(size in 1..LogicalCaptureLimitsV1.MAX_MEDIA, LogicalCaptureFailureV1.SIZE)
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        newFile(target).use { out ->
            Files.newInputStream(source, READ, NOFOLLOW_LINKS).use { input ->
                val block = ByteArray(65536)
                while (true) {
                    captureRemaining(budget)
                    val count = input.read(block)
                    if (count < 0) break
                    bytes += count
                    captureRequire(bytes <= size, LogicalCaptureFailureV1.CUSTODY)
                    digest.update(block, 0, count)
                    val buffer = ByteBuffer.wrap(block, 0, count)
                    while (buffer.hasRemaining()) out.write(buffer)
                }
            }
            out.force(true)
        }
        captureRequire(bytes == size && before == checkedPath(source, requirePrivate = true), LogicalCaptureFailureV1.CUSTODY)
        val actual = digest.digest().hex()
        captureRequire(actual == expected, LogicalCaptureFailureV1.BUNDLE)
        return LogicalCaptureFileV1(target.fileName.toString(), bytes, actual)
    }

    fun syncDirectory(path: Path) = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
    fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Exact installed image, subsequently checked through the *actual* long-lived child's /proc/exe. */
internal class LogicalCaptureImageV1(val path: Path, val sha256: String) {
    init { captureRequire(SHA256.matches(sha256), LogicalCaptureFailureV1.INPUT) }
    private var approvedFacts: Map<String, Any>? = null

    fun prepare(budget: PersistenceTimeBudget) {
        val facts = LogicalCaptureFilesV1.checkedPath(path)
        captureRequire(((facts.getValue("mode") as Int) and 0x49) != 0, LogicalCaptureFailureV1.IMAGE)
        captureRequire(LogicalCaptureFilesV1.hash(path, LogicalCaptureLimitsV1.MAX_IMAGE, budget).sha256 == sha256, LogicalCaptureFailureV1.IMAGE)
        captureRequire(facts == LogicalCaptureFilesV1.checkedPath(path), LogicalCaptureFailureV1.IMAGE)
        approvedFacts = facts
    }

    fun recheck() = captureRequire(approvedFacts != null && approvedFacts == LogicalCaptureFilesV1.checkedPath(path), LogicalCaptureFailureV1.IMAGE)

    fun bind(process: Process, budget: PersistenceTimeBudget): LogicalCaptureChildV1 {
        recheck()
        captureRequire(process.isAlive, LogicalCaptureFailureV1.CHILD_IDENTITY)
        val started = process.info().startInstant().orElse(null)
        captureRequire(started != null, LogicalCaptureFailureV1.CHILD_IDENTITY)
        val executable = Path.of("/proc", process.pid().toString(), "exe")
        // Deliberately follow this kernel-owned magic link, never an operator-supplied symlink.
        val facts = Files.readAttributes(executable, "unix:ino,dev,size,lastModifiedTime,ctime")
        val expected = checkNotNull(approvedFacts)
        captureRequire(facts.all { (key, value) -> expected[key] == value }, LogicalCaptureFailureV1.IMAGE)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        Files.newInputStream(executable).use { stream ->
            val block = ByteArray(65536)
            while (true) {
                captureRemaining(budget)
                val length = stream.read(block)
                if (length < 0) break
                count += length
                captureRequire(count <= LogicalCaptureLimitsV1.MAX_IMAGE, LogicalCaptureFailureV1.SIZE)
                digest.update(block, 0, length)
            }
        }
        val actual = with(LogicalCaptureFilesV1) { digest.digest().hex() }
        captureRequire(actual == sha256 && facts == Files.readAttributes(executable, "unix:ino,dev,size,lastModifiedTime,ctime"),
            LogicalCaptureFailureV1.IMAGE)
        return LogicalCaptureChildV1(process, checkNotNull(started), facts).also { it.requireAlive() }
    }

    override fun toString(): String = "LogicalCaptureImageV1(cold-build-pin,redacted)"
}

/** A native socket witness can only be made by observing a bound owned Process, not from a caller's tag. */
internal class LogicalCaptureSocketV1 private constructor(
    val childPid: Long, val childStarted: Instant, val inode: Long, val clientPort: Int, val serverPort: Int,
) {
    fun same(other: LogicalCaptureSocketV1): Boolean = childPid == other.childPid && childStarted == other.childStarted &&
        inode == other.inode && clientPort == other.clientPort && serverPort == other.serverPort
    override fun toString(): String = "LogicalCaptureSocketV1(owned-loopback-tcp)"

    companion object {
        internal fun observe(child: LogicalCaptureChildV1, port: Int): LogicalCaptureSocketV1? {
            child.requireAlive()
            val root = Path.of("/proc", child.process.pid().toString())
            captureRequire(Files.readSymbolicLink(root.resolve("ns/net")) == Files.readSymbolicLink(Path.of("/proc/self/ns/net")),
                LogicalCaptureFailureV1.CHILD_IDENTITY)
            val sockets = mutableSetOf<Long>()
            Files.newDirectoryStream(root.resolve("fd")).use { entries ->
                var count = 0
                for (entry in entries) {
                    captureRequire(++count <= LogicalCaptureLimitsV1.MAX_FDS, LogicalCaptureFailureV1.CHILD_IDENTITY)
                    val target = Files.readSymbolicLink(entry).toString()
                    if (target.startsWith("socket:[")) {
                        val match = Regex("socket:\\[([0-9]{1,19})]").matchEntire(target)
                        captureRequire(match != null, LogicalCaptureFailureV1.CHILD_IDENTITY)
                        sockets.add(checkNotNull(match).groupValues[1].toLong())
                    }
                }
            }
            captureRequire(sockets.size <= 1, LogicalCaptureFailureV1.CHILD_IDENTITY)
            if (sockets.isEmpty()) return null
            val tcp4 = procLines(root.resolve("net/tcp"))
            val tcp6 = procLines(root.resolve("net/tcp6"))
            val owned6 = tcp6.drop(1).map { it.trim().split(Regex("\\s+")) }.filter { it.size > 9 && it[9].toLongOrNull() in sockets }
            captureRequire(owned6.isEmpty(), LogicalCaptureFailureV1.CHILD_IDENTITY)
            val owned = tcp4.drop(1).map { it.trim().split(Regex("\\s+")) }.filter { it.size > 9 && it[9].toLongOrNull() in sockets }
            captureRequire(owned.size <= 1, LogicalCaptureFailureV1.CHILD_IDENTITY)
            val row = owned.singleOrNull() ?: return null
            val local = row[1].split(':')
            val peer = row[2].split(':')
            captureRequire(local.size == 2 && peer.size == 2 && local[0] == "0100007F" && peer[0] == "0100007F" &&
                peer[1].toInt(16) == port, LogicalCaptureFailureV1.CHILD_IDENTITY)
            child.requireAlive()
            if (row[3] != "01") return null // Still connecting; the unchanged handoff deadline remains in force.
            return LogicalCaptureSocketV1(child.process.pid(), child.started, row[9].toLong(), local[1].toInt(16), port)
        }

        private fun procLines(path: Path): List<String> {
            val bytes = Files.newInputStream(path).use { it.readNBytes(LogicalCaptureLimitsV1.MAX_PROC_BYTES + 1) }
            captureRequire(bytes.size <= LogicalCaptureLimitsV1.MAX_PROC_BYTES, LogicalCaptureFailureV1.CHILD_IDENTITY)
            return bytes.toString(Charsets.US_ASCII).lineSequence().filter { it.isNotBlank() }.toList()
        }
    }
}

internal class LogicalCaptureChildV1 internal constructor(
    val process: Process,
    val started: Instant,
    private val imageFacts: Map<String, Any>,
) {
    private var descendantsObserved = false

    fun requireAlive() {
        captureRequire(process.isAlive && process.info().startInstant().orElse(null) == started, LogicalCaptureFailureV1.CHILD_IDENTITY)
        captureRequire(imageFacts == Files.readAttributes(Path.of("/proc", process.pid().toString(), "exe"), "unix:ino,dev,size,lastModifiedTime,ctime"),
            LogicalCaptureFailureV1.CHILD_IDENTITY)
        process.descendants().use { if (it.limit(1).count() != 0L) descendantsObserved = true }
        captureRequire(!descendantsObserved, LogicalCaptureFailureV1.CHILD_IDENTITY)
    }

    fun unexpectedDescendantsObserved(): Boolean = descendantsObserved
    fun socket(port: Int): LogicalCaptureSocketV1? = LogicalCaptureSocketV1.observe(this, port)
    override fun toString(): String = "LogicalCaptureChildV1(actual-native-process,redacted)"
}
