package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationCaptureOperation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One original-root NONPOOLED role. Only the fixed capture operation leaves this owner; no JDBC/SQL callback surface. */
internal class EpochRotationPersistence private constructor(
    private val root: PersistenceJdbcDriverRoot,
    private val participant: PersistenceJdbcParticipant,
    private val material: VersionBoundEpochRotationMaterial,
) : AutoCloseable {
    private val stopped = AtomicBoolean()
    private val active = AtomicReference<Capture?>()

    fun descriptor(): VersionBoundEpochRotationDescriptor {
        requireUnchangedConfiguration()
        return material.descriptor
    }

    fun belongsTo(pools: VersionBoundPersistencePools): Boolean = root.versionBoundPools === pools && pools.ownsEpochRotation(this)

    fun requireUnchangedConfiguration() {
        check(root.epochRotation === this && root.epochRotationMaterial === material && material.matches(root.endpoint))
        check(participant.ownsEpochRotation(this) && !root.sourceOnly && root.pathStyle === PersistencePathStyle.POSIX)
    }

    /** Explicit cold-role preparation only. No physical connection is opened until a fixed capture is admitted. */
    fun prepare(): PersistenceLifecycleObservation {
        requireConnectionFree()
        requireUnchangedConfiguration()
        if (stopped.get()) return PersistenceLifecycleObservation.UNAVAILABLE
        val result = root.prepareEpochRotation()
        if (result !== PersistenceLifecycleActivation.STARTED && result !== PersistenceLifecycleActivation.ALREADY_CLAIMED) {
            return PersistenceLifecycleObservation.UNAVAILABLE
        }
        return PersistenceManagedObserver.observe(root, PersistenceManagedObservation.EPOCH_ROTATION)
    }

    fun observePreparation(): PersistenceLifecycleObservation = root.epochRotationPreparationObservation()

    fun requestShutdown(): Boolean = root.requestEpochRotationShutdown()

    fun observeShutdown(): PersistenceLifecycleObservation = PersistenceManagedObserver.observe(root, PersistenceManagedObservation.EPOCH_ROTATION_SHUTDOWN)

    override fun close() {
        requireConnectionFree()
        requestShutdown()
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun capture(attempt: CatalogEpochRotationAttemptV1): CatalogEpochRotationCaptureOperation {
        var call: Capture? = null
        try {
            requireConnectionFree()
            requireUnchangedConfiguration()
            attempt.requireCore(this)
            reconcile()
            if (stopped.get() || root.epochRotationPreparationObservation() !== PersistenceLifecycleObservation.READY) refuse()
            val retained = Capture(attempt)
            if (!active.compareAndSet(null, retained)) refuse()
            call = retained // Retain before any request/session construction can fail.
            current.set(retained)
            val request = participant.prepareEpochRotationRequest(this, attempt)
            retained.request = request
            val outcome = request.execute()
            val session = when (outcome) {
                is PersistenceFactoryResult.Success -> outcome.value
                else -> throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED, cleanupProven = request.custodyEnded())
            }
            retained.session = session
            session.begin()
            val operation = CatalogEpochRotationCaptureOperation.execute(attempt, session)
            session.commit(operation)
            session.finish()
            session.awaitRelease()
            attempt.requireCore(this) // The same original local lease/configuration and total deadline still apply.
            session.requireReleased(operation)
            return operation
        } catch (problem: Throwable) {
            attempt.abort()
            call?.session?.failed()
            throw call?.session?.failure() ?: (problem as? PersistencePhaseException ?: PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
        } finally {
            val retained = call
            if (retained != null) {
                try {
                    retained.session?.finish()
                    retained.request?.requestRetirement()
                } finally {
                    try {
                        retained.session?.restoreAfterFailure()
                    } catch (_: Throwable) {
                        attempt.abort()
                        retained.session?.failed()
                        throw retained.session?.failure() ?: PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
                    } finally {
                        retained.bodyEnded.set(true)
                        retained.reconcileCaller()
                    }
                }
            }
        }
    }

    /** No clock/JDBC/callback here: the original scanner only reconciles exact ended wrapper/physical facts. */
    internal fun reconcile() {
        active.get()?.let { if (it.custodyEnded()) active.compareAndSet(it, null) }
    }

    internal fun seal() {
        stopped.set(true)
        active.get()?.request?.requestRetirement()
    }

    internal fun endedForTrust(): Boolean {
        reconcile()
        return stopped.get() && active.get() == null
    }

    private inner class Capture(val attempt: CatalogEpochRotationAttemptV1) {
        val bodyEnded = AtomicBoolean()

        @Volatile var request: PersistenceEpochRotationFactoryRequest? = null

        @Volatile var session: PersistenceEpochRotationSession? = null

        fun custodyEnded(): Boolean = bodyEnded.get() && request?.custodyEnded() != false

        fun reconcileCaller() {
            if (custodyEnded()) {
                active.compareAndSet(this, null)
                if (current.get() === this) current.remove()
            }
        }
    }

    override fun toString(): String = "EpochRotationPersistence(original-root,capacity1,non-pooled,no-seal-or-checkpoint)"

    companion object {
        private val current = ThreadLocal<EpochRotationPersistence.Capture?>()

        internal fun callerHasOutstanding(): Boolean {
            current.get()?.reconcileCaller()
            return current.get() != null
        }

        internal fun create(
            root: PersistenceJdbcDriverRoot,
            participant: PersistenceJdbcParticipant,
            material: VersionBoundEpochRotationMaterial,
        ): EpochRotationPersistence = EpochRotationPersistence(root, participant, material)

        private fun refuse(): Nothing = throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
    }
}
