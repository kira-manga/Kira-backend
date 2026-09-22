package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** SOURCE ONLY / NOT_COMPILED / NOT_RUN. The unchanged genuine prerequisite is required, not bypassed. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRegisteredAdminReadHttpIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRegisteredAdminReadHttpIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun explicitRegisteredAdminSearchDetailStatsReadBothCreatedOwnersWithBoundCursorAndNoWrites() = withFixture {
        TestRegisteredAdminReadHttpCasesV1.createdOwnersSearchDetailStats(it)
    }
    @Test fun registeredAdminReadsUseConfiguredJwtAndCurrentDatabaseRoleNotDiagnosticClaim() = withFixture {
        TestRegisteredAdminReadHttpCasesV1.currentRoleBearerAndScope(it)
    }
    @Test fun registeredAdminReadSelectionPreservesOldSubsetsAndRejectsForeignPairAndClosedAuthentication() = withFixture {
        TestRegisteredAdminReadHttpCasesV1.exactSubsetResourcesAndLifetime(it)
    }
    @Test fun registeredAdminAndOwnerReadsShareEightResponseSlotsOneReadQuotaAndOriginalCleanup() = withFixture {
        TestRegisteredAdminReadHttpCasesV1.sharedResponsesQuotaAndCleanup(it)
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
}
