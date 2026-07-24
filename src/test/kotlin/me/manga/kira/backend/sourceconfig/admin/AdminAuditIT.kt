package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

class AdminAuditIT : AbstractAdminSourceIT() {
    @Test
    fun `audit page exposes metadata but not config bodies`() {
        createSource(SourceConfigFixtures.validGenericSource("AuditSafe")).andExpect { status { isCreated() } }

        mockMvc.get("/api/v1/admin/audit") {
            header("Authorization", "Bearer $adminToken")
            param("page", "0")
            param("size", "20")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(2) }
            jsonPath("$.items[0].action") { exists() }
            jsonPath("$.items[0].detail") { isMap() }
            jsonPath("$.items[0].detail.config") { doesNotExist() }
        }
    }

    @Test
    fun `audit pagination fails closed outside bounds`() {
        mockMvc.get("/api/v1/admin/audit") {
            header("Authorization", "Bearer $adminToken")
            param("size", "101")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].code") { value("INVALID_PAGE") }
        }
    }
}
