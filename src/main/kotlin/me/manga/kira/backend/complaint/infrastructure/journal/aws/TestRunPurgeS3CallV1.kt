package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeCustodyV1

/** Only the concrete original post-freeze/release custody can supply this binding. No row-shaped PUT capability. */
internal class TestRunPurgeS3BindingV1 private constructor(internal val custody: TestRunPurgeCustodyV1) {
    val routing get() = custody.routing
    val attempt get() = custody.attempt
    val frozen get() = custody.frozen()
    fun requirePublicationStart() { requireConnectionFree(); custody.requirePublication() }
    fun requirePut() { requirePublicationStart(); custody.requirePut() }
    fun putRetention() = custody.putRetention()
    companion object {
        internal fun released(custody: TestRunPurgeCustodyV1): TestRunPurgeS3BindingV1 =
            TestRunPurgeS3BindingV1(custody).also { it.requirePublicationStart() }
    }
}

internal class TestRunPurgeS3CallV1 private constructor(
    val binding: TestRunPurgeS3BindingV1,
    val operation: JournalS3OperationV1,
    val versionId: String?,
    val candidate: TestRunPurgeS3CandidateV1?,
    private val nanoTime: () -> Long,
) {
    val routing get() = binding.routing
    val declaration = routing.journalConfiguration.declaration()
    val objectKey get() = binding.frozen.binding.objectKey
    private val started = nanoTime()
    private val allowance = binding.attempt.remainingMillis(declaration.limits.deadlines.s3CallMillis) * 1_000_000L
    private var lastElapsed = 0L
    private var expired = false
    @Synchronized fun remainingMillis(): Int {
        requireConnectionFree()
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        binding.requirePublicationStart()
        val total = binding.attempt.remainingMillis(declaration.limits.deadlines.s3CallMillis)
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
        fun list(binding: TestRunPurgeS3BindingV1, nanoTime: () -> Long) = TestRunPurgeS3CallV1(binding, JournalS3OperationV1.LIST, null, null, nanoTime)
        fun get(binding: TestRunPurgeS3BindingV1, versionId: String, nanoTime: () -> Long) =
            TestRunPurgeS3CallV1(binding, JournalS3OperationV1.GET, requireJournalVersion(versionId), null, nanoTime)
        fun put(binding: TestRunPurgeS3BindingV1, candidate: TestRunPurgeS3CandidateV1, nanoTime: () -> Long): TestRunPurgeS3CallV1 {
            binding.requirePut()
            candidate.requireBinding(binding)
            return TestRunPurgeS3CallV1(binding, JournalS3OperationV1.PUT, null, candidate, nanoTime)
        }
    }
}

/** Borrowed immutable winner. Capture the custody-derived PUT lock once; byte access rechecks dispatch custody. */
internal class TestRunPurgeS3CandidateV1 private constructor(private val binding: TestRunPurgeS3BindingV1) {
    private val row: TestTerminalDurableRowV1 = binding.frozen
    val retainUntil = binding.putRetention()
    val wireSha256 get() = checkNotNull(row.wireSha256)
    val checksum get() = checkNotNull(row.checksumSha256)
    val size: Int get() = bytes().let { try { it.size } finally { it.fill(0) } }
    fun bytes(): ByteArray { binding.requirePublicationStart(); return checkNotNull(row.wireBytes()) }
    fun metadata(): Map<String, String> { binding.requirePublicationStart(); return checkNotNull(row.metadata()) }
    internal fun requireBinding(selected: TestRunPurgeS3BindingV1) {
        requireJournalPublication(binding === selected && row === selected.frozen)
    }
    companion object {
        fun frozen(binding: TestRunPurgeS3BindingV1): TestRunPurgeS3CandidateV1 {
            binding.requirePut()
            requireJournalPublication(binding.frozen.state === TestTerminalDurableStateV1.WIRE_FROZEN)
            return TestRunPurgeS3CandidateV1(binding)
        }
    }
}
