package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * SOURCE ONLY / NOT_COMPILED / NOT_RUN. Actual PG/TLS and genuine registered native fixture chain;
 * only raw provider HTTP is substituted. Initial-only CREATE and explicit read/CREATE HTTP subsets,
 * not recurrence/reply/health/queue, default activation or deployment.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRegisteredInitialCheckpointCreateIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRegisteredInitialCheckpointCreateIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun genuineRegisteredCheckpointAllowsRepeatedCountedCreateAndReceiptReplayBeforeClosedControlAndQuotas() = withFixture {
        withRegisteredInitialCheckpointCreate(it, action = TestRegisteredInitialCheckpointCreateCasesV1::genuineRepeatedCreateAndReplay)
    }
    @Test fun factoryRejectsDesiredOnlyForeignTemplateOwnerIngressReplyAndClosedRegistration() = withFixture {
        withRegisteredInitialCheckpointCreate(it, action = TestRegisteredInitialCheckpointCreateCasesV1::exactGraphOnly)
    }
    @Test fun nativeVerifiedSealAndRegistrationAloneCannotReplaceTheCurrentCheckpoint() = withFixture {
        withRegisteredInitialCheckpointCreate(it, completeCheckpoint = false, action = TestRegisteredInitialCheckpointCreateCasesV1::missingCheckpoint)
    }
    @Test fun allSeventeenCheckpointColumnsAndFullCurrentBindingsAreCheckedWithoutMintingEligibilityFromBytes() = withFixture {
        withRegisteredInitialCheckpointCreate(it, action = TestRegisteredInitialCheckpointCreateCasesV1::persistedDrift)
    }
    @Test fun actualClaimLoserObservesWinnerBeforeChangedControlFreshnessOrAnotherCapacityCharge() = withFixture {
        withRegisteredInitialCheckpointCreate(it, action = TestRegisteredInitialCheckpointCreateRaceCasesV1::claimLoser)
    }
    @Test fun actualClaimLoserRefusesCurrentDesiredIdentityDriftWithoutAnotherCapacityCharge() = withFixture {
        withRegisteredInitialCheckpointCreate(it, action = TestRegisteredInitialCheckpointCreateRaceCasesV1::desiredIdentityClaimLoser)
    }
    @Test fun bornWithShortFreshnessExpiresOnDatabaseTimeAfterActorAcquisitionButExactReceiptsRemainReplayable() = withFixture {
        withRegisteredInitialCheckpointCreate(it, shortFreshness = true, action = TestRegisteredInitialCheckpointCreateRaceCasesV1::naturalFreshness)
    }
    @Test fun actualCredentialRowWaitCannotOutrunCurrentActorRevalidation() = withFixture {
        withRegisteredInitialCheckpointCreate(it, action = TestRegisteredInitialCheckpointCreateRaceCasesV1::credentialWait)
    }
    @Test fun rollbackUnknownCommitAndCommittedTailFailureNeverReleaseAnUnprovedOriginalResult() = withFixture {
        withRegisteredInitialCheckpointCreate(it, action = TestRegisteredInitialCheckpointCreateRaceCasesV1::completionFailures)
    }
    @Test fun registeredHttpSubsetUsesOneIngressForBootstrapIdentityCreateAndCurrentReceiptStatus() = withFixture {
        withRegisteredInitialCheckpointCreate(it, action = TestRegisteredInitialCheckpointCreateHttpCasesV1::identityCreateAndReceipts)
    }
    @Test fun registeredHttpSubsetDeniesUnimplementedAliasesBeforeBodyAndRejectsUnboundResources() = withFixture {
        withRegisteredInitialCheckpointCreate(it, action = TestRegisteredInitialCheckpointCreateHttpCasesV1::exactSubsetAndResources)
    }
    @Test fun registeredHttpSubsetCannotCreateBeforeCheckpointAndBootstrapOnlyNeverExpands() = withFixture {
        withRegisteredInitialCheckpointCreate(it, completeCheckpoint = false, action = TestRegisteredInitialCheckpointCreateHttpCasesV1::missingCheckpointAndBootstrapOnly)
    }

    @Test fun explicitLoopbackStartupServesRegisteredIdentityCountedCreateAndReceiptsWithOriginalJpa() = withFixture {
        TestRegisteredHttpStartupCasesV1.identityCreateAndReceipts(it)
    }
    @Test fun explicitLoopbackStartupReadsOnlyOwnCreatedItemsAndScopedNoticesWithBoundCursorAndNoReadMutation() = withFixture {
        TestRegisteredHttpStartupCasesV1.ownerReadsNoticesAndCursor(it)
    }
    @Test fun explicitLoopbackStartupWaitsForHeldOriginalIngressBeforeClosingJpaAndLeavesBorrowedPoolsOpen() = withFixture {
        TestRegisteredHttpStartupCasesV1.heldRequestDrainsBeforeJpaClose(it)
    }
    @Test fun originalAssemblyActuallyClosesItsRetainedLoopbackServletAndJpaBeforeNativeTeardown() = withFixture {
        TestRegisteredHttpStartupCasesV1.assemblyClosesRetainedStartup(it)
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
}
