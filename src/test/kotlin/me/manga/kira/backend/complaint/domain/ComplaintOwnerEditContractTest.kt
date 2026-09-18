package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.HexFormat
import java.util.UUID

/** Independently constructed backend literals; no mobile edit producer/parity claim. */
class ComplaintOwnerEditContractTest {
    private val scope = ComplaintDataScope.of(UUID.fromString("22222222-2222-4222-8222-222222222222"))
    private val target = UUID.fromString("123e4567-e89b-52d3-a456-426614174000")
    private val key = UUID.fromString("33333333-3333-4333-8333-333333333333")

    @Test
    fun `ordinary and body only inputs normalize CRLF and fixed trim without NFC or reply body limits`() {
        val ordinary = request(" \u3000Synthetic edit\u00a0 ", "\t Line 1\r\nLine 2 \n")
        assertEquals("Synthetic edit", ordinary.subject)
        assertEquals("Line 1\nLine 2", ordinary.body)
        assertNull(request(null, " Notice reply ").subject)
        assertEquals("x".repeat(1000), request(null, "x".repeat(1000)).body)
        assertNotEquals(request("é", "body").subject, request("e\u0301", "body").subject)
        assertEquals("ComplaintOwnerEditRequest(redacted)", ordinary.toString())
        assertEquals("ComplaintOwnerEditInput(redacted)", input("subject", "body").toString())
    }

    @Test
    fun `edited text exact code point and UTF8 maxima reject controls malformed Unicode and one over`() {
        val maximum = request("🙂".repeat(200), "🙂".repeat(1000))
        assertEquals(800, checkNotNull(maximum.subject).toByteArray().size)
        assertEquals(4000, maximum.body.toByteArray().size)
        val rejected = listOf(
            Triple("🙂".repeat(201), "body", ComplaintReportRejection.TOO_LONG),
            Triple("subject", "🙂".repeat(1001), ComplaintReportRejection.TOO_LONG),
            Triple("", "body", ComplaintReportRejection.REQUIRED),
            Triple("subject", " \n\t", ComplaintReportRejection.REQUIRED),
            Triple("subject", "a\rb", ComplaintReportRejection.FORBIDDEN_CONTROL),
            Triple("subject", "a\u0000b", ComplaintReportRejection.FORBIDDEN_CONTROL),
            Triple("subject", "a\u007fb", ComplaintReportRejection.FORBIDDEN_CONTROL),
            Triple("\ud800", "body", ComplaintReportRejection.MALFORMED_UNICODE),
            Triple("subject", "a\udc00", ComplaintReportRejection.MALFORMED_UNICODE),
        )
        rejected.forEach { (subject, body, reason) ->
            assertEquals(reason, assertThrows<ComplaintReportTextRejected> { request(subject, body) }.reason)
        }
    }

    @Test
    fun `precondition is a bounded single canonical strong target tag with positive long version`() {
        val exact = "\"complaint-$target-v7\""
        val parsed = ComplaintOwnerEditPrecondition.parse(target, " \t$exact\t ")
        assertEquals(7L, parsed.version)
        assertEquals(exact, parsed.canonical)
        assertEquals(Long.MAX_VALUE, ComplaintOwnerEditPrecondition.parse(target, "\"complaint-$target-v${Long.MAX_VALUE}\"").version)
        assertEquals(exact, ComplaintOwnerEditPrecondition.parse(target, " ".repeat(256 - exact.length) + exact).canonical)
        assertEquals(
            ComplaintOwnerOperationFailure.PRECONDITION_REQUIRED,
            assertThrows<ComplaintOwnerOperationRejected> { ComplaintOwnerEditPrecondition.parse(target, null) }.failure,
        )
        listOf(
            "", "*", "W/$exact", "$exact,$exact", exact.removeSurrounding("\""), "$exact\r\n", "\u00a0$exact",
            exact.replace("v7", "v0"), exact.replace("v7", "v07"), exact.replace("v7", "v-1"),
            exact.replace("v7", "v9223372036854775808"), exact.replace(target.toString(), key.toString()),
            exact.uppercase(), " ".repeat(257 - exact.length) + exact,
        ).forEach { invalid ->
            assertEquals(
                ComplaintOwnerOperationFailure.PRECONDITION_FAILED,
                assertThrows<ComplaintOwnerOperationRejected> { ComplaintOwnerEditPrecondition.parse(target, invalid) }.failure,
            )
        }
    }

