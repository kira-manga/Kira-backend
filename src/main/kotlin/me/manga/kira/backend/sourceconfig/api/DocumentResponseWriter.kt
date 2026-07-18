package me.manga.kira.backend.sourceconfig.api

import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * The single writer for a stored-canonical-bytes document response (PLAN §4.1/§4.3). Serves the EXACT
 * stored bytes VERBATIM (never re-serialized by a message converter — the checksum is over the bytes
 * sent), stamps the strong quoted ETag (= the document checksum) plus `X-Config-Revision`/
 * `X-Config-Checksum`, and evaluates `If-None-Match` → a bodiless 304 on a match. Both the public
 * [SourceDocumentController] and the admin [AdminDocumentsController] route through here so the
 * raw-bytes + ETag + conditional-GET contract lives in ONE place (PLAN §4.1 handoff).
 *
 * It writes DIRECTLY to the [HttpServletResponse] (the caller declares it and returns no body) rather
 * than returning a `ResponseEntity`: Spring's built-in `ResponseEntity` conditional handling applies
 * **weak** `If-None-Match` comparison (RFC 9110 §13.1.2), which would 304 a `W/"…"` validator — but
 * PLAN §4.1 mandates **strong** comparison (weak validators never match → full body). Owning the write
 * keeps that guarantee intact.
 *
 * [cacheable] gates the public-only cross-cutting headers (PLAN §4.5): `Cache-Control` + nosniff and the
 * `charset=UTF-8` content-type. The admin historical-snapshot route passes `false` (metadata in headers
 * only, no cache directives). `X-Config-Checksum` is a corruption check, NOT authenticity (§4.1).
 */
@Component
class DocumentResponseWriter {

    /**
     * Write [document] to [response]: a bodiless **304** (same ETag + headers) when [ifNoneMatch]
     * strongly matches the checksum, otherwise **200** with the verbatim stored bytes.
     */
    fun write(document: PublishedDocument, ifNoneMatch: String?, cacheable: Boolean, response: HttpServletResponse) {
        response.setHeader(HttpHeaders.ETAG, "\"${document.checksum}\"")
        response.setHeader(HEADER_CONFIG_REVISION, document.documentRevision.toString())
        response.setHeader(HEADER_CONFIG_CHECKSUM, document.checksum)
        if (cacheable) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
            response.setHeader(NOSNIFF_HEADER, NOSNIFF_VALUE)
        }
        // A 304 carries the same ETag/Cache-Control headers but NO body and no content-type (PLAN §4.1).
        if (matchesIfNoneMatch(ifNoneMatch, document.checksum)) {
            response.status = HttpStatus.NOT_MODIFIED.value()
            return
        }
        val bytes = document.documentJson.toByteArray(StandardCharsets.UTF_8)
        response.status = HttpStatus.OK.value()
        response.contentType = (if (cacheable) JSON_UTF8 else JSON).toString()
        response.setContentLength(bytes.size)
        response.outputStream.write(bytes)
        response.outputStream.flush()
    }

    /**
     * RFC 9110 §8.8.3.2 strong comparison (PLAN §4.1): `*` matches any existing document; a
     * comma-separated list is parsed and each entry compared with quotes stripped; a **weak validator
     * `W/"…"` never strongly matches** even with an identical opaque hash → full 200 body.
     */
    private fun matchesIfNoneMatch(ifNoneMatch: String?, checksum: String): Boolean {
        val header = ifNoneMatch?.trim() ?: return false
        if (header.isEmpty()) return false
        if (header == "*") return true
        return header.split(",").any { raw ->
            val entry = raw.trim()
            entry.isNotEmpty() && !entry.startsWith("W/") && entry.trim('"') == checksum
        }
    }

    companion object {
        /** `Cache-Control` value for the public document endpoints (PLAN §4.1/§4.5), verbatim. */
        const val CACHE_CONTROL_VALUE = "public, max-age=300, no-transform"
        const val NOSNIFF_HEADER = "X-Content-Type-Options"
        const val NOSNIFF_VALUE = "nosniff"
        const val HEADER_CONFIG_REVISION = "X-Config-Revision"
        const val HEADER_CONFIG_CHECKSUM = "X-Config-Checksum"

        /** `application/json; charset=UTF-8` — the normative public content-type (PLAN §4.1/§4.5). */
        val JSON_UTF8: MediaType = MediaType("application", "json", StandardCharsets.UTF_8)
        val JSON: MediaType = MediaType.APPLICATION_JSON
    }
}
