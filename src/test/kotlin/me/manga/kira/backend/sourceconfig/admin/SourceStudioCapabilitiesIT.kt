package me.manga.kira.backend.sourceconfig.admin

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

class SourceStudioCapabilitiesIT : AbstractAdminSourceIT() {
    @Test
    fun `capabilities expose the exact generic-only editor contract`() {
        mockMvc.get("/api/v1/admin/source-studio/capabilities") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.sourceSchemaVersion") { value(1) }
            jsonPath("$.catalogSchemaVersion") { value(1) }
            jsonPath("$.canonicalization") { value("kcj-1") }
            jsonPath("$.authorableEngines.length()") { value(1) }
            jsonPath("$.authorableEngines[0]") { value("generic") }
            jsonPath("$.publicEnginePolicy") { value("generic-only") }
            jsonPath("$.editorDraftMaxBytes") { value(524288) }
            jsonPath("$.optimisticLocking") { value("strong-etag-if-match") }
            jsonPath("$.transforms[?(@ == 'trim')]") { exists() }
            jsonPath("$.dateStrategies[?(@ == 'iso')]") { exists() }
            jsonPath("$.paginationStrategies[0]") { value("page-number") }
        }
    }
}
