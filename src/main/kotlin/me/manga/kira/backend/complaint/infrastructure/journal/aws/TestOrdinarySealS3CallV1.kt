package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealCustodyV1

/** Only the concrete original post-freeze/release custody can supply this binding. No row-shaped PUT capability. */
internal class TestOrdinarySealS3BindingV1 private constructor(internal val custody: TestOrdinarySealCustodyV1) {
    val routing get() = custody.routing
    val attempt get() = custody.attempt
    val frozen get() = custody.frozen()
    fun requirePublicationStart() { requireConnectionFree(); custody.requirePublication() }
    fun requirePut() { requirePublicationStart(); custody.requirePutRetention() }
    fun putRetention() = custody.putRetention()
    companion object {
        internal fun released(custody: TestOrdinarySealCustodyV1): TestOrdinarySealS3BindingV1 =
            TestOrdinarySealS3BindingV1(custody).also { it.requirePublicationStart() }
    }
}

internal class TestOrdinarySealS3CallV1 private constructor(
    val binding: TestOrdinarySealS3BindingV1,
    val operation: JournalS3OperationV1,
    val versionId: String?,
    val candidate: TestOrdinarySealS3CandidateV1?,
    private val nanoTime: () -> Long,
) {
    val routing get() = binding.routing
    val declaration = routing.journalConfiguration.declaration()
    val objectKey get() = binding.frozen.binding.objectKey
    private val started = nanoTime()
    private val allowance = binding.attempt.remainingProviderMillis(declaration.limits.deadlines.s3CallMillis) * 1_000_000L
    private var lastElapsed = 0L
    private var expired = false
    @Synchronized fun remainingMillis(): Int {
        requireConnectionFree()
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        binding.requirePublicationStart()
        val total = binding.attempt.remainingProviderMillis(declaration.limits.deadlines.s3CallMillis)
        val elapsed = nanoTime() - started
        val remaining = (allowance - elapsed) / 1_000_000L
        if (expired || elapsed < 0 || elapsed < lastElapsed || remaining <= 0) {
            expired = true
            requireJournalPublication(false, JournalPublicationFailureV1.DEADLINE_EXHAUSTED)
        }
        lastElapsed = elapsed
        return minOf(total.toLong(), remaining).toInt()
    }
    fun check() { remainingMillis() }
    companion object {
        fun list(binding: TestOrdinarySealS3BindingV1, nanoTime: () -> Long) = TestOrdinarySealS3CallV1(binding, JournalS3OperationV1.LIST, null, null, nanoTime)
        fun get(binding: TestOrdinarySealS3BindingV1, versionId: String, nanoTime: () -> Long) =
            TestOrdinarySealS3CallV1(binding, JournalS3OperationV1.GET, requireJournalVersion(versionId), null, nanoTime)
        fun put(binding: TestOrdinarySealS3BindingV1, candidate: TestOrdinarySealS3CandidateV1, nanoTime: () -> Long): TestOrdinarySealS3CallV1 {
            binding.requirePut()
            candidate.requireBinding(binding)
            return TestOrdinarySealS3CallV1(binding, JournalS3OperationV1.PUT, null, candidate, nanoTime)
        }
    }
}

/** Borrowed immutable winner, never an encryption candidate. Every access rechecks original dispatch custody. */
internal class TestOrdinarySealS3CandidateV1 private constructor(private val binding: TestOrdinarySealS3BindingV1) {
    internal val row: TestTerminalDurableRowV1 = binding.frozen
    val wireSha256 get() = checkNotNull(row.wireSha256)
    val checksum get() = checkNotNull(row.checksumSha256)
    val retainUntil = binding.putRetention()
    val size: Int get() = bytes().let { try { it.size } finally { it.fill(0) } }
    fun bytes(): ByteArray { binding.requirePublicationStart(); return checkNotNull(row.wireBytes()) }
    fun metadata(): Map<String, String> { binding.requirePublicationStart(); return checkNotNull(row.metadata()) }
    internal fun requireBinding(selected: TestOrdinarySealS3BindingV1) { requireJournalPublication(binding === selected && row === selected.frozen) }
    companion object {
        fun frozen(binding: TestOrdinarySealS3BindingV1): TestOrdinarySealS3CandidateV1 {
            binding.requirePut()
            requireJournalPublication(binding.frozen.state === TestTerminalDurableStateV1.WIRE_FROZEN)
            return TestOrdinarySealS3CandidateV1(binding)
        }
    }
}
