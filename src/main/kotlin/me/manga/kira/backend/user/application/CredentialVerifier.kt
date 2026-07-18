package me.manga.kira.backend.user.application

import me.manga.kira.backend.user.domain.User
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * Performs exactly one password-hash verification for every login candidate. Unknown and disabled
 * users are checked against a startup-generated decoy hash, reducing the account-state timing signal
 * without ever storing or logging the submitted password.
 */
@Component
class CredentialVerifier(
    private val passwordEncoder: PasswordEncoder,
) {
    private val decoyHash = passwordEncoder.encode(DECOY_PASSWORD_SOURCE)

    fun verify(
        rawPassword: String,
        candidate: User?,
    ): User? {
        val eligible = candidate?.takeIf { it.enabled }
        val matched = passwordEncoder.matches(rawPassword, eligible?.passwordHash ?: decoyHash)
        return if (eligible != null && matched) eligible else null
    }

    private companion object {
        // This value is never a credential; only its one-way hash is used to equalize verification work.
        const val DECOY_PASSWORD_SOURCE = "kira-login-decoy-password-not-an-account"
    }
}
