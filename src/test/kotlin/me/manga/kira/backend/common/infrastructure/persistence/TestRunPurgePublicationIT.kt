package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.catalog.TestRegistrationCompletionCut
import me.manga.kira.backend.complaint.catalog.TestRunPurgePublicationCasesV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeStepV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** Authored unused-run mechanics, NOT_RUN; no nonempty, historical, terminal-catalog or PURGED claim. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRunPurgePublicationIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRunPurgePublicationIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test
    fun genuineUnusedRunCarriesEmptyRootAndZeroChunksThenPaysFreezesAuthenticatesAndReplaysExactPurge() = withFixture {
        TestRunPurgePublicationCasesV1.unusedAndReplay(it)
    }

    @Test
    fun lostPurgePutAcknowledgmentUsesRepeatListAndExactVersionReadWithoutAnotherPhysicalCharge() = withFixture {
        TestRunPurgePublicationCasesV1.unusedAndReplay(it, unknownPut = true)
    }

    @Test
    fun laterPreconditionFailedPutAcceptsValidExistingRetentionBelowTheRejectedProposedLock() = withFixture {
        TestRunPurgePublicationCasesV1.completion(it, TestRunPurgeStepV1.VERIFY, TestRegistrationCompletionCut.BEFORE_COMMIT,
            preconditionOnRetry = true)
    }

    @Test
    fun unfinishedManifestAndChangedCurrentSealVersionCannotAdmitPurgeProvidersOrPayment() = withFixture {
        TestRunPurgePublicationCasesV1.predecessorAndReferenceRefusals(it)
    }

    @Test
    fun syntheticHistoricalManifestIsRefusedRatherThanErasedOrRelabelledAsInitialHistory() = withFixture {
        TestRunPurgePublicationCasesV1.historicalFixtureIsNotLaundered(it)
    }

    @Test
    fun prepareRollbackTransfersNothingAndFreshOriginalPaysExactlyOnce() =
        completion(TestRunPurgeStepV1.PREPARE, TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun lostPrepareAcknowledgmentLeavesPaidCanonicalWinnerAndOriginalCannotBecomeSuccessful() =
        completion(TestRunPurgeStepV1.PREPARE, TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun deferredPrepareCommitFailureRemainsUnknownAndDoesNotClaimPaymentOrCompletion() =
        completion(TestRunPurgeStepV1.PREPARE, TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)

    @Test
    fun unresolvedPrepareCleanupCannotStartSuccessorUntilActualResourceRetirement() =
        completion(TestRunPurgeStepV1.PREPARE, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)

    @Test
    fun verifyRollbackLeavesFrozenPreparedWinnerAndFreshOriginalPerformsReadbackOnly() =
        completion(TestRunPurgeStepV1.VERIFY, TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun lostVerifyAcknowledgmentPreservesTheExactFirstProofOnFreshReadback() =
        completion(TestRunPurgeStepV1.VERIFY, TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun deferredVerifyCommitFailureRemainsUnknownEvenAfterFreshOriginalVerifies() =
        completion(TestRunPurgeStepV1.VERIFY, TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)

    @Test
    fun unresolvedVerifyCleanupQuarantinesOriginalUntilActualReleaseAndStillRequiresFreshLease() =
        completion(TestRunPurgeStepV1.VERIFY, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)

    private fun completion(step: TestRunPurgeStepV1, cut: TestRegistrationCompletionCut) =
        withFixture { TestRunPurgePublicationCasesV1.completion(it, step, cut) }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use { it.bind(); action(it) }
}
