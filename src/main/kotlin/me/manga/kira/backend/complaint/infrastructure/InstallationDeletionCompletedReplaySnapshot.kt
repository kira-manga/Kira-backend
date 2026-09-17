package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationCodecV1
import me.manga.kira.backend.security.ComplaintJournalActorKindV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.ComplaintJournalDeletionTupleV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalCodecV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalJsonV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * Same-J authenticated, originally released read-only replay. This is not a new APPLY, provider
 * observation, HTTP status or current-mode/restore authority. Only the preflight's private producer
 * can implement it; the later consumer still owns full-D/current authority and HTTP gating.
 */
internal sealed interface BoundOwnerDeleteAllReplayV1 : OwnerDeleteAllOutcome {
    val completedAt: Instant
    val expiresAt: Instant
}

/**
 * Bounded comparison data from the original single MVCC statement. It cannot issue a replay or
 * substitute for the private committed/released preflight; only that owner's Completed retains it.
 * No ResultSet, connection, credential secret, routing replacement or mutable byte array escapes.
 */
internal class InstallationDeletionCompletedReplaySnapshot private constructor(
    private val publication: Publication,
    private val proof: Proof,
    private val times: Times,
) {
    val completedAt: Instant get() = times.completedAt
    val expiresAt: Instant get() = times.expiresAt

    fun requireBound(
        comparison: InstallationDeletionPreflightResult.Completed,
        routing: VersionBoundComplaintJournalRouting,
        codec: OwnerDeleteAllJournalCodecV1,
    ) {
        requireConnectionFree()
        check(comparison.installation.scope == ComplaintDataScope.LIVE)
        val declaration = routing.journalConfiguration.declaration()
        val payload = OwnerDeleteAllJournalJsonV1(declaration.limits.decoder).payload(publication.bytes)
        val targets = payload.complaintIds.map(ComplaintIdentifiers::resourceId)
        val tuple = ComplaintJournalDeletionTupleV1(
            publication.epoch,
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL,
            ComplaintJournalActorKindV1.INSTALLATION,
            comparison.installation.id,
            comparison.submittedCredentialVersion,
            comparison.operationKey,
            comparison.fingerprint.bytes(),
            comparison.installation.scope,
        )
        // Reconstruct exactly the submitted tuple and frozen IDs under the stored retained key.
        // canonicalize is local only; this uses the existing codec without opening its data-key port.
        val event = codec.canonicalize(tuple, targets, publication.routingKeyId)
        check(event.belongsTo(routing) && publication.writer.toString() == declaration.writer.generationId)
        check(comparison.publicationReference == publication.eventId && event.route.eventId == publication.eventId)
        check(event.route.routingKeyId == publication.routingKeyId && event.route.objectKey == publication.objectKey)
        check(publication.targetCount == targets.size.toLong())
        val canonical = event.canonicalBytes()
        try {
            check(publication.bytes.contentEquals(canonical))
        } finally {
            canonical.fill(0)
        }
        check(MessageDigest.isEqual(publication.hash, HexFormat.of().parseHex(event.semanticSha256)))
        check(MessageDigest.isEqual(proof.hash, HexFormat.of().parseHex(Sha256.hex(proof.bytes))))
        // Strict same-routing/J parsing authenticates every canonical proof field. It does not
        // refresh verifiedAt/retention, contact S3 or mint a new verified-publication capability.
        val record = OwnerDeleteAllVerificationCodecV1(routing).parse(proof.bytes, event)
        check(record.objectVersion == proof.objectVersion)
        check(MessageDigest.isEqual(proof.ciphertextHash, HexFormat.of().parseHex(record.ciphertextSha256)))
        check(Instant.parse(record.objectCreatedAt) == proof.objectCreatedAt)
        check(Instant.parse(record.retainUntil) == proof.retainUntil && Instant.parse(record.verifiedAt) == proof.verifiedAt)
        requireOriginalTimes()
        requireConnectionFree()
    }

    private fun requireOriginalTimes() {
        check(publication.createdAt == times.authorizedAt && !publication.createdAt.isAfter(proof.verifiedAt))
        check(!proof.verifiedAt.isAfter(completedAt) && proof.retainUntil.isAfter(completedAt))
        check(publication.appliedAt == completedAt && times.appliedAt == completedAt)
        check(times.identityTerminalAt == completedAt && times.credentialDeletedAt == completedAt)
        check(times.credentialExpiresAt == expiresAt && expiresAt == completedAt.plus(RETRY_RETENTION))
        check(!completedAt.isAfter(times.observedAt) && times.observedAt.isBefore(expiresAt))
    }

    override fun toString(): String = "InstallationDeletionCompletedReplaySnapshot(redacted,comparison-only)"

    private class Publication(
        val eventId: String,
        val writer: UUID,
        val epoch: Long,
        val targetCount: Long,
        val routingKeyId: String,
        val objectKey: String,
        val bytes: ByteArray,
        val hash: ByteArray,
        val createdAt: Instant,
        val appliedAt: Instant,
    )

    private class Proof(
        val bytes: ByteArray,
        val hash: ByteArray,
        val objectVersion: String,
        val ciphertextHash: ByteArray,
        val objectCreatedAt: Instant,
        val retainUntil: Instant,
        val verifiedAt: Instant,
    )

    private class Times(
        val authorizedAt: Instant,
        val completedAt: Instant,
        val expiresAt: Instant,
        val appliedAt: Instant,
        val identityTerminalAt: Instant,
        val credentialDeletedAt: Instant?,
        val credentialExpiresAt: Instant?,
        val observedAt: Instant,
    )

    companion object {
        private val RETRY_RETENTION = Duration.ofHours(192)

        /** Called only after the existing receipt/publication/applied structural comparisons. */
        fun read(row: ResultSet): InstallationDeletionCompletedReplaySnapshot = InstallationDeletionCompletedReplaySnapshot(
            Publication(
                checkNotNull(row.getString("publication_id")),
                checkNotNull(row.getObject("publication_writer", UUID::class.java)),
                requiredLong(row, "publication_epoch"),
                requiredLong(row, "publication_target_count"),
                checkNotNull(row.getString("publication_routing_key_id")),
                checkNotNull(row.getString("publication_object_key")),
                bytes(row, "publication_event_bytes"),
                bytes(row, "publication_semantic_hash"),
                instant(row, "publication_created_at"),
                instant(row, "publication_applied_at"),
            ),
            Proof(
                bytes(row, "publication_verification_bytes"),
                bytes(row, "publication_verification_hash"),
                checkNotNull(row.getString("publication_object_version")),
                bytes(row, "publication_ciphertext_hash"),
                instant(row, "publication_object_created_at"),
                instant(row, "publication_retain_until"),
                instant(row, "publication_verified_at"),
            ),
            Times(
                instant(row, "receipt_authorized_at"),
                instant(row, "receipt_completed_at"),
                instant(row, "receipt_expires_at"),
                instant(row, "applied_at"),
                instant(row, "identity_terminal_at"),
                row.getTimestamp("credential_deleted_at")?.toInstant(),
                row.getTimestamp("verifier_expires_at")?.toInstant(),
                instant(row, "observed_at"),
            ),
        )

        private fun bytes(row: ResultSet, column: String): ByteArray = checkNotNull(row.getBytes(column)).copyOf()
        private fun instant(row: ResultSet, column: String): Instant = checkNotNull(row.getTimestamp(column)).toInstant()
        private fun requiredLong(row: ResultSet, column: String): Long = row.getLong(column).also { check(!row.wasNull()) }
    }
}
