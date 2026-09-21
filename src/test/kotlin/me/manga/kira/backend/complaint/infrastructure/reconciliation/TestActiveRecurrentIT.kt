package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * SOURCE_ONLY / NOT_COMPILED / NOT_RUN / NOT_REVIEWED. Selectors for actual PG/TLS and substituted
 * raw HTTP only; no provider, deployment-cadence, dropped-COMMIT-reply or two-JVM qualification.
 * Split1 produces only ordinary recurrent history. Missing replay remains a private native input;
 * no split2 deferred reducer, catalog writer handoff, retirement or terminal authority is implied.
 * The ALL setup additionally needs the separately owned B readback-oracle correction composed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestActiveRecurrentIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestActiveRecurrentIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun genuineOwnerDeleteRequiresArchivedPredecessorTwoPaidNativePassesApplyAndPhysicalRefund() = withFixture {
        TestActiveRecurrentCasesV1.genuineNonempty(it, ComplaintJournalDeletionKindV1.OWNER_DELETE)
    }
    @Test fun genuineAdminDeleteMarkerCoversTheNativeVersionWithoutReapplyingDeletion() = withFixture {
        TestActiveRecurrentCasesV1.genuineNonempty(it, ComplaintJournalDeletionKindV1.ADMIN_DELETE)
    }
    @Test fun genuineAdminBatchMarkerCoversItsCanonicalTargetsWithoutReapplyingDeletion() = withFixture {
        TestActiveRecurrentCasesV1.genuineNonempty(it, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE)
    }
    @Test fun genuineOwnerDeleteAllMarkerCoversTheNativeVersionAfterBOracleComposition() = withFixture {
        TestActiveRecurrentCasesV1.genuineNonempty(it, ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)
    }
    @Test fun theNextEmptyRangeStillRereadsEveryPriorNonemptyRangeTwice() = withFixture {
        TestActiveRecurrentCasesV1.nextRangeStillReadsEveryPriorNonemptyRange(it)
    }
    @Test fun missingApplyYieldsOnlyTheOriginalAuthenticatedPrivateScanRecoveryInput() = withFixture {
        TestActiveRecurrentCasesV1.missingApplyIsOnlyPrivateRecoveryInput(it)
    }
    @Test fun genuinePreparedCutoffPublicationIsNativelyVerifiedButCannotStandInForApply() = withFixture {
        TestActiveRecurrentCasesV1.genuinePreparedPublicationIsResolvedWithoutInventingApply(it)
    }
    @Test fun recurrentExclusiveEpochWaitOwnsNoPooledHolderAndCaptureUsesFreshDatabaseTime() = withFixture {
        TestActiveRecurrentCasesV1.captureWaitUsesFreshDatabaseTimeAndNoPooledHolderAcrossExclusiveEpoch(it)
    }
    @Test fun missingDriftingExtraDuplicateDeletedForeignAndRelabelledHigherVersionsCannotComplete() {
        RecurrentListingCut.entries.forEach { cut -> withFixture { TestActiveRecurrentFailureCasesV1.inventory(it, cut) } }
    }
    @Test fun ordinaryNativePrincipalVersionWireMetadataRetentionAndLockAreReauthenticated() {
        RecurrentNativeCut.entries.forEach { cut -> withFixture { TestActiveRecurrentFailureCasesV1.native(it, cut) } }
    }
    @Test fun currentDesiredDatabaseRestoreCatalogLeaseCancellationAndMonotonicCutsFailClosed() {
        RecurrentBindingCut.entries.forEach { cut -> withFixture { TestActiveRecurrentFailureCasesV1.currentBinding(it, cut) } }
    }
    @Test fun historicalSuccessCannotReplaceTheActualRawCatalogReadback() = withFixture {
        TestActiveRecurrentFailureCasesV1.rawCatalogCannotBeReplacedByHistoricalSuccess(it)
    }
    @Test fun oneNativeVersionFitsItsExactCountLimitButAnExtraVersionIsNeverFiltered() {
        withFixture { TestActiveRecurrentCasesV1.genuineNonempty(it, ComplaintJournalDeletionKindV1.OWNER_DELETE, maximumVersions = 1) }
        withFixture { TestActiveRecurrentFailureCasesV1.nativeCountLimitIncludesEveryListedVersion(it) }
    }
    @Test fun lp32HeaderAndTripleFramingUseThePreDBudgetSeparatelyFromCiphertextBytes() = withFixture {
        TestActiveRecurrentFailureCasesV1.framedByteLimitDoesNotBecomeCiphertextOrAnEntryOnlyAllowance(it)
    }
    @Test fun insufficientOrdinaryHeadroomNeverSpendsTheTerminalReserve() = withFixture {
        TestActiveRecurrentFailureCasesV1.insufficientOrdinaryHeadroomCannotBorrowTerminalReserve(it)
    }
    @Test fun newOriginalAfterActualLeaseExpiryPreservesCanonicalAndFrozenNativeWinnersWithoutRecharge() {
        RecurrentSealRecoveryCut.entries.forEach { cut -> withFixture { TestActiveRecurrentRecoveryCasesV1.immutablePreparedResume(it, cut) } }
    }
    @Test fun singlePairAndPartiallyRefundedPaidPrefixesNeedFreshFencesAndBothNewNativePasses() {
        RecurrentPaidPrefixCut.entries.forEach { cut -> withFixture { TestActiveRecurrentRecoveryCasesV1.recoverPaidPrefix(it, cut) } }
    }
    @Test fun immutableSourceHistoryScanOwnershipAndReplayGuardsCannotBecomeRefundAuthority() = withFixture {
        TestActiveRecurrentRecoveryCasesV1.immutableHistoryIntentAndStagingGuards(it)
    }
    @Test fun beforeAfterAndUnknownFinalCommitNeverRehabilitateTheOriginal() {
        listOf(RecurrentCommitCut.BEFORE_COMMIT, RecurrentCommitCut.AFTER_COMMIT, RecurrentCommitCut.DEFERRED_COMMIT_UNKNOWN).forEach { cut ->
            withFixture { TestActiveRecurrentFailureCasesV1.successCommit(it, cut) }
        }
    }
    @Test fun unresolvedPhysicalReleaseCannotIssueCompletionDespiteVisibleDatabaseSuccess() = withFixture {
        TestActiveRecurrentFailureCasesV1.successCommit(it, RecurrentCommitCut.UNRESOLVED_RELEASE)
    }
    @Test fun stickyNativeCloseCannotInventACloseReceiptOrReleaseTheFailedOriginalSlot() = withFixture {
        TestActiveRecurrentFailureCasesV1.nativeCloseFailureCannotInventAReleaseOrRetryAuthority(it)
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
}
