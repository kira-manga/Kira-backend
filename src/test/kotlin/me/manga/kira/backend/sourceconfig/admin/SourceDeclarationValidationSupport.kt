package me.manga.kira.backend.sourceconfig.admin

import com.fasterxml.jackson.databind.JsonNode
import me.manga.kira.backend.sourceconfig.DeclarationValidationFixtures
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

internal const val LEGACY_DECLARATION_RULES_VERSION = "schema1/rules-2026.09-hdr"

/** Shared request/assertion helpers only; the concrete IT owns every test invocation. */
abstract class SourceDeclarationValidationSupport : AbstractAdminSourceIT() {
    override val bootstrapCatalogBeforeEach: Boolean = true

    protected fun assertFinding(errors: JsonNode, id: String, api: String) {
        val expected = DeclarationValidationFixtures.fixture(id).expectedFindings.single()
        assertEquals(1, errors.size(), "the shared defect, not a missing server-required endpoint, must cause rejection")
        assertEquals(expected.code, errors[0].get("code").asText())
        assertEquals("sources[$api].${expected.path}", errors[0].get("path").asText())
        assertFalse(errors[0].get("message").asText().isBlank())
    }

    protected fun seedPublishedSource(api: String) {
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(true) }
        }
        publish(api, 1).andExpect { status { isOk() } }
    }

    protected fun markHistoricallyValid(api: String, number: Int) {
        val revision = sourceAdminService.getRevision(api, number).revision
        // Simulate a row created under the prior rules, without changing immutable revision bytes.
        assertEquals(
            1,
            jdbcTemplate.update(
                "UPDATE source_validation_results SET valid = TRUE, errors = '[]'::jsonb, warnings = '[]'::jsonb, rules_version = ? WHERE revision_id = ?",
                LEGACY_DECLARATION_RULES_VERSION,
                revision.id,
            ),
        )
        assertTrue(sourceAdminService.getLatestValidation(api, number).isValid, "the stored-valid precondition must be real")
    }

    protected fun openAndSaveDraft(api: String, source: SourceConfig) {
        mockMvc.post("/api/v1/admin/sources/$api/editor-draft") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"draft-1\"") }
        }
        saveDraft(api, 1, source).andExpect {
            status { isOk() }
            header { string("ETag", "\"draft-2\"") }
        }
    }

    protected fun saveDraft(api: String, version: Int, source: SourceConfig): ResultActionsDsl = mockMvc.put("/api/v1/admin/sources/$api/editor-draft") {
        header("Authorization", "Bearer $adminToken")
        header("If-Match", "\"draft-$version\"")
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsString(mapOf("content" to toJson(source)))
    }

    protected fun readDraft(api: String): ResultActionsDsl = mockMvc.get("/api/v1/admin/sources/$api/editor-draft") {
        header("Authorization", "Bearer $adminToken")
    }.andExpect { status { isOk() } }

    protected fun validationCount(api: String): Long = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM source_validation_results v JOIN source_config_revisions r ON r.id = v.revision_id " +
            "JOIN source_configs s ON s.id = r.source_config_id WHERE s.api = ?",
        Long::class.java,
        api,
    )!!

    protected fun auditCount(action: String, api: String): Long = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM audit_log WHERE action = ? AND detail->>'api' = ?",
        Long::class.java,
        action,
        api,
    )!!
}
