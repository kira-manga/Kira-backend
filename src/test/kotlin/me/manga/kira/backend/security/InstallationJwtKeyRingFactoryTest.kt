package me.manga.kira.backend.security

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

class InstallationJwtKeyRingFactoryTest {
    @Test
    fun `canonical configuration has exact bounds and is copied into the existing ring`() {
        val sizes = listOf(32, 33, 34, 64, 65, 126, 127, 128)
        val ids = listOf("a", "A._-".repeat(16)) + (3..8).map { "key-$it" }
        val material = sizes.mapIndexed { index, size -> bytes(index + 1, size) }
        val configuration = ids.zip(material.map(::encoded)).toMap().toMutableMap()
        val retainedConfiguration = configuration.toMap()
        val ring = InstallationJwtKeyRingFactory.fromConfiguration(ids.last(), configuration, forbidden())
        configuration.clear()
        assertEquals(ids.last(), ring.activeKeyId)
        assertEquals(ids.toSet(), ring.verificationKeyIds())
        ids.forEachIndexed { index, id -> assertArrayEquals(material[index], checkNotNull(ring.key(id)).encoded) }
        assertEquals("InstallationJwtKeyRing(redacted)", ring.toString())
        retainedConfiguration.values.forEach { value ->
            assertFalse(ring.toString().contains(value))
        }
        listOf(0xfb, 0xff).forEach { value ->
            val raw = ByteArray(32) { value.toByte() }
            assertArrayEquals(raw, checkNotNull(configured(mapOf("key-1" to encoded(raw))).key("key-1")).encoded)
        }
    }

    @Test
    fun `missing malformed and nonstring values never acquire defaults or coercion`() {
        val validKeys = mapOf("key-1" to encoded(bytes(1)))
        val noRendering = object {
            override fun toString(): String = error("Raw configuration must not be rendered")
        }
        listOf(null, 1, true, noRendering, StringBuilder("key-1")).forEach { active ->
            invalid { InstallationJwtKeyRingFactory.fromConfiguration(active, validKeys, forbidden()) }
        }
        listOf("", " ", " key-1", "key-1 ", "missing", "x".repeat(65), "key-1\n", "clé", "a/b").forEach { active ->
            invalid { InstallationJwtKeyRingFactory.fromConfiguration(active, validKeys, forbidden()) }
        }
        invalid { configured(null) }
        invalid { configured(emptyMap<String, String>()) }
        listOf(null, 1, false, noRendering, StringBuilder("key-1")).forEach { key ->
            invalid { configured(mapOf(key to encoded(bytes(1)))) }
        }
        listOf(null, 1, false, noRendering, bytes(1), StringBuilder(encoded(bytes(1)))).forEach { value ->
            invalid { configured(mapOf("key-1" to value)) }
        }
        listOf("", "x".repeat(65), "key 1", "../key", "key\n", "clé", "key:").forEach { id ->
            invalid { InstallationJwtKeyRingFactory.fromConfiguration(id, mapOf(id to encoded(bytes(1))), forbidden()) }
        }
    }

    @Test
    fun `only canonical standard Base64 within encoded and decoded bounds is accepted`() {
        val canonical = encoded(bytes(1))
        val invalidEncodings = listOf(
            "",
            " $canonical",
            "$canonical ",
            "$canonical\n",
            "$canonical\r\n",
            "$canonical\t",
            "$canonical\u00a0",
            canonical.replaceRange(2, 3, " "),
            canonical.replaceRange(2, 3, "é"),
            canonical.replaceRange(2, 3, "="),
            "=" + canonical.drop(1),
            canonical.trimEnd('='),
            encoded(bytes(1, 34)).trimEnd('='),
            "$canonical=",
            "$canonical====",
            "A".repeat(172),
            "A".repeat(173),
            "A".repeat(176),
            Base64.getUrlEncoder().encodeToString(ByteArray(32) { 0xfb.toByte() }),
            Base64.getUrlEncoder().encodeToString(ByteArray(32) { 0xff.toByte() }),
        )
        invalidEncodings.forEach { value -> invalid { configured(mapOf("key-1" to value)) } }
        listOf(0, 1, 30, 31, 129, 130, 131).forEach { size ->
            invalid { configured(mapOf("key-1" to encoded(bytes(1, size)))) }
        }
        listOf(32, 34).forEach { size ->
            val raw = bytes(1, size)
            val alias = nonCanonicalPadding(encoded(raw))
            assertArrayEquals(raw, Base64.getDecoder().decode(alias))
            invalid { configured(mapOf("key-1" to alias)) }
        }
    }

