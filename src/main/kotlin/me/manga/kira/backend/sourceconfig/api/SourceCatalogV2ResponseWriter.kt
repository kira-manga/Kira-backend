package me.manga.kira.backend.sourceconfig.api

import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceArtifact
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalog
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

@Component
class SourceCatalogV2ResponseWriter {
    fun writeManifest(catalog: PublishedSourceCatalog, ifNoneMatch: String?, response: HttpServletResponse) {
        response.setHeader(HttpHeaders.ETAG, quote(catalog.checksum))
        response.setHeader(HttpHeaders.CACHE_CONTROL, MANIFEST_CACHE_CONTROL)
        response.setHeader(DocumentResponseWriter.NOSNIFF_HEADER, DocumentResponseWriter.NOSNIFF_VALUE)
        response.setHeader(DocumentResponseWriter.HEADER_CONFIG_REVISION, catalog.catalogRevision.toString())
        response.setHeader(DocumentResponseWriter.HEADER_CONFIG_CHECKSUM, catalog.checksum)
        response.setHeader(DocumentResponseWriter.HEADER_SIGNATURE_FORMAT, catalog.signatureFormat)
        response.setHeader(DocumentResponseWriter.HEADER_SIGNATURE_ALGORITHM, catalog.signatureAlgorithm)
        response.setHeader(DocumentResponseWriter.HEADER_SIGNING_KEY_ID, catalog.signingKeyId)
        response.setHeader(DocumentResponseWriter.HEADER_SIGNATURE, catalog.signatureBase64)
        catalog.previousCatalogRevision?.let { response.setHeader(DocumentResponseWriter.HEADER_PREVIOUS_REVISION, it.toString()) }
        catalog.previousCatalogChecksum?.let { response.setHeader(DocumentResponseWriter.HEADER_PREVIOUS_CHECKSUM, it) }
        response.setHeader(DocumentResponseWriter.HEADER_CREATED_AT, catalog.createdAt.toString())
        writeConditional(catalog.manifestJson, catalog.checksum, ifNoneMatch, response)
    }

    fun writeSource(artifact: PublishedSourceArtifact, ifNoneMatch: String?, response: HttpServletResponse) {
        response.setHeader(HttpHeaders.ETAG, quote(artifact.checksum))
        response.setHeader(HttpHeaders.CACHE_CONTROL, SOURCE_CACHE_CONTROL)
        response.setHeader(DocumentResponseWriter.NOSNIFF_HEADER, DocumentResponseWriter.NOSNIFF_VALUE)
        response.setHeader(HEADER_SOURCE_API, artifact.api)
        response.setHeader(HEADER_SOURCE_REVISION, artifact.sourceRevision.toString())
        response.setHeader(HEADER_SOURCE_CHECKSUM, artifact.checksum)
        response.setHeader(HEADER_SOURCE_CANON_VERSION, artifact.canonVersion)
        writeConditional(artifact.canonicalJson, artifact.checksum, ifNoneMatch, response)
    }

    private fun writeConditional(body: String, checksum: String, ifNoneMatch: String?, response: HttpServletResponse) {
        if (stronglyMatches(ifNoneMatch, checksum)) {
            response.status = HttpStatus.NOT_MODIFIED.value()
            return
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        response.status = HttpStatus.OK.value()
        response.contentType = DocumentResponseWriter.JSON_UTF8.toString()
        response.setContentLength(bytes.size)
        response.outputStream.write(bytes)
        response.outputStream.flush()
    }

    private fun stronglyMatches(value: String?, checksum: String): Boolean {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return raw == "*" || raw.split(",").any { it.trim() == quote(checksum) }
    }

    private fun quote(value: String) = "\"$value\""

    companion object {
        const val MANIFEST_CACHE_CONTROL = "public, max-age=300, no-transform"
        const val SOURCE_CACHE_CONTROL = "public, max-age=31536000, immutable, no-transform"
        const val HEADER_SOURCE_API = "X-Source-Api"
        const val HEADER_SOURCE_REVISION = "X-Source-Revision"
        const val HEADER_SOURCE_CHECKSUM = "X-Source-Checksum"
        const val HEADER_SOURCE_CANON_VERSION = "X-Source-Canon-Version"
    }
}
