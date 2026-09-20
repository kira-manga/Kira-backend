package me.manga.kira.backend.security

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

class InstallationJwtKeyRingTest {
    @Test
    fun `key sizes IDs counts and active signer have exact finite bounds`() {
        val lower = InstallationJwtKeyMaterial("a", bytes(1, 32))
        val upper = InstallationJwtKeyMaterial("A._-".repeat(16), bytes(2, 128))
        assertEquals(setOf(lower.id, upper.id), ring(listOf(lower, upper), lower.id).verificationKeyIds())
        val eight = (1..8).map { InstallationJwtKeyMaterial("key-$it", bytes(it)) }
        assertEquals(8, ring(eight, "key-8").verificationKeyIds().size)

        listOf(0, 31, 129).forEach { size -> invalid { InstallationJwtKeyMaterial("valid", bytes(1, size)) } }
        listOf("", "x".repeat(65), "space key", "../key", "key\n", "clé", "key:").forEach { id ->
            invalid { InstallationJwtKeyMaterial(id, bytes(1)) }
        }
        invalid { ring(emptyList()) }
        invalid { ring(eight + InstallationJwtKeyMaterial("key-9", bytes(9)), "key-8") }
        invalid { ring(listOf(lower), "missing") }
        invalid { ring(listOf(lower), " ") }
    }

    @Test
    fun `duplicate IDs and effective HMAC keys cannot hide behind different byte shapes`() {
        invalid { ring(listOf(InstallationJwtKeyMaterial("same", bytes(1)), InstallationJwtKeyMaterial("same", bytes(2))), "same") }
        invalid { ring(listOf(InstallationJwtKeyMaterial("one", bytes(1)), InstallationJwtKeyMaterial("two", bytes(1))), "one") }
        val short = bytes(3)
        invalid { ring(listOf(InstallationJwtKeyMaterial("one", short), InstallationJwtKeyMaterial("two", short.copyOf(64))), "one") }
        val long = bytes(4, 128)
        val reduced = MessageDigest.getInstance("SHA-256").digest(long)
        invalid { ring(listOf(InstallationJwtKeyMaterial("one", long), InstallationJwtKeyMaterial("two", reduced)), "one") }
    }

    @Test
    fun `actual forbidden user family material IDs and family names are mandatory and isolated`() {
        val installation = InstallationJwtKeyMaterial("installation", bytes(1))
        invalid { InstallationJwtForbiddenFamily("user-issuer", "user-audience", emptyList()) }
        invalid { InstallationJwtKeyRing("installation", listOf(installation), forbidden("installation", bytes(2))) }
        invalid { InstallationJwtKeyRing("installation", listOf(installation), forbidden("user-key", bytes(1))) }
        invalid { InstallationJwtKeyRing("installation", listOf(installation), forbidden("user-key", bytes(1).copyOf(64))) }
        listOf(InstallationJwtCodec.ISSUER, InstallationJwtCodec.AUDIENCE).forEach { reserved ->
            invalid { InstallationJwtKeyRing("installation", listOf(installation), forbidden(issuer = reserved)) }
            invalid { InstallationJwtKeyRing("installation", listOf(installation), forbidden(audience = reserved)) }
        }
        invalid { forbidden(issuer = "same", audience = "same") }
        invalid { forbidden(issuer = "") }
        invalid { forbidden(audience = " ") }
        invalid { forbidden(issuer = "x".repeat(257)) }
    }

    @Test
    fun `all byte and collection inputs are copied and all representations redact key material`() {
        val raw = "only-test-key-material-never-a-production-secret".toByteArray()
        val retained = raw.copyOf()
        val material = InstallationJwtKeyMaterial("installation-private-id", raw)
        val input = mutableListOf(material)
        val forbiddenRaw = bytes(91)
        val forbiddenKeys = mutableListOf(InstallationJwtKeyMaterial("user-private-id", forbiddenRaw))
        val family = InstallationJwtForbiddenFamily("user-issuer", "user-audience", forbiddenKeys)
        val ring = InstallationJwtKeyRing(material.id, input, family)
        raw.fill(0)
        forbiddenRaw.fill(0)
        input.clear()
        forbiddenKeys.clear()
        val exposed = checkNotNull(ring.key(material.id)).encoded
        exposed.fill(0)
        assertArrayEquals(retained, checkNotNull(ring.key(material.id)).encoded)
        assertEquals(setOf(material.id), ring.verificationKeyIds())
        assertNull(ring.key("unknown"))
        invalid { InstallationJwtKeyRing("collision", listOf(InstallationJwtKeyMaterial("collision", bytes(91))), family) }
        val rendered = listOf(material, family, ring).joinToString()
        listOf(String(retained), Base64.getEncoder().encodeToString(retained), material.id, "user-private-id").forEach { sensitive ->
            assertFalse(rendered.contains(sensitive))
        }
    }

    private fun ring(keys: List<InstallationJwtKeyMaterial>, active: String = "installation"): InstallationJwtKeyRing =
        InstallationJwtKeyRing(active, keys, forbidden())

    private fun forbidden(
        id: String = "user-key",
        material: ByteArray = bytes(91),
        issuer: String = "user-issuer",
        audience: String = "user-audience",
    ): InstallationJwtForbiddenFamily = InstallationJwtForbiddenFamily(issuer, audience, listOf(InstallationJwtKeyMaterial(id, material)))

    private fun bytes(seed: Int, count: Int = 32): ByteArray = ByteArray(count) { (seed + it * 7).toByte() }

    private fun invalid(operation: () -> Unit) {
        val failure = assertThrows(IllegalArgumentException::class.java) { operation() }
        assertEquals("Invalid installation JWT key configuration", failure.message)
        assertNull(failure.cause)
    }
}
