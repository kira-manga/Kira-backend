package me.manga.kira.backend.complaint.infrastructure.reconciliation

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletInputStream
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerReplyParentRows
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerCreateStore
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJpaTransactionManager
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerEditStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.ownerCreateTestIngress
import me.manga.kira.backend.security.ownerEditTestIngress
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointCreateCasesV1.refused
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Only negative/observational seams. Product clocks, limits, deadlines, SQL results and cleanup are never replaced. */
internal object TestRegisteredInitialCheckpointCreateRaceCasesV1 {
    fun claimLoser(f: TestRegisteredInitialCheckpointCreateFixtureV1) = claimLoser(f, desiredDrift = false)
    fun desiredIdentityClaimLoser(f: TestRegisteredInitialCheckpointCreateFixtureV1) = claimLoser(f, desiredDrift = true)

    fun replyExactGraph(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val attempt = registeredReplyAttempt(f.actor, f.notice())
        val before = f.state(); val calls = f.jdbc.calls.size; val providers = f.providerCounts()
        val foreignOwner = PersistencePhaseOwnership(f.exchange.ordinary.admission,
            GuardedJpaTransactionManager(f.exchange.ordinary.entityManagerFactory, f.exchange.ordinary.pool))
        assertThrows<Exception> { JdbcComplaintOwnerCreateStore.registeredInitialCheckpointWithReplies(f.jdbc, f.exchange.service,
            foreignOwner, f.registration, f.checkpoint.assembly) }
        assertThrows<Exception> { JdbcComplaintOwnerCreateStore.registeredInitialCheckpointWithReplies(JdbcTemplate(f.process.pools.ordinary),
            f.exchange.service, f.exchange.ordinary.ownership, f.registration, f.checkpoint.assembly) }
        assertEquals(calls, f.jdbc.calls.size)
        val foreignIngress = ownerCreateTestIngress(f.process.consumers.capacityPolicy, creates = f.process.consumers.ownerCreatePolicy)
        foreignIngress.withIngress(f.request()) { context ->
            foreignIngress.startOwnerReply(context)
            val handoff = foreignIngress.admitOwnerReply(context, attempt.candidate.tuple)
            val failure = assertThrows<PersistencePhaseException> {
                f.replyExecutor.reply(f.identity(), attempt.candidate, ComplaintPlatform.ANDROID, handoff)
            }
            assertTrue(failure.cleanupProven); assertEquals(PersistenceDatabaseOutcome.NONE, failure.databaseOutcome)
        }
        val missing = assertThrows<PersistencePhaseException> { f.replyExecutor.replyPreflight(f.identity(), attempt.candidate.tuple) }
        assertTrue(missing.cleanupProven); assertEquals(PersistenceDatabaseOutcome.NONE, missing.databaseOutcome)
        assertEquals(calls, f.jdbc.calls.size, "Foreign or missing ingress cannot start any JDBC work.")
        assertTrue(f.replySql().isEmpty()); assertEquals(before, f.state()); f.assertReleased()
        f.assertApplied(f.reply(attempt), attempt); f.assertReleased()
        val applied = f.state(); val counters = f.counters()
        f.registration.close()
        refused { f.reply(attempt) }; refused { f.replyStatus(attempt) }
        assertEquals(applied, f.state()); assertEquals(counters, f.counters())
        assertEquals(providers, f.providerCounts()); f.assertReleased()
    }

    /** The richer birth recipe is not an implicit route selection or EDIT authority for a narrower factory. */
    fun narrowerFactoriesStayNarrow(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val before = f.state(); val providers = f.providerCounts()
        val bootstrap = ComplaintTestBootstrapHttpCompositionV1.fromRegistered(f.registration, f.exchange.ordinary.ownership, f.jdbc)
        val create = ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointCreate(
            f.registration, f.checkpoint.assembly, f.exchange.ordinary.ownership, f.jdbc, f.exchange.service)
        val reply = ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateReply(
            f.registration, f.checkpoint.assembly, f.exchange.ordinary.ownership, f.jdbc, f.exchange.service)
        val edit = ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateReplyEdit(
            f.registration, f.checkpoint.assembly, f.exchange.ordinary.ownership, f.jdbc, f.exchange.service)
        val base = ComplaintInstallationRoutes.HISTORY
        val id = UUID.randomUUID()
        assertEquals(setOf(ComplaintInstallationRoutes.BOOTSTRAP), bootstrap.mappedPaths)
        assertFalse("${base}/{id}/replies" in create.mappedPaths); assertFalse("${base}/{id}/content" in create.mappedPaths)
        assertTrue("${base}/{id}/replies" in reply.mappedPaths); assertFalse("${base}/{id}/content" in reply.mappedPaths)
        assertTrue("${base}/{id}/content" in edit.mappedPaths)
        for ((composition, method, path) in listOf(
            Triple(bootstrap, "POST", base), Triple(create, "POST", "$base/$id/replies"),
            Triple(create, "PATCH", "$base/$id/content"), Triple(reply, "PATCH", "$base/$id/content"),
        )) {
            val request = object : MockHttpServletRequest(method, path) {
                override fun getInputStream(): ServletInputStream = error("Narrow factory acquired an excluded body")
            }.apply {
                servletPath = path; remoteAddr = "192.0.2.43"
                addHeader("Authorization", "malformed"); addHeader("Content-Length", Long.MAX_VALUE.toString())
            }
            val response = MockHttpServletResponse()
            composition.ingressFilter.doFilter(request, response, FilterChain { _, _ -> error("Excluded route escaped the fixed ingress filter") })
            assertEquals(404, response.status); assertNull(response.getHeader("WWW-Authenticate"))
        }
        assertTrue(f.jdbc.calls.isEmpty(), "Explicitly narrower selectors refuse before AUTH, buffering or new-work SQL.")
        assertEquals(before, f.state()); assertEquals(providers, f.providerCounts()); f.assertReleased()
    }

