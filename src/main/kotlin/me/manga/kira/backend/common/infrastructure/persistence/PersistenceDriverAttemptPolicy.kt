package me.manga.kira.backend.common.infrastructure.persistence

internal enum class PersistenceDriverExecutionRecipe {
    ORIGINAL_PROVIDER,
    TRACKED_STANDARD,
}

internal enum class PersistenceDriverEvidencePolicy {
    DRIVER_CONTRACT_ONLY,
    TRACKED_CONJUNCTION,
}

internal enum class PersistenceDriverTransportRoute {
    ORDINARY,
    APPROVED_DIRECT,
}

/**
 * Immutable per-attempt policy data, not a bridge/timer readiness or complaint capability.
 * The complete private driver composition must select it from actual prerequisites before dispatch.
 */
internal class PersistenceDriverAttemptPolicy private constructor(
    val recipe: PersistenceDriverExecutionRecipe,
    val evidence: PersistenceDriverEvidencePolicy,
    val route: PersistenceDriverTransportRoute,
) {
    override fun toString(): String = "PersistenceDriverAttemptPolicy(${recipe.name},${evidence.name},${route.name})"

    companion object {
        val ORIGINAL_PROVIDER = PersistenceDriverAttemptPolicy(
            PersistenceDriverExecutionRecipe.ORIGINAL_PROVIDER,
            PersistenceDriverEvidencePolicy.DRIVER_CONTRACT_ONLY,
            PersistenceDriverTransportRoute.ORDINARY,
        )
        val TRACKED_ORDINARY_CONTRACT = PersistenceDriverAttemptPolicy(
            PersistenceDriverExecutionRecipe.TRACKED_STANDARD,
            PersistenceDriverEvidencePolicy.DRIVER_CONTRACT_ONLY,
            PersistenceDriverTransportRoute.ORDINARY,
        )
        val TRACKED_ORDINARY_CONJUNCTION = PersistenceDriverAttemptPolicy(
            PersistenceDriverExecutionRecipe.TRACKED_STANDARD,
            PersistenceDriverEvidencePolicy.TRACKED_CONJUNCTION,
            PersistenceDriverTransportRoute.ORDINARY,
        )
        val TRACKED_DELETION_CONJUNCTION = PersistenceDriverAttemptPolicy(
            PersistenceDriverExecutionRecipe.TRACKED_STANDARD,
            PersistenceDriverEvidencePolicy.TRACKED_CONJUNCTION,
            PersistenceDriverTransportRoute.APPROVED_DIRECT,
        )
    }
}
