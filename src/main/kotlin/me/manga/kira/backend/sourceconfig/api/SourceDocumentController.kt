package me.manga.kira.backend.sourceconfig.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.sourceconfig.api.dto.DocumentMetaResponse
import me.manga.kira.backend.sourceconfig.api.dto.SigningKeysResponse
import me.manga.kira.backend.sourceconfig.application.ClientCatalogQueryService
import me.manga.kira.backend.sourceconfig.application.NoPublishedDocumentException
import me.manga.kira.backend.sourceconfig.signing.DocumentSignatureCodec
import me.manga.kira.backend.sourceconfig.signing.DocumentSigner
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The public app document endpoints (PLAN §4.1) — read-only, no auth (permitted by the §6 security
 * matrix). `/document` serves the selected signed client snapshot as the EXACT stored canonical bytes
 * ([DocumentResponseWriter]: strong ETag/304, `Cache-Control`/nosniff, `X-Config-*`); `/document/meta`
 * is the metadata poll. There is no arbitrary public historical retrieval: no `revision` parameter
 * exists here. Only explicitly configured client cohorts may select a pinned historical snapshot.
 */
@RestController
@RequestMapping("/api/v1/source-config")
class SourceDocumentController(
    private val clientCatalogQueryService: ClientCatalogQueryService,
    private val documentResponseWriter: DocumentResponseWriter,
    private val documentSigner: DocumentSigner,
) {

    /**
     * Raw decoded values preserve duplicates and whitespace; framework list conversion must not admit
     * an ambiguous version. Selection precedes ETag handling. No submitted version is logged.
     */
    @GetMapping("/document")
    fun document(
        request: HttpServletRequest,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
        response: HttpServletResponse,
    ) {
        val document = clientCatalogQueryService.documentForClient(request.getParameterValues("appVersion")?.toList())
            ?: throw NoPublishedDocumentException()
        documentResponseWriter.write(document, ifNoneMatch, cacheable = true, response = response)
    }

    /** Metadata for the same client selection as `/document`, not an independently resolved latest pointer. */
    @GetMapping("/document/meta")
    fun meta(request: HttpServletRequest): ResponseEntity<DocumentMetaResponse> {
        val document = clientCatalogQueryService.documentMetaForClient(request.getParameterValues("appVersion")?.toList())
            ?: throw NoPublishedDocumentException()
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CACHE_CONTROL, DocumentResponseWriter.CACHE_CONTROL_VALUE)
            .header(DocumentResponseWriter.NOSNIFF_HEADER, DocumentResponseWriter.NOSNIFF_VALUE)
            .contentType(DocumentResponseWriter.JSON_UTF8)
            .body(DocumentMetaResponse.of(document))
    }

    /** Discovery aid for planned rotation. Clients pin accepted keys and never trust this list alone. */
    @GetMapping("/signing-keys")
    fun signingKeys(): SigningKeysResponse = SigningKeysResponse(
        format = DocumentSignatureCodec.FORMAT,
        algorithm = DocumentSignatureCodec.ALGORITHM,
        keys = documentSigner.publicKeys(),
    )
}
