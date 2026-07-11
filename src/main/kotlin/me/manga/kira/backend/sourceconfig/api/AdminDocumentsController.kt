package me.manga.kira.backend.sourceconfig.api

import me.manga.kira.backend.security.AuthenticatedUser
import me.manga.kira.backend.sourceconfig.api.dto.DocumentSummaryResponse
import me.manga.kira.backend.sourceconfig.api.dto.DocumentValidationResponse
import me.manga.kira.backend.sourceconfig.api.dto.PublishResponse
import me.manga.kira.backend.sourceconfig.application.SourceAdminService
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

/**
 * Admin document-snapshot endpoints (PLAN §4.3) — `ROLE_ADMIN` only. `GET /{revision}` returns the EXACT
 * stored canonical bytes as the body (the same raw-bytes contract as the public endpoint, Phase 7) with
 * metadata in headers only — deliberately NOT a JSON envelope, which would break the
 * serve-stored-bytes/checksum guarantee (PLAN §4.3/§S3). This is the ONLY historical-snapshot retrieval
 * surface; the public endpoint (Phase 7) serves the latest only.
 */
@RestController
@RequestMapping("/api/v1/admin/documents")
class AdminDocumentsController(
    private val sourceAdminService: SourceAdminService,
) {

    @GetMapping
    fun list(): List<DocumentSummaryResponse> =
        sourceAdminService.listDocuments().map { DocumentSummaryResponse.of(it) }

    @GetMapping("/{revision}")
    fun getRaw(
        @PathVariable revision: Long,
    ): ResponseEntity<ByteArray> {
        val document = sourceAdminService.getDocument(revision)
        return rawBytes(document)
    }

    @PostMapping("/validate")
    fun validateCandidate(): DocumentValidationResponse =
        DocumentValidationResponse.of(sourceAdminService.validateCandidateDocument())

    @PostMapping("/republish")
    fun republish(
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): PublishResponse = PublishResponse.of(sourceAdminService.republish(admin.id))

    /** Serve the stored canonical bytes verbatim + metadata headers (never re-serialized; PLAN §4.1/§4.3). */
    private fun rawBytes(document: PublishedDocument): ResponseEntity<ByteArray> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .eTag("\"${document.checksum}\"")
            .header("X-Config-Revision", document.documentRevision.toString())
            .header("X-Config-Checksum", document.checksum)
            .body(document.documentJson.toByteArray(StandardCharsets.UTF_8))
}
