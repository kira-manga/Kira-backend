package me.manga.kira.backend.sourceconfig.admin

import com.fasterxml.jackson.databind.JsonNode
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.domain.model.EndpointSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.ResultActionsDsl

/**
 * PLAN §11 test 17 — `ImportBundledIT`: the bundled-import contract (§12.2) end-to-end. Each clause of
 * the plan's test 17 is a method: created+published+served-in-payload-order (positions per §12.2);
 * idempotent no-op re-import (zero new per-source revisions, zero new snapshots); one bad stanza → 422
 * with nothing persisted; incoming `revision`/`generatedAt` never drive server revision allocation; a
 * terminally `removed` source is not revived; changed content for a `retired` source → `skippedRetired`,
 * nothing stored. Plus the §4.5 5-MiB body cap → 413.
 */
class ImportBundledIT : AbstractAdminSourceIT() {

    private fun trimmed(): String = SourceConfigFixtures.loadFixture("bundled-trimmed.json")

    private fun result(actions: ResultActionsDsl): JsonNode = objectMapper.readTree(actions.andReturn().response.contentAsString)

    private fun JsonNode.apis(field: String): List<String> = this.get(field).map { it.asText() }

    @Test
    fun `import of the trimmed document creates + publishes + serves the sources in payload order`() {
        val body = result(importBundled(trimmed()).andExpect { status { isOk() } })

        // All four created (2 generic + 2 legacy), nothing else.
        assertEquals(listOf("Azora", "SwatManga", "Lavatoons", "Manhwatop"), body.apis("created"))
        assertTrue(body.apis("updated").isEmpty() && body.apis("unchanged").isEmpty())
        assertTrue(body.get("documentRevision").asLong() >= 100L, "server-allocated document revision")

        // Served document preserves the payload order (positions per §12.2 / §5 source ordering).
        val served = publicServedDocument()
        assertEquals(listOf("Azora", "SwatManga", "Lavatoons", "Manhwatop"), served.sources.map { it.api })
        // Exactly one snapshot for the whole import (test 23 asserts this in isolation).
        assertEquals(1L, snapshotCount())
    }

    @Test
    fun `re-import of the identical payload is a no-op - zero new revisions and zero new snapshots`() {
        importBundled(trimmed()).andExpect { status { isOk() } }
        val snapshotsAfterFirst = snapshotCount()
        val revisionsAfterFirst = listOf("Azora", "SwatManga", "Lavatoons", "Manhwatop").associateWith { revisionCount(it) }

        val body = result(importBundled(trimmed()).andExpect { status { isOk() } })

        assertEquals(listOf("Azora", "SwatManga", "Lavatoons", "Manhwatop"), body.apis("unchanged"))
        assertTrue(body.apis("created").isEmpty() && body.apis("updated").isEmpty())
        // documentRevision is absent/null on the no-op (NON_NULL serialization → the key is absent).
        assertTrue(body.get("documentRevision") == null || body.get("documentRevision").isNull, "no-op → no documentRevision")
        assertEquals(snapshotsAfterFirst, snapshotCount(), "no new snapshot on an identical re-import")
        revisionsAfterFirst.forEach { (api, count) -> assertEquals(count, revisionCount(api), "no new revision for $api") }
    }

    @Test
    fun `one bad stanza fails the whole import with 422 and nothing is persisted`() {
        // A generic stanza whose search url uses raw {query} (rule 14) alongside otherwise-valid stanzas.
        val bad =
            SourceConfigFixtures.validGenericSource("Broken").let {
                it.copy(endpoints = it.endpoints + ("search" to EndpointSpec(url = "{baseUrl}/s?q={query}", format = "json")))
            }
        val document = SourceConfigFixtures.document(SourceConfigFixtures.validGenericSource("Good"), bad)

        importBundled(document).andExpect { status { isUnprocessableEntity() } }

        assertEquals(0L, sourceRowCount(), "nothing persisted on a 422")
        assertEquals(0L, snapshotCount(), "no snapshot on a 422")
    }

