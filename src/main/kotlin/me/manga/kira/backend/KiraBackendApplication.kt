package me.manga.kira.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * `@ConfigurationPropertiesScan` binds the `me.manga.kira.backend.config` `@ConfigurationProperties`
 * classes (`KiraSecurityProperties`, `KiraCompletionProperties`, `KiraAdminSeedProperties` — PLAN
 * §3 / §15.2). Their consuming beans arrive in later phases; Phase 2 only establishes the validated
 * config surface.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class KiraBackendApplication

fun main(args: Array<String>) {
    runApplication<KiraBackendApplication>(*args)
}
