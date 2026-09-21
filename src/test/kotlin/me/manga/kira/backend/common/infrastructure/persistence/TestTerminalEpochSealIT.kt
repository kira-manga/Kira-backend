package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.catalog.TerminalEpochCurrentDriftV1
import me.manga.kira.backend.complaint.catalog.TerminalEpochReadbackDriftV1
import me.manga.kira.backend.complaint.catalog.TestRegistrationCompletionCut
import me.manga.kira.backend.complaint.catalog.TestTerminalEpochSealCasesV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalEpochSealStepV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** Component tests authored only: NOT_RUN. No produced gate-close, final inventories, catalog or PURGED claim. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestTerminalEpochSealIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestTerminalEpochSealIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test
    fun genuineUnusedPurgePaysOneAtomicRotationIntentAndAddsActualTerminalRefWithoutChangingPurgePrefix() = withFixture {
        TestTerminalEpochSealCasesV1.successful(it)
    }

    @Test
    fun actualProtectedIdentityEnrollmentRecordsRetiredDispositionAndPublishesItsChunkBeforeTerminalSeal() = withFixture {
        TestTerminalEpochSealCasesV1.successful(it, enrolled = true)
    }

    @Test
    fun lostPutAcknowledgmentRepeatsExactListAndReadbackWithoutAnotherPutOrSidecarPayment() = withFixture {
        TestTerminalEpochSealCasesV1.successful(it, unknownPut = true)
    }

    @Test
    fun failedPurgeOriginalCannotMintTerminalChild() = withFixture { TestTerminalEpochSealCasesV1.failedPurgeCannotParent(it) }

    @Test
    fun prepareRollbackLeavesNeitherRotationNorIntentAndAncestorCannotReplaceFailedChild() =
        completion(TestTerminalEpochSealStepV1.PREPARE, TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun lostPrepareAcknowledgmentKeepsAtomicPaidIntentButNeverRehabilitatesOriginal() =
        completion(TestTerminalEpochSealStepV1.PREPARE, TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun deferredPrepareCommitFailureStaysUnknownWithoutInferringRotationOrPayment() =
        completion(TestTerminalEpochSealStepV1.PREPARE, TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)

    @Test
    fun unresolvedPrepareCleanupCannotMintAReplacementOriginal() =
        completion(TestTerminalEpochSealStepV1.PREPARE, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)

    @Test
    fun freezeRollbackKeepsPaidCanonicalWinnerAndDoesNotStartS3() =
        completion(TestTerminalEpochSealStepV1.FREEZE, TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun lostFreezeAcknowledgmentKeepsFrozenWinnerWithoutTreatingItAsNativeProof() =
        completion(TestTerminalEpochSealStepV1.FREEZE, TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun deferredFreezeCommitFailureStaysUnknownAndPreservesCanonicalPayment() =
        completion(TestTerminalEpochSealStepV1.FREEZE, TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)

    @Test
    fun unresolvedFreezeCleanupRequiresActualNativeRetirementAndOriginalStillFails() =
        completion(TestTerminalEpochSealStepV1.FREEZE, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)

    @Test
    fun verifyRollbackLeavesNativeWinnerButOrdinaryOnlyLocalSealSet() =
        completion(TestTerminalEpochSealStepV1.VERIFY, TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun lostVerifyAcknowledgmentCannotPromoteCommittedReferenceToSuccessfulOriginal() =
        completion(TestTerminalEpochSealStepV1.VERIFY, TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun deferredVerifyCommitFailureRemainsUnknownAndDoesNotMintHistoricalAccessor() =
        completion(TestTerminalEpochSealStepV1.VERIFY, TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)

    @Test
    fun unresolvedVerifyCleanupCannotBeReplacedThroughPurgeOrManifestAncestor() =
        completion(TestTerminalEpochSealStepV1.VERIFY, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)

    @Test
    fun completeRollbackDoesNotTreatAlreadyVerifiedTerminalReferenceAsSuccessfulRelease() =
        completion(TestTerminalEpochSealStepV1.COMPLETE, TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun lostCompleteAcknowledgmentRetainsFailureEvenWhenTheLeaseReleaseCommitted() =
        completion(TestTerminalEpochSealStepV1.COMPLETE, TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun deferredCompleteCommitFailureStaysUnknownAndKeepsOriginalFailed() =
        completion(TestTerminalEpochSealStepV1.COMPLETE, TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)

    @Test
    fun unresolvedCompleteCleanupCannotIssueSuccessfulAccessorAfterPhysicalRetirement() =
        completion(TestTerminalEpochSealStepV1.COMPLETE, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)

    @Test
    fun changedCurrentEpochBeforePrepareCannotCreateUnrepresentedRotation() =
        drift(TestTerminalEpochSealStepV1.PREPARE, TerminalEpochCurrentDriftV1.CONTROL_EPOCH)

    @Test
    fun verifyRechecksCurrentEpochInsteadOfBorrowingPurgesOldControlObservation() =
        drift(TestTerminalEpochSealStepV1.VERIFY, TerminalEpochCurrentDriftV1.CONTROL_EPOCH)

    @Test
    fun verifyRechecksCurrentClosedGate() = drift(TestTerminalEpochSealStepV1.VERIFY, TerminalEpochCurrentDriftV1.CONTROL_GATE)

    @Test
    fun verifyRefusesCurrentRunSealCardinalityDrift() = drift(TestTerminalEpochSealStepV1.VERIFY, TerminalEpochCurrentDriftV1.RUN_SEALS)

    @Test
    fun prepareRefusesCurrentCounterReserveDriftBeforeAnyNativeAcquisition() =
        drift(TestTerminalEpochSealStepV1.PREPARE, TerminalEpochCurrentDriftV1.COUNTER_RESERVE)

    @Test
    fun verifyOwnsAndRechecksCurrentPaidCounters() = drift(TestTerminalEpochSealStepV1.VERIFY, TerminalEpochCurrentDriftV1.COUNTER_RESERVE)

    @Test
    fun currentSourceChangeBeforePrepareRollsBackWithoutSidecarPayment() =
        drift(TestTerminalEpochSealStepV1.PREPARE, TerminalEpochCurrentDriftV1.SOURCE)

    @Test
    fun completeCurrentSourceFailureCannotPromoteAlreadyVerifiedReference() =
        drift(TestTerminalEpochSealStepV1.COMPLETE, TerminalEpochCurrentDriftV1.SOURCE)

    @Test
    fun verifyRequiresExactPreviouslyAuthenticatedPurgeVersion() = drift(TestTerminalEpochSealStepV1.VERIFY, TerminalEpochCurrentDriftV1.PURGE_VERSION)

    @Test
    fun wrongAcknowledgedVersionCannotReachGetOrVerify() = badReadback(TerminalEpochReadbackDriftV1.ACK_VERSION)

    @Test
    fun unexpectedNativeMetadataCannotReachDecryptOrVerify() = badReadback(TerminalEpochReadbackDriftV1.METADATA)

    @Test
    fun nonComplianceReadbackCannotBecomeTerminalProof() = badReadback(TerminalEpochReadbackDriftV1.LOCK_MODE)

    @Test
    fun retentionBelowFrozenMetadataMinimumCannotBecomeTerminalProof() = badReadback(TerminalEpochReadbackDriftV1.RETENTION)

    @Test
    fun wrongNativeChecksumCannotBecomeTerminalProof() = badReadback(TerminalEpochReadbackDriftV1.CHECKSUM)

    @Test
    fun differentWireBytesCannotBecomeTerminalProof() = badReadback(TerminalEpochReadbackDriftV1.WIRE)

    @Test
    fun missingWinnerAfterOnePutCannotCauseAnotherPutOrLocalVerify() = withFixture {
        TestTerminalEpochSealCasesV1.missingOrDuplicateWinner(it, duplicate = false)
    }

    @Test
    fun extraExactKeyVersionCannotBeFilteredIntoOneTerminalWinner() = withFixture {
        TestTerminalEpochSealCasesV1.missingOrDuplicateWinner(it, duplicate = true)
    }

    @Test
    fun throwingNativeCloseRetainsSameJOwnerAndRefusesVerifyReplacementAndSuccessfulShutdown() = withFixture {
        TestTerminalEpochSealCasesV1.failedNativeClose(it)
    }

    private fun completion(step: TestTerminalEpochSealStepV1, cut: TestRegistrationCompletionCut) =
        withFixture { TestTerminalEpochSealCasesV1.completion(it, step, cut) }
    private fun drift(step: TestTerminalEpochSealStepV1, cut: TerminalEpochCurrentDriftV1) =
        withFixture { TestTerminalEpochSealCasesV1.currentDrift(it, step, cut) }
    private fun badReadback(cut: TerminalEpochReadbackDriftV1) = withFixture { TestTerminalEpochSealCasesV1.badReadback(it, cut) }
    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use { it.bind(); action(it) }
}
