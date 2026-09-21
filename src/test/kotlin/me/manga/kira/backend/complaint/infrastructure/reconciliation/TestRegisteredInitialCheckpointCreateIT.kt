package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * SOURCE ONLY / NOT_COMPILED / NOT_RUN. Actual PG/TLS and genuine registered native fixture chain;
 * only raw provider HTTP is substituted. Initial-only CREATE and separately selected read/CREATE/REPLY,
 * separately selected EDIT, not recurrence/health/queue, default activation or deployment.
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
    @Test fun explicitReplyStartupCreatesOnlyOwnReportReplyAndReplaysItsExactReceiptWithoutAnotherCharge() = withFixture {
        TestRegisteredHttpStartupCasesV1.ownReportReply(it)
    }
    @Test fun explicitReplyStartupInheritsScopedNoticeKeyThroughReplyToReplyWithoutServerProse() = withFixture {
        TestRegisteredHttpStartupCasesV1.noticeReplyThread(it)
    }
    @Test fun explicitReplyStartupReceiptsForeignParentDenialWithoutContentResourceOrAuditMutation() = withFixture {
        TestRegisteredHttpStartupCasesV1.foreignParentReply(it)
    }
    @Test fun actualRegisteredReplyClaimLoserReplaysBeforeClosedControlsAndNeverDuplicatesCapacityOrAudit() = withFixture {
        withRegisteredInitialCheckpointCreate(it, initialCheckpointCreate = replyInput(), action = TestRegisteredInitialCheckpointCreateRaceCasesV1::replyClaimLoser)
    }
    @Test fun actualReplyParentResourceWaitCrossesCheckpointExpiryAndRollsBackProvisionalChildWhileReplaySurvives() = withFixture {
        withRegisteredInitialCheckpointCreate(it, shortFreshness = true, initialCheckpointCreate = replyInput()) { fixture ->
            TestRegisteredInitialCheckpointCreateRaceCasesV1.replyWaitedCheckpointExpiry(fixture, resource = true)
        }
    }
    @Test fun actualReplyParentContentWaitCrossesCheckpointExpiryBeforeContentOrReceiptCompletionWhileReplaySurvives() = withFixture {
        withRegisteredInitialCheckpointCreate(it, shortFreshness = true, initialCheckpointCreate = replyInput()) { fixture ->
            TestRegisteredInitialCheckpointCreateRaceCasesV1.replyWaitedCheckpointExpiry(fixture, resource = false)
        }
    }
    @Test fun explicitEditStartupUpdatesOnlyOwnReportAndReplaysHistoricalVersionBeforeClosedControls() = withFixture {
        TestRegisteredHttpStartupCasesV1.ownReportEdit(it)
    }
    @Test fun explicitEditStartupUpdatesOwnNoticeReplyWithoutChangingTheScopedNotice() = withFixture {
        TestRegisteredHttpStartupCasesV1.noticeReplyEdit(it)
    }
    @Test fun explicitEditStartupRejectsForeignReportAndSystemNoticeWithoutLockingTheirResourcesOrContent() = withFixture {
        TestRegisteredHttpStartupCasesV1.foreignAndSystemEdit(it)
    }
    @Test fun actualRegisteredEditClaimLoserReplaysBeforeClosedControlAndHistoricalIfMatch() = withFixture {
        withRegisteredInitialCheckpointCreate(it, initialCheckpointCreate = editInput(), action = TestRegisteredInitialCheckpointCreateRaceCasesV1::editClaimLoser)
    }
    @Test fun actualEditResourceWaitCrossesCheckpointExpiryAndRollsBackAllMutationWhileExactReplaySurvives() = withFixture {
        withRegisteredInitialCheckpointCreate(it, shortFreshness = true, initialCheckpointCreate = editInput()) { fixture ->
            TestRegisteredInitialCheckpointCreateRaceCasesV1.editWaitedCheckpointExpiry(fixture, resource = true)
        }
    }
    @Test fun actualEditContentWaitCrossesCheckpointExpiryBeforeMutationOrRejectionWhileExactReplaySurvives() = withFixture {
        withRegisteredInitialCheckpointCreate(it, shortFreshness = true, initialCheckpointCreate = editInput()) { fixture ->
            TestRegisteredInitialCheckpointCreateRaceCasesV1.editWaitedCheckpointExpiry(fixture, resource = false)
        }
    }
    @Test fun registeredEditRequiresSameOriginalOwnerTemplateIngressAndCurrentDesiredIdentityBeforeEvenReceiptReplay() = withFixture {
        withRegisteredInitialCheckpointCreate(it, initialCheckpointCreate = editInput(), action = TestRegisteredInitialCheckpointCreateRaceCasesV1::editExactGraph)
    }
    @Test fun explicitLoopbackStartupWaitsForHeldOriginalIngressBeforeClosingJpaAndLeavesBorrowedPoolsOpen() = withFixture {
        TestRegisteredHttpStartupCasesV1.heldRequestDrainsBeforeJpaClose(it)
    }
    @Test fun originalAssemblyActuallyClosesItsRetainedLoopbackServletAndJpaBeforeNativeTeardown() = withFixture {
        TestRegisteredHttpStartupCasesV1.assemblyClosesRetainedStartup(it)
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
    private fun replyInput() = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.REPLY_PROFILE)
    private fun editInput() = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.EDIT_PROFILE)
}
