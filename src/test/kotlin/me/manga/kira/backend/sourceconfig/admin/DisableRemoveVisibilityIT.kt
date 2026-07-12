package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 14 — `DisableRemoveVisibilityIT`: disable keeps the stanza in the document as
 * `lifecycle:"disabled"` (NOT absent, NOT active); retire (from disabled) → `lifecycle:"removed"`;
 * remove drops the stanza entirely; a direct `active → retired` and `active → removed` are each 409.
 *
 * The stanza content is asserted through BOTH the assembled snapshot (admin raw-bytes endpoint) AND
 * the public routes (Phase-7 visibility half): the public `GET /source-config/document` carries the
 * injected lifecycle, and a removed source is absent from it AND `GET /sources/{api}` → 410.
 */
class DisableRemoveVisibilityIT : AbstractAdminSourceIT() {

    private fun lifecycleInDocument(
        documentRevision: Long,
        api: String,
    ): String? = servedDocument(documentRevision).sources.firstOrNull { it.api == api }?.lifecycle

    /** The injected lifecycle for [api] as seen through the PUBLIC latest-document endpoint. */
    private fun publicLifecycle(api: String): String? =
        publicServedDocument().sources.firstOrNull { it.api == api }?.lifecycle

    @Test
    fun `disable then retire then remove walk the stanza through the document`() {
        val api = "Vis"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }

        val disabledRev = docRevisionOf(disable(api).andExpect { status { isOk() } })
        assertEquals("disabled", lifecycleInDocument(disabledRev, api))
        assertEquals("disabled", publicLifecycle(api), "the public document must carry lifecycle:\"disabled\"")
        getPublicSource(api).andExpect {
            status { isOk() }
            jsonPath("$.lifecycle") { value("disabled") }
        }

        val retiredRev = docRevisionOf(retire(api).andExpect { status { isOk() } })
        assertEquals("removed", lifecycleInDocument(retiredRev, api))
        assertEquals("removed", publicLifecycle(api), "a retired stanza is served as lifecycle:\"removed\"")

        val removedRev = docRevisionOf(remove(api).andExpect { status { isOk() } })
        assertNull(lifecycleInDocument(removedRev, api), "a removed source must be absent from the document")
        assertEquals(0, servedDocument(removedRev).sources.size)
        // Public visibility: absent from the served document AND GET /sources/{api} → 410.
        assertNull(publicLifecycle(api), "a removed source must be absent from the PUBLIC document")
        getPublicSource(api).andExpect {
            status { isGone() }
            jsonPath("$.errors[0].code") { value("SOURCE_REMOVED") }
        }
    }

    @Test
    fun `a direct active to retired is 409`() {
        val api = "DirectRetire"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }
        retire(api).andExpect {
            status { isConflict() }
            jsonPath("$.errors[0].code") { value("INVALID_LIFECYCLE_TRANSITION") }
        }
    }

    @Test
    fun `a direct active to removed is 409`() {
        val api = "DirectRemove"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }
        remove(api).andExpect {
            status { isConflict() }
            jsonPath("$.errors[0].code") { value("INVALID_LIFECYCLE_TRANSITION") }
        }
    }
}
