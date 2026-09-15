package me.manga.kira.backend.sourceconfig

import kotlinx.serialization.json.Json
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.sources.testkit.DeclarationCapabilityFixture
import me.manga.kira.sources.testkit.DeclarationCapabilityFixtures
import me.manga.kira.source.contracts.model.SourceConfig as SharedSourceConfig

/** Small consumer slice of the shared corpus; the Engine owns exhaustive grammar coverage. */
object DeclarationValidationFixtures {
    val unsupportedIds = listOf(
        "unknown_url_variable",
        "json_scalar_path",
        "page_counter_does_not_update_page_offset",
    )

    val representativeIds = listOf(
        "all_seeded_request_names_including_empty",
        "placeholder_filters_share_active_request_namespace",
        "field_own_vars_coalescing_pipes_and_base_shadow",
        "css_real_parser_not_a_regex_subset",
        "mixed_formats_respect_field_overrides",
        "page_root_and_dir_without_root_dirs",
        "unknown_url_variable",
        "unknown_form_variable_post-form",
        "json_scalar_path",
        "malformed_css_field",
        "page_counter_does_not_update_page_offset",
        "script_json_inline_chapters_unsupported",
    )

    private val modelJson = Json { encodeDefaults = true }

    fun fixture(id: String): DeclarationCapabilityFixture = DeclarationCapabilityFixtures.cases.single { it.id == id }

    fun backendSource(fixture: DeclarationCapabilityFixture): SourceConfig = modelJson.decodeFromJsonElement(
        SourceConfig.serializer(),
        modelJson.encodeToJsonElement(SharedSourceConfig.serializer(), fixture.source),
    )

    /** Synthetic Engine vectors omit server-required verbs; supply those without repairing defects. */
    fun completeSource(id: String, api: String): SourceConfig {
        val source = backendSource(fixture(id))
        return source.copy(
            api = api,
            displayName = api,
            endpoints = SourceConfigFixtures.validGenericSource(api).endpoints + source.endpoints,
        )
    }
}
