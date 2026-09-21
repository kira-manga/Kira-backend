package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * SOURCE ONLY: NOT_COMPILED / NOT_RUN / NOT_RUNTIME_ACCEPTED. Genuine EMPTY and synthetic resolver
 * checks are deliberately separate selectors. No test is a SUCCESS/checkpoint/content grant.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestActiveOrdinarySealIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestActiveOrdinarySealIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun genuineUnusedEmptyCaptureImmediatelySealsWithoutSecondChargeOrHealth() = withFixture {
        TestActiveOrdinarySealCasesV1.genuineEmpty(it, enrolled = false)
    }

    @Test fun genuineIdentityEnrolledEmptyCaptureSealsWithCurrentReserveShape() = withFixture {
        TestActiveOrdinarySealCasesV1.genuineEmpty(it, enrolled = true)
    }

    @Test fun genuineEmptyLostPutAcknowledgmentRequiresExactNativeReadback() = withFixture {
        TestActiveOrdinarySealCasesV1.genuineEmpty(it, enrolled = false, lostPutAcknowledgment = true)
    }

    @Test fun wrongNativeRoleVersionWireMetadataLockAndRetentionCannotVerify() {
        ActiveSealProviderCut.entries.forEach { cut -> withFixture { TestActiveOrdinarySealFailureCasesV1.provider(it, cut) } }
    }

    @Test fun canonicalWireAndVerificationCommitCutsNeverRepairOriginalFromVisibleRows() {
        listOf(TestActiveOrdinarySealStepV1.CANONICAL, TestActiveOrdinarySealStepV1.FREEZE, TestActiveOrdinarySealStepV1.VERIFY).forEach { step ->
            listOf(ActiveSealCompletionCut.BEFORE_COMMIT, ActiveSealCompletionCut.AFTER_COMMIT, ActiveSealCompletionCut.DEFERRED_COMMIT_UNKNOWN).forEach { cut ->
                withFixture { TestActiveOrdinarySealFailureCasesV1.completion(it, step, cut) }
            }
        }
    }

    @Test fun unreleasedWireCommitCannotDispatchOrReleaseNativeLane() = withFixture {
        TestActiveOrdinarySealFailureCasesV1.completion(it, TestActiveOrdinarySealStepV1.FREEZE, ActiveSealCompletionCut.UNRESOLVED_RELEASE)
    }

    @Test fun cancellationExpiryReplacementAndLateCleanupRefuseOriginalSeal() {
        ActiveSealLifetimeCut.entries.forEach { cut -> withFixture { TestActiveOrdinarySealFailureCasesV1.lifetime(it, cut) } }
    }

    @Test fun failedActualNativeCloseKeepsSharedLaneAndCannotBeRetriedAsSuccess() = withFixture {
        TestActiveOrdinarySealFailureCasesV1.failedNativeCloseKeepsOriginalLane(it)
    }

    @Test fun syntheticPreparedFourFamiliesAcrossPagesGetActualNativeVerificationNotAuthorization() = withFixture {
        TestActiveSyntheticResolverCasesV1.fourFamiliesAcrossPages(it)
    }

    @Test fun syntheticForeignWriterLowerBoundAliasUnsupportedFamilyAndMalformedCanonicalRefuse() {
        ActiveSyntheticRowCut.entries.forEach { cut -> withFixture { TestActiveSyntheticResolverCasesV1.malformedRowsRefuseBeforeNativeSeal(it, cut) } }
    }

    @Test fun syntheticUnresolvedPreparedWorkUsesSameWireAndCannotSeal() = withFixture {
        TestActiveSyntheticResolverCasesV1.unresolvedNativePublicationCannotSeal(it)
    }

    @Test fun syntheticPreparedEvidenceCommitUnknownIsNotRepairedByActualObjectVisibility() = withFixture {
        TestActiveSyntheticResolverCasesV1.evidenceCommitUnknownCannotRehabilitateOriginal(it)
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
}
