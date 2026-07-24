package me.manga.kira.backend.security

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcAdminStepUpGrantRepository(private val jdbc: JdbcTemplate) : AdminStepUpGrantRepository {
    override fun deleteExpiredOrUsed(at: Instant) {
        jdbc.update(
            "DELETE FROM admin_step_up_grants WHERE expires_at <= ? OR used_at IS NOT NULL",
            Timestamp.from(at),
        )
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
}
