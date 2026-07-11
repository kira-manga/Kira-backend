package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.application.StructuralAuthoringGate
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 26 — `ServerManagedLifecycleIT`: payload lifecycle never controls server lifecycle.
 * Authoring bodies with `lifecycle:"disabled"|"removed"` → 400 `LIFECYCLE_NOT_AUTHORABLE` (on both create
 * and revision); rollback copies content only, never restoring an old lifecycle (PLAN §9). (The import
 * lifecycle-mapping half is Phase 8.)
 */
class ServerManagedLifecycleIT : AbstractAdminSourceIT() {

    @Test
    fun `authoring a non-neutral lifecycle is 400 on create and on revision`() {
        for (lifecycle in listOf("disabled", "removed")) {
            createSource(SourceConfigFixtures.validGenericSource("Create").copy(lifecycle = lifecycle)).andExpect {
                status { isBadRequest() }
                jsonPath("$.errors[0].code") { value(StructuralAuthoringGate.LIFECYCLE_NOT_AUTHORABLE) }
            }
        }

        createSource(SourceConfigFixtures.validGenericSource("Rev")).andExpect { status { isCreated() } }
        createRevision("Rev", SourceConfigFixtures.validGenericSource("Rev").copy(lifecycle = "disabled")).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].code") { value(StructuralAuthoringGate.LIFECYCLE_NOT_AUTHORABLE) }
        }
    }

    @Test
    fun `rollback does not restore a prior server lifecycle`() {
        val api = "Roll"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } } // active
        disable(api).andExpect { status { isOk() } } // now disabled

        // Rolling back to r1 publishes a new revision but the source stays DISABLED (publish never re-enables).
        rollback(api, 1).andExpect { status { isOk() } }

        val status =
            jdbcTemplate.queryForObject("SELECT status FROM source_configs WHERE api = ?", String::class.java, api)
        assert(status == "disabled") { "rollback must not restore the lifecycle to active; was $status" }
    }
}
