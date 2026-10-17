package me.manga.kira.backend.sourceconfig.api

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.common.GlobalExceptionHandler
import me.manga.kira.backend.config.KiraClientCatalogProperties
import me.manga.kira.backend.sourceconfig.application.ClientCatalogFixture
import me.manga.kira.backend.sourceconfig.application.SourceCatalogQueryService
import me.manga.kira.backend.sourceconfig.application.SourceQueryService
import me.manga.kira.backend.sourceconfig.application.StoredCatalogPair
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** Actual servlet parameter binding, shared selector, original codecs and writers; no listener/security filter/DB. */
class ClientCatalogDeliveryTest {
    private val fixture = ClientCatalogFixture()
    private val json = ObjectMapper()

    @Test
    fun `raw version cohorts select identical stored pairs before document manifest and meta responses`() {
        val mvc = mvc()
        val cases = listOf(
            null to fixture.baseline,
            listOf("unknown_PRIVATE_input") to fixture.baseline,
            listOf("9.0.2", "9.0.2") to fixture.baseline,
            listOf(" 9.0.2 ") to fixture.baseline,
            listOf("9.0.2,9.0.2") to fixture.baseline,
            listOf("9.0.1") to fixture.pinned,
            listOf("9.0.2") to fixture.current,
        )
        cases.forEach { (versions, pair) ->
            assertPair(mvc, versions, pair)
            listOf(DOCUMENT to pair.document.checksum, MANIFEST to pair.manifest.checksum).forEach { (path, checksum) ->
                val unchanged = request(mvc, path, versions, "W/\"$checksum\"")
                assertEquals(304, unchanged.status)
                assertEquals("\"$checksum\"", unchanged.getHeader(HttpHeaders.ETAG))
                assertArrayEquals(byteArrayOf(), unchanged.contentAsByteArray)
                assertNull(unchanged.contentType)
            }
        }
        // A new cohort's ETag must not suppress the distinct old-cohort snapshot.
        val oldDocument = request(mvc, DOCUMENT, null, "\"${fixture.current.document.checksum}\"")
        assertEquals(200, oldDocument.status)
        assertEquals("100", oldDocument.getHeader("X-Config-Revision"))
        val oldManifest = request(mvc, MANIFEST, null, "\"${fixture.current.manifest.checksum}\"")
        assertEquals(200, oldManifest.status)
        assertEquals("100", oldManifest.getHeader("X-Config-Revision"))
    }

    @Test
    fun `corrupt selected state refuses before wildcard conditional response without content leakage`() {
        val mvc = mvc()
        fixture.documentRows[100] = fixture.baseline.document.copy(documentJson = "synthetic_PRIVATE_stored_payload")
        listOf(DOCUMENT, MANIFEST, META).forEach { path ->
            val response = request(mvc, path, listOf("synthetic_PRIVATE_submitted_version"), "*")
            assertEquals(503, response.status)
            assertEquals("5", response.getHeader(HttpHeaders.RETRY_AFTER))
            assertNull(response.getHeader(HttpHeaders.ETAG))
            assertNull(response.getHeader("X-Config-Signature"))
            assertEquals("CLIENT_CATALOG_UNAVAILABLE", json.readTree(response.contentAsByteArray)["errors"][0]["code"].asText())
            assertFalse(response.contentAsString.contains("PRIVATE"))
        }
    }

    @Test
    fun `default off legacy validation independent latest queries and immutable artifact routes are unchanged`() {
        val off = mvc(KiraClientCatalogProperties())
        assertPair(off, null, fixture.current)
        val invalid = request(off, DOCUMENT, listOf("invalid_PRIVATE_version"))
        assertEquals(400, invalid.status)
        assertEquals("INVALID_APP_VERSION", json.readTree(invalid.contentAsByteArray)["errors"][0]["code"].asText())
        assertFalse(invalid.contentAsString.contains("PRIVATE"))
        assertEquals("102", request(off, MANIFEST, listOf("invalid")).getHeader("X-Config-Revision"))
        assertEquals(102, json.readTree(request(off, META, listOf("invalid")).contentAsByteArray)["revision"].asInt())

        val originalQueries = SourceCatalogQueryService(fixture.documents, fixture.catalogs)
        assertSame(fixture.current.manifest, originalQueries.latestManifest())
        val sources = mock(SourceConfigRepository::class.java)
        assertSame(fixture.current.document, SourceQueryService(fixture.documents, sources).latestDocument())
        val active = mvc()
        fixture.documentRows.remove(100) // This cannot affect immutable member lookup or its cache contract.
        clearInvocations(fixture.documents, fixture.signer)
        val artifact = fixture.artifact
        val path = "/api/v2/source-config/sources/${artifact.api}/revisions/${artifact.sourceRevision}"
        val response = request(active, path, listOf("unknown"))
        assertEquals(200, response.status)
        assertArrayEquals(artifact.canonicalJson.toByteArray(Charsets.UTF_8), response.contentAsByteArray)
        assertEquals(SourceCatalogV2ResponseWriter.SOURCE_CACHE_CONTROL, response.getHeader(HttpHeaders.CACHE_CONTROL))
        assertEquals("\"${artifact.checksum}\"", response.getHeader(HttpHeaders.ETAG))
        assertEquals(304, request(active, path, listOf("9.0.2"), "W/\"${artifact.checksum}\"").status)
        assertEquals(404, request(active, "/api/v2/source-config/sources/${artifact.api}/revisions/999", null).status)
        verifyNoInteractions(fixture.documents, fixture.signer, sources)
    }

