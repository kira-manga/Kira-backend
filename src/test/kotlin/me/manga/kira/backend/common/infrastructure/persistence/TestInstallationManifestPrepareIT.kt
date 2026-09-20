package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.catalog.TestInstallationManifestFailureCasesV1
import me.manga.kira.backend.complaint.catalog.TestInstallationManifestPrepareCasesV1
import me.manga.kira.backend.complaint.catalog.TestRegistrationCompletionCut
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** Connected source definitions only; synthetic intake/provider fixtures do not qualify deployed terminal evidence. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestInstallationManifestPrepareIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestInstallationManifestPrepareIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test
    fun genuineStrictDrainPreparesPaidPublicationReservedZeroPromiseAndCanonicalSidecarAtomically() = withFixture {
        TestInstallationManifestPrepareCasesV1.paidPrepare(it)
    }

    @Test
    fun freshFenceReplaysExactPaidWinnerAndHistoricProgressWithoutAnotherChargeOrNetworkCall() = withFixture {
        TestInstallationManifestPrepareCasesV1.exactReplay(it)
    }

    @Test
    fun unfinishedDrainAndBareRegisteredStateCannotEnterManifestPrepare() = withFixture {
        TestInstallationManifestPrepareCasesV1.unfinishedDrainRefuses(it)
    }

    @Test
    fun changedRawCredentialOnActualSecondSourcePassRollsBackCaptureWithoutProgressOrPayment() = withFixture {
        TestInstallationManifestFailureCasesV1.sourceDrift(it, duringPrepare = false)
    }

    @Test
    fun changedRawCredentialOnActualPrepareChunkRereadRollsBackAllManifestEffects() = withFixture {
        TestInstallationManifestFailureCasesV1.sourceDrift(it, duringPrepare = true)
    }

    @Test
    fun staleFenceAfterCommittedChunkCannotPrepareOrPayWithReleasedCanonicalBytes() = withFixture {
        TestInstallationManifestFailureCasesV1.staleFence(it)
    }

    @Test
    fun prepareBeforeCommitFailureRollsBackAllRowsAndFreshOriginalPaysExactlyOnce() = completion(TestRegistrationCompletionCut.BEFORE_COMMIT)

    @Test
    fun prepareAfterCommitAcknowledgmentLossReloadsExactWinnerWithoutRepayment() = completion(TestRegistrationCompletionCut.AFTER_COMMIT)

    @Test
    fun deferredCommitFailureRemainsUnknownAndCannotReportPreparedSuccess() = completion(TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)

    @Test
    fun unresolvedPrepareReleaseQuarantinesOriginalAndRefusesNextEntryUntilActualRetirement() = completion(TestRegistrationCompletionCut.UNRESOLVED_RELEASE)

    @Test
    fun realCanonicalRowsWithoutTheirExactPaidRemainderAreNotAdoptedOrBackfilled() = withFixture {
        TestInstallationManifestFailureCasesV1.unpaidWinnerRefuses(it)
    }

    private fun completion(cut: TestRegistrationCompletionCut) = withFixture { TestInstallationManifestFailureCasesV1.completion(it, cut) }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use { it.bind(); action(it) }
}
