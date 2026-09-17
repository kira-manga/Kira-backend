package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import me.manga.kira.backend.complaint.domain.ComplaintReportTestFixtures as Fixtures

class ComplaintReportRequestTest {
    @Test
    fun canonicalIdentitiesRejectAliasesAndInvalidUuidRoles() {
        val canonical = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        assertEquals(canonical, Fixtures.identity(id = canonical).clientId.canonical)
        assertEquals(Fixtures.LIVE, Fixtures.identity().dataScopeId)
        assertEquals(Fixtures.TEST_SCOPE, Fixtures.identity(scope = Fixtures.TEST_SCOPE).dataScopeId)
        val invalid = listOf(
            "",
            canonical.uppercase(),
            " $canonical",
            "$canonical ",
            canonical.replace("-", ""),
            "aaaaaaaa-aaaa-5aaa-8aaa-aaaaaaaaaaaa",
            "aaaaaaaa-aaaa-4aaa-0aaa-aaaaaaaaaaaa",
            "1-1-4-8-1",
        )
        for (value in invalid) {
            assertNull(ComplaintReportIdentity.checked(value, Fixtures.KEY, Fixtures.LIVE))
            assertNull(ComplaintReportIdentity.checked(Fixtures.ID, value, Fixtures.LIVE))
            assertNull(ComplaintReportIdentity.checked(Fixtures.ID, Fixtures.KEY, value))
        }
        assertNull(ComplaintReportIdentity.checked(Fixtures.LIVE, Fixtures.KEY, Fixtures.LIVE))
        assertNull(ComplaintReportIdentity.checked(Fixtures.ID, Fixtures.LIVE, Fixtures.LIVE))
    }

    @Test
    fun normalizationUsesOnlyFixedWhitespaceAndPreservesInteriorText() {
        val whitespace = listOf(9, 10, 32, 0xa0, 0x1680) + (0x2000..0x200a) +
            listOf(0x2028, 0x2029, 0x202f, 0x205f, 0x3000)
        for (point in whitespace) {
            val edge = point.toChar()
            val request = Fixtures.request(subject = "${edge}S$edge", body = "${edge}abcde$edge")
            assertEquals("S", request.subject)
            assertEquals("abcde", request.body)
        }
        val preserved = "\u200b\ufeff\u180eAé\t\nZ\u200b\ufeff\u180e"
        assertEquals(preserved, Fixtures.request(body = " $preserved ").body)
        assertEquals("ab\ncd", Fixtures.request(body = " \tab\r\ncd\n ").body)
        assertNull(Fixtures.request().metadata.appVersion)
        val empty = Fixtures.request(metadata = Fixtures.metadata(" \t\r\n ", " ", "\t", "\n"))
        assertEquals(
            listOf("", "", "", ""),
            listOf(
                empty.metadata.appVersion,
                empty.metadata.osVersion,
                empty.metadata.manufacturer,
                empty.metadata.deviceModel,
            ),
        )
    }

    @Test
    fun allTextFieldsRejectControlAndSurrogateEdgesBeforeTrimming() {
        val forbidden = ((0..31) + (127..159)).filter { it != 9 && it != 10 }.map { it.toChar().toString() }
        val malformed = listOf("\ud800", "\udc00", "\ud800x", "\ud800\ud800", "\udc00\ud800", "😀\udc00")
        for (field in ComplaintReportField.entries) {
            for (bad in forbidden + malformed) {
                val reason = if (bad in forbidden) {
                    ComplaintReportRejection.FORBIDDEN_CONTROL
                } else {
                    ComplaintReportRejection.MALFORMED_UNICODE
                }
                for (text in listOf("${bad}abcde", "ab${bad}cde", "abcde$bad")) {
                    val rejected = Fixtures.rejected(Fixtures.withField(field, text))
                    assertEquals(field, rejected.field)
                    assertEquals(reason, rejected.reason)
                }
            }
        }
    }

    @Test
    fun fieldBoundsCountSupplementaryScalarsAndBoundDirectCallScratch() {
        val limits = listOf(200, 500, 64, 128, 128, 128)
        for ((field, maximum) in ComplaintReportField.entries.zip(limits)) {
            val value = "😀".repeat(maximum)
            val normalized = Fixtures.text(Fixtures.accepted(Fixtures.withField(field, value)), field)
            assertEquals(value, normalized)
            assertEquals(maximum * 4, checkNotNull(normalized).encodeToByteArray().size)
            assertEquals(ComplaintReportRejection.TOO_LONG, Fixtures.rejected(Fixtures.withField(field, value + "😀")).reason)
            val scratch = Fixtures.rejected(Fixtures.withField(field, " ".repeat(16_385)))
            assertEquals(ComplaintReportRejection.TOO_LONG, scratch.reason)
        }
        assertEquals(ComplaintReportRejection.REQUIRED, Fixtures.rejected(Fixtures.result(subject = " \t\r\n")).reason)
        assertEquals(ComplaintReportRejection.REQUIRED, Fixtures.rejected(Fixtures.result(body = " \t\r\n")).reason)
        assertEquals(ComplaintReportRejection.TOO_SHORT, Fixtures.rejected(Fixtures.result(body = "😀".repeat(4))).reason)
        assertEquals("😀".repeat(5), Fixtures.request(body = "😀".repeat(5)).body)
    }

    @Test
    fun requestAndIntermediateTypesDoNotRevealIdentifiersOrProse() {
        val metadata = Fixtures.metadata("private-app", "private-os", "private-maker", "private-model")
        val result = Fixtures.result(subject = "private-subject", body = "private-body", metadata = metadata)
        val request = Fixtures.accepted(result)
        val fingerprint = ComplaintReportFingerprint.of(request)
        val rejected = Fixtures.rejected(Fixtures.result(subject = "private-subject".repeat(100)))
        val rendering = listOf(
            metadata, request.metadata, request, result, rejected, request.identity,
            request.identity.clientId, request.identity.key, request.identity.dataScope, fingerprint,
        ).joinToString()
        for (forbidden in listOf("private-", Fixtures.ID, Fixtures.KEY, Fixtures.LIVE, fingerprint.encoded)) {
            assertFalse(rendering.contains(forbidden))
        }
        assertTrue(rendering.contains("redacted"))
    }
}
