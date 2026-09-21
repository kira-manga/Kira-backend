package me.manga.kira.backend.complaint.infrastructure.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletInputStream
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.OrdinaryPersistenceAdmission
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.api.ComplaintInstallationMeHttpHandler
import me.manga.kira.backend.complaint.application.ComplaintInstallationMeService
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutFixtureV1
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.catalog.withTestActiveFirstCut
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeReadPort
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeRejected
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.config.ComplaintTestRegisteredHttpStartupV1
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Genuine global prepare/PROJECT/registration/identity RELEASE and product-owned JPA/loopback.
 * No ACTIVE seed, copied identity result, checkpoint stand-in or substitute cleanup. Runtime is
 * deferred until the shared genuine prerequisite permits it; lower me parser tests are not repeated.
 */
internal object TestRegisteredInstallationMeHttpCasesV1 {
    fun currentIdentityAndMaintenance(tls: VersionBoundPersistenceConnectedFixture) = withPrepared(tls) { first, ordinary, raw ->
        val providers = providerCounts(first, ordinary, raw)
        val startup = first.assembly.beginRegisteredMeHttpStartup(first.registration)
        startup.use {
            assertSame(startup, poolTestField<ComplaintTestRegisteredHttpStartupV1>(first.assembly, "httpStartup"))
            startup.start()
            MeHttpView(first, startup).use { web ->
                val actor = first.initial.candidate().installation // Existing exact fixture cleanup owns the new HTTP-enrolled actor.
                val bearer = web.enroll(actor)
                val counters = first.counters()
                val content = first.outsideCut()
                assertProjection(web.get(ME, bearer), actor)
                web.assertReleased()
                assertEquals(counters, first.counters()); assertEquals(content, first.outsideCut())

                val control = first.observer.queryForMap(
                    "SELECT maintenance_closed, creation_closed FROM complaint_journal_control WHERE data_scope_id = ?", first.scope)
                assertEquals(1, first.observer.update(
                    "UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", first.scope))
                try {
                    // Identity has no prose or checkpoint dependency. Neither a maintenance gate nor a read is mutation authority.
                    assertProjection(web.get(ME, bearer), actor)
                    assertEquals(1, first.observer.update(
                        "UPDATE app_installations SET credential_version = credential_version + 1 WHERE id = ? AND data_scope_id = ?", actor.id, first.scope))
                    try {
                        checked(web.get(ME, bearer), 401)
                    } finally {
                        assertEquals(1, first.observer.update(
                            "UPDATE app_installations SET credential_version = 1 WHERE id = ? AND data_scope_id = ?", actor.id, first.scope))
                    }
                    assertProjection(web.get(ME, bearer), actor)
                    web.assertReleased()
                    assertEquals(counters, first.counters())
                } finally {
                    assertEquals(1, first.observer.update(
                        "UPDATE complaint_journal_control SET maintenance_closed = ?, creation_closed = ? WHERE data_scope_id = ?",
                        control.getValue("maintenance_closed"), control.getValue("creation_closed"), first.scope))
                }
                assertEquals(providers, providerCounts(first, ordinary, raw))
            }
        }
        startup.requireCleanupProven()
        assertTrue(first.process.pools.ordinary.businessReady(), "The retained startup must not close its borrowed native pool.")
    }

