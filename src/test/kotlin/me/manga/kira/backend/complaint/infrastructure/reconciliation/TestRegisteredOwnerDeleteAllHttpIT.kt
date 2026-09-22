package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** SOURCE ONLY / NOT_COMPILED / NOT_RUN. Explicit loopback TEST selection, never default or LIVE activation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRegisteredOwnerDeleteAllHttpIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRegisteredOwnerDeleteAllHttpIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun genuineHttpAllVerifyStays503UntilIndependentBThenFreshBodyAuthenticated204WithoutRecharge() = withFixture {
        TestRegisteredOwnerDeleteAllHttpCasesV1.authenticatedReplayAfterSeparateB(it)
    }
    @Test fun actualCurrentCheckpointExpiryRefusesNewAllWithoutAReceiptOrNativeWork() = withFixture {
        TestRegisteredOwnerDeleteAllHttpCasesV1.expiredCurrentCheckpoint(it)
    }
    @Test fun nativeGetFailureLeavesAuthorizedAllPendingAndActualOwnersCleanUpWithoutRetryOrApply() = withFixture {
        TestRegisteredOwnerDeleteAllHttpCasesV1.nativeReadbackFailure(it)
    }
    @Test fun shutdownStopsIngressRevokesRegistrationAndRetainsBothPublishersUntilOriginalAllLaneRelease() = withFixture {
        TestRegisteredOwnerDeleteAllHttpCasesV1.originalAllLaneShutdown(it)
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
}
