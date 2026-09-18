package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.SecretMaterialPurpose
import me.manga.kira.backend.security.VersionedSecretBinding
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Cold single-host password/SCRAM + verify-full input. No bean, pool, provider attestation or activation authority.
 * Retain the bound owner before explicitly preparing its public trust file; one configuration binds only one root.
 */
internal class VersionBoundPersistenceConfiguration private constructor(
    private val endpoint: ResolvedPersistenceEndpoint,
    private val ordinaryCapacity: Int,
    private val trust: OwnedPersistencePublicTrust,
    authenticationPassword: VersionedSecretBinding,
    private val desiredInstallationOperator: Boolean,
    private val catalogGenesisAuthoring: Boolean,
) {
    val descriptor = EndpointDescriptor(endpoint, authenticationPassword, trust.sha256, trust.byteCount, trust.certificateCount)
    private var adoptedRoot: PersistenceJdbcDriverRoot? = null

    fun bindLifecycleOwner(): PersistenceJdbcLifecycleOwner {
        requireConfiguration(!desiredInstallationOperator && !catalogGenesisAuthoring)
        return PersistenceJdbcLifecycleOwner.versionBound(this)
    }

    /** Explicit cold resource opt-in; the existing default root and v1/v2 inventory remain unchanged. */
    fun bindLifecycleOwnerWithEpochRotation(): PersistenceJdbcLifecycleOwner {
        requireConfiguration(!desiredInstallationOperator && !catalogGenesisAuthoring)
        return PersistenceJdbcLifecycleOwner.versionBoundWithEpochRotation(this)
    }

    /** Only the explicit non-web installer uses this fixed login/root. This is a route, not launch qualification. */
    internal fun bindDesiredInstallationOperatorOwner(): PersistenceJdbcLifecycleOwner {
        requireConfiguration(desiredInstallationOperator)
        return PersistenceJdbcLifecycleOwner.desiredInstallationOperator(this)
    }

    /** Separate fixed author authentication. Never an alternate launch or password for the TARGET graph. */
    internal fun bindCatalogGenesisAuthoringOwner(): PersistenceJdbcLifecycleOwner {
        requireConfiguration(catalogGenesisAuthoring)
        return PersistenceJdbcLifecycleOwner.catalogGenesisAuthoring(this)
    }

    /** Same acquired TARGET endpoint and inventory; restriction is not part of D or a substitute principal. */
    internal fun bindCatalogGenesisFinalizationOwner(): PersistenceJdbcLifecycleOwner {
        requireFinalizerConfiguration()
        return PersistenceJdbcLifecycleOwner.catalogGenesisFinalization(this)
    }

    internal fun bindCatalogGenesisFinalizationOwnerWithEpochRotation(): PersistenceJdbcLifecycleOwner {
        requireFinalizerConfiguration()
        return PersistenceJdbcLifecycleOwner.catalogGenesisFinalizationWithEpochRotation(this)
    }

    /** Fixed no-Sign recovery purpose on the same runtime principal; never an ordinary UNKNOWN launch grant. */
    internal fun bindCatalogSignerRotationRecoveryOwner(epochRotation: Boolean): PersistenceJdbcLifecycleOwner {
        requireFinalizerConfiguration()
        return PersistenceJdbcLifecycleOwner.catalogSignerRotationRecovery(this, epochRotation)
    }

    internal fun createCatalogSignerRotationRecoveryRoot(epochRotation: Boolean): PersistenceJdbcDriverRoot {
        requireFinalizerConfiguration()
        return PersistenceJdbcDriverRoot(
            endpoint, ordinaryCapacity, PersistencePathStyle.POSIX, versionBound = this,
            epochRotationEnabled = epochRotation, catalogSignerRotationRecovery = true,
        )
    }

    internal fun createCatalogGenesisFinalizationRoot(): PersistenceJdbcDriverRoot {
        requireFinalizerConfiguration()
        return PersistenceJdbcDriverRoot(
            endpoint,
            ordinaryCapacity,
            PersistencePathStyle.POSIX,
            versionBound = this,
            catalogGenesisFinalization = true,
        )
    }

    internal fun createCatalogGenesisFinalizationRootWithEpochRotation(): PersistenceJdbcDriverRoot {
        requireFinalizerConfiguration()
        return PersistenceJdbcDriverRoot(
            endpoint,
            ordinaryCapacity,
            PersistencePathStyle.POSIX,
            versionBound = this,
            epochRotationEnabled = true,
            catalogGenesisFinalization = true,
        )
    }

    private fun requireFinalizerConfiguration() {
        val username = descriptor.publicDriverProperties()["user"]
        requireConfiguration(
            !desiredInstallationOperator && !catalogGenesisAuthoring &&
                username != DESIRED_INSTALLATION_OPERATOR_USERNAME && username != CATALOG_GENESIS_AUTHOR_USERNAME,
        )
    }

    internal fun createRoot(): PersistenceJdbcDriverRoot =
        PersistenceJdbcDriverRoot(endpoint, ordinaryCapacity, PersistencePathStyle.POSIX, versionBound = this)

    internal fun createRootWithEpochRotation(): PersistenceJdbcDriverRoot =
        PersistenceJdbcDriverRoot(endpoint, ordinaryCapacity, PersistencePathStyle.POSIX, versionBound = this, epochRotationEnabled = true)

    internal fun createDesiredInstallationOperatorRoot(): PersistenceJdbcDriverRoot = PersistenceJdbcDriverRoot(
        endpoint,
        ordinaryCapacity,
        PersistencePathStyle.POSIX,
        versionBound = this,
        desiredInstallationOperator = true,
    )

    internal fun createCatalogGenesisAuthoringRoot(): PersistenceJdbcDriverRoot = PersistenceJdbcDriverRoot(
        endpoint,
        ordinaryCapacity,
        PersistencePathStyle.POSIX,
        versionBound = this,
        catalogGenesisAuthoring = true,
    )

    /** Root construction calls this before any actor can start. No independent endpoint/trust pairing is accepted. */
    internal fun adopt(
        root: PersistenceJdbcDriverRoot,
        actualEndpoint: ResolvedPersistenceEndpoint,
        capacity: Int,
        pathStyle: PersistencePathStyle,
        sourceOnly: Boolean,
        operatorOnly: Boolean,
        authorOnly: Boolean,
    ): OwnedPersistencePublicTrust {
        requireConfiguration(actualEndpoint === endpoint && capacity == ordinaryCapacity && pathStyle === PersistencePathStyle.POSIX && !sourceOnly)
        requireConfiguration(operatorOnly == desiredInstallationOperator && authorOnly == catalogGenesisAuthoring)
        if (root.catalogGenesisFinalization || root.catalogSignerRotationRecovery) requireFinalizerConfiguration()
        trust.adopt(root)
        adoptedRoot = root
        return trust
    }

    internal fun createPools(root: PersistenceJdbcDriverRoot): VersionBoundPersistencePools {
        requireConfiguration(adoptedRoot === root)
        return VersionBoundPersistencePools.create(root, endpoint, ordinaryCapacity, descriptor)
    }

    internal fun createEpochRotationMaterial(root: PersistenceJdbcDriverRoot): VersionBoundEpochRotationMaterial {
        requireConfiguration(adoptedRoot === root)
        return VersionBoundEpochRotationMaterial.create(endpoint, descriptor)
    }

    /**
     * Only the exact original-provider endpoint input, NOT finalized Hikari/per-role settings or complete D.
     * The generation-local filename and password are excluded; no secret-derived fingerprint is exposed.
     */
    class EndpointDescriptor internal constructor(
        endpoint: ResolvedPersistenceEndpoint,
        val authenticationPassword: VersionedSecretBinding,
        val publicTrustSha256: String,
        val publicTrustByteCount: Int,
        val publicTrustCertificateCount: Int,
    ) {
        private val values = endpoint.driverProperties().let { properties ->
            properties.stringPropertyNames().filterNot { it == "password" || it == "sslrootcert" }
                .associateWith { properties.getProperty(it) }
        }
        val driverUrl: String = endpoint.driverUrl
        val loginBudgetMillis: Long = endpoint.loginPolicy.durationMillis

        fun publicDriverProperties(): Map<String, String> = values.toMutableMap()

        override fun toString(): String = "VersionBoundPersistenceEndpointDescriptor(redacted)"
    }

    override fun toString(): String = "VersionBoundPersistenceConfiguration(redacted)"

    companion object {
        internal const val DESIRED_INSTALLATION_OPERATOR_USERNAME = "kira_complaint_config_operator"
        internal const val CATALOG_GENESIS_AUTHOR_USERNAME = "kira_complaint_catalog_operator"

        /** No trust-file I/O, resolver call, JDBC driver loading, pool construction or actor start. */
        fun fromAcquired(
            authenticationPassword: AcquiredVersionedSecret,
            host: String,
            port: Int,
            database: String,
            username: String,
            ordinaryCapacity: Int,
            publicTrustPem: ByteArray,
            protectedTrustParent: Path,
        ): VersionBoundPersistenceConfiguration = capture(
            authenticationPassword, host, port, database, username, ordinaryCapacity, publicTrustPem, protectedTrustParent, false, false,
        )

        /** A separately acquired DB password; never substitutes credentials in the target process or its D. */
        internal fun forDesiredInstallationOperator(
            authenticationPassword: AcquiredVersionedSecret,
            host: String,
            port: Int,
            database: String,
            publicTrustPem: ByteArray,
            protectedTrustParent: Path,
        ): VersionBoundPersistenceConfiguration = capture(
            authenticationPassword, host, port, database, DESIRED_INSTALLATION_OPERATOR_USERNAME, 1, publicTrustPem, protectedTrustParent, true, false,
        )

        /** No source/runtime identity, desired graph, future envelope pin or caller-selected database principal. */
        internal fun forCatalogGenesisAuthoring(
            authenticationPassword: AcquiredVersionedSecret,
            host: String,
            port: Int,
            database: String,
            publicTrustPem: ByteArray,
            protectedTrustParent: Path,
        ): VersionBoundPersistenceConfiguration = capture(
            authenticationPassword, host, port, database, CATALOG_GENESIS_AUTHOR_USERNAME, 1, publicTrustPem, protectedTrustParent, false, true,
        )

        private fun capture(
            authenticationPassword: AcquiredVersionedSecret,
            host: String,
            port: Int,
            database: String,
            username: String,
            ordinaryCapacity: Int,
            publicTrustPem: ByteArray,
            protectedTrustParent: Path,
            operatorOnly: Boolean,
            authorOnly: Boolean,
        ): VersionBoundPersistenceConfiguration = persistenceBootstrapBoundary {
            val binding = authenticationPassword.descriptor
            requireConfiguration(binding.family === SecretMaterialFamily.DATABASE && binding.purpose === SecretMaterialPurpose.AUTHENTICATION_PASSWORD)
            requireConfiguration(host.length in 1..253 && host.split('.').all { DNS_LABEL.matches(it) })
            requireConfiguration(port in 1..65_535 && DATABASE_NAME.matches(database) && DATABASE_NAME.matches(username))
            requireConfiguration(ordinaryCapacity in 1..64)
            val password = authenticationPassword.useMaterial(::decodePassword)
            val trust = OwnedPersistencePublicTrust.capture(publicTrustPem, protectedTrustParent)
            val endpoint = ResolvedPersistenceEndpoint(
                mapOf(
                    "PGHOST" to host,
                    "PGPORT" to port.toString(),
                    "PGDBNAME" to database,
                    "user" to username,
                    "password" to password,
                    "loginTimeout" to "0",
                    "connectTimeout" to "1",
                    "socketTimeout" to "2",
                    "cancelSignalTimeout" to "1",
                    "sslmode" to "verify-full",
                    "sslfactory" to "org.postgresql.ssl.LibPQFactory",
                    "sslhostnameverifier" to "org.postgresql.ssl.PGjdbcHostnameVerifier",
                    "sslrootcert" to trust.path.toString(),
                    "sslcert" to "",
                    "sslkey" to "",
                    "requireAuth" to "password,scram-sha-256",
                    "scramMaxIterations" to "100000",
                    "gssEncMode" to "disable",
                    "channelBinding" to "prefer",
                ),
                PersistenceLoginPolicy.resolve("2", 2000),
            )
            requireConfiguration(PersistenceNativeSettings.assessOrdinary(endpoint, PersistencePathStyle.POSIX) is PersistenceNativeSettingsResult.Supported)
            VersionBoundPersistenceConfiguration(endpoint, ordinaryCapacity, trust, binding, operatorOnly, authorOnly)
        }

        private fun decodePassword(material: ByteArray): String {
            requireConfiguration(material.isNotEmpty() && material.none { it == 0.toByte() })
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val decoded = decoder.decode(ByteBuffer.wrap(material))
            return try {
                decoded.toString() // No trim, replacement decoding or Base64/SecretString fallback.
            } finally {
                if (decoded.hasArray()) decoded.array().fill('\u0000')
            }
        }

        private fun requireConfiguration(condition: Boolean) {
            if (!condition) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)
        }

        private val DNS_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        private val DATABASE_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,62}")
    }
}