    @Test
    fun `incoming revision and generatedAt do not drive server revision allocation`() {
        val document =
            SourceConfigFixtures
                .document(SourceConfigFixtures.validGenericSource("Provenance"))
                .copy(revision = 999_999L, generatedAt = "2099-01-01T00:00:00Z")

        val body = result(importBundled(document).andExpect { status { isOk() } })

        val documentRevision = body.get("documentRevision").asLong()
        assertNotEquals(999_999L, documentRevision, "the payload revision must be ignored")
        assertTrue(documentRevision in 100L..1_000L, "server sequence value, not the payload's 999999")

        val served = publicServedDocument()
        assertEquals(documentRevision, served.revision, "served revision is the server-allocated one")
        assertNotEquals("2099-01-01T00:00:00Z", served.generatedAt, "the payload generatedAt must be ignored")
    }

    @Test
    fun `a terminally removed source is not revived by import`() {
        // Walk "Azora" (generic) all the way to terminal removed, then import a document that contains it.
        val api = "Azora"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }
        disable(api).andExpect { status { isOk() } }
        retire(api).andExpect { status { isOk() } }
        remove(api).andExpect { status { isOk() } }

        val body = result(importBundled(trimmed()).andExpect { status { isOk() } })

        assertTrue(body.apis("skippedRemoved").contains(api), "removed source is skipped, never revived")
        assertFalse(body.apis("created").contains(api), "removed source is not re-created")
        // Still terminally gone: absent from the served document, 410 on the by-api route.
        assertFalse(publicServedDocument().sources.any { it.api == api })
        getPublicSource(api).andExpect { status { isGone() } }
        assertEquals("removed", sourceStatus(api))
    }

    @Test
    fun `changed content for a retired source is skippedRetired and nothing is stored`() {
        // Retire a generic "Azora" (create → publish → disable → retire); it stays in the doc as removed.
        val api = "Azora"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }
        disable(api).andExpect { status { isOk() } }
        retire(api).andExpect { status { isOk() } }
        val revisionsBefore = revisionCount(api)

        // The trimmed fixture's Azora has DIFFERENT content (real production baseUrl vs the fixture's).
        val body = result(importBundled(trimmed()).andExpect { status { isOk() } })

        assertTrue(body.apis("skippedRetired").contains(api), "changed content for a retired source is skipped")
        assertFalse(body.apis("updated").contains(api))
        assertEquals(revisionsBefore, revisionCount(api), "no revision stored for the retired source")
        // Retired stays visible as lifecycle:"removed" with its ORIGINAL content (never the import's).
        val served = publicServedDocument().sources.first { it.api == api }
        assertEquals("removed", served.lifecycle)
        assertEquals("https://example.com", served.baseUrl, "the retired source keeps its own content")
        assertEquals("retired", sourceStatus(api))
    }

    @Test
    fun `re-import never publishes or replaces a draft-only source`() {
        val api = "AdminDraft"
        val draft = SourceConfigFixtures.validGenericSource(api)
        createSource(draft).andExpect { status { isCreated() } }
        val revisionsBefore = revisionCount(api)

        val changedPayload = SourceConfigFixtures.document(draft.copy(displayName = "Imported replacement"))
        val body = result(importBundled(changedPayload).andExpect { status { isOk() } })

        assertTrue(body.apis("skippedDraft").contains(api))
        assertFalse(body.apis("updated").contains(api))
        assertEquals("draft", sourceStatus(api))
        assertEquals(revisionsBefore, revisionCount(api), "import must not create a revision over admin WIP")
        assertEquals(0L, publishedRevisionCount(api), "import must not publish the draft")
        assertEquals(0L, snapshotCount(), "skipping only drafts is a document no-op")
        assertTrue(body.get("documentRevision") == null || body.get("documentRevision").isNull)
    }

    @Test
    fun `a body over the 5 MiB import limit is rejected with 413`() {
        val oversized = "a".repeat(5 * 1024 * 1024 + 1)
        importBundled(oversized).andExpect { status { isPayloadTooLarge() } }
        assertEquals(0L, sourceRowCount(), "an over-limit body is rejected before any persistence")
    }
}
