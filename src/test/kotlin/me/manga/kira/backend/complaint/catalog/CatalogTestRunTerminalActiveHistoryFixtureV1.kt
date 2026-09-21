package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.DeleteAllCounter
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDeniedPathV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEvidenceDigestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPolicyRefV1
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueExceptionV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueFixtureV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveQueueHistoricalAllObjectV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointCreateFixtureV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointDeletionFixtureV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialDeletionNativeRecordV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.withActiveQueueFixture
import me.manga.kira.backend.complaint.infrastructure.reconciliation.withRegisteredInitialCheckpointCreate
import me.manga.kira.backend.complaint.infrastructure.reconciliation.withRegisteredInitialCheckpointDeletion
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgePublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalEpochSealResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceResultV1
import me.manga.kira.backend.complaint.journal.JournalPublisherHttpRequest
import me.manga.kira.backend.complaint.journal.JournalPublisherObject
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
import me.manga.kira.backend.complaint.journal.journalPublisherRawGetReply
import me.manga.kira.backend.complaint.journal.journalPublisherRawHttpClient
import me.manga.kira.backend.complaint.journal.journalPublisherRawListDocument
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import software.amazon.awssdk.http.SdkHttpClient
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Real global G1/full-D/capture -> fresh TEST registration -> enrollment -> A's initial EMPTY
 * seal/checkpoint -> C CREATE -> closed gates -> D's full 1..2 drain and 1,2,3 seal history.
 * This independently composes producer entry points; no copied completed cut or fake APPLIED row.
 * The ordinary journal is genuinely empty despite retained nonempty complaint/installation data.
 * The separate nonempty helper below starts with A's actual registered deletion instead.
 */
internal fun withActiveHistoryTerminalCatalogRun(tls: VersionBoundPersistenceConnectedFixture,
    action: (CatalogTestRunTerminalActiveHistoryFixtureV1) -> Unit) {
    val inputs = TestOrdinaryDrainFixtureInputsV1(terminalQuiescence = TestTerminalQuiescenceFixtureInputsV1())
    withRegisteredInitialCheckpointCreate(tls, terminalHistory = inputs) { c ->
        val before = c.counters(); val attempt = c.attempt()
        c.assertApplied(c.create(attempt), attempt); c.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE); c.assertReleased()
        val sealer = c.checkpoint.sealer
        assertEquals(PersistenceLifecycleObservation.READY, sealer.runtime.pools.deletion.prepareDeletion())
        TestRunPurgeFixtureV1(sealer.p, sealer.runtime, c.registration, c.exchange.service, sealer.native, inputs).use { f ->
            val history = terminalCatalogActiveRows(f)
            assertEquals(1, history.getValue("V26").size); assertTrue(history.getValue("V29").isEmpty())
            CatalogTerminalHistorySealingProbeV1(f).use { probe ->
                assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, probe.begin().seal())
                probe.assertReleased(); f.assertReleased()
            }
            val drain = f.beginDrain()
            val ordinaryApproval = activeHistoryOrdinaryApproval(f, inputs, drain)
            val ordinaryRaw = f.rawEvidence
            try {
                assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                    drain.drain(ordinaryApproval, ordinaryRaw, AwsJournalKmsFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS))
                f.assertReleased(); assertEquals(history, terminalCatalogActiveRows(f))
                assertEquals(listOf("LIST", "LIST"), f.inventoryRequests.map { it.kind }); assertTrue(f.inventoryKeys.requests.isEmpty())
                withDrainedActiveHistoryCatalog(f, inputs, drain, ordinaryApproval, history) { terminal ->
                    action(CatalogTestRunTerminalActiveHistoryFixtureV1(terminal, c))
                }
            } finally { ordinaryApproval.fill(0); ordinaryRaw.forEach { it.fill(0) } }
        }
    }
}

internal enum class TerminalCatalogQueueHistoryV1 { ABSENT, SETTLED, POLLING }
internal enum class TerminalCatalogAllRecoveryHistoryV1 {
    RECONSTRUCTED_PUBLICATION_AND_RECEIPTS, LATER_DOMAIN_AND_RESOURCE,
    HISTORICAL_ALIAS_BEFORE_PRIMARY_VERIFY, HISTORICAL_ALIAS_BEFORE_PRIMARY_COMPLETION,
}

/**
 * Genuine A AUTH -> original PUT/readback -> optional SQL VERIFY, optionally genuine B APPLY/ACK/SETTLED
 * or malformed-message POLLING. Pins are supplied before full D. D keeps A's exact registered
 * deletion owner/template and rereads the original ciphertext; no second publisher or fake D.
 * One OWNER_DELETE or retained-N/P/L ALL primary/one installation. ALL is selected before full
 * D and must pass a real settled queue plus an explicit registered privacy replay, not HTTP auth.
 * All cases remain source-authored, NOT_RUN.
 */
internal fun withNonemptyActiveHistoryTerminalCatalogRun(tls: VersionBoundPersistenceConnectedFixture,
    queue: TerminalCatalogQueueHistoryV1 = TerminalCatalogQueueHistoryV1.ABSENT,
    family: ComplaintJournalDeletionKindV1 = ComplaintJournalDeletionKindV1.OWNER_DELETE,
    verifyPublication: Boolean = true,
    action: (CatalogTestRunTerminalActiveHistoryFixtureV1) -> Unit) =
    withSealedNonemptyActiveHistoryTerminalRun(tls, queue, family, verifyPublication) { f, a, b, inputs, _ ->
        val record = checkNotNull(a.record)
        val history = terminalCatalogActiveRows(f)
        val retainedAll = if (family === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) terminalCatalogAllPrimaryRows(f.observer, f.scope) else null
        val recoveryBefore = if (retainedAll != null) terminalCatalogAllRecoveryWithoutState(f.observer, f.scope) else null
        CatalogTerminalHistoryDrainProbeV1(f, a).use { probe ->
            if (retainedAll != null) {
                val before = terminalCatalogAllComparisonRows(f.observer, f.scope)
                probe.replayAll()
                assertEquals(before, terminalCatalogAllComparisonRows(f.observer, f.scope),
                    "Registered privacy replay keeps N/P/E/L, verifier/version, both TTLs, audit and every counter+xmin unchanged.")
                assertRetainedAllQueuePrimary(checkNotNull(b), "PARTIAL")
            }
            val native = CatalogTerminalOriginalOrdinaryHttpV1(a.process.consumers.journalConfiguration, record, probe::assertReleased)
            val drain = probe.begin(native::client, native.keys::httpClient)
            val approval = activeHistoryOrdinaryApproval(f, inputs, drain); val raw = f.rawEvidence
            try {
                terminalCatalogHistoryBoundaries(f, probe::assertReleased) {
                    assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                        drain.drain(approval, raw, AwsJournalKmsFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS))
                }
                probe.assertReleased(); native.assertReadPairs(2, recovery = true); f.assertReleased(); a.assertReleased()
                probe.assertRetainedInventoryRecovery(record.stored)
                assertTrue(f.inventoryRequests.isEmpty() && f.inventoryKeys.requests.isEmpty(), "The empty helper's new deletion pair and raw graph are never used.")
                assertEquals(history, terminalCatalogActiveRows(f))
                assertEquals("APPLIED", a.publication()["state"])
                assertEquals(1L, f.observer.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", Long::class.java, f.scope))
                probe.assertPrimaryApplied(queue != TerminalCatalogQueueHistoryV1.SETTLED)
                if (retainedAll != null) {
                    probe.assertAllResidualConversion()
                    assertRetainedAllQueuePrimary(checkNotNull(b), "CONVERTED")
                    assertEquals(recoveryBefore, terminalCatalogAllRecoveryWithoutState(f.observer, f.scope), "D closes only P-U; original Y/U and last application time survive.")
                    assertEquals(retainedAll, terminalCatalogAllPrimaryRows(f.observer, f.scope))
                }
                withDrainedActiveHistoryCatalog(f, inputs, drain, approval, history, record) { terminal ->
                    action(CatalogTestRunTerminalActiveHistoryFixtureV1(terminal, a.creators.single(), a, b))
                    if (retainedAll != null) assertEquals(retainedAll, terminalCatalogAllPrimaryRows(f.observer, f.scope))
                }
            } finally { approval.fill(0); raw.forEach { it.fill(0) }; native.assertClosed() }
        }
    }

