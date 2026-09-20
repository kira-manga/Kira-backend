package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunObservation
import me.manga.kira.backend.complaint.domain.ComplaintInstallationSession
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.InstallationCredentialState
import me.manga.kira.backend.complaint.domain.InstallationIdentityState
import me.manga.kira.backend.complaint.domain.InstallationSessionCandidate
import me.manga.kira.backend.complaint.domain.InstallationSessionPreflight
import me.manga.kira.backend.complaint.domain.InstallationSessionRejection
import me.manga.kira.backend.complaint.domain.InstallationSessionResult
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.SessionPreflightResult
import me.manga.kira.backend.complaint.domain.SessionRefreshResult
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Dormant database comparison core. Explicit TEST binding does not supply a bean, JWT, activation proof or semantic admission. */
internal class JdbcComplaintInstallationSessionStore private constructor(
    private val jdbc: JdbcTemplate,
    private val testBinding: ComplaintInstallationTestBinding?,
) : ComplaintInstallationSession {
    /** Original LIVE-only compatibility seam; no default desired D is fabricated. */
    constructor(jdbc: JdbcTemplate) : this(jdbc, null)

    constructor(jdbc: JdbcTemplate, desired: ComplaintInstallationDesiredSettings.Configured) : this(jdbc, ComplaintInstallationTestBinding(desired))
    constructor(jdbc: JdbcTemplate, registration: ComplaintTestNamespaceRegistrationV1) : this(jdbc, ComplaintInstallationTestBinding(registration))

    private val issuer = Any()

    override fun preflight(candidate: InstallationSessionCandidate): SessionPreflightResult =
        ComplaintInstallationSessionOperation.preparePreflight(jdbc, issuer, candidate, testBinding).preflight()

    override fun refresh(preflight: InstallationSessionPreflight): SessionRefreshResult =
        ComplaintInstallationSessionOperation.prepareRefresh(jdbc, issuer, preflight, testBinding).refresh()

    override fun toString(): String = "JdbcComplaintInstallationSessionStore(redacted)"
}

