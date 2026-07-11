package me.manga.kira.backend.sourceconfig.domain

/**
 * The source-config lifecycle state machine (PLAN §9). **Pure domain** — no framework types (PLAN
 * §2/§3), so it extracts into the shared module and unit-tests without Spring. Callers (Phase 6's
 * `SourceAdminService`) translate the pure exceptions below into the HTTP problem envelope (409).
 *
 * Allowed transitions (anything else throws [IllegalLifecycleTransitionException]):
 * ```
 * active   --disable-->  disabled
 * disabled --enable -->  active
 * disabled --retire -->  retired      (direct active->retired is REJECTED: soft-disable is mandatory)
 * retired  --remove -->  removed      (terminal; removed -> * is always refused)
 * retired  --enable -->  active       (un-retire: engine == "generic" ONLY, else UNRETIRE_UNSUPPORTED)
 * ```
 * `draft` leaves only via a valid publish (`draft -> active`); it never takes a lifecycle action.
 *
 * Publish × status ([statusAfterPublish]) — publishing content NEVER implicitly re-enables:
 * `draft`(first valid publish) `-> active`; `active -> active`; `disabled -> disabled`;
 * `retired`/`removed` -> forbidden (409).
 *
 * **Engine-gated un-retire (PLAN §9, proved against the app sync):** a returning **generic** stanza is
 * re-seeded by the app (`seedIfGeneric`, disabled-by-default), so `retired -> active` is functional; a
 * **legacy/kotlin** stanza is never re-seeded from config, so an un-retired one would stay permanently
 * invisible on every client that synced during retirement — therefore `retired -> active` is allowed
 * for `engine == "generic"` only; otherwise [UnretireNotAllowedForEngineException] (their only path out
 * of `retired` is `removed`).
 */
object LifecycleStateMachine {

    private const val GENERIC_ENGINE = "generic"

    /**
     * Apply a lifecycle [action] to [current] for a source whose (published) revision has engine
     * [engine]; return the resulting status. Throws on any disallowed transition (§9).
     */
    fun transition(
        current: SourceLifecycleStatus,
        action: LifecycleAction,
        engine: String,
    ): SourceLifecycleStatus =
        when (action) {
            LifecycleAction.DISABLE ->
                if (current == SourceLifecycleStatus.ACTIVE) {
                    SourceLifecycleStatus.DISABLED
                } else {
                    illegal(current, action)
                }

            LifecycleAction.ENABLE ->
                when (current) {
                    SourceLifecycleStatus.DISABLED -> SourceLifecycleStatus.ACTIVE
                    SourceLifecycleStatus.RETIRED ->
                        if (canUnretire(engine)) {
                            SourceLifecycleStatus.ACTIVE
                        } else {
                            throw UnretireNotAllowedForEngineException(
                                engine,
                                "Un-retire (retired -> active) is supported only for engine=\"generic\" " +
                                    "sources (engine '$engine' would stay permanently invisible on clients " +
                                    "that synced during retirement); its only path out of retired is remove.",
                            )
                        }
                    else -> illegal(current, action)
                }

            LifecycleAction.RETIRE ->
                if (current == SourceLifecycleStatus.DISABLED) {
                    SourceLifecycleStatus.RETIRED
                } else {
                    illegal(current, action)
                }

            LifecycleAction.REMOVE ->
                if (current == SourceLifecycleStatus.RETIRED) {
                    SourceLifecycleStatus.REMOVED
                } else {
                    illegal(current, action)
                }
        }

    /**
     * The source status after publishing a (valid) revision (PLAN §9 publish × status). Publishing
     * never re-enables: `draft`/`active` -> `active`, `disabled` -> `disabled`; `retired`/`removed`
     * throw [IllegalLifecycleTransitionException] (publish is forbidden on those).
     */
    fun statusAfterPublish(current: SourceLifecycleStatus): SourceLifecycleStatus =
        when (current) {
            SourceLifecycleStatus.DRAFT, SourceLifecycleStatus.ACTIVE -> SourceLifecycleStatus.ACTIVE
            SourceLifecycleStatus.DISABLED -> SourceLifecycleStatus.DISABLED
            SourceLifecycleStatus.RETIRED, SourceLifecycleStatus.REMOVED ->
                throw IllegalLifecycleTransitionException(
                    current,
                    "publish",
                    "Cannot publish a ${current.wire} source (PLAN §9 publish × status).",
                )
        }

    /** Whether un-retire (retired -> active) is permitted for [engine] — generic only (PLAN §9). */
    fun canUnretire(engine: String): Boolean = engine == GENERIC_ENGINE

    private fun illegal(
        current: SourceLifecycleStatus,
        action: LifecycleAction,
    ): Nothing =
        throw IllegalLifecycleTransitionException(
            current,
            action.wire,
            "Lifecycle transition '${action.wire}' is not allowed from status '${current.wire}' (PLAN §9).",
        )
}

/** A lifecycle-changing admin action (PLAN §4.3 / §9). [wire] labels the transition in error paths. */
enum class LifecycleAction(val wire: String) {
    DISABLE("disable"),
    ENABLE("enable"),
    RETIRE("retire"),
    REMOVE("remove"),
}

/**
 * A disallowed lifecycle transition (PLAN §9). **Pure domain** exception — carries no framework types.
 * Phase 6 maps it to 409 `INVALID_LIFECYCLE_TRANSITION`.
 */
class IllegalLifecycleTransitionException(
    val from: SourceLifecycleStatus,
    val action: String,
    message: String,
) : RuntimeException(message)

/**
 * Un-retire attempted for a non-generic engine (PLAN §9). **Pure domain** exception. Phase 6 maps it
 * to 409 `UNRETIRE_UNSUPPORTED_FOR_ENGINE` (a distinct code from the generic invalid-transition 409).
 */
class UnretireNotAllowedForEngineException(
    val engine: String,
    message: String,
) : RuntimeException(message)