/** Shared genuine producers; refusal cases stop here, never manufacture or reset a D/E capability. */
internal fun withSealedNonemptyActiveHistoryTerminalRun(tls: VersionBoundPersistenceConnectedFixture,
    queue: TerminalCatalogQueueHistoryV1, family: ComplaintJournalDeletionKindV1, verifyPublication: Boolean,
    allRecovery: TerminalCatalogAllRecoveryHistoryV1? = null,
    completeHistoricalPrimary: Boolean = true,
    action: (TestRunPurgeFixtureV1, TestRegisteredInitialCheckpointDeletionFixtureV1, TestActiveOwnerDeleteQueueFixtureV1?,
        TestOrdinaryDrainFixtureInputsV1, TestActiveQueueHistoricalAllObjectV1?) -> Unit) {
    require(family in setOf(ComplaintJournalDeletionKindV1.OWNER_DELETE, ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL))
    require(if (family === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) queue === TerminalCatalogQueueHistoryV1.SETTLED
        else verifyPublication || queue === TerminalCatalogQueueHistoryV1.ABSENT)
    require(allRecovery == null || family === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL &&
        verifyPublication == (allRecovery !== TerminalCatalogAllRecoveryHistoryV1.HISTORICAL_ALIAS_BEFORE_PRIMARY_VERIFY))
    require(completeHistoricalPrimary || allRecovery in setOf(TerminalCatalogAllRecoveryHistoryV1.HISTORICAL_ALIAS_BEFORE_PRIMARY_VERIFY,
        TerminalCatalogAllRecoveryHistoryV1.HISTORICAL_ALIAS_BEFORE_PRIMARY_COMPLETION))
    val inputs = TestOrdinaryDrainFixtureInputsV1(terminalQuiescence = TestTerminalQuiescenceFixtureInputsV1())
    fun continueFrom(a: TestRegisteredInitialCheckpointDeletionFixtureV1, b: TestActiveOwnerDeleteQueueFixtureV1?, historical: TestActiveQueueHistoricalAllObjectV1? = null) {
        a.assertReleased()
        val record = checkNotNull(a.record)
        assertSame(checkNotNull(a.event), record.event); assertEquals(2L, record.event.comparison.epoch)
        val producerCounts = a.native.counts(); val originalBytes = record.stored.bytes.copyOf()
        val queueOrder = b?.raw?.order?.toList(); val acknowledgements = b?.raw?.ackRequests?.toList()
        fun queueClients() = b?.raw?.let { listOf(it.sts.createdClients, it.kms.createdClients, it.sqs.createdClients, it.s3Created) }
        val queueOpenings = queueClients(); val queueSqlCalls = b?.calls?.size
        val sealer = a.checkpoint.sealer
        assertSame(a.runtime, sealer.runtime)
        val primaryBeforeChildClose = TestRunPurgeFixtureV1(sealer.p, a.runtime, a.registration, a.audit,
            sealer.native, inputs, borrowedPrimaryOwner = a).use { f ->
            val history = terminalCatalogActiveRows(f)
            val pending = if (completeHistoricalPrimary) null else terminalCatalogAllPrimaryRows(f.observer, f.scope).filterKeys {
                it in setOf("installation_deletion_receipts", "complaint_journal_publications")
            }
            assertEquals(1, history.getValue("V26").size)
            assertEquals(if (b == null) 0 else 1, history.getValue("V29").size)
            CatalogTerminalHistorySealingProbeV1(f).use { probe ->
                assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, probe.begin().seal())
                probe.assertReleased(); f.assertReleased()
            }
            assertEquals(history, terminalCatalogActiveRows(f))
            pending?.let { assertEquals(it, terminalCatalogAllPrimaryRows(f.observer, f.scope).filterKeys { table -> table in it.keys },
                "Real sealing preserves the original pending N/P/proof and their xmin.") }
            action(f, a, b, inputs, historical)
            assertEquals(history, terminalCatalogActiveRows(f))
            a.image()
        }
        assertEquals(primaryBeforeChildClose, a.image(),
            "Child teardown preserves the outer A owner's N/L/P and domain rows, including xmin, until its own ordered cleanup.")
        assertArrayEquals(originalBytes, record.stored.bytes); originalBytes.fill(0)
        assertEquals(producerCounts, a.native.counts(), "D/E never re-encrypt, PUT or reopen A's producer graph.")
        assertEquals(queueOrder, b?.raw?.order); assertEquals(acknowledgements, b?.raw?.ackRequests)
        assertEquals(queueOpenings, queueClients()); assertEquals(queueSqlCalls, b?.calls?.size)
        b?.assertReleased()
    }
    if (queue === TerminalCatalogQueueHistoryV1.ABSENT) {
        withRegisteredInitialCheckpointDeletion(tls, family, terminalHistory = inputs) { a ->
            a.authorize(); a.publish(); if (verifyPublication) a.verify(); continueFrom(a, null)
        }
    } else withActiveQueueFixture(tls, family, verifyPublication, terminalHistory = inputs) { b ->
        if (family === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) {
            assertEquals(if (verifyPublication) "VERIFIED" else "PREPARED", b.precursor.publication()["state"])
            b.beforeAuthorization.forEach { (counter, old) ->
                val auth = TerminalCatalogAllLiteralChargesV1.authorization[counter]; val promise = TerminalCatalogAllLiteralChargesV1.promise[counter]
                assertEquals(old.copy(free = old.free - auth - promise, actual = old.actual + auth, recovery = old.recovery + promise),
                    b.afterAuthorization.getValue(counter), counter.storedName)
            }
        }
        if (allRecovery != null) {
            val historical = when (allRecovery) {
                TerminalCatalogAllRecoveryHistoryV1.HISTORICAL_ALIAS_BEFORE_PRIMARY_VERIFY,
                TerminalCatalogAllRecoveryHistoryV1.HISTORICAL_ALIAS_BEFORE_PRIMARY_COMPLETION -> authorAllHistoricalAlias(b, completeHistoricalPrimary)
                else -> { authorAllRecoveryHistory(b, allRecovery); null }
            }
            continueFrom(b.precursor, b, historical)
            return@withActiveQueueFixture
        }
        val before = b.counters()
        if (queue === TerminalCatalogQueueHistoryV1.POLLING) {
            b.raw.primaryBody = "{" // Genuine failed original: no native journal dispatch, APPLY or ACK.
            val original = b.begin()
            assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { b.poll(original) }
            assertTrue(b.raw.requests.isEmpty() && b.raw.kms.requests.isEmpty() && b.raw.ackRequests.isEmpty())
            assertEquals(0L, b.count("complaint_deletion_journal_applied"))
        } else {
            val completed = b.poll()
            assertEquals(1, completed.primaryAcknowledged); assertEquals(0, completed.dlqAcknowledged)
            assertEquals(1L, b.count("complaint_deletion_journal_applied"))
        }
        b.assertReleased(); b.assertNoAuthority(); assertEquals(queue.name, b.observation()?.get("state"))
        val after = b.counters()
        before.forEach { (counter, old) ->
            val observation = if (counter === ComplaintCapacityCounter.STORAGE_BYTES) 8192L else 0L
            val applied = queue === TerminalCatalogQueueHistoryV1.SETTLED
            val used = if (!applied) 0L else if (family === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)
                TerminalCatalogAllLiteralChargesV1.applied[counter] else (OwnerDeleteLiteralCharges.appliedOnly + OwnerDeleteLiteralCharges.audit.scaled(2))[counter]
            val refund = if (!applied) 0L else if (family === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)
                TerminalCatalogAllLiteralChargesV1.content[counter] else OwnerDeleteLiteralCharges.content[counter]
            assertEquals(old.copy(free = old.free + refund - observation, actual = old.actual + used - refund + observation,
                recovery = old.recovery - used), after.getValue(counter), counter.storedName)
        }
        if (family === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) assertRetainedAllQueuePrimary(b, "PARTIAL")
        continueFrom(b.precursor, b) // Keep the actual observation alive until every D/E assertion.
    }
}

internal enum class TerminalCatalogRetainedPrimaryFaultV1 { RELOAD_OWNER, VERIFY_LEASE, APPLY_LEASE, APPLY_CONTROL }

/** Narrow genuine A -> retained D primary cuts. No synthetic healthy history, inventory owner,
 * proof issuer or completed phase is substituted. Existing VERIFIED D/E/erasure cases stay separate. */
