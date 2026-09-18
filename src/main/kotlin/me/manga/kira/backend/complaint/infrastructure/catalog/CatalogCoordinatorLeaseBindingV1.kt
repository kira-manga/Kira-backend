package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.EpochRotationPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import org.springframework.jdbc.core.JdbcTemplate
import java.util.HexFormat
import java.util.UUID

/**
 * Exact retained LIVE process plus genuine released G1/current refresh or the fixed delivery owner's
 * actual head1 snapshot and raw fixed2 fold. This comparison is not current DB leadership, catalog
 * freshness, a checkpoint or deployment/restore authority.
 * No opaque-D, raw tuple or supplied CatalogCommonHeadEvidence factory exists.
 */
internal class CatalogCoordinatorLeaseBindingV1 private constructor(
    private val process: VersionBoundComplaintProcessConfiguration,
    private val catalog: CatalogCommonHeadEvidence?,
    private val preparedRecovery: CatalogSignerRotationPreparedRecoveryV1? = null,
    private val initialAuthor: CatalogSignerRotationInitialAuthorV1? = null,
    private val delivery: CatalogSignerRotationDeliveryV1? = null,
    deliveryReadback: CatalogDualLocationVerifier.Overlap2Readback? = null,
) {
    internal val coordinator = process.pools.catalogCoordinator
    private val ownership = coordinator.ownership
    private val manager = coordinator.manager
    private val source = coordinator.dataSource
    private val desired = process.desiredSettings()
    private val desiredHash = process.configurationHashBytes()
    private val journal = process.consumers.journalConfiguration
    private val writer = UUID.fromString(journal.declaration().writer.generationId)
    private val epochRotationMillis = journal.declaration().limits.deadlines.epochRotationMillis

    // A delivery raw tail may already be2 while SQL is still accepted1. Never promote that tail to B.
    private val accepted = catalog?.chain?.tail?.let { CatalogLocalHead(it.generation, it.envelopeSha256) }
        ?: checkNotNull(deliveryReadback).snapshotHead
    private val catalogGeneration = accepted.generation
    private val catalogHash = digest(accepted.envelopeSha256)
    private val trustHash = digest(catalog?.chain?.trust?.currentBundleEnvelopeSha256 ?: checkNotNull(deliveryReadback).currentTrustBundleSha256)
    private val catalogWriter = UUID.fromString(
        catalog?.chain?.tail?.catalogWriterGenerationId ?: checkNotNull(process.catalogSignerRotation).deployment.catalogWriterGenerationId,
    )

    init {
        requireConnectionFree()
        requireCatalogReadback(
            desired.mode === ComplaintInstallationMode.LIVE && desired.scope == ComplaintDataScope.LIVE &&
                desired.implementationSchema == 1 && desired.desiredGeneration > 0 &&
                desired.configurationHashBytes().contentEquals(desiredHash) && desiredHash.size == 32 &&
                writer.version() == 4 && writer.variant() == 2 && catalogWriter.version() == 4 && catalogWriter.variant() == 2 &&
                catalogGeneration >= 1L && (catalog == null || catalog.chain.trust.minimumHeadGeneration <= catalogGeneration),
            CatalogReadbackFailure.INVALID_POLICY,
        )
        requireCatalogReadback(
            (delivery == null) == (deliveryReadback == null) && (catalog == null) == (delivery != null),
            CatalogReadbackFailure.INVALID_POLICY,
        )
        if (delivery != null) {
            delivery.requireBindingInputs(process, checkNotNull(deliveryReadback))
            requireCatalogReadback(catalogGeneration == 1L, CatalogReadbackFailure.INVALID_POLICY)
        }
        requireUnchangedConfiguration()
    }

    /** Exact generation travels with its hash; defensive SQL buffers are detached before phase entry, never cloned or hashed under the row lock. */
    internal fun arguments(): Array<Any?> {
        requireConnectionFree()
        requireUnchangedConfiguration()
        return arrayOf(
            desired.desiredGeneration,
            desiredHash.copyOf(),
            desired.databaseIdentity,
            desired.restoreIdentity,
            writer,
            catalogGeneration,
            catalogHash.copyOf(),
            trustHash.copyOf(),
            catalogWriter,
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
        if (!hasOriginalCoordinatorResources() || process.consumers.journalConfiguration !== journal) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        coordinator.requireResources()
    }

    /** The original J starts the one total budget before nonce generation, phase entry or handoff. */
    internal fun startEpochRotationBudget(): PersistenceTimeBudget {
        requireOrdinaryPurpose()
        requireConnectionFree()
        if (epochRotationMillis !in 1..10_000) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        val budget = PersistenceTimeBudget.start(epochRotationMillis.toLong(), ownership.nanoClock)
        requireUnchangedConfiguration()
        return budget
    }

    /** A NEW seal attempt has its own original same-J deadline, never rotation's spent budget. */
    internal fun startEpochSealBudget(): PersistenceTimeBudget {
        requireOrdinaryPurpose()
        requireConnectionFree()
        val millis = journal.declaration().limits.deadlines.epochSealMillis
        val budget = PersistenceTimeBudget.start(millis.toLong(), ownership.nanoClock)
        requireUnchangedConfiguration()
        return budget
    }

    /** Fixed cold D7/G1 author only. No supplied catalog tuple or rebuilt process can obtain this binding. */
    internal fun startSignerRotationBudget(selected: VersionBoundComplaintProcessConfiguration): PersistenceTimeBudget {
        requireOrdinaryPurpose()
        requireConnectionFree()
        val writer = selected.catalogSignerRotation ?: throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        val budget = PersistenceTimeBudget.start(writer.deployment.totalAttemptMillis, ownership.nanoClock)
        requireSignerRotationProcess(selected)
        return budget
    }

    /** Local identity/configuration checks only; safe while the fixed SQL phase owns the full-B row lock. */
    internal fun requireSignerRotationProcess(selected: VersionBoundComplaintProcessConfiguration) {
        requireOrdinaryPurpose()
        requireSignerRotationConfiguration(selected)
    }

    private fun requireSignerRotationConfiguration(selected: VersionBoundComplaintProcessConfiguration) {
        requireUnchangedConfiguration()
        val writer = process.catalogSignerRotation ?: throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        if (selected !== process || desired.desiredGeneration != 1L || catalogGeneration != 1L) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (process.catalogReadback?.projectedCurrent != false || writer.deployment.catalogWriterGenerationId != catalogWriter.toString()) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        writer.requireRetained(process.pools, checkNotNull(process.catalogReadback))
    }

    internal fun requireRecoveredSignerRotationProcess(
        selected: VersionBoundComplaintProcessConfiguration,
        original: CatalogSignerRotationPreparedRecoveryV1,
    ) {
        requireRecoveryPurpose(original)
        original.requireBoundProcess(this, selected)
        requireSignerRotationConfiguration(selected)
    }

    internal fun recoveredSignerRotationPredecessorHash(
        selected: VersionBoundComplaintProcessConfiguration,
        original: CatalogSignerRotationPreparedRecoveryV1,
    ): String {
        requireRecoveredSignerRotationProcess(selected, original)
        return checkNotNull(catalog).chain.tail.envelopeSha256
    }

    internal fun requireRecoveredSignerRotationPredecessor(
        selected: VersionBoundComplaintProcessConfiguration,
        original: CatalogSignerRotationPreparedRecoveryV1,
        readback: CatalogDualLocationVerifier.SignerRotationAuthorReadback,
    ) {
        requireConnectionFree()
        requireRecoveredSignerRotationProcess(selected, original)
        val observed = readback.commonHeadEvidence()
        requireCatalogReadback(
            observed.chain.tail == checkNotNull(catalog).chain.tail && observed.chain.trust == catalog.chain.trust,
            CatalogReadbackFailure.HEAD_CONFLICT,
        )
    }

    internal fun startInitialAuthorSignerRotationBudget(
        selected: VersionBoundComplaintProcessConfiguration,
        original: CatalogSignerRotationInitialAuthorV1,
    ): PersistenceTimeBudget {
        requireConnectionFree()
        val writer = selected.catalogSignerRotation ?: throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        val budget = PersistenceTimeBudget.start(writer.deployment.totalAttemptMillis, ownership.nanoClock)
        requireInitialAuthorSignerRotationProcess(selected, original)
        return budget
    }

    internal fun requireInitialAuthorSignerRotationProcess(
        selected: VersionBoundComplaintProcessConfiguration,
        original: CatalogSignerRotationInitialAuthorV1,
    ) {
        requireInitialAuthorPurpose(original)
        original.requireBoundProcess(this, selected)
        requireSignerRotationConfiguration(selected)
    }

    internal fun initialAuthorSignerRotationPredecessorHash(
        selected: VersionBoundComplaintProcessConfiguration,
        original: CatalogSignerRotationInitialAuthorV1,
    ): String {
        requireInitialAuthorSignerRotationProcess(selected, original)
        return checkNotNull(catalog).chain.tail.envelopeSha256
    }

    internal fun requireInitialAuthorSignerRotationPredecessor(
        selected: VersionBoundComplaintProcessConfiguration,
        original: CatalogSignerRotationInitialAuthorV1,
        readback: CatalogDualLocationVerifier.SignerRotationAuthorReadback,
    ) {
        requireConnectionFree()
        requireInitialAuthorSignerRotationProcess(selected, original)
        val observed = readback.commonHeadEvidence()
        requireCatalogReadback(
            observed.chain.tail == checkNotNull(catalog).chain.tail && observed.chain.trust == catalog.chain.trust,
            CatalogReadbackFailure.HEAD_CONFLICT,
        )
    }

    @Suppress("ComplexCondition") // Check the exact retained owner plus every incompatible named purpose together.
    internal fun requireInitialAuthorPurpose(original: CatalogSignerRotationInitialAuthorV1) {
        if (initialAuthor !== original || !coordinator.catalogSignerRotationAuthoring || preparedRecovery != null || delivery != null) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    @Suppress("ComplexCondition") // Explicitly reject every retained signer owner and immutable named purpose.
    internal fun requireOrdinaryPurpose() {
        if (preparedRecovery != null || coordinator.catalogSignerRotationRecovery || initialAuthor != null || coordinator.catalogSignerRotationAuthoring ||
            delivery != null || coordinator.catalogSignerRotationDelivery
        ) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    internal fun requireRecoveryPurpose(original: CatalogSignerRotationPreparedRecoveryV1) {
        if (preparedRecovery !== original ||
            !coordinator.catalogSignerRotationRecovery || delivery != null
        ) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    @Suppress("ComplexCondition") // Check the exact retained owner plus every incompatible named purpose together.
    internal fun requireDeliveryPurpose(original: CatalogSignerRotationDeliveryV1) {
        if (delivery !== original || !coordinator.catalogSignerRotationDelivery || preparedRecovery != null || initialAuthor != null) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    /** Existing campaign bounding may discard diagnostics, but never a concrete signer owner's original signal. */
    internal fun observeRecoveryFailure(problem: Throwable) {
        preparedRecovery?.observeFailure(problem)
        initialAuthor?.observeFailure(problem)
        delivery?.observeFailure(problem)
    }

    internal fun signerRotationPredecessorHash(selected: VersionBoundComplaintProcessConfiguration): String {
        requireSignerRotationProcess(selected)
        return checkNotNull(catalog).chain.tail.envelopeSha256
    }

    internal fun requireSignerRotationPredecessor(
        selected: VersionBoundComplaintProcessConfiguration,
        readback: CatalogDualLocationVerifier.SignerRotationAuthorReadback,
    ) {
        requireConnectionFree()
        requireSignerRotationProcess(selected)
        val observed = readback.commonHeadEvidence()
        requireCatalogReadback(
            observed.chain.tail == checkNotNull(catalog).chain.tail && observed.chain.trust == catalog.chain.trust,
            CatalogReadbackFailure.HEAD_CONFLICT,
        )
    }

    internal fun cutoffRouting(): VersionBoundComplaintJournalRouting {
        requireOrdinaryPurpose()
        requireUnchangedConfiguration()
        return process.consumers.journalRouting.also { check(it.journalConfiguration === journal) }
    }

    /** Only the actual cold owner retained before this process's D4/G1; never a caller-selected replacement. */
    internal fun epochSealAcquisition(expectedLanes: JournalPublicationLanesV1): VersionBoundEpochSealAcquisitionV1 {
        requireOrdinaryPurpose()
        requireUnchangedConfiguration()
        val selected = process.epochSealAcquisition ?: throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        selected.requireRetained(cutoffRouting(), expectedLanes)
        return selected
    }

    /** Only the actual retained D3 resource; a fourth pool or independently assembled descriptor is not accepted. */
    internal fun epochRotationResource(): EpochRotationPersistence {
        requireOrdinaryPurpose()
        val selected = process.epochRotation ?: throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        requireEpochRotation(selected)
        return selected
    }

    /** Identity/configuration checks only, safe inside either fixed phase; no connection, hash or provider work. */
    internal fun requireEpochRotation(selected: EpochRotationPersistence) {
        requireOrdinaryPurpose()
        requireUnchangedConfiguration()
        if (process.epochRotation !== selected || process.pools.epochRotation !== selected || !selected.belongsTo(process.pools)) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        selected.requireUnchangedConfiguration()
    }

    private fun hasOriginalCoordinatorOwnership(): Boolean = process.pools.catalogCoordinator === coordinator && coordinator.ownership === ownership

    private fun hasOriginalCoordinatorResources(): Boolean =
        hasOriginalCoordinatorOwnership() && coordinator.manager === manager && coordinator.dataSource === source

    override fun toString(): String = "CatalogCoordinatorLeaseBindingV1(retained-LIVE-projected-catalog,no-current-authority)"

    companion object {
        fun fromRetained(
            process: VersionBoundComplaintProcessConfiguration,
            refresh: CurrentAcceptedCatalogRefreshV1.Result,
        ): CatalogCoordinatorLeaseBindingV1 {
            requireConnectionFree()
            requireCatalogReadback(
                !process.pools.catalogCoordinator.catalogSignerRotationRecovery &&
                    !process.pools.catalogCoordinator.catalogSignerRotationAuthoring && !process.pools.catalogCoordinator.catalogSignerRotationDelivery,
                CatalogReadbackFailure.INVALID_POLICY,
            )
            val catalog = refresh.catalogFor(process)
            requireCatalogReadback(catalog.chain.tail.generation == 1L, CatalogReadbackFailure.INVALID_POLICY)
            return CatalogCoordinatorLeaseBindingV1(process, catalog)
        }

        /** Only the actual closed-provider and committed/released historical revalidation producer admits a later head. */
        fun fromProjectedRetained(
            process: VersionBoundComplaintProcessConfiguration,
            refresh: CurrentProjectedCatalogRefreshV1.Result,
        ): CatalogCoordinatorLeaseBindingV1 {
            requireConnectionFree()
            requireCatalogReadback(
                !process.pools.catalogCoordinator.catalogSignerRotationRecovery &&
                    !process.pools.catalogCoordinator.catalogSignerRotationAuthoring && !process.pools.catalogCoordinator.catalogSignerRotationDelivery,
                CatalogReadbackFailure.INVALID_POLICY,
            )
            val catalog = refresh.catalogFor(process)
            requireCatalogReadback(
                process.catalogReadback?.projectedCurrent == true && catalog.chain.tail.generation > 1L,
                CatalogReadbackFailure.INVALID_POLICY,
            )
            return CatalogCoordinatorLeaseBindingV1(process, catalog)
        }

        internal fun fromInitialAuthor(
            original: CatalogSignerRotationInitialAuthorV1,
            process: VersionBoundComplaintProcessConfiguration,
            refresh: CurrentAcceptedCatalogRefreshV1.Result,
        ): CatalogCoordinatorLeaseBindingV1 {
            requireConnectionFree()
            original.requireBindingInputs(process, refresh)
            val catalog = refresh.catalogFor(process)
            requireCatalogReadback(catalog.chain.tail.generation == 1L, CatalogReadbackFailure.INVALID_POLICY)
            return CatalogCoordinatorLeaseBindingV1(process, catalog, initialAuthor = original)
        }

        /** Actual released snapshot/raw fold retained by one concrete owner; this purpose cannot start any normal campaign consumer. */
        internal fun fromPreparedRecovery(
            original: CatalogSignerRotationPreparedRecoveryV1,
            process: VersionBoundComplaintProcessConfiguration,
            readback: CatalogDualLocationVerifier.SignerRotationAuthorReadback,
        ): CatalogCoordinatorLeaseBindingV1 {
            requireConnectionFree()
            original.requireBindingInputs(process, readback)
            return CatalogCoordinatorLeaseBindingV1(process, readback.commonHeadEvidence(), original)
        }

        internal fun fromDelivery(
            original: CatalogSignerRotationDeliveryV1,
            process: VersionBoundComplaintProcessConfiguration,
            readback: CatalogDualLocationVerifier.Overlap2Readback,
        ): CatalogCoordinatorLeaseBindingV1 {
            requireConnectionFree()
            original.requireBindingInputs(process, readback)
            return CatalogCoordinatorLeaseBindingV1(process, null, delivery = original, deliveryReadback = readback)
        }

        private fun digest(value: String): ByteArray {
            requireCatalogReadback(value.matches(Regex("[0-9a-f]{64}")), CatalogReadbackFailure.INVALID_POLICY)
            return HexFormat.of().parseHex(value)
        }
    }
}
