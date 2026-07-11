package me.manga.kira.backend.sourceconfig.validation.rules

import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.validation.Findings
import me.manga.kira.backend.sourceconfig.validation.RuleContext
import me.manga.kira.backend.sourceconfig.validation.ValidationCodes
import me.manga.kira.backend.sourceconfig.validation.sourcePath

/**
 * Field-spec strategy-reference rules for **generic** sources (PLAN §8 rule 15): every
 * `transform[].fn` must be in the transform whitelist; a non-empty `dateStrategy` must be in the
 * date-strategy whitelist; ANY `imageStrategy` is rejected (the app's image-strategy set is
 * intentionally EMPTY — fail-closed). Pure.
 */
object FieldRules {

    fun check(
        source: SourceConfig,
        ctx: RuleContext,
        findings: Findings,
    ) {
        val base = sourcePath(source.api)
        for ((key, field) in source.fields) {
            val path = "$base.fields[$key]"

            field.transform.forEachIndexed { i, transform ->
                if (!ctx.strategies.hasTransform(transform.fn)) {
                    findings.error(
                        ValidationCodes.UNKNOWN_TRANSFORM,
                        "$path.transform[$i].fn",
                        "unknown transform '${transform.fn}'.",
                    )
                }
            }

            if (field.dateStrategy.isNotEmpty() && !ctx.strategies.hasDateStrategy(field.dateStrategy)) {
                findings.error(
                    ValidationCodes.UNKNOWN_DATE_STRATEGY,
                    "$path.dateStrategy",
                    "unknown dateStrategy '${field.dateStrategy}'.",
                )
            }

            if (field.imageStrategy.isNotEmpty()) {
                // The whitelist is empty by design; hasImageStrategy is always false.
                findings.error(
                    ValidationCodes.IMAGE_STRATEGY_NOT_ALLOWED,
                    "$path.imageStrategy",
                    "imageStrategy is not supported (the image-strategy whitelist is empty).",
                )
            }
        }
    }
}
