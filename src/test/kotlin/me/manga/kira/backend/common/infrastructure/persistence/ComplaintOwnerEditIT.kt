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
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBearerAuthenticator
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerEditOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerOperationIdentity
import me.manga.kira.backend.complaint.infrastructure.InstallationHttpPrincipal
import me.manga.kira.backend.security.ComplaintAdmittedOwnerEdit
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintInstallationSecurityChainFactory
import me.manga.kira.backend.security.ComplaintOwnerEditAdmissionPolicy
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.security.complaintSpringSecurityContext
import me.manga.kira.backend.security.ownerEditTestIngress
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.FilterChainProxy
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** Actual session-HTTP-issued TEST token and ordinary producer/capacity/phase owner. Not activation or mobile parity. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
// Keep the eleven grouped producer/race/fault scenarios under one owned PostgreSQL fixture lifecycle.
@Suppress("LargeClass")
class ComplaintOwnerEditIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintOwnerEditIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `only explicit paired TEST edit and status dispatch accepts the real session token and sends after physical release`() = withFixture { f, e ->
        val report = f.attempt()
        assertEquals(201, f.create(report, e.creations).status)
        val attempt = e.attempt(report.id)
        val before = f.state()
        assertEquals(f.actor, f.jwt.verify(f.json(f.sessionResponse)["accessToken"].asText()).installation)
        for (enabled in listOf(false, true)) {
            val bridge = ComplaintHttpIngressBridge(e.ingress)
            val installations = mock(ComplaintInstallationHttpHandler::class.java)
            val me = mock(ComplaintInstallationMeHttpHandler::class.java)
            val history = mock(ComplaintOwnerHistoryHttpHandler::class.java)
            val authentication = ComplaintInstallationBearerAuthenticator(f.run.scope, f.jwt, f.historyPhases, e.ingress)
            val factory = ComplaintInstallationSecurityChainFactory(
                bridge,
                authentication,
                installations,
                me,
                history,
                if (enabled) e.creations else f.handler(e.ingress),
                edit = if (enabled) e.handler else null,
            )
            val users = mock(UserRepository::class.java)
            complaintSpringSecurityContext(factory, users).use { spring ->
                val chain = spring.getBean(FilterChainProxy::class.java)
                fun send(request: MockHttpServletRequest): MockHttpServletResponse {
                    request.servletPath = requireNotNull(request.requestURI)
                    var sentReleased = false
                    val response = object : MockHttpServletResponse() {
                        override fun getOutputStream(): ServletOutputStream {
                            f.assertReleased()
                            sentReleased = true
                            return super.getOutputStream()
                        }
                    }
                    val dispatch = FilterChain { routed, output ->
                        f.assertReleased()
                        val principal = SecurityContextHolder.getContext().authentication.principal as InstallationHttpPrincipal
                        assertEquals(f.actor, principal.installation)
                        assertNull(CurrentUser().getOrNull())
                        factory.handler.handleRequest(routed as HttpServletRequest, output as HttpServletResponse)
                    }
                    bridge.doFilter(request, response) { admitted, output ->
                        RequestBodySizeLimitFilter(ObjectMapper()).doFilter(admitted, output) { bounded, result -> chain.doFilter(bounded, result, dispatch) }
                    }
                    assertTrue(sentReleased)
                    assertNull(SecurityContextHolder.getContext().authentication)
                    return response
                }
                val direct = send(e.input(attempt))
                if (enabled) {
                    assertAcknowledgement(f, direct, attempt.id, 2)
                    val status = send(e.statusInput(attempt))
                    assertEquals(200, status.status)
                    assertEquals(f.json(direct), f.json(status)["body"])
                    assertEquals(200, f.json(status)["originalStatus"].asInt())
                    assertFalse(f.json(status).has("location"))
                    assertNull(status.getHeader("ETag"))
                } else {
                    f.problem(direct, 404, "NOT_FOUND")
                    f.problem(send(e.statusInput(attempt)), 400, "VALIDATION_FAILED")
                    assertEquals(before, f.state())
                }
            }
            verifyNoInteractions(users, installations, me, history)
        }
        f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_EDIT)
    }

    @Test
    fun `produced reports ordinary replies and orphan notice replies preserve immutable and CLOSED state while growing and shrinking prepaid envelopes`() =
        withFixture {
                f,
                e,
            ->
            val report = f.attempt()
            assertEquals(201, f.create(report, e.creations).status)
            val ordinary = f.replyAttempt(report.id)
            assertEquals(201, f.reply(ordinary, e.creations).status)
            val notice = f.replyNotice() // Synthetic system seed; owned reply itself comes from the actual producer.
            val noticeReply = f.replyAttempt(notice)
            assertEquals(201, f.reply(noticeReply, e.creations).status)
            f.eraseReplyParent(notice) // Fixture erasure, not a WORM/deletion activation proof.
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE complaints SET status = 'CLOSED', closure_reason = 'Synthetic closure', closure_provenance = 'ADMIN', " +
                        "closure_actor_id = ?, closed_at = now() WHERE id = ?",
                    f.base.ordinary.userId,
                    report.id,
                ),
            )
            val before = f.state()
            for ((id, subject) in listOf(report.id to "Edited report", ordinary.id to "Edited reply", noticeReply.id to null)) {
                val immutable = immutableContent(f, id)
                val attempt = e.attempt(id, subject, "🙂".repeat(1000))
                f.observations.clear()
                assertAcknowledgement(f, e.edit(attempt), id, 2)
                assertEquals(immutable, immutableContent(f, id))
                assertEquals(4000, f.observer.queryForObject("SELECT octet_length(body) FROM complaints WHERE id = ?", Int::class.java, id))
                assertEquals(1, f.observations.count { it.first == OwnerCreateFixtureStep.EDIT_CONTENT })
                assertEquals(
                    listOf(
                        OwnerCreateFixtureStep.CLAIM, OwnerCreateFixtureStep.COUNTERS, OwnerCreateFixtureStep.RUN, OwnerCreateFixtureStep.OWNER,
                        OwnerCreateFixtureStep.CREDENTIAL, OwnerCreateFixtureStep.PARENT_CANDIDATE, OwnerCreateFixtureStep.PARENT_RESOURCE,
                        OwnerCreateFixtureStep.PARENT_CONTENT, OwnerCreateFixtureStep.EDIT_CONTENT, OwnerCreateFixtureStep.COMPLETE,
                    ),
                    f.observations.map { it.first }.filter {
                        it != OwnerCreateFixtureStep.AUTH && it != OwnerCreateFixtureStep.OBSERVE &&
                            it != OwnerCreateFixtureStep.CHARGE
                    },
                )
                assertEquals(
                    true,
                    f.observer.queryForObject(
                        "SELECT complaint_actor_kind = 'INSTALLATION' AND actor_user_id IS NULL AND detail = '{\"version\":2}'::jsonb " +
                            "FROM audit_log WHERE complaint_data_scope_id = ? AND entity_id = ? AND action = 'COMPLAINT_CONTENT_EDITED'",
                        Boolean::class.java,
                        f.run.scope.id,
                        id.toString(),
                    ),
                )
            }
            val shrinking = e.attempt(report.id, "Edited report", "x", 2)
            assertAcknowledgement(f, e.edit(shrinking), report.id, 3)
            assertEquals(1, f.observer.queryForObject("SELECT octet_length(body) FROM complaints WHERE id = ?", Int::class.java, report.id))
            f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_EDIT.scaled(4))
            assertEquals(before.resources, f.state().resources)
            assertEquals(before.content.size, f.state().content.size)
            for (counter in listOf(ComplaintCapacityCounter.RESOURCE_IDS, ComplaintCapacityCounter.COMPLAINT_ROWS)) {
                assertEquals(before.counters.getValue(counter.storedName), f.state().counters.getValue(counter.storedName))
            }
            // Explicitly synthetic business-count staging; not evidence that these extra rows paid a content allocation.
            repeat(97) { f.content(pending = it % 2 == 0) }
            val atLimit = f.state()
            assertEquals(100, atLimit.content.size)
            assertAcknowledgement(f, e.edit(e.attempt(ordinary.id, "At business limit", "body", 2)), ordinary.id, 3)
            assertEquals(100, f.state().content.size)
            f.assertCharge(atLimit.counters, ComplaintCapacityCharges.OWNER_EDIT)
        }

    @Test
    fun `terminal edit rejections retain only normal receipt while malformed precondition and current auth failures retain nothing`() = withFixture { f, e ->
        val report = f.attempt()
        assertEquals(201, f.create(report, e.creations).status)
        val notice = f.replyNotice()
        val noticeReply = f.replyAttempt(notice)
        assertEquals(201, f.reply(noticeReply, e.creations).status)
        val exhausted = f.content()
        assertEquals(1, f.observer.update("UPDATE complaints SET version = ? WHERE id = ?", Long.MAX_VALUE, exhausted))
        val pending = f.content(pending = true)
        val other = f.jwt.verify(f.json(f.exchange(UUID.randomUUID(), session = false))["accessToken"].asText()).installation
        val hidden = f.content(pending = true)
        assertEquals(1, f.observer.update("UPDATE complaints SET owner_id = ? WHERE id = ?", other.id, hidden))
        val erased = f.content()
        f.eraseReplyParent(erased)
        val cases = listOf(
            Triple(e.attempt(report.id, "  Synthetic subject  ", " \tSynthetic body\r\nline \n"), 409, "COMPLAINT_NO_CHANGE"),
            Triple(e.attempt(report.id, version = 2), 412, "PRECONDITION_FAILED"),
            Triple(e.attempt(report.id, subject = null), 409, "COMPLAINT_INVALID_TRANSITION"),
            Triple(e.attempt(noticeReply.id), 409, "COMPLAINT_INVALID_TRANSITION"),
            Triple(e.attempt(exhausted, version = Long.MAX_VALUE), 409, "COMPLAINT_INVALID_TRANSITION"),
            Triple(e.attempt(pending), 409, "COMPLAINT_DELETION_PENDING"),
            Triple(e.attempt(hidden), 404, "COMPLAINT_NOT_FOUND"),
            Triple(e.attempt(notice, subject = null), 404, "COMPLAINT_NOT_FOUND"),
            Triple(e.attempt(erased), 404, "COMPLAINT_NOT_FOUND"),
            Triple(e.attempt(UUID.randomUUID()), 404, "COMPLAINT_NOT_FOUND"),
        )
        for ((attempt, status, code) in cases) {
            val before = f.state()
            f.observations.clear()
            val direct = e.edit(attempt)
            f.problem(direct, status, code)
            val outcome = e.status(attempt)
            assertEquals("""{"outcome":"REJECTED","originalStatus":$status,"problemCode":"$code"}""", outcome.contentAsString)
            assertArrayEquals(direct.contentAsByteArray, e.edit(attempt).contentAsByteArray)
            val after = f.state()
            assertEquals(before.content, after.content)
            assertEquals(before.resources, after.resources)
            assertEquals(before.audits, after.audits)
            assertEquals(before.receipts.size + 1, after.receipts.size)
            f.assertCharge(before.counters, ComplaintCapacityCharges.NORMAL_RECEIPT)
            if (status ==
                404
            ) {
                assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.PARENT_RESOURCE || it.first == OwnerCreateFixtureStep.PARENT_CONTENT })
            }
        }
        val fresh = e.attempt(report.id)
        val before = f.state()
        for ((request, status, code) in listOf(
            Triple(e.input(fresh).apply { removeHeader("If-Match") }, 428, "PRECONDITION_REQUIRED"),
            Triple(
                e.input(fresh).apply {
                    removeHeader("If-Match")
                    addHeader("If-Match", "*")
                },
                412,
                "PRECONDITION_FAILED",
            ),
            Triple(e.input(fresh).apply { setContent("{}".toByteArray()) }, 400, "VALIDATION_FAILED"),
            Triple(e.input(fresh).apply { setContent("{\"subject\":\"x\",\"body\":\"\\ud800\"}".toByteArray()) }, 400, "VALIDATION_FAILED"),
            Triple(e.input(fresh, "invalid-token"), 401, "UNAUTHORIZED"),
        )) {
            f.problem(e.send(request), status, code)
            assertEquals(before, f.state())
        }
        assertEquals(1, f.observer.update("UPDATE app_installations SET credential_version = 2 WHERE id = ?", f.actor.id))
        try {
            f.problem(e.edit(fresh), 401, "UNAUTHORIZED")
            f.problem(e.status(cases.first().first), 401, "UNAUTHORIZED")
            assertEquals(before, f.state())
        } finally {
            assertEquals(1, f.observer.update("UPDATE app_installations SET credential_version = 1 WHERE id = ?", f.actor.id))
        }
    }

    @Test
    fun `historical edit replay and status survive later edit erasure and exhausted or unavailable mutation admission without tuple leakage`() = withFixture {
            f,
            e,
        ->
        val report = f.attempt()
        assertEquals(201, f.create(report, e.creations).status)
        val first = e.attempt(report.id)
        val original = e.edit(first)
        assertAcknowledgement(f, original, report.id, 2)
        assertAcknowledgement(f, e.edit(e.attempt(report.id, body = "Later content", version = 2)), report.id, 3)
        f.eraseReplyParent(report.id)
        val otherToken = f.json(f.exchange(UUID.randomUUID(), session = false))["accessToken"].asText()
        val unavailable = OwnerEditFixture(f, ownerEditTestIngress(f.policy, edits = ComplaintOwnerEditAdmissionPolicy.Disabled))
        val exhausted = OwnerEditFixture(f)
        // Sixty LOWER admissions only: synthetic P's nine-MB storage envelope cannot fit sixty successful SQL edits.
        repeat(60) { index ->
            val attempt = exhausted.attempt(report.id, body = "Admission only $index")
            exhausted.ingress.withIngress(exhausted.input(attempt)) { context ->
                exhausted.ingress.startOwnerEdit(context)
                exhausted.ingress.admitOwnerEdit(context, attempt.candidate.tuple)
            }
        }
        val before = f.state()
        f.observations.clear()
        for (selected in listOf(unavailable, exhausted)) {
            val replay = selected.edit(first)
            assertArrayEquals(original.contentAsByteArray, replay.contentAsByteArray)
            assertEquals(original.getHeader("ETag"), replay.getHeader("ETag"))
            assertNull(replay.getHeader("Location"))
            val status = selected.status(first)
            assertEquals(200, status.status)
            assertEquals(f.json(original), f.json(status)["body"])
        }
        f.problem(exhausted.edit(exhausted.attempt(report.id)), 429, "RATE_LIMITED")
        f.problem(unavailable.edit(unavailable.attempt(report.id)), 503, "SERVICE_UNAVAILABLE")
        f.problem(unavailable.edit(unavailable.attempt(report.id, body = "changed", key = first.key)), 409, "IDEMPOTENCY_KEY_REUSED")
        f.problem(unavailable.send(unavailable.statusInput(first, targets = listOf(UUID.randomUUID()))), 409, "IDEMPOTENCY_KEY_REUSED")
        f.problem(unavailable.send(unavailable.statusInput(first, bearer = otherToken)), 404, "OPERATION_NOT_FOUND")
        f.problem(unavailable.edit(unavailable.attempt(report.id, key = report.key)), 409, "IDEMPOTENCY_KEY_REUSED")
        f.problem(f.create(f.attempt(id = report.id, key = first.key), unavailable.creations), 409, "IDEMPOTENCY_KEY_REUSED")
        assertEquals(before, f.state())
        assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.CLAIM || it.first == OwnerCreateFixtureStep.PARENT_CANDIDATE })

        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_idempotency_receipts SET created_at = now() - interval '9 days', completed_at = now() - interval '9 days', " +
                    "expires_at = now() - interval '1 day' WHERE actor_id = ? AND idempotency_key = ?",
                f.actor.id,
                first.key,
            ),
        )
        val expired = f.state()
        f.problem(e.status(first), 404, "OPERATION_NOT_FOUND")
        f.problem(e.edit(first), 503, "SERVICE_UNAVAILABLE") // Extant expired claim is neither replaced nor reexecuted.
        assertEquals(expired, f.state())
    }

    @Test
    fun `run reservation credential and platform changes after released preflight roll edit back before target discovery`() = withFixture { f, e ->
        val report = f.attempt()
        assertEquals(201, f.create(report, e.creations).status)
        val changes = listOf(
            "UPDATE complaint_installation_ids SET state = 'DELETION_PENDING' WHERE id = '${f.actor.id}'" to
                "UPDATE complaint_installation_ids SET state = 'ACTIVE' WHERE id = '${f.actor.id}'",
            "UPDATE app_installations SET credential_version = 2 WHERE id = '${f.actor.id}'" to
                "UPDATE app_installations SET credential_version = 1 WHERE id = '${f.actor.id}'",
            "UPDATE app_installations SET platform = 'IOS' WHERE id = '${f.actor.id}'" to
                "UPDATE app_installations SET platform = 'ANDROID' WHERE id = '${f.actor.id}'",
            "UPDATE complaint_test_runs SET state = 'SEALED', sealed_at = now() WHERE data_scope_id = '${f.run.scope.id}'" to
                "UPDATE complaint_test_runs SET state = 'ACTIVE', sealed_at = NULL WHERE data_scope_id = '${f.run.scope.id}'",
        )
        for ((change, restore) in changes) {
            val attempt = e.attempt(report.id)
            val before = f.state()
            val changed = AtomicBoolean()
            f.observations.clear()
            f.beforeStep = { step ->
                if (step == OwnerCreateFixtureStep.CLAIM && changed.compareAndSet(false, true)) assertEquals(1, f.observer.update(change))
            }
            try {
                f.problem(e.edit(attempt), 401, "UNAUTHORIZED")
                assertTrue(changed.get())
                assertEquals(before, f.state())
                assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.PARENT_CANDIDATE })
            } finally {
                f.beforeStep = {}
                assertEquals(1, f.observer.update(restore)) // Synthetic state linearization only, not deletion/activation authority.
            }
        }
    }

    @Test
    fun `two requests preflight the same version before either claim and the later locked edit receives stale412 exactly once`() = withFixture { f, e ->
        val report = f.attempt()
        assertEquals(201, f.create(report, e.creations).status)
        val first = e.attempt(report.id, body = "Winning body")
        val second = e.attempt(report.id, body = "Competing body")
        val before = f.state()
        OwnedCallerTestScope().use { callers ->
            val firstGate = callers.gate()
            val secondGate = callers.gate()
            val arriving = AtomicInteger()
            f.beforeStep = { step ->
                if (step == OwnerCreateFixtureStep.CLAIM) {
                    when (arriving.incrementAndGet()) {
                        1 -> firstGate.hold()
                        2 -> secondGate.hold()
                        else -> error("Unexpected extra claim")
                    }
                }
            }
            try {
                val winner = callers.launch { e.send(e.input(first)) }
                firstGate.awaitEntered()
                val stale = callers.launch { e.send(e.input(second)) }
                secondGate.awaitEntered()
                // Both real authentication/preflight phases have released. No timeout/retry can substitute for this race.
                assertEquals(2, arriving.get())
                firstGate.release()
                assertEquals(200, winner.value().status)
                secondGate.release()
                f.problem(stale.value(), 412, "PRECONDITION_FAILED")
            } finally {
                firstGate.release()
                secondGate.release()
                f.beforeStep = {}
            }
        }
        f.assertReleased()
        assertEquals(2L, f.observer.queryForObject("SELECT version FROM complaints WHERE id = ?", Long::class.java, report.id))
        assertEquals("Winning body", f.observer.queryForObject("SELECT body FROM complaints WHERE id = ?", String::class.java, report.id))
        assertEquals(before.receipts.size + 2, f.state().receipts.size)
        assertEquals(before.audits.size + 1, f.state().audits.size)
        f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_EDIT + ComplaintCapacityCharges.NORMAL_RECEIPT)
    }

    @Test
    fun `same key actual claim contention is the only in progress retry and its uncommitted outcome is invisible to status`() = withFixture { f, e ->
        val report = f.attempt()
        assertEquals(201, f.create(report, e.creations).status)
        val attempt = e.attempt(report.id)
        val before = f.state()
        val events = semanticEvents(e)
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val first = AtomicBoolean()
            f.afterStep = { step -> if (step == OwnerCreateFixtureStep.CLAIM && first.compareAndSet(false, true)) gate.hold() }
            val winner = callers.launch { e.send(e.input(attempt)) }
            gate.awaitEntered()
            try {
                f.problem(e.send(e.statusInput(attempt)), 404, "OPERATION_NOT_FOUND")
                val waiting = e.send(e.input(attempt))
                f.problem(waiting, 409, "IDEMPOTENCY_IN_PROGRESS")
                assertEquals("1", waiting.getHeader("Retry-After"))
            } finally {
                gate.release()
            }
            assertEquals(200, winner.value().status)
            f.afterStep = {}
        }
        assertAcknowledgement(f, e.edit(attempt), report.id, 2)
        assertEquals(200, e.status(attempt).status)
        assertEquals(events + 1, semanticEvents(e), "Only one paid edit-member actor event, not a create/global pair.")
        assertEquals(before.receipts.size + 1, f.state().receipts.size)
        assertEquals(before.audits.size + 1, f.state().audits.size)
        f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_EDIT)

        // A second same-key race forces the DO NOTHING/new-statement branch, not released-preflight replay.
        val next = e.attempt(report.id, body = "Next edit", version = 2)
        val beforeConflict = f.state()
        val conflictEvents = semanticEvents(e)
        f.observations.clear()
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val firstAtClaim = AtomicBoolean()
            f.beforeStep = { step -> if (step == OwnerCreateFixtureStep.CLAIM && firstAtClaim.compareAndSet(false, true)) gate.hold() }
            val lagging = callers.launch { e.send(e.input(next)) }
            gate.awaitEntered()
            try {
                val winning = e.send(e.input(next))
                assertEquals(200, winning.status)
                gate.release()
                val conflictReplay = lagging.value()
                assertEquals(200, conflictReplay.status)
                assertArrayEquals(winning.contentAsByteArray, conflictReplay.contentAsByteArray)
                assertEquals(winning.getHeader("ETag"), conflictReplay.getHeader("ETag"))
            } finally {
                gate.release()
                f.beforeStep = {}
            }
        }
        f.assertReleased()
        assertEquals(1, f.observations.count { it.first == OwnerCreateFixtureStep.EDIT_CONTENT })
        assertEquals(beforeConflict.receipts.size + 1, f.state().receipts.size)
        assertEquals(conflictEvents + 1, semanticEvents(e))
        f.assertCharge(beforeConflict.counters, ComplaintCapacityCharges.OWNER_EDIT)
    }

    @Test
    fun `expired facts after the actual content lock cannot mutate content or retain a claim`() = withFixture { f, e ->
        val report = f.attempt()
        assertEquals(201, f.create(report, e.creations).status)
        val attempt = e.attempt(report.id)
        val before = f.state()
        e.ingress.withIngress(e.input(attempt)) { context ->
            e.ingress.startOwnerEdit(context)
            // Negative lower-phase clock facts only. All successful integrated requests use the real HTTP token.
            val expires = Instant.now().minusSeconds(59)
            val identity = ComplaintOwnerOperationIdentity(f.actor, 1, expires.minusSeconds(900), expires)
            assertEquals(ComplaintPlatform.ANDROID, e.phases.authenticate(identity).platform)
            assertNull(e.phases.preflight(identity, attempt.candidate.tuple).receipt)
            val admitted = e.ingress.admitOwnerEdit(context, attempt.candidate.tuple)
            val locked = AtomicBoolean()
            f.afterStep = { step ->
                if (step == OwnerCreateFixtureStep.PARENT_CONTENT) {
                    locked.set(true)
                    while (Instant.now() < identity.expiresAt.plusSeconds(60)) LockSupport.parkNanos(1_000_000)
                }
            }
            try {
                val refusal = assertThrows<ComplaintOwnerOperationRejected> { e.phases.edit(identity, attempt.candidate, ComplaintPlatform.ANDROID, admitted) }
                assertEquals(ComplaintOwnerOperationFailure.UNAUTHORIZED, refusal.failure)
                assertTrue(locked.get())
            } finally {
                f.afterStep = {}
            }
        }
        assertEquals(before, f.state())
        assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.EDIT_CONTENT })
        f.assertReleased()
    }

    @Test
    fun `fake substituted and reused edit capabilities fail before claim and a content lock timeout is not idempotency in progress`() = withFixture { f, e ->
        val report = f.attempt()
        assertEquals(201, f.create(report, e.creations).status)
        val attempt = e.attempt(report.id)
        val substitute = e.attempt(report.id, body = "Substitute", key = attempt.key)
        val before = f.state()
        f.observations.clear()
        e.ingress.withIngress(e.input(attempt)) { context ->
            e.ingress.startOwnerEdit(context)
            val identity = f.identity()
            assertEquals(ComplaintPlatform.ANDROID, e.phases.authenticate(identity).platform)
            assertNull(e.phases.preflight(identity, attempt.candidate.tuple).receipt)
            val admitted = e.ingress.admitOwnerEdit(context, attempt.candidate.tuple)
            val forged = assertThrows<PersistencePhaseException> {
                e.phases.edit(identity, attempt.candidate, ComplaintPlatform.ANDROID, object : ComplaintAdmittedOwnerEdit {})
            }
            assertTrue(forged.cleanupProven)
            val replaced = assertThrows<PersistencePhaseException> { e.phases.edit(identity, substitute.candidate, ComplaintPlatform.ANDROID, admitted) }
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, replaced.databaseOutcome)
            assertTrue(replaced.cleanupProven)
            assertThrows<PersistencePhaseException> { e.phases.edit(identity, attempt.candidate, ComplaintPlatform.ANDROID, admitted) }
        }
        assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.CLAIM })
        assertEquals(before, f.state())
        f.lock("SELECT id FROM complaints WHERE id = ? FOR UPDATE", report.id).use { locked ->
            try {
                val response = e.edit(attempt)
                f.problem(response, 503, "SERVICE_UNAVAILABLE")
                assertNull(response.getHeader("Retry-After"))
                assertEquals(before, f.state())
            } finally {
                locked.rollback()
            }
        }
        assertAcknowledgement(f, e.edit(attempt), report.id, 2)
    }

    @Test
    fun `faults after claim counters content audit and completion roll every edit effect back but not logical admission`() = withFixture { f, e ->
        val report = f.attempt()
        assertEquals(201, f.create(report, e.creations).status)
        var version = 1L
        for (point in listOf(
            OwnerCreateFixtureStep.CLAIM,
            OwnerCreateFixtureStep.CHARGE,
            OwnerCreateFixtureStep.PARENT_CONTENT,
            OwnerCreateFixtureStep.EDIT_CONTENT,
            OwnerCreateFixtureStep.COMPLETE,
        )) {
            val attempt = e.attempt(report.id, body = "Fault round $version", version = version)
            val before = f.state()
            val events = semanticEvents(e)
            val failed = AtomicBoolean()
            f.afterStep = { step -> if (step == point && failed.compareAndSet(false, true)) throw SyntheticInstallationEnrollmentFailure() }
            try {
                f.problem(e.edit(attempt), 503, "SERVICE_UNAVAILABLE")
                assertTrue(failed.get())
                assertEquals(before, f.state())
                assertEquals(events + 1, semanticEvents(e))
            } finally {
                f.afterStep = {}
            }
            version += 1
            assertAcknowledgement(f, e.edit(attempt), report.id, version)
            assertEquals(events + 1, semanticEvents(e))
            f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_EDIT)
        }
        val attempt = e.attempt(report.id, body = "After actual audit", version = version)
        val before = f.state()
        val reachedAudit = AtomicBoolean()
        f.beforeStep = { step ->
            if (step == OwnerCreateFixtureStep.COMPLETE) {
                assertEquals(
                    1,
                    f.jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'COMPLAINT_CONTENT_EDITED' AND (detail->>'version')::bigint = ?",
                        Int::class.java,
                        report.id.toString(),
                        version + 1,
                    ),
                )
                reachedAudit.set(true)
                throw SyntheticInstallationEnrollmentFailure()
            }
        }
        try {
            f.problem(e.edit(attempt), 503, "SERVICE_UNAVAILABLE")
            assertTrue(reachedAudit.get())
            assertEquals(before, f.state())
        } finally {
            f.beforeStep = {}
        }
        // Legal synthetic headroom exhaustion: no extra SQL success is presumed from the admission member.
        val storage = before.counters.getValue(ComplaintCapacityCounter.STORAGE_BYTES.storedName)
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_capacity_counters SET free_units = 0, actual_units = hard_limit - " +
                    "recovery_reserved_units - test_reserved_units WHERE name = 'storage_bytes'",
            ),
        )
        try {
            val full = f.state()
            f.problem(e.edit(attempt), 503, "SERVICE_UNAVAILABLE")
            f.problem(e.status(attempt), 404, "OPERATION_NOT_FOUND")
            assertEquals(full, f.state())
        } finally {
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE complaint_capacity_counters SET free_units = ?, actual_units = ? WHERE name = 'storage_bytes'",
                    storage.free,
                    storage.actual,
                ),
            )
        }
    }

    @Test
    fun `retained edit output requires proven commit and release and neither ambiguous commit nor failed committed tail publishes success`() {
        for (mode in listOf("RELEASE", "COMMIT", "TAIL")) {
            withFixture { f, e ->
                val report = f.attempt()
                assertEquals(201, f.create(report, e.creations).status)
                val attempt = e.attempt(report.id)
                val before = f.state()
                var retained: ComplaintOwnerEditOperation? = null
                var observed: PersistencePhaseException? = null
                e.ingress.withIngress(e.input(attempt)) { context ->
                    e.ingress.startOwnerEdit(context)
                    val identity = f.identity()
                    assertEquals(ComplaintPlatform.ANDROID, e.phases.authenticate(identity).platform)
                    assertNull(e.phases.preflight(identity, attempt.candidate.tuple).receipt)
                    val admission = e.ingress.admitOwnerEdit(context, attempt.candidate.tuple)
                    val phase = f.base.ordinary.ownership.enterComplaintOwnerEdit()
                    try {
                        phase.ownerEdit.bindEdit(admission)
                        phase.begin()
                        retained = e.store.edit(identity, attempt.candidate, ComplaintPlatform.ANDROID)
                        val early = assertThrows<PersistencePhaseException> { checkNotNull(retained).result }
                        assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
                        assertFalse(early.cleanupProven)
                        if (mode == "COMMIT") {
                            f.jdbc.execute("CREATE TEMP TABLE kira_owner_edit_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                            assertEquals(2, f.jdbc.update("INSERT INTO kira_owner_edit_commit VALUES (1), (1)"))
                        } else if (mode == "TAIL") {
                            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                                override fun afterCommit(): Unit = throw SyntheticInstallationEnrollmentFailure()
                            })
                        }
                        val failed = runCatching(phase::commit).exceptionOrNull()
                        if (mode == "RELEASE") assertNull(failed) else assertNotNull(failed)
                        failed?.let(phase::recordFailure)
                        if (failed == null) {
                            val unreleased = assertThrows<PersistencePhaseException> { checkNotNull(retained).result }
                            assertEquals(PersistenceDatabaseOutcome.COMMITTED, unreleased.databaseOutcome)
                            assertFalse(unreleased.cleanupProven)
                        }
                    } finally {
                        phase.finish()
                    }
                    if (mode == "RELEASE") {
                        assertTrue(checkNotNull(retained).result.receipt is ComplaintOwnerEditReceipt.Applied)
                    } else {
                        observed = assertThrows<PersistencePhaseException> { checkNotNull(retained).result }
                    }
                }
                f.assertReleased()
                if (mode == "COMMIT") {
                    assertEquals(PersistenceDatabaseOutcome.UNKNOWN, checkNotNull(observed).databaseOutcome)
                    assertEquals(before, f.state())
                    f.problem(e.status(attempt), 404, "OPERATION_NOT_FOUND")
                } else {
                    if (mode == "TAIL") assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(observed).databaseOutcome)
                    assertEquals(200, e.status(attempt).status)
                }
                observed?.let {
                    assertTrue(it.cleanupProven)
                    assertNull(it.cause)
                    assertTrue(it.suppressed.isEmpty())
                }
                assertAcknowledgement(f, e.edit(attempt), report.id, 2)
                f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_EDIT)
            }
        }
    }

    private fun semanticEvents(e: OwnerEditFixture): Int = lifecycleField(checkNotNull(lifecycleField(e.ingress, "semantics")), "events") as Int

    private fun assertAcknowledgement(f: ComplaintOwnerCreateFixture, response: MockHttpServletResponse, id: UUID, version: Long) {
        assertEquals(200, response.status)
        assertEquals("""{"id":"$id","version":$version}""", response.contentAsString)
        assertEquals("\"complaint-$id-v$version\"", response.getHeader("ETag"))
        assertNull(response.getHeader("Location"))
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        f.assertReleased()
    }

    private fun immutableContent(f: ComplaintOwnerCreateFixture, id: UUID): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT (to_jsonb(c) - 'subject' - 'body' - 'version' - 'updated_at')::text FROM complaints c WHERE id = ?",
            String::class.java,
            id,
        ),
    )

    private fun withFixture(test: (ComplaintOwnerCreateFixture, OwnerEditFixture) -> Unit) = withComplaintOwnerCreate(database.value) { f ->
        test(f, OwnerEditFixture(f))
    }
}
