package me.manga.kira.backend.sourceconfig.api

import jakarta.validation.Valid
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.security.AdminStepUpService
import me.manga.kira.backend.security.AuthenticatedUser
import me.manga.kira.backend.sourceconfig.api.dto.CreateSourceChangesetRequest
import me.manga.kira.backend.sourceconfig.api.dto.SaveSourceChangesetRequest
import me.manga.kira.backend.sourceconfig.api.dto.SourceChangesetApplyResponse
import me.manga.kira.backend.sourceconfig.api.dto.SourceChangesetResponse
import me.manga.kira.backend.sourceconfig.api.dto.SourceChangesetValidationResponse
import me.manga.kira.backend.sourceconfig.application.ChangesetView
import me.manga.kira.backend.sourceconfig.application.SourceChangesetService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/source-changesets")
class SourceChangesetController(private val changesets: SourceChangesetService, private val stepUp: AdminStepUpService) {
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateSourceChangesetRequest,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): ResponseEntity<SourceChangesetResponse> {
        val value = changesets.create(requireNotNull(request.name), request.description, admin.id)
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag("\"changeset-${value.changeset.version}\"")
            .body(SourceChangesetResponse.of(value.changeset, value.operations))
    }

    @GetMapping
    fun list(): List<SourceChangesetResponse> = changesets.list().map { SourceChangesetResponse.of(it.changeset, it.operations) }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<SourceChangesetResponse> = response(changesets.get(id))

    @PutMapping("/{id}")
    fun save(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH) ifMatch: String,
        @Valid @RequestBody request: SaveSourceChangesetRequest,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): ResponseEntity<SourceChangesetResponse> = response(
        changesets.save(
            id,
            version(ifMatch),
            requireNotNull(request.name),
            request.description,
            request.operations.map { it.toDomain() },
            admin.id,
        ),
    )

    @PostMapping("/{id}/validate")
    fun validate(@PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH) ifMatch: String): SourceChangesetValidationResponse =
        SourceChangesetValidationResponse.of(changesets.validate(id, version(ifMatch)))

    @PostMapping("/{id}/apply")
    fun apply(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH) ifMatch: String,
        @RequestHeader(name = AdminStepUpService.HEADER, required = false) proof: String?,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): SourceChangesetApplyResponse {
        stepUp.requireSourceMutation(admin.id, proof)
        return SourceChangesetApplyResponse.of(changesets.apply(id, version(ifMatch), admin.id))
    }

    @DeleteMapping("/{id}")
    fun discard(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH) ifMatch: String,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): ResponseEntity<SourceChangesetResponse> = response(changesets.discard(id, version(ifMatch), admin.id))

    private fun response(value: ChangesetView): ResponseEntity<SourceChangesetResponse> = ResponseEntity.ok()
        .eTag("\"changeset-${value.changeset.version}\"")
        .body(SourceChangesetResponse.of(value.changeset, value.operations))

    private fun version(raw: String): Long = ETAG.matchEntire(raw.trim())?.groupValues?.get(1)?.toLongOrNull()
        ?: throw BadRequestException("If-Match must contain a changeset ETag.", code = "INVALID_CHANGESET_ETAG")

    private companion object {
        val ETAG = Regex("\"changeset-([1-9][0-9]*)\"")
    }
}
