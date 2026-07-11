package me.manga.kira.backend.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * springdoc OpenAPI metadata (PLAN §3 config/, §13 "Foundation now"). Declares the API info and a
 * reusable HTTP-bearer (JWT) security scheme so the eventual admin/auth operations can reference
 * it. This is documentation metadata only — it enforces nothing; the actual authorization rules
 * live in the SecurityFilterChain (Phase 3), and dev-vs-prod exposure of `/swagger-ui`/`/v3/api-docs`
 * is a property-gated security concern (PLAN §5 matrix), also Phase 3.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun kiraOpenApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("kira-backend API")
                    .version(API_VERSION)
                    .description(
                        "Remote authority for the Kira Manga source-configuration document, " +
                            "plus JWT auth, admin, and completion foundations. See docs/PLAN.md.",
                    ),
            ).components(
                Components().addSecuritySchemes(
                    BEARER_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT access token from POST /api/v1/auth/login (added in a later phase)."),
                ),
            )

    companion object {
        const val API_VERSION = "v1"
        const val BEARER_SCHEME = "bearerAuth"
    }
}
