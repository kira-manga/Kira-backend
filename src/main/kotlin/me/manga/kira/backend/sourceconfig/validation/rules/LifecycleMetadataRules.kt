package me.manga.kira.backend.sourceconfig.validation.rules

import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.validation.Findings
import me.manga.kira.backend.sourceconfig.validation.RuleContext
import me.manga.kira.backend.sourceconfig.validation.ValidationCodes
import me.manga.kira.backend.sourceconfig.validation.sourcePath

/**
 * Per-source metadata rules that apply to **every engine**: `siteState` (PLAN §8 rule 7),
 * `lifecycle` (rule 8), the three bare-host lists (rule 9), the icon block (rule 10 + the rule-32c
 * structural URL check for `icon.remoteUrl`), and the advisory icon-key warning (rule 33). Pure.
 */
object LifecycleMetadataRules {

    private val SITE_STATES = setOf("WORKING", "UNDER_MAINTENANCE", "STOPPED", "ADULT_18_PLUS")
    private val LIFECYCLES = setOf("active", "disabled", "removed")
    private val RESOURCE_KEY_REGEX = Regex("[a-z0-9_]{1,64}")

    fun check(source: SourceConfig, ctx: RuleContext, findings: Findings) {
        val base = sourcePath(source.api)

        // Rule 7 — siteState vocabulary.
        if (source.siteState !in SITE_STATES) {
            findings.error(
                ValidationCodes.UNKNOWN_SITE_STATE,
                "$base.siteState",
                "unknown siteState '${source.siteState}'.",
            )
        }
        // Rule 8 — lifecycle vocabulary (the app's 3-state whitelist).
        if (source.lifecycle !in LIFECYCLES) {
            findings.error(
                ValidationCodes.UNKNOWN_LIFECYCLE,
                "$base.lifecycle",
                "unknown lifecycle '${source.lifecycle}'.",
            )
        }

        // Rule 9 — bare hosts in all three host lists.
        checkHosts(source.previousHosts, "$base.previousHosts", findings)
        checkHosts(source.previousImageHosts, "$base.previousImageHosts", findings)
        checkHosts(source.trustedHosts, "$base.trustedHosts", findings)

        // Rule 10 + rule 33 — icon.
        checkIcon(source, ctx, findings, base)
    }

    private fun checkHosts(hosts: List<String>, path: String, findings: Findings) {
        hosts.forEachIndexed { i, host ->
            if (!isBareHost(host)) {
                findings.error(
                    ValidationCodes.HOST_NOT_BARE,
                    "$path[$i]",
                    "must be a bare host (no scheme, path, port, or whitespace).",
                )
            }
        }
    }

    /** Bare host: non-blank, no `://`, no `/`, no `:` (so no port), no whitespace (PLAN §8 rule 9). */
    private fun isBareHost(host: String): Boolean = host.isNotBlank() &&
        !host.contains("://") &&
        !host.contains('/') &&
        !host.contains(':') &&
        host.none { it.isWhitespace() }

    private fun checkIcon(source: SourceConfig, ctx: RuleContext, findings: Findings, base: String) {
        val icon = source.icon ?: return
        val iconPath = "$base.icon"

        // Rule 10 — resourceKey regex (if non-empty).
        if (icon.resourceKey.isNotEmpty() && !RESOURCE_KEY_REGEX.matches(icon.resourceKey)) {
            findings.error(
                ValidationCodes.ICON_RESOURCE_KEY_INVALID,
                "$iconPath.resourceKey",
                "resourceKey must match [a-z0-9_]{1,64}.",
            )
        }
        // Rule 10 — remoteUrl HTTPS-only (if non-empty).
        if (icon.remoteUrl.isNotEmpty() && !icon.remoteUrl.startsWith("https://")) {
            findings.error(
                ValidationCodes.ICON_REMOTE_URL_NOT_HTTPS,
                "$iconPath.remoteUrl",
                "icon remoteUrl must be HTTPS.",
            )
        }
        // Rule 10 — the block must not be empty (at least one of the two set).
        if (icon.resourceKey.isEmpty() && icon.remoteUrl.isEmpty()) {
            findings.error(
                ValidationCodes.ICON_EMPTY,
                iconPath,
                "icon block must set at least one of resourceKey or remoteUrl.",
            )
        }
        // Rule 32c — structural URL check for a non-empty remoteUrl (HTTPS-only is owned by rule 10
        // above, so http/https is accepted here to avoid a duplicate scheme error).
        if (icon.remoteUrl.isNotEmpty()) {
            UrlSafety.check(icon.remoteUrl, "$iconPath.remoteUrl", findings)
        }

        // Rule 33 — advisory: unknown packaged key with no remote fallback → warning (never blocks).
        if (icon.resourceKey.isNotEmpty() &&
            !ctx.iconCatalog.hasKey(icon.resourceKey) &&
            icon.remoteUrl.isEmpty()
        ) {
            findings.warn(
                ValidationCodes.UNKNOWN_ICON_KEY,
                "$iconPath.resourceKey",
                "icon resourceKey '${icon.resourceKey}' is not in the packaged-icon catalog and has no remoteUrl fallback.",
            )
        }
    }
}
