package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.concurrent.withLock

/** Real F→G/T bookkeeping, but explicitly MODEL opening/readiness/resource facts. No driver is invoked. */
internal class PhysicalTransportTestFixture(
    policy: PersistenceDriverAttemptPolicy = PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT,
    val binding: PersistencePhysicalFactoryBinding = modelReadyBinding(),
    admit: Boolean = true,
) {
    val control = PersistenceOwnedCallerControl.prepare(5_000)
    val entry = requireNotNull(binding.reserve(control, policy))
    val transports: PersistencePhysicalTransportBinding get() = requireNotNull(entry.transports)
    val owner: PersistenceTransportOwner<*> get() = physicalTransportTestOwner(transports)

    init {
        if (admit) {
            assertTrue(binding.admit(entry))
            binding.ledger.lock.withLock { entry.opening = PersistencePhysicalOpeningPhase.ACTIVE }
        }
    }

    fun prepare(
        scope: TransportTestScope,
        role: PersistenceTransportRole = PersistenceTransportRole.PRIMARY,
    ): PersistencePhysicalTransportBinding.Construction = transports.prepare(role).also { construction ->
        // Register cleanup before any admission/constructor so a later failed assertion cannot leak a raw.
        scope.own(AutoCloseable { owner.requestClose(construction.record) })
    }

    fun retire() {
        entry.candidate.requestRetirement()
        assertTrue(binding.reconcileCallers())
    }
}

/** Test-only own-project inspection. No production raw getter or private JDK access is introduced. */
internal fun physicalTransportTestOwner(binding: PersistencePhysicalTransportBinding): PersistenceTransportOwner<*> {
    val field = PersistencePhysicalTransportBinding::class.java.getDeclaredField("owner")
    check(field.trySetAccessible())
    return field.get(binding) as PersistenceTransportOwner<*>
}

internal fun physicalTransportTestPolicy(name: String): PersistenceDriverAttemptPolicy = when (name) {
    "ORIGINAL" -> PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER
    "ORDINARY_WEAK" -> PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT
    "ORDINARY_STRONG" -> PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION
    "DELETION" -> PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION
    else -> error("Unknown synthetic attempt policy.")
}

internal fun assertPhysicalConstructionRefused(construction: PersistencePhysicalTransportBinding.Construction) {
    assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, (construction.construct() as PersistenceTransportCreation.Refused).reason)
}
