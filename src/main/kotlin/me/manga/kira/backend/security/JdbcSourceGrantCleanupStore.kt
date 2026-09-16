package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.requireSourceGrantCleanup
import me.manga.kira.backend.security.AdminStepUpService.Companion.SOURCE_ADMIN_MUTATION_SCOPE
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant

/**
 * Dormant, non-bean adapter for one fixed source-grant cleanup operation. It does not replace or
 * reroute the live step-up repository. No user, complaint counter or fence lock belongs to this batch.
 */
internal class JdbcSourceGrantCleanupStore(private val jdbc: JdbcTemplate) : SourceGrantCleanup {
    override fun deleteEligibleSourceGrants(cutoff: Instant): Int {
        requireSourceGrantCleanup(jdbc)
        val cutoffTimestamp = Timestamp.from(cutoff)
        val deleted = jdbc.update(
            DELETE_ELIGIBLE_SOURCE_GRANTS,
            SOURCE_ADMIN_MUTATION_SCOPE,
            cutoffTimestamp,
            SOURCE_ADMIN_MUTATION_SCOPE,
            cutoffTimestamp,
        )
        check(deleted in 0..BATCH_LIMIT) { "Source grant cleanup returned an invalid row count." }
        return deleted
    }

    private companion object {
        const val BATCH_LIMIT = 50
        val DELETE_ELIGIBLE_SOURCE_GRANTS = """
            WITH eligible AS (
                SELECT id
                FROM admin_step_up_grants
                WHERE scope = ? AND (expires_at <= ? OR used_at IS NOT NULL)
                ORDER BY id
                LIMIT $BATCH_LIMIT
                FOR UPDATE SKIP LOCKED
            )
            DELETE FROM admin_step_up_grants AS grant_row
            USING eligible
            WHERE grant_row.id = eligible.id
                AND grant_row.scope = ?
                AND (grant_row.expires_at <= ? OR grant_row.used_at IS NOT NULL)
        """.trimIndent()
    }
}
