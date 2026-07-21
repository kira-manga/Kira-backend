package me.manga.kira.backend.security

import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.user.domain.UserRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * The security composition root (PLAN §6). Stateless bearer-token API: sessions STATELESS, CSRF
 * off, no CORS in v1, HTTP Basic / form login disabled. Authorization follows the §6 matrix.
 *
 *  - **Decoder** ([jwtDecoder]): `NimbusJwtDecoder.withSecretKey(...)` (HS256) with explicit
 *    validators — timestamp (`exp`/`nbf`, 60s skew), issuer, and audience (PLAN §6).
 *  - **Converter** ([DbUserJwtAuthenticationConverter]): registered on `oauth2ResourceServer { jwt {} }`
 *    so the per-request DB enabled-check + DB-derived authorities run on EVERY protected endpoint.
 *  - **401/403** flow through the same problem+json envelope ([ProblemAuthenticationEntryPoint] /
 *    [ProblemAccessDeniedHandler]).
 *  - **Swagger** (`/swagger-ui`, `/v3/api-docs`) is exposed in the `dev` profile and requires ADMIN
 *    otherwise (PLAN §6 matrix, property/profile-gated).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(private val environment: Environment) {
    /**
     * `DelegatingPasswordEncoder` with `{bcrypt}` as the initial id (PLAN §5/§6) — the stored hash
     * carries its `{id}` prefix, so migrating to Argon2 later is a config change, not a schema one.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    /** HS256 decoder + explicit signature/exp/nbf/issuer/audience validation with 60s skew (PLAN §6). */
    @Bean
    fun jwtDecoder(keyProvider: JwtKeyProvider, properties: KiraSecurityProperties): JwtDecoder {
        val decoder =
            NimbusJwtDecoder
                .withSecretKey(keyProvider.secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build()
        val audienceValidator =
            JwtClaimValidator<List<String>?>(JwtClaimNames.AUD) { aud -> aud?.contains(properties.audience) == true }
        val validators: List<OAuth2TokenValidator<Jwt>> =
            listOf(
                JwtTimestampValidator(properties.clockSkew),
                JwtIssuerValidator(properties.issuer),
                audienceValidator,
            )
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(validators))
        return decoder
    }

    @Bean
    @Suppress("LongMethod")
    fun securityFilterChain(
        http: HttpSecurity,
        users: UserRepository,
        entryPoint: ProblemAuthenticationEntryPoint,
        deniedHandler: ProblemAccessDeniedHandler,
        properties: KiraSecurityProperties,
        corsConfigurationSource: CorsConfigurationSource,
    ): SecurityFilterChain {
        val converter = DbUserJwtAuthenticationConverter(users)
        val devProfile = environment.activeProfiles.contains("dev")

        http {
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            if (properties.allowedOrigins.isEmpty()) {
                cors { disable() }
            } else {
                cors { configurationSource = corsConfigurationSource }
            }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }

            authorizeHttpRequests {
                // Framework error dispatch — must be reachable so 404/500 render as the problem envelope.
                authorize("/error", permitAll)

                // Actuator: only liveness/readiness/health are exposed and public (status only, §6).
                authorize("/actuator/health", permitAll)
                authorize("/actuator/health/**", permitAll)
                // In production this is served only on the internal management port (9090), which
                // is not routed by the public ingress and is network-policy restricted to monitoring.
                authorize("/actuator/prometheus", permitAll)

                // Public, read-only app config surface (controllers arrive in Phase 7).
                authorize(HttpMethod.GET, "/api/v1/source-config/**", permitAll)
                authorize(HttpMethod.GET, "/api/v1/sources/**", permitAll)
                authorize(HttpMethod.GET, "/api/v1/tutorial-categories", permitAll)
                authorize(HttpMethod.GET, "/api/v1/tutorials/**", permitAll)
                authorize(HttpMethod.GET, "/api/v1/tutorial-media/**", permitAll)

                // Auth: register/login open; me requires a token.
                authorize(HttpMethod.POST, "/api/v1/auth/register", permitAll)
                authorize(HttpMethod.POST, "/api/v1/auth/login", permitAll)
                authorize("/api/v1/auth/me", authenticated)

                // Completions: authenticated (controller arrives in Phase 9; the 401 gate is here now).
                authorize("/api/v1/completions/**", authenticated)

                // Admin (incl. /admin/users): ADMIN only.
                authorize("/api/v1/admin/**", hasRole("ADMIN"))

                // OpenAPI: open in dev, ADMIN-only otherwise (§6 matrix, profile-gated).
                val swaggerAccess = if (devProfile) permitAll else hasRole("ADMIN")
                authorize("/swagger-ui.html", swaggerAccess)
                authorize("/swagger-ui/**", swaggerAccess)
                authorize("/v3/api-docs/**", swaggerAccess)
                authorize("/v3/api-docs.yaml", swaggerAccess)

                authorize(anyRequest, authenticated)
            }

            oauth2ResourceServer {
                authenticationEntryPoint = entryPoint
                jwt { jwtAuthenticationConverter = converter }
            }

            exceptionHandling {
                authenticationEntryPoint = entryPoint
                accessDeniedHandler = deniedHandler
            }
        }
        return http.build()
    }

    @Bean
    fun corsConfigurationSource(properties: KiraSecurityProperties): CorsConfigurationSource {
        val source = UrlBasedCorsConfigurationSource()
        if (properties.allowedOrigins.isNotEmpty()) {
            val configuration = CorsConfiguration()
            configuration.allowedOrigins = properties.allowedOrigins
            configuration.allowedMethods = listOf("GET", "POST", "OPTIONS")
            configuration.allowedHeaders = listOf("Authorization", "Content-Type", "If-None-Match", "X-Request-Id")
            configuration.exposedHeaders =
                listOf(
                    "ETag",
                    "X-Config-Revision",
                    "X-Config-Checksum",
                    "X-Config-Signature-Format",
                    "X-Config-Signature-Algorithm",
                    "X-Config-Signing-Key-Id",
                    "X-Config-Signature",
                    "X-Config-Previous-Revision",
                    "X-Config-Previous-Checksum",
                    "X-Config-Created-At",
                    "X-Request-Id",
                    "Retry-After",
                )
            configuration.allowCredentials = false
            configuration.maxAge = 3600
            source.registerCorsConfiguration("/api/**", configuration)
        }
        return source
    }
}
