package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.config.KiraAdminStudioProperties
import me.manga.kira.backend.user.domain.Role
import org.springframework.jdbc.core.JdbcTemplate
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.util.Base64
import java.util.UUID

/** Fixed selected-holder statements only. This non-bean adapter neither begins nor completes a transaction. */
internal class JdbcScopedAdminStepUpStore(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val clock: Clock,
    private val properties: KiraAdminStudioProperties,
) {
    internal fun readSnapshot(userId: UUID, scope: ScopedAdminStepUpScope): StepUpUserSnapshot = StepUpUserSnapshot.read(jdbc, userId, scope)

    internal fun issue(verified: VerifiedScopedAdminStepUp): StepUpGrantIssuance = StepUpGrantIssuance.begin(jdbc, verified).insert(capacity, clock, properties)

    override fun toString(): String = "JdbcScopedAdminStepUpStore(redacted)"
}

/**
 * One retained issuance cursor: verified/cleaned snapshot -> exact counters (complaint only) ->
 * locked current Admin -> one real insert. No caller-supplied vector, charge flag or SQL permits it.
 */
internal class StepUpGrantIssuance private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val snapshot: StepUpUserSnapshot,
) {
    val scope: ScopedAdminStepUpScope get() = snapshot.scope
    private var stage = Stage.PREPARED
    private var counters: JdbcComplaintCapacityStore.ChargedStepUpGrant? = null
    private var proof: IssuedScopedAdminStepUp? = null
    private var resultExposed = false

    internal fun belongsTo(candidate: PersistencePhaseContext): Boolean = phase === candidate

    internal fun completedFor(candidate: PersistencePhaseContext): Boolean = phase === candidate && stage === Stage.COMPLETE &&
        (scope === ScopedAdminStepUpScope.SOURCE || counters?.completedFor(this) == true)

    @Suppress("TooGenericExceptionCaught")
    internal fun insert(capacity: JdbcComplaintCapacityStore, clock: Clock, properties: KiraAdminStudioProperties): StepUpGrantIssuance {
        try {
            requireAt(Stage.PREPARED, jdbc)
            if (scope === ScopedAdminStepUpScope.COMPLAINT) {
                stage = Stage.COUNTERS_REQUESTED
                val charged = capacity.chargeStepUpGrant(this)
                requireAt(Stage.COUNTERS_CHARGING, jdbc)
                check(counters === charged && charged.completedFor(this))
            }
            stage = Stage.USER_LOCKING
            lockCurrentAdmin()
            stage = Stage.USER_LOCKED
            insertGrant(clock, properties)
            stage = Stage.COMPLETE
            return this
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    internal fun beginCounterCharge(selected: JdbcTemplate) {
        requireAt(Stage.COUNTERS_REQUESTED, selected)
        check(scope === ScopedAdminStepUpScope.COMPLAINT)
        stage = Stage.COUNTERS_LOCKING
    }

    internal fun retainCounterCharge(charged: JdbcComplaintCapacityStore.ChargedStepUpGrant, selected: JdbcTemplate) {
        requireAt(Stage.COUNTERS_LOCKING, selected)
        if (counters != null || !charged.belongsTo(this)) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
        counters = charged
        stage = Stage.COUNTERS_CHARGING
    }

    internal fun requireCounterCharge(charged: JdbcComplaintCapacityStore.ChargedStepUpGrant, selected: JdbcTemplate) {
        requireAt(Stage.COUNTERS_CHARGING, selected)
        if (counters !== charged) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun result(): IssuedScopedAdminStepUp {
        try {
            phase.checkStepUpIssuanceResult(this)
            check(!resultExposed)
            resultExposed = true
            return checkNotNull(proof)
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    internal fun failed(problem: Throwable): Nothing {
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun requireAt(expected: Stage, selected: JdbcTemplate) {
        phase.requireStepUpIssuance(this, selected)
        if (stage !== expected) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
    }

    private fun lockCurrentAdmin() {
        requireAt(Stage.USER_LOCKING, jdbc)
        val matches = jdbc.query(
            LOCK_USER,
            { result, _ ->
                val id = result.getObject("id", UUID::class.java)
                val hash = result.getString("password_hash")
                val enabled = result.getBoolean("enabled").also { check(!result.wasNull()) }
                id == snapshot.userId && enabled && result.getString("role") == Role.ADMIN.name && hash == snapshot.passwordHash
            },
            snapshot.userId,
        )
        check(matches.size == 1 && matches.single()) // Missing, changed hash, disabled or role-changed: roll the earlier charge back.
        requireAt(Stage.USER_LOCKING, jdbc)
    }

    private fun insertGrant(clock: Clock, properties: KiraAdminStudioProperties) {
        requireAt(Stage.USER_LOCKED, jdbc)
        val now = clock.instant() // Final-phase time, not the earlier snapshot or cleanup cutoff.
        val expiresAt = now.plus(properties.stepUpTtl)
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).let(encoder::encodeToString)
        val id = UUID.randomUUID()
        val hash = Sha256.hexUtf8(token)
        proof = IssuedScopedAdminStepUp(token, expiresAt, scope, id)
        stage = Stage.INSERTING
        requireAt(Stage.INSERTING, jdbc)
        check(jdbc.update(INSERT_GRANT, id, snapshot.userId, hash, scope.storedName, Timestamp.from(now), Timestamp.from(expiresAt)) == 1)
        requireAt(Stage.INSERTING, jdbc)
    }

    override fun toString(): String = "StepUpGrantIssuance(redacted)"

    private enum class Stage {
        PREPARED,
        COUNTERS_REQUESTED,
        COUNTERS_LOCKING,
        COUNTERS_CHARGING,
        USER_LOCKING,
        USER_LOCKED,
        INSERTING,
        COMPLETE,
    }

    companion object {
        private const val TOKEN_BYTES = 32
        private val random = SecureRandom()
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private const val LOCK_USER = "SELECT id, password_hash, enabled, role FROM users WHERE id = ? FOR UPDATE"
        private val INSERT_GRANT = """
            INSERT INTO admin_step_up_grants (id, user_id, token_hash, scope, created_at, expires_at, used_at)
            VALUES (?, ?, ?, ?, ?, ?, NULL)
        """.trimIndent()

        @Suppress("TooGenericExceptionCaught")
        internal fun begin(jdbc: JdbcTemplate, verified: VerifiedScopedAdminStepUp): StepUpGrantIssuance {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.requireStepUpIssuance(jdbc, verified.scope)
                val snapshot = verified.claimForIssuance() // One cleaned verification cannot be replayed in another phase or another insert.
                val issuance = StepUpGrantIssuance(phase, jdbc, snapshot)
                phase.retainStepUpIssuance(issuance, jdbc)
                return issuance
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}
