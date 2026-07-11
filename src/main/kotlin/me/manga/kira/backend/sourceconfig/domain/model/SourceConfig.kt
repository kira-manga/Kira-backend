package me.manga.kira.backend.sourceconfig.domain.model

import kotlinx.serialization.Serializable

/**
 * The mirrored source-config model (PLAN §7). These `@Serializable` data classes are copied
 * **field-for-field (names, types, defaults)** from the app's
 * `sources/contracts/.../model/SourceConfig.kt` (read 2026-07-11) so that default-omission and
 * shape parity behave identically to the app's bundled `CONFIG_BACKED_SOURCES_JSON`.
 *
 * **This package is FRAMEWORK-FREE (PLAN §3):** kotlin-stdlib + kotlinx-serialization only — no
 * Spring, JPA, or Jackson imports — so the model (with the sibling `validation` package) can later
 * be extracted verbatim into a shared Kotlin module consumed by both backend and mobile app.
 *
 * Outbound serialization always goes through `kcj-1` canonicalization
 * ([me.manga.kira.backend.common.CanonicalJson]) — omitted defaults, recursively sorted keys,
 * compact UTF-8 (PLAN §5). Because the app parses leniently and ignores unknown keys, the server
 * *could* add fields; the rule is **don't** — serve exactly this model (PLAN §7).
 */
@Serializable
data class SourceConfigDocument(
    val schemaVersion: Int,
    /** ISO-8601 provenance only; the app has zero readers of this field (PLAN §9). */
    val generatedAt: String? = null,
    /** Monotonic; higher-wins on the app's merge (PLAN §5 anti-rollback floor). */
    val revision: Long = 0,
    val sources: List<SourceConfig> = emptyList(),
)

/**
 * One source stanza. `api` is the stable key (= legacy `MangaSource.API`). Defaults mirror the app
 * exactly — notably `displayName` defaults to `api`, `engine` defaults to `"legacy"`,
 * `usesCapturedHeaders` defaults to `true`, and `lifecycle` defaults to the neutral `"active"`
 * (which, under `kcj-1` default-omission, renders as an *absent* key — PLAN §9 lifecycle-neutral).
 */
@Serializable
data class SourceConfig(
    val api: String,
    val language: String,
    val displayName: String = api,
    val baseUrl: String,
    val imageBase: String = "",
    /** First-seed enablement only (PLAN §7). */
    val enabled: Boolean = false,
    /** Merge tiebreak (PLAN §7). */
    val priority: Int = 0,
    /** `"generic"` | `"legacy"` | `"kotlin:<id>"` (PLAN §7 / §8 rule 6). */
    val engine: String = "legacy",
    /** Reserved — NOT enforced by the app engine yet (PLAN §7). */
    val minAppVersion: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val usesCapturedHeaders: Boolean = true,
    val pagination: PaginationSpec = PaginationSpec(),
    /** Verbs: `home`, `featured`, `search`, `details`, `chapters`, `pages` (PLAN §7). */
    val endpoints: Map<String, EndpointSpec> = emptyMap(),
    /** Dotted paths, e.g. `item.title`, `chapter.url`, `page.image` (PLAN §7). */
    val fields: Map<String, FieldSpec> = emptyMap(),
    val blacklistGenres: List<String> = emptyList(),
    /** `WORKING` | `UNDER_MAINTENANCE` | `STOPPED` | `ADULT_18_PLUS` (PLAN §7 / §8 rule 7). */
    val siteState: String = "WORKING",
    /** The app's 3-state vocabulary: `active` | `disabled` | `removed` (PLAN §7 / §8 rule 8). */
    val lifecycle: String = "active",
    /** All three host lists carry BARE hosts, e.g. `"azoramoon.co"` (PLAN §7 / §8 rule 9). */
    val previousHosts: List<String> = emptyList(),
    val previousImageHosts: List<String> = emptyList(),
    val trustedHosts: List<String> = emptyList(),
    val icon: IconSpec? = null,
    /** Declaration order = UI order = request-composition order (PLAN §7). */
    val filters: List<FilterDefinition> = emptyList(),
)

/** `resourceKey` regex `[a-z0-9_]{1,64}`; `remoteUrl` absolute **HTTPS-only** (PLAN §7 / §8 rule 10). */
@Serializable
data class IconSpec(
    val resourceKey: String = "",
    val remoteUrl: String = "",
)

