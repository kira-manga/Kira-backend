package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.OrdinaryJournalRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationCall
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.security.EpochSealAttemptV1
import me.manga.kira.backend.security.EpochSealCanonicalV1
import me.manga.kira.backend.security.EpochSealContentV1
import me.manga.kira.backend.security.EpochSealEnvelopeV1
import me.manga.kira.backend.security.EpochSealWireV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import me.manga.kira.backend.security.withEpochSealBuffers
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checked same-J encoded input and one immutable in-memory candidate only. No durable wire-ready
 * intent, current campaign, STS provenance, LIVE restore-horizon authority or publication permission.
 */
internal class EpochSealS3BindingV1 private constructor(
    val routing: VersionBoundComplaintJournalRouting,
    val content: EpochSealContentV1,
    val attempt: EpochSealAttemptV1,
    internal val candidate: EpochSealS3CandidateV1,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    internal fun check() {
        requireConnectionFree()
        requireJournalPublication(!closed.get())
        attempt.requireOwner(routing)
        attempt.remainingMillis(1)
        candidate.requireOpen()
    }

    override fun close() {
        closed.set(true)
        candidate.close()
    }

    override fun toString(): String = "EpochSealS3BindingV1(encoded-in-memory-only,redacted,no-publication-authority)"

    companion object {
        fun encoded(
            routing: VersionBoundComplaintJournalRouting,
            content: EpochSealContentV1,
            envelope: EpochSealEnvelopeV1,
            retainUntil: Instant,
            attempt: EpochSealAttemptV1,
        ): EpochSealS3BindingV1 = journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
            requireConnectionFree()
            attempt.requireOwner(routing)
            attempt.remainingMillis(1)
            requireJournalPublication(content.belongsTo(routing) && envelope.content === content)
            withEpochSealBuffers { buffers -> EpochSealCanonicalV1(routing).checkedContent(content, buffers) }
            OrdinaryJournalRetentionV1.canonicalInstant(retainUntil.toString())
            val candidate = EpochSealS3CandidateV1.encoded(routing, envelope, retainUntil)
            try {
                EpochSealS3BindingV1(routing, content, attempt, candidate).also { it.check() }
            } catch (failure: Throwable) {
                candidate.close()
                throw failure
            }
        }
    }
}

/** Byte copies and checksum/metadata belong to this candidate; closing it cannot revoke an AWS request. */
internal class EpochSealS3CandidateV1 private constructor(
    private val content: EpochSealContentV1,
    private val wire: ByteArray,
    override val retainUntil: Instant,
) : JournalS3PutV1 {
    override val wireSha256: String = Sha256.hex(wire)
    override val checksum: String = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(wireSha256))
    override val size: Int get() = wire.size
    private val closed = AtomicBoolean()

    internal fun requireOpen() = requireJournalPublication(!closed.get(), JournalPublicationFailureV1.INVALID_PUT)

    @Synchronized
    override fun bytes(): ByteArray {
        requireOpen()
        return wire.copyOf()
    }

    override fun metadata(): Map<String, String> {
        requireOpen()
        return journalMetadata(content.route.sealId, wireSha256, retainUntil.toString())
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        wire.fill(0)
    }

    override fun toString(): String = "EpochSealS3CandidateV1(immutable-in-memory-only,redacted)"

    companion object {
        internal fun encoded(
            routing: VersionBoundComplaintJournalRouting,
            envelope: EpochSealEnvelopeV1,
            retainUntil: Instant,
        ): EpochSealS3CandidateV1 {
            val wire = envelope.wireBytes()
            var transferred = false
            try {
                requireJournalPublication(
                    wire.size in EpochSealWireV1.OUTER_BYTES..routing.journalConfiguration.declaration().limits.decoder.maximumEnvelopeBytes,
                    JournalPublicationFailureV1.LIMIT_EXCEEDED,
                )
                requireJournalPublication(Sha256.hex(wire) == envelope.wireSha256, JournalPublicationFailureV1.CHECKSUM_MISMATCH)
                return EpochSealS3CandidateV1(envelope.content, wire, retainUntil).also { transferred = true }
            } finally {
                if (!transferred) wire.fill(0)
            }
        }
    }
}

/** One S3 subcall of the original seal attempt; there is no ordinary-publication or renewed total allowance. */
internal class EpochSealS3CallV1 private constructor(
    val binding: EpochSealS3BindingV1,
    override val operation: JournalS3OperationV1,
    override val versionId: String?,
    override val candidate: EpochSealS3CandidateV1?,
    private val nanoTime: () -> Long,
) : JournalS3RequestV1 {
    override val routing: VersionBoundComplaintJournalRouting get() = binding.routing
    override val declaration = routing.journalConfiguration.declaration()
    override val objectKey: String get() = binding.content.route.objectKey
    private val started = nanoTime()
    private val allowance = binding.attempt.remainingMillis(declaration.limits.deadlines.s3CallMillis) * 1_000_000L
    private var lastElapsed = 0L
    private var expired = false

    @Synchronized
    override fun remainingMillis(): Int {
        binding.check()
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

    override fun check() {
        remainingMillis()
    }

    override fun toString(): String = "EpochSealS3CallV1(original-seal-attempt,redacted,no-publication-authority)"

    companion object {
        fun list(binding: EpochSealS3BindingV1, nanoTime: () -> Long): EpochSealS3CallV1 {
            binding.check()
            return EpochSealS3CallV1(binding, JournalS3OperationV1.LIST, null, null, nanoTime)
        }

        fun put(binding: EpochSealS3BindingV1, nanoTime: () -> Long): EpochSealS3CallV1 {
            binding.check()
            return EpochSealS3CallV1(binding, JournalS3OperationV1.PUT, null, binding.candidate, nanoTime)
        }

        fun get(binding: EpochSealS3BindingV1, versionId: String, nanoTime: () -> Long): EpochSealS3CallV1 {
            binding.check()
            requireJournalVersion(versionId)
            return EpochSealS3CallV1(binding, JournalS3OperationV1.GET, versionId, null, nanoTime)
        }
    }
}
