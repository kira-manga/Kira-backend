package me.manga.kira.backend.security

import java.util.Base64

/**
 * Dormant conversion of explicit installation-security active-key-id and verification-keys values.
 * No binding, defaults, environment/profile lookup or activation. The actual retained user/admin
 * family is mandatory. Key randomness remains an operator obligation; length cannot prove entropy.
 * A Map cannot reveal duplicate configuration names already collapsed by an upstream parser.
 */
internal object InstallationJwtKeyRingFactory {
    fun fromConfiguration(activeKeyId: Any?, verificationKeys: Map<*, *>?, forbiddenUserFamily: InstallationJwtForbiddenFamily): InstallationJwtKeyRing {
        require(activeKeyId is String) { INVALID_KEY_CONFIGURATION }
        require(verificationKeys != null) { INVALID_KEY_CONFIGURATION }
        val count = verificationKeys.size
        require(count in 1..8) { INVALID_KEY_CONFIGURATION }
        val entries = verificationKeys.entries.iterator()
        val keys = ArrayList<InstallationJwtKeyMaterial>(count)
        // Bound traversal as well as allocation; do not trust a changing or inconsistent Map size.
        repeat(count) {
            require(entries.hasNext()) { INVALID_KEY_CONFIGURATION }
            val entry = entries.next()
            keys.add(keyMaterial(entry.key, entry.value))
        }
        require(!entries.hasNext()) { INVALID_KEY_CONFIGURATION }
        return InstallationJwtKeyRing(activeKeyId, keys, forbiddenUserFamily)
    }

    private fun keyMaterial(rawId: Any?, rawEncoded: Any?): InstallationJwtKeyMaterial {
        require(rawId is String && rawEncoded is String) { INVALID_KEY_CONFIGURATION }
        // Check encoded and exact decoded bounds BEFORE invoking the decoder. Standard Base64
        // only: no whitespace, URL alphabet, missing padding or noncanonical unused padding bits.
        require(rawEncoded.length in 44..172 && rawEncoded.length % 4 == 0) { INVALID_KEY_CONFIGURATION }
        require(STANDARD_BASE64.matches(rawEncoded)) { INVALID_KEY_CONFIGURATION }
        val padding = rawEncoded.takeLast(2).count { it == '=' }
        require(rawEncoded.length / 4 * 3 - padding in 32..128) { INVALID_KEY_CONFIGURATION }
        val decoded = decode(rawEncoded)
        try {
            require(Base64.getEncoder().encodeToString(decoded) == rawEncoded) { INVALID_KEY_CONFIGURATION }
            return InstallationJwtKeyMaterial(rawId, decoded)
        } finally {
            decoded.fill(0)
        }
    }

    @Suppress("SwallowedException") // Decoder diagnostics must not enter configuration failures.
    private fun decode(encoded: String): ByteArray = try {
        Base64.getDecoder().decode(encoded)
    } catch (ex: IllegalArgumentException) {
        // Never retain the input or attach a decoder exception to a configuration failure.
        throw IllegalArgumentException(INVALID_KEY_CONFIGURATION)
    }

    private val STANDARD_BASE64 = Regex("[A-Za-z0-9+/]+={0,2}")
    private const val INVALID_KEY_CONFIGURATION = "Invalid installation JWT key configuration"
}
