package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.application.StructuralAuthoringGate
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 27 — `PathApiMismatchIT`: the api identity is immutable and path-bound. Creating a
 * revision whose `body.api` differs from the path `{api}` → 400 `API_ID_MISMATCH` (Tier-1, PLAN §8).
 */
class PathApiMismatchIT : AbstractAdminSourceIT() {

    @Test
    fun `a revision whose body api differs from the path is 400 API_ID_MISMATCH`() {
        createSource(SourceConfigFixtures.validGenericSource("Xsrc")).andExpect { status { isCreated() } }

        createRevision("Xsrc", SourceConfigFixtures.validGenericSource("Ysrc")).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].code") { value(StructuralAuthoringGate.API_ID_MISMATCH) }
        }
    }
}
