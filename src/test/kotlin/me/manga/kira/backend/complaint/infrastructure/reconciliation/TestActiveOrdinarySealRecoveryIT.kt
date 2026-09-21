package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.catalog.TestActiveSealRecoveryColdProcessCasesV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** SOURCE ONLY: NOT_COMPILED / NOT_RUN / NOT_RUNTIME_ACCEPTED. No checkpoint, terminal or healthy result is claimed. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestActiveOrdinarySealRecoveryIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestActiveOrdinarySealRecoveryIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun genuineCanonicalContinuesWithOneGenerationAndHigherCurrentFence() = withFixture {
        TestActiveSealRecoveryCasesV1.continueInitial(it, ActiveSealRecoveryHistoryCutV1.CANONICAL)
    }
    @Test fun genuineFrozenMissingObjectUsesNoGenerationOrRefreezeAndPreservesCurrentEnrollment() = withFixture {
        TestActiveSealRecoveryCasesV1.continueInitial(it, ActiveSealRecoveryHistoryCutV1.WIRE_FROZEN, enrolled = true)
    }
    @Test fun genuineExistingLostPutAckObjectUsesReadbackDespiteStaleFreshPutRetentionRequirement() = withFixture {
        TestActiveSealRecoveryCasesV1.continueInitial(it, ActiveSealRecoveryHistoryCutV1.EXISTING_WIRE, horizon = java.time.Instant.parse("2030-01-01T00:00:00Z"))
    }
    @Test fun livePriorLeaseIsNotStolenOrWaitedOut() = withFixture { TestActiveSealRecoveryCasesV1.livePriorLeaseRefusesWithoutStealOrWaiting(it) }
    @Test fun verifiedHistoryIsRefusedAndBelongsToIndependentCheckpointRestart() = withFixture { TestActiveSealRecoveryCasesV1.verifiedHistoryBelongsToCheckpointNotThisOriginal(it) }
    @Test fun staleMissingFrozenObjectRetainsExplicitUnresolvedRecoveryLimitation() = withFixture { TestActiveSealRecoveryCasesV1.staleAbsentFrozenRetentionRefusesWithoutRegeneration(it) }
    @Test fun bornWithCurrentPutFloorPublishesStaleMissingFrozenBytesWithoutRepair() = withFixture {
        TestActiveSealRecoveryCasesV1.currentPutFloorForStaleMissingFrozen(it, shortenAcknowledgedGet = false)
    }
    @Test fun bornWithCurrentPutFloorRejectsKnownAckGetBelowCapturedLockDespiteCoveringFrozenMinimum() = withFixture {
        TestActiveSealRecoveryCasesV1.currentPutFloorForStaleMissingFrozen(it, shortenAcknowledgedGet = true)
    }

    @Test fun scopedForeignWriterAliasAndHigherEpochRowsCannotHideBehindEmptyFilter() {
        ActiveSealRecoveryRowCutV1.entries.forEach { cut -> withFixture { TestActiveSealRecoveryRefusalCasesV1.localClosure(it, cut) } }
    }
    @Test fun syntheticNonemptyActualACanonicalCannotBecomeEmptyByLosingItsLocalRow() = withFixture {
        TestActiveSealRecoveryRefusalCasesV1.nonemptyCanonicalCannotBeRelabelledEmptyAfterLocalRowsDisappear(it)
    }
    @Test fun currentFullDDriftRefusesBeforeLeaseOrRawRead() = withFixture { TestActiveSealRecoveryRefusalCasesV1.currentFullDDriftRefusesBeforeRawOrLease(it) }
    @Test fun nativeRoleVersionWireMetadataLockAndRetentionFaultsCannotVerify() {
        ActiveSealProviderCut.entries.forEach { cut -> withFixture { TestActiveSealRecoveryFailureCasesV1.provider(it, cut) } }
    }
    @Test fun freezeAndVerifyCommitFaultsNeverRepairOriginalFromVisibleRows() {
        listOf(TestActiveOrdinarySealRecoveryStepV1.FREEZE, TestActiveOrdinarySealRecoveryStepV1.VERIFY).forEach { step ->
            ActiveSealRecoveryCompletionCutV1.entries.forEach { cut -> withFixture { TestActiveSealRecoveryFailureCasesV1.completion(it, step, cut) } }
        }
    }
    @Test fun cancellationLeaseExpiryReplacementAndLateNativeClosePoisonOriginal() {
        ActiveSealLifetimeCut.entries.forEach { cut -> withFixture { TestActiveSealRecoveryFailureCasesV1.lifetime(it, cut) } }
    }
    @Test fun failedActualNativeCloseCannotReleaseLaneOrVerify() = withFixture { TestActiveSealRecoveryFailureCasesV1.failedNativeCloseCannotReleaseOrVerify(it) }

    @Test fun actualTwoJvmCanonicalContinuationUsesOriginalPaidBytesAndFreshNativeGeneration() = TestActiveSealRecoveryColdProcessCasesV1.qualify(existingObject = false)
    @Test fun actualTwoJvmExistingObjectContinuationUsesChildOnePutBytesWithoutGenerationOrRefreeze() = TestActiveSealRecoveryColdProcessCasesV1.qualify(existingObject = true)

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
}
