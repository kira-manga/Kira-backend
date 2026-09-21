package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.TestActiveInitialCheckpointReaderV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.requireInitialCheckpoint

/** Closed read-only calls. No caller key/version, continuation, publication candidate or PUT branch. */
internal class TestActiveInitialCheckpointS3CallV1 private constructor(
    val reader: TestActiveInitialCheckpointReaderV1,
    val operation: JournalS3OperationV1,
    val objectKey: String,
    val versionId: String?,
    private val nanoTime: () -> Long,
) {
    val declaration = reader.routing.journalConfiguration.declaration()
    private val started = nanoTime()
    private val allowance = reader.remainingNativeMillis(declaration.limits.deadlines.s3CallMillis) * 1_000_000L
    private var lastElapsed = 0L
    private var expired = false

    @Synchronized
    fun remainingMillis(): Int {
        requireConnectionFree()
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val outer = reader.remainingNativeMillis(declaration.limits.deadlines.s3CallMillis)
        val elapsed = nanoTime() - started
        val remaining = (allowance - elapsed) / 1_000_000L
        if (expired || elapsed < 0 || elapsed < lastElapsed || remaining <= 0) {
            expired = true
            requireInitialCheckpoint(false)
        }
        lastElapsed = elapsed
        return minOf(outer.toLong(), remaining).toInt()
    }
    fun check() { remainingMillis() }
    override fun toString(): String = "InitialCheckpointS3Call(read-only-original,redacted)"
    companion object {
        fun listSeal(reader: TestActiveInitialCheckpointReaderV1, nanoTime: () -> Long): TestActiveInitialCheckpointS3CallV1 {
            reader.requireSealRead()
            return TestActiveInitialCheckpointS3CallV1(reader, JournalS3OperationV1.LIST, reader.sealObjectKey(), null, nanoTime)
        }
        fun getSeal(reader: TestActiveInitialCheckpointReaderV1, nanoTime: () -> Long): TestActiveInitialCheckpointS3CallV1 {
            reader.requireSealRead()
            return TestActiveInitialCheckpointS3CallV1(reader, JournalS3OperationV1.GET, reader.sealObjectKey(), requireJournalVersion(reader.sealVersion()), nanoTime)
        }
        fun listOrdinary(reader: TestActiveInitialCheckpointReaderV1, nanoTime: () -> Long): TestActiveInitialCheckpointS3CallV1 {
            reader.requirePassRead()
            return TestActiveInitialCheckpointS3CallV1(reader, JournalS3OperationV1.LIST, reader.routing.journalConfiguration.ordinaryPrefix, null, nanoTime)
        }
    }
}
