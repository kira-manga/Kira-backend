package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** SOURCE ONLY / NOT_COMPILED / NOT_RUN. No supplied activation, current checkpoint or moderation grants. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRegisteredAdminContentHttpIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRegisteredAdminContentHttpIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun registeredAdminPasswordEditCountsExactMutationAndReceiptsReplayOriginalGrantWithoutConsumingFreshProof() = withFixture {
        TestRegisteredAdminContentHttpCasesV1.passwordEditAccountingAndReplay(it)
    }
    @Test fun registeredAdminContentUsesCurrentDatabaseIdentityPasswordScopeAndActualIssuedProof() = withFixture {
        TestRegisteredAdminContentHttpCasesV1.currentIdentityPasswordAndProof(it)
    }
    @Test fun registeredAdminContentRequiresOriginalPairAndCurrentCheckpointForNewClaimsButNotTerminalReplay() = withFixture {
        TestRegisteredAdminContentHttpCasesV1.originalPairCheckpointAndReplay(it)
    }
    @Test fun registeredAdminContentAndPasswordIssuanceShareBoundedBodiesEightResponsesAndOriginalCleanup() = withFixture {
        TestRegisteredAdminContentHttpCasesV1.boundedBodiesSharedResponsesAndCleanup(it)
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
}