/** Only `page-number` exists in the app registry (PLAN §7 / §8 rule 12). */
@Serializable
data class PaginationSpec(
    val type: String = "page-number",
    val param: String = "page",
    val start: Int = 1,
)

/**
 * One endpoint (verb) template. `url` supports `{baseUrl}`, `{imageBase}`, `{page}`,
 * `{queryEncoded}`, `{itemUrl}`, `{chapterUrl}`, `{id}` placeholders (PLAN §7).
 */
@Serializable
data class EndpointSpec(
    val url: String,
    val method: String = "get",
    /** `json` | `html` | `script-json` | `""` (PLAN §7 / §8 rule 14). */
    val format: String = "",
    val scriptId: String = "",
    /** JSONPath; may be comma-separated coalesce candidates (PLAN §7). */
    val root: String = "",
    val rootDirs: List<String> = emptyList(),
    val listSelector: String = "",
    val formBody: Map<String, String> = emptyMap(),
    val jsonBody: String = "",
    val listFilters: List<FilterSpec> = emptyList(),
    val pageParam: String = "",
    val lastPageLocator: String = "",
)

/** List-item predicate (PLAN §7 / §8 rule 14). `op` and `mode` are validated per the whitelists. */
@Serializable
data class FilterSpec(
    val path: String,
    /** `equals` | `notEquals` | `contains` | `notNull` | `isNull` (PLAN §7). */
    val op: String,
    val value: String = "",
    /** `include` | `exclude` (PLAN §7). */
    val mode: String = "exclude",
)

/** Field-extraction spec (PLAN §7 / §8 rule 15 for transform/date/image strategy references). */
@Serializable
data class FieldSpec(
    val path: String = "",
    val selector: String = "",
    val attr: String = "text",
    val fallbackPath: String = "",
    val fallbackSelectors: List<String> = emptyList(),
    val lazyAttrChain: List<String> = emptyList(),
    val template: String = "",
    val vars: Map<String, String> = emptyMap(),
    val listPath: String = "",
    val listSelector: String = "",
    val imageStrategy: String = "",
    val dateStrategy: String = "",
    val transform: List<TransformSpec> = emptyList(),
)

/** A single transform step; `fn` is validated against the transform whitelist (PLAN §8 rule 15). */
@Serializable
data class TransformSpec(
    val fn: String,
    val args: Map<String, String> = emptyMap(),
    val list: List<String> = emptyList(),
)

/**
 * User-facing search filter (PLAN §7 / §8 rules 16–27). `request` is REQUIRED (no default).
 * `appliesTo` defaults to `["search"]` (the v1 whitelist).
 */
@Serializable
data class FilterDefinition(
    /** `[a-z0-9_]{1,64}` (PLAN §8 rule 16). */
    val id: String,
    val label: String,
    /** `select` | `multiselect` | `toggle` | `text` | `number` (`range`/`date` reserved-rejected). */
    val type: String,
    val options: List<FilterOptionSpec> = emptyList(),
    val default: String = "",
    /** Multiselect only (PLAN §8 rule 21). */
    val defaults: List<String> = emptyList(),
    val required: Boolean = false,
    val request: FilterRequestSpec,
    val visibleWhen: List<FilterConditionSpec> = emptyList(),
    val excludeOf: String = "",
    /** v1 whitelist: `search` only (PLAN §8 rule 24). */
    val appliesTo: List<String> = listOf("search"),
)

/** One selectable option for a select/multiselect filter (PLAN §7). */
@Serializable
data class FilterOptionSpec(
    val value: String,
    val label: String = "",
)

/**
 * How a filter value is composed into the request (PLAN §7 / §8 rules 22–24). `target` and `param`
 * are REQUIRED (no default).
 */
@Serializable
data class FilterRequestSpec(
    /** `query` | `path` | `form` | `header` | `body-json` (PLAN §8 rule 22). */
    val target: String,
    val param: String,
    /** `single` | `csv` | `repeat` | `json-array` (PLAN §8 rule 22). */
    val encode: String = "single",
    val delimiter: String = ",",
    val omitIfEmpty: Boolean = true,
    val trueValue: String = "true",
    val falseValue: String = "",
)

/** A `visibleWhen` condition (PLAN §7 / §8 rule 25). `filter` and `anyOf` are REQUIRED. */
@Serializable
data class FilterConditionSpec(
    val filter: String,
    val anyOf: List<String>,
)
