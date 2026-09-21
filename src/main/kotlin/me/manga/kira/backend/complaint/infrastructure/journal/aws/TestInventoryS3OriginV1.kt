package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestTerminalInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestTerminalAttemptV1

/** Two closed actual reader origins, not a caller-supplied prefix/callback or generic native stack. */
internal class TestInventoryS3OriginV1 private constructor(
    private val ordinary: TestOrdinaryInventoryReaderV1?,
    private val terminal: TestTerminalInventoryReaderV1?,
) {
    val routing get() = ordinary?.routing ?: checkNotNull(terminal).routing
    val prefix get() = ordinary?.ordinaryPrefix ?: checkNotNull(terminal).sealTerminalPrefix
    fun requireNativeRead() { if (ordinary != null) ordinary.requireNativeRead() else checkNotNull(terminal).requireNativeRead() }
    fun remainingNativeMillis(ceiling: Int): Int = ordinary?.remainingNativeMillis(ceiling) ?: checkNotNull(terminal).remainingNativeMillis(ceiling)
    fun requireInventoryKey(key: String?): String = ordinary?.requireInventoryKey(key) ?: checkNotNull(terminal).requireInventoryKey(key)
    fun requireAttempt(attempt: TestOwnerDeleteCodecAttemptV1) {
        requireJournalPublication(ordinary != null && terminal == null)
        attempt.requireOwner(routing)
    }
    fun requireAttempt(attempt: TestTerminalAttemptV1) {
        requireJournalPublication(ordinary == null && terminal != null)
        checkNotNull(terminal).requireCodecAttempt(attempt)
    }
    companion object {
        fun ordinary(reader: TestOrdinaryInventoryReaderV1): TestInventoryS3OriginV1 =
            TestInventoryS3OriginV1(reader, null).also { it.requireNativeRead() }
        fun terminal(reader: TestTerminalInventoryReaderV1): TestInventoryS3OriginV1 =
            TestInventoryS3OriginV1(null, reader).also { it.requireNativeRead() }
    }
}
