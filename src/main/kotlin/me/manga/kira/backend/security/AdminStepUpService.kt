package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.common.exception.UnauthorizedException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Password step-up for high-impact source catalog mutations.
 *
 * The opaque proof is generated from 256 random bits, returned once, and stored only as a SHA-256
 * hash. Proofs are scoped, short-lived, and atomically one-time-use.
 */
@Service
class AdminStepUpService internal constructor(
    private val issuer: ScopedAdminStepUpIssuer,
    private val grants: AdminStepUpGrantRepository,
    private val clock: Clock,
) {
    // Deliberately no enclosing transaction: the existing named phases release every holder,
    // lease and permit before password/throttle calls, then lock and recheck fresh Admin state.
    @Suppress("SwallowedException") // The public problem must not expose a persistence failure graph.
    fun issue(userId: UUID, rawPassword: String, clientIp: String): IssuedAdminStepUp {
        try {
            val issued = issuer.issueSource(userId, rawPassword, clientIp)
            return IssuedAdminStepUp(issued.token, issued.expiresAt)
        } catch (_: PersistencePhaseException) {
            // No proof, automatic retry or guessed rollback after an unresolved/unknown phase.
            throw ServiceUnavailableException("Password verification is temporarily unavailable.", code = "ADMIN_STEP_UP_UNAVAILABLE")
        }
    }

    @Transactional
    fun requireSourceMutation(userId: UUID, token: String?) {
        // Ordinary source controllers may participate in their existing transaction. This
        // standalone wrapper is never a consumer inside a counted complaint/short phase.
        PersistencePhaseOwnership.current()?.let { phase ->
            val refusal = PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            phase.recordFailure(refusal)
            throw refusal
        }
        val accepted =
            !token.isNullOrBlank() &&
                token.length <= MAX_TOKEN_CHARS &&
                grants.consume(
                    userId = userId,
                    tokenHash = Sha256.hexUtf8(token),
                    scope = SOURCE_ADMIN_MUTATION_SCOPE,
                    usedAt = clock.instant(),
                )
        if (!accepted) {
            throw UnauthorizedException(
                "A fresh admin password verification is required.",
                code = "ADMIN_STEP_UP_REQUIRED",
            )
        }
    }

    companion object {
        const val SOURCE_ADMIN_MUTATION_SCOPE = "source-admin-mutation"
        const val HEADER = "X-Kira-Admin-Step-Up"
        private const val MAX_TOKEN_CHARS = 128
    }
}

class IssuedAdminStepUp(val token: String, val expiresAt: Instant) {
    override fun toString(): String = "IssuedAdminStepUp(redacted)"
}
