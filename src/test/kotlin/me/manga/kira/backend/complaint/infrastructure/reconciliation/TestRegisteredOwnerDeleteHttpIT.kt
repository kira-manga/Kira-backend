package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** SOURCE ONLY / NOT_COMPILED / NOT_RUN. Registered TEST selection, never default/Core/LIVE activation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRegisteredOwnerDeleteHttpIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRegisteredOwnerDeleteHttpIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun realHttpAuthorizationAndNativeVerificationStayPendingUntilSeparateQueueCompletionThenReplayExact204() = withFixture {
        TestRegisteredOwnerDeleteHttpCasesV1.authorizationQueueAndReceipts(it)
    }
    @Test fun actualCheckpointExpiryRefusesNewHttpDeleteWithoutNativeWorkOrReceipt() = withFixture {
        TestRegisteredOwnerDeleteHttpCasesV1.expiredCurrent(it)
    }
    @Test fun nativeReadbackFailureCannotVerifyEraseOrCompleteAndOriginalCleanupStillRuns() = withFixture {
        TestRegisteredOwnerDeleteHttpCasesV1.nativeFailure(it)
    }
    @Test fun originalShutdownStopsAdmissionAndWaitsForDeletionAndNativeOwnersBeforeDisposingJpa() {
        for (nativeOnly in listOf(false, true)) withFixture {
            TestRegisteredOwnerDeleteHttpCasesV1.originalShutdown(it, nativeOnly)
        }
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
}
