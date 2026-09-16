package me.manga.kira.backend.sourceconfig.signing

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.admin.AbstractAdminSourceIT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

class SignedDocumentIT : AbstractAdminSourceIT() {
    @Autowired
    private lateinit var signer: DocumentSigner

    @Test
    fun `published documents expose verifiable immutable signature chain and conditional GET metadata`() {
        createSource(SourceConfigFixtures.validGenericSource("Signed")).andExpect { status { isCreated() } }
        val firstRevision = docRevisionOf(publish("Signed", 1).andExpect { status { isOk() } })

        val first = getPublicDocument().andExpect { status { isOk() } }.andReturn().response
        verifyResponse(firstRevision, null, null, first)

        val updated = SourceConfigFixtures.validGenericSource("Signed").copy(baseUrl = "https://v2.example")
        createRevision("Signed", updated)
            .andExpect { status { isCreated() } }
        val secondRevision = docRevisionOf(publish("Signed", 2).andExpect { status { isOk() } })
        val second = getPublicDocument().andExpect { status { isOk() } }.andReturn().response
        verifyResponse(
            firstRevision = secondRevision,
            expectedPreviousRevision = firstRevision,
            expectedPreviousChecksum = first.getHeader("X-Config-Checksum"),
            response = second,
        )

        getPublicDocument(ifNoneMatch = second.getHeader("ETag")).andExpect {
            status { isNotModified() }
            header { exists("X-Config-Signature") }
            header { string("X-Config-Previous-Revision", firstRevision.toString()) }
        }
    }

    private fun verifyResponse(
        firstRevision: Long,
        expectedPreviousRevision: Long?,
        expectedPreviousChecksum: String?,
        response: org.springframework.mock.web.MockHttpServletResponse,
    ) {
        val bytes = response.contentAsByteArray
        val checksum = requireNotNull(response.getHeader("X-Config-Checksum"))
        val createdAt = Instant.parse(requireNotNull(response.getHeader("X-Config-Created-At")))
        val keyId = requireNotNull(response.getHeader("X-Config-Signing-Key-Id"))
        assertEquals(Sha256.hexUtf8(bytes.toString(Charsets.UTF_8)), checksum)
        assertEquals(DocumentSignatureCodec.FORMAT, response.getHeader("X-Config-Signature-Format"))
        assertEquals(DocumentSignatureCodec.ALGORITHM, response.getHeader("X-Config-Signature-Algorithm"))
        assertEquals(expectedPreviousRevision?.toString(), response.getHeader("X-Config-Previous-Revision"))
        assertEquals(expectedPreviousChecksum, response.getHeader("X-Config-Previous-Checksum"))
        if (expectedPreviousRevision == null) assertNull(response.getHeader("X-Config-Previous-Revision"))

        val input =
            DocumentSigningInput(
                revision = firstRevision,
                checksum = checksum,
                createdAt = createdAt,
                previousRevision = expectedPreviousRevision,
                previousChecksum = expectedPreviousChecksum,
                documentJson = bytes.toString(Charsets.UTF_8),
            )
        val publicBytes = Base64.getDecoder().decode(requireNotNull(signer.publicKeys()[keyId]))
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicBytes))
        val valid =
            Signature.getInstance("Ed25519").run {
                initVerify(publicKey)
                update(DocumentSignatureCodec.payload(input))
                verify(Base64.getDecoder().decode(requireNotNull(response.getHeader("X-Config-Signature"))))
            }
        assertTrue(valid)
    }

    companion object {
        private val testKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        @JvmStatic
        @DynamicPropertySource
        fun signingProperties(registry: DynamicPropertyRegistry) {
            registry.add("kira.signing.enabled") { "true" }
            registry.add("kira.signing.active-key-id") { "integration-test-key" }
            registry.add("kira.signing.private-key") { Base64.getEncoder().encodeToString(testKeyPair.private.encoded) }
            registry.add("kira.signing.verification-keys[0].key-id") { "integration-test-key" }
            registry.add("kira.signing.verification-keys[0].public-key") { Base64.getEncoder().encodeToString(testKeyPair.public.encoded) }
        }
    }
}
