package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.complaint.api.ComplaintInstallationBootstrapHttpHandler
import me.manga.kira.backend.complaint.application.ComplaintInstallationBootstrapService
import me.manga.kira.backend.complaint.catalog.ComplaintTestNamespaceRegistrationCases
import me.manga.kira.backend.complaint.catalog.ProjectionActivationObservation
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrapFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrapRejected
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStateAssessment
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpRejected
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBootstrapReadAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationExchangeAdapter
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.InstallationCurrentStateReadOperation
import me.manga.kira.backend.complaint.infrastructure.admission.JdbcInstallationCurrentStateReader
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationCurrentStatePhaseExecutor
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintBootstrapSpringTestFixture
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.support.JwtTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.verify
import org.mockito.Mockito.spy
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Real first PROJECT + target primary/replica readback + ordinary phase. No seeded/usable registration issuer. */
internal object ComplaintInstallationBootstrapCases {
    fun mountedMaintenanceIsolation(tls: VersionBoundPersistenceConnectedFixture) = withFixture(tls, expireClosedSetupPredecessors = false) { f ->
        val before = f.image()
        val providerReads = f.p.f.http.read.requests.size
        assertEquals(true, f.observer.queryForMap("SELECT maintenance_closed FROM complaint_journal_control WHERE data_scope_id = ?", f.scope.id)["maintenance_closed"])
        val composition = ComplaintTestBootstrapHttpCompositionV1.fromRegistered(f.registration, f.ordinary.ownership, f.ordinary.jdbc)
        ComplaintBootstrapSpringTestFixture(composition).use { web ->
            assertEquals(2, web.context.getBeansOfType(SecurityFilterChain::class.java).size)
            val mapping = web.context.getBean("complaintTestBootstrapHandlerMapping", SimpleUrlHandlerMapping::class.java)
            assertEquals(setOf(PATH), mapping.urlMap.keys)
            val userId = UUID.randomUUID()
            val userToken = JwtTestSupport.mint(userId)
            val installationToken = InstallationJwtCodec(f.registration.process.consumers.jwt.installationKeyRing, Clock.systemUTC())
                .issue(ScopedInstallationId(UUID.randomUUID(), f.scope), 1, Instant.now()).value
            val beforeRead = f.p.f.http.beforeRead
            f.p.f.http.beforeRead = { error("Bootstrap attempted catalog/provider work") }
            try {
                for (token in listOf(null, "malformed", "Bearer $userToken", "Bearer $installationToken")) {
                    val request = get(PATH).with { it.remoteAddr = "192.0.2.1"; it }
                    token?.let { request.header("Authorization", it, "Bearer duplicate-invalid") }
                    val response = web.mvc.perform(request).andReturn().response
                    assertEquals(200, response.status)
                    assertEquals("""{"dataScopeId":"${f.scope.id}","contractVersion":1}""", response.contentAsString)
                    assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
                    assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
                    assertTrue(response.contentLength in 1..16 * 1024)
                    for (name in listOf("WWW-Authenticate", "Set-Cookie", "ETag", "Location", "Content-Encoding")) assertNull(response.getHeader(name))
                    f.assertReleased()
                }
            } finally { f.p.f.http.beforeRead = beforeRead }
            // Even framing errors must be selected inside the original ingress and before the generic 256 KiB buffer.
            for ((header, value, status) in listOf(Triple("Content-Length", Long.MAX_VALUE.toString(), 400),
                Triple("Transfer-Encoding", "chunked", 400), Triple("Content-Encoding", "gzip", 415))) {
                val response = web.mvc.perform { servlet ->
                    object : MockHttpServletRequest(servlet, "GET", PATH) {
                        override fun getInputStream(): jakarta.servlet.ServletInputStream = error("Invalid framing buffered input")
                    }.apply { servletPath = PATH; remoteAddr = "192.0.2.1"; addHeader(header, value); addHeader("Authorization", "malformed") }
                }.andReturn().response
                assertEquals(status, response.status)
            }
            for ((method, path) in listOf("POST" to PATH, "HEAD" to PATH, "GET" to "$PATH/", "GET" to "/api/v1/installations/%62ootstrap",
                "POST" to "/api/v1/installations", "POST" to "/api/v1/installations/session", "GET" to "/api/v1/installations/me",
                "POST" to "/api/v1/installations/delete-all", "GET" to "/api/v1/complaints", "POST" to "/api/v1/complaint-operations/status",
                "POST" to "/api/v1/admin/complaints/search")) {
                val response = web.mvc.perform { servlet ->
                    object : MockHttpServletRequest(servlet, method, path) {
                        override fun getInputStream(): jakarta.servlet.ServletInputStream = error("Closed route buffered input")
                    }.apply { servletPath = path; remoteAddr = "192.0.2.1"; addHeader("Authorization", "malformed"); addHeader("Content-Length", "999999999") }
                }.andReturn().response
                assertEquals(404, response.status, "$method $path")
                if (method == "HEAD") assertTrue(response.contentAsByteArray.isEmpty())
                assertNull(response.getHeader("WWW-Authenticate"))
            }
            verifyNoInteractions(web.users)
            assertEquals(401, web.mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer $userToken")).andReturn().response.status)
            verify(web.users).findById(userId) // Existing qualified @Order(2) still owns unrelated user authentication.
        }
        assertEquals(providerReads, f.p.f.http.read.requests.size)
        assertEquals(before, f.image(), "Bootstrap never writes counters, leases, timestamps, catalog, run, credential or audit state.")
    }

    fun driftNeverSelectsFallback(tls: VersionBoundPersistenceConnectedFixture) = withFixture(tls) { f ->
        assertEquals(f.scope, f.read().scope)
        assertEquals(ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED,
            f.phases.assess(f.registration.process.desiredSettings(), f.scope), "Diagnostic equality remains separate and untrusted.")
        val runDrift = mapOf(
            "configuration_hash" to "decode(repeat('ac', 32), 'hex')",
            "installation_limit" to "installation_limit + 1",
            "activation_catalog_generation" to "activation_catalog_generation + 1",
            "activation_catalog_hash" to "decode(repeat('ad', 32), 'hex')",
            "created_at" to "created_at + interval '1 second'",
        )
        for ((column, expression) in runDrift) f.drift("complaint_test_runs", f.scope, column, expression)
        val controlDrift = mapOf(
            "desired_generation" to "desired_generation + 1", "desired_configuration_hash" to "decode(repeat('ae', 32), 'hex')",
            "database_identity" to "gen_random_uuid()", "restore_identity" to "gen_random_uuid()",
            "event_writer_generation" to "gen_random_uuid()", "catalog_writer_generation" to "gen_random_uuid()",
            "trust_bundle_hash" to "decode(repeat('af', 32), 'hex')", "accepted_catalog_generation" to "accepted_catalog_generation + 1",
            "accepted_catalog_hash" to "decode(repeat('ab', 32), 'hex')",
            "pending_projection_token" to "(SELECT operation_token FROM complaint_catalog_mutations ORDER BY successor_generation DESC LIMIT 1)",
        )
        for ((column, expression) in controlDrift) f.drift("complaint_journal_control", f.scope, column, expression)
        for ((column, expression) in controlDrift.filterKeys { it !in setOf("desired_generation", "desired_configuration_hash") }) {
            f.drift("complaint_journal_control", ComplaintDataScope.LIVE, column, expression)
        }
        // A different current ACTIVE UUID cannot be substituted for the registered scope, even with the same row bytes.
        f.drift("complaint_test_runs", f.scope, "data_scope_id", "gen_random_uuid()")
        for (table in listOf("complaint_test_runs", "complaint_journal_control")) {
            val original = f.row(table)
            assertEquals(1, f.observer.update("DELETE FROM $table WHERE data_scope_id = ?", f.scope.id))
            try { f.assertUnavailableWithoutWrites() } finally { f.restoreMissingRow(table, original) }
        }
        assertEquals(f.scope, f.read().scope)
    }

    fun terminalScopeIsNeverRebound(tls: VersionBoundPersistenceConnectedFixture) = withFixture(tls) { f ->
        val durablySelected = f.read().scope
        val installation = ScopedInstallationId(UUID.randomUUID(), durablySelected)
        val candidate = InstallationEnrollmentCredentials.prepare(installation, ComplaintPlatform.ANDROID, ByteArray(32) { 47 })
        val exchange = ComplaintInstallationExchangeAdapter(f.registration, f.ordinary.ownership, f.ordinary.jdbc,
            ComplaintInstallationEnrollmentAudit { scope, allocation, at -> f.audit.recordInstallationEnrollment(scope, allocation, at) })
        assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, TestRunSealingV1.begin(f.registration).seal())
        val sealed = f.image()
        f.assertUnavailableWithoutWrites()
        val result = f.ingress.withIngress(f.request()) { context -> assertThrows<ComplaintInstallationHttpRejected> { exchange.enroll(context, candidate) } }
        // The default maintenance gate remains closed; this bootstrap-only milestone does not open enrollment to prove its 410 route.
        assertTrue(result.failure in setOf(ComplaintInstallationHttpFailure.UNAVAILABLE, ComplaintInstallationHttpFailure.INSTALLATION_SCOPE_RETIRED))
        assertEquals(installation, candidate.installation)
        assertTrue(candidate.installation.scope.testOnly)
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_installation_ids WHERE id = ?", Long::class.java, installation.id))
        assertEquals(sealed, f.image())
        val original = f.row("complaint_test_runs")
        try {
            // Deliberate adverse SQL shapes only, NOT a terminal manifest producer or evidence of a completed purge.
            val bytes = "bootstrap-negative-terminal-only".toByteArray()
            assertEquals(1, f.observer.update("UPDATE complaint_test_runs SET state = 'PURGING', purging_at = now(), " +
                "unused_reserve = array_fill(0::bigint, ARRAY[22]), final_ordinary_epoch = 1, terminal_seal_epoch = 2, " +
                "generation_seal_count = 1, generation_seal_root = configuration_hash, seal_set_bytes = ?, seal_set_hash = sha256(?), " +
                "event_manifest_count = 0, event_manifest_root = configuration_hash, installation_manifest_count = enrolled_count, " +
                "installation_manifest_root = configuration_hash, installation_chunk_count = CASE WHEN enrolled_count = 0 THEN 0 ELSE 1 END, " +
                "retired_count = enrolled_count, deleted_count = 0, permanent_denial_bytes = ?, permanent_denial_hash = sha256(?), " +
                "terminal_event_id = ?, terminal_object_key = 'bootstrap/negative', terminal_object_version = 'negative-v1', " +
                "terminal_ciphertext_hash = configuration_hash, terminal_catalog_generation = activation_catalog_generation + 1, " +
                "terminal_catalog_hash = configuration_hash WHERE data_scope_id = ?", bytes, bytes, bytes, bytes, "A".repeat(43), f.scope.id))
            f.assertUnavailableWithoutWrites()
            assertEquals(1, f.observer.update("UPDATE complaint_test_runs SET state = 'PURGED', purged_at = now() WHERE data_scope_id = ?", f.scope.id))
            f.assertUnavailableWithoutWrites()
        } finally {
            assertEquals(1, f.observer.update("DELETE FROM complaint_test_runs WHERE data_scope_id = ?", f.scope.id))
            f.restoreMissingRow("complaint_test_runs", original)
        }
    }

