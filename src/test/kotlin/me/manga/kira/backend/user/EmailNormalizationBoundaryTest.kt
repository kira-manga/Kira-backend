package me.manga.kira.backend.user

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.config.KiraAuthProperties
import me.manga.kira.backend.security.AuthThrottle
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.user.application.AuthService
import me.manga.kira.backend.user.application.CredentialVerifier
import me.manga.kira.backend.user.application.PasswordPolicy
import me.manga.kira.backend.user.application.UserService
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.security.crypto.password.PasswordEncoder

/** Interaction-only checks for work that HTTP status and database row counts cannot rule out. */
class EmailNormalizationBoundaryTest {
    private val users = mock(UserRepository::class.java)
    private val encoder = mock(PasswordEncoder::class.java)
    private val policy = mock(PasswordPolicy::class.java)
    private val userService = UserService(users, encoder, policy)

    @Test
    fun `login rejects lowercase expansion past the bound before any downstream work`() {
        val verifier = mock(CredentialVerifier::class.java)
        val tokens = mock(JwtService::class.java)
        val throttle = mock(AuthThrottle::class.java)
        val audit = mock(AuditService::class.java)
        val auth = AuthService(users, userService, verifier, tokens, throttle, KiraAuthProperties(), audit)
        val email = "a".repeat(307) + "İ@example.com"
        assertEquals(320, email.codePointCount(0, email.length))

        val error =
            assertThrows(BadRequestException::class.java) {
                auth.login(email, "submitted password", "127.0.0.1")
            }

        assertEquals("EMAIL_TOO_LONG", error.code)
        assertEquals(400, error.status.value())
        verifyNoInteractions(users, encoder, policy, verifier, tokens, throttle, audit)
    }

    @Test
    fun `creation rejects an oversized normalized email before policy lookup hashing or insertion`() {
        val error =
            assertThrows(BadRequestException::class.java) {
                userService.createUser("a".repeat(309) + "@example.com", "submitted password", Role.USER)
            }

        assertEquals("EMAIL_TOO_LONG", error.code)
        assertEquals(400, error.status.value())
        verifyNoInteractions(users, encoder, policy)
    }
}
