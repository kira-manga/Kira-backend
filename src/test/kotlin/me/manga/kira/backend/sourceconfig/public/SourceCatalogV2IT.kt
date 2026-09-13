package me.manga.kira.backend.sourceconfig.public

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.admin.AbstractAdminSourceIT
import me.manga.kira.backend.sourceconfig.domain.model.SourceCatalogManifest
import me.manga.kira.backend.sourceconfig.signing.DocumentSigner
import me.manga.kira.backend.sourceconfig.signing.SourceCatalogSignatureCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.get
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

class SourceCatalogV2IT : AbstractAdminSourceIT() {
    @Autowired
    private lateinit var signer: DocumentSigner

    @Test
    fun `signed manifest and immutable source revisions are conditional and verifiable`() {
        bootstrapInitialCatalog()

        val manifestResponse =
            mockMvc.get("/api/v2/source-config/manifest")
                .andExpect {
                    status { isOk() }
                    header { string("X-Config-Signature-Format", SourceCatalogSignatureCodec.MANIFEST_FORMAT) }
                    header { string(HttpHeaders.CACHE_CONTROL, "public, max-age=300, no-transform") }
                }.andReturn().response
        val body = manifestResponse.contentAsString
        val manifest = servedJson.decodeFromString(SourceCatalogManifest.serializer(), body)
        assertEquals(initialGenericApis, manifest.sources.map { it.api })
        assertTrue(manifest.sources.all { it.engine == "generic" && it.lifecycle == "active" })
        assertTrue(manifest.removedSources.isEmpty())
        assertEquals((0 until 12).toList(), manifest.sources.map { it.order })

        val checksum = requireNotNull(manifestResponse.getHeader("X-Config-Checksum"))
        assertEquals(Sha256.hexUtf8(body), checksum)
        val keyId = requireNotNull(manifestResponse.getHeader("X-Config-Signing-Key-Id"))
        val publicKey =
            KeyFactory.getInstance("Ed25519").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(requireNotNull(signer.publicKeys()[keyId]))),
            )
        val manifestPayload =
            SourceCatalogSignatureCodec.manifestPayload(
                catalogRevision = manifest.catalogRevision,
                previousCatalogRevision = null,
                previousCatalogChecksum = null,
                checksum = checksum,
                createdAt = Instant.parse(requireNotNull(manifestResponse.getHeader("X-Config-Created-At"))),
                manifestJson = body,
            )
        assertTrue(verify(publicKey, manifestPayload, requireNotNull(manifestResponse.getHeader("X-Config-Signature"))))

        mockMvc.get("/api/v2/source-config/manifest") {
            header(HttpHeaders.IF_NONE_MATCH, requireNotNull(manifestResponse.getHeader(HttpHeaders.ETAG)))
        }.andExpect {
            status { isNotModified() }
            content { string("") }
        }
        mockMvc.get("/api/v2/source-config/manifest") {
            header(HttpHeaders.IF_NONE_MATCH, "W/${requireNotNull(manifestResponse.getHeader(HttpHeaders.ETAG))}")
        }.andExpect {
            status { isNotModified() }
            content { string("") }
        }

        val entry = manifest.sources.first()
        val artifactResponse =
            mockMvc.get("/api/v2/source-config/sources/${entry.api}/revisions/${entry.sourceRevision}")
                .andExpect {
                    status { isOk() }
                    header { string("X-Source-Api", entry.api) }
                    header { string("X-Source-Revision", entry.sourceRevision.toString()) }
                    header { string("X-Source-Checksum", entry.checksum) }
                    header { string(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable, no-transform") }
                }.andReturn().response
        val sourceJson = artifactResponse.contentAsString
        assertEquals(entry.checksum, Sha256.hexUtf8(sourceJson))
        assertTrue(
            verify(
                publicKey,
                SourceCatalogSignatureCodec.sourcePayload(entry.api, entry.sourceRevision, entry.checksum, sourceJson),
                requireNotNull(entry.sourceSignature),
            ),
        )
        assertEquals(keyId, entry.sourceSigningKeyId)

        mockMvc.get("/api/v2/source-config/sources/${entry.api}/revisions/${entry.sourceRevision}") {
            header(HttpHeaders.IF_NONE_MATCH, "W/${requireNotNull(artifactResponse.getHeader(HttpHeaders.ETAG))}")
        }.andExpect {
            status { isNotModified() }
            content { string("") }
        }

        mockMvc.get("/api/v2/source-config/sources/Lavatoons/revisions/1")
            .andExpect { status { isNotFound() } }
        mockMvc.get("/api/v2/source-config/sources/${entry.api}/revisions/999")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `cutover is dry-run first atomic exact 12 and idempotent`() {
        val payload = approvedBootstrapPayload()
        val before = snapshotCount()
        val publicBefore = publicState()

        val dryRun =
            mockMvc.get("/api/v1/admin/source-catalog-v2/cutover") {
                header("Authorization", "Bearer $adminToken")
            }.andExpect {
                status { isOk() }
                jsonPath("$.ready") { value(true) }
                jsonPath("$.applied") { value(false) }
                jsonPath("$.phase") { value("PENDING") }
                jsonPath("$.receipt") { doesNotExist() }
                jsonPath("$.approvedActiveSources.length()") { value(12) }
                jsonPath("$.legacySourcesToWithhold.length()") { value(33) }
            }.andReturn().response.contentAsString
        assertFalse(objectMapper.readTree(dryRun).get("applied").asBoolean())
        assertEquals(before, snapshotCount(), "dry-run must not materialize")
        assertPublicStateUnchanged(publicBefore)

        val receipt = objectMapper.readTree(bootstrapRequest(payload).andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(before + 1, snapshotCount())
        assertEquals(
            33,
            jdbcTemplate.queryForObject("SELECT count(*) FROM source_configs WHERE status = 'withheld'", Int::class.java),
        )
        assertEquals(initialGenericApis, publicServedDocument().sources.map { it.api })
        getPublicSource("Lavatoons").andExpect { status { isNotFound() } }

        mockMvc.get("/api/v1/admin/source-catalog-v2/cutover") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.phase") { value("COMPLETE") }
            jsonPath("$.applied") { value(false) }
            jsonPath("$.legacySourcesToWithhold.length()") { value(0) }
            jsonPath("$.alreadyWithheldSources.length()") { value(33) }
            jsonPath("$.receipt.documentRevision") { value(receipt.get("documentRevision").asLong()) }
        }
        // An identical raw retry returns the origin receipt without reapplying or republishing.
        val complete = publicState()
        val replay = objectMapper.readTree(bootstrapRequest(payload).andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(receipt, replay)
        assertPublicStateUnchanged(complete)
        assertEquals(before + 1, snapshotCount())
    }

    @Test
    fun `cutover apply rejects unexpected inventory without partial lifecycle changes`() {
        createSource(SourceConfigFixtures.validGenericSource("Unexpected Generic"))
            .andExpect { status { isCreated() } }
        val before = snapshotCount()
        val publicBefore = publicState()
        val rowsBefore = sourceRowCount()

        mockMvc.get("/api/v1/admin/source-catalog-v2/cutover") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.ready") { value(false) }
            jsonPath("$.phase") { value("PENDING") }
        }

        bootstrapRequest().andExpect {
            status { isConflict() }
        }

        assertEquals(before, snapshotCount())
        assertEquals(rowsBefore, sourceRowCount())
        assertEquals("draft", sourceStatus("Unexpected Generic"))
        assertPublicStateUnchanged(publicBefore)
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM source_configs WHERE status = 'withheld'",
                Int::class.java,
            ),
        )
    }

    private fun verify(publicKey: java.security.PublicKey, payload: ByteArray, signatureBase64: String): Boolean = Signature
        .getInstance("Ed25519")
        .run {
            initVerify(publicKey)
            update(payload)
            verify(Base64.getDecoder().decode(signatureBase64))
        }
}
