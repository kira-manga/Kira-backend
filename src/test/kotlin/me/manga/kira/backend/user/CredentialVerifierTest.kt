package me.manga.kira.backend.user

import me.manga.kira.backend.user.application.CredentialVerifier
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.util.UUID

class CredentialVerifierTest {
    @Test
    fun `unknown disabled and enabled candidates each perform one hash verification`() {
        val encoder = RecordingPasswordEncoder()
        val verifier = CredentialVerifier(encoder)
        val disabled = user(enabled = false, hash = "disabled-real-hash")
        val enabled = user(enabled = true, hash = "enabled-real-hash")

        assertNull(verifier.verify("submitted password", null))
        assertNull(verifier.verify("submitted password", disabled))
        assertSame(enabled, verifier.verify("submitted password", enabled))

        assertEquals(listOf("decoy-hash", "decoy-hash", "enabled-real-hash"), encoder.verifiedHashes)
    }

    private fun user(enabled: Boolean, hash: String) = User(
        id = UUID.randomUUID(),
        email = "reader@example.com",
        passwordHash = hash,
        role = Role.USER,
        enabled = enabled,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private class RecordingPasswordEncoder : PasswordEncoder {
        val verifiedHashes = mutableListOf<String>()

        override fun encode(rawPassword: CharSequence): String = "decoy-hash"

        override fun matches(rawPassword: CharSequence, encodedPassword: String): Boolean {
            verifiedHashes += encodedPassword
            return encodedPassword == "enabled-real-hash"
        }
    }
}
