package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import me.manga.kira.backend.complaint.domain.ComplaintReportTestFixtures as Fixtures
import org.junit.jupiter.api.Assertions.assertArrayEquals as assertContentEquals

class ComplaintReportFingerprintTest {
    @Test
    fun fourApprovedFramesAndDigestsAreByteIdentical() {
        for (golden in Fixtures.goldens()) {
            val frame = ComplaintReportFingerprint.frameBytes(golden.request)
            val fingerprint = ComplaintReportFingerprint.of(golden.request)
            assertEquals(golden.frameBytes, frame.size)
            assertContentEquals(Fixtures.hexBytes(golden.frameHex), frame)
            assertContentEquals(Fixtures.hexBytes(golden.digestHex), fingerprint.bytes())
            assertEquals(golden.encoded, fingerprint.encoded)
            assertEquals(1, fingerprint.version)
        }
    }

    @Test
    fun normalizationEquivalentUnicodeHashesMatchWithoutFoldingNullOrNfc() {
        val raw = Fixtures.request(
            identity = Fixtures.identity(scope = Fixtures.TEST_SCOPE),
            type = ComplaintType.CUSTOM,
            subject = " \u00a0Aé\t ",
            body = " \tA\r\n😀bc\n ",
            metadata = Fixtures.metadata(" \t1.0\r\n ", " \u00a0 ", " M ", " D "),
        )
        assertContentEquals(
            ComplaintReportFingerprint.frameBytes(Fixtures.unicodeRequest()),
            ComplaintReportFingerprint.frameBytes(raw),
        )
        assertEquals("MZv1nnigeui7yKDgffhQZdA9iNOGHEI0KUpTEmaEMBM", ComplaintReportFingerprint.of(raw).encoded)
        assertNotEquals(
            ComplaintReportFingerprint.of(raw).encoded,
            ComplaintReportFingerprint.of(Fixtures.unicodeRequest("Ae\u0301")).encoded,
        )
        assertNotEquals(
            ComplaintReportFingerprint.of(Fixtures.request()).encoded,
            ComplaintReportFingerprint.of(Fixtures.request(metadata = Fixtures.metadata(appVersion = ""))).encoded,
        )
    }

    @Test
    fun everySemanticFieldIsBoundButIdempotencyKeyIsSeparateIdentity() {
        val original = Fixtures.request()
        val changes = listOf(
            Fixtures.request(identity = Fixtures.identity(id = Fixtures.OTHER_ID)),
            Fixtures.request(identity = Fixtures.identity(scope = Fixtures.TEST_SCOPE)),
            Fixtures.request(type = ComplaintType.CUSTOM),
            Fixtures.request(subject = "T"),
            Fixtures.request(body = "abcdf"),
            Fixtures.request(metadata = Fixtures.metadata(appVersion = "")),
            Fixtures.request(metadata = Fixtures.metadata(osVersion = "1")),
            Fixtures.request(metadata = Fixtures.metadata(manufacturer = "M")),
            Fixtures.request(metadata = Fixtures.metadata(deviceModel = "D")),
        )
        val fingerprints = (listOf(original) + changes).map { ComplaintReportFingerprint.of(it).encoded }
        assertEquals(fingerprints.size, fingerprints.toSet().size)
        val differentKey = Fixtures.request(identity = Fixtures.identity(key = Fixtures.OTHER_KEY))
        assertNotEquals(original.identity.key.value, differentKey.identity.key.value)
        assertContentEquals(
            ComplaintReportFingerprint.frameBytes(original),
            ComplaintReportFingerprint.frameBytes(differentKey),
        )
    }

    @Test
    fun maximalFrameIsBoundedAndReturnedByteArraysCannotMutateDigestOrRequest() {
        val request = Fixtures.request(
            type = ComplaintType.SITE_ERROR,
            subject = "😀".repeat(200),
            body = "😀".repeat(500),
            metadata = Fixtures.metadata("😀".repeat(64), "😀".repeat(128), "😀".repeat(128), "😀".repeat(128)),
        )
        val frame = ComplaintReportFingerprint.frameBytes(request)
        assertEquals(4_806, frame.size)
        val fingerprint = ComplaintReportFingerprint.of(request)
        val encoded = fingerprint.encoded
        val digest = fingerprint.bytes()
        assertEquals(32, digest.size)
        assertEquals(43, encoded.length)
        digest.fill(0)
        frame.fill(0)
        assertEquals(encoded, fingerprint.encoded)
        assertEquals(encoded, ComplaintReportFingerprint.of(request).encoded)
        assertNotEquals(digest.toList(), fingerprint.bytes().toList())
    }
}
