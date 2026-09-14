package me.manga.kira.backend.sourceconfig.api

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.sourceconfig.application.SourceAdminService
import me.manga.kira.backend.sourceconfig.application.SourceCatalogQueryService
import me.manga.kira.backend.sourceconfig.application.SourceQueryService
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceArtifact
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalog
import me.manga.kira.backend.sourceconfig.signing.DocumentSigner
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.Collections
import java.util.UUID

/** Servlet delivery only: actual controllers/writers, without Boot, security filters or a database. */
class SourceConfigConditionalResponseTest {
    private val documentQuery = mock(SourceQueryService::class.java)
    private val admin = mock(SourceAdminService::class.java)
    private val catalogQuery = mock(SourceCatalogQueryService::class.java)
    private val signer = mock(DocumentSigner::class.java)
    private lateinit var mockMvc: MockMvc

    private val createdAt = Instant.parse("2026-09-10T00:00:00Z")
    private val previousChecksum = "a".repeat(64)
    private val signature = "c3ludGhldGljLXNpZ25hdHVyZQ=="
    private val sourceJson = """{"api":"Fixture","baseUrl":"https://example.invalid","displayName":"مانجا café 漫画","engine":"generic"}"""
    private val artifact = PublishedSourceArtifact("Fixture", 3, sourceJson, Sha256.hexUtf8(sourceJson), "kcj-1")
    private val latest = document(102)
    private val historical = document(101)
    private val manifest = catalog()

    @BeforeEach
    fun setUp() {
        `when`(documentQuery.latestDocument()).thenReturn(latest)
        `when`(admin.getDocument(historical.documentRevision)).thenReturn(historical)
        `when`(catalogQuery.latestManifest()).thenReturn(manifest)
        `when`(catalogQuery.publishedArtifact(artifact.api, artifact.sourceRevision)).thenReturn(artifact)
        val documents = DocumentResponseWriter()
        mockMvc =
            MockMvcBuilders.standaloneSetup(
                SourceDocumentController(documentQuery, documents, signer),
                AdminDocumentsController(admin, documents),
                SourceCatalogV2Controller(catalogQuery, SourceCatalogV2ResponseWriter()),
            ).build()
    }

    @TestFactory
    fun `all four routes preserve stored bytes and metadata when weak revalidation becomes 304`(): List<DynamicTest> = deliveries().map { delivery ->
        dynamicTest(delivery.name) {
            val full = mockMvc.get(delivery.path).andReturn().response
            val expectedBytes = delivery.body.toByteArray(Charsets.UTF_8)
            assertTrue(expectedBytes.size > delivery.body.length, "fixture must exercise non-ASCII UTF-8 bytes")
            assertEquals(200, full.status)
            assertArrayEquals(expectedBytes, full.contentAsByteArray)
            assertEquals(expectedBytes.size.toString(), full.getHeader(HttpHeaders.CONTENT_LENGTH))
            assertEquals(delivery.contentType, full.contentType)
            assertMetadata(delivery, full)

            val conditional =
                mockMvc.get(delivery.path) {
                    header(HttpHeaders.IF_NONE_MATCH, "W/${delivery.headers.getValue(HttpHeaders.ETAG)}")
                }.andReturn().response
            assertNotModified(delivery, conditional)
            verifyNoInteractions(signer)
        }
    }

    @Test
    fun `repeated If-None-Match field lines are combined by actual request-header binding`() {
        val delivery = deliveries().first()
        val weakTag = "W/${delivery.headers.getValue(HttpHeaders.ETAG)}"
        val result =
            mockMvc.get(delivery.path) {
                header(HttpHeaders.IF_NONE_MATCH, "\"stale\"", weakTag)
            }.andReturn()

        assertEquals(listOf("\"stale\"", weakTag), Collections.list(result.request.getHeaders(HttpHeaders.IF_NONE_MATCH)))
        assertNotModified(delivery, result.response)
        verifyNoInteractions(signer)
    }

    private fun assertNotModified(delivery: Delivery, response: MockHttpServletResponse) {
        assertEquals(304, response.status)
        assertArrayEquals(byteArrayOf(), response.contentAsByteArray)
        assertNull(response.contentType)
        assertFalse(response.containsHeader(HttpHeaders.CONTENT_LENGTH))
        assertMetadata(delivery, response)
    }

    private fun assertMetadata(delivery: Delivery, response: MockHttpServletResponse) {
        delivery.headers.forEach { (name, value) ->
            assertEquals(listOf(value), response.getHeaders(name).toList(), "${delivery.name}: $name")
        }
        delivery.absentHeaders.forEach { name ->
            assertFalse(response.containsHeader(name), "${delivery.name}: $name must remain absent")
        }
    }

