package me.manga.kira.backend.sourceconfig.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.common.exception.PayloadTooLargeException
import me.manga.kira.backend.security.AuthenticatedUser
import me.manga.kira.backend.sourceconfig.api.dto.AdminSourceResponse
import me.manga.kira.backend.sourceconfig.api.dto.ImportBundledResponse
import me.manga.kira.backend.sourceconfig.api.dto.PublishResponse
import me.manga.kira.backend.sourceconfig.api.dto.RemoveRequest
import me.manga.kira.backend.sourceconfig.api.dto.RevisionDetailResponse
import me.manga.kira.backend.sourceconfig.api.dto.RevisionSummaryResponse
import me.manga.kira.backend.sourceconfig.api.dto.RollbackRequest
import me.manga.kira.backend.sourceconfig.api.dto.RollbackResponse
import me.manga.kira.backend.sourceconfig.api.dto.SourceMutationResponse
import me.manga.kira.backend.sourceconfig.api.dto.ValidationResultDto
import me.manga.kira.backend.sourceconfig.application.BundledImportService
import me.manga.kira.backend.sourceconfig.application.SourceAdminService
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Admin source-management endpoints (PLAN §4.3) — `ROLE_ADMIN` only (enforced by the §6 security
 * matrix; the controller slots in behind it). Every mutation is audited by [SourceAdminService].
 *
 * Authoring bodies (`create`, `revisions`) are read as the RAW request body and handed to the STRICT
 * authoring parser (PLAN §7) — NOT bound by Jackson — so unknown/duplicate keys and trailing garbage
 * are rejected with the offending token named, rather than silently dropped by a lenient object mapper.
 * The acting admin's id comes from the authenticated principal (audit actor / `created_by`).
 */
