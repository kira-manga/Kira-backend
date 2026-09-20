package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteWork
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.OrdinaryJournalRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.security.EncodedTestOwnerDeleteEnvelopeV1
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

/** Separate TEST family. Only released SQL work can enable PUT; recovery comparison can only LIST/GET. */
internal class TestOwnerDeleteS3BindingV1 private constructor(
    val routing: TestOwnerDeleteJournalRoutingV1,
    val event: TestOwnerDeleteJournalEventV1,
    val attempt: TestOwnerDeleteCodecAttemptV1,
    private val store: JdbcComplaintOwnerDeleteStore?,
    private val work: CommittedTestOwnerDeleteWork.Prepared?,
    private val allStore: JdbcComplaintOwnerDeleteAllStore? = null,
    private val allWork: CommittedOwnerDeleteAllWork.Prepared? = null,
) {
    fun requirePublicationStart() {
        requireConnectionFree()
        requireJournalPublication(event.belongsTo(routing))
        attempt.requireOwner(routing)
        if (work != null) requireJournalPublication(checkNotNull(store).preparedEvent(work) === event)
        if (allWork != null) {
            val original = checkNotNull(allStore).testPreparedEvent(allWork)
            requireJournalPublication(original.belongsTo(routing) && original.route == event.route && original.canonicalBytes().contentEquals(event.canonicalBytes()))
        }
    }
    fun requirePut() { requirePublicationStart(); requireJournalPublication((work != null && store != null) xor (allWork != null && allStore != null)) }
    companion object {
        internal fun released(store: JdbcComplaintOwnerDeleteAllStore, work: CommittedOwnerDeleteAllWork.Prepared,
            routing: TestOwnerDeleteJournalRoutingV1, attempt: TestOwnerDeleteCodecAttemptV1): TestOwnerDeleteS3BindingV1 {
            requireConnectionFree()
            return TestOwnerDeleteS3BindingV1(routing, store.testPreparedEvent(work), attempt, null, null, store, work).also { it.requirePut() }
        }
        fun released(store: JdbcComplaintOwnerDeleteStore, work: CommittedTestOwnerDeleteWork.Prepared, routing: TestOwnerDeleteJournalRoutingV1, attempt: TestOwnerDeleteCodecAttemptV1): TestOwnerDeleteS3BindingV1 {
            requireConnectionFree()
            return TestOwnerDeleteS3BindingV1(routing, store.preparedEvent(work), attempt, store, work).also { it.requirePut() }
        }
        fun readOnly(event: TestOwnerDeleteJournalEventV1, routing: TestOwnerDeleteJournalRoutingV1, attempt: TestOwnerDeleteCodecAttemptV1): TestOwnerDeleteS3BindingV1 {
            requireConnectionFree()
            return TestOwnerDeleteS3BindingV1(routing, event, attempt, null, null).also { it.requirePublicationStart() }
        }
    }
}

internal class TestOwnerDeleteS3CallV1 private constructor(
    val binding: TestOwnerDeleteS3BindingV1,
    val operation: JournalS3OperationV1,
    val versionId: String?,
    val candidate: TestOwnerDeleteS3CandidateV1?,
    private val nanoTime: () -> Long,
) {
    val routing get() = binding.routing
    val declaration = routing.journalConfiguration.declaration()
    val objectKey get() = binding.event.route.objectKey
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
        fun list(binding: TestOwnerDeleteS3BindingV1, nanoTime: () -> Long) = TestOwnerDeleteS3CallV1(binding, JournalS3OperationV1.LIST, null, null, nanoTime)
        fun get(binding: TestOwnerDeleteS3BindingV1, versionId: String, nanoTime: () -> Long) = TestOwnerDeleteS3CallV1(binding, JournalS3OperationV1.GET, requireJournalVersion(versionId), null, nanoTime)
        fun put(binding: TestOwnerDeleteS3BindingV1, candidate: TestOwnerDeleteS3CandidateV1, nanoTime: () -> Long): TestOwnerDeleteS3CallV1 {
            binding.requirePut()
            requireJournalPublication(candidate.event === binding.event)
            return TestOwnerDeleteS3CallV1(binding, JournalS3OperationV1.PUT, null, candidate, nanoTime)
        }
    }
}

/** Does NOT implement the existing LIVE-only JournalS3PutV1. */
internal class TestOwnerDeleteS3CandidateV1 private constructor(val event: TestOwnerDeleteJournalEventV1, private val wire: ByteArray, val retainUntil: Instant) : AutoCloseable {
    val wireSha256 = Sha256.hex(wire)
    val checksum: String = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(wireSha256))
    val size get() = wire.size
    private var closed = false
    fun bytes(): ByteArray { requireJournalPublication(!closed); return wire.copyOf() }
    fun metadata(): Map<String, String> = journalMetadata(event.route.eventId, wireSha256, retainUntil.toString())
    override fun close() { closed = true; wire.fill(0) }
    companion object {
        fun sealed(binding: TestOwnerDeleteS3BindingV1, envelope: EncodedTestOwnerDeleteEnvelopeV1, retainUntil: Instant): TestOwnerDeleteS3CandidateV1 {
            binding.requirePut()
            requireJournalPublication(envelope.route == binding.event.route && envelope.semanticSha256 == binding.event.semanticSha256)
            OrdinaryJournalRetentionV1.canonicalInstant(retainUntil.toString())
            val wire = envelope.wireBytes()
            return try {
                requireJournalPublication(wire.size in 1..binding.routing.journalConfiguration.declaration().limits.decoder.maximumEnvelopeBytes)
                TestOwnerDeleteS3CandidateV1(binding.event, wire.copyOf(), retainUntil)
            } finally { wire.fill(0) }
        }
    }
}
