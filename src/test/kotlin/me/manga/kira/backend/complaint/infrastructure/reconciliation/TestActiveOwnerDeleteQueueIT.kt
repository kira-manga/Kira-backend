package me.manga.kira.backend.complaint.infrastructure.reconciliation

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.DeleteAllCounter
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import org.junit.jupiter.api.AfterAll
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
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * SOURCE ONLY / NOT_COMPILED / NOT_RUN / NOT_RUNTIME_ACCEPTED. Real TLS/PG lifecycle and real
 * SQS/S3/KMS SDK/crypto with synthetic public HTTP SPI. Not AWS, deployment, two-process or dropped
 * wire-COMMIT evidence. This queue accelerates at-least-once recovery; scans establish completeness.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestActiveOwnerDeleteQueueIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestActiveOwnerDeleteQueueIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun nativeExactOwnerDeleteReconstructsMissingBookkeepingAndAcksOnlyAfterCommittedReleasedApply() = withQueue { f ->
        val before = f.counters()
        assertTrue(listOf("complaint_idempotency_receipts", "complaint_journal_publications", "complaint_recovery_capacity_reservations",
            "complaint_deletion_journal_applied").all { f.count(it) == 0L })
        val original = f.begin()
        val completed = f.poll(original)
        assertEquals(f.scope, completed.scope); assertEquals(1, completed.primaryAcknowledged); assertEquals(0, completed.dlqAcknowledged)
        f.assertReleased(); f.assertNoAuthority()
        assertApplied(f); assertRecoveryCharge(before, f.counters())
        assertEquals(listOf("synthetic-primary-receipt-1"), f.raw.ackRequests)
        assertEquals(listOf("STS", "GetQueueUrl:PRIMARY", "GetQueueAttributes:PRIMARY", "ReceiveMessage:PRIMARY", "GET", "DECRYPT",
            "DeleteMessage:PRIMARY", "GetQueueUrl:DLQ", "GetQueueAttributes:DLQ", "ReceiveMessage:DLQ"), f.raw.order)
        assertEquals("SETTLED", f.observation()?.get("state")); assertEquals(1, (f.observation()?.get("primary_acked") as Number).toInt())
        assertNull(f.control()["lease_owner"]); assertNull(f.control()["lease_expires_at"])
        assertTrue(f.raw.budgets.isNotEmpty()); assertTrue(f.raw.budgets.all { it.second in 1..10_000 })
        f.coordinator.observations.forEach { (phase, value) ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome()); assertTrue(value.lease.completion.quiescent())
        }
        f.deletion.observations.forEach { (phase, value) ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome()); assertTrue(value.lease.completion.quiescent())
        }
        // Actual populated logical envelope only, not a maximum physical disk/WAL qualification.
        val envelope = f.observer.queryForMap("SELECT pg_column_size(o) AS heap, pg_column_size(data_scope_id) AS key " +
            "FROM complaint_test_active_queue_observations o WHERE data_scope_id = ?", f.scope)
        assertTrue((envelope["heap"] as Number).toLong() <= 768); assertTrue((envelope["key"] as Number).toLong() <= 256)
        f.assertSameOriginalRefused(original)
    }

    @Test fun aFreshAtLeastOnceRedeliveryReauthenticatesEverythingWithoutRechargingOrSecondAudit() = withQueue { f ->
        f.poll(); f.assertReleased()
        val paid = f.counters(); val oldToken = f.control()["lease_token"] as Long
        val original = f.begin(); val result = f.poll(original)
        assertEquals(1, result.primaryAcknowledged); assertEquals(0, result.dlqAcknowledged)
        f.assertReleased(); f.assertNoAuthority(); assertApplied(f)
        assertEquals(paid, f.counters()); assertEquals(oldToken + 1, f.control()["lease_token"])
        assertEquals(1L, f.count("complaint_test_active_queue_observations"))
        assertEquals(2, f.raw.order.count { it == "STS" }); assertEquals(2, f.raw.order.count { it == "GET" }); assertEquals(2, f.raw.order.count { it == "DECRYPT" })
        assertEquals(listOf("synthetic-primary-receipt-1", "synthetic-primary-receipt-2"), f.raw.ackRequests)
        assertEquals(0, f.calls.count { it.sql == OwnerDeletePersistenceSql.INSERT_APPLIED })
        f.assertSameOriginalRefused(original)
    }

    @Test fun sameKeyDifferentOpaqueVersionIsNotBorrowedTerminalAliasAuthority() = withQueue { f ->
        f.poll(); f.assertReleased(); val paid = f.counters()
        f.raw.differentOpaqueVersion()
        val original = f.begin()
        assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { f.poll(original) }
        f.assertReleased(); assertApplied(f); assertEquals(paid, f.counters())
        assertEquals(0, original.primaryAcked); assertEquals(1, f.raw.ackRequests.size)
        assertEquals("POLLING", f.observation()?.get("state")); f.assertNoAuthority(); f.assertSameOriginalRefused(original)
    }

    @Test fun aSupportedDlqDeliveryWarnsWithValueFreeTextButCreatesNoDlqOrQueueHealthAuthority() = withQueue { f ->
        f.raw.primaryBody = null; f.raw.dlqBody = f.raw.notification()
        val logger = LoggerFactory.getLogger(TestActiveOwnerDeleteQueueV1::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        try {
            val result = f.poll()
            assertEquals(0, result.primaryAcknowledged); assertEquals(1, result.dlqAcknowledged)
            f.assertReleased(); assertApplied(f); f.assertNoAuthority()
            assertEquals(listOf("synthetic-dlq-receipt-1"), f.raw.ackRequests)
            assertEquals(listOf("ACTIVE TEST OWNER_DELETE DLQ delivery observed; bounded recovery attempted, not queue-health evidence."),
                appender.list.map { it.formattedMessage })
        } finally { logger.detachAppender(appender); appender.stop() }
    }

    @Test fun twoEmptyOneSecondLongPollRequestsSettleOnlyTheOneOrdinaryObservation() = withQueue { f ->
        f.raw.primaryBody = null; f.raw.dlqBody = null
        val before = f.counters(); val result = f.poll()
        assertEquals(0, result.primaryAcknowledged); assertEquals(0, result.dlqAcknowledged)
        f.assertReleased(); f.assertNoAuthority(); assertObservationCharge(before, f.counters())
        assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.raw.requests.isEmpty()); assertTrue(f.raw.kms.requests.isEmpty())
        assertTrue(f.deletion.observations.isEmpty()); assertEquals(0L, f.count("complaint_deletion_journal_applied"))
        assertEquals(2, f.raw.order.count { it.startsWith("ReceiveMessage:") })
        assertEquals("SETTLED", f.observation()?.get("state"))
    }

    @Test fun malformedUnknownAndMultiRecordNotificationsRemainUnackedWithoutJournalDispatch() {
        listOf("malformed", "unknown", "two").forEach { kind -> withQueue { f ->
            f.raw.primaryBody = when (kind) {
                "malformed" -> "{"
                "unknown" -> f.raw.notification().replace("ObjectCreated:Put", "ObjectRemoved:Delete")
                else -> {
                    val record = com.fasterxml.jackson.databind.ObjectMapper().readTree(f.raw.notification()).get("Records").single().toString()
                    "{\"Records\":[$record,$record]}"
                }
            }
            val before = f.counters(); val original = f.begin()
            assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { f.poll(original) }
            f.assertReleased(); f.assertNoAuthority(); assertObservationCharge(before, f.counters())
            assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.raw.requests.isEmpty()); assertTrue(f.raw.kms.requests.isEmpty())
            assertTrue(f.deletion.observations.isEmpty()); assertEquals("POLLING", f.observation()?.get("state"))
            f.assertSameOriginalRefused(original)
        } }
    }

    @Test fun recoveryRoleIsAuthenticatedBeforeAnyQueueOrJournalRequest() = withQueue { f ->
        f.raw.wrongPrincipal = true
        val original = f.begin()
        assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { f.poll(original) }
        f.assertReleased(); f.assertNoAuthority(); assertEquals(listOf("STS"), f.raw.order)
        assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.deletion.observations.isEmpty()); f.assertSameOriginalRefused(original)
    }

    @Test fun authenticatedUnsupportedFamilyBadMetadataAndBadAeadTagCannotBecomeApplyOrAck() {
        listOf("family", "metadata", "tag").forEach { kind -> withPoisonedQueue { f ->
            if (kind == "family") f.raw.externalObject(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)
            f.raw.changeS3 = { _, reply ->
                if (kind == "metadata") reply.headers = reply.headers.filterKeys { !it.equals("x-amz-meta-kira-journal-schema", true) } +
                    ("x-amz-meta-kira-journal-schema" to listOf("2"))
                if (kind == "tag") {
                    reply.bytes[reply.bytes.lastIndex] = (reply.bytes.last().toInt() xor 1).toByte()
                    val digest = MessageDigest.getInstance("SHA-256").digest(reply.bytes)
                    val names = setOf("x-amz-checksum-sha256", "x-amz-meta-kira-journal-ciphertext-sha256")
                    reply.headers = reply.headers.filterKeys { it.lowercase() !in names } + mapOf(
                        "x-amz-checksum-sha256" to listOf(Base64.getEncoder().encodeToString(digest)),
                        "x-amz-meta-kira-journal-ciphertext-sha256" to listOf(Sha256.hex(reply.bytes)))
                }
            }
            val original = f.begin()
            assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { f.poll(original) }
            f.assertReleased(); f.assertNoAuthority()
            assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.deletion.observations.isEmpty())
            assertEquals(0L, f.count("complaint_deletion_journal_applied"))
            assertEquals(if (kind == "metadata") 0 else 1, f.raw.order.count { it == "DECRYPT" })
            f.assertSameOriginalRefused(original)
        } }
    }

    @Test fun fullDesiredDatabaseRestoreCatalogAndExactLeaseDriftDuringReceiveRefuseBeforeApply() {
        QueueBindingCut.entries.forEach { cut -> withQueue { f ->
            var reached = false
            f.raw.changeSqs = { request, reply -> if (!reached && request.target() == "AmazonSQS.ReceiveMessage") {
                reply.beforeCall = { reached = true; changeBinding(f, cut) }
            } }
            val original = f.begin()
            assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { f.poll(original) }
            assertTrue(reached); f.assertReleased(); f.assertNoAuthority()
            assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.raw.requests.isEmpty()); assertTrue(f.deletion.observations.isEmpty())
            assertEquals(0, original.primaryAcked); assertEquals("POLLING", f.observation()?.get("state")); f.assertSameOriginalRefused(original)
        } }
    }

    @Test fun bindingIsRecheckedUnderApplyAndAgainAfterActualApplyReleaseBeforeAck() {
        listOf(false, true).forEach { afterApply -> withQueue { f ->
            var changed = false
            f.before = { call ->
                val priorApply = f.calls.any { it.step === TestActiveOwnerDeleteQueueStepV1.APPLY }
                val selected = if (afterApply) call.step === TestActiveOwnerDeleteQueueStepV1.RECHECK && priorApply
                    else call.step === TestActiveOwnerDeleteQueueStepV1.APPLY
                if (!changed && selected) {
                    changed = true
                    assertEquals(1, f.initial.foreignUpdate("UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?",
                        ByteArray(32) { 7 }, f.scope))
                }
            }
            val original = f.begin()
            assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { f.poll(original) }
            assertTrue(changed); f.assertReleased(); f.assertNoAuthority()
            assertEquals(if (afterApply) 1L else 0L, f.count("complaint_deletion_journal_applied"))
            assertTrue(f.raw.ackRequests.isEmpty()); assertEquals(0, original.primaryAcked); f.assertSameOriginalRefused(original)
        } }
    }

    @Test fun cancelledHeldNativeReceiveKeepsOriginalCustodyUntilLateReturnAndNeverAcks() = withPoisonedQueue { f ->
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate(); val aborted = CountDownLatch(1)
            val nativeReturning = AtomicBoolean(); val ownerReturned = AtomicBoolean()
            val original = AtomicReference<TestActiveOwnerDeleteQueueV1?>()
            f.raw.changeSqs = { request, reply -> if (request.target() == "AmazonSQS.ReceiveMessage") {
                reply.beforeCall = { gate.hold(); nativeReturning.set(true) }
                reply.onAbort = { aborted.countDown() }
            } }
            val worker = callers.launch {
                val owner = f.begin(); original.set(owner)
                assertThrows<CancellationException> { f.poll(owner) }; ownerReturned.set(true)
                f.assertReleased(); f.assertNoAuthority()
                assertThrows<CancellationException> { f.poll(owner) }
                Unit
            }
            gate.awaitEntered()
            val owner = checkNotNull(original.get())
            owner.cancel()
            assertTrue(aborted.await(1, TimeUnit.SECONDS))
            assertFalse(nativeReturning.get()); assertFalse(ownerReturned.get()); assertTrue(worker.thread.isAlive)
            assertSame(owner, (ownedCutField(owner.recipe, "active") as AtomicReference<*>).get())
            assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { owner.recipe.close() }
            assertFalse(ownerReturned.get(), "Abort and close request are not native return/reclamation.")
            assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.deletion.observations.isEmpty())
            gate.release(); worker.value()
            assertTrue(nativeReturning.get() && ownerReturned.get()); assertEquals(0, owner.primaryAcked)
            assertSame(owner, (ownedCutField(owner.recipe, "active") as AtomicReference<*>).get(), "Late return cannot rehabilitate this poisoned delivery.")
        }
    }

    @Test fun unknownApplyCommitAndUnprovenReleaseNeverAuthorizeAckEvenWhenRowsAreVisible() {
        listOf(QueueCommitCut.UNKNOWN, QueueCommitCut.UNRESOLVED_RELEASE).forEach { cut -> withPoisonedQueue { f -> commitCut(f, cut, settlement = false) } }
    }

    @Test fun nativeCancellationAndInterruptedIoSurviveSdkWrappingWithoutReleasingTheOriginal() {
        listOf(false, true).forEach { interrupted -> withPoisonedQueue { f ->
            f.raw.changeSqs = { request, reply -> if (request.target() == "AmazonSQS.ReceiveMessage") {
                reply.bodyPresent = false // The native call throws before supplying any response/body.
                reply.beforeCall = {
                    if (interrupted) throw InterruptedIOException("Synthetic native interruption")
                    else throw CancellationException("Synthetic native cancellation")
                }
            } }
            val original = f.begin()
            try {
                if (interrupted) { assertThrows<InterruptedException> { f.poll(original) }; assertTrue(Thread.currentThread().isInterrupted) }
                else assertThrows<CancellationException> { f.poll(original) }
            } finally { Thread.interrupted() } // Caller cleanup only; never a renewed original.
            f.assertReleased(); f.assertNoAuthority(); assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.deletion.observations.isEmpty())
            assertSame(original, (ownedCutField(original.recipe, "active") as AtomicReference<*>).get())
            val requests = f.raw.order.toList()
            try {
                if (interrupted) assertThrows<InterruptedException> { f.poll(original) }
                else assertThrows<CancellationException> { f.poll(original) }
            } finally { Thread.interrupted() }
            assertEquals(requests, f.raw.order); assertEquals(0, original.primaryAcked)
        } }
    }

    @Test fun beforeOrAfterApplyCommitFailureKeepsTheDeliveryUnackedWithoutInventingAnOutcome() {
        listOf(QueueCommitCut.BEFORE, QueueCommitCut.AFTER).forEach { cut -> withQueue { f -> commitCut(f, cut, settlement = false) } }
    }

    @Test fun unknownDeleteMessageResponseKeepsAckCountZeroDespiteAlreadyCommittedApply() = withPoisonedQueue { f ->
        f.raw.changeSqs = { request, reply -> if (request.target() == "AmazonSQS.DeleteMessage") reply.status = 500 }
        val original = f.begin()
        assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { f.poll(original) }
        f.assertReleased(); f.assertNoAuthority(); assertApplied(f)
        assertEquals(1, f.raw.ackRequests.size, "Dispatch may have deleted the message; no successful ack observation is forged.")
        assertEquals(0, original.primaryAcked); assertEquals("POLLING", f.observation()?.get("state"))
        assertEquals(0, (f.observation()?.get("primary_acked") as Number).toInt())
        f.assertSameOriginalRefused(original)
    }

    @Test fun priorSafeApplyAndAckRemainTruthfulWhenFinalSettlementCommitIsUnknown() = withPoisonedQueue { f ->
        commitCut(f, QueueCommitCut.UNKNOWN, settlement = true)
    }

    @Test fun nativeCloseFailureRetainsLaterErrorOverEarlierInvalidReadbackAndCannotBeCalledReleased() = withPoisonedQueue { f ->
        val marker = object : Error("Synthetic native cleanup Error; no provider details") {}
        var closeAttempted = false
        f.raw.changeS3 = { _, reply -> reply.headers = reply.headers.filterKeys { !it.equals("x-amz-meta-kira-journal-schema", true) } +
            ("x-amz-meta-kira-journal-schema" to listOf("2")) }
        f.raw.onNativeClose = { kind -> if (kind == "KMS") { closeAttempted = true; throw marker } }
        val original = f.begin()
        val fatal = assertThrows<Error> { f.poll(original) }
        assertTrue(closeAttempted); assertEquals("Complaint journal publication failed fatally.", fatal.message)
        assertNull(fatal.cause); assertTrue(fatal.suppressed.isEmpty())
        f.assertSqlReleased(); f.raw.assertDisposed(returned = false); f.assertNoAuthority()
        assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.deletion.observations.isEmpty())
        assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { original.requireNativeReleased(original.recipe) }
        assertSame(fatal, assertThrows<Error> { f.poll(original) })
    }

    private fun commitCut(f: TestActiveOwnerDeleteQueueFixtureV1, cut: QueueCommitCut, settlement: Boolean) {
        var phase: PersistencePhaseContext? = null
        val resource = Any(); val sentinel = Any(); var bound = false
        f.after = { call -> if (phase == null && call.sql == (if (settlement) TestActiveOwnerDeleteQueueSqlV1.settle else OwnerDeletePersistenceSql.INSERT_APPLIED)) {
            phase = call.phase
            if (cut === QueueCommitCut.UNKNOWN) {
                val jdbc = JdbcTemplate(if (settlement) f.runtime.pools.catalogCoordinator.dataSource else f.runtime.pools.deletion)
                jdbc.execute("CREATE TEMP TABLE kira_active_queue_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, jdbc.update("INSERT INTO kira_active_queue_commit_cut VALUES (1), (1)"))
            } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) { if (cut === QueueCommitCut.BEFORE) error("Synthetic before-COMMIT refusal") }
                override fun afterCommit() {
                    if (cut === QueueCommitCut.UNRESOLVED_RELEASE) { TransactionSynchronizationManager.bindResource(resource, sentinel); bound = true }
                    if (cut !== QueueCommitCut.BEFORE) error("Synthetic after-COMMIT return failure; not dropped wire evidence")
                }
            })
        } }
        val before = f.counters(); val original = f.begin()
        try {
            assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { f.poll(original) }
            if (cut === QueueCommitCut.UNRESOLVED_RELEASE) {
                assertTrue(bound); assertSame(phase, PersistencePhaseOwnership.current()); assertTrue(checkNotNull(phase).quarantined())
            }
        } finally {
            f.after = {}
            if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resource))
            requireConnectionFree() // TEST quarantine cleanup is not a successful original release receipt.
        }
        val outcome = when (cut) {
            QueueCommitCut.BEFORE -> PersistenceDatabaseOutcome.ROLLED_BACK
            QueueCommitCut.UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
            else -> PersistenceDatabaseOutcome.COMMITTED
        }
        assertEquals(outcome, checkNotNull(phase).databaseOutcome())
        f.assertReleased(); f.assertNoAuthority()
        if (settlement) {
            assertApplied(f); assertRecoveryCharge(before, f.counters())
            assertEquals(1, f.raw.ackRequests.size); assertEquals(1, original.primaryAcked)
            assertEquals("POLLING", f.observation()?.get("state")); assertEquals(0, (f.observation()?.get("primary_acked") as Number).toInt())
            assertEquals(original.attemptId, f.control()["lease_owner"])
        } else {
            assertTrue(f.raw.ackRequests.isEmpty()); assertEquals(0, original.primaryAcked)
            if (outcome === PersistenceDatabaseOutcome.COMMITTED) { assertApplied(f); assertRecoveryCharge(before, f.counters()) }
            else { assertEquals(0L, f.count("complaint_deletion_journal_applied")); assertObservationCharge(before, f.counters()) }
        }
        f.assertSameOriginalRefused(original)
    }

    private fun changeBinding(f: TestActiveOwnerDeleteQueueFixtureV1, cut: QueueBindingCut) {
        val updated = when (cut) {
            QueueBindingCut.DESIRED -> f.observer.update("UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?", ByteArray(32) { 7 }, f.scope)
            QueueBindingCut.DATABASE -> f.observer.update("UPDATE complaint_journal_control SET database_identity = ? WHERE data_scope_id = ?", UUID.randomUUID(), f.scope)
            QueueBindingCut.RESTORE -> f.observer.update("UPDATE complaint_journal_control SET restore_identity = ? WHERE data_scope_id = ?", UUID.randomUUID(), f.scope)
            QueueBindingCut.CATALOG -> f.observer.update("UPDATE complaint_journal_control SET accepted_catalog_hash = ? WHERE data_scope_id = ?", ByteArray(32) { 8 }, UUID(0L, 0L))
            QueueBindingCut.EXPIRED -> f.observer.update("UPDATE complaint_journal_control SET lease_expires_at = clock_timestamp() - interval '1 millisecond' WHERE data_scope_id = ?", f.scope)
            QueueBindingCut.REPLACED -> f.observer.update("UPDATE complaint_journal_control SET lease_owner = ?, lease_token = lease_token + 1 WHERE data_scope_id = ?", UUID.randomUUID(), f.scope)
        }
        assertEquals(1, updated)
    }

    private fun assertApplied(f: TestActiveOwnerDeleteQueueFixtureV1) {
        listOf("complaint_idempotency_receipts", "complaint_journal_publications", "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied",
            "complaint_installation_ids", "complaint_resource_ids").forEach { assertEquals(1L, f.count(it), it) }
        assertEquals(0L, f.count("app_installations"), "Recovery reserves identity, never synthesizes credentials.")
        assertEquals(0L, f.count("complaints")); assertEquals(mapOf("COMPLAINT_RECOVERY_APPLIED" to 1L), f.audits())
        assertEquals("RECOVERY_RESERVED", f.observer.queryForObject("SELECT state FROM complaint_installation_ids WHERE id = ?", String::class.java, f.raw.event.tuple.actorId))
        assertEquals("DELETED", f.observer.queryForObject("SELECT state FROM complaint_resource_ids WHERE id = ?", String::class.java, f.raw.event.complaintIds().single()))
        assertEquals("APPLIED", f.observer.queryForObject("SELECT state FROM complaint_journal_publications WHERE event_id = ?", String::class.java, f.raw.event.route.eventId))
        assertEquals("COMPLETED", f.observer.queryForObject("SELECT state FROM complaint_idempotency_receipts WHERE data_scope_id = ?", String::class.java, f.scope))
    }
    private fun assertObservationCharge(before: Map<ComplaintCapacityCounter, DeleteAllCounter>, after: Map<ComplaintCapacityCounter, DeleteAllCounter>) {
        assertEquals(22, after.size)
        before.forEach { (counter, old) ->
            val charge = if (counter === ComplaintCapacityCounter.STORAGE_BYTES) 8192L else 0L
            assertEquals(old.copy(free = old.free - charge, actual = old.actual + charge), after.getValue(counter), counter.storedName)
        }
    }
    private fun assertRecoveryCharge(before: Map<ComplaintCapacityCounter, DeleteAllCounter>, after: Map<ComplaintCapacityCounter, DeleteAllCounter>) {
        assertEquals(22, after.size)
        before.forEach { (counter, old) ->
            val observation = if (counter === ComplaintCapacityCounter.STORAGE_BYTES) 8192L else 0L
            val bookkeeping = OwnerDeleteLiteralCharges.missingBookkeeping[counter]
            val promise = OwnerDeleteLiteralCharges.promise[counter]
            val materialized = OwnerDeleteLiteralCharges.reconstructAbsent[counter]
            assertEquals(old.copy(free = old.free - bookkeeping - promise - observation, actual = old.actual + bookkeeping + materialized + observation,
                recovery = old.recovery + promise - materialized), after.getValue(counter), counter.storedName)
        }
    }

    /** Sticky unknown/failed native custody is expected to make owning assembly shutdown refuse too.
     * A body-complete marker prevents that expected teardown error from hiding setup/test failures.
     */
    private fun withPoisonedQueue(action: (TestActiveOwnerDeleteQueueFixtureV1) -> Unit) {
        var assertionsCompleted = false
        assertThrows<RuntimeException> { withQueue { f -> action(f); assertionsCompleted = true } }
        assertTrue(assertionsCompleted, "Only the already-asserted poisoned recipe's assembly close may supply the expected outer failure.")
    }
    private fun withQueue(action: (TestActiveOwnerDeleteQueueFixtureV1) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); withActiveQueueFixture(it, action) }
}

private enum class QueueBindingCut { DESIRED, DATABASE, RESTORE, CATALOG, EXPIRED, REPLACED }
private enum class QueueCommitCut { BEFORE, AFTER, UNKNOWN, UNRESOLVED_RELEASE }
