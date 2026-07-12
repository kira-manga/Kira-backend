package me.manga.kira.backend.sourceconfig.api

import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.sourceconfig.api.dto.SourceSummaryResponse
import me.manga.kira.backend.sourceconfig.application.SourceNotFoundException
import me.manga.kira.backend.sourceconfig.application.SourceQueryService
import me.manga.kira.backend.sourceconfig.application.SourceRemovedException
import me.manga.kira.backend.sourceconfig.application.SourceStanzaResult
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

/**
 * The public source endpoints (PLAN §4.1) — read-only, no auth. `GET /sources` lists the summaries of
 * the sources in the current document (document order, `(position ASC, api ASC)`); `GET /sources/{api}`
 * serves the single published stanza CONSISTENT with the served document, with the §4.1 status mapping
 * (active/disabled/retired → 200; removed → 410; unknown/draft-only → 404). Both delegate to
 * [SourceQueryService] and stay thin.
 */
@RestController
@RequestMapping("/api/v1/sources")
class SourcesController(
    private val sourceQueryService: SourceQueryService,
) {

    /**
     * `GET /sources` — a plain JSON array (no pagination; it mirrors the bounded document content). No
     * document ever published → `[]`. Optional comma-separated `lifecycle` (app vocabulary {active,
     * disabled, removed}) and `engine` ({generic, legacy}) filters; an unknown value → 400 (PLAN §4.5).
     */
    @GetMapping
    fun list(
        @RequestParam(required = false) lifecycle: String?,
        @RequestParam(required = false) engine: String?,
    ): List<SourceSummaryResponse> {
        val lifecycles = parseFilter(lifecycle, APP_LIFECYCLES, "lifecycle", "INVALID_LIFECYCLE_FILTER")
        val engines = parseFilter(engine, ENGINES, "engine", "INVALID_ENGINE_FILTER")
        return sourceQueryService.listSummaries(lifecycles, engines).map { SourceSummaryResponse.of(it) }
    }

    /**
     * `GET /sources/{api}` — the single stanza as raw canonical bytes (exact app shape, not a
     * re-serialized DTO). 200 present / 410 removed / 404 unknown-or-draft-only (PLAN §4.1).
     */
    @GetMapping("/{api}")
    fun byApi(
        @PathVariable api: String,
    ): ResponseEntity<ByteArray> =
        when (val result = sourceQueryService.sourceStanza(api)) {
            is SourceStanzaResult.Found ->
                ResponseEntity
                    .ok()
                    .contentType(DocumentResponseWriter.JSON_UTF8)
                    .body(result.json.toByteArray(StandardCharsets.UTF_8))
            SourceStanzaResult.Gone -> throw SourceRemovedException(api)
            SourceStanzaResult.NotFound -> throw SourceNotFoundException(api)
        }

    /**
     * Parse a comma-separated multi-value filter (PLAN §4.5) into a set, rejecting any token outside
     * [allowed] with a 400 (stable [code], generic message — the submitted value is never echoed, §6).
     * A null or empty param → null (no filter).
     */
    private fun parseFilter(
        raw: String?,
        allowed: Set<String>,
        param: String,
        code: String,
    ): Set<String>? {
        if (raw == null) return null
        val values = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val unknown = values - allowed
        if (unknown.isNotEmpty()) {
            throw BadRequestException("the '$param' filter contains an unsupported value.", code = code)
        }
        return values.ifEmpty { null }
    }

    private companion object {
        /** App-vocabulary lifecycle filter values (PLAN §4.1 — the 3-value vocabulary). */
        val APP_LIFECYCLES = setOf("active", "disabled", "removed")

        /** Engine filter values (PLAN §4.1). */
        val ENGINES = setOf("generic", "legacy")
    }
}
