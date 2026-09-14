package me.manga.kira.backend.sourceconfig.signing

import jakarta.annotation.PostConstruct
import me.manga.kira.backend.config.KiraSigningProperties
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

@Service
@Lazy(false)
class DocumentSigner(private val properties: KiraSigningProperties) {
    /** Every running profile needs valid signing material before this bean can be used. */
    @PostConstruct
    fun validateConfiguration() {
        require(properties.enabled) { configurationMessage("kira.signing.enabled must be true") }
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
            configurationMessage("kira.signing.active-key-id is required and must match ${KEY_ID.pattern}")
        }
        val privateKey = decodePrivate(
            requireNotNull(properties.privateKey?.takeIf { it.isNotBlank() }) {
                configurationMessage("kira.signing.private-key is required")
            },
        )
        val publicKey = decodePublic(
            requireNotNull(publicKeys()[keyId]) {
                configurationMessage("kira.signing.verification-keys must contain the kira.signing.active-key-id")
            },
        )
        val signature = try {
            val signer = Signature.getInstance(DocumentSignatureCodec.ALGORITHM)
            signer.initSign(privateKey)
            signer.update(payload)
            val signed = signer.sign()

            val verifier = Signature.getInstance(DocumentSignatureCodec.ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(payload)
            check(verifier.verify(signed)) {
                configurationMessage("kira.signing.private-key must match the active kira.signing.verification-keys public-key")
            }
            signed
        } catch (_: GeneralSecurityException) {
            // Provider diagnostics may contain material. Do not retain their message or cause.
            throw IllegalArgumentException(configurationMessage("kira.signing.private-key and kira.signing.verification-keys must support Ed25519"))
        }

        return DetachedSignature(
            algorithm = DocumentSignatureCodec.ALGORITHM,
            keyId = keyId,
            signatureBase64 = Base64.getEncoder().encodeToString(signature),
        )
    }

    fun publicKeys(): Map<String, String> {
        val pairs = properties.verificationKeys.map { key ->
            require(KEY_ID.matches(key.keyId)) {
                configurationMessage("kira.signing.verification-keys key-id values must match ${KEY_ID.pattern}")
            }
            require(key.publicKey.isNotBlank()) { configurationMessage("kira.signing.verification-keys public-key values must not be blank") }
            key.keyId to key.publicKey
        }
        require(pairs.map { it.first }.distinct().size == pairs.size) {
            configurationMessage("kira.signing.verification-keys key-id values must be unique")
        }
        return pairs.toMap().toSortedMap()
    }

    private fun decodePrivate(value: String) = runCatching {
        KeyFactory.getInstance(DocumentSignatureCodec.ALGORITHM)
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(value)))
    }.getOrElse {
        throw IllegalArgumentException(configurationMessage("kira.signing.private-key must be Base64 PKCS#8 Ed25519 material"))
    }

    private fun decodePublic(value: String) = runCatching {
        KeyFactory.getInstance(DocumentSignatureCodec.ALGORITHM)
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(value)))
    }.getOrElse {
        throw IllegalArgumentException(configurationMessage("kira.signing.verification-keys public-key values must be Base64 X.509 Ed25519 material"))
    }

    private fun configurationMessage(message: String) = "$message; see docs/LOCAL_DEV.md#local-document-signing"

    private companion object {
        val KEY_ID = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

data class DetachedSignature(val algorithm: String, val keyId: String, val signatureBase64: String)
