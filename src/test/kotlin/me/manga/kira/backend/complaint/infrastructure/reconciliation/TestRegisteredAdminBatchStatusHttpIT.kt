package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** SOURCE ONLY / NOT_COMPILED / NOT_RUN. Existing genuine registered status/content graph, not a new harness. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRegisteredAdminBatchStatusHttpIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRegisteredAdminBatchStatusHttpIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun registeredAdminBatchStatusChangesMultipleOwnersAtomicallyAndRejectsStaleMissingAndNoOpWithoutPartialEffects() = withFixture {
        TestRegisteredAdminBatchStatusHttpCasesV1.atomicAccountingAndBounds(it)
    }
    @Test fun registeredAdminBatchStatusReplaysOriginalGrantAcrossKeysCurrentPrincipalAndCheckpointChanges() = withFixture {
        TestRegisteredAdminBatchStatusHttpCasesV1.replayAndCurrentState(it)
    }
    @Test fun registeredAdminBatchStatusSharesOriginalBudgetsAndEightResponsesWithBoundedStatusOnlyBodiesAndCleanup() = withFixture {
        TestRegisteredAdminBatchStatusHttpCasesV1.quotaBodiesResponsesAndCleanup(it)
    }
    @Test fun registeredAdminStatusOnlySelectionKeepsDeclaredBatchRouteClosedAndOriginalPair() = withFixture {
        TestRegisteredAdminBatchStatusHttpCasesV1.oldStatusSelectionAndOriginalPair(it)
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
}