internal object CatalogRetainedDPrimaryCasesV1 {
    fun prepared(tls: VersionBoundPersistenceConnectedFixture) =
        withSealedNonemptyActiveHistoryTerminalRun(tls, TerminalCatalogQueueHistoryV1.ABSENT,
            ComplaintJournalDeletionKindV1.OWNER_DELETE, verifyPublication = false) { f, a, _, inputs, _ ->
            assertEquals("PREPARED", a.publication()["state"])
            val record = checkNotNull(a.record); val before = a.counters()
            val nonTargetRowsBeforeD = nonTargetDomainRows(f.observer, f.scope, record.event.complaintIds().single())
            val identity = a.image().filterKeys { it in setOf("app_installations", "complaint_installation_ids") }
            val otherControlRows = otherControls(f.observer, f.scope)
            var completedRows: Map<String, List<String>>? = null
            var completedRecovery: String? = null
            CatalogTerminalHistoryDrainProbeV1(f, a, preparedPrimary = true).use { probe ->
                val primary = CatalogTerminalOriginalOrdinaryHttpV1(a.process.consumers.journalConfiguration, record, probe::assertReleased)
                val inventory = CatalogTerminalOriginalOrdinaryHttpV1(a.process.consumers.journalConfiguration, record, probe::assertReleased)
                probe.afterPrimary = { call, jdbc ->
                    if (call.sql == OwnerDeletePersistenceSql.COMPLETE_RECEIPT) {
                        assertEquals(PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY, call.path)
                        assertNull(completedRows)
                        assertCompletedPrimary(jdbc, a, "PARTIAL", nonTargetRowsBeforeD)
                        assertApplyAccounting(jdbc, before)
                        completedRows = primaryRows(jdbc, f.scope) - "complaint_recovery_capacity_reservations"
                        completedRecovery = terminalCatalogAllRecoveryWithoutState(jdbc, f.scope)
                    }
                }
                val drain = probe.begin(inventory::client, inventory.keys::httpClient, primary::primaryClient, primary.keys::httpClient)
                val approval = activeHistoryOrdinaryApproval(f, inputs, drain); val raw = f.rawEvidence
                // Keep an original D failure when the unchanged native cleanup also refuses.
                AutoCloseable { approval.fill(0); raw.forEach { it.fill(0) }; primary.assertClosed(); inventory.assertClosed() }.use {
                    terminalCatalogHistoryBoundaries(f, probe::assertReleased) {
                        assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                            drain.drain(approval, raw, AwsJournalKmsFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS))
                    }
                    probe.assertRetainedPrimarySequence(listOf("SELECT", "RELOAD", "VERIFY", "APPLY"))
                    probe.assertPrimaryApplied(expected = true); probe.assertRetainedInventoryRecovery(record.stored)
                    primary.assertPrimaryReadback(); inventory.assertReadPairs(2, recovery = true)
                    assertEquals(checkNotNull(completedRows), primaryRows(f.observer, f.scope) - "complaint_recovery_capacity_reservations",
                        "Later inventory, conversion and seal never rewrite the actual committed N/P/E/proof/domain/audit, even xmin.")
                    assertEquals(checkNotNull(completedRecovery), terminalCatalogAllRecoveryWithoutState(f.observer, f.scope))
                    assertCompletedPrimary(f.observer, a, "CONVERTED", nonTargetRowsBeforeD)
                    assertEquals(identity, a.image().filterKeys { it in identity.keys })
                    assertEquals(otherControlRows, otherControls(f.observer, f.scope), "No global healthy history is synthesized.")
                    assertTrue(f.inventoryRequests.isEmpty() && f.inventoryKeys.requests.isEmpty())
                    a.assertReleased(); f.assertReleased()
                }
            }
        }

    fun refuses(tls: VersionBoundPersistenceConnectedFixture, fault: TerminalCatalogRetainedPrimaryFaultV1) {
        val prepared = fault === TerminalCatalogRetainedPrimaryFaultV1.VERIFY_LEASE
        withSealedNonemptyActiveHistoryTerminalRun(tls, TerminalCatalogQueueHistoryV1.ABSENT,
            ComplaintJournalDeletionKindV1.OWNER_DELETE, verifyPublication = !prepared) { f, a, _, inputs, _ ->
            val before = refusalRows(f.observer, f.scope)
            val sealOrderBefore = f.sealHttp.order.toList()
            assertTrue(sealOrderBefore.isNotEmpty(), "The shared native transport already records the genuine A predecessor.")
            val record = checkNotNull(a.record)
            val nonTargetRowsBeforeD = nonTargetDomainRows(f.observer, f.scope, record.event.complaintIds().single())
            var controlBeforeFault: String? = null
            var injected = 0
            CatalogTerminalHistoryDrainProbeV1(f, a, preparedPrimary = prepared).use { probe ->
                val primary = CatalogTerminalOriginalOrdinaryHttpV1(a.process.consumers.journalConfiguration, record, probe::assertReleased)
                val inventory = CatalogTerminalOriginalOrdinaryHttpV1(a.process.consumers.journalConfiguration, record, probe::assertReleased)
                val drain = probe.begin(inventory::client, inventory.keys::httpClient, primary::primaryClient, primary.keys::httpClient)
                val targetSql = when (fault) {
                    TerminalCatalogRetainedPrimaryFaultV1.RELOAD_OWNER -> TestRunSealingSqlV1.lockScopeControl
                    TerminalCatalogRetainedPrimaryFaultV1.VERIFY_LEASE -> OwnerDeletePersistenceSql.RECORD_VERIFIED
                    else -> OwnerDeletePersistenceSql.COMPLETE_RECEIPT
                }
                probe.afterPrimary = { call, jdbc ->
                    if (injected == 0 && call.sql == targetSql &&
                        (fault !== TerminalCatalogRetainedPrimaryFaultV1.RELOAD_OWNER || probe.isSelectedReload(call))) {
                        probe.expectPrimaryRollback(call)
                        controlBeforeFault = control(jdbc, f.scope)
                        if (fault === TerminalCatalogRetainedPrimaryFaultV1.VERIFY_LEASE) {
                            assertEquals("VERIFIED", jdbc.queryForObject("SELECT state FROM complaint_journal_publications WHERE data_scope_id = ?", String::class.java, f.scope))
                        } else if (fault !== TerminalCatalogRetainedPrimaryFaultV1.RELOAD_OWNER) {
                            assertCompletedPrimary(jdbc, a, "PARTIAL", nonTargetRowsBeforeD) // Real domain/E/L/audit/N/P writes already happened on this holder.
                        }
                        val change = when (fault) {
                            TerminalCatalogRetainedPrimaryFaultV1.RELOAD_OWNER -> "lease_owner = ?::uuid"
                            TerminalCatalogRetainedPrimaryFaultV1.APPLY_CONTROL -> "maintenance_closed = false"
                            else -> "lease_expires_at = clock_timestamp() - interval '1 second'"
                        }
                        val prefix: Array<out Any> = if (fault === TerminalCatalogRetainedPrimaryFaultV1.RELOAD_OWNER)
                            arrayOf(UUID.randomUUID().also { assertNotEquals(drain.attemptId, it) }) else emptyArray()
                        assertEquals(1, jdbc.update("UPDATE complaint_journal_control SET $change WHERE data_scope_id = ? AND test_only " +
                            "AND lease_owner = ? AND lease_token = ? AND lease_expires_at > clock_timestamp()",
                            *prefix, f.scope, drain.attemptId, drain.leaseToken))
                        injected++ // Only after the actual fault SQL returned, never by throwing from the callback.
                    }
                }
                val approval = activeHistoryOrdinaryApproval(f, inputs, drain); val raw = f.rawEvidence
                try {
                    assertThrows<TestOrdinaryDrainExceptionV1> {
                        drain.drain(approval, raw, AwsJournalKmsFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS)
                    }
                    assertEquals(1, injected)
                    val expected = listOf("SELECT", "RELOAD") + when (fault) {
                        TerminalCatalogRetainedPrimaryFaultV1.RELOAD_OWNER -> emptyList()
                        TerminalCatalogRetainedPrimaryFaultV1.VERIFY_LEASE -> listOf("VERIFY")
                        else -> listOf("APPLY")
                    }
                    probe.assertRetainedPrimarySequence(expected)
                    probe.assertPrimaryRefusal(if (fault === TerminalCatalogRetainedPrimaryFaultV1.APPLY_CONTROL)
                        TestRunSealingSqlV1.readScopeControl else TestOrdinarySealSqlV1.lease, targetSql,
                        if (fault === TerminalCatalogRetainedPrimaryFaultV1.RELOAD_OWNER) OwnerDeletePersistenceSql.LOCK_REGISTERED_RECEIPT else null)
                    assertEquals(before, refusalRows(f.observer, f.scope),
                        "The actual refused transaction rolls back proof/primary/domain/audit/counters/run/scan rows and xmin; OPEN pays nothing.")
                    assertEquals(checkNotNull(controlBeforeFault), control(f.observer, f.scope), "The negative comparison drift rolls back too; no lease reset is manufactured.")
                    if (prepared) primary.assertPrimaryReadback() else primary.assertUnused()
                    inventory.assertUnused(); assertEquals(sealOrderBefore, f.sealHttp.order,
                        "The refused D cannot append native work to the retained A/first-cut transport.")
                    assertTrue(f.inventoryRequests.isEmpty() && f.inventoryKeys.requests.isEmpty())
                    val calls = probe.observedCallCounts()
                    assertThrows<TestOrdinaryDrainExceptionV1> {
                        drain.drain(approval, raw, AwsJournalKmsFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS)
                    }
                    assertEquals(calls, probe.observedCallCounts()); inventory.assertUnused()
                    assertEquals(sealOrderBefore, f.sealHttp.order, "The consumed original cannot append native work either.")
                    if (prepared) primary.assertPrimaryReadback() else primary.assertUnused()
                    probe.assertRetainedPrimarySequence(expected) // Consumed original adds no phase/SQL/native attempt.
                    a.assertReleased(); f.assertReleased()
                } finally { approval.fill(0); raw.forEach { it.fill(0) }; primary.assertClosed(); inventory.assertClosed() }
            }
        }
    }

    private fun assertApplyAccounting(jdbc: JdbcTemplate, before: Map<ComplaintCapacityCounter, DeleteAllCounter>) {
        val after = jdbc.query("SELECT name, free_units, actual_units, recovery_reserved_units, test_reserved_units " +
            "FROM complaint_capacity_counters ORDER BY ordinal", { row, _ -> row.getString(1) to (2..5).map { row.getLong(it) } }).toMap()
        before.forEach { (counter, old) ->
            val used = OwnerDeleteLiteralCharges.ordinaryApply[counter]; val refund = OwnerDeleteLiteralCharges.content[counter]
            assertEquals(listOf(old.free + refund, old.actual + used - refund, old.recovery - used, old.test), after.getValue(counter.storedName),
                "One real E + one removal audit; exact content refund and no use of TEST reserve: ${counter.storedName}")
        }
    }

    private fun assertCompletedPrimary(
        jdbc: JdbcTemplate,
        a: TestRegisteredInitialCheckpointDeletionFixtureV1,
        recoveryState: String,
        nonTargetRowsBeforeD: Map<String, List<String>>,
    ) {
        val record = checkNotNull(a.record)
        val target = record.event.complaintIds().single()
        val p = jdbc.queryForMap("SELECT * FROM complaint_journal_publications WHERE data_scope_id = ?", a.scope)
        val n = jdbc.queryForMap("SELECT * FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND operation = 'OWNER_DELETE'", a.scope)
        val e = jdbc.queryForMap("SELECT * FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", a.scope)
        val l = jdbc.queryForMap("SELECT state, reserved_amounts::text, converted_amounts::text, converted_at " +
            "FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", a.scope)
        assertEquals("APPLIED", p["state"]); assertEquals("COMPLETED", n["state"]); assertEquals("APPLIED", n["outcome"]); assertEquals(204, n["response_status"])
        assertEquals(record.event.route.eventId, p["event_id"]); assertEquals(record.stored.key, p["object_key"]); assertEquals(record.stored.version, p["object_version"])
        val canonical = record.event.canonicalBytes()
        try { assertArrayEquals(canonical, p["event_bytes"] as ByteArray) } finally { canonical.fill(0) }
        assertEquals(Sha256.hex(record.stored.bytes), HexFormat.of().formatHex(p["ciphertext_hash"] as ByteArray))
        assertEquals(Sha256.hex(p["verification_bytes"] as ByteArray), HexFormat.of().formatHex(p["verification_hash"] as ByteArray))
        assertEquals(record.stored.lastModified, (p["object_created_at"] as Timestamp).toInstant())
        assertEquals(record.stored.retainUntil, (p["retain_until"] as Timestamp).toInstant())
        assertEquals(p["created_at"], n["authorized_at"]); assertEquals(a.actor.id, n["actor_id"]); assertEquals(a.key, n["idempotency_key"])
        assertEquals(p["event_id"], n["publication_ref"]); assertEquals(p["event_id"], n["external_event_id"])
        assertEquals(2L, n["external_epoch"]); assertEquals(record.stored.version, n["external_object_version"])
        assertArrayEquals(p["ciphertext_hash"] as ByteArray, n["external_ciphertext_hash"] as ByteArray)
        for (column in listOf("event_id", "object_key", "object_version", "writer_generation", "journal_epoch", "event_kind", "target_count", "data_scope_id", "test_only"))
            assertEquals(p[column], e[column], column)
        assertArrayEquals(p["ciphertext_hash"] as ByteArray, e["ciphertext_hash"] as ByteArray)
        assertEquals(recoveryState, l["state"])
        assertEquals(OwnerDeleteLiteralCharges.promise.toLongArray().joinToString(",", "{", "}"), l["reserved_amounts"])
        assertEquals(OwnerDeleteLiteralCharges.ordinaryApply.toLongArray().joinToString(",", "{", "}"), l["converted_amounts"])
        val at = listOf(p["created_at"], p["verified_at"], e["applied_at"], l["converted_at"], p["applied_at"], n["completed_at"]).map { (it as Timestamp).toInstant() }
        assertTrue(at.zipWithNext().all { (earlier, later) -> !later.isBefore(earlier) })
        assertEquals(at.last().plus(Duration.ofHours(192)), (n["expires_at"] as Timestamp).toInstant())
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM complaints WHERE id = ? AND data_scope_id = ?", Long::class.java, target, a.scope))
        assertEquals("DELETED", jdbc.queryForObject("SELECT state FROM complaint_resource_ids WHERE id = ? AND data_scope_id = ?", String::class.java, target, a.scope))
        assertEquals(nonTargetRowsBeforeD, nonTargetDomainRows(jdbc, a.scope, target),
            "All non-target content and resources, including genuine SYSTEM NOTICE rows and xmin, remain unchanged from before D; no new rows appear.")
        assertEquals(true, jdbc.queryForObject("SELECT actor_user_id IS NULL AND complaint_actor_kind = 'INSTALLATION' AND entity_type = 'complaint' " +
            "AND entity_id = ? AND detail = jsonb_build_object('version', 1) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_DELETED'",
            Boolean::class.java, record.event.complaintIds().single().toString(), a.scope))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED'", Long::class.java, a.scope))
    }

    private fun nonTargetDomainRows(jdbc: JdbcTemplate, scope: UUID, target: UUID): Map<String, List<String>> =
        listOf("complaints", "complaint_resource_ids").associateWith { table -> jdbc.queryForList(
            "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t WHERE data_scope_id = ? AND id <> ? ORDER BY id",
            String::class.java, scope, target) }

    private fun primaryRows(jdbc: JdbcTemplate, scope: UUID): Map<String, List<String>> = listOf(
        "complaint_idempotency_receipts", "installation_deletion_receipts", "complaint_journal_publications", "complaint_recovery_capacity_reservations",
        "complaint_deletion_journal_applied", "complaint_deletion_journal_retirements", "complaints", "complaint_resource_ids", "app_installations", "complaint_installation_ids",
    ).associateWith { rows(jdbc, it, scope) } + mapOf("audit" to jdbc.queryForList(
        "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM audit_log t WHERE complaint_data_scope_id = ? ORDER BY id", String::class.java, scope))

    private fun refusalRows(jdbc: JdbcTemplate, scope: UUID): Map<String, List<String>> = primaryRows(jdbc, scope) + listOf(
        "complaint_test_runs", "complaint_test_active_seal_intents", "complaint_test_active_queue_observations",
        "complaint_journal_scan_runs", "complaint_journal_scan_entries", "complaint_test_terminal_intents",
    ).associateWith { rows(jdbc, it, scope) } + mapOf("counters" to jdbc.queryForList(
        "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_capacity_counters t ORDER BY ordinal", String::class.java),
        "other-controls" to otherControls(jdbc, scope))

    private fun rows(jdbc: JdbcTemplate, table: String, scope: UUID): List<String> = jdbc.queryForList(
        "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text", String::class.java, scope)
    private fun control(jdbc: JdbcTemplate, scope: UUID): String = rows(jdbc, "complaint_journal_control", scope).single()
    private fun otherControls(jdbc: JdbcTemplate, scope: UUID): List<String> = jdbc.queryForList(
        "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_journal_control t WHERE data_scope_id <> ? ORDER BY data_scope_id", String::class.java, scope)
}

