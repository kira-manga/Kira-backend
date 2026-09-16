package me.manga.kira.backend.sourceconfig.signing

import me.manga.kira.backend.config.KiraSigningProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

class DocumentSignerTest {
    @Test
    fun `signature authenticates exact bytes and metadata and rejects tampering or wrong key`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val properties =
            KiraSigningProperties(
                enabled = true,
                activeKeyId = "rotation-2",
                privateKey = pair.private.encoded.base64(),
                verificationKeys =
                listOf(
                    KiraSigningProperties.VerificationKey("rotation-1", other.public.encoded.base64()),
                    KiraSigningProperties.VerificationKey("rotation-2", pair.public.encoded.base64()),
                ),
            )
        val input =
            DocumentSigningInput(
                revision = 101,
                checksum = "a".repeat(64),
                createdAt = Instant.parse("2026-07-18T10:00:00Z"),
                previousRevision = 100,
                previousChecksum = "b".repeat(64),
                documentJson = "{\"revision\":101}",
            )
        val signed = requireNotNull(DocumentSigner(properties).sign(input))

        assertTrue(verify(pair.public.encoded, input, signed.signatureBase64))
        assertFalse(verify(pair.public.encoded, input.copy(documentJson = "{\"revision\":101,\"tampered\":true}"), signed.signatureBase64))
        assertFalse(verify(pair.public.encoded, input.copy(previousRevision = 99), signed.signatureBase64))
        assertFalse(verify(other.public.encoded, input, signed.signatureBase64))
        assertNotEquals(properties.verificationKeys[0].publicKey, properties.verificationKeys[1].publicKey)
    }

    private fun verify(publicBytes: ByteArray, input: DocumentSigningInput, signatureBase64: String): Boolean {
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicBytes))
        return Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(DocumentSignatureCodec.payload(input))
            verify(Base64.getDecoder().decode(signatureBase64))
        }
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
}
