package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import org.springframework.jdbc.core.JdbcTemplate
import java.util.HexFormat
import java.util.UUID

/**
 * Exact retained INITIAL_LIVE process plus genuine released G1 refresh. This immutable comparison
 * is not current DB leadership, catalog freshness, a checkpoint or deployment/restore authority.
 * No opaque-D, raw tuple or supplied CatalogCommonHeadEvidence factory exists.
 */
internal class CatalogCoordinatorLeaseBindingV1 private constructor(
    private val process: VersionBoundComplaintProcessConfiguration,
    private val refresh: CurrentAcceptedCatalogRefreshV1.Result,
) {
    internal val coordinator = process.pools.catalogCoordinator
    private val ownership = coordinator.ownership
    private val manager = coordinator.manager
    private val source = coordinator.dataSource
    private val desired = process.desiredSettings()
    private val desiredHash = process.configurationHashBytes()
    private val writer = UUID.fromString(process.consumers.journalConfiguration.declaration().writer.generationId)
    private val catalog = refresh.catalogFor(process)
    private val catalogHash = digest(catalog.chain.tail.envelopeSha256)
    private val trustHash = digest(catalog.chain.trust.currentBundleEnvelopeSha256)
    private val catalogWriter = UUID.fromString(catalog.chain.tail.catalogWriterGenerationId)

    init {
        requireConnectionFree()
        requireCatalogReadback(
            desired.mode === ComplaintInstallationMode.LIVE && desired.scope == ComplaintDataScope.LIVE &&
                desired.implementationSchema == 1 && desired.desiredGeneration > 0 &&
                desired.configurationHashBytes().contentEquals(desiredHash) && desiredHash.size == 32 &&
                writer.version() == 4 && writer.variant() == 2 && catalogWriter.version() == 4 && catalogWriter.variant() == 2 &&
                catalog.chain.tail.generation == 1L && catalog.chain.trust.minimumHeadGeneration <= 1L,
            CatalogReadbackFailure.INVALID_POLICY,
        )
        requireUnchangedConfiguration()
    }

    /** Defensive SQL buffers are detached before phase entry, never cloned or hashed under the row lock. */
    internal fun arguments(): Array<Any?> {
        requireConnectionFree()
        requireUnchangedConfiguration()
        return arrayOf(
            desired.desiredGeneration, desiredHash.copyOf(), desired.databaseIdentity, desired.restoreIdentity,
            writer, catalogHash.copyOf(), trustHash.copyOf(), catalogWriter,
        )
    }

    /** Safe within the original phase: identity/configuration checks only, no connection/JSON/crypto/provider call. */
    internal fun requirePersistence(selected: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireUnchangedConfiguration()
        if (selected !== ownership || selected.manager !== manager || jdbc.dataSource !== source) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    internal fun requireUnchangedConfiguration() {
        process.requireUnchangedConfiguration()
        if (process.pools.catalogCoordinator !== coordinator || coordinator.ownership !== ownership ||
            coordinator.manager !== manager || coordinator.dataSource !== source
        ) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        coordinator.requireResources()
    }

    override fun toString(): String = "CatalogCoordinatorLeaseBindingV1(retained-initial-LIVE-G1,no-current-authority)"

    companion object {
        fun fromRetained(process: VersionBoundComplaintProcessConfiguration, refresh: CurrentAcceptedCatalogRefreshV1.Result): CatalogCoordinatorLeaseBindingV1 {
            requireConnectionFree()
            return CatalogCoordinatorLeaseBindingV1(process, refresh)
        }

        private fun digest(value: String): ByteArray {
            requireCatalogReadback(value.matches(Regex("[0-9a-f]{64}")), CatalogReadbackFailure.INVALID_POLICY)
            return HexFormat.of().parseHex(value)
        }
    }
}
