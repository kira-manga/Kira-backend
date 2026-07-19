package me.manga.kira.backend.sourceconfig.signing

import java.time.Instant

data class DocumentSignature(
    val format: String,
    val algorithm: String,
    val keyId: String,
    val signatureBase64: String,
    val previousRevision: Long?,
    val previousChecksum: String?,
)

data class DocumentSigningInput(
    val revision: Long,
    val checksum: String,
    val createdAt: Instant,
    val previousRevision: Long?,
    val previousChecksum: String?,
    val documentJson: String,
)
