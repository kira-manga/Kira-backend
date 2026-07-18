package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.domain.model.EndpointSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 13 — `PublishInvalidFailsIT`: publishing a revision with an invalid endpoint (raw
 * `{query}`) → 422 with the pinpoint error; the served document is unchanged (same revision, same ETag,
 * no new snapshot). The server re-validates live inside the publish transaction (PLAN §4.3/§9).
 */
class PublishInvalidFailsIT : AbstractAdminSourceIT() {

    @Test
    fun `publishing an invalid revision is 422 and leaves the document unchanged`() {
        // Baseline: publish a valid source so there IS a served document to compare against.
        createSource(SourceConfigFixtures.validGenericSource("Good")).andExpect { status { isCreated() } }
        publish("Good", 1).andExpect { status { isOk() } }
        val baselineRevision = latestPointer()
        val baselineCount = snapshotCount()

        // An invalid generic source: the search endpoint uses raw {query} (must be {queryEncoded}).
        val invalid =
            SourceConfigFixtures.validGenericSource("Bad").copy(
                endpoints =
                SourceConfigFixtures.validGenericSource("Bad").endpoints +
                    ("search" to EndpointSpec(url = "{baseUrl}/search?q={query}&page={page}", format = "json")),
            )
        createSource(invalid).andExpect {
            status { isCreated() }
            // Tier-2-invalid draft IS stored, and its inline result reports the pinpoint (PLAN §8).
            jsonPath("$.validation.valid") { value(false) }
            jsonPath("$.validation.errors[?(@.code == 'ENDPOINT_URL_RAW_QUERY')]") { exists() }
        }

        publish("Bad", 1).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.errors[?(@.code == 'ENDPOINT_URL_RAW_QUERY')]") { exists() }
        }

        // Document unchanged: same latest revision, no new snapshot.
        assertEquals(baselineRevision, latestPointer(), "the failed publish must not move the pointer")
        assertEquals(baselineCount, snapshotCount(), "the failed publish must not create a snapshot")
    }
}
