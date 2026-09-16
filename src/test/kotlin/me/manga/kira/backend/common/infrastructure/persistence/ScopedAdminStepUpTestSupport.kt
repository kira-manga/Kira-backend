package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.security.AuthThrottle
import me.manga.kira.backend.security.ScopedAdminStepUpIssuer
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows

internal object ScopedAdminStepUpTestSupport {
    fun issueWithThrottle(f: ScopedStepUpFixture, scope: ScopedAdminStepUpScope, throttle: AuthThrottle) {
        val issuer = ScopedAdminStepUpIssuer(f.phases, f.dependencies, throttle)
        when (scope) {
            ScopedAdminStepUpScope.SOURCE -> issuer.issueSource(f.ordinary.userId, ScopedStepUpFixture.PASSWORD, "192.0.2.29")
            ScopedAdminStepUpScope.COMPLAINT -> issuer.issueComplaint(f.ordinary.userId, ScopedStepUpFixture.PASSWORD, "192.0.2.29")
        }
    }

    fun assertCapacityRefused(f: ScopedStepUpFixture) {
        val before = f.counters.snapshot()
        val failure = assertThrows<PersistencePhaseException> { f.issue(ScopedAdminStepUpScope.COMPLAINT) }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertEquals(0, f.jdbc.counterUpdates)
        assertEquals(0, f.jdbc.insertAttempts)
        assertTrue(f.jdbc.issuances.isEmpty() && f.ordinary.grantIds().isEmpty())
        assertEquals(before, f.counters.snapshot())
    }

    fun issuancePhase(f: ScopedStepUpFixture, scope: ScopedAdminStepUpScope): PersistencePhaseContext = when (scope) {
        ScopedAdminStepUpScope.SOURCE -> f.ordinary.ownership.enterSourceStepUpIssuance()
        ScopedAdminStepUpScope.COMPLAINT -> f.ordinary.ownership.enterComplaintStepUpIssuance()
    }

    fun assertUserLockAvailable(f: OrdinarySourceGrantCleanupFixture) {
        checkNotNull(f.foreignTemplate().dataSource).connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE NOWAIT").use { statement ->
                    statement.queryTimeout = 2
                    statement.setObject(1, f.userId)
                    statement.executeQuery().use { result -> assertTrue(result.next() && !result.next()) }
                }
            } finally {
                connection.rollback()
            }
        }
    }

    enum class UserChange(val sql: String) {
        HASH("UPDATE users SET password_hash = 'changed-synthetic-hash' WHERE id = ?"),
        ENABLED("UPDATE users SET enabled = false WHERE id = ?"),
        ROLE("UPDATE users SET role = 'USER' WHERE id = ?"),
        DELETED("DELETE FROM users WHERE id = ?"),
    }
}
