package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalCasesV1
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalActiveHistoryCasesV1
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalRecoveryCasesV1
import me.manga.kira.backend.complaint.catalog.CatalogRetainedDPrimaryCasesV1
import me.manga.kira.backend.complaint.catalog.OfflineCatalogTestRunTerminalCasesV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogCommitEdgeV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogDeliveryFaultV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogEvidenceFaultV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogRecoveryRefusalV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogHistoryNativeFaultV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogHistoryRowFaultV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogRecurrentNativeFaultV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogRecurrentRowFaultV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogRetainedPrimaryFaultV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Authored focused selector only, NOT discovery/execution/acceptance. PostgreSQL/native SDK over
 * synthetic raw HTTP; genuine predecessor producers, but no installed denial/provider provenance.
 * Endpoint is PURGING. Exact A1/ordinary2/terminal3 declarations/producers are authored below.
 * B's original-object SETTLED/POLLING and one nonempty OWNER_DELETE composition are authored below.
 * Retained-N/P/L ALL PREPARED/VERIFIED queue -> registered privacy replay -> D/E is separate from HTTP authentication.
 * Reconstructed/later-domain/historical-alias ALL histories stop at registered comparison/replay or ordinary D,
 * not E. The missing-bookkeeping dataset deliberately does not claim consistent-restore closure.
 * Alias-only pending ALL cases use a fresh registered primary completion and replay after real sealing, without D/E.
 * Retained-D single-primary cuts include genuine PREPARED completion and same-holder lease/full-D refusals, stopping at D.
 * Genuine recurrent N>=2 -> D/E and N14/total16/refusal selectors are source-authored below,
 * not executed or accepted by this file. N0/N1 stay separate regressions. No erasure/PURGED,
 * prerequisite-PASS assumption or supported-maximum-N runtime qualification is asserted.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class CatalogTestRunTerminalIT {
    private val database = lazy { PgLifecycleDatabaseFixture(CatalogTestRunTerminalIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun unusedCompletedDPublishesActualSchema4AndSpendsOnlyCatalogPlusOneAuditToPurging() = withFixture { CatalogTestRunTerminalCasesV1.successful(it) }
    @Test fun enrolledCompletedDRechecksFullManifestPurgeSealInventoryWithoutDeletingAnyRow() = withFixture { CatalogTestRunTerminalCasesV1.successful(it, enrolled = true) }
    @Test fun exactOrdinarySignedApprovalStillRequiresItsRawEvidence() = evidence(TerminalCatalogEvidenceFaultV1.ORDINARY_RAW)
    @Test fun exactTerminalSignedApprovalStillRequiresItsRawEvidence() = evidence(TerminalCatalogEvidenceFaultV1.TERMINAL_RAW)
    @Test fun corruptedTerminalApprovalCannotReuseHistoricalDAdmission() = evidence(TerminalCatalogEvidenceFaultV1.TERMINAL_SIGNATURE)
    @Test fun terminalAdmissionRequiresTheExactRetainedTrueScanFlag() = withFixture { CatalogTestRunTerminalCasesV1.staleScanFlag(it) }
    @Test fun crossThreadCannotStealOrPoisonTheOriginalDChild() = withFixture { CatalogTestRunTerminalCasesV1.crossThreadCannotStealChild(it) }
    @Test fun unfinishedAndFailedDHaveNoTerminalCatalogChild() = withFixture { CatalogTestRunTerminalCasesV1.unfinishedAndFailedD(it) }
    @Test fun replicaVersionSubstitutionNeverCompletesOrProjects() = delivery(TerminalCatalogDeliveryFaultV1.REPLICA_VERSION)
    @Test fun replicaDeleteMarkerCannotBeFilteredFromFullAcceptance() = delivery(TerminalCatalogDeliveryFaultV1.DELETE_MARKER)
    @Test fun secondPrimaryVersionCannotBecomeSingleVersionAcceptance() = delivery(TerminalCatalogDeliveryFaultV1.EXTRA_VERSION)
    @Test fun changedReplicaEnvelopeNeverCompletesOrProjects() = delivery(TerminalCatalogDeliveryFaultV1.ENVELOPE_BYTES)
    @Test fun unreturnedReadClientCloseNeverCompletesOrProjects() = delivery(TerminalCatalogDeliveryFaultV1.READ_CLOSE)

    @Test fun sourceOnlyRemainsPreparedThenFreshExactResumerAcceptsWithoutSecondSignPutOrPayment() = withFixture { CatalogTestRunTerminalRecoveryCasesV1.sourceOnly(it) }
    @Test fun alreadyProjectedFreshReplayIsReadOnlyIncludingGlobalXminAndEveryCustodyFile() = withFixture { CatalogTestRunTerminalRecoveryCasesV1.alreadyProjected(it) }
    @Test fun wrongTokenIsNotAPreparedRecoveryCapability() = refusal(TerminalCatalogRecoveryRefusalV1.WRONG_TOKEN)
    @Test fun freshPreparedRecoveryRequiresExactOrdinaryRawEvidenceAgain() = refusal(TerminalCatalogRecoveryRefusalV1.MISSING_ORDINARY_RAW)
    @Test fun freshPreparedRecoveryRequiresExactTerminalRawEvidenceAgain() = refusal(TerminalCatalogRecoveryRefusalV1.MISSING_TERMINAL_RAW)
    @Test fun committedPrepareLostAckKeepsAtomicPaymentAndAllowsOnlyTheFirstFrozenSign() = lost(TerminalCatalogCommitEdgeV1.PREPARE)
    @Test fun committedSignatureLostAckKeepsExactSignatureWireAndNeverResigns() = lost(TerminalCatalogCommitEdgeV1.SIGNATURE)
    @Test fun committedCompleteLostAckProjectsOnlyItsOwnPendingToken() = lost(TerminalCatalogCommitEdgeV1.COMPLETE)
    @Test fun committedProjectLostAckConfirmsWithoutOwnerTimestampAuditOrCounterChurn() = lost(TerminalCatalogCommitEdgeV1.PROJECT)
    @Test fun knownPrepareRollbackLeavesNeitherCatalogRowNorReserveTransferAndCannotResumeFromFilesOnly() = withFixture { CatalogTestRunTerminalRecoveryCasesV1.rollbackPrepare(it) }
    @Test fun actualDeferredCommitUnknownUsesFreshExactReadsWithoutRefundOrRepricing() = withFixture { CatalogTestRunTerminalRecoveryCasesV1.actualCommitUnknown(it) }
    @Test fun actualPutAcceptedBeforeLostAckIsRecoveredReadOnlyAtTheProvider() = withFixture { CatalogTestRunTerminalRecoveryCasesV1.lostPutAcknowledgement(it) }
    @Test fun oldSignArmWithoutDurableSignatureRefusesAnotherSignAndNeverClaimsCleanup() = withFixture { CatalogTestRunTerminalRecoveryCasesV1.unreturnedSignCannotRepeat(it) }

    @Test fun genuineDDeclarationPreservesFullV1V2V3PrefixAndAllSevenNoncyclicHistoryHeads() = withFixture { OfflineCatalogTestRunTerminalCasesV1.canonicalFullHistory(it) }
    @Test fun closedSchema4ParserAndFullChainRejectMalformedHistoryLinkAndSuffixSubstitution() = withFixture { OfflineCatalogTestRunTerminalCasesV1.strictFieldsHistoryAndLinks(it) }
    @Test fun unsignedSigned131072AndIndependentReaderBoundsAreEnforcedWithoutTruncatingInventory() = withFixture { OfflineCatalogTestRunTerminalCasesV1.signedPaidAndReaderBounds(it) }

    @Test fun genuineInitialEmptyAAndRegisteredCreateReachSchema4WithAllThreeSealsAndNoSecondACharge() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.successful(it) }
    @Test fun initialAHistoryPreparedFreshContinuationRetainsItsV26BytesAndNeverRepeatsSignPutOrPayment() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.preparedRecovery(it) }
    @Test fun initialAHistoryProjectedFreshReplayIsReadOnlyIncludingV26XminAndEveryCustodyFile() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.projectedReplay(it) }
    @Test fun declaredThreeSealCountCannotReplaceAMissingActualV26Row() = historyRow(TerminalCatalogHistoryRowFaultV1.MISSING_A)
    @Test fun actualAHistoryMustKeepItsDatabaseAndFullDIdentity() = historyRow(TerminalCatalogHistoryRowFaultV1.FOREIGN_A_IDENTITY)
    @Test fun identicalABytesWithDifferentXminCannotReplaceDsRetainedPhysicalPreimage() = historyRow(TerminalCatalogHistoryRowFaultV1.REWRITTEN_A_XMIN)
    @Test fun freshPreparedContinuationRejectsRewrittenAAgainstOriginalCustodyBeforeAuthority() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.preparedPhysicalRewriteRefuses(it) }
    @Test fun fullTerminalInventoryCannotOmitTheRealInitialASeal() = historyNative(TerminalCatalogHistoryNativeFaultV1.MISSING_A)
    @Test fun extraAObjectVersionInSecondFullInventoryPassCannotBeFilteredAway() = historyNative(TerminalCatalogHistoryNativeFaultV1.SECOND_PASS_EXTRA_VERSION)
    @Test fun initialAActualRetentionIsPreservedExactlyNotMerelyLongEnough() = historyNative(TerminalCatalogHistoryNativeFaultV1.CHANGED_RETENTION)
    @Test fun initialANativeLastModifiedIsBoundToItsOriginalVerificationBytes() = historyNative(TerminalCatalogHistoryNativeFaultV1.CHANGED_LAST_MODIFIED)
    @Test fun oldNoANoBTwoSealPreconditionAndCustodyFramingStayByteCompatible() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.legacyTwoSealCompatibility(it) }
    @Test fun genuineThreeSealDeclarationKeepsCompleteContextualHistoryAndTheTwoSealPreterminalRoot() = withFixture { OfflineCatalogTestRunTerminalCasesV1.canonicalActiveHistory(it) }
    @Test fun schema4RejectsOmittedReorderedRepeatedWrongWriterAndExtendedActiveSealDeclarations() = withFixture { OfflineCatalogTestRunTerminalCasesV1.strictActiveOrder(it) }

    @Test fun genuineVerifiedADeletionIsCompletedByDThenERevisitsItsOriginalNonemptyInventoryWithoutRechargingHistory() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.nonemptySuccessful(it) }
    @Test fun genuineSettledBApplyAndItsPermanent8192ChargeSurviveDAndThreeSealTerminalCatalogProjection() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.nonemptySuccessful(it, settledQueue = true) }
    @Test fun genuineSettledBNonemptyPreparedRecoveryAndProjectedReplayKeepOriginalBytesRowsAndSinglePayment() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.settledQueuePreparedRecoveryAndReplay(it) }
    @Test fun genuineMalformedQueuePollingSurvivesActualDAndRefusesEBeforeAnyNativeOrCatalogPayment() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.genuinePollingQueueRefusesCatalog(it) }

    @Test fun genuineRecurrentTwoActiveSealsReachFourSealTerminalWithFullHistoricalAndTailRootsAndOnlyTwoNewV21Charges() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.recurrentSuccessful(it) }
    @Test fun genuineRecurrentPreparedColdContinuationAndProjectedReplayRecheckEveryOriginalAndKeepExactBytesCustodyAndSinglePayment() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.recurrentPreparedRecoveryAndReplay(it) }
    @Test fun genuineFourteenActiveSealsReachSixteenTotalWithoutRechargingHistoryAndRejectSeventeenRecordSyntax() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.recurrentSuccessful(it, activeSeals = 14) }
    @Test fun genuineFifteenthActiveAttemptRefusesBeforeRequestChargeOrAnotherPutAndIsNeverReusedForD() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.recurrentFifteenthActiveRefuses(it) }
    @Test fun genuineDMissingMiddleHistoryRefusesAfterPaidOrdinaryCloseoutBeforeNewV21SealPaymentOrPut() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.recurrentDrainHistoryRefuses(it, physical = false) }
    @Test fun genuineDRechecksIdenticalHistoricalBytesWithChangedXminAfterNativeReadBeforeNewV21SealPaymentOrPut() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.recurrentDrainHistoryRefuses(it, physical = true) }
    @Test fun recurrentCatalogCannotReplaceMissingLastPaidCheckpointArchiveWithItsDeclaredSealCount() = recurrentRow(TerminalCatalogRecurrentRowFaultV1.MISSING_LATEST_ARCHIVE)
    @Test fun recurrentCatalogRequiresOriginalLastIntentAndItsArchiveRatherThanTheRetainedNativeLocatorAlone() = recurrentRow(TerminalCatalogRecurrentRowFaultV1.MISSING_LATEST_SOURCE)
    @Test fun recurrentCatalogRefusesPartiallyCheckpointedArchiveWithoutRepairSignOrPayment() = recurrentRow(TerminalCatalogRecurrentRowFaultV1.PARTIAL_CHECKPOINT)
    @Test fun recurrentCatalogRefusesForeignSourceIdentityDespiteTheSameNativeSealAndOrderedCount() = recurrentRow(TerminalCatalogRecurrentRowFaultV1.FOREIGN_SOURCE_IDENTITY)
    @Test fun recurrentCatalogRefusesReorderedPhysicalArchivesRatherThanSortingAwayBrokenOrdinalBinding() = recurrentRow(TerminalCatalogRecurrentRowFaultV1.REORDERED_ARCHIVES)
    @Test fun recurrentCatalogRefusesIdenticalIntentBytesWithRewrittenXminAgainstOriginalD() = recurrentRow(TerminalCatalogRecurrentRowFaultV1.REWRITTEN_SOURCE_XMIN)
    @Test fun recurrentCatalogRefusesIdenticalCheckpointArchiveBytesWithRewrittenXminAgainstOriginalD() = recurrentRow(TerminalCatalogRecurrentRowFaultV1.REWRITTEN_ARCHIVE_XMIN)
    @Test fun recurrentColdPreparedContinuationRefusesChangedArchiveXminAgainstExactOriginalCustody() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.recurrentColdPhysicalRefusal(it, projected = false) }
    @Test fun recurrentColdProjectedReplayRefusesChangedIntentXminWithoutNewAuthorityOrCustodyWrites() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.recurrentColdPhysicalRefusal(it, projected = true) }
    @Test fun recurrentFullTerminalInventoryCannotOmitTheMiddleHistoricalNativeSeal() = recurrentNative(TerminalCatalogRecurrentNativeFaultV1.MISSING_MIDDLE)
    @Test fun recurrentSecondFullInventoryCannotFilterAnExtraMiddleSealVersionAfterAGenuineFirstPass() = recurrentNative(TerminalCatalogRecurrentNativeFaultV1.SECOND_PASS_EXTRA_VERSION)
    @Test fun recurrentMiddleHistoricalCiphertextDriftRefusesWithoutRepairOrCatalogPayment() = recurrentNative(TerminalCatalogRecurrentNativeFaultV1.CHANGED_CIPHERTEXT)
    @Test fun recurrentMiddleHistoricalRetentionMustRemainExactNotMerelyLongEnough() = recurrentNative(TerminalCatalogRecurrentNativeFaultV1.CHANGED_RETENTION)

    @Test fun genuinePreparedADeletionUsesRetainedDSelectionAndItsOwnVerifyBeforeExactApplyAndDrain() = withFixture { CatalogRetainedDPrimaryCasesV1.prepared(it) }
    @Test fun retainedDSelectedPrimaryRefusesStolenLeaseBeforeReloadingItsReceipt() = primaryRefusal(TerminalCatalogRetainedPrimaryFaultV1.RELOAD_OWNER)
    @Test fun retainedDPreparedPrimaryFinalLeaseExpiryRollsBackItsActualVerification() = primaryRefusal(TerminalCatalogRetainedPrimaryFaultV1.VERIFY_LEASE)
    @Test fun retainedDVerifiedPrimaryFinalLeaseExpiryRollsBackActualApplyAndRefund() = primaryRefusal(TerminalCatalogRetainedPrimaryFaultV1.APPLY_LEASE)
    @Test fun retainedDPrimaryFinalFullDDriftRollsBackItsActualApply() = primaryRefusal(TerminalCatalogRetainedPrimaryFaultV1.APPLY_CONTROL)

    @Test fun genuinePreparedAllQueuePrimaryReplaysRegisteredAndCompletesTerminalWithSystemAudit() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.allQueueSuccessful(it, verifyPublication = false) }
    @Test fun genuineVerifiedAllQueuePrimaryReplaysRegisteredAndCompletesTerminalWithSystemAudit() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.allQueueSuccessful(it, verifyPublication = true) }
    @Test fun allSystemPrimaryAuditCannotReplaceMissingOrMismatchedFamilyEvidence() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.allFamilyEvidenceRefuses(it) }
    @Test fun allSystemPrimaryAuditRequiresOneExactPrimaryEventTimeActorAndCountedOutcome() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.allPrimarySummaryRefuses(it) }

    @Test fun allReconstructedPublicationAndReceiptRepairPreserveNativeTimelineOnRegisteredReplay() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.allRecoveredChronology(it, reconstructed = true) }
    @Test fun allLaterDomainAndResourceRepairPreservePrimaryCompletionAndBothRetryWindows() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.allRecoveredChronology(it, reconstructed = false) }
    @Test fun historicalPreparedAllAliasBeforePrimaryVerifyReplaysAndDrainsWithoutChangingEitherExpiry() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.allHistoricalChronology(it, prepared = true) }
    @Test fun historicalVerifiedAllAliasBeforePrimaryCompletionReplaysAndDrainsWithoutChangingEitherExpiry() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.allHistoricalChronology(it, prepared = false) }
    @Test fun registeredPreparedAllPrimaryCompletesAfterHistoricalAliasWithoutRefreshingFirstVerifier() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.registeredPendingAllAfterAlias(it, prepared = true) }
    @Test fun registeredVerifiedAllPrimaryCompletesAfterHistoricalAliasWithoutChangingProofOrDomain() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.registeredPendingAllAfterAlias(it, prepared = false) }
    @Test fun registeredPendingAllAfterAliasRefusesUnbackedFamilyBeforePublication() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.registeredPendingAllAliasReloadRefusals(it) }
    @Test fun registeredPendingAllAfterAliasRevalidatesFamilyAndVerifierAtApply() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.registeredPendingAllAliasApplyRefusals(it) }
    @Test fun allRetainedFamilyTimelineOrAccountingMismatchRefusesWithoutConsumerMutation() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.allRetainedTimelineRefuses(it) }
    // Expiry half is pure boundary-policy coverage; only missing verifier is an actual registered original refusal.
    @Test fun allRegisteredFamilyReplayRefusesMissingOrIndependentlyExpiredVerifier() = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.allRegisteredReplayRefusals(it) }

    private fun evidence(fault: TerminalCatalogEvidenceFaultV1) = withFixture { CatalogTestRunTerminalCasesV1.badEvidence(it, fault) }
    private fun delivery(fault: TerminalCatalogDeliveryFaultV1) = withFixture { CatalogTestRunTerminalCasesV1.badDelivery(it, fault) }
    private fun refusal(fault: TerminalCatalogRecoveryRefusalV1) = withFixture { CatalogTestRunTerminalRecoveryCasesV1.refuses(it, fault) }
    private fun lost(edge: TerminalCatalogCommitEdgeV1) = withFixture { CatalogTestRunTerminalRecoveryCasesV1.lostCommitAcknowledgement(it, edge) }
    private fun historyRow(fault: TerminalCatalogHistoryRowFaultV1) = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.rowRefusal(it, fault) }
    private fun historyNative(fault: TerminalCatalogHistoryNativeFaultV1) = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.nativeRefusal(it, fault) }
    private fun recurrentRow(fault: TerminalCatalogRecurrentRowFaultV1) = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.recurrentRowRefusal(it, fault) }
    private fun recurrentNative(fault: TerminalCatalogRecurrentNativeFaultV1) = withFixture { CatalogTestRunTerminalActiveHistoryCasesV1.recurrentNativeRefusal(it, fault) }
    private fun primaryRefusal(fault: TerminalCatalogRetainedPrimaryFaultV1) = withFixture { CatalogRetainedDPrimaryCasesV1.refuses(it, fault) }
    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use {
            var stage = "BIND"
            try {
                it.bind()
                stage = "BODY" // Includes the case's nested setup/finally, not proof feature assertions ran.
                action(it)
            } catch (failure: PersistencePhaseException) {
                try {
                    println("TEST_CATALOG_TERMINAL_PHASE_FAILURE stage=$stage code=${failure.code.name} " +
                        "databaseOutcome=${failure.databaseOutcome.name} cleanupProven=${failure.cleanupProven}")
                } catch (_: Throwable) { /* Preserve the original failure if diagnostics fail. */ }
                throw failure
            }
        }
}
