package me.manga.kira.backend.sourceconfig.admin

import com.fasterxml.jackson.databind.JsonNode
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 24 — `ImportNoChangesIsNoOpIT`: *idempotent re-import*. Importing an identical document
 * again creates zero revisions and zero snapshots and reports everything `unchanged` — INCLUDING when
 * stanzas carry explicit non-neutral `lifecycle` values (the §9 lifecycle-neutral normalization is what
 * makes the idempotency hold: the stored content is compared neutralized, so a `disabled` stanza compares
 * equal to its stored-neutral twin).
 */
class ImportNoChangesIsNoOpIT : AbstractAdminSourceIT() {

    // An active source + a source carrying an EXPLICIT non-neutral lifecycle:"disabled" (§9/§12.2).
    private fun document() = SourceConfigFixtures.document(
        SourceConfigFixtures.validGenericSource("ActiveOne"),
        SourceConfigFixtures.validGenericSource("DisabledOne").copy(lifecycle = "disabled"),
    )

    private fun apis(body: JsonNode, field: String): List<String> = body.get(field).map { it.asText() }

    @Test
    fun `re-importing an identical document with explicit lifecycle values is a no-op`() {
        // First import creates both; the disabled stanza lands with initial status disabled.
        importBundled(document()).andExpect { status { isOk() } }
        assertEquals("active", sourceStatus("ActiveOne"))
        assertEquals("disabled", sourceStatus("DisabledOne"))

        val snapshotsAfterFirst = snapshotCount()
        val revisionsAfterFirst = mapOf("ActiveOne" to revisionCount("ActiveOne"), "DisabledOne" to revisionCount("DisabledOne"))

        // Re-import the byte-identical document.
        val body = objectMapper.readTree(importBundled(document()).andExpect { status { isOk() } }.andReturn().response.contentAsString)

        assertEquals(listOf("ActiveOne", "DisabledOne"), apis(body, "unchanged"))
        assertTrue(apis(body, "created").isEmpty(), "no creates on re-import")
        assertTrue(apis(body, "updated").isEmpty(), "no updates on re-import")
        // The disabled stanza's payload lifecycle matches the server's (disabled) → NO lifecycle conflict.
        assertTrue(body.get("lifecycleConflicts").isEmpty, "matching lifecycle → no conflict")
        assertTrue(
            body.get("documentRevision") == null || body.get("documentRevision").isNull,
            "no-op → no new document revision",
        )

        assertEquals(snapshotsAfterFirst, snapshotCount(), "zero new snapshots")
        revisionsAfterFirst.forEach { (api, count) -> assertEquals(count, revisionCount(api), "zero new revisions for $api") }
    }
}
