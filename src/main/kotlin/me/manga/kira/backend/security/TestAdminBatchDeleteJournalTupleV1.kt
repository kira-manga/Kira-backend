package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import java.util.Base64
import java.util.Collections
import java.util.UUID

/** Authenticated payload comparisons only; never request/grant/epoch/publication authority. */
internal class TestAdminBatchDeleteJournalTupleV1(
    override val epoch: Long, override val actorId: UUID, override val operationKey: UUID,
    fingerprint: ByteArray, override val scope: ComplaintDataScope, override val consumedGrantId: UUID,
    owners: List<UUID>,
) : TestAdminErasureJournalTupleV1 {
    private val digest = fingerprint.copyOf()
    private val ids = Collections.unmodifiableList(owners.toList())
    override val credentialVersion: Long? get() = null
    override val actorKind: ComplaintJournalActorKindV1 get() = ComplaintJournalActorKindV1.ADMIN
    override val eventKind: ComplaintJournalDeletionKindV1 get() = ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE
    init {
        require(epoch > 0 && scope.testOnly && digest.size == 32 && actorId != UUID(0, 0))
        ComplaintIdentifiers.idempotencyKey(operationKey.toString())
        ComplaintIdentifiers.idempotencyKey(consumedGrantId.toString())
        require(ids.size in 1..50 && ids.distinct().size == ids.size && ids == ids.sortedBy(UUID::toString))
        ids.forEach { ComplaintIdentifiers.installationId(it.toString()) }
    }
    override fun fingerprintBytes(): ByteArray = digest.copyOf()
    override fun encodedFingerprint(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    override fun ownerInstallationIds(): List<UUID> = ids
    override fun toString(): String = "TestAdminBatchDeleteJournalTupleV1(redacted,no-authority)"
}
