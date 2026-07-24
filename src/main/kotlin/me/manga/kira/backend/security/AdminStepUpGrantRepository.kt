package me.manga.kira.backend.security

import java.time.Instant
import java.util.UUID

interface AdminStepUpGrantRepository {
    /** Bound storage by removing proofs that can no longer be accepted. */
    fun deleteExpiredOrUsed(at: Instant)

    fun create(grant: NewAdminStepUpGrant)

    /** Atomically consumes one unexpired, unused grant. */
    fun consume(userId: UUID, tokenHash: String, scope: String, usedAt: Instant): Boolean
}

data class NewAdminStepUpGrant(val id: UUID, val userId: UUID, val tokenHash: String, val scope: String, val createdAt: Instant, val expiresAt: Instant)
