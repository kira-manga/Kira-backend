package me.manga.kira.backend.sourceconfig.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import me.manga.kira.backend.security.AdminStepUpService
import me.manga.kira.backend.security.AuthenticatedUser
import me.manga.kira.backend.security.ClientIpResolver
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/admin/step-up")
class AdminStepUpController(private val stepUp: AdminStepUpService, private val clientIpResolver: ClientIpResolver) {
    @PostMapping
    fun issue(
        @Valid @RequestBody request: AdminStepUpRequest,
        @AuthenticationPrincipal admin: AuthenticatedUser,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<AdminStepUpResponse> {
        val issued = stepUp.issue(admin.id, request.password, clientIpResolver.resolve(httpRequest), request.scope)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .headers { headers ->
                if (issued.scope == AdminStepUpService.COMPLAINT_MODERATION_MUTATION_SCOPE) {
                    // Server-to-server correlation only. The existing JSON and source scope remain unchanged.
                    headers.set(GRANT_ID_HEADER, issued.grantId.toString())
                }
            }
            .body(
                AdminStepUpResponse(
                    token = issued.token,
                    expiresAt = issued.expiresAt,
                    scope = issued.scope,
                ),
            )
    }

    companion object {
        const val GRANT_ID_HEADER = "X-Kira-Admin-Step-Up-Grant-Id"
    }
}

data class AdminStepUpRequest(
    @field:NotBlank @field:Size(max = 256) val password: String = "",
    @field:Size(max = 64) val scope: String = AdminStepUpService.SOURCE_ADMIN_MUTATION_SCOPE,
)

data class AdminStepUpResponse(val token: String, val expiresAt: Instant, val scope: String)
