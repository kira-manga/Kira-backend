package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import me.manga.kira.backend.complaint.domain.ComplaintReportTestFixtures as Fixtures

/** Independent reply vectors only; the existing report goldens stay authoritative and unchanged. */
class ComplaintReplyFingerprintTest {
    @Test
    fun `two independent reply frames and digests bind the fixed route operation and ordered identities`() {
        val goldens = listOf(
            Golden(
                request(),
                BASIC_HEX,
                249,
                "6ac63649647daed223d03db6698d0f16fc871caf2ee276556f04a843bf99bc9e",
                "asY2SWR9rtIj0D22aY0PFvyHHK8u4nZVbwSoQ7-ZvJ4",
            ),
            Golden(
                unicodeRequest(),
                UNICODE_HEX,
                261,
                "38ef127b0a034c551d39ec18bacdb68bae3f0b0b81abe4997420649118c9256e",
                "OO8SewoDTFUdOewYus22i64_CwuBq-SZdCBkkRjJJW4",
            ),
        )
        for (golden in goldens) {
            val frame = ComplaintReplyFingerprint.frameBytes(golden.request)
            val fingerprint = ComplaintReplyFingerprint.of(golden.request)
            assertEquals(golden.size, frame.size)
            assertArrayEquals(Fixtures.hexBytes(golden.frameHex), frame)
            assertArrayEquals(Fixtures.hexBytes(golden.digestHex), fingerprint.bytes())
            assertEquals(golden.encoded, fingerprint.encoded)
            assertEquals(1, fingerprint.version)
            assertEquals(ComplaintOwnerCreationOperation.OWNER_REPLY, golden.request.operation)
        }
    }

    @Test
    fun `reply normalization accepts one through five hundred code points without hiding invalid edge characters`() {
        assertEquals("a", request(body = " \ta\u00a0 ").body)
        assertEquals("a".repeat(500), request(body = "a".repeat(500)).body)
        assertEquals("😀".repeat(500), request(body = "😀".repeat(500)).body)
        val raw = request(
            identity = Fixtures.identity(scope = Fixtures.TEST_SCOPE),
            body = " \u00a0A\r\n😀é\t ",
            metadata = Fixtures.metadata(" \t1.0\r\n ", " \u00a0 ", " M ", " D "),
        )
        assertArrayEquals(ComplaintReplyFingerprint.frameBytes(unicodeRequest()), ComplaintReplyFingerprint.frameBytes(raw))
        val invalid = listOf(
            "" to ComplaintReportRejection.REQUIRED,
            " \t\n\u00a0 " to ComplaintReportRejection.REQUIRED,
            "a".repeat(501) to ComplaintReportRejection.TOO_LONG,
            "😀".repeat(501) to ComplaintReportRejection.TOO_LONG,
            "a".repeat(16_385) to ComplaintReportRejection.TOO_LONG,
            "\ra" to ComplaintReportRejection.FORBIDDEN_CONTROL,
            "a\u0000" to ComplaintReportRejection.FORBIDDEN_CONTROL,
            "\ud800" to ComplaintReportRejection.MALFORMED_UNICODE,
            "a\udc00" to ComplaintReportRejection.MALFORMED_UNICODE,
        )
        for ((body, reason) in invalid) {
            val failure = assertThrows<ComplaintReportTextRejected> { request(body = body) }
            assertEquals(ComplaintReportField.BODY, failure.field)
            assertEquals(reason, failure.reason)
            assertEquals("Complaint report text rejected.", failure.message)
            assertNull(failure.cause)
        }
        assertEquals(
            ComplaintReportRejection.TOO_SHORT,
            Fixtures.rejected(Fixtures.result(body = "a")).reason,
            "Adding reply normalization must not relax the five-character report minimum.",
        )
    }

