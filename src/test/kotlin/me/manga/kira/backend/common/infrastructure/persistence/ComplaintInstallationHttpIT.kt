package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import me.manga.kira.backend.complaint.api.ComplaintInstallationHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryHttpHandler
import me.manga.kira.backend.complaint.application.ComplaintInstallationService
import me.manga.kira.backend.complaint.application.ComplaintOwnerHistoryService
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerHistoryReadAdapter
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerHistoryStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerHistoryPhaseExecutor
import me.manga.kira.backend.security.historyTestCursors
import me.manga.kira.backend.security.historyTestJwt
import me.manga.kira.backend.security.historyTestRequest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/** Existing owned PostgreSQL/ordinary fixtures only. Synthetic TEST configuration is never activation or bootstrap authority. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintInstallationHttpIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintInstallationHttpIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `real enrollment and session HTTP token reaches actual scoped owner history without direct token issuance`() {
        withFixture { f ->
            val before = f.run.state()
            val created = f.request(ENROLLMENT)
            assertEquals(201, created.status)
            assertEquals("/api/v1/installations/me", created.getHeader("Location"))
            f.run.assertChargedOnce(before)
            f.assertIssued(created, f.base.jdbc.databaseTimes.single())
            val replay = f.request(ENROLLMENT)
            assertEquals(200, replay.status)
            assertNull(replay.getHeader("Location"))
            assertEquals(f.json(created)["credentialVersion"], f.json(replay)["credentialVersion"])
            f.run.assertChargedOnce(before)

            val session = f.request(SESSION)
            assertEquals(200, session.status)
            f.assertIssued(session)
            val token = f.json(session)["accessToken"].asText() // Actual HTTP producer output, not jwt.issue or a caller-created principal.
            val id = f.content()
            val history = f.history(token)
            assertEquals(200, history.status)
            assertEquals(listOf(id.toString()), f.json(history)["items"].map { it["id"].asText() })
            assertEquals(emptyList<String>(), f.json(history)["notices"].map { it["id"].asText() })
            assertTrue(f.json(history)["nextCursor"].isNull)
            f.base.assertReleased()
        }
    }

    @Test
    fun `real body-secret refusals preserve never-claimed recovery and never turn stale TEST scopes into LIVE`() {
        withFixture { f ->
            val before = f.run.state()
            f.assertProblem(f.request(SESSION), 404, "INSTALLATION_NOT_FOUND")
            assertEquals(before, f.run.state())
            assertEquals(201, f.request(ENROLLMENT).status)
            val enrolled = f.run.state()
            f.assertProblem(f.request(SESSION, secret = ByteArray(32) { 99 }), 403, "INSTALLATION_CREDENTIAL_REJECTED")
            f.assertProblem(f.request(ENROLLMENT, platform = "IOS"), 409, "INSTALLATION_PLATFORM_MISMATCH")
            f.assertProblem(f.request(ENROLLMENT, scope = ComplaintDataScope.LIVE), 409, "INSTALLATION_SCOPE_MISMATCH")
            assertEquals(enrolled, f.run.state())
            f.run.terminalState("SEALED")
            val sealed = f.run.state()
            f.assertProblem(f.request(SESSION), 410, "INSTALLATION_SCOPE_RETIRED")
            f.assertProblem(f.request(ENROLLMENT, id = UUID.randomUUID()), 410, "INSTALLATION_SCOPE_RETIRED")
            assertEquals(sealed, f.run.state())
        }
    }

    @Test
    fun `credential change after real preflight is rechecked by admitted refresh before any token response`() {
        withFixture { f ->
            assertEquals(201, f.request(ENROLLMENT).status)
            val before = f.base.observer.queryForObject("SELECT last_authenticated_at FROM app_installations WHERE id = ?", Timestamp::class.java, f.id)
            var changed = false
            f.base.afterStep = { step ->
                if (step == EnrollmentFixtureStep.SESSION_SNAPSHOT) {
                    assertEquals(
                        1,
                        f.base.observer.update(
                            "UPDATE app_installations SET credential_version = credential_version + 1, version = version + 1 WHERE id = ?",
                            f.id,
                        ),
                    )
                    changed = true
                }
            }
            val response = try {
                f.request(SESSION)
            } finally {
                f.base.afterStep = {}
            }
            assertTrue(changed)
            f.assertProblem(response, 403, "INSTALLATION_CREDENTIAL_REJECTED")
            assertEquals(
                before,
                f.base.observer.queryForObject("SELECT last_authenticated_at FROM app_installations WHERE id = ?", Timestamp::class.java, f.id),
            )
        }
    }

    @Test
    fun `rollback unknown commit and failed completion tail expose no token and preserve actual database outcome`() {
        for (fault in Fault.entries) {
            withFixture { f ->
                val before = f.run.state()
                f.base.afterStep = { step ->
                    if (step == EnrollmentFixtureStep.AUDIT) {
                        when (fault) {
                            Fault.ROLLBACK -> throw SyntheticInstallationEnrollmentFailure()

                            Fault.COMMIT -> {
                                f.base.ordinary.jdbc.execute("CREATE TEMP TABLE kira_installation_http_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                                check(f.base.ordinary.jdbc.update("INSERT INTO kira_installation_http_commit VALUES (1), (1)") == 2)
                            }

                            Fault.TAIL -> TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                                override fun afterCommit(): Unit = throw SyntheticInstallationEnrollmentFailure()
                            })
                        }
                    }
                }
                val response = try {
                    f.request(ENROLLMENT)
                } finally {
                    f.base.afterStep = {}
                }
                f.assertProblem(response, 503, "SERVICE_UNAVAILABLE")
                val expected = when (fault) {
                    Fault.ROLLBACK -> PersistenceDatabaseOutcome.ROLLED_BACK
                    Fault.COMMIT -> PersistenceDatabaseOutcome.UNKNOWN
                    Fault.TAIL -> PersistenceDatabaseOutcome.COMMITTED
                }
                assertEquals(expected, f.base.observations.last().second.phase.databaseOutcome())
                if (fault == Fault.TAIL) {
                    assertEquals(200, f.request(ENROLLMENT).status) // A committed response failure is not absence or rollback.
                    f.run.assertChargedOnce(before)
                } else {
                    assertEquals(before, f.run.state())
                }
                f.base.assertReleased()
            }
        }
    }

    @Test
    fun `lost HTTP enrollment send retains one durable identity and exact retry returns a usable session`() {
        withFixture { f ->
            val before = f.run.state()
            var writes = 0
            val response = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream = object : ServletOutputStream() {
                    override fun isReady(): Boolean = true
                    override fun setWriteListener(listener: WriteListener) = Unit
                    override fun write(value: Int) {
                        writes += 1
                        throw IOException("Synthetic response loss")
                    }
                }
            }
            val failure = assertThrows<IOException> { f.handler.handleRequest(f.input(ENROLLMENT), response) }
            assertEquals("Installation HTTP exchange failed.", failure.message)
            assertNull(failure.cause)
            assertEquals(1, writes)
            assertEquals(201, response.status)
            f.run.assertChargedOnce(before)
            assertEquals(200, f.request(ENROLLMENT).status)
            f.run.assertChargedOnce(before)
            val session = f.request(SESSION)
            assertEquals(200, session.status)
            assertEquals(200, f.history(f.json(session)["accessToken"].asText()).status)
            f.base.assertReleased()
        }
    }

    private fun withFixture(work: (Fixture) -> Unit) {
        withOrdinaryComplaintInstallationEnrollment(database.value) { base ->
            OrdinaryComplaintTestInstallationFixture(base).use { run -> Fixture(base, run).use(work) }
        }
    }

    /** Thin HTTP/owned-row composition only; no alternate database or manually issued token. */
    private class Fixture(val base: OrdinaryComplaintInstallationEnrollmentFixture, val run: OrdinaryComplaintTestInstallationFixture) : AutoCloseable {
        val id: UUID = UUID.randomUUID().also { base.ids.add(it) }
        private val ingress = enrollmentAdmissionTestIngress(base)
        private val jwt = historyTestJwt()
        private val mapper = ObjectMapper()
        private val resources = mutableListOf<UUID>()
        private val exchange = SyntheticInstallationExchangeFixture(run.desired, base.ordinary.ownership, base.jdbc, base.capacity, base.audit, ingress, jwt)
        val handler = ComplaintInstallationHttpHandler(ComplaintInstallationService(exchange), ingress)
        private val historyHandler = ComplaintOwnerHistoryHttpHandler(
            ComplaintOwnerHistoryService(
                ComplaintOwnerHistoryReadAdapter(
                    run.scope,
                    jwt,
                    historyTestCursors(),
                    ComplaintOwnerHistoryPhaseExecutor(base.ordinary.ownership, JdbcComplaintOwnerHistoryStore(base.ordinary.jdbc, run.scope)),
                    ingress,
                ),
            ),
            ingress,
        )

        fun request(
            path: String,
            id: UUID = this.id,
            secret: ByteArray = ByteArray(32) { it.toByte() },
            platform: String = "ANDROID",
            scope: ComplaintDataScope = run.scope,
        ): MockHttpServletResponse = MockHttpServletResponse().also {
            handler.handleRequest(input(path, id, secret, platform, scope), it)
            base.assertReleased()
        }

        fun input(
            path: String,
            id: UUID = this.id,
            secret: ByteArray = ByteArray(32) { it.toByte() },
            platform: String = "ANDROID",
            scope: ComplaintDataScope = run.scope,
        ): MockHttpServletRequest {
            base.ids.addIfAbsent(id)
            return MockHttpServletRequest("POST", path).apply {
                remoteAddr = "192.0.2.1"
                contentType = "application/json"
                setContent(
                    mapper.writeValueAsBytes(
                        linkedMapOf<String, String>().apply {
                            put("installationId", id.toString())
                            put("secret", Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
                            put("expectedDataScopeId", scope.id.toString())
                            if (path == ENROLLMENT) put("platform", platform)
                        },
                    ),
                )
            }
        }

        fun history(token: String): MockHttpServletResponse = MockHttpServletResponse().also {
            historyHandler.handleRequest(historyTestRequest(token), it)
            base.assertReleased()
        }

        fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsByteArray)

        fun assertIssued(
            response: MockHttpServletResponse,
            databaseTime: Instant = checkNotNull(
                base.observer.queryForObject("SELECT last_authenticated_at FROM app_installations WHERE id = ?", Timestamp::class.java, id),
            ).toInstant(),
        ) {
            val data = json(response)
            val verified = jwt.verify(data["accessToken"].asText())
            assertEquals(id, verified.installation.id)
            assertEquals(run.scope, verified.installation.scope)
            assertEquals(data["credentialVersion"].asLong(), verified.credentialVersion)
            val at = Instant.parse(data["issuedAt"].asText())
            // Enrollment supplies the observed SQL sample; refresh uses its persisted authentication time.
            assertEquals(databaseTime.truncatedTo(ChronoUnit.SECONDS), at)
            assertEquals(verified.issuedAt, at)
            assertEquals(at.plusSeconds(900), verified.expiresAt)
            assertEquals(900L, data["expiresInSeconds"].asLong())
            assertTrue(data["accessToken"].asText().length + 7 <= 4096)
            base.assertReleased()
        }

        fun assertProblem(response: MockHttpServletResponse, status: Int, code: String) {
            assertEquals(status, response.status)
            val problem = json(response)
            assertEquals(setOf("type", "title", "status", "errors"), problem.fieldNames().asSequence().toSet())
            assertEquals(1, problem["errors"].size())
            assertEquals(code, problem["errors"][0]["code"].asText())
            assertFalse(response.contentAsString.contains("accessToken"))
            assertNull(response.getHeader("WWW-Authenticate"))
            assertNull(response.getHeader("Location"))
            assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
            assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        }

        fun content(): UUID {
            val resource = UUID.randomUUID().also { resources.add(it) }
            val at = Timestamp.from(base.ordinary.cutoff)
            assertEquals(
                1,
                base.observer.update(
                    "INSERT INTO complaint_resource_ids (id, data_scope_id, test_only, state, created_at) VALUES (?, ?, true, 'LIVE', ?)",
                    resource,
                    run.scope.id,
                    at,
                ),
            )
            assertEquals(
                1,
                base.observer.update(
                    "INSERT INTO complaints (id, data_scope_id, test_only, owner_id, ownership, kind, type, status, subject, body, " +
                        "platform, os_version, manufacturer, device_model, created_at, updated_at, version) " +
                        "VALUES (?, ?, true, ?, 'INSTALLATION', 'REPORT', 'TECHNICAL', 'OPEN', 'Synthetic HTTP report', " +
                        "'Synthetic HTTP body', 'ANDROID', '', '', '', ?, ?, 1)",
                    resource,
                    run.scope.id,
                    id,
                    at,
                    at,
                ),
            )
            return resource
        }

        override fun close() {
            base.assertReleased()
            resources.forEach { base.observer.update("DELETE FROM complaints WHERE id = ?", it) }
            resources.forEach { base.observer.update("DELETE FROM complaint_resource_ids WHERE id = ?", it) }
        }
    }

    private enum class Fault { ROLLBACK, COMMIT, TAIL }

    private companion object {
        const val ENROLLMENT = "/api/v1/installations"
        const val SESSION = "/api/v1/installations/session"
    }
}
