package me.manga.kira.backend.complaint.infrastructure.restore

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLogicalBackupCaptureSessionV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.SystemPersistenceNanoClock
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Dormant, one-shot, local operator capture. Not a bean, command-line entry point, JDBC role,
 * coordinator registration, backup acceptance, restore admission or production readiness path.
 * There is intentionally NO epoch/control mutation until a real pre-boundary producer exists.
 */
internal class ComplaintLogicalBackupCaptureV1 private constructor(private val input: LocalLogicalBackupInputV1) {
    private val used = AtomicBoolean()
    private var stage: Path? = null
    private var lockChannel: FileChannel? = null
    private var parentLock: FileLock? = null
    private var dump: Pg17LogicalBackupDumpV1? = null
    private var session: PersistenceLogicalBackupCaptureSessionV1? = null

    /** No caller receipt/Boolean/checkpoint can shortcut this original's native handoff. */
    fun capture(): LogicalBackupCaptureResultV1 {
        if (!used.compareAndSet(false, true)) return refusal(LogicalCaptureFailureV1.ALREADY_USED, retained.get() !== this)
        if (!retained.compareAndSet(null, this)) return refusal(LogicalCaptureFailureV1.BUSY, true)
        val total = PersistenceTimeBudget.start(LogicalCaptureLimitsV1.TOTAL_MILLIS, SystemPersistenceNanoClock)
        var candidate: QuarantinedLogicalCaptureV1? = null
        var failure: LogicalCaptureFailureV1? = null
        var fatal: Error? = null
        var cleanupProven = false
        try {
            captureRemaining(total)
            retainParent()
            val token = UUID.randomUUID().toString().replace("-", "")
            val selectedStage = input.privateParent.resolve(".logical-backup-q-$token")
            stage = selectedStage // Keep even a failed/ambiguous creation; no same-attempt retry or cleanup-tree deletion.
            LogicalCaptureFilesV1.createDirectory(selectedStage)
            val nativeDump = Pg17LogicalBackupDumpV1(input, selectedStage, token, total)
            dump = nativeDump
            nativeDump.prepare()
            val handoff = total.capped(LogicalCaptureLimitsV1.HANDOFF_MILLIS)
            val exporter = PersistenceLogicalBackupCaptureSessionV1(input, nativeDump, handoff)
            session = exporter
            exporter.open()
            val snapshot = exporter.snapshotFor(nativeDump)
            nativeDump.start(exporter, handoff)
            val imported = exporter.awaitImported()
            exporter.releaseAfterImport()
            val (manifest, dumpFile, media) = nativeDump.finish(exporter)
            captureRemaining(total)
            candidate = QuarantinedLogicalCaptureV1(selectedStage.fileName.toString(), snapshot, exporter.releasedSocketFor(nativeDump), imported, manifest, dumpFile, media,
                input.tools.psql.sha256, input.tools.pgDump.sha256, input.tools.pgRestore.sha256)
        } catch (problem: Throwable) {
            failure = classify(problem)
            if (problem is InterruptedException) Thread.currentThread().interrupt()
            if (problem is Error) fatal = problem
        } finally {
            // Cleanup has its own finite allowance, never a new work/import budget. Preserve the original interrupt.
            val interrupted = Thread.interrupted()
            try {
                val cleanup = PersistenceTimeBudget.start(LogicalCaptureLimitsV1.CLEANUP_MILLIS, SystemPersistenceNanoClock)
                val dumpEnded = dump?.stop(cleanup) ?: true
                val exporterReleased = session?.stop(cleanup) ?: true
                cleanupProven = dumpEnded && exporterReleased
                if (dumpEnded && session?.localChildrenEnded != false) {
                    try { dump?.removePrivateAuthentication() } catch (_: Throwable) { cleanupProven = false }
                }
            } catch (_: Throwable) {
                cleanupProven = false
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        if (!cleanupProven && failure == null) failure = LogicalCaptureFailureV1.CLEANUP
        if (failure == null && candidate != null && cleanupProven) {
            try {
                captureRemaining(total)
                writeQuarantine(checkNotNull(candidate))
                captureRemaining(total)
            } catch (problem: Throwable) {
                failure = classify(problem)
                if (problem is Error) fatal = problem
            }
        }
        if (cleanupProven) {
            try {
                parentLock?.release()
                lockChannel?.close()
                parentLock = null
                lockChannel = null
            } catch (_: Throwable) { cleanupProven = false }
        }
        if (failure == null && candidate != null) {
            try { captureRemaining(total) } catch (problem: Throwable) { failure = classify(problem) }
        }
        // Unknown native/guard/pipe/file-lock cleanup retains this whole original and blocks another capture.
        // A count, timeout, kill request or absent process-list entry is never a slot-refund receipt.
        if (cleanupProven) retained.compareAndSet(this, null)
        fatal?.let { throw it }
        return if (failure == null && cleanupProven && candidate != null) {
            LogicalBackupCaptureResultV1.Quarantined(checkNotNull(candidate))
        } else {
            refusal(failure ?: LogicalCaptureFailureV1.CLEANUP, cleanupProven)
        }
    }

    private fun retainParent() {
        LogicalCaptureFilesV1.checkedPath(input.privateParent, directory = true, requirePrivate = true)
        val lockPath = input.privateParent.resolve(".logical-backup-capture.lock")
        try {
            lockChannel = LogicalCaptureFilesV1.newFile(lockPath)
        } catch (_: FileAlreadyExistsException) {
            LogicalCaptureFilesV1.checkedPath(lockPath, requirePrivate = true)
            lockChannel = FileChannel.open(lockPath, WRITE, NOFOLLOW_LINKS)
        }
        LogicalCaptureFilesV1.checkedPath(lockPath, requirePrivate = true)
        parentLock = checkNotNull(lockChannel).tryLock()
        captureRequire(parentLock != null, LogicalCaptureFailureV1.BUSY)
    }

    private fun writeQuarantine(record: QuarantinedLogicalCaptureV1) {
        val mapper = LogicalCaptureJsonV1.mapper
        val node = mapper.createObjectNode()
            .put("schema", "kira.logical-backup-quarantine.v1").put("state", record.state).put("acceptance", record.acceptance)
            .put("epoch_boundary", record.epochBoundary).put("pre_post_reconciliation", record.prePostReconciliation)
            .put("media_writer_exclusion_and_drain", record.mediaWriterExclusionAndDrain)
            .put("writer_restore_catalog_binding", "NOT_IMPLEMENTED").put("stage", record.stageName)
            .put("guard_release", "NATIVE_ROLLBACK_CONFIRMED").put("local_native_cleanup", "CONFIRMED")
        node.putObject("source").put("address", "127.0.0.1").put("port", input.port).put("database", input.database)
            .put("role", input.username).put("database_oid", record.exported.databaseOid).put("server_version_num", 170006)
        node.putObject("snapshot").put("id", record.exported.snapshot).put("xmin", record.exported.xmin).put("guard_oid", record.exported.guardOid)
            .put("exporter_pid", record.exported.backendPid).put("exporter_started_micros", record.exported.backendStartedMicros)
            .put("exported_micros", record.exported.observedMicros).put("dump_backend_pid", record.imported.dumpBackendPid)
            .put("dump_backend_started_micros", record.imported.backendStartedMicros).put("dump_transaction_started_micros", record.imported.transactionStartedMicros)
            .put("native_wait_observed_micros", record.imported.observedMicros)
        node.putObject("native_child").put("pid", record.imported.socket.childPid).put("started", record.imported.socket.childStarted.toString())
            .put("socket_inode", record.imported.socket.inode).put("client_port", record.imported.socket.clientPort)
        node.putObject("exporter_native_child").put("pid", record.exporterNative.childPid).put("started", record.exporterNative.childStarted.toString())
            .put("socket_inode", record.exporterNative.inode).put("client_port", record.exporterNative.clientPort)
        node.putObject("builds").put("source_revision", "REL_17_6").put("psql_sha256", record.psqlSha256).put("pg_dump_sha256", record.pgDumpSha256)
            .put("pg_restore_sha256", record.pgRestoreSha256).put("python_sha256", input.tools.python.sha256)
            .put("bundle_helper_sha256", LogicalCaptureLimitsV1.BUNDLE_HELPER_SHA256)
        for ((role, file) in listOf("manifest" to record.manifest, "dump" to record.dump, "media" to record.media)) {
            node.putObject(role).put("name", file.name).put("bytes", file.bytes).put("sha256", file.sha256)
        }
        val raw = mapper.writeValueAsBytes(node) + byteArrayOf(10)
        captureRequire(raw.size <= LogicalCaptureLimitsV1.MAX_FRAME, LogicalCaptureFailureV1.SIZE)
        // A private diagnostic record, NOT kcj-1, a bundle manifest replacement or an acceptance envelope.
        LogicalCaptureFilesV1.write(checkNotNull(stage).resolve("capture.json"), raw)
    }

    private fun refusal(failure: LogicalCaptureFailureV1, cleaned: Boolean) = LogicalBackupCaptureResultV1.Refused(
        failure, if (cleaned) LogicalCaptureCleanupV1.CONFIRMED else LogicalCaptureCleanupV1.RETAINED_UNKNOWN, stage?.fileName?.toString(),
    )

    private fun classify(problem: Throwable): LogicalCaptureFailureV1 = when (problem) {
        is LogicalCaptureRefusalV1 -> problem.code
        is InterruptedException -> LogicalCaptureFailureV1.INTERRUPTED
        is me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException -> LogicalCaptureFailureV1.TIMEOUT
        else -> LogicalCaptureFailureV1.INTERNAL
    }

    override fun toString(): String = "ComplaintLogicalBackupCaptureV1(one-shot,quarantine-only,redacted)"

    companion object {
        // Concrete capacity-one operator resource, independent of every app/JDBC/campaign role.
        private val retained = AtomicReference<ComplaintLogicalBackupCaptureV1?>()
        fun prepare(input: LocalLogicalBackupInputV1): ComplaintLogicalBackupCaptureV1 = ComplaintLogicalBackupCaptureV1(input)
    }
}
