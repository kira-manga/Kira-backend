package me.manga.kira.backend.sourceconfig.domain

import java.time.Instant
import java.util.UUID

/** Durable initial-publication admission, independent of today's catalog or reference policy. */
enum class InitialSourceCatalogPhase(val wire: String) {
    PENDING("pending"),
    COMPLETE("complete"),
    RECONCILIATION_REQUIRED("reconciliation_required"),
    ;

    companion object {
        fun fromWire(value: String): InitialSourceCatalogPhase = entries.firstOrNull { it.wire == value } ?: error("source-catalog bootstrap phase is invalid")
    }
}

/** Immutable origin evidence. Later publications must not replace any of these fields. */
data class InitialSourceCatalogReceipt(
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
    init {
        check(policyId.isNotBlank() && policyId.length <= 128) { "source-catalog bootstrap policy identity is invalid" }
        check(listOf(referenceSha256, payloadSha256, documentChecksum, catalogChecksum).all(SHA256::matches)) {
            "source-catalog bootstrap receipt checksum is invalid"
        }
        check(documentRevision > 0 && documentRevision == catalogRevision) {
            "source-catalog bootstrap receipt revisions are inconsistent"
        }
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

data class InitialSourceCatalogState(val phase: InitialSourceCatalogPhase, val latestDocumentRevision: Long?, val receipt: InitialSourceCatalogReceipt?) {
    init {
        when (phase) {
            InitialSourceCatalogPhase.PENDING ->
                check(latestDocumentRevision == null && receipt == null) {
                    "pending source-catalog bootstrap state is inconsistent"
                }

            InitialSourceCatalogPhase.COMPLETE ->
                check(
                    receipt != null && latestDocumentRevision != null && latestDocumentRevision >= receipt.documentRevision,
                ) { "completed source-catalog bootstrap state is inconsistent" }

            InitialSourceCatalogPhase.RECONCILIATION_REQUIRED ->
                check(receipt == null) {
                    "unreconciled source-catalog bootstrap state has an unexpected receipt"
                }
        }
    }
}
