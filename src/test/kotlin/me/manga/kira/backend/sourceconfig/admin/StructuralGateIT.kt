package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.application.StructuralAuthoringGate
import me.manga.kira.backend.sourceconfig.domain.model.EndpointSpec
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 44 — `StructuralGateIT`: identity defects are 400s that persist NOTHING; semantic
 * defects are stored inspectable drafts (PLAN §8 two-tier model). Each Tier-1 violation → 400 with the
 * named code and zero rows; an oversized `displayName` → 400 (not a 500 from a persistence exception); a
 * Tier-2-invalid draft IS created with its stored result; every real bundled api (incl. `"Team X"`,
 * `"مانجا بارك"`) passes the identifier gate.
 */
class StructuralGateIT : AbstractAdminSourceIT() {

    private val base = SourceConfigFixtures.validGenericSource("Base")

    @Test
    fun `blank, control-char, slash, over-128 and trailing-space apis are 400 with nothing persisted`() {
        val badApis = listOf("", "abcd", "a/b", "a\\b", "x".repeat(129), "trailing ")
        for (badApi in badApis) {
            createSource(base.copy(api = badApi)).andExpect {
                status { isBadRequest() }
                jsonPath("$.errors[0].code") { value(StructuralAuthoringGate.API_IDENTIFIER_INVALID) }
            }
        }
        assertEquals(0, sourceRowCount(), "no gate-rejected source may be persisted")
    }

    @Test
    fun `authoring lifecycle is 400 LIFECYCLE_NOT_AUTHORABLE with nothing persisted`() {
        for (lifecycle in listOf("disabled", "removed")) {
            createSource(base.copy(api = "Lc", lifecycle = lifecycle)).andExpect {
                status { isBadRequest() }
                jsonPath("$.errors[0].code") { value(StructuralAuthoringGate.LIFECYCLE_NOT_AUTHORABLE) }
            }
        }
        assertEquals(0, sourceRowCount())
    }

    @Test
    fun `oversized identity fields are 400 FIELD_TOO_LONG not 500`() {
        val tooLong =
            listOf(
                base.copy(api = "D", displayName = "x".repeat(257)),
                base.copy(api = "L", language = "x".repeat(33)),
                base.copy(api = "E", engine = "kotlin:" + "x".repeat(58)), // 65 chars
                base.copy(api = "B", baseUrl = "https://" + "x".repeat(505)), // 513 chars
            )
        for (model in tooLong) {
            createSource(model).andExpect {
                status { isBadRequest() }
                jsonPath("$.errors[0].code") { value(StructuralAuthoringGate.FIELD_TOO_LONG) }
            }
        }
        assertEquals(0, sourceRowCount())
    }

    @Test
    fun `a Tier-2-invalid draft is created and stored with its validation result`() {
        // A generic source missing 'search' is a Tier-2 (semantic) defect, not a Tier-1 identity defect.
        val missingSearch =
            base.copy(api = "Draft", endpoints = base.endpoints.filterKeys { it != "search" })
        createSource(missingSearch).andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("draft") }
            jsonPath("$.validation.valid") { value(false) }
            jsonPath("$.validation.errors[?(@.code == 'GENERIC_MISSING_SEARCH')]") { exists() }
        }
        assertEquals(1, sourceRowCount(), "a Tier-2-invalid draft IS persisted (inspectable)")
    }

    @Test
    fun `every real bundled api passes the identifier gate`() {
        val document = SourceConfigParser.parseCompatibleDocument(SourceConfigFixtures.loadFixture("bundled-full.json"))
        for (source in document.sources) {
            assertThatCode { StructuralAuthoringGate.check(source, pathApi = null) }
                .`as`("bundled api '%s' must pass the Tier-1 gate", source.api)
                .doesNotThrowAnyException()
        }
    }

    /** A generic source missing an endpoint is Tier-2 (stored), never a Tier-1 400 — sanity for the split. */
    @Test
    fun `a missing endpoint is not a structural 400`() {
        val missingPages =
            base.copy(api = "P", endpoints = base.endpoints + ("pages" to EndpointSpec(url = "")))
        createSource(missingPages).andExpect { status { isCreated() } }
    }
}
