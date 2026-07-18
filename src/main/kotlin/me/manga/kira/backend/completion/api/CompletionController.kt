package me.manga.kira.backend.completion.api

import me.manga.kira.backend.common.PageResponse
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.common.exception.NotFoundException
import me.manga.kira.backend.common.exception.PayloadTooLargeException
import me.manga.kira.backend.completion.api.dto.CompletionRequestDto
import me.manga.kira.backend.completion.api.dto.CompletionResponse
import me.manga.kira.backend.completion.application.CompletionService
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.security.AuthenticatedUser
import me.manga.kira.backend.user.domain.Role
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
import java.util.UUID

/**
 * Completion endpoints (PLAN §4.6) — authenticated USER or ADMIN (the §6 security matrix returns 401
 * for anonymous before dispatch). The controller owns request-shape validation (blank prompt → 400,
 * over-length prompt → 413); [CompletionService] owns the three-transaction orchestration. Ownership
 * is enforced in the service: a non-owner non-admin GET returns 404 (never 403 — no id probing).
 */
@RestController
@RequestMapping("/api/v1/completions")
class CompletionController(
    private val completionService: CompletionService,
    private val properties: KiraCompletionProperties,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody request: CompletionRequestDto,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): CompletionResponse {
        val prompt = request.prompt
        if (prompt.isBlank()) {
            throw BadRequestException("prompt must not be blank.", code = "BLANK_PROMPT")
        }
        if (prompt.length > properties.promptMaxLength) {
            throw PayloadTooLargeException(
                "prompt exceeds the maximum length of ${properties.promptMaxLength} characters.",
                code = "PROMPT_TOO_LARGE",
            )
        }
        if (request.model != null && request.model.length > MAX_MODEL_LENGTH) {
            throw BadRequestException(
                "model must be at most $MAX_MODEL_LENGTH characters.",
                code = "MODEL_TOO_LONG",
            )
        }
        return CompletionResponse.from(completionService.create(user.id, prompt, request.model))
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): CompletionResponse =
        completionService
            .getForReader(id, user.id, user.role == Role.ADMIN)
            ?.let(CompletionResponse::from)
            ?: throw NotFoundException("No completion with that id.", code = "COMPLETION_NOT_FOUND")

    @GetMapping
    fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(required = false) userId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<CompletionResponse> {
        if (page < 0) throw BadRequestException("page must be >= 0.", code = "INVALID_PAGE")
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw BadRequestException("size must be between 1 and $MAX_PAGE_SIZE.", code = "INVALID_PAGE_SIZE")
        }
        val paged = completionService.list(user.id, user.role == Role.ADMIN, userId, page, size)
        return PageResponse(
            items = paged.items.map(CompletionResponse::from),
            page = page,
            size = size,
            total = paged.total,
        )
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
        const val MAX_MODEL_LENGTH = 128
    }
}
