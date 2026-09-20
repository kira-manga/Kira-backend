package me.manga.kira.backend.security

import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.support.JwtTestSupport
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Clock
import java.util.Base64

/** Explicit throwaway TEST material only. No environment, configuration file or runtime secret lookup. */
internal fun historyTestJwtKey(): ByteArray = ByteArray(32) { (it + 3).toByte() }
internal fun historyTestCursorKey(seed: Int = 71): ByteArray = ByteArray(32) { (it + seed).toByte() }
internal fun historyTestUserKey(): ByteArray = Base64.getDecoder().decode(JwtTestSupport.TEST_JWT_SECRET_BASE64)

internal fun historyTestJwt(clock: Clock = Clock.systemUTC()): InstallationJwtCodec = InstallationJwtCodec(
    InstallationJwtKeyRing(
        "history-installation",
        listOf(InstallationJwtKeyMaterial("history-installation", historyTestJwtKey())),
        InstallationJwtForbiddenFamily(
            JwtTestSupport.ISSUER,
            JwtTestSupport.AUDIENCE,
            listOf(InstallationJwtKeyMaterial("history-user", historyTestUserKey())),
        ),
    ),
    clock,
)

internal fun historyTestCursors(
    clock: Clock = Clock.systemUTC(),
    active: String = "history-cursor",
    keys: Map<String, ByteArray> = mapOf("history-cursor" to historyTestCursorKey()),
): ComplaintOwnerCursorCodec = ComplaintOwnerCursorCodec(active, keys, listOf(historyTestJwtKey(), historyTestUserKey()), clock)

internal fun historyTestIngress(clock: ComplaintAdmissionNanoClock = SystemComplaintAdmissionNanoClock): ComplaintIngressAdmission = ComplaintIngressAdmission(
    ClientIpResolver(KiraSecurityProperties()),
    admissionTestPolicy(),
    ComplaintAdmissionKeyConfiguration(
        ComplaintAdmissionKey("history-admission", ByteArray(32) { (it + 171).toByte() }),
        null,
        listOf(
            ComplaintAdmissionForbiddenFamily("jwt", listOf(historyTestJwtKey(), historyTestUserKey())),
            ComplaintAdmissionForbiddenFamily("cursor", listOf(historyTestCursorKey())),
        ),
    ),
    clock,
)

internal fun historyTestRequest(token: String? = "test-token", query: String? = "limit=50", ip: String = "192.0.2.1"): MockHttpServletRequest =
    MockHttpServletRequest("GET", "/api/v1/complaints").apply {
        remoteAddr = ip
        queryString = query
        token?.let { addHeader("Authorization", "Bearer $it") }
        addHeader("Accept", "application/json, application/problem+json")
        addHeader("Accept-Encoding", "identity")
        addHeader("Cache-Control", "no-store, no-transform")
    }
