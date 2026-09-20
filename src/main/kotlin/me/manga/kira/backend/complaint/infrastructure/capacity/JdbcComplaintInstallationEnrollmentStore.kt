package me.manga.kira.backend.complaint.infrastructure.capacity

import jakarta.persistence.EntityManager
import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.audit.domain.CountedInstallationEnrollmentAuditEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationEnrollment
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunObservation
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunState
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.InstallationCredentialState
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentRejection
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentResult
import me.manga.kira.backend.complaint.domain.InstallationIdentityState
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationTestBinding
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationTestComparison
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationTestRunRows
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Dormant core: counters -> exact TEST run when configured -> permanent ID -> credential -> scoped audit, on one owned
 * ordinary transaction. No mode/configuration-opening authority, request admission or JWT. A
 * configuration digest match is not W04's aggregate erasure-headroom/reconciliation proof.
 * TEST requires independent constructor-captured desired D. Matching an ACTIVE row/reserve is not activation provenance.
 */
internal class JdbcComplaintInstallationEnrollmentStore private constructor(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: ComplaintInstallationEnrollmentAudit,
    private val testBinding: ComplaintInstallationTestBinding?,
) : ComplaintInstallationEnrollment {
    /** Explicit compatibility seam: LIVE only, with no invented desired configuration. */
    constructor(jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore, audit: ComplaintInstallationEnrollmentAudit) : this(jdbc, capacity, audit, null)

    constructor(
        jdbc: JdbcTemplate,
        capacity: JdbcComplaintCapacityStore,
        audit: ComplaintInstallationEnrollmentAudit,
        desired: ComplaintInstallationDesiredSettings.Configured,
    ) : this(jdbc, capacity, audit, ComplaintInstallationTestBinding(desired))

    constructor(jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore, audit: ComplaintInstallationEnrollmentAudit,
        registration: ComplaintTestNamespaceRegistrationV1) : this(jdbc, capacity, audit, ComplaintInstallationTestBinding(registration))

    override fun enroll(candidate: InstallationEnrollmentCandidate): InstallationEnrollmentResult = (
        testBinding?.let { ComplaintInstallationEnrollmentOperation.prepareTest(jdbc, candidate, it) }
            ?: ComplaintInstallationEnrollmentOperation.prepare(jdbc, candidate)
        ).enroll(capacity, audit)

    override fun toString(): String = "JdbcComplaintInstallationEnrollmentStore(redacted)"
}

