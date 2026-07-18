package me.manga.kira.backend.sourceconfig.validation

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.domain.model.EndpointSpec
import me.manga.kira.backend.sourceconfig.domain.model.FieldSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterConditionSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterDefinition
import me.manga.kira.backend.sourceconfig.domain.model.FilterOptionSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterRequestSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterSpec
import me.manga.kira.backend.sourceconfig.domain.model.IconSpec
import me.manga.kira.backend.sourceconfig.domain.model.PaginationSpec
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.domain.model.TransformSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 8b — `ContractInventoryTest`. Parity protection for the mirrored contract
 * (Appendix A #21): the exact field-name inventory of every §7 data class (via serialization), the
 * default values, the enum vocabularies (siteState/lifecycle/filter types/targets/encodes/methods/
 * formats), and the four strategy whitelists (transform / date / pagination / **empty** image). Any
 * drift from the app's `SourceConfig.kt` / `DefaultStrategyRegistry` fails loudly here.
 */
class ContractInventoryTest {

    // encodeDefaults=true so EVERY field (incl. defaulted + null) appears — pins the full inventory.
    private val json = Json { encodeDefaults = true }
    private val validator = SourceConfigValidator()

    private fun <T> keysOf(serializer: SerializationStrategy<T>, value: T): Set<String> = json.encodeToJsonElement(serializer, value).jsonObject.keys

    private fun genCodes(source: SourceConfig): Set<String> = validator.validate(SourceConfigFixtures.document(source)).errors.map { it.code }.toSet()

    // --- Field-name inventory (§7) ---

    @Test
    fun `SourceConfigDocument fields`() {
        assertEquals(
            setOf("schemaVersion", "generatedAt", "revision", "sources"),
            keysOf(SourceConfigDocument.serializer(), SourceConfigDocument(schemaVersion = 1)),
        )
    }

    @Test
    fun `SourceConfig fields`() {
        assertEquals(
            setOf(
                "api", "language", "displayName", "baseUrl", "imageBase", "enabled", "priority",
                "engine", "minAppVersion", "headers", "usesCapturedHeaders", "pagination", "endpoints",
                "fields", "blacklistGenres", "siteState", "lifecycle", "previousHosts",
                "previousImageHosts", "trustedHosts", "icon", "filters",
            ),
            keysOf(SourceConfig.serializer(), SourceConfig(api = "a", language = "l", baseUrl = "b")),
        )
    }

    @Test
    fun `nested spec fields`() {
        assertEquals(setOf("resourceKey", "remoteUrl"), keysOf(IconSpec.serializer(), IconSpec()))
        assertEquals(setOf("type", "param", "start"), keysOf(PaginationSpec.serializer(), PaginationSpec()))
        assertEquals(
            setOf(
                "url", "method", "format", "scriptId", "root", "rootDirs", "listSelector", "formBody", "jsonBody", "listFilters",
                "pageParam", "lastPageLocator",
            ),
            keysOf(EndpointSpec.serializer(), EndpointSpec(url = "u")),
        )
        assertEquals(setOf("path", "op", "value", "mode"), keysOf(FilterSpec.serializer(), FilterSpec(path = "p", op = "equals")))
        assertEquals(
            setOf(
                "path", "selector", "attr", "fallbackPath", "fallbackSelectors", "lazyAttrChain", "template", "vars", "listPath",
                "listSelector", "imageStrategy", "dateStrategy", "transform",
            ),
            keysOf(FieldSpec.serializer(), FieldSpec()),
        )
        assertEquals(setOf("fn", "args", "list"), keysOf(TransformSpec.serializer(), TransformSpec(fn = "trim")))
        assertEquals(
            setOf("id", "label", "type", "options", "default", "defaults", "required", "request", "visibleWhen", "excludeOf", "appliesTo"),
            keysOf(
                FilterDefinition.serializer(),
                FilterDefinition(id = "i", label = "l", type = "text", request = FilterRequestSpec(target = "query", param = "p")),
            ),
        )
        assertEquals(setOf("value", "label"), keysOf(FilterOptionSpec.serializer(), FilterOptionSpec(value = "v")))
        assertEquals(
            setOf("target", "param", "encode", "delimiter", "omitIfEmpty", "trueValue", "falseValue"),
            keysOf(FilterRequestSpec.serializer(), FilterRequestSpec(target = "query", param = "p")),
        )
        assertEquals(setOf("filter", "anyOf"), keysOf(FilterConditionSpec.serializer(), FilterConditionSpec(filter = "f", anyOf = listOf("a"))))
    }

    // --- Default values (§7) ---

    @Test
    fun `SourceConfig defaults mirror the app`() {
        val s = SourceConfig(api = "MyApi", language = "en", baseUrl = "https://x")
        assertEquals("MyApi", s.displayName) // displayName defaults to api
        assertEquals("", s.imageBase)
        assertFalse(s.enabled)
        assertEquals(0, s.priority)
        assertEquals("legacy", s.engine)
        assertEquals(null, s.minAppVersion)
        assertTrue(s.usesCapturedHeaders)
        assertEquals("WORKING", s.siteState)
        assertEquals("active", s.lifecycle)
        assertEquals(PaginationSpec(), s.pagination)
        assertTrue(s.headers.isEmpty() && s.endpoints.isEmpty() && s.fields.isEmpty() && s.filters.isEmpty())
        assertTrue(s.previousHosts.isEmpty() && s.previousImageHosts.isEmpty() && s.trustedHosts.isEmpty())
        assertEquals(null, s.icon)
    }

    @Test
    fun `document, pagination, endpoint, field, filter defaults`() {
        SourceConfigDocument(schemaVersion = 1).let {
            assertEquals(null, it.generatedAt)
            assertEquals(0L, it.revision)
            assertTrue(it.sources.isEmpty())
        }
        PaginationSpec().let {
            assertEquals("page-number", it.type)
            assertEquals("page", it.param)
            assertEquals(1, it.start)
        }
        EndpointSpec(url = "u").let {
            assertEquals("get", it.method)
            assertEquals("", it.format)
        }
        FieldSpec().let { assertEquals("text", it.attr) }
        FilterSpec(path = "p", op = "equals").let {
            assertEquals("", it.value)
            assertEquals("exclude", it.mode)
        }
        FilterOptionSpec(value = "v").let { assertEquals("", it.label) }
        FilterRequestSpec(target = "query", param = "p").let {
            assertEquals("single", it.encode)
            assertEquals(",", it.delimiter)
            assertTrue(it.omitIfEmpty)
            assertEquals("true", it.trueValue)
            assertEquals("", it.falseValue)
        }
        FilterDefinition(id = "i", label = "l", type = "text", request = FilterRequestSpec(target = "query", param = "p")).let {
            assertEquals(listOf("search"), it.appliesTo)
            assertFalse(it.required)
        }
        IconSpec().let {
            assertEquals("", it.resourceKey)
            assertEquals("", it.remoteUrl)
        }
    }

    @Test
    fun `default-valued instance round-trips through canonical omission`() {
        // The app parses key-order-insensitively and defaults omit; a re-parse must be equal.
        val original = SourceConfig(api = "a", language = "l", baseUrl = "https://x", engine = "generic")
        val canonical = me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser.canonicalSource(original)
        val reparsed: SourceConfig = decode(SourceConfig.serializer(), canonical)
        assertEquals(original, reparsed)
    }

    private fun <T> decode(serializer: DeserializationStrategy<T>, text: String): T =
        me.manga.kira.backend.common.CanonicalJson.json.decodeFromString(serializer, text)

    // --- Strategy whitelists (§8 rules 12/15) ---

    @Test
    fun `transform whitelist is exactly the app set`() {
        assertEquals(
            setOf(
                "trim", "lowercase", "uppercase", "strip-html", "clean-html", "regex-replace",
                "regex-extract", "replace", "remove", "prepend", "append", "substring-before",
                "substring-after", "default", "enum-map", "format-number", "decimal",
            ),
            ServerStrategyCatalog.TRANSFORMS,
        )
    }

    @Test
    fun `date, pagination, and image strategy whitelists`() {
        assertEquals(setOf("iso", "epoch-seconds", "epoch-millis"), ServerStrategyCatalog.DATE_STRATEGIES)
        assertEquals(setOf("page-number"), ServerStrategyCatalog.PAGINATION_TYPES)
        assertTrue(ServerStrategyCatalog.IMAGE_STRATEGIES.isEmpty(), "image-strategy whitelist must be empty")
    }

    // --- Packaged-icon catalog (§8 rule 33 — advisory) ---

    @Test
    fun `rule 33 - packaged-icon catalog is the app-sourced set and warns only on unknown keys`() {
        // The catalog is the app SourceIconRegistry key set (read-only inspection, 2026-07-11): 40 keys.
        val catalog = PackagedIconCatalog()
        assertEquals(40, catalog.size)
        assertTrue(catalog.hasKey("azora")) // referenced by the real bundled document
        assertTrue(catalog.hasKey("mangahub")) // packaged-only (not referenced by any current stanza)
        assertTrue(catalog.hasKey("webtoon_tr"))
        assertFalse(catalog.hasKey("definitely_not_a_packaged_icon"))

        // A catalog key → no warning.
        val known =
            validator.validate(
                SourceConfigFixtures.document(
                    SourceConfigFixtures.validGenericSource().copy(icon = IconSpec(resourceKey = "mangahub")),
                ),
            )
        assertTrue(known.warnings.none { it.code == ValidationCodes.UNKNOWN_ICON_KEY })

        // An unknown key (regex-valid) with no remoteUrl fallback → advisory warning, NEVER an error.
        val unknown =
            validator.validate(
                SourceConfigFixtures.document(
                    SourceConfigFixtures.validGenericSource().copy(icon = IconSpec(resourceKey = "not_a_real_key")),
                ),
            )
        assertTrue(unknown.isValid, "an unknown icon key must not block publish (rule 33 is advisory)")
        assertTrue(unknown.warnings.any { it.code == ValidationCodes.UNKNOWN_ICON_KEY })
    }

    // --- Enum vocabularies (behavioral pinning through the validator) ---

    @Test
    fun `siteState vocabulary`() {
        for (v in listOf("WORKING", "UNDER_MAINTENANCE", "STOPPED", "ADULT_18_PLUS")) {
            assertFalse(ValidationCodes.UNKNOWN_SITE_STATE in genCodes(SourceConfigFixtures.validGenericSource().copy(siteState = v)), "expected $v accepted")
        }
        assertTrue(ValidationCodes.UNKNOWN_SITE_STATE in genCodes(SourceConfigFixtures.validGenericSource().copy(siteState = "OTHER")))
    }

    @Test
    fun `lifecycle vocabulary`() {
        for (v in listOf("active", "disabled", "removed")) {
            assertFalse(ValidationCodes.UNKNOWN_LIFECYCLE in genCodes(SourceConfigFixtures.validGenericSource().copy(lifecycle = v)), "expected $v accepted")
        }
        assertTrue(ValidationCodes.UNKNOWN_LIFECYCLE in genCodes(SourceConfigFixtures.validGenericSource().copy(lifecycle = "retired")))
    }

    @Test
    fun `endpoint method and format vocabularies`() {
        for (m in listOf("get", "post-form", "post_form", "postform", "post-json", "post_json", "postjson")) {
            val src = SourceConfigFixtures.validGenericSource().let {
                it.copy(
                    endpoints =
                    it.endpoints + ("home" to EndpointSpec(url = "{baseUrl}/h", method = m)),
                )
            }
            assertFalse(ValidationCodes.ENDPOINT_UNKNOWN_METHOD in genCodes(src), "expected method $m accepted")
        }
        val badMethod = SourceConfigFixtures.validGenericSource().let {
            it.copy(
                endpoints =
                it.endpoints + ("home" to EndpointSpec(url = "{baseUrl}/h", method = "trace")),
            )
        }
        assertTrue(ValidationCodes.ENDPOINT_UNKNOWN_METHOD in genCodes(badMethod))

        for (fmt in listOf("json", "html", "script-json")) {
            val src = SourceConfigFixtures.validGenericSource().let {
                it.copy(
                    endpoints =
                    it.endpoints + ("home" to EndpointSpec(url = "{baseUrl}/h", format = fmt)),
                )
            }
            assertFalse(ValidationCodes.ENDPOINT_UNKNOWN_FORMAT in genCodes(src), "expected format $fmt accepted")
        }
        val badFormat = SourceConfigFixtures.validGenericSource().let {
            it.copy(
                endpoints =
                it.endpoints + ("home" to EndpointSpec(url = "{baseUrl}/h", format = "yaml")),
            )
        }
        assertTrue(ValidationCodes.ENDPOINT_UNKNOWN_FORMAT in genCodes(badFormat))
    }

    @Test
    fun `filter type, target, and encode vocabularies`() {
        // types
        for (t in listOf("select", "multiselect", "toggle", "text", "number")) {
            val f = FilterDefinition(id = "x", label = "L", type = t, request = FilterRequestSpec(target = "query", param = "p"))
            assertFalse(
                ValidationCodes.FILTER_UNKNOWN_TYPE in genCodes(SourceConfigFixtures.validGenericSource().copy(filters = listOf(f))),
                "expected type $t accepted",
            )
        }
        assertTrue(
            ValidationCodes.FILTER_UNKNOWN_TYPE in
                genCodes(
                    SourceConfigFixtures.validGenericSource().copy(
                        filters = listOf(FilterDefinition(id = "x", label = "L", type = "range", request = FilterRequestSpec(target = "query", param = "p"))),
                    ),
                ),
        )
        // targets (only the unknown-target code is asserted; other per-target coherence may add errors)
        for (target in listOf("query", "path", "form", "header", "body-json")) {
            val f = FilterDefinition(id = "x", label = "L", type = "text", request = FilterRequestSpec(target = target, param = "p"))
            assertFalse(
                ValidationCodes.FILTER_REQUEST_UNKNOWN_TARGET in genCodes(SourceConfigFixtures.validGenericSource().copy(filters = listOf(f))),
                "expected target $target accepted",
            )
        }
        assertTrue(
            ValidationCodes.FILTER_REQUEST_UNKNOWN_TARGET in
                genCodes(
                    SourceConfigFixtures.validGenericSource().copy(
                        filters = listOf(FilterDefinition(id = "x", label = "L", type = "text", request = FilterRequestSpec(target = "cookie", param = "p"))),
                    ),
                ),
        )
        // encodes
        for (encode in listOf("single", "csv", "repeat", "json-array")) {
            val f =
                FilterDefinition(
                    id = "x",
                    label = "L",
                    type = "multiselect",
                    options = listOf(FilterOptionSpec("a")),
                    request = FilterRequestSpec(target = "query", param = "p", encode = encode),
                )
            assertFalse(
                ValidationCodes.FILTER_REQUEST_UNKNOWN_ENCODE in genCodes(SourceConfigFixtures.validGenericSource().copy(filters = listOf(f))),
                "expected encode $encode accepted",
            )
        }
        assertTrue(
            ValidationCodes.FILTER_REQUEST_UNKNOWN_ENCODE in
                genCodes(
                    SourceConfigFixtures.validGenericSource().copy(
                        filters = listOf(
                            FilterDefinition(
                                id = "x",
                                label = "L",
                                type = "multiselect",
                                options = listOf(FilterOptionSpec("a")),
                                request = FilterRequestSpec(target = "query", param = "p", encode = "base64"),
                            ),
                        ),
                    ),
                ),
        )
    }
}
