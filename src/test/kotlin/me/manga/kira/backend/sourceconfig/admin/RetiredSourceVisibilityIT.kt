package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 30 — `RetiredSourceVisibilityIT`: a retired stanza stays in the document as
 * `lifecycle:"removed"`; un-retire (`retired → active`) succeeds for a generic source and is 409
 * `UNRETIRE_UNSUPPORTED_FOR_ENGINE` for a legacy source (PLAN §9 engine-gated un-retire).
 *
 * The `GET /sources/{api}` → 200-with-`removed` half is asserted through BOTH the assembled snapshot
 * and the public per-api route (Phase-7 extension).
 */
class RetiredSourceVisibilityIT : AbstractAdminSourceIT() {

    private fun toRetired(
        api: String,
        legacy: Boolean,
    ) {
        val model =
            if (legacy) SourceConfigFixtures.validLegacySource(api) else SourceConfigFixtures.validGenericSource(api)
        createSource(model).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }
        disable(api).andExpect { status { isOk() } }
        retire(api).andExpect { status { isOk() } }
    }

    @Test
    fun `a retired generic stanza is served as removed and can be un-retired`() {
        val api = "Gen"
        toRetired(api, legacy = false)

        val retiredDoc = latestPointer()!!
        assertEquals("removed", servedDocument(retiredDoc).sources.first { it.api == api }.lifecycle)

        // Phase 7 — the public per-api route serves the retired stanza as 200 with lifecycle:"removed".
        getPublicSource(api).andExpect {
            status { isOk() }
            jsonPath("$.api") { value(api) }
            jsonPath("$.lifecycle") { value("removed") }
        }

        // Un-retire (generic only): retired -> active.
        val enabledDoc = docRevisionOf(enable(api).andExpect { status { isOk() } })
        assertEquals("active", servedDocument(enabledDoc).sources.first { it.api == api }.lifecycle)
        val status = jdbcTemplate.queryForObject("SELECT status FROM source_configs WHERE api = ?", String::class.java, api)
        assertEquals("active", status)
    }

    @Test
    fun `a retired legacy stanza cannot be un-retired`() {
        val api = "Leg"
        toRetired(api, legacy = true)
        enable(api).andExpect {
            status { isConflict() }
            jsonPath("$.errors[0].code") { value("UNRETIRE_UNSUPPORTED_FOR_ENGINE") }
        }
    }
}
