package me.manga.kira.backend.complaint.domain

import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateCandidate
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import java.util.UUID

class ComplaintOwnerOperationTest {
    private val actor = ScopedInstallationId(UUID.randomUUID(), ComplaintDataScope.of(UUID.randomUUID()))
    private val key = UUID.randomUUID()
    private val id = UUID.randomUUID()
    private val digest = ByteArray(32) { it.toByte() }

    @Test
    fun `comparison tuple copies its digest and compares every identity dimension without retaining prose`() {
        val supplied = digest.copyOf()
        val tuple = ComplaintOwnerOperationTuple(actor, key, id, supplied)
        supplied.fill(0)
        tuple.fingerprintBytes().fill(0)
        assertArrayEquals(digest, tuple.fingerprintBytes())
        assertTrue(tuple.matches(ComplaintOwnerOperationTuple(actor, key, id, digest)))
        val changed = listOf(
            ComplaintOwnerOperationTuple(ScopedInstallationId(UUID.randomUUID(), actor.scope), key, id, digest),
            ComplaintOwnerOperationTuple(ScopedInstallationId(actor.id, ComplaintDataScope.LIVE), key, id, digest),
            ComplaintOwnerOperationTuple(actor, UUID.randomUUID(), id, digest),
            ComplaintOwnerOperationTuple(actor, key, UUID.randomUUID(), digest),
            ComplaintOwnerOperationTuple(actor, key, id, ByteArray(32) { 91 }),
        )
        changed.forEach { assertFalse(tuple.matches(it)) }
        assertFalse(tuple.toString().contains(id.toString()))
        assertThrows<IllegalArgumentException> { ComplaintOwnerOperationTuple(actor, key, id, ByteArray(31)) }
    }

    @Test
    fun `status admits only canonical create identity with an independently copied digest`() {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        val query = ComplaintOwnerStatusQuery("OWNER_CREATE", key.toString(), id.toString(), encoded)
        query.fingerprintBytes().fill(0)
        assertArrayEquals(digest, query.fingerprintBytes())
        assertEquals(key, query.key)
        assertEquals(id, query.targetId)
        listOf("CREATE_REPORT", "OWNER_REPLY", "ADMIN_EDIT", "owner_create").forEach { operation ->
            assertEquals(
                ComplaintOwnerOperationFailure.INVALID_REQUEST,
                assertThrows<ComplaintOwnerOperationRejected> { ComplaintOwnerStatusQuery(operation, key.toString(), id.toString(), encoded) }.failure,
            )
        }
        assertThrows<ComplaintValidationException> { ComplaintOwnerStatusQuery("OWNER_CREATE", key.toString(), id.toString(), "$encoded=") }
        assertThrows<ComplaintValidationException> { ComplaintOwnerStatusQuery("OWNER_CREATE", "00000000-0000-4000-8000-00000000000A", id.toString(), encoded) }
    }

    @Test
    fun `create candidate consumes the approved normalized request and binds the retained body to its fingerprint`() {
        val identity = checkNotNull(ComplaintReportIdentity.checked(id.toString(), key.toString(), actor.scope.id.toString()))
        fun request(body: String): ComplaintReportRequest = (
            ComplaintReportRequest.normalize(
                identity,
                ComplaintType.TECHNICAL,
                "  Synthetic subject  ",
                body,
                ComplaintReportMetadataInput(null, "", "", ""),
            ) as ComplaintReportRequestResult.Accepted
            ).request
        val accepted = request("  Synthetic body\r\nline  ")
        val candidate = ComplaintOwnerCreateCandidate.prepare(actor, accepted)
        assertEquals("Synthetic subject", candidate.request.subject)
        assertEquals("Synthetic body\nline", candidate.request.body)
        assertArrayEquals(ComplaintReportFingerprint.of(accepted).bytes(), candidate.tuple.fingerprintBytes())
        val substituted = ComplaintOwnerCreateCandidate.prepare(actor, request("Different body"))
        assertFalse(candidate.tuple.matches(substituted.tuple))
        assertNotEquals(candidate.tuple, substituted.tuple)
        assertThrows<IllegalArgumentException> {
            ComplaintOwnerCreateCandidate.prepare(ScopedInstallationId(actor.id, ComplaintDataScope.LIVE), accepted)
        }
        assertEquals("ComplaintOwnerCreateCandidate(redacted)", candidate.toString())
    }

    @Test
    fun `create receipts have exactly two rejections and preserve only the original version one acknowledgement`() {
        assertEquals(setOf("COMPLAINT_CAPACITY_REACHED", "COMPLAINT_RESOURCE_ID_REUSED"), ComplaintOwnerCreateRejection.entries.map { it.name }.toSet())
        val receipt = ComplaintOwnerReceipt.Applied(id, 1)
        assertEquals("/api/v1/complaints/$id", receipt.location)
        assertEquals("\"complaint-$id-v1\"", receipt.etag)
        listOf(-1L, 0L, 2L, Long.MAX_VALUE).forEach { version ->
            assertThrows<IllegalArgumentException> { ComplaintOwnerReceipt.Applied(id, version) }
        }
        assertEquals("ComplaintOwnerReceipt(redacted)", receipt.toString())
    }
}
