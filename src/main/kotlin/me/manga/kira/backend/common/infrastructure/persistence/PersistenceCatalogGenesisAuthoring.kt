package me.manga.kira.backend.common.infrastructure.persistence

/** Named cold author composition only; no profile, principal or phase list can be supplied. */
internal fun PersistenceJdbcLifecycleOwner.bindCatalogGenesisAuthoringPools(
    nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
): VersionBoundPersistencePools {
    if (!catalogGenesisAuthoring) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)
    return checkNotNull(versionBoundPools).bind(this, PersistencePoolLaunchProfile.UNKNOWN, nanoClock)
}
