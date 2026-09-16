package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 28 — `EndpointCompletenessIT`: no broken generic source can publish (PLAN §8 rule 31).
 * A generic stanza missing `search`/`details`/`pages`/both-home-and-featured → 422 with the matching
 * `GENERIC_MISSING_*` code; a generic stanza WITHOUT `chapters` publishes fine (the chapter list parses
 * inline from details — 4 of 12 real generic stanzas omit it). Legacy revisions can remain as
 * migration input but cannot be published into the public catalog.
 */
class EndpointCompletenessIT : AbstractAdminSourceIT() {

    private fun generic(api: String, drop: String): SourceConfig {
        val full = SourceConfigFixtures.validGenericSource(api)
        return full.copy(endpoints = full.endpoints.filterKeys { it != drop })
    }

    @Test
    fun `a generic source missing a required verb cannot publish`() {
        val cases =
            listOf(
                "search" to "GENERIC_MISSING_SEARCH",
                "details" to "GENERIC_MISSING_DETAILS",
                "pages" to "GENERIC_MISSING_PAGES",
            )
        for ((verb, code) in cases) {
            val api = "Missing_$verb"
            createSource(generic(api, verb)).andExpect { status { isCreated() } }
            publish(api, 1).andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.errors[?(@.code == '$code')]") { exists() }
            }
        }
    }

    @Test
    fun `a generic source missing both home and featured cannot publish`() {
        val full = SourceConfigFixtures.validGenericSource("NoHome")
        val model = full.copy(endpoints = full.endpoints.filterKeys { it != "home" && it != "featured" })
        createSource(model).andExpect { status { isCreated() } }
        publish("NoHome", 1).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.errors[?(@.code == 'GENERIC_MISSING_HOME_OR_FEATURED')]") { exists() }
        }
    }

    @Test
    fun `a generic source without chapters publishes fine`() {
        // validGenericSource declares home/search/details/pages but NOT chapters — the optional verb.
        createSource(SourceConfigFixtures.validGenericSource("NoChapters")).andExpect { status { isCreated() } }
        publish("NoChapters", 1).andExpect { status { isOk() } }
    }

    @Test
    fun `a legacy source is not publicly publishable`() {
        createSource(SourceConfigFixtures.validLegacySource("Legacy")).andExpect { status { isCreated() } }
        publish("Legacy", 1).andExpect {
            status { isConflict() }
            jsonPath("$.errors[0].code") { value("NON_GENERIC_PUBLICATION_FORBIDDEN") }
        }
    }
}
