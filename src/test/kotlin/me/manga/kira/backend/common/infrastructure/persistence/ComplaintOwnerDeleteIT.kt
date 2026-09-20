package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.web.RequestBodySizeLimitFilter
import me.manga.kira.backend.complaint.api.ComplaintInstallationHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintInstallationMeHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryHttpHandler
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditTuple
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBearerAuthenticator
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAuthorizationOperation
import me.manga.kira.backend.complaint.infrastructure.InstallationHttpPrincipal
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.errorReply
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintInstallationSecurityChainFactory
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.security.complaintSpringSecurityContext
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.FilterChainProxy
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real enrollment/session/create/reply, released SQL and raw TEST SDK publication. The run, catalog,
 * controls and checkpoint comparisons are synthetic lower fixtures, NOT registered/current TEST authority.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Suppress("LargeClass")
class ComplaintOwnerDeleteIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintOwnerDeleteIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `explicitly paired lower DELETE and status use the real session token and write an empty 204 only after release`() = withFixture { f ->
        val report = report(f)
        val attempt = f.attempt(report.id)
        val wire = f.wire(attempt)
        val before = f.state()
        val counters = f.counters()
        wire.factory(f.store, f.lanes).use { publishers ->
            val http = f.http(publishers)
            for (enabled in listOf(false, true)) {
                val bridge = ComplaintHttpIngressBridge(f.ingress)
                val installations = mock(ComplaintInstallationHttpHandler::class.java)
                val me = mock(ComplaintInstallationMeHttpHandler::class.java)
                val history = mock(ComplaintOwnerHistoryHttpHandler::class.java)
                val authentication = ComplaintInstallationBearerAuthenticator(f.scope, f.creator.jwt, f.creator.historyPhases, f.ingress)
                val factory = ComplaintInstallationSecurityChainFactory(
                    bridge, authentication, installations, me, history,
                    if (enabled) http.creates else f.creator.handler(f.ingress),
                    edit = if (enabled) http.edits else null,
                    delete = if (enabled) http.deletes else null,
                )
                val users = mock(UserRepository::class.java)
                complaintSpringSecurityContext(factory, users).use { spring ->
                    val chain = spring.getBean(FilterChainProxy::class.java)
                    fun send(request: MockHttpServletRequest): MockHttpServletResponse {
                        request.servletPath = requireNotNull(request.requestURI)
                        var wroteStatus = false
                        var streams = 0
                        val response = object : MockHttpServletResponse() {
                            override fun setStatus(sc: Int) {
                                f.assertReleased()
                                wroteStatus = true
                                super.setStatus(sc)
                            }

                            override fun getOutputStream(): ServletOutputStream {
                                f.assertReleased()
                                streams++
                                return super.getOutputStream()
                            }
                        }
                        val dispatch = FilterChain { routed, output ->
                            f.assertReleased()
                            val principal = SecurityContextHolder.getContext().authentication.principal as InstallationHttpPrincipal
                            assertEquals(f.creator.actor, principal.installation)
                            assertNull(CurrentUser().getOrNull())
                            factory.handler.handleRequest(routed as HttpServletRequest, output as HttpServletResponse)
                        }
                        bridge.doFilter(request, response) { admitted, output ->
                            RequestBodySizeLimitFilter(ObjectMapper()).doFilter(admitted, output) { bounded, result -> chain.doFilter(bounded, result, dispatch) }
                        }
                        assertTrue(wroteStatus)
                        if (response.status == 204) assertEquals(0, streams, "Even an empty representation writer is forbidden for 204")
                        assertNull(SecurityContextHolder.getContext().authentication)
                        return response
                    }
                    if (enabled) {
                        applied(send(f.input(attempt)))
                        appliedStatus(send(f.statusInput(attempt)))
                    } else {
                        f.creator.problem(send(f.input(attempt)), 404, "NOT_FOUND")
                        f.creator.problem(send(f.statusInput(attempt)), 400, "VALIDATION_FAILED")
                        assertEquals(before, f.state())
                        assertTrue(wire.requests.isEmpty())
                    }
                }
                verifyNoInteractions(users, installations, me, history)
            }
        }
        f.assertCounterDelta(counters, OwnerDeleteLiteralCharges.authorization, OwnerDeleteLiteralCharges.promise,
            OwnerDeleteLiteralCharges.ordinaryApply, OwnerDeleteLiteralCharges.content)
        wire.assertClientsClosed()
    }

    @Test
    fun `one owned report or reply is erased while children notice linkage other owner and installation credentials remain byte exact`() = withFixture { f ->
        val parent = report(f)
        val reply = f.creator.replyAttempt(parent.id)
        assertEquals(201, f.creator.reply(reply).status)
        val notice = f.creator.replyNotice() // Synthetic notice seed; its owned reply is genuinely produced.
        val noticeReply = f.creator.replyAttempt(notice)
        assertEquals(201, f.creator.reply(noticeReply).status)
        val otherToken = f.creator.json(f.creator.exchange(UUID.randomUUID(), session = false))["accessToken"].asText()
        val foreign = f.creator.attempt()
        assertEquals(201, f.creator.create(foreign, bearer = otherToken).status)
        val identities = f.rows("complaint_installation_ids")
        val credentials = f.rows("app_installations")
        val untouched = listOf(notice, foreign.id).associateWith { content(f, it) }
        for (id in listOf(parent.id, reply.id, noticeReply.id)) {
            val protected = (listOf(reply.id, noticeReply.id) - id).filter { present(f, it) }.associateWith { content(f, it) }
            val before = f.counters()
            val attempt = f.attempt(id)
            val wire = f.wire(attempt)
            wire.factory(f.store, f.lanes).use { publishers ->
                val http = f.http(publishers)
                f.observations.clear()
                applied(f.delete(attempt, http))
                val phases = f.observations.map { it.second.phase }.distinct()
                assertEquals(5, phases.size, "Authentication, preflight, AUTHORIZE, VERIFY and APPLY are distinct released phases")
                assertFalse(present(f, id))
                assertEquals("DELETED", f.scalar("SELECT state FROM complaint_resource_ids WHERE id = ?", id))
                assertEquals(identities, f.rows("complaint_installation_ids"))
                assertEquals(credentials, f.rows("app_installations"))
                (untouched + protected).forEach { (kept, bytes) -> assertEquals(bytes, content(f, kept)) }
                assertEquals(2, f.observer.queryForObject(
                    "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND entity_id = ? " +
                        "AND action IN ('COMPLAINT_DELETE_AUTHORIZED', 'COMPLAINT_DELETED') AND actor_user_id IS NULL " +
                        "AND complaint_actor_kind = 'INSTALLATION' AND detail = '{\"version\":1}'::jsonb",
                    Int::class.java, f.scope.id, id.toString(),
                ))
                assertPartial(f, wire.event.route.eventId, 393216)
                val done = f.state()
                appliedStatus(f.status(attempt, http))
                applied(f.delete(attempt, http))
                assertEquals(done, f.state())
                assertEquals(1, wire.requests.count { it.kind == "PUT" })
            }
            f.assertCounterDelta(before, OwnerDeleteLiteralCharges.authorization, OwnerDeleteLiteralCharges.promise,
                OwnerDeleteLiteralCharges.ordinaryApply, OwnerDeleteLiteralCharges.content)
            wire.assertClientsClosed()
        }
        assertEquals(2, f.rows("complaints").size)
    }

    @Test
    fun `missing foreign NOTICE stale and pending targets retain only one normal receipt and never call a provider`() = withFixture { f ->
        val report = report(f)
        val notice = f.creator.replyNotice()
        val pending = f.creator.content(pending = true) // Business-state comparison only; not a deletion/PREPARED capability.
        val otherToken = f.creator.json(f.creator.exchange(UUID.randomUUID(), session = false))["accessToken"].asText()
        val foreign = f.creator.attempt()
        assertEquals(201, f.creator.create(foreign, bearer = otherToken).status)
        val cases = listOf(
            Triple(f.attempt(UUID.randomUUID()), 404, "COMPLAINT_NOT_FOUND"),
            Triple(f.attempt(foreign.id), 404, "COMPLAINT_NOT_FOUND"),
            Triple(f.attempt(notice), 404, "COMPLAINT_NOT_FOUND"),
            Triple(f.attempt(report.id, version = 2), 412, "PRECONDITION_FAILED"),
            Triple(f.attempt(pending), 409, "COMPLAINT_DELETION_PENDING"),
        )
        for ((attempt, status, code) in cases) {
            val before = f.state()
            val counters = f.counters()
            val wire = f.wire(attempt)
            wire.factory(f.store, f.lanes).use { publishers ->
                val http = f.http(publishers)
                val rejection = f.delete(attempt, http)
                f.creator.problem(rejection, status, code)
                assertArrayEquals(rejection.contentAsByteArray, f.delete(attempt, http).contentAsByteArray)
                val observed = f.status(attempt, http)
                assertEquals(200, observed.status)
                assertEquals("{\"outcome\":\"REJECTED\",\"originalStatus\":$status,\"problemCode\":\"$code\"}", observed.contentAsString)
                val after = f.state()
                for (name in listOf("identities", "credentials", "resources", "content", "publications", "reservations", "applied", "audit")) {
                    assertEquals(before.getValue(name), after.getValue(name), name)
                }
                assertEquals(before.getValue("receipts").size + 1, after.getValue("receipts").size)
                assertTrue(wire.requests.isEmpty())
                assertTrue(wire.kms.requests.isEmpty())
                assertEquals(0, wire.s3ClientsCreated)
            }
            f.assertCounterDelta(counters, actual = OwnerDeleteLiteralCharges.receipt)
        }
        val attempt = f.attempt(report.id)
        val wire = f.wire(attempt)
        wire.factory(f.store, f.lanes).use { publishers ->
            val http = f.http(publishers)
            val before = f.state()
            for ((request, status, code) in listOf(
                Triple(f.input(attempt).apply { removeHeader("If-Match") }, 428, "PRECONDITION_REQUIRED"),
                Triple(f.input(attempt).apply { removeHeader("If-Match"); addHeader("If-Match", "*") }, 412, "PRECONDITION_FAILED"),
                Triple(f.input(attempt).apply { setContent("{}".toByteArray()) }, 400, "VALIDATION_FAILED"),
                Triple(f.input(attempt, "invalid-token"), 401, "UNAUTHORIZED"),
            )) {
                f.creator.problem(f.send(request, http), status, code)
                assertEquals(before, f.state())
            }
            assertTrue(wire.requests.isEmpty())
        }
    }

    @Test
    fun `revoked identity credential or platform after released preflight rolls authorization back before target discovery`() = withFixture { f ->
        val report = report(f)
        val actor = f.creator.actor.id
        val changes = listOf(
            "UPDATE complaint_installation_ids SET state = 'DELETION_PENDING' WHERE id = '$actor'" to
                "UPDATE complaint_installation_ids SET state = 'ACTIVE' WHERE id = '$actor'",
            "UPDATE app_installations SET state = 'DELETION_PENDING' WHERE id = '$actor'" to
                "UPDATE app_installations SET state = 'ACTIVE' WHERE id = '$actor'",
            "UPDATE app_installations SET credential_version = 2 WHERE id = '$actor'" to
                "UPDATE app_installations SET credential_version = 1 WHERE id = '$actor'",
            "UPDATE app_installations SET platform = 'IOS' WHERE id = '$actor'" to
                "UPDATE app_installations SET platform = 'ANDROID' WHERE id = '$actor'",
        )
        for ((change, restore) in changes) {
            val attempt = f.attempt(report.id)
            val wire = f.wire(attempt)
            val before = f.state()
            val changed = AtomicBoolean()
            f.observations.clear()
            requireConnectionFree()
            // Preopen a raw observer; JdbcTemplate would enlist a foreign Spring holder inside the deletion phase.
            val revocationObserver = checkNotNull(f.observer.dataSource).connection
            revocationObserver.use { observer ->
                assertTrue(observer.autoCommit)
                f.beforeStep = { step ->
                    if (step == OwnerDeleteFixtureStep.CONTROL && changed.compareAndSet(false, true)) {
                        assertEquals(0, f.base.ordinary.admission.activeOwners())
                        assertTrue(f.observations.filter { it.first in setOf(OwnerDeleteFixtureStep.AUTH, OwnerDeleteFixtureStep.PREFLIGHT) }
                            .all { it.second.lease.completion.quiescent() })
                        observer.createStatement().use { statement -> assertEquals(1, statement.executeUpdate(change)) }
                    }
                }
                try {
                    wire.factory(f.store, f.lanes).use { f.creator.problem(f.delete(attempt, f.http(it)), 401, "UNAUTHORIZED") }
                    assertTrue(changed.get())
                    assertFalse(f.observations.any { it.first == OwnerDeleteFixtureStep.CANDIDATE })
                    assertTrue(wire.requests.isEmpty())
                } finally {
                    f.beforeStep = {}
                    if (changed.get()) assertEquals(1, f.observer.update(restore))
                }
            }
            assertTrue(revocationObserver.isClosed)
            assertEquals(before, f.state(), "Compare rollback only AFTER restoring the independently committed revocation")
        }
    }

    @Test
    fun `completed replay and PREPARED or VERIFIED continuation bypass an exhausted shared edit delete quota without bypassing current auth`() {
        for (state in listOf("PREPARED", "VERIFIED")) withFixture { f ->
            val first = f.attempt(report(f).id)
            val firstWire = f.wire(first)
            firstWire.factory(f.store, f.lanes).use { applied(f.delete(first, f.http(it))) }
            val pending = f.attempt(report(f).id)
            val wire = f.wire(pending)
            wire.factory(f.store, f.lanes).use { publishers ->
                val http = f.http(publishers)
                val work = f.prepared(pending, publishers)
                if (state == "VERIFIED") f.phases.verify(publishers.reserve().use { it.publish(work) })
                // Real LOWER admissions only: never describe these 58 members as 58 paid SQL mutations.
                repeat(58) { index ->
                    val value = f.attempt(pending.id)
                    f.ingress.withIngress(f.input(value)) { context ->
                        if (index % 2 == 0) {
                            f.ingress.startOwnerDelete(context)
                            f.ingress.admitOwnerDelete(context, value.candidate.tuple)
                        } else {
                            f.ingress.startOwnerEdit(context)
                            val tuple = value.candidate.tuple
                            f.ingress.admitOwnerEdit(context, ComplaintOwnerEditTuple(tuple.installation, tuple.key, tuple.targetId, tuple.fingerprintBytes()))
                        }
                    }
                }
                assertEquals(60, semanticEvents(f))
                wire.respond = { errorReply(503) }
                val before = f.state()
                val requests = wire.requests.size
                applied(f.delete(first, http))
                appliedStatus(f.status(first, http))
                f.creator.problem(f.status(pending, http), 404, "OPERATION_NOT_FOUND")
                f.creator.problem(f.delete(f.attempt(pending.id), http), 429, "RATE_LIMITED")
                assertEquals(requests, wire.requests.size)
                assertEquals(before, f.state())
                if (state == "PREPARED") {
                    f.creator.problem(f.delete(pending, http), 503, "SERVICE_UNAVAILABLE")
                    assertEquals(before, f.state())
                    wire.respond = wire::statefulReply
                    applied(f.delete(pending, http))
                } else {
                    applied(f.delete(pending, http))
                    assertEquals(requests, wire.requests.size, "Persisted VERIFIED must not reopen any provider")
                }
                assertEquals(60, semanticEvents(f))
                val done = f.state()
                assertEquals(1, f.observer.update("UPDATE app_installations SET credential_version = 2 WHERE id = ?", f.creator.actor.id))
                try {
                    f.creator.problem(f.delete(first, http), 401, "UNAUTHORIZED")
                    f.creator.problem(f.status(pending, http), 401, "UNAUTHORIZED")
                } finally {
                    assertEquals(1, f.observer.update("UPDATE app_installations SET credential_version = 1 WHERE id = ?", f.creator.actor.id))
                }
                assertEquals(done, f.state())
            }
            wire.assertClientsClosed()
        }
    }

    @Test
    fun `historical tag replay is exact completed expiry is eight days and old nonterminal authorization never age expires`() = withFixture { f ->
        val created = report(f)
        val attempt = f.attempt(created.id)
        val wire = f.wire(attempt)
        wire.factory(f.store, f.lanes).use { publishers ->
            val http = f.http(publishers)
            applied(f.delete(attempt, http))
            val done = f.state()
            applied(f.delete(attempt, http))
            f.creator.problem(f.delete(f.attempt(attempt.id, version = 2, key = attempt.key), http), 409, "IDEMPOTENCY_KEY_REUSED")
            f.creator.problem(f.delete(f.attempt(attempt.id, key = created.key), http), 409, "IDEMPOTENCY_KEY_REUSED")
            assertEquals(done, f.state())
            assertEquals(true, f.observer.queryForObject(
                "SELECT expires_at = completed_at + interval '192 hours' FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?",
                Boolean::class.java, f.creator.actor.id, attempt.key,
            ))
            assertEquals(1, f.observer.update(
                "UPDATE complaint_idempotency_receipts SET created_at = created_at - interval '9 days', " +
                    "authorized_at = authorized_at - interval '9 days', completed_at = completed_at - interval '9 days', expires_at = expires_at - interval '9 days' " +
                    "WHERE actor_id = ? AND idempotency_key = ?", f.creator.actor.id, attempt.key,
            ))
            val expired = f.state()
            f.creator.problem(f.status(attempt, http), 404, "OPERATION_NOT_FOUND")
            f.creator.problem(f.delete(attempt, http), 503, "SERVICE_UNAVAILABLE")
            assertEquals(expired, f.state(), "An extant expired claim is never replaced or reexecuted")
        }
        val pending = f.attempt(report(f).id)
        val nextWire = f.wire(pending)
        nextWire.factory(f.store, f.lanes).use { publishers ->
            f.prepared(pending, publishers)
            f.existing.transaction { sql ->
                assertEquals(1, sql.update("UPDATE complaint_journal_publications SET created_at = created_at - interval '9 days' WHERE event_id = ?", nextWire.event.route.eventId))
                assertEquals(1, sql.update(
                    "UPDATE complaint_idempotency_receipts SET created_at = created_at - interval '9 days', authorized_at = authorized_at - interval '9 days' " +
                        "WHERE actor_id = ? AND idempotency_key = ?", f.creator.actor.id, pending.key,
                ))
            }
            val old = f.state()
            val http = f.http(publishers)
            f.creator.problem(f.status(pending, http), 404, "OPERATION_NOT_FOUND")
            assertTrue(nextWire.requests.isEmpty())
            assertEquals(old, f.state())
            assertEquals(true, f.observer.queryForObject(
                "SELECT state = 'AUTHORIZED_DELETE' AND expires_at IS NULL FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?",
                Boolean::class.java, f.creator.actor.id, pending.key,
            ))
            applied(f.delete(pending, http))
        }
    }

    @Test
    fun `authorization statement commit and committed tail failures publish nothing and exact retry charges only once`() {
        val points = listOf(
            OwnerDeleteFixtureStep.CLAIM, OwnerDeleteFixtureStep.CHARGE, OwnerDeleteFixtureStep.RESERVATION,
            OwnerDeleteFixtureStep.RUN, OwnerDeleteFixtureStep.CREDENTIAL, OwnerDeleteFixtureStep.CONTENT,
            OwnerDeleteFixtureStep.PENDING, OwnerDeleteFixtureStep.PUBLICATION, OwnerDeleteFixtureStep.AUTHORIZE_RECEIPT, OwnerDeleteFixtureStep.AUDIT,
        )
        for (point in points) withFixture { f ->
            val attempt = f.attempt(report(f).id)
            val wire = f.wire(attempt)
            val before = f.state()
            val counters = f.counters()
            val fired = AtomicBoolean()
            f.afterStep = { step ->
                val isInsert = step != OwnerDeleteFixtureStep.PUBLICATION || f.statements.last().startsWith("INSERT INTO complaint_journal_publications")
                if (step == point && isInsert && fired.compareAndSet(false, true)) throw SyntheticInstallationEnrollmentFailure()
            }
            wire.factory(f.store, f.lanes).use { publishers ->
                val http = f.http(publishers)
                try {
                    f.creator.problem(f.delete(attempt, http), 503, "SERVICE_UNAVAILABLE")
                    assertTrue(fired.get(), point.name)
                    assertEquals(before, f.state(), point.name)
                    assertTrue(wire.requests.isEmpty())
                    assertEquals(0, wire.s3ClientsCreated)
                    assertEquals(0L, f.lanes.activeOwners().totalOwners)
                } finally {
                    f.afterStep = {}
                }
                assertEquals(1, semanticEvents(f))
                applied(f.delete(attempt, http))
                assertEquals(1, semanticEvents(f), "A failed logical attempt retains its original admission member")
            }
            f.assertCounterDelta(counters, OwnerDeleteLiteralCharges.authorization, OwnerDeleteLiteralCharges.promise,
                OwnerDeleteLiteralCharges.ordinaryApply, OwnerDeleteLiteralCharges.content)
        }
        for (mode in listOf("COMMIT", "TAIL")) withFixture { f ->
            val attempt = f.attempt(report(f).id)
            val wire = f.wire(attempt)
            val before = f.state()
            val counters = f.counters()
            val fired = AtomicBoolean()
            f.afterStep = { step ->
                if (step == OwnerDeleteFixtureStep.AUDIT && fired.compareAndSet(false, true)) completionFault(f, mode)
            }
            wire.factory(f.store, f.lanes).use { publishers ->
                val http = f.http(publishers)
                try {
                    f.creator.problem(f.delete(attempt, http), 503, "SERVICE_UNAVAILABLE")
                    assertTrue(fired.get())
                    val original = f.observations.last().second.phase
                    assertEquals(if (mode == "COMMIT") PersistenceDatabaseOutcome.UNKNOWN else PersistenceDatabaseOutcome.COMMITTED, original.databaseOutcome())
                    if (mode == "COMMIT") {
                        assertEquals(before, f.state())
                    } else {
                        assertEquals("PREPARED", f.scalar("SELECT state FROM complaint_journal_publications WHERE event_id = ?", wire.event.route.eventId))
                        f.assertCounterDelta(counters, OwnerDeleteLiteralCharges.authorization, OwnerDeleteLiteralCharges.promise)
                    }
                    assertTrue(wire.requests.isEmpty())
                    assertEquals(0, wire.s3ClientsCreated)
                } finally {
                    f.afterStep = {}
                }
                f.creator.problem(f.status(attempt, http), 404, "OPERATION_NOT_FOUND")
                applied(f.delete(attempt, http))
                assertEquals(1, semanticEvents(f))
            }
            f.assertCounterDelta(counters, OwnerDeleteLiteralCharges.authorization, OwnerDeleteLiteralCharges.promise,
                OwnerDeleteLiteralCharges.ordinaryApply, OwnerDeleteLiteralCharges.content)
        }
    }

    @Test
    fun `VERIFY and APPLY faults never leak effects and restart from genuine PREPARED or VERIFIED without rekeying`() {
        for (point in listOf(
            OwnerDeleteFixtureStep.VERIFY, OwnerDeleteFixtureStep.DELETE_CONTENT, OwnerDeleteFixtureStep.TOMBSTONE,
            OwnerDeleteFixtureStep.APPLIED, OwnerDeleteFixtureStep.SPEND, OwnerDeleteFixtureStep.AUDIT,
            OwnerDeleteFixtureStep.APPLY_PUBLICATION, OwnerDeleteFixtureStep.COMPLETE,
        )) withFixture { f ->
            val attempt = f.attempt(report(f).id)
            val wire = f.wire(attempt)
            val counters = f.counters()
            wire.factory(f.store, f.lanes).use { publishers ->
                val work = f.prepared(attempt, publishers)
                val readback = publishers.reserve().use { it.publish(work) }
                if (point != OwnerDeleteFixtureStep.VERIFY) f.phases.verify(readback)
                val before = f.state()
                val calls = wire.requests.size
                val fired = AtomicBoolean()
                f.afterStep = { step -> if (step == point && fired.compareAndSet(false, true)) throw SyntheticInstallationEnrollmentFailure() }
                val http = f.http(publishers)
                try {
                    f.creator.problem(f.delete(attempt, http), 503, "SERVICE_UNAVAILABLE")
                    assertTrue(fired.get(), point.name)
                    assertEquals(before, f.state(), point.name)
                } finally {
                    f.afterStep = {}
                }
                f.creator.problem(f.status(attempt, http), 404, "OPERATION_NOT_FOUND")
                applied(f.delete(attempt, http))
                assertEquals(1, wire.requests.count { it.kind == "PUT" }, "A retained version is probed, never re-keyed")
                if (point == OwnerDeleteFixtureStep.VERIFY) {
                    assertEquals(listOf("LIST", "GET", "LIST", "GET"), wire.requests.drop(calls).map { it.kind })
                } else {
                    assertEquals(calls, wire.requests.size, "SQL VERIFIED resumes with no provider activity")
                }
                assertPartial(f, wire.event.route.eventId, 393216)
                assertEquals(1, semanticEvents(f))
            }
            f.assertCounterDelta(counters, OwnerDeleteLiteralCharges.authorization, OwnerDeleteLiteralCharges.promise,
                OwnerDeleteLiteralCharges.ordinaryApply, OwnerDeleteLiteralCharges.content)
            wire.assertClientsClosed()
        }
    }

    @Test
    fun `real verification and apply completion failures return 503 while only later exact reads prove the durable state`() {
        for (mode in listOf("VERIFY_TAIL", "APPLY_COMMIT", "APPLY_TAIL")) withFixture { f ->
            val attempt = f.attempt(report(f).id)
            val wire = f.wire(attempt)
            val counters = f.counters()
            wire.factory(f.store, f.lanes).use { publishers ->
                val work = f.prepared(attempt, publishers)
                val readback = publishers.reserve().use { it.publish(work) }
                if (mode != "VERIFY_TAIL") f.phases.verify(readback)
                val before = f.state()
                val fired = AtomicBoolean()
                val point = if (mode == "VERIFY_TAIL") OwnerDeleteFixtureStep.VERIFY else OwnerDeleteFixtureStep.COMPLETE
                f.afterStep = { step ->
                    if (step == point && fired.compareAndSet(false, true)) completionFault(f, if (mode.endsWith("COMMIT")) "COMMIT" else "TAIL")
                }
                val http = f.http(publishers)
                try {
                    f.creator.problem(f.delete(attempt, http), 503, "SERVICE_UNAVAILABLE")
                    assertTrue(fired.get())
                    val original = f.observations.last().second.phase
                    assertEquals(if (mode == "APPLY_COMMIT") PersistenceDatabaseOutcome.UNKNOWN else PersistenceDatabaseOutcome.COMMITTED, original.databaseOutcome())
                } finally {
                    f.afterStep = {}
                }
                if (mode == "APPLY_TAIL") {
                    assertFalse(present(f, attempt.id))
                    appliedStatus(f.status(attempt, http))
                } else {
                    assertTrue(present(f, attempt.id))
                    f.creator.problem(f.status(attempt, http), 404, "OPERATION_NOT_FOUND")
                    assertEquals("VERIFIED", f.scalar("SELECT state FROM complaint_journal_publications WHERE event_id = ?", wire.event.route.eventId))
                    if (mode == "APPLY_COMMIT") assertEquals(before, f.state())
                }
                val calls = wire.requests.size
                wire.respond = { errorReply(503) }
                applied(f.delete(attempt, http))
                assertEquals(calls, wire.requests.size)
                assertEquals(1, semanticEvents(f))
            }
            f.assertCounterDelta(counters, OwnerDeleteLiteralCharges.authorization, OwnerDeleteLiteralCharges.promise,
                OwnerDeleteLiteralCharges.ordinaryApply, OwnerDeleteLiteralCharges.content)
        }
    }

    @Test
    fun `genuinely nonempty original Spring cleanup quarantines committed authorization and cannot issue work even after later reconciliation`() = withFixture { f ->
        val attempt = f.attempt(report(f).id)
        val wire = f.wire(attempt)
        val counters = f.counters()
        wire.factory(f.store, f.lanes).use { publishers ->
            publishers.reserve().use {
                f.admitted(attempt) { identity, preflight, admitted ->
                    val phase = f.existing.ownership.enterComplaintOwnerDeleteAuthorize(admitted, attempt.candidate.tuple)
                    var operation: ComplaintOwnerDeleteAuthorizationOperation? = null
                    val sentinelKey = Any()
                    val sentinel = Any()
                    var bound = false
                    try {
                        try {
                            phase.ownerDelete.bindAuthorize(admitted)
                            phase.begin()
                            operation = f.store.authorize(identity, attempt.candidate, checkNotNull(preflight.platform))
                            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                                override fun afterCommit() {
                                    // Existing containment fault: real original Spring state, never a fabricated release flag.
                                    TransactionSynchronizationManager.bindResource(sentinelKey, sentinel)
                                    bound = true
                                    throw SyntheticInstallationEnrollmentFailure()
                                }
                            })
                            phase.commit()
                        } catch (problem: Throwable) {
                            phase.recordFailure(problem)
                        } finally {
                            phase.finish()
                        }
                        assertTrue(phase.quarantined())
                        assertSame(phase, PersistencePhaseOwnership.current())
                        val retained = checkNotNull(operation)
                        val failure = assertThrows<PersistencePhaseException> { retained.result }
                        assertEquals(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED, failure.code)
                        assertEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
                        assertFalse(failure.cleanupProven)
                        redacted(failure)
                        OwnedCallerTestScope().use { callers ->
                            assertTrue(callers.launch {
                                requireConnectionFree()
                                val foreign = assertThrows<PersistencePhaseException> { retained.result }
                                assertFalse(foreign.cleanupProven)
                                redacted(foreign)
                                true
                            }.value())
                        }
                        assertTrue(wire.requests.isEmpty())
                        assertEquals(0, wire.s3ClientsCreated)
                    } finally {
                        if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(sentinelKey))
                        requireConnectionFree() // Only this original caller can reconcile the real retained quarantine.
                    }
                    val later = assertThrows<PersistencePhaseException> { checkNotNull(operation).result }
                    assertEquals(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED, later.code)
                    assertTrue(later.cleanupProven)
                    redacted(later)
                }
            }
            f.assertReleased()
            val http = f.http(publishers)
            f.creator.problem(f.status(attempt, http), 404, "OPERATION_NOT_FOUND")
            applied(f.delete(attempt, http))
            assertEquals(1, semanticEvents(f))
        }
        f.assertCounterDelta(counters, OwnerDeleteLiteralCharges.authorization, OwnerDeleteLiteralCharges.promise,
            OwnerDeleteLiteralCharges.ordinaryApply, OwnerDeleteLiteralCharges.content)
    }

    @Test
    fun `actual edit before locked delete makes tag stale while committed authorization blocks later edit and wins deletion`() {
        for (winner in listOf("EDIT", "DELETE")) withFixture { f ->
            val attempt = f.attempt(report(f).id)
            val wire = f.wire(attempt)
            val before = f.counters()
            wire.beforePrepare = ::requireConnectionFree // Another registered caller may legitimately hold SQL.
            wire.factory(f.store, f.lanes).use { publishers ->
                val http = f.http(publishers)
                OwnedCallerTestScope().use { callers ->
                    val gate = callers.gate()
                    val first = AtomicBoolean()
                    if (winner == "EDIT") {
                        f.beforeStep = { step -> if (step == OwnerDeleteFixtureStep.CLAIM && first.compareAndSet(false, true)) gate.hold() }
                    } else {
                        wire.beforePrepare = { requireConnectionFree(); if (first.compareAndSet(false, true)) gate.hold() }
                    }
                    val deletion = callers.launch { f.send(f.input(attempt), http) }
                    gate.awaitEntered()
                    try {
                        val edit = f.send(editInput(f, attempt.id, UUID.randomUUID()), http)
                        if (winner == "EDIT") {
                            assertEquals(200, edit.status)
                            assertEquals("\"complaint-${attempt.id}-v2\"", edit.getHeader("ETag"))
                        } else {
                            assertEquals("DELETION_PENDING", f.scalar("SELECT state FROM complaint_resource_ids WHERE id = ?", attempt.id))
                            f.creator.problem(edit, 409, "COMPLAINT_DELETION_PENDING")
                        }
                    } finally {
                        gate.release()
                        f.beforeStep = {}
                    }
                    if (winner == "EDIT") f.creator.problem(deletion.value(), 412, "PRECONDITION_FAILED") else applied(deletion.value())
                }
            }
            f.assertReleased()
            if (winner == "EDIT") {
                assertEquals(2L, f.observer.queryForObject("SELECT version FROM complaints WHERE id = ?", Long::class.java, attempt.id))
                assertTrue(wire.requests.isEmpty())
                f.assertCounterDelta(before, actual = OwnerDeleteLiteralCharges.receipt.scaled(2) + OwnerDeleteLiteralCharges.audit)
            } else {
                assertFalse(present(f, attempt.id))
                f.assertCounterDelta(before, OwnerDeleteLiteralCharges.authorization + OwnerDeleteLiteralCharges.receipt, OwnerDeleteLiteralCharges.promise,
                    OwnerDeleteLiteralCharges.ordinaryApply, OwnerDeleteLiteralCharges.content)
            }
        }
    }

    @Test
    fun `same delete next statement conflict replays one apply while only a real shared receipt claim wait yields retry after one`() {
        withFixture { f ->
            val attempt = f.attempt(report(f).id)
            val wire = f.wire(attempt)
            val counters = f.counters()
            wire.beforePrepare = ::requireConnectionFree
            wire.factory(f.store, f.lanes).use { publishers ->
                val http = f.http(publishers)
                OwnedCallerTestScope().use { callers ->
                    val gate = callers.gate()
                    val first = AtomicBoolean()
                    f.beforeStep = { step -> if (step == OwnerDeleteFixtureStep.CONTROL && first.compareAndSet(false, true)) gate.hold() }
                    val lagging = callers.launch { f.send(f.input(attempt), http) }
                    gate.awaitEntered()
                    try {
                        applied(f.send(f.input(attempt), http))
                        gate.release()
                        applied(lagging.value())
                    } finally {
                        gate.release()
                        f.beforeStep = {}
                    }
                }
                assertEquals(2, f.observations.count { it.first == OwnerDeleteFixtureStep.CLAIM })
                assertEquals(1, f.observations.count { it.first == OwnerDeleteFixtureStep.DELETE_CONTENT })
                assertEquals(1, semanticEvents(f))
                assertEquals(1, wire.requests.count { it.kind == "PUT" })
            }
            f.assertReleased()
            f.assertCounterDelta(counters, OwnerDeleteLiteralCharges.authorization, OwnerDeleteLiteralCharges.promise,
                OwnerDeleteLiteralCharges.ordinaryApply, OwnerDeleteLiteralCharges.content)
        }
        withOwnerDelete(database.value, ordinaryMaximumPoolSize = 3) { f ->
            // The held edit and status/preflight need two real ordinary owners: P=3 yields P-1=2.
            assertEquals(3, f.base.ordinary.pool.ordinaryPoolSize())
            assertEquals(2, f.base.ordinary.admission.ownerLimit)
            val attempt = f.attempt(report(f).id)
            val wire = f.wire(attempt)
            val before = f.counters()
            wire.factory(f.store, f.lanes).use { publishers ->
                val http = f.http(publishers)
                OwnedCallerTestScope().use { callers ->
                    val gate = callers.gate()
                    val first = AtomicBoolean()
                    f.creator.afterStep = { step -> if (step == OwnerCreateFixtureStep.CLAIM && first.compareAndSet(false, true)) gate.hold() }
                    val edit = callers.launch { f.send(editInput(f, attempt.id, attempt.key), http) }
                    gate.awaitEntered()
                    try {
                        assertEquals(1, f.base.ordinary.admission.activeOwners())
                        f.creator.problem(f.send(f.statusInput(attempt), http), 404, "OPERATION_NOT_FOUND")
                        val refused = f.send(f.input(attempt), http)
                        f.creator.problem(refused, 409, "IDEMPOTENCY_IN_PROGRESS")
                        assertEquals("1", refused.getHeader("Retry-After"))
                        assertTrue(wire.requests.isEmpty())
                    } finally {
                        gate.release()
                        f.creator.afterStep = {}
                    }
                    assertEquals(200, edit.value().status)
                }
                f.creator.problem(f.delete(attempt, http), 409, "IDEMPOTENCY_KEY_REUSED")
                assertTrue(f.rows("complaint_journal_publications").isEmpty())
            }
            f.assertReleased()
            f.assertCounterDelta(before, actual = OwnerDeleteLiteralCharges.receipt + OwnerDeleteLiteralCharges.audit)
        }
    }

    @Test
    fun `pending content retains its hundredth slot until actual erasure and authorization needs full hard X plus Y without a prospective refund`() {
        withFixture { f ->
            val attempt = f.attempt(report(f).id)
            // Explicitly synthetic business-count staging, not 99 charged producer successes.
            repeat(99) { f.creator.content(pending = it % 2 == 0) }
            assertEquals(100, f.rows("complaints").size)
            val wire = f.wire(attempt)
            wire.factory(f.store, f.lanes).use { publishers ->
                val http = f.http(publishers)
                f.prepared(attempt, publishers)
                assertEquals(100, f.rows("complaints").size)
                f.creator.problem(f.creator.create(f.creator.attempt()), 409, "COMPLAINT_CAPACITY_REACHED")
                assertEquals(100, f.rows("complaints").size)
                val paid = f.counters()
                applied(f.delete(attempt, http))
                assertEquals(99, f.rows("complaints").size)
                f.assertCounterDelta(paid, spent = OwnerDeleteLiteralCharges.ordinaryApply, refund = OwnerDeleteLiteralCharges.content)
                assertEquals(201, f.creator.create(f.creator.attempt()).status)
                assertEquals(100, f.rows("complaints").size)
                assertEquals("DELETED", f.scalar("SELECT state FROM complaint_resource_ids WHERE id = ?", attempt.id))
            }
        }
        withFixture { f ->
            val attempt = f.attempt(report(f).id)
            val wire = f.wire(attempt)
            wire.factory(f.store, f.lanes).use { publishers ->
                val http = f.http(publishers)
                // Synthetic ledger headroom only. Keep H=F+B+Q+Z and do not lend the TEST terminal reserve.
                assertEquals(1, f.observer.update(
                    "UPDATE complaint_capacity_counters SET free_units = 966655, " +
                        "actual_units = hard_limit - recovery_reserved_units - test_reserved_units - 966655 WHERE name = 'storage_bytes'",
                ))
                val short = f.state()
                f.creator.problem(f.delete(attempt, http), 503, "SERVICE_UNAVAILABLE")
                assertEquals(short, f.state())
                assertTrue(wire.requests.isEmpty())
                assertEquals(1, f.observer.update(
                    "UPDATE complaint_capacity_counters SET free_units = free_units + 1, actual_units = actual_units - 1 WHERE name = 'storage_bytes'",
                ))
                val exact = f.counters()
                f.prepared(attempt, publishers)
                assertEquals(0L, f.counters().getValue(ComplaintCapacityCounter.STORAGE_BYTES).free)
                f.assertCounterDelta(exact, OwnerDeleteLiteralCharges.authorization, OwnerDeleteLiteralCharges.promise)
                assertEquals(1, semanticEvents(f))
                applied(f.delete(attempt, http))
                f.assertCounterDelta(exact, OwnerDeleteLiteralCharges.authorization, OwnerDeleteLiteralCharges.promise,
                    OwnerDeleteLiteralCharges.ordinaryApply, OwnerDeleteLiteralCharges.content)
                assertPartial(f, wire.event.route.eventId, 393216)
            }
        }
    }

    private fun withFixture(test: (OwnerDeleteFixture) -> Unit) = withOwnerDelete(database.value, test)

    private fun report(f: OwnerDeleteFixture): OwnerCreateFixtureAttempt = f.creator.attempt().also { assertEquals(201, f.creator.create(it).status) }

    private fun present(f: OwnerDeleteFixture, id: UUID): Boolean = f.observer.queryForObject("SELECT EXISTS (SELECT 1 FROM complaints WHERE id = ?)", Boolean::class.java, id) == true

    private fun content(f: OwnerDeleteFixture, id: UUID): String = checkNotNull(f.scalar("SELECT to_jsonb(c)::text FROM complaints c WHERE id = ?", id))

    private fun applied(response: MockHttpServletResponse) {
        assertEquals(204, response.status)
        assertEquals(0, response.contentAsByteArray.size)
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        for (name in listOf("ETag", "Location", "Content-Type", "Content-Length", "Transfer-Encoding")) assertNull(response.getHeader(name), name)
    }

    private fun appliedStatus(response: MockHttpServletResponse) {
        assertEquals(200, response.status)
        assertEquals("{\"outcome\":\"APPLIED\",\"originalStatus\":204}", response.contentAsString)
        assertNull(response.getHeader("ETag"))
        assertNull(response.getHeader("Location"))
    }

    private fun assertPartial(f: OwnerDeleteFixture, eventId: String, storageRemaining: Long) {
        assertEquals(true, f.observer.queryForObject(
            "SELECT state = 'PARTIAL' AND converted_at IS NOT NULL AND reserved_amounts[21] = 491520 " +
                "AND reserved_amounts[21] - converted_amounts[21] = ? FROM complaint_recovery_capacity_reservations WHERE event_id = ?",
            Boolean::class.java, storageRemaining, eventId,
        ))
        assertTrue(f.rows("complaint_deletion_journal_retirements").isEmpty())
    }

    private fun semanticEvents(f: OwnerDeleteFixture): Int = lifecycleField(checkNotNull(lifecycleField(f.ingress, "semantics")), "events") as Int

    private fun completionFault(f: OwnerDeleteFixture, mode: String) {
        if (mode == "COMMIT") {
            f.jdbc.execute("CREATE TEMP TABLE kira_owner_delete_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED) ON COMMIT DROP")
            assertEquals(2, f.jdbc.update("INSERT INTO kira_owner_delete_commit VALUES (1), (1)"))
        } else {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit(): Unit = throw SyntheticInstallationEnrollmentFailure()
            })
        }
    }

    private fun redacted(failure: PersistencePhaseException) {
        assertEquals("Persistence phase refused.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun editInput(f: OwnerDeleteFixture, id: UUID, key: UUID): MockHttpServletRequest =
        MockHttpServletRequest("PATCH", "/api/v1/complaints/$id/content").apply {
            remoteAddr = "192.0.2.1"
            contentType = "application/json"
            addHeader("Authorization", "Bearer ${f.creator.token}")
            addHeader("X-Kira-Idempotency-Key", key.toString())
            addHeader("If-Match", "\"complaint-$id-v1\"")
            setContent("{\"subject\":\"Changed by real edit\",\"body\":\"Changed body\"}".toByteArray())
        }
}
