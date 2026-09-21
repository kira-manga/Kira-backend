package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * SOURCE ONLY / NOT_COMPILED / NOT_RUN / NOT_RUNTIME_ACCEPTED. Actual TLS/PG + substituted raw HTTP
 * selectors; no deployment/real AWS claim. Initial EMPTY is not general replay, content/Admin or health.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestActiveInitialCheckpointIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestActiveInitialCheckpointIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun bornWithRecoveryFeedsFreshAssemblyCheckpointWithoutHistoricalAuthorityOrRecharge() = withFixture {
        TestActiveRecoveryCheckpointConnectionV1.recoveredSealFeedsFreshNativeCheckpoint(it)
    }

    @Test fun genuineUnusedEmptyEpochOneRequiresTwoReleasedNativePassesAndExactOrdinaryRefund() = withFixture {
        TestActiveInitialCheckpointCasesV1.genuineEmpty(it, enrolled = false)
    }
    @Test fun genuineEnrolledEmptyEpochOneWritesAllSeventeenFieldsWithoutTerminalReserveSpend() = withFixture {
        TestActiveInitialCheckpointCasesV1.genuineEmpty(it, enrolled = true)
    }
    @Test fun aLiveSealerLeaseCannotBeWaitedOutInsideOrReplacedByTheNewOriginal() = withFixture {
        TestActiveInitialCheckpointCasesV1.livePredecessorLeaseIsRefusedWithoutWaitingOrReplacingIt(it)
    }
    @Test fun ordinaryObjectsNewerEpochUnknownNamespaceVersionsDeletesAndContinuationAllRefuse() {
        InitialCheckpointListingCut.entries.forEach { cut -> withFixture { TestActiveInitialCheckpointFailureCasesV1.wholePrefix(it, cut) } }
    }
    @Test fun secondWholePrefixPassCannotHideAnObjectSeenAfterTheFirstEmptyPass() = withFixture {
        TestActiveInitialCheckpointFailureCasesV1.wholePrefix(it, InitialCheckpointListingCut.NEWER_EPOCH, pass = 2)
    }
    @Test fun exactSealPrincipalVersionWireMetadataRetentionAndLockAreReauthenticated() {
        InitialCheckpointSealCut.entries.forEach { cut -> withFixture { TestActiveInitialCheckpointFailureCasesV1.seal(it, cut) } }
    }
    @Test fun fullDesiredDatabaseRestoreCatalogLeaseCancellationAndDeadlineDriftCannotComplete() {
        InitialCheckpointBindingCut.entries.forEach { cut -> withFixture { TestActiveInitialCheckpointFailureCasesV1.currentBinding(it, cut) } }
    }
    @Test fun changedActualRawCatalogCannotBeSubstitutedByTheHistoricalSealHandle() = withFixture {
        TestActiveInitialCheckpointFailureCasesV1.changedActualRawCatalogRefusesBeforeScannerNative(it)
    }
    @Test fun freshOriginalSupersedesSingleAndPairRowsButRepeatsAllNativeProof() {
        for (pass in 1..3) withFixture { TestActiveInitialCheckpointCasesV1.restartPaidPrefix(it, pass, freshAssembly = false) }
    }
    @Test fun freshAssemblySameJvmCleansThePairUnderNewFenceAndReauthenticatesNativeSealAndPasses() = withFixture {
        TestActiveInitialCheckpointCasesV1.restartPaidPrefix(it, pass = 2, freshAssembly = true)
    }
    @Test fun initialOwnershipNoEntryAndRunTransitionGuardsRejectForeignRefundCandidates() = withFixture {
        TestActiveInitialCheckpointCasesV1.databaseOwnershipGuardsAndForeignRowsCannotBecomeRefundProof(it)
    }
    @Test fun aMissingFirstPassIsNotAnInitialOwnedPrefixOrRefundAuthority() = withFixture {
        TestActiveInitialCheckpointCasesV1.missingFirstPassCannotBeGuessedOrRefunded(it)
    }
    @Test fun exhaustedOrdinaryHeadroomCannotBorrowFromTheTerminalReserve() = withFixture {
        TestActiveInitialCheckpointCasesV1.unrelatedCapacityExhaustionRefusesWithoutSpendingTerminalReserve(it)
    }
    @Test fun beforeAfterAndUnknownFinalCommitCannotRehabilitateTheFailedOriginal() {
        listOf(InitialCheckpointCommitCut.BEFORE_COMMIT, InitialCheckpointCommitCut.AFTER_COMMIT, InitialCheckpointCommitCut.DEFERRED_COMMIT_UNKNOWN).forEach { cut ->
            withFixture { TestActiveInitialCheckpointFailureCasesV1.successCommit(it, cut) }
        }
    }
    @Test fun unresolvedPhysicalReleaseCannotIssueCompletionDespiteVisibleSuccess() = withFixture {
        TestActiveInitialCheckpointFailureCasesV1.successCommit(it, InitialCheckpointCommitCut.UNRESOLVED_RELEASE)
    }
    @Test fun nativeCloseFailureKeepsTheReadSlotPoisonedAndCannotBeRetriedAsSuccess() = withFixture {
        TestActiveInitialCheckpointFailureCasesV1.failedNativeClosePoisonsTheOriginalReadSlot(it)
    }
    @Test fun maximumPopulatedInitialScanRowAndFourIndexKeysStayInsideTheLogicalEnvelope() = withFixture {
        TestActiveInitialCheckpointCasesV1.maximumPopulatedRowAndIndexKeysObservation(it)
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
}
