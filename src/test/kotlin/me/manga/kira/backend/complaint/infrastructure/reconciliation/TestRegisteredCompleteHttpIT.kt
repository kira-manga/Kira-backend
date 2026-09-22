package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** SOURCE ONLY / NOT_COMPILED / NOT_RUN. One explicit TEST listener, never default/LIVE or request-time B. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRegisteredCompleteHttpIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRegisteredCompleteHttpIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun actualLoginSharesSourceProofConsumerAndBoundComplaintEditWithoutCrossScopeAuthority() = withFixture {
        TestRegisteredCompleteHttpCasesV1.sharedLoginSourceAndBoundComplaint(it)
    }
    @Test fun actualSourcePreviewAsyncResponseAndLateCompletionTailDrainBeforeJpaClose() = withFixture {
        TestRegisteredCompleteHttpCasesV1.sharedPreviewAsyncAndCompletionTailDrain(it)
    }

    @Test fun genuineSingleAdminHttpVerifyRemains503UntilSeparateBAndFreshCurrentAuthenticated204() = withFixture {
        TestRegisteredCompleteHttpCasesV1.separateBAndExactReceipt(it, batch = false)
    }
    @Test fun genuineTwoOwnerBatchHttpVerifyRemains503UntilSeparateBAndExactSorted200() = withFixture {
        TestRegisteredCompleteHttpCasesV1.separateBAndExactReceipt(it, batch = true)
    }
    @Test fun priorOwnerAndAdminFamiliesShareTheSameCompleteListenerAndResponseOwner() = withFixture {
        TestRegisteredCompleteHttpCasesV1.priorFamiliesShareTheCompleteListener(it)
    }
    @Test fun olderStatusOnlySelectionStillRejectsDeleteWithAllColdInputsPresent() = withFixture {
        TestRegisteredCompleteHttpCasesV1.olderSelectorStaysNarrow(it)
    }
    @Test fun batchOfOneNativeGetFailurePreservesConsumedGrantAndPendingOriginalCleanup() = withFixture {
        TestRegisteredCompleteHttpCasesV1.nativeFailureKeepsOriginalGrant(it)
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
}
