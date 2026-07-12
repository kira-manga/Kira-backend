package me.manga.kira.backend.sourceconfig.api

import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.sourceconfig.api.dto.DocumentMetaResponse
import me.manga.kira.backend.sourceconfig.application.NoPublishedDocumentException
import me.manga.kira.backend.sourceconfig.application.SourceQueryService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The public app document endpoints (PLAN §4.1) — read-only, no auth (permitted by the §6 security
 * matrix). `/document` serves the latest published snapshot as the EXACT stored canonical bytes
 * ([DocumentResponseWriter]: strong ETag/304, `Cache-Control`/nosniff, `X-Config-*`); `/document/meta`
 * is the cheap poll. There is **no public historical retrieval** — no `revision` parameter exists here
 * (historical snapshots stay admin-only at `GET /admin/documents/{revision}`).
 */
@RestController
@RequestMapping("/api/v1/source-config")
class SourceDocumentController(
    private val sourceQueryService: SourceQueryService,
    private val documentResponseWriter: DocumentResponseWriter,
) {

    /**
     * `GET /source-config/document` — the app document. Optional `appVersion` is validated (semver-ish,
     * bounded), recorded (logged, never stored), and does NOT filter in v1 (PLAN §4.1 / Open Q6). No
     * document published yet → 404.
     */
    @GetMapping("/document")
    fun document(
        @RequestParam(required = false) appVersion: String?,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
        response: HttpServletResponse,
    ) {
        validateAndLogAppVersion(appVersion)
        val document = sourceQueryService.latestDocument() ?: throw NoPublishedDocumentException()
        documentResponseWriter.write(document, ifNoneMatch, cacheable = true, response = response)
    }

    /** `GET /source-config/document/meta` — `{revision, schemaVersion, checksum, publishedAt}` of the latest. */
    @GetMapping("/document/meta")
    fun meta(): ResponseEntity<DocumentMetaResponse> {
        val document = sourceQueryService.latestDocument() ?: throw NoPublishedDocumentException()
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CACHE_CONTROL, DocumentResponseWriter.CACHE_CONTROL_VALUE)
            .header(DocumentResponseWriter.NOSNIFF_HEADER, DocumentResponseWriter.NOSNIFF_VALUE)
            .contentType(DocumentResponseWriter.JSON_UTF8)
            .body(DocumentMetaResponse.of(document))
    }

    /**
     * Validate the optional `appVersion` (bounded, semver-ish) and LOG it (PLAN §4.1 / §6 — appVersion
     * and revision numbers are the only client-supplied values loggable). Invalid → 400 with a stable
     * code and a generic message that NEVER echoes the submitted value (§6).
     */
    private fun validateAndLogAppVersion(appVersion: String?) {
        if (appVersion == null) return
        if (appVersion.length > APP_VERSION_MAX_LENGTH || !APP_VERSION_PATTERN.matches(appVersion)) {
            throw BadRequestException(
                "the 'appVersion' query parameter is not a valid version string.",
                code = "INVALID_APP_VERSION",
            )
        }
        log.info("public source-config document requested (appVersion={})", appVersion)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(SourceDocumentController::class.java)
        const val APP_VERSION_MAX_LENGTH = 64

        /** `1`, `1.2`, `1.2.3`, `1.2.3.4`, with an optional `-`/`+` pre-release/build suffix (PLAN §4.1). */
        val APP_VERSION_PATTERN = Regex("""\d+(\.\d+){0,3}([-+][A-Za-z0-9.-]+)?""")
    }
}
