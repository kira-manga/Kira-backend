package me.manga.kira.backend.security

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Explicit key input only. No configuration defaults, beans, persistence or remote key lookup. */
internal class InstallationJwtKeyMaterial(val id: String, material: ByteArray) {
    private val bytes: ByteArray
    private val separationTag: ByteArray

    init {
        require(validInstallationKeyId(id) && material.size in 32..128) { INVALID_KEY_CONFIGURATION }
        bytes = material.copyOf()
        // Compare effective HMAC keys as well as literal duplicates: zero padding and hashing a
        // long key can otherwise make two different byte arrays the same HS256 signing key.
        separationTag = Mac.getInstance("HmacSHA256").run {
            init(secretKey())
            doFinal("kira-installation-key-separation-v1".toByteArray(Charsets.US_ASCII))
        }
    }

    internal fun snapshot(): InstallationJwtKeyMaterial = InstallationJwtKeyMaterial(id, bytes)

    internal fun secretKey(): SecretKey = SecretKeySpec(bytes, "HmacSHA256")

    internal fun sameSecret(other: InstallationJwtKeyMaterial): Boolean = MessageDigest.isEqual(separationTag, other.separationTag)

    override fun toString(): String = "InstallationJwtKeyMaterial(redacted)"
}

/** The actual user/admin family must be supplied explicitly, including every retained verifier. */
internal class InstallationJwtForbiddenFamily(val issuer: String, val audience: String, keys: List<InstallationJwtKeyMaterial>) {
    private val retainedKeys = snapshotInstallationKeys(keys)

    init {
        require(issuer.length in 1..256 && audience.length in 1..256 && issuer.isNotBlank() && audience.isNotBlank()) {
            INVALID_KEY_CONFIGURATION
        }
        require(issuer != audience) { INVALID_KEY_CONFIGURATION }
    }

    internal fun keys(): List<InstallationJwtKeyMaterial> = retainedKeys.map { it.snapshot() }

    override fun toString(): String = "InstallationJwtForbiddenFamily(redacted)"
}

/**
 * Dormant, immutable HS256 ring: one explicit active signer and at most eight verifiers. The
 * caller owns rollout/retention policy; constructing this value does not activate any capability.
 */
internal class InstallationJwtKeyRing(
    val activeKeyId: String,
    verificationKeys: List<InstallationJwtKeyMaterial>,
    forbiddenUserFamily: InstallationJwtForbiddenFamily,
) {
    private val retainedKeys = snapshotInstallationKeys(verificationKeys)
    private val keysById = retainedKeys.associateBy { it.id }

    init {
        require(validInstallationKeyId(activeKeyId) && keysById.containsKey(activeKeyId)) { INVALID_KEY_CONFIGURATION }
        val reservedFamily = setOf(InstallationJwtCodec.ISSUER, InstallationJwtCodec.AUDIENCE)
        require(forbiddenUserFamily.issuer !in reservedFamily && forbiddenUserFamily.audience !in reservedFamily) { INVALID_KEY_CONFIGURATION }
        val forbiddenKeys = forbiddenUserFamily.keys()
        require(retainedKeys.none { key -> forbiddenKeys.any { it.id == key.id || it.sameSecret(key) } }) { INVALID_KEY_CONFIGURATION }
    }

    internal fun verificationKeyIds(): Set<String> = keysById.keys.toSet()

    internal fun key(id: String): SecretKey? = keysById[id]?.secretKey()

    override fun toString(): String = "InstallationJwtKeyRing(redacted)"
}

internal fun validInstallationKeyId(id: String): Boolean = id.length in 1..64 && INSTALLATION_KEY_ID.matches(id)

private fun snapshotInstallationKeys(keys: List<InstallationJwtKeyMaterial>): List<InstallationJwtKeyMaterial> {
    require(keys.size in 1..8) { INVALID_KEY_CONFIGURATION }
    val copied = keys.map { it.snapshot() }
    require(copied.map { it.id }.distinct().size == copied.size) { INVALID_KEY_CONFIGURATION }
    require(copied.indices.none { index -> copied.take(index).any { it.sameSecret(copied[index]) } }) { INVALID_KEY_CONFIGURATION }
    return copied
}

private val INSTALLATION_KEY_ID = Regex("[A-Za-z0-9._-]{1,64}")
private const val INVALID_KEY_CONFIGURATION = "Invalid installation JWT key configuration"
