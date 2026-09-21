package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.catalog.ComplaintTestColdRecoveryProcessCasesV1
import me.manga.kira.backend.complaint.catalog.ComplaintTestNamespaceRecoveryRegistrationCasesV1
import me.manga.kira.backend.complaint.catalog.TestActiveHistoryTerminalCasesV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainAccountingCasesV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainAuthorityCutV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainCommitStepV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainFailureCasesV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainLargeRecycleCasesV1
import me.manga.kira.backend.complaint.catalog.TestRegistrationCompletionCut
import me.manga.kira.backend.complaint.catalog.TestRecoveryRegistrationIdentityCutV1
import me.manga.kira.backend.complaint.catalog.TestRecoveryRegistrationPhaseCutV1
import me.manga.kira.backend.complaint.catalog.TestRecoveryRegistrationRawCutV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.ResultSet
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * Real registered four-key OWNER_DELETE closeout, retained native objects and owned PostgreSQL TLS.
 * Synthetic authority inputs test code behavior, not installed denial, approved bound or deployment.
 * The explicitly SQL-only 104-row case below does NOT prove producer-level partial-recycle takeover.
 * The separate thirteen-primary case authors 52 native objects/two real scans for that connection.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestOrdinaryDrainConnectedIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestOrdinaryDrainConnectedIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    // Source-only A/V26 connection cases; no execution/qualification is claimed by their addition.
    @Test
    fun genuineActiveSealHistoryReachesDrainManifestPurgeTerminalAndFullQuiescentSealSet() = withActiveHistoryFixture {
        TestActiveHistoryTerminalCasesV1.successful(it)
    }

    @Test
    fun activeHistoryChangedGlobalGenerationCannotCloseOpenGatesOrPaySealedAudit() = withActiveHistoryFixture {
        TestActiveHistoryTerminalCasesV1.currentGlobalDrift(it, closedRetry = false)
    }

    @Test
    fun activeHistoryChangedGlobalHashCannotReadmitClosedSealerOrBeginDrain() = withActiveHistoryFixture {
        TestActiveHistoryTerminalCasesV1.currentGlobalDrift(it, closedRetry = true)
    }

    @Test
    fun activeHistoryMissingRetainedNativeVersionCannotRecreateAOrPublishSuccessor() = withActiveHistoryFixture {
        TestActiveHistoryTerminalCasesV1.changedNativeHistory(it, missing = true)
    }

    @Test
    fun activeHistoryChangedRetainedNativeVersionCannotRecreateAOrPublishSuccessor() = withActiveHistoryFixture {
        TestActiveHistoryTerminalCasesV1.changedNativeHistory(it, missing = false)
    }

    @Test
    fun activeHistoryMissingAInSecondNativeInventoryCannotWitnessOrRecycleD() = withActiveHistoryFixture {
        TestActiveHistoryTerminalCasesV1.secondInventoryHistoryFault(it, missing = true)
    }

    @Test
    fun activeHistoryExtraAVersionInSecondNativeInventoryCannotWitnessOrRecycleD() = withActiveHistoryFixture {
        TestActiveHistoryTerminalCasesV1.secondInventoryHistoryFault(it, missing = false)
    }

    @Test
    fun activeHistoryChangedCurrentGlobalGenerationCannotStartDNativeReadback() = withActiveHistoryFixture {
        TestActiveHistoryTerminalCasesV1.currentGlobalDriftAtD(it)
    }

    @Test
    fun fourRetainedKeysReachPaidCutActualRecoveryExactConversionRecycleAndNativeOrdinarySeal() = withFixture {
        TestOrdinaryDrainAccountingCasesV1.fourKeyPaidCloseout(it)
    }

    @Test
    fun preparedPrimaryReallyPublishesVerifiesAndAppliesBeforeDenialRangeCapture() = withFixture {
        TestOrdinaryDrainAccountingCasesV1.preparedPrimaryPrecedesCapture(it)
    }

    @Test
    fun freshOriginalReadmitsPaidCutWithoutSecondTransferOrSealPut() = withFixture {
        TestOrdinaryDrainAccountingCasesV1.paidReplayHasNoSecondTransfer(it, hideStoredSeal = false)
    }

    @Test
    fun missingStoredSealVersionRefusesWithoutRecreatingOrRepayingIt() = withFixture {
        TestOrdinaryDrainAccountingCasesV1.paidReplayHasNoSecondTransfer(it, hideStoredSeal = true)
    }

    @Test
    fun thirteenPrimaryFamiliesCommitFirstHundredRecycleRowsThenFreshOriginalFinishesFourWithoutDoublePayment() = withFixture {
        TestOrdinaryDrainLargeRecycleCasesV1.committedPartialRecycle(it)
    }

    // Source-only additions: fresh actual root in one JVM, not separate-process qualification.
    @Test
    fun coldTestRecoveryFreshRootRegistersSealedCurrentStateWithoutProjectOrMutationAdmission() = withFixture {
        ComplaintTestNamespaceRecoveryRegistrationCasesV1.freshRootContinuesWithoutReplayingProjection(it, paidCut = false)
    }

    @Test
    fun coldTestRecoveryFreshRootReusesCommittedPaidCutWithoutSecondCharge() = withFixture {
        ComplaintTestNamespaceRecoveryRegistrationCasesV1.freshRootContinuesWithoutReplayingProjection(it, paidCut = true)
    }

    @Test
    fun coldTestRecoveryTwoJvmPreCutReassemblesRegistersAndDrainsWithoutProjectionOrEnrollment() =
        ComplaintTestColdRecoveryProcessCasesV1.qualify(paidCut = false)

    @Test
    fun coldTestRecoveryTwoJvmCommittedWitnessIsReadmittedWithoutRescanOrSecondCharge() =
        ComplaintTestColdRecoveryProcessCasesV1.qualify(paidCut = true)

    @Test
    fun coldTestRecoveryChangedFullDRefusesBeforeNativeReadback() = recoveryIdentity(TestRecoveryRegistrationIdentityCutV1.FULL_D)

    @Test
    fun coldTestRecoveryChangedDatabaseIdentityRefusesWithoutRepair() = recoveryIdentity(TestRecoveryRegistrationIdentityCutV1.DATABASE)

    @Test
    fun coldTestRecoveryChangedRestoreIdentityRefusesWithoutRepair() = recoveryIdentity(TestRecoveryRegistrationIdentityCutV1.RESTORE)

    @Test
    fun coldTestRecoveryChangedEventWriterRefusesWithoutRepair() = recoveryIdentity(TestRecoveryRegistrationIdentityCutV1.EVENT_WRITER)

    @Test
    fun coldTestRecoveryChangedCatalogWriterRefusesWithoutRepair() = recoveryIdentity(TestRecoveryRegistrationIdentityCutV1.CATALOG_WRITER)

    @Test
    fun coldTestRecoveryMissingRawReplicaCannotRegister() = withFixture {
        ComplaintTestNamespaceRecoveryRegistrationCasesV1.rawCopiesAreIndependentlyRequired(it, TestRecoveryRegistrationRawCutV1.MISSING_REPLICA)
    }

    @Test
    fun coldTestRecoveryChangedRawEnvelopeCannotRegister() = withFixture {
        ComplaintTestNamespaceRecoveryRegistrationCasesV1.rawCopiesAreIndependentlyRequired(it, TestRecoveryRegistrationRawCutV1.CHANGED_ENVELOPE)
    }

    @Test
    fun coldTestRecoveryUnattributedUnusedReserveCannotBeRepaid() = withFixture {
        ComplaintTestNamespaceRecoveryRegistrationCasesV1.currentReserveMismatchIsNotRepaid(it)
    }

    @Test
    fun coldTestRecoveryInstallationManifestProgressRemainsFailClosed() = withFixture {
        ComplaintTestNamespaceRecoveryRegistrationCasesV1.manifestProgressRemainsOutsideColdSlice(it)
    }

    @Test
    fun coldTestRecoveryCaptureRollbackCannotIssueOrReadProvider() = recoveryCompletion(TestRecoveryRegistrationPhaseCutV1.CAPTURE, TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun coldTestRecoveryCaptureNativeCommitUnknownRetainsOriginalCustody() = recoveryCompletion(TestRecoveryRegistrationPhaseCutV1.CAPTURE, TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)

    @Test
    fun coldTestRecoveryCaptureAcknowledgmentLossCannotIssueOrReadProvider() = recoveryCompletion(TestRecoveryRegistrationPhaseCutV1.CAPTURE, TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun coldTestRecoveryCaptureUnresolvedReleaseRetainsOriginalCustody() = recoveryCompletion(TestRecoveryRegistrationPhaseCutV1.CAPTURE, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)

    @Test
    fun coldTestRecoveryRecheckRollbackCannotIssueAfterRawReadback() = recoveryCompletion(TestRecoveryRegistrationPhaseCutV1.RECHECK, TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun coldTestRecoveryRecheckNativeCommitUnknownRetainsOriginalCustody() = recoveryCompletion(TestRecoveryRegistrationPhaseCutV1.RECHECK, TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)

    @Test
    fun coldTestRecoveryRecheckAcknowledgmentLossCannotIssue() = recoveryCompletion(TestRecoveryRegistrationPhaseCutV1.RECHECK, TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun coldTestRecoveryRecheckUnresolvedReleaseRetainsOriginalCustody() = recoveryCompletion(TestRecoveryRegistrationPhaseCutV1.RECHECK, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)

    @Test
    fun foreignDenialSignerCannotAuthorizeTheCapturedRange() = denied(TestOrdinaryDrainAuthorityCutV1.FOREIGN_SIGNER)

    @Test
    fun catalogOnlyDenialPurposeCannotAuthorizeTheCapturedRange() = denied(TestOrdinaryDrainAuthorityCutV1.WRONG_PURPOSE)

    @Test
    fun unregisteredDenialWriterCannotAuthorizeTheCapturedRange() = denied(TestOrdinaryDrainAuthorityCutV1.WRITER)

    @Test
    fun unrelatedDenialLineageCannotAuthorizeTheCapturedRange() = denied(TestOrdinaryDrainAuthorityCutV1.LINEAGE)

    @Test
    fun partialDenialEpochRangeCannotAuthorizeTheCapturedRange() = denied(TestOrdinaryDrainAuthorityCutV1.RANGE)

    @Test
    fun missingRawEffectivePathRefusesBeforeNativeInventoryOrSpend() = denied(TestOrdinaryDrainAuthorityCutV1.MISSING_PATH)

    @Test
    fun missingRawAcceptedRequestBoundRefusesBeforeNativeInventoryOrSpend() = denied(TestOrdinaryDrainAuthorityCutV1.MISSING_BOUND)

    @Test
    fun mutatedRawDenialEvidenceRefusesBeforeNativeInventoryOrSpend() = denied(TestOrdinaryDrainAuthorityCutV1.MUTATED_RAW)

    @Test
    fun witnessRollbackLeavesNoPaidCutAndFreshOriginalAbandonsUnwitnessedRows() =
        completion(TestOrdinaryDrainCommitStepV1.WITNESS, TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun witnessDeferredCommitFailureIsUnknownAndCannotDispatchRecovery() =
        completion(TestOrdinaryDrainCommitStepV1.WITNESS, TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)

    @Test
    fun witnessAcknowledgmentLossRetainsOnePaidCutForFreshOriginalRecovery() =
        completion(TestOrdinaryDrainCommitStepV1.WITNESS, TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun witnessUnresolvedReleaseQuarantinesTheOriginalWithoutNativeDispatch() =
        completion(TestOrdinaryDrainCommitStepV1.WITNESS, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)

    @Test
    fun conversionRollbackRetainsExactPartialResidualUntilFreshOriginalSettlement() =
        completion(TestOrdinaryDrainCommitStepV1.CONVERT, TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun conversionDeferredCommitFailureIsUnknownAndCannotRecycle() =
        completion(TestOrdinaryDrainCommitStepV1.CONVERT, TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)

    @Test
    fun conversionAcknowledgmentLossCannotTransferTheResidualTwice() =
        completion(TestOrdinaryDrainCommitStepV1.CONVERT, TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun conversionUnresolvedReleaseQuarantinesTheOriginalWithoutSealDispatch() =
        completion(TestOrdinaryDrainCommitStepV1.CONVERT, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)

    @Test
    fun wholeSmallRecycleBatchRollbackPreservesAllPaidAppliedRowsForRetry() =
        completion(TestOrdinaryDrainCommitStepV1.RECYCLE, TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun wholeSmallRecycleBatchDeferredFailureIsUnknownAndCannotSeal() =
        completion(TestOrdinaryDrainCommitStepV1.RECYCLE, TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)

    @Test
    fun wholeSmallRecycleBatchAcknowledgmentLossCannotRecreditThePoolTwice() =
        completion(TestOrdinaryDrainCommitStepV1.RECYCLE, TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun wholeSmallRecycleBatchUnresolvedReleaseCannotDispatchTheNativeSeal() =
        completion(TestOrdinaryDrainCommitStepV1.RECYCLE, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)

    @Test
    fun strictSealVerifyRollbackRequiresNativeReadbackButNeverASecondPut() =
        completion(TestOrdinaryDrainCommitStepV1.SEAL_VERIFY, TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun strictSealVerifyDeferredCommitFailureCannotReportSuccess() =
        completion(TestOrdinaryDrainCommitStepV1.SEAL_VERIFY, TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)

    @Test
    fun strictSealVerifyAcknowledgmentLossReplaysWithoutASecondPut() =
        completion(TestOrdinaryDrainCommitStepV1.SEAL_VERIFY, TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun strictSealVerifyUnresolvedReleaseCannotReportSuccess() =
        completion(TestOrdinaryDrainCommitStepV1.SEAL_VERIFY, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)

    @Test
    fun legacyCanonicalProgressWithoutItsPaidEnvelopeIsNotAdoptedOrBackfilled() = withFixture {
        TestOrdinaryDrainFailureCasesV1.legacyUnpaidProgressRefuses(it)
    }

    @Test
    fun staleFenceAfterExactRecoveryGetCannotSpendOrConvertAnything() = withFixture {
        TestOrdinaryDrainFailureCasesV1.staleFenceBeforeRecovery(it)
    }

    @Test
    fun syntacticallyLegalButUnattributedUsedVectorCannotBeConverted() = withFixture {
        TestOrdinaryDrainFailureCasesV1.inconsistentUsedVectorRefuses(it)
    }

    @Test
    fun synthetic104RowRecycleSqlBoundaryIsScopedBoundedExactAndNotProducerTakeover() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        val reader = ordinaryCleanupReader(database.value)
        Flyway.configure().dataSource(reader).locations("classpath:db/migration").load().migrate()
        reader.connection.use { connection ->
            val before = syntheticPublicImage(connection)
            connection.autoCommit = false
            try {
                connection.createStatement().use {
                    // LIKE retains the real CHECK/PK/index definitions, but intentionally no FK.
                    // Only a transaction-local shadow is seeded. No registered run, paid witness,
                    // native body or successful lifecycle state is manufactured in the real tables.
                    it.execute("CREATE TEMP TABLE complaint_journal_scan_entries " +
                        "(LIKE public.complaint_journal_scan_entries INCLUDING ALL) ON COMMIT DROP")
                }
                val scope = UUID.randomUUID()
                val foreignScope = UUID.randomUUID()
                val scan = UUID.randomUUID()
                val foreignScan = UUID.randomUUID()
                val writer = UUID.randomUUID()
                val expected = (1..2).flatMap { pass -> (0 until 52).map { index ->
                    SyntheticRecycleRow(scan, pass, scope, "synthetic-page/key-${index.toString().padStart(3, '0')}",
                        "opaque-page-version+$index/", "11".repeat(32), "22".repeat(32),
                        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { index.toByte() }), writer)
                } }
                val foreign = expected.take(3).map { it.copy(scan = foreignScan, scope = foreignScope) }
                (expected + foreign).asReversed().forEach { row ->
                    // Raw synthetic seed through the actual INSERT text; PENDING is deliberate.
                    // The real RECYCLE authority would reject these unauthenticated rows.
                    assertEquals(1, syntheticUpdate(connection, TestOrdinaryDrainSqlV1.insertEntry,
                        row.scan, row.pass, row.scope, row.key, row.version, unhex(row.ciphertext), unhex(row.semantic),
                        row.event, "OWNER_DELETE", row.writer, row.epoch, row.bytes))
                }
                val first = syntheticRows(connection, TestOrdinaryDrainSqlV1.recyclePage, scope)
                assertEquals(100, first.size, "Production unlocked discovery is bounded independently of the synthetic 104-row seed.")
                assertEquals(expected.take(100), first)
                assertEquals(foreign, syntheticRows(connection, TestOrdinaryDrainSqlV1.recyclePage, foreignScope))
                first.forEach { row ->
                    assertEquals(listOf(row), syntheticRows(connection, TestOrdinaryDrainSqlV1.exactRecycleEntry,
                        row.scan, row.pass, row.key, row.version))
                    assertEquals(1, syntheticDelete(connection, row))
                }
                assertEquals(0, syntheticDelete(connection, first.first()), "A deleted exact row cannot count twice.")
                val second = syntheticRows(connection, TestOrdinaryDrainSqlV1.recyclePage, scope)
                assertEquals(4, second.size)
                assertEquals(expected.drop(100), second)
                second.forEach { row ->
                    assertEquals(listOf(row), syntheticRows(connection, TestOrdinaryDrainSqlV1.exactRecycleEntry,
                        row.scan, row.pass, row.key, row.version))
                    assertEquals(1, syntheticDelete(connection, row))
                }
                assertTrue(syntheticRows(connection, TestOrdinaryDrainSqlV1.recyclePage, scope).isEmpty())
                assertEquals(foreign, syntheticRows(connection, TestOrdinaryDrainSqlV1.recyclePage, foreignScope))
                assertEquals(before, syntheticPublicImage(connection))
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
            assertEquals(before, syntheticPublicImage(connection), "No public scan/capacity/run/recovery bytes or xmin changed.")
        }
        // No batch commit, counters, run deletion, recredit or takeover occurred in this SQL-only
        // case. Actual producer-level committed partial recycle >100 rows remains separate work.
    }

    private fun denied(cut: TestOrdinaryDrainAuthorityCutV1) = withFixture {
        TestOrdinaryDrainFailureCasesV1.deniedAuthority(it, cut)
    }

    private fun recoveryIdentity(cut: TestRecoveryRegistrationIdentityCutV1) = withFixture {
        ComplaintTestNamespaceRecoveryRegistrationCasesV1.changedIdentityRefuses(it, cut)
    }

    private fun recoveryCompletion(phase: TestRecoveryRegistrationPhaseCutV1, cut: TestRegistrationCompletionCut) = withFixture {
        ComplaintTestNamespaceRecoveryRegistrationCasesV1.actualCompletionAndCleanupAreRequired(it, phase, cut)
    }

    private fun completion(step: TestOrdinaryDrainCommitStepV1, cut: TestRegistrationCompletionCut) = withFixture {
        TestOrdinaryDrainFailureCasesV1.completion(it, step, cut)
    }

    private fun withActiveHistoryFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use {
            it.bind()
            action(it)
        }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use {
            it.bind()
            action(it)
        }

    private fun syntheticUpdate(connection: Connection, sql: String, vararg arguments: Any?): Int =
        connection.prepareStatement(sql).use { statement ->
            arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }

    private fun syntheticDelete(connection: Connection, row: SyntheticRecycleRow): Int =
        syntheticUpdate(connection, TestOrdinaryDrainSqlV1.deleteEntry, row.scan, row.pass, row.scope, row.key, row.version,
            unhex(row.ciphertext), unhex(row.semantic), row.event, "OWNER_DELETE", row.writer, row.epoch, row.bytes, row.replay)

    private fun syntheticRows(connection: Connection, sql: String, vararg arguments: Any?): List<SyntheticRecycleRow> =
        connection.prepareStatement(sql).use { statement ->
            arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result -> buildList { while (result.next()) add(SyntheticRecycleRow.read(result)) } }
        }

    private fun syntheticPublicImage(connection: Connection): Map<String, List<String>> =
        listOf("complaint_journal_scan_entries", "complaint_journal_scan_runs", "complaint_capacity_counters",
            "complaint_test_runs", "complaint_recovery_capacity_reservations").associateWith { table ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text " +
                    "FROM public.$table t ORDER BY to_jsonb(t)::text").use { result -> buildList { while (result.next()) add(result.getString(1)) } }
            }
        }

    private fun unhex(value: String): ByteArray = HexFormat.of().parseHex(value)

    /** SQL locator only; deliberately not a production Entry, original or native readback. */
    private data class SyntheticRecycleRow(
        val scan: UUID, val pass: Int, val scope: UUID, val key: String, val version: String,
        val ciphertext: String, val semantic: String, val event: String, val writer: UUID,
        val epoch: Long = 1, val bytes: Long = 1, val replay: String = "PENDING",
    ) {
        companion object {
            fun read(row: ResultSet): SyntheticRecycleRow {
                assertTrue(row.getBoolean("test_only")); assertTrue(!row.wasNull())
                assertEquals("OWNER_DELETE", row.getString("event_kind"))
                return SyntheticRecycleRow(checkNotNull(row.getObject("scan_id", UUID::class.java)), row.getInt("pass"),
                    checkNotNull(row.getObject("data_scope_id", UUID::class.java)), row.getString("object_key"), row.getString("object_version"),
                    HexFormat.of().formatHex(row.getBytes("ciphertext_hash")), HexFormat.of().formatHex(row.getBytes("semantic_hash")),
                    row.getString("event_id"), checkNotNull(row.getObject("writer_generation", UUID::class.java)),
                    row.getLong("journal_epoch"), row.getLong("entry_bytes"), row.getString("replay_state"))
            }
        }
    }
}
