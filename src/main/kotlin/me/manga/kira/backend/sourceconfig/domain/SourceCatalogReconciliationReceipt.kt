package me.manga.kira.backend.sourceconfig.domain

import java.time.Instant
import java.util.UUID

/** New approval of retained signed history, never a claim about the original raw bootstrap input. */
data class SourceCatalogReconciliationReceipt(
    val policyId: String,
    val requestSha256: String,
    val anchorRevision: Long,
    val anchorDocumentChecksum: String,
    val anchorCatalogChecksum: String,
    val admittedRevision: Long,
    val documentChecksum: String,
    val catalogChecksum: String,
    val approvedAt: Instant,
    val actorId: UUID,
) {
    init {
        check(policyId.isNotBlank() && policyId.length <= 128) { "reconciliation policy identity is invalid" }
        check(listOf(requestSha256, anchorDocumentChecksum, anchorCatalogChecksum, documentChecksum, catalogChecksum).all(HASH::matches)) {
            "reconciliation receipt checksum is invalid"
        }
        check(anchorRevision > 0 && admittedRevision >= anchorRevision) { "reconciliation receipt revisions are inconsistent" }
    }

    private companion object {
        val HASH = Regex("[0-9a-f]{64}")
    }
}
