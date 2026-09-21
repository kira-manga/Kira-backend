package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.catalog.TerminalQuiescenceCurrentFaultV1
import me.manga.kira.backend.complaint.catalog.TerminalQuiescenceDenialFaultV1
import me.manga.kira.backend.complaint.catalog.TerminalQuiescenceListingFaultV1
import me.manga.kira.backend.complaint.catalog.TerminalQuiescenceReadbackFaultV1
import me.manga.kira.backend.complaint.catalog.TestRegistrationCompletionCut
import me.manga.kira.backend.complaint.catalog.TestTerminalQuiescenceCasesV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** Source-only connected cases. Synthetic policies are not installed denial or provenance; this path remains SEALED. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestTerminalQuiescenceIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestTerminalQuiescenceIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun genuineEmptyRunInventoriesBothSealsAndPurgeTwiceAndRecyclesWithoutSecondLifetimeCharge() = withFixture { TestTerminalQuiescenceCasesV1.successful(it) }
    @Test fun genuineEnrolledRunAlsoInventoriesItsActualManifestAndPreservesEveryPublicationReservationAndIntent() = withFixture { TestTerminalQuiescenceCasesV1.successful(it, enrolled = true) }
    @Test fun uncompletedTerminalSealAndAbsentPreActivationPinCannotMintQuiescence() = withFixture { TestTerminalQuiescenceCasesV1.noAbsentOrUnfinishedParent(it) }
    @Test fun ordinarySignatureDomainCannotAdmitTerminalDenial() = denial(TerminalQuiescenceDenialFaultV1.ORDINARY_SIGNATURE_DOMAIN)
    @Test fun foreignSealedCandidateCannotAdmitTerminalDenial() = denial(TerminalQuiescenceDenialFaultV1.FOREIGN_CANDIDATE)
    @Test fun missingRawEvidenceCannotAdmitTerminalDenial() = denial(TerminalQuiescenceDenialFaultV1.MISSING_EVIDENCE)
    @Test fun unexpectedWholePrefixVersionCannotBeFilteredIntoExpectedSet() = listing(TerminalQuiescenceListingFaultV1.EXTRA_VERSION)
    @Test fun unexpectedWholePrefixKeyCannotBeFilteredIntoExpectedSet() = listing(TerminalQuiescenceListingFaultV1.EXTRA_KEY)
    @Test fun deleteMarkerCannotBeHiddenByNativeVersionDecoding() = listing(TerminalQuiescenceListingFaultV1.DELETE_MARKER)
    @Test fun wrongFrozenMetadataCannotBecomeNativeReadback() = readback(TerminalQuiescenceReadbackFaultV1.WRONG_METADATA)
    @Test fun individuallyValidButDifferentSecondRetentionMetadataCannotBecomeEqualPair() = readback(TerminalQuiescenceReadbackFaultV1.CHANGED_SECOND_RETENTION)
    @Test fun sealRoleIdentityCannotSubstituteForActualRecoveryRole() = withFixture { TestTerminalQuiescenceCasesV1.wrongRecoveryIdentity(it) }
    @Test fun currentEpochDriftRefusesBeforeFirstNativeInventory() = drift(TerminalQuiescenceCurrentFaultV1.EPOCH)
    @Test fun scanFenceDriftRefusesActualFirstPassCompletion() = drift(TerminalQuiescenceCurrentFaultV1.SCAN_FENCE)
    @Test fun freshInstallationSourceDriftRefusesPaidCutAfterBothNativePasses() = drift(TerminalQuiescenceCurrentFaultV1.SOURCE)
    @Test fun cutRollbackCannotRecycleOrRehabilitateOriginal() = completion(TestRegistrationCompletionCut.BEFORE_COMMIT)
    @Test fun lostCutCommitAcknowledgmentCannotAdoptDurableRowsOrRecycle() = completion(TestRegistrationCompletionCut.AFTER_COMMIT)
    @Test fun unknownCutCommitCannotInferCutOrReusePaidPair() = completion(TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)
    @Test fun unresolvedCutCleanupCannotMintReplacementChild() = completion(TestRegistrationCompletionCut.UNRESOLVED_RELEASE)
    @Test fun unprovenActualNativeCleanupRefusesCutAndKeepsFailedOriginalSticky() = withFixture { TestTerminalQuiescenceCasesV1.nativeCleanupFailure(it) }

    private fun denial(fault: TerminalQuiescenceDenialFaultV1) = withFixture { TestTerminalQuiescenceCasesV1.badDenial(it, fault) }
    private fun listing(fault: TerminalQuiescenceListingFaultV1) = withFixture { TestTerminalQuiescenceCasesV1.badListing(it, fault) }
    private fun readback(fault: TerminalQuiescenceReadbackFaultV1) = withFixture { TestTerminalQuiescenceCasesV1.badReadback(it, fault) }
    private fun drift(fault: TerminalQuiescenceCurrentFaultV1) = withFixture { TestTerminalQuiescenceCasesV1.currentDrift(it, fault) }
    private fun completion(fault: TestRegistrationCompletionCut) = withFixture { TestTerminalQuiescenceCasesV1.cutCompletion(it, fault) }
    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use { it.bind(); action(it) }
}
