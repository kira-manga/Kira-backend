package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.support.JwtTestSupport
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Clock
import java.util.UUID

/** Test-only composition of the actual normal signer and qualified decoder, never jwt() or an ADMIN principal stub. */
internal class AdminReadTestUserJwt {
    val properties = KiraSecurityProperties(jwtSecret = JwtTestSupport.TEST_JWT_SECRET_BASE64)
    private val keys = JwtKeyProvider(properties)
    val signer = JwtService(keys, properties, Clock.systemUTC())
    val decoder = SecurityConfig(MockEnvironment()).jwtDecoder(keys, properties)
}

internal fun adminReadTestCursorKey(): ByteArray = historyTestCursorKey(101)

internal fun adminReadTestCursors(clock: Clock = Clock.systemUTC()): ComplaintAdminCursorCodec = ComplaintAdminCursorCodec(
    "admin-read-cursor", mapOf("admin-read-cursor" to adminReadTestCursorKey()),
    listOf(historyTestJwtKey(), historyTestUserKey(), historyTestCursorKey()), clock,
)

/** An explicit lowerable TEST policy, not a change to the shipping Disabled default. */
internal fun adminReadTestIngress(
    clock: ComplaintAdmissionNanoClock = SystemComplaintAdmissionNanoClock,
    policy: ComplaintAdmissionPolicy = admissionTestPolicy(),
    adminPolicy: ComplaintAdminReadAdmissionPolicy = ComplaintAdminReadAdmissionPolicy.Bounded(),
): ComplaintIngressAdmission = ComplaintIngressAdmission(
    ClientIpResolver(KiraSecurityProperties()), policy,
    ComplaintAdmissionKeyConfiguration(
        ComplaintAdmissionKey("admin-read-admission", ByteArray(32) { (it + 201).toByte() }), null,
        listOf(
            ComplaintAdmissionForbiddenFamily("jwt", listOf(historyTestJwtKey(), historyTestUserKey())),
            ComplaintAdmissionForbiddenFamily("cursor", listOf(adminReadTestCursorKey(), historyTestCursorKey())),
        ),
    ),
    clock, adminReadPolicy = adminPolicy,
)

internal fun adminReadSearchRequest(
    scope: ComplaintDataScope,
    token: String? = "synthetic-token",
    body: String = """{"dataScopeId":"${scope.id}"}""",
    ip: String = "192.0.2.1",
): MockHttpServletRequest = MockHttpServletRequest("POST", "/api/v1/admin/complaints/search").apply {
    remoteAddr = ip
    token?.let { addHeader("Authorization", "Bearer $it") }
    contentType = "application/json"
    val bytes = body.toByteArray(Charsets.UTF_8)
    setContent(bytes)
    addHeader("Content-Length", bytes.size.toString())
    addHeader("X-Kira-Complaint-Contract", "1")
}

internal fun adminReadDetailRequest(scope: ComplaintDataScope, id: UUID, token: String? = "synthetic-token"): MockHttpServletRequest =
    MockHttpServletRequest("GET", "/api/v1/admin/complaints/$id").apply {
        remoteAddr = "192.0.2.1"
        queryString = "dataScopeId=${scope.id}"
        token?.let { addHeader("Authorization", "Bearer $it") }
        addHeader("X-Kira-Complaint-Contract", "1")
    }
