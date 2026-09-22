package me.manga.kira.backend.complaint.infrastructure.reconciliation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.CounterSnapshot
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutFixtureV1
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminContentInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminReadInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminStatusInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredHttpStartupCasesV1.StartedHttpView
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.JwtKeyProvider
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/** Request bytes only, never an authenticated actor, grant, checkpoint, receipt or successful mutation. */
internal data class RegisteredAdminContentAttemptV1(
    val id: UUID,
    val key: UUID = UUID.randomUUID(),
    val version: Long = 1,
    val type: String = "TECHNICAL",
    val subject: String = " Registered Admin subject ",
    val body: String = " Registered Admin body\r\nline ",
) {
    val etag: String get() = "\"complaint-$id-v$version\""
}

/** Actual HTTP response observation. Plaintext proof is never a diagnostic string or database fixture. */
internal class RegisteredAdminIssuedProofV1(val token: String, val id: UUID, val expiresAt: Instant) {
    override fun toString(): String = "RegisteredAdminIssuedProofV1(redacted)"
}

/** Thin view of the existing registered activation/checkpoint/loopback graph; owns no replacement resources. */
internal class TestRegisteredAdminContentHttpFixtureV1(
    val first: TestActiveFirstCutFixtureV1,
    val ordinary: TestActiveOrdinaryRawFixtureV1,
    val raw: TestActiveInitialCheckpointRawFixtureV1,
    val web: StartedHttpView,
    val users: List<User>,
    val signer: JwtService,
) {
    val scope: ComplaintDataScope = first.process.desiredSettings().scope
    val observer = first.observer
    val owner = first.initial.candidate().installation
    val composition: ComplaintTestBootstrapHttpCompositionV1 get() = web.context.getBean(ComplaintTestBootstrapHttpCompositionV1::class.java)
    val ownerBearer: String by lazy {
        val response = web.post(ComplaintInstallationRoutes.ENROLLMENT, mapper.writeValueAsBytes(mapOf(
            "installationId" to owner.id.toString(), "expectedDataScopeId" to scope.id.toString(), "platform" to "ANDROID",
            "secret" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }),
        )))
        checked(response, 201)
        checkNotNull(json(response)["accessToken"].textValue()).also { value ->
            assertEquals(owner, InstallationJwtCodec(first.process.consumers.jwt.installationKeyRing, Clock.systemUTC()).verify(value).installation)
            web.assertRequestsReleased()
        }
    }

    fun admin(index: Int = 0): String = signer.issue(users[index]).value

    fun withCurrent(action: () -> Unit) {
        ownerBearer // Genuine enrollment must precede the actual captured seal/checkpoint.
        val captured = first.capture()
        first.awaitNativeReclaimed()
        TestActiveOrdinarySealFixtureV1(first, captured, ordinary).use { sealer ->
            val verified = sealer.seal(); sealer.assertReleased()
            awaitInitialCheckpointLeaseExpiry(sealer.observer, sealer.scope)
            TestActiveInitialCheckpointFixtureV1(sealer, verified, raw).use { checkpoint ->
                checkpoint.checkpoint() // No retained Completed result is supplied to the registered writer.
                checkpoint.assertReleased()
                first.p.f.rows.globalPredecessor?.assertPreserved(observer)
                action()
                web.assertRequestsReleased(); checkpoint.assertReleased()
            }
        }
    }

    fun report(): UUID {
        val id = UUID.randomUUID()
        val created = web.post(ComplaintInstallationRoutes.HISTORY, mapper.writeValueAsBytes(mapOf(
            "id" to id.toString(), "type" to "TECHNICAL", "subject" to "Original registered report", "body" to "Original registered body",
            "metadata" to mapOf("appVersion" to "registered-admin-content-fixture", "osVersion" to "fixture-os", "manufacturer" to "", "deviceModel" to ""),
        )), ownerBearer, UUID.randomUUID())
        checked(created, 201); assertEquals(id.toString(), json(created)["id"].textValue()); assertEquals(1L, json(created)["version"].longValue())
        web.assertRequestsReleased()
        return id
    }

    fun issue(bearer: String? = admin(), password: String = PASSWORD, selectedScope: String = PROOF_SCOPE): HttpResponse<ByteArray> =
        web.post(STEP_UP, mapper.writeValueAsBytes(mapOf("password" to password, "scope" to selectedScope)), bearer)

    fun proof(index: Int = 0, bearer: String = admin(index)): RegisteredAdminIssuedProofV1 {
        val response = issue(bearer); checked(response, 200)
        assertTrue(response.body().size <= 1024)
        val value = json(response)
        assertEquals(setOf("token", "expiresAt", "scope"), value.fieldNames().asSequence().toSet())
        assertEquals(PROOF_SCOPE, value["scope"].textValue())
        val token = checkNotNull(value["token"].textValue())
        assertEquals(43, token.length)
        assertEquals(32, Base64.getUrlDecoder().decode(token).size)
        val rawId = checkNotNull(header(response, ISSUED_ID))
        val id = UUID.fromString(rawId)
        assertEquals(id.toString(), rawId); assertEquals(4, id.version()); assertEquals(2, id.variant())
        assertEquals(listOf(rawId), response.headers().allValues(ISSUED_ID))
        assertFalse(response.body().toString(Charsets.UTF_8).contains(rawId))
        val expires = Instant.parse(value["expiresAt"].textValue())
        assertEquals(listOf(true), observer.query(
            "SELECT user_id = ? AND token_hash = ? AND scope = ? AND used_at IS NULL " +
                "AND expires_at = ? AND expires_at = created_at + interval '300 seconds' AS valid FROM admin_step_up_grants WHERE id = ?",
            { row, _ -> row.getBoolean("valid") }, users[index].id, Sha256.hexUtf8(token), PROOF_SCOPE, Timestamp.from(expires), id))
        web.assertRequestsReleased()
        return RegisteredAdminIssuedProofV1(token, id, expires)
    }

    fun body(attempt: RegisteredAdminContentAttemptV1): ByteArray = mapper.writeValueAsBytes(mapOf(
        "type" to attempt.type, "subject" to attempt.subject, "body" to attempt.body))
    fun path(attempt: RegisteredAdminContentAttemptV1, selectedScope: ComplaintDataScope = scope): String =
        "$ADMIN/${attempt.id}/content?dataScopeId=${selectedScope.id}"
    fun edit(attempt: RegisteredAdminContentAttemptV1, proof: String? = null, bearer: String? = admin(),
        selectedScope: ComplaintDataScope = scope, etag: String? = attempt.etag): HttpResponse<ByteArray> =
        web.patchAdminContent(path(attempt, selectedScope), body(attempt), bearer, attempt.key, etag, proof)
    fun detail(id: UUID, bearer: String = admin()): HttpResponse<ByteArray> = web.get("$ADMIN/$id?dataScopeId=${scope.id}", bearer)

    fun contentRequest(attempt: RegisteredAdminContentAttemptV1, proof: String? = null, bearer: String = admin()): MockHttpServletRequest =
        MockHttpServletRequest("PATCH", "$ADMIN/${attempt.id}/content").apply {
            remoteAddr = "192.0.2.29"; queryString = "dataScopeId=${scope.id}"; contentType = "application/json"
            addHeader("Authorization", "Bearer $bearer"); addHeader("If-Match", attempt.etag)
            addHeader("X-Kira-Idempotency-Key", attempt.key.toString()); proof?.let { addHeader("X-Kira-Admin-Step-Up", it) }
            setContent(body(attempt))
        }
    fun stepUpRequest(bearer: String = admin()): MockHttpServletRequest = MockHttpServletRequest("POST", STEP_UP).apply {
        remoteAddr = "192.0.2.29"; contentType = "application/json"; addHeader("Authorization", "Bearer $bearer")
        setContent(mapper.writeValueAsBytes(mapOf("password" to PASSWORD, "scope" to PROOF_SCOPE)))
    }
    fun dispatch(request: HttpServletRequest, response: MockHttpServletResponse = MockHttpServletResponse()): MockHttpServletResponse =
        response.also { composition.ingressFilter.doFilter(request, it, noFallthrough) }

    fun rows(table: String): List<String> {
        require(table in TABLES)
        val condition = if (table == "admin_step_up_grants") "user_id IN ('${users[0].id}','${users[1].id}')" else "data_scope_id = '${scope.id}'"
        return observer.queryForList("SELECT encode(sha256(convert_to(jsonb_build_array(to_jsonb(t),t.xmin::text)::text,'UTF8')),'hex') " +
            "FROM $table t WHERE $condition ORDER BY to_jsonb(t)::text", String::class.java)
    }
    fun image(): Map<String, List<String>> = first.p.image() + TABLES.associateWith(::rows)
    fun grantRow(id: UUID): List<String> = observer.queryForList(
        "SELECT jsonb_build_array(to_jsonb(g),g.xmin::text)::text FROM admin_step_up_grants g WHERE id = ?", String::class.java, id)
    fun unused(id: UUID) = assertEquals(true, observer.queryForObject("SELECT used_at IS NULL FROM admin_step_up_grants WHERE id = ?", Boolean::class.java, id))
    fun used(id: UUID) = assertEquals(true, observer.queryForObject("SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE id = ?", Boolean::class.java, id))
    fun immutable(id: UUID): String = checkNotNull(observer.queryForObject(
        "SELECT (to_jsonb(c) - ARRAY['type','subject','body','updated_at','version'])::text FROM complaints c WHERE data_scope_id = ? AND id = ?",
        String::class.java, scope.id, id))
    fun providerCounts(): List<Int> = listOf(first.native.sts.requests.size, first.native.kms.requests.size, first.native.requests.size,
        ordinary.requestBudgets.size, raw.sts.requests.size, raw.kms.requests.size, raw.requests.size, first.p.f.http.read.requests.size)

    fun assertCharge(before: Map<String, CounterSnapshot>, charge: ComplaintCapacityVector, precharged: ComplaintCapacityVector = charge) {
        val after = first.counters(); assertEquals(before.keys, after.keys)
        for (counter in ComplaintCapacityCounter.entries) {
            val old = before.getValue(counter.storedName); val current = after.getValue(counter.storedName)
            if (precharged[counter] == 0L) assertEquals(old, current, counter.storedName) else {
                assertEquals(old.preserved, current.preserved, counter.storedName)
                assertEquals(old.free - charge[counter], current.free, counter.storedName)
                assertEquals(old.actual + charge[counter], current.actual, counter.storedName)
            }
        }
    }
    fun acknowledged(response: HttpResponse<ByteArray>, attempt: RegisteredAdminContentAttemptV1, grantId: UUID) {
        checked(response, 200)
        assertEquals(setOf("id", "version"), json(response).fieldNames().asSequence().toSet())
        assertEquals(attempt.id.toString(), json(response)["id"].textValue())
        assertTrue(json(response)["version"].isIntegralNumber); assertEquals(attempt.version + 1, json(response)["version"].longValue())
        assertEquals("\"complaint-${attempt.id}-v${attempt.version + 1}\"", header(response, "ETag")); association(response, grantId)
    }
    fun association(response: HttpResponse<ByteArray>, id: UUID?) {
        assertEquals(if (id == null) emptyList<String>() else listOf("true"), response.headers().allValues(CONSUMED))
        assertEquals(id?.let { listOf(it.toString()) } ?: emptyList<String>(), response.headers().allValues(CONSUMED_ID))
        if (id != null) assertFalse(response.body().toString(Charsets.UTF_8).contains(id.toString()))
    }
    fun problem(response: HttpResponse<ByteArray>, status: Int, code: String, consumed: UUID? = null) {
        checked(response, status); assertEquals(code, json(response)["errors"][0]["code"].textValue())
        assertEquals(status, json(response)["status"].intValue()); assertTrue(response.body().size <= 512)
        assertNull(header(response, ISSUED_ID))
        assertNull(header(response, "ETag")); association(response, consumed)
    }
    fun checked(response: HttpResponse<ByteArray>, status: Int) {
        assertEquals(status, response.statusCode()); assertEquals("1", header(response, "X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", header(response, "Cache-Control"))
        assertNull(header(response, "Set-Cookie")); assertNull(header(response, "Content-Encoding"))
        assertTrue(response.body().size in 1..2 * 1024 * 1024)
    }
    fun json(response: HttpResponse<ByteArray>): JsonNode = mapper.readTree(response.body())
    fun header(response: HttpResponse<ByteArray>, name: String): String? = response.headers().firstValue(name).orElse(null)

    companion object {
        const val PASSWORD = "synthetic-registered-admin-content-password"
        const val PROOF_SCOPE = "complaint-moderation-mutation"
        const val STEP_UP = "/api/v1/admin/step-up"
        const val ADMIN = "/api/v1/admin/complaints"
        const val ISSUED_ID = "X-Kira-Admin-Step-Up-Grant-Id"
        const val CONSUMED = "X-Kira-Admin-Step-Up-Consumed"
        const val CONSUMED_ID = "X-Kira-Admin-Step-Up-Consumed-Grant-Id"
        val PATHS = setOf(ComplaintInstallationRoutes.BOOTSTRAP, ComplaintInstallationRoutes.ENROLLMENT,
            ComplaintInstallationRoutes.SESSION, ComplaintInstallationRoutes.HISTORY, ComplaintInstallationRoutes.STATUS,
            "${ComplaintInstallationRoutes.HISTORY}/{id}", "$ADMIN/search", "$ADMIN/stats", "$ADMIN/{id}", "$ADMIN/{id}/content", STEP_UP)
        private val TABLES = listOf("app_installations", "complaint_installation_ids", "complaint_idempotency_receipts", "admin_step_up_grants",
            "complaint_test_active_seal_intents", "complaint_test_active_checkpoint_history", "complaint_test_active_recurrent_seal_intents")
        val mapper = ObjectMapper()
        private val noFallthrough = FilterChain { _, _ -> error("Selected Admin content/issuance escaped the original pre-buffer boundary.") }
    }
}

