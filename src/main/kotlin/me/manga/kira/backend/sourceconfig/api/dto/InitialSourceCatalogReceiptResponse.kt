package me.manga.kira.backend.sourceconfig.api.dto

import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogReceipt
import java.time.Instant
import java.util.UUID

/** The immutable origin result, not today's evolved catalog or the replaying admin's identity. */
data class InitialSourceCatalogReceiptResponse(
    val policyId: String,
    val referenceSha256: String,
    val payloadSha256: String,
    val documentRevision: Long,
    val documentChecksum: String,
    val catalogRevision: Long,
    val catalogChecksum: String,
    val completedAt: Instant,
    val actorId: UUID,
) {
    companion object {
        fun of(receipt: InitialSourceCatalogReceipt) = InitialSourceCatalogReceiptResponse(
            policyId = receipt.policyId,
            referenceSha256 = receipt.referenceSha256,
            payloadSha256 = receipt.payloadSha256,
            documentRevision = receipt.documentRevision,
            documentChecksum = receipt.documentChecksum,
            catalogRevision = receipt.catalogRevision,
            catalogChecksum = receipt.catalogChecksum,
            completedAt = receipt.completedAt,
            actorId = receipt.actorId,
        )
    }
}
