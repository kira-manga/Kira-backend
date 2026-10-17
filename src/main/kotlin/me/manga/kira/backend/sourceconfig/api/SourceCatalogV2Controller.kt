package me.manga.kira.backend.sourceconfig.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.sourceconfig.application.ClientCatalogQueryService
import me.manga.kira.backend.sourceconfig.application.NoPublishedDocumentException
import me.manga.kira.backend.sourceconfig.application.SourceCatalogQueryService
import me.manga.kira.backend.sourceconfig.application.SourceNotFoundException
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Public fail-closed incremental source-catalog protocol. */
@RestController
@RequestMapping("/api/v2/source-config")
class SourceCatalogV2Controller(
    private val query: SourceCatalogQueryService,
    private val writer: SourceCatalogV2ResponseWriter,
    private val clientCatalogQueryService: ClientCatalogQueryService,
) {
    @GetMapping("/manifest")
    fun manifest(
        request: HttpServletRequest,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
        response: HttpServletResponse,
    ) {
        val catalog = clientCatalogQueryService.manifestForClient(request.getParameterValues("appVersion")?.toList())
            ?: throw NoPublishedDocumentException()
        writer.writeManifest(catalog, ifNoneMatch, response)
    }

    @GetMapping("/sources/{api}/revisions/{revision}")
    fun sourceRevision(
        @PathVariable api: String,
        @PathVariable revision: Int,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
        response: HttpServletResponse,
    ) {
        val artifact = query.publishedArtifact(api, revision) ?: throw SourceNotFoundException(api)
        writer.writeSource(artifact, ifNoneMatch, response)
    }
}
