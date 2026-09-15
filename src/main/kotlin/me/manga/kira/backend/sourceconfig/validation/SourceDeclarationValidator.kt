package me.manga.kira.backend.sourceconfig.validation

import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig

/**
 * Required declaration-capability port, implemented outside the framework-free validation layer.
 * Findings use the same source-prefixed paths as the other rule groups. The implementation must
 * honor [strategies], not substitute a different registry for field-variable pipe checks.
 *
 * The caller owns schema, complexity, lifecycle, URL, header and filter-definition validation; this
 * check augments those rules rather than replacing them. There is deliberately no no-op default.
 */
fun interface SourceDeclarationValidator {
    fun validate(source: SourceConfig, strategies: StrategyCatalog): List<ValidationError>
}
