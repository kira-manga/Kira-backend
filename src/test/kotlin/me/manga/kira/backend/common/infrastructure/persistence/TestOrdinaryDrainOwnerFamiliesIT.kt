package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainOwnerFamiliesCasesV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainAdminFamiliesCasesV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** Focused new source cases only; NOT_COMPILED / NOT_RUN, never external denial or deployment evidence. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestOrdinaryDrainOwnerFamiliesIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestOrdinaryDrainOwnerFamiliesIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test
    fun ownerSameKeySecondVersionReachesExactPaidClosureWithoutRewritingPrimary() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.ownerSameKeyCloseout(it)
    }

    @Test
    fun ownerFourTotalVersionsAcrossThreeRetainedKeysReachNativeSeal() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.ownerFourVersionsAcrossRetainedKeys(it)
    }

    @Test
    fun ownerFifthVersionCannotSpendASecondPerKeyAllowance() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.fifthVersionRefuses(it, all = false)
    }

    @Test
    fun ownerConflictingSameKeyCanonicalBodyCannotConvertOrSeal() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.conflictingSameKeyCanonicalBodyRefuses(it, all = false)
    }

    @Test
    fun genuinePreparedEmptyAllCompletesUnderSharedBudgetBeforeDenialCapture() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.preparedEmptyAllCompletesBeforeCapture(it)
    }

    @Test
    fun allFourTotalVersionsAcrossThreeRetainedKeysReachExactPaidClosure() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.allFourVersionsAcrossRetainedKeys(it)
    }

    @Test
    fun allFifthVersionCannotSpendASecondPerKeyAllowance() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.fifthVersionRefuses(it, all = true)
    }

    @Test
    fun allConflictingSameKeyCanonicalBodyCannotConvertOrSeal() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.conflictingSameKeyCanonicalBodyRefuses(it, all = true)
    }

    @Test
    fun mixedOwnerThenAllClosesOnlyThroughGenuineCompletedCompanion() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.mixedOwnerThenAllNeedsRealCompanion(it)
    }

    @Test
    fun allExactAlreadyAppliedVersionErasesNewPaidCurrentContentWithoutDuplicateAppliedCharge() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.allExactReplayErasesNewPaidContent(it)
    }

    @Test
    fun allExactAlreadyAppliedVersionReconstructsOnlyMissingSnapshotResource() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.allExactReplayReconstructsOnlyMissingSnapshotResource(it)
    }

    @Test
    fun allUnattributedAuditUseCannotReleaseAnyResidual() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.allUnattributedAuditUseRefusesConversion(it)
    }

    @Test
    fun mixedSpoofedCompanionCannotReleaseEitherResidual() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.mixedSpoofedCompanionRefusesBothResiduals(it)
    }

    @Test
    fun allRecoveryRollbackKeepsAtomicPendingCopiesAndFreshOriginalPaysNoSecondWitness() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.allRecoveryCompletion(it, committed = false)
    }

    @Test
    fun allRecoveryLostAcknowledgmentRetainsOneUseAndFreshOriginalPaysNoSecondWitness() = withFixture {
        TestOrdinaryDrainOwnerFamiliesCasesV1.allRecoveryCompletion(it, committed = true)
    }

    @Test
    fun registeredAdminPreparedUsesReleasedPrimaryAndOriginalGrantAfterRoleAndExpiryChange() = withFixture {
        TestOrdinaryDrainAdminFamiliesCasesV1.retainedPrimary(it, prepared = true)
    }

    @Test
    fun registeredAdminVerifiedSkipsPublicationAndFreshAuthorization() = withFixture {
        TestOrdinaryDrainAdminFamiliesCasesV1.retainedPrimary(it, prepared = false)
    }

    @Test
    fun registeredAdminFourTotalNativeVersionsCloseWithoutPrimaryGrantOrCredentialRewrite() = withFixture {
        TestOrdinaryDrainAdminFamiliesCasesV1.fourVersions(it)
    }

    @Test
    fun registeredAdminGenuinelyRecoveredIdentityStaysCredentialFreeThroughPaidClosure() = withFixture {
        TestOrdinaryDrainAdminFamiliesCasesV1.recoveredIdentity(it)
    }

    @Test
    fun registeredAdminFifthVersionCannotBorrowAnotherPerKeyAllowance() = withFixture {
        TestOrdinaryDrainAdminFamiliesCasesV1.fifthVersion(it)
    }

    @Test
    fun registeredAdminConflictingTargetOwnerGrantActorOrWireCannotConvert() {
        TestOrdinaryDrainAdminFamiliesCasesV1.Conflict.entries.forEach { conflict ->
            withFixture { TestOrdinaryDrainAdminFamiliesCasesV1.conflictingObject(it, conflict) }
        }
    }

    @Test
    fun registeredAdminNativeInventoryCannotReconstructMissingReceiptPublicationOrReserve() {
        TestOrdinaryDrainAdminFamiliesCasesV1.Missing.entries.forEach { missing ->
            withFixture { TestOrdinaryDrainAdminFamiliesCasesV1.missingPrimaryBookkeeping(it, missing) }
        }
    }

    @Test
    fun registeredAdminAlreadyAppliedTargetRecreatedContentRefusesWithoutAnotherErasureDebit() = withFixture {
        TestOrdinaryDrainAdminFamiliesCasesV1.recreatedAppliedTarget(it)
    }

    @Test
    fun registeredAdminUnattributedAuditUseRefusesResidualRelease() = withFixture {
        TestOrdinaryDrainAdminFamiliesCasesV1.auditClosureRefusal(it, badAttribution = false)
    }

    @Test
    fun registeredAdminWrongActorAttributionRefusesResidualRelease() = withFixture {
        TestOrdinaryDrainAdminFamiliesCasesV1.auditClosureRefusal(it, badAttribution = true)
    }

    @Test
    fun registeredMixedOwnerAdminAndAllCloseThroughResolvedOwnersGenuineCompanion() = withFixture {
        TestOrdinaryDrainAdminFamiliesCasesV1.mixedCompanion(it, spoofed = false)
    }

    @Test
    fun registeredMixedAdminCannotUseDeletedBitOrSpoofedAllCompanion() = withFixture {
        TestOrdinaryDrainAdminFamiliesCasesV1.mixedCompanion(it, spoofed = true)
    }

    @Test
    fun registeredAdminRecoveryRollbackKeepsAtomicCopiesAndPaysNoSecondWitness() = withFixture {
        TestOrdinaryDrainAdminFamiliesCasesV1.recoveryCompletion(it, committed = false)
    }

    @Test
    fun registeredAdminRecoveryLostAcknowledgmentRetainsOneDebitAndPaysNoSecondWitness() = withFixture {
        TestOrdinaryDrainAdminFamiliesCasesV1.recoveryCompletion(it, committed = true)
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use { it.bind(); action(it) }
}