    fun exactSubsetResourcesAndLifetime(tls: VersionBoundPersistenceConnectedFixture) = withPrepared(tls,
        TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.EDIT_PROFILE)) { first, ordinary, raw ->
        val providers = providerCounts(first, ordinary, raw)
        val startup = first.assembly.beginRegisteredMeHttpStartup(first.registration)
        startup.use {
            startup.start()
            MeHttpView(first, startup).use { web ->
                val r = first.registration; val a = first.assembly; val o = web.ownership; val j = web.jdbc; val audit = web.audit
                val before = first.counters()
                // Even the richer birth profile does not expand any earlier selector or the new read/CREATE/me sibling.
                val old = listOf(
                    ComplaintTestBootstrapHttpCompositionV1.fromRegistered(r, o, j),
                    ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointCreate(r, a, o, j, audit),
                    ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreate(r, a, o, j, audit),
                    ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateReply(r, a, o, j, audit),
                    ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateReplyEdit(r, a, o, j, audit),
                )
                old.forEach { narrow ->
                    assertFalse(ME in narrow.mappedPaths)
                    closedBeforeBody(narrow, "GET", ME)
                }
                val id = UUID.randomUUID()
                for ((method, path) in listOf(
                    "POST" to ME, "HEAD" to ME, "OPTIONS" to ME, "GET" to "$ME/",
                    "POST" to ComplaintInstallationRoutes.DELETE_ALL,
                    "DELETE" to "${ComplaintInstallationRoutes.HISTORY}/$id",
                    "POST" to "${ComplaintInstallationRoutes.HISTORY}/$id/replies",
                    "PATCH" to "${ComplaintInstallationRoutes.HISTORY}/$id/content",
                )) closedBeforeBody(web.composition, method, path)
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                    ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateMe(r, a, o, JdbcTemplate(checkNotNull(j.dataSource)), audit)
                }
                ComplaintTestProcessAssemblyV1.begin().use { foreign ->
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                        ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateMe(r, foreign, o, j, audit)
                    }
                }
                r.requireIdentityAdmissionPhaseResources(o, j)
                assertEquals(before, first.counters())

                val actor = first.initial.candidate().installation
                val bearer = web.enroll(actor)
                assertProjection(web.get(ME, bearer), actor)
                web.assertReleased()
                val afterEnrollment = first.counters()
                // Observe the actual composed port, not a fabricated authentication. Its first owned AUTH
                // commits/releases; closing the original registration must invalidate that retained handoff.
                val handler = poolTestField<ComplaintInstallationMeHttpHandler>(web.composition, "me")
                val service = poolTestField<ComplaintInstallationMeService>(handler, "service")
                val reader = poolTestField<ComplaintInstallationMeReadPort>(service, "reader")
                val request = MockHttpServletRequest("GET", ME).apply { remoteAddr = "127.0.0.1" }
                first.process.consumers.ingressAdmission.withIngress(request) { context ->
                    val authentication = reader.authenticate(context, bearer)
                    r.close()
                    assertEquals(ComplaintInstallationMeFailure.UNAVAILABLE, assertThrows<ComplaintInstallationMeRejected> {
                        reader.read(context, authentication)
                    }.failure)
                }
                checked(web.get(ME, bearer), 503)
                web.assertReleased()
                assertEquals(afterEnrollment, first.counters())
                assertEquals(providers, providerCounts(first, ordinary, raw))
            }
        }
        startup.requireCleanupProven()
    }

    private fun withPrepared(tls: VersionBoundPersistenceConnectedFixture,
        input: TestInitialCheckpointCreateInputV1 = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.PROFILE),
        action: (TestActiveFirstCutFixtureV1, TestActiveOrdinaryRawFixtureV1, TestActiveInitialCheckpointRawFixtureV1) -> Unit) {
        val ordinary = TestActiveOrdinaryRawFixtureV1()
        val raw = TestActiveInitialCheckpointRawFixtureV1()
        val factories = ordinary.factories.let { TestActiveOrdinaryRawHttpV1(it.sts, it.kms, it.s3, raw.input, initialCheckpointCreate = input) }
        withTestActiveFirstCut(tls, ordinaryRawHttp = factories, globalScanBeforeActivation = true) { first ->
            action(first, ordinary, raw) // Never select the shortened/global-skipping setup to evade the shared prerequisite.
        }
    }

    private fun closedBeforeBody(composition: ComplaintTestBootstrapHttpCompositionV1, method: String, path: String) {
        val request = object : MockHttpServletRequest(method, path) {
            override fun getInputStream(): ServletInputStream = error("Excluded route read input.")
        }
        val response = MockHttpServletResponse()
        composition.ingressFilter.doFilter(request, response, FilterChain { _, _ -> error("Excluded route fell through.") })
        assertEquals(404, response.status)
    }

    /** Only the real TCP client is owned here; all other fields observe the retained startup's exact instances. */
    private class MeHttpView(val first: TestActiveFirstCutFixtureV1, startup: ComplaintTestRegisteredHttpStartupV1) : AutoCloseable {
        private val context = poolTestField<AnnotationConfigServletWebServerApplicationContext>(startup, "context")
        val composition = context.getBean(ComplaintTestBootstrapHttpCompositionV1::class.java)
        val ownership = context.getBean(PersistencePhaseOwnership::class.java)
        val jdbc = context.getBean(JdbcTemplate::class.java)
        val audit = context.getBean(AuditService::class.java)
        private val admission = context.getBean(OrdinaryPersistenceAdmission::class.java)
        private val ingress = first.process.consumers.ingressAdmission
        private val port = startup.localPort
        private val client: HttpClient

        init {
            assertSame(first.process.pools.ordinary, jdbc.dataSource)
            assertSame(ownership, poolTestField<PersistencePhaseOwnership>(startup, "ownership"))
            assertSame(jdbc, poolTestField<JdbcTemplate>(startup, "jdbc"))
            first.registration.requireIdentityAdmissionPhaseResources(ownership, jdbc)
            val mapping = context.getBean("complaintTestBootstrapHandlerMapping", SimpleUrlHandlerMapping::class.java)
            assertEquals(SUBSET, mapping.urlMap.keys)
            mapping.urlMap.values.forEach { assertSame(composition.handler, it) }
            client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).proxy(direct).connectTimeout(Duration.ofSeconds(2)).build()
        }

        fun enroll(actor: ScopedInstallationId): String {
            val body = mapper.writeValueAsBytes(mapOf("installationId" to actor.id.toString(),
                "expectedDataScopeId" to actor.scope.id.toString(), "platform" to "ANDROID",
                "secret" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })))
            val response = send(HttpRequest.newBuilder(uri(ComplaintInstallationRoutes.ENROLLMENT))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)))
            checked(response, 201); assertEquals(ME, header(response, "Location"))
            val parsed = mapper.readTree(response.body())
            assertEquals(actor.id.toString(), parsed["installationId"].textValue())
            assertEquals(actor.scope.id.toString(), parsed["dataScopeId"].textValue())
            assertEquals(1L, parsed["credentialVersion"].longValue())
            assertReleased()
            return checkNotNull(parsed["accessToken"].textValue())
        }

        fun get(path: String, bearer: String): HttpResponse<ByteArray> =
            send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer $bearer").GET())
        private fun uri(path: String) = URI.create("http://127.0.0.1:$port$path")
        private fun send(builder: HttpRequest.Builder) =
            client.send(builder.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofByteArray())

        fun assertReleased() {
            awaitLifecycleFact(5_000) {
                synchronized(poolTestField<Any>(ingress, "lock")) {
                    poolTestField<Int>(ingress, "reservations") == 0 && poolTestField<Map<*, *>>(ingress, "contexts").isEmpty()
                } && admission.activeOwners() == 0
            }
            requireConnectionFree(); assertNull(PersistencePhaseOwnership.current())
        }

        override fun close() = client.close()
    }

    private fun assertProjection(response: HttpResponse<ByteArray>, actor: ScopedInstallationId) {
        checked(response, 200)
        assertEquals("""{"installationId":"${actor.id}","credentialVersion":1,"dataScopeId":"${actor.scope.id}"}""", response.body().decodeToString())
        assertNull(header(response, "ETag")); assertNull(header(response, "Location"))
    }
    private fun providerCounts(first: TestActiveFirstCutFixtureV1, ordinary: TestActiveOrdinaryRawFixtureV1, raw: TestActiveInitialCheckpointRawFixtureV1) =
        listOf(first.native.sts.requests.size, first.native.kms.requests.size, first.native.requests.size, ordinary.requestBudgets.size,
            raw.sts.requests.size, raw.kms.requests.size, raw.requests.size, first.p.f.http.read.requests.size)
    private fun header(response: HttpResponse<ByteArray>, name: String): String? = response.headers().firstValue(name).orElse(null)
    private fun checked(response: HttpResponse<ByteArray>, expected: Int) {
        assertEquals(expected, response.statusCode())
        assertEquals("1", header(response, "X-Kira-Complaint-Contract")); assertEquals("no-store, no-transform", header(response, "Cache-Control"))
        assertNull(header(response, "Set-Cookie")); assertTrue(response.body().size in 1..16 * 1024)
    }
    private const val ME = ComplaintInstallationRoutes.ME
    private val SUBSET = setOf(ComplaintInstallationRoutes.BOOTSTRAP, ComplaintInstallationRoutes.ENROLLMENT, ComplaintInstallationRoutes.SESSION,
        ComplaintInstallationRoutes.HISTORY, ComplaintInstallationRoutes.STATUS, "${ComplaintInstallationRoutes.HISTORY}/{id}", ME)
    private val mapper = ObjectMapper()
    private val direct = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }
}
