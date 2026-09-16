package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.security.IssuedScopedAdminStepUp
import me.manga.kira.backend.security.JdbcScopedAdminStepUpStore
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import me.manga.kira.backend.security.StepUpGrantIssuance
import me.manga.kira.backend.security.StepUpUserSnapshot
import me.manga.kira.backend.security.VerifiedScopedAdminStepUp
import java.util.UUID

/** Fixed operations on the existing owner; no bean, generic work callback, independent transaction template or success receipt. */
internal class ScopedAdminStepUpPhaseExecutor(
    private val ownership: PersistencePhaseOwnership,
    private val store: JdbcScopedAdminStepUpStore,
    private val sourceCleanup: OrdinaryPersistencePhaseExecutor,
    private val complaintCleanup: ComplaintGrantCleanupPhaseExecutor,
) {
    fun readSourceSnapshot(userId: UUID): StepUpUserSnapshot = snapshot(ownership.enterSourceStepUpSnapshot(), userId, ScopedAdminStepUpScope.SOURCE)

    fun readComplaintSnapshot(userId: UUID): StepUpUserSnapshot = snapshot(ownership.enterComplaintStepUpSnapshot(), userId, ScopedAdminStepUpScope.COMPLAINT)

    fun issueSource(verified: VerifiedScopedAdminStepUp): IssuedScopedAdminStepUp = issue(verified, ScopedAdminStepUpScope.SOURCE)

    fun issueComplaint(verified: VerifiedScopedAdminStepUp): IssuedScopedAdminStepUp = issue(verified, ScopedAdminStepUpScope.COMPLAINT)

    @Suppress("TooGenericExceptionCaught")
    private fun snapshot(phase: PersistencePhaseContext, userId: UUID, scope: ScopedAdminStepUpScope): StepUpUserSnapshot {
        var snapshot: StepUpUserSnapshot? = null
        try {
            phase.begin()
            snapshot = store.readSnapshot(userId, scope)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return snapshot?.releasedFrom(phase) ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun issue(verified: VerifiedScopedAdminStepUp, scope: ScopedAdminStepUpScope): IssuedScopedAdminStepUp {
        if (verified.scope !== scope) {
            val refusal = PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            PersistencePhaseOwnership.current()?.recordFailure(refusal)
            throw refusal
        }
        verified.completeCleanup(sourceCleanup, complaintCleanup)
        val phase = when (scope) {
            ScopedAdminStepUpScope.SOURCE -> ownership.enterSourceStepUpIssuance()
            ScopedAdminStepUpScope.COMPLAINT -> ownership.enterComplaintStepUpIssuance()
        }
        var issuance: StepUpGrantIssuance? = null
        try {
            phase.begin()
            issuance = store.issue(verified)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return issuance?.result() ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "ScopedAdminStepUpPhaseExecutor(redacted)"
}
