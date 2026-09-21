package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.catalog.TestInstallationManifestFamilyJoinCasesV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainAdminBatchFamiliesCasesV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** SOURCE ONLY / NOT RUN. Existing actual registered/PG/native owners; synthetic historical comparisons are explicit. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestOrdinaryDrainAdminBatchFamiliesIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestOrdinaryDrainAdminBatchFamiliesIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test
    fun preparedAndVerifiedBatchPrimariesCompleteBeforeInventoryWithoutRenewedAuthority() {
        for (prepared in listOf(true, false)) withFixture {
            TestOrdinaryDrainAdminBatchFamiliesCasesV1.retainedPrimaries(it, prepared)
        }
    }

    @Test
    fun fourTotalBatchVersionsAcrossThreeKeysPreservePrimaryAndPayOnlyThreeEventSummaries() = withFixture {
        TestOrdinaryDrainAdminBatchFamiliesCasesV1.fourTotalVersions(it)
    }

    @Test
    fun fifthNativeBatchVersionCannotConvertOrPublishOrdinarySeal() = withFixture {
        TestOrdinaryDrainAdminBatchFamiliesCasesV1.fifthVersion(it)
    }

    @Test
    fun genuineOwnerBatchAndEmptyAllWithFourBatchVersionsCloseIntoDeletedManifest() = withFixture {
        TestInstallationManifestFamilyJoinCasesV1.mixedEmittedFamiliesAndAliases(it, adminBatch = true)
    }

    @Test
    fun postDrainMissingBatchReceiptAndPaidAuditRefuseBeforeManifestProviders() {
        for (publication in listOf(false, true)) withFixture {
            TestInstallationManifestFamilyJoinCasesV1.missingReceiptAndPaidAuditRefuse(it, publication, adminBatch = true)
        }
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use { it.bind(); action(it) }
}