    @Test
    fun `parent client scope body and metadata are semantic while idempotency key stays separate`() {
        val original = request()
        val changed = listOf(
            request(parent = UUID.fromString(Fixtures.OTHER_KEY)),
            request(identity = Fixtures.identity(id = Fixtures.OTHER_KEY)),
            request(identity = Fixtures.identity(scope = Fixtures.TEST_SCOPE)),
            request(identity = Fixtures.identity(id = Fixtures.OTHER_ID), parent = UUID.fromString(Fixtures.ID)),
            request(body = "b"),
            request(metadata = Fixtures.metadata(appVersion = "")),
            request(metadata = Fixtures.metadata(osVersion = "1")),
            request(metadata = Fixtures.metadata(manufacturer = "M")),
            request(metadata = Fixtures.metadata(deviceModel = "D")),
        )
        val fingerprints = (listOf(original) + changed).map { ComplaintReplyFingerprint.of(it).encoded }
        assertEquals(fingerprints.size, fingerprints.toSet().size)
        val differentKey = request(identity = Fixtures.identity(key = Fixtures.OTHER_KEY))
        assertNotEquals(original.identity.key.value, differentKey.identity.key.value)
        assertArrayEquals(ComplaintReplyFingerprint.frameBytes(original), ComplaintReplyFingerprint.frameBytes(differentKey))
        val decomposed = request(Fixtures.identity(scope = Fixtures.TEST_SCOPE), body = "A\n😀e\u0301", metadata = unicodeMetadata())
        assertNotEquals(
            ComplaintReplyFingerprint.of(unicodeRequest()).encoded,
            ComplaintReplyFingerprint.of(decomposed).encoded,
        )
        assertNotEquals(
            ComplaintReportFingerprint.of(Fixtures.request()).encoded,
            ComplaintReplyFingerprint.of(request(body = "abcde")).encoded,
        )
    }

    @Test
    fun `maximal reply frame is bounded and returned bytes and diagnostics cannot expose or mutate retained prose`() {
        val request = request(
            body = "😀".repeat(500),
            metadata = Fixtures.metadata("😀".repeat(64), "😀".repeat(128), "😀".repeat(128), "😀".repeat(128)),
        )
        val frame = ComplaintReplyFingerprint.frameBytes(request)
        assertEquals(4_040, frame.size)
        assertTrue(frame.size <= 4_096)
        val fingerprint = ComplaintReplyFingerprint.of(request)
        assertEquals("n-RybksF209zKRBJYickTQZ5_kQIB8S4ojaZqReao88", fingerprint.encoded)
        val digest = fingerprint.bytes()
        assertEquals(32, digest.size)
        digest.fill(0)
        frame.fill(0)
        assertEquals("n-RybksF209zKRBJYickTQZ5_kQIB8S4ojaZqReao88", fingerprint.encoded)
        assertEquals(fingerprint.encoded, ComplaintReplyFingerprint.of(request).encoded)
        assertFalse(digest.contentEquals(fingerprint.bytes()))
        assertEquals("ComplaintReplyRequest(redacted)", request.toString())
        assertEquals("ComplaintReplyFingerprint(redacted)", fingerprint.toString())
    }

