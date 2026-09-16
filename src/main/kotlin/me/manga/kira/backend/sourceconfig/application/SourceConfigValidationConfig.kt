package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.config.KiraValidationProperties
import me.manga.kira.backend.sourceconfig.validation.PackagedIconCatalog
import me.manga.kira.backend.sourceconfig.validation.ServerStrategyCatalog
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the pure [SourceConfigValidator] (PLAN §8) as a Spring bean, binding the
 * `kira.validation.public-header-placeholder-values` allowlist (PLAN §8 rule 32b, default
 * `["Bearer null"]`) that the framework-free validator takes as a constructor param (it cannot read
 * Spring properties itself — PLAN §3). The `rules-version` stamped on stored validation results is
 * exposed here too so the service and the validator stay in one place (PLAN §5 `rules_version`).
 */
@Configuration
class SourceConfigValidationConfig {

    @Bean
    fun sourceConfigValidator(properties: KiraValidationProperties): SourceConfigValidator = SourceConfigValidator(
        strategies = ServerStrategyCatalog(),
        iconCatalog = PackagedIconCatalog(),
        publicHeaderPlaceholderValues = properties.publicHeaderPlaceholderValues.toSet(),
    )

    companion object {
        /** Lets a stored "valid" be recognized as stale after a rule change (PLAN §5 `rules_version`). */
        const val RULES_VERSION = "schema1/rules-2026.07"
    }
}
