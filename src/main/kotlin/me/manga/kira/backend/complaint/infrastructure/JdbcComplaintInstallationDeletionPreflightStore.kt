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
import me.manga.kira.backend.security.OwnerDeleteAllJournalCodecV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

/** Fixed read-only comparison producer. No session refresh, enabled bean, admission or deletion writer. */
internal class JdbcComplaintInstallationDeletionPreflightStore(private val jdbc: JdbcTemplate, private val process: OwnerDeleteAllProcessBinding? = null,
    internal val testGraph: TestOwnerDeleteLocalGraphV1? = null) {
    private val issuer = Any()
    init {
        check(process == null || testGraph == null)
        check(testGraph?.recoveryRegistration == null)
        testGraph?.requireOrdinary(jdbc)
        val source = jdbc.dataSource
        if (source is me.manga.kira.backend.common.infrastructure.persistence.GuardedDataSource)
            source.requireTestInitialCheckpointDeletion(testGraph?.initialDeletion?.policy)
    }
    internal fun requireEntry(owner: PersistencePhaseOwnership) {
        testGraph?.initialDeletion?.requireEntry(owner, me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath.COMPLAINT_INSTALLATION_DELETION_PREFLIGHT)
    }
    internal fun bind(phase: PersistencePhaseContext) { testGraph?.initialDeletion?.let(phase::bindInitialDeletionRead) }

    fun read(candidate: InstallationDeletionCandidate): ComplaintInstallationDeletionPreflightOperation =
        ComplaintInstallationDeletionPreflightOperation.capture(jdbc, issuer, candidate, process, testGraph)

    fun requireOwned(comparison: InstallationDeletionPreflightTuple, ownerIdentity: Any) {
        process?.requireOrdinary(jdbc)
        testGraph?.requireOrdinary(jdbc)
        ComplaintInstallationDeletionPreflightOperation.requireOwned(comparison, issuer, ownerIdentity)
    }

    fun bindReplay(
        comparison: InstallationDeletionPreflightResult.Completed,
        ownerIdentity: Any,
        routing: VersionBoundComplaintJournalRouting,
        codec: OwnerDeleteAllJournalCodecV1,
    ): BoundOwnerDeleteAllReplayV1 {
        check(testGraph == null) // The LIVE replay binder is not a registered TEST APPLY route.
        process?.requireOrdinary(jdbc)
        process?.requireInputs(process.desired, routing)
        return ComplaintInstallationDeletionPreflightOperation.bindReplay(comparison, issuer, ownerIdentity, routing, codec)
    }

    override fun toString(): String = "JdbcComplaintInstallationDeletionPreflightStore(read-only)"
}

