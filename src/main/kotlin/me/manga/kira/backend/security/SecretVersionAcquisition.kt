package me.manga.kira.backend.security

import java.util.concurrent.CancellationException

/**
 * Trusted acquisition port. The dormant security.aws adapter requests the exact VersionId and returns
 * the independently reported full ARN/VersionId with that response's decoded SecretBinary bytes.
 * Other implementations must meet the same rule; echoing request labels beside unrelated bytes does not.
 * Provider authentication, permissions and finite I/O/cancellation bounds remain adapter obligations.
 */
internal fun interface SecretVersionResolver {
    fun resolve(version: ImmutableSecretVersion): SecretVersionSnapshot
}

/** A defensively copied resolver report, not a provider attestation or a cryptographically validated key. */
internal class SecretVersionSnapshot(val version: ImmutableSecretVersion, material: ByteArray) {
    init {
        requireSecretVersion(material.size in 1..MAX_MATERIAL_BYTES, SecretVersionFailure.INVALID_MATERIAL)
    }

    private val bytes = material.copyOf()

    internal fun copyMaterial(): ByteArray = bytes.copyOf()

    override fun toString(): String = "SecretVersionSnapshot(redacted)"

    private companion object {
        const val MAX_MATERIAL_BYTES = 65_536
    }
}

/**
 * Exact-reference match and one immutable material snapshot only, never D or activation authority.
 * Actual key-format/strength and same-effective-key checks must still run in the consuming key rings.
 */
internal class AcquiredVersionedSecret private constructor(val descriptor: VersionedSecretBinding, private val material: ByteArray) {
    /** The consumer receives a fresh temporary copy. Existing consumers must copy what they retain. */
    fun <T> useMaterial(consumer: (ByteArray) -> T): T {
        val copy = material.copyOf()
        return try {
            consumer(copy)
        } finally {
            copy.fill(0)
        }
    }

    override fun toString(): String = "AcquiredVersionedSecret(redacted)"

    companion object {
        fun acquire(binding: VersionedSecretBinding, resolver: SecretVersionResolver): AcquiredVersionedSecret {
            requireUninterrupted()
            val snapshot = resolve(resolver, binding.version)
            requireUninterrupted()
            requireSecretVersion(snapshot.version == binding.version, SecretVersionFailure.REFERENCE_MISMATCH)
            val descriptor = VersionedSecretBinding.of(binding.family, binding.purpose, binding.logicalKeyId, snapshot.version)
            return AcquiredVersionedSecret(descriptor, snapshot.copyMaterial())
        }

        /** Only the resolver call is sanitized. Fatal Errors retain their normal propagation; nothing is logged here. */
        @Suppress("TooGenericExceptionCaught")
        private fun resolve(resolver: SecretVersionResolver, version: ImmutableSecretVersion): SecretVersionSnapshot {
            try {
                return resolver.resolve(version)
            } catch (_: CancellationException) {
                throw CancellationException("Secret-version acquisition cancelled.")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SecretVersionException(SecretVersionFailure.INTERRUPTED)
            } catch (_: Exception) {
                throw SecretVersionException(SecretVersionFailure.RESOLVER_FAILURE)
            }
        }

        private fun requireUninterrupted() {
            requireSecretVersion(!Thread.currentThread().isInterrupted, SecretVersionFailure.INTERRUPTED)
        }
    }
}
