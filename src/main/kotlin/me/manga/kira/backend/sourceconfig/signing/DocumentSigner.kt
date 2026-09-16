package me.manga.kira.backend.sourceconfig.signing

import me.manga.kira.backend.config.KiraSigningProperties
import org.springframework.stereotype.Service
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

@Service
class DocumentSigner(private val properties: KiraSigningProperties) {
    /** Parses and cross-checks active material without exposing it. Called before production serves traffic. */
    fun validateConfiguration() {
        if (!properties.enabled) return
        sign(
            DocumentSigningInput(
                revision = 1,
                checksum = "0".repeat(64),
                createdAt = Instant.EPOCH,
                previousRevision = null,
                previousChecksum = null,
                documentJson = "{}",
            ),
        )
    }

    fun sign(input: DocumentSigningInput): DocumentSignature? {
        val payload = DocumentSignatureCodec.payload(input)
        val detached = signDetached(payload) ?: return null
        return DocumentSignature(
            format = DocumentSignatureCodec.FORMAT,
            algorithm = detached.algorithm,
            keyId = detached.keyId,
            signatureBase64 = detached.signatureBase64,
            previousRevision = input.previousRevision,
            previousChecksum = input.previousChecksum,
        )
    }

    /**
     * Sign an already domain-separated payload with the configured active Ed25519 key. The method is
     * shared by whole-document, catalog-manifest, and immutable source-revision contracts.
     */
    fun signDetached(payload: ByteArray): DetachedSignature? {
        if (!properties.enabled) return null
        val keyId = requireNotNull(properties.activeKeyId?.takeIf(KEY_ID::matches)) {
            "kira.signing.active-key-id is required and must match ${KEY_ID.pattern}"
        }
        val privateKey = decodePrivate(requireNotNull(properties.privateKey) { "kira.signing.private-key is required" })
        val publicKey = decodePublic(requireNotNull(publicKeys()[keyId]) { "public key for active key '$keyId' is required" })
        val signer = Signature.getInstance(DocumentSignatureCodec.ALGORITHM)
        signer.initSign(privateKey)
        signer.update(payload)
        val signature = signer.sign()

        val verifier = Signature.getInstance(DocumentSignatureCodec.ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(payload)
        check(verifier.verify(signature)) { "active Ed25519 private key does not match its configured public key" }

        return DetachedSignature(
            algorithm = DocumentSignatureCodec.ALGORITHM,
            keyId = keyId,
            signatureBase64 = Base64.getEncoder().encodeToString(signature),
        )
    }

    fun publicKeys(): Map<String, String> {
        val pairs = properties.verificationKeys.map { key ->
            require(KEY_ID.matches(key.keyId)) { "signing verification key ids must match ${KEY_ID.pattern}" }
            require(key.publicKey.isNotBlank()) { "public key '${key.keyId}' must not be blank" }
            key.keyId to key.publicKey
        }
        require(pairs.map { it.first }.distinct().size == pairs.size) { "signing verification key ids must be unique" }
        return pairs.toMap().toSortedMap()
    }

    private fun decodePrivate(value: String) = runCatching {
        KeyFactory.getInstance(DocumentSignatureCodec.ALGORITHM)
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(value)))
    }.getOrElse { throw IllegalArgumentException("kira.signing.private-key must be Base64 PKCS#8 Ed25519 material", it) }

    private fun decodePublic(value: String) = runCatching {
        KeyFactory.getInstance(DocumentSignatureCodec.ALGORITHM)
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(value)))
    }.getOrElse { throw IllegalArgumentException("kira.signing.public-keys values must be Base64 X.509 Ed25519 material", it) }

    private companion object {
        val KEY_ID = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

data class DetachedSignature(val algorithm: String, val keyId: String, val signatureBase64: String)
