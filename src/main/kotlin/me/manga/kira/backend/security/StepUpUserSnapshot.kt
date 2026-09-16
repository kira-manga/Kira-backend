package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.user.domain.Role
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/** Bounded scalar copy only: no managed entity, phase, connection or password-bearing generated diagnostic escapes the read. */
internal class StepUpUserSnapshot private constructor(
    val scope: ScopedAdminStepUpScope,
    val userId: UUID,
    val email: String?,
    val passwordHash: String?,
    val enabled: Boolean,
    val role: Role?,
) {
    private var readCompleted = false

    internal fun releasedFrom(phase: PersistencePhaseContext): StepUpUserSnapshot {
        phase.checkStepUpSnapshotResult(this)
        readCompleted = true
        return this
    }

    internal fun requireReleased() {
        if (!readCompleted) throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
    }

    override fun toString(): String = "StepUpUserSnapshot(redacted)"

    companion object {
        private const val READ_USER = "SELECT id, email, password_hash, enabled, role FROM users WHERE id = ?"

        @Suppress("TooGenericExceptionCaught")
        internal fun read(jdbc: JdbcTemplate, userId: UUID, scope: ScopedAdminStepUpScope): StepUpUserSnapshot {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.requireStepUpSnapshot(jdbc, scope)
                val rows = jdbc.query(READ_USER, { result, _ -> decode(result, userId, scope) }, userId)
                check(rows.size <= 1)
                val snapshot = rows.singleOrNull() ?: StepUpUserSnapshot(scope, userId, null, null, false, null)
                phase.retainStepUpSnapshot(snapshot, jdbc)
                return snapshot
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        private fun decode(result: ResultSet, userId: UUID, scope: ScopedAdminStepUpScope): StepUpUserSnapshot {
            check(result.getObject("id", UUID::class.java) == userId)
            val email = checkNotNull(result.getString("email"))
            val hash = checkNotNull(result.getString("password_hash"))
            // PostgreSQL varchar limits count code points; bound the UTF-16 copy without rejecting supplementary characters.
            check(email.length <= 640 && hash.length <= 510)
            val enabled = result.getBoolean("enabled").also { check(!result.wasNull()) }
            val role = Role.valueOf(checkNotNull(result.getString("role")))
            return StepUpUserSnapshot(scope, userId, email, hash, enabled, role)
        }
    }
}