    private fun assertPair(mvc: MockMvc, versions: List<String>?, pair: StoredCatalogPair) {
        val document = request(mvc, DOCUMENT, versions)
        assertEnvelope(document, pair.document.documentJson, pair.document.documentRevision, pair.document.checksum)
        assertEquals(pair.document.signatureBase64, document.getHeader("X-Config-Signature"))
        assertEquals(pair.document.signatureFormat, document.getHeader("X-Config-Signature-Format"))
        assertEquals(pair.document.previousDocumentRevision?.toString(), document.getHeader("X-Config-Previous-Revision"))
        assertEquals(pair.document.previousDocumentChecksum, document.getHeader("X-Config-Previous-Checksum"))
        val manifest = request(mvc, MANIFEST, versions)
        assertEnvelope(manifest, pair.manifest.manifestJson, pair.manifest.catalogRevision, pair.manifest.checksum)
        assertEquals(pair.manifest.signatureBase64, manifest.getHeader("X-Config-Signature"))
        assertEquals(pair.manifest.signatureFormat, manifest.getHeader("X-Config-Signature-Format"))
        assertEquals(pair.manifest.previousCatalogRevision?.toString(), manifest.getHeader("X-Config-Previous-Revision"))
        assertEquals(pair.manifest.previousCatalogChecksum, manifest.getHeader("X-Config-Previous-Checksum"))
        val metaResponse = request(mvc, META, versions)
        assertEquals(200, metaResponse.status)
        assertEquals(DocumentResponseWriter.CACHE_CONTROL_VALUE, metaResponse.getHeader(HttpHeaders.CACHE_CONTROL))
        val meta = json.readTree(metaResponse.contentAsByteArray)
        assertEquals(pair.document.documentRevision, meta["revision"].asLong())
        assertEquals(pair.document.checksum, meta["checksum"].asText())
        assertEquals(pair.document.signatureBase64, meta["signature"].asText())
    }

    private fun assertEnvelope(response: MockHttpServletResponse, body: String, revision: Long, checksum: String) {
        assertEquals(200, response.status)
        assertArrayEquals(body.toByteArray(Charsets.UTF_8), response.contentAsByteArray)
        assertEquals("\"$checksum\"", response.getHeader(HttpHeaders.ETAG))
        assertEquals(revision.toString(), response.getHeader("X-Config-Revision"))
        assertEquals(checksum, response.getHeader("X-Config-Checksum"))
        assertEquals(DocumentResponseWriter.CACHE_CONTROL_VALUE, response.getHeader(HttpHeaders.CACHE_CONTROL))
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
    }

    private fun mvc(properties: KiraClientCatalogProperties = fixture.config()): MockMvc {
        val query = fixture.query(properties)
        return MockMvcBuilders.standaloneSetup(
            SourceDocumentController(query, DocumentResponseWriter(), fixture.signer),
            SourceCatalogV2Controller(SourceCatalogQueryService(fixture.documents, fixture.catalogs), SourceCatalogV2ResponseWriter(), query),
        ).setControllerAdvice(GlobalExceptionHandler()).build()
    }

    private fun request(mvc: MockMvc, path: String, versions: List<String>?, etag: String? = null): MockHttpServletResponse {
        val request = get(path)
        versions?.let { request.param("appVersion", *it.toTypedArray()) }
        etag?.let { request.header(HttpHeaders.IF_NONE_MATCH, it) }
        val result = mvc.perform(request).andReturn()
        assertEquals(versions, result.request.getParameterValues("appVersion")?.toList())
        return result.response
    }

    private companion object {
        const val DOCUMENT = "/api/v1/source-config/document"
        const val MANIFEST = "/api/v2/source-config/manifest"
        const val META = "/api/v1/source-config/document/meta"
    }
}
