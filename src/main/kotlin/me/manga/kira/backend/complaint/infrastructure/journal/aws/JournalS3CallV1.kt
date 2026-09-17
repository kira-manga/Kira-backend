package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.catalog.ReleasedCutoffPublicationV1
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.OrdinaryJournalRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.security.EncodedOwnerDeleteAllEnvelopeV1
import me.manga.kira.backend.security.JournalCodecAttemptV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalEventV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

/** A request can be bound only by the actual post-commit/post-release producer, never a codec event or flag. */
internal class JournalS3BindingV1 private constructor(
    val routing: VersionBoundComplaintJournalRouting,
    val event: OwnerDeleteAllJournalEventV1,
    val attempt: JournalCodecAttemptV1,
    private val receiptless: ReleasedCutoffPublicationV1? = null,
) {
    internal fun requirePublicationStart() {
        receiptless?.let {
            check(it.requireEvent(routing) === event)
            it.requireAttempt(attempt)
        }
    }

    companion object {
        internal fun receiptless(
            work: ReleasedCutoffPublicationV1,
            routing: VersionBoundComplaintJournalRouting,
            attempt: JournalCodecAttemptV1,
        ): JournalS3BindingV1 {
            requireConnectionFree()
            val event = work.requireEvent(routing)
            work.requireAttempt(attempt)
            attempt.requireOwner(routing)
            attempt.remainingMillis(routing.journalConfiguration.declaration().limits.deadlines.s3CallMillis)
            return JournalS3BindingV1(routing, event, attempt, work)
        }

        fun released(
            store: JdbcComplaintOwnerDeleteAllStore,
            work: CommittedOwnerDeleteAllWork.Prepared,
            routing: VersionBoundComplaintJournalRouting,
            attempt: JournalCodecAttemptV1,
        ): JournalS3BindingV1 {
            requireConnectionFree()
            val event = store.preparedEvent(work)
            requireJournalPublication(event.belongsTo(routing))
            attempt.requireOwner(routing)
            attempt.remainingMillis(routing.journalConfiguration.declaration().limits.deadlines.s3CallMillis)
            return JournalS3BindingV1(routing, event, attempt)
        }
    }

    override fun toString(): String = "JournalS3BindingV1(released-custody,redacted,no-runtime-authority)"
}

internal enum class JournalS3OperationV1 { LIST, PUT, GET }

/** The S3 subcall ceiling shrinks inside the SAME codec attempt; it never supplies a fresh total allowance. */
internal class JournalS3CallV1 private constructor(
    val binding: JournalS3BindingV1,
    val operation: JournalS3OperationV1,
    val versionId: String?,
    val candidate: JournalS3CandidateV1?,
    private val nanoTime: () -> Long,
) {
    val declaration = binding.routing.journalConfiguration.declaration()
    private val started = nanoTime()
    private val allowance = binding.attempt.remainingMillis(declaration.limits.deadlines.s3CallMillis) * 1_000_000L
    private var lastElapsed = 0L
    private var expired = false

    @Synchronized
    fun remainingMillis(): Int {
        requireConnectionFree()
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        binding.requirePublicationStart()
        binding.attempt.requireOwner(binding.routing)
        val total = binding.attempt.remainingMillis(declaration.limits.deadlines.s3CallMillis)
        val elapsed = nanoTime() - started
        val remaining = (allowance - elapsed) / 1_000_000L
        val clockRegressed = elapsed < 0 || elapsed < lastElapsed
        if (expired || clockRegressed || remaining <= 0) {
            expired = true
            requireJournalPublication(false, JournalPublicationFailureV1.DEADLINE_EXHAUSTED)
        }
        lastElapsed = elapsed
        return minOf(total.toLong(), remaining).toInt()
    }

    fun check() {
        remainingMillis()
    }

    override fun toString(): String = "JournalS3CallV1(one-shrinking-attempt,redacted)"

    companion object {
        fun list(binding: JournalS3BindingV1, nanoTime: () -> Long): JournalS3CallV1 = JournalS3CallV1(binding, JournalS3OperationV1.LIST, null, null, nanoTime)

        fun get(binding: JournalS3BindingV1, versionId: String, nanoTime: () -> Long): JournalS3CallV1 {
            requireJournalVersion(versionId)
            return JournalS3CallV1(binding, JournalS3OperationV1.GET, versionId, null, nanoTime)
        }

        fun put(binding: JournalS3BindingV1, candidate: JournalS3CandidateV1, nanoTime: () -> Long): JournalS3CallV1 {
            requireJournalPublication(candidate.event === binding.event, JournalPublicationFailureV1.INVALID_PUT)
            return JournalS3CallV1(binding, JournalS3OperationV1.PUT, null, candidate, nanoTime)
        }
    }
}

/** One in-memory randomized candidate, reused byte-for-byte (including retention metadata) for the sole optional retry. */
internal class JournalS3CandidateV1 private constructor(val event: OwnerDeleteAllJournalEventV1, private val wire: ByteArray, val retainUntil: Instant) :
    AutoCloseable {
    val wireSha256: String = Sha256.hex(wire)
    val checksum: String = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(wireSha256))
    val size: Int get() = wire.size
    private var closed = false

    fun bytes(): ByteArray {
        requireJournalPublication(!closed, JournalPublicationFailureV1.INVALID_PUT)
        return wire.copyOf()
    }

    fun metadata(): Map<String, String> = journalMetadata(event.route.eventId, wireSha256, retainUntil.toString())

    override fun close() {
        closed = true
        wire.fill(0)
    }

    override fun toString(): String = "JournalS3CandidateV1(randomized-candidate-only,redacted)"

    companion object {
        fun sealed(binding: JournalS3BindingV1, envelope: EncodedOwnerDeleteAllEnvelopeV1, retainUntil: Instant): JournalS3CandidateV1 {
            requireJournalPublication(envelope.route == binding.event.route && envelope.semanticSha256 == binding.event.semanticSha256)
            OrdinaryJournalRetentionV1.canonicalInstant(retainUntil.toString())
            val wire = envelope.wireBytes()
            return try {
                requireJournalPublication(
                    wire.size in 1..binding.routing.journalConfiguration.declaration().limits.decoder.maximumEnvelopeBytes,
                    JournalPublicationFailureV1.LIMIT_EXCEEDED,
                )
                JournalS3CandidateV1(binding.event, wire.copyOf(), retainUntil)
            } finally {
                wire.fill(0)
            }
        }
    }
}

internal fun journalMetadata(eventId: String, wireSha256: String, retainUntil: String): Map<String, String> = mapOf(
    "kira-journal-schema" to "1",
    "kira-journal-event-id" to eventId,
    "kira-journal-ciphertext-sha256" to wireSha256,
    "kira-journal-retain-until" to retainUntil,
)

/** Version IDs are opaque: in particular do not URL-decode percent escapes or turn plus into space. */
internal fun requireJournalVersion(value: String?): String {
    requireJournalPublication(
        value != null && value.length in 1..1024 && value != "null" && value.none { it < ' ' || it == '\u007f' },
        JournalPublicationFailureV1.INVALID_READBACK,
    )
    val selected = checkNotNull(value)
    val encoded = selected.toByteArray(Charsets.UTF_8)
    requireJournalPublication(encoded.size <= 1024 && encoded.toString(Charsets.UTF_8) == selected, JournalPublicationFailureV1.INVALID_READBACK)
    return selected
}
