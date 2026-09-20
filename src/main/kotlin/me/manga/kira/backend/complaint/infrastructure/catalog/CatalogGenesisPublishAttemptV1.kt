package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintCatalogGenesisPublishRecheckOperationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import org.springframework.jdbc.core.JdbcTemplate

/** One publisher's real cold TARGET, fixed operator, frozen comparison and original budget; never a portable selection receipt. */
internal class CatalogGenesisPublishAttemptV1 internal constructor(
    private val operator: CatalogGenesisPublishV1,
    private val coordinator: CatalogCoordinatorPersistence,
    internal val process: VersionBoundComplaintProcessConfiguration,
    private val release: CatalogGenesisPublishReleaseV1,
    private val comparison: ComplaintSignedGenesisFirstDInputsV1.Verified,
    internal val budget: PersistenceTimeBudget,
) {
    private val caller = Thread.currentThread()
    private val ownership = coordinator.ownership
    private val desired = process.desiredSettings()
    private val hash = desired.configurationHashBytes()
    internal val desiredGeneration = desired.desiredGeneration
    internal val databaseName = checkNotNull(
        process.pools.descriptors().flatMap { it.openings() }.map { it.publicDriverProperties()["PGDBNAME"] }.distinct().single(),
    )
    private var retained: ComplaintCatalogGenesisPublishRecheckOperationV1? = null
    private var started = false
    private var rechecking = false
    private var entered = false
    private var failed = false

    init {
        requirePublication(desired.implementationSchema == 1 && desiredGeneration == 1L, CatalogGenesisPublishFailureV1.INPUT_REFUSED)
        comparison.requireTarget(process)
    }

    internal fun recheck() {
        requireConnectionFree()
        requireRunning()
        requirePublication(!started && retained == null, CatalogGenesisPublishFailureV1.PROCESS_REFUSED)
        started = true
        rechecking = true
        try {
            coordinator.catalogGenesisPublishRecheck.recheck(this)
        } finally {
            rechecking = false
        }
        requireSelected()
    }

    internal fun requirePhaseEntry(selected: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireRunning()
        requirePublication(
            selected === ownership && path === PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK &&
                coordinator.desiredInstallationOperator && rechecking && !entered,
            CatalogGenesisPublishFailureV1.PROCESS_REFUSED,
        )
        entered = true
    }

    internal fun requirePersistence(selected: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning()
        requirePublication(
            selected === ownership && jdbc.dataSource === coordinator.dataSource && coordinator.desiredInstallationOperator && rechecking,
            CatalogGenesisPublishFailureV1.PROCESS_REFUSED,
        )
        coordinator.requireResources()
    }

    internal fun retain(operation: ComplaintCatalogGenesisPublishRecheckOperationV1) {
        requireRunning()
        requirePublication(retained == null && operation.attempt === this && rechecking && entered, CatalogGenesisPublishFailureV1.PROCESS_REFUSED)
        retained = operation
    }

    internal fun requireOperation(operation: ComplaintCatalogGenesisPublishRecheckOperationV1) {
        requireRunning()
        requirePublication(retained === operation && operation.attempt === this, CatalogGenesisPublishFailureV1.PROCESS_REFUSED)
    }

    internal fun releaseArguments(operation: ComplaintCatalogGenesisPublishRecheckOperationV1): Array<Any?> {
        requireOperation(operation)
        return comparison.arguments(operation)
    }

    internal fun requireRelease(operation: ComplaintCatalogGenesisPublishRecheckOperationV1, selected: ComplaintSignedGenesisFirstDInputsV1.Verified) {
        requireOperation(operation)
        requirePublication(selected === comparison, CatalogGenesisPublishFailureV1.PROCESS_REFUSED)
    }

    internal fun requireRelease(selected: CatalogGenesisPublishReleaseV1) {
        requireRunning()
        requirePublication(selected === release, CatalogGenesisPublishFailureV1.PROCESS_REFUSED)
    }

    internal fun requireSelected() {
        requireConnectionFree()
        requireRunning()
        requirePublication(started && entered && !rechecking, CatalogGenesisPublishFailureV1.PROCESS_REFUSED)
        checkNotNull(retained).requireReleased() // Rechecks the ORIGINAL operation's known commit and complete holder/permit release.
    }

    internal fun requireRunning() {
        requirePublication(!failed && caller === Thread.currentThread() && operator.owns(this), CatalogGenesisPublishFailureV1.PROCESS_REFUSED)
        operator.requireRunning()
        process.requireUnchangedConfiguration()
    }

    internal fun matchesHash(value: ByteArray?): Boolean = hash.contentEquals(value)
    internal fun abort() {
        failed = true
    }

    override fun toString(): String = "CatalogGenesisPublishAttemptV1(original-operator-target-and-release,redacted)"
}
