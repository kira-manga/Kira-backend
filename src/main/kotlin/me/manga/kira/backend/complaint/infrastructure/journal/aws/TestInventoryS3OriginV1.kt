package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestTerminalInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestActiveQueueJournalReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestTerminalAttemptV1

/** Closed actual reader origins (queue is GET-only), not a caller-supplied prefix/callback or generic native stack. */
internal class TestInventoryS3OriginV1 private constructor(
    private val ordinary: TestOrdinaryInventoryReaderV1?,
    private val terminal: TestTerminalInventoryReaderV1?,
    private val queue: TestActiveQueueJournalReaderV1? = null,
) {
    val routing get() = ordinary?.routing ?: queue?.routing ?: checkNotNull(terminal).routing
    val prefix get() = ordinary?.ordinaryPrefix ?: queue?.ordinaryPrefix ?: checkNotNull(terminal).sealTerminalPrefix
    fun requireNativeRead() { if (ordinary != null) ordinary.requireNativeRead() else if (queue != null) queue.requireNativeRead() else checkNotNull(terminal).requireNativeRead() }
    fun remainingNativeMillis(ceiling: Int): Int = ordinary?.remainingNativeMillis(ceiling) ?: queue?.remainingNativeMillis(ceiling) ?: checkNotNull(terminal).remainingNativeMillis(ceiling)
    fun requireInventoryKey(key: String?): String = ordinary?.requireInventoryKey(key) ?: queue?.requireInventoryKey(key) ?: checkNotNull(terminal).requireInventoryKey(key)
    fun requireAttempt(attempt: TestOwnerDeleteCodecAttemptV1) {
        requireJournalPublication((ordinary != null || queue != null) && terminal == null)
        attempt.requireOwner(routing)
    }
    fun requireAttempt(attempt: TestTerminalAttemptV1) {
        requireJournalPublication(ordinary == null && queue == null && terminal != null)
        checkNotNull(terminal).requireCodecAttempt(attempt)
    }
    fun requireListing() { requireJournalPublication(queue == null) }
    companion object {
        fun queue(reader: TestActiveQueueJournalReaderV1): TestInventoryS3OriginV1 =
            TestInventoryS3OriginV1(null, null, reader).also { it.requireNativeRead() }
        fun ordinary(reader: TestOrdinaryInventoryReaderV1): TestInventoryS3OriginV1 =
            TestInventoryS3OriginV1(reader, null).also { it.requireNativeRead() }
        fun terminal(reader: TestTerminalInventoryReaderV1): TestInventoryS3OriginV1 =
            TestInventoryS3OriginV1(null, reader).also { it.requireNativeRead() }
    }
}
