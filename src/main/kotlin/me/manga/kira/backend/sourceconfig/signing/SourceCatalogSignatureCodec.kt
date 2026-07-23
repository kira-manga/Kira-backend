package me.manga.kira.backend.sourceconfig.signing

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Domain-separated Ed25519 byte contracts for source-catalog v2. */
object SourceCatalogSignatureCodec {
    const val MANIFEST_FORMAT = "kira-source-catalog-manifest-v1"
    const val SOURCE_FORMAT = "kira-source-revision-v1"

    fun manifestPayload(
        catalogRevision: Long,
        previousCatalogRevision: Long?,
        previousCatalogChecksum: String?,
        checksum: String,
        createdAt: Instant,
        manifestJson: String,
    ): ByteArray {
        val metadata =
            buildString {
                append(MANIFEST_FORMAT).append('\n')
                append(catalogRevision).append('\n')
                append(previousCatalogRevision ?: 0).append('\n')
                append(previousCatalogChecksum ?: "-").append('\n')
                append(checksum).append('\n')
                append(DateTimeFormatter.ISO_INSTANT.format(createdAt)).append('\n')
            }
        return (metadata + manifestJson).toByteArray(StandardCharsets.UTF_8)
    }

    fun sourcePayload(api: String, sourceRevision: Int, checksum: String, canonicalJson: String): ByteArray = buildString {
        append(SOURCE_FORMAT).append('\n')
        append(api).append('\n')
        append(sourceRevision).append('\n')
        append(checksum).append('\n')
        append(canonicalJson)
    }.toByteArray(StandardCharsets.UTF_8)
}
