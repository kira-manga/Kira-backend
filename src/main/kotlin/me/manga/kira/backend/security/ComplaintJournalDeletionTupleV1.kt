package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import java.util.Base64
import java.util.UUID

internal enum class ComplaintJournalActorKindV1 { INSTALLATION, ADMIN }

/** This routing profile deliberately excludes retention, retirement, seals, manifests and TEST events. */
internal enum class ComplaintJournalDeletionKindV1(val actorKind: ComplaintJournalActorKindV1) {
    OWNER_DELETE(ComplaintJournalActorKindV1.INSTALLATION),
    OWNER_DELETE_ALL(ComplaintJournalActorKindV1.INSTALLATION),
    ADMIN_DELETE(ComplaintJournalActorKindV1.ADMIN),
    ADMIN_BATCH_DELETE(ComplaintJournalActorKindV1.ADMIN),
}

/**
 * Bounded routing inputs, not an authenticated actor, database-assigned epoch or normalized-request proof.
 * No event payload, target snapshot, policy operation-key producer or publication authority is represented.
 */
internal class ComplaintJournalDeletionTupleV1(
    val epoch: Long,
    val eventKind: ComplaintJournalDeletionKindV1,
    val actorKind: ComplaintJournalActorKindV1,
    val actorId: UUID,
    val credentialVersion: Long?,
    val operationKey: UUID,
    fingerprint: ByteArray,
    val scope: ComplaintDataScope,
) {
    private val storedFingerprint: ByteArray

    init {
        require(epoch > 0 && scope == ComplaintDataScope.LIVE && actorKind == eventKind.actorKind) { INVALID_TUPLE }
        require(operationKey.version() == 4 && operationKey.variant() == 2 && fingerprint.size == 32) { INVALID_TUPLE }
        when (actorKind) {
            ComplaintJournalActorKindV1.INSTALLATION -> require(
                actorId.version() == 4 && actorId.variant() == 2 && credentialVersion != null && credentialVersion > 0,
            ) { INVALID_TUPLE }
            ComplaintJournalActorKindV1.ADMIN -> require(credentialVersion == null) { INVALID_TUPLE }
        }
        storedFingerprint = fingerprint.copyOf()
    }

    internal fun encodedFingerprint(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(storedFingerprint)

    override fun toString(): String = "ComplaintJournalDeletionTupleV1(redacted,no-authority)"

    private companion object {
        const val INVALID_TUPLE = "Invalid complaint journal deletion tuple"
    }
}
