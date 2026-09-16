package me.manga.kira.backend.complaint.infrastructure.capacity

import jakarta.persistence.EntityManager
import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.audit.domain.CountedInstallationEnrollmentAuditEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationEnrollment
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.InstallationCredentialState
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentRejection
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentResult
import me.manga.kira.backend.complaint.domain.InstallationIdentityState
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Dormant LIVE-only core: counters -> permanent ID -> credential -> scoped audit, on one owned
 * ordinary transaction. No mode/configuration-opening authority, request admission or JWT. A
 * configuration digest match is not W04's aggregate erasure-headroom/reconciliation proof.
 * Test scopes require their real activation-time reserve-share producer and are refused here.
 */
internal class JdbcComplaintInstallationEnrollmentStore(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: ComplaintInstallationEnrollmentAudit,
) : ComplaintInstallationEnrollment {
    override fun enroll(candidate: InstallationEnrollmentCandidate): InstallationEnrollmentResult =
        ComplaintInstallationEnrollmentOperation.prepare(jdbc, candidate).enroll(capacity, audit)

    override fun toString(): String = "JdbcComplaintInstallationEnrollmentStore(redacted)"
}

/** Only locked database absence or a verified exact ACTIVE pair selects the branch; no caller can supply that decision. */
internal class ComplaintInstallationEnrollmentOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val candidate: InstallationEnrollmentCandidate,
) {
    private var stage = Stage.PREPARED
    private var counters: JdbcComplaintCapacityStore.LockedInstallationEnrollment? = null
    private var identity: LockedIdentity? = null
    private var credential: LockedCredential? = null
    private var time: DatabaseTime? = null
    private var result: InstallationEnrollmentResult? = null

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected

    internal fun ownerReferenceIsDistinct(reference: UUID): Boolean = reference != candidate.installation.id

    internal fun completedResult(selected: PersistencePhaseContext): InstallationEnrollmentResult? =
        if (phase === selected && stage === Stage.COMPLETE) result else null

    @Suppress("TooGenericExceptionCaught")
    internal fun enroll(capacity: JdbcComplaintCapacityStore, audit: ComplaintInstallationEnrollmentAudit): InstallationEnrollmentResult {
        try {
            requireAt(Stage.PREPARED, jdbc)
            stage = Stage.COUNTERS_REQUESTED
            val locked = capacity.lockForInstallationEnrollment(this)
            requireAt(Stage.COUNTERS_LOCKING, jdbc)
            check(locked.belongsTo(this))
            counters = locked
            stage = Stage.IDENTITY_LOCKING
            identity = jdbc.query(LOCK_IDENTITY, { row, _ -> readIdentity(row) }, candidate.installation.id).singleOrNull()
            requireAt(Stage.IDENTITY_LOCKING, jdbc)
            stage = Stage.CREDENTIAL_LOCKING
            credential = jdbc.query(LOCK_CREDENTIAL, { row, _ -> readCredential(row) }, candidate.installation.id).singleOrNull()
            requireAt(Stage.CREDENTIAL_LOCKING, jdbc)
            val rejection = storedRejection()
            val outcome = when {
                rejection != null -> InstallationEnrollmentResult.Rejected(rejection)
                identity == null -> create(locked, audit)
                else -> replay()
            }
            result = outcome
            stage = Stage.COMPLETE
            return outcome
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    private fun create(
        locked: JdbcComplaintCapacityStore.LockedInstallationEnrollment,
        audit: ComplaintInstallationEnrollmentAudit,
    ): InstallationEnrollmentResult {
        val ownerReference = phase.installationEnrollment.ownerReference(this, jdbc)
        sampleTime()
        checkAdmittedWrite()
        stage = Stage.CHARGING
        val rejection = locked.chargeNewIdentity(this)
        requireAt(Stage.CHARGING, jdbc)
        if (rejection != null) return rejection
        check(locked.chargedFor(this))
        val at = Timestamp.from(checkNotNull(time).instant)
        checkAdmittedWrite()
        stage = Stage.INSERTING_IDENTITY
        check(jdbc.update(INSERT_IDENTITY, candidate.installation.id, candidate.installation.scope.id, at) == 1)
        requireAt(Stage.INSERTING_IDENTITY, jdbc)
        stage = Stage.INSERTING_CREDENTIAL
        checkAdmittedWrite()
        check(
            jdbc.update(
                INSERT_CREDENTIAL,
                candidate.installation.id,
                candidate.installation.scope.id,
                candidate.verifierBytes(),
                candidate.platform.name,
                ownerReference,
                at,
                at,
            ) == 1,
        )
        requireAt(Stage.INSERTING_CREDENTIAL, jdbc)
        checkAdmittedWrite()
        stage = Stage.AUDITING
        audit.record(candidate.installation.scope, locked, checkNotNull(time).instant)
        requireAt(Stage.AUDITING, jdbc)
        check(locked.completedFor(this)) // A missing/fake audit writer cannot turn paired INSERTs into a successful enrollment.
        return InstallationEnrollmentResult.Enrolled(InstallationEnrollmentDisposition.CREATED, candidate.installation, 1, checkNotNull(time).instant)
    }

    private fun replay(): InstallationEnrollmentResult {
        val originalIdentity = checkNotNull(identity) // An orphan is never enrollable absence.
        val original = checkNotNull(credential) // A permanently reserved, credential-free UUID is never repopulated.
        check(originalIdentity.state === InstallationIdentityState.ACTIVE && original.state === InstallationCredentialState.ACTIVE)
        val nextVersion = Math.addExact(original.rowVersion, 1L)
        sampleTime()
        checkAdmittedWrite()
        stage = Stage.REFRESHING_REPLAY
        check(
            jdbc.update(
                REFRESH_REPLAY,
                Timestamp.from(checkNotNull(time).instant), nextVersion, candidate.installation.id, candidate.installation.scope.id,
                original.verifier, original.platform?.name, original.credentialVersion, original.rowVersion,
            ) == 1,
        )
        requireAt(Stage.REFRESHING_REPLAY, jdbc)
        return InstallationEnrollmentResult.Enrolled(
            InstallationEnrollmentDisposition.EXACT_REPLAY,
            candidate.installation,
            original.credentialVersion,
            checkNotNull(time).instant,
        )
    }

    private fun storedRejection(): InstallationEnrollmentRejection? {
        val originalIdentity = identity
        val original = credential
        requireCoherentPair(originalIdentity, original)
        if (original != null && !MessageDigest.isEqual(candidate.verifierBytes(), original.verifier)) {
            return InstallationEnrollmentRejection.INSTALLATION_CREDENTIAL_REJECTED
        }
        // A permanent terminal reservation is burned globally, not enrollable under a different scope.
        val terminal = when (originalIdentity?.state) {
            InstallationIdentityState.RETIRED, InstallationIdentityState.RECOVERY_RESERVED -> InstallationEnrollmentRejection.INSTALLATION_RETIRED
            InstallationIdentityState.DELETED -> InstallationEnrollmentRejection.INSTALLATION_DELETED
            else -> null
        }
        if (terminal != null) return terminal
        if (originalIdentity != null && originalIdentity.scope != candidate.installation.scope) {
            return InstallationEnrollmentRejection.INSTALLATION_SCOPE_MISMATCH
        }
        return when {
            originalIdentity?.state === InstallationIdentityState.DELETION_PENDING -> InstallationEnrollmentRejection.INSTALLATION_DELETION_PENDING
            original != null && original.platform !== candidate.platform -> InstallationEnrollmentRejection.INSTALLATION_PLATFORM_MISMATCH
            else -> null
        }
    }

    private fun requireCoherentPair(originalIdentity: LockedIdentity?, original: LockedCredential?) {
        if (originalIdentity == null) {
            check(original == null)
        } else if (original == null) {
            check(
                originalIdentity.state in setOf(
                    InstallationIdentityState.RETIRED,
                    InstallationIdentityState.DELETED,
                    InstallationIdentityState.RECOVERY_RESERVED,
                ),
            )
        } else {
            check(originalIdentity.scope == original.scope)
            val expected = when (originalIdentity.state) {
                InstallationIdentityState.ACTIVE -> InstallationCredentialState.ACTIVE
                InstallationIdentityState.DELETION_PENDING -> InstallationCredentialState.DELETION_PENDING
                InstallationIdentityState.DELETED -> InstallationCredentialState.DELETED
                else -> error("Inconsistent installation pair.")
            }
            check(original.state === expected)
        }
    }

    private fun sampleTime() {
        requireAt(Stage.CREDENTIAL_LOCKING, jdbc)
        stage = Stage.SAMPLING_TIME
        time = jdbc.query(DATABASE_TIME, { row, _ ->
            check(requiredBoolean(row, "finite_time"))
            val instant = checkNotNull(row.getTimestamp("observed_at")).toInstant()
            val day = requiredLong(row, "utc_epoch_day")
            check(day == instant.atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay())
            DatabaseTime(instant, day)
        }).single()
        requireAt(Stage.SAMPLING_TIME, jdbc)
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireAt(Stage.COUNTERS_REQUESTED, selected)
        stage = Stage.COUNTERS_LOCKING
    }

    /** Invoked only by the concrete locked allocation, before identity locks or any counter rollover/update. */
    internal fun checkAdmissionBounds(
        locked: JdbcComplaintCapacityStore.LockedInstallationEnrollment,
        selected: JdbcTemplate,
        ledger: ComplaintCapacityLedger,
        daily: ComplaintDailyAdmission,
    ) {
        requireAt(Stage.COUNTERS_LOCKING, selected)
        check(locked.belongsTo(this))
        phase.installationEnrollment.checkAdmittedBounds(this, selected, ledger, daily)
    }

    internal fun requireNewIdentityCharge(locked: JdbcComplaintCapacityStore.LockedInstallationEnrollment, selected: JdbcTemplate) {
        requireAt(Stage.CHARGING, selected)
        if (counters !== locked || identity != null) {
            failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
        }
        if (credential != null || time == null) {
            failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
        }
        checkAdmittedWrite() // Also rechecked before each counter/daily update inside the retained allocation.
    }

    internal fun databaseUtcEpochDay(selected: JdbcTemplate): Long {
        requireAt(Stage.CHARGING, selected)
        return checkNotNull(time).utcEpochDay
    }

    internal fun databaseInstant(selected: JdbcTemplate): Instant {
        requireAt(Stage.CHARGING, selected)
        return checkNotNull(time).instant
    }

    internal fun requireAuditWrite(locked: JdbcComplaintCapacityStore.LockedInstallationEnrollment, selected: JdbcTemplate) {
        requireAt(Stage.AUDITING, selected)
        if (counters !== locked || !locked.chargedFor(this)) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
        checkAdmittedWrite()
    }

    internal fun auditEntityManager(
        locked: JdbcComplaintCapacityStore.LockedInstallationEnrollment,
        entry: CountedInstallationEnrollmentAuditEntry,
        selected: JdbcTemplate,
    ): EntityManager {
        requireAuditWrite(locked, selected)
        if (entry.scope != candidate.installation.scope || entry.createdAt != checkNotNull(time).instant) {
            failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
        }
        return phase.installationEnrollment.entityManager(this, selected)
    }

    internal fun failed(problem: Throwable): Nothing {
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun requireAt(expected: Stage, selected: JdbcTemplate) {
        phase.installationEnrollment.requireRetained(this, selected)
        if (selected !== jdbc || stage !== expected) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
    }

    private fun checkAdmittedWrite() = phase.installationEnrollment.checkAdmittedWrite(this, jdbc)

    private fun readIdentity(row: ResultSet): LockedIdentity {
        val scope = requireStoredIdentity(row)
        val state = InstallationIdentityState.valueOf(checkNotNull(row.getString("state")))
        checkNotNull(row.getTimestamp("created_at"))
        val terminal = row.getTimestamp("terminal_at")
        check((state in setOf(InstallationIdentityState.RETIRED, InstallationIdentityState.DELETED)) == (terminal != null))
        return LockedIdentity(scope, state)
    }

    private fun readCredential(row: ResultSet): LockedCredential {
        val scope = requireStoredIdentity(row)
        val state = InstallationCredentialState.valueOf(checkNotNull(row.getString("state")))
        val verifier = checkNotNull(row.getBytes("secret_verifier"))
        check(verifier.size == 32)
        val platform = row.getString("platform")?.let(ComplaintPlatform::valueOf)
        val ownerReference = row.getObject("owner_reference", UUID::class.java)
        checkNotNull(row.getTimestamp("created_at"))
        val authenticatedAt = row.getTimestamp("last_authenticated_at")
        val deletedAt = row.getTimestamp("deleted_at")?.toInstant()
        val expiresAt = row.getTimestamp("verifier_expires_at")?.toInstant()
        if (state === InstallationCredentialState.DELETED) {
            check(platform == null && ownerReference == null && authenticatedAt == null)
            check(deletedAt != null && expiresAt == deletedAt.plusSeconds(192 * 3_600L))
        } else {
            check(platform != null && ownerReference != null && ownerReference != candidate.installation.id)
            check(ownerReference.version() == 4 && ownerReference.variant() == 2)
            check(authenticatedAt != null && deletedAt == null && expiresAt == null)
        }
        val credentialVersion = requiredLong(row, "credential_version")
        val rowVersion = requiredLong(row, "version")
        check(credentialVersion > 0 && rowVersion > 0)
        return LockedCredential(scope, state, verifier, platform, credentialVersion, rowVersion)
    }

    private fun requireStoredIdentity(row: ResultSet): ComplaintDataScope {
        check(row.getObject("id", UUID::class.java) == candidate.installation.id)
        val scope = ComplaintDataScope.of(checkNotNull(row.getObject("data_scope_id", UUID::class.java)))
        check(requiredBoolean(row, "test_only") == scope.testOnly)
        check(requiredBoolean(row, "finite_times"))
        return scope
    }

    override fun toString(): String = "ComplaintInstallationEnrollmentOperation(redacted)"

    private class LockedIdentity(val scope: ComplaintDataScope, val state: InstallationIdentityState)

    private class LockedCredential(
        val scope: ComplaintDataScope,
        val state: InstallationCredentialState,
        val verifier: ByteArray,
        val platform: ComplaintPlatform?,
        val credentialVersion: Long,
        val rowVersion: Long,
    )

    private class DatabaseTime(val instant: Instant, val utcEpochDay: Long)

    private enum class Stage {
        PREPARED,
        COUNTERS_REQUESTED,
        COUNTERS_LOCKING,
        IDENTITY_LOCKING,
        CREDENTIAL_LOCKING,
        SAMPLING_TIME,
        CHARGING,
        INSERTING_IDENTITY,
        INSERTING_CREDENTIAL,
        AUDITING,
        REFRESHING_REPLAY,
        COMPLETE,
    }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun prepare(jdbc: JdbcTemplate, candidate: InstallationEnrollmentCandidate): ComplaintInstallationEnrollmentOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.installationEnrollment.requireOperation(jdbc)
                check(!candidate.installation.scope.testOnly)
                val operation = ComplaintInstallationEnrollmentOperation(phase, jdbc, candidate)
                phase.installationEnrollment.retain(operation, jdbc)
                phase.installationEnrollment.claimAdmitted(operation, jdbc, candidate)
                return operation
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        private val LOCK_IDENTITY = """
            SELECT id, data_scope_id, test_only, state, created_at, terminal_at,
                isfinite(created_at) AND (terminal_at IS NULL OR isfinite(terminal_at)) AS finite_times
            FROM complaint_installation_ids WHERE id = ? FOR UPDATE
        """.trimIndent()
        private val LOCK_CREDENTIAL = """
            SELECT id, data_scope_id, test_only, secret_verifier, platform, state, credential_version, owner_reference,
                created_at, last_authenticated_at, deleted_at, verifier_expires_at, version,
                isfinite(created_at) AND (last_authenticated_at IS NULL OR isfinite(last_authenticated_at))
                    AND (deleted_at IS NULL OR isfinite(deleted_at)) AND (verifier_expires_at IS NULL OR isfinite(verifier_expires_at)) AS finite_times
            FROM app_installations WHERE id = ? FOR UPDATE
        """.trimIndent()
        private val DATABASE_TIME = """
            WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS observed_at)
            SELECT observed_at, isfinite(observed_at) AS finite_time,
                ((observed_at AT TIME ZONE 'UTC')::date - DATE '1970-01-01')::bigint AS utc_epoch_day
            FROM sampled
        """.trimIndent()
        private val INSERT_IDENTITY = """
            INSERT INTO complaint_installation_ids (id, data_scope_id, test_only, state, created_at)
            VALUES (?, ?, false, 'ACTIVE', ?)
        """.trimIndent()
        private val INSERT_CREDENTIAL = """
            INSERT INTO app_installations (id, data_scope_id, test_only, secret_verifier, platform, state, credential_version,
                owner_reference, created_at, last_authenticated_at, version)
            VALUES (?, ?, false, ?, ?, 'ACTIVE', 1, ?, ?, ?, 1)
        """.trimIndent()
        private val REFRESH_REPLAY = """
            UPDATE app_installations SET last_authenticated_at = greatest(last_authenticated_at, ?), version = ?
            WHERE id = ? AND data_scope_id = ? AND NOT test_only AND state = 'ACTIVE'
                AND secret_verifier = ? AND platform = ? AND credential_version = ? AND version = ?
        """.trimIndent()

        private fun requiredLong(row: ResultSet, column: String): Long = row.getLong(column).also { check(!row.wasNull()) }

        private fun requiredBoolean(row: ResultSet, column: String): Boolean = row.getBoolean(column).also { check(!row.wasNull()) }
    }
}
