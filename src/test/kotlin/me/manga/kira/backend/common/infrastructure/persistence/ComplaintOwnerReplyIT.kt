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
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReceipt
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBearerAuthenticator
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerOperationIdentity
import me.manga.kira.backend.complaint.infrastructure.InstallationHttpPrincipal
import me.manga.kira.backend.database.complaint.assertOwnerReplyReceiptMigration
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintInstallationSecurityChainFactory
import me.manga.kira.backend.security.ComplaintOwnerCreateAdmissionPolicy
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.security.complaintSpringSecurityContext
import me.manga.kira.backend.security.ownerCreateTestIngress
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** Actual HTTP-issued TEST token, fixed reply producer and existing ordinary PG owner. No mode or activation proof. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintOwnerReplyIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintOwnerReplyIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `only the optional TEST chain dispatches reply with the actual session token and sends after release`() = withFixture { f ->
        val parent = f.content()
        val attempt = f.replyAttempt(parent, body = "x")
        val before = f.state()
        assertEquals(f.actor, f.jwt.verify(f.json(f.sessionResponse)["accessToken"].asText()).installation)
        for (supplied in listOf(false, true)) {
            val bridge = ComplaintHttpIngressBridge(f.ingress)
            val installations = mock(ComplaintInstallationHttpHandler::class.java)
            val me = mock(ComplaintInstallationMeHttpHandler::class.java)
            val history = mock(ComplaintOwnerHistoryHttpHandler::class.java)
            val authentication = ComplaintInstallationBearerAuthenticator(f.run.scope, f.jwt, f.historyPhases, f.ingress)
            val factory = ComplaintInstallationSecurityChainFactory(
                bridge,
                authentication,
                installations,
                me,
                history,
                f.handler,
                reply = if (supplied) f.handler else null,
            )
            val users = mock(UserRepository::class.java)
            complaintSpringSecurityContext(factory, users).use { spring ->
                val chain = spring.getBean(FilterChainProxy::class.java)
                val guard = RequestBodySizeLimitFilter(ObjectMapper())
                val request = f.replyInput(attempt).apply { servletPath = requireNotNull(requestURI) }
                var sentReleased = false
                val response = object : MockHttpServletResponse() {
                    override fun getOutputStream(): ServletOutputStream {
                        f.assertReleased()
                        sentReleased = true
                        return super.getOutputStream()
                    }
                }
                var dispatched = false
                val dispatch = FilterChain { routed, output ->
                    dispatched = true
                    f.assertReleased()
                    val principal = SecurityContextHolder.getContext().authentication.principal as InstallationHttpPrincipal
                    assertEquals(f.actor, principal.installation)
                    assertNull(CurrentUser().getOrNull())
                    factory.handler.handleRequest(routed as HttpServletRequest, output as HttpServletResponse)
                }
                bridge.doFilter(request, response) { admitted, output ->
                    guard.doFilter(admitted, output) { bounded, result -> chain.doFilter(bounded, result, dispatch) }
                }
                assertEquals(supplied, dispatched)
                assertTrue(sentReleased)
                assertNull(SecurityContextHolder.getContext().authentication)
                if (supplied) {
                    assertEquals(201, response.status)
                    assertEquals("""{"id":"${attempt.id}","version":1}""", response.contentAsString)
                    assertEquals("/api/v1/complaints/${attempt.id}", response.getHeader("Location"))
                    assertEquals("\"complaint-${attempt.id}-v1\"", response.getHeader("ETag"))
                    assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
                    assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
                } else {
                    f.problem(response, 404, "NOT_FOUND")
                    assertEquals(before, f.state())
                }
            }
            verifyNoInteractions(users, installations, me, history)
        }
        f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE)
        assertEquals(before.audits.size + 1, f.state().audits.size)
        assertEquals(200, f.replyStatus(attempt).status)
    }

    @Test
    fun `ordinary and notice thread replies snapshot the locked parent transitively and survive ancestor erasure unchanged`() = withFixture { f ->
        val report = f.attempt()
        assertEquals(201, f.create(report).status)
        val before = f.state()
        val child = f.replyAttempt(report.id)
        val lateEdit = AtomicBoolean()
        f.beforeStep = { step ->
            if (step == OwnerCreateFixtureStep.CLAIM && lateEdit.compareAndSet(false, true)) {
                // Legal synthetic edit after released preflight, before this write's parent locks.
                assertEquals(1, f.observer.update("UPDATE complaints SET type = 'FEATURES', subject = 'Snapshot at lock', version = 2 WHERE id = ?", report.id))
            }
        }
        try {
            assertEquals(201, f.reply(child).status)
            assertTrue(lateEdit.get())
        } finally {
            f.beforeStep = {}
        }
        assertEquals(1, f.observer.update("UPDATE complaints SET type = 'CUSTOM', subject = 'Later ancestor subject', version = 3 WHERE id = ?", report.id))
        val nested = f.replyAttempt(child.id)
        assertEquals(201, f.reply(nested).status)
        val notice = f.replyNotice()
        assertTrue(notice.version() != 4)
        val noticeChild = f.replyAttempt(notice)
        assertEquals(201, f.reply(noticeChild).status)
        val originalChild = storedContent(f, child.id)
        val originalNoticeChild = storedContent(f, noticeChild.id)
        f.eraseReplyParent(report.id)
        f.eraseReplyParent(notice)
        val noticeNested = f.replyAttempt(noticeChild.id)
        assertEquals(201, f.reply(noticeNested).status)
        assertEquals(originalChild, storedContent(f, child.id))
        assertEquals(originalNoticeChild, storedContent(f, noticeChild.id))
        for (attempt in listOf(child, nested, noticeChild, noticeNested)) {
            assertEquals(
                true,
                f.observer.queryForObject(
                    "SELECT ownership = 'INSTALLATION' AND kind = 'REPLY' AND status = 'OPEN' AND version = 1 " +
                        "AND parent_resource_id = ? AND body = E'Synthetic reply\\nline' AND platform = 'ANDROID' AND app_version IS NULL " +
                        "AND os_version = 'fixture-os' AND manufacturer = '' AND device_model = '' AND created_at = updated_at " +
                        "AND closure_reason IS NULL FROM complaints WHERE id = ?",
                    Boolean::class.java,
                    attempt.parentId,
                    attempt.id,
                ),
            )
        }
        val history = f.json(f.history())["items"].associateBy { it["id"].asText() }
        for (attempt in listOf(child, nested)) {
            val item = history.getValue(attempt.id.toString())
            assertEquals("FEATURES", item["type"].asText())
            assertEquals("Snapshot at lock", item["subject"].asText())
            assertFalse(item.has("noticeKey"))
            assertEquals(attempt.parentId.toString(), item["replyToId"].asText())
        }
        for (attempt in listOf(noticeChild, noticeNested)) {
            val item = history.getValue(attempt.id.toString())
            assertEquals("CUSTOM", item["type"].asText())
            assertTrue(item["subject"].isNull)
            assertEquals("complaints.notice.fixture.$notice", item["noticeKey"].asText())
            assertEquals(attempt.parentId.toString(), item["replyToId"].asText())
        }
        f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE.scaled(4))
        assertEquals(before.audits.size + 4, f.state().audits.size)
    }

    @Test
    fun `hidden missing and erased parents share durable404 while eligible pending parents reject409 without reserving a child`() = withFixture { f ->
        val other = f.jwt.verify(f.json(f.exchange(UUID.randomUUID(), session = false))["accessToken"].asText()).installation
        val hidden = f.content(pending = true)
        assertEquals(1, f.observer.update("UPDATE complaints SET owner_id = ? WHERE id = ?", other.id, hidden))
        val pending = f.content(pending = true)
        val erased = f.content()
        f.eraseReplyParent(erased)
        val liveNotice = f.replyNotice(ComplaintDataScope.LIVE)
        val pendingNotice = f.replyNotice()
        assertEquals(1, f.observer.update("UPDATE complaint_resource_ids SET state = 'DELETION_PENDING' WHERE id = ?", pendingNotice))
        val before = f.state()
        val cases = listOf(UUID.randomUUID() to 404, hidden to 404, erased to 404, liveNotice to 404, pending to 409, pendingNotice to 409)
        var notFound: ByteArray? = null
        for ((parent, status) in cases) {
            val attempt = f.replyAttempt(parent)
            val response = f.reply(attempt)
            val code = if (status == 404) "COMPLAINT_PARENT_NOT_FOUND" else "COMPLAINT_DELETION_PENDING"
            f.problem(response, status, code)
            assertFalse(response.contentAsString.contains(parent.toString()))
            assertFalse(response.contentAsString.contains(attempt.rawBody))
            if (status == 404) {
                notFound?.let { assertArrayEquals(it, response.contentAsByteArray) }
                notFound = response.contentAsByteArray
            }
            val receipt = f.replyStatus(attempt)
            assertEquals(200, receipt.status)
            assertEquals("""{"outcome":"REJECTED","originalStatus":$status,"problemCode":"$code"}""", receipt.contentAsString)
            assertEquals(0, f.observer.queryForObject("SELECT count(*) FROM complaint_resource_ids WHERE id = ?", Int::class.java, attempt.id))
        }
        val after = f.state()
        assertEquals(before.resources, after.resources)
        assertEquals(before.content, after.content)
        assertEquals(before.audits, after.audits)
        assertEquals(cases.size, after.receipts.size)
        f.assertCharge(before.counters, ComplaintCapacityCharges.NORMAL_RECEIPT.scaled(cases.size.toLong()))
    }

    @Test
    fun `reply resource collision is one generic receipted result across every scope and reservation state`() = withFixture { f ->
        val parent = f.content()
        val occupied = listOf(f.run.scope, ComplaintDataScope.LIVE).flatMap { scope ->
            listOf("LIVE", "DELETION_PENDING", "DELETED").map { state -> f.resource(state, scope) }
        }
        val before = f.state()
        for (id in occupied) {
            val attempt = f.replyAttempt(parent, id = id)
            val response = f.reply(attempt)
            f.problem(response, 409, "COMPLAINT_RESOURCE_ID_REUSED")
            assertFalse(response.contentAsString.contains(id.toString()))
            assertEquals("COMPLAINT_RESOURCE_ID_REUSED", f.json(f.replyStatus(attempt))["problemCode"].asText())
        }
        assertEquals(before.resources, f.state().resources)
        assertEquals(before.content, f.state().content)
        assertEquals(before.audits, f.state().audits)
        assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.PARENT_CANDIDATE })
        f.assertCharge(before.counters, ComplaintCapacityCharges.NORMAL_RECEIPT.scaled(occupied.size.toLong()))
    }

    @Test
    fun `replay and status survive erasure while operation or ordered target mismatch never discloses a receipt`() = withFixture { f ->
        val otherToken = f.json(f.exchange(UUID.randomUUID(), session = false))["accessToken"].asText()
        val parent = f.content()
        val attempt = f.replyAttempt(parent)
        val original = f.reply(attempt)
        assertEquals(201, original.status)
        assertEquals(1, f.observer.update("UPDATE complaints SET body = 'Later synthetic edit', version = 2 WHERE id = ?", attempt.id))
        f.eraseReplyParent(parent)
        f.eraseReplyParent(attempt.id)
        val before = f.state()
        val events = semanticEvents(f)
        val disabled = f.handler(ownerCreateTestIngress(creates = ComplaintOwnerCreateAdmissionPolicy.Disabled))
        f.observations.clear()
        val replay = f.reply(attempt, disabled)
        assertEquals(201, replay.status)
        assertArrayEquals(original.contentAsByteArray, replay.contentAsByteArray)
        assertEquals(original.getHeader("Location"), replay.getHeader("Location"))
        assertEquals(original.getHeader("ETag"), replay.getHeader("ETag"))
        val status = f.replyStatus(attempt, selected = disabled)
        assertEquals(200, status.status)
        assertEquals(f.json(original), f.json(status)["body"])
        f.problem(f.replyStatus(attempt, targets = listOf(attempt.id, parent)), 409, "IDEMPOTENCY_KEY_REUSED")
        f.problem(f.replyStatus(attempt, operation = "OWNER_CREATE", targets = listOf(attempt.id)), 409, "IDEMPOTENCY_KEY_REUSED")
        f.problem(f.replyStatus(attempt, bearer = otherToken), 404, "OPERATION_NOT_FOUND")
        val substituted = f.replyAttempt(UUID.randomUUID(), attempt.id, attempt.key)
        f.problem(f.reply(substituted, disabled), 409, "IDEMPOTENCY_KEY_REUSED")
        f.problem(f.create(f.attempt(id = attempt.id, key = attempt.key), disabled), 409, "IDEMPOTENCY_KEY_REUSED")
        assertEquals(before, f.state())
        assertEquals(events, semanticEvents(f))
        assertFalse(f.observations.any { it.first in setOf(OwnerCreateFixtureStep.CLAIM, OwnerCreateFixtureStep.PARENT_CANDIDATE) })
    }

    @Test
    fun `mixed create and reply race from ninety nine includes pending content and cannot exceed one hundred`() = withFixture { f ->
        val parent = f.content()
        repeat(97) { f.content(pending = it % 2 == 0) }
        f.content(pending = true, parent = parent)
        val report = f.attempt()
        val reply = f.replyAttempt(parent)
        val before = f.state()
        val responses = raceOriginalOwnerClaims(f, listOf(f.input(report), f.replyInput(reply)))
        val claims = f.observations.filter { it.first == OwnerCreateFixtureStep.CLAIM }
        assertEquals(2, claims.size, "Both original claim statements must complete before any retry.")
        assertEquals(2, claims.map { it.second.identity.second }.toSet().size, "The original claims must use two real transactions.")
        val completed = responses.mapIndexed { index, response ->
            if (response.status != 503) {
                response
            } else if (index == 0) {
                f.create(report)
            } else {
                f.reply(reply)
            }
        }
        assertEquals(listOf(201, 409), completed.map { it.status }.sorted())
        f.problem(completed.single { it.status == 409 }, 409, "COMPLAINT_CAPACITY_REACHED")
        assertEquals(100, f.state().content.size)
        assertEquals(2, f.state().receipts.size)
        assertEquals(before.audits.size + 1, f.state().audits.size)
        f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE + ComplaintCapacityCharges.NORMAL_RECEIPT)
    }

    @Test
    fun `actual HTTP create and reply share ten hourly attempts while exact completed replay bypasses the exhausted mutation window`() = withFixture { f ->
        val parent = f.content()
        val before = f.state()
        val replies = mutableListOf<OwnerReplyFixtureAttempt>()
        repeat(10) { index ->
            if (index % 2 == 0) {
                assertEquals(201, f.create(f.attempt()).status)
            } else {
                val attempt = f.replyAttempt(parent)
                replies.add(attempt)
                assertEquals(201, f.reply(attempt).status)
            }
        }
        val after = f.state()
        f.problem(f.reply(f.replyAttempt(parent)), 429, "RATE_LIMITED")
        f.problem(f.create(f.attempt()), 429, "RATE_LIMITED")
        assertEquals(201, f.reply(replies.first()).status)
        assertEquals(200, f.replyStatus(replies.first()).status)
        assertEquals(after, f.state())
        assertEquals(10, after.receipts.size)
        f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE.scaled(10))
    }

    @Test
    fun `current run reservation credential and platform changes after preflight roll reply back before parent lookup`() = withFixture { f ->
        val parent = f.content()
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
            val attempt = f.replyAttempt(parent)
            val before = f.state()
            f.observations.clear()
            val changed = AtomicBoolean()
            f.beforeStep = { step ->
                if (step == OwnerCreateFixtureStep.CLAIM && changed.compareAndSet(false, true)) assertEquals(1, f.observer.update(change))
            }
            try {
                f.problem(f.reply(attempt), 401, "UNAUTHORIZED")
                assertTrue(changed.get())
                assertEquals(before, f.state())
                assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.PARENT_CANDIDATE })
            } finally {
                f.beforeStep = {}
                assertEquals(1, f.observer.update(restore)) // Synthetic linearization/restoration, not a deletion or activation producer.
            }
        }
    }

    @Test
    fun `reply reserves resources in PostgreSQL UUID order and discards only its provisional child on terminal pending rejection`() {
        val low = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val high = UUID.fromString("f0000000-0000-4000-8000-000000000001")
        for ((parentId, childId) in listOf(low to high, high to low)) {
            withFixture { f ->
                f.content(pending = true, id = parentId)
                val before = f.state()
                val attempt = f.replyAttempt(parentId, id = childId)
                f.problem(f.reply(attempt), 409, "COMPLAINT_DELETION_PENDING")
                val resourceSteps = f.observations.map { it.first }.filter {
                    it in setOf(OwnerCreateFixtureStep.PARENT_RESOURCE, OwnerCreateFixtureStep.RESOURCE, OwnerCreateFixtureStep.PARENT_CONTENT)
                }
                val first = if (parentId == low) OwnerCreateFixtureStep.PARENT_RESOURCE else OwnerCreateFixtureStep.RESOURCE
                val second = if (parentId == low) OwnerCreateFixtureStep.RESOURCE else OwnerCreateFixtureStep.PARENT_RESOURCE
                assertEquals(listOf(first, second, OwnerCreateFixtureStep.PARENT_CONTENT), resourceSteps)
                assertEquals(1, f.observations.count { it.first == OwnerCreateFixtureStep.DISCARD_RESOURCE })
                assertEquals(before.resources, f.state().resources)
                assertEquals(before.content, f.state().content)
                assertEquals(before.audits, f.state().audits)
                f.assertCharge(before.counters, ComplaintCapacityCharges.NORMAL_RECEIPT)
            }
        }
    }

    @Test
    fun `after acquiring the actual parent lock expired token facts cannot authorize a reply or retain its resource`() = withFixture { f ->
        val attempt = f.replyAttempt(f.content())
        val before = f.state()
        f.ingress.withIngress(f.replyInput(attempt)) { context ->
            f.ingress.startOwnerReply(context)
            // Negative lower-phase clock facts only; the successful integrated cases use actual issued HTTP tokens.
            val expires = Instant.now().minusSeconds(59)
            val identity = ComplaintOwnerOperationIdentity(f.actor, 1, expires.minusSeconds(900), expires)
            assertEquals(ComplaintPlatform.ANDROID, f.phases.authenticate(identity).platform)
            assertNull(f.phases.replyPreflight(identity, attempt.candidate.tuple).receipt)
            val admission = f.ingress.admitOwnerReply(context, attempt.candidate.tuple)
            val locked = AtomicBoolean()
            f.afterStep = { step ->
                if (step == OwnerCreateFixtureStep.PARENT_CONTENT) {
                    locked.set(true)
                    while (Instant.now() < identity.expiresAt.plusSeconds(60)) LockSupport.parkNanos(1_000_000)
                }
            }
            try {
                val refusal = assertThrows<ComplaintOwnerOperationRejected> {
                    f.phases.reply(identity, attempt.candidate, ComplaintPlatform.ANDROID, admission)
                }
                assertEquals(ComplaintOwnerOperationFailure.UNAUTHORIZED, refusal.failure)
                assertTrue(locked.get())
            } finally {
                f.afterStep = {}
            }
        }
        assertEquals(before, f.state())
        f.assertReleased()
    }

    @Test
    fun `concurrent first reply use has one claim mutation and logical quota charge with bounded in progress waiting`() = withFixture { f ->
        val attempt = f.replyAttempt(f.content())
        val before = f.state()
        val events = semanticEvents(f)
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val first = AtomicBoolean()
            f.afterStep = { step -> if (step == OwnerCreateFixtureStep.CLAIM && first.compareAndSet(false, true)) gate.hold() }
            val winner = callers.launch { f.send(f.replyInput(attempt)) }
            gate.awaitEntered()
            try {
                val waiting = f.send(f.replyInput(attempt))
                f.problem(waiting, 409, "IDEMPOTENCY_IN_PROGRESS")
                assertEquals("1", waiting.getHeader("Retry-After"))
            } finally {
                gate.release()
            }
            assertEquals(201, winner.value().status)
            f.afterStep = {}
        }
        assertEquals(201, f.reply(attempt).status)
        assertEquals(1, f.state().receipts.size)
        assertEquals(before.audits.size + 1, f.state().audits.size)
        assertEquals(events + 2, semanticEvents(f))
        assertEquals(
            true,
            f.observer.queryForObject(
                "SELECT operation = 'OWNER_REPLY' AND target_ids = ARRAY[?::uuid,?::uuid] AND state = 'COMPLETED' " +
                    "AND ack_ids = ARRAY[?::uuid] AND ack_versions = ARRAY[1::bigint] FROM complaint_idempotency_receipts WHERE actor_id = ?",
                Boolean::class.java,
                attempt.parentId,
                attempt.id,
                attempt.id,
                f.actor.id,
            ),
        )
        f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE)
    }

    @Test
    fun `changed parent candidate cannot consume the admitted original reply even before the unique claim`() = withFixture { f ->
        val attempt = f.replyAttempt(f.content())
        val substitute = f.replyAttempt(f.content(), attempt.id, attempt.key)
        val before = f.state()
        f.ingress.withIngress(f.replyInput(attempt)) { context ->
            f.ingress.startOwnerReply(context)
            val identity = f.identity()
            assertEquals(ComplaintPlatform.ANDROID, f.phases.authenticate(identity).platform)
            assertNull(f.phases.replyPreflight(identity, attempt.candidate.tuple).receipt)
            val admitted = f.ingress.admitOwnerReply(context, attempt.candidate.tuple)
            val failure = assertThrows<PersistencePhaseException> { f.phases.reply(identity, substitute.candidate, ComplaintPlatform.ANDROID, admitted) }
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
            assertTrue(failure.cleanupProven)
            assertThrows<PersistencePhaseException> { f.phases.reply(identity, attempt.candidate, ComplaintPlatform.ANDROID, admitted) }
        }
        assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.CLAIM })
        assertEquals(before, f.state())
        assertEquals(201, f.reply(attempt).status)
    }

    @Test
    fun `faults at reply resource parent content audit completion and rejection cleanup leave no abandoned ordinary claim`() = withFixture { f ->
        val parent = f.content()
        for (point in listOf(
            OwnerCreateFixtureStep.RESOURCE,
            OwnerCreateFixtureStep.PARENT_RESOURCE,
            OwnerCreateFixtureStep.PARENT_CONTENT,
            OwnerCreateFixtureStep.CONTENT,
            OwnerCreateFixtureStep.COMPLETE,
            OwnerCreateFixtureStep.DISCARD_RESOURCE,
        )) {
            val attempt = f.replyAttempt(if (point == OwnerCreateFixtureStep.DISCARD_RESOURCE) f.content(pending = true) else parent)
            val before = f.state()
            val events = semanticEvents(f)
            val failed = AtomicBoolean()
            f.afterStep = { step -> if (step == point && failed.compareAndSet(false, true)) throw SyntheticInstallationEnrollmentFailure() }
            try {
                f.problem(f.reply(attempt), 503, "SERVICE_UNAVAILABLE")
                assertTrue(failed.get())
                assertEquals(before, f.state())
                assertEquals(events + 2, semanticEvents(f))
            } finally {
                f.afterStep = {}
            }
            if (point == OwnerCreateFixtureStep.DISCARD_RESOURCE) {
                f.problem(f.reply(attempt), 409, "COMPLAINT_DELETION_PENDING")
                f.assertCharge(before.counters, ComplaintCapacityCharges.NORMAL_RECEIPT)
            } else {
                assertEquals(201, f.reply(attempt).status)
                f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE)
            }
            assertEquals(events + 2, semanticEvents(f))
        }
    }

    @Test
    fun `reply result requires original commit and physical release and unknown commit or failed tail cannot publish success`() {
        for (mode in listOf("RELEASE", "COMMIT", "TAIL")) {
            withFixture { f ->
                val attempt = f.replyAttempt(f.content())
                val before = f.state()
                var retained: ComplaintOwnerCreateOperation? = null
                var observed: PersistencePhaseException? = null
                f.ingress.withIngress(f.replyInput(attempt)) { context ->
                    f.ingress.startOwnerReply(context)
                    val identity = f.identity()
                    assertEquals(ComplaintPlatform.ANDROID, f.phases.authenticate(identity).platform)
                    assertNull(f.phases.replyPreflight(identity, attempt.candidate.tuple).receipt)
                    val admission = f.ingress.admitOwnerReply(context, attempt.candidate.tuple)
                    val phase = f.base.ordinary.ownership.enterComplaintOwnerReply()
                    try {
                        phase.ownerOperation.bindCreate(admission)
                        phase.begin()
                        retained = f.store.reply(identity, attempt.candidate, ComplaintPlatform.ANDROID)
                        val early = assertThrows<PersistencePhaseException> { checkNotNull(retained).result }
                        assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
                        assertFalse(early.cleanupProven)
                        if (mode == "COMMIT") {
                            f.jdbc.execute("CREATE TEMP TABLE kira_owner_reply_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                            assertEquals(2, f.jdbc.update("INSERT INTO kira_owner_reply_commit VALUES (1), (1)"))
                        } else if (mode == "TAIL") {
                            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                                override fun afterCommit(): Unit = throw SyntheticInstallationEnrollmentFailure()
                            })
                        }
                        val commitFailure = runCatching(phase::commit).exceptionOrNull()
                        if (mode == "RELEASE") assertNull(commitFailure) else assertNotNull(commitFailure)
                        commitFailure?.let(phase::recordFailure)
                        if (commitFailure == null) {
                            val unreleased = assertThrows<PersistencePhaseException> { checkNotNull(retained).result }
                            assertEquals(PersistenceDatabaseOutcome.COMMITTED, unreleased.databaseOutcome)
                            assertFalse(unreleased.cleanupProven)
                        }
                    } finally {
                        phase.finish()
                    }
                    if (mode == "RELEASE") {
                        assertTrue(checkNotNull(retained).result.receipt is ComplaintOwnerReceipt.Applied)
                    } else {
                        observed = assertThrows<PersistencePhaseException> { checkNotNull(retained).result }
                    }
                }
                f.assertReleased()
                if (mode == "COMMIT") {
                    assertEquals(PersistenceDatabaseOutcome.UNKNOWN, checkNotNull(observed).databaseOutcome)
                    assertEquals(before, f.state())
                    f.problem(f.replyStatus(attempt), 404, "OPERATION_NOT_FOUND")
                } else {
                    if (mode == "TAIL") assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(observed).databaseOutcome)
                    assertEquals(200, f.replyStatus(attempt).status)
                }
                observed?.let { assertTrue(it.cleanupProven) }
                assertEquals(201, f.reply(attempt).status)
                f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE)
            }
        }
    }

    @Test
    fun `populated predecessor migration permits only the reply resource rejection cell without rewriting data or other objects`() = withFixture { f ->
        assertOwnerReplyReceiptMigration(checkNotNull(f.observer.dataSource))
        f.assertReleased()
    }

    private fun raceOriginalOwnerClaims(f: ComplaintOwnerCreateFixture, inputs: List<MockHttpServletRequest>): List<MockHttpServletResponse> {
        val firstAtClaim = AtomicBoolean()
        val claimBarrierPassed = AtomicBoolean()
        val barrier = CyclicBarrier(2) { claimBarrierPassed.set(true) }
        val lastSql = ConcurrentHashMap<Thread, String>()
        f.beforeStep = { step ->
            lastSql[Thread.currentThread()] = "${TransactionSynchronizationManager.getCurrentTransactionName()}:$step"
            if (step == OwnerCreateFixtureStep.CLAIM) {
                firstAtClaim.set(true)
                barrier.await(500, TimeUnit.MILLISECONDS)
            }
        }
        return try {
            OwnedCallerTestScope().use { callers ->
                val first = callers.launch { f.send(inputs[0]) }
                // Admission deliberately refuses CAS contention. Park the first write before admitting the second original.
                awaitLifecycleFact { firstAtClaim.get() || !first.thread.isAlive }
                assertTrue(firstAtClaim.get()) {
                    "First original refused before CLAIM: status=${first.value().status}, lastSql=${lastSql[first.thread] ?: "NONE"}."
                }
                val second = callers.launch { f.send(inputs[1]) }
                val originals = listOf(first, second)
                val responses = originals.map { it.value() }
                f.assertReleased()
                assertTrue(claimBarrierPassed.get()) {
                    "Both originals must pass CLAIM before retries: statuses=${responses.map { it.status }}, " +
                        "lastSql=${originals.map { lastSql[it.thread] ?: "NONE" }}."
                }
                responses
            }
        } finally {
            f.beforeStep = {}
        }
    }

    private fun storedContent(f: ComplaintOwnerCreateFixture, id: UUID): String = checkNotNull(
        f.observer.queryForObject("SELECT to_jsonb(c)::text FROM complaints c WHERE id = ?", String::class.java, id),
    )

    private fun withFixture(test: (ComplaintOwnerCreateFixture) -> Unit) = withComplaintOwnerCreate(database.value, test)

    private fun semanticEvents(f: ComplaintOwnerCreateFixture): Int = lifecycleField(checkNotNull(lifecycleField(f.ingress, "semantics")), "events") as Int
}
