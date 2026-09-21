package me.manga.kira.backend.complaint.infrastructure.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManagerFactory
import me.manga.kira.backend.common.infrastructure.persistence.CounterSnapshot
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJpaTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.OrdinaryPersistenceAdmission
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutFixtureV1
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.catalog.withTestActiveFirstCut
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreateInput
import me.manga.kira.backend.complaint.domain.ComplaintReportFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintReportIdentity
import me.manga.kira.backend.complaint.domain.ComplaintReportMetadataInput
import me.manga.kira.backend.complaint.domain.ComplaintReportRequest
import me.manga.kira.backend.complaint.domain.ComplaintReportRequestResult
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateCandidate
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentFailureV1
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.config.ComplaintTestRegisteredHttpStartupV1
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.JwtKeyProvider
import org.apache.catalina.LifecycleState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.EntityManagerFactoryInfo
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * SOURCE ONLY / NOT_COMPILED / NOT_RUN. Actual loopback TCP, the product-created JPA graph and the
 * existing genuine PG/TLS -> registration -> identity-release -> native-seal -> checkpoint chain.
 * No MockMvc, prebound ordinary fixture, fake server/EMF/audit, result or cleanup substitution.
 */
internal object TestRegisteredHttpStartupCasesV1 {
    fun identityCreateAndReceipts(tls: VersionBoundPersistenceConnectedFixture) = withPrepared(tls) { first, ordinary, raw ->
        val providers = providerCounts(first, ordinary, raw)
        first.assembly.beginRegisteredHttpStartup(first.registration).use { startup ->
            assertSame(startup, poolTestField<ComplaintTestRegisteredHttpStartupV1>(first.assembly, "httpStartup"))
            assertRefused { first.assembly.beginRegisteredHttpStartup(first.registration) }
            OwnedCallerTestScope().use { callers -> callers.launch {
                assertRefused { startup.start() }
                assertRefused { startup.close() }
                assertRefused { startup.localPort }
                assertRefused { first.assembly.beginRegisteredHttpStartup(first.registration) }
            }.value() }
            assertNull(poolTestField<Any?>(startup, "factory"), "A foreign caller must not initialize original JPA.")
            assertEquals(providers, providerCounts(first, ordinary, raw))
            startup.start()
            assertRefused { startup.start() }
            StartedHttpView(first, startup).use { web ->
                val bootstrap = web.get(ComplaintInstallationRoutes.BOOTSTRAP)
                checked(bootstrap, 200)
                assertEquals("""{"dataScopeId":"${first.scope}","contractVersion":1}""", bootstrap.body().decodeToString())
                for (path in listOf("/api/v1/auth/me", ComplaintInstallationRoutes.HISTORY, "${ComplaintInstallationRoutes.BOOTSTRAP}/")) {
                    checked(web.get(path), 404) // Standalone TEST context does not host the full account/source/Admin app.
                }
                val actor = first.initial.candidate().installation // Register identity with the existing fixture's exact cleanup.
                try {
                    val beforeEnrollment = first.p.counters()
                    val enrolled = web.post(ComplaintInstallationRoutes.ENROLLMENT, identityBody(actor, enrollment = true))
                    checked(enrolled, 201)
                    token(enrolled, actor, first)
                    val share = ComplaintCapacityCharges.INSTALLATION_ID + ComplaintCapacityCharges.INSTALLATION_CREDENTIAL
                    val afterEnrollment = first.p.counters()
                    for (counter in ComplaintCapacityCounter.entries) {
                        val old = beforeEnrollment.getValue(counter.storedName); val current = afterEnrollment.getValue(counter.storedName)
                        assertEquals(old.free - ComplaintCapacityCharges.AUDIT[counter], current.free)
                        assertEquals(old.actual + share[counter] + ComplaintCapacityCharges.AUDIT[counter], current.actual)
                        assertEquals(old.reserved - share[counter], current.reserved)
                        assertEquals(old.recovery, current.recovery)
                    }
                    assertEquals(1L, first.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? " +
                        "AND action = 'COMPLAINT_INSTALLATION_ENROLLED' AND complaint_actor_kind = 'INSTALLATION'", Long::class.java, first.scope))
                    assertEquals(1L, first.observer.queryForObject("SELECT enrolled_count FROM complaint_test_runs WHERE data_scope_id = ?", Long::class.java, first.scope))
                    val session = web.post(ComplaintInstallationRoutes.SESSION, identityBody(actor))
                    checked(session, 200)
                    val bearer = token(session, actor, first)
                    assertEquals(afterEnrollment, first.p.counters())
                    val attempt = attempt(actor)
                    val missing = first.counters()
                    checked(web.post(ComplaintInstallationRoutes.HISTORY, createBody(attempt), bearer, attempt.input.key), 503)
                    assertEquals(missing, first.counters(), "Registration/identity release cannot replace the current checkpoint.")
                    web.assertRequestsReleased()
                    assertEquals(providers, providerCounts(first, ordinary, raw), "HTTP and startup do not reacquire provider credentials.")

                    val captured = first.capture()
                    first.awaitNativeReclaimed()
                    TestActiveOrdinarySealFixtureV1(first, captured, ordinary).use { sealer ->
                        val verified = sealer.seal()
                        sealer.assertReleased()
                        awaitInitialCheckpointLeaseExpiry(sealer.observer, sealer.scope)
                        TestActiveInitialCheckpointFixtureV1(sealer, verified, raw).use { checkpoint ->
                            checkpoint.checkpoint() // Completed is intentionally discarded, never supplied as HTTP authority.
                            checkpoint.assertReleased()
                            val afterProviders = providerCounts(first, ordinary, raw)
                            val beforeCreate = first.counters()
                            val created = web.post(ComplaintInstallationRoutes.HISTORY, createBody(attempt), bearer, attempt.input.key)
                            checked(created, 201)
                            assertEquals("""{"id":"${attempt.input.id}","version":1}""", created.body().decodeToString())
                            assertEquals("/api/v1/complaints/${attempt.input.id}", header(created, "Location"))
                            assertTrue(checkNotNull(header(created, "ETag")).isNotEmpty())
                            assertCreateCharge(beforeCreate, first.counters())
                            assertEquals(actor.id, first.observer.queryForObject("SELECT owner_id FROM complaints WHERE id = ?", UUID::class.java, attempt.input.id))
                            assertEquals(1L, first.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? " +
                                "AND action = 'COMPLAINT_CREATED'", Long::class.java, first.scope))
                            assertEquals(1, first.observer.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", first.scope))
                            val closed = first.counters()
                            checked(web.post(ComplaintInstallationRoutes.HISTORY, createBody(attempt), bearer, attempt.input.key), 201)
                            val status = web.post(ComplaintInstallationRoutes.STATUS, statusBody(attempt), bearer)
                            checked(status, 200)
                            val receipt = mapper.readTree(status.body())
                            assertEquals("APPLIED", receipt["outcome"].textValue()); assertEquals(201, receipt["originalStatus"].intValue())
                            assertEquals(attempt.input.id.toString(), receipt["body"]["id"].textValue())
                            assertEquals(header(created, "ETag"), receipt["etag"].textValue())
                            assertEquals(closed, first.counters()); assertEquals(afterProviders, providerCounts(first, ordinary, raw))
                            web.assertRequestsReleased(); checkpoint.assertReleased()
                        }
                    }
                } finally {
                    web.assertRequestsReleased()
                    // Disposable-scope teardown only. No product erasure/refund or checkpoint proof.
                    first.observer.update("DELETE FROM complaints WHERE data_scope_id = ?", first.scope)
                    first.observer.update("DELETE FROM complaint_resource_ids WHERE data_scope_id = ?", first.scope)
                    first.observer.update("DELETE FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND actor_id = ?", first.scope, actor.id)
                    first.observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CREATED'", first.scope)
                }
                startup.close()
                web.assertDisposed(nativeStillActive = true) // Spring must not infer close on any borrowed pool.
                assertRefused { startup.start() }; assertRefused { startup.localPort }
            }
        }
    }

    fun heldRequestDrainsBeforeJpaClose(tls: VersionBoundPersistenceConnectedFixture) = withPrepared(tls) { first, _, _ ->
        first.assembly.beginRegisteredHttpStartup(first.registration).use { startup ->
            startup.start()
            StartedHttpView(first, startup).use { web ->
                val actor = first.initial.candidate().installation
                val body = identityBody(actor, enrollment = true)
                val before = first.counters()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(loopback(), web.port), 2_000)
                    socket.soTimeout = 5_000
                    socket.tcpNoDelay = true
                    val output = socket.getOutputStream()
                    output.write(("POST ${ComplaintInstallationRoutes.ENROLLMENT} HTTP/1.1\r\nHost: 127.0.0.1:${web.port}\r\n" +
                        "Content-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    output.write(body, 0, 1); output.flush() // Real servlet ingress owns the unfinished body, before any JPA work.
                    awaitLifecycleFact(5_000) { web.ingressSnapshot().let { it.reservations == 1 && it.contexts == 1 } }
                    assertEquals(0, web.admission.activeOwners())
                    OwnedCallerTestScope().use { callers ->
                        callers.beforeClose(socket::close)
                        val releasing = callers.launch {
                            try {
                                awaitLifecycleFact(5_000) { web.ingressSnapshot().stopped }
                                val held = web.ingressSnapshot()
                                assertEquals(1, held.reservations); assertEquals(1, held.contexts)
                                assertTrue(web.emf.isOpen); assertTrue(web.context.isActive)
                                assertEquals(LifecycleState.STARTED, web.server.tomcat.server.state)
                                assertFalse(first.process.pools.shutdownRequested())
                                assertTrue(first.process.pools.ordinary.businessReady())
                                assertFalse(poolTestField<Boolean>(startup, "cleanupProven"))
                                output.write(body, 1, body.size - 1); output.flush()
                            } finally {
                                // EOF releases the actual blocked body read. Shutdown need not deliver an HTTP response.
                                socket.close()
                            }
                        }
                        startup.close() // Original assembly caller; no background close or reset of the original 10s allowance.
                        releasing.value()
                    }
                }
                web.assertDisposed(nativeStillActive = true)
                assertEquals(before, first.counters())
                assertEquals(0L, first.observer.queryForObject("SELECT count(*) FROM app_installations WHERE id = ? AND data_scope_id = ?", Long::class.java, actor.id, first.scope))
                assertEquals(0L, first.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? " +
                    "AND action = 'COMPLAINT_INSTALLATION_ENROLLED'", Long::class.java, first.scope))
            }
        }
    }

    fun assemblyClosesRetainedStartup(tls: VersionBoundPersistenceConnectedFixture) {
        var retained: StartedHttpView? = null
        withPrepared(tls) { first, _, _ ->
            val startup = first.assembly.beginRegisteredHttpStartup(first.registration)
            startup.start()
            StartedHttpView(first, startup).use { web ->
                checked(web.get(ComplaintInstallationRoutes.BOOTSTRAP), 200)
                web.assertRequestsReleased()
                retained = web
                assertFalse(first.process.pools.shutdownRequested())
                // Deliberately leave the actual startup to its original assembly. The existing fixture
                // closes its peer first, then that assembly, and checks native/trust disposal before fallback.
            }
        }
        checkNotNull(retained).assertDisposed(nativeStillActive = false)
    }

    private fun withPrepared(tls: VersionBoundPersistenceConnectedFixture,
        action: (TestActiveFirstCutFixtureV1, TestActiveOrdinaryRawFixtureV1, TestActiveInitialCheckpointRawFixtureV1) -> Unit) {
        val ordinary = TestActiveOrdinaryRawFixtureV1()
        val raw = TestActiveInitialCheckpointRawFixtureV1()
        val factories = ordinary.factories.let { TestActiveOrdinaryRawHttpV1(it.sts, it.kms, it.s3, raw.input,
            initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.PROFILE)) }
        withTestActiveFirstCut(tls, ordinaryRawHttp = factories) { first -> action(first, ordinary, raw) }
    }

    /** Passive references to the actual product graph; this view owns only its real TCP client. */
    private class StartedHttpView(val first: TestActiveFirstCutFixtureV1, val startup: ComplaintTestRegisteredHttpStartupV1) : AutoCloseable {
        val context = poolTestField<AnnotationConfigServletWebServerApplicationContext>(startup, "context")
        val emf = poolTestField<EntityManagerFactory>(startup, "emf")
        val server = poolTestField<TomcatWebServer>(startup, "server")
        val admission = poolTestField<OrdinaryPersistenceAdmission>(startup, "admission")
        val port = startup.localPort
        private val ingress = first.process.consumers.ingressAdmission
        private val client: HttpClient

        init {
            assertTrue(context.isActive); assertTrue(emf.isOpen)
            assertSame(server, context.webServer)
            assertSame(first.process.pools.ordinary, context.getBean("dataSource"))
            assertSame(first.process.pools.ordinary, (emf as EntityManagerFactoryInfo).dataSource)
            assertSame(emf, context.getBean("entityManagerFactory"))
            val manager = context.getBean(GuardedJpaTransactionManager::class.java)
            val ownership = context.getBean(PersistencePhaseOwnership::class.java)
            assertSame(emf, manager.entityManagerFactory); assertSame(manager, ownership.manager)
            assertSame(ownership, poolTestField<PersistencePhaseOwnership>(startup, "ownership"))
            assertSame(admission, context.getBean(OrdinaryPersistenceAdmission::class.java))
            val jdbc = context.getBean(JdbcTemplate::class.java)
            assertSame(jdbc, poolTestField<JdbcTemplate>(startup, "jdbc"))
            first.registration.requireInstallationResources(ownership, jdbc)
            val size = first.process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.ORDINARY }.hikari.sizing.maximumPoolSize
            assertTrue(admission.matchesComplaintPool(size))
            assertSame(first.process.consumers.jwt.boundUserKeyProvider, context.getBean(JwtKeyProvider::class.java))
            assertEquals(1, context.getBeansOfType(EntityManagerFactory::class.java).size)
            assertEquals(2, context.getBeansOfType(SecurityFilterChain::class.java).size)
            val composition = context.getBean(ComplaintTestBootstrapHttpCompositionV1::class.java)
            val mapping = context.getBean("complaintTestBootstrapHandlerMapping", SimpleUrlHandlerMapping::class.java)
            assertEquals(SUBSET, mapping.urlMap.keys)
            mapping.urlMap.values.forEach { assertSame(composition.handler, it) }
            val listener = context.getBean(TomcatServletWebServerFactory::class.java)
            assertEquals("127.0.0.1", checkNotNull(listener.address).hostAddress); assertEquals(0, listener.port)
            assertTrue(port in 1..65_535)
            for (bean in listOf("dataSource", "entityManagerFactory", "transactionManager", "jwtKeyProvider")) {
                assertEquals("", (context.beanFactory.getBeanDefinition(bean) as RootBeanDefinition).destroyMethodName)
            }
            client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(direct).connectTimeout(Duration.ofSeconds(2)).build()
        }

        fun get(path: String): HttpResponse<ByteArray> = send(HttpRequest.newBuilder(uri(path)).GET())
        fun post(path: String, body: ByteArray, bearer: String? = null, key: UUID? = null): HttpResponse<ByteArray> =
            send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").apply {
                bearer?.let { header("Authorization", "Bearer $it") }
                key?.let { header("X-Kira-Idempotency-Key", it.toString()) }
            }.POST(HttpRequest.BodyPublishers.ofByteArray(body)))
        private fun uri(path: String) = URI.create("http://127.0.0.1:$port$path")
        private fun send(request: HttpRequest.Builder): HttpResponse<ByteArray> =
            client.send(request.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofByteArray())
        fun ingressSnapshot(): IngressSnapshot = snapshot(ingress)
        fun assertRequestsReleased() {
            awaitLifecycleFact(5_000) { ingressSnapshot().let { it.reservations == 0 && it.contexts == 0 } && admission.activeOwners() == 0 }
            requireConnectionFree()
            assertNull(PersistencePhaseOwnership.current())
        }
        fun assertDisposed(nativeStillActive: Boolean) {
            startup.requireCleanupProven()
            assertFalse(context.isActive); assertFalse(emf.isOpen)
            assertEquals(LifecycleState.DESTROYED, server.tomcat.server.state)
            assertTrue(ingress.registeredStartupAdmissionReleased()); assertEquals(0, admission.activeOwners())
            assertEquals(!nativeStillActive, first.process.pools.shutdownRequested())
            assertEquals(nativeStillActive, first.process.pools.ordinary.businessReady())
        }
        override fun close() = client.close() // No application, JPA, native-pool or trust teardown in this view.
    }

    private data class IngressSnapshot(val stopped: Boolean, val reservations: Int, val contexts: Int)
    private fun snapshot(ingress: ComplaintIngressAdmission): IngressSnapshot = synchronized(poolTestField<Any>(ingress, "lock")) {
        IngressSnapshot(poolTestField(ingress, "closed"), poolTestField(ingress, "reservations"), poolTestField<Map<*, *>>(ingress, "contexts").size)
    }
    private fun assertRefused(action: () -> Any?) = assertEquals(ComplaintTestDeploymentFailureV1.PROCESS_REFUSED,
        assertThrows<ComplaintTestDeploymentExceptionV1> { action() }.code)
    private fun providerCounts(first: TestActiveFirstCutFixtureV1, ordinary: TestActiveOrdinaryRawFixtureV1, raw: TestActiveInitialCheckpointRawFixtureV1) =
        listOf(first.native.sts.requests.size, first.native.kms.requests.size, first.native.requests.size, ordinary.requestBudgets.size,
            raw.sts.requests.size, raw.kms.requests.size, raw.requests.size, first.p.f.http.read.requests.size)

    private fun attempt(actor: ScopedInstallationId): RegisteredInitialCreateAttemptV1 {
        val input = ComplaintOwnerCreateInput(UUID.randomUUID(), UUID.randomUUID(), ComplaintType.TECHNICAL,
            " Registered subject ", " Registered body\r\nline ", ComplaintReportMetadataInput(null, "fixture-os", "", ""))
        val identity = checkNotNull(ComplaintReportIdentity.checked(input.id.toString(), input.key.toString(), actor.scope.id.toString()))
        val request = (ComplaintReportRequest.normalize(identity, input.type, input.subject, input.body, input.metadata) as ComplaintReportRequestResult.Accepted).request
        return RegisteredInitialCreateAttemptV1(input, ComplaintOwnerCreateCandidate.prepare(actor, request))
    }
    private fun identityBody(actor: ScopedInstallationId, enrollment: Boolean = false): ByteArray = mapper.writeValueAsBytes(
        linkedMapOf("installationId" to actor.id.toString(), "expectedDataScopeId" to actor.scope.id.toString(),
            "secret" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })).also { if (enrollment) it["platform"] = "ANDROID" })
    private fun createBody(attempt: RegisteredInitialCreateAttemptV1): ByteArray = mapper.writeValueAsBytes(mapOf(
        "id" to attempt.input.id.toString(), "type" to attempt.input.type.name, "subject" to attempt.input.subject, "body" to attempt.input.body,
        "metadata" to mapOf("appVersion" to null, "osVersion" to "fixture-os", "manufacturer" to "", "deviceModel" to "")))
    private fun statusBody(attempt: RegisteredInitialCreateAttemptV1): ByteArray = mapper.writeValueAsBytes(mapOf(
        "operation" to "OWNER_CREATE", "key" to attempt.input.key.toString(), "targetIds" to listOf(attempt.input.id.toString()),
        "fingerprint" to ComplaintReportFingerprint.of(attempt.candidate.request).encoded))
    private fun token(response: HttpResponse<ByteArray>, actor: ScopedInstallationId, first: TestActiveFirstCutFixtureV1): String {
        val parsed = mapper.readTree(response.body())
        assertEquals(actor.id.toString(), parsed["installationId"].textValue()); assertEquals(actor.scope.id.toString(), parsed["dataScopeId"].textValue())
        assertEquals(1L, parsed["credentialVersion"].longValue())
        return parsed["accessToken"].textValue().also {
            assertEquals(actor, InstallationJwtCodec(first.process.consumers.jwt.installationKeyRing, Clock.systemUTC()).verify(it).installation)
        }
    }
    private fun assertCreateCharge(before: Map<String, CounterSnapshot>, after: Map<String, CounterSnapshot>) {
        assertEquals(before.keys, after.keys)
        for (counter in ComplaintCapacityCounter.entries) {
            val old = before.getValue(counter.storedName); val current = after.getValue(counter.storedName)
            val amount = ComplaintCapacityCharges.OWNER_CREATE[counter]
            if (amount == 0L) assertEquals(old, current) else {
                assertEquals(old.preserved, current.preserved)
                assertEquals(old.free - amount, current.free); assertEquals(old.actual + amount, current.actual)
            }
        }
    }
    private fun header(response: HttpResponse<ByteArray>, name: String): String? = response.headers().firstValue(name).orElse(null)
    private fun checked(response: HttpResponse<ByteArray>, expected: Int) {
        assertEquals(expected, response.statusCode())
        assertEquals("1", header(response, "X-Kira-Complaint-Contract")); assertEquals("no-store, no-transform", header(response, "Cache-Control"))
        assertTrue(response.body().size in 1..16 * 1024)
        assertNull(header(response, "Set-Cookie")); assertNull(header(response, "Content-Encoding"))
    }
    private fun loopback(): InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val mapper = ObjectMapper()
    private val SUBSET = setOf(ComplaintInstallationRoutes.BOOTSTRAP, ComplaintInstallationRoutes.ENROLLMENT, ComplaintInstallationRoutes.SESSION,
        ComplaintInstallationRoutes.HISTORY, ComplaintInstallationRoutes.STATUS)
    private val direct = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }
}
