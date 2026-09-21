package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
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
 * Split2 joins exact retained-primary missing APPLY, including a previously B-applied retained-key
 * ALL alias. Alias fixture bytes are labeled protocol history, NOT a second A AUTH/PUT producer.
 * Missing alias APPLY, same-key versions, general deferred replay sweep, missing bookkeeping,
 * receiptless retirement, full14 and terminal authority remain unresolved.
 * The ALL setup additionally needs the separately owned B readback-oracle correction composed.
 * The current-consumer selectors retain the actual original actor through genuine nonempty
 * SUCCESS, with the combined CREATE/REPLY/EDIT recipe selected before D; no supplied SUCCESS.
 * Four current-deletion families select their separate privacy recipe before D, then create real
 * new targets after SUCCESS. OWNER additionally uses actual epoch3 B APPLY, CREATE and another
 * deletion under the unchanged checkpoint/advanced fence. A genuine B-reconstructed prior N is
 * covered separately; this does not claim general recurrent bookkeeping reconstruction.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestActiveRecurrentIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestActiveRecurrentIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun genuineRecurrentOwnerDeletionAppliesThroughBThenCreatesAndDeletesAgainWithoutAnotherCheckpoint() = withFixture {
        TestActiveRecurrentCasesV1.genuineRecurrentDeletion(it, ComplaintJournalDeletionKindV1.OWNER_DELETE)
    }
    @Test fun genuineRecurrentOwnerDeleteAllUsesItsOwnCurrentClaimAndNativeProofUnderCreationClosure() = withFixture {
        TestActiveRecurrentCasesV1.genuineRecurrentDeletion(it, ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)
    }
    @Test fun genuineRecurrentAdminDeleteUsesItsOwnCurrentClaimAndNativeProofUnderCreationClosure() = withFixture {
        TestActiveRecurrentCasesV1.genuineRecurrentDeletion(it, ComplaintJournalDeletionKindV1.ADMIN_DELETE)
    }
    @Test fun genuineRecurrentAdminBatchUsesItsOwnCurrentClaimAndNativeProofUnderCreationClosure() = withFixture {
        TestActiveRecurrentCasesV1.genuineRecurrentDeletion(it, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE)
    }
    @Test fun genuinelyBReconstructedPriorReceiptPermitsNewRecurrentDeletionWithoutReauthorizationOrReconversion() = withFixture {
        TestActiveRecurrentCasesV1.reconstructedPriorReceiptFeedsANewCurrentDeletionWithoutReauthorization(it)
    }
    @Test fun oldInitialDeletionProfileStillRefusesNewWorkAfterGenuineRecurrentSuccess() = withFixture {
        TestActiveRecurrentCasesV1.olderDeletionProfileRemainsInitialOnly(it)
    }
    @Test fun recurrentDeletionRejectsIdentityPrivacyAndCheckpointDriftBeforeCounters() = withFixture {
        TestActiveRecurrentCasesV1.currentDeletionRejectsCheckpointAndPrivacyGateDrift(it)
    }
    @Test fun recurrentDeletionRequiresExactPriorPublicationReceiptReservationAndNativeApply() = withFixture {
        TestActiveRecurrentCasesV1.currentDeletionRequiresExactPriorPNLAndNativeApply(it)
    }
    @Test fun recurrentDeletionOwnClaimCannotBorrowAnyPreviousPNL() = withFixture {
        TestActiveRecurrentCasesV1.currentDeletionOwnClaimCannotBorrowPriorRows(it)
    }
    @Test fun recurrentDeletionPendingPriorAndDamagedHistoryRefuseNewWorkButNotExactContinuation() = withFixture {
        TestActiveRecurrentCasesV1.pendingPriorAndMissingHistoryNeverBlockExactDeletionContinuation(it)
    }
    @Test fun recurrentDeletionClaimLoserReplaysBeforeClosedControlsWithoutDoubleCharge() = withFixture {
        TestActiveRecurrentCasesV1.currentDeletionClaimLoser(it)
    }
    @Test fun recurrentDeletionWaitedCredentialChangeCannotAuthorizeTheOriginalClaim() = withFixture {
        TestActiveRecurrentCasesV1.currentDeletionWaitedCredential(it)
    }
    @Test fun recurrentDeletionRechecksDatabaseFreshnessAfterCredentialBoundary() = withFixture {
        TestActiveRecurrentCasesV1.currentDeletionNaturalFreshness(it)
    }
    @Test fun recurrentDeletionRetainsExactOwnersTemplatesIngressAndNativeLane() = withFixture {
        TestActiveRecurrentCasesV1.currentDeletionExactResources(it)
    }

    @Test fun genuineRecurrentCurrentCheckpointFeedsCountedCreateReplyEditAndExactHistoricalReceipts() = withFixture {
        TestActiveRecurrentCasesV1.genuineCurrentCreateReplyEdit(it)
    }
    @Test fun recurrentCurrentConsumerRejectsCheckpointIdentityCountTimeAndNativeSealDriftWithoutInitialFallback() = withFixture {
        TestActiveRecurrentCasesV1.currentConsumerCheckpointDrift(it)
    }
    @Test fun missingGenuineRecurrentHistoryRefusesNewWorkButNotExactReceiptReplay() = withFixture {
        TestActiveRecurrentCasesV1.currentConsumerMissingHistory(it)
    }
    @Test fun allThreeOlderProfilesRemainStrictlyInitialDespiteARealRecurrentSuccess() {
        RecurrentConsumerPath.entries.forEach { path -> withFixture { TestActiveRecurrentCasesV1.oldInitialProfileRemainsInitialOnly(it, path) } }
    }
    @Test fun recurrentCreateReplyAndEditEachRejectForeignOwnerTemplateAndIngressHandoffs() {
        RecurrentConsumerPath.entries.forEach { path -> withFixture { TestActiveRecurrentCasesV1.currentConsumerExactGraph(it, path) } }
    }
    @Test fun recurrentCreateReplyAndEditClaimLosersReplayBeforeClosedControlsWithoutDoubleCharge() {
        RecurrentConsumerPath.entries.forEach { path -> withFixture { TestActiveRecurrentCasesV1.currentConsumerClaimLoser(it, path) } }
    }
    @Test fun recurrentCombinedProfileDoesNotWidenBootstrapCreateOrReplyFactoryRoutes() = withFixture {
        TestActiveRecurrentCasesV1.currentConsumerNarrowFactories(it)
    }
    @Test fun recurrentCreateRechecksActualDatabaseFreshnessAfterTheOriginalActorBoundary() = withFixture {
        TestActiveRecurrentCasesV1.currentConsumerNaturalFreshness(it)
    }
    @Test fun recurrentReplyResourceWaitCrossesCheckpointExpiryAndRollsBackTheProvisionalChild() = withFixture {
        TestActiveRecurrentCasesV1.currentConsumerWaitedExpiry(it, RecurrentConsumerPath.REPLY, resource = true)
    }
    @Test fun recurrentReplyContentWaitCrossesCheckpointExpiryBeforeCompletingAnyReceipt() = withFixture {
        TestActiveRecurrentCasesV1.currentConsumerWaitedExpiry(it, RecurrentConsumerPath.REPLY, resource = false)
    }
    @Test fun recurrentEditResourceWaitCrossesCheckpointExpiryAndRollsBackItsOriginalAllocation() = withFixture {
        TestActiveRecurrentCasesV1.currentConsumerWaitedExpiry(it, RecurrentConsumerPath.EDIT, resource = true)
    }
    @Test fun recurrentEditContentWaitCrossesCheckpointExpiryBeforeAnyMutationOrRejectionReceipt() = withFixture {
        TestActiveRecurrentCasesV1.currentConsumerWaitedExpiry(it, RecurrentConsumerPath.EDIT, resource = false)
    }
    @Test fun recurrentCreateWaitedCredentialChangeCannotAuthorizeTheOriginalWrite() = withFixture {
        TestActiveRecurrentCasesV1.currentConsumerCredentialWait(it)
    }
    @Test fun recurrentCreateBeforeAfterAndUnknownCommitCutsPreserveFailureAndExactReceiptSemantics() = withFixture {
        TestActiveRecurrentCasesV1.currentConsumerCompletionFailures(it)
    }

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
    @Test fun genuineMissingOwnerPrimaryAppliesBeforeTheIndependentRecurrentMarkerRecheck() = withFixture {
        TestActiveRecurrentRecoveryCasesV1.firstMissingPrimary(it, ComplaintJournalDeletionKindV1.OWNER_DELETE)
    }
    @Test fun genuineMissingAllPrimaryAppliesBeforeTheIndependentRecurrentMarkerRecheck() = withFixture {
        TestActiveRecurrentRecoveryCasesV1.firstMissingPrimary(it, ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)
    }
    @Test fun genuineMissingAdminPrimaryAppliesBeforeTheIndependentRecurrentMarkerRecheck() = withFixture {
        TestActiveRecurrentRecoveryCasesV1.firstMissingPrimary(it, ComplaintJournalDeletionKindV1.ADMIN_DELETE)
    }
    @Test fun genuineMissingAdminBatchPrimaryAppliesBeforeTheIndependentRecurrentMarkerRecheck() = withFixture {
        TestActiveRecurrentRecoveryCasesV1.firstMissingPrimary(it, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE)
    }
    @Test fun bAppliedProtocolHistoryAllAliasPrecedesOnlyTheActualOriginalPrimarySplit2() = withFixture {
        TestActiveRecurrentRecoveryCasesV1.retainedAllAliasThenActualPrimary(it)
    }
    @Test fun bothAppliedAllVersionsRemainExactNativeCoverageWithoutAnotherApplyOrCharge() = withFixture {
        TestActiveRecurrentRecoveryCasesV1.retainedAllAlreadyAppliedFamily(it)
    }
    @Test fun retainedAllManifestRejectsMissingVersionsOverlapForeignRowsAndPhysicalDrift() {
        RecurrentRetainedAllInventoryCut.entries.forEach { cut -> withFixture { TestActiveRecurrentRecoveryCasesV1.retainedAllInventoryCannotHideDrift(it, cut) } }
    }
    @Test fun authenticatedAllAliasCannotReplaceMissingPrimaryHistoryOrTornCumulativeUse() {
        RecurrentRetainedAllHistoryCut.entries.forEach { cut -> withFixture { TestActiveRecurrentRecoveryCasesV1.retainedAllHistoryMustRemainWhole(it, cut) } }
    }
    @Test fun missingPrimaryApplyBeforeAfterAndUnknownCommitCannotResumeTheFailedOriginal() {
        ComplaintJournalDeletionKindV1.entries.forEach { family ->
            listOf(RecurrentCommitCut.BEFORE_COMMIT, RecurrentCommitCut.AFTER_COMMIT, RecurrentCommitCut.DEFERRED_COMMIT_UNKNOWN).forEach { cut ->
                withFixture { TestActiveRecurrentRecoveryCasesV1.missingPrimaryCommit(it, family, cut) }
            }
        }
    }
    @Test fun pendingNativeInputCannotBeAdoptedByAnotherRecurrentOriginal() = withFixture {
        TestActiveRecurrentRecoveryCasesV1.differentOriginalCannotAdoptPendingNativeInput(it)
    }
    @Test fun recurrentApplyCannotBorrowAnOrdinaryTemplateAndReviveTheFailedOriginal() = withFixture {
        TestActiveRecurrentRecoveryCasesV1.wrongDeletionTemplatePoisonsOnlyTheAttemptWithoutApplying(it)
    }
    @Test fun unusedRecurrentApplyAdmissionReleasesCustodyForANewOriginalButCannotReviveTheFailedOne() = withFixture {
        TestActiveRecurrentRecoveryCasesV1.unusedDeletionEntryRefusalReleasesCustodyButNeverRevivesTheOriginal(it)
    }
    @Test fun missingRecurrentPrimaryBookkeepingIsRefusedWithoutSynthesizingNPL() {
        ComplaintJournalDeletionKindV1.entries.forEach { family -> RecurrentApplyBookkeepingCut.entries.forEach { cut ->
            withFixture { TestActiveRecurrentRecoveryCasesV1.missingBookkeepingCannotBeSynthesized(it, family, cut) }
        } }
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
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use {
            var stage = "BIND"
            try {
                it.bind()
                stage = "BODY" // Includes the case's nested setup/finally, not proof feature assertions ran.
                action(it)
            } catch (failure: PersistencePhaseException) {
                try {
                    println("TEST_ACTIVE_RECURRENT_PHASE_FAILURE stage=$stage code=${failure.code.name} " +
                        "databaseOutcome=${failure.databaseOutcome.name} cleanupProven=${failure.cleanupProven}")
                } catch (_: Throwable) { /* Preserve the original failure if diagnostics fail. */ }
                throw failure
            }
        }
}
