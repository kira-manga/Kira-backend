package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallOperationV1
import org.springframework.jdbc.core.JdbcTemplate

/** View of one phase's private fixed-operation boundary. Implementing this cannot replace its retained operation or commit/release predicate. */
internal interface PersistenceDesiredInstall {
    fun requireOperation(jdbc: JdbcTemplate, path: PersistencePhasePath)
    fun retain(operation: ComplaintDesiredInstallOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintDesiredInstallOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: ComplaintDesiredInstallOperationV1)
    fun completed(): Boolean
}

/** Retain this fixed operator owner before binding. No manifest/profile argument and no actor starts. */
internal fun PersistenceJdbcLifecycleOwner.bindDesiredInstallationOperatorPools(
    nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
): VersionBoundPersistencePools {
    if (!desiredInstallationOperator) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)
    return checkNotNull(versionBoundPools).bind(this, PersistencePoolLaunchProfile.UNKNOWN, nanoClock)
}
