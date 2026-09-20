package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.api.ComplaintAdminReadHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintAdminReadResponses
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryResponses
import me.manga.kira.backend.complaint.application.ComplaintAdminReadService
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadIdentity
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminReadStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminReadPhaseExecutor
import me.manga.kira.backend.security.AdminReadTestUserJwt
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.adminReadDetailRequest
import me.manga.kira.backend.security.adminReadSearchRequest
import me.manga.kira.backend.security.adminReadStatsRequest
import me.manga.kira.backend.security.adminReadTestCursors
import me.manga.kira.backend.security.adminReadTestIngress
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.time.Instant
import java.util.UUID

/** Consumed composition on the existing PG/ordinary/row owners; no new runner, pool, fake mode or product port. */
internal class ComplaintAdminReadFixture(
    val rows: ComplaintOwnerHistoryFixture,
    val ingress: ComplaintIngressAdmission = adminReadTestIngress(),
    val responseOwner: ComplaintOwnerHistoryResponses = rows.responses,
) {
    val base = rows.base
    val ordinary = rows.ordinary
    val observer = rows.observer
    val run = rows.run
    val scope: ComplaintDataScope = run.scope
    val userJwt = AdminReadTestUserJwt()
    var decoderCalls = 0
        private set
    val decoder = JwtDecoder { value ->
        requireConnectionFree()
        decoderCalls++
        userJwt.decoder.decode(value)
    }
    val cursors = adminReadTestCursors()
    val store = JdbcComplaintAdminReadStore(ordinary.jdbc, scope)
    val phases = ComplaintAdminReadPhaseExecutor(ordinary.ownership, store)
    val reader = newReader()
    val responses = ComplaintAdminReadResponses(responseOwner)
    val handler = ComplaintAdminReadHttpHandler(ComplaintAdminReadService(reader), ingress, responses)
    val token: String = userJwt.signer.issue(user()).value
    private val mapper = ObjectMapper()

    fun newReader(): ComplaintAdminReadAdapter =
        ComplaintAdminReadAdapter(scope, decoder, cursors, phases, ingress, userJwt.properties.clockSkew)

    /** Real bcrypt-hashed row originally created by the existing fixture; no principal/role impersonation. */
    fun user(): User = observer.queryForObject(
        "SELECT id, email, password_hash, role, enabled, created_at, updated_at, credential_version FROM users WHERE id = ?",
        { row, _ ->
            User(
                row.getObject("id", UUID::class.java), row.getString("email"), row.getString("password_hash"), Role.valueOf(row.getString("role")),
                row.getBoolean("enabled"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(), row.getLong("credential_version"),
            )
        },
        ordinary.userId,
    )!!

    fun identity(credentialVersion: Long = 0): ComplaintAdminReadIdentity =
        ComplaintAdminReadIdentity(ordinary.userId, scope, credentialVersion.toString(), null, Instant.now().plusSeconds(600))

    fun search(body: String = """{"dataScopeId":"${scope.id}"}""", bearer: String? = token): MockHttpServletResponse =
        request(adminReadSearchRequest(scope, bearer, body))

    fun detail(id: UUID, bearer: String? = token): MockHttpServletResponse = request(adminReadDetailRequest(scope, id, bearer))

    fun stats(bearer: String? = token): MockHttpServletResponse = request(adminReadStatsRequest(scope, bearer))

    fun request(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also {
        handler.handleRequest(request, it)
        // Another owned test caller may deliberately hold the admission ceiling, but this caller must be connection-free.
        requireConnectionFree()
    }

    fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsByteArray)

    fun itemIds(response: MockHttpServletResponse): List<UUID> = json(response)["items"].map { UUID.fromString(it["id"].asText()) }

    fun cursor(response: MockHttpServletResponse): String? = json(response)["nextCursor"].let { if (it.isNull) null else it.asText() }

    fun assertProblem(response: MockHttpServletResponse, status: Int, code: String) {
        assertEquals(status, response.status)
        assertEquals(code, json(response)["errors"][0]["code"].asText())
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
    }

    fun <T> withPhase(
        enter: () -> PersistencePhaseContext = ordinary.ownership::enterComplaintAdminSearch,
        work: (PersistencePhaseContext) -> T,
    ): T = rows.withPhase(enter, work)
}