/** Independent ALL v1 literals: unchanged promise plus exact materialized version/repair costs. */
internal object TerminalCatalogAllLiteralChargesV1 {
    val authorization = ComplaintCapacityVector.of(longArrayOf(0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 376832, 0))
    val promise = ComplaintCapacityVector.of(longArrayOf(0, 113, 0, 0, 0, 0, 0, 1, 0, 4, 0, 0, 4, 0, 0, 0, 0, 100, 0, 0, 10240000, 0))
    val applied = ComplaintCapacityVector.of(longArrayOf(0, 2, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 163840, 0))
    val content = ComplaintCapacityVector.of(longArrayOf(0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 262144, 0))
    val receipt = ComplaintCapacityVector.units(ComplaintCapacityCounter.INSTALLATION_RECEIPTS, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 32768)
    val publication = ComplaintCapacityVector.units(ComplaintCapacityCounter.JOURNAL_PUBLICATIONS, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 262144)
    val reservation = ComplaintCapacityVector.units(ComplaintCapacityCounter.RECOVERY_RESERVATIONS, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 16384)
    val resource = ComplaintCapacityVector.units(ComplaintCapacityCounter.RESOURCE_IDS, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 16384)
    val audit = ComplaintCapacityVector.units(ComplaintCapacityCounter.AUDIT_ROWS, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 65536)
    val appliedEvent = ComplaintCapacityVector.units(ComplaintCapacityCounter.JOURNAL_APPLIED, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 32768)
}

/** One actual A producer plus B's separately labeled protocol-history object. No alias AUTH/PUT,
 * active-key switch or crypto construction here; the queue authenticates the alias, and optionally the original. */
