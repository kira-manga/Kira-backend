package me.manga.kira.backend.sourceconfig.validation

import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.validation.rules.DocumentRules
import me.manga.kira.backend.sourceconfig.validation.rules.EndpointRules
import me.manga.kira.backend.sourceconfig.validation.rules.FieldRules
import me.manga.kira.backend.sourceconfig.validation.rules.FilterRules
import me.manga.kira.backend.sourceconfig.validation.rules.LifecycleMetadataRules
import me.manga.kira.backend.sourceconfig.validation.rules.SourceRules

/**
 * The pure `1:1` mirror of the app's `DefaultSourceConfigValidator` (PLAN §8), plus the
 * server-additional rules 31–33. **FRAMEWORK-FREE (PLAN §3):** kotlin-stdlib only (plus the pure
 * model) — no Spring/JPA/Jackson — so it extracts verbatim into the future shared module.
 *
 * Rules live as small pure classes grouped by concern ([DocumentRules], [SourceRules],
 * [EndpointRules], [FieldRules], [FilterRules], [LifecycleMetadataRules]), each writing into a
 * [Findings] sink, composed here (PLAN §8 "Extraction posture"). Errors are **collected, not
 * fail-fast** (except the rule-1 schema-version gate, which stops probing further), and acceptance
 * is **all-or-nothing** per document — exactly like the app.
 *
 * Rule scope (this pure layer, PLAN §15.4): document rules 1–2; per-source rules 3–27 and 31–33.
 * Rules 28–30 (lifecycle transitions, publish gate, revision monotonicity) are **service-layer**
 * concerns (PLAN §9, Phases 5/6) and are deliberately NOT here.
 *
 * @param strategies the server mirror of the app's strategy registry (PLAN §8 rules 12/15).
 * @param iconCatalog the packaged-icon catalog for the advisory icon warning (PLAN §8 rule 33).
 * @param publicHeaderPlaceholderValues the allowlist of non-secret sensitive-header values
 *   (PLAN §8 rule 32b; bound from `kira.validation.public-header-placeholder-values`, default
 *   exactly `["Bearer null"]`). Passed in because this layer cannot read Spring properties.
 */
class SourceConfigValidator(
    private val strategies: StrategyCatalog = ServerStrategyCatalog(),
    private val iconCatalog: PackagedIconCatalog = PackagedIconCatalog(),
    publicHeaderPlaceholderValues: Set<String> = setOf(DEFAULT_HEADER_PLACEHOLDER),
) {
    private val ctx =
        RuleContext(
            strategies = strategies,
            iconCatalog = iconCatalog,
            publicHeaderPlaceholderValues = publicHeaderPlaceholderValues,
        )

    /** Validate a whole document (PLAN §8): rule 1 (schema gate) → rule 2 (unique api) → per source. */
    fun validate(document: SourceConfigDocument): ValidationResult {
        val findings = Findings()

        // Rule 1 is the ONLY fail-fast gate: a wrong schemaVersion stops all further probing.
        if (!DocumentRules.schemaVersion(document, findings)) {
            return ValidationResult.of(findings.errors, findings.warnings)
        }

        // Rule 2 — unique api across the document (reported once per duplicated api, doc-level).
        DocumentRules.uniqueApis(document, findings)

        // Per-source semantic rules (3–27, 31–33). Duplicate-api is NOT re-checked per source here
        // (rule 2 above owns it) — avoids double reporting.
        for (source in document.sources) {
            collectSourceFindings(source, findings)
        }

        return ValidationResult.of(findings.errors, findings.warnings)
    }

    /**
     * Validate ONE stanza in candidate-document context (PLAN §8 signature). [otherApis] is the set
     * of every OTHER source's api already in the candidate document — used to enforce rule 2
     * (`DUPLICATE_API`) for a single-stanza validate/publish preview. Returns errors only (the app
     * signature); warnings surface only through [validate].
     */
    fun validateSource(
        source: SourceConfig,
        otherApis: Set<String>,
    ): List<ValidationError> {
        val findings = Findings()
        if (source.api in otherApis) {
            findings.error(
                ValidationCodes.DUPLICATE_API,
                sourcePath(source.api),
                "Duplicate api '${source.api}' — api must be unique within the document.",
            )
        }
        collectSourceFindings(source, findings)
        return findings.errors
    }

    /** Compose every per-source rule group for one stanza into [findings]. */
    private fun collectSourceFindings(
        source: SourceConfig,
        findings: Findings,
    ) {
        val isGeneric = source.engine == "generic"

        // All engines: identity (3–6), secret-safety (32), metadata (7–10), icon advisory (33).
        SourceRules.check(source, ctx, findings)
        LifecycleMetadataRules.check(source, ctx, findings)

        if (isGeneric) {
            // Generic engine: pagination (12), endpoints (13/14/31), fields (15), filters (16–27).
            EndpointRules.check(source, ctx, findings)
            FieldRules.check(source, ctx, findings)
            FilterRules.check(source, ctx, findings)
        } else {
            // Non-generic (rule 11): filters must be empty; strategy/endpoint/field checks skipped.
            if (source.filters.isNotEmpty()) {
                findings.error(
                    ValidationCodes.FILTERS_NOT_ALLOWED_FOR_ENGINE,
                    "${sourcePath(source.api)}.filters",
                    "filters are a generic-engine capability; engine '${source.engine}' must not declare filters.",
                )
            }
        }
    }

    companion object {
        const val DEFAULT_HEADER_PLACEHOLDER = "Bearer null"
    }
}

/**
 * Mutable sink both errors and warnings accumulate into (PLAN §8 "Extraction posture" — each rule
 * group is `(input, ctx) -> findings`). A sink rather than a returned `List<ValidationError>` because
 * rule 33 emits a [ValidationWarning] alongside the blocking errors.
 */
class Findings {
    private val _errors = mutableListOf<ValidationError>()
    private val _warnings = mutableListOf<ValidationWarning>()

    val errors: List<ValidationError> get() = _errors
    val warnings: List<ValidationWarning> get() = _warnings

    fun error(
        code: String,
        path: String,
        message: String,
    ) {
        _errors += ValidationError(code, path, message)
    }

    fun warn(
        code: String,
        path: String,
        message: String,
    ) {
        _warnings += ValidationWarning(code, path, message)
    }
}

/** Immutable per-validation context handed to every rule group (PLAN §8). */
data class RuleContext(
    val strategies: StrategyCatalog,
    val iconCatalog: PackagedIconCatalog,
    val publicHeaderPlaceholderValues: Set<String>,
)

/** The normative pinpoint prefix for a source (PLAN §8 error `path`), e.g. `sources[Azora]`. */
fun sourcePath(api: String): String = "sources[$api]"