    fun replyClaimLoser(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val currentSql = f.currentCheckpointSql()
        val attempt = registeredReplyAttempt(f.actor, f.notice())
        val before = f.counters(); val providers = f.providerCounts()
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
            JdbcComplaintOwnerCreateStore.registeredInitialCheckpointWithReplies(JdbcTemplate(f.process.pools.ordinary), f.exchange.service,
                f.exchange.ordinary.ownership, f.registration, f.checkpoint.assembly)
        }
        // Even this reply-capable full D cannot widen the older registered CREATE-only store.
        refused { f.ingress.withIngress(f.request()) { f.adapter.reply(it, f.token, attempt.input) } }
        assertEquals(before, f.counters()); assertTrue(f.replySql().isEmpty())
        val winnerThread = AtomicReference<Thread?>(); val loserPid = AtomicInteger()
        val loserEntering = CountDownLatch(1); val closedAfterClaim = AtomicBoolean()
        f.raw { observer ->
            OwnedCallerTestScope().use { callers ->
                val completedWinner = callers.gate()
                f.jdbc.before = { path, sql ->
                    if (path === REGISTERED_REPLY && sql.startsWith("INSERT INTO complaint_idempotency_receipts") &&
                        winnerThread.get() != null && Thread.currentThread() !== winnerThread.get()) {
                        loserPid.set(currentPid(f)); loserEntering.countDown()
                    }
                }
                f.jdbc.after = { path, sql -> if (path === REGISTERED_REPLY) {
                    if ("UPDATE complaint_idempotency_receipts" in sql && winnerThread.compareAndSet(null, Thread.currentThread())) completedWinner.hold()
                    if (sql.startsWith("INSERT INTO complaint_idempotency_receipts") && winnerThread.get() != null &&
                        Thread.currentThread() !== winnerThread.get() && closedAfterClaim.compareAndSet(false, true)) {
                        assertEquals(1, f.exchange.f.foreignUpdate("UPDATE complaint_journal_control SET creation_closed = true, maintenance_closed = true WHERE data_scope_id = ?", f.scope))
                    }
                } }
                val winner = callers.launch { f.reply(attempt) }
                completedWinner.awaitEntered()
                try {
                    val loser = callers.launch { f.reply(attempt) }
                    assertTrue(loserEntering.await(1, TimeUnit.SECONDS))
                    awaitActualLockWait(observer, loserPid.get())
                    completedWinner.release()
                    f.assertApplied(winner.value(), attempt); f.assertApplied(loser.value(), attempt)
                } finally { completedWinner.release() }
            }
        }
        f.jdbc.before = { _, _ -> }; f.jdbc.after = { _, _ -> }
        assertTrue(closedAfterClaim.get()); f.assertReleased()
        assertEquals(2, f.replyPhases().size)
        assertTrue(f.replyPhases().all { it.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED })
        assertEquals(7, f.replySql().count { it == currentSql }, "Only the winner runs new-work checks, including both resources and parent content.")
        assertEquals(1, f.replySql().count { "FROM complaint_capacity_counters" in it && "FOR UPDATE" in it })
        f.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE)
        assertEquals(1L, f.observer.queryForObject("SELECT count(*) FROM complaint_idempotency_receipts WHERE actor_id = ? AND operation = 'OWNER_REPLY'", Long::class.java, f.actor.id))
        val state = f.state(); f.jdbc.calls.clear()
        f.assertApplied(f.reply(attempt), attempt); f.assertApplied(f.replyStatus(attempt), attempt)
        assertTrue(f.replySql().isEmpty()); assertEquals(state, f.state())
        assertEquals(providers, f.providerCounts()); f.assertReleased()
    }

    fun replyWaitedCheckpointExpiry(f: TestRegisteredInitialCheckpointCreateFixtureV1, resource: Boolean) {
        val currentSql = f.currentCheckpointSql()
        val deadlines = f.process.consumers.journalConfiguration.declaration().limits.deadlines
        assertEquals(30_000, deadlines.scanMillis); assertEquals(30_000, deadlines.scanCadenceMillis); assertEquals(30_000, deadlines.checkpointMaxAgeMillis)
        val first = registeredReplyAttempt(f.actor, f.notice())
        f.assertApplied(f.reply(first), first); f.assertReleased()
        val before = f.state(); val counters = f.counters(); val providers = f.providerCounts()
        val expires = (f.checkpoint.control().getValue("checkpoint_completed_at") as Timestamp).toInstant().plusMillis(deadlines.checkpointMaxAgeMillis.toLong())
        val second = registeredReplyAttempt(f.actor, first.input.id, UUID.fromString("00000000-0000-4000-8000-000000000001"))
        assertTrue(second.input.id.toString() < first.input.id.toString(), "The provisional child must precede the locked parent.")
        val entering = CountDownLatch(1); val reached = AtomicBoolean(); val pid = AtomicInteger()
        val blockedSql = if (resource) ComplaintOwnerReplyParentRows.RESOURCE else ComplaintOwnerReplyParentRows.content
        f.raw { locker ->
            locker.autoCommit = false
            try {
                val table = if (resource) "complaint_resource_ids" else "complaints"
                locker.prepareStatement("SELECT id FROM $table WHERE id = ? FOR UPDATE").use { statement ->
                    statement.queryTimeout = 1; statement.setObject(1, first.input.id)
                    statement.executeQuery().use { row -> assertTrue(row.next()); assertFalse(row.next()) }
                }
                f.raw { observer ->
                    waitUntil(observer, expires.minusMillis(800), 31_000) // No original request/admission exists during this wait.
                    f.jdbc.calls.clear()
                    OwnedCallerTestScope().use { callers ->
                        f.jdbc.before = { path, sql -> if (path === REGISTERED_REPLY && sql == blockedSql && reached.compareAndSet(false, true)) {
                            // Negative caller stall consumes the same 2s original. Then the real 100ms
                            // lock_timeout remains untouched while the actual row wait crosses expiry.
                            waitUntil(observer, expires.minusMillis(45), 900)
                            assertTrue(now(observer).isBefore(expires))
                            pid.set(currentPid(f)); entering.countDown()
                        } }
                        val original = callers.launch { f.reply(second) }
                        assertTrue(entering.await(1, TimeUnit.SECONDS))
                        awaitActualLockWait(observer, pid.get())
                        waitUntil(observer, expires.plusNanos(1000), 80)
                        locker.commit()
                        val failure = original.problem()
                        assertTrue(failure is ComplaintOwnerOperationRejected)
                        assertEquals(ComplaintOwnerOperationFailure.UNAVAILABLE, (failure as ComplaintOwnerOperationRejected).failure)
                    }
                }
            } finally { f.jdbc.before = { _, _ -> }; locker.rollback() }
        }
        assertTrue(reached.get()); f.assertReleased()
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, f.replyPhases().last().databaseOutcome())
        assertTrue(f.replySql().any { it.startsWith("INSERT INTO complaint_resource_ids") })
        assertEquals(if (resource) 5 else 6, f.replySql().count { it == currentSql })
        if (resource) assertFalse(f.replySql().contains(ComplaintOwnerReplyParentRows.content))
        assertFalse(f.replySql().any { it.startsWith("INSERT INTO complaints") || "UPDATE complaint_idempotency_receipts" in it })
        assertEquals(before, f.state()); assertEquals(counters, f.counters()) // Includes provisional child, claim, capacity and audit rollback.
        f.jdbc.calls.clear()
        f.assertApplied(f.reply(first), first); f.assertApplied(f.replyStatus(first), first)
        refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { f.replyStatus(second) }
        assertTrue(f.replySql().isEmpty(), "Expired current checkpoint cannot block exact completed reply receipt reads.")
        refused { f.reply(second) }
        assertFalse(f.replySql().any { "complaint_capacity_counters" in it })
        assertEquals(before, f.state()); assertEquals(counters, f.counters())
        assertEquals(providers, f.providerCounts()); f.assertReleased()
    }

    fun editExactGraph(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val report = f.attempt(); f.assertApplied(f.create(report), report); f.assertReleased()
        val attempt = registeredEditAttempt(f.actor, report.input.id)
        val before = f.state(); val calls = f.jdbc.calls.size; val providers = f.providerCounts()
        val p = f.process.consumers.capacityPolicy
        assertThrows<Exception> { JdbcComplaintOwnerEditStore(f.jdbc, JdbcComplaintCapacityStore(f.jdbc, p.digestBytes()),
            f.exchange.service, f.process.desiredSettings()) }
        assertThrows<Exception> { JdbcComplaintOwnerEditStore.registeredInitialCheckpoint(JdbcTemplate(f.process.pools.ordinary), f.exchange.service,
            f.exchange.ordinary.ownership, f.registration, f.checkpoint.assembly) }
        val foreignOwner = PersistencePhaseOwnership(f.exchange.ordinary.admission,
            GuardedJpaTransactionManager(f.exchange.ordinary.entityManagerFactory, f.exchange.ordinary.pool))
        assertThrows<Exception> { JdbcComplaintOwnerEditStore.registeredInitialCheckpoint(f.jdbc, f.exchange.service,
            foreignOwner, f.registration, f.checkpoint.assembly) }
        assertEquals(calls, f.jdbc.calls.size)
        val foreignIngress = ownerEditTestIngress(p)
        foreignIngress.withIngress(f.request()) { context ->
            foreignIngress.startOwnerEdit(context)
            val handoff = foreignIngress.admitOwnerEdit(context, attempt.candidate.tuple)
            val failure = assertThrows<PersistencePhaseException> { f.editExecutor.edit(f.identity(), attempt.candidate, ComplaintPlatform.ANDROID, handoff) }
            assertTrue(failure.cleanupProven); assertEquals(PersistenceDatabaseOutcome.NONE, failure.databaseOutcome)
        }
        val missingIngress = assertThrows<PersistencePhaseException> { f.editExecutor.authenticate(f.identity()) }
        assertTrue(missingIngress.cleanupProven); assertEquals(PersistenceDatabaseOutcome.NONE, missingIngress.databaseOutcome)
        assertTrue(f.editSql().isEmpty()); assertEquals(before, f.state()); f.assertReleased()
        f.assertApplied(f.edit(attempt), attempt)
        val counters = f.counters()
        val generation = (f.checkpoint.control().getValue("desired_generation") as Number).toLong()
        assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET desired_generation = ? WHERE data_scope_id = ?", generation + 1, f.scope))
        val damaged = f.state()
        try {
            f.jdbc.calls.clear()
            refused { f.edit(attempt) }; refused { f.editStatus(attempt) }
            assertTrue(f.editSql().isEmpty(), "Current desired identity still precedes even exact EDIT receipt replay.")
            assertEquals(damaged, f.state()); assertEquals(counters, f.counters())
        } finally {
            // Restore only this negative scoped drift to the captured original; no successful checkpoint/flag is seeded.
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET desired_generation = ? WHERE data_scope_id = ?", generation, f.scope))
        }
        val restored = f.state()
        f.assertApplied(f.editStatus(attempt), attempt)
        f.registration.close()
        refused { f.edit(attempt) }; refused { f.editStatus(attempt) }
        assertEquals(restored, f.state()); assertEquals(counters, f.counters())
        assertEquals(providers, f.providerCounts()); f.assertReleased()
    }

    fun editClaimLoser(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val currentSql = f.currentCheckpointSql()
        val report = f.attempt(); f.assertApplied(f.create(report), report)
        val attempt = registeredEditAttempt(f.actor, report.input.id)
        val before = f.counters(); val providers = f.providerCounts()
        val winnerThread = AtomicReference<Thread?>(); val loserPid = AtomicInteger()
        val loserEntering = CountDownLatch(1); val closedAfterClaim = AtomicBoolean()
        f.raw { observer ->
            OwnedCallerTestScope().use { callers ->
                val completedWinner = callers.gate()
                f.jdbc.before = { path, sql ->
                    if (path === REGISTERED_EDIT && sql.startsWith("INSERT INTO complaint_idempotency_receipts") &&
                        winnerThread.get() != null && Thread.currentThread() !== winnerThread.get()) {
                        loserPid.set(currentPid(f)); loserEntering.countDown()
                    }
                }
                f.jdbc.after = { path, sql -> if (path === REGISTERED_EDIT) {
                    if ("UPDATE complaint_idempotency_receipts" in sql && winnerThread.compareAndSet(null, Thread.currentThread())) completedWinner.hold()
                    if (sql.startsWith("INSERT INTO complaint_idempotency_receipts") && winnerThread.get() != null &&
                        Thread.currentThread() !== winnerThread.get() && closedAfterClaim.compareAndSet(false, true)) {
                        assertEquals(1, f.exchange.f.foreignUpdate("UPDATE complaint_journal_control SET creation_closed = true, maintenance_closed = true WHERE data_scope_id = ?", f.scope))
                    }
                } }
                val winner = callers.launch { f.edit(attempt) }
                completedWinner.awaitEntered()
                try {
                    val loser = callers.launch { f.edit(attempt) }
                    assertTrue(loserEntering.await(1, TimeUnit.SECONDS))
                    awaitActualLockWait(observer, loserPid.get())
                    completedWinner.release()
                    f.assertApplied(winner.value(), attempt); f.assertApplied(loser.value(), attempt)
                } finally { completedWinner.release() }
            }
        }
        f.jdbc.before = { _, _ -> }; f.jdbc.after = { _, _ -> }
        assertTrue(closedAfterClaim.get()); f.assertReleased()
        assertEquals(2, f.editPhases().size)
        assertTrue(f.editPhases().all { it.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED })
        assertEquals(6, f.editSql().count { it == currentSql }, "Only winner checks new work, never loser or historical If-Match.")
        assertEquals(1, f.editSql().count { "FROM complaint_capacity_counters" in it && "FOR UPDATE" in it })
        f.assertCharge(before, ComplaintCapacityCharges.OWNER_EDIT)
        assertEquals(1L, f.observer.queryForObject("SELECT count(*) FROM complaint_idempotency_receipts WHERE actor_id = ? AND operation = 'OWNER_EDIT'", Long::class.java, f.actor.id))
        val state = f.state(); f.jdbc.calls.clear()
        f.assertApplied(f.edit(attempt), attempt); f.assertApplied(f.editStatus(attempt), attempt)
        assertTrue(f.editSql().isEmpty()); assertEquals(state, f.state())
        assertEquals(providers, f.providerCounts()); f.assertReleased()
    }

    fun editWaitedCheckpointExpiry(f: TestRegisteredInitialCheckpointCreateFixtureV1, resource: Boolean) {
        val currentSql = f.currentCheckpointSql()
        val deadlines = f.process.consumers.journalConfiguration.declaration().limits.deadlines
        assertEquals(30_000, deadlines.scanMillis); assertEquals(30_000, deadlines.scanCadenceMillis); assertEquals(30_000, deadlines.checkpointMaxAgeMillis)
        val report = f.attempt(); f.assertApplied(f.create(report), report)
        val first = registeredEditAttempt(f.actor, report.input.id)
        f.assertApplied(f.edit(first), first); f.assertReleased()
        val before = f.state(); val counters = f.counters(); val providers = f.providerCounts()
        val expires = (f.checkpoint.control().getValue("checkpoint_completed_at") as Timestamp).toInstant().plusMillis(deadlines.checkpointMaxAgeMillis.toLong())
        val second = registeredEditAttempt(f.actor, report.input.id, version = 2, body = "Second registered edit")
        val blockedSql = f.editSql().single { if (resource) it.startsWith("SELECT state FROM complaint_resource_ids") else "FOR UPDATE OF c" in it }
        val entering = CountDownLatch(1); val reached = AtomicBoolean(); val pid = AtomicInteger()
        f.raw { locker ->
            locker.autoCommit = false
            try {
                val table = if (resource) "complaint_resource_ids" else "complaints"
                locker.prepareStatement("SELECT id FROM $table WHERE id = ? FOR UPDATE").use { statement ->
                    statement.queryTimeout = 1; statement.setObject(1, report.input.id)
                    statement.executeQuery().use { row -> assertTrue(row.next()); assertFalse(row.next()) }
                }
                f.raw { observer ->
                    waitUntil(observer, expires.minusMillis(800), 31_000) // No original request/admission exists during this wait.
                    f.jdbc.calls.clear()
                    OwnedCallerTestScope().use { callers ->
                        f.jdbc.before = { path, sql -> if (path === REGISTERED_EDIT && sql == blockedSql && reached.compareAndSet(false, true)) {
                            // Negative stall consumes the same2s original, with actual100ms lock_timeout untouched.
                            waitUntil(observer, expires.minusMillis(45), 900)
                            assertTrue(now(observer).isBefore(expires))
                            pid.set(currentPid(f)); entering.countDown()
                        } }
                        val original = callers.launch { f.edit(second) }
                        assertTrue(entering.await(1, TimeUnit.SECONDS))
                        awaitActualLockWait(observer, pid.get())
                        waitUntil(observer, expires.plusNanos(1000), 80)
                        locker.commit()
                        val failure = original.problem()
                        assertTrue(failure is ComplaintOwnerOperationRejected)
                        assertEquals(ComplaintOwnerOperationFailure.UNAVAILABLE, (failure as ComplaintOwnerOperationRejected).failure)
                    }
                }
            } finally { f.jdbc.before = { _, _ -> }; locker.rollback() }
        }
        assertTrue(reached.get()); f.assertReleased()
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, f.editPhases().last().databaseOutcome())
        assertEquals(if (resource) 4 else 5, f.editSql().count { it == currentSql })
        if (resource) assertFalse(f.editSql().any { "FOR UPDATE OF c" in it })
        assertFalse(f.editSql().any { "UPDATE complaints SET" in it || "UPDATE complaint_idempotency_receipts" in it })
        assertEquals(before, f.state()); assertEquals(counters, f.counters())
        f.jdbc.calls.clear()
        f.assertApplied(f.edit(first), first); f.assertApplied(f.editStatus(first), first)
        refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { f.editStatus(second) }
        assertTrue(f.editSql().isEmpty())
        refused { f.edit(second) }
        assertFalse(f.editSql().any { "complaint_capacity_counters" in it })
        assertEquals(before, f.state()); assertEquals(counters, f.counters())
        assertEquals(providers, f.providerCounts()); f.assertReleased()
    }

    private fun claimLoser(f: TestRegisteredInitialCheckpointCreateFixtureV1, desiredDrift: Boolean) {
        val currentSql = f.currentCheckpointSql()
        val attempt = f.attempt(); val before = f.counters(); val providers = f.providerCounts()
        val beforeReceipts = checkNotNull(f.observer.queryForObject(
            "SELECT count(*) FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND actor_id = ?", Long::class.java, f.scope, f.actor.id))
        val winnerThread = AtomicReference<Thread?>()
        val loserPid = AtomicInteger()
        val loserEntering = CountDownLatch(1)
        val changedAfterClaim = AtomicBoolean()
        f.raw { observer ->
            OwnedCallerTestScope().use { callers ->
                val completedWinner = callers.gate()
                f.jdbc.before = { path, sql ->
                    if (path === REGISTERED_CREATE && sql.startsWith("INSERT INTO complaint_idempotency_receipts") &&
                        winnerThread.get() != null && Thread.currentThread() !== winnerThread.get()) {
                        loserPid.set(currentPid(f)); loserEntering.countDown()
                    }
                }
                f.jdbc.after = { path, sql -> if (path === REGISTERED_CREATE) {
                    if ("UPDATE complaint_idempotency_receipts" in sql && winnerThread.compareAndSet(null, Thread.currentThread())) completedWinner.hold()
                    if (sql.startsWith("INSERT INTO complaint_idempotency_receipts") && winnerThread.get() != null &&
                        Thread.currentThread() !== winnerThread.get() && changedAfterClaim.compareAndSet(false, true)) {
                        // Winner committed and released its control locks before this actual DO NOTHING
                        // returned. New work is unavailable now; the loser still owes an exact receipt read.
                        if (desiredDrift) assertEquals(1, f.exchange.f.foreignUpdate(
                            "UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?", ByteArray(32) { 0x68 }, f.scope))
                        else assertEquals(1, f.exchange.f.foreignUpdate("UPDATE complaint_journal_control SET creation_closed = true, maintenance_closed = true WHERE data_scope_id = ?", f.scope))
                    }
                } }
                val winner = callers.launch { f.create(attempt) }
                completedWinner.awaitEntered()
                try {
                    val loser = callers.launch { f.create(attempt) }
                    assertTrue(loserEntering.await(1, TimeUnit.SECONDS))
                    awaitActualLockWait(observer, loserPid.get())
                    completedWinner.release()
                    f.assertApplied(winner.value(), attempt)
                    if (desiredDrift) {
                        val failure = loser.problem()
                        assertTrue(failure is ComplaintOwnerOperationRejected)
                        assertEquals(ComplaintOwnerOperationFailure.UNAVAILABLE, (failure as ComplaintOwnerOperationRejected).failure)
                    } else f.assertApplied(loser.value(), attempt)
                } finally { completedWinner.release() }
            }
        }
        f.jdbc.before = { _, _ -> }; f.jdbc.after = { _, _ -> }
        assertTrue(changedAfterClaim.get()); f.assertReleased()
        assertEquals(2, f.createPhases().size)
        assertEquals(if (desiredDrift) 1 else 2, f.createPhases().count { it.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED })
        assertEquals(if (desiredDrift) 1 else 0, f.createPhases().count { it.databaseOutcome() === PersistenceDatabaseOutcome.ROLLED_BACK })
        assertEquals(5, f.createSql().count { it == currentSql }, "Only winner checked new-work eligibility.")
        assertEquals(1, f.createSql().count { "FROM complaint_capacity_counters" in it && "FOR UPDATE" in it })
        f.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE)
        assertEquals(beforeReceipts + 1L, f.observer.queryForObject(
            "SELECT count(*) FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND actor_id = ?", Long::class.java, f.scope, f.actor.id))
        if (desiredDrift) refused { f.status(attempt) } else f.assertApplied(f.status(attempt), attempt)
        assertEquals(providers, f.providerCounts())
    }

    fun naturalFreshness(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val currentSql = f.currentCheckpointSql()
        val deadlines = f.process.consumers.journalConfiguration.declaration().limits.deadlines
        assertEquals(30_000, deadlines.scanMillis); assertEquals(30_000, deadlines.scanCadenceMillis); assertEquals(30_000, deadlines.checkpointMaxAgeMillis)
        val first = f.attempt(); f.assertApplied(f.create(first), first); f.assertReleased()
        val before = f.state(); val counters = f.counters(); val providers = f.providerCounts()
        val expires = (f.checkpoint.control().getValue("checkpoint_completed_at") as Timestamp).toInstant().plusMillis(deadlines.checkpointMaxAgeMillis.toLong())
        val second = f.attempt()
        val heldAfterActor = AtomicBoolean()
        f.raw { observer ->
            requireConnectionFree()
            waitUntil(observer, expires.minusMillis(800), 31_000) // Outside every CREATE original/admission.
            f.jdbc.calls.clear()
            f.jdbc.after = { path, sql -> if (path === REGISTERED_CREATE && credentialLock(sql) && heldAfterActor.compareAndSet(false, true)) {
                // The actual locked credential result and both earlier current checks already exist.
                // This intentional caller stall consumes the SAME 2s original, no clock/reset shortcut.
                assertEquals(2, f.createSql().count { it == currentSql })
                assertTrue(now(observer).isBefore(expires))
                waitUntil(observer, expires.plusNanos(1000), 900)
            } }
            try { refused { f.create(second) } } finally { f.jdbc.after = { _, _ -> } }
        }
        assertTrue(heldAfterActor.get()); f.assertReleased()
        assertEquals(3, f.createSql().count { it == currentSql })
        assertFalse(f.createSql().any { it.startsWith("INSERT INTO complaint_resource_ids") })
        assertEquals(before, f.state()); assertEquals(counters, f.counters())
        f.jdbc.calls.clear()
        f.assertApplied(f.create(first), first); f.assertApplied(f.status(first), first)
        assertTrue(f.createSql().isEmpty(), "Exact receipts precede expired freshness and already-spent admission members.")
        refused { f.create(second) }; f.assertNoCounterSql(); f.assertReleased()
        assertEquals(before, f.state()); assertEquals(providers, f.providerCounts())
    }

    fun credentialWait(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val currentSql = f.currentCheckpointSql()
        val attempt = f.attempt(); val before = f.state(); val counters = f.counters()
        val entering = CountDownLatch(1); val pid = AtomicInteger()
        f.raw { locker ->
            locker.autoCommit = false
            try {
                locker.prepareStatement("SELECT id FROM app_installations WHERE id = ? FOR UPDATE").use { statement ->
                    statement.queryTimeout = 1; statement.setObject(1, f.actor.id)
                    statement.executeQuery().use { row -> assertTrue(row.next()); assertFalse(row.next()) }
                }
                f.raw { observer ->
                    OwnedCallerTestScope().use { callers ->
                        f.jdbc.before = { path, sql -> if (path === REGISTERED_CREATE && credentialLock(sql)) {
                            pid.set(currentPid(f)); entering.countDown()
                        } }
                        val original = callers.launch { f.create(attempt) }
                        assertTrue(entering.await(1, TimeUnit.SECONDS))
                        awaitActualLockWait(observer, pid.get())
                        locker.prepareStatement("UPDATE app_installations SET credential_version = credential_version + 1 WHERE id = ?").use { statement ->
                            statement.queryTimeout = 1; statement.setObject(1, f.actor.id); assertEquals(1, statement.executeUpdate())
                        }
                        locker.commit()
                        val failure = original.problem()
                        assertTrue(failure is ComplaintOwnerOperationRejected)
                        assertEquals(ComplaintOwnerOperationFailure.UNAUTHORIZED, (failure as ComplaintOwnerOperationRejected).failure)
                    }
                }
            } finally { locker.rollback(); f.jdbc.before = { _, _ -> } }
        }
        f.assertReleased(); assertEquals(counters, f.counters())
        assertEquals(before.filterKeys { it != "app_installations" }, f.state().filterKeys { it != "app_installations" })
        assertEquals(2, f.createSql().count { it == currentSql })
        assertFalse(f.createSql().any { it.startsWith("INSERT INTO complaint_resource_ids") })
    }

    fun completionFailures(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val first = f.attempt(); val initial = f.state(); val counters = f.counters()
        val rollback = failedCompletion(f, first, CompletionCut.BEFORE)
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, rollback.databaseOutcome)
        assertEquals(initial, f.state())
        val tail = failedCompletion(f, first, CompletionCut.TAIL)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, tail.databaseOutcome)
        f.assertApplied(f.status(first), first); f.assertApplied(f.create(first), first)
        f.assertCharge(counters, ComplaintCapacityCharges.OWNER_CREATE)
        val second = f.attempt(); val beforeUnknown = f.state()
        val unknown = failedCompletion(f, second, CompletionCut.DEFERRED_UNIQUE)
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, unknown.databaseOutcome)
        assertEquals(beforeUnknown, f.state())
        refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { f.status(second) }
        f.assertApplied(f.create(second), second); f.assertReleased()
        f.assertCharge(counters, ComplaintCapacityCharges.OWNER_CREATE.scaled(2))
    }

    private fun failedCompletion(f: TestRegisteredInitialCheckpointCreateFixtureV1, attempt: RegisteredInitialCreateAttemptV1,
        cut: CompletionCut): PersistencePhaseException {
        val reached = AtomicBoolean()
        f.jdbc.after = { path, sql -> if (path === REGISTERED_CREATE && "UPDATE complaint_idempotency_receipts" in sql && reached.compareAndSet(false, true)) {
            if (cut === CompletionCut.DEFERRED_UNIQUE) {
                // Real driver COMMIT failure, not a caller-provided UNKNOWN. A temp table lives only
                // in this original transaction; the registered checkpoint/current checks stay intact.
                val connection = (TransactionSynchronizationManager.getResource(f.jdbc.dataSource!!) as ConnectionHolder).connection
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TEMP TABLE kira_registered_create_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED) ON COMMIT DROP")
                    assertEquals(2, statement.executeUpdate("INSERT INTO kira_registered_create_commit VALUES (1), (1)"))
                }
            } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) { if (cut === CompletionCut.BEFORE) throw SyntheticRegisteredCreateCutV1() }
                override fun afterCommit() { if (cut === CompletionCut.TAIL) throw SyntheticRegisteredCreateCutV1() }
            })
        } }
        try { refused { f.create(attempt) } } finally { f.jdbc.after = { _, _ -> } }
        assertTrue(reached.get()); f.assertReleased()
        val phase = f.createPhases().last()
        val original = poolTestField<ComplaintOwnerCreateOperation>(phase.ownerOperation, "retained")
        val failure = assertThrows<PersistencePhaseException> { original.result }
        assertTrue(failure.cleanupProven); assertNull(failure.cause); assertTrue(failure.suppressed.isEmpty())
        assertEquals(phase.databaseOutcome(), failure.databaseOutcome)
        assertThrows<PersistencePhaseException> { original.result } // Neither visible rows nor another read rehabilitate it.
        return failure
    }

    private fun currentPid(f: TestRegisteredInitialCheckpointCreateFixtureV1): Int =
        f.jdbc.observations.getValue(checkNotNull(PersistencePhaseOwnership.current())).identity.first
    private fun credentialLock(sql: String): Boolean = "FROM app_installations WHERE id = ? FOR UPDATE" in sql

    /** The real lock_timeout remains 100ms. No retry or long wait is reclassified as a successful race. */
    private fun awaitActualLockWait(observer: Connection, pid: Int) {
        val stop = System.nanoTime() + 80_000_000L
        observer.prepareStatement("SELECT coalesce(wait_event_type = 'Lock', false) FROM pg_stat_activity WHERE pid = ?").use { statement ->
            statement.queryTimeout = 1; statement.setInt(1, pid)
            while (true) {
                val waiting = statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                if (waiting) return
                check(System.nanoTime() - stop < 0) { "Actual original did not reach the bounded PostgreSQL row wait." }
                LockSupport.parkNanos(500_000)
            }
        }
    }
    private fun now(observer: Connection): Instant = observer.createStatement().use { statement ->
        statement.queryTimeout = 1
        statement.executeQuery("SELECT clock_timestamp()").use { row -> assertTrue(row.next()); row.getTimestamp(1).toInstant() }
    }
    private fun waitUntil(observer: Connection, time: Instant, maximumMillis: Long) {
        val stop = System.nanoTime() + maximumMillis * 1_000_000L
        while (now(observer).isBefore(time)) {
            check(System.nanoTime() - stop < 0) { "Actual database time did not reach the explicit test boundary." }
            LockSupport.parkNanos(2_000_000)
        }
    }
    private enum class CompletionCut { BEFORE, TAIL, DEFERRED_UNIQUE }
    private class SyntheticRegisteredCreateCutV1 : RuntimeException("Synthetic registered CREATE completion cut.")
}