    @Test
    fun `v1 edit frame has independent ordinary body only and maximal literals and does not bind key`() {
        val ordinary = request("Synthetic edit", "Line 1\nLine 2")
        val bodyOnly = request(null, "Notice reply")
        assertArrayEquals(HexFormat.of().parseHex(ORDINARY_HEX), ComplaintOwnerEditFingerprint.frameBytes(ordinary))
        assertArrayEquals(HexFormat.of().parseHex(BODY_ONLY_HEX), ComplaintOwnerEditFingerprint.frameBytes(bodyOnly))
        assertEquals("cXjLO2OeE8ZUn_4TyoWns9JwhcdBoL-CcTAB4emRHOY", ComplaintOwnerEditFingerprint.of(ordinary).encoded)
        assertEquals("dN0dOWXt0nhHTb4LTOalYCg92MRxI8EHfiQJikM3ij4", ComplaintOwnerEditFingerprint.of(bodyOnly).encoded)
        val maximum = request("🙂".repeat(200), "🙂".repeat(1000), Long.MAX_VALUE)
        assertEquals(5065, ComplaintOwnerEditFingerprint.frameBytes(maximum).size)
        assertEquals(6144, ComplaintOwnerEditFingerprint.MAX_FRAME_BYTES)
        assertEquals("WSE34gdD0_m4v2wA7W7aWbAv8jaxpT1ZIivnhU3Vzcc", ComplaintOwnerEditFingerprint.of(maximum).encoded)
        assertEquals(
            ComplaintOwnerEditFingerprint.of(ordinary).encoded,
            ComplaintOwnerEditFingerprint.of(ComplaintOwnerEditRequest.normalize(scope, input("Synthetic edit", "Line 1\nLine 2", selectedKey = UUID.randomUUID()))).encoded,
        )
        for (changed in listOf(request("Other", ordinary.body), request(null, ordinary.body), request(ordinary.subject, "Other"), request(ordinary.subject, ordinary.body, 8))) {
            assertNotEquals(ComplaintOwnerEditFingerprint.of(ordinary).encoded, ComplaintOwnerEditFingerprint.of(changed).encoded)
        }
        assertNotEquals(
            ComplaintOwnerEditFingerprint.of(ordinary).encoded,
            ComplaintOwnerEditFingerprint.of(ComplaintOwnerEditRequest.normalize(ComplaintDataScope.LIVE, input(ordinary.subject, ordinary.body))).encoded,
        )
    }

    @Test
    fun `edit status and tuple keep exact copied identity without weakening creation contracts`() {
        val digest = ComplaintOwnerEditFingerprint.of(request(null, "Notice reply"))
        val actor = ScopedInstallationId(UUID.randomUUID(), scope)
        val supplied = digest.bytes()
        val tuple = ComplaintOwnerEditTuple(actor, key, target, supplied)
        supplied.fill(0)
        tuple.fingerprintBytes().fill(0)
        assertArrayEquals(digest.bytes(), tuple.fingerprintBytes())
        assertTrue(tuple.matches(ComplaintOwnerEditTuple(actor, key, target, digest.bytes())))
        listOf(
            ComplaintOwnerEditTuple(ScopedInstallationId(UUID.randomUUID(), scope), key, target, digest.bytes()),
            ComplaintOwnerEditTuple(ScopedInstallationId(actor.id, ComplaintDataScope.LIVE), key, target, digest.bytes()),
            ComplaintOwnerEditTuple(actor, UUID.randomUUID(), target, digest.bytes()),
            ComplaintOwnerEditTuple(actor, key, UUID.randomUUID(), digest.bytes()),
            ComplaintOwnerEditTuple(actor, key, target, ByteArray(32)),
        ).forEach { assertFalse(tuple.matches(it)) }
        val query = ComplaintOwnerEditStatusQuery(key.toString(), listOf(target.toString()), digest.encoded)
        assertEquals(target, query.targetId) // Existing non-v4 resource UUIDs are legal.
        query.fingerprintBytes().fill(0)
        assertArrayEquals(digest.bytes(), query.fingerprintBytes())
        for (targets in listOf(emptyList(), listOf(target.toString(), key.toString()))) {
            assertThrows<ComplaintOwnerOperationRejected> { ComplaintOwnerEditStatusQuery(key.toString(), targets, digest.encoded) }
        }
        assertThrows<ComplaintValidationException> { ComplaintOwnerEditStatusQuery(target.toString(), listOf(target.toString()), digest.encoded) }
        assertThrows<ComplaintValidationException> { ComplaintOwnerEditStatusQuery(key.toString(), listOf(target.toString().uppercase()), digest.encoded) }
        assertThrows<ComplaintValidationException> { ComplaintOwnerEditStatusQuery(key.toString(), listOf(target.toString()), "${digest.encoded}=") }
        assertEquals(setOf("OWNER_CREATE", "OWNER_REPLY"), ComplaintOwnerCreationOperation.entries.map { it.name }.toSet())
        assertThrows<ComplaintOwnerOperationRejected> { ComplaintOwnerStatusQuery("OWNER_EDIT", key.toString(), target.toString(), digest.encoded) }
        assertThrows<IllegalArgumentException> { ComplaintOwnerReceipt.Applied(UUID.randomUUID(), 2) }
        assertEquals("ComplaintOwnerEditTuple(redacted)", tuple.toString())
    }

