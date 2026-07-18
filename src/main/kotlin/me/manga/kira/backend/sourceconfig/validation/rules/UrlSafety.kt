package me.manga.kira.backend.sourceconfig.validation.rules

import me.manga.kira.backend.sourceconfig.validation.Findings
import me.manga.kira.backend.sourceconfig.validation.ValidationCodes
import java.net.URI

/**
 * Strict absolute-URI safety check for **published** URL VALUES (PLAN §8 rule 32c) — strictly
 * tighter than the app's `startsWith("http")`. Applied to `baseUrl`, non-empty `imageBase`, and
 * non-empty `icon.remoteUrl`. Endpoint `url` **templates** (`{baseUrl}/…`) are NOT parsed here — they
 * are templates, covered by rule 14.
 *
 * Requirements: parses as a real absolute URI; scheme in [allowedSchemes]; non-empty host; **no
 * user-info** (`https://user:pass@host` is the `URL_USERINFO_FORBIDDEN` case); no fragment. A
 * malformed port (non-numeric) fails parsing → `URL_INVALID`. Pure JDK ([URI]); framework-free.
 */
internal object UrlSafety {

    fun check(rawUrl: String, path: String, findings: Findings, allowedSchemes: Set<String> = setOf("http", "https")) {
        val uri =
            try {
                URI(rawUrl)
            } catch (_: Exception) {
                findings.error(ValidationCodes.URL_INVALID, path, "Not a valid absolute URL.")
                return
            }

        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in allowedSchemes) {
            findings.error(
                ValidationCodes.URL_SCHEME_INVALID,
                path,
                "URL scheme must be one of ${allowedSchemes.sorted()}.",
            )
        }
        if (uri.host.isNullOrBlank()) {
            findings.error(ValidationCodes.URL_HOST_MISSING, path, "URL must have a non-empty host.")
        }
        if (uri.userInfo != null) {
            findings.error(ValidationCodes.URL_USERINFO_FORBIDDEN, path, "URL must not contain user-info.")
        }
        if (uri.fragment != null) {
            findings.error(ValidationCodes.URL_FRAGMENT_FORBIDDEN, path, "URL must not contain a fragment.")
        }
    }
}
