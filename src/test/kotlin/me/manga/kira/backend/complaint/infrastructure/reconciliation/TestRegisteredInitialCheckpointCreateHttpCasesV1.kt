package me.manga.kira.backend.complaint.infrastructure.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.ServletInputStream
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJpaTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintReportFingerprint
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.security.ComplaintBootstrapSpringTestFixture
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.ComplaintSecurityRejected
import me.manga.kira.backend.support.JwtTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Real registered producers and actual optional Spring mount. No current-row, checkpoint, handler or admission stand-in. */
internal object TestRegisteredInitialCheckpointCreateHttpCasesV1 {
    fun identityCreateAndReceipts(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val providers = f.providerCounts()
        val composition = composition(f)
        assertTrue(f.jdbc.calls.isEmpty(), "Constructing a route is not a current eligibility read.")
        val installation = f.exchange.f.candidate().installation
        try {
            ComplaintBootstrapSpringTestFixture(composition).use { web ->
                mapping(web, composition, SUBSET)
                val bootstrap = web.mvc.perform(get(ComplaintInstallationRoutes.BOOTSTRAP).with { it.remoteAddr = ADDRESS; it }
                    .header("Authorization", "malformed", "Bearer duplicate")).andReturn().response
                checked(bootstrap, 200)
                assertEquals("""{"dataScopeId":"${f.scope}","contractVersion":1}""", bootstrap.contentAsString)
                assertNull(bootstrap.getHeader("Location")); assertNull(bootstrap.getHeader("ETag"))
                f.assertReleased()

                val enrollment = enrollmentInOriginalIngress(f, composition, web, identityBody(installation, enrollment = true))
                checked(enrollment, 201)
                assertEquals("/api/v1/installations/me", enrollment.getHeader("Location")) // Existing wire contract, not a newly enabled me route.
                val enrolledToken = token(enrollment, installation, f)
                assertTrue(enrolledToken.isNotEmpty())
                val session = send(web, ComplaintInstallationRoutes.SESSION, identityBody(installation), "malformed")
                checked(session, 200)
                val bearer = token(session, installation, f)
                f.assertReleased()

                val attempt = f.attempt()
                val before = f.counters(); f.jdbc.calls.clear()
                val created = send(web, ComplaintInstallationRoutes.HISTORY, createBody(attempt), bearer, attempt.input.key)
                checked(created, 201)
                assertEquals("""{"id":"${attempt.input.id}","version":1}""", created.contentAsString)
                assertEquals("/api/v1/complaints/${attempt.input.id}", created.getHeader("Location"))
                assertTrue(checkNotNull(created.getHeader("ETag")).isNotEmpty())
                f.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE); f.assertReleased()
                assertEquals(5, f.createSql().count { it == TestActiveInitialCheckpointSqlV1.currentForOwnerCreate })
                assertEquals(1, f.createPhases().size)
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, f.createPhases().single().databaseOutcome())
                assertEquals(installation.id, f.observer.queryForObject("SELECT owner_id FROM complaints WHERE id = ?", UUID::class.java, attempt.input.id))
                // One HTTP CREATE plus the two unchanged SYSTEM/NOTICE activation creation audits.
                assertEquals(3L, f.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CREATED'", Long::class.java, f.scope))
                assertTrue(f.jdbc.calls.any { it.first === PersistencePhasePath.COMPLAINT_OWNER_HISTORY_AUTHENTICATION })
                assertFalse(f.jdbc.calls.any { it.first === PersistencePhasePath.COMPLAINT_OWNER_HISTORY_PAGE })

                assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", f.scope))
                val closed = f.state(); f.jdbc.calls.clear()
                checked(send(web, ComplaintInstallationRoutes.HISTORY, createBody(attempt), bearer, attempt.input.key), 201)
                val status = send(web, ComplaintInstallationRoutes.STATUS, statusBody(attempt), bearer)
                checked(status, 200)
                val receipt = mapper.readTree(status.contentAsByteArray)
                assertEquals("APPLIED", receipt["outcome"].textValue()); assertEquals(201, receipt["originalStatus"].intValue())
                assertEquals(attempt.input.id.toString(), receipt["body"]["id"].textValue())
                assertEquals(created.getHeader("ETag"), receipt["etag"].textValue())
                assertNull(status.getHeader("Location")); assertNull(status.getHeader("ETag"))
                assertTrue(f.createSql().isEmpty()); assertEquals(closed, f.state()); f.assertReleased()

                // Early bearer AUTH alone is not authority: the actual registered producer must reject current D drift.
                val original = f.observer.queryForObject("SELECT desired_configuration_hash FROM complaint_journal_control WHERE data_scope_id = ?", ByteArray::class.java, f.scope)
                assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?", ByteArray(32) { 0x68 }, f.scope))
                val damaged = f.state(); f.jdbc.calls.clear()
                checked(send(web, ComplaintInstallationRoutes.HISTORY, createBody(attempt), bearer, attempt.input.key), 503)
                checked(send(web, ComplaintInstallationRoutes.STATUS, statusBody(attempt), bearer), 503)
                assertTrue(f.createSql().isEmpty()); assertEquals(damaged, f.state()); f.assertReleased()
                assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?", original, f.scope))
                checked(send(web, ComplaintInstallationRoutes.STATUS, statusBody(attempt), bearer), 200)
                assertEquals(1, f.observer.update("UPDATE app_installations SET credential_version = credential_version + 1 WHERE id = ?", installation.id))
                checked(send(web, ComplaintInstallationRoutes.HISTORY, createBody(attempt), bearer, attempt.input.key), 401)
                checked(send(web, ComplaintInstallationRoutes.STATUS, statusBody(attempt), bearer), 401)
                verifyNoInteractions(web.users); assertEquals(providers, f.providerCounts()); f.assertReleased()
            }
        } finally {
            // Extra HTTP-created actor belongs to this disposable fixture; no product erasure/refund is claimed.
            f.observer.update("DELETE FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND actor_id = ?", f.scope, installation.id)
        }
    }

    fun exactSubsetAndResources(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val before = f.state(); val providers = f.providerCounts()
        assertThrows<Exception> { composition(f, jdbc = JdbcTemplate(f.process.pools.ordinary)) }
        // One manager binds one owner: a foreign owner needs its own real manager, not a rebind.
        val otherOwner = PersistencePhaseOwnership(f.exchange.ordinary.admission,
            GuardedJpaTransactionManager(f.exchange.ordinary.entityManagerFactory, f.exchange.ordinary.pool))
        assertThrows<Exception> { composition(f, ownership = otherOwner) }
        ComplaintTestProcessAssemblyV1.begin().use { unassembled -> assertThrows<Exception> { composition(f, assembly = unassembled) } }
        assertTrue(f.jdbc.calls.isEmpty()); assertEquals(before, f.state())
        val composition = composition(f)
        val bare = MockHttpServletRequest("POST", ComplaintInstallationRoutes.HISTORY)
        assertThrows<ComplaintSecurityRejected> { composition.handler.handleRequest(bare, MockHttpServletResponse()) }
        ComplaintBootstrapSpringTestFixture(composition).use { web ->
            mapping(web, composition, SUBSET)
            val id = UUID.randomUUID()
            for ((method, path) in listOf(
                "POST" to ComplaintInstallationRoutes.BOOTSTRAP, "HEAD" to ComplaintInstallationRoutes.BOOTSTRAP,
                "GET" to ComplaintInstallationRoutes.ENROLLMENT, "GET" to ComplaintInstallationRoutes.SESSION,
                "GET" to ComplaintInstallationRoutes.HISTORY, "GET" to ComplaintInstallationRoutes.STATUS,
                "OPTIONS" to ComplaintInstallationRoutes.HISTORY, "HEAD" to ComplaintInstallationRoutes.STATUS,
                "GET" to ComplaintInstallationRoutes.ME, "POST" to ComplaintInstallationRoutes.DELETE_ALL,
                "GET" to "/api/v1/complaints/$id", "POST" to "/api/v1/complaints/$id/replies",
                "PATCH" to "/api/v1/complaints/$id/content", "DELETE" to "/api/v1/complaints/$id",
                "POST" to "/api/v1/admin/complaints/search", "DELETE" to "/api/v1/admin/complaints/$id",
                "POST" to "/api/v1/installations/", "POST" to "/api/v1/installations/session/",
                "POST" to "/api/v1/complaints/", "POST" to "/api/v1/complaint-operations/status/",
                "GET" to "/api/v1/installations/bootstrap/", "POST" to "/api/v1/complaint-operations/%73tatus",
                "POST" to "/api/v1/%63omplaints", "GET" to "/api/v1/installations/%62ootstrap",
            )) {
                val response = web.mvc.perform { servlet ->
                    object : MockHttpServletRequest(servlet, method, path) {
                        override fun getInputStream(): ServletInputStream = error("Unimplemented route buffered input")
                    }.apply {
                        servletPath = path; remoteAddr = ADDRESS
                        addHeader("Authorization", "malformed"); addHeader("Content-Length", Long.MAX_VALUE.toString())
                    }
                }.andReturn().response
                checked(response, 404)
                assertNull(response.getHeader("WWW-Authenticate"))
                if (method == "HEAD") assertTrue(response.contentAsByteArray.isEmpty())
            }
            assertTrue(f.jdbc.calls.isEmpty())
            for (path in SUBSET - ComplaintInstallationRoutes.BOOTSTRAP) {
                val response = web.mvc.perform { servlet ->
                    object : MockHttpServletRequest(servlet, "POST", path) {
                        override fun getInputStream(): ServletInputStream = error("Oversized request acquired its body")
                    }.apply {
                        servletPath = path; remoteAddr = ADDRESS; contentType = "application/json"
                        addHeader("Content-Length", Long.MAX_VALUE.toString()); addHeader("Authorization", "Bearer ${f.token}")
                        if (path == ComplaintInstallationRoutes.HISTORY) addHeader("X-Kira-Idempotency-Key", UUID.randomUUID().toString())
                    }
                }.andReturn().response
                checked(response, 413)
            }
            assertTrue(f.jdbc.calls.isEmpty(), "Framing/body caps precede even the early bearer current-row read.")
            val attempt = f.attempt()
            val foreignScope = ScopedInstallationId(f.actor.id, ComplaintDataScope.of(UUID.randomUUID()))
            for (bearer in listOf(null, "malformed", JwtTestSupport.mint(f.actor.id), JwtTestSupport.tamperSignature(f.token),
                f.jwt.issue(foreignScope, 1, Instant.now()).value)) {
                checked(send(web, ComplaintInstallationRoutes.HISTORY, createBody(attempt), bearer, attempt.input.key), 401)
            }
            assertTrue(f.jdbc.calls.isEmpty()); verifyNoInteractions(web.users)
            val user = UUID.randomUUID()
            assertEquals(401, web.mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer ${JwtTestSupport.mint(user)}")).andReturn().response.status)
            verify(web.users).findById(user) // Unrelated account route remains under the existing @Order(2) chain.
        }
        assertEquals(before, f.state()); assertEquals(providers, f.providerCounts()); f.assertReleased()
    }

    fun missingCheckpointAndBootstrapOnly(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val before = f.state(); val providers = f.providerCounts()
        val bootstrapOnly = ComplaintTestBootstrapHttpCompositionV1.fromRegistered(f.registration, f.exchange.ordinary.ownership, f.jdbc)
        ComplaintBootstrapSpringTestFixture(bootstrapOnly).use { web ->
            mapping(web, bootstrapOnly, setOf(ComplaintInstallationRoutes.BOOTSTRAP))
            for (path in SUBSET - ComplaintInstallationRoutes.BOOTSTRAP) {
                checked(send(web, path, "{}".toByteArray(), "malformed"), 404)
            }
            assertTrue(f.jdbc.calls.isEmpty(), "A born-with CREATE policy never implicitly expands the bootstrap-only mount.")
        }
        ComplaintBootstrapSpringTestFixture(composition(f)).use { web ->
            checked(web.mvc.perform(get(ComplaintInstallationRoutes.BOOTSTRAP).with { it.remoteAddr = ADDRESS; it }).andReturn().response, 200)
            val attempt = f.attempt(); f.jdbc.calls.clear()
            checked(send(web, ComplaintInstallationRoutes.HISTORY, createBody(attempt), f.token, attempt.input.key), 503)
            f.assertNoCounterSql(); f.assertReleased()
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, f.createPhases().single().databaseOutcome())
            checked(send(web, ComplaintInstallationRoutes.STATUS, statusBody(attempt), f.token), 404)
            assertEquals(before, f.state()); assertEquals(providers, f.providerCounts())
            verifyNoInteractions(web.users)
        }
    }

    private fun enrollmentInOriginalIngress(f: TestRegisteredInitialCheckpointCreateFixtureV1,
        composition: ComplaintTestBootstrapHttpCompositionV1, web: ComplaintBootstrapSpringTestFixture, body: ByteArray): MockHttpServletResponse {
        val bridge = poolTestField<ComplaintHttpIngressBridge>(composition, "bridge")
        var context: ComplaintIngressContext? = null
        var bodyReads = 0; var sqlReads = 0
        lateinit var request: MockHttpServletRequest
        f.jdbc.calls.clear()
        f.jdbc.before = { _, _ ->
            sqlReads += 1
            ComplaintIngressAdmission.requireOwnerOperationReadOwner(checkNotNull(context), f.ingress) // Pure comparison, not another admission.
        }
        val response = try {
            web.mvc.perform { servlet ->
                object : MockHttpServletRequest(servlet, "POST", ComplaintInstallationRoutes.ENROLLMENT) {
                    override fun getInputStream(): ServletInputStream {
                        requireConnectionFree(); assertTrue(f.jdbc.calls.isEmpty())
                        val actual = bridge.authenticationContext(this)
                        context?.let { assertSame(it, actual) } ?: run { context = actual }
                        f.ingress.requireLiveContext(actual); bodyReads += 1
                        return super.getInputStream()
                    }
                }.apply {
                    request = this; servletPath = ComplaintInstallationRoutes.ENROLLMENT; remoteAddr = ADDRESS
                    contentType = "application/json"; setContent(body); addHeader("Authorization", "malformed")
                }
            }.andReturn().response
        } finally { f.jdbc.before = { _, _ -> } }
        assertEquals(1, bodyReads); assertTrue(sqlReads > 0)
        assertThrows<ComplaintSecurityRejected> { bridge.authenticationContext(request) }
        f.assertReleased()
        return response
    }

    private fun composition(f: TestRegisteredInitialCheckpointCreateFixtureV1,
        ownership: PersistencePhaseOwnership = f.exchange.ordinary.ownership, jdbc: JdbcTemplate = f.jdbc,
        assembly: ComplaintTestProcessAssemblyV1 = f.checkpoint.assembly) =
        ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointCreate(f.registration, assembly, ownership, jdbc, f.exchange.service)

    private fun mapping(web: ComplaintBootstrapSpringTestFixture, composition: ComplaintTestBootstrapHttpCompositionV1, paths: Set<String>) {
        assertEquals(2, web.context.getBeansOfType(SecurityFilterChain::class.java).size)
        val mapping = web.context.getBean("complaintTestBootstrapHandlerMapping", SimpleUrlHandlerMapping::class.java)
        assertEquals(paths, mapping.urlMap.keys)
        mapping.urlMap.values.forEach { assertSame(composition.handler, it) }
    }

    private fun send(web: ComplaintBootstrapSpringTestFixture, path: String, body: ByteArray, bearer: String? = null, key: UUID? = null): MockHttpServletResponse {
        val request = post(path).with { it.remoteAddr = ADDRESS; it }.contentType("application/json").content(body)
        bearer?.let { request.header("Authorization", "Bearer $it") }
        key?.let { request.header("X-Kira-Idempotency-Key", it.toString()) }
        return web.mvc.perform(request).andReturn().response
    }

    private fun token(response: MockHttpServletResponse, installation: ScopedInstallationId, f: TestRegisteredInitialCheckpointCreateFixtureV1): String {
        val parsed = mapper.readTree(response.contentAsByteArray)
        assertEquals(installation.id.toString(), parsed["installationId"].textValue())
        assertEquals(installation.scope.id.toString(), parsed["dataScopeId"].textValue())
        assertEquals(1L, parsed["credentialVersion"].longValue())
        return parsed["accessToken"].textValue().also { assertEquals(installation, f.jwt.verify(it).installation) }
    }

    private fun identityBody(installation: ScopedInstallationId, enrollment: Boolean = false): ByteArray = mapper.writeValueAsBytes(
        linkedMapOf("installationId" to installation.id.toString(), "expectedDataScopeId" to installation.scope.id.toString(),
            "secret" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }))
            .also { if (enrollment) it["platform"] = "ANDROID" },
    )

    private fun createBody(attempt: RegisteredInitialCreateAttemptV1): ByteArray = mapper.writeValueAsBytes(mapOf(
        "id" to attempt.input.id.toString(), "type" to attempt.input.type.name, "subject" to attempt.input.subject, "body" to attempt.input.body,
        "metadata" to mapOf("appVersion" to null, "osVersion" to "fixture-os", "manufacturer" to "", "deviceModel" to ""),
    ))

    private fun statusBody(attempt: RegisteredInitialCreateAttemptV1): ByteArray = mapper.writeValueAsBytes(mapOf(
        "operation" to "OWNER_CREATE", "key" to attempt.input.key.toString(), "targetIds" to listOf(attempt.input.id.toString()),
        "fingerprint" to ComplaintReportFingerprint.of(attempt.candidate.request).encoded,
    ))

    private fun checked(response: MockHttpServletResponse, expected: Int) {
        assertEquals(expected, response.status)
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertTrue(response.contentLength in 1..16 * 1024)
        assertNull(response.getHeader("Set-Cookie")); assertNull(response.getHeader("Content-Encoding"))
        if (expected != 401) assertNull(response.getHeader("WWW-Authenticate"))
    }

    private val mapper = ObjectMapper()
    private const val ADDRESS = "192.0.2.43"
    private val SUBSET = setOf(ComplaintInstallationRoutes.BOOTSTRAP, ComplaintInstallationRoutes.ENROLLMENT, ComplaintInstallationRoutes.SESSION,
        ComplaintInstallationRoutes.HISTORY, ComplaintInstallationRoutes.STATUS)
}