/** Only immutable declarations differ; true intake, full D, activation, registration and startup are reused. */
internal fun withRegisteredAdminContentHttpV1(tls: VersionBoundPersistenceConnectedFixture, perHour: Int = 60,
    adminStatus: TestRegisteredAdminStatusInputV1? = null, selectAdminStatus: Boolean = adminStatus != null,
    action: (TestRegisteredAdminContentHttpFixtureV1) -> Unit) {
    require(!selectAdminStatus || adminStatus != null)
    TestRegisteredHttpStartupCasesV1.withPrepared(tls, globalScanBeforeActivation = true,
        adminRead = TestRegisteredAdminReadInputV1(1, TestRegisteredAdminReadInputV1.PROFILE, 60),
        adminContent = TestRegisteredAdminContentInputV1(1, TestRegisteredAdminContentInputV1.PROFILE, perHour),
        adminStatus = adminStatus) { first, ordinary, raw ->
        val selected = if (selectAdminStatus) first.assembly.beginRegisteredAdminContentStatusHttpStartup(first.registration)
            else first.assembly.beginRegisteredAdminContentHttpStartup(first.registration)
        selected.use { startup ->
            startup.start()
            val paths = TestRegisteredAdminContentHttpFixtureV1.PATHS + if (selectAdminStatus) setOf(
                "/api/v1/admin/complaints/{id}/status", "/api/v1/admin/complaints/{id}/closure") else emptySet()
            StartedHttpView(first, startup, paths).use { web ->
                val users = mutableListOf<User>()
                try {
                    repeat(2) {
                        val id = UUID.randomUUID(); val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
                        val hash = web.context.getBean(PasswordEncoder::class.java).encode(TestRegisteredAdminContentHttpFixtureV1.PASSWORD)
                        val user = User(id, "registered-content-$id@example.invalid", hash, Role.ADMIN, true, now, now)
                        assertEquals(1, first.observer.update("INSERT INTO users (id,email,password_hash,role,enabled,created_at,updated_at) VALUES (?,?,?,?,true,?,?)",
                            id, user.email, hash, user.role.name, Timestamp.from(now), Timestamp.from(now)))
                        users += user
                    }
                    val keys = web.context.getBean(JwtKeyProvider::class.java)
                    assertSame(first.process.consumers.jwt.boundUserKeyProvider, keys)
                    val signer = JwtService(keys, web.context.getBean(KiraSecurityProperties::class.java), Clock.systemUTC())
                    action(TestRegisteredAdminContentHttpFixtureV1(first, ordinary, raw, web, users, signer))
                    web.assertRequestsReleased()
                } finally {
                    web.assertRequestsReleased()
                    // Existing disposable TEST teardown only: no fabricated grant, ACTIVE row, current proof or product refund.
                    first.observer.update("DELETE FROM complaints WHERE data_scope_id = ?", first.scope)
                    first.observer.update("DELETE FROM complaint_resource_ids WHERE data_scope_id = ?", first.scope)
                    first.observer.update("DELETE FROM complaint_idempotency_receipts WHERE data_scope_id = ?", first.scope)
                    first.observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action IN ('COMPLAINT_CREATED','COMPLAINT_CONTENT_EDITED')", first.scope)
                    for (user in users) {
                        first.observer.update("DELETE FROM admin_step_up_grants WHERE user_id = ?", user.id)
                        first.observer.update("DELETE FROM audit_log WHERE actor_user_id = ?", user.id)
                        assertEquals(1, first.observer.update("DELETE FROM users WHERE id = ?", user.id))
                    }
                }
                startup.close(); web.assertDisposed(nativeStillActive = true)
            }
            startup.requireCleanupProven()
        }
    }
}
