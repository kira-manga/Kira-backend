package me.manga.kira.backend.sourceconfig.public

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.admin.AbstractAdminSourceIT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get

/**
 * PLAN §11 test 16 — `ETagIT`: `GET /source-config/document` → 200 + a strong quoted ETag; the same
 * request with `If-None-Match` → 304 with an empty body and the SAME ETag; after any publish → a new
 * ETag, and the conditional GET (old ETag) → 200 again. Also: historical snapshots are admin-only —
 * `GET /admin/documents/{revision}` returns the exact stored bytes + that snapshot's checksum, while the
 * public endpoint has NO `revision` parameter (PLAN §4.1).
 */
class ETagIT : AbstractAdminSourceIT() {

    private fun etagOf(actions: ResultActionsDsl): String = actions.andReturn().response.getHeader("ETag") ?: error("missing ETag header")

    @Test
    fun `document serves a strong quoted etag and honors conditional GET`() {
        val api = "Etag"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }

        // 200 + strong quoted ETag ("<sha256-hex>").
        val etag1 =
            etagOf(
                getPublicDocument().andExpect {
                    status { isOk() }
                    header { string("Content-Type", "application/json;charset=UTF-8") }
                },
            )
        assertTrue(etag1.matches(Regex("\"[0-9a-f]{64}\"")), "ETag must be a strong quoted sha256 hex: $etag1")

        // Conditional GET with the current ETag → 304, empty body, same ETag.
        getPublicDocument(ifNoneMatch = etag1).andExpect {
            status { isNotModified() }
            header { string("ETag", etag1) }
            content { string("") }
        }

        // Publish a changed revision → a new document ETag.
        val changed = SourceConfigFixtures.validGenericSource(api).copy(baseUrl = "https://changed.example.com")
        createRevision(api, changed).andExpect { status { isCreated() } }
        publish(api, 2).andExpect { status { isOk() } }

        val etag2 = etagOf(getPublicDocument().andExpect { status { isOk() } })
        assertNotEquals(etag1, etag2, "a new publication must change the document ETag")

        // The stale ETag no longer matches → 200 full body again; the fresh one → 304.
        getPublicDocument(ifNoneMatch = etag1).andExpect { status { isOk() } }
        getPublicDocument(ifNoneMatch = etag2).andExpect { status { isNotModified() } }
    }

    @Test
    fun `historical snapshots are admin-only and the public route has no revision param`() {
        val api = "Hist"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        val rev1 = docRevisionOf(publish(api, 1).andExpect { status { isOk() } })

        // Publish a second document so rev1 is now historical.
        createRevision(api, SourceConfigFixtures.validGenericSource(api).copy(baseUrl = "https://v2.example.com"))
            .andExpect { status { isCreated() } }
        publish(api, 2).andExpect { status { isOk() } }

        // The admin historical route returns rev1's EXACT stored bytes + that snapshot's checksum.
        val storedChecksum =
            jdbcTemplate.queryForObject(
                "SELECT checksum FROM published_documents WHERE document_revision = ?",
                String::class.java,
                rev1,
            )!!
        val raw =
            getRawDocument(rev1)
                .andExpect {
                    status { isOk() }
                    header { string("ETag", "\"$storedChecksum\"") }
                    header { string("X-Config-Checksum", storedChecksum) }
                }.andReturn().response.contentAsByteArray
        assertEquals(storedChecksum, Sha256.hexUtf8(raw.toString(Charsets.UTF_8)))

        // The PUBLIC endpoint has no revision parameter — a positional revision path is simply not routed.
        mockMvc.get("/api/v1/source-config/document/$rev1").andExpect { status { isNotFound() } }
    }
}
