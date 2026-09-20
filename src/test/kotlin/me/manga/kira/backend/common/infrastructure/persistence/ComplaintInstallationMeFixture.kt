package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.api.ComplaintInstallationHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintInstallationMeHttpHandler
import me.manga.kira.backend.complaint.api.installationMeTestRequest
import me.manga.kira.backend.complaint.application.ComplaintInstallationMeService
import me.manga.kira.backend.complaint.application.ComplaintInstallationService
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationMeReadAdapter
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerHistoryStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerHistoryPhaseExecutor
import me.manga.kira.backend.security.historyTestJwt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Base64
import java.util.UUID

/** Thin composition on the existing owned PG fixture. Synthetic TEST rows are not production activation authority. */
internal class ComplaintInstallationMeFixture(val base: OrdinaryComplaintInstallationEnrollmentFixture, val run: OrdinaryComplaintTestInstallationFixture) {
    val id: UUID = UUID.randomUUID().also { base.ids.add(it) }
    val ingress = enrollmentAdmissionTestIngress(base)
    val jwt = historyTestJwt()
    val phases = ComplaintOwnerHistoryPhaseExecutor(base.ordinary.ownership, JdbcComplaintOwnerHistoryStore(base.ordinary.jdbc, run.scope))
    val reader = newReader()
    val handler = ComplaintInstallationMeHttpHandler(ComplaintInstallationMeService(reader), ingress)
    private val mapper = ObjectMapper()
    private val exchange = SyntheticInstallationExchangeFixture(run.desired, base.ordinary.ownership, base.jdbc, base.capacity, base.audit, ingress, jwt)
    private val sessions = ComplaintInstallationHttpHandler(ComplaintInstallationService(exchange), ingress)
    val location = enrollViaHttp()
    val token = issueViaHttp()

    fun newReader(): ComplaintInstallationMeReadAdapter = ComplaintInstallationMeReadAdapter(run.scope, jwt, phases, ingress)

    fun request(bearer: String? = token): MockHttpServletResponse = MockHttpServletResponse().also {
        handler.handleRequest(installationMeTestRequest(bearer).apply { requestURI = location }, it)
    }

    fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsByteArray)

    fun assertProblem(response: MockHttpServletResponse, status: Int, code: String) {
        assertEquals(status, response.status)
        assertEquals(code, json(response)["errors"][0]["code"].asText())
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals(if (status == 401) "Bearer realm=\"kira-complaints\"" else null, response.getHeader("WWW-Authenticate"))
        assertNull(response.getHeader("ETag"))
        assertNull(response.getHeader("Location"))
        assertFalse(response.contentAsString.contains(id.toString()))
        assertFalse(response.contentAsString.contains(token))
    }

    fun assertReleased() {
        base.assertReleased()
        assertEquals(0L, base.ordinary.ownedPool.lifecycle.activeAcquisitions())
        assertEquals(0L, base.ordinary.ownedPool.lifecycle.actorSnapshot().futureLeaseEntries)
        requireConnectionFree()
    }

    private fun enrollViaHttp(): String {
        val created = MockHttpServletResponse()
        sessions.handleRequest(sessionInput(enrollment = true), created)
        assertEquals(201, created.status)
        assertReleased()
        val location = checkNotNull(created.getHeader("Location"))
        assertEquals("/api/v1/installations/me", location)
        return location
    }

    private fun issueViaHttp(): String {
        val response = MockHttpServletResponse()
        sessions.handleRequest(sessionInput(enrollment = false), response)
        assertEquals(200, response.status)
        assertReleased()
        val token = json(response)["accessToken"].asText() // The historical synthetic lower HTTP recipe; not genuine registration.
        val verified = jwt.verify(token)
        assertEquals(id, verified.installation.id)
        assertEquals(run.scope, verified.installation.scope)
        assertEquals(1L, verified.credentialVersion)
        return token
    }

    private fun sessionInput(enrollment: Boolean): MockHttpServletRequest {
        val path = if (enrollment) "/api/v1/installations" else "/api/v1/installations/session"
        return MockHttpServletRequest("POST", path).apply {
            remoteAddr = "192.0.2.1"
            contentType = "application/json"
            setContent(
                mapper.writeValueAsBytes(
                    linkedMapOf(
                        "installationId" to id.toString(),
                        "secret" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }),
                        "expectedDataScopeId" to run.scope.id.toString(),
                    ).apply { if (enrollment) put("platform", "ANDROID") },
                ),
            )
        }
    }
}
