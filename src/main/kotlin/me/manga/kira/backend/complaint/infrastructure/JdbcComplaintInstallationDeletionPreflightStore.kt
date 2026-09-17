package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDeleteAllFingerprint
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightRejection
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightTuple
import org.springframework.jdbc.core.JdbcTemplate

/** Fixed read-only comparison producer. No session refresh, enabled bean, admission or deletion writer. */
internal class JdbcComplaintInstallationDeletionPreflightStore(private val jdbc: JdbcTemplate) {
    private val issuer = Any()

    fun read(candidate: InstallationDeletionCandidate): ComplaintInstallationDeletionPreflightOperation =
        ComplaintInstallationDeletionPreflightOperation.capture(jdbc, issuer, candidate)

    fun requireOwned(comparison: InstallationDeletionPreflightTuple, ownerIdentity: Any) =
        ComplaintInstallationDeletionPreflightOperation.requireOwned(comparison, issuer, ownerIdentity)

    override fun toString(): String = "JdbcComplaintInstallationDeletionPreflightStore(read-only)"
}

/** The existing phase retains this exact operation before SQL; no caller result/flag can complete it. */
internal class ComplaintInstallationDeletionPreflightOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val issuer: Any,
    private val candidate: InstallationDeletionCandidate,
) {
    private var stage = Stage.PREPARED
    private var ownerIdentity: Any? = null
    private var comparison: InstallationDeletionPreflightSnapshot.Comparison? = null
    private var released: InstallationDeletionPreflightResult? = null

    fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected

    fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE && comparison != null

    val result: InstallationDeletionPreflightResult
        get() {
            phase.installationDeletionPreflight.requireCommitted(this)
            requireConnectionFree()
            // Private continuation objects do not even exist before known commit + actual owned release.
            return released ?: release(checkNotNull(comparison), checkNotNull(ownerIdentity)).also { released = it }
        }

    private fun read() {
        requireRetained()
        check(stage === Stage.PREPARED)
        ownerIdentity = phase.installationDeletionPreflight.ownerIdentity(this, jdbc)
        stage = Stage.READING
        val snapshot = jdbc.query(
            InstallationDeletionPreflightSnapshot.SQL,
            { row, _ -> InstallationDeletionPreflightSnapshot.read(row, candidate.installation) },
            candidate.installation.id,
            candidate.installation.scope.id,
        ).single() // The bounded two-row sentinel refuses multiple receipts/applied versions; it never chooses one.
        requireRetained()
        comparison = snapshot.compare(candidate)
        requireRetained()
        stage = Stage.COMPLETE
    }

    private fun requireRetained() = phase.installationDeletionPreflight.requireRetained(this, jdbc)

    private fun release(value: InstallationDeletionPreflightSnapshot.Comparison, owner: Any): InstallationDeletionPreflightResult = when (value) {
        is InstallationDeletionPreflightSnapshot.Comparison.Rejected -> ReleasedRejection(value.reason)
        is InstallationDeletionPreflightSnapshot.Comparison.Active -> ReleasedActive(candidate, value.fingerprint, issuer, owner)
        is InstallationDeletionPreflightSnapshot.Comparison.Authorized ->
            ReleasedAuthorized(candidate, value.fingerprint, issuer, owner, value.publicationReference)

        is InstallationDeletionPreflightSnapshot.Comparison.Completed ->
            ReleasedCompleted(candidate, value.fingerprint, issuer, owner, value.publicationReference)
    }

    private fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "ComplaintInstallationDeletionPreflightOperation(redacted)"

    private enum class Stage { PREPARED, READING, COMPLETE, FAILED }

    /** No retained phase, connection, lock, mutable success setter or raw secret escapes in a continuation. */
    private abstract class ReleasedTuple(
        private val candidate: InstallationDeletionCandidate,
        final override val fingerprint: ComplaintDeleteAllFingerprint,
        private val issuer: Any,
        private val ownerIdentity: Any,
    ) : InstallationDeletionPreflightTuple {
        private val caller = Thread.currentThread()
        final override val installation get() = candidate.installation
        final override val submittedCredentialVersion get() = candidate.credentialVersion
        final override val operationKey get() = candidate.operationKey

        fun requireOwned(selectedIssuer: Any, selectedOwner: Any) {
            if (issuer !== selectedIssuer || ownerIdentity !== selectedOwner || caller !== Thread.currentThread()) {
                throw PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        override fun toString(): String = "InstallationDeletionPreflightTuple(redacted)"
    }

    private class ReleasedActive(candidate: InstallationDeletionCandidate, fingerprint: ComplaintDeleteAllFingerprint, issuer: Any, owner: Any) :
        ReleasedTuple(candidate, fingerprint, issuer, owner), InstallationDeletionPreflightResult.Active

    private class ReleasedAuthorized(
        candidate: InstallationDeletionCandidate,
        fingerprint: ComplaintDeleteAllFingerprint,
        issuer: Any,
        owner: Any,
        override val publicationReference: String,
    ) : ReleasedTuple(candidate, fingerprint, issuer, owner), InstallationDeletionPreflightResult.Authorized

    private class ReleasedCompleted(
        candidate: InstallationDeletionCandidate,
        fingerprint: ComplaintDeleteAllFingerprint,
        issuer: Any,
        owner: Any,
        override val publicationReference: String,
    ) : ReleasedTuple(candidate, fingerprint, issuer, owner), InstallationDeletionPreflightResult.Completed

    private class ReleasedRejection(override val reason: InstallationDeletionPreflightRejection) : InstallationDeletionPreflightResult.Rejected {
        override fun toString(): String = "InstallationDeletionPreflightResult.Rejected($reason)"
    }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        fun capture(jdbc: JdbcTemplate, issuer: Any, candidate: InstallationDeletionCandidate): ComplaintInstallationDeletionPreflightOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            var operation: ComplaintInstallationDeletionPreflightOperation? = null
            try {
                phase.installationDeletionPreflight.requireOperation(jdbc)
                operation = ComplaintInstallationDeletionPreflightOperation(phase, jdbc, issuer, candidate)
                phase.installationDeletionPreflight.retain(operation, jdbc)
                operation.read()
                return operation
            } catch (problem: Throwable) {
                operation?.failed(problem)
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        fun requireOwned(comparison: InstallationDeletionPreflightTuple, issuer: Any, ownerIdentity: Any) {
            requireConnectionFree()
            val retained = comparison as? ReleasedTuple ?: throw PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
            retained.requireOwned(issuer, ownerIdentity)
        }
    }
}
