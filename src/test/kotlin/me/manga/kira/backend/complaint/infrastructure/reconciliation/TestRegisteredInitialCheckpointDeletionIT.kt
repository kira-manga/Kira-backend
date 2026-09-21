package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * SOURCE ONLY / NOT_COMPILED / NOT_RUN. Genuine initial-origin AUTH/native/VERIFY, not direct APPLY,
 * ColdActive, renewed checkpoint, HTTP activation, repeated primary history or deployment acceptance.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRegisteredInitialCheckpointDeletionIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRegisteredInitialCheckpointDeletionIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun genuineOwnerDeleteFirstEpochTwoPrimaryPublishesVerifiesAndReplaysWithoutApply() = withFixture {
        TestRegisteredInitialCheckpointDeletionCasesV1.genuine(it)
    }
    @Test fun genuineOwnerDeleteAllFirstEpochTwoPrimaryPublishesVerifiesAndReplaysWithoutApply() = withFixture(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) {
        TestRegisteredInitialCheckpointDeletionCasesV1.genuine(it)
    }
    @Test fun genuineAdminDeleteUsesRealScopedGrantAndFirstEpochTwoPrimaryWithoutApply() = withFixture(ComplaintJournalDeletionKindV1.ADMIN_DELETE) {
        TestRegisteredInitialCheckpointDeletionCasesV1.genuine(it)
    }
    @Test fun genuineAdminBatchUsesTwoEnrolledOwnersTwoCreatesAndOneCountedFirstPrimary() = withFixture(ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE) {
        TestRegisteredInitialCheckpointDeletionCasesV1.genuine(it)
    }
    @Test fun actualPreparedOnlyStopKeepsReceiptPublicationReservationWithoutProviderOrVerify() = withFixture {
        TestRegisteredInitialCheckpointDeletionCasesV1.preparedOnly(it)
    }
    @Test fun exactRegisteredResourcesIngressThreadReservedLaneAndOpenAuthorityAreMandatory() = withFixture {
        TestRegisteredInitialCheckpointDeletionFailureCasesV1.exactResourcesAndHandoff(it)
    }
    @Test fun creationOnlyClosureStillAllowsPrivacyWhileMaintenanceScanAndCurrentIdentityRefuse() = withFixture {
        TestRegisteredInitialCheckpointDeletionFailureCasesV1.privacyGatesAndCurrentIdentity(it)
    }
    @Test fun checkpointColumnDocumentAndNativeSealDriftCannotIssueTheFirstPrimary() = withFixture {
        TestRegisteredInitialCheckpointDeletionFailureCasesV1.checkpointColumnsAndNativeSeal(it)
    }
    @Test fun actualNativeLaneExhaustionPrecedesAuthAndGenuinePrimaryRejectsSecondAndMixedClaims() = withFixture {
        TestRegisteredInitialCheckpointDeletionFailureCasesV1.laneExhaustionAndUnsupportedSecondPrimary(it)
    }
    @Test fun incorrectInstallationSecretCannotReachAllAuthorizationOrProviders() = withFixture(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) {
        TestRegisteredInitialCheckpointDeletionFailureCasesV1.wrongOwnerSecret(it)
    }
    @Test fun actualIssuedGrantScopeExpiryRevocationAndCurrentAdminAreRechecked() = withFixture(ComplaintJournalDeletionKindV1.ADMIN_DELETE) {
        TestRegisteredInitialCheckpointDeletionFailureCasesV1.genuineGrantAndCurrentAdmin(it)
    }
    @Test fun realReceiptLockLoserReplaysWinnerBeforeFreshnessWithoutASecondCharge() = withFixture {
        TestRegisteredInitialCheckpointDeletionRaceCasesV1.receiptClaimLoser(it)
    }
    @Test fun realReceiptLockLoserStillRechecksTheCurrentActorAfterWaiting() = withFixture {
        TestRegisteredInitialCheckpointDeletionRaceCasesV1.receiptClaimLoser(it, revokeActor = true)
    }
    @Test fun actualCredentialRowWaitCannotOutrunTheCurrentCredentialVersion() = withFixture {
        TestRegisteredInitialCheckpointDeletionRaceCasesV1.credentialWait(it)
    }
    @Test fun actualCounterAndRunWaitsCannotUseTheEarlierRegisteredRunIdentity() = withFixture {
        TestRegisteredInitialCheckpointDeletionRaceCasesV1.counterAndRunWaits(it)
    }
    @Test fun actualTargetRowWaitObservesChangedEtagAndChargesOnlyARejectionReceipt() = withFixture {
        TestRegisteredInitialCheckpointDeletionRaceCasesV1.targetWait(it)
    }
    @Test fun actualScopedGrantWaitCannotConsumeAProofExpiredBeforeItsConsumeStatement() = withFixture(ComplaintJournalDeletionKindV1.ADMIN_DELETE) {
        TestRegisteredInitialCheckpointDeletionRaceCasesV1.grantWait(it)
    }
    @Test fun bornWithShortCheckpointFreshnessExpiresOnActualDatabaseTimeInsideTheSameOriginal() = withFixture(shortFreshness = true) {
        TestRegisteredInitialCheckpointDeletionRaceCasesV1.naturalFreshnessAfterCredential(it)
    }
    @Test fun actualNativeCloseMustReturnBeforeReadbackAndShortSqlVerifyCanEscape() = withFixture {
        TestRegisteredInitialCheckpointDeletionRaceCasesV1.nativeCloseCustody(it)
    }
    @Test fun rollbackUnknownDriverCommitAndCommittedTailNeverReleaseTheFailedOriginalWork() = withFixture {
        TestRegisteredInitialCheckpointDeletionRaceCasesV1.completionFailures(it)
    }

    private fun withFixture(family: ComplaintJournalDeletionKindV1 = ComplaintJournalDeletionKindV1.OWNER_DELETE,
        shortFreshness: Boolean = false, action: (TestRegisteredInitialCheckpointDeletionFixtureV1) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { tls ->
            tls.bind(); withRegisteredInitialCheckpointDeletion(tls, family, shortFreshness = shortFreshness, action = action)
        }
}
