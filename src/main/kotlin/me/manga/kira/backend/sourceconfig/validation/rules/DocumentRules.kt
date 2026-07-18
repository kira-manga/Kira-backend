package me.manga.kira.backend.sourceconfig.validation.rules

import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.validation.Findings
import me.manga.kira.backend.sourceconfig.validation.ValidationCodes
import me.manga.kira.backend.sourceconfig.validation.sourcePath

/** Document-level rules (PLAN §8 rules 1–2). Pure. */
object DocumentRules {
    /** `SUPPORTED_SCHEMA_VERSION` — mirrors the app constant (PLAN §8 rule 1). */
    const val SUPPORTED_SCHEMA_VERSION = 1

    /**
     * Rule 1 — `schemaVersion` must equal [SUPPORTED_SCHEMA_VERSION]; otherwise fail immediately
     * WITHOUT probing further (the sole fail-fast gate). Returns `true` when the version is fine.
     */
    fun schemaVersion(document: SourceConfigDocument, findings: Findings): Boolean {
        if (document.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            findings.error(
                ValidationCodes.UNSUPPORTED_SCHEMA_VERSION,
                "schemaVersion",
                "schemaVersion must be $SUPPORTED_SCHEMA_VERSION (got ${document.schemaVersion}).",
            )
            return false
        }
        return true
    }

    /**
     * Rule 2 — `api` unique across the document. Reported once per duplicated api (doc-level). The
     * server also enforces this via the `uq_source_configs_api` DB constraint (PLAN §8 rule 2).
     */
    fun uniqueApis(document: SourceConfigDocument, findings: Findings) {
        val seen = mutableSetOf<String>()
        val reported = mutableSetOf<String>()
        for (source in document.sources) {
            if (!seen.add(source.api) && reported.add(source.api)) {
                findings.error(
                    ValidationCodes.DUPLICATE_API,
                    sourcePath(source.api),
                    "Duplicate api '${source.api}' — api must be unique within the document.",
                )
            }
        }
    }
}
