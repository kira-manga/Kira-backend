package me.manga.kira.backend.sourceconfig.api

import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.security.AuthenticatedUser
import me.manga.kira.backend.sourceconfig.api.dto.DocumentSummaryResponse
import me.manga.kira.backend.sourceconfig.api.dto.DocumentValidationResponse
import me.manga.kira.backend.sourceconfig.api.dto.PublishResponse
import me.manga.kira.backend.sourceconfig.application.SourceAdminService
import org.springframework.http.HttpHeaders
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Admin document-snapshot endpoints (PLAN §4.3) — `ROLE_ADMIN` only. `GET /{revision}` returns the EXACT
 * stored canonical bytes as the body (the same raw-bytes contract as the public endpoint) with metadata
 * in headers only — deliberately NOT a JSON envelope, which would break the serve-stored-bytes/checksum
 * guarantee (PLAN §4.3/§S3). This is the ONLY historical-snapshot retrieval surface; the public endpoint
 * serves the latest only. The raw-bytes + ETag/`If-None-Match` writing is shared with the public route
 * via [DocumentResponseWriter] (`cacheable = false` here: no public `Cache-Control`/nosniff directives).
 */
@RestController
@RequestMapping("/api/v1/admin/documents")
class AdminDocumentsController(private val sourceAdminService: SourceAdminService, private val documentResponseWriter: DocumentResponseWriter) {

    @GetMapping
    fun list(): List<DocumentSummaryResponse> = sourceAdminService.listDocuments().map { DocumentSummaryResponse.of(it) }

    @GetMapping("/{revision}")
    fun getRaw(
        @PathVariable revision: Long,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
        response: HttpServletResponse,
    ) = documentResponseWriter.write(sourceAdminService.getDocument(revision), ifNoneMatch, cacheable = false, response = response)

    @PostMapping("/validate")
    fun validateCandidate(): DocumentValidationResponse = DocumentValidationResponse.of(sourceAdminService.validateCandidateDocument())

    @PostMapping("/republish")
    fun republish(@AuthenticationPrincipal admin: AuthenticatedUser): PublishResponse = PublishResponse.of(sourceAdminService.republish(admin.id))
}