    private fun deliveries(): List<Delivery> = listOf(
        Delivery(
            name = "v1 public latest document",
            path = "/api/v1/source-config/document?appVersion=1.2.3",
            body = latest.documentJson,
            headers = configHeaders(latest.documentRevision, latest.checksum, "kira-source-signature-v1") + publicCacheHeaders(),
        ),
        Delivery(
            name = "v1 admin historical document",
            path = "/api/v1/admin/documents/${historical.documentRevision}",
            body = historical.documentJson,
            headers = configHeaders(historical.documentRevision, historical.checksum, "kira-source-signature-v1"),
            contentType = "application/json",
            absentHeaders = listOf(HttpHeaders.CACHE_CONTROL, "X-Content-Type-Options"),
        ),
        Delivery(
            name = "v2 signed manifest",
            path = "/api/v2/source-config/manifest",
            body = manifest.manifestJson,
            headers = configHeaders(manifest.catalogRevision, manifest.checksum, "kira-source-catalog-manifest-v1") + publicCacheHeaders(),
        ),
        Delivery(
            name = "v2 immutable source revision",
            path = "/api/v2/source-config/sources/${artifact.api}/revisions/${artifact.sourceRevision}",
            body = artifact.canonicalJson,
            headers = mapOf(
                HttpHeaders.ETAG to "\"${artifact.checksum}\"",
                HttpHeaders.CACHE_CONTROL to "public, max-age=31536000, immutable, no-transform",
                "X-Content-Type-Options" to "nosniff",
                "X-Source-Api" to artifact.api,
                "X-Source-Revision" to artifact.sourceRevision.toString(),
                "X-Source-Checksum" to artifact.checksum,
                "X-Source-Canon-Version" to "kcj-1",
            ),
        ),
    )

    private fun configHeaders(revision: Long, checksum: String, format: String): Map<String, String> = mapOf(
        HttpHeaders.ETAG to "\"$checksum\"",
        "X-Config-Revision" to revision.toString(),
        "X-Config-Checksum" to checksum,
        "X-Config-Signature-Format" to format,
        "X-Config-Signature-Algorithm" to "Ed25519",
        "X-Config-Signing-Key-Id" to "fixture-key",
        "X-Config-Signature" to signature,
        "X-Config-Previous-Revision" to (revision - 1).toString(),
        "X-Config-Previous-Checksum" to previousChecksum,
        "X-Config-Created-At" to createdAt.toString(),
    )

    private fun publicCacheHeaders(): Map<String, String> = mapOf(
        HttpHeaders.CACHE_CONTROL to "public, max-age=300, no-transform",
        "X-Content-Type-Options" to "nosniff",
    )

    private fun document(revision: Long): PublishedDocument {
        val body = """{"generatedAt":"$createdAt","revision":$revision,"sources":[$sourceJson]}"""
        return PublishedDocument(
            id = UUID(0, revision),
            documentRevision = revision,
            schemaVersion = 1,
            documentJson = body,
            checksum = Sha256.hexUtf8(body),
            canonVersion = "kcj-1",
            sourceCount = 1,
            createdBy = UUID(0, 1),
            createdAt = createdAt,
            notes = null,
            signatureFormat = "kira-source-signature-v1",
            signatureAlgorithm = "Ed25519",
            signingKeyId = "fixture-key",
            signatureBase64 = signature,
            previousDocumentRevision = revision - 1,
            previousDocumentChecksum = previousChecksum,
        )
    }

    private fun catalog(): PublishedSourceCatalog {
        val body =
            """{"catalogRevision":103,"generatedAt":"$createdAt","removedSources":[{"api":"مفقود"}],"schemaVersion":1,"sourceSchemaVersion":1,"sources":[]}"""
        return PublishedSourceCatalog(
            id = UUID(0, 103),
            catalogRevision = 103,
            schemaVersion = 1,
            sourceSchemaVersion = 1,
            manifestJson = body,
            checksum = Sha256.hexUtf8(body),
            canonVersion = "kcj-1",
            sourceCount = 0,
            createdBy = UUID(0, 1),
            createdAt = createdAt,
            signatureFormat = "kira-source-catalog-manifest-v1",
            signatureAlgorithm = "Ed25519",
            signingKeyId = "fixture-key",
            signatureBase64 = signature,
            previousCatalogRevision = 102,
            previousCatalogChecksum = previousChecksum,
        )
    }

    private data class Delivery(
        val name: String,
        val path: String,
        val body: String,
        val headers: Map<String, String>,
        val contentType: String = "application/json;charset=UTF-8",
        val absentHeaders: List<String> = emptyList(),
    )
}
