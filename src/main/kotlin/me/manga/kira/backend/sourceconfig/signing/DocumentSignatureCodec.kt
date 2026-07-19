package me.manga.kira.backend.sourceconfig.signing

import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter

/** Versioned byte contract covered by backend and application golden tests. */
object DocumentSignatureCodec {
    const val FORMAT = "kira-source-signature-v1"
    const val ALGORITHM = "Ed25519"

    fun payload(input: DocumentSigningInput): ByteArray {
        val metadata =
            buildString {
                append(FORMAT).append('\n')
                append(input.revision).append('\n')
                append(input.previousRevision ?: 0).append('\n')
                append(input.previousChecksum ?: "-").append('\n')
                append(input.checksum).append('\n')
                append(DateTimeFormatter.ISO_INSTANT.format(input.createdAt)).append('\n')
            }
        return (metadata + input.documentJson).toByteArray(StandardCharsets.UTF_8)
    }
}
