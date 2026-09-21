package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import java.util.Base64
import java.util.UUID

/** Closed comparison union only. Neither branch is authentication, a database epoch, or publication authority. */
internal sealed interface TestDeletionJournalTupleV1 {
    val epoch: Long
    val actorId: UUID
    val actorKind: ComplaintJournalActorKindV1
    val credentialVersion: Long?
    val operationKey: UUID
    val scope: ComplaintDataScope
    val eventKind: ComplaintJournalDeletionKindV1
    fun fingerprintBytes(): ByteArray
    fun encodedFingerprint(): String
}

/** Only immutable Admin erasure comparisons. Single-owner callers retain their exact scalar type. */
internal sealed interface TestAdminErasureJournalTupleV1 : TestDeletionJournalTupleV1 {
    val consumedGrantId: UUID
    fun ownerInstallationIds(): List<UUID>
}

/** ADMIN and resolved owner are deliberately distinct. The grant is journal history, never a proof token. */
internal class TestAdminDeleteJournalTupleV1(
    override val epoch: Long,
    override val actorId: UUID,
    override val operationKey: UUID,
    fingerprint: ByteArray,
    override val scope: ComplaintDataScope,
    override val consumedGrantId: UUID,
    val ownerInstallationId: UUID,
) : TestAdminErasureJournalTupleV1 {
    private val digest = fingerprint.copyOf()
    override val credentialVersion: Long? get() = null
    override val actorKind: ComplaintJournalActorKindV1 get() = ComplaintJournalActorKindV1.ADMIN
    override val eventKind: ComplaintJournalDeletionKindV1 get() = ComplaintJournalDeletionKindV1.ADMIN_DELETE
    init {
        require(epoch > 0 && scope.testOnly && digest.size == 32)
        require(actorId != UUID(0, 0))
        ComplaintIdentifiers.idempotencyKey(operationKey.toString())
        ComplaintIdentifiers.idempotencyKey(consumedGrantId.toString())
        ComplaintIdentifiers.installationId(ownerInstallationId.toString())
    }
    override fun fingerprintBytes(): ByteArray = digest.copyOf()
    override fun ownerInstallationIds(): List<UUID> = listOf(ownerInstallationId)
    override fun encodedFingerprint(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    override fun toString(): String = "TestAdminDeleteJournalTupleV1(redacted,no-authority)"
}
