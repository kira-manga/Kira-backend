package me.manga.kira.backend.security.aws

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.SecretMaterialPurpose
import me.manga.kira.backend.security.SecretVersionFailure
import me.manga.kira.backend.security.VersionedSecretBinding
import me.manga.kira.backend.security.requireSecretVersion
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Retain this inert one-shot bootstrap before bind. The caller separately owns the explicit resolver;
 * this object retains each acquired/configured stage even if a later construction stage fails.
 */
internal class AwsVersionBoundPersistenceBootstrap(private val binding: VersionedSecretBinding, private val resolver: AwsSecretsManagerVersionResolver) {
    private val entered = AtomicBoolean()

    @Volatile var acquired: AcquiredVersionedSecret? = null
        private set
    @Volatile var configuration: VersionBoundPersistenceConfiguration? = null
        private set
    @Volatile var owner: PersistenceJdbcLifecycleOwner? = null
        private set

    init {
        requireSecretVersion(
            binding.family == SecretMaterialFamily.DATABASE && binding.purpose == SecretMaterialPurpose.AUTHENTICATION_PASSWORD,
            SecretVersionFailure.INVALID_BINDING,
        )
    }

    /** No trust-file preparation, pool construction, JDBC, retry or activation; retain the returned owner before those steps. */
    fun bind(
        host: String,
        port: Int,
        database: String,
        username: String,
        ordinaryCapacity: Int,
        publicTrustPem: ByteArray,
        protectedTrustParent: Path,
    ): PersistenceJdbcLifecycleOwner {
        requireConnectionFree()
        requireSecretVersion(entered.compareAndSet(false, true), SecretVersionFailure.INVALID_BINDING)
        val captured = AcquiredVersionedSecret.acquire(binding, resolver)
        acquired = captured
        val configured = VersionBoundPersistenceConfiguration.fromAcquired(
            captured, host, port, database, username, ordinaryCapacity, publicTrustPem, protectedTrustParent,
        )
        configuration = configured
        val bound = configured.bindLifecycleOwner()
        owner = bound
        return bound
    }

    override fun toString(): String = "AwsVersionBoundPersistenceBootstrap(redacted,one-shot,no-activation-authority)"
}
