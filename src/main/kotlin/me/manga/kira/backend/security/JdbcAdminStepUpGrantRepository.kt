package me.manga.kira.backend.security

import me.manga.kira.backend.security.AdminStepUpService.Companion.SOURCE_ADMIN_MUTATION_SCOPE
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcAdminStepUpGrantRepository(private val jdbc: JdbcTemplate) : AdminStepUpGrantRepository {
    override fun deleteEligibleSourceGrants(at: Instant): Int {
        val cutoff = Timestamp.from(at)
        val deleted = jdbc.update(
            DELETE_ELIGIBLE_SOURCE_GRANTS,
            SOURCE_ADMIN_MUTATION_SCOPE,
            cutoff,
            SOURCE_ADMIN_MUTATION_SCOPE,
            cutoff,
        )
        check(deleted in 0..BATCH_LIMIT) { "Source grant cleanup returned an invalid row count." }
        return deleted
    }

    override fun create(grant: NewAdminStepUpGrant) {
        jdbc.update(
            """
            INSERT INTO admin_step_up_grants
                (id, user_id, token_hash, scope, created_at, expires_at, used_at)
            VALUES (?, ?, ?, ?, ?, ?, NULL)
            """.trimIndent(),
            grant.id,
            grant.userId,
            grant.tokenHash,
            grant.scope,
            Timestamp.from(grant.createdAt),
            Timestamp.from(grant.expiresAt),
        )
    }

    override fun consume(userId: UUID, tokenHash: String, scope: String, usedAt: Instant): Boolean = jdbc.update(
        """
            UPDATE admin_step_up_grants
            SET used_at = ?
            WHERE user_id = ? AND token_hash = ? AND scope = ? AND used_at IS NULL AND expires_at > ?
        """.trimIndent(),
        Timestamp.from(usedAt),
        userId,
        tokenHash,
        scope,
        Timestamp.from(usedAt),
    ) == 1

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