private fun authorAllHistoricalAlias(b: TestActiveOwnerDeleteQueueFixtureV1, completePrimary: Boolean): TestActiveQueueHistoricalAllObjectV1 {
    val a = b.precursor; val charge = TerminalCatalogAllLiteralChargesV1
    val native = a.native.counts(); val originalWire = b.record.stored.bytes.copyOf()
    val pending = terminalCatalogAllPrimaryRows(b.observer, b.scope).filterKeys {
        it in setOf("installation_deletion_receipts", "complaint_journal_publications")
    }
    fun reservationIdentity() = b.observer.queryForObject("SELECT (to_jsonb(l) - ARRAY['state','converted_amounts','converted_at'])::text " +
        "FROM complaint_recovery_capacity_reservations l WHERE event_id = ?", String::class.java, b.record.event.route.eventId)
    val reservation = reservationIdentity(); val before = b.counters()
    val alias = b.raw.protocolHistoricalAllObject()
    assertSame(b.record.event, b.raw.event); assertSame(b.record.stored, b.raw.stored)
    assertNotEquals(b.record.event.route, alias.event.route)
    val original = b.record.event.tuple; val other = alias.event.tuple
    assertEquals(original.scope, other.scope); assertEquals(original.eventKind, other.eventKind)
    assertEquals(original.actorKind, other.actorKind); assertEquals(original.actorId, other.actorId)
    assertEquals(original.credentialVersion, other.credentialVersion); assertEquals(original.epoch, other.epoch)
    assertEquals(original.operationKey, other.operationKey); assertEquals(original.encodedFingerprint(), other.encodedFingerprint())
    assertEquals(b.record.event.complaintIds(), alias.event.complaintIds())
    assertNotEquals(b.record.event.semanticSha256, alias.event.semanticSha256,
        "Alternate route eventId is in the canonical bytes; equal semantics do not mean equal hashes.")
    b.raw.selectHistorical(alias); b.expectAppliedObjects(alias.stored)
    assertEquals(1, b.poll().primaryAcknowledged); b.assertReleased(); b.assertNoAuthority(); b.assertExpectedAppliedObjects()
    assertAllHistoryTransfer(before, b.counters(), use = charge.applied, refund = charge.content, observation = true)
    assertEquals(pending, terminalCatalogAllPrimaryRows(b.observer, b.scope).filterKeys { it in pending.keys }, "Alias cannot complete or VERIFY primary N/P, even xmin.")
    assertEquals(if (b.verifiedPublication) "VERIFIED" else "PREPARED", a.publication()["state"])
    assertEquals("AUTHORIZED_DELETE", b.receipt()["state"]); assertEquals(b.proofBeforeQueue, b.publicationProof())
    assertEquals(reservation, reservationIdentity())
    if (completePrimary) {
        val erased = terminalCatalogAllPrimaryRows(b.observer, b.scope).filterKeys {
            it in setOf("app_installations", "complaint_installation_ids", "complaint_resource_ids")
        }
        val aliasApplied = terminalCatalogAllPrimaryRows(b.observer, b.scope).getValue("complaint_deletion_journal_applied").single()
        val paid = b.counters()
        b.raw.selectOriginal(); b.expectAppliedObjects(alias.stored, b.record.stored)
        assertEquals(1, b.poll().primaryAcknowledged); b.assertReleased(); b.assertNoAuthority(); b.assertExpectedAppliedObjects()
        assertAllHistoryTransfer(paid, b.counters(), use = charge.appliedEvent + charge.audit)
        assertEquals(erased, terminalCatalogAllPrimaryRows(b.observer, b.scope).filterKeys { it in erased.keys }, "Original primary completion preserves earlier domain rows/xmin and D+192h.")
        assertTrue(aliasApplied in terminalCatalogAllPrimaryRows(b.observer, b.scope).getValue("complaint_deletion_journal_applied"))
        assertEquals(reservation, reservationIdentity()); assertEquals(b.receiptBeforeQueue, b.receiptIdentity())
        if (b.verifiedPublication) assertEquals(b.proofBeforeQueue, b.publicationProof())
        assertEquals("COMPLETED", b.receipt()["state"]); assertEquals("APPLIED", a.publication()["state"])
    }
    assertEquals("SETTLED", b.observation()?.get("state"))
    assertEquals(native, a.native.counts()); assertArrayEquals(originalWire, b.record.stored.bytes); originalWire.fill(0)
    return alias
}

/** Explicit missing-row/restored-domain INPUTS, not a consistent backup, another AUTH or seeded
 * success. Only B's fresh original native GET/decrypt/APPLY commits the recovered family. */
private fun authorAllRecoveryHistory(b: TestActiveOwnerDeleteQueueFixtureV1, history: TerminalCatalogAllRecoveryHistoryV1) {
    require(history in setOf(TerminalCatalogAllRecoveryHistoryV1.RECONSTRUCTED_PUBLICATION_AND_RECEIPTS, TerminalCatalogAllRecoveryHistoryV1.LATER_DOMAIN_AND_RESOURCE))
    val a = b.precursor; val charge = TerminalCatalogAllLiteralChargesV1
    val originalNative = a.native.counts(); val originalWire = b.record.stored.bytes.copyOf()
    val credential = b.observer.queryForMap("SELECT platform, owner_reference, last_authenticated_at, secret_verifier FROM app_installations WHERE id = ?", a.actor.id)
    val content = checkNotNull(b.observer.queryForObject("SELECT to_jsonb(c)::text FROM complaints c WHERE owner_id = ?", String::class.java, a.actor.id))
    val before = b.counters()
    if (history === TerminalCatalogAllRecoveryHistoryV1.RECONSTRUCTED_PUBLICATION_AND_RECEIPTS) {
        b.adversarialTransaction { jdbc ->
            listOf("installation_deletion_receipts", "complaint_recovery_capacity_reservations", "complaint_journal_publications").forEach { table ->
                assertEquals(1, jdbc.update("DELETE FROM $table WHERE data_scope_id = ?", b.scope))
            }
        }
        assertEquals(before, b.counters(), "The cut does not refund the old unrepresented aggregate Y or actual charges.")
    }
    assertEquals(1, b.poll().primaryAcknowledged); b.assertReleased(); b.assertNoAuthority()
    val rebuilt = history === TerminalCatalogAllRecoveryHistoryV1.RECONSTRUCTED_PUBLICATION_AND_RECEIPTS
    assertAllHistoryTransfer(before, b.counters(), use = charge.applied, refund = charge.content, observation = true,
        actual = if (rebuilt) charge.receipt + charge.publication + charge.reservation else ComplaintCapacityVector.ZERO,
        reserve = if (rebuilt) charge.promise else ComplaintCapacityVector.ZERO)
    val primary = terminalCatalogAllPrimaryRows(b.observer, b.scope).filterKeys {
        it in setOf("installation_deletion_receipts", "complaint_journal_publications", "complaint_deletion_journal_applied")
    }
    if (rebuilt) {
        assertEquals(true, b.observer.queryForObject("SELECT verified_at < created_at AND created_at <= applied_at " +
            "FROM complaint_journal_publications WHERE event_id = ?", Boolean::class.java, b.record.event.route.eventId))
        val originalReceipt = b.receipt()
        // Five paid summaries for ONE E: four actual missing-N repairs, never a reset of U.
        repeat(4) {
            val paid = b.counters()
            assertEquals(1, b.observer.update("DELETE FROM installation_deletion_receipts WHERE data_scope_id = ?", b.scope))
            assertEquals(paid, b.counters())
            assertEquals(1, b.poll().primaryAcknowledged); b.assertReleased(); b.assertNoAuthority()
            assertAllHistoryTransfer(paid, b.counters(), actual = charge.receipt, use = charge.audit)
            for (field in listOf("authorized_at", "completed_at", "expires_at", "external_event_id", "external_object_version")) {
                assertEquals(originalReceipt[field], b.receipt()[field], field)
            }
            assertEquals(primary - "installation_deletion_receipts", terminalCatalogAllPrimaryRows(b.observer, b.scope).filterKeys {
                it in setOf("complaint_journal_publications", "complaint_deletion_journal_applied")
            })
        }
        assertEquals(5L, b.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED'",
            Long::class.java, b.scope))
    } else {
        val later = UUID.randomUUID()
        b.adversarialTransaction { jdbc ->
            // Preserve the genuine verifier; restore only the earlier pair's public input fields.
            assertEquals(1, jdbc.update("UPDATE complaint_installation_ids SET state = 'ACTIVE', terminal_at = NULL WHERE id = ? AND state = 'DELETED'", a.actor.id))
            assertEquals(1, jdbc.update("UPDATE app_installations SET state = 'ACTIVE', credential_version = ?, platform = ?, owner_reference = ?, " +
                "last_authenticated_at = ?, deleted_at = NULL, verifier_expires_at = NULL WHERE id = ? AND state = 'DELETED'",
                b.record.event.tuple.credentialVersion, credential["platform"], credential["owner_reference"], credential["last_authenticated_at"], a.actor.id))
            assertEquals(1, jdbc.update("DELETE FROM complaint_resource_ids WHERE id = ? AND data_scope_id = ?", b.record.event.complaintIds().single(), b.scope))
            val paidInput = charge.resource + charge.content
            ComplaintCapacityCounter.entries.filter { paidInput[it] > 0 }.sortedBy { it.storedName }.forEach { counter ->
                assertEquals(1, jdbc.update("UPDATE complaint_capacity_counters SET free_units = free_units - ?, actual_units = actual_units + ? " +
                    "WHERE name = ? AND free_units >= ?", paidInput[counter], paidInput[counter], counter.storedName, paidInput[counter]))
            }
            assertEquals(1, jdbc.update("INSERT INTO complaint_resource_ids(id,data_scope_id,test_only,state,created_at) VALUES (?, ?, true, 'LIVE', clock_timestamp())", later, b.scope))
            assertEquals(1, jdbc.update("INSERT INTO complaints SELECT r.* FROM jsonb_populate_record(NULL::complaints, ?::jsonb || " +
                "jsonb_build_object('id', ?::uuid, 'version', 17)) r", content, later))
        }
        val paid = b.counters()
        assertEquals(1, b.poll().primaryAcknowledged); b.assertReleased(); b.assertNoAuthority()
        assertAllHistoryTransfer(paid, b.counters(), use = charge.resource + charge.audit.scaled(2), refund = charge.content)
        assertEquals(primary, terminalCatalogAllPrimaryRows(b.observer, b.scope).filterKeys { it in primary.keys }, "Repair never rewrites original N/P/E/VERIFY or N's expiry.")
        assertEquals(0L, b.observer.queryForObject("SELECT count(*) FROM complaints WHERE owner_id = ?", Long::class.java, a.actor.id))
    }
    assertArrayEquals(credential["secret_verifier"] as ByteArray,
        b.observer.queryForObject("SELECT secret_verifier FROM app_installations WHERE id = ?", ByteArray::class.java, a.actor.id))
    assertEquals(originalNative, a.native.counts()); assertArrayEquals(originalWire, b.record.stored.bytes); originalWire.fill(0)
    assertEquals("SETTLED", b.observation()?.get("state")); assertEquals(1L, b.count("complaint_deletion_journal_applied"))
}