    @Test
    fun `reply tuple and status retain ordered defensive targets and reject cross operation or count aliases`() {
        val parent = UUID.fromString(Fixtures.OTHER_ID)
        val client = UUID.fromString(Fixtures.ID)
        val key = UUID.fromString(Fixtures.KEY)
        val actor = ScopedInstallationId(UUID.fromString(Fixtures.OTHER_KEY), ComplaintDataScope.LIVE)
        val fingerprint = ComplaintReplyFingerprint.of(request())
        val targets = mutableListOf(parent, client)
        val bytes = fingerprint.bytes()
        val tuple = ComplaintOwnerOperationTuple(actor, key, ComplaintOwnerCreationOperation.OWNER_REPLY, targets, bytes)
        targets.reverse()
        bytes.fill(0)
        (tuple.targetIds() as MutableList<UUID>).reverse()
        assertEquals(listOf(parent, client), tuple.targetIds())
        assertEquals(parent, tuple.parentId)
        assertEquals(client, tuple.targetId)
        assertArrayEquals(fingerprint.bytes(), tuple.fingerprintBytes())
        val equal = ComplaintOwnerOperationTuple(actor, key, ComplaintOwnerCreationOperation.OWNER_REPLY, listOf(parent, client), fingerprint.bytes())
        assertTrue(tuple.matches(equal))
        assertFalse(tuple.matches(ComplaintOwnerOperationTuple(actor, key, client, fingerprint.bytes())))
        assertFalse(tuple.matches(ComplaintOwnerOperationTuple(actor, key, ComplaintOwnerCreationOperation.OWNER_REPLY, targets, fingerprint.bytes())))
        val queryTargets = mutableListOf(parent.toString(), client.toString())
        val query = ComplaintOwnerStatusQuery("OWNER_REPLY", key.toString(), queryTargets, fingerprint.encoded)
        queryTargets.reverse()
        (query.targetIds() as MutableList<UUID>).reverse()
        query.fingerprintBytes().fill(0)
        assertEquals(ComplaintOwnerCreationOperation.OWNER_REPLY, query.operation)
        assertEquals(listOf(parent, client), query.targetIds())
        assertEquals(client, query.targetId)
        assertArrayEquals(fingerprint.bytes(), query.fingerprintBytes())
        for ((operation, ids) in listOf(
            "OWNER_REPLY" to listOf(client.toString()),
            "OWNER_REPLY" to listOf(parent.toString(), client.toString(), Fixtures.OTHER_KEY),
            "OWNER_REPLY" to listOf(client.toString(), client.toString()),
            "OWNER_CREATE" to listOf(parent.toString(), client.toString()),
        )) {
            val failure = assertThrows<ComplaintOwnerOperationRejected> { ComplaintOwnerStatusQuery(operation, key.toString(), ids, fingerprint.encoded) }
            assertEquals(ComplaintOwnerOperationFailure.INVALID_REQUEST, failure.failure)
        }
        val notice = UUID.fromString("77777777-7777-5777-8777-777777777777")
        assertEquals(notice, request(parent = notice).parentId)
        val noticeQuery = ComplaintOwnerStatusQuery("OWNER_REPLY", key.toString(), listOf(notice.toString(), client.toString()), fingerprint.encoded)
        assertEquals(listOf(notice, client), noticeQuery.targetIds())
        assertEquals("ComplaintOwnerOperationTuple(redacted)", tuple.toString())
        assertEquals("ComplaintOwnerStatusQuery(redacted)", query.toString())
    }

    private fun request(
        identity: ComplaintReportIdentity = Fixtures.identity(),
        parent: UUID = UUID.fromString(Fixtures.OTHER_ID),
        body: String = "a",
        metadata: ComplaintReportMetadataInput = Fixtures.metadata(),
    ): ComplaintReplyRequest = ComplaintReplyRequest.normalize(identity, parent, body, metadata)

    private fun unicodeMetadata(): ComplaintReportMetadataInput = Fixtures.metadata("1.0", "", "M", "D")

    private fun unicodeRequest(): ComplaintReplyRequest = request(Fixtures.identity(scope = Fixtures.TEST_SCOPE), body = "A\n😀é", metadata = unicodeMetadata())

    private class Golden(val request: ComplaintReplyRequest, val frameHex: String, val size: Int, val digestHex: String, val encoded: String)

    private companion object {
        // Literal protocol bytes authored independently of the production frame writer; no app parity claim.
        const val BASIC_HEX =
            "000000226b6972612d636f6d706c61696e742d726571756573742d66696e6765727072696e740000000100000004504f53540000001f2f6170692f76" +
                "312f636f6d706c61696e74732f7b69647d2f7265706c6965730000000b4f574e45525f5245504c590000002430303030303030302d303030302d3030" +
                "30302d303030302d303030303030303030303030000000020000002434343434343434342d343434342d343434342d383434342d3434343434343434" +
                "343434340000002431313131313131312d313131312d343131312d383131312d3131313131313131313131310000000161ffffffff00000000000000" +
                "0000000000ffffffff"
        const val UNICODE_HEX =
            "000000226b6972612d636f6d706c61696e742d726571756573742d66696e6765727072696e740000000100000004504f53540000001f2f6170692f76" +
                "312f636f6d706c61696e74732f7b69647d2f7265706c6965730000000b4f574e45525f5245504c590000002433333333333333332d333333332d3433" +
                "33332d383333332d333333333333333333333333000000020000002434343434343434342d343434342d343434342d383434342d3434343434343434" +
                "343434340000002431313131313131312d313131312d343131312d383131312d31313131313131313131313100000008410af09f9880c3a900000003" +
                "312e3000000000000000014d0000000144ffffffff"
    }
}
