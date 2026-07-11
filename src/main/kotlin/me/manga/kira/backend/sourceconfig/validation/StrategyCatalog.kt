package me.manga.kira.backend.sourceconfig.validation

/**
 * Mirror of the app's `StrategyRegistry` port (PLAN §8). The validator asks the catalog whether a
 * config's named strategy reference is compiled into the engine — a config may reference ONLY names
 * the engine actually implements (fail-closed, PLAN §8: "configs are data-only and may reference
 * only strategy names compiled into `DefaultStrategyRegistry`").
 *
 * FRAMEWORK-FREE (PLAN §3): pure Kotlin, extractable into the shared module alongside the model.
 */
interface StrategyCatalog {
    fun hasTransform(name: String): Boolean

    fun hasImageStrategy(name: String): Boolean

    fun hasDateStrategy(name: String): Boolean

    fun hasPagination(name: String): Boolean
}

/**
 * The server mirror of the app build's `DefaultStrategyRegistry` (PLAN §8 rules 12 & 15). The
 * whitelists are captured verbatim from the app (read 2026-07-11):
 *
 * - **transforms** — `sources/engine/.../internal/Transforms.kt` (16 names).
 * - **dateStrategies** — `sources/engine/.../DateStrategies.kt` (3 names).
 * - **pagination** — the app registry ships exactly `{"page-number"}`.
 * - **imageStrategies** — intentionally **EMPTY** (fail-closed; a Stage-1 capability). ANY
 *   `imageStrategy` reference is therefore rejected (PLAN §8 rule 15).
 *
 * `ContractInventoryTest` (PLAN §11 test 8b) pins these sets against the app so any drift fails
 * loudly. The sets are exposed as constants precisely so that pinning is exact.
 */
class ServerStrategyCatalog(
    private val transforms: Set<String> = TRANSFORMS,
    private val dateStrategies: Set<String> = DATE_STRATEGIES,
    private val paginationTypes: Set<String> = PAGINATION_TYPES,
    private val imageStrategies: Set<String> = IMAGE_STRATEGIES,
) : StrategyCatalog {

    override fun hasTransform(name: String): Boolean = name in transforms

    override fun hasImageStrategy(name: String): Boolean = name in imageStrategies

    override fun hasDateStrategy(name: String): Boolean = name in dateStrategies

    override fun hasPagination(name: String): Boolean = name in paginationTypes

    companion object {
        /** The app's transform whitelist — `Transforms.kt`, verbatim, 16 names (PLAN §8 rule 15). */
        val TRANSFORMS: Set<String> =
            linkedSetOf(
                "trim",
                "lowercase",
                "uppercase",
                "strip-html",
                "clean-html",
                "regex-replace",
                "regex-extract",
                "replace",
                "remove",
                "prepend",
                "append",
                "substring-before",
                "substring-after",
                "default",
                "enum-map",
                "format-number",
                "decimal",
            )

        /** The app's date-strategy whitelist — `DateStrategies.kt`, verbatim (PLAN §8 rule 15). */
        val DATE_STRATEGIES: Set<String> = linkedSetOf("iso", "epoch-seconds", "epoch-millis")

        /** The app's pagination whitelist — exactly `{"page-number"}` (PLAN §8 rule 12). */
        val PAGINATION_TYPES: Set<String> = linkedSetOf("page-number")

        /** Intentionally EMPTY — the app's image-strategy set is empty; any reference fails (rule 15). */
        val IMAGE_STRATEGIES: Set<String> = emptySet()
    }
}
