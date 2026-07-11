package me.manga.kira.backend.sourceconfig.parsing

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamReadException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument

/**
 * The two deliberate inbound-parsing modes (PLAN §7) plus canonical outbound. The app's leniency is
 * right for CONSUMING config and wrong for AUTHORING it, so:
 *
 * - **STRICT authoring parser** — `POST /admin/sources`, `POST /admin/sources/{api}/revisions` (and
 *   any future authoring input). kotlinx `Json { ignoreUnknownKeys = false; isLenient = false }`
 *   preceded by a **Jackson structural pre-pass** (`STRICT_DUPLICATE_DETECTION` +
 *   `FAIL_ON_TRAILING_TOKENS`, parse-and-discard) because kotlinx-serialization has no duplicate-key
 *   or trailing-garbage switch. Net effect: an authoring typo like `usesCaptureHeaders` (for
 *   `usesCapturedHeaders`) is a 400 with the offending key named, never a silently-ignored no-op.
 * - **COMPATIBILITY import parser** — `import-bundled` ONLY (PLAN §12.2). Mirrors the app's
 *   `SourceConfigParser` (`ignoreUnknownKeys = true; isLenient = true`) so the real bundled document
 *   imports exactly as the app reads it.
 *
 * **Design decision (parser architecture, plan gave latitude):** this lives in a dedicated
 * `sourceconfig/parsing` package — NOT in `domain`/`validation`, which must stay framework-free
 * (PLAN §3) — precisely because the STRICT mode depends on Jackson. It is a plain stateless `object`
 * instantiable/usable directly by later phases (no Spring bean required). The two modes share the
 * same [SourceConfig]/[SourceConfigDocument] serializers and differ only in the two [Json] configs;
 * STRICT additionally runs the Jackson pre-pass. Rollback does NOT re-parse (it copies an
 * already-stored validated model), so no parser applies there (PLAN §7).
 *
 * **Log-hygiene (PLAN §6):** rejection messages name the offending *key/token* and JSON path but
 * NEVER echo submitted values — the raw JSON snippet that kotlinx/Jackson append to their messages
 * is stripped by [sanitize] before it can reach the client `detail` or a log line.
 *
 * Canonical outbound reuses [CanonicalJson] (`kcj-1`, PLAN §5) — the algorithm is defined once in
 * the common layer and is NOT re-implemented here.
 */
object SourceConfigParser {

    /** STRICT authoring: reject unknown keys and non-lenient JSON (PLAN §7). */
    val strictJson: Json =
        Json {
            ignoreUnknownKeys = false
            isLenient = false
        }

    /** COMPATIBILITY import: exactly the app's `SourceConfigParser` settings (PLAN §7). */
    val compatibilityJson: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Jackson mapper for the STRICT structural pre-pass: rejects duplicate object keys and trailing
     * garbage after the top-level value — neither of which kotlinx-serialization can detect (PLAN §7,
     * Appendix A #8). Used parse-and-discard purely for structural validation.
     */
    private val strictStructureMapper: ObjectMapper =
        ObjectMapper(
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build(),
        ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    private const val CODE_MALFORMED = "MALFORMED_CONFIG_JSON"
    private const val MAX_DETAIL_LENGTH = 300

    // --- STRICT authoring ---

    /** Parse a single authoring [SourceConfig] strictly (PLAN §7 admin authoring). */
    fun parseStrictSource(json: String): SourceConfig =
        strictDecode(json, SourceConfig.serializer())

    /** Parse a whole authoring [SourceConfigDocument] strictly. */
    fun parseStrictDocument(json: String): SourceConfigDocument =
        strictDecode(json, SourceConfigDocument.serializer())

    // --- COMPATIBILITY import ---

    /** Parse a bundled [SourceConfigDocument] leniently, exactly as the app does (PLAN §12.2). */
    fun parseCompatibleDocument(json: String): SourceConfigDocument =
        compatibilityDecode(json, SourceConfigDocument.serializer())

    /** Parse a single [SourceConfig] leniently (compatibility mode). */
    fun parseCompatibleSource(json: String): SourceConfig =
        compatibilityDecode(json, SourceConfig.serializer())

    // --- Canonical outbound (delegates to the common kcj-1 implementation) ---

    /** Canonical `kcj-1` bytes of a document (PLAN §5). */
    fun canonicalDocument(document: SourceConfigDocument): String =
        CanonicalJson.canonicalize(SourceConfigDocument.serializer(), document)

    /** Canonical `kcj-1` bytes of a single stanza (PLAN §5). */
    fun canonicalSource(source: SourceConfig): String =
        CanonicalJson.canonicalize(SourceConfig.serializer(), source)

    private fun <T> strictDecode(
        json: String,
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T {
        // 1) Structural pre-pass: duplicate keys / trailing garbage / malformed JSON.
        try {
            strictStructureMapper.readValue(json, Any::class.java)
        } catch (ex: StreamReadException) {
            throw BadRequestException(
                sanitize(ex.originalMessage ?: "Malformed JSON.") +
                    locationSuffix(ex.location?.lineNr, ex.location?.columnNr),
                code = CODE_MALFORMED,
            )
        } catch (ex: com.fasterxml.jackson.databind.DatabindException) {
            throw BadRequestException(sanitize(ex.originalMessage ?: "Malformed JSON."), code = CODE_MALFORMED)
        }
        // 2) kotlinx strict decode: unknown keys / type mismatches / missing required fields.
        return try {
            strictJson.decodeFromString(serializer, json)
        } catch (ex: SerializationException) {
            throw BadRequestException(sanitize(ex.message ?: "Invalid source config."), code = CODE_MALFORMED)
        }
    }

    private fun <T> compatibilityDecode(
        json: String,
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T =
        try {
            compatibilityJson.decodeFromString(serializer, json)
        } catch (ex: SerializationException) {
            throw BadRequestException(sanitize(ex.message ?: "Invalid source config."), code = CODE_MALFORMED)
        }

    /**
     * Strip the raw-input snippet kotlinx/Jackson append to their messages (everything from a
     * `JSON input:` marker on), collapse control characters, and bound the length — so the offending
     * key/token/path is surfaced but no submitted value is ever echoed (PLAN §6).
     */
    private fun sanitize(raw: String): String {
        val snippetMarker = raw.indexOf("\nJSON input:")
        val trimmed = if (snippetMarker >= 0) raw.substring(0, snippetMarker) else raw
        val collapsed = trimmed.replace(Regex("[\\p{Cntrl}]+"), " ").trim()
        return if (collapsed.length > MAX_DETAIL_LENGTH) collapsed.take(MAX_DETAIL_LENGTH) + "…" else collapsed
    }

    private fun locationSuffix(
        line: Int?,
        column: Int?,
    ): String = if (line != null && line > 0) " (line $line, column ${column ?: 0})" else ""
}
