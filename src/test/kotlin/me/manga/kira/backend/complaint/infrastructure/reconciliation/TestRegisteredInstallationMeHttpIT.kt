package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** SOURCE ONLY / NOT_COMPILED / NOT_RUN. Genuine shared prerequisite required; no runtime or launch acceptance. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRegisteredInstallationMeHttpIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRegisteredInstallationMeHttpIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun explicitMeStartupReadsCurrentIdentityDuringMaintenanceWithoutCheckpointOrComplaintMutation() = withFixture {
        TestRegisteredInstallationMeHttpCasesV1.currentIdentityAndMaintenance(it)
    }
    @Test fun explicitMeSubsetPreservesOlderRoutesAndOriginalResourcesAndRejectsRetainedAuthenticationAfterClose() = withFixture {
        TestRegisteredInstallationMeHttpCasesV1.exactSubsetResourcesAndLifetime(it)
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
}