internal fun assertAllHistoryTransfer(before: Map<ComplaintCapacityCounter, DeleteAllCounter>, after: Map<ComplaintCapacityCounter, DeleteAllCounter>,
    actual: ComplaintCapacityVector = ComplaintCapacityVector.ZERO, reserve: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
    use: ComplaintCapacityVector, refund: ComplaintCapacityVector = ComplaintCapacityVector.ZERO, observation: Boolean = false) {
    assertEquals(22, after.size)
    before.forEach { (counter, old) ->
        val observed = if (observation && counter === ComplaintCapacityCounter.STORAGE_BYTES) 8192L else 0L
        assertEquals(old.copy(free = old.free - actual[counter] - reserve[counter] + refund[counter] - observed,
            actual = old.actual + actual[counter] + use[counter] - refund[counter] + observed,
            recovery = old.recovery + reserve[counter] - use[counter]), after.getValue(counter), counter.storedName)
    }
}

/** C stops at the actual ordinary D closeout; no E erasure files or successor authority are borrowed. */
internal fun drainRetainedAllHistory(f: TestRunPurgeFixtureV1, a: TestRegisteredInitialCheckpointDeletionFixtureV1,
    inputs: TestOrdinaryDrainFixtureInputsV1, probe: CatalogTerminalHistoryDrainProbeV1, used: ComplaintCapacityVector,
    historical: TestActiveQueueHistoricalAllObjectV1? = null) {
    val retained = terminalCatalogAllPrimaryRows(f.observer, f.scope)
    val recovery = terminalCatalogAllRecoveryWithoutState(f.observer, f.scope)
    val native = CatalogTerminalOriginalOrdinaryHttpV1(a.process.consumers.journalConfiguration, checkNotNull(a.record), probe::assertReleased, historical)
    val drain = probe.begin(native::client, native.keys::httpClient)
    val approval = activeHistoryOrdinaryApproval(f, inputs, drain); val raw = f.rawEvidence
    try {
        terminalCatalogHistoryBoundaries(f, probe::assertReleased) {
            assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                drain.drain(approval, raw, AwsJournalKmsFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS))
        }
        probe.assertReleased(); probe.assertPrimaryApplied(expected = false); probe.assertAllResidualConversion(used); native.assertReadPairs(2, recovery = true)
        probe.assertRetainedInventoryRecovery(*(listOf(checkNotNull(a.record).stored) + listOfNotNull(historical?.stored)).toTypedArray())
        assertEquals(retained, terminalCatalogAllPrimaryRows(f.observer, f.scope))
        assertEquals(recovery, terminalCatalogAllRecoveryWithoutState(f.observer, f.scope))
        assertEquals("CONVERTED", f.observer.queryForObject("SELECT state FROM complaint_recovery_capacity_reservations WHERE event_id = ?",
            String::class.java, checkNotNull(a.event).route.eventId))
    } finally { approval.fill(0); raw.forEach { it.fill(0) }; native.assertClosed() }
}

internal fun terminalCatalogAllPrimaryRows(jdbc: JdbcTemplate, scope: UUID): Map<String, List<String>> = listOf(
    "installation_deletion_receipts", "complaint_journal_publications", "complaint_deletion_journal_applied",
    "app_installations", "complaint_installation_ids", "complaint_resource_ids",
).associateWith { table -> jdbc.queryForList("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t " +
    "WHERE data_scope_id = ?" + (if (table == "complaint_journal_publications" || table == "complaint_deletion_journal_applied") " AND event_kind = 'OWNER_DELETE_ALL'" else "") +
    " ORDER BY to_jsonb(t)::text", String::class.java, scope) }

/** Full comparison preimage, including counters/audit xmin. No comparison result issues authority. */
internal fun terminalCatalogAllComparisonRows(jdbc: JdbcTemplate, scope: UUID): Map<String, List<String>> =
    terminalCatalogAllPrimaryRows(jdbc, scope) + listOf("complaint_recovery_capacity_reservations", "complaints", "complaint_test_runs",
        "complaint_test_active_seal_intents", "complaint_test_active_queue_observations", "complaint_journal_scan_runs", "complaint_journal_scan_entries",
        "complaint_test_terminal_intents").associateWith { table -> jdbc.queryForList(
        "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text", String::class.java, scope) } + mapOf(
        "audit" to jdbc.queryForList("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM audit_log t WHERE complaint_data_scope_id = ? ORDER BY id", String::class.java, scope),
        "counters" to jdbc.queryForList("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_capacity_counters t ORDER BY ordinal", String::class.java),
        "controls" to jdbc.queryForList("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_journal_control t ORDER BY data_scope_id", String::class.java),
    )

private fun terminalCatalogAllRecoveryWithoutState(jdbc: JdbcTemplate, scope: UUID): String = checkNotNull(jdbc.queryForObject(
    "SELECT (to_jsonb(l) - 'state')::text FROM complaint_recovery_capacity_reservations l WHERE data_scope_id = ?", String::class.java, scope))

