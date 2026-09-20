package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.servlet.ServletOutputStream
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusOperation
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintTextRules
import me.manga.kira.backend.security.ComplaintAdminStatusAdmissionPolicy
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import me.manga.kira.backend.security.adminStatusTestIngress
import me.manga.kira.backend.support.JwtTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** Add only new moderation/receipt-family risks to the existing real JWT/issuer/content fixture; no activation or deletion authority. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintAdminStatusIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintAdminStatusIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun actualCloseCorrectAndReopenReplaceOrClearTheCompleteTupleWhilePreservingTwoOwnersAndReplyContent() = withFixture { s ->
        val e = s.content
        val f = s.base
        val id = e.report()
        var version = 1L
        for (target in listOf(ComplaintStatus.IN_PROGRESS, ComplaintStatus.PLANNED, ComplaintStatus.RESOLVED, ComplaintStatus.NOT_PLANNED, ComplaintStatus.OPEN)) {
            applied(s, AdminStatusAttempt(id, CLOSURE, version = version++, reason = "  key: opaque\r\nreason \n"))
            applied(s, AdminStatusAttempt(id, version = version++, status = target))
        }
        applied(s, AdminStatusAttempt(id, version = version++, status = ComplaintStatus.IN_PROGRESS))
        applied(s, AdminStatusAttempt(id, CLOSURE, version = version++, reason = "First reason"))
        val firstTuple = closure(s, id)
        val other = UUID.randomUUID()
        val user = e.user().copy(id = other, email = "status-$other@example.invalid")
        assertEquals(1, e.observer.update(
            "INSERT INTO users (id,email,password_hash,role,enabled,created_at,updated_at) VALUES (?, ?, ?, 'ADMIN', true, now(), now())",
            other, user.email, user.passwordHash,
        ))
        try {
            val corrected = AdminStatusAttempt(id, CLOSURE, version = version++, reason = "🙂".repeat(500))
            val otherProof = e.stepUp.issue(ScopedAdminStepUpScope.COMPLAINT, userId = other).token
            val wrongActor = e.state()
            e.problem(s.change(corrected, otherProof), 401, "ADMIN_STEP_UP_REQUIRED")
            assertEquals(wrongActor, e.state())
            assertFalse(used(s, otherProof), "A genuine proof is not portable between current Admin actors.")
            applied(s, corrected, e.userJwt.signer.issue(user).value, other)
            assertNotEquals(firstTuple, closure(s, id))
            val unchanged = AdminStatusAttempt(id, CLOSURE, version = version, reason = " \t${corrected.reason}\r\n")
            val proof = e.proof().token
            val before = e.state()
            e.problem(s.change(unchanged, proof), 409, "COMPLAINT_NO_CHANGE", consumed = true)
            assertEquals(before.ownerState.content, e.state().ownerState.content)
            assertEquals(before.ownerState.audits, e.state().ownerState.audits)
            assertEquals(other, e.observer.queryForObject("SELECT closure_actor_id FROM complaints WHERE id = ?", UUID::class.java, id))
            f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT)
            val rejected = e.state()
            e.problem(s.change(unchanged), 409, "COMPLAINT_NO_CHANGE", consumed = true)
            assertEquals(rejected, e.state(), "Same normalized reason cannot steal actor/time even when another Admin retries.")
            applied(s, AdminStatusAttempt(id, version = version, status = ComplaintStatus.OPEN))
        } finally {
            // Disposal only, after assertions. The borrowed outer fixture still owns resource/counter restoration.
            e.observer.update("DELETE FROM complaints WHERE data_scope_id = ? AND closure_actor_id = ?", e.scope.id, other)
            e.observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND actor_user_id = ?", e.scope.id, other)
            e.observer.update("DELETE FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? AND data_scope_id = ?", other, e.scope.id)
            e.observer.update("UPDATE admin_step_up_grants SET created_at = now() - interval '2 minutes', expires_at = now() - interval '1 minute' WHERE user_id = ? AND used_at IS NULL", other)
            e.stepUp.complaintCleanup.cleanupComplaintGrants()
            assertEquals(1, e.observer.update("DELETE FROM users WHERE id = ?", other))
        }
        val otherToken = f.json(f.exchange(UUID.randomUUID(), session = false))["accessToken"].asText()
        val otherReport = e.report(otherToken)
        val large = 9_007_199_254_740_993L
        assertEquals(1, e.observer.update("UPDATE complaints SET version = ? WHERE id = ?", large, otherReport))
        applied(s, AdminStatusAttempt(otherReport, CLOSURE, version = large, reason = "OTHER: keep raw compatibility text"))
        val owned = s.ownerDetail(otherReport, otherToken)
        assertEquals(200, owned.status)
        assertEquals(large + 1, f.json(owned)["version"].longValue())
        assertEquals("OTHER: keep raw compatibility text", f.json(owned)["closureReason"].asText())
        val reply = f.replyAttempt(id).also { assertEquals(201, f.reply(it).status) }.id
        val notice = f.replyNotice()
        val noticeReply = f.replyAttempt(notice).also { assertEquals(201, f.reply(it).status) }.id
        f.eraseReplyParent(notice)
        for (target in listOf(reply, noticeReply)) {
            applied(s, AdminStatusAttempt(target, CLOSURE, reason = "Reply closure"))
            applied(s, AdminStatusAttempt(target, version = 2, status = ComplaintStatus.NOT_PLANNED))
            assertEquals("NOT_PLANNED", f.json(s.ownerDetail(target))["status"].asText())
        }
    }

    @Test
    fun statusClosureAndContentKeepSeparateReceiptBindingsWhileHistoricalReplaySurvivesSealedErasureAndSharedQuota() = withFixture { s ->
        val e = s.content
        val f = s.base
        val id = e.report()
        val max = e.report()
        assertEquals(1, e.observer.update("UPDATE complaints SET version = ? WHERE id = ?", Long.MAX_VALUE, max))
        val notice = f.replyNotice()
        val unsupported = f.content()
        assertEquals(1, e.observer.update("UPDATE complaints SET status = 'PINNED' WHERE id = ?", unsupported))
        for ((attempt, status, code) in listOf(
            Triple(AdminStatusAttempt(id, status = ComplaintStatus.OPEN), 409, "COMPLAINT_NO_CHANGE"),
            Triple(AdminStatusAttempt(id, CLOSURE, version = 2), 412, "PRECONDITION_FAILED"),
            Triple(AdminStatusAttempt(max, version = Long.MAX_VALUE), 409, "COMPLAINT_INVALID_TRANSITION"),
            Triple(AdminStatusAttempt(max, CLOSURE, version = Long.MAX_VALUE), 409, "COMPLAINT_INVALID_TRANSITION"),
            Triple(AdminStatusAttempt(notice), 404, "COMPLAINT_NOT_FOUND"),
            Triple(AdminStatusAttempt(notice, CLOSURE), 404, "COMPLAINT_NOT_FOUND"),
            Triple(AdminStatusAttempt(unsupported), 404, "COMPLAINT_NOT_FOUND"),
            Triple(AdminStatusAttempt(unsupported, CLOSURE), 404, "COMPLAINT_NOT_FOUND"),
            Triple(AdminStatusAttempt(UUID.randomUUID()), 404, "COMPLAINT_NOT_FOUND"),
            Triple(AdminStatusAttempt(UUID.randomUUID(), CLOSURE), 404, "COMPLAINT_NOT_FOUND"),
        )) {
            val proof = e.proof().token
            val before = e.state()
            e.problem(s.change(attempt, proof), status, code, consumed = true)
            assertTrue(used(s, proof))
            assertEquals(before.ownerState.content, e.state().ownerState.content)
            assertEquals(before.ownerState.audits, e.state().ownerState.audits)
            f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT)
            val rejected = e.state()
            e.problem(s.change(attempt), status, code, consumed = true)
            assertEquals(rejected, e.state(), "Terminal rejection replay retains only its original receipt charge.")
        }
        val proof = e.proof().token
        val unclaimed = e.state()
        f.observations.clear()
        for (target in listOf(ComplaintStatus.CLOSED, ComplaintStatus.PINNED, ComplaintStatus.UNKNOWN)) {
            e.problem(s.change(AdminStatusAttempt(id, status = target), proof), 400, "VALIDATION_FAILED")
        }
        for (reason in listOf(" ", "x\u0000", "🙂".repeat(501))) {
            e.problem(s.change(AdminStatusAttempt(id, CLOSURE, reason = reason), proof), 400, "VALIDATION_FAILED")
        }
        assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.CLAIM })
        assertEquals(unclaimed, e.state())

        val limited = ComplaintAdminStatusFixture(e, adminStatusTestIngress(f.policy, perHour = 2))
        val transition = AdminStatusAttempt(id)
        val close = AdminStatusAttempt(id, CLOSURE, version = 2, reason = "  Historical\r\nreason  ")
        val changed = limited.change(transition, proof).also { e.acknowledged(it, id, 2) }
        val closed = limited.change(close, e.proof().token).also { e.acknowledged(it, id, 3) }
        e.problem(limited.change(AdminStatusAttempt(id, version = 3, status = ComplaintStatus.OPEN)), 429, "RATE_LIMITED")
        val content = AdminContentAttempt(id, version = 3)
        e.acknowledged(e.edit(content, e.proof().token), id, 4)
        val unused = e.proof().token
        assertEquals(0L, e.observer.queryForObject("SELECT count(*) FROM admin_step_up_grants WHERE user_id = ? AND used_at IS NOT NULL", Long::class.java, e.ordinary.userId))
        f.eraseReplyParent(id)
        f.run.terminalState("SEALED")
        val disabled = ComplaintAdminStatusFixture(e, adminStatusTestIngress(f.policy, status = ComplaintAdminStatusAdmissionPolicy.Disabled))
        val before = e.state()
        for (selected in listOf(limited, disabled)) {
            for ((attempt, original) in listOf(transition to changed, close.copy(reason = "Historical\nreason") to closed)) {
                val replay = selected.change(attempt, unused)
                e.acknowledged(replay, id, attempt.version + 1)
                assertArrayEquals(original.contentAsByteArray, replay.contentAsByteArray)
                assertEquals(before, e.state())
                e.acknowledged(selected.change(attempt), id, attempt.version + 1)
            }
            for (conflict in listOf(
                transition.copy(operation = CLOSURE), close.copy(operation = STATUS),
                transition.copy(status = ComplaintStatus.PLANNED), close.copy(reason = "Different"),
                transition.copy(id = UUID.randomUUID()), close.copy(id = UUID.randomUUID()),
                transition.copy(version = 2), close.copy(version = 3),
                transition.copy(key = content.key), close.copy(key = content.key),
            )) e.problem(selected.change(conflict), 409, "IDEMPOTENCY_KEY_REUSED")
        }
        e.problem(e.edit(content.copy(key = transition.key)), 409, "IDEMPOTENCY_KEY_REUSED")
        e.problem(e.edit(content.copy(key = close.key)), 409, "IDEMPOTENCY_KEY_REUSED")
        e.problem(s.change(AdminStatusAttempt(id), unused), 404, "NOT_FOUND")
        e.problem(disabled.change(AdminStatusAttempt(id)), 503, "SERVICE_UNAVAILABLE")
        assertEquals(404, e.detail(id).status)
        for ((change, restore, status, code) in listOf(
            listOf("role = 'USER'", "role = 'ADMIN'", "403", "FORBIDDEN"),
            listOf("enabled = false", "enabled = true", "401", "UNAUTHORIZED"),
            listOf("credential_version = 1", "credential_version = 0", "401", "UNAUTHORIZED"),
        )) {
            assertEquals(1, e.observer.update("UPDATE users SET $change WHERE id = ?", e.ordinary.userId))
            try { e.problem(disabled.change(close), status.toInt(), code) } finally {
                assertEquals(1, e.observer.update("UPDATE users SET $restore WHERE id = ?", e.ordinary.userId))
            }
        }
        assertFalse(used(s, unused))
        assertEquals(before, e.state())
    }

    @Test
    fun statusAndClosureAssociateAppliedRejectedAndLegacyReplaysWithoutUsingFreshProof() = withFixture { s ->
        val e = s.content
        for (operation in listOf(STATUS, CLOSURE)) for (rejected in listOf(false, true)) {
            val attempt = AdminStatusAttempt(e.report(), operation, version = if (rejected) 2L else 1L)
            val a = e.proof()
            val original = s.change(attempt, a.token)
            if (rejected) e.problem(original, 412, "PRECONDITION_FAILED", consumed = true) else e.acknowledged(original, attempt.id, 2)
            e.association(original, a.grantId)
            assertEquals(a.grantId, e.observer.queryForObject(
                "SELECT consumed_grant_id FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?",
                UUID::class.java, e.ordinary.userId, attempt.key,
            ))
            val b = e.proof()
            assertEquals(0L, e.observer.queryForObject("SELECT count(*) FROM admin_step_up_grants WHERE id = ?", Long::class.java, a.grantId))
            val before = e.state()
            for (proof in listOf(null, b.token)) {
                val replay = s.change(attempt, proof)
                assertEquals(original.status, replay.status)
                assertArrayEquals(original.contentAsByteArray, replay.contentAsByteArray)
                e.association(replay, a.grantId)
                assertEquals(before, e.state())
            }
            // Historical fixture only; the producer is required to retain its consumed grant on every new completion.
            assertEquals(1, e.observer.update(
                "UPDATE complaint_idempotency_receipts SET consumed_grant_id = NULL WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?",
                e.ordinary.userId, attempt.key,
            ))
            val historical = e.state()
            for (proof in listOf(null, b.token)) {
                val replay = s.change(attempt, proof)
                assertEquals(original.status, replay.status)
                assertArrayEquals(original.contentAsByteArray, replay.contentAsByteArray)
                assertEquals(listOf("true"), replay.getHeaders(ComplaintAdminContentFixture.CONSUMED).toList())
                e.association(replay, null)
                assertEquals(historical, e.state())
            }
            assertFalse(used(s, b.token))
            e.association(s.ownerDetail(attempt.id), null)
        }
    }

    @Test
    fun contentAndModerationRaceOnOneVersionAndCrossOperationClaimCannotConsumeTheLosingProof() = withFixture { s ->
        val e = s.content
        val f = s.base
        val id = e.report()
        val proof = e.proof().token
        val contentProof = e.proof().token
        val before = e.state()
        OwnedCallerTestScope().use { callers ->
            val statusGate = callers.gate()
            val contentGate = callers.gate()
            val arrivals = AtomicInteger()
            f.beforeStep = { step -> if (step == OwnerCreateFixtureStep.CLAIM) when (arrivals.incrementAndGet()) {
                1 -> statusGate.hold()
                2 -> contentGate.hold()
                else -> error("Unexpected extra claim")
            } }
            try {
                val status = callers.launch { s.send(s.input(AdminStatusAttempt(id), proof)) }
                statusGate.awaitEntered()
                val content = callers.launch { e.send(e.input(AdminContentAttempt(id), contentProof)) }
                contentGate.awaitEntered()
                contentGate.release()
                assertEquals(200, content.value().status)
                statusGate.release()
                e.problem(status.value(), 412, "PRECONDITION_FAILED", consumed = true)
                e.acknowledged(content.value(), id, 2)
            } finally { statusGate.release(); contentGate.release(); f.beforeStep = {} }
        }
        assertTrue(used(s, proof) && used(s, contentProof))
        assertEquals(before.ownerState.audits.size + 1, e.state().ownerState.audits.size)
        f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT.scaled(2) + ComplaintCapacityCharges.AUDIT)

        val losingProof = e.proof().token
        val winningProof = e.proof().token
        val attempt = AdminStatusAttempt(id, version = 2)
        val close = attempt.copy(operation = CLOSURE)
        val baseline = e.state()
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val first = AtomicBoolean()
            f.beforeStep = { step -> if (step == OwnerCreateFixtureStep.CLAIM && first.compareAndSet(false, true)) gate.hold() }
            try {
                val status = callers.launch { s.send(s.input(attempt, losingProof)) }
                gate.awaitEntered()
                val closure = s.send(s.input(close, winningProof))
                assertEquals(200, closure.status)
                gate.release()
                e.problem(status.value(), 409, "IDEMPOTENCY_KEY_REUSED")
                e.acknowledged(closure, id, 3)
            } finally { gate.release(); f.beforeStep = {} }
        }
        assertFalse(used(s, losingProof))
        assertTrue(used(s, winningProof))
        assertEquals(baseline.receipts.size + 1, e.state().receipts.size)
        f.assertCharge(baseline.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT + ComplaintCapacityCharges.AUDIT)

        val inProgress = AdminStatusAttempt(id, version = 3, status = ComplaintStatus.PLANNED)
        val heldProof = e.proof().token
        val retryProof = e.proof().token
        val claimBefore = e.state()
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val claimed = AtomicBoolean()
            f.afterStep = { step -> if (step == OwnerCreateFixtureStep.CLAIM && claimed.compareAndSet(false, true)) gate.hold() }
            try {
                val held = callers.launch { s.send(s.input(inProgress, heldProof)) }
                gate.awaitEntered()
                val competing = s.send(s.input(inProgress, retryProof))
                e.problem(competing, 409, "IDEMPOTENCY_IN_PROGRESS")
                assertEquals("1", competing.getHeader("Retry-After"))
                assertFalse(used(s, heldProof))
                assertFalse(used(s, retryProof))
                assertEquals(claimBefore, e.state(), "Original uncommitted claim and timed-out competitor remain invisible.")
                gate.release()
                e.acknowledged(held.value(), id, 4)
            } finally { gate.release(); f.afterStep = {} }
        }
        val committed = e.state()
        e.acknowledged(s.change(inProgress, retryProof), id, 4)
        assertEquals(committed, e.state())
        assertFalse(used(s, retryProof))
        f.assertCharge(claimBefore.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT + ComplaintCapacityCharges.AUDIT)
        for ((table, target, restore) in listOf(
            Triple("complaint_installation_ids", f.actor.id, "ACTIVE"), Triple("app_installations", f.actor.id, "ACTIVE"),
            Triple("complaint_resource_ids", id, "LIVE"),
        )) {
            val fresh = e.proof().token
            val pendingBefore = e.state()
            val changed = AtomicBoolean()
            f.beforeStep = { step -> if (step == OwnerCreateFixtureStep.CLAIM && changed.compareAndSet(false, true)) {
                assertEquals(1, e.observer.update("UPDATE $table SET state = 'DELETION_PENDING' WHERE id = ?", target))
            } }
            try {
                e.problem(s.change(AdminStatusAttempt(id, CLOSURE, version = 2), fresh), 409, "COMPLAINT_DELETION_PENDING", consumed = true)
                assertTrue(changed.get())
                assertEquals(pendingBefore.ownerState.content, e.state().ownerState.content)
                assertEquals(pendingBefore.journal, e.state().journal)
                f.assertCharge(pendingBefore.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT)
                val unchangedProof = e.proof().token
                val noChangeBefore = e.state()
                e.problem(s.change(AdminStatusAttempt(id, version = 4, status = ComplaintStatus.PLANNED), unchangedProof), 409, "COMPLAINT_DELETION_PENDING", consumed = true)
                assertEquals(noChangeBefore.ownerState.content, e.state().ownerState.content)
                assertEquals(noChangeBefore.ownerState.audits, e.state().ownerState.audits)
                f.assertCharge(noChangeBefore.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT)
            } finally {
                f.beforeStep = {}
                assertEquals(1, e.observer.update("UPDATE $table SET state = ? WHERE id = ?", restore, target))
            }
        }
    }

    @Test
    fun bothRoutesAuthenticateBeforeNormalizationAndRecheckCurrentAdminAfterTheOriginalComplaintGrant() = withFixture { s ->
        val e = s.content
        val f = s.base
        val id = e.report()
        for ((index, operation) in listOf(STATUS, CLOSURE).withIndex()) {
            val attempt = AdminStatusAttempt(id, operation, version = index + 1L)
            val proof = e.proof().token
            val source = e.proof(ScopedAdminStepUpScope.SOURCE).token
            val before = e.state()
            for (bearer in listOf(
                f.token, JwtTestSupport.tamperSignature(e.token),
                JwtTestSupport.mint(e.ordinary.userId, credentialVersion = 0),
                JwtTestSupport.mint(e.ordinary.userId, credentialVersion = "00"),
                JwtTestSupport.mint(e.ordinary.userId, credentialVersion = "1"),
            )) {
                e.problem(s.change(attempt, proof, bearer), 401, "UNAUTHORIZED")
                assertEquals(before, e.state())
            }
            e.problem(s.change(attempt.copy(operation = CLOSURE, reason = " \t\n"), proof, f.token), 401, "UNAUTHORIZED")
            e.problem(s.change(attempt.copy(operation = CLOSURE, reason = " \t\n"), proof), 400, "VALIDATION_FAILED")
            e.problem(s.send(s.input(attempt, proof).apply { queryString = "dataScopeId=${UUID.randomUUID()}" }), 404, "NOT_FOUND")
            assertEquals(before, e.state(), "Invalid authenticated text and a foreign captured scope cannot claim or consume.")
            for (supplied in listOf(null, source, "unknown-printable-proof")) {
                e.problem(s.change(attempt, supplied), 401, "ADMIN_STEP_UP_REQUIRED")
                assertEquals(before, e.state())
            }
            for ((change, restore, status, code) in listOf(
                AdminChange("role = 'USER'", "role = 'ADMIN'", 403, "FORBIDDEN"),
                AdminChange("enabled = false", "enabled = true", 401, "UNAUTHORIZED"),
                AdminChange("credential_version = 1", "credential_version = 0", 401, "UNAUTHORIZED"),
            )) {
                val reached = AtomicBoolean()
                f.beforeStep = { step -> if (step == OwnerCreateFixtureStep.COUNTERS && reached.compareAndSet(false, true)) {
                    assertEquals(true, f.jdbc.queryForObject(
                        "SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE token_hash = ?", Boolean::class.java, Sha256.hexUtf8(proof),
                    ))
                    assertEquals(1, e.observer.update("UPDATE users SET $change WHERE id = ?", e.ordinary.userId))
                } }
                try {
                    e.problem(s.change(attempt, proof), status, code)
                    assertTrue(reached.get(), "Revocation follows real preflight release and original grant consumption, not a mocked principal.")
                    assertEquals(before, e.state())
                    assertFalse(used(s, proof))
                } finally {
                    f.beforeStep = {}
                    assertEquals(1, e.observer.update("UPDATE users SET $restore WHERE id = ?", e.ordinary.userId))
                }
            }
            e.acknowledged(s.change(attempt, proof, JwtTestSupport.mint(e.ordinary.userId, role = "USER")), id, attempt.version + 1)
            assertTrue(used(s, proof), "Signed diagnostic USER role must not override the current database ADMIN.")
            f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT + ComplaintCapacityCharges.AUDIT)
            val committed = e.state()
            val opposite = attempt.copy(
                operation = if (operation == STATUS) CLOSURE else STATUS, key = UUID.randomUUID(),
                version = attempt.version + 1, status = ComplaintStatus.OPEN,
            )
            e.problem(s.change(opposite, proof), 401, "ADMIN_STEP_UP_REQUIRED")
            assertEquals(committed, e.state(), "Consumed complaint proof cannot authorize another operation in this family.")
        }
    }

    @Test
    fun grantExpiryAfterGrantLockAndJwtExpiryAfterModerationLockRestoreTheOriginalClosureTuple() = withFixture { s ->
        val e = s.content
        val f = s.base
        val id = e.report()
        applied(s, AdminStatusAttempt(id, CLOSURE, reason = "Original tuple"))
        val nearExpiry = ScopedStepUpFixture(
            e.ordinary, f.base.counters, f.policy.digestBytes(),
            phaseClock = Clock.fixed(Instant.now().minus(e.stepUp.properties.stepUpTtl).plusMillis(750), ZoneOffset.UTC),
        )
        val proof = nearExpiry.issue(ScopedAdminStepUpScope.COMPLAINT)
        val before = e.state()
        val locked = AtomicBoolean()
        f.observations.clear()
        f.afterStep = { step -> if (step == OwnerCreateFixtureStep.ADMIN_GRANT_LOCK && locked.compareAndSet(false, true)) {
            assertEquals(true, f.jdbc.queryForObject(
                "SELECT used_at IS NULL AND expires_at > clock_timestamp() FROM admin_step_up_grants WHERE token_hash = ?",
                Boolean::class.java, Sha256.hexUtf8(proof.token),
            ), "The genuine lock must return before this real issued proof expires.")
            awaitDatabaseTime(s, proof.expiresAt, 1_000_000_000L)
        } }
        try {
            e.problem(s.change(AdminStatusAttempt(id, CLOSURE, version = 2, reason = "Late correction"), proof.token), 401, "ADMIN_STEP_UP_REQUIRED")
            assertTrue(locked.get())
            assertEquals(before, e.state())
            assertFalse(used(s, proof.token))
            assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.COUNTERS })
        } finally { f.afterStep = {} }

        val fresh = e.proof().token
        val jwtBefore = e.state()
        val until = e.observer.queryForObject("SELECT date_trunc('second', clock_timestamp()) + interval '2 seconds'", Timestamp::class.java)!!.toInstant()
        awaitDatabaseTime(s, until.minusMillis(750), 2_000_000_000L)
        val expires = until.minus(e.userJwt.properties.clockSkew)
        val bearer = JwtTestSupport.mint(e.ordinary.userId, issuedAt = expires.minusSeconds(60), expiresAt = expires)
        val contentLocks = AtomicInteger()
        f.observations.clear()
        f.afterStep = { step ->
            // Owner discovery is the first content query; the second is the original FOR UPDATE moderation lock.
            if (step == OwnerCreateFixtureStep.PARENT_CONTENT && contentLocks.incrementAndGet() == 2) {
                assertEquals(true, f.jdbc.queryForObject("SELECT clock_timestamp() < ?", Boolean::class.java, Timestamp.from(until)))
                awaitDatabaseTime(s, until, 1_000_000_000L)
            }
        }
        try {
            e.problem(s.change(AdminStatusAttempt(id, version = 2, status = ComplaintStatus.OPEN), fresh, bearer), 401, "UNAUTHORIZED")
            assertEquals(2, contentLocks.get())
            assertEquals(jwtBefore, e.state(), "Late JWT expiry cannot clear any closure field or retain the already consumed proof.")
            assertFalse(used(s, fresh))
            assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.ADMIN_STATUS_CHANGE })
        } finally { f.afterStep = {} }
    }

    @Test
    fun bothRoutesKeepReadCommittedMaintenanceCapacityAndSourceOnlyGatesBeforeOriginalPhysicalRelease() = withFixture { s ->
        val e = s.content
        val f = s.base
        val id = e.report()
        val admission = OrdinaryPersistenceAdmission.sourceOnly(4)
        val sourceOnly = PersistencePhaseOwnership(admission, GuardedJpaTransactionManager(e.ordinary.entityManagerFactory, e.ordinary.pool))
        for (enter in listOf(sourceOnly::enterComplaintAdminStatusPreflight, sourceOnly::enterComplaintAdminStatus)) {
            val refused = assertThrows<PersistencePhaseException> { enter() }
            assertEquals(PersistenceDatabaseOutcome.NONE, refused.databaseOutcome)
            assertTrue(refused.cleanupProven)
            assertEquals(0, admission.activeOwners())
        }
        for ((index, operation) in listOf(STATUS, CLOSURE).withIndex()) {
            val proof = e.proof().token
            val attempt = AdminStatusAttempt(id, operation, version = index + 1L)
            val before = e.state()
            val storage = before.ownerState.counters.getValue(ComplaintCapacityCounter.STORAGE_BYTES.storedName)
            assertEquals(1, e.observer.update(
                "UPDATE complaint_capacity_counters SET free_units = 0, actual_units = hard_limit - recovery_reserved_units - test_reserved_units WHERE name = 'storage_bytes'",
            ))
            try {
                val full = e.state()
                e.problem(s.change(attempt, proof), 503, "SERVICE_UNAVAILABLE")
                assertEquals(full, e.state())
                assertFalse(used(s, proof))
            } finally {
                assertEquals(1, e.observer.update("UPDATE complaint_capacity_counters SET free_units = ?, actual_units = ? WHERE name = 'storage_bytes'", storage.free, storage.actual))
            }
            f.observations.clear()
            f.lock("SELECT pg_advisory_xact_lock(hashtextextended('complaint-maintenance-v1', 0))").use { held ->
                try {
                    e.problem(s.change(attempt, proof), 503, "SERVICE_UNAVAILABLE")
                    assertEquals(before, e.state())
                    assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.CLAIM || it.first == OwnerCreateFixtureStep.ADMIN_GRANT_LOCK })
                } finally { held.rollback() }
            }
            f.observations.clear()
            val isolationChecks = mutableSetOf<OwnerCreateFixtureStep>()
            f.afterStep = { step -> if (step in setOf(OwnerCreateFixtureStep.OBSERVE, OwnerCreateFixtureStep.ADMIN_STATUS_CHANGE)) {
                assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                assertEquals("read committed", f.jdbc.queryForObject("SHOW transaction_isolation", String::class.java))
                assertEquals(step != OwnerCreateFixtureStep.ADMIN_STATUS_CHANGE, TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                isolationChecks.add(step)
            } }
            val response = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream {
                    f.assertReleased()
                    return super.getOutputStream()
                }
            }
            try { s.handler.handleRequest(s.input(attempt, proof), response) } finally { f.afterStep = {} }
            e.acknowledged(response, id, attempt.version + 1)
            assertEquals(setOf(OwnerCreateFixtureStep.OBSERVE, OwnerCreateFixtureStep.ADMIN_STATUS_CHANGE), isolationChecks)
            assertEquals(1, f.observations.count { it.first == OwnerCreateFixtureStep.ADMIN_STATUS_CHANGE })
            assertTrue(used(s, proof))
            f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT + ComplaintCapacityCharges.AUDIT)
        }
    }

    @Test
    fun closureCreationCorrectionAndClearingRollbackWithAuditAndProofWhileFailedCommittedTailRequiresExactRetry() {
        val scenarios = listOf(STATUS to true, CLOSURE to true, CLOSURE to false).flatMap { (operation, initiallyClosed) ->
            listOf("ROW", "AUDIT", "CREDENTIAL", "COMMIT", "TAIL").map { Triple(operation, initiallyClosed, it) }
        }
        for ((operation, initiallyClosed, fault) in scenarios) withFixture { s ->
            val e = s.content
            val f = s.base
            val id = e.report()
            if (initiallyClosed) applied(s, AdminStatusAttempt(id, CLOSURE, reason = "Original complete tuple"))
            val attempt = AdminStatusAttempt(id, operation, version = if (initiallyClosed) 2L else 1L, status = ComplaintStatus.OPEN, reason = "Corrected tuple")
            val next = attempt.version + 1
            val issued = e.proof()
            val proof = issued.token
            val before = e.state()
            val reached = AtomicBoolean()
            f.observations.clear()
            f.beforeStep = { step -> if (fault == "CREDENTIAL" && step == OwnerCreateFixtureStep.COUNTERS && reached.compareAndSet(false, true)) {
                assertEquals(true, f.jdbc.queryForObject("SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE token_hash = ?", Boolean::class.java, Sha256.hexUtf8(proof)))
                assertEquals(1, e.observer.update("UPDATE users SET credential_version = 1 WHERE id = ?", e.ordinary.userId))
            } }
            f.afterStep = { step ->
                val target = if (fault == "ROW") OwnerCreateFixtureStep.ADMIN_STATUS_CHANGE else OwnerCreateFixtureStep.COMPLETE
                if (fault != "CREDENTIAL" && step == target && reached.compareAndSet(false, true)) {
                    assertEquals(true, f.jdbc.queryForObject(
                        "SELECT version = ? AND status = ? FROM complaints WHERE id = ?", Boolean::class.java, next, if (operation == STATUS) "OPEN" else "CLOSED", id,
                    ))
                    if (fault != "ROW") assertEquals(1L, f.jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND entity_id = ? AND detail ->> 'version' = ?",
                        Long::class.java, e.scope.id, id.toString(), next.toString(),
                    ))
                    when (fault) {
                        "ROW", "AUDIT" -> throw SyntheticInstallationEnrollmentFailure()
                        "COMMIT" -> {
                            f.jdbc.execute("CREATE TEMP TABLE kira_admin_status_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                            assertEquals(2, f.jdbc.update("INSERT INTO kira_admin_status_commit VALUES (1), (1)"))
                        }
                        "TAIL" -> TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                            override fun afterCommit(): Unit = throw SyntheticInstallationEnrollmentFailure()
                        })
                        else -> Unit
                    }
                }
            }
            try {
                e.problem(s.change(attempt, proof), if (fault == "CREDENTIAL") 401 else 503, if (fault == "CREDENTIAL") "UNAUTHORIZED" else "SERVICE_UNAVAILABLE")
                assertTrue(reached.get(), "Fault must follow the actual original grant/row/audit SQL, never a replacement owner.")
            } finally {
                f.beforeStep = {}; f.afterStep = {}
                if (fault == "CREDENTIAL") assertEquals(1, e.observer.update("UPDATE users SET credential_version = 0 WHERE id = ?", e.ordinary.userId))
            }
            if (fault == "TAIL") {
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, f.observations.last().second.phase.databaseOutcome())
                assertTrue(used(s, proof))
                val committed = e.state()
                e.acknowledged(s.change(attempt).also { e.association(it, issued.grantId) }, id, next)
                assertEquals(committed, e.state())
            } else {
                assertEquals(if (fault == "COMMIT") PersistenceDatabaseOutcome.UNKNOWN else PersistenceDatabaseOutcome.ROLLED_BACK, f.observations.last().second.phase.databaseOutcome())
                assertEquals(before, e.state(), "Restores complete prior closure tuple, counters, audit, receipt and proof together.")
                assertFalse(used(s, proof))
                e.acknowledged(s.change(attempt, proof).also { e.association(it, issued.grantId) }, id, next)
            }
            f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT + ComplaintCapacityCharges.AUDIT)
        }
    }

    private fun applied(s: ComplaintAdminStatusFixture, attempt: AdminStatusAttempt, bearer: String = s.content.token, actor: UUID = s.content.ordinary.userId): MockHttpServletResponse {
        val e = s.content
        val proof = e.stepUp.issue(ScopedAdminStepUpScope.COMPLAINT, userId = actor).token
        val before = e.state()
        val immutable = s.immutable(attempt.id)
        val from = e.observer.queryForObject("SELECT status FROM complaints WHERE id = ?", String::class.java, attempt.id)!!
        val to = if (attempt.operation == CLOSURE) "CLOSED" else attempt.status.name
        val response = s.change(attempt, proof, bearer)
        e.acknowledged(response, attempt.id, attempt.version + 1)
        assertEquals(immutable, s.immutable(attempt.id))
        assertTrue(used(s, proof))
        assertEquals(true, e.observer.queryForObject(
            "SELECT status = ? AND version = ? AND " + if (attempt.operation == CLOSURE) {
                "closure_reason = ? AND closure_provenance = 'ADMIN' AND closure_actor_id = ? AND closed_at = updated_at FROM complaints WHERE id = ?"
            } else {
                "closure_reason IS NULL AND closure_provenance IS NULL AND closure_actor_id IS NULL AND closed_at IS NULL FROM complaints WHERE id = ?"
            }, Boolean::class.java,
            *if (attempt.operation == CLOSURE) arrayOf(to, attempt.version + 1, ComplaintTextRules.closureReason(attempt.reason), actor, attempt.id)
            else arrayOf(to, attempt.version + 1, attempt.id),
        ))
        assertEquals(true, e.observer.queryForObject(
            "SELECT a.complaint_actor_kind = 'ADMIN' AND a.actor_user_id = ? AND a.created_at = c.updated_at " +
                "AND a.detail = jsonb_build_object('version', ?::bigint, 'fromStatus', ?::text, 'toStatus', ?::text) " +
                "FROM audit_log a JOIN complaints c ON c.id::text = a.entity_id WHERE a.complaint_data_scope_id = ? AND c.id = ? " +
                "AND a.action = ? AND a.detail ->> 'version' = ?",
            Boolean::class.java, actor, attempt.version + 1, from, to, e.scope.id, attempt.id,
            if (attempt.operation == CLOSURE) "COMPLAINT_CLOSED" else "COMPLAINT_STATUS_CHANGED", (attempt.version + 1).toString(),
        ))
        assertEquals(attempt.operation.name, e.observer.queryForObject(
            "SELECT operation FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?",
            String::class.java, actor, attempt.key,
        ))
        assertEquals(to, s.base.json(e.detail(attempt.id))["status"].asText())
        assertEquals(before.ownerState.resources, e.state().ownerState.resources)
        assertEquals(before.journal, e.state().journal)
        s.base.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT + ComplaintCapacityCharges.AUDIT)
        return response
    }

    private fun closure(s: ComplaintAdminStatusFixture, id: UUID): String = s.content.observer.queryForObject(
        "SELECT jsonb_build_array(closure_reason, closure_provenance, closure_actor_id, closed_at)::text FROM complaints WHERE id = ?", String::class.java, id,
    )!!

    private fun used(s: ComplaintAdminStatusFixture, proof: String): Boolean = s.content.observer.queryForObject(
        "SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE token_hash = ?", Boolean::class.java, Sha256.hexUtf8(proof),
    )!!

    private fun awaitDatabaseTime(s: ComplaintAdminStatusFixture, until: Instant, maximumWaitNanos: Long) {
        val started = System.nanoTime()
        while (s.content.observer.queryForObject("SELECT clock_timestamp() >= ?", Boolean::class.java, Timestamp.from(until)) != true) {
            assertTrue(System.nanoTime() - started < maximumWaitNanos)
            LockSupport.parkNanos(1_000_000)
        }
    }

    private fun withFixture(work: (ComplaintAdminStatusFixture) -> Unit) = withComplaintOwnerCreate(database.value) { base ->
        ComplaintAdminContentFixture(base, adminStatusTestIngress(base.policy)).use { work(ComplaintAdminStatusFixture(it)) }
    }

    private data class AdminChange(val change: String, val restore: String, val status: Int, val code: String)

    private companion object {
        val STATUS = ComplaintAdminStatusOperation.ADMIN_STATUS
        val CLOSURE = ComplaintAdminStatusOperation.ADMIN_CLOSURE
    }
}
