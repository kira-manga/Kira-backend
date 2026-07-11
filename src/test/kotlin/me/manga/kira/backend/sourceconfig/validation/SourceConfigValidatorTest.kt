package me.manga.kira.backend.sourceconfig.validation

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.domain.model.EndpointSpec
import me.manga.kira.backend.sourceconfig.domain.model.FieldSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterSpec
import me.manga.kira.backend.sourceconfig.domain.model.IconSpec
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.TransformSpec
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 1 (valid document passes) + test 2 (source-level rejection families) + the
 * generic-source non-filter halves of test 3. Pure — no Spring context. Ports the app's
 * `DefaultSourceConfigValidatorTest` coverage. Filter rules (16–27) are in
 * `SourceConfigValidatorFilterTest`.
 */
class SourceConfigValidatorTest {

    private val validator = SourceConfigValidator()

    private fun codes(source: SourceConfig): Set<String> =
        validator.validate(SourceConfigFixtures.document(source)).errors.map { it.code }.toSet()

    // --- Test 1: fully-valid documents pass with zero errors ---

    @Test
    fun `real Azora-style document passes with zero errors`() {
        val json = SourceConfigFixtures.loadFixture("valid-document.json")
        val document = SourceConfigParser.parseCompatibleDocument(json)
        val result = validator.validate(document)
        assertTrue(result.isValid, "expected valid, got errors: ${result.errors}")
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `valid generic, legacy, and generic-with-filter document is valid`() {
        val doc =
            SourceConfigFixtures.document(
                SourceConfigFixtures.validGenericSource("Gen"),
                SourceConfigFixtures.validLegacySource("Leg"),
                SourceConfigFixtures.validGenericSource("WithFilter").copy(
                    filters = listOf(SourceConfigFixtures.validFilter()),
                ),
            )
        val result = validator.validate(doc)
        assertTrue(result.isValid, "expected valid, got errors: ${result.errors}")
    }

    @Test
    fun `the full real bundled document validates with zero errors - rules 31-33 must never reject it`() {
        val json = SourceConfigFixtures.loadFixture("bundled-full.json")
        val document = SourceConfigParser.parseCompatibleDocument(json)
        val result = validator.validate(document)
        assertTrue(result.isValid, "full bundled document must validate clean; errors: ${result.errors}")
        assertEquals(45, document.sources.size)
    }

    // --- Test 2: document + per-source rejection families ---

    @Test
    fun `rule 1 - unsupported schemaVersion fails immediately without probing further`() {
        val doc = SourceConfigFixtures.document(SourceConfigFixtures.validGenericSource()).copy(schemaVersion = 2)
        val result = validator.validate(doc)
        assertEquals(listOf(ValidationCodes.UNSUPPORTED_SCHEMA_VERSION), result.errors.map { it.code })
    }

    @Test
    fun `rule 2 - duplicate api reported once`() {
        val doc =
            SourceConfigFixtures.document(
                SourceConfigFixtures.validGenericSource("Dup"),
                SourceConfigFixtures.validGenericSource("Dup"),
            )
        val result = validator.validate(doc)
        assertEquals(1, result.errors.count { it.code == ValidationCodes.DUPLICATE_API })
    }

    @Test
    fun `rule 2 - validateSource flags api already present in candidate document`() {
        val errors = validator.validateSource(SourceConfigFixtures.validGenericSource("X"), otherApis = setOf("X"))
        assertTrue(errors.any { it.code == ValidationCodes.DUPLICATE_API })
    }

    @Test
    fun `rule 3 - blank api`() {
        assertTrue(ValidationCodes.API_BLANK in codes(SourceConfigFixtures.validGenericSource().copy(api = "")))
    }

    @Test
    fun `rule 4 - blank language`() {
        assertTrue(ValidationCodes.LANGUAGE_BLANK in codes(SourceConfigFixtures.validGenericSource().copy(language = " ")))
    }

    @Test
    fun `rule 5 - non-http baseUrl`() {
        assertTrue(ValidationCodes.BASE_URL_NOT_HTTP in codes(SourceConfigFixtures.validGenericSource().copy(baseUrl = "ftp://example.com")))
    }

    @Test
    fun `rule 6 - unknown engine, and kotlin prefix accepted`() {
        assertTrue(ValidationCodes.UNKNOWN_ENGINE in codes(SourceConfigFixtures.validLegacySource().copy(engine = "python")))
        // kotlin:<id> is a recognized engine (skips generic checks); should be valid.
        assertFalse(ValidationCodes.UNKNOWN_ENGINE in codes(SourceConfigFixtures.validLegacySource().copy(engine = "kotlin:promanga")))
    }

    @Test
    fun `rule 7 - unknown siteState`() {
        assertTrue(ValidationCodes.UNKNOWN_SITE_STATE in codes(SourceConfigFixtures.validGenericSource().copy(siteState = "BROKEN")))
    }

    @Test
    fun `rule 8 - unknown lifecycle`() {
        assertTrue(ValidationCodes.UNKNOWN_LIFECYCLE in codes(SourceConfigFixtures.validGenericSource().copy(lifecycle = "retired")))
    }

    @Test
    fun `rule 9 - non-bare hosts in each list, including the port case`() {
        assertTrue(ValidationCodes.HOST_NOT_BARE in codes(SourceConfigFixtures.validGenericSource().copy(previousHosts = listOf("https://x.co"))))
        assertTrue(ValidationCodes.HOST_NOT_BARE in codes(SourceConfigFixtures.validGenericSource().copy(previousImageHosts = listOf("x.co/path"))))
        // The port case explicitly called out in PLAN §11 test 2.
        assertTrue(ValidationCodes.HOST_NOT_BARE in codes(SourceConfigFixtures.validGenericSource().copy(trustedHosts = listOf("host:8080"))))
    }

    @Test
    fun `rule 10 - icon bad resourceKey, non-https remoteUrl, empty block`() {
        assertTrue(ValidationCodes.ICON_RESOURCE_KEY_INVALID in codes(SourceConfigFixtures.validGenericSource().copy(icon = IconSpec(resourceKey = "Bad Key!"))))
        assertTrue(ValidationCodes.ICON_REMOTE_URL_NOT_HTTPS in codes(SourceConfigFixtures.validGenericSource().copy(icon = IconSpec(remoteUrl = "http://x.co/i.png"))))
        assertTrue(ValidationCodes.ICON_EMPTY in codes(SourceConfigFixtures.validGenericSource().copy(icon = IconSpec())))
    }

    @Test
    fun `rule 11 - filters on a legacy engine rejected, and legacy endpoint defects are skipped`() {
        val legacyWithFilters = SourceConfigFixtures.validLegacySource().copy(filters = listOf(SourceConfigFixtures.validFilter()))
        assertTrue(ValidationCodes.FILTERS_NOT_ALLOWED_FOR_ENGINE in codes(legacyWithFilters))
        // A legacy source with a broken endpoint is NOT endpoint-checked (rule 11 skip).
        val legacyBrokenEndpoint = SourceConfigFixtures.validLegacySource().copy(endpoints = mapOf("home" to EndpointSpec(url = "")))
        assertFalse(ValidationCodes.ENDPOINT_URL_BLANK in codes(legacyBrokenEndpoint))
    }

    // --- Test 3 (non-filter halves): generic-source rejections ---

    @Test
    fun `rule 12 - unknown pagination type`() {
        val src = SourceConfigFixtures.validGenericSource().copy(pagination = me.manga.kira.backend.sourceconfig.domain.model.PaginationSpec(type = "cursor"))
        assertTrue(ValidationCodes.UNKNOWN_PAGINATION_TYPE in codes(src))
    }

    @Test
    fun `rule 13-31 - missing home and featured`() {
        val src = SourceConfigFixtures.validGenericSource().copy(
            endpoints = SourceConfigFixtures.validGenericSource().endpoints.filterKeys { it != "home" && it != "featured" },
        )
        assertTrue(ValidationCodes.GENERIC_MISSING_HOME_OR_FEATURED in codes(src))
    }

    @Test
    fun `rule 31 - search, details, pages required, chapters is optional`() {
        val base = SourceConfigFixtures.validGenericSource()
        assertTrue(ValidationCodes.GENERIC_MISSING_SEARCH in codes(base.copy(endpoints = base.endpoints - "search")))
        assertTrue(ValidationCodes.GENERIC_MISSING_DETAILS in codes(base.copy(endpoints = base.endpoints - "details")))
        assertTrue(ValidationCodes.GENERIC_MISSING_PAGES in codes(base.copy(endpoints = base.endpoints - "pages")))
        // Absence of a `chapters` endpoint is fine — it must NOT be reported.
        assertFalse(codes(base).any { it.startsWith("GENERIC_MISSING") })
    }

    @Test
    fun `rule 14 - blank url, raw query in url and jsonBody, unknown method and format, listFilter op and mode`() {
        val base = SourceConfigFixtures.validGenericSource()
        assertTrue(ValidationCodes.ENDPOINT_URL_BLANK in codes(base.copy(endpoints = base.endpoints + ("home" to EndpointSpec(url = "")))))
        assertTrue(ValidationCodes.ENDPOINT_URL_RAW_QUERY in codes(base.copy(endpoints = base.endpoints + ("search" to EndpointSpec(url = "{baseUrl}/s?q={query}", format = "json")))))
        assertTrue(
            ValidationCodes.ENDPOINT_JSONBODY_RAW_QUERY in
                codes(base.copy(endpoints = base.endpoints + ("search" to EndpointSpec(url = "{baseUrl}/s", method = "post-json", format = "json", jsonBody = "{\"q\":\"{query}\"}")))),
        )
        assertTrue(ValidationCodes.ENDPOINT_UNKNOWN_METHOD in codes(base.copy(endpoints = base.endpoints + ("home" to EndpointSpec(url = "{baseUrl}/h", method = "PUT")))))
        assertTrue(ValidationCodes.ENDPOINT_UNKNOWN_FORMAT in codes(base.copy(endpoints = base.endpoints + ("home" to EndpointSpec(url = "{baseUrl}/h", format = "xml")))))
        val listFilterBad = base.copy(endpoints = base.endpoints + ("home" to EndpointSpec(url = "{baseUrl}/h", listFilters = listOf(FilterSpec(path = "p", op = "matches", mode = "keep")))))
        assertTrue(ValidationCodes.LISTFILTER_UNKNOWN_OP in codes(listFilterBad))
        assertTrue(ValidationCodes.LISTFILTER_UNKNOWN_MODE in codes(listFilterBad))
    }

    @Test
    fun `rule 15 - unknown transform, unknown dateStrategy, any imageStrategy`() {
        val base = SourceConfigFixtures.validGenericSource()
        assertTrue(ValidationCodes.UNKNOWN_TRANSFORM in codes(base.copy(fields = mapOf("item.title" to FieldSpec(transform = listOf(TransformSpec(fn = "explode")))))))
        assertTrue(ValidationCodes.UNKNOWN_DATE_STRATEGY in codes(base.copy(fields = mapOf("chapter.date" to FieldSpec(dateStrategy = "rfc822")))))
        assertTrue(ValidationCodes.IMAGE_STRATEGY_NOT_ALLOWED in codes(base.copy(fields = mapOf("page.image" to FieldSpec(imageStrategy = "anything")))))
    }

    // --- Rule 32 secret-safety (applies to every engine) ---

    @Test
    fun `rule 32a - forbidden header on any engine`() {
        assertTrue(ValidationCodes.FORBIDDEN_HEADER in codes(SourceConfigFixtures.validLegacySource().copy(headers = mapOf("Cookie" to "sid=1"))))
    }

    @Test
    fun `rule 32b - secret-like header rejected unless value is on the placeholder allowlist`() {
        val bearer = SourceConfigFixtures.validGenericSource().copy(headers = mapOf("authorization" to "Bearer real-token-xyz"))
        assertTrue(ValidationCodes.SECRET_LIKE_HEADER in codes(bearer))
        // "Bearer null" is the documented public placeholder — accepted.
        val placeholder = SourceConfigFixtures.validGenericSource().copy(headers = mapOf("authorization" to "Bearer null"))
        assertFalse(ValidationCodes.SECRET_LIKE_HEADER in codes(placeholder))
        // A token-substring name is also sensitive.
        assertTrue(ValidationCodes.SECRET_LIKE_HEADER in codes(SourceConfigFixtures.validGenericSource().copy(headers = mapOf("x-session-token" to "abc"))))
    }

    @Test
    fun `rule 32c - url user-info is forbidden`() {
        assertTrue(ValidationCodes.URL_USERINFO_FORBIDDEN in codes(SourceConfigFixtures.validGenericSource().copy(baseUrl = "https://user:pass@example.com")))
    }

    @Test
    fun `secret-like header message never echoes the value`() {
        val bearer = SourceConfigFixtures.validGenericSource().copy(headers = mapOf("authorization" to "Bearer super-secret-123"))
        val error = validator.validate(SourceConfigFixtures.document(bearer)).errors.first { it.code == ValidationCodes.SECRET_LIKE_HEADER }
        assertFalse(error.message.contains("super-secret-123"), "value must never be echoed")
        assertFalse(error.path.contains("super-secret-123"))
    }
}
