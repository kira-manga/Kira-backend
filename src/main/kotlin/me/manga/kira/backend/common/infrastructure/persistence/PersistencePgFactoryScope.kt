package me.manga.kira.backend.common.infrastructure.persistence

import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference

internal enum class PersistencePgScopePhase {
    PREPARED,
    ENTERING,
    ACTIVE,
    REJECTED,
    REMOVING,
    REMOVAL_FAILED,
    ENDED,
}

/** Inert Entry-owned capability. Only the actual opening worker may install/remove its constructor scope. */
internal class PersistencePgFactoryScope(
    private val image: PersistencePgDriverImage,
    private val physical: PersistencePhysicalFactoryBinding,
    private val transports: PersistencePhysicalTransportBinding,
) {
    private val phase = AtomicReference(INITIAL_PHASE)
    private val caller = AtomicReference<Thread?>()
    private val captured = AtomicReference<TrackedPgSocketFactory?>()
    val extentSource = PersistenceTransportExtentSource()

    fun enter() {
        if (!physical.isOwnedWorkerThread() || ownershipLockHeld()) unavailable()
        if (!phase.compareAndSet(INITIAL_PHASE, PersistencePgScopePhase.ENTERING)) unavailable()
        caller.set(Thread.currentThread())
        if (current.get() != null) {
            phase.set(PersistencePgScopePhase.REJECTED)
            unavailable()
        }
        current.set(this)
        phase.set(PersistencePgScopePhase.ACTIVE)
    }

    /** True means no outstanding owned capture scope, including a positively unentered/rejected scope. */
    fun leave(): Boolean {
        if (!physical.isOwnedWorkerThread() || ownershipLockHeld()) return false
        val previous = phase.get()
        if (previous == INITIAL_PHASE || previous == PersistencePgScopePhase.REJECTED) {
            phase.set(PersistencePgScopePhase.ENDED)
            return true
        }
        if (previous == PersistencePgScopePhase.ENDED) return true
        if (previous != PersistencePgScopePhase.ENTERING && previous != PersistencePgScopePhase.ACTIVE) return false
        if (caller.get() !== Thread.currentThread()) return false
        // Distinguish a never-installed rejection from failed removal. The latter is never retryable as success.
        phase.set(PersistencePgScopePhase.REMOVING)
        var removed = false
        try {
            val local = current.get()
            if (local === this) {
                current.remove()
                removed = current.get() == null
            } else {
                removed = previous == PersistencePgScopePhase.ENTERING && local == null
            }
            return removed
        } finally {
            phase.set(if (removed) PersistencePgScopePhase.ENDED else PersistencePgScopePhase.REMOVAL_FAILED)
        }
    }

    fun createSocket(factory: TrackedPgSocketFactory): Socket {
        if (captured.get() !== factory || ownershipLockHeld()) unavailable()
        val role = PersistencePgDriverFrames.classifyAllocation(image) ?: unavailable()
        if (role == PersistenceTransportRole.PRIMARY && !isActiveCaller()) unavailable()
        val origin = PersistencePgTransportOrigin(this, factory, image, PersistenceTransportExtent(extentSource, role), Thread.currentThread())
        val construction = transports.prepareBound(origin)
        if (construction.reserve() != null) unavailable()
        return when (val result = construction.construct()) {
            is PersistenceTransportCreation.Created -> result.resource
            is PersistenceTransportCreation.Refused, is PersistenceTransportCreation.Retained -> unavailable()
        }
    }

    private fun isActiveCaller(): Boolean = phase.get() == PersistencePgScopePhase.ACTIVE && caller.get() === Thread.currentThread() && current.get() === this

    fun ownsOrigin(origin: PersistencePgTransportOrigin): Boolean = origin.scope === this && origin.image === image &&
        captured.get() === origin.factory && origin.extent.source === extentSource

    fun acceptsAllocation(origin: PersistencePgTransportOrigin): Boolean = ownsOrigin(origin) && !ownershipLockHeld() &&
        (origin.extent.role !== PersistenceTransportRole.PRIMARY || isActiveCaller())

    fun ownershipLockHeld(): Boolean = physical.ledger.lock.isHeldByCurrentThread || physical.rendezvous.lock.isHeldByCurrentThread ||
        transports.ownershipLockHeld()

    override fun toString(): String = "PersistencePgFactoryScope(redacted)"

    companion object {
        private val current = ThreadLocal<PersistencePgFactoryScope?>()
        private val INITIAL_PHASE = PersistencePgScopePhase.PREPARED

        fun prepareRuntime(): PersistencePgScopePhase = INITIAL_PHASE

        fun capture(factory: TrackedPgSocketFactory): PersistencePgFactoryScope {
            val scope = current.get() ?: unavailable()
            if (!scope.isActiveCaller() || scope.ownershipLockHeld()) unavailable()
            if (!PersistencePgDriverFrames.constructorAllowed(scope.image)) unavailable()
            if (!scope.captured.compareAndSet(null, factory)) unavailable()
            return scope
        }

        private fun unavailable(): Nothing = throw SocketException("Persistence driver bridge is unavailable.")
    }
}
