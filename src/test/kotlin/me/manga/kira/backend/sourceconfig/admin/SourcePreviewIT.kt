package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class SourcePreviewIT : AbstractAdminSourceIT() {
    @Test
    fun `preview executes the shared engine against an inert response fixture`() {
        val source = SourceConfigFixtures.validGenericSource("Previewed")
        val pending =
            mockMvc.post("/api/v1/admin/source-preview") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "sourceJson" to toJson(source),
                            "operation" to "search",
                            "page" to 3,
                            "query" to "safe query",
                            "responseStatus" to 200,
                            "responseBody" to """{"items":[]}""",
                        ),
                    )
            }.andExpect {
                request { asyncStarted() }
            }.andReturn()
        mockMvc
            .perform(asyncDispatch(pending))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.request.url").value("https://example.com/search?q=safe%20query&page=3"))
            .andExpect(jsonPath("$.request.method").value("GET"))
            .andExpect(jsonPath("$.request.headerNames").isArray)
            .andExpect(jsonPath("$.output").isArray)
    }

    @Test
    fun `preview rejects non generic source without executing`() {
        val pending =
            mockMvc.post("/api/v1/admin/source-preview") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "sourceJson" to toJson(SourceConfigFixtures.validLegacySource("LegacyPreview")),
                            "operation" to "home",
                        ),
                    )
            }.andExpect {
                request { asyncStarted() }
            }.andReturn()
        mockMvc
            .perform(asyncDispatch(pending))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].code").value("NON_GENERIC_PREVIEW_FORBIDDEN"))
    }
}