    @Test
    fun `edit positive version receipt has closed rejections and receipt plus audit charge with zero content allocation`() {
        val receipt = ComplaintOwnerEditReceipt.Applied(target, Long.MAX_VALUE)
        assertEquals("\"complaint-$target-v${Long.MAX_VALUE}\"", receipt.etag)
        for (version in listOf(-1L, 0L)) assertThrows<IllegalArgumentException> { ComplaintOwnerEditReceipt.Applied(target, version) }
        assertEquals(
            mapOf("COMPLAINT_NOT_FOUND" to 404, "COMPLAINT_INVALID_TRANSITION" to 409, "COMPLAINT_NO_CHANGE" to 409, "COMPLAINT_DELETION_PENDING" to 409, "PRECONDITION_FAILED" to 412),
            ComplaintOwnerEditRejection.entries.associate { it.name to it.status },
        )
        val expected = LongArray(22).also {
            it[ComplaintCapacityCounter.NORMAL_RECEIPTS.storedOrdinal - 1] = 1
            it[ComplaintCapacityCounter.AUDIT_ROWS.storedOrdinal - 1] = 1
            it[ComplaintCapacityCounter.STORAGE_BYTES.storedOrdinal - 1] = 196608
        }
        assertArrayEquals(expected, ComplaintCapacityCharges.OWNER_EDIT.toLongArray())
        assertEquals(ComplaintCapacityCharges.NORMAL_RECEIPT, ComplaintCapacityCharges.OWNER_EDIT - ComplaintCapacityCharges.AUDIT)
        assertEquals(131072L, ComplaintCapacityCharges.NORMAL_RECEIPT[ComplaintCapacityCounter.STORAGE_BYTES])
        ComplaintCapacityCharges.OWNER_EDIT.toLongArray().fill(0)
        assertArrayEquals(expected, ComplaintCapacityCharges.OWNER_EDIT.toLongArray())
    }

    private fun input(subject: String?, body: String, version: Long = 7, selectedKey: UUID = key) = ComplaintOwnerEditInput(
        target, selectedKey, subject, body, ComplaintOwnerEditPrecondition.parse(target, "\"complaint-$target-v$version\""),
    )

    private fun request(subject: String?, body: String, version: Long = 7) = ComplaintOwnerEditRequest.normalize(scope, input(subject, body, version))

    private companion object {
        // Python struct.pack('>i', UTF8 length), -1 null; independently constructed before the implementation tests.
        const val ORDINARY_HEX = "000000226b6972612d636f6d706c61696e742d726571756573742d66696e6765727072696e74000000010000000550415443480000001f2f6170692f76312f636f6d706c61696e74732f7b69647d2f636f6e74656e740000000a4f574e45525f454449540000002432323232323232322d323232322d343232322d383232322d323232323232323232323232000000010000002431323365343536372d653839622d353264332d613435362d3432363631343137343030300000000e53796e74686574696320656469740000000d4c696e6520310a4c696e6520320000003322636f6d706c61696e742d31323365343536372d653839622d353264332d613435362d3432363631343137343030302d763722"
        const val BODY_ONLY_HEX = "000000226b6972612d636f6d706c61696e742d726571756573742d66696e6765727072696e74000000010000000550415443480000001f2f6170692f76312f636f6d706c61696e74732f7b69647d2f636f6e74656e740000000a4f574e45525f454449540000002432323232323232322d323232322d343232322d383232322d323232323232323232323232000000010000002431323365343536372d653839622d353264332d613435362d343236363134313734303030ffffffff0000000c4e6f74696365207265706c790000003322636f6d706c61696e742d31323365343536372d653839622d353264332d613435362d3432363631343137343030302d763722"
    }
}
