package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReceipt
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerOperationIdentity
import me.manga.kira.backend.database.complaint.assertOwnerCreateReceiptMigration
import me.manga.kira.backend.security.ComplaintOwnerCreateAdmissionPolicy
import me.manga.kira.backend.security.ownerCreateTestIngress
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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** Actual dormant HTTP/token/normalization/owned-SQL integration. Synthetic run/P data is not activation or deployment evidence. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintOwnerCreateIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintOwnerCreateIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `real returned enrollment and session HTTP token creates one counted report then matching status and owner history`() = withFixture { f ->
        assertEquals(f.actor, f.jwt.verify(f.json(f.enrollmentResponse)["accessToken"].asText()).installation)
        assertEquals(f.actor, f.jwt.verify(f.token).installation)
        val attempt = f.attempt()
        val before = f.state()
        val credentialActivity = f.observer.queryForMap("SELECT last_authenticated_at, credential_version FROM app_installations WHERE id = ?", f.actor.id)
        var releasedSend = false
        val response = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream {
                f.assertReleased()
                releasedSend = true
                return super.getOutputStream()
            }
        }
        f.handler.handleRequest(f.input(attempt), response)
        assertTrue(releasedSend)
        assertEquals(201, response.status)
        assertEquals("""{"id":"${attempt.id}","version":1}""", response.contentAsString)
        assertEquals("/api/v1/complaints/${attempt.id}", response.getHeader("Location"))
        assertEquals("\"complaint-${attempt.id}-v1\"", response.getHeader("ETag"))
        f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE)
        assertEquals(before.audits.size + 1, f.state().audits.size)
        assertEquals(1, f.state().receipts.size)
        assertEquals(
            true,
            f.observer.queryForObject(
                "SELECT ownership = 'INSTALLATION' AND kind = 'REPORT' AND status = 'OPEN' AND version = 1 " +
                    "AND subject = 'Synthetic subject' AND body = E'Synthetic body\\nline' AND app_version IS NULL AND platform = 'ANDROID' " +
                    "AND os_version = 'fixture-os' AND manufacturer = '' AND device_model = '' AND created_at = updated_at FROM complaints WHERE id = ?",
                Boolean::class.java,
                attempt.id,
            ),
        )
        assertEquals(
            true,
            f.observer.queryForObject(
                "SELECT state = 'COMPLETED' AND expires_at = completed_at + interval '192 hours' " +
                    "AND publication_ref IS NULL AND external_event_id IS NULL FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?",
                Boolean::class.java,
                f.actor.id,
                attempt.key,
            ),
        )
        assertEquals(
            "{\"version\": 1}",
            f.observer.queryForObject(
                "SELECT detail::text FROM audit_log WHERE entity_id = ? AND complaint_data_scope_id = ?",
                String::class.java,
                attempt.id.toString(),
                f.run.scope.id,
            ),
        )
        val after = f.state()
        val status = f.status(attempt)
        assertEquals(200, status.status)
        assertEquals("APPLIED", f.json(status)["outcome"].asText())
        assertEquals(201, f.json(status)["originalStatus"].asInt())
        assertEquals(f.json(response), f.json(status)["body"])
        assertEquals(response.getHeader("Location"), f.json(status)["location"].asText())
        assertEquals(response.getHeader("ETag"), f.json(status)["etag"].asText())
        assertNull(status.getHeader("ETag"))
        val history = f.history()
        assertEquals(200, history.status)
        assertEquals(attempt.id.toString(), f.json(history)["items"].single()["id"].asText())
        assertEquals(after, f.state(), "Status/history do not change domain, receipt, audit or capacity state.")
        assertEquals(
            credentialActivity,
            f.observer.queryForMap("SELECT last_authenticated_at, credential_version FROM app_installations WHERE id = ?", f.actor.id),
            "Create/status/history authentication does not mutate credential activity or version.",
        )
    }

    @Test
    fun `receipt first replay is byte identical after content edit removal and unavailable new work admission`() = withFixture { f ->
        val attempt = f.attempt()
        val original = f.create(attempt)
        assertEquals(201, original.status)
        assertEquals(1, f.observer.update("UPDATE complaints SET body = 'Synthetic later edit', version = 2, updated_at = now() WHERE id = ?", attempt.id))
        val editReplay = f.create(attempt)
        assertArrayEquals(original.contentAsByteArray, editReplay.contentAsByteArray)
        assertEquals(original.getHeader("ETag"), editReplay.getHeader("ETag"))
        assertEquals(1, f.observer.update("DELETE FROM complaints WHERE id = ?", attempt.id))
        assertEquals(1, f.observer.update("UPDATE complaint_resource_ids SET state = 'DELETED', deleted_at = now() WHERE id = ?", attempt.id))
        val before = f.state()
        val disabled = f.handler(ownerCreateTestIngress(creates = ComplaintOwnerCreateAdmissionPolicy.Disabled))
        val replay = f.create(attempt, disabled)
        assertEquals(201, replay.status)
        assertArrayEquals(original.contentAsByteArray, replay.contentAsByteArray)
        assertEquals(original.getHeader("Location"), replay.getHeader("Location"))
        assertEquals(original.getHeader("ETag"), replay.getHeader("ETag"))
        assertEquals(1L, f.json(f.status(attempt))["body"]["version"].asLong())
        f.problem(f.create(f.attempt(id = attempt.id, key = attempt.key, body = "Changed dispatched body"), disabled), 409, "IDEMPOTENCY_KEY_REUSED")
        f.problem(f.status(attempt, "A".repeat(43)), 409, "IDEMPOTENCY_KEY_REUSED")
        assertEquals(before, f.state())
    }

    @Test
    fun `uniform resource UUID collision ignores all scopes and states but cannot outrun locked current authentication`() = withFixture { f ->
        val before = f.state()
        val ids = listOf(f.run.scope, ComplaintDataScope.LIVE).flatMap { scope ->
            listOf("LIVE", "DELETION_PENDING", "DELETED").map { state -> f.resource(state, scope) }
        }
        ids.forEach { id ->
            val attempt = f.attempt(id = id)
            val response = f.create(attempt)
            f.problem(response, 409, "COMPLAINT_RESOURCE_ID_REUSED")
            assertFalse(response.contentAsString.contains(id.toString()))
            val status = f.status(attempt)
            assertEquals("""{"outcome":"REJECTED","originalStatus":409,"problemCode":"COMPLAINT_RESOURCE_ID_REUSED"}""", status.contentAsString)
        }
        f.assertCharge(before.counters, ComplaintCapacityCharges.NORMAL_RECEIPT.scaled(ids.size.toLong()))
        assertEquals(before.content, f.state().content)
        assertEquals(before.audits, f.state().audits)
        val owned = f.content()
        val foreignToken = f.json(f.exchange(UUID.randomUUID(), session = false))["accessToken"].asText()
        val foreignAttempt = f.attempt(id = owned)
        val beforeForeign = f.state()
        f.problem(f.create(foreignAttempt, bearer = foreignToken), 409, "COMPLAINT_RESOURCE_ID_REUSED")
        assertEquals("COMPLAINT_RESOURCE_ID_REUSED", f.json(f.status(foreignAttempt, bearer = foreignToken))["problemCode"].asText())
        f.assertCharge(beforeForeign.counters, ComplaintCapacityCharges.NORMAL_RECEIPT)
        assertEquals(beforeForeign.content, f.state().content)
        assertEquals(beforeForeign.audits, f.state().audits)
        val next = f.attempt(id = ids.first())
        val beforeAuthFailure = f.state()
        val collisionReads = f.observations.count { it.first == OwnerCreateFixtureStep.COLLISION }
        val changed = AtomicBoolean()
        f.beforeStep = { step ->
            if (step == OwnerCreateFixtureStep.CLAIM && changed.compareAndSet(false, true)) {
                assertEquals(1, f.observer.update("UPDATE app_installations SET credential_version = credential_version + 1 WHERE id = ?", f.actor.id))
            }
        }
        f.problem(f.create(next), 401, "UNAUTHORIZED")
        assertTrue(changed.get())
        assertEquals(beforeAuthFailure, f.state())
        assertTrue(f.observations.any { it.first == OwnerCreateFixtureStep.CREDENTIAL })
        assertEquals(collisionReads, f.observations.count { it.first == OwnerCreateFixtureStep.COLLISION })
        f.beforeStep = {}
    }

    @Test
    fun `one hundred includes pending reports and replies and serializes concurrent ninety nine to one hundred`() {
        for (concurrent in listOf(false, true)) {
            withFixture { f ->
                val parent = f.content()
                repeat(97) { f.content(pending = it % 2 == 0) }
                f.content(pending = true, parent = parent)
                val attempts = listOf(f.attempt(), f.attempt())
                val before = f.state()
                val responses = if (concurrent) {
                    val barrier = CyclicBarrier(2)
                    f.beforeStep = { step -> if (step == OwnerCreateFixtureStep.CLAIM) barrier.await(1, TimeUnit.SECONDS) }
                    OwnedCallerTestScope().use { callers ->
                        val calls = attempts.map { attempt -> callers.launch { f.send(f.input(attempt)) } }
                        calls.map { it.value() }
                    }.also {
                        f.beforeStep = {}
                        f.assertReleased()
                    }
                } else {
                    attempts.map { f.create(it) }
                }
                // A bounded unrelated counter/run wait may refuse503; the same immutable attempt is then retried, never regenerated.
                val completed = responses.mapIndexed { index, response -> if (response.status == 503) f.create(attempts[index]) else response }
                assertEquals(listOf(201, 409), completed.map { it.status }.sorted())
                val rejected = completed.indexOfFirst { it.status == 409 }
                f.problem(completed[rejected], 409, "COMPLAINT_CAPACITY_REACHED")
                assertEquals("COMPLAINT_CAPACITY_REACHED", f.json(f.status(attempts[rejected]))["problemCode"].asText())
                assertEquals(100, f.state().content.size)
                assertEquals(2, f.state().receipts.size)
                assertEquals(before.audits.size + 1, f.state().audits.size)
                f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE + ComplaintCapacityCharges.NORMAL_RECEIPT)
            }
        }
    }

    @Test
    fun `duplicate first use waits only at the receipt claim then replays one mutation and one logical quota charge`() = withFixture { f ->
        val attempt = f.attempt()
        val before = f.state()
        val events = semanticEvents(f)
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val firstClaim = AtomicBoolean()
            f.afterStep = { step -> if (step == OwnerCreateFixtureStep.CLAIM && firstClaim.compareAndSet(false, true)) gate.hold() }
            val winner = callers.launch { f.send(f.input(attempt)) }
            gate.awaitEntered()
            try {
                val waiting = f.send(f.input(attempt))
                f.problem(waiting, 409, "IDEMPOTENCY_IN_PROGRESS")
                assertEquals("1", waiting.getHeader("Retry-After"))
            } finally {
                gate.release()
            }
            val original = winner.value()
            assertEquals(201, original.status)
            f.afterStep = {}
            val replay = f.create(attempt)
            assertArrayEquals(original.contentAsByteArray, replay.contentAsByteArray)
            assertEquals(200, f.status(attempt).status)
        }
        assertEquals(events + 2, semanticEvents(f), "One actor and one global event for the logical attempt, no quota refund/recharge.")
        f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE)
        assertEquals(before.audits.size + 1, f.state().audits.size)
        assertEquals(1, f.state().receipts.size)
        f.assertReleased()
    }

    @Test
    fun `hard cap and unrelated lock waits are unreceipted unavailable work never durable business rejection or claim conflict`() = withFixture { f ->
        val attempt = f.attempt()
        f.base.counters.lockLastCounter().use {
            val before = f.state()
            val response = f.create(attempt)
            f.problem(response, 503, "SERVICE_UNAVAILABLE")
            assertNull(response.getHeader("Retry-After"))
            assertEquals(before, f.state())
        }
        f.run.lockedRun().use { lock ->
            val before = f.state()
            f.problem(f.create(attempt), 503, "SERVICE_UNAVAILABLE")
            assertEquals(before, f.state())
            lock.rollback()
        }
        assertEquals(22, f.observer.update("UPDATE complaint_capacity_counters SET configuration_closed = true"))
        val closed = f.state()
        f.problem(f.create(attempt), 503, "SERVICE_UNAVAILABLE")
        assertEquals(closed, f.state())
        assertEquals(22, f.observer.update("UPDATE complaint_capacity_counters SET configuration_closed = false"))
        val storage = f.state().counters.getValue("storage_bytes")
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_capacity_counters SET free_units = 0, " +
                    "actual_units = hard_limit - recovery_reserved_units - test_reserved_units WHERE name = 'storage_bytes'",
            ),
        )
        val exhausted = f.state()
        f.problem(f.create(attempt), 503, "SERVICE_UNAVAILABLE")
        assertEquals(exhausted, f.state())
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_capacity_counters SET free_units = ?, actual_units = ? WHERE name = 'storage_bytes'",
                storage.free,
                storage.actual,
            ),
        )
        val before = f.state()
        assertEquals(201, f.create(attempt).status)
        f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE)
    }

    @Test
    fun `receipt lookup is by actor key before scope operation target fingerprint comparison and never reexecutes expired keys`() = withFixture { f ->
        val attempt = f.attempt()
        assertEquals(201, f.create(attempt).status)
        val changes = listOf(
            "data_scope_id = '00000000-0000-0000-0000-000000000000', test_only = false" to "data_scope_id = '${f.run.scope.id}', test_only = true",
            "operation = 'OWNER_EDIT', response_status = 200, response_location = NULL" to
                "operation = 'OWNER_CREATE', response_status = 201, response_location = '/api/v1/complaints/${attempt.id}'",
            "fingerprint = decode(repeat('ab',32),'hex')" to
                "fingerprint = decode('${attempt.candidate.tuple.fingerprintBytes().joinToString("") { "%02x".format(it) }}','hex')",
        )
        for ((change, restore) in changes) {
            f.observer.update("UPDATE complaint_idempotency_receipts SET $change WHERE actor_id = ? AND idempotency_key = ?", f.actor.id, attempt.key)
            val before = f.state()
            f.problem(f.create(attempt), 409, "IDEMPOTENCY_KEY_REUSED")
            f.problem(f.status(attempt), 409, "IDEMPOTENCY_KEY_REUSED")
            assertEquals(before, f.state())
            f.observer.update("UPDATE complaint_idempotency_receipts SET $restore WHERE actor_id = ? AND idempotency_key = ?", f.actor.id, attempt.key)
        }
        f.problem(f.status(f.attempt(key = attempt.key)), 409, "IDEMPOTENCY_KEY_REUSED")
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_idempotency_receipts SET ack_versions = ARRAY[2]::bigint[], " +
                    "response_etag = ? WHERE actor_id = ? AND idempotency_key = ?",
                "\"complaint-${attempt.id}-v2\"",
                f.actor.id,
                attempt.key,
            ),
        )
        val corrupt = f.state()
        f.problem(f.status(attempt), 503, "SERVICE_UNAVAILABLE")
        f.problem(f.create(attempt), 503, "SERVICE_UNAVAILABLE")
        assertEquals(corrupt, f.state(), "Storage's wider APPLIED allowance is not a version-two create acknowledgement.")
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_idempotency_receipts SET ack_versions = ARRAY[1]::bigint[], " +
                    "response_etag = ? WHERE actor_id = ? AND idempotency_key = ?",
                "\"complaint-${attempt.id}-v1\"",
                f.actor.id,
                attempt.key,
            ),
        )
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_idempotency_receipts SET completed_at = statement_timestamp() - interval '193 hours', " +
                    "expires_at = statement_timestamp() - interval '1 hour' WHERE actor_id = ? AND idempotency_key = ?",
                f.actor.id,
                attempt.key,
            ),
        )
        val expired = f.state()
        f.problem(f.status(attempt), 404, "OPERATION_NOT_FOUND")
        f.problem(f.create(attempt), 503, "SERVICE_UNAVAILABLE")
        assertEquals(expired, f.state())
    }

    @Test
    fun `committed authorized deletion key mismatch is visible before create admission without exposing its result`() = withFixture { f ->
        val attempt = f.attempt(id = f.content())
        f.authorizedDelete(attempt)
        val before = f.state()
        val events = semanticEvents(f)
        val publications = f.observer.queryForList(
            "SELECT to_jsonb(r)::text FROM complaint_journal_publications r WHERE data_scope_id = ?",
            String::class.java,
            f.run.scope.id,
        )
        for (response in listOf(f.create(attempt), f.status(attempt))) {
            f.problem(response, 409, "IDEMPOTENCY_KEY_REUSED")
            assertFalse(response.contentAsString.contains("OWNER_DELETE"))
            assertFalse(response.contentAsString.contains(attempt.id.toString()))
        }
        assertEquals(events, semanticEvents(f))
        assertEquals(before, f.state())
        assertEquals(
            publications,
            f.observer.queryForList(
                "SELECT to_jsonb(r)::text FROM complaint_journal_publications r WHERE data_scope_id = ?",
                String::class.java,
                f.run.scope.id,
            ),
        )
        assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.CLAIM })
    }

    @Test
    fun `run configuration state and authenticated platform changes after preflight roll back before collision`() = withFixture { f ->
        val changes = listOf(
            Triple(
                "UPDATE complaint_test_runs SET state = 'SEALED', sealed_at = now() WHERE data_scope_id = '${f.run.scope.id}'",
                "UPDATE complaint_test_runs SET state = 'ACTIVE', sealed_at = NULL WHERE data_scope_id = '${f.run.scope.id}'",
                401,
            ),
            Triple(
                "UPDATE complaint_test_runs SET configuration_hash = decode(repeat('ac',32),'hex') WHERE data_scope_id = '${f.run.scope.id}'",
                "UPDATE complaint_test_runs SET configuration_hash = decode('${f.run.desired.configurationHashBytes().joinToString("") {
                    "%02x".format(it)
                }}','hex') WHERE data_scope_id = '${f.run.scope.id}'",
                503,
            ),
            Triple(
                "UPDATE app_installations SET platform = 'IOS' WHERE id = '${f.actor.id}'",
                "UPDATE app_installations SET platform = 'ANDROID' WHERE id = '${f.actor.id}'",
                401,
            ),
        )
        for ((change, restore, status) in changes) {
            val attempt = f.attempt(id = f.resource())
            val before = f.state()
            val changed = AtomicBoolean()
            f.beforeStep = { step ->
                if (step == OwnerCreateFixtureStep.CLAIM && changed.compareAndSet(false, true)) assertEquals(1, f.observer.update(change))
            }
            f.problem(f.create(attempt), status, if (status == 401) "UNAUTHORIZED" else "SERVICE_UNAVAILABLE")
            assertTrue(changed.get())
            assertEquals(before, f.state())
            assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.COLLISION })
            f.beforeStep = {}
            assertEquals(1, f.observer.update(restore)) // Synthetic fixture restoration, not an activation protocol.
        }
    }

    @Test
    fun `changed body candidate and consumed handoff cannot substitute for the exact admitted transaction`() = withFixture { f ->
        val attempt = f.attempt()
        val substituted = f.attempt(id = attempt.id, key = attempt.key, body = "Substituted synthetic body")
        val before = f.state()
        f.ingress.withIngress(f.input(attempt)) { context ->
            f.ingress.startOwnerCreate(context)
            val identity = f.identity()
            assertEquals(ComplaintPlatform.ANDROID, f.phases.authenticate(identity).platform)
            assertNull(f.phases.preflight(identity, attempt.candidate.tuple).receipt)
            val admitted = f.ingress.admitOwnerCreate(context, attempt.candidate.tuple)
            val failure = assertThrows<PersistencePhaseException> { f.phases.create(identity, substituted.candidate, ComplaintPlatform.ANDROID, admitted) }
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
            assertTrue(failure.cleanupProven)
            assertThrows<PersistencePhaseException> { f.phases.create(identity, attempt.candidate, ComplaintPlatform.ANDROID, admitted) }
        }
        assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.CLAIM })
        assertEquals(before, f.state())
        assertEquals(201, f.create(attempt).status)
        f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE)
    }

    @Test
    fun `database token time is sampled again after actual owner and credential lock acquisition`() = withFixture { f ->
        val attempt = f.attempt()
        val before = f.state()
        f.ingress.withIngress(f.input(attempt)) { context ->
            f.ingress.startOwnerCreate(context)
            // Negative lower-phase facts only: the integrated smoke above always consumes the actual HTTP token.
            val now = Instant.now()
            val expires = now.minusSeconds(59)
            val identity = ComplaintOwnerOperationIdentity(f.actor, 1, expires.minusSeconds(900), expires)
            assertEquals(ComplaintPlatform.ANDROID, f.phases.authenticate(identity).platform)
            assertNull(f.phases.preflight(identity, attempt.candidate.tuple).receipt)
            val admitted = f.ingress.admitOwnerCreate(context, attempt.candidate.tuple)
            val locked = AtomicBoolean()
            f.afterStep = { step ->
                if (step == OwnerCreateFixtureStep.CREDENTIAL) {
                    locked.set(true)
                    while (Instant.now() < identity.expiresAt.plusSeconds(60)) LockSupport.parkNanos(1_000_000)
                }
            }
            val refusal = assertThrows<ComplaintOwnerOperationRejected> { f.phases.create(identity, attempt.candidate, ComplaintPlatform.ANDROID, admitted) }
            assertEquals(ComplaintOwnerOperationFailure.UNAUTHORIZED, refusal.failure)
            assertTrue(locked.get())
            f.afterStep = {}
        }
        assertEquals(before, f.state())
        assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.COLLISION })
        f.assertReleased()
    }

    @Test
    fun `real failures after claim counter resource content audit and completion roll back without abandoned receipts or refunding admission`() =
        withFixture { f ->
            val points = listOf(
                OwnerCreateFixtureStep.CLAIM,
                OwnerCreateFixtureStep.CHARGE,
                OwnerCreateFixtureStep.RESOURCE,
                OwnerCreateFixtureStep.CONTENT,
                OwnerCreateFixtureStep.COMPLETE,
            )
            for (point in points) {
                val attempt = f.attempt()
                val before = f.state()
                val events = semanticEvents(f)
                val failed = AtomicBoolean()
                f.afterStep = { step -> if (step == point && failed.compareAndSet(false, true)) throw SyntheticInstallationEnrollmentFailure() }
                f.problem(f.create(attempt), 503, "SERVICE_UNAVAILABLE")
                assertTrue(failed.get())
                assertEquals(before, f.state())
                assertEquals(events + 2, semanticEvents(f))
                f.afterStep = {}
                assertEquals(201, f.create(attempt).status)
                assertEquals(events + 2, semanticEvents(f))
                f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE)
            }
            val attempt = f.attempt()
            val before = f.state()
            val reachedAudit = AtomicBoolean()
            f.beforeStep = { step ->
                if (step == OwnerCreateFixtureStep.COMPLETE) {
                    assertNotNull(f.jdbc.queryForObject("SELECT id FROM audit_log WHERE entity_id = ?", Long::class.java, attempt.id.toString()))
                    reachedAudit.set(true)
                    throw SyntheticInstallationEnrollmentFailure()
                }
            }
            f.problem(f.create(attempt), 503, "SERVICE_UNAVAILABLE")
            assertTrue(reachedAudit.get())
            assertEquals(before, f.state())
            f.beforeStep = {}
        }

    @Test
    fun `result needs actual commit and physical release and unknown commit or failing committed tail never publishes success`() {
        for (mode in listOf("RELEASE", "COMMIT", "TAIL")) {
            withFixture { f ->
                val attempt = f.attempt()
                val before = f.state()
                var retained: ComplaintOwnerCreateOperation? = null
                var observed: PersistencePhaseException? = null
                f.ingress.withIngress(f.input(attempt)) { context ->
                    f.ingress.startOwnerCreate(context)
                    val identity = f.identity()
                    assertEquals(ComplaintPlatform.ANDROID, f.phases.authenticate(identity).platform)
                    assertNull(f.phases.preflight(identity, attempt.candidate.tuple).receipt)
                    val admission = f.ingress.admitOwnerCreate(context, attempt.candidate.tuple)
                    val phase = f.base.ordinary.ownership.enterComplaintOwnerCreate()
                    try {
                        phase.ownerOperation.bindCreate(admission)
                        phase.begin()
                        retained = f.store.create(identity, attempt.candidate, ComplaintPlatform.ANDROID)
                        val early = assertThrows<PersistencePhaseException> { checkNotNull(retained).result }
                        assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
                        assertFalse(early.cleanupProven)
                        if (mode == "COMMIT") {
                            f.jdbc.execute("CREATE TEMP TABLE kira_owner_create_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                            assertEquals(2, f.jdbc.update("INSERT INTO kira_owner_create_commit VALUES (1), (1)"))
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
                    f.problem(f.status(attempt), 404, "OPERATION_NOT_FOUND")
                } else {
                    if (mode == "TAIL") assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(observed).databaseOutcome)
                    assertEquals(200, f.status(attempt).status)
                }
                observed?.let {
                    assertTrue(it.cleanupProven)
                    assertNull(it.cause)
                    assertTrue(it.suppressed.isEmpty())
                }
                assertEquals(201, f.create(attempt).status)
                f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE)
            }
        }
    }

    @Test
    fun `lost acknowledgement resolves through the same committed receipt without another charge`() = withFixture { f ->
        val attempt = f.attempt()
        val before = f.state()
        val response = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream {
                f.assertReleased()
                return object : ServletOutputStream() {
                    override fun isReady(): Boolean = true
                    override fun setWriteListener(listener: WriteListener) = Unit
                    override fun write(value: Int): Unit = throw IOException("Synthetic lost send")
                }
            }
        }
        assertThrows<IOException> { f.handler.handleRequest(f.input(attempt), response) }
        assertEquals(200, f.status(attempt).status)
        assertEquals(201, f.create(attempt).status)
        assertEquals(1, f.state().receipts.size)
        f.assertCharge(before.counters, ComplaintCapacityCharges.OWNER_CREATE)
    }

    @Test
    fun `populated V14 to V15 preserves all other objects and permits only the new create rejection matrix cell`() = withFixture { f ->
        assertOwnerCreateReceiptMigration(checkNotNull(f.observer.dataSource))
        f.assertReleased()
    }

    private fun withFixture(test: (ComplaintOwnerCreateFixture) -> Unit) = withComplaintOwnerCreate(database.value, test)

    private fun semanticEvents(f: ComplaintOwnerCreateFixture): Int = lifecycleField(checkNotNull(lifecycleField(f.ingress, "semantics")), "events") as Int
}
