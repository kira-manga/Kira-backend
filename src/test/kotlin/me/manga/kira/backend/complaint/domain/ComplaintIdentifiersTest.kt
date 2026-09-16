package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

class ComplaintIdentifiersTest {
    @Test
    fun `client identities accept only lowercase canonical variant two version four`() {
        val expected = UUID.fromString(V4)
        assertEquals(expected, ComplaintIdentifiers.installationId(V4))
        assertEquals(expected, ComplaintIdentifiers.clientResourceId(V4))
        assertEquals(expected, ComplaintIdentifiers.idempotencyKey(V4))
        for (invalid in listOf(V5, "aaaaaaaa-aaaa-4aaa-0aaa-aaaaaaaaaaaa", NIL)) {
            for (parse in clientParsers) {
                assertEquals(
                    ComplaintValidationReason.UUID_NOT_V4,
                    assertThrows(ComplaintValidationException::class.java) { parse(invalid) }.reason,
                )
            }
        }
    }

    @Test
    fun `UUID spelling aliases are rejected before a lookup can happen`() {
        val aliases = listOf(V4.uppercase(), " $V4", "$V4 ", "{$V4}", "urn:uuid:$V4", "1-1-4-8-1", V4.replace("-", ""), V4 + "\n")
        for (alias in aliases) {
            for (parse in clientParsers + listOf(ComplaintIdentifiers::resourceId)) {
                assertEquals(
                    ComplaintValidationReason.NON_CANONICAL_UUID,
                    assertThrows(ComplaintValidationException::class.java) { parse(alias) }.reason,
                )
            }
        }
    }

    @Test
    fun `server resource IDs permit deterministic canonical non-v4 IDs`() {
        assertEquals(UUID.fromString(V5), ComplaintIdentifiers.resourceId(V5))
    }

    @Test
    fun `scope accepts only the live sentinel or a canonical v4 test UUID`() {
        assertEquals(ComplaintDataScope.LIVE, ComplaintIdentifiers.dataScope(NIL))
        assertFalse(ComplaintIdentifiers.dataScope(NIL).testOnly)
        assertEquals(UUID.fromString(V4), ComplaintIdentifiers.dataScope(V4).id)
        assertTrue(ComplaintIdentifiers.dataScope(V4).testOnly)
        for (value in listOf(V5, "aaaaaaaa-aaaa-4aaa-0aaa-aaaaaaaaaaaa")) {
            val exception = assertThrows(ComplaintValidationException::class.java) { ComplaintIdentifiers.dataScope(value) }
            assertEquals(ComplaintValidationReason.INVALID_SCOPE, exception.reason)
        }
        assertThrows(ComplaintValidationException::class.java) { ComplaintIdentifiers.dataScope(V4.uppercase()) }
        assertThrows(ComplaintValidationException::class.java) { ComplaintDataScope.of(UUID.fromString(V5)) }
    }

    @Test
    fun `typed installation identities also enforce v4 when constructed from stored UUID values`() {
        assertThrows(ComplaintValidationException::class.java) {
            ScopedInstallationId(UUID.fromString(V5), ComplaintDataScope.LIVE)
        }
    }

    @Test
    fun `notice keys have an exact ASCII syntax and byte bound`() {
        for (key in listOf("a", "complaints.notice.example-v1_2", "a".repeat(96))) {
            assertEquals(key, ComplaintIdentifiers.noticeKey(key))
        }
        for (invalid in listOf("", "a".repeat(97), "Notice", "notice/key", "notice key", " notice", "notice\n", "إشعار")) {
            val exception = assertThrows(ComplaintValidationException::class.java) { ComplaintIdentifiers.noticeKey(invalid) }
            assertEquals(ComplaintValidationReason.INVALID_NOTICE_KEY, exception.reason)
        }
    }

    @Test
    fun `fingerprint accepts the canonical unpadded encoding of exactly 32 bytes`() {
        for (bytes in listOf(ByteArray(32), ByteArray(32) { it.toByte() }, ByteArray(32) { 0xff.toByte() })) {
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            assertEquals(43, encoded.length)
            assertArrayEquals(bytes, ComplaintIdentifiers.fingerprint(encoded))
        }
    }

    @Test
    fun `fingerprint rejects alternate pad bits even if the JDK decodes the same bytes`() {
        val canonical = "A".repeat(43)
        val alias = "A".repeat(42) + "B"
        assertArrayEquals(Base64.getUrlDecoder().decode(canonical), Base64.getUrlDecoder().decode(alias))
        val exception = assertThrows(ComplaintValidationException::class.java) { ComplaintIdentifiers.fingerprint(alias) }
        assertEquals(ComplaintValidationReason.INVALID_FINGERPRINT, exception.reason)
    }

    @Test
    fun `fingerprint rejects padding whitespace non-url alphabet and wrong lengths`() {
        val canonical = "A".repeat(43)
        for (invalid in listOf(canonical + "=", canonical.dropLast(1), canonical + "A", "$canonical\n", "/".repeat(43), "+".repeat(43))) {
            assertEquals(
                ComplaintValidationReason.INVALID_FINGERPRINT,
                assertThrows(ComplaintValidationException::class.java) { ComplaintIdentifiers.fingerprint(invalid) }.reason,
            )
        }
    }

    @Test
    fun `exception and domain debug strings do not disclose identities or fingerprints`() {
        val invalid = V4.uppercase()
        val exception = assertThrows(ComplaintValidationException::class.java) { ComplaintIdentifiers.installationId(invalid) }
        assertFalse(exception.toString().contains(invalid))
        val scope = ComplaintIdentifiers.dataScope(V4)
        val identity = ScopedInstallationId(UUID.fromString(V4), scope)
        assertFalse(scope.toString().contains(V4))
        assertFalse(identity.toString().contains(V4))
    }

    private val clientParsers = listOf(
        ComplaintIdentifiers::installationId,
        ComplaintIdentifiers::clientResourceId,
        ComplaintIdentifiers::idempotencyKey,
    )

    companion object {
        private const val V4 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        private const val V5 = "aaaaaaaa-aaaa-5aaa-8aaa-aaaaaaaaaaaa"
        private const val NIL = "00000000-0000-0000-0000-000000000000"
    }
}