/** Exact retained operation; neither a fabricated result nor a caught store failure can complete its phase. */
internal class ComplaintInstallationSessionOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val issuer: Any,
    private val path: PersistencePhasePath,
    private val candidate: InstallationSessionCandidate,
    private val testBinding: ComplaintInstallationTestBinding?,
    private val expectedCredentialVersion: Long? = null,
) {
    private var stage = Stage.PREPARED
    private var result: InstallationSessionResult? = null

    internal fun belongsTo(selected: PersistencePhaseContext, selectedPath: PersistencePhasePath): Boolean = phase === selected && path === selectedPath

    internal fun completedResult(selected: PersistencePhaseContext): InstallationSessionResult? =
        if (phase === selected && stage === Stage.COMPLETE) result else null

    @Suppress("TooGenericExceptionCaught")
    internal fun preflight(): SessionPreflightResult {
        try {
            requireAt(Stage.PREPARED)
            check(path === PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_PREFLIGHT)
            stage = Stage.READING_SNAPSHOT
            // One statement/MVCC snapshot, including both absences: concurrent retirement cannot
            // turn two independently read versions into a fictitious orphan or authenticated pair.
            val snapshot = if (testBinding == null) {
                val pair = jdbc.query(READ_PAIR, { row, _ -> StoredPair(readIdentity(row), readCredential(row)) }, candidate.installation.id).single()
                Snapshot(pair, null)
            } else {
                jdbc.query(READ_TEST_PAIR, { row, _ ->
                    val rejection = scopeRejection(ComplaintInstallationTestRunRows.read(row, candidate.installation.scope))
                    Snapshot(if (rejection == null) StoredPair(readIdentity(row), readCredential(row)) else null, rejection)
                }, candidate.installation.id, candidate.installation.scope.id).single()
            }
            requireAt(Stage.READING_SNAPSHOT)
            val rejection = snapshot.scopeRejection ?: checkNotNull(snapshot.pair).rejection(candidate, null, testBinding != null)
            if (rejection == null) testBinding?.requireCurrent(jdbc)
            val completed = if (rejection != null) {
                SessionPreflightResult.Rejected(rejection)
            } else {
                SessionPreflightResult.Ready(
                    Continuation(
                        candidate,
                        checkNotNull(checkNotNull(snapshot.pair).credential).credentialVersion,
                        issuer,
                        phase.installationSession.ownerIdentity(this, jdbc),
                    ),
                )
            }
            result = completed
            stage = Stage.COMPLETE
            return completed
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun refresh(): SessionRefreshResult {
        try {
            requireAt(Stage.PREPARED)
            check(path === PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_REFRESH && expectedCredentialVersion != null)
            val scopeRejection = lockTestRun()
            if (scopeRejection != null) {
                val rejected = SessionRefreshResult.Rejected(scopeRejection)
                result = rejected
                stage = Stage.COMPLETE
                return rejected
            }
            stage = Stage.LOCKING_IDENTITY
            val identity = jdbc.query(LOCK_IDENTITY, { row, _ -> readIdentity(row) }, candidate.installation.id).singleOrNull()
            requireAt(Stage.LOCKING_IDENTITY)
            stage = Stage.LOCKING_CREDENTIAL
            val credential = jdbc.query(LOCK_CREDENTIAL, { row, _ -> readCredential(row) }, candidate.installation.id).singleOrNull()
            requireAt(Stage.LOCKING_CREDENTIAL)
            val rejection = StoredPair(identity, credential).rejection(candidate, expectedCredentialVersion, testBinding != null)
            val completed = if (rejection != null) SessionRefreshResult.Rejected(rejection) else refreshActive(checkNotNull(credential))
            result = completed
            stage = Stage.COMPLETE
            return completed
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    private fun lockTestRun(): InstallationSessionRejection? {
        if (testBinding == null) return null
        requireAt(Stage.PREPARED)
        stage = Stage.LOCKING_RUN
        val scope = candidate.installation.scope
        val observed = if (scope.testOnly) {
            jdbc.query(ComplaintInstallationTestRunRows.lock, { row, _ -> ComplaintInstallationTestRunRows.read(row, scope) }, scope.id).singleOrNull()
                ?: ComplaintInstallationRunObservation.Absent(scope)
        } else {
            null
        }
        requireAt(Stage.LOCKING_RUN)
        return scopeRejection(observed).also { if (it == null) testBinding.requireCurrent(jdbc) }
    }

    private fun scopeRejection(observed: ComplaintInstallationRunObservation?): InstallationSessionRejection? =
        when (checkNotNull(testBinding).compare(candidate.installation.scope, observed)) {
            ComplaintInstallationTestComparison.MATCHING_COMPARISON -> null
            ComplaintInstallationTestComparison.SCOPE_MISMATCH -> InstallationSessionRejection.INSTALLATION_SCOPE_MISMATCH
            ComplaintInstallationTestComparison.SCOPE_RETIRED -> InstallationSessionRejection.INSTALLATION_SCOPE_RETIRED
        }

    private fun refreshActive(credential: StoredCredential): SessionRefreshResult.Refreshed {
        requireAt(Stage.LOCKING_CREDENTIAL)
        stage = Stage.SAMPLING_TIME
        val issuedAt = jdbc.query(DATABASE_TIME, { row, _ ->
            check(requiredBoolean(row, "finite_time"))
            checkNotNull(row.getTimestamp("issued_at")).toInstant()
        }).single()
        requireAt(Stage.SAMPLING_TIME)
        val nextVersion = Math.addExact(credential.rowVersion, 1L)
        testBinding?.requireCurrent(jdbc)
        phase.installationSession.checkAdmittedRefreshWrite(this, jdbc, candidate.installation, credential.credentialVersion)
        stage = Stage.REFRESHING
        check(
            jdbc.update(
                REFRESH_ACTIVE,
                Timestamp.from(issuedAt),
                nextVersion,
                candidate.installation.id,
                candidate.installation.scope.id,
                candidate.installation.scope.testOnly,
                candidate.verifierBytes(),
                credential.credentialVersion,
                credential.rowVersion,
            ) == 1,
        )
        requireAt(Stage.REFRESHING)
        testBinding?.requireCurrent(jdbc)
        return SessionRefreshResult.Refreshed(candidate.installation, credential.credentialVersion, issuedAt)
    }

    private fun requireAt(expected: Stage) {
        phase.installationSession.requireRetained(this, jdbc)
        if (stage !== expected) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
        testBinding?.registration?.let { phase.installationSession.requireRegistered(this, jdbc, it) }
    }

    private fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "ComplaintInstallationSessionOperation(redacted)"

    private fun readIdentity(row: ResultSet): StoredIdentity? {
        val id = row.getObject("identity_id", UUID::class.java) ?: return null
        check(id == candidate.installation.id && requiredBoolean(row, "identity_finite_times"))
        val scope = ComplaintDataScope.of(checkNotNull(row.getObject("identity_scope", UUID::class.java)))
        check(requiredBoolean(row, "identity_test_only") == scope.testOnly)
        val state = InstallationIdentityState.valueOf(checkNotNull(row.getString("identity_state")))
        checkNotNull(row.getTimestamp("identity_created_at"))
        val terminalAt = row.getTimestamp("identity_terminal_at")
        check((state in setOf(InstallationIdentityState.RETIRED, InstallationIdentityState.DELETED)) == (terminalAt != null))
        return StoredIdentity(scope, state)
    }

    private fun readCredential(row: ResultSet): StoredCredential? {
        val id = row.getObject("credential_id", UUID::class.java) ?: return null
        check(id == candidate.installation.id && requiredBoolean(row, "credential_finite_times"))
        val scope = ComplaintDataScope.of(checkNotNull(row.getObject("credential_scope", UUID::class.java)))
        check(requiredBoolean(row, "credential_test_only") == scope.testOnly)
        val state = InstallationCredentialState.valueOf(checkNotNull(row.getString("credential_state")))
        val verifier = checkNotNull(row.getBytes("secret_verifier"))
        check(verifier.size == 32)
        val credentialVersion = requiredLong(row, "credential_version")
        val rowVersion = requiredLong(row, "row_version")
        check(credentialVersion > 0 && rowVersion > 0)
        requireCredentialShape(row, id, state)
        return StoredCredential(scope, state, verifier, credentialVersion, rowVersion)
    }

    private fun requireCredentialShape(row: ResultSet, id: UUID, state: InstallationCredentialState) {
        checkNotNull(row.getTimestamp("credential_created_at"))
        val platform = row.getString("platform")?.let(ComplaintPlatform::valueOf)
        val ownerReference = row.getObject("owner_reference", UUID::class.java)
        val authenticatedAt = row.getTimestamp("last_authenticated_at")
        val deletedAt = row.getTimestamp("deleted_at")?.toInstant()
        val expiresAt = row.getTimestamp("verifier_expires_at")?.toInstant()
        if (state === InstallationCredentialState.DELETED) {
            check(platform == null && ownerReference == null && authenticatedAt == null)
            check(deletedAt != null && expiresAt == deletedAt.plusSeconds(192 * 3_600L))
        } else {
            check(platform != null && ownerReference != null && ownerReference != id)
            check(ownerReference.version() == 4 && ownerReference.variant() == 2)
            check(authenticatedAt != null && deletedAt == null && expiresAt == null)
        }
    }

    private enum class Stage { PREPARED, READING_SNAPSHOT, LOCKING_RUN, LOCKING_IDENTITY, LOCKING_CREDENTIAL, SAMPLING_TIME, REFRESHING, COMPLETE, FAILED }

    private class Snapshot(val pair: StoredPair?, val scopeRejection: InstallationSessionRejection?)

    private class StoredIdentity(val scope: ComplaintDataScope, val state: InstallationIdentityState)

    private class StoredCredential(
        val scope: ComplaintDataScope,
        val state: InstallationCredentialState,
        val verifier: ByteArray,
        val credentialVersion: Long,
        val rowVersion: Long,
    )

    private class StoredPair(val identity: StoredIdentity?, val credential: StoredCredential?) {
        fun rejection(candidate: InstallationSessionCandidate, expectedVersion: Long?, testConfigured: Boolean): InstallationSessionRejection? {
            requireCoherentPair()
            // Unsupported comparison scopes are normal rejections, never authority to fall back to LIVE.
            if (candidate.installation.scope.testOnly && !testConfigured) return InstallationSessionRejection.INSTALLATION_SCOPE_MISMATCH
            if (credential != null && !MessageDigest.isEqual(candidate.verifierBytes(), credential.verifier)) {
                return InstallationSessionRejection.INSTALLATION_CREDENTIAL_REJECTED
            }
            if (identity != null && identity.scope != candidate.installation.scope) return InstallationSessionRejection.INSTALLATION_SCOPE_MISMATCH
            return when (identity?.state) {
                null -> InstallationSessionRejection.INSTALLATION_NOT_FOUND

                InstallationIdentityState.RETIRED, InstallationIdentityState.RECOVERY_RESERVED -> InstallationSessionRejection.INSTALLATION_RETIRED

                InstallationIdentityState.DELETED -> InstallationSessionRejection.INSTALLATION_DELETED

                InstallationIdentityState.DELETION_PENDING -> InstallationSessionRejection.INSTALLATION_DELETION_PENDING

                InstallationIdentityState.ACTIVE -> if (expectedVersion != null && checkNotNull(credential).credentialVersion != expectedVersion) {
                    InstallationSessionRejection.INSTALLATION_CREDENTIAL_REJECTED
                } else {
                    null
                }
            }
        }

        private fun requireCoherentPair() {
            if (identity == null) {
                check(credential == null) // Credential-only storage is corruption, not authenticated absence.
            } else if (credential == null) {
                check(
                    identity.state in setOf(
                        InstallationIdentityState.RETIRED,
                        InstallationIdentityState.DELETED,
                        InstallationIdentityState.RECOVERY_RESERVED,
                    ),
                )
            } else {
                check(identity.scope == credential.scope)
                val expected = when (identity.state) {
                    InstallationIdentityState.ACTIVE -> InstallationCredentialState.ACTIVE
                    InstallationIdentityState.DELETION_PENDING -> InstallationCredentialState.DELETION_PENDING
                    InstallationIdentityState.DELETED -> InstallationCredentialState.DELETED
                    else -> error("Inconsistent installation pair.")
                }
                check(credential.state === expected)
            }
        }
    }

    /** No phase/resource reference or public success setter. This is one-use custody, not a request-admission token. */
    private class Continuation(
        val candidate: InstallationSessionCandidate,
        override val credentialVersion: Long,
        private val issuer: Any,
        private val ownerIdentity: Any,
    ) : InstallationSessionPreflight {
        override val installation: ScopedInstallationId get() = candidate.installation
        private val caller = Thread.currentThread()
        private val state = AtomicReference(ContinuationState.CAPTURED)

        fun release() {
            check(Thread.currentThread() === caller)
            check(state.compareAndSet(ContinuationState.CAPTURED, ContinuationState.RELEASED))
        }

        fun claim(selectedIssuer: Any, selectedOwner: Any) {
            check(Thread.currentThread() === caller)
            check(issuer === selectedIssuer && ownerIdentity === selectedOwner)
            check(state.compareAndSet(ContinuationState.RELEASED, ContinuationState.CLAIMED))
        }

        override fun toString(): String = "InstallationSessionPreflight(redacted)"
    }

    private enum class ContinuationState { CAPTURED, RELEASED, CLAIMED }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun preparePreflight(
            jdbc: JdbcTemplate,
            issuer: Any,
            candidate: InstallationSessionCandidate,
            binding: ComplaintInstallationTestBinding?,
        ): ComplaintInstallationSessionOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.installationSession.requirePreflight(jdbc)
                return ComplaintInstallationSessionOperation(
                    phase,
                    jdbc,
                    issuer,
                    PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_PREFLIGHT,
                    candidate,
                    binding,
                ).also { phase.installationSession.retain(it, jdbc) }
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        internal fun prepareRefresh(
            jdbc: JdbcTemplate,
            issuer: Any,
            preflight: InstallationSessionPreflight,
            binding: ComplaintInstallationTestBinding?,
        ): ComplaintInstallationSessionOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.installationSession.requireRefresh(jdbc)
                val retained = preflight as? Continuation ?: throw PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
                val operation = ComplaintInstallationSessionOperation(
                    phase,
                    jdbc,
                    issuer,
                    PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_REFRESH,
                    retained.candidate,
                    binding,
                    retained.credentialVersion,
                )
                phase.installationSession.retain(operation, jdbc)
                retained.claim(issuer, phase.installationSession.ownerIdentity(operation, jdbc))
                // The exact phase retains admission even when an adapter wrapper calls this legacy store signature.
                phase.installationSession.claimAdmittedRefresh(operation, jdbc, retained, retained.candidate.installation, retained.credentialVersion)
                return operation
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        /** The original phase validates exact identity, known commit and actual release BEFORE making its continuation consumable. */
        @Suppress("TooGenericExceptionCaught")
        internal fun releasePreflight(phase: PersistencePhaseContext, result: SessionPreflightResult?): SessionPreflightResult {
            val completed = phase.installationSession.preflightResult(result)
            try {
                if (completed is SessionPreflightResult.Ready) {
                    val retained = completed.continuation as? Continuation ?: throw PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
                    retained.release()
                }
                return completed
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        private val IDENTITY_COLUMNS = """
            i.id AS identity_id, i.data_scope_id AS identity_scope, i.test_only AS identity_test_only, i.state AS identity_state,
            i.created_at AS identity_created_at, i.terminal_at AS identity_terminal_at,
            isfinite(i.created_at) AND (i.terminal_at IS NULL OR isfinite(i.terminal_at)) AS identity_finite_times
        """.trimIndent()
        private val CREDENTIAL_COLUMNS = """
            c.id AS credential_id, c.data_scope_id AS credential_scope, c.test_only AS credential_test_only, c.state AS credential_state,
            c.secret_verifier, c.platform, c.owner_reference, c.credential_version, c.version AS row_version,
            c.created_at AS credential_created_at, c.last_authenticated_at, c.deleted_at, c.verifier_expires_at,
            isfinite(c.created_at) AND (c.last_authenticated_at IS NULL OR isfinite(c.last_authenticated_at))
                AND (c.deleted_at IS NULL OR isfinite(c.deleted_at)) AND (c.verifier_expires_at IS NULL OR isfinite(c.verifier_expires_at))
                AS credential_finite_times
        """.trimIndent()
        private val READ_PAIR = """
            SELECT $IDENTITY_COLUMNS, $CREDENTIAL_COLUMNS
            FROM (VALUES (?::uuid)) AS requested(id)
            LEFT JOIN complaint_installation_ids i ON i.id = requested.id
            LEFT JOIN app_installations c ON c.id = requested.id
        """.trimIndent()
        private val READ_TEST_PAIR = """
            SELECT $IDENTITY_COLUMNS, $CREDENTIAL_COLUMNS, ${ComplaintInstallationTestRunRows.columns}
            FROM (VALUES (?::uuid, ?::uuid)) AS requested(id, data_scope_id)
            LEFT JOIN complaint_test_runs r ON r.data_scope_id = requested.data_scope_id
            LEFT JOIN complaint_installation_ids i ON i.id = requested.id
            LEFT JOIN app_installations c ON c.id = requested.id
        """.trimIndent()
        private val LOCK_IDENTITY = "SELECT $IDENTITY_COLUMNS FROM complaint_installation_ids i WHERE i.id = ? FOR UPDATE"
        private val LOCK_CREDENTIAL = "SELECT $CREDENTIAL_COLUMNS FROM app_installations c WHERE c.id = ? FOR UPDATE"
        private val DATABASE_TIME = """
            WITH session_time AS MATERIALIZED (SELECT clock_timestamp() AS issued_at)
            SELECT issued_at, isfinite(issued_at) AS finite_time FROM session_time
        """.trimIndent()
        private val REFRESH_ACTIVE = """
            UPDATE app_installations SET last_authenticated_at = greatest(last_authenticated_at, ?), version = ?
            WHERE id = ? AND data_scope_id = ? AND test_only = ? AND state = 'ACTIVE'
                AND secret_verifier = ? AND credential_version = ? AND version = ?
        """.trimIndent()

        private fun requiredLong(row: ResultSet, column: String): Long = row.getLong(column).also { check(!row.wasNull()) }

        private fun requiredBoolean(row: ResultSet, column: String): Boolean = row.getBoolean(column).also { check(!row.wasNull()) }
    }
}
