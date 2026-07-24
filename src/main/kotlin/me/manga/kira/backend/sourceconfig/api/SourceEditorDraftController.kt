package me.manga.kira.backend.sourceconfig.api

import jakarta.validation.Valid
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.security.AdminStepUpService
import me.manga.kira.backend.security.AuthenticatedUser
import me.manga.kira.backend.sourceconfig.api.dto.FinalizeSourceDraftResponse
import me.manga.kira.backend.sourceconfig.api.dto.OpenSourceDraftRequest
import me.manga.kira.backend.sourceconfig.api.dto.PublishSourceDraftResponse
import me.manga.kira.backend.sourceconfig.api.dto.SaveSourceDraftRequest
import me.manga.kira.backend.sourceconfig.api.dto.SourceDraftResponse
import me.manga.kira.backend.sourceconfig.api.dto.ValidationResultDto
import me.manga.kira.backend.sourceconfig.application.SourceEditorDraftService
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/sources/{api}/editor-draft")
class SourceEditorDraftController(private val drafts: SourceEditorDraftService, private val stepUp: AdminStepUpService) {
    @PostMapping
    fun open(
        @PathVariable api: String,
        @RequestBody(required = false) request: OpenSourceDraftRequest?,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): ResponseEntity<SourceDraftResponse> = response(drafts.open(api, request?.fromRevision, admin.id))

    @GetMapping
    fun get(@PathVariable api: String): ResponseEntity<SourceDraftResponse> = response(drafts.get(api))

    @PutMapping
    fun save(
        @PathVariable api: String,
        @RequestHeader(HttpHeaders.IF_MATCH) ifMatch: String,
        @Valid @RequestBody request: SaveSourceDraftRequest,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): ResponseEntity<SourceDraftResponse> = response(drafts.save(api, version(ifMatch), requireNotNull(request.content), admin.id))

    @PostMapping("/validate")
    fun validate(@PathVariable api: String, @RequestHeader(HttpHeaders.IF_MATCH) ifMatch: String): ValidationResultDto =
        ValidationResultDto.of(drafts.validate(api, version(ifMatch)))

    @PostMapping("/finalize")
    fun finalize(
        @PathVariable api: String,
        @RequestHeader(HttpHeaders.IF_MATCH) ifMatch: String,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): ResponseEntity<FinalizeSourceDraftResponse> {
        val result = drafts.finalizeDraft(api, version(ifMatch), admin.id)
        return ResponseEntity
            .ok()
            .eTag(etag(result.draft.version))
            .body(FinalizeSourceDraftResponse.of(result))
    }

    @PostMapping("/publish")
    fun publish(
        @PathVariable api: String,
        @RequestHeader(HttpHeaders.IF_MATCH) ifMatch: String,
        @RequestHeader(name = AdminStepUpService.HEADER, required = false) proof: String?,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): ResponseEntity<PublishSourceDraftResponse> {
        stepUp.requireSourceMutation(admin.id, proof)
        val result = drafts.publish(api, version(ifMatch), admin.id)
        return ResponseEntity
            .ok()
            .eTag(etag(result.draft.version))
            .body(PublishSourceDraftResponse.of(result))
    }

    @DeleteMapping
    fun discard(
        @PathVariable api: String,
        @RequestHeader(HttpHeaders.IF_MATCH) ifMatch: String,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): ResponseEntity<Void> {
        drafts.discard(api, version(ifMatch), admin.id)
        return ResponseEntity.noContent().build()
    }

    private fun response(draft: me.manga.kira.backend.sourceconfig.domain.SourceEditorDraft): ResponseEntity<SourceDraftResponse> =
        ResponseEntity.ok().eTag(etag(draft.version)).body(SourceDraftResponse.of(draft))

    private fun version(raw: String): Long {
        val match = ETAG.matchEntire(raw.trim())
            ?: throw BadRequestException("If-Match must contain a source draft ETag.", code = "INVALID_DRAFT_ETAG")
        return match.groupValues[1].toLongOrNull()
            ?: throw BadRequestException("If-Match must contain a source draft ETag.", code = "INVALID_DRAFT_ETAG")
    }

    private fun etag(version: Long): String = "\"draft-$version\""

    private companion object {
        val ETAG = Regex("\"draft-([1-9][0-9]*)\"")
    }
}
