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
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.AdminDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplySql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllPersistenceSql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllVerificationSql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationExceptionV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import java.sql.Timestamp
import java.time.Duration
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

    @Test fun genuineRegisteredFamiliesApplyTheOriginalNativePutAndAckOnlyAfterCommittedReleasedApply() = forEachFamily { f ->
        val before = f.counters()
        assertAuthorizationCharge(f)
        assertEquals("AUTHORIZED_DELETE", f.receipt()["state"])
        assertEquals("VERIFIED", f.precursor.publication()["state"])
        assertEquals(0L, f.count("complaint_deletion_journal_applied"))
        assertSame(f.record.stored, f.raw.stored)
        assertSame(f.record.event, f.raw.event)
        val original = f.begin()
        val completed = f.poll(original)
        assertEquals(f.scope, completed.scope); assertEquals(1, completed.primaryAcknowledged); assertEquals(0, completed.dlqAcknowledged)
        f.assertReleased(); f.assertNoAuthority()
        assertApplied(f); assertRecoveryCharge(f, before, f.counters())
        assertEquals(listOf("synthetic-primary-receipt-1"), f.raw.ackRequests)
        assertEquals(listOf("STS", "GetQueueUrl:PRIMARY", "GetQueueAttributes:PRIMARY", "ReceiveMessage:PRIMARY", "GET", "DECRYPT",
            "DeleteMessage:PRIMARY", "GetQueueUrl:DLQ", "GetQueueAttributes:DLQ", "ReceiveMessage:DLQ"), f.raw.order)
        assertEquals("SETTLED", f.observation()?.get("state")); assertEquals(1, (f.observation()?.get("primary_acked") as Number).toInt())
        assertNull(f.control()["lease_owner"]); assertNull(f.control()["lease_expires_at"])
        assertTrue(f.raw.budgets.isNotEmpty()); assertTrue(f.raw.budgets.all { it.second in 1..10_000 })
        f.coordinator.observations.forEach { (phase, value) ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome()); assertTrue(value.lease.completion.quiescent())
        }
        f.deletionObservations.forEach { (phase, value) ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome()); assertTrue(value.lease.completion.quiescent())
        }
        // Actual populated logical envelope only, not a maximum physical disk/WAL qualification.
        val envelope = f.observer.queryForMap("SELECT pg_column_size(o) AS heap, pg_column_size(data_scope_id) AS key " +
            "FROM complaint_test_active_queue_observations o WHERE data_scope_id = ?", f.scope)
        assertTrue((envelope["heap"] as Number).toLong() <= 768); assertTrue((envelope["key"] as Number).toLong() <= 256)
        f.assertSameOriginalRefused(original)
    }

    @Test fun aFreshAtLeastOnceRedeliveryReauthenticatesEverythingWithoutRechargingOrSecondAudit() = forEachFamily { f ->
        f.poll(); f.assertReleased()
        val paid = f.counters(); val oldToken = f.control()["lease_token"] as Long; val rows = f.domainImage()
        val original = f.begin(); val result = f.poll(original)
        assertEquals(1, result.primaryAcknowledged); assertEquals(0, result.dlqAcknowledged)
        f.assertReleased(); f.assertNoAuthority(); assertApplied(f)
        assertEquals(paid, f.counters()); assertEquals(rows, f.domainImage()); assertEquals(oldToken + 1, f.control()["lease_token"])
        assertEquals(1L, f.count("complaint_test_active_queue_observations"))
        assertEquals(2, f.raw.order.count { it == "STS" }); assertEquals(2, f.raw.order.count { it == "GET" }); assertEquals(2, f.raw.order.count { it == "DECRYPT" })
        assertEquals(listOf("synthetic-primary-receipt-1", "synthetic-primary-receipt-2"), f.raw.ackRequests)
        assertEquals(0, f.calls.count { it.sql == appliedSql(f) })
        f.assertSameOriginalRefused(original)
    }

    @Test fun sameKeyDifferentOpaqueVersionIsNotBorrowedTerminalAliasAuthority() = forEachFamily { f ->
        f.poll(); f.assertReleased(); val paid = f.counters()
        f.raw.differentOpaqueVersion()
        val original = f.begin()
        assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { f.poll(original) }
        f.assertReleased(); assertApplied(f); assertEquals(paid, f.counters())
        assertEquals(0, original.primaryAcked); assertEquals(1, f.raw.ackRequests.size)
        assertEquals("POLLING", f.observation()?.get("state")); f.assertNoAuthority(); f.assertSameOriginalRefused(original)
    }

    @Test fun creationClosureDoesNotBlockGenuinePrivacyApplyOrSpendAnyCreationReserve() = forEachFamily { f ->
        withPrivacyInput(f, QueuePrivacyInput.CLOSED) {
            val before = f.counters()
            assertEquals(1, f.poll().primaryAcknowledged)
            f.assertReleased(); f.assertNoAuthority(); assertApplied(f); assertRecoveryCharge(f, before, f.counters())
        }
    }

    @Test fun creationCeilingDoesNotBlockGenuinePrivacyApplyWithUnpromisedHardHeadroom() = ComplaintJournalDeletionKindV1.entries.forEach { family ->
        var fixture: TestActiveOwnerDeleteQueueFixtureV1? = null
        var bodyReturned = false
        try {
            withQueue(family) { f ->
                fixture = f
                withPrivacyInput(f, QueuePrivacyInput.CEILING) {
                    val before = f.counters()
                    assertEquals(1, f.poll().primaryAcknowledged)
                    f.assertReleased(); f.assertNoAuthority(); assertApplied(f); assertRecoveryCharge(f, before, f.counters())
                }
                bodyReturned = true
            }
        } catch (failure: Throwable) {
            // Last reached boundary only; never replace the original failure or inspect data/provider values.
            runCatching {
                val stage = if (fixture == null) "SETUP" else if (bodyReturned) "TEARDOWN" else "BODY"
                val phaseFailure = failure as? PersistencePhaseException
                System.err.println("TEST_ACTIVE_QUEUE_CEILING_FAILURE family=${family.name} stage=$stage " +
                    "body_entered=${fixture != null} body_returned=$bodyReturned class=${failure.javaClass.name} " +
                    "code=${phaseFailure?.code?.name ?: "NONE"} database_outcome=${phaseFailure?.databaseOutcome?.name ?: "NONE"} " +
                    "cleanup_proven=${phaseFailure?.cleanupProven?.toString() ?: "UNOBSERVED"} " +
                    "original_step=${fixture?.original?.step?.name ?: "NONE"} last_probe_step=${fixture?.calls?.lastOrNull()?.step?.name ?: "NONE"}")
            }
            throw failure
        }
    }

    @Test fun oneByteShortOfTheObservationHardCapRefusesBeforeNativeApplyOrAck() = forEachFamily { f ->
        withPrivacyInput(f, QueuePrivacyInput.HARD_CAP) {
            val before = f.counters(); val rows = f.domainImage(); val original = f.begin()
            assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { f.poll(original) }
            f.assertReleased(); f.assertNoAuthority()
            assertEquals(before, f.counters()); assertEquals(rows, f.domainImage()); assertNull(f.observation())
            assertTrue(f.raw.order.isEmpty() && f.raw.ackRequests.isEmpty() && f.deletionObservations.isEmpty())
            assertEquals(0, original.primaryAcked); f.assertSameOriginalRefused(original)
        }
    }

    @Test fun adminRecoveryUsesTheOriginalConsumedGrantNotLaterRoleCredentialOrFreshStepUpAuthority() {
        listOf(ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE).forEach { family -> withQueue(family) { f ->
            val user = f.precursor.exchange.ordinary.userId
            val before = f.observer.queryForMap("SELECT role, enabled, credential_version FROM users WHERE id = ?", user)
            val grant = checkNotNull(f.precursor.proof).grantId
            assertEquals(grant, f.record.event.adminComparison.consumedGrantId)
            assertEquals(grant, f.receipt()["consumed_grant_id"])
            assertNotNull(f.observer.queryForObject("SELECT used_at FROM admin_step_up_grants WHERE id = ?", java.sql.Timestamp::class.java, grant))
            val nativeBefore = f.precursor.native.counts()
            assertEquals(1, f.observer.update("UPDATE users SET role = 'USER', enabled = false, credential_version = credential_version + 1 WHERE id = ?", user))
            try {
                assertEquals(1, f.poll().primaryAcknowledged); f.assertReleased(); assertApplied(f)
                val rows = f.domainImage(); val paid = f.counters()
                assertEquals(1, f.poll().primaryAcknowledged); f.assertReleased(); f.assertNoAuthority(); assertApplied(f)
                assertEquals(rows, f.domainImage()); assertEquals(paid, f.counters())
                assertEquals(f.grantBeforeQueue, f.grantImage()); assertEquals(nativeBefore, f.precursor.native.counts())
                assertFalse(f.calls.any { "admin_step_up_grants" in it.sql }, "Recovery is not a step-up/authentication issuer.")
            } finally {
                // Dispose only the adversarial auth input after outcome assertions, not a product transition.
                assertEquals(1, f.observer.update("UPDATE users SET role = ?, enabled = ?, credential_version = ? WHERE id = ?",
                    before["role"], before["enabled"], before["credential_version"], user))
            }
        } }
    }

    @Test fun allPreparedNativePutIsVerifiedOnlyFromTheCurrentReleasedQueueReadback() =
        withQueue(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, verifyPublication = false) { f ->
            val before = f.counters(); val native = f.precursor.native.counts()
            assertEquals("PREPARED", f.precursor.publication()["state"])
            assertNull(f.precursor.publication()["verification_bytes"])
            assertEquals(1, f.poll().primaryAcknowledged)
            f.assertReleased(); f.assertNoAuthority(); assertApplied(f); assertAllRecoveryState(f, materialized(f))
            assertRecoveryCharge(f, before, f.counters())
            assertEquals(native, f.precursor.native.counts(), "Queue uses the original PUT/key mapping, never another producer call.")
            val calls = f.calls.filter { it.step === TestActiveOwnerDeleteQueueStepV1.APPLY }.map { it.sql }
            val counters = calls.indexOfFirst { "FROM complaint_capacity_counters" in it && "FOR UPDATE" in it }
            val verified = calls.indexOfFirst { it == OwnerDeleteAllVerificationSql.test(f.precursor.dataScope).RECORD_VERIFIED }
            val domain = calls.indexOfFirst { "FROM complaint_installation_ids" in it && "FOR UPDATE" in it }
            assertTrue(counters >= 0 && verified > counters && domain > verified)
            assertEquals(1, f.raw.order.count { it == "GET" }); assertEquals(1, f.raw.order.count { it == "DECRYPT" })
            assertAllNoopReplay(f)
        }

    @Test fun allPreparedPrimaryStaysPendingWhileProtocolHistoryAppliesAndOnlyItsOwnPutCompletesIt() =
        withQueue(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, verifyPublication = false, action = ::assertHistoricalAllSequence)

    @Test fun allVerifiedPrimaryKeepsItsProofWhileProtocolHistoryAppliesAndOnlyItsOwnPutCompletesIt() =
        withQueue(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, action = ::assertHistoricalAllSequence)

    @Test fun allAuthenticatedProtocolHistoryWithWrongTargetsOrAnotherVersionAtTheSameKeyRemainsUnacked() {
        listOf("targets", "version").forEach { cut ->
            var stage = "SETUP"
            try {
                withQueue(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) { f ->
                    stage = "BODY"
                    val native = f.precursor.native.counts()
                    val alias = if (cut == "targets") f.raw.protocolHistoricalAllObject(targets = listOf(UUID.randomUUID().also {
                        assertFalse(it in f.record.event.complaintIds())
                    })) else f.raw.protocolHistoricalAllObject()
                    if (cut == "version") {
                        val before = f.counters(); val pending = allPrimaryImage(f) - "complaint_deletion_journal_applied"
                        f.raw.selectHistorical(alias); f.expectAppliedObjects(alias.stored)
                        assertEquals(1, f.poll().primaryAcknowledged); f.assertReleased()
                        assertRecoveryCharge(f, before, f.counters()); f.assertOnlyAuthorizedReportsErased()
                        assertEquals(pending, allPrimaryImage(f) - "complaint_deletion_journal_applied")
                        f.raw.selectHistorical(alias.withAdversarialOpaqueVersion("synthetic-protocol-history-hostile-other-version"))
                    } else {
                        f.raw.selectHistorical(alias); f.expectAppliedObjects()
                    }
                    val rows = f.domainImage(); val before = f.counters(); val acknowledgements = f.raw.ackRequests.toList()
                    val nativeStart = f.raw.order.size; val original = f.begin()
                    assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { f.poll(original) }
                    f.assertReleased(); f.assertNoAuthority(); f.assertExpectedAppliedObjects()
                    assertEquals(rows, f.domainImage(), "Authenticated but conflicting history cannot mutate domain/N/P/L/E/audit.")
                    if (cut == "version") assertEquals(before, f.counters()) else assertObservationCharge(before, f.counters())
                    val fresh = f.raw.order.drop(nativeStart)
                    assertEquals(1, fresh.count { it == "GET" }); assertEquals(1, fresh.count { it == "DECRYPT" })
                    assertTrue(f.deletionObservations.isNotEmpty(), "Valid AEAD reached the actual semantic/history SQL gate.")
                    assertFalse(fresh.any { it.startsWith("DeleteMessage:") }); assertEquals(acknowledgements, f.raw.ackRequests)
                    assertEquals(0, original.primaryAcked); assertEquals(native, f.precursor.native.counts())
                    f.assertSameOriginalRefused(original)
                    stage = "TEARDOWN"
                }
            } catch (failure: Throwable) {
                // Last reached boundary only; the final bounded code can reflect cleanup precedence, not the first cause.
                runCatching {
                    val code = (failure as? ComplaintDesiredInstallationExceptionV1)?.code?.name ?: "NONE"
                    val phase = failure as? PersistencePhaseException
                    System.err.println("TEST_ACTIVE_QUEUE_HISTORY_FAILURE variant=$cut stage=$stage class=${failure.javaClass.name} desired_code=$code " +
                        "phase_code=${phase?.code?.name ?: "NONE"} databaseOutcome=${phase?.databaseOutcome?.name ?: "NONE"} " +
                        "cleanupProven=${phase?.cleanupProven?.toString() ?: "NONE"}")
                }
                throw failure
            }
        }
    }

    @Test fun allMissingReceiptReservationOrWholeBookkeepingIsChargedWithoutResettingAnyUnattributedBalance() {
        listOf("n", "l", "npl").forEach { cut -> withQueue(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) { f ->
            val originalCounters = f.counters()
            // Explicit missing-row cuts after real AUTH/native PUT/VERIFY. The old frozen npl
            // cut did NOT refund counters: neither does this one. It is not a consistent restore.
            f.adversarialTransaction { jdbc ->
                if (cut != "l") assertEquals(1, jdbc.update("DELETE FROM installation_deletion_receipts WHERE data_scope_id = ?", f.scope))
                if (cut != "n") assertEquals(1, jdbc.update("DELETE FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", f.scope))
                if (cut == "npl") assertEquals(1, jdbc.update("DELETE FROM complaint_journal_publications WHERE data_scope_id = ?", f.scope))
            }
            assertEquals(originalCounters, f.counters())
            val actual = when (cut) { "n" -> allReceipt; "l" -> allReservation; else -> allReceipt + allPublication + allReservation }
            val reserve = if (cut == "n") ComplaintCapacityVector.ZERO else promise(f)
            assertEquals(1, f.poll().primaryAcknowledged)
            f.assertReleased(); f.assertNoAuthority(); assertAllRecoveryState(f, materialized(f))
            assertTransfer(originalCounters, f.counters(), actual = actual, reserve = reserve, use = materialized(f),
                refund = OwnerDeleteLiteralCharges.content, observation = true)
            assertRecoveryAuditDelta(f.auditsBeforeQueue, f.audits(), removed = 1)
            if (cut != "npl") assertEquals(f.proofBeforeQueue, f.publicationProof())
            else {
                val row = f.precursor.publication()
                assertTrue((row["created_at"] as Timestamp).toInstant() >= (row["verified_at"] as Timestamp).toInstant(),
                    "Rebuilt P records actual SQL creation after native readback, not a fabricated earlier AUTH time.")
            }
            val calls = f.calls.filter { it.step === TestActiveOwnerDeleteQueueStepV1.APPLY }.map { it.sql }
            val counters = calls.indexOfFirst { "FROM complaint_capacity_counters" in it && "FOR UPDATE" in it }
            val insert = calls.indexOfFirst { it.startsWith("INSERT INTO installation_deletion_receipts") || it.startsWith("INSERT INTO complaint_recovery_capacity_reservations") }
            val domain = calls.indexOfFirst { "FROM complaint_installation_ids" in it && "FOR UPDATE" in it }
            assertTrue(counters >= 0 && insert > counters && domain > insert)
            assertAllNoopReplay(f)
        } }
    }

    @Test fun allValidAbsentReservedOrDeletedPairsNeverSynthesizeCredentials() {
        listOf("absent", "reserved", "deleted").forEach { cut -> withQueue(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) { f ->
            // Adversarial domain row cuts, with no counter refund hidden in setup. The existing
            // authentic N/P/L and original native event remain the only deletion authority.
            val before = f.counters()
            f.adversarialTransaction { jdbc ->
                assertEquals(1, jdbc.update("DELETE FROM complaints WHERE owner_id = ? AND data_scope_id = ?", f.precursor.actor.id, f.scope))
                assertEquals(1, jdbc.update("DELETE FROM app_installations WHERE id = ? AND data_scope_id = ?", f.precursor.actor.id, f.scope))
                when (cut) {
                    "absent" -> {
                        assertEquals(1, jdbc.update("DELETE FROM installation_deletion_receipts WHERE data_scope_id = ?", f.scope))
                        assertEquals(1, jdbc.update("DELETE FROM complaint_installation_ids WHERE id = ? AND data_scope_id = ?", f.precursor.actor.id, f.scope))
                    }
                    "reserved" -> assertEquals(1, jdbc.update("UPDATE complaint_installation_ids SET state = 'RECOVERY_RESERVED' WHERE id = ? AND data_scope_id = ?", f.precursor.actor.id, f.scope))
                    else -> assertEquals(1, jdbc.update("UPDATE complaint_installation_ids SET state = 'DELETED', terminal_at = clock_timestamp() WHERE id = ? AND data_scope_id = ?", f.precursor.actor.id, f.scope))
                }
            }
            assertEquals(before, f.counters())
            val oldTerminal = if (cut == "deleted") f.observer.queryForObject("SELECT terminal_at FROM complaint_installation_ids WHERE id = ?", Timestamp::class.java, f.precursor.actor.id) else null
            val use = OwnerDeleteLiteralCharges.appliedOnly + OwnerDeleteLiteralCharges.audit +
                if (cut == "absent") OwnerDeleteLiteralCharges.installation else ComplaintCapacityVector.ZERO
            assertEquals(1, f.poll().primaryAcknowledged)
            f.assertReleased(); f.assertNoAuthority(); assertAllRecoveryState(f, use, credentialPresent = false)
            assertTransfer(before, f.counters(), actual = if (cut == "absent") allReceipt else ComplaintCapacityVector.ZERO, use = use, observation = true)
            if (oldTerminal != null) assertEquals(oldTerminal, f.observer.queryForObject("SELECT terminal_at FROM complaint_installation_ids WHERE id = ?", Timestamp::class.java, f.precursor.actor.id))
            assertFalse(f.calls.any { it.sql.startsWith("INSERT INTO app_installations") || it.sql.startsWith("UPDATE app_installations") })
            assertRecoveryAuditDelta(f.auditsBeforeQueue, f.audits(), removed = 0)
            assertAllNoopReplay(f)
        } }
    }

    @Test fun allExactReplayRepairsValidRestoredActivePaidLaterReportsAndRepliesWithoutASecondAppliedRow() =
        withQueue(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) { f ->
            val credential = f.observer.queryForMap("SELECT platform, owner_reference, last_authenticated_at, secret_verifier FROM app_installations WHERE id = ?", f.precursor.actor.id)
            val originalContent = checkNotNull(f.observer.queryForObject("SELECT to_jsonb(c)::text FROM complaints c WHERE owner_id = ?", String::class.java, f.precursor.actor.id))
            assertEquals(1, f.poll().primaryAcknowledged); f.assertReleased()
            val primary = allPrimaryImage(f); val oldAudits = f.audits()
            val originalTarget = f.record.event.complaintIds().single()
            val oldTerminal = f.observer.queryForObject("SELECT deleted_at FROM complaint_resource_ids WHERE id = ?", Timestamp::class.java, originalTarget)
            val report = UUID.randomUUID(); val reply = UUID.randomUUID()
            f.adversarialTransaction { jdbc ->
                // A deliberately restored valid ACTIVE pair, not a minted credential: keep its
                // existing verifier and restore only the genuine pre-erasure public row fields.
                assertEquals(1, jdbc.update("UPDATE complaint_installation_ids SET state = 'ACTIVE', terminal_at = NULL WHERE id = ? AND state = 'DELETED'", f.precursor.actor.id))
                assertEquals(1, jdbc.update("UPDATE app_installations SET state = 'ACTIVE', credential_version = ?, platform = ?, owner_reference = ?, " +
                    "last_authenticated_at = ?, deleted_at = NULL, verifier_expires_at = NULL WHERE id = ? AND state = 'DELETED'",
                    f.record.event.tuple.credentialVersion, credential["platform"], credential["owner_reference"], credential["last_authenticated_at"], f.precursor.actor.id))
                chargeAdversarialRows(jdbc, (OwnerDeleteLiteralCharges.resource + OwnerDeleteLiteralCharges.content).scaled(2))
                listOf(report, reply).forEach { id -> assertEquals(1, jdbc.update(
                    "INSERT INTO complaint_resource_ids(id,data_scope_id,test_only,state,created_at) VALUES (?, ?, true, 'LIVE', clock_timestamp())", id, f.scope)) }
                assertEquals(1, jdbc.update("INSERT INTO complaints SELECT r.* FROM jsonb_populate_record(NULL::complaints, ?::jsonb || " +
                    "jsonb_build_object('id', ?::uuid, 'version', 17)) r", originalContent, report))
                assertEquals(1, jdbc.update("INSERT INTO complaints SELECT r.* FROM jsonb_populate_record(NULL::complaints, ?::jsonb || " +
                    "jsonb_build_object('id', ?::uuid, 'kind', 'REPLY', 'parent_resource_id', ?::uuid, 'version', 18)) r", originalContent, reply, report))
            }
            val before = f.counters(); val use = OwnerDeleteLiteralCharges.audit.scaled(3)
            assertEquals(1, f.poll().primaryAcknowledged); f.assertReleased(); f.assertNoAuthority()
            assertAllRecoveryState(f, materialized(f) + use)
            assertEquals(primary, allPrimaryImage(f), "Exact replay cannot rewrite N/P/native proof/TTL/E.")
            assertEquals(0, f.calls.count { it.sql == appliedSql(f) })
            assertTransfer(before, f.counters(), use = use, refund = OwnerDeleteLiteralCharges.content.scaled(2))
            assertRecoveryAuditDelta(oldAudits, f.audits(), removed = 2)
            assertEquals(setOf(17L, 18L), f.observer.queryForList("SELECT (detail->>'version')::bigint FROM audit_log " +
                "WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_DELETED' AND entity_id IN (?, ?)",
                Long::class.java, f.scope, report.toString(), reply.toString()).toSet())
            assertArrayEquals(credential["secret_verifier"] as ByteArray, f.observer.queryForObject("SELECT secret_verifier FROM app_installations WHERE id = ?", ByteArray::class.java, f.precursor.actor.id))
            assertEquals(oldTerminal, f.observer.queryForObject("SELECT deleted_at FROM complaint_resource_ids WHERE id = ?", Timestamp::class.java, originalTarget))
            assertEquals(3L, f.observer.queryForObject("SELECT count(*) FROM complaint_resource_ids WHERE data_scope_id = ? AND state = 'DELETED'", Long::class.java, f.scope))
            assertAllNoopReplay(f)
        }

    @Test fun allExactReplayReconstructsOnlyMissingSnapshotReservationFromRemainingOriginalPromise() =
        withQueue(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) { f ->
            assertEquals(1, f.poll().primaryAcknowledged); f.assertReleased()
            val primary = allPrimaryImage(f); val identities = f.identityImage(); val oldAudits = f.audits(); val before = f.counters()
            // Missing paid row, deliberately no test-side refund and no reset of the original U.
            assertEquals(1, f.observer.update("DELETE FROM complaint_resource_ids WHERE id = ? AND data_scope_id = ?", f.record.event.complaintIds().single(), f.scope))
            assertEquals(before, f.counters())
            val use = OwnerDeleteLiteralCharges.resource + OwnerDeleteLiteralCharges.audit
            assertEquals(1, f.poll().primaryAcknowledged); f.assertReleased(); f.assertNoAuthority()
            assertAllRecoveryState(f, materialized(f) + use)
            assertEquals(primary, allPrimaryImage(f)); assertEquals(identities, f.identityImage())
            assertTransfer(before, f.counters(), use = use); assertRecoveryAuditDelta(oldAudits, f.audits(), removed = 0)
            assertEquals(0, f.calls.count { it.sql == appliedSql(f) })
            val calls = f.calls.filter { it.step === TestActiveOwnerDeleteQueueStepV1.APPLY }.map { it.sql }
            assertTrue(calls.indexOfFirst { it.startsWith("INSERT INTO complaint_resource_ids") } < calls.indexOfFirst { "FROM complaints WHERE id = ANY" in it })
            assertAllNoopReplay(f)
        }

    @Test fun allMissingCompletedReceiptRetainsTheOriginalCompletionAndRetryExpiry() = withQueue(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) { f ->
        assertEquals(1, f.poll().primaryAcknowledged); f.assertReleased()
        val old = f.receipt(); val proofAndApplied = allPrimaryImage(f) - "installation_deletion_receipts"
        val before = f.counters(); val oldAudits = f.audits()
        assertEquals(1, f.observer.update("DELETE FROM installation_deletion_receipts WHERE data_scope_id = ?", f.scope))
        assertEquals(1, f.poll().primaryAcknowledged); f.assertReleased(); f.assertNoAuthority()
        assertAllRecoveryState(f, materialized(f) + OwnerDeleteLiteralCharges.audit)
        listOf("authorized_at", "completed_at", "expires_at", "external_event_id", "external_object_version").forEach { assertEquals(old[it], f.receipt()[it], it) }
        assertEquals(proofAndApplied, allPrimaryImage(f) - "installation_deletion_receipts")
        assertTransfer(before, f.counters(), actual = allReceipt, use = OwnerDeleteLiteralCharges.audit)
        assertRecoveryAuditDelta(oldAudits, f.audits(), removed = 0)
        assertAllNoopReplay(f)
    }

    @Test fun allMissingBookkeepingUsesPrivacyHeadroomAcrossCreationClosureAndCeiling() {
        listOf(QueuePrivacyInput.CLOSED, QueuePrivacyInput.CEILING).forEach { input -> withQueue(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) { f ->
            assertEquals(1, f.observer.update("DELETE FROM installation_deletion_receipts WHERE data_scope_id = ?", f.scope))
            withPrivacyInput(f, input) {
                val before = f.counters()
                assertEquals(1, f.poll().primaryAcknowledged); f.assertReleased(); f.assertNoAuthority()
                assertAllRecoveryState(f, materialized(f))
                assertTransfer(before, f.counters(), actual = allReceipt, use = materialized(f), refund = OwnerDeleteLiteralCharges.content, observation = true)
            }
        } }
    }

    @Test fun allMissingReceiptOneByteBelowItsPostObservationHardHeadroomRefusesBeforeBookkeepingOrDomainWrites() =
        withQueue(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) { f ->
            assertEquals(1, f.observer.update("DELETE FROM installation_deletion_receipts WHERE data_scope_id = ?", f.scope))
            withPrivacyInput(f, QueuePrivacyInput.BOOKKEEPING_HARD_CAP) {
                val rows = f.domainImage(); val before = f.counters(); val original = f.begin()
                assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { f.poll(original) }
                f.assertReleased(); f.assertNoAuthority(); assertEquals(rows, f.domainImage()); assertObservationCharge(before, f.counters())
                assertTrue(f.deletionObservations.isNotEmpty()); assertEquals(1, f.raw.order.count { it == "DECRYPT" })
                assertFalse(f.calls.any { it.step === TestActiveOwnerDeleteQueueStepV1.APPLY &&
                    (it.sql.startsWith("INSERT") || it.sql.startsWith("UPDATE") || it.sql.startsWith("DELETE")) })
                assertEquals(0, original.primaryAcked); assertTrue(f.raw.ackRequests.isEmpty()); f.assertSameOriginalRefused(original)
            }
        }

    @Test fun allTornPairCompetingPrimariesAndUnknownCumulativeHistoryRemainUnacked() {
        listOf("missing-credential", "competing-n", "competing-p", "missing-l-after-e", "unknown-u").forEach { cut ->
            withQueue(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) { f ->
                val applied = cut in setOf("missing-l-after-e", "unknown-u")
                if (applied) { assertEquals(1, f.poll().primaryAcknowledged); f.assertReleased() }
                f.adversarialTransaction { jdbc -> when (cut) {
                    "missing-credential" -> {
                        // DATABASE-CORRUPTION injection, not a legitimate producible backup:
                        // preserve the frozen pending-ID/no-credential/PRESENT-C negative. The
                        // old raw DELETE violated an immediate FK before reaching recovery.
                        assertEquals("origin", jdbc.queryForObject("SHOW session_replication_role", String::class.java))
                        jdbc.execute("SET LOCAL session_replication_role = 'replica'")
                        try { assertEquals(1, jdbc.update("DELETE FROM app_installations WHERE id = ? AND data_scope_id = ?", f.precursor.actor.id, f.scope)) }
                        finally { jdbc.execute("SET LOCAL session_replication_role = 'origin'") }
                        assertEquals("origin", jdbc.queryForObject("SHOW session_replication_role", String::class.java))
                    }
                    "competing-n" -> assertEquals(1, jdbc.update("INSERT INTO installation_deletion_receipts SELECT r.* FROM installation_deletion_receipts n " +
                        "CROSS JOIN LATERAL jsonb_populate_record(NULL::installation_deletion_receipts, to_jsonb(n) || jsonb_build_object('deletion_key', ?::uuid)) r " +
                        "WHERE n.data_scope_id = ?", UUID.randomUUID(), f.scope))
                    "competing-p" -> {
                        // Canonical comparison bytes only: a second PREPARED SQL row is an
                        // adversarial input, not another native PUT/readback or primary issuer.
                        val route = f.process.consumers.journalRouting.derive(f.record.event.tuple).candidates().first { it != f.record.event.route }
                        val event = f.precursor.codec.canonicalize(f.record.event.tuple, f.record.event.complaintIds(), route.routingKeyId)
                        jdbc.query(OwnerDeleteAllPersistenceSql.test(f.precursor.dataScope).INSERT_PUBLICATION, { row, _ -> row.getTimestamp(1) }, event.route.eventId,
                            UUID.fromString(f.process.consumers.journalConfiguration.declaration().writer.generationId), event.tuple.epoch, event.complaintIds().size,
                            event.route.routingKeyId, event.route.objectKey, event.canonicalBytes(), MessageDigest.getInstance("SHA-256").digest(event.canonicalBytes())).single()
                    }
                    "missing-l-after-e" -> assertEquals(1, jdbc.update("DELETE FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", f.scope))
                    else -> {
                        val retired = ComplaintCapacityVector.units(ComplaintCapacityCounter.JOURNAL_RETIREMENTS, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 262144)
                        assertEquals(1, jdbc.update("UPDATE complaint_recovery_capacity_reservations SET converted_amounts = ?::bigint[] WHERE data_scope_id = ?",
                            vectorText(materialized(f) + retired), f.scope))
                    }
                } }
                assertEquals("origin", f.observer.queryForObject("SHOW session_replication_role", String::class.java))
                if (cut == "missing-credential") {
                    f.assertOriginalContentUnchanged(); assertEquals(0L, f.count("app_installations"))
                    assertEquals("DELETION_PENDING", f.observer.queryForObject("SELECT state FROM complaint_installation_ids WHERE id = ?", String::class.java, f.precursor.actor.id))
                }
                val rows = f.domainImage(); val before = f.counters(); val acknowledgements = f.raw.ackRequests.toList(); val original = f.begin()
                assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { f.poll(original) }
                f.assertReleased(); f.assertNoAuthority(); assertEquals(rows, f.domainImage())
                if (applied) assertEquals(before, f.counters()) else assertObservationCharge(before, f.counters())
                assertEquals(acknowledgements, f.raw.ackRequests); assertEquals(0, original.primaryAcked)
                assertEquals(if (applied) 1L else 0L, f.count("complaint_deletion_journal_applied")); f.assertSameOriginalRefused(original)
            }
        }
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
            assertEquals(listOf("ACTIVE TEST deletion DLQ delivery observed; bounded recovery attempted, not queue-health evidence."),
                appender.list.map { it.formattedMessage })
        } finally { logger.detachAppender(appender); appender.stop() }
    }

    @Test fun twoEmptyOneSecondLongPollRequestsSettleOnlyTheOneOrdinaryObservation() = withQueue { f ->
        f.raw.primaryBody = null; f.raw.dlqBody = null
        val before = f.counters(); val result = f.poll()
        assertEquals(0, result.primaryAcknowledged); assertEquals(0, result.dlqAcknowledged)
        f.assertReleased(); f.assertNoAuthority(); assertObservationCharge(before, f.counters())
        assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.raw.requests.isEmpty()); assertTrue(f.raw.kms.requests.isEmpty())
        assertTrue(f.deletionObservations.isEmpty()); assertEquals(0L, f.count("complaint_deletion_journal_applied"))
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
            assertTrue(f.deletionObservations.isEmpty()); assertEquals("POLLING", f.observation()?.get("state"))
            f.assertSameOriginalRefused(original)
        } }
    }

    @Test fun recoveryRoleIsAuthenticatedBeforeAnyQueueOrJournalRequest() = withQueue { f ->
        f.raw.wrongPrincipal = true
        val original = f.begin()
        assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { f.poll(original) }
        f.assertReleased(); f.assertNoAuthority(); assertEquals(listOf("STS"), f.raw.order)
        assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.deletionObservations.isEmpty()); f.assertSameOriginalRefused(original)
    }

    @Test fun badMetadataAndBadAeadTagOnTheOriginalPutCannotBecomeApplyOrAck() {
        listOf("metadata", "tag").forEach { kind -> withPoisonedQueue { f ->
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
            assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.deletionObservations.isEmpty())
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
            assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.raw.requests.isEmpty()); assertTrue(f.deletionObservations.isEmpty())
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
            assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.deletionObservations.isEmpty())
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
            f.assertReleased(); f.assertNoAuthority(); assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.deletionObservations.isEmpty())
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

    @Test fun priorSafeApplyAndAckRemainTruthfulWhenFinalSettlementCommitIsUnknown() = forEachPoisonedFamily { f ->
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
        assertTrue(f.raw.ackRequests.isEmpty()); assertTrue(f.deletionObservations.isEmpty())
        assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { original.requireNativeReleased(original.recipe) }
        assertSame(fatal, assertThrows<Error> { f.poll(original) })
    }

    private fun commitCut(f: TestActiveOwnerDeleteQueueFixtureV1, cut: QueueCommitCut, settlement: Boolean) {
        var phase: PersistencePhaseContext? = null
        val resource = Any(); val sentinel = Any(); var bound = false
        f.after = { call -> if (phase == null && call.sql == (if (settlement) TestActiveOwnerDeleteQueueSqlV1.settle else appliedSql(f))) {
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
            assertApplied(f); assertRecoveryCharge(f, before, f.counters())
            assertEquals(1, f.raw.ackRequests.size); assertEquals(1, original.primaryAcked)
            assertEquals("POLLING", f.observation()?.get("state")); assertEquals(0, (f.observation()?.get("primary_acked") as Number).toInt())
            assertEquals(original.attemptId, f.control()["lease_owner"])
        } else {
            assertTrue(f.raw.ackRequests.isEmpty()); assertEquals(0, original.primaryAcked)
            if (outcome === PersistenceDatabaseOutcome.COMMITTED) { assertApplied(f); assertRecoveryCharge(f, before, f.counters()) }
            else { assertEquals(0L, f.count("complaint_deletion_journal_applied")); assertObservationCharge(before, f.counters()) }
        }
        f.assertSameOriginalRefused(original)
    }

    /**
     * Explicit adversarial input AFTER genuine AUTH/VERIFY, not naturally generated exhaustion.
     * Full D, P hashes/limits/ordinals, Y/T reserves and every history row stay unchanged. Cleanup
     * reverses only the injected offset/closure bits, preserving the actual queue accounting delta.
     */
    private fun withPrivacyInput(f: TestActiveOwnerDeleteQueueFixtureV1, input: QueuePrivacyInput, action: () -> Unit) {
        fun invariantCounters() = f.observer.queryForList("SELECT (to_jsonb(c) - ARRAY['free_units','actual_units','configuration_closed'])::text " +
            "FROM complaint_capacity_counters c ORDER BY ordinal", String::class.java)
        fun invariantControls() = f.observer.queryForList("SELECT (to_jsonb(c) - 'creation_closed')::text FROM complaint_journal_control c ORDER BY data_scope_id", String::class.java)
        val controls = invariantControls(); val counters = invariantCounters(); val domain = f.domainImage(); val old = f.counters()
        assertEquals(22L, f.observer.queryForObject("SELECT count(*) FROM complaint_capacity_counters WHERE NOT configuration_closed", Long::class.java))
        val storage = f.observer.queryForMap("SELECT hard_limit, creation_limit, free_units, actual_units, recovery_reserved_units, test_reserved_units " +
            "FROM complaint_capacity_counters WHERE name = 'storage_bytes'")
        fun amount(name: String) = (storage.getValue(name) as Number).toLong()
        val promised = amount("recovery_reserved_units") + amount("test_reserved_units")
        val target = when (input) {
            QueuePrivacyInput.CLOSED -> amount("actual_units")
            QueuePrivacyInput.CEILING -> amount("creation_limit") - promised + 1
            QueuePrivacyInput.HARD_CAP -> amount("hard_limit") - amount("recovery_reserved_units") - amount("test_reserved_units") - 8191
            QueuePrivacyInput.BOOKKEEPING_HARD_CAP -> amount("hard_limit") - amount("recovery_reserved_units") - amount("test_reserved_units") - (8192 + 32768 - 1)
        }
        val injected = target - amount("actual_units")
        assertTrue(injected >= 0)
        val free = amount("free_units") - injected
        assertEquals(amount("hard_limit"), free + target + promised)
        if (input == QueuePrivacyInput.CEILING) {
            assertTrue(injected > 0)
            assertEquals(amount("creation_limit") + 1, target + promised, "Creation headroom counts actual + original Y + original T.")
        }
        if (input == QueuePrivacyInput.HARD_CAP) assertEquals(8191L, free) else assertTrue(free >= 8192)
        if (input == QueuePrivacyInput.BOOKKEEPING_HARD_CAP) assertEquals(40959L, free)
        assertEquals(1, f.observer.update("UPDATE complaint_capacity_counters SET free_units = ?, actual_units = ? WHERE name = 'storage_bytes'", free, target))
        if (input == QueuePrivacyInput.CLOSED) {
            assertEquals(2, f.observer.update("UPDATE complaint_journal_control SET creation_closed = true WHERE data_scope_id IN (?, ?) AND NOT creation_closed", UUID(0, 0), f.scope))
            assertEquals(22, f.observer.update("UPDATE complaint_capacity_counters SET configuration_closed = true"))
        }
        try {
            assertEquals(counters, invariantCounters()); assertEquals(controls, invariantControls()); assertEquals(domain, f.domainImage())
            assertEquals(22, old.size)
            f.counters().forEach { (counter, current) ->
                val prior = old.getValue(counter); val delta = if (counter == ComplaintCapacityCounter.STORAGE_BYTES) injected else 0
                assertEquals(prior.free - delta, current.free); assertEquals(prior.actual + delta, current.actual)
                assertEquals(prior.recovery, current.recovery); assertEquals(prior.test, current.test)
            }
            action()
        } finally {
            // Fixture input cleanup only, never a refund/completeness result.
            assertEquals(1, f.observer.update("UPDATE complaint_capacity_counters SET free_units = free_units + ?, actual_units = actual_units - ? WHERE name = 'storage_bytes'", injected, injected))
            if (input == QueuePrivacyInput.CLOSED) {
                assertEquals(22, f.observer.update("UPDATE complaint_capacity_counters SET configuration_closed = false"))
                assertEquals(2, f.observer.update("UPDATE complaint_journal_control SET creation_closed = false WHERE data_scope_id IN (?, ?)", UUID(0, 0), f.scope))
            }
        }
    }

    private fun changeBinding(f: TestActiveOwnerDeleteQueueFixtureV1, cut: QueueBindingCut) {
        val updated = when (cut) {
            QueueBindingCut.DESIRED -> f.observer.update("UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?", ByteArray(32) { 7 }, f.scope)
            QueueBindingCut.DATABASE -> f.observer.update("UPDATE complaint_journal_control SET database_identity = ? WHERE data_scope_id = ?", UUID.randomUUID(), f.scope)
            QueueBindingCut.RESTORE -> f.observer.update("UPDATE complaint_journal_control SET restore_identity = ? WHERE data_scope_id = ?", UUID.randomUUID(), f.scope)
            QueueBindingCut.CATALOG -> f.observer.update("UPDATE complaint_journal_control SET accepted_catalog_hash = ? WHERE data_scope_id = ?", ByteArray(32) { 8 }, UUID(0L, 0L))
            QueueBindingCut.EXPIRED -> f.observer.update("UPDATE complaint_journal_control SET lease_expires_at = clock_timestamp() - interval '1 millisecond' WHERE data_scope_id = ?", f.scope)
            QueueBindingCut.MAINTENANCE -> f.observer.update("UPDATE complaint_journal_control SET maintenance_closed = true WHERE data_scope_id = ?", f.scope)
            QueueBindingCut.SCAN -> f.observer.update("UPDATE complaint_journal_control SET scan_requested = true WHERE data_scope_id = ?", f.scope)
            QueueBindingCut.REPLACED -> f.observer.update("UPDATE complaint_journal_control SET lease_owner = ?, lease_token = lease_token + 1 WHERE data_scope_id = ?", UUID.randomUUID(), f.scope)
        }
        assertEquals(1, updated)
    }

    // Independent fixed V14 bookkeeping envelopes, not the product's charge calculator as oracle.
    private val allReceipt = ComplaintCapacityVector.units(ComplaintCapacityCounter.INSTALLATION_RECEIPTS, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 32768)
    private val allPublication = ComplaintCapacityVector.units(ComplaintCapacityCounter.JOURNAL_PUBLICATIONS, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 262144)
    private val allReservation = ComplaintCapacityVector.units(ComplaintCapacityCounter.RECOVERY_RESERVATIONS, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 16384)

    private fun allPrimaryImage(f: TestActiveOwnerDeleteQueueFixtureV1) = f.domainImage().filterKeys {
        it in setOf("installation_deletion_receipts", "complaint_journal_publications", "complaint_deletion_journal_applied")
    }

    /** One real producer plus labeled protocol-history bytes; never a two-AUTH/PUT history. */
    private fun assertHistoricalAllSequence(f: TestActiveOwnerDeleteQueueFixtureV1) {
        assertAuthorizationCharge(f)
        val before = f.counters(); val native = f.precursor.native.counts(); val setup = f.domainImage()
        val pending = allPrimaryImage(f) - "complaint_deletion_journal_applied"
        val reservation = allReservationIdentity(f)
        val alias = f.raw.protocolHistoricalAllObject()
        val originalEvent = f.record.event; val other = alias.event
        assertTrue(other.route in f.process.consumers.journalRouting.derive(originalEvent.tuple).candidates())
        assertFalse(other.route.routingKeyId == originalEvent.route.routingKeyId)
        assertFalse(other.route.objectKey == originalEvent.route.objectKey); assertFalse(other.route.eventId == originalEvent.route.eventId)
        assertEquals(originalEvent.tuple.scope, other.tuple.scope); assertEquals(originalEvent.tuple.eventKind, other.tuple.eventKind)
        assertEquals(originalEvent.tuple.actorKind, other.tuple.actorKind); assertEquals(originalEvent.tuple.actorId, other.tuple.actorId)
        assertEquals(originalEvent.tuple.credentialVersion, other.tuple.credentialVersion); assertEquals(originalEvent.tuple.epoch, other.tuple.epoch)
        assertEquals(originalEvent.tuple.operationKey, other.tuple.operationKey); assertEquals(originalEvent.tuple.encodedFingerprint(), other.tuple.encodedFingerprint())
        assertEquals(originalEvent.complaintIds(), other.complaintIds())
        assertFalse(originalEvent.canonicalBytes().contentEquals(other.canonicalBytes()), "Route-dependent eventId is part of the canonical payload.")
        assertFalse(originalEvent.semanticSha256 == other.semanticSha256)
        assertSame(f.record.stored, f.raw.stored); assertSame(originalEvent, f.raw.event)
        assertEquals(setup, f.domainImage()); assertEquals(before, f.counters()); assertEquals(native, f.precursor.native.counts())

        val sql = OwnerDeleteAllApplySql.test(f.precursor.dataScope)
        val verifySql = OwnerDeleteAllVerificationSql.test(f.precursor.dataScope).RECORD_VERIFIED
        f.raw.selectHistorical(alias); f.expectAppliedObjects(alias.stored)
        val aliasPoll = f.begin(); val first = f.poll(aliasPoll)
        assertEquals(1, first.primaryAcknowledged); assertEquals(0, first.dlqAcknowledged)
        f.assertReleased(); f.assertNoAuthority(); f.assertExpectedAppliedObjects(); f.assertOnlyAuthorizedReportsErased()
        assertEquals(pending, allPrimaryImage(f) - "complaint_deletion_journal_applied", "Alias never updates the original N/P, even xmin.")
        assertEquals(f.proofBeforeQueue, f.publicationProof())
        assertEquals(if (f.verifiedPublication) "VERIFIED" else "PREPARED", f.precursor.publication()["state"])
        assertEquals("AUTHORIZED_DELETE", f.receipt()["state"]); assertNull(f.receipt()["external_event_id"])
        assertFalse(f.calls.any { it.sql in setOf(verifySql, sql.COMPLETE_RECEIPT, sql.MARK_APPLIED) })
        assertEquals(1, f.calls.count { it.sql == sql.INSERT_APPLIED })
        assertRecoveryCharge(f, before, f.counters()); assertHistoricalReservation(f, reservation, materialized(f))
        assertRecoveryAuditDelta(f.auditsBeforeQueue, f.audits(), f.precursor.reports.size.toLong())
        assertHistoricalSummaries(f, mapOf(other.route.eventId to f.precursor.reports.size))
        listOf("installation_deletion_receipts", "complaint_journal_publications", "complaint_recovery_capacity_reservations",
            "complaint_installation_ids", "app_installations", "complaint_resource_ids").forEach {
            assertEquals(f.countsBeforeQueue.getValue(it), f.count(it), "No new bookkeeping, credential or reservation: $it")
        }
        assertEquals("DELETED", f.observer.queryForObject("SELECT state FROM complaint_installation_ids WHERE id = ?", String::class.java, f.precursor.actor.id))
        assertEquals(f.precursor.reports.size.toLong(), f.observer.queryForObject("SELECT count(*) FROM complaint_resource_ids WHERE data_scope_id = ? AND state = 'DELETED'", Long::class.java, f.scope))
        val credential = f.observer.queryForMap("SELECT * FROM app_installations WHERE id = ?", f.precursor.actor.id)
        assertEquals("DELETED", credential["state"]); assertEquals(originalEvent.tuple.credentialVersion + 1, credential["credential_version"])
        listOf("platform", "owner_reference", "last_authenticated_at").forEach { assertNull(credential[it], it) }
        assertEquals(f.credentialIdentityBeforeQueue, f.credentialIdentity())
        assertEquals((credential["deleted_at"] as Timestamp).toInstant().plus(Duration.ofHours(192)), (credential["verifier_expires_at"] as Timestamp).toInstant())
        val erased = f.domainImage().filterKeys { it in setOf("complaint_installation_ids", "app_installations", "complaint_resource_ids", "complaints") }
        val aliasApplied = f.domainImage().getValue("complaint_deletion_journal_applied").single()
        f.assertSameOriginalRefused(aliasPoll); assertAllNoopReplay(f)

        // Only the actual primary PUT may fill its PREPARED proof and complete its own N/P.
        val paid = f.counters(); val audits = f.audits()
        f.raw.selectOriginal(); f.expectAppliedObjects(alias.stored, f.record.stored)
        val primaryPoll = f.begin(); val second = f.poll(primaryPoll)
        assertEquals(1, second.primaryAcknowledged); assertEquals(0, second.dlqAcknowledged)
        f.assertReleased(); f.assertNoAuthority(); f.assertExpectedAppliedObjects(); f.assertOnlyAuthorizedReportsErased()
        assertEquals(erased, f.domainImage().filterKeys { it in erased.keys }, "Primary completion never extends credential TTL or rewrites domain tombstones/xmin.")
        assertTrue(aliasApplied in f.domainImage().getValue("complaint_deletion_journal_applied"))
        val publication = f.precursor.publication(); val receipt = f.receipt()
        assertEquals("APPLIED", publication["state"]); assertEquals("COMPLETED", receipt["state"]); assertEquals("APPLIED", receipt["outcome"])
        assertArrayEquals(originalEvent.canonicalBytes(), publication["event_bytes"] as ByteArray)
        assertEquals(originalEvent.route.routingKeyId, publication["routing_key_id"]); assertEquals(f.record.stored.key, publication["object_key"])
        assertEquals(f.record.stored.version, publication["object_version"]); assertEquals(f.record.stored.version, receipt["external_object_version"])
        val hash = MessageDigest.getInstance("SHA-256").digest(f.record.stored.bytes)
        assertArrayEquals(hash, publication["ciphertext_hash"] as ByteArray); assertArrayEquals(hash, receipt["external_ciphertext_hash"] as ByteArray)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(publication["verification_bytes"] as ByteArray), publication["verification_hash"] as ByteArray)
        assertEquals(f.record.stored.lastModified, (publication["object_created_at"] as Timestamp).toInstant())
        assertEquals(originalEvent.route.eventId, receipt["external_event_id"]); assertEquals(f.receiptBeforeQueue, f.receiptIdentity())
        assertEquals(publication["created_at"], receipt["authorized_at"]); assertEquals(publication["applied_at"], receipt["completed_at"])
        assertEquals(publication["applied_at"], f.observer.queryForObject("SELECT applied_at FROM complaint_deletion_journal_applied " +
            "WHERE object_key = ? AND object_version = ? AND data_scope_id = ?", Timestamp::class.java, f.record.stored.key, f.record.stored.version, f.scope))
        assertEquals((receipt["completed_at"] as Timestamp).toInstant().plus(Duration.ofHours(192)), (receipt["expires_at"] as Timestamp).toInstant())
        if (f.verifiedPublication) assertEquals(f.proofBeforeQueue, f.publicationProof())
        assertEquals(if (f.verifiedPublication) 0 else 1, f.calls.count { it.sql == verifySql })
        assertEquals(1, f.calls.count { it.sql == sql.COMPLETE_RECEIPT }); assertEquals(1, f.calls.count { it.sql == sql.MARK_APPLIED })
        assertEquals(1, f.calls.count { it.sql == sql.INSERT_APPLIED })
        val secondUse = OwnerDeleteLiteralCharges.appliedOnly + OwnerDeleteLiteralCharges.audit
        assertTransfer(paid, f.counters(), use = secondUse)
        assertHistoricalReservation(f, reservation, materialized(f) + secondUse)
        assertRecoveryAuditDelta(audits, f.audits(), removed = 0)
        assertHistoricalSummaries(f, mapOf(other.route.eventId to f.precursor.reports.size, originalEvent.route.eventId to 0))
        f.assertSameOriginalRefused(primaryPoll); assertAllNoopReplay(f)
        f.raw.selectHistorical(alias); assertAllNoopReplay(f)
        assertEquals(native, f.precursor.native.counts(), "Reference history and all five queue polls never issue another producer call.")
        assertEquals(5, f.raw.order.count { it == "GET" }); assertEquals(5, f.raw.order.count { it == "DECRYPT" })
        assertEquals(5, f.raw.ackRequests.size)
    }

    private fun allReservationIdentity(f: TestActiveOwnerDeleteQueueFixtureV1): String = checkNotNull(f.observer.queryForObject(
        "SELECT (to_jsonb(r) - ARRAY['state','converted_amounts','converted_at'])::text FROM complaint_recovery_capacity_reservations r WHERE data_scope_id = ?",
        String::class.java, f.scope))

    private fun assertHistoricalReservation(f: TestActiveOwnerDeleteQueueFixtureV1, identity: String, used: ComplaintCapacityVector) {
        assertEquals(identity, allReservationIdentity(f), "Only U/state/conversion time may change; primary identity and Y are frozen.")
        val row = f.observer.queryForMap("SELECT state, reserved_amounts::text, converted_amounts::text FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", f.scope)
        assertEquals("PARTIAL", row["state"]); assertEquals(vectorText(promise(f)), row["reserved_amounts"]); assertEquals(vectorText(used), row["converted_amounts"])
    }

    private fun assertHistoricalSummaries(f: TestActiveOwnerDeleteQueueFixtureV1, events: Map<String, Int>) {
        val rows = f.observer.queryForList("SELECT detail->>'eventId' AS event_id, (detail->>'removed')::integer AS removed, " +
            "complaint_actor_kind, actor_user_id FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED'", f.scope)
        assertEquals(events.size, rows.size); assertEquals(events, rows.associate { it["event_id"] as String to (it["removed"] as Number).toInt() })
        rows.forEach { assertEquals("SYSTEM", it["complaint_actor_kind"]); assertNull(it["actor_user_id"]) }
    }

    private fun assertAllRecoveryState(f: TestActiveOwnerDeleteQueueFixtureV1, used: ComplaintCapacityVector, credentialPresent: Boolean = true) {
        assertEquals(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, f.family)
        assertSame(f.record.stored, f.raw.stored); assertSame(f.record.event, f.raw.event)
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaints WHERE owner_id = ?", Long::class.java, f.precursor.actor.id))
        assertEquals("DELETED", f.observer.queryForObject("SELECT state FROM complaint_installation_ids WHERE id = ?", String::class.java, f.precursor.actor.id))
        val credentials = f.observer.queryForList("SELECT * FROM app_installations WHERE id = ?", f.precursor.actor.id)
        if (credentialPresent) {
            val credential = credentials.single()
            assertEquals("DELETED", credential["state"]); assertEquals(f.record.event.tuple.credentialVersion + 1, credential["credential_version"])
            listOf("platform", "owner_reference", "last_authenticated_at").forEach { assertNull(credential[it], it) }
            assertEquals(f.credentialIdentityBeforeQueue, f.credentialIdentity(), "No verifier, identity or original creation replacement.")
        } else assertTrue(credentials.isEmpty(), "Valid absent credentials are never reconstructed.")
        val publication = f.precursor.publication(); val receipt = f.receipt()
        assertEquals("APPLIED", publication["state"]); assertEquals("COMPLETED", receipt["state"])
        assertArrayEquals(f.record.event.canonicalBytes(), publication["event_bytes"] as ByteArray)
        assertEquals(f.record.stored.version, publication["object_version"]); assertEquals(f.record.stored.key, publication["object_key"])
        val ciphertextHash = MessageDigest.getInstance("SHA-256").digest(f.record.stored.bytes)
        assertArrayEquals(ciphertextHash, publication["ciphertext_hash"] as ByteArray)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(publication["verification_bytes"] as ByteArray), publication["verification_hash"] as ByteArray)
        assertEquals(f.record.stored.lastModified, (publication["object_created_at"] as Timestamp).toInstant())
        assertEquals(f.record.event.route.eventId, receipt["external_event_id"]); assertEquals(f.record.stored.version, receipt["external_object_version"])
        assertArrayEquals(ciphertextHash, receipt["external_ciphertext_hash"] as ByteArray)
        assertEquals(publication["created_at"], receipt["authorized_at"]); assertEquals(publication["applied_at"], receipt["completed_at"])
        assertEquals((receipt["completed_at"] as Timestamp).toInstant().plus(Duration.ofHours(192)), (receipt["expires_at"] as Timestamp).toInstant())
        val applied = f.observer.queryForList("SELECT * FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", f.scope).single()
        assertEquals(f.record.stored.key, applied["object_key"]); assertEquals(f.record.stored.version, applied["object_version"])
        assertArrayEquals(ciphertextHash, applied["ciphertext_hash"] as ByteArray); assertEquals(publication["applied_at"], applied["applied_at"])
        val reserve = f.observer.queryForMap("SELECT state, reserved_amounts::text, converted_amounts::text FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", f.scope)
        assertEquals("PARTIAL", reserve["state"]); assertEquals(vectorText(promise(f)), reserve["reserved_amounts"]); assertEquals(vectorText(used), reserve["converted_amounts"])
        assertEquals(setOf("SYSTEM"), f.observer.queryForList("SELECT complaint_actor_kind FROM audit_log WHERE complaint_data_scope_id = ? " +
            "AND action = 'COMPLAINT_RECOVERY_APPLIED'", String::class.java, f.scope).toSet())
    }

    private fun assertAllNoopReplay(f: TestActiveOwnerDeleteQueueFixtureV1) {
        val rows = f.domainImage(); val counters = f.counters()
        assertEquals(1, f.poll().primaryAcknowledged); f.assertReleased(); f.assertNoAuthority()
        assertEquals(rows, f.domainImage()); assertEquals(counters, f.counters())
        assertEquals(0, f.calls.count { it.sql == appliedSql(f) })
        assertFalse(f.calls.any { it.step === TestActiveOwnerDeleteQueueStepV1.APPLY &&
            (it.sql.startsWith("INSERT") || it.sql.startsWith("UPDATE") || it.sql.startsWith("DELETE")) })
    }

    private fun assertRecoveryAuditDelta(before: Map<String, Long>, after: Map<String, Long>, removed: Long) {
        val expected = before.toMutableMap()
        if (removed != 0L) expected["COMPLAINT_DELETED"] = expected.getOrDefault("COMPLAINT_DELETED", 0L) + removed
        expected["COMPLAINT_RECOVERY_APPLIED"] = expected.getOrDefault("COMPLAINT_RECOVERY_APPLIED", 0L) + 1
        assertEquals(expected, after)
    }

    private fun assertTransfer(before: Map<ComplaintCapacityCounter, DeleteAllCounter>, after: Map<ComplaintCapacityCounter, DeleteAllCounter>,
        actual: ComplaintCapacityVector = ComplaintCapacityVector.ZERO, reserve: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
        use: ComplaintCapacityVector = ComplaintCapacityVector.ZERO, refund: ComplaintCapacityVector = ComplaintCapacityVector.ZERO, observation: Boolean = false) {
        assertEquals(22, after.size)
        before.forEach { (counter, old) ->
            val observed = if (observation && counter === ComplaintCapacityCounter.STORAGE_BYTES) 8192L else 0L
            assertEquals(old.copy(free = old.free - actual[counter] - reserve[counter] + refund[counter] - observed,
                actual = old.actual + actual[counter] + use[counter] - refund[counter] + observed,
                recovery = old.recovery + reserve[counter] - use[counter]), after.getValue(counter), counter.storedName)
        }
    }

    private fun chargeAdversarialRows(jdbc: JdbcTemplate, charge: ComplaintCapacityVector) {
        // Fixed counted synthetic C/resource setup, not a product capacity grant or conversion.
        ComplaintCapacityCounter.entries.filter { charge[it] > 0 }.sortedBy { it.storedName }.forEach { counter ->
            assertEquals(1, jdbc.update("UPDATE complaint_capacity_counters SET free_units = free_units - ?, actual_units = actual_units + ? " +
                "WHERE name = ? AND free_units >= ?", charge[counter], charge[counter], counter.storedName, charge[counter]))
        }
    }

    private fun assertApplied(f: TestActiveOwnerDeleteQueueFixtureV1) {
        val targets = f.precursor.reports.size.toLong()
        listOf("complaint_idempotency_receipts", "installation_deletion_receipts", "complaint_journal_publications",
            "complaint_recovery_capacity_reservations", "complaint_installation_ids", "app_installations", "complaint_resource_ids").forEach {
            assertEquals(f.countsBeforeQueue.getValue(it), f.count(it), "No reconstruction/credential synthesis: $it")
        }
        assertEquals(1L, f.count("complaint_deletion_journal_applied")); f.assertOnlyAuthorizedReportsErased()
        val expectedAudits = f.auditsBeforeQueue.toMutableMap()
        fun add(action: String, count: Long) { expectedAudits[action] = expectedAudits.getOrDefault(action, 0L) + count }
        add("COMPLAINT_DELETED", targets)
        add("COMPLAINT_RECOVERY_APPLIED", 1)
        assertEquals(expectedAudits, f.audits())
        assertEquals("APPLIED", f.precursor.publication()["state"])
        assertEquals("COMPLETED", f.receipt()["state"]); assertEquals("APPLIED", f.receipt()["outcome"])
        assertEquals(f.record.stored.version, f.receipt()["external_object_version"])
        assertEquals(f.record.event.route.eventId, f.receipt()["external_event_id"])
        assertEquals(f.receiptBeforeQueue, f.receiptIdentity(), "The original actor, operation, targets, fingerprint and consumed grant never change.")
        if (f.verifiedPublication) assertEquals(f.proofBeforeQueue, f.publicationProof(), "Recovery does not replace the committed native VERIFY proof.")
        assertEquals(f.grantBeforeQueue, f.grantImage(), "AUTH's original consumed grant is neither reissued nor consumed again.")
        assertEquals(targets, f.observer.queryForObject("SELECT count(*) FROM complaint_resource_ids WHERE data_scope_id = ? AND state = 'DELETED'", Long::class.java, f.scope))
        if (f.family == ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) {
            assertEquals("DELETED", f.observer.queryForObject("SELECT state FROM complaint_installation_ids WHERE id = ?", String::class.java, f.precursor.actor.id))
            val credential = f.observer.queryForMap("SELECT state, credential_version, platform, owner_reference, last_authenticated_at FROM app_installations WHERE id = ?", f.precursor.actor.id)
            assertEquals("DELETED", credential["state"])
            assertEquals(f.record.event.tuple.credentialVersion + 1, credential["credential_version"])
            listOf("platform", "owner_reference", "last_authenticated_at").forEach { assertNull(credential[it], it) }
            assertEquals(f.credentialIdentityBeforeQueue, f.credentialIdentity(), "ALL changes its retained lifecycle/version, never the original verifier or identity.")
        } else assertEquals(f.identitiesBeforeQueue, f.identityImage(), "OWNER/ADMIN resource erasure preserves the existing credential pairs exactly.")
        val reserve = f.observer.queryForMap("SELECT state, reserved_amounts::text, converted_amounts::text FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", f.scope)
        assertEquals("PARTIAL", reserve["state"])
        assertEquals(vectorText(promise(f)), reserve["reserved_amounts"])
        assertEquals(vectorText(materialized(f)), reserve["converted_amounts"], "Only observed new applied/audit rows spend the existing Y; its future remainder stays reserved.")
    }

    private fun authorization(f: TestActiveOwnerDeleteQueueFixtureV1): ComplaintCapacityVector = when (f.family) {
        ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> ComplaintCapacityVector.of(longArrayOf(
            0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 376832, 0))
        ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> OwnerDeleteLiteralCharges.authorization + OwnerDeleteLiteralCharges.audit
        else -> OwnerDeleteLiteralCharges.authorization
    }
    private fun promise(f: TestActiveOwnerDeleteQueueFixtureV1): ComplaintCapacityVector = when (f.family) {
        ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> ComplaintCapacityVector.of(longArrayOf(
            0, 113, 0, 0, 0, 0, 0, 1, 0, 4, 0, 0, 4, 0, 0, 0, 0, 100, 0, 0, 10240000, 0))
        ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> ComplaintCapacityVector.of(longArrayOf(
            0, 6, 0, 0, 0, 0, 0, 2, 0, 4, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 589824, 0))
        else -> OwnerDeleteLiteralCharges.promise
    }
    private fun materialized(f: TestActiveOwnerDeleteQueueFixtureV1) = OwnerDeleteLiteralCharges.appliedOnly +
        OwnerDeleteLiteralCharges.audit.scaled(f.precursor.reports.size.toLong() + 1)
    private fun vectorText(vector: ComplaintCapacityVector) = vector.toLongArray().joinToString(",", "{", "}")
    private fun assertAuthorizationCharge(f: TestActiveOwnerDeleteQueueFixtureV1) {
        f.beforeAuthorization.forEach { (counter, old) ->
            val auth = authorization(f)[counter]; val promise = promise(f)[counter]
            assertEquals(old.copy(free = old.free - auth - promise, actual = old.actual + auth, recovery = old.recovery + promise),
                f.afterAuthorization.getValue(counter), counter.storedName)
        }
    }
    private fun appliedSql(f: TestActiveOwnerDeleteQueueFixtureV1): String = when (f.family) {
        ComplaintJournalDeletionKindV1.OWNER_DELETE -> OwnerDeletePersistenceSql.INSERT_APPLIED
        ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> OwnerDeleteAllApplySql.test(f.precursor.dataScope).INSERT_APPLIED
        else -> AdminDeletePersistenceSql.INSERT_APPLIED
    }
    private fun assertObservationCharge(before: Map<ComplaintCapacityCounter, DeleteAllCounter>, after: Map<ComplaintCapacityCounter, DeleteAllCounter>) {
        assertEquals(22, after.size)
        before.forEach { (counter, old) ->
            val charge = if (counter === ComplaintCapacityCounter.STORAGE_BYTES) 8192L else 0L
            assertEquals(old.copy(free = old.free - charge, actual = old.actual + charge), after.getValue(counter), counter.storedName)
        }
    }
    private fun assertRecoveryCharge(f: TestActiveOwnerDeleteQueueFixtureV1, before: Map<ComplaintCapacityCounter, DeleteAllCounter>, after: Map<ComplaintCapacityCounter, DeleteAllCounter>) {
        assertEquals(22, after.size)
        before.forEach { (counter, old) ->
            val observation = if (counter === ComplaintCapacityCounter.STORAGE_BYTES) 8192L else 0L
            val used = materialized(f)[counter]
            val refund = OwnerDeleteLiteralCharges.content[counter] * f.precursor.reports.size
            assertEquals(old.copy(free = old.free + refund - observation, actual = old.actual + used - refund + observation,
                recovery = old.recovery - used), after.getValue(counter), counter.storedName)
        }
    }

    /** Sticky unknown/failed native custody is expected to make owning assembly shutdown refuse too.
     * A body-complete marker prevents that expected teardown error from hiding setup/test failures.
     */
    private fun withPoisonedQueue(family: ComplaintJournalDeletionKindV1 = ComplaintJournalDeletionKindV1.OWNER_DELETE, action: (TestActiveOwnerDeleteQueueFixtureV1) -> Unit) {
        var assertionsCompleted = false
        assertThrows<RuntimeException> { withQueue(family) { f -> action(f); assertionsCompleted = true } }
        assertTrue(assertionsCompleted, "Only the already-asserted poisoned recipe's assembly close may supply the expected outer failure.")
    }
    private fun forEachFamily(action: (TestActiveOwnerDeleteQueueFixtureV1) -> Unit) =
        ComplaintJournalDeletionKindV1.entries.forEach { withQueue(it, action = action) }
    private fun forEachPoisonedFamily(action: (TestActiveOwnerDeleteQueueFixtureV1) -> Unit) =
        ComplaintJournalDeletionKindV1.entries.forEach { withPoisonedQueue(it, action) }
    private fun withQueue(family: ComplaintJournalDeletionKindV1 = ComplaintJournalDeletionKindV1.OWNER_DELETE,
        verifyPublication: Boolean = true, action: (TestActiveOwnerDeleteQueueFixtureV1) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use {
            it.bind(); withActiveQueueFixture(it, family, verifyPublication, action = action)
        }
}

private enum class QueueBindingCut { DESIRED, DATABASE, RESTORE, CATALOG, EXPIRED, REPLACED, MAINTENANCE, SCAN }
private enum class QueuePrivacyInput { CLOSED, CEILING, HARD_CAP, BOOKKEEPING_HARD_CAP }
private enum class QueueCommitCut { BEFORE, AFTER, UNKNOWN, UNRESOLVED_RELEASE }
