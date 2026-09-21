package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalCasesV1
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalRecoveryCasesV1
import me.manga.kira.backend.complaint.catalog.OfflineCatalogTestRunTerminalCasesV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogCommitEdgeV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogDeliveryFaultV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogEvidenceFaultV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogRecoveryRefusalV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Authored focused selector only, NOT discovery/execution/acceptance. PostgreSQL/native SDK over
 * synthetic raw HTTP; genuine predecessor producers, but no installed denial/provider provenance.
 * Endpoint is PURGING. No ACTIVE/V26/B/V29, erasure, PURGED or supported-maximum-N qualification.
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

    private fun evidence(fault: TerminalCatalogEvidenceFaultV1) = withFixture { CatalogTestRunTerminalCasesV1.badEvidence(it, fault) }
    private fun delivery(fault: TerminalCatalogDeliveryFaultV1) = withFixture { CatalogTestRunTerminalCasesV1.badDelivery(it, fault) }
    private fun refusal(fault: TerminalCatalogRecoveryRefusalV1) = withFixture { CatalogTestRunTerminalRecoveryCasesV1.refuses(it, fault) }
    private fun lost(edge: TerminalCatalogCommitEdgeV1) = withFixture { CatalogTestRunTerminalRecoveryCasesV1.lostCommitAcknowledgement(it, edge) }
    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use { it.bind(); action(it) }
}
