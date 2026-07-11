package me.manga.kira.backend.common

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * **Kira Canonical JSON v1 (`kcj-1`)** — the deterministic canonicalization algorithm (PLAN §5,
 * Appendix B S6). *Inspired by* RFC 8785 (JCS) but deliberately NOT claiming full compliance: the
 * source-config model has no floating-point fields (so the RFC's number rules are vacuous) and
 * kotlinx-serialization's compact string escaping is the normative behavior.
 *
 * Why a canonical algorithm at all: Postgres `jsonb` does not preserve key order/whitespace, and
 * kotlinx parses JSON maps into insertion-ordered `LinkedHashMap`s — so the same semantic config
 * authored with different `headers`/`endpoints`/`fields` key orders (or after an innocent
 * data-class field reorder) would otherwise yield different checksum bytes. `kcj-1` removes that
 * nondeterminism:
 *
 *  1. Encode the model with [json] (`encodeDefaults=false; prettyPrint=false; explicitNulls=false`)
 *     to a [JsonElement] — default-omission matches the app's bundled style.
 *  2. Recursively sort **every object's keys** lexicographically by Unicode code point (covers both
 *     data-class field names and map keys — immune to authoring order and model declaration order).
 *     Arrays are **never** reordered (source ordering is a separate normative concern, PLAN §5).
 *  3. Serialize compact: UTF-8, no insignificant whitespace, no trailing newline.
 *
 * The checksum is SHA-256 hex over the canonical UTF-8 bytes ([checksum]). The [CANON_VERSION]
 * identifier is stored with every revision/snapshot so a future algorithm change is explicit and
 * versioned (PLAN §5 `canon_version` columns).
 */
object CanonicalJson {

    /** The canonicalization algorithm identifier persisted in `canon_version` columns (PLAN §5). */
    const val CANON_VERSION: String = "kcj-1"

    /** The kotlinx Json instance (PLAN §3 common/). Default-omitting + compact. */
    // `explicitNulls = false` is @ExperimentalSerializationApi in kotlinx-serialization but is
    // mandated verbatim by PLAN §5 step 1, so the opt-in is deliberate (not a suppression).
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json =
        Json {
            encodeDefaults = false
            prettyPrint = false
            explicitNulls = false
        }

    /** Canonicalize a serializable model to its `kcj-1` string form. */
    fun <T> canonicalize(serializer: SerializationStrategy<T>, value: T): String =
        canonicalize(json.encodeToJsonElement(serializer, value))

    /** Canonicalize an already-parsed [JsonElement] to its `kcj-1` string form. */
    fun canonicalize(element: JsonElement): String =
        json.encodeToString(JsonElement.serializer(), sortKeys(element))

    /** SHA-256 hex over the canonical UTF-8 bytes — the document/revision checksum (PLAN §5). */
    fun checksum(canonicalJson: String): String = Sha256.hexUtf8(canonicalJson)

    private fun sortKeys(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(
                    element.entries
                        .sortedWith(compareBy(CODE_POINT_ORDER) { it.key })
                        .associateTo(LinkedHashMap()) { (key, value) -> key to sortKeys(value) },
                )
            is JsonArray -> JsonArray(element.map { sortKeys(it) })
            else -> element
        }

    /**
     * Lexicographic ordering by Unicode **code point** (PLAN §5). This differs from Kotlin's
     * default `String.compareTo` (which compares UTF-16 code units) only for supplementary/astral
     * characters, which code-unit ordering would misplace. All real source-config keys are ASCII,
     * so this is invariant-protection; it is correct for the general case regardless.
     */
    private val CODE_POINT_ORDER =
        Comparator<String> { a, b ->
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val cpA = a.codePointAt(i)
                val cpB = b.codePointAt(j)
                if (cpA != cpB) return@Comparator cpA.compareTo(cpB)
                i += Character.charCount(cpA)
                j += Character.charCount(cpB)
            }
            (a.length - i).compareTo(b.length - j)
        }
}