    fun originalResourcesAndRelease(tls: VersionBoundPersistenceConnectedFixture) = withFixture(tls) { f ->
        val before = f.image()
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintInstallationBootstrapReadAdapter(f.registration, f.ordinary.ownership, JdbcTemplate(f.ordinary.pool)) }
        val other = PersistencePhaseOwnership(OrdinaryPersistenceAdmission(2), GuardedJpaTransactionManager(f.ordinary.entityManagerFactory, f.ordinary.pool))
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintInstallationBootstrapReadAdapter(f.registration, other, f.ordinary.jdbc) }
        assertThrows<PersistencePhaseException> { f.reader.readBootstrap(f.ordinary.ownership, f.registration) }
        f.withPhase(other) { phase ->
            // Same physical pool, wrong original phase owner: the registration's retained pair cannot be borrowed.
            assertThrows<PersistencePhaseException> { f.reader.readBootstrap(f.ordinary.ownership, f.registration) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        f.withPhase { phase ->
            assertThrows<PersistencePhaseException> { JdbcInstallationCurrentStateReader(JdbcTemplate(f.ordinary.pool)).readBootstrap(f.ordinary.ownership, f.registration) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        val rolledBack = f.withPhase { f.reader.readBootstrap(f.ordinary.ownership, f.registration) }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, assertThrows<PersistencePhaseException> { rolledBack.bootstrap(f.registration) }.databaseOutcome)
        val operation = f.withPhase { phase ->
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            assertEquals("on", f.ordinary.jdbc.queryForObject("SHOW transaction_read_only", String::class.java))
            val retained = f.reader.readBootstrap(f.ordinary.ownership, f.registration)
            assertFalse(assertThrows<PersistencePhaseException> { retained.bootstrap(f.registration) }.cleanupProven)
            phase.commit()
            val unreleased = assertThrows<PersistencePhaseException> { retained.bootstrap(f.registration) }
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, unreleased.databaseOutcome)
            assertFalse(unreleased.cleanupProven)
            retained
        }
        assertEquals(f.scope, operation.bootstrap(f.registration).scope)
        assertThrows<IllegalStateException> { operation.bootstrap(f.registration) }
        val wrongCaller = f.withPhase { phase -> f.reader.readBootstrap(f.ordinary.ownership, f.registration).also { phase.commit() } }
        OwnedCallerTestScope().use { callers -> callers.launch { assertThrows<PersistencePhaseException> { wrongCaller.bootstrap(f.registration) } }.value() }
        assertThrows<PersistencePhaseException> { wrongCaller.bootstrap(f.registration) }
        assertThrows<ComplaintAdmissionRejected> { f.adapter.read(ComplaintIngressContext()) }
        f.ingress.withIngress(f.request()) { context ->
            assertEquals(f.scope, f.adapter.read(context).scope)
            assertThrows<ComplaintAdmissionRejected> { f.adapter.read(context) } // No second semantic attempt/read on the original ingress.
        }
        assertEquals(before, f.image())
    }

    fun completionFailuresCannotRelease(tls: VersionBoundPersistenceConnectedFixture) = withFixture(tls) { f ->
        val before = f.image()
        for (afterCommit in listOf(false, true)) {
            val read = f.withPhase { phase ->
                val operation = f.reader.readBootstrap(f.ordinary.ownership, f.registration)
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) { if (!afterCommit) error("Synthetic before-commit failure") }
                    override fun afterCommit() { if (afterCommit) error("Synthetic after-commit failure") }
                })
                phase.recordFailure(checkNotNull(runCatching { phase.commit() }.exceptionOrNull()))
                operation
            }
            val failure = assertThrows<PersistencePhaseException> { read.bootstrap(f.registration) }
            assertEquals(if (afterCommit) PersistenceDatabaseOutcome.COMMITTED else PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
            assertTrue(failure.cleanupProven)
        }
        // A known commit cannot outlive revocation between the read and final connection-free release.
        val closed = f.withPhase { phase ->
            val operation = f.reader.readBootstrap(f.ordinary.ownership, f.registration)
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() { f.registration.close() }
            })
            phase.commit()
            operation
        }
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { closed.bootstrap(f.registration) }
        f.assertUnavailableWithoutWrites()
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestBootstrapHttpCompositionV1.fromRegistered(f.registration, f.ordinary.ownership, f.ordinary.jdbc) }
        assertEquals(before, f.image())
    }

    fun unknownCommitAndUnresolvedRelease(tls: VersionBoundPersistenceConnectedFixture) = withFixture(tls) { f ->
        val before = f.image()
        val interruptedCommit = f.withPhase { phase ->
            val read = f.reader.readBootstrap(f.ordinary.ownership, f.registration)
            val pid = checkNotNull(f.ordinary.jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java))
            // Actual remote PG connection termination, not a fabricated UNKNOWN outcome or replacement reader.
            checkNotNull(f.observer.dataSource).connection.use { observer ->
                observer.prepareStatement("SELECT pg_terminate_backend(?)").use { statement ->
                    statement.queryTimeout = 2
                    statement.setInt(1, pid)
                    statement.executeQuery().use { row -> assertTrue(row.next() && row.getBoolean(1) && !row.next()) }
                }
            }
            phase.recordFailure(checkNotNull(runCatching { phase.commit() }.exceptionOrNull()))
            read
        }
        val unknown = assertThrows<PersistencePhaseException> { interruptedCommit.bootstrap(f.registration) }
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, unknown.databaseOutcome)
        assertTrue(unknown.cleanupProven)

        val key = Any()
        val sentinel = Any()
        var bound = false
        val phase = f.ordinary.ownership.enterComplaintInstallationCurrentState()
        var read: InstallationCurrentStateReadOperation? = null
        var lease: PersistenceJdbcLease? = null
        try {
            try {
                phase.begin()
                lease = ownedPoolLease((TransactionSynchronizationManager.getResource(f.ordinary.pool) as ConnectionHolder).connection)
                read = f.reader.readBootstrap(f.ordinary.ownership, f.registration)
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        TransactionSynchronizationManager.bindResource(key, sentinel)
                        bound = true
                        error("Synthetic unresolved original release")
                    }
                })
                phase.recordFailure(checkNotNull(runCatching { phase.commit() }.exceptionOrNull()))
            } finally { phase.finish() }
            assertTrue(bound)
            assertSame(phase, PersistencePhaseOwnership.current())
            assertTrue(phase.quarantined())
            val unresolved = assertThrows<PersistencePhaseException> { checkNotNull(read).bootstrap(f.registration) }
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, unresolved.databaseOutcome)
            assertFalse(unresolved.cleanupProven)
            val response = spy(MockHttpServletResponse())
            assertThrows<PersistencePhaseException> { f.http(response) }
            verifyNoInteractions(response)
            assertSame(sentinel, TransactionSynchronizationManager.getResource(key))
            assertSame(phase, PersistencePhaseOwnership.current())
            assertTrue(phase.quarantined())
            assertTrue(response.contentAsByteArray.isEmpty())
        } finally {
            if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
            requireConnectionFree() // Eventual retirement belongs to that same original phase, never a repaired success.
        }
        assertTrue(checkNotNull(lease).completion.quiescent())
        assertThrows<PersistencePhaseException> { checkNotNull(read).bootstrap(f.registration) }
        assertEquals(before, f.image())
    }

    fun unavailableAdmissionIsNotQuotaOrReadiness(tls: VersionBoundPersistenceConnectedFixture) = withFixture(tls) { f ->
        val before = f.image()
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val peer = callers.launch {
                val permit = checkNotNull(f.ordinary.admission.tryComplaintBoundary())
                try { gate.hold() } finally { assertTrue(permit.releaseAfterQuiescence()) }
            }
            gate.awaitEntered()
            try {
                val unavailable = f.http()
                assertEquals(503, unavailable.status)
                assertNull(unavailable.getHeader("Retry-After"))
                assertEquals(1, f.ordinary.admission.activeOwners()) // Refusal cannot release another caller's permit.
                assertEquals(0L, f.ordinary.ownedPool.lifecycle.activeAcquisitions())
            } finally { gate.release(); peer.value() }
        }
        assertEquals(200, f.http().status)
        f.runtime.owner.requestShutdown()
        f.assertUnavailableWithoutWrites()
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestBootstrapHttpCompositionV1.fromRegistered(f.registration, f.ordinary.ownership, f.ordinary.jdbc) }
        assertEquals(before, f.image())
    }

    // Downstream cases use explicit synthetic expiry of actually closed setup predecessors only.
    // The mounted positive above keeps natural expiry; the shared helper default and runtime clocks stay unchanged.
    private fun withFixture(tls: VersionBoundPersistenceConnectedFixture, expireClosedSetupPredecessors: Boolean = true, action: (Fixture) -> Unit) =
        ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls, expireClosedSetupPredecessors = expireClosedSetupPredecessors) { p, runtime, registration, _ ->
            ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, audit ->
                Fixture(p, runtime, registration, ordinary, audit).let { f ->
                    try { action(f) } finally { f.assertReleased() }
                }
            }
        }

    private class Fixture(
        val p: ProjectionActivationObservation,
        val runtime: VersionBoundPersistenceConnectedFixture,
        val registration: ComplaintTestNamespaceRegistrationV1,
        val ordinary: OrdinarySourceGrantCleanupFixture,
        val audit: AuditService,
    ) {
        val scope = registration.process.desiredSettings().scope
        val observer = p.f.rows.observer
        val ingress = registration.process.consumers.ingressAdmission
        val adapter = ComplaintInstallationBootstrapReadAdapter(registration, ordinary.ownership, ordinary.jdbc)
        val reader = JdbcInstallationCurrentStateReader(ordinary.jdbc)
        val phases = ComplaintInstallationCurrentStatePhaseExecutor(ordinary.ownership, reader)
        private val handler = ComplaintInstallationBootstrapHttpHandler(ComplaintInstallationBootstrapService(adapter), ingress)
        private val leases = mutableListOf<PersistenceJdbcLease>()

        fun request() = MockHttpServletRequest("GET", PATH).apply { remoteAddr = "192.0.2.1" }
        fun read() = ingress.withIngress(request()) { adapter.read(it) }.also { assertReleased() }
        fun http(response: MockHttpServletResponse = MockHttpServletResponse()) = response.also { handler.handleRequest(request(), it) }

        fun image(): Pair<Map<String, List<String>>, String> = p.image() to checkNotNull(observer.queryForObject(
            "SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, ComplaintDataScope.LIVE.id))

        fun assertUnavailableWithoutWrites() {
            val before = image()
            val failure = assertThrows<ComplaintInstallationBootstrapRejected> { read() }
            assertEquals(ComplaintInstallationBootstrapFailure.UNAVAILABLE, failure.failure)
            assertEquals(503, http().status)
            assertEquals(before, image())
            assertReleased()
        }

        fun row(table: String): String = checkNotNull(observer.queryForObject("SELECT to_jsonb(t)::text FROM $table t WHERE data_scope_id = ?", String::class.java, scope.id))

        fun restoreMissingRow(table: String, json: String) {
            assertEquals(1, observer.update("INSERT INTO $table SELECT * FROM jsonb_populate_record(NULL::$table, ?::jsonb)", json))
        }

        fun drift(table: String, selected: ComplaintDataScope, column: String, expression: String) {
            val old = observer.queryForMap("SELECT $column FROM $table WHERE data_scope_id = ?", selected.id)[column]
            assertEquals(1, observer.update("UPDATE $table SET $column = $expression WHERE data_scope_id = ?", selected.id))
            try { assertUnavailableWithoutWrites() } finally {
                // Fixed test identifiers only. The relocated sole run is selected explicitly, never treated as current authority.
                val where = if (column == "data_scope_id") "state = 'ACTIVE'" else "data_scope_id = ?"
                val args = if (column == "data_scope_id") arrayOf(old) else arrayOf(old, selected.id)
                assertEquals(1, observer.update("UPDATE $table SET $column = ? WHERE $where", *args))
            }
        }

        fun <T> withPhase(owner: PersistencePhaseOwnership = ordinary.ownership, work: (PersistencePhaseContext) -> T): T {
            val phase = owner.enterComplaintInstallationCurrentState()
            try {
                phase.begin()
                val connection = (TransactionSynchronizationManager.getResource(ordinary.pool) as ConnectionHolder).connection
                leases.add(ownedPoolLease(connection))
                return work(phase)
            } finally { phase.finish(); assertReleased() }
        }

        fun assertReleased() {
            requireConnectionFree()
            assertEquals(0, ordinary.admission.activeOwners())
            assertEquals(0L, ordinary.ownedPool.lifecycle.activeAcquisitions())
            assertEquals(0L, ordinary.ownedPool.lifecycle.actorSnapshot().futureLeaseEntries)
            assertTrue(leases.all { it.completion.quiescent() })
        }
    }

    private const val PATH = ComplaintInstallationBootstrapHttpHandler.PATH
}
