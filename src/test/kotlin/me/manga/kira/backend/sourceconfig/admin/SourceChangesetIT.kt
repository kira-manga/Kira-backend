package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

class SourceChangesetIT : AbstractAdminSourceIT() {
    @Test
    fun `changesets autosave with etags and apply two sources in one snapshot`() {
        seedPublished("Alpha")
        seedPublished("Beta")
        createRevision("Alpha", SourceConfigFixtures.validGenericSource("Alpha").copy(displayName = "Alpha 2"))
        createRevision("Beta", SourceConfigFixtures.validGenericSource("Beta").copy(displayName = "Beta 2"))
        val before = snapshotCount()

        val id = createChangeset()
        saveChangeset(
            id,
            "\"changeset-1\"",
            listOf(
                mapOf("type" to "publish", "api" to "Alpha", "revisionNumber" to 2),
                mapOf("type" to "publish", "api" to "Beta", "revisionNumber" to 2),
            ),
        ).andExpect {
            status { isOk() }
            header { string("ETag", "\"changeset-2\"") }
        }

        mockMvc.post("/api/v1/admin/source-changesets/$id/validate") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"changeset-2\"")
        }.andExpect {
            status { isOk() }
            jsonPath("$.valid") { value(true) }
            jsonPath("$.operationCount") { value(2) }
        }

        val proof = issueStepUp()
        mockMvc.post("/api/v1/admin/source-changesets/$id/apply") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"changeset-2\"")
            header("X-Kira-Admin-Step-Up", proof)
        }.andExpect {
            status { isOk() }
            jsonPath("$.affectedApis.length()") { value(2) }
        }

        assertEquals(before + 1, snapshotCount(), "one changeset must create exactly one snapshot")
        assertEquals(1, publishedRevisionCount("Alpha"))
        assertEquals(1, publishedRevisionCount("Beta"))
        mockMvc.get("/api/v1/admin/source-changesets/$id") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"changeset-3\"") }
            jsonPath("$.status") { value("applied") }
        }
    }

    @Test
    fun `failed changeset makes no partial lifecycle or catalog change`() {
        seedPublished("Alpha")
        seedPublished("Beta")
        disable("Alpha").andExpect { status { isOk() } }
        val beforeSnapshots = snapshotCount()
        val beforePointer = latestPointer()
        val id = createChangeset()
        saveChangeset(
            id,
            "\"changeset-1\"",
            listOf(
                mapOf("type" to "enable", "api" to "Alpha"),
                mapOf("type" to "retire", "api" to "Beta"),
            ),
        ).andExpect { status { isOk() } }

        val proof = issueStepUp()
        mockMvc.post("/api/v1/admin/source-changesets/$id/apply") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"changeset-2\"")
            header("X-Kira-Admin-Step-Up", proof)
        }.andExpect {
            status { isConflict() }
        }

        assertEquals("disabled", sourceStatus("Alpha"), "valid first operation must roll back/not run")
        assertEquals("active", sourceStatus("Beta"))
        assertEquals(beforeSnapshots, snapshotCount())
        assertEquals(beforePointer, latestPointer())
        mockMvc.get("/api/v1/admin/source-changesets/$id") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"changeset-2\"") }
            jsonPath("$.status") { value("open") }
        }
    }

    @Test
    fun `stale changeset write fails and apply requires one-time step-up`() {
        seedPublished("Only")
        val id = createChangeset()
        saveChangeset(
            id,
            "\"changeset-1\"",
            listOf(mapOf("type" to "disable", "api" to "Only")),
        ).andExpect { status { isOk() } }
        saveChangeset(id, "\"changeset-1\"", emptyList()).andExpect {
            status { isConflict() }
            jsonPath("$.errors[0].code") { value("SOURCE_CHANGESET_VERSION_CONFLICT") }
        }
        mockMvc.post("/api/v1/admin/source-changesets/$id/apply") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"changeset-2\"")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.errors[0].code") { value("ADMIN_STEP_UP_REQUIRED") }
        }
        assertEquals("active", sourceStatus("Only"))
    }

    private fun seedPublished(api: String) {
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }
    }

    private fun createChangeset(): String {
        val response =
            mockMvc.post("/api/v1/admin/source-changesets") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Release set"}"""
            }.andReturn().response
        check(response.status in 200..299)
        return objectMapper.readTree(response.contentAsString).get("id").asText()
    }

    private fun saveChangeset(id: String, etag: String, operations: List<Map<String, Any>>) = mockMvc.put("/api/v1/admin/source-changesets/$id") {
        header("Authorization", "Bearer $adminToken")
        header("If-Match", etag)
        contentType = MediaType.APPLICATION_JSON
        content =
            objectMapper.writeValueAsString(
                mapOf("name" to "Release set", "operations" to operations),
            )
    }
}