internal fun assertRetainedAllQueuePrimary(b: TestActiveOwnerDeleteQueueFixtureV1, recoveryState: String) {
    assertEquals(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, b.family)
    val a = b.precursor; val record = b.record
    val p = b.observer.queryForMap("SELECT * FROM complaint_journal_publications WHERE data_scope_id = ? AND event_id = ?", b.scope, record.event.route.eventId)
    val n = b.receipt()
    assertEquals("APPLIED", p["state"]); assertEquals("COMPLETED", n["state"])
    assertEquals(b.receiptBeforeQueue, b.receiptIdentity())
    if (b.verifiedPublication) assertEquals(b.proofBeforeQueue, b.publicationProof(), "B preserves the original SQL VERIFY when one exists.")
    val canonical = record.event.canonicalBytes()
    try { assertArrayEquals(canonical, p["event_bytes"] as ByteArray) } finally { canonical.fill(0) }
    assertEquals(record.stored.key, p["object_key"]); assertEquals(record.stored.version, p["object_version"])
    assertEquals(Sha256.hex(record.stored.bytes), HexFormat.of().formatHex(p["ciphertext_hash"] as ByteArray))
    assertEquals(Sha256.hex(p["verification_bytes"] as ByteArray), HexFormat.of().formatHex(p["verification_hash"] as ByteArray))
    assertEquals(record.stored.lastModified, (p["object_created_at"] as Timestamp).toInstant())
    assertEquals(record.stored.retainUntil, (p["retain_until"] as Timestamp).toInstant())
    assertEquals(p["created_at"], n["authorized_at"]); assertEquals(p["applied_at"], n["completed_at"])
    assertEquals(record.event.route.eventId, n["external_event_id"]); assertEquals(2L, n["external_epoch"])
    assertEquals(record.stored.version, n["external_object_version"]); assertArrayEquals(p["ciphertext_hash"] as ByteArray, n["external_ciphertext_hash"] as ByteArray)
    val at = (n["completed_at"] as Timestamp).toInstant()
    assertEquals(at.plus(Duration.ofHours(192)), (n["expires_at"] as Timestamp).toInstant())
    val credential = b.observer.queryForMap("SELECT state, credential_version, deleted_at, verifier_expires_at FROM app_installations WHERE id = ?", a.actor.id)
    assertEquals("DELETED", credential["state"]); assertEquals(record.event.tuple.credentialVersion + 1L, credential["credential_version"])
    assertEquals(at, (credential["deleted_at"] as Timestamp).toInstant())
    assertEquals(at.plus(Duration.ofHours(192)), (credential["verifier_expires_at"] as Timestamp).toInstant())
    assertEquals(b.credentialIdentityBeforeQueue, b.credentialIdentity(), "Queue never replaces the enrolled verifier or identity.")
    val applied = b.observer.queryForMap("SELECT * FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", b.scope)
    assertEquals(record.event.route.eventId, applied["event_id"]); assertEquals(record.stored.key, applied["object_key"])
    assertEquals(record.stored.version, applied["object_version"]); assertArrayEquals(p["ciphertext_hash"] as ByteArray, applied["ciphertext_hash"] as ByteArray)
    assertEquals(p["applied_at"], applied["applied_at"])
    val l = b.observer.queryForMap("SELECT state, reserved_amounts::text, converted_amounts::text, converted_at FROM complaint_recovery_capacity_reservations " +
        "WHERE data_scope_id = ? AND event_id = ?", b.scope, record.event.route.eventId)
    assertEquals(recoveryState, l["state"]); assertEquals(p["applied_at"], l["converted_at"])
    assertEquals(TerminalCatalogAllLiteralChargesV1.promise.toLongArray().joinToString(",", "{", "}"), l["reserved_amounts"])
    assertEquals(TerminalCatalogAllLiteralChargesV1.applied.toLongArray().joinToString(",", "{", "}"), l["converted_amounts"])
    val audits = b.observer.queryForMap("SELECT created_at, complaint_actor_kind, (actor_user_id IS NULL AND entity_type = 'complaint_scope' AND entity_id = ? " +
        "AND detail = jsonb_build_object('eventId', ?::text, 'removed', 1, 'reconstructed', 0, 'installation', 0)) AS exact " +
        "FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED'", b.scope.toString(), record.event.route.eventId, b.scope)
    assertEquals("SYSTEM", audits["complaint_actor_kind"]); assertEquals(p["applied_at"], audits["created_at"])
    assertEquals(true, audits["exact"])
    assertEquals(0L, b.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_INSTALLATION_DELETED'", Long::class.java, b.scope))
}

/** Shared actual post-drain producers; accepts an original drain, never a supplied completed cut. */
private fun withDrainedActiveHistoryCatalog(f: TestRunPurgeFixtureV1, inputs: TestOrdinaryDrainFixtureInputsV1,
    drain: TestRunOrdinaryDrainV1, ordinaryApproval: ByteArray, history: Map<String, List<String>>,
    ordinaryRecord: TestRegisteredInitialDeletionNativeRecordV1? = null, action: (CatalogTestRunTerminalFixtureV1) -> Unit) {
    val preparation = TestRunInstallationManifestV1.begin(drain)
    TestInstallationManifestSqlProbeV1(f, expectedDrain = drain).use { probe ->
        probe.original = preparation
        assertEquals(TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK, preparation.prepare())
    }
    val manifest = preparation.beginPublication()
    assertEquals(TestRunInstallationManifestPublicationResultV1.ALL_CHUNKS_AUTHENTICATED_AND_VERIFIED, manifest.publish())
    assertEquals(1L, manifest.authenticatedSummary().installationCount); assertEquals(1, manifest.capturedSource().count)
    val purge = TestRunPurgeSqlProbeV1(f).use { probe ->
        val value = manifest.beginPurgePublication().also { probe.original = it }
        terminalCatalogHistoryBoundaries(f, { probe.assertReleased(requireCommitted = false) }) {
            assertEquals(TestRunPurgePublicationResultV1.PURGE_AUTHENTICATED_AND_VERIFIED, value.publish())
        }
        probe.assertReleased(); f.assertReleased(); value
    }
    val seal = TestTerminalEpochSealSqlProbeV1(f).use { probe ->
        val value = purge.beginTerminalEpochSeal().also { probe.original = it }
        terminalCatalogHistoryBoundaries(f, { probe.assertReleased(requireCommitted = false) }) {
            assertEquals(TestRunTerminalEpochSealResultV1.TERMINAL_EPOCH_AUTHENTICATED_AND_SEALED, value.seal())
        }
        probe.assertReleased(); f.assertReleased(); value
    }
    TestTerminalQuiescenceSqlProbeV1(f).use { probe ->
        val d = seal.beginTerminalQuiescence().also { probe.original = it }
        val terminalInputs = checkNotNull(inputs.terminalQuiescence)
        val approval = terminalInputs.approval(terminalInputs.statement(d)) // One exact PSS input retained through E.
        val raw = terminalInputs.rawEvidence
        try {
            terminalCatalogHistoryBoundaries(f, { probe.assertReleased(requireCommitted = false) }) {
                assertEquals(TestRunTerminalQuiescenceResultV1.TERMINAL_PREFIX_QUIESCENT_AND_SEALED, d.quiesce(approval, raw))
            }
            probe.assertReleased(); f.assertReleased(); assertEquals(history, terminalCatalogActiveRows(f))
            CatalogTestRunTerminalFixtureV1(f, d, approval, raw, ordinaryApproval, ordinaryRecord).use(action)
        } finally { approval.fill(0); raw.forEach { it.fill(0) } }
    }
}

internal class CatalogTestRunTerminalActiveHistoryFixtureV1(
    val catalog: CatalogTestRunTerminalFixtureV1,
    val create: TestRegisteredInitialCheckpointCreateFixtureV1,
    val deletion: TestRegisteredInitialCheckpointDeletionFixtureV1? = null,
    val queue: TestActiveOwnerDeleteQueueFixtureV1? = null,
) {
    val initialSeal = catalog.record.sealSet.records().first()
    fun historyRows(): Map<String, List<String>> = terminalCatalogActiveRows(catalog.f)
    fun preservedRows(): Map<String, List<String>> = CatalogTestRunTerminalCasesV1.preservedRows(catalog) + historyRows() + (deletion?.image() ?: emptyMap())
    fun fullImage(): Map<String, List<String>> = CatalogTestRunTerminalCasesV1.fullImage(catalog) + historyRows() + (deletion?.image() ?: emptyMap())
}

/** Exact row+xmin observations, not a current-history admission or recovery capability. */
private fun terminalCatalogActiveRows(f: TestRunPurgeFixtureV1): Map<String, List<String>> = mapOf(
    "V26" to "complaint_test_active_seal_intents", "V29" to "complaint_test_active_queue_observations",
).mapValues { (_, table) -> f.observer.queryForList("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t " +
    "WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text", String::class.java, f.scope) }

/** Input signing only, distinct from the legacy helper's asserted no-A 1..1 history. */
private fun activeHistoryOrdinaryApproval(f: TestRunPurgeFixtureV1, inputs: TestOrdinaryDrainFixtureInputsV1,
    original: TestRunOrdinaryDrainV1): ByteArray {
    val journal = f.registration.process.consumers.journalConfiguration
    val pin = inputs.authorityInput(journal, f.registration.process.catalogReadback.chainPolicy.trustBundlePolicy.expectedEnvironment)
    val role = journal.declaration().authorities.ordinary; val context = original.runContext
    val at = Instant.now().minusSeconds(2).epochSecond; val raw = f.rawEvidence
    fun digest(value: ByteArray) = TestTerminalEvidenceDigestV1(Sha256.hex(value), value.size.toLong())
    return try { inputs.approval(TestOrdinaryDenialStatementV1(1, pin.purpose, pin.minimumApprovalVersion, pin.authorityGrant,
        pin.implementationAcceptance, pin.evidenceRetentionPolicy, pin.environment, context.dataScopeId,
        context.activationCatalogGeneration, context.activationCatalogSha256, context.configurationSha256, context.terminalEncodingSha256,
        f.registration.process.catalogActivation.initialWriterRegistrySha256, pin.writerGeneration, pin.databaseIdentity, pin.restoreIdentity,
        1, 2, pin.bucket, pin.accountId, pin.region, journal.ordinaryPrefix, role.roleId,
        TestTerminalPolicyRefV1(role.policy.policyId, role.policy.version, role.policy.sha256), at, at, 1, 0,
        f.sealHttp.horizon.epochSecond, digest(raw[1]), listOf(TestOrdinaryDeniedPathV1("synthetic-terminal-active-history-path", role.roleId, at, at, digest(raw[0])))), pin.keyId)
    } finally { raw.forEach { it.fill(0) } }
}

private fun <T> terminalCatalogHistoryBoundaries(f: TestRunPurgeFixtureV1, check: () -> Unit, action: () -> T): T {
    val boundary = f.sealHttp.boundary; val close = f.sealHttp.nativeBoundary
    f.sealHttp.boundary = { boundary(); check() }; f.sealHttp.nativeBoundary = { close(); check() }
    return try { action() } finally { f.sealHttp.boundary = boundary; f.sealHttp.nativeBoundary = close }
}

/**
 * Read-only raw transport over the original A object/private responder, optionally plus ONE
 * separate labeled protocol-history object from B's frozen seam. D/E or a registered primary open their own readers;
 * this never reclassifies history as A's PUT, exports a key or supplies a readback/cleanup result.
 */
internal class CatalogTerminalOriginalOrdinaryHttpV1(private val journal: TestOwnerDeleteJournalConfigurationV1,
    private val record: TestRegisteredInitialDeletionNativeRecordV1, private val boundary: () -> Unit,
    private val historical: TestActiveQueueHistoricalAllObjectV1? = null) {
    private val objects = (listOf(record.stored) + listOfNotNull(historical?.stored)).sortedWith(compareBy<JournalPublisherObject>({ it.key }, { it.version }))
    val requests = mutableListOf<JournalPublisherHttpRequest>()
    val keys = AwsJournalKmsFixture()
    private var created = 0
    private var closed = 0
    private var returnedCloses = 0
    private val assertion = AtomicReference<AssertionError?>()
    init {
        assertEquals(objects.size, objects.map { it.key to it.version }.distinct().size)
        historical?.let { assertNotEquals(record.kmsContext, it.kmsContext) }
        keys.beforePrepare = { checked { boundary() } }
        keys.onClientClose = { checked { boundary() } }
        keys.respond = { request -> checked {
            boundary(); signedKms(request)
            assertEquals(AwsJournalKmsFixture.DECRYPT_TARGET, request.target(), "Readback cannot generate another data key.")
            assertEquals(journal.declaration().encryption.keyArn, request.fields()["KeyId"].textValue())
            val context = request.fields()["EncryptionContext"].fields().asSequence().associate { it.key to it.value.textValue() }
            val reply = if (context == record.kmsContext) record.decrypt(request) else {
                val selected = checkNotNull(historical)
                assertEquals(selected.kmsContext, context)
                selected.decrypt(request) // B's exact immutable wrapped-token/context map, not a copied key or second codec.
            }
            reply.apply {
                beforeCall = { checked { boundary() } }; beforeRead = { checked { boundary() } }
                onAbort = { checked { boundary() } }; onClose = { checked { boundary() } }
            }
        } }
    }
    fun client(): SdkHttpClient = open(primaryOnly = false)
    /** Fresh registered PREPARED continuation reads only its original exact key, not the D/E prefix. */
    fun primaryClient(): SdkHttpClient = open(primaryOnly = true)
    private fun open(primaryOnly: Boolean): SdkHttpClient {
        checked { boundary() }; created++
        val selectedObjects = if (primaryOnly) listOf(record.stored) else objects
        val prefix = if (primaryOnly) record.stored.key else journal.ordinaryPrefix
        return journalPublisherRawHttpClient(requests, { checked { boundary() } }, {}, {
            closed++; checked { boundary() }; returnedCloses++
        }) { request -> checked {
            boundary()
            val location = journal.declaration().journalLocation
            journalPublisherRawAssertSigned(request, location.region, location.accountId, AwsJournalKmsFixture.CREDENTIALS)
            assertTrue(request.body.isEmpty())
            when (request.kind) {
                "LIST" -> {
                    assertEquals(listOf(prefix), request.http.rawQueryParameters()["prefix"])
                    assertEquals(listOf("2"), request.http.rawQueryParameters()["max-keys"])
                    assertTrue(request.http.rawQueryParameters().keys.none { it in setOf("key-marker", "version-id-marker") })
                    OwnerDeleteAllJournalPublisherFixture.xmlReply(journalPublisherRawListDocument(location.bucket, prefix, selectedObjects))
                }
                "GET" -> {
                    val stored = selectedObjects.single { request.http.encodedPath() == "/${location.bucket}/${it.key}" && request.http.rawQueryParameters()["versionId"] == listOf(it.version) }
                    assertEquals("/${location.bucket}/${stored.key}", request.http.encodedPath())
                    assertEquals(listOf(stored.version), request.http.rawQueryParameters()["versionId"])
                    journalPublisherRawGetReply(location.region, stored.copy(bytes = stored.bytes.copyOf()))
                }
                else -> error("Original readback permits only the selected LIST and exact-version GET, never PUT.")
            }
        } }
    }
    fun assertPrimaryReadback() {
        assertEquals(listOf("LIST", "GET"), requests.map { it.kind })
        assertEquals(listOf(record.stored.key), requests.first().http.rawQueryParameters()["prefix"])
        assertEquals(listOf(record.stored.version), requests.last().http.rawQueryParameters()["versionId"])
        assertArrayEquals(record.stored.bytes, checkNotNull(requests.last().reply).bytes)
        val request = keys.requests.single()
        assertEquals(AwsJournalKmsFixture.DECRYPT_TARGET, request.target())
        assertEquals(record.kmsContext, request.fields()["EncryptionContext"].fields().asSequence().associate { it.key to it.value.textValue() })
        assertEquals(1, created); assertEquals(1, keys.createdClients)
        assertClosed()
    }
    fun assertUnused() {
        assertTrue(requests.isEmpty() && keys.requests.isEmpty())
        assertEquals(0, created); assertEquals(0, keys.createdClients); assertClosed()
    }
    fun assertReadPairs(passes: Int, recovery: Boolean = false) {
        // D additionally rereads each PENDING scan version after the two complete inventories;
        // E's separate raw reader uses only its own inventory pairs (the unchanged default).
        val reads = passes + if (recovery) 1 else 0
        val scans = List(passes) { listOf("LIST") + List(objects.size) { "GET" } }.flatten()
        assertEquals(scans + (if (recovery) List(objects.size) { "GET" } else emptyList()), requests.map { it.kind })
        assertEquals(reads * objects.size, keys.requests.size)
        keys.requests.forEach { assertEquals(AwsJournalKmsFixture.DECRYPT_TARGET, it.target()) }
        objects.forEach { stored ->
            val exact = requests.filter { it.kind == "GET" && it.http.encodedPath().endsWith("/${stored.key}") && it.http.rawQueryParameters()["versionId"] == listOf(stored.version) }
            assertEquals(reads, exact.size); exact.forEach { assertArrayEquals(stored.bytes, checkNotNull(it.reply).bytes) }
        }
        assertClosed()
    }
    fun assertClosed() {
        requireConnectionFree(); assertion.get()?.let { throw it }
        assertEquals(created, closed); assertEquals(created, returnedCloses)
        assertEquals(keys.createdClients, keys.closedClients); assertEquals(keys.createdClients, keys.returnedClientCloses)
        requests.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertTrue(it.responseReturned); assertEquals(1, checkNotNull(it.reply).closes) }
        keys.replies.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertEquals(1, it.closes) }
    }
    private fun signedKms(request: JournalKmsHttpRequest) {
        val http = request.http; val region = journal.declaration().journalLocation.region; val credentials = AwsJournalKmsFixture.CREDENTIALS
        assertEquals("https", http.protocol()); assertEquals("kms.$region.amazonaws.com", http.host())
        assertEquals(credentials.sessionToken(), http.firstMatchingHeader("x-amz-security-token").orElseThrow())
        val authorization = http.firstMatchingHeader("Authorization").orElseThrow()
        val scope = authorization.substringAfter("Credential=${credentials.accessKeyId()}/").substringBefore(',')
        val signed = authorization.substringAfter("SignedHeaders=").substringBefore(',').split(';')
        val headers = signed.joinToString("") { name -> "$name:${http.firstMatchingHeader(name).orElseThrow().trim().replace(Regex("[ \\t]+"), " ")}\n" }
        assertTrue(http.rawQueryParameters().isEmpty())
        val canonical = "${http.method()}\n${http.encodedPath().ifEmpty { "/" }}\n\n$headers\n${signed.joinToString(";")}\n${Sha256.hex(request.json.toByteArray())}"
        val date = http.firstMatchingHeader("x-amz-date").orElseThrow()
        assertEquals("${date.take(8)}/$region/kms/aws4_request", scope)
        fun hmac(key: ByteArray, text: String) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(text.toByteArray()) }
        val day = hmac(("AWS4" + credentials.secretAccessKey()).toByteArray(), date.take(8))
        val regional = hmac(day, region); val service = hmac(regional, "kms"); val signing = hmac(service, "aws4_request")
        try { assertEquals(HexFormat.of().formatHex(hmac(signing, "AWS4-HMAC-SHA256\n$date\n$scope\n${Sha256.hex(canonical.toByteArray())}")), authorization.substringAfter("Signature=")) }
        finally { day.fill(0); regional.fill(0); service.fill(0); signing.fill(0) }
    }
    private fun <T> checked(action: () -> T): T = try { action() } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }
}
