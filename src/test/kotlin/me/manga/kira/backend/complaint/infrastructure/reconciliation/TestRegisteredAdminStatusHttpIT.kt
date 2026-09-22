package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** SOURCE ONLY / NOT_COMPILED / NOT_RUN. Existing registered content graph; no supplied grant or current authority. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRegisteredAdminStatusHttpIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRegisteredAdminStatusHttpIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun registeredAdminStatusClosureCorrectionAndReopenCountExactTuplesAuditsAndReceipts() = withFixture {
        TestRegisteredAdminStatusHttpCasesV1.transitionsAndAccounting(it)
    }
    @Test fun registeredAdminStatusClosureKeepOperationKeysAndReplayOriginalGrantsAcrossCurrentStateChanges() = withFixture {
        TestRegisteredAdminStatusHttpCasesV1.receiptsAndCurrentState(it)
    }
    @Test fun registeredAdminStatusClosureShareQuotaBoundedBodiesEightResponsesAndCleanupWithContent() = withFixture {
        TestRegisteredAdminStatusHttpCasesV1.quotaBodiesResponsesAndCleanup(it)
    }
    @Test fun registeredAdminContentOnlySelectionKeepsDeclaredStatusRoutesClosedAndOriginalPair() = withFixture {
        TestRegisteredAdminStatusHttpCasesV1.oldContentSelectionAndOriginalPair(it)
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
}
