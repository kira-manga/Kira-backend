package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.sourceconfig.domain.model.EndpointSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterDefinition
import me.manga.kira.backend.sourceconfig.domain.model.FilterOptionSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterRequestSpec
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument

/**
 * Test builders for valid stanzas/documents (PLAN §11). Rejection tests start from a fully-valid
 * base and tweak ONE field via `.copy(...)`, so a single-rule assertion is precise. A valid
 * generic source deliberately declares the four rule-31-required verbs (home/search/details/pages).
 */
object SourceConfigFixtures {

    fun validGenericSource(api: String = "GenSrc"): SourceConfig = SourceConfig(
        api = api,
        language = "en",
        baseUrl = "https://example.com",
        imageBase = "https://img.example.com",
        engine = "generic",
        endpoints =
        mapOf(
            "home" to EndpointSpec(url = "{baseUrl}/home?page={page}", format = "json"),
            "search" to EndpointSpec(url = "{baseUrl}/search?q={queryEncoded}&page={page}", format = "json"),
            "details" to EndpointSpec(url = "{itemUrl}", format = "json"),
            "pages" to EndpointSpec(url = "{chapterUrl}", format = "json"),
        ),
    )

    fun validLegacySource(api: String = "LegSrc"): SourceConfig = SourceConfig(
        api = api,
        language = "en",
        baseUrl = "https://legacy.example.com",
        engine = "legacy",
    )

    /** A minimal fully-valid multiselect filter usable on the [validGenericSource]'s query search. */
    fun validFilter(id: String = "genres"): FilterDefinition = FilterDefinition(
        id = id,
        label = "Genres",
        type = "multiselect",
        options = listOf(FilterOptionSpec("action"), FilterOptionSpec("comedy")),
        defaults = listOf("action"),
        request = FilterRequestSpec(target = "query", param = "genre", encode = "csv"),
        appliesTo = listOf("search"),
    )

    fun document(vararg sources: SourceConfig): SourceConfigDocument = SourceConfigDocument(schemaVersion = 1, revision = 1, sources = sources.toList())

    /** Load a classpath fixture (`/fixtures/<name>`) as text. */
    fun loadFixture(name: String): String = requireNotNull(SourceConfigFixtures::class.java.getResourceAsStream("/fixtures/$name")) {
        "missing test fixture: /fixtures/$name"
    }.use { it.readBytes().toString(Charsets.UTF_8) }
}
