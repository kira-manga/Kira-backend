package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationControlOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.boundedEpochRotationFailure
import org.springframework.jdbc.core.JdbcTemplate

/** Fixed pooled request/discovery only. Both commit AND release before a nonpooled exclusive wait. */
internal class ComplaintEpochRotationPersistencePhaseExecutor(private val coordinator: CatalogCoordinatorPersistence, private val jdbc: JdbcTemplate) {
    private val ownership = coordinator.ownership

    internal fun request(attempt: CatalogEpochRotationAttemptV1): CatalogEpochRotationControlOperation =
        persist(attempt, PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST)

    internal fun resume(attempt: CatalogEpochRotationAttemptV1): CatalogEpochRotationControlOperation =
        persist(attempt, PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME)

    @Suppress("TooGenericExceptionCaught")
    private fun persist(attempt: CatalogEpochRotationAttemptV1, path: PersistencePhasePath): CatalogEpochRotationControlOperation {
        attempt.requirePersistence(ownership, jdbc)
        if (attempt.path !== path || coordinator.epochRotationCustody !== attempt.custody) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        val phase = when (path) {
            PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST -> ownership.enterComplaintEpochRotationRequest(attempt)
            PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME -> ownership.enterComplaintEpochRotationResume(attempt)
            else -> error("Unsupported rotation control phase.")
        }
        var completed: CatalogEpochRotationControlOperation? = null
        try {
            phase.begin() // Control-only named paths deliberately acquire NO epoch fence.
            completed = when (path) {
                PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST -> CatalogEpochRotationControlOperation.request(jdbc, attempt)
                PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME -> CatalogEpochRotationControlOperation.resume(jdbc, attempt)
                else -> error("Unsupported rotation control phase.")
            }
            attempt.requireRunning()
            phase.commit()
        } catch (problem: Throwable) {
            attempt.abort()
            phase.recordFailure(boundedEpochRotationFailure(problem))
        } finally {
            phase.finish()
        }
        val result = completed ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        result.requireReleasedRow() // No handoff, including a read-only discovery, before actual released ownership.
        return result
    }

    override fun toString(): String = "ComplaintEpochRotationPersistencePhaseExecutor(control-only,no-exclusive-session)"
}
