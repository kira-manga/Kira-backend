package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.servlet.ServletOutputStream
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryPosition
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryRejected
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerHistoryStore
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.historyTestRequest
import me.manga.kira.backend.support.JwtTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.dao.DataAccessException
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID

/** Actual dormant handler/service/JWT/guarded SQL, not an enabled Spring chain or production authority. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintOwnerHistoryIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintOwnerHistoryIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `genuine enrollment session and installation JWT drive the actual empty handler without history writes`() = withFixture { f ->
        assertEquals(f.actor, f.jwt.verify(f.token.value).installation)
        val before = f.state()
        var connectionFreeSend = false
        val response = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream {
                requireConnectionFree()
                connectionFreeSend = true
                return super.getOutputStream()
            }
        }
        f.handler.handleRequest(historyTestRequest(f.token.value), response)
        assertEquals(200, response.status)
        assertTrue(connectionFreeSend)
        assertEquals("{\"notices\":[],\"items\":[],\"nextCursor\":null}", response.contentAsString)
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        assertNull(response.getHeader("ETag"))
        assertNull(response.getHeader("Location"))
        f.assertReleased()
        assertEquals(before, f.state())
    }

    @Test
    fun `fifty fifty one and one hundred tied rows use exact owner keysets and first page scoped notices`() = withFixture { f ->
        val owned = MutableList(50) { f.content() }
        val notice = f.notice()
        val foreign = f.enrolledSession()
        val hidden = listOf(
            f.content(owner = foreign.installation),
            f.content(resourceState = "DELETION_PENDING"),
            f.notice(ComplaintDataScope.LIVE),
            f.legacy(),
        )
        val fifty = f.request()
        assertEquals(200, fifty.status)
        assertEquals(owned.sortedByDescending(UUID::toString), f.itemIds(fifty))
        assertNull(f.cursor(fifty))
        assertEquals(notice.toString(), f.json(fifty)["notices"].single()["id"].asText())
        hidden.forEach { assertFalse(fifty.contentAsString.contains(it.toString())) }

        owned.add(f.content())
        val first = f.request()
        val cursor = checkNotNull(f.cursor(first))
        val position = f.cursors.decode(cursor, f.actor, 50)
        assertEquals(f.itemIds(first).last(), position.id) // Last returned, not the lookahead row.
        assertEquals(f.ordinary.cutoff, position.createdAt)
        val tail = f.request(query = "limit=50&cursor=$cursor")
        assertEquals(200, tail.status)
        assertEquals(owned.sortedByDescending(UUID::toString), f.itemIds(first) + f.itemIds(tail))
        assertEquals(1, f.itemIds(tail).size)
        assertTrue(f.json(tail)["notices"].isEmpty)
        assertNull(f.cursor(tail))
        val foreignToken = f.jwt.issue(foreign.installation, foreign.credentialVersion, foreign.issuedAt).value
        assertEquals(400, f.request(foreignToken, "limit=50&cursor=$cursor").status)
        assertEquals(400, f.request(query = "limit=49&cursor=$cursor").status)

        repeat(49) { owned.add(f.content()) }
        val before = f.state()
        val hundredFirst = f.request()
        val hundredTail = f.request(query = "limit=50&cursor=${checkNotNull(f.cursor(hundredFirst))}")
        assertEquals(50, f.itemIds(hundredFirst).size)
        assertEquals(50, f.itemIds(hundredTail).size)
        assertEquals(owned.sortedByDescending(UUID::toString), f.itemIds(hundredFirst) + f.itemIds(hundredTail))
        assertNull(f.cursor(hundredTail))
        assertTrue(f.json(hundredTail)["notices"].isEmpty)
        assertEquals(before, f.state())
        f.assertReleased()
    }

    @Test
    fun `ordinary and notice replies survive permanent parent deletion without joining erased content`() = withFixture { f ->
        val parent = f.content(body = "Synthetic erased parent sentinel")
        val reply = f.content(parent = parent)
        val key = "history.fixture.notice-reply"
        val notice = f.notice(key = key)
        val noticeReply = f.content(parent = notice, key = key)
        f.eraseParent(parent)
        val before = f.state()
        val response = f.request()
        assertEquals(200, response.status)
        assertEquals(setOf(reply, noticeReply), f.itemIds(response).toSet())
        val items = f.json(response)["items"].associateBy { it["id"].asText() }
        assertEquals(parent.toString(), items.getValue(reply.toString())["replyToId"].asText())
        assertFalse(items.getValue(reply.toString()).has("noticeKey"))
        val linked = items.getValue(noticeReply.toString())
        assertEquals(notice.toString(), linked["replyToId"].asText())
        assertEquals(key, linked["noticeKey"].asText())
        assertEquals("CUSTOM", linked["type"].asText())
        assertTrue(linked["subject"].isNull)
        assertFalse(response.contentAsString.contains("Synthetic erased parent sentinel"))
        assertFalse(response.contentAsString.contains(f.actor.id.toString()))
        assertEquals(before, f.state())
    }

    @Test
    fun `signed claims never bypass missing stale pending retired wrong scope or inactive run checks`() = withFixture { f ->
        f.content()
        val missing = f.jwt.issue(ScopedInstallationId(UUID.randomUUID(), f.run.scope), 1, Instant.now()).value
        val wrongScope = f.jwt.issue(ScopedInstallationId(f.actor.id, ComplaintDataScope.of(UUID.randomUUID())), 1, Instant.now()).value
        for (token in listOf(missing, wrongScope, JwtTestSupport.mint(f.ordinary.userId), JwtTestSupport.mint(f.ordinary.userId, role = "ADMIN"))) {
            assertEquals(401, f.request(token).status)
        }
        assertThrows<IllegalArgumentException> { JdbcComplaintOwnerHistoryStore(f.ordinary.jdbc, ComplaintDataScope.LIVE) }
        assertEquals(1, f.observer.update("UPDATE app_installations SET credential_version = 2 WHERE id = ?", f.actor.id))
        assertEquals(401, f.request().status)
        assertEquals(1, f.observer.update("UPDATE app_installations SET credential_version = 1, state = 'DELETION_PENDING' WHERE id = ?", f.actor.id))
        assertEquals(401, f.request().status)
        assertEquals(1, f.observer.update("UPDATE app_installations SET state = 'ACTIVE' WHERE id = ?", f.actor.id))
        for (state in listOf("DELETION_PENDING", "RECOVERY_RESERVED", "RETIRED", "DELETED")) {
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE complaint_installation_ids SET state = ?, terminal_at = CASE WHEN ? IN ('RETIRED','DELETED') THEN now() END WHERE id = ?",
                    state, state, f.actor.id,
                ),
            )
            val response = f.request()
            assertEquals(401, response.status)
            assertEquals("Bearer realm=\"kira-complaints\"", response.getHeader("WWW-Authenticate"))
            assertFalse(response.contentAsString.contains("items"))
        }
        assertEquals(1, f.observer.update("UPDATE complaint_installation_ids SET state = 'ACTIVE', terminal_at = NULL WHERE id = ?", f.actor.id))
        for (state in listOf("SEALED", "PURGING", "PURGED")) {
            f.run.terminalState(state)
            assertEquals(401, f.request().status)
        }
        f.assertReleased()
    }

    @Test
    fun `page observation rechecks credential identity and run after real authentication preflight released`() = withFixture { f ->
        f.content()
        val mutations = listOf(
            "UPDATE app_installations SET credential_version = 2 WHERE id = ?" to
                "UPDATE app_installations SET credential_version = 1 WHERE id = ?",
            "UPDATE app_installations SET state = 'DELETION_PENDING' WHERE id = ?" to
                "UPDATE app_installations SET state = 'ACTIVE' WHERE id = ?",
            "UPDATE complaint_installation_ids SET state = 'DELETION_PENDING' WHERE id = ?" to
                "UPDATE complaint_installation_ids SET state = 'ACTIVE' WHERE id = ?",
        )
        for ((change, restore) in mutations) {
            f.ingress.withIngress(historyTestRequest(f.token.value)) { context ->
                val authenticated = f.reader.authenticate(context, f.token.value, ComplaintOwnerHistoryQuery(50, null))
                f.assertReleased()
                assertEquals(1, f.observer.update(change, f.actor.id))
                val before = f.state()
                denied { f.reader.read(context, authenticated) }
                assertEquals(before, f.state())
            }
            assertEquals(1, f.observer.update(restore, f.actor.id))
            assertEquals(200, f.request().status)
        }
        f.ingress.withIngress(historyTestRequest(f.token.value)) { context ->
            val authenticated = f.reader.authenticate(context, f.token.value, ComplaintOwnerHistoryQuery(50, null))
            f.run.terminalState("SEALED")
            denied { f.reader.read(context, authenticated) }
        }
        f.assertReleased()
    }

    @Test
    fun `only the exact owner live ingress and caller may consume an authenticated attempt once`() = withFixture { f ->
        val id = f.content()
        f.ingress.withIngress(historyTestRequest(f.token.value)) { context ->
            val authenticated = f.reader.authenticate(context, f.token.value, ComplaintOwnerHistoryQuery(50, null))
            denied { f.reader.read(context, object : ComplaintOwnerHistoryAuthentication {}) }
            denied { f.reader.read(ComplaintIngressContext(), authenticated) }
            denied { f.newReader().read(context, authenticated) }
            OwnedCallerTestScope().use { callers ->
                callers.launch { denied { f.reader.read(context, authenticated) } }.value()
            }
            assertEquals(listOf(id), f.reader.read(context, authenticated).items.map { it.id })
            denied { f.reader.read(context, authenticated) }
        }
        val stale = f.ingress.withIngress(historyTestRequest(f.token.value)) { context ->
            context to f.reader.authenticate(context, f.token.value, ComplaintOwnerHistoryQuery(50, null))
        }
        assertThrows<ComplaintAdmissionRejected> { f.reader.read(stale.first, stale.second) }
        f.assertReleased()
    }

    @Test
    fun `fixed history operations require exact named phase resource completion commit and physical release`() = withFixture { f ->
        f.content()
        assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, assertThrows<PersistencePhaseException> { f.store.authenticate(f.identity()) }.code)
        f.withPhase(enter = f.ordinary.ownership::enterComplaintInstallationSessionPreflight) { phase ->
            assertThrows<PersistencePhaseException> { f.store.authenticate(f.identity()) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        f.withPhase { phase ->
            val foreign = JdbcComplaintOwnerHistoryStore(f.ordinary.foreignTemplate(), f.run.scope)
            assertThrows<PersistencePhaseException> { foreign.page(f.identity(), null, 50) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        f.withPhase { phase ->
            phase.ownerHistory.requirePage(f.ordinary.jdbc)
            assertFalse(phase.ownerHistory.completed())
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        val repeated = f.withPhase { phase ->
            val operation = f.store.page(f.identity(), null, 50)
            assertThrows<PersistencePhaseException> { f.store.page(f.identity(), null, 50) }
            assertThrows<PersistencePhaseException> { phase.commit() }
            operation
        }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, assertThrows<PersistencePhaseException> { repeated.result }.databaseOutcome)
        for (authentication in listOf(true, false)) {
            val committed = f.withPhase(
                enter = if (authentication) {
                    f.ordinary.ownership::enterComplaintOwnerHistoryAuthentication
                } else {
                    f.ordinary.ownership::enterComplaintOwnerHistoryPage
                },
            ) { phase ->
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                assertEquals("on", f.ordinary.jdbc.queryForObject("SHOW transaction_read_only", String::class.java))
                val operation = if (authentication) f.store.authenticate(f.identity()) else f.store.page(f.identity(), null, 50)
                assertTrue(phase.ownerHistory.completed())
                val early = assertThrows<PersistencePhaseException> { operation.result }
                assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
                assertFalse(early.cleanupProven)
                phase.commit()
                val beforeRelease = assertThrows<PersistencePhaseException> { operation.result }
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, beforeRelease.databaseOutcome)
                assertFalse(beforeRelease.cleanupProven)
                operation
            }
            assertTrue(committed.result.authorized)
            OwnedCallerTestScope().use { callers ->
                val failure = callers.launch { assertThrows<PersistencePhaseException> { committed.result } }.value()
                assertTrue(failure.cleanupProven)
            }
            assertThrows<PersistencePhaseException> { committed.result }
        }
        f.assertReleased()
    }

    @Test
    fun `real SQL rollback and commit tail failure cannot publish retained page success`() = withFixture { f ->
        f.content()
        val before = f.state()
        for (sqlFailure in listOf(true, false)) {
            val retained = f.withPhase { phase ->
                val operation = f.store.page(f.identity(), null, 50)
                if (sqlFailure) {
                    phase.recordFailure(assertThrows<DataAccessException> { f.ordinary.jdbc.execute("SELECT 1 / 0") })
                } else {
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit(): Unit = throw SyntheticInstallationEnrollmentFailure()
                    })
                }
                val failure = runCatching { phase.commit() }.exceptionOrNull()
                assertNotNull(failure)
                phase.recordFailure(checkNotNull(failure))
                operation
            }
            val rejected = assertThrows<PersistencePhaseException> { retained.result }
            assertEquals(if (sqlFailure) PersistenceDatabaseOutcome.ROLLED_BACK else PersistenceDatabaseOutcome.COMMITTED, rejected.databaseOutcome)
            assertTrue(rejected.cleanupProven)
            assertNull(rejected.cause)
            assertTrue(rejected.suppressed.isEmpty())
        }
        assertEquals(before, f.state())
    }

    @Test
    fun `invalid JWT and cursor refuse before unavailable SQL admission and admission refuses retained SQL`() = withFixture { f ->
        val cursor = f.cursors.encode(f.actor, 50, ComplaintOwnerHistoryPosition(f.ordinary.cutoff, UUID.randomUUID()))
        val expired = f.jwt.issue(f.actor, 1, Instant.now().minusSeconds(1000)).value
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val held = callers.launch {
                val permit = checkNotNull(f.ordinary.admission.tryComplaintBoundary())
                try {
                    gate.hold()
                } finally {
                    assertTrue(permit.releaseAfterQuiescence())
                }
                true
            }
            gate.awaitEntered()
            try {
                assertEquals(503, f.request().status) // Same real token reaches the unavailable named phase.
                for (token in listOf(expired, JwtTestSupport.tamperSignature(f.token.value), JwtTestSupport.mint(f.ordinary.userId))) {
                    assertEquals(401, f.request(token).status)
                }
                assertEquals(400, f.request(query = "limit=50&cursor=${JwtTestSupport.tamperSignature(cursor)}").status)
                assertEquals(0L, f.ordinary.ownedPool.lifecycle.activeAcquisitions())
            } finally {
                gate.release()
                held.value()
            }
        }
        f.ingress.withIngress(historyTestRequest(f.token.value)) { context ->
            f.ingress.startOwnerHistory(context)
            f.withPhase(enter = f.ordinary.ownership::enterComplaintOwnerHistoryAuthentication) { phase ->
                f.store.authenticate(f.identity())
                val refusal = assertThrows<PersistencePhaseException> { f.ingress.chargeOwnerHistory(context, f.actor, Any()) }
                assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, refusal.code)
                phase.commit()
            }
        }
        f.assertReleased()
    }

    @Test
    fun `seventeenth notice and corrupt nullable text close only dormant responses before any success prefix`() {
        withFixture { f ->
            repeat(17) { f.notice() }
            val before = f.state()
            val response = f.request()
            assertEquals(500, response.status)
            assertEquals("INTERNAL_ERROR", f.json(response)["errors"][0]["code"].asText())
            assertFalse(response.contentAsString.contains("items"))
            assertFalse(f.responses.isOpen())
            assertEquals(503, f.request().status)
            assertEquals(before, f.state())
        }
        withFixture { f ->
            val id = f.content()
            val definition = f.observer.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'complaints'::regclass AND conname = 'chk_complaints_text'",
                String::class.java,
            )!!
            f.observer.execute("ALTER TABLE complaints DROP CONSTRAINT chk_complaints_text")
            try {
                assertEquals(1, f.observer.update("UPDATE complaints SET app_version = ? WHERE id = ?", "a".repeat(4096), id))
                val response = f.request()
                assertEquals(500, response.status) // Oversized optional text cannot silently become appVersion:null.
                assertFalse(response.contentAsString.contains("items"))
                assertFalse(f.responses.isOpen())
            } finally {
                f.observer.update("UPDATE complaints SET app_version = NULL WHERE id = ?", id)
                f.observer.execute("ALTER TABLE complaints ADD CONSTRAINT chk_complaints_text $definition")
            }
        }
    }

    private fun denied(work: () -> Unit): ComplaintOwnerHistoryRejected = assertThrows<ComplaintOwnerHistoryRejected> { work() }.also {
        assertEquals(ComplaintOwnerHistoryFailure.UNAUTHORIZED, it.failure)
        assertNull(it.cause)
    }

    private fun withFixture(work: (ComplaintOwnerHistoryFixture) -> Unit) {
        withOrdinaryComplaintInstallationEnrollment(database.value) { base ->
            OrdinaryComplaintTestInstallationFixture(base).use { run -> ComplaintOwnerHistoryFixture(base, run).use(work) }
        }
    }
}