@RestController
@RequestMapping("/api/v1/admin/sources")
class AdminSourcesController(
    private val sourceAdminService: SourceAdminService,
    private val bundledImportService: BundledImportService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        httpRequest: HttpServletRequest,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): SourceMutationResponse =
        SourceMutationResponse.of(sourceAdminService.createSource(httpRequest.readBody(), admin.id))

    /**
     * `POST /admin/sources/import-bundled` (PLAN §4.3 / §12.2) — seed/re-sync from the app's bundled
     * document JSON. Read as the RAW body (COMPATIBILITY-parsed by the service, NOT Jackson-bound) with
     * a **5 MiB** cap for THIS endpoint (the §4.5 default elsewhere is 256 KB): over-limit → 413. A 200
     * (including the no-op case) carries the structured summary; a validation failure is a 422 with the
     * full error list; malformed JSON is the parser's 400.
     */
    @PostMapping("/import-bundled")
    fun importBundled(
        httpRequest: HttpServletRequest,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): ImportBundledResponse =
        ImportBundledResponse.of(bundledImportService.import(httpRequest.readBoundedBody(MAX_IMPORT_BODY_BYTES), admin.id))

    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
    ): List<AdminSourceResponse> =
        sourceAdminService.listSources(parseStatus(status)).map { AdminSourceResponse.of(it) }

    @GetMapping("/{api}")
    fun get(
        @PathVariable api: String,
    ): AdminSourceResponse = AdminSourceResponse.of(sourceAdminService.getSource(api))

    @PostMapping("/{api}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    fun createRevision(
        @PathVariable api: String,
        httpRequest: HttpServletRequest,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): SourceMutationResponse =
        SourceMutationResponse.of(sourceAdminService.createRevision(api, httpRequest.readBody(), admin.id))

    @GetMapping("/{api}/revisions")
    fun listRevisions(
        @PathVariable api: String,
    ): List<RevisionSummaryResponse> =
        sourceAdminService.listRevisions(api).map { RevisionSummaryResponse.of(it) }

    @GetMapping("/{api}/revisions/{number}")
    fun getRevision(
        @PathVariable api: String,
        @PathVariable number: Int,
    ): RevisionDetailResponse = RevisionDetailResponse.of(sourceAdminService.getRevision(api, number))

    @PostMapping("/{api}/revisions/{number}/validate")
    fun validateRevision(
        @PathVariable api: String,
        @PathVariable number: Int,
    ): ValidationResultDto = ValidationResultDto.of(sourceAdminService.validateRevision(api, number))

    @GetMapping("/{api}/revisions/{number}/validation")
    fun getValidation(
        @PathVariable api: String,
        @PathVariable number: Int,
    ): ValidationResultDto = ValidationResultDto.of(sourceAdminService.getLatestValidation(api, number))

    @PostMapping("/{api}/revisions/{number}/publish")
    fun publish(
        @PathVariable api: String,
        @PathVariable number: Int,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): PublishResponse = PublishResponse.of(sourceAdminService.publish(api, number, admin.id))

    @PostMapping("/{api}/disable")
    fun disable(
        @PathVariable api: String,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): PublishResponse = PublishResponse.of(sourceAdminService.disable(api, admin.id))

    @PostMapping("/{api}/enable")
    fun enable(
        @PathVariable api: String,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): PublishResponse = PublishResponse.of(sourceAdminService.enable(api, admin.id))

    @PostMapping("/{api}/retire")
    fun retire(
        @PathVariable api: String,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): PublishResponse = PublishResponse.of(sourceAdminService.retire(api, admin.id))

    @PostMapping("/{api}/remove")
    fun remove(
        @PathVariable api: String,
        @Valid @RequestBody request: RemoveRequest,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): PublishResponse = PublishResponse.of(sourceAdminService.remove(api, request.confirm, admin.id))

    @PostMapping("/{api}/rollback")
    fun rollback(
        @PathVariable api: String,
        @Valid @RequestBody request: RollbackRequest,
        @AuthenticationPrincipal admin: AuthenticatedUser,
    ): RollbackResponse = RollbackResponse.of(sourceAdminService.rollback(api, request.toRevision, admin.id))

    /** Read the raw request body (UTF-8) for the STRICT authoring parser (PLAN §7). */
    private fun HttpServletRequest.readBody(): String = inputStream.readBytes().toString(Charsets.UTF_8)

    /**
     * Read the raw request body (UTF-8) bounded to [maxBytes], enforcing the §4.5 `import-bundled` 5 MiB
     * limit → **413** (PLAN §4.5). The declared `Content-Length` is checked first for a fast reject, but
     * the read itself is also bounded (`readNBytes(maxBytes + 1)`) so an absent/chunked or under-reporting
     * Content-Length cannot smuggle a larger body past the cap. The message never echoes any submitted
     * content (§6 log-hygiene).
     */
    private fun HttpServletRequest.readBoundedBody(maxBytes: Int): String {
        if (contentLengthLong > maxBytes) throw payloadTooLarge(maxBytes)
        val bytes = inputStream.readNBytes(maxBytes + 1)
        if (bytes.size > maxBytes) throw payloadTooLarge(maxBytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun payloadTooLarge(maxBytes: Int): PayloadTooLargeException =
        PayloadTooLargeException("request body exceeds the ${maxBytes / (1024 * 1024)} MiB import limit.")

    /** Parse the optional `?status=` filter into the enum; an unknown value is a 400 (PLAN §4.5). */
    private fun parseStatus(status: String?): SourceLifecycleStatus? =
        status?.let { raw ->
            SourceLifecycleStatus.entries.firstOrNull { it.wire == raw }
                ?: throw BadRequestException("unknown status filter '$raw'.", code = "INVALID_STATUS_FILTER")
        }

    private companion object {
        /** `import-bundled` request-body cap (PLAN §4.5): 5 MiB (the real document is well under 1 MiB). */
        const val MAX_IMPORT_BODY_BYTES = 5 * 1024 * 1024
    }
}
