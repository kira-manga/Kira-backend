package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import org.springframework.jdbc.core.JdbcTemplate

/** Retained by only the original first-D owner. No alternate constructor caller can enter its named phase. */
internal class ComplaintSignedGenesisFirstDAttemptV1 internal constructor(
    private val operator: ComplaintDesiredInstallationOperatorV1,
    private val coordinator: CatalogCoordinatorPersistence,
    private val target: VersionBoundComplaintProcessConfiguration,
    private val release: ComplaintSignedGenesisFirstDInputsV1.Verified,
    internal val budget: PersistenceTimeBudget,
) {
    private val caller = Thread.currentThread()
    private val ownership = coordinator.ownership
    private val desired = target.desiredSettings()
    private val hash = desired.configurationHashBytes()
    internal val desiredGeneration = desired.desiredGeneration
    internal val databaseName = checkNotNull(
        target.pools.descriptors().flatMap { it.openings() }.map { it.publicDriverProperties()["PGDBNAME"] }.distinct().single(),
    )
    private var failed = false
    private var retained: ComplaintSignedGenesisFirstDOperationV1? = null

    init {
        requireDesiredInstallation(desired.implementationSchema == 1 && desiredGeneration == 1L, ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        release.requireTarget(target)
    }

    internal fun requirePhaseEntry(selected: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireRunning()
        requireDesiredInstallation(
            selected === ownership && path === PersistencePhasePath.COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST && coordinator.desiredInstallationOperator,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requirePersistence(selected: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning()
        requireDesiredInstallation(
            selected === ownership && jdbc.dataSource === coordinator.dataSource && coordinator.desiredInstallationOperator,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        coordinator.requireResources()
    }

    internal fun requireRunning() {
        requireDesiredInstallation(!failed && caller === Thread.currentThread() && operator.owns(this), ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
        operator.requireRunning()
        target.requireUnchangedConfiguration()
    }

    internal fun retain(operation: ComplaintSignedGenesisFirstDOperationV1) {
        requireRunning()
        requireDesiredInstallation(retained == null && operation.attempt === this, ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
        retained = operation
    }

    internal fun requireOperation(operation: ComplaintSignedGenesisFirstDOperationV1) {
        requireRunning()
        requireDesiredInstallation(retained === operation && operation.attempt === this, ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
    }

    internal fun releaseArguments(operation: ComplaintSignedGenesisFirstDOperationV1): Array<Any?> {
        requireOperation(operation)
        return release.arguments(operation)
    }

    internal fun requireRelease(operation: ComplaintSignedGenesisFirstDOperationV1, selected: ComplaintSignedGenesisFirstDInputsV1.Verified) {
        requireOperation(operation)
        requireDesiredInstallation(selected === release, ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
    }

    internal fun configurationHash(): ByteArray = hash.copyOf()
    internal fun matchesHash(value: ByteArray?): Boolean = hash.contentEquals(value)
    internal fun abort() {
        failed = true
    }

    override fun toString(): String = "ComplaintSignedGenesisFirstDAttemptV1(original-owner-and-release,redacted)"
}

internal class ComplaintSignedGenesisFirstDResultV1(val transition: ComplaintSignedGenesisFirstDTransitionV1, val desiredGeneration: Long) {
    override fun toString(): String = "ComplaintSignedGenesisFirstDResultV1(historical-only,no-authority)"
}

internal enum class ComplaintSignedGenesisFirstDTransitionV1 { SELECTED, ALREADY_SELECTED }
