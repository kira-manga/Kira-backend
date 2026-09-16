package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

class SourceEditorDraftIT : AbstractAdminSourceIT() {
    @Test
    fun `autosave uses optimistic etags and keeps invalid JSON outside immutable history`() {
        createSource(SourceConfigFixtures.validGenericSource("Drafted")).andExpect { status { isCreated() } }

        mockMvc.post("/api/v1/admin/sources/Drafted/editor-draft") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"draft-1\"") }
            jsonPath("$.basedOnRevisionNumber") { value(1) }
            jsonPath("$.version") { value(1) }
        }

        mockMvc.put("/api/v1/admin/sources/Drafted/editor-draft") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-1\"")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("content" to """{"api":"""))
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"draft-2\"") }
            jsonPath("$.version") { value(2) }
        }

        mockMvc.put("/api/v1/admin/sources/Drafted/editor-draft") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-1\"")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("content" to "{}"))
        }.andExpect {
            status { isConflict() }
            jsonPath("$.errors[0].code") { value("SOURCE_DRAFT_VERSION_CONFLICT") }
        }

        mockMvc.get("/api/v1/admin/sources/Drafted/editor-draft") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"draft-2\"") }
            jsonPath("$.content") { value("""{"api":""") }
        }
        assertEquals(1, revisionCount("Drafted"), "autosave must not create immutable revisions")
        assertEquals(0, snapshotCount(), "autosave must not publish a document")
    }

    @Test
    fun `finalize is strict and atomically advances the editor baseline`() {
        val original = SourceConfigFixtures.validGenericSource("FinalizeMe")
        createSource(original).andExpect { status { isCreated() } }
        mockMvc.post("/api/v1/admin/sources/FinalizeMe/editor-draft") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isOk() } }

        val updated = original.copy(displayName = "Finalized source")
        mockMvc.put("/api/v1/admin/sources/FinalizeMe/editor-draft") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-1\"")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("content" to toJson(updated)))
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"draft-2\"") }
        }

        mockMvc.post("/api/v1/admin/sources/FinalizeMe/editor-draft/validate") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-2\"")
        }.andExpect {
            status { isOk() }
            jsonPath("$.valid") { value(true) }
        }

        mockMvc.post("/api/v1/admin/sources/FinalizeMe/editor-draft/finalize") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-2\"")
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"draft-3\"") }
            jsonPath("$.draft.basedOnRevisionNumber") { value(2) }
            jsonPath("$.revision.revisionNumber") { value(2) }
            jsonPath("$.revision.validation.valid") { value(true) }
        }

        assertEquals(2, revisionCount("FinalizeMe"))
        assertEquals(0, snapshotCount(), "finalization is not publication")

        mockMvc.post("/api/v1/admin/sources/FinalizeMe/editor-draft/finalize") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-2\"")
        }.andExpect {
            status { isConflict() }
        }
        assertEquals(2, revisionCount("FinalizeMe"), "stale finalize must not create another revision")
    }

    @Test
    fun `invalid draft fails strict finalization and discard is compare and swap`() {
        createSource(SourceConfigFixtures.validGenericSource("InvalidDraft")).andExpect { status { isCreated() } }
        mockMvc.post("/api/v1/admin/sources/InvalidDraft/editor-draft") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isOk() } }
        mockMvc.put("/api/v1/admin/sources/InvalidDraft/editor-draft") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-1\"")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("content" to """{"api":"""))
        }.andExpect { status { isOk() } }

        mockMvc.post("/api/v1/admin/sources/InvalidDraft/editor-draft/finalize") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-2\"")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].code") { value("MALFORMED_CONFIG_JSON") }
        }
        assertEquals(1, revisionCount("InvalidDraft"))

        mockMvc.delete("/api/v1/admin/sources/InvalidDraft/editor-draft") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-1\"")
        }.andExpect { status { isConflict() } }
        mockMvc.delete("/api/v1/admin/sources/InvalidDraft/editor-draft") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-2\"")
        }.andExpect { status { isNoContent() } }
        mockMvc.get("/api/v1/admin/sources/InvalidDraft/editor-draft") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `oversized autosave fails without changing the draft`() {
        createSource(SourceConfigFixtures.validGenericSource("BoundedDraft")).andExpect { status { isCreated() } }
        mockMvc.post("/api/v1/admin/sources/BoundedDraft/editor-draft") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isOk() } }

        mockMvc.put("/api/v1/admin/sources/BoundedDraft/editor-draft") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-1\"")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("content" to "x".repeat(512 * 1024 + 1)))
        }.andExpect { status { isPayloadTooLarge() } }

        mockMvc.get("/api/v1/admin/sources/BoundedDraft/editor-draft") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"draft-1\"") }
        }
    }

    @Test
    fun `quick publish is atomic and requires one-time password step-up`() {
        val original = SourceConfigFixtures.validGenericSource("QuickPublish")
        createSource(original).andExpect { status { isCreated() } }
        mockMvc.post("/api/v1/admin/sources/QuickPublish/editor-draft") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isOk() } }
        mockMvc.put("/api/v1/admin/sources/QuickPublish/editor-draft") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-1\"")
            contentType = MediaType.APPLICATION_JSON
            content =
                objectMapper.writeValueAsString(
                    mapOf("content" to toJson(original.copy(displayName = "Ready to publish"))),
                )
        }.andExpect { status { isOk() } }

        mockMvc.post("/api/v1/admin/sources/QuickPublish/editor-draft/publish") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-2\"")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.errors[0].code") { value("ADMIN_STEP_UP_REQUIRED") }
        }
        assertEquals(1, revisionCount("QuickPublish"))
        assertEquals(0, snapshotCount())

        val proof = issueStepUp()
        mockMvc.post("/api/v1/admin/sources/QuickPublish/editor-draft/publish") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-2\"")
            header("X-Kira-Admin-Step-Up", proof)
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"draft-3\"") }
            jsonPath("$.revision.revisionNumber") { value(2) }
            jsonPath("$.revision.validation.valid") { value(true) }
            jsonPath("$.publication.documentRevision") { isNumber() }
        }
        assertEquals(2, revisionCount("QuickPublish"))
        assertEquals(1, snapshotCount())
        assertEquals("active", sourceStatus("QuickPublish"))

        mockMvc.post("/api/v1/admin/sources/QuickPublish/editor-draft/publish") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-3\"")
            header("X-Kira-Admin-Step-Up", proof)
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.errors[0].code") { value("ADMIN_STEP_UP_REQUIRED") }
        }
        assertEquals(2, revisionCount("QuickPublish"), "used proof cannot create another revision")
        assertEquals(1, snapshotCount(), "used proof cannot publish another snapshot")
    }
}
