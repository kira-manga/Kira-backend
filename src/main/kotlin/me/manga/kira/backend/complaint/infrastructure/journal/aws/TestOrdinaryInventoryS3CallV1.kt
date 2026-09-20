package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1

/**
 * Read-only native call of the original registered inventory reader. A key/version locator is not
 * an event, publication binding or authority to PUT. Neither LIVE nor seal callers use this family.
 */
internal class TestOrdinaryInventoryS3CallV1 private constructor(
    val reader: TestOrdinaryInventoryReaderV1,
    val operation: JournalS3OperationV1,
    val objectKey: String,
    val versionId: String?,
    val keyMarker: String?,
    val versionIdMarker: String?,
    private val attempt: TestOwnerDeleteCodecAttemptV1?,
    private val nanoTime: () -> Long,
) {
    val declaration = reader.routing.journalConfiguration.declaration()
    private val started = nanoTime()
    private val allowance = remainingParentMillis() * 1_000_000L
    private var lastElapsed = 0L
    private var expired = false

    @Synchronized
    fun remainingMillis(): Int {
        requireConnectionFree()
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val total = remainingParentMillis()
        val elapsed = nanoTime() - started
        val remaining = (allowance - elapsed) / 1_000_000L
        if (expired || elapsed < 0 || elapsed < lastElapsed || remaining <= 0) {
            expired = true
            requireJournalPublication(false, JournalPublicationFailureV1.DEADLINE_EXHAUSTED)
        }
        lastElapsed = elapsed
        return minOf(total.toLong(), remaining).toInt()
    }

    private fun remainingParentMillis(): Int {
        val remaining = reader.remainingNativeMillis(declaration.limits.deadlines.s3CallMillis)
        attempt?.requireOwner(reader.routing)
        return minOf(remaining, attempt?.remainingMillis(remaining) ?: remaining)
    }

    fun check() { remainingMillis() }

    override fun toString(): String = "TestOrdinaryInventoryS3CallV1(original-read-only,redacted)"

    companion object {
        fun list(
            reader: TestOrdinaryInventoryReaderV1,
            cursor: TestOrdinaryInventoryS3ClientV1.Cursor?,
            nanoTime: () -> Long,
        ): TestOrdinaryInventoryS3CallV1 {
            reader.requireNativeRead()
            cursor?.let {
                reader.requireInventoryKey(it.key)
                requireJournalVersion(it.version)
            }
            return TestOrdinaryInventoryS3CallV1(
                reader, JournalS3OperationV1.LIST, reader.ordinaryPrefix, null,
                cursor?.key, cursor?.version, null, nanoTime,
            )
        }

        fun get(
            reader: TestOrdinaryInventoryReaderV1,
            key: String,
            version: String,
            attempt: TestOwnerDeleteCodecAttemptV1,
            nanoTime: () -> Long,
        ): TestOrdinaryInventoryS3CallV1 {
            reader.requireNativeRead()
            attempt.requireOwner(reader.routing)
            return TestOrdinaryInventoryS3CallV1(
                reader, JournalS3OperationV1.GET, reader.requireInventoryKey(key),
                requireJournalVersion(version), null, null, attempt, nanoTime,
            )
        }
    }
}