    @Test
    fun `key count bounds apply before snapshot and to the actual iterator`() {
        val nine = (1..9).associate { "key-$it" to encoded(bytes(it)) }
        invalid { configured(nine) }
        listOf(Triple(9, 9, 0), Triple(8, 9, 8), Triple(2, 1, 1), Triple(1, 2, 1)).forEach { (declared, actual, reads) ->
            val configuration = CountedConfigurationMap(declared, actual)
            invalid { configured(configuration) }
            assertEquals(reads, configuration.reads)
        }
    }

    @Test
    fun `duplicate effective installation keys are rejected by the existing ring`() {
        val short = bytes(1)
        val long = bytes(2, 128)
        val reduced = MessageDigest.getInstance("SHA-256").digest(long)
        listOf(short to short, short to short.copyOf(64), long to reduced).forEach { (first, second) ->
            invalid { configured(mapOf("key-1" to encoded(first), "key-2" to encoded(second))) }
        }
    }

    @Test
    fun `explicit retained user family cannot share identifiers effective keys or reserved families`() {
        val current = bytes(91)
        val retired = bytes(92, 128)
        val family = InstallationJwtForbiddenFamily(
            "user-issuer",
            "user-audience",
            listOf(InstallationJwtKeyMaterial("user-current", current), InstallationJwtKeyMaterial("user-retired", retired)),
        )
        listOf("user-current", "user-retired").forEach { id ->
            invalid { InstallationJwtKeyRingFactory.fromConfiguration(id, mapOf(id to encoded(bytes(1))), family) }
        }
        listOf(current, current.copyOf(64), retired, MessageDigest.getInstance("SHA-256").digest(retired)).forEach { material ->
            invalid { InstallationJwtKeyRingFactory.fromConfiguration("key-1", mapOf("key-1" to encoded(material)), family) }
        }
        listOf(InstallationJwtCodec.ISSUER, InstallationJwtCodec.AUDIENCE).forEach { reserved ->
            val userKey = listOf(InstallationJwtKeyMaterial("user-key", current))
            listOf(
                InstallationJwtForbiddenFamily(reserved, "user-audience", userKey),
                InstallationJwtForbiddenFamily("user-issuer", reserved, userKey),
            ).forEach { reservedFamily ->
                invalid {
                    InstallationJwtKeyRingFactory.fromConfiguration("key-1", mapOf("key-1" to encoded(bytes(1))), reservedFamily)
                }
            }
        }
    }

    private fun configured(keys: Map<*, *>?): InstallationJwtKeyRing = InstallationJwtKeyRingFactory.fromConfiguration("key-1", keys, forbidden())

    private fun forbidden(): InstallationJwtForbiddenFamily =
        InstallationJwtForbiddenFamily("user-issuer", "user-audience", listOf(InstallationJwtKeyMaterial("user-key", bytes(91))))

    private fun bytes(seed: Int, count: Int = 32): ByteArray = ByteArray(count) { (seed + it * 7).toByte() }

    private fun encoded(raw: ByteArray): String = Base64.getEncoder().encodeToString(raw)

    private fun nonCanonicalPadding(canonical: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val index = canonical.indexOf('=') - 1
        return canonical.replaceRange(index, index + 1, alphabet[alphabet.indexOf(canonical[index]) + 1].toString())
    }

    private fun invalid(operation: () -> Unit) {
        val failure = assertThrows(IllegalArgumentException::class.java) { operation() }
        assertEquals("Invalid installation JWT key configuration", failure.message)
        assertNull(failure.cause)
    }

    private class CountedConfigurationMap(private val declaredSize: Int, private val actualSize: Int) : AbstractMap<String, String>() {
        var reads = 0
            private set

        override val size: Int get() = declaredSize

        override val entries: Set<Map.Entry<String, String>>
            get() = object : AbstractSet<Map.Entry<String, String>>() {
                override val size: Int get() = actualSize

                override fun iterator(): Iterator<Map.Entry<String, String>> = (1..actualSize).asSequence().map { index ->
                    reads += 1
                    val material = ByteArray(32) { (index + it * 7).toByte() }
                    java.util.AbstractMap.SimpleImmutableEntry("key-$index", Base64.getEncoder().encodeToString(material))
                }.iterator()
            }
    }
}
