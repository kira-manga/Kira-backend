package me.manga.kira.backend.common.infrastructure.persistence

/** Fixed non-pooled material from the adopted endpoint. No pool settings or caller-selected driver recipe. */
internal class VersionBoundEpochRotationMaterial private constructor(
    private val endpoint: ResolvedPersistenceEndpoint,
    identity: VersionBoundPersistenceConfiguration.EndpointDescriptor,
) {
    internal val opening = PersistencePgDriverOpening.Configuration.resolve(
        endpoint,
        PersistenceDriverAttemptPolicy.TRACKED_EPOCH_ROTATION_CONJUNCTION,
        PersistencePathStyle.POSIX,
    )
    internal val descriptor = VersionBoundEpochRotationDescriptor(identity, opening)

    internal fun matches(actual: ResolvedPersistenceEndpoint): Boolean = actual === endpoint

    override fun toString(): String = "VersionBoundEpochRotationMaterial(original-root,non-pooled,redacted)"

    companion object {
        internal fun create(
            endpoint: ResolvedPersistenceEndpoint,
            identity: VersionBoundPersistenceConfiguration.EndpointDescriptor,
        ): VersionBoundEpochRotationMaterial = VersionBoundEpochRotationMaterial(endpoint, identity)
    }
}

/** Effective fixed scalars, not measured capacity sufficiency, activation or a completed native session. */
internal class VersionBoundEpochRotationDescriptor internal constructor(
    identity: VersionBoundPersistenceConfiguration.EndpointDescriptor,
    configuration: PersistencePgDriverOpening.Configuration,
) {
    val role = PersistenceJdbcParticipantRole.EPOCH_ROTATION
    val capacity = 1
    val pooled = false
    val sessionPolicy = "FRESH_TERMINAL_ONLY"
    val protocolVersion = 1
    val maximumRotationMillis = EpochRotationLimits.MAXIMUM_ROTATION_MILLIS
    val requestPhaseMillis = EpochRotationLimits.REQUEST_PHASE_MILLIS
    val statementMillis = EpochRotationLimits.STATEMENT_MILLIS
    val controlLockMillis = EpochRotationLimits.CONTROL_LOCK_MILLIS
    val authenticationPassword = identity.authenticationPassword
    val publicTrustSha256 = identity.publicTrustSha256
    val publicTrustByteCount = identity.publicTrustByteCount
    val publicTrustCertificateCount = identity.publicTrustCertificateCount
    val opening = OpeningDescriptor(configuration)

    override fun toString(): String = "VersionBoundEpochRotationDescriptor(non-pooled,no-capacity-qualification)"

    class OpeningDescriptor internal constructor(configuration: PersistencePgDriverOpening.Configuration) {
        val policy = configuration.policy
        val driverUrl = configuration.driverUrl
        val loginBudgetMillis = configuration.loginPolicy.durationMillis
        private val properties = configuration.publicDriverProperties()

        fun publicDriverProperties(): Map<String, String> = properties.toMutableMap()

        override fun toString(): String = "VersionBoundEpochRotationOpeningDescriptor(redacted)"
    }
}

internal object EpochRotationLimits {
    const val MAXIMUM_ROTATION_MILLIS = 10_000L
    const val REQUEST_PHASE_MILLIS = 2_000L
    const val STATEMENT_MILLIS = 1_000L
    const val CONTROL_LOCK_MILLIS = 100L
}
