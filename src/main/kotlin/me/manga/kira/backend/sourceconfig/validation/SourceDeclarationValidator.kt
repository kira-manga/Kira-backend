package me.manga.kira.backend.sourceconfig.validation

import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig

/**
 * Required declaration-capability port, implemented outside this framework-free validation layer.
 * Findings use source-prefixed paths and the caller's strategy registry. The caller still owns
 * schema/complexity, lifecycle, URL, header and filter safety; there is deliberately no no-op default.
 */
fun interface SourceDeclarationValidator {
    fun validate(source: SourceConfig, strategies: StrategyCatalog): List<ValidationError>
}
