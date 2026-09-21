package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.catalog.TestInstallationManifestPublicationCasesV1
import me.manga.kira.backend.complaint.catalog.TestInstallationManifestPublicationFailureCasesV1
import me.manga.kira.backend.complaint.catalog.TestInstallationManifestFamilyJoinCasesV1
import me.manga.kira.backend.complaint.catalog.TestRegistrationCompletionCut
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestPublicationStepV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** Synthetic connected mechanics only. Source-authored selectors are NOT_RUN until the controller executes them. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestInstallationManifestPublicationIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestInstallationManifestPublicationIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test
    fun actualPaidPrepareFreezesAuthenticatesClosesAndVerifiesWithoutReceiptRunOrCapacityWrites() = withFixture {
        TestInstallationManifestPublicationCasesV1.publishPaidWinner(it)
    }

    @Test
    fun freshExactVerifiedRetryReadsAndAuthenticatesWithoutGeneratePutUpdateOrTimestampChurn() = withFixture {
        TestInstallationManifestPublicationCasesV1.exactVerifiedRetry(it)
    }

    @Test
    fun lostConditionalPutAcknowledgmentUsesOneRepeatListAndExactVersionGet() = withFixture {
        TestInstallationManifestPublicationCasesV1.lostPutAcknowledgment(it)
    }

    @Test
    fun preconditionFailedPutReusesFrozenWinnerAndPerformsOneRepeatListAndGet() = withFixture {
        TestInstallationManifestPublicationFailureCasesV1.completion(it, TestInstallationManifestPublicationStepV1.VERIFY,
            TestRegistrationCompletionCut.BEFORE_COMMIT, preconditionOnRetry = true)
    }

    @Test
    fun genuine501PaidEnrollmentsExerciseBothMixedStatesThenReuseBothFrozenWinners() = withFixture {
        TestInstallationManifestPublicationCasesV1.mixedTwoChunkRetry(it)
    }

    @Test
    fun unfinishedAndFailedPrepareOriginalsCannotAdmitPublicationOrProviderWork() = withFixture {
        TestInstallationManifestPublicationFailureCasesV1.unfinishedAndFailedPrepare(it)
    }

    @Test
    fun authorityChangedAfterLoadDuringStsRefusesFreezeAndAllS3() = withFixture {
        TestInstallationManifestPublicationFailureCasesV1.staleAuthorityAfterLoad(it)
    }

    @Test
    fun loadAndItsCommittedReleaseConsumeTheOriginalPublicationDeadlineBeforeProviders() = withFixture {
        TestInstallationManifestPublicationFailureCasesV1.loadConsumesOriginalPublicationDeadline(it)
    }

    @Test
    fun freezeRollbackCannotDispatchAndFreshChildGeneratesOnlyForUnfrozenCanonicalWinner() =
        completion(TestInstallationManifestPublicationStepV1.FREEZE, TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun lostFreezeAcknowledgmentCannotDispatchAndFreshChildReusesDurableWireWithoutGenerate() =
        completion(TestInstallationManifestPublicationStepV1.FREEZE, TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun verifyRollbackLeavesPreparedFrozenAndFreshChildAuthenticatesWithoutAnotherPut() =
        completion(TestInstallationManifestPublicationStepV1.VERIFY, TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun lostVerifyAcknowledgmentPreservesExactFirstVerifiedWinnerOnFreshReadback() =
        completion(TestInstallationManifestPublicationStepV1.VERIFY, TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun deferredVerifyCommitFailureStaysUnknownEvenWhenFreshChildEventuallyVerifies() =
        completion(TestInstallationManifestPublicationStepV1.VERIFY, TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)

    @Test
    fun unresolvedVerifyReleaseQuarantinesUntilActualRetirementAndStillRequiresFreshExpiredLease() =
        completion(TestInstallationManifestPublicationStepV1.VERIFY, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)

    @Test
    fun missingAlreadyVerifiedVersionCannotFallThroughToConditionalPut() = withFixture {
        TestInstallationManifestPublicationCasesV1.verifiedVersionMissingRefusesPut(it)
    }

    @Test
    fun malformedMetadataLockRetentionChecksumWireAndExtraListingsNeverReachVerify() = withFixture {
        TestInstallationManifestPublicationCasesV1.malformedReadbacks(it)
    }

    @Test
    fun realCanonicalRowsWithCorruptUnpaidRemainderCannotReachStsOrBeBackfilled() = withFixture {
        TestInstallationManifestPublicationCasesV1.unpaidRemainderRefusesBeforeProviders(it)
    }

    @Test
    fun mixedOwnerAdminAndEmptyAllWithFourTotalAdminVersionsCloseIntoGenuineDeletedManifest() = withFixture {
        TestInstallationManifestFamilyJoinCasesV1.mixedEmittedFamiliesAndAliases(it)
    }

    @Test
    fun postDrainMissingAdminReceiptAndPaidAuditEvidenceRefuseBeforeManifestProviders() {
        for (publication in listOf(false, true)) withFixture {
            TestInstallationManifestFamilyJoinCasesV1.missingReceiptAndPaidAuditRefuse(it, publication)
        }
    }

    private fun completion(step: TestInstallationManifestPublicationStepV1, cut: TestRegistrationCompletionCut) =
        withFixture { TestInstallationManifestPublicationFailureCasesV1.completion(it, step, cut) }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use { it.bind(); action(it) }
}
