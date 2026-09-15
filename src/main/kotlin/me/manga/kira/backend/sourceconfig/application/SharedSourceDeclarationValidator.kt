package me.manga.kira.backend.sourceconfig.application

import kotlinx.serialization.json.Json
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.validation.SourceDeclarationValidator
import me.manga.kira.backend.sourceconfig.validation.StrategyCatalog
import me.manga.kira.backend.sourceconfig.validation.ValidationError
import me.manga.kira.backend.sourceconfig.validation.sourcePath
import me.manga.kira.source.contracts.StrategyRegistry
import me.manga.kira.source.engine.SourceDeclarationCapabilities
import me.manga.kira.source.contracts.model.SourceConfig as SharedSourceConfig

/**
 * Adapts the complete Backend model to the shared executor's declaration checker. Explicit defaults
 * avoid inheriting different shared defaults; strict decoding rejects unrecognized Backend fields.
 * This is model conversion, NOT kcj-1 canonicalization or a serialized-byte parity claim: map order
 * is preserved because the checker's ordinal form/variable paths refer to declaration order.
 *
 * Only structured machine findings cross back; no display-message parsing, parser exception text,
 * requests or provider execution is involved. Existing server-only rules remain in the caller.
 */
class SharedSourceDeclarationValidator : SourceDeclarationValidator {
    private val modelJson = Json { encodeDefaults = true }

    override fun validate(source: SourceConfig, strategies: StrategyCatalog): List<ValidationError> {
        val sharedSource = modelJson.decodeFromJsonElement(
            SharedSourceConfig.serializer(),
            modelJson.encodeToJsonElement(SourceConfig.serializer(), source),
        )
        val sharedStrategies = object : StrategyRegistry {
            override fun hasTransform(name: String): Boolean = strategies.hasTransform(name)

            override fun hasImageStrategy(name: String): Boolean = strategies.hasImageStrategy(name)

            override fun hasDateStrategy(name: String): Boolean = strategies.hasDateStrategy(name)

            override fun hasPagination(name: String): Boolean = strategies.hasPagination(name)
        }
        return SourceDeclarationCapabilities(sharedStrategies).validate(sharedSource).map { finding ->
            ValidationError(
                code = finding.code,
                path = "${sourcePath(source.api)}.${finding.path}",
                message = finding.message,
            )
        }
    }
}
