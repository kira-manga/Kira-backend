package me.manga.kira.backend.security

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.factory.PasswordEncoderFactories

/**
 * Test 7 (PLAN §11) — `PasswordHashingTest`: BCrypt hash verifies; hash ≠ plaintext; two hashes of
 * the same password differ (per-hash salt). Uses the same `DelegatingPasswordEncoder` the app wires
 * ({bcrypt} initial id — PLAN §6).
 */
class PasswordHashingTest {

    private val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Test
    fun `hash verifies and is not the plaintext`() {
        val raw = "correct horse battery staple"
        val hash = encoder.encode(raw)

        assertTrue(encoder.matches(raw, hash))
        assertNotEquals(raw, hash)
        assertTrue(hash.startsWith("{bcrypt}"), "delegating encoder tags the algorithm id")
        assertTrue(!encoder.matches("wrong password entirely", hash))
    }

    @Test
    fun `two hashes of the same password differ (salt)`() {
        val raw = "correct horse battery staple"
        val first = encoder.encode(raw)
        val second = encoder.encode(raw)

        assertNotEquals(first, second)
        assertTrue(encoder.matches(raw, first))
        assertTrue(encoder.matches(raw, second))
    }
}