/** The existing phase retains this exact operation before SQL; no caller result/flag can complete it. */
internal class ComplaintInstallationDeletionPreflightOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val issuer: Any,
    private val candidate: InstallationDeletionCandidate,
    private val process: OwnerDeleteAllProcessBinding?,
    private val testGraph: TestOwnerDeleteLocalGraphV1?,
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
            process?.requireOrdinary(jdbc)
            testGraph?.requireOrdinary(jdbc)
            // Private continuation objects do not even exist before known commit + actual owned release.
            return released ?: release(checkNotNull(comparison), checkNotNull(ownerIdentity)).also { released = it }
        }

    private fun read() {
        requireRetained()
        check(stage === Stage.PREPARED)
        ownerIdentity = phase.installationDeletionPreflight.ownerIdentity(this, jdbc)
        process?.requireOrdinary(jdbc)
        testGraph?.let { it.requireOrdinary(jdbc); phase.requireRegisteredInitialDeletion(it, jdbc) }
        stage = Stage.READING
        val initial = testGraph?.initialDeletion
        val args = arrayOf<Any?>(candidate.installation.id, candidate.installation.scope.id)
        val snapshot = jdbc.query(
            if (initial == null) InstallationDeletionPreflightSnapshot.SQL else InstallationDeletionPreflightSnapshot.REGISTERED_SQL,
            { row, _ ->
                if (initial != null) check(row.getBoolean("registered_current_identity") && !row.wasNull())
                process?.requireSnapshot(row)
                InstallationDeletionPreflightSnapshot.read(row, candidate.installation)
            },
            *(initial?.observationIdentityArguments()?.plus(elements = args) ?: args),
        ).single() // The bounded two-row sentinel refuses multiple receipts/applied versions; it never chooses one.
        requireRetained()
        process?.requireOrdinary(jdbc)
        comparison = snapshot.compare(candidate)
        requireRetained()
        stage = Stage.COMPLETE
    }

    private fun requireRetained() {
        phase.installationDeletionPreflight.requireRetained(this, jdbc)
        testGraph?.requireOrdinary(jdbc)
        phase.requireRegisteredInitialDeletion(testGraph, jdbc)
    }

    private fun release(value: InstallationDeletionPreflightSnapshot.Comparison, owner: Any): InstallationDeletionPreflightResult = when (value) {
        is InstallationDeletionPreflightSnapshot.Comparison.Rejected -> ReleasedRejection(value.reason)

        is InstallationDeletionPreflightSnapshot.Comparison.Active -> ReleasedActive(candidate, value.fingerprint, issuer, owner)

        is InstallationDeletionPreflightSnapshot.Comparison.Authorized ->
            ReleasedAuthorized(candidate, value.fingerprint, issuer, owner, value.publicationReference)

        is InstallationDeletionPreflightSnapshot.Comparison.Completed ->
            ReleasedCompleted(candidate, value.fingerprint, issuer, owner, value.publicationReference, value.replay)
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
        candidate: InstallationDeletionCandidate,
        final override val fingerprint: ComplaintDeleteAllFingerprint,
        private val issuer: Any,
        private val ownerIdentity: Any,
    ) : InstallationDeletionPreflightTuple {
        private val caller = Thread.currentThread()
        final override val installation = candidate.installation
        final override val submittedCredentialVersion = candidate.credentialVersion
        final override val operationKey = candidate.operationKey

        fun requireOwned(selectedIssuer: Any, selectedOwner: Any) {
            if (issuer !== selectedIssuer || ownerIdentity !== selectedOwner || caller !== Thread.currentThread()) {
                throw PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        override fun toString(): String = "InstallationDeletionPreflightTuple(redacted)"
    }

    private class ReleasedActive(candidate: InstallationDeletionCandidate, fingerprint: ComplaintDeleteAllFingerprint, issuer: Any, owner: Any) :
        ReleasedTuple(candidate, fingerprint, issuer, owner),
        InstallationDeletionPreflightResult.Active

    private class ReleasedAuthorized(
        candidate: InstallationDeletionCandidate,
        fingerprint: ComplaintDeleteAllFingerprint,
        issuer: Any,
        owner: Any,
        override val publicationReference: String,
    ) : ReleasedTuple(candidate, fingerprint, issuer, owner),
        InstallationDeletionPreflightResult.Authorized

    private class ReleasedCompleted(
        candidate: InstallationDeletionCandidate,
        fingerprint: ComplaintDeleteAllFingerprint,
        issuer: Any,
        owner: Any,
        override val publicationReference: String,
        private val snapshot: InstallationDeletionCompletedReplaySnapshot,
    ) : ReleasedTuple(candidate, fingerprint, issuer, owner),
        InstallationDeletionPreflightResult.Completed {
        private var boundReplay: BoundOwnerDeleteAllReplayV1? = null

        fun bindReplay(routing: VersionBoundComplaintJournalRouting, codec: OwnerDeleteAllJournalCodecV1): BoundOwnerDeleteAllReplayV1 {
            snapshot.requireBound(this, routing, codec) // Even a cached outcome cannot bypass a mismatched same-J codec check.
            return boundReplay ?: ReleasedBoundReplay(snapshot.completedAt, snapshot.expiresAt).also { boundReplay = it }
        }
    }

    private class ReleasedBoundReplay(override val completedAt: Instant, override val expiresAt: Instant) : BoundOwnerDeleteAllReplayV1 {
        override fun toString(): String = "BoundOwnerDeleteAllReplayV1(redacted,no-runtime-authority)"
    }

    private class ReleasedRejection(override val reason: InstallationDeletionPreflightRejection) : InstallationDeletionPreflightResult.Rejected {
        override fun toString(): String = "InstallationDeletionPreflightResult.Rejected($reason)"
    }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        fun capture(
            jdbc: JdbcTemplate,
            issuer: Any,
            candidate: InstallationDeletionCandidate,
            process: OwnerDeleteAllProcessBinding?,
            testGraph: TestOwnerDeleteLocalGraphV1? = null,
        ): ComplaintInstallationDeletionPreflightOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            var operation: ComplaintInstallationDeletionPreflightOperation? = null
            try {
                phase.installationDeletionPreflight.requireOperation(jdbc)
                phase.requireRegisteredInitialDeletion(testGraph, jdbc)
                operation = ComplaintInstallationDeletionPreflightOperation(phase, jdbc, issuer, candidate, process, testGraph)
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

        fun bindReplay(
            comparison: InstallationDeletionPreflightResult.Completed,
            issuer: Any,
            ownerIdentity: Any,
            routing: VersionBoundComplaintJournalRouting,
            codec: OwnerDeleteAllJournalCodecV1,
        ): BoundOwnerDeleteAllReplayV1 {
            requireConnectionFree()
            val retained = comparison as? ReleasedCompleted ?: throw PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
            retained.requireOwned(issuer, ownerIdentity)
            return retained.bindReplay(routing, codec)
        }
    }
}
