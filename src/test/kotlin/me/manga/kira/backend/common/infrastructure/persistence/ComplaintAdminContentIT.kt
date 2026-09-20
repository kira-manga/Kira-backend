package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.security.ComplaintAdminContentAdmissionPolicy
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import me.manga.kira.backend.security.adminContentTestIngress
import me.manga.kira.backend.support.JwtTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** Actual normal JWT/real complaint issuer -> original ordinary writer -> Admin detail; synthetic TEST rows are not activation authority. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintAdminContentIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintAdminContentIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun realAdminProofEditsTwoOwnersTypeOnlyClosedAndOrphanNoticeRepliesWithoutRepayingContent() = withFixture { e ->
        val f = e.base
        val report = e.report()
        val otherToken = f.json(f.exchange(UUID.randomUUID(), session = false))["accessToken"].asText()
        val other = e.report(otherToken)
        val reply = f.replyAttempt(report).also { assertEquals(201, f.reply(it).status) }.id
        val notice = f.replyNotice()
        val noticeReply = f.replyAttempt(notice).also { assertEquals(201, f.reply(it).status) }.id
        f.eraseReplyParent(notice) // Actual fixture erasure only; no journal/deletion or seed authority claim.
        assertEquals(1, e.observer.update(
            "UPDATE complaints SET status = 'CLOSED', closure_reason = 'Synthetic resolved', closure_provenance = 'ADMIN', " +
                "closure_actor_id = ?, closed_at = ? WHERE id = ?", e.ordinary.userId, Timestamp.from(e.ordinary.cutoff), report,
        ))
        val large = 9_007_199_254_740_993L
        assertEquals(1, e.observer.update("UPDATE complaints SET version = ? WHERE id = ?", large, other))
        val attempts = listOf(
            AdminContentAttempt(report, type = ComplaintType.FEATURES, subject = "  Synthetic subject  ", body = " \tSynthetic body\r\nline \n"),
            AdminContentAttempt(other, version = large, type = ComplaintType.SITE_ERROR, body = "🙂".repeat(1000)),
            AdminContentAttempt(reply, type = ComplaintType.LANGUAGES),
            AdminContentAttempt(noticeReply, type = null, subject = null, body = "Edited notice reply"),
        )
        val proofs = attempts.map { e.proof().token }
        val before = e.state()
        for ((attempt, proof) in attempts.zip(proofs)) {
            val preserved = e.immutable(attempt.id)
            f.observations.clear()
            f.afterStep = { step ->
                if (step == OwnerCreateFixtureStep.EDIT_CONTENT) {
                    assertEquals("read committed", f.jdbc.queryForObject("SHOW transaction_isolation", String::class.java))
                }
            }
            val response = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream {
                    f.assertReleased()
                    return super.getOutputStream()
                }
            }
            try { e.handler.handleRequest(e.input(attempt, proof), response) } finally { f.afterStep = {} }
            e.acknowledged(response, attempt.id, attempt.version + 1)
            assertEquals(preserved, e.immutable(attempt.id))
            assertTrue(grantUsed(e, proof))
            assertEquals(1, f.observations.count { it.first == OwnerCreateFixtureStep.EDIT_CONTENT })
            val detail = e.detail(attempt.id)
            assertEquals(200, detail.status)
            assertEquals(attempt.version + 1, f.json(detail)["version"].longValue())
            assertTrue(f.json(detail)["version"].isIntegralNumber)
            assertEquals(response.getHeader("ETag"), detail.getHeader("ETag"))
            assertEquals(attempt.type?.name ?: "CUSTOM", f.json(detail)["type"].asText())
            assertEquals(true, e.observer.queryForObject(
                "SELECT complaint_actor_kind = 'ADMIN' AND actor_user_id = ? AND detail = jsonb_build_object('version', ?::bigint) " +
                    "FROM audit_log WHERE complaint_data_scope_id = ? AND entity_id = ? AND action = 'COMPLAINT_CONTENT_EDITED'",
                Boolean::class.java, e.ordinary.userId, attempt.version + 1, e.scope.id, attempt.id.toString(),
            ))
        }
        f.assertCharge(before.ownerState.counters, (ComplaintCapacityCharges.NORMAL_RECEIPT + ComplaintCapacityCharges.AUDIT).scaled(4))
        val after = e.state()
        assertEquals(before.ownerState.resources, after.ownerState.resources)
        assertEquals(before.run, after.run)
        assertEquals(before.journal, after.journal)
        assertEquals(before.receipts.size + 4, after.receipts.size)
        assertEquals(4L, e.observer.queryForObject(
            "SELECT count(*) FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? " +
                "AND operation = 'ADMIN_EDIT' AND state = 'COMPLETED' AND consumed_grant_id IS NULL AND publication_ref IS NULL",
            Long::class.java, e.ordinary.userId,
        ))
    }

    @Test
    fun completedReplayBypassesProofQuotaOldEtagAndErasedTargetButNeverCurrentAdminOrExactKeyBinding() = withFixture { e ->
        val f = e.base
        val limited = ComplaintAdminContentFixture(f, adminContentTestIngress(f.policy, perHour = 1), e.responseOwner)
        val id = e.report()
        val attempt = AdminContentAttempt(id)
        val original = limited.edit(attempt, e.proof().token)
        e.acknowledged(original, id, 2)
        e.acknowledged(e.edit(attempt.copy(key = UUID.randomUUID(), version = 2, body = "Later content"), e.proof().token), id, 3)
        val unused = e.proof().token // Real cleanup has removed the first used grant; the terminal receipt must suffice.
        assertEquals(0L, e.observer.queryForObject("SELECT count(*) FROM admin_step_up_grants WHERE user_id = ? AND used_at IS NOT NULL", Long::class.java, e.ordinary.userId))
        f.eraseReplyParent(id)
        f.run.terminalState("SEALED")
        val disabled = ComplaintAdminContentFixture(f, adminContentTestIngress(f.policy, content = ComplaintAdminContentAdmissionPolicy.Disabled), e.responseOwner)
        val before = e.state()
        for (selected in listOf(limited, disabled)) {
            for (proof in listOf(null, unused)) {
                val replay = selected.edit(attempt, proof)
                e.acknowledged(replay, id, 2)
                assertArrayEquals(original.contentAsByteArray, replay.contentAsByteArray)
                assertEquals(before, e.state(), "Replay must not consume a newly supplied proof or charge any dimension.")
            }
            e.acknowledged(selected.edit(attempt.copy(subject = "  Edited subject \n", body = "\tEdited body\r\n")), id, 2)
            assertEquals(before, e.state(), "Replay fingerprint binds normalized content, not inconsequential raw formatting.")
            for (conflict in listOf(
                attempt.copy(body = "Different body"), attempt.copy(type = ComplaintType.FEATURES),
                attempt.copy(id = UUID.randomUUID()), attempt.copy(version = 2),
            )) e.problem(selected.edit(conflict), 409, "IDEMPOTENCY_KEY_REUSED")
        }
        e.problem(e.edit(attempt.copy(key = UUID.randomUUID()), unused), 404, "NOT_FOUND")
        assertEquals(404, e.detail(id).status, "Existing Admin read must still require an ACTIVE run; replay does not reactivate it.")
        assertEquals(before, e.state())
        e.problem(limited.edit(attempt.copy(key = UUID.randomUUID())), 429, "RATE_LIMITED")
        e.problem(disabled.edit(attempt.copy(key = UUID.randomUUID())), 503, "SERVICE_UNAVAILABLE")
        for ((change, restore, status, code) in listOf(
            AdminChange("role = 'USER'", "role = 'ADMIN'", 403, "FORBIDDEN"),
            AdminChange("enabled = false", "enabled = true", 401, "UNAUTHORIZED"),
            AdminChange("credential_version = 1", "credential_version = 0", 401, "UNAUTHORIZED"),
        )) {
            assertEquals(1, e.observer.update("UPDATE users SET $change WHERE id = ?", e.ordinary.userId))
            try { e.problem(disabled.edit(attempt), status, code) } finally {
                assertEquals(1, e.observer.update("UPDATE users SET $restore WHERE id = ?", e.ordinary.userId))
            }
        }
        assertEquals(before, e.state())
        assertFalse(grantUsed(e, unused))
    }

    @Test
    fun receiptedBusinessRejectionsConsumeOneProofAndOnlyNormalReceiptWhileMalformedRequestsRetainNothing() = withFixture { e ->
        val f = e.base
        val report = e.report()
        val notice = f.replyNotice()
        val reply = f.replyAttempt(notice).also { assertEquals(201, f.reply(it).status) }.id
        val maxVersion = e.report()
        assertEquals(1, e.observer.update("UPDATE complaints SET version = ? WHERE id = ?", Long.MAX_VALUE, maxVersion))
        val pending = f.content(pending = true) // Legal small storage marker only, not a deletion producer.
        val cases = listOf(
            Triple(AdminContentAttempt(report, subject = " Synthetic subject ", body = " Synthetic body\r\nline "), 409, "COMPLAINT_NO_CHANGE"),
            Triple(AdminContentAttempt(report, version = 2), 412, "PRECONDITION_FAILED"),
            Triple(AdminContentAttempt(report, type = null, subject = null), 409, "COMPLAINT_INVALID_TRANSITION"),
            Triple(AdminContentAttempt(reply), 409, "COMPLAINT_INVALID_TRANSITION"),
            Triple(AdminContentAttempt(maxVersion, version = Long.MAX_VALUE), 409, "COMPLAINT_INVALID_TRANSITION"),
            Triple(AdminContentAttempt(pending), 409, "COMPLAINT_DELETION_PENDING"),
            Triple(AdminContentAttempt(notice, type = null, subject = null), 404, "COMPLAINT_NOT_FOUND"),
            Triple(AdminContentAttempt(UUID.randomUUID()), 404, "COMPLAINT_NOT_FOUND"),
        )
        for ((attempt, status, code) in cases) {
            val proof = e.proof().token
            val before = e.state()
            val rejected = e.edit(attempt, proof)
            e.problem(rejected, status, code, consumed = true)
            assertTrue(grantUsed(e, proof))
            val after = e.state()
            assertEquals(before.ownerState.content, after.ownerState.content)
            assertEquals(before.ownerState.resources, after.ownerState.resources)
            assertEquals(before.ownerState.audits, after.ownerState.audits)
            assertEquals(before.receipts.size + 1, after.receipts.size)
            assertEquals(before.journal, after.journal)
            f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT)
            val replay = e.edit(attempt)
            e.problem(replay, status, code, consumed = true)
            assertArrayEquals(rejected.contentAsByteArray, replay.contentAsByteArray)
            assertEquals(after, e.state())
        }
        val proof = e.proof().token
        val attempt = AdminContentAttempt(report)
        val before = e.state()
        for ((request, status, code) in listOf(
            Triple(e.input(attempt, proof).apply { removeHeader("If-Match") }, 428, "PRECONDITION_REQUIRED"),
            Triple(e.input(attempt, proof).apply { removeHeader("If-Match"); addHeader("If-Match", "*") }, 412, "PRECONDITION_FAILED"),
            Triple(e.input(attempt, proof, "invalid-token"), 401, "UNAUTHORIZED"),
            Triple(e.input(attempt, proof, f.token), 401, "UNAUTHORIZED"),
            Triple(e.input(attempt, proof, selectedScope = ComplaintDataScope.of(UUID.randomUUID())), 404, "NOT_FOUND"),
        )) {
            e.problem(e.send(request), status, code)
            assertEquals(before, e.state())
        }
    }

    @Test
    fun realProofScopeUserExpiryReuseAndCurrentAdminGenerationCannotBeReplacedByTokenRole() = withFixture { e ->
        val f = e.base
        val id = e.report()
        val attempt = AdminContentAttempt(id)
        val proof = e.proof().token
        val source = e.proof(ScopedAdminStepUpScope.SOURCE).token
        val other = UUID.randomUUID()
        val actor = e.user()
        assertEquals(1, e.observer.update(
            "INSERT INTO users (id,email,password_hash,role,enabled,created_at,updated_at) VALUES (?, ?, ?, 'ADMIN', true, now(), now())",
            other, "admin-content-$other@example.invalid", actor.passwordHash,
        ))
        try {
            val wrongUser = e.stepUp.issue(ScopedAdminStepUpScope.COMPLAINT, userId = other).token
            val expired = e.proof().token
            assertEquals(1, e.observer.update(
                "UPDATE admin_step_up_grants SET created_at = now() - interval '2 minutes', expires_at = now() - interval '1 minute' WHERE token_hash = ?",
                Sha256.hexUtf8(expired),
            ))
            val before = e.state()
            for (supplied in listOf(null, source, wrongUser, expired, "unknown-printable-proof")) {
                e.problem(e.edit(attempt, supplied), 401, "ADMIN_STEP_UP_REQUIRED")
                assertEquals(before, e.state())
            }
            assertEquals(false, e.observer.queryForObject("SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE token_hash = ?", Boolean::class.java, Sha256.hexUtf8(wrongUser)))
            for (bearer in listOf(
                JwtTestSupport.tamperSignature(e.token), JwtTestSupport.mint(e.ordinary.userId, credentialVersion = 0),
                JwtTestSupport.mint(e.ordinary.userId, credentialVersion = "00"), JwtTestSupport.mint(e.ordinary.userId, credentialVersion = "1"),
            )) {
                e.problem(e.edit(attempt, proof, bearer), 401, "UNAUTHORIZED")
                assertEquals(before, e.state())
            }
            // The signed diagnostic USER role does not revoke the actual database ADMIN.
            e.acknowledged(e.edit(attempt, proof, JwtTestSupport.mint(e.ordinary.userId, role = "USER")), id, 2)
            val applied = e.state()
            e.problem(e.edit(attempt.copy(key = UUID.randomUUID(), version = 2, body = "Reused proof"), proof), 401, "ADMIN_STEP_UP_REQUIRED")
            assertEquals(applied, e.state())
        } finally {
            e.observer.update(
                "UPDATE admin_step_up_grants SET created_at = now() - interval '2 minutes', expires_at = now() - interval '1 minute' WHERE user_id = ?",
                other,
            )
            e.stepUp.complaintCleanup.cleanupComplaintGrants()
            assertEquals(1, e.observer.update("DELETE FROM users WHERE id = ?", other))
        }
        for (change in listOf(
            AdminChange("role = 'USER'", "role = 'ADMIN'", 403, "FORBIDDEN"),
            AdminChange("enabled = false", "enabled = true", 401, "UNAUTHORIZED"),
            AdminChange("credential_version = 1", "credential_version = 0", 401, "UNAUTHORIZED"),
        )) {
            val fresh = e.proof().token
            val before = e.state()
            val changed = AtomicBoolean()
            f.beforeStep = { step ->
                if (step == OwnerCreateFixtureStep.COUNTERS && changed.compareAndSet(false, true)) {
                    assertEquals(true, f.jdbc.queryForObject("SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE token_hash = ?", Boolean::class.java, Sha256.hexUtf8(fresh)))
                    assertEquals(1, e.observer.update("UPDATE users SET ${change.change} WHERE id = ?", e.ordinary.userId))
                }
            }
            try {
                e.problem(e.edit(AdminContentAttempt(id, version = 2), fresh), change.status, change.code)
                assertTrue(changed.get(), "Revoke only after genuine preflight release and original proof consumption, before current ADMIN lock.")
                assertEquals(before, e.state(), "Authentication failure must roll proof, claim, capacity and all content/audit back.")
            } finally {
                f.beforeStep = {}
                assertEquals(1, e.observer.update("UPDATE users SET ${change.restore} WHERE id = ?", e.ordinary.userId))
            }
        }
    }

    @Test
    fun realGrantAndJwtExpiryAfterTheirOriginalLocksRollbackWithoutUsingPreLockTime() = withFixture { e ->
        val f = e.base
        val id = e.report()
        // Only the existing issuer's test clock selects a nearly expired real grant. The writer still uses database time.
        val nearExpiry = ScopedStepUpFixture(
            e.ordinary, f.base.counters, f.policy.digestBytes(),
            phaseClock = Clock.fixed(Instant.now().minus(e.stepUp.properties.stepUpTtl).plusMillis(750), ZoneOffset.UTC),
        )
        val proof = nearExpiry.issue(ScopedAdminStepUpScope.COMPLAINT)
        val before = e.state()
        val locked = AtomicBoolean()
        f.observations.clear()
        f.afterStep = { step ->
            if (step == OwnerCreateFixtureStep.ADMIN_GRANT_LOCK && locked.compareAndSet(false, true)) {
                assertEquals(true, f.jdbc.queryForObject(
                    "SELECT used_at IS NULL AND expires_at > clock_timestamp() FROM admin_step_up_grants WHERE token_hash = ?",
                    Boolean::class.java, Sha256.hexUtf8(proof.token),
                ), "The genuine lock must return while the grant is still valid.")
                val start = System.nanoTime()
                while (e.observer.queryForObject("SELECT clock_timestamp() >= ?", Boolean::class.java, Timestamp.from(proof.expiresAt)) != true) {
                    assertTrue(System.nanoTime() - start < 1_000_000_000L)
                    LockSupport.parkNanos(1_000_000)
                }
            }
        }
        try {
            e.problem(e.edit(AdminContentAttempt(id), proof.token), 401, "ADMIN_STEP_UP_REQUIRED")
            assertTrue(locked.get())
            assertEquals(before, e.state())
            assertFalse(grantUsed(e, proof.token))
            assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.COUNTERS })
        } finally { f.afterStep = {} }

        val fresh = e.proof().token
        val jwtBefore = e.state()
        val until = e.observer.queryForObject(
            "SELECT date_trunc('second', clock_timestamp()) + interval '2 seconds'", Timestamp::class.java,
        )!!.toInstant()
        val start = System.nanoTime()
        while (e.observer.queryForObject("SELECT clock_timestamp() >= ?", Boolean::class.java, Timestamp.from(until.minusMillis(750))) != true) {
            assertTrue(System.nanoTime() - start < 2_000_000_000L)
            LockSupport.parkNanos(1_000_000)
        }
        val expires = until.minus(e.userJwt.properties.clockSkew)
        val bearer = JwtTestSupport.mint(e.ordinary.userId, issuedAt = expires.minusSeconds(60), expiresAt = expires)
        val contentObservations = AtomicInteger()
        f.observations.clear()
        f.afterStep = { step ->
            // The first observation discovers owner_id; the second is the actual FOR UPDATE content lock.
            if (step == OwnerCreateFixtureStep.PARENT_CONTENT && contentObservations.incrementAndGet() == 2) {
                assertEquals(true, f.jdbc.queryForObject("SELECT clock_timestamp() < ?", Boolean::class.java, Timestamp.from(until)))
                val heldAt = System.nanoTime()
                while (e.observer.queryForObject("SELECT clock_timestamp() >= ?", Boolean::class.java, Timestamp.from(until)) != true) {
                    assertTrue(System.nanoTime() - heldAt < 1_000_000_000L)
                    LockSupport.parkNanos(1_000_000)
                }
            }
        }
        try {
            e.problem(e.edit(AdminContentAttempt(id), fresh, bearer), 401, "UNAUTHORIZED")
            assertEquals(2, contentObservations.get())
            assertEquals(jwtBefore, e.state(), "JWT expiry after the real content lock rolls the already consumed proof and quota-backed SQL effects back.")
            assertFalse(grantUsed(e, fresh))
            assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.EDIT_CONTENT })
        } finally { f.afterStep = {} }
    }

    @Test
    fun originalSqlFaultsCapacityAndMaintenanceRefusalsRollbackProofReceiptAuditAndContent() = withFixture { e ->
        val f = e.base
        val id = e.report()
        for (point in listOf(OwnerCreateFixtureStep.CLAIM, OwnerCreateFixtureStep.CHARGE, OwnerCreateFixtureStep.EDIT_CONTENT, OwnerCreateFixtureStep.COMPLETE)) {
            val proof = e.proof().token
            val before = e.state()
            val reached = AtomicBoolean()
            f.observations.clear()
            f.afterStep = { step ->
                if (step == point && reached.compareAndSet(false, true)) {
                    if (point == OwnerCreateFixtureStep.COMPLETE) {
                        assertEquals(true, f.jdbc.queryForObject(
                            "SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE token_hash = ?", Boolean::class.java, Sha256.hexUtf8(proof),
                        ))
                        assertEquals(1L, f.jdbc.queryForObject(
                            "SELECT count(*) FROM audit_log WHERE actor_user_id = ? AND complaint_data_scope_id = ? " +
                                "AND entity_id = ? AND action = 'COMPLAINT_CONTENT_EDITED' AND detail = '{\"version\":2}'::jsonb",
                            Long::class.java, e.ordinary.userId, e.scope.id, id.toString(),
                        ), "Observe the real audit on the same ordinary EntityManager transaction before the injected rollback.")
                    }
                    throw SyntheticInstallationEnrollmentFailure()
                }
            }
            try {
                e.problem(e.edit(AdminContentAttempt(id), proof), 503, "SERVICE_UNAVAILABLE")
                assertTrue(reached.get())
                assertEquals(before, e.state())
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, f.observations.last().second.phase.databaseOutcome())
            } finally { f.afterStep = {} }
        }
        val proof = e.proof().token
        val attempt = AdminContentAttempt(id)
        val before = e.state()
        val storage = before.ownerState.counters.getValue(ComplaintCapacityCounter.STORAGE_BYTES.storedName)
        assertEquals(1, e.observer.update(
            "UPDATE complaint_capacity_counters SET free_units = 0, actual_units = hard_limit - recovery_reserved_units - test_reserved_units WHERE name = 'storage_bytes'",
        ))
        try {
            val full = e.state()
            e.problem(e.edit(attempt, proof), 503, "SERVICE_UNAVAILABLE")
            assertEquals(full, e.state())
        } finally {
            assertEquals(1, e.observer.update("UPDATE complaint_capacity_counters SET free_units = ?, actual_units = ? WHERE name = 'storage_bytes'", storage.free, storage.actual))
        }
        f.observations.clear()
        f.lock("SELECT pg_advisory_xact_lock(hashtextextended('complaint-maintenance-v1', 0))").use { held ->
            try {
                e.problem(e.edit(attempt, proof), 503, "SERVICE_UNAVAILABLE")
                assertEquals(before, e.state())
                assertFalse(f.observations.any { it.first == OwnerCreateFixtureStep.CLAIM || it.first == OwnerCreateFixtureStep.ADMIN_GRANT_LOCK })
            } finally { held.rollback() }
        }
        val admission = OrdinaryPersistenceAdmission.sourceOnly(4)
        val sourceOnly = PersistencePhaseOwnership(admission, GuardedJpaTransactionManager(e.ordinary.entityManagerFactory, e.ordinary.pool))
        for (enter in listOf(sourceOnly::enterComplaintAdminEditPreflight, sourceOnly::enterComplaintAdminEdit)) {
            val refused = assertThrows<PersistencePhaseException> { enter() }
            assertEquals(PersistenceDatabaseOutcome.NONE, refused.databaseOutcome)
            assertTrue(refused.cleanupProven)
            assertEquals(0, admission.activeOwners())
        }
        assertEquals(before, e.state())
        e.acknowledged(e.edit(attempt, proof), id, 2)
        f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT + ComplaintCapacityCharges.AUDIT)
    }

    @Test
    fun originalClaimsLinearizeDistinctAndSameKeysBeforeProofReuseAndDeletionPendingWinsAfterPreflight() = withFixture { e ->
        val f = e.base
        val id = e.report()
        val first = AdminContentAttempt(id, body = "Winning body")
        val second = AdminContentAttempt(id, body = "Competing body")
        val proofs = listOf(e.proof().token, e.proof().token)
        val before = e.state()
        OwnedCallerTestScope().use { callers ->
            val firstGate = callers.gate()
            val secondGate = callers.gate()
            val arriving = AtomicInteger()
            f.beforeStep = { step ->
                if (step == OwnerCreateFixtureStep.CLAIM) when (arriving.incrementAndGet()) {
                    1 -> firstGate.hold()
                    2 -> secondGate.hold()
                    else -> error("Unexpected extra claim")
                }
            }
            try {
                val winner = callers.launch { e.send(e.input(first, proofs[0])) }
                firstGate.awaitEntered()
                val stale = callers.launch { e.send(e.input(second, proofs[1])) }
                secondGate.awaitEntered()
                assertEquals(2, arriving.get(), "Both genuine authentication/preflight phases released before either claim.")
                firstGate.release()
                assertEquals(200, winner.value().status)
                secondGate.release()
                e.problem(stale.value(), 412, "PRECONDITION_FAILED", consumed = true)
                e.acknowledged(winner.value(), id, 2)
            } finally {
                firstGate.release()
                secondGate.release()
                f.beforeStep = {}
            }
        }
        proofs.forEach { assertTrue(grantUsed(e, it)) }
        assertEquals("Winning body", e.observer.queryForObject("SELECT body FROM complaints WHERE id = ?", String::class.java, id))
        assertEquals(before.receipts.size + 2, e.state().receipts.size)
        assertEquals(before.ownerState.audits.size + 1, e.state().ownerState.audits.size)
        f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT.scaled(2) + ComplaintCapacityCharges.AUDIT)

        for (insertAlreadyHeld in listOf(true, false)) {
            val attempt = AdminContentAttempt(id, version = if (insertAlreadyHeld) 2 else 3, body = "Next $insertAlreadyHeld")
            val proof = e.proof().token
            val baseline = e.state()
            f.observations.clear()
            OwnedCallerTestScope().use { callers ->
                val gate = callers.gate()
                val once = AtomicBoolean()
                val hook: (OwnerCreateFixtureStep) -> Unit = { step ->
                    if (step == OwnerCreateFixtureStep.CLAIM && once.compareAndSet(false, true)) gate.hold()
                }
                if (insertAlreadyHeld) f.afterStep = hook else f.beforeStep = hook
                try {
                    val held = callers.launch { e.send(e.input(attempt, proof)) }
                    gate.awaitEntered()
                    val competing = e.send(e.input(attempt, proof))
                    if (insertAlreadyHeld) {
                        e.problem(competing, 409, "IDEMPOTENCY_IN_PROGRESS")
                        assertEquals("1", competing.getHeader("Retry-After"))
                        assertFalse(grantUsed(e, proof), "The held original claim has not consumed proof yet; its uncommitted result is invisible.")
                    } else {
                        assertEquals(200, competing.status)
                    }
                    gate.release()
                    e.acknowledged(held.value(), id, attempt.version + 1)
                    if (!insertAlreadyHeld) {
                        assertArrayEquals(competing.contentAsByteArray, held.value().contentAsByteArray)
                        assertEquals(2, f.observations.count { it.first == OwnerCreateFixtureStep.CLAIM })
                    }
                } finally {
                    gate.release()
                    f.beforeStep = {}
                    f.afterStep = {}
                }
            }
            assertTrue(grantUsed(e, proof))
            assertEquals(1, f.observations.count { it.first == OwnerCreateFixtureStep.EDIT_CONTENT })
            assertEquals(baseline.receipts.size + 1, e.state().receipts.size)
            f.assertCharge(baseline.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT + ComplaintCapacityCharges.AUDIT)
            val completed = e.state()
            e.acknowledged(e.edit(attempt), id, attempt.version + 1)
            assertEquals(completed, e.state())
        }

        // Small legal state linearizations, not synthetic deletion publication, WORM, or deletion-permit evidence.
        for ((table, target, restored) in listOf(
            Triple("complaint_installation_ids", f.actor.id, "ACTIVE"),
            Triple("app_installations", f.actor.id, "ACTIVE"),
            Triple("complaint_resource_ids", id, "LIVE"),
        )) {
            val proof = e.proof().token
            val attempt = AdminContentAttempt(id, version = 4)
            val baseline = e.state()
            val changed = AtomicBoolean()
            f.beforeStep = { step ->
                if (step == OwnerCreateFixtureStep.CLAIM && changed.compareAndSet(false, true)) {
                    assertEquals(1, e.observer.update("UPDATE $table SET state = 'DELETION_PENDING' WHERE id = ?", target))
                }
            }
            try {
                e.problem(e.edit(attempt, proof), 409, "COMPLAINT_DELETION_PENDING", consumed = true)
                assertTrue(changed.get())
                assertTrue(grantUsed(e, proof))
                assertEquals(baseline.ownerState.content, e.state().ownerState.content)
                assertEquals(baseline.ownerState.audits, e.state().ownerState.audits)
                assertEquals(baseline.journal, e.state().journal)
                f.assertCharge(baseline.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT)
            } finally {
                f.beforeStep = {}
                assertEquals(1, e.observer.update("UPDATE $table SET state = ? WHERE id = ?", restored, target))
            }
        }
    }

    @Test
    fun unknownCommitFailedCommittedTailAndPartialDeliveryNeverGuessConsumptionOrAppendButExactRetryRecovers() {
        for (mode in listOf("COMMIT", "TAIL", "DELIVERY")) withFixture { e ->
            val f = e.base
            val id = e.report()
            val attempt = AdminContentAttempt(id)
            val proof = e.proof().token
            val before = e.state()
            val installed = AtomicBoolean()
            f.observations.clear()
            f.afterStep = { step ->
                if (step == OwnerCreateFixtureStep.COMPLETE && installed.compareAndSet(false, true)) {
                    when (mode) {
                        "COMMIT" -> {
                            f.jdbc.execute("CREATE TEMP TABLE kira_admin_content_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                            assertEquals(2, f.jdbc.update("INSERT INTO kira_admin_content_commit VALUES (1), (1)"))
                        }
                        "TAIL" -> TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                            override fun afterCommit(): Unit = throw SyntheticInstallationEnrollmentFailure()
                        })
                        else -> Unit
                    }
                }
            }
            try {
                if (mode == "DELIVERY") {
                    val prefix = ByteArrayOutputStream()
                    val accesses = AtomicInteger()
                    val response = object : MockHttpServletResponse() {
                        override fun getOutputStream(): ServletOutputStream {
                            f.assertReleased()
                            accesses.incrementAndGet()
                            return object : ServletOutputStream() {
                                override fun isReady(): Boolean = true
                                override fun setWriteListener(listener: WriteListener) = Unit
                                override fun write(value: Int) {
                                    prefix.write(value)
                                    if (prefix.size() == 12) throw IOException("Synthetic private delivery failure")
                                }
                            }
                        }
                    }
                    val failed = assertThrows<IOException> { e.handler.handleRequest(e.input(attempt, proof), response) }
                    assertEquals("Complaint response delivery failed.", failed.message)
                    assertNull(failed.cause)
                    assertTrue(failed.suppressed.isEmpty())
                    assertEquals(1, accesses.get())
                    assertEquals(12, prefix.size())
                    assertArrayEquals("{\"id\":\"$id\",\"version\":2}".toByteArray().copyOfRange(0, 12), prefix.toByteArray())
                    assertEquals(listOf("true"), response.getHeaders(ComplaintAdminContentFixture.CONSUMED).toList())
                } else {
                    e.problem(e.edit(attempt, proof), 503, "SERVICE_UNAVAILABLE")
                }
                assertTrue(installed.get(), "Fault must occur only after actual proof, audit and terminal receipt SQL.")
            } finally { f.afterStep = {} }
            f.assertReleased()
            assertEquals(
                if (mode == "COMMIT") PersistenceDatabaseOutcome.UNKNOWN else PersistenceDatabaseOutcome.COMMITTED,
                f.observations.last().second.phase.databaseOutcome(),
            )
            if (mode == "COMMIT") {
                assertEquals(before, e.state())
                assertFalse(grantUsed(e, proof))
                e.acknowledged(e.edit(attempt, proof), id, 2)
            } else {
                assertTrue(grantUsed(e, proof))
                val committed = e.state()
                e.acknowledged(e.edit(attempt), id, 2)
                assertEquals(committed, e.state())
            }
            f.assertCharge(before.ownerState.counters, ComplaintCapacityCharges.NORMAL_RECEIPT + ComplaintCapacityCharges.AUDIT)
        }
    }

    private fun grantUsed(e: ComplaintAdminContentFixture, proof: String): Boolean = e.observer.queryForObject(
        "SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE user_id = ? AND token_hash = ?",
        Boolean::class.java, e.ordinary.userId, Sha256.hexUtf8(proof),
    )!!

    private fun withFixture(work: (ComplaintAdminContentFixture) -> Unit) = withComplaintOwnerCreate(database.value) { base ->
        ComplaintAdminContentFixture(base).use(work)
    }

    private data class AdminChange(val change: String, val restore: String, val status: Int, val code: String)
}
