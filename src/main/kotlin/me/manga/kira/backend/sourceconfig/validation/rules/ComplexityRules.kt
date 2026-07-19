package me.manga.kira.backend.sourceconfig.validation.rules

import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.validation.Findings
import me.manga.kira.backend.sourceconfig.validation.ValidationCodes
import me.manga.kira.backend.sourceconfig.validation.sourcePath

/**
 * Fail-closed resource-safety limits for source documents.
 *
 * The HTTP body limit bounds bytes, but a compact JSON document can still contain enough tiny
 * collection entries to make semantic validation disproportionately expensive. These limits are
 * intentionally well above the shipping catalog and are checked before the detailed rules walk a
 * document. Invalid oversized objects are not probed further.
 */
object ComplexityRules {
    const val MAX_SOURCES = 512
    const val MAX_COLLECTION_ENTRIES = 256
    const val MAX_FILTERS = 128
    const val MAX_SOURCE_COMPLEXITY = 20_000L

    fun document(document: SourceConfigDocument, findings: Findings): Boolean {
        if (document.sources.size <= MAX_SOURCES) return true
        findings.error(
            ValidationCodes.DOCUMENT_COMPLEXITY_EXCEEDED,
            "sources",
            "document contains ${document.sources.size} sources; maximum is $MAX_SOURCES.",
        )
        return false
    }

    fun source(source: SourceConfig, findings: Findings): Boolean {
        val base = sourcePath(source.api)
        var valid = true

        fun bounded(path: String, size: Int, maximum: Int = MAX_COLLECTION_ENTRIES) {
            if (size > maximum) {
                findings.error(
                    ValidationCodes.SOURCE_COMPLEXITY_EXCEEDED,
                    "$base.$path",
                    "collection contains $size entries; maximum is $maximum.",
                )
                valid = false
            }
        }

        bounded("headers", source.headers.size)
        bounded("endpoints", source.endpoints.size, 16)
        bounded("fields", source.fields.size)
        bounded("blacklistGenres", source.blacklistGenres.size)
        bounded("previousHosts", source.previousHosts.size)
        bounded("previousImageHosts", source.previousImageHosts.size)
        bounded("trustedHosts", source.trustedHosts.size)
        bounded("filters", source.filters.size, MAX_FILTERS)

        var complexity =
            source.headers.size.toLong() + source.endpoints.size + source.fields.size +
                source.blacklistGenres.size + source.previousHosts.size +
                source.previousImageHosts.size + source.trustedHosts.size + source.filters.size

        source.endpoints.values.take(MAX_COLLECTION_ENTRIES).forEach { endpoint ->
            bounded("endpoints[].rootDirs", endpoint.rootDirs.size)
            bounded("endpoints[].formBody", endpoint.formBody.size)
            bounded("endpoints[].listFilters", endpoint.listFilters.size)
            complexity += endpoint.rootDirs.size + endpoint.formBody.size + endpoint.listFilters.size
        }
        source.fields.values.take(MAX_COLLECTION_ENTRIES).forEach { field ->
            bounded("fields[].fallbackSelectors", field.fallbackSelectors.size)
            bounded("fields[].lazyAttrChain", field.lazyAttrChain.size)
            bounded("fields[].vars", field.vars.size)
            bounded("fields[].transform", field.transform.size)
            complexity += field.fallbackSelectors.size + field.lazyAttrChain.size + field.vars.size + field.transform.size
            field.transform.take(MAX_COLLECTION_ENTRIES).forEach { transform ->
                bounded("fields[].transform[].args", transform.args.size)
                bounded("fields[].transform[].list", transform.list.size)
                complexity += transform.args.size + transform.list.size
            }
        }
        source.filters.take(MAX_FILTERS).forEach { filter ->
            bounded("filters[].options", filter.options.size)
            bounded("filters[].defaults", filter.defaults.size)
            bounded("filters[].visibleWhen", filter.visibleWhen.size)
            bounded("filters[].appliesTo", filter.appliesTo.size, 16)
            complexity += filter.options.size + filter.defaults.size + filter.visibleWhen.size + filter.appliesTo.size
            filter.visibleWhen.take(MAX_COLLECTION_ENTRIES).forEach { condition ->
                bounded("filters[].visibleWhen[].anyOf", condition.anyOf.size)
                complexity += condition.anyOf.size
            }
        }

        if (complexity > MAX_SOURCE_COMPLEXITY) {
            findings.error(
                ValidationCodes.SOURCE_COMPLEXITY_EXCEEDED,
                base,
                "source complexity is $complexity entries; maximum is $MAX_SOURCE_COMPLEXITY.",
            )
            valid = false
        }
        return valid
    }
}
