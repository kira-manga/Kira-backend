package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.post

/**
 * PLAN §11 test 12 — `SourcePublishFlowIT`: create a valid source → validate → publish → 200; the stanza
 * appears in the served document with `lifecycle:"active"`; the document revision is a real server
 * revision (> the bundled floor); the served bytes re-checksum to the ETag/checksum.
 *
 * The served-document assertion goes through the admin raw-bytes endpoint (`GET /admin/documents/{rev}`,
 * the Phase-6 surface); Phase 7 extends this to assert the same through the public `GET
 * /source-config/document` route.
 */
class SourcePublishFlowIT : AbstractAdminSourceIT() {

    @Test
    fun `create validate publish then the stanza is served with a matching checksum`() {
        val api = "Azora"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }

        mockMvc
            .post("/api/v1/admin/sources/$api/revisions/1/validate") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isOk() }
                jsonPath("$.valid") { value(true) }
            }

        val publishBody =
            publish(api, 1)
                .andExpect { status { isOk() } }
                .andReturn().response.contentAsString
        val published = objectMapper.readTree(publishBody)
        val documentRevision = published.get("documentRevision").asLong()
        val checksum = published.get("checksum").asText()

        // A real server revision, strictly above the bundled floor (default 4) and >= the seed (100).
        assertTrue(documentRevision >= 100, "first server revision must be >= the sequence seed 100")
        assertEquals(documentRevision, latestPointer())

        // Raw served bytes re-checksum to the ETag / X-Config-Checksum (no re-serialization drift).
        val raw =
            getRawDocument(documentRevision)
                .andExpect {
                    status { isOk() }
                    header { string("ETag", "\"$checksum\"") }
                    header { string("X-Config-Revision", documentRevision.toString()) }
                    header { string("X-Config-Checksum", checksum) }
                }.andReturn().response.contentAsByteArray
        assertEquals(checksum, Sha256.hexUtf8(raw.toString(Charsets.UTF_8)))

        // The stanza is present and served as active (kcj-1 omits the default "active" — the parser
        // reconstructs it as "active").
        val document = servedDocument(documentRevision)
        val stanza = document.sources.firstOrNull { it.api == api }
        assertNotNull(stanza, "the published stanza must appear in the served document")
        assertEquals("active", stanza!!.lifecycle)
        assertEquals(1, document.sources.size)
    }
}
