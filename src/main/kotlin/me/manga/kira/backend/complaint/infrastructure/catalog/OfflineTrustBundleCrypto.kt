package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireOfflineTrustBundle
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** One implemented allowlisted suite; no host signing keys, provider discovery from wire IDs, or signing operation. */
internal object OfflineTrustBundleCrypto {
    // Canonical DER rsaEncryption + NULL, BIT STRING with zero unused bits, RSA-3072 positive modulus.
    private val spkiPrefix = "308201a2300d06092a864886f70d01010105000382018f003082018a0282018100".hexBytes()
    private val spkiSuffix = "0203010001".hexBytes()
    private val parameters = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)

    fun publicKey(bytes: ByteArray): RSAPublicKey {
        requireOfflineTrustBundle(bytes.size == OfflineTrustBundleProtocol.PUBLIC_KEY_BYTES, OfflineTrustBundleFailure.INVALID_PUBLIC_KEY)
        requireOfflineTrustBundle(
            bytes.take(spkiPrefix.size).toByteArray().contentEquals(spkiPrefix) &&
                bytes.takeLast(spkiSuffix.size).toByteArray().contentEquals(spkiSuffix) &&
                (bytes[spkiPrefix.size].toInt() and 0x80) != 0,
            OfflineTrustBundleFailure.INVALID_PUBLIC_KEY,
        )
        try {
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes)) as? RSAPublicKey
                ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_PUBLIC_KEY)
            requireOfflineTrustBundle(
                key.modulus.bitLength() == 3072 && key.modulus.testBit(0) &&
                    key.publicExponent == BigInteger.valueOf(65537) && key.encoded.contentEquals(bytes),
                OfflineTrustBundleFailure.INVALID_PUBLIC_KEY,
            )
            return key
        } catch (_: GeneralSecurityException) {
            throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_PUBLIC_KEY)
        }
    }

    fun decodeBase64(value: String, expectedBytes: Int, failure: OfflineTrustBundleFailure): ByteArray {
        requireOfflineTrustBundle(value.length == ((expectedBytes + 2) / 3) * 4, failure)
        val decoded = try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw OfflineTrustBundleException(failure)
        }
        requireOfflineTrustBundle(decoded.size == expectedBytes && Base64.getEncoder().encodeToString(decoded) == value, failure)
        return decoded
    }

    /** F(domain)||F(kcj-1)||F(root ID)||F(algorithm ID)||F(32 RAW SHA-256 body-digest bytes). */
    fun signatureFrame(rootKeyId: String, canonicalBodyBytes: ByteArray): ByteArray {
        val parts = listOf(
            OfflineTrustBundleProtocol.DOMAIN.toByteArray(Charsets.UTF_8),
            CanonicalJson.CANON_VERSION.toByteArray(Charsets.UTF_8),
            rootKeyId.toByteArray(Charsets.UTF_8),
            OfflineTrustBundleProtocol.ALGORITHM_ID.toByteArray(Charsets.UTF_8),
            MessageDigest.getInstance("SHA-256").digest(canonicalBodyBytes),
        )
        val frame = ByteBuffer.allocate(parts.sumOf { Integer.BYTES + it.size })
        parts.forEach { frame.putInt(it.size).put(it) }
        return frame.array()
    }

    fun verify(root: RSAPublicKey, frame: ByteArray, signatureBytes: ByteArray) {
        try {
            val verifier = Signature.getInstance("RSASSA-PSS")
            verifier.setParameter(parameters)
            verifier.initVerify(root)
            verifier.update(frame) // JCA hashes the frame; do not pass SHA256(frame) here.
            requireOfflineTrustBundle(verifier.verify(signatureBytes), OfflineTrustBundleFailure.INVALID_SIGNATURE)
        } catch (_: GeneralSecurityException) {
            throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_SIGNATURE)
        }
    }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
