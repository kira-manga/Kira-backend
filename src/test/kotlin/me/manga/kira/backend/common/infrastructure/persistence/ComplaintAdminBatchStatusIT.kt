package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.servlet.ServletOutputStream
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusOperation
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.security.ComplaintAdminBatchStatusAdmissionPolicy
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import me.manga.kira.backend.security.adminBatchStatusTestIngress
import me.manga.kira.backend.security.ownerCreateTestCapacityPolicy
import me.manga.kira.backend.support.JwtTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.dao.DataAccessException
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** Actual dormant TEST HTTP/JWT/complaint grant/ordinary PG+JPA path. No activated issuer or deletion/publication is inferred. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintAdminBatchStatusIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintAdminBatchStatusIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun realOneTargetThenReportReplyAndOtherOwnerBatchHaveOneSortedLosslessAckAndOneAuditPerTransition() = withFixture { s ->
        val e = s.content
        val f = s.base
        val report = e.report()
        applied(s, AdminBatchStatusAttempt(listOf(report to 1L)))
        val reply = f.replyAttempt(report)
        assertEquals(201, f.reply(reply).status)
        val otherToken = f.json(f.exchange(UUID.randomUUID(), session = false))["accessToken"].asText()
        val other = e.report(otherToken)
        val single = ComplaintAdminStatusFixture(e)
        val closureProof = e.proof()
        e.acknowledged(single.change(AdminStatusAttempt(other, ComplaintAdminStatusOperation.ADMIN_CLOSURE, reason = "Synthetic original closure"), closureProof.token), other, 2)
        // Legal preexisting version boundary, not a fabricated batch result or transition.
        val large = 9_007_199_254_740_992L
        assertEquals(1, e.observer.update("UPDATE complaints SET version = ? WHERE id = ?", large, reply.id))
        val attempt = AdminBatchStatusAttempt(listOf(report to 2L, reply.id to large, other to 2L).sortedByDescending { it.first.toString() }, ComplaintStatus.RESOLVED)
        applied(s, attempt)
        assertEquals(true, e.observer.queryForObject(
            "SELECT closure_reason IS NULL AND closure_actor_id IS NULL AND closure_provenance IS NULL AND closed_at IS NULL FROM complaints WHERE id = ?",
            Boolean::class.java, other,
        ))
        assertEquals("REPLY", e.observer.queryForObject("SELECT kind FROM complaints WHERE id = ?", String::class.java, reply.id))
    }

    @Test
    fun fiftyPaidPreexistingTargetsStillUseOneReceiptProofAndSemanticAdmissionAndReplayBypassesExhaustion() = withLargeFixture { s ->
        val e = s.content
        val f = s.base
        // Explicitly synthetic PREEXISTING content setup. Each real row/reservation is paid below; no receipt, grant or audit is fabricated.
        val ids = List(50) { f.content() }
        val setup = (ComplaintCapacityCharges.RESOURCE_ID + ComplaintCapacityCharges.INSTALLATION_CONTENT_V1).scaled(50)
        for (counter in ComplaintCapacityCounter.entries) if (setup[counter] != 0L) {
            assertEquals(1, e.observer.update(
                "UPDATE complaint_capacity_counters SET actual_units = actual_units + ?, free_units = free_units - ? WHERE name = ?",
                setup[counter], setup[counter], counter.storedName,
            ))
        }
        val attempt = AdminBatchStatusAttempt(ids.sortedByDescending(UUID::toString).map { it to 1L })
        applied(s, attempt)
        val fresh = e.proof()
        val before = e.state()
        s.acknowledged(s.change(attempt.copy(targets = attempt.targets.reversed()), fresh.token), attempt)
        assertEquals(before, e.state())
        e.problem(s.change(attempt.copy(key = UUID.randomUUID(), status = ComplaintStatus.PLANNED), fresh.token), 429, "RATE_LIMITED")
        assertEquals(before, e.state())
        assertFalse(used(s, fresh.token))
        e.problem(s.change(attempt.copy(key = UUID.randomUUID(), targets = attempt.targets + (UUID.randomUUID() to 1L)), fresh.token), 400, "VALIDATION_FAILED")
        assertEquals(before, e.state())
    }

    @Test
    fun staleNoChangeMissingNoticePendingAndExhaustedMemberRejectTheEntireBatchBeforeAnyRowWrite() = withFixture { s ->
        val e = s.content
        val f = s.base
        val ids = listOf(e.report(), e.report()).sortedBy(UUID::toString)
        val notice = f.replyNotice() // Shape-only immutable negative fixture; not a notice activation/producer.
        val good = ids.first()
        val bad = ids.last()
        data class Case(val kind: String, val status: Int, val code: String)
        for (case in listOf(
            Case("STALE", 412, "PRECONDITION_FAILED"), Case("NO_CHANGE", 409, "COMPLAINT_NO_CHANGE"),
            Case("MISSING", 404, "COMPLAINT_NOT_FOUND"), Case("NOTICE", 404, "COMPLAINT_NOT_FOUND"),
            Case("RESOURCE", 409, "COMPLAINT_DELETION_PENDING"), Case("OWNER", 409, "COMPLAINT_DELETION_PENDING"),
            Case("CREDENTIAL", 409, "COMPLAINT_DELETION_PENDING"), Case("EXHAUSTED", 409, "COMPLAINT_INVALID_TRANSITION"),
        )) {
            val selected = when (case.kind) { "MISSING" -> UUID.randomUUID(); "NOTICE" -> notice; else -> bad }
            val version = when (case.kind) { "STALE" -> 2L; "EXHAUSTED" -> Long.MAX_VALUE; else -> 1L }
            val pendingTable = when (case.kind) { "RESOURCE" -> "complaint_resource_ids"; "OWNER" -> "complaint_installation_ids"; "CREDENTIAL" -> "app_installations"; else -> null }
            val pendingId = if (case.kind == "RESOURCE") bad else f.actor.id
            if (pendingTable != null) assertEquals(1, e.observer.update("UPDATE $pendingTable SET state = 'DELETION_PENDING' WHERE id = ?", pendingId))
            if (case.kind == "NO_CHANGE") assertEquals(1, e.observer.update("UPDATE complaints SET status = 'IN_PROGRESS' WHERE id = ?", bad))
            if (case.kind == "EXHAUSTED") assertEquals(1, e.observer.update("UPDATE complaints SET version = ? WHERE id = ?", Long.MAX_VALUE, bad))
            try {
                val attempt = AdminBatchStatusAttempt(listOf(good to 1L, selected to version))
                val proof = e.proof()
                val before = e.state()
                f.observations.clear()
                val response = s.change(attempt, proof.token)
                e.problem(response, case.status, case.code, consumed = true)
                e.association(response, proof.grantId)
                assertTrue(used(s, proof.token))
                assertEquals(before.ownerState.content, e.state().ownerState.content)
                assertEquals(before.ownerState.audits, e.state().ownerState.audits)
                assertEquals(before.ownerState.resources, e.state().ownerState.resources)
                assertEquals(before.run, e.state().run)
                assertEquals(before.journal, e.state().journal)
                assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.ADMIN_STATUS_CHANGE }, "No earlier valid target may be written, even transiently.")
                f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT)
                val committed = e.state()
                e.problem(s.change(attempt), case.status, case.code, consumed = true)
                assertEquals(committed, e.state())
            } finally {
                if (pendingTable != null) assertEquals(1, e.observer.update("UPDATE $pendingTable SET state = ? WHERE id = ?", if (case.kind == "RESOURCE") "LIVE" else "ACTIVE", pendingId))
                if (case.kind == "NO_CHANGE") assertEquals(1, e.observer.update("UPDATE complaints SET status = 'OPEN' WHERE id = ?", bad))
                if (case.kind == "EXHAUSTED") assertEquals(1, e.observer.update("UPDATE complaints SET version = 1 WHERE id = ?", bad))
            }
        }
    }

    @Test
    fun actualComplaintScopeProofAndCurrentDatabaseAdminRemainRequiredAfterPreflightAndGrantConsumption() = withFixture { s ->
        val e = s.content
        val f = s.base
        val attempt = AdminBatchStatusAttempt(listOf(e.report() to 1L, e.report() to 1L))
        val proof = e.proof()
        val source = e.proof(ScopedAdminStepUpScope.SOURCE)
        val before = e.state()
        for (bearer in listOf(null, "invalid", f.token, JwtTestSupport.tamperSignature(e.token))) {
            e.problem(s.change(attempt, proof.token, bearer), 401, "UNAUTHORIZED")
            assertEquals(before, e.state())
        }
        for (submitted in listOf(null, source.token)) {
            e.problem(s.change(attempt, submitted), 401, "ADMIN_STEP_UP_REQUIRED")
            assertEquals(before, e.state())
        }
        assertFalse(used(s, source.token))
        for ((change, restore, code, status) in listOf(
            listOf("enabled = false", "enabled = true", "UNAUTHORIZED", "401"),
            listOf("credential_version = 1", "credential_version = 0", "UNAUTHORIZED", "401"),
            listOf("role = 'USER'", "role = 'ADMIN'", "FORBIDDEN", "403"),
        )) {
            val reached = AtomicBoolean()
            f.beforeStep = { step -> if (step == OwnerCreateFixtureStep.COUNTERS && reached.compareAndSet(false, true)) {
                assertEquals(true, f.jdbc.queryForObject("SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE id = ?", Boolean::class.java, proof.grantId))
                // Raw observer only: never attach a foreign JdbcTemplate resource to the original Spring phase.
                assertEquals(1, outsideUpdate(s, "UPDATE users SET $change WHERE id = ?", e.ordinary.userId))
            } }
            try {
                e.problem(s.change(attempt, proof.token), status.toInt(), code)
                assertTrue(reached.get())
                assertEquals(before, e.state())
                assertFalse(used(s, proof.token))
            } finally {
                f.beforeStep = {}
                assertEquals(1, e.observer.update("UPDATE users SET $restore WHERE id = ?", e.ordinary.userId))
            }
        }
        s.acknowledged(s.change(attempt, proof.token, JwtTestSupport.mint(e.ordinary.userId, role = "USER")), attempt)
        assertTrue(used(s, proof.token), "Signed diagnostic USER role never overrides current DB ADMIN.")
        f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.adminBatchStatus(2))
    }

    @Test
    fun secondRowSecondAuditCommitAndLostCommittedTailCannotExposeAPartialBatchOrLoseTheOriginalRetry() {
        for (fault in listOf("ROW", "AUDIT", "COMMIT", "TAIL")) withFixture { s ->
            val e = s.content
            val f = s.base
            val attempt = AdminBatchStatusAttempt(listOf(e.report() to 1L, e.report() to 1L).sortedBy { it.first.toString() })
            val proof = e.proof()
            val before = e.state()
            val writes = AtomicInteger()
            val reached = AtomicBoolean()
            if (fault == "AUDIT") {
                // Fault at the real second JPA insertion, not a fake audit/receipt completion. Fixture-private DDL is always removed.
                e.observer.execute("""CREATE FUNCTION kira_batch_status_audit_fault() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                    BEGIN IF NEW.action = 'COMPLAINT_STATUS_CHANGED' AND NEW.entity_id = '${attempt.targets.last().first}'
                    THEN RAISE EXCEPTION 'Synthetic batch audit failure'; END IF; RETURN NEW; END ${'$'}${'$'}""")
                e.observer.execute("CREATE TRIGGER kira_batch_status_audit_fault BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION kira_batch_status_audit_fault()")
            }
            f.observations.clear()
            f.afterStep = { step ->
                if (step == OwnerCreateFixtureStep.ADMIN_STATUS_CHANGE && writes.incrementAndGet() == 2) {
                    assertEquals(1L, f.jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_STATUS_CHANGED'", Long::class.java, e.scope.id,
                    ))
                    if (fault == "ROW" || fault == "AUDIT") reached.set(true)
                    if (fault == "ROW") throw SyntheticInstallationEnrollmentFailure()
                }
                if (step == OwnerCreateFixtureStep.COMPLETE && reached.compareAndSet(false, true)) {
                    assertEquals(2L, f.jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_STATUS_CHANGED'", Long::class.java, e.scope.id,
                    ))
                    if (fault == "COMMIT") {
                        f.jdbc.execute("CREATE TEMP TABLE kira_batch_status_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, f.jdbc.update("INSERT INTO kira_batch_status_commit VALUES (1), (1)"))
                    }
                    if (fault == "TAIL") TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit(): Unit = throw SyntheticInstallationEnrollmentFailure()
                    })
                }
            }
            try {
                e.problem(s.change(attempt, proof.token), 503, "SERVICE_UNAVAILABLE")
                assertTrue(reached.get())
                assertEquals(2, writes.get())
            } finally {
                f.afterStep = {}
                if (fault == "AUDIT") {
                    e.observer.execute("DROP TRIGGER IF EXISTS kira_batch_status_audit_fault ON audit_log")
                    e.observer.execute("DROP FUNCTION IF EXISTS kira_batch_status_audit_fault()")
                }
            }
            val outcome = f.observations.last().second.phase.databaseOutcome()
            if (fault == "TAIL") {
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, outcome)
                assertTrue(used(s, proof.token))
                val committed = e.state()
                val replay = s.change(attempt)
                s.acknowledged(replay, attempt)
                e.association(replay, proof.grantId)
                assertEquals(committed, e.state())
            } else {
                assertEquals(if (fault == "COMMIT") PersistenceDatabaseOutcome.UNKNOWN else PersistenceDatabaseOutcome.ROLLED_BACK, outcome)
                assertEquals(before, e.state(), "Rows, N audits, receipt, proof and all counter dimensions roll back together.")
                assertFalse(used(s, proof.token))
                s.acknowledged(s.change(attempt, proof.token), attempt)
            }
            f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.adminBatchStatus(2))
        }
    }

    @Test
    fun replayPrecedesNewProofQuotaAndLiveTargetRunChecksAndKeepsOriginalGrantAfterCleanupAndLegacyNull() = withFixture { s ->
        val e = s.content
        val f = s.base
        val attempt = AdminBatchStatusAttempt(listOf(e.report() to 1L, e.report() to 1L))
        val proof = e.proof()
        val original = s.change(attempt, proof.token)
        s.acknowledged(original, attempt)
        e.association(original, proof.grantId)
        applied(s, attempt.copy(key = UUID.randomUUID(), targets = attempt.targets.map { it.first to 2L }, status = ComplaintStatus.PLANNED))
        val fresh = e.proof()
        assertEquals(0L, e.observer.queryForObject("SELECT count(*) FROM admin_step_up_grants WHERE id = ?", Long::class.java, proof.grantId))
        val disabled = ComplaintAdminBatchStatusFixture(e, adminBatchStatusTestIngress(f.policy, batch = ComplaintAdminBatchStatusAdmissionPolicy.Disabled))
        for (variant in listOf(
            attempt.copy(status = ComplaintStatus.RESOLVED), attempt.copy(targets = attempt.targets.map { it.first to 2L }),
            attempt.copy(targets = attempt.targets.dropLast(1)), attempt.copy(targets = listOf(UUID.randomUUID() to 1L)),
        )) e.problem(disabled.change(variant), 409, "IDEMPOTENCY_KEY_REUSED")
        e.problem(e.edit(AdminContentAttempt(attempt.targets.first().first, key = attempt.key, version = 3)), 409, "IDEMPOTENCY_KEY_REUSED")
        // Shape-only terminal/erasure fixture, explicitly not a deletion or terminal protocol execution.
        f.run.terminalState("SEALED")
        f.eraseReplyParent(attempt.targets.first().first)
        val before = e.state()
        for (submitted in listOf(null, fresh.token)) {
            val replay = disabled.change(attempt.copy(targets = attempt.targets.reversed()), submitted)
            s.acknowledged(replay, attempt)
            assertArrayEquals(original.contentAsByteArray, replay.contentAsByteArray)
            e.association(replay, proof.grantId)
            assertEquals(before, e.state())
        }
        e.problem(disabled.change(attempt.copy(key = UUID.randomUUID()), fresh.token), 503, "SERVICE_UNAVAILABLE")
        e.problem(s.change(attempt.copy(key = UUID.randomUUID()), fresh.token), 404, "NOT_FOUND")
        assertEquals(before, e.state())
        assertFalse(used(s, fresh.token))
        assertEquals(1, e.observer.update(
            "UPDATE complaint_idempotency_receipts SET consumed_grant_id = NULL WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?",
            e.ordinary.userId, attempt.key,
        ))
        val historical = e.state()
        val replay = disabled.change(attempt, fresh.token)
        s.acknowledged(replay, attempt)
        e.association(replay, null)
        assertEquals(historical, e.state())
        assertFalse(used(s, fresh.token))
    }

    @Test
    fun ownersThenResourcesThenContentLockInCanonicalOrderAndReverseOrderContendersCannotPartiallyApply() = withFixture { s ->
        val e = s.content
        val f = s.base
        val other = UUID.randomUUID()
        val token = f.json(f.exchange(other, session = false))["accessToken"].asText()
        val ids = listOf(e.report(), e.report(token)).sortedBy(UUID::toString)
        val owners = listOf(f.actor.id, other).sortedBy(UUID::toString)
        val ownerLocks = AtomicInteger()
        val credentials = AtomicInteger()
        val resources = AtomicInteger()
        val content = AtomicInteger()
        // One independent raw observer avoids connection creation inside the original two-second phase.
        checkNotNull(e.observer.dataSource).connection.use { observer ->
            assertTrue(observer.autoCommit)
            f.afterStep = { step -> when (step) {
                OwnerCreateFixtureStep.OWNER -> {
                    val count = ownerLocks.incrementAndGet()
                    for ((index, id) in owners.withIndex()) assertEquals(index < count, isLocked(observer, "complaint_installation_ids", id))
                    owners.forEach { assertFalse(isLocked(observer, "app_installations", it)) }
                    ids.forEach { assertFalse(isLocked(observer, "complaint_resource_ids", it)) }
                }
                OwnerCreateFixtureStep.CREDENTIAL -> {
                    val count = credentials.incrementAndGet()
                    owners.forEach { assertTrue(isLocked(observer, "complaint_installation_ids", it)) }
                    for ((index, id) in owners.withIndex()) assertEquals(index < count, isLocked(observer, "app_installations", id))
                    ids.forEach { assertFalse(isLocked(observer, "complaint_resource_ids", it)) }
                }
                OwnerCreateFixtureStep.PARENT_RESOURCE -> {
                    val count = resources.incrementAndGet()
                    for ((index, id) in ids.withIndex()) assertEquals(index < count, isLocked(observer, "complaint_resource_ids", id))
                    ids.forEach { assertFalse(isLocked(observer, "complaints", it)) }
                }
                OwnerCreateFixtureStep.PARENT_CONTENT -> {
                    val count = content.incrementAndGet() - ids.size // First N reads are nonlocking discovery.
                    if (count > 0) for ((index, id) in ids.withIndex()) assertEquals(index < count, isLocked(observer, "complaints", id))
                }
                else -> Unit
            } }
            try { applied(s, AdminBatchStatusAttempt(ids.reversed().map { it to 1L })) } finally { f.afterStep = {} }
        }
        assertEquals(2, ownerLocks.get())
        assertEquals(2, credentials.get())
        assertEquals(2, resources.get())
        assertEquals(4, content.get())

        val left = AdminBatchStatusAttempt(ids.reversed().map { it to 2L }, ComplaintStatus.PLANNED)
        val right = left.copy(key = UUID.randomUUID(), targets = left.targets.reversed())
        val leftProof = e.proof()
        val rightProof = e.proof()
        val before = e.state()
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val first = AtomicBoolean()
            f.beforeStep = { step -> if (step == OwnerCreateFixtureStep.COUNTERS && first.compareAndSet(false, true)) gate.hold() }
            try {
                val waiting = callers.launch { s.send(s.input(left, leftProof.token)) }
                gate.awaitEntered()
                val winning = s.send(s.input(right, rightProof.token))
                gate.release()
                e.problem(waiting.value(), 412, "PRECONDITION_FAILED", consumed = true)
                s.acknowledged(winning, right)
            } finally { gate.release(); f.beforeStep = {} }
        }
        assertTrue(used(s, leftProof.token) && used(s, rightProof.token))
        assertEquals(before.ownerState.audits.size + 2, e.state().ownerState.audits.size)
        f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT.scaled(2) + ComplaintCapacityCharges.AUDIT.scaled(2))
    }

    @Test
    fun conflictingUncommittedClaimTimesOutWithoutProofAndExactReplayPaysNothingTwice() = withFixture { s ->
        val e = s.content
        val f = s.base
        val attempt = AdminBatchStatusAttempt(listOf(e.report() to 1L, e.report() to 1L))
        val proof = e.proof()
        val spare = e.proof()
        val before = e.state()
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val first = AtomicBoolean()
            f.afterStep = { step -> if (step == OwnerCreateFixtureStep.CLAIM && first.compareAndSet(false, true)) gate.hold() }
            try {
                val held = callers.launch { s.send(s.input(attempt, proof.token)) }
                gate.awaitEntered()
                val blocked = s.send(s.input(attempt.copy(targets = attempt.targets.reversed()), spare.token))
                e.problem(blocked, 409, "IDEMPOTENCY_IN_PROGRESS")
                assertEquals("1", blocked.getHeader("Retry-After"))
                assertEquals(before, e.state())
                gate.release()
                s.acknowledged(held.value(), attempt)
            } finally { gate.release(); f.afterStep = {} }
        }
        val committed = e.state()
        s.acknowledged(s.change(attempt, spare.token), attempt)
        assertEquals(committed, e.state())
        assertFalse(used(s, spare.token))
        f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.adminBatchStatus(2))
    }

    @Test
    fun sourceOnlyMaintenanceCapacityAndOriginalReadCommittedReleaseGuardsRemainUnchanged() = withFixture { s ->
        val e = s.content
        val f = s.base
        val attempt = AdminBatchStatusAttempt(listOf(e.report() to 1L, e.report() to 1L))
        val admission = OrdinaryPersistenceAdmission.sourceOnly(4)
        val sourceOnly = PersistencePhaseOwnership(admission, GuardedJpaTransactionManager(e.ordinary.entityManagerFactory, e.ordinary.pool))
        for (enter in listOf(sourceOnly::enterComplaintAdminBatchStatusPreflight, sourceOnly::enterComplaintAdminBatchStatus)) {
            val failure = assertThrows<PersistencePhaseException> { enter() }
            assertEquals(PersistenceDatabaseOutcome.NONE, failure.databaseOutcome)
            assertTrue(failure.cleanupProven)
            assertEquals(0, admission.activeOwners())
        }
        val proof = e.proof()
        val before = e.state()
        val storage = before.ownerState.counters.getValue(ComplaintCapacityCounter.STORAGE_BYTES.storedName)
        assertEquals(1, e.observer.update("UPDATE complaint_capacity_counters SET free_units = 0, actual_units = hard_limit - recovery_reserved_units - test_reserved_units WHERE name = 'storage_bytes'"))
        try {
            val full = e.state()
            e.problem(s.change(attempt, proof.token), 503, "SERVICE_UNAVAILABLE")
            assertEquals(full, e.state())
            assertFalse(used(s, proof.token))
        } finally {
            assertEquals(1, e.observer.update("UPDATE complaint_capacity_counters SET free_units = ?, actual_units = ? WHERE name = 'storage_bytes'", storage.free, storage.actual))
        }
        f.observations.clear()
        f.lock("SELECT pg_advisory_xact_lock(hashtextextended('complaint-maintenance-v1', 0))").use { held ->
            try {
                e.problem(s.change(attempt, proof.token), 503, "SERVICE_UNAVAILABLE")
                assertEquals(before, e.state())
                assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.CLAIM || it.first == OwnerCreateFixtureStep.ADMIN_GRANT_LOCK })
            } finally { held.rollback() }
        }
        val seen = mutableSetOf<OwnerCreateFixtureStep>()
        f.afterStep = { step -> if (step == OwnerCreateFixtureStep.OBSERVE || step == OwnerCreateFixtureStep.CLAIM) {
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
            assertEquals("read committed", f.jdbc.queryForObject("SHOW transaction_isolation", String::class.java))
            assertEquals(step == OwnerCreateFixtureStep.OBSERVE, TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            seen.add(step)
        } }
        val response = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream { f.assertReleased(); return super.getOutputStream() }
        }
        try { s.handler.handleRequest(s.input(attempt, proof.token), response) } finally { f.afterStep = {} }
        s.acknowledged(response, attempt)
        assertEquals(setOf(OwnerCreateFixtureStep.OBSERVE, OwnerCreateFixtureStep.CLAIM), seen)
        f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.adminBatchStatus(2))
    }

    @Test
    fun lateJwtExpiryAfterEveryTargetLockRollsBackProofAndAllRowsAndOwnerDiscoveryIsRevalidated() = withFixture { s ->
        val e = s.content
        val f = s.base
        val ids = listOf(e.report(), e.report())
        val attempt = AdminBatchStatusAttempt(ids.map { it to 1L })
        val proof = e.proof()
        val before = e.state()
        val until = e.observer.queryForObject("SELECT date_trunc('second', clock_timestamp()) + interval '2 seconds'", Timestamp::class.java)!!.toInstant()
        awaitDatabaseTime(s, until.minusMillis(750), 2_000_000_000L)
        val expires = until.minus(e.userJwt.properties.clockSkew)
        val bearer = JwtTestSupport.mint(e.ordinary.userId, issuedAt = expires.minusSeconds(60), expiresAt = expires)
        val reads = AtomicInteger()
        f.observations.clear()
        f.afterStep = { step -> if (step == OwnerCreateFixtureStep.PARENT_CONTENT && reads.incrementAndGet() == 4) {
            assertEquals(true, f.jdbc.queryForObject("SELECT clock_timestamp() < ?", Boolean::class.java, Timestamp.from(until)))
            awaitDatabaseTime(s, until, 1_000_000_000L)
        } }
        try {
            e.problem(s.change(attempt, proof.token, bearer), 401, "UNAUTHORIZED")
            assertEquals(4, reads.get())
            assertEquals(before, e.state())
            assertFalse(used(s, proof.token))
            assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.ADMIN_STATUS_CHANGE })
        } finally { f.afterStep = {} }

        val other = UUID.randomUUID()
        assertEquals(201, f.exchange(other, session = false).status)
        val fresh = e.proof()
        val prior = e.state()
        val discovered = AtomicInteger()
        val changed = ids.sortedBy(UUID::toString).last()
        f.afterStep = { step -> if (step == OwnerCreateFixtureStep.PARENT_CONTENT && discovered.incrementAndGet() == 2) {
            assertEquals(1, outsideUpdate(s, "UPDATE complaints SET owner_id = ? WHERE id = ?", other, changed))
        } }
        try {
            e.problem(s.change(attempt, fresh.token), 404, "COMPLAINT_NOT_FOUND", consumed = true)
            assertEquals(4, discovered.get())
            assertEquals(prior.ownerState.audits, e.state().ownerState.audits)
            ids.forEach { assertEquals(1L, e.observer.queryForObject("SELECT version FROM complaints WHERE id = ?", Long::class.java, it)) }
            f.assertCharge(prior.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT)
        } finally {
            f.afterStep = {}
            assertEquals(1, e.observer.update("UPDATE complaints SET owner_id = ? WHERE id = ?", f.actor.id, changed))
        }
    }

    @Test
    fun v24RetainsTheRejectedOriginalGrantButDoesNotAuthorizeBatchDeleteAssociation() = withFixture { s ->
        val e = s.content
        val attempt = AdminBatchStatusAttempt(listOf(e.report() to 2L))
        val a = e.proof()
        e.problem(s.change(attempt, a.token), 412, "PRECONDITION_FAILED", consumed = true)
        val b = e.proof()
        val before = e.state()
        val replay = s.change(attempt, b.token)
        e.problem(replay, 412, "PRECONDITION_FAILED", consumed = true)
        e.association(replay, a.grantId)
        assertEquals(before, e.state())
        assertFalse(used(s, b.token))
        // Constraint probe on an actual rejected receipt, not a fabricated APPLIED batch/deletion/publication.
        val denied = assertThrows<DataAccessException> { e.observer.update(
            "UPDATE complaint_idempotency_receipts SET operation = 'ADMIN_BATCH_DELETE' WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?",
            e.ordinary.userId, attempt.key,
        ) }
        assertEquals("23514", (denied.cause as SQLException).sqlState)
        assertEquals(before, e.state())
        assertEquals(1, e.observer.update(
            "UPDATE complaint_idempotency_receipts SET consumed_grant_id = NULL WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?",
            e.ordinary.userId, attempt.key,
        ))
        val historical = e.state()
        val legacyReplay = s.change(attempt, b.token)
        e.problem(legacyReplay, 412, "PRECONDITION_FAILED", consumed = true)
        e.association(legacyReplay, null)
        assertEquals(historical, e.state())
    }

    private fun applied(s: ComplaintAdminBatchStatusFixture, attempt: AdminBatchStatusAttempt): MockHttpServletResponse {
        val e = s.content
        val f = s.base
        val proof = e.proof()
        val before = e.state()
        val immutable = attempt.targets.associate { it.first to s.immutable(it.first) }
        val from = attempt.targets.associate { it.first to e.observer.queryForObject("SELECT status FROM complaints WHERE id = ?", String::class.java, it.first)!! }
        f.observations.clear()
        val response = s.change(attempt, proof.token)
        s.acknowledged(response, attempt)
        e.association(response, proof.grantId)
        assertTrue(used(s, proof.token))
        for ((id, oldVersion) in attempt.targets) {
            assertEquals(immutable.getValue(id), s.immutable(id))
            assertEquals(true, e.observer.queryForObject(
                "SELECT status = ? AND version = ? FROM complaints WHERE id = ?", Boolean::class.java, attempt.status.name, oldVersion + 1, id,
            ))
            assertEquals(true, e.observer.queryForObject(
                "SELECT a.complaint_actor_kind = 'ADMIN' AND a.actor_user_id = ? AND a.created_at = c.updated_at " +
                    "AND a.detail = jsonb_build_object('version', ?::bigint, 'fromStatus', ?::text, 'toStatus', ?::text) " +
                    "FROM audit_log a JOIN complaints c ON c.id::text = a.entity_id WHERE a.complaint_data_scope_id = ? AND c.id = ? " +
                    "AND a.action = 'COMPLAINT_STATUS_CHANGED' AND a.detail ->> 'version' = ?",
                Boolean::class.java, e.ordinary.userId, oldVersion + 1, from.getValue(id), attempt.status.name, e.scope.id, id, (oldVersion + 1).toString(),
            ))
        }
        val ids = attempt.targets.sortedBy { it.first.toString() }.joinToString(prefix = "{", postfix = "}", separator = ",") { it.first.toString() }
        val versions = attempt.targets.sortedBy { it.first.toString() }.joinToString(prefix = "{", postfix = "}", separator = ",") { (it.second + 1).toString() }
        assertEquals(true, e.observer.queryForObject(
            "SELECT operation = 'ADMIN_BATCH_STATUS' AND state = 'COMPLETED' AND outcome = 'APPLIED' AND response_status = 200 " +
                "AND target_ids = ?::uuid[] AND ack_ids = target_ids AND ack_versions = ?::bigint[] AND consumed_grant_id = ? " +
                "AND response_etag IS NULL AND response_location IS NULL AND publication_ref IS NULL AND authorized_at IS NULL " +
                "AND expires_at = completed_at + interval '192 hours' FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?",
            Boolean::class.java, ids, versions, proof.grantId, e.ordinary.userId, attempt.key,
        ))
        assertEquals(before.receipts.size + 1, e.state().receipts.size)
        assertEquals(before.ownerState.audits.size + attempt.targets.size, e.state().ownerState.audits.size)
        assertEquals(before.ownerState.resources, e.state().ownerState.resources)
        assertEquals(before.run, e.state().run)
        assertEquals(before.journal, e.state().journal)
        for (step in listOf(OwnerCreateFixtureStep.CLAIM, OwnerCreateFixtureStep.ADMIN_GRANT_LOCK, OwnerCreateFixtureStep.COUNTERS, OwnerCreateFixtureStep.COMPLETE)) {
            assertEquals(1, f.observations.count { it.first == step })
        }
        assertEquals(attempt.targets.size, f.observations.count { it.first == OwnerCreateFixtureStep.ADMIN_STATUS_CHANGE })
        val writer = f.observations.last().second.phase
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, writer.databaseOutcome())
        assertEquals(1, f.observations.filter { it.second.phase === writer }.map { it.second.identity }.distinct().size)
        f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.adminBatchStatus(attempt.targets.size))
        return response
    }

    private fun used(s: ComplaintAdminBatchStatusFixture, proof: String): Boolean = s.content.observer.queryForObject(
        "SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE token_hash = ?", Boolean::class.java, Sha256.hexUtf8(proof),
    )!!

    /** Independent raw observer, never DataSourceUtils/JdbcTemplate/guard suspension in an owned-phase callback. */
    private fun outsideUpdate(s: ComplaintAdminBatchStatusFixture, sql: String, vararg values: Any): Int =
        checkNotNull(s.content.observer.dataSource).connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = 2
                values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }

    private fun isLocked(connection: Connection, table: String, id: UUID): Boolean {
        require(table in setOf("complaint_installation_ids", "app_installations", "complaint_resource_ids", "complaints"))
        return try {
            connection.prepareStatement("SELECT id FROM $table WHERE id = ? FOR UPDATE NOWAIT").use { statement ->
                statement.queryTimeout = 2
                statement.setObject(1, id)
                statement.executeQuery().use { assertTrue(it.next()) }
            }
            false
        } catch (failure: SQLException) {
            assertEquals("55P03", failure.sqlState)
            true
        }
    }

    private fun awaitDatabaseTime(s: ComplaintAdminBatchStatusFixture, until: Instant, maximumNanos: Long) {
        val started = System.nanoTime()
        checkNotNull(s.content.observer.dataSource).connection.use { connection ->
            connection.prepareStatement("SELECT clock_timestamp() >= ?").use { statement ->
                statement.queryTimeout = 2
                statement.setTimestamp(1, Timestamp.from(until))
                while (true) {
                    if (statement.executeQuery().use { row -> check(row.next()); row.getBoolean(1) }) break
                    assertTrue(System.nanoTime() - started < maximumNanos)
                    LockSupport.parkNanos(1_000_000)
                }
            }
        }
    }

    private fun withFixture(work: (ComplaintAdminBatchStatusFixture) -> Unit) = withComplaintOwnerCreate(database.value) { base ->
        ComplaintAdminContentFixture(base, adminBatchStatusTestIngress(base.policy)).use { work(ComplaintAdminBatchStatusFixture(it)) }
    }

    /** Larger disposable SQL-ledger fixture ONLY, so 50 preexisting paid content envelopes fit. No production limit/store is enlarged. */
    private fun withLargeFixture(work: (ComplaintAdminBatchStatusFixture) -> Unit) = withOrdinaryComplaintInstallationEnrollment(database.value, maximumPoolSize = 4) { base ->
        val normal = ownerCreateTestCapacityPolicy()
        val policy = ComplaintCapacityPolicyV1.of(normal.hardLimit.scaled(8), normal.creationLimit.scaled(8), 100)
        for (counter in ComplaintCapacityCounter.entries) assertEquals(1, base.observer.update(
            "UPDATE complaint_capacity_counters SET hard_limit = ?, creation_limit = ?, free_units = ? - actual_units - recovery_reserved_units - test_reserved_units WHERE name = ?",
            policy.hardLimit[counter], policy.creationLimit[counter], policy.hardLimit[counter], counter.storedName,
        ))
        OrdinaryComplaintTestInstallationFixture(base).use { run ->
            ComplaintOwnerCreateFixture(base, run, policy = policy).use { create ->
                ComplaintAdminContentFixture(create, adminBatchStatusTestIngress(policy, perHour = 1)).use { work(ComplaintAdminBatchStatusFixture(it)) }
            }
        }
    }
}
