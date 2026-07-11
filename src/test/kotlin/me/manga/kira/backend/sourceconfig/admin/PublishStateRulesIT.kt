package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 47 — `PublishStateRulesIT`: only the right revisions are publishable (PLAN §4.3/§9).
 * Re-publishing the currently published revision → 200 no-op with NO new snapshot; publishing a
 * `superseded` revision → 409 `REVISION_SUPERSEDED`; publishing a draft older than the current published
 * revision → 409 `REVISION_OLDER_THAN_PUBLISHED`; rollback restores older content as a NEW highest
 * revision; the one-published-per-source partial unique index holds throughout.
 */
class PublishStateRulesIT : AbstractAdminSourceIT() {

    @Test
    fun `publishable-revision-states rules hold and there is always one published revision`() {
        val api = "States"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }

        // Publish r1.
        val doc1 = docRevisionOf(publish(api, 1).andExpect { status { isOk() } })
        assertEquals(1L, publishedRevisionCount(api))

        // Re-publish r1 (currently published) → 200 idempotent no-op, NO new snapshot.
        val snapshotsBefore = snapshotCount()
        val noOpDoc = docRevisionOf(publish(api, 1).andExpect { status { isOk() } })
        assertEquals(doc1, noOpDoc, "the no-op re-publish must return the same document revision")
        assertEquals(snapshotsBefore, snapshotCount(), "the no-op must NOT create a snapshot")

        // Publish r2 → r1 becomes superseded.
        createRevision(api, SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 2).andExpect { status { isOk() } }
        assertEquals(1L, publishedRevisionCount(api))

        // Publishing the now-superseded r1 → 409 REVISION_SUPERSEDED.
        publish(api, 1).andExpect {
            status { isConflict() }
            jsonPath("$.errors[0].code") { value("REVISION_SUPERSEDED") }
        }

        // Two more drafts; publish the newest (r4).
        createRevision(api, SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } } // r3
        createRevision(api, SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } } // r4
        publish(api, 4).andExpect { status { isOk() } }
        assertEquals(1L, publishedRevisionCount(api))

        // Publishing r3 (a draft older than the published r4) → 409 REVISION_OLDER_THAN_PUBLISHED.
        publish(api, 3).andExpect {
            status { isConflict() }
            jsonPath("$.errors[0].code") { value("REVISION_OLDER_THAN_PUBLISHED") }
        }

        // Rollback restores r1's content as a NEW highest revision (r5), never reviving the old row.
        val rollbackBody = rollback(api, 1).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals(5, objectMapper.readTree(rollbackBody).get("newRevisionNumber").asInt())

        // The partial unique index held throughout: exactly one published revision now.
        assertEquals(1L, publishedRevisionCount(api))
    }
}
