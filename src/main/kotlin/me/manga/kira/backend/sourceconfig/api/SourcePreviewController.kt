package me.manga.kira.backend.sourceconfig.api

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.source.contracts.SourceEngineResult
import me.manga.kira.source.contracts.SourceHeaderProvider
import me.manga.kira.source.contracts.SourceRequest
import me.manga.kira.source.contracts.SourceResponse
import me.manga.kira.source.contracts.SourceTransport
import me.manga.kira.source.engine.GenericSourceEngine
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import me.manga.kira.source.contracts.SourceConfigParser as SharedSourceConfigParser

/**
 * Deterministic backend preview powered by the exact shared engine artifact used by app adapters.
 * The caller supplies a saved response fixture; preview never performs server-side network access,
 * so an authored URL cannot turn the admin surface into an SSRF primitive.
 */
@RestController
@RequestMapping("/api/v1/admin/source-preview")
class SourcePreviewController {
    @PostMapping
    suspend fun preview(@Valid @RequestBody request: SourcePreviewRequest): SourcePreviewResponse {
        requireByteLimit(request.sourceJson, 512 * 1024, "sourceJson")
        requireByteLimit(request.responseBody, 2 * 1024 * 1024, "responseBody")
        val config =
            when (val parsed = SharedSourceConfigParser.parseSource(request.sourceJson)) {
                is SourceEngineResult.Success -> parsed.value

                is SourceEngineResult.Failure ->
                    throw BadRequestException("preview source JSON is invalid.", code = "INVALID_PREVIEW_SOURCE")
            }
        if (config.engine != "generic") {
            throw BadRequestException("only generic sources can be previewed.", code = "NON_GENERIC_PREVIEW_FORBIDDEN")
        }
        var generated: SourceRequest? = null
        val transport =
            SourceTransport { outgoing ->
                generated = outgoing
                SourceResponse(
                    status = request.responseStatus,
                    body = request.responseBody,
                    finalUrl = request.finalUrl,
                )
            }
        val engine = GenericSourceEngine(config, transport, SourceHeaderProvider { emptyMap() })
        val result =
            when (request.operation) {
                "home" -> engine.home(request.page)
                "featured" -> engine.featured(request.page)
                "search" -> engine.search(request.query, request.page)
                else -> throw BadRequestException("unsupported preview operation.", code = "INVALID_PREVIEW_OPERATION")
            }
        val outgoing = generated
        return when (result) {
            is SourceEngineResult.Success ->
                SourcePreviewResponse(
                    success = true,
                    output = result.value,
                    error = null,
                    request =
                    outgoing?.let {
                        PreviewRequestMetadata(
                            url = it.url,
                            method = it.method.name,
                            headerNames = it.headers.keys.sorted(),
                            hasFormBody = it.formBody != null,
                            hasJsonBody = it.jsonBody != null,
                        )
                    },
                )

            is SourceEngineResult.Failure ->
                SourcePreviewResponse(
                    success = false,
                    output = null,
                    error = result.error::class.simpleName ?: "SourceEngineError",
                    request = null,
                )
        }
    }

    private fun requireByteLimit(value: String, limit: Int, field: String) {
        if (value.toByteArray(Charsets.UTF_8).size > limit) {
            throw BadRequestException("$field exceeds its preview limit.", code = "PREVIEW_PAYLOAD_TOO_LARGE")
        }
    }
}

data class SourcePreviewRequest(
    @field:NotBlank val sourceJson: String = "",
    @field:NotBlank val operation: String = "",
    @field:Min(1) @field:Max(10_000) val page: Int = 1,
    @field:Size(max = 500) val query: String = "",
    val responseStatus: Int = 200,
    val responseBody: String = "",
    @field:Size(max = 2048) val finalUrl: String? = null,
)

data class SourcePreviewResponse(val success: Boolean, val output: Any?, val error: String?, val request: PreviewRequestMetadata?)

data class PreviewRequestMetadata(val url: String, val method: String, val headerNames: List<String>, val hasFormBody: Boolean, val hasJsonBody: Boolean)
