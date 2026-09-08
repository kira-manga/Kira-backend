package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicInteger

/** One shared scoped owner budget; it neither borrows a connection nor opens complaint capability. */
internal class OrdinaryPersistenceAdmission(configuredPoolSize: Int) {
    val ownerLimit: Int = ownerLimit(configuredPoolSize)
    private val complaintsAllowed = configuredPoolSize > 1
    private val owners = AtomicInteger()

    fun trySourceBoundary(): LocalPersistencePermit? = tryAcquire()

    fun tryComplaintBoundary(): LocalPersistencePermit? = if (complaintsAllowed) tryAcquire() else null

    fun activeOwners(): Int = owners.get()

    private fun tryAcquire(): LocalPersistencePermit? {
        val observed = owners.get()
        if (observed >= ownerLimit) return null
        // Contention is refusal, not a queued wait or a spin that retains an earlier admission deadline.
        if (!owners.compareAndSet(observed, observed + 1)) return null
        return LocalPersistencePermit { owners.getAndDecrement() }
    }

    override fun toString(): String = "OrdinaryPersistenceAdmission(redacted)"

    companion object {
        private const val MAX_OWNERS = 4

        private fun ownerLimit(configuredPoolSize: Int): Int {
            if (configuredPoolSize <= 0) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_POOL_CAPACITY)
            return minOf(MAX_OWNERS, configuredPoolSize - 1).coerceAtLeast(1)
        }
    }
}
