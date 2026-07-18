package me.manga.kira.backend.user.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import me.manga.kira.backend.security.AuthenticatedUser
import me.manga.kira.backend.security.ClientIpResolver
import me.manga.kira.backend.user.api.dto.LoginRequest
import me.manga.kira.backend.user.api.dto.LoginResponse
import me.manga.kira.backend.user.api.dto.MeResponse
import me.manga.kira.backend.user.api.dto.RegisterRequest
import me.manga.kira.backend.user.api.dto.RegisterResponse
import me.manga.kira.backend.user.application.AuthService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Auth endpoints (PLAN §4.2). `register`/`login` are public (security matrix); `me` requires a
 * bearer token. The client IP for throttling is resolved via [ClientIpResolver] (trusted-proxy
 * aware — PLAN §6), never trusted from a raw forwarding header.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val authService: AuthService, private val clientIpResolver: ClientIpResolver) {
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterRequest, httpRequest: HttpServletRequest): RegisterResponse {
        val user = authService.register(request.email, request.password, clientIpResolver.resolve(httpRequest))
        return RegisterResponse(id = user.id, email = user.email, role = user.role)
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest, httpRequest: HttpServletRequest): LoginResponse {
        val result = authService.login(request.email, request.password, clientIpResolver.resolve(httpRequest))
        return LoginResponse(
            accessToken = result.token.value,
            tokenType = "Bearer",
            expiresInSeconds = result.token.expiresInSeconds,
            role = result.role,
        )
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: AuthenticatedUser): MeResponse = MeResponse(
        id = principal.id,
        email = principal.email,
        role = principal.role,
        createdAt = principal.createdAt,
    )
}