/** Only locked database absence or a verified exact ACTIVE pair selects the branch; no caller can supply that decision. */
internal class ComplaintInstallationEnrollmentOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val candidate: InstallationEnrollmentCandidate,
    private val testBinding: ComplaintInstallationTestBinding?,
) {
    private var stage = Stage.PREPARED
    private var counters: JdbcComplaintCapacityStore.LockedInstallationEnrollment? = null
    private var identity: LockedIdentity? = null
    private var credential: LockedCredential? = null
    private var run: LockedRun? = null
    private var remainingTestReserve: ComplaintCapacityVector? = null
    private var runUpdated = false
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
            val scopeRejection = lockTestRun()
            if (scopeRejection != null) {
                val rejected = InstallationEnrollmentResult.Rejected(scopeRejection)
                result = rejected
                stage = Stage.COMPLETE
                return rejected
            }
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
            if (outcome is InstallationEnrollmentResult.Enrolled) testBinding?.requireCurrent(jdbc)
            result = outcome
            stage = Stage.COMPLETE
            return outcome
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    private fun lockTestRun(): InstallationEnrollmentRejection? {
        val binding = testBinding ?: return null
        requireAt(Stage.COUNTERS_LOCKING, jdbc)
        stage = Stage.RUN_LOCKING
        val scope = candidate.installation.scope
        val observed = if (scope.testOnly) {
            run = jdbc.query(LOCK_RUN, { row, _ -> readRun(row) }, scope.id).singleOrNull()
            run?.observation ?: ComplaintInstallationRunObservation.Absent(scope)
        } else {
            null
        }
        requireAt(Stage.RUN_LOCKING, jdbc)
        return when (binding.compare(scope, observed)) {
            ComplaintInstallationTestComparison.MATCHING_COMPARISON -> { binding.requireCurrent(jdbc); null }
            ComplaintInstallationTestComparison.SCOPE_MISMATCH -> InstallationEnrollmentRejection.INSTALLATION_SCOPE_MISMATCH
            ComplaintInstallationTestComparison.SCOPE_RETIRED -> InstallationEnrollmentRejection.INSTALLATION_SCOPE_RETIRED
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
        if (testBinding != null) updateRun()
        val at = Timestamp.from(checkNotNull(time).instant)
        checkAdmittedWrite()
        stage = Stage.INSERTING_IDENTITY
        check(jdbc.update(INSERT_IDENTITY, candidate.installation.id, candidate.installation.scope.id, candidate.installation.scope.testOnly, at) == 1)
        requireAt(Stage.INSERTING_IDENTITY, jdbc)
        stage = Stage.INSERTING_CREDENTIAL
        checkAdmittedWrite()
        check(
            jdbc.update(
                INSERT_CREDENTIAL,
                candidate.installation.id,
                candidate.installation.scope.id,
                candidate.installation.scope.testOnly,
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
                candidate.installation.scope.testOnly,
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

    internal fun creationCharge(selected: JdbcTemplate): ComplaintCapacityVector {
        requireAt(Stage.CHARGING, selected)
        return if (testBinding == null) ComplaintCapacityCharges.INSTALLATION_ENROLLMENT else ComplaintCapacityCharges.AUDIT
    }

    internal fun testSlotAvailable(selected: JdbcTemplate): Boolean {
        requireAt(Stage.CHARGING, selected)
        if (testBinding == null) return true
        val active = checkNotNull(checkNotNull(run).active)
        return active.enrolled < active.limit && TEST_INSTALLATION_SHARE.fitsWithin(active.unused)
    }

    /** P authenticates only the locked counter declarations; the independently checked run binding uses D. */
    internal fun chargeLedger(selected: JdbcTemplate, ledger: ComplaintCapacityLedger, expectedPolicyDigest: ByteArray): ComplaintCapacityLedger {
        requireAt(Stage.CHARGING, selected)
        val afterReserve = if (testBinding == null) {
            ledger
        } else {
            val active = checkNotNull(checkNotNull(run).active)
            check(active.enrolled < active.limit && remainingTestReserve == null)
            remainingTestReserve = active.unused - TEST_INSTALLATION_SHARE
            ledger.spendTestReserve(expectedPolicyDigest, TEST_INSTALLATION_SHARE, ComplaintCapacityVector.ZERO)
        }
        return afterReserve.chargeCreation(expectedPolicyDigest, creationCharge(selected))
    }

    private fun updateRun() {
        requireAt(Stage.CHARGING, jdbc)
        val original = checkNotNull(run)
        val active = checkNotNull(original.active)
        check(!runUpdated)
        checkAdmittedWrite()
        stage = Stage.UPDATING_RUN
        check(
            jdbc.update(
                ENROLL_RUN,
                Math.addExact(active.enrolled, 1L), checkNotNull(remainingTestReserve).sqlArray(),
                candidate.installation.scope.id, original.observation.configurationHashBytes(), active.version,
                active.limit, active.enrolled, active.original.sqlArray(), active.unused.sqlArray(),
                active.activationGeneration, active.activationHash,
            ) == 1,
        )
        requireAt(Stage.UPDATING_RUN, jdbc)
        runUpdated = true
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
        check(testBinding == null || runUpdated)
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
        testBinding?.registration?.let { phase.installationEnrollment.requireRegistered(this, jdbc, it) }
    }

    private fun checkAdmittedWrite() {
        testBinding?.registration?.let { phase.installationEnrollment.requireRegistered(this, jdbc, it) }
        testBinding?.requireCurrent(jdbc)
        phase.installationEnrollment.checkAdmittedWrite(this, jdbc)
    }

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

    private fun readRun(row: ResultSet): LockedRun {
        val observed = ComplaintInstallationTestRunRows.read(row, candidate.installation.scope) as ComplaintInstallationRunObservation.Present
        if (observed.state !== ComplaintInstallationRunState.ACTIVE) return LockedRun(observed, null)
        val version = requiredInt(row, "accounting_version")
        ComplaintCapacityEncoding.requireVersion(version)
        val limit = requiredLong(row, "installation_limit")
        val enrolled = requiredLong(row, "enrolled_count")
        check(limit > 0 && enrolled in 0L..limit)
        val generation = requiredLong(row, "activation_catalog_generation")
        val hash = checkNotNull(row.getBytes("activation_catalog_hash"))
        check(generation in 1L..65_536L && hash.size == 32)
        val original = readVector(row, "original", version)
        val unused = readVector(row, "unused", version)
        check(unused.fitsWithin(original))
        // This lower comparison does not prove complete terminal sizing or catalog provenance.
        check(TEST_INSTALLATION_SHARE.scaled(limit).fitsWithin(original))
        check(TEST_INSTALLATION_SHARE.scaled(limit - enrolled).fitsWithin(unused))
        return LockedRun(observed, ActiveRun(version, limit, enrolled, original, unused, generation, hash))
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

    private class LockedRun(val observation: ComplaintInstallationRunObservation.Present, val active: ActiveRun?)

    private class ActiveRun(
        val version: Int,
        val limit: Long,
        val enrolled: Long,
        val original: ComplaintCapacityVector,
        val unused: ComplaintCapacityVector,
        val activationGeneration: Long,
        val activationHash: ByteArray,
    )

    private enum class Stage {
        PREPARED,
        COUNTERS_REQUESTED,
        COUNTERS_LOCKING,
        RUN_LOCKING,
        IDENTITY_LOCKING,
        CREDENTIAL_LOCKING,
        SAMPLING_TIME,
        CHARGING,
        UPDATING_RUN,
        INSERTING_IDENTITY,
        INSERTING_CREDENTIAL,
        AUDITING,
        REFRESHING_REPLAY,
        COMPLETE,
    }

    companion object {
        internal fun prepare(jdbc: JdbcTemplate, candidate: InstallationEnrollmentCandidate): ComplaintInstallationEnrollmentOperation =
            capture(jdbc, candidate, null)

        internal fun prepareTest(
            jdbc: JdbcTemplate,
            candidate: InstallationEnrollmentCandidate,
            binding: ComplaintInstallationTestBinding,
        ): ComplaintInstallationEnrollmentOperation = capture(jdbc, candidate, binding)

        @Suppress("TooGenericExceptionCaught")
        private fun capture(
            jdbc: JdbcTemplate,
            candidate: InstallationEnrollmentCandidate,
            binding: ComplaintInstallationTestBinding?,
        ): ComplaintInstallationEnrollmentOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.installationEnrollment.requireOperation(jdbc)
                check(binding != null || !candidate.installation.scope.testOnly)
                val operation = ComplaintInstallationEnrollmentOperation(phase, jdbc, candidate, binding)
                phase.installationEnrollment.retain(operation, jdbc)
                phase.installationEnrollment.claimAdmitted(operation, jdbc, candidate)
                return operation
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        private val TEST_INSTALLATION_SHARE = ComplaintCapacityCharges.INSTALLATION_ID + ComplaintCapacityCharges.INSTALLATION_CREDENTIAL
        private val LOCK_RUN = """
            SELECT ${ComplaintInstallationTestRunRows.columns}, r.accounting_version, r.installation_limit, r.enrolled_count,
                r.activation_catalog_generation, r.activation_catalog_hash,
                r.original_reserve, array_ndims(r.original_reserve) AS original_dimensions,
                array_lower(r.original_reserve, 1) AS original_lower, cardinality(r.original_reserve) AS original_width,
                r.unused_reserve, array_ndims(r.unused_reserve) AS unused_dimensions,
                array_lower(r.unused_reserve, 1) AS unused_lower, cardinality(r.unused_reserve) AS unused_width
            FROM complaint_test_runs r WHERE r.data_scope_id = ? FOR UPDATE
        """.trimIndent()
        private val ENROLL_RUN = """
            UPDATE complaint_test_runs SET enrolled_count = ?, unused_reserve = ?::bigint[]
            WHERE data_scope_id = ? AND test_only AND state = 'ACTIVE' AND configuration_hash = ? AND accounting_version = ?
                AND installation_limit = ? AND enrolled_count = ? AND original_reserve = ?::bigint[] AND unused_reserve = ?::bigint[]
                AND activation_catalog_generation = ? AND activation_catalog_hash = ?
        """.trimIndent()

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
            VALUES (?, ?, ?, 'ACTIVE', ?)
        """.trimIndent()
        private val INSERT_CREDENTIAL = """
            INSERT INTO app_installations (id, data_scope_id, test_only, secret_verifier, platform, state, credential_version,
                owner_reference, created_at, last_authenticated_at, version)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, ?, 1)
        """.trimIndent()
        private val REFRESH_REPLAY = """
            UPDATE app_installations SET last_authenticated_at = greatest(last_authenticated_at, ?), version = ?
            WHERE id = ? AND data_scope_id = ? AND test_only = ? AND state = 'ACTIVE'
                AND secret_verifier = ? AND platform = ? AND credential_version = ? AND version = ?
        """.trimIndent()

        private fun readVector(row: ResultSet, prefix: String, version: Int): ComplaintCapacityVector {
            check(requiredInt(row, "${prefix}_dimensions") == 1 && requiredInt(row, "${prefix}_lower") == 1)
            check(requiredInt(row, "${prefix}_width") == ComplaintCapacityEncoding.WIDTH)
            val array = checkNotNull(row.getArray("${prefix}_reserve"))
            try {
                check(array.baseType == Types.BIGINT)
                val values = array.array as? Array<*> ?: error("Invalid test reserve representation.")
                check(values.size == ComplaintCapacityEncoding.WIDTH)
                return ComplaintCapacityVector.of(LongArray(values.size) { checkNotNull(values[it] as? Long) }, version)
            } finally {
                array.free()
            }
        }

        private fun ComplaintCapacityVector.sqlArray(): String = toLongArray().joinToString(separator = ",", prefix = "{", postfix = "}")

        private fun requiredInt(row: ResultSet, column: String): Int = row.getInt(column).also { check(!row.wasNull()) }

        private fun requiredLong(row: ResultSet, column: String): Long = row.getLong(column).also { check(!row.wasNull()) }

        private fun requiredBoolean(row: ResultSet, column: String): Boolean = row.getBoolean(column).also { check(!row.wasNull()) }
    }
}
