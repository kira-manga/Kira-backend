package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.application.ComplaintAdminBatchDeleteService
import me.manga.kira.backend.complaint.application.ComplaintAdminBatchStatusService
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeletePort
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeleteRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusAcknowledgement
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRejected
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.adminBatchDeleteTestIngress
import me.manga.kira.backend.security.adminBatchStatusTestRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

/** Supplied-data HTTP tests, not real authorization/commit evidence or public route activation. */
class ComplaintAdminBatchDeleteHttpTest {
    private val mapper = ObjectMapper()
    private val scope = ComplaintDataScope.of(UUID.randomUUID())
    private val key = UUID.randomUUID()
    private val grant = UUID.randomUUID()
    private val ids = listOf(UUID.fromString("123e4567-e89b-52d3-a456-426614174000"), UUID.fromString("fedcba98-7654-4321-8fed-cba987654321"))

    @Test
    fun oneBodyOneIngressAndOneSharedResponseSlotDispatchExactDeleteAckWithoutChangingStatus() {
        val f = Fixture()
        var bodyReads = 0
        val request = object : HttpServletRequestWrapper(input()) {
            override fun getInputStream(): ServletInputStream { bodyReads++; return super.getInputStream() }
        }
        val response = f.send(request)
        assertEquals(200, response.status); assertEquals(1, bodyReads)
        assertEquals(1, f.deleted); assertEquals(0, f.statusChanged)
        val body = mapper.readTree(response.contentAsByteArray)
        assertEquals(setOf("items"), body.fieldNames().asSequence().toSet())
        assertEquals(ids.map(UUID::toString), body["items"].map { it["id"].asText() })
        assertTrue(body["items"].all { it.fieldNames().asSequence().toSet() == setOf("id") })
        assertEquals("true", response.getHeader(ComplaintAdminBatchHttpHandler.CONSUMED_HEADER))
        assertEquals(grant.toString(), response.getHeader(ComplaintAdminBatchHttpHandler.CONSUMED_GRANT_HEADER))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertNull(response.getHeader("ETag")); assertNull(response.getHeader("Location"))
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        for (secret in listOf("private-proof", "synthetic-token", grant.toString(), key.toString(), scope.id.toString()))
            assertFalse(response.contentAsString.contains(secret))
        val slots = List(8) { checkNotNull(f.owner.acquire()) }
        try { assertNull(f.owner.acquire()) } finally { slots.forEach { it.close() } }
        val status = f.send(input("STATUS"))
        assertEquals(200, status.status); assertEquals(1, f.deleted); assertEquals(1, f.statusChanged)
        assertTrue(mapper.readTree(status.contentAsByteArray)["items"].all { it["version"].longValue() == 8L })
    }

    @Test
    fun scalarOrIncompleteBatchReceiptCannotCompleteEvenOneTargetAndKnown503KeepsOnlyOriginalGrant() {
        for ((selected, result) in listOf(
            ids to ComplaintAdminDeleteReceipt.Applied(grant),
            ids.take(1) to ComplaintAdminDeleteReceipt.Applied(grant),
            ids to ComplaintAdminDeleteReceipt.BatchApplied(ids.take(1), grant),
            ids.take(1) to ComplaintAdminDeleteReceipt.BatchApplied(ids, grant),
        )) {
            val f = Fixture().apply { receipt = result }
            val response = f.send(input(selected = selected))
            assertEquals(500, response.status)
            assertNull(response.getHeader(ComplaintAdminBatchHttpHandler.CONSUMED_HEADER))
            assertFalse(response.contentAsString.contains("items"))
            assertFalse(f.responses.isOpen())
        }
        for (known in listOf(false, true)) {
            val f = Fixture().apply { failure = ComplaintAdminDeleteRejected(ComplaintAdminDeleteFailure.UNAVAILABLE, if (known) grant else null) }
            val response = f.send(input())
            assertEquals(503, response.status)
            assertEquals(if (known) grant.toString() else null, response.getHeader(ComplaintAdminBatchHttpHandler.CONSUMED_GRANT_HEADER))
            assertEquals(if (known) "true" else null, response.getHeader(ComplaintAdminBatchHttpHandler.CONSUMED_HEADER))
            assertFalse(response.contentAsString.contains("items")); assertTrue(f.responses.isOpen())
        }
        val f = Fixture()
        val malformed = input().apply { addHeader("If-Match", "*") }
        assertEquals(400, f.send(malformed).status); assertEquals(0, f.deleted)
    }

    private fun input(action: String = "DELETE", selected: List<UUID> = ids): org.springframework.mock.web.MockHttpServletRequest {
        val body = linkedMapOf<String, Any>("action" to action)
        if (action == "STATUS") body["status"] = "RESOLVED"
        body["targets"] = selected.reversed().map { linkedMapOf("id" to it.toString(), "actionTag" to "\"complaint-$it-v${if (action == "DELETE") Long.MAX_VALUE else 7}\"") }
        return adminBatchStatusTestRequest(scope, key, "synthetic-token", "private-proof", mapper.writeValueAsString(body))
    }

    private inner class Fixture {
        val ingress = adminBatchDeleteTestIngress()
        val owner = ComplaintOwnerHistoryResponses()
        val responses = ComplaintAdminBatchStatusResponses(owner)
        var deleted = 0
        var statusChanged = 0
        var receipt: ComplaintAdminDeleteReceipt = ComplaintAdminDeleteReceipt.BatchApplied(ids, grant)
        var failure: ComplaintAdminDeleteRejected? = null
        private fun retained(context: ComplaintIngressContext, bearer: String, proof: String?) {
            requireConnectionFree(); ingress.requireLiveContext(context)
            assertEquals("synthetic-token", bearer); assertEquals("private-proof", proof)
            val otherSlots = List(7) { checkNotNull(owner.acquire()) }
            try { assertNull(owner.acquire(), "The dispatcher must retain exactly one shared slot through the port.") }
            finally { otherSlots.forEach { it.close() } }
        }
        val handler = ComplaintAdminBatchHttpHandler(ComplaintAdminBatchStatusService(object : ComplaintAdminBatchStatusPort {
            override fun change(context: ComplaintAdminBatchStatusRequestContext, bearer: String, proof: String?, input: ComplaintAdminBatchStatusInput): ComplaintAdminBatchStatusReceipt {
                retained(context as ComplaintIngressContext, bearer, proof); statusChanged++
                return ComplaintAdminBatchStatusReceipt.Applied(input.targets.map { ComplaintAdminBatchStatusAcknowledgement(it.id, 8) }, grant)
            }
        }), ingress, responses, ComplaintAdminBatchDeleteService(object : ComplaintAdminBatchDeletePort {
            override fun deleteBatch(context: ComplaintAdminBatchDeleteRequestContext, bearer: String, proof: String?, input: ComplaintAdminBatchDeleteInput): ComplaintAdminDeleteReceipt {
                retained(context as ComplaintIngressContext, bearer, proof); deleted++
                assertEquals(input.targets.map { it.id }.sortedBy(UUID::toString), input.targets.map { it.id })
                assertTrue(input.targets.all { it.precondition.version == Long.MAX_VALUE })
                failure?.let { throw it }
                return receipt
            }
        }))
        fun send(request: HttpServletRequest) = MockHttpServletResponse().also { handler.handleRequest(request, it) }
    }
}
