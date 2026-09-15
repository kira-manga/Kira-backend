package me.manga.kira.backend.sourceconfig.admin

import com.fasterxml.jackson.databind.JsonNode
import me.manga.kira.backend.sourceconfig.DeclarationValidationFixtures
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.application.SourceConfigValidationConfig
import me.manga.kira.backend.sourceconfig.domain.RevisionStatus
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/** Real bean wiring, HTTP boundaries and PostgreSQL transactions; no mocked validator or persistence. */
class SourceDeclarationValidationIT : AbstractAdminSourceIT() {
    override val bootstrapCatalogBeforeEach: Boolean = true

    @ParameterizedTest(name = "inspectable declaration draft {0}")
    @MethodSource("unsupportedIds")
    fun `ordinary create and revision retain declaration-invalid content with current stored rules`(id: String) {
        val api = "DeclarationDraft"
        val source = DeclarationValidationFixtures.completeSource(id, api)
        val before = publicState()
        val created = createSource(source).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(false) }
        }.andReturn().response.contentAsString
        assertFinding(objectMapper.readTree(created).get("validation").get("errors"), id, api)

        val revised = createRevision(api, source.copy(displayName = "Still inspectable")).andExpect {
            status { isCreated() }
            jsonPath("$.revisionNumber") { value(2) }
            jsonPath("$.validation.valid") { value(false) }
        }.andReturn().response.contentAsString
        assertFinding(objectMapper.readTree(revised).get("validation").get("errors"), id, api)
        assertEquals(2L, revisionCount(api))
        assertEquals(2L, validationCount(api))
        assertEquals("draft", sourceStatus(api))
        assertNotEquals(LEGACY_RULES_VERSION, SourceConfigValidationConfig.RULES_VERSION)
        for (number in 1..2) {
            val revision = sourceAdminService.getRevision(api, number).revision
            assertEquals(RevisionStatus.DRAFT, revision.status)
            assertEquals(
                if (number == 1) source else source.copy(displayName = "Still inspectable"),
                SourceConfigParser.parseCompatibleSource(revision.configCanonicalJson),
                "invalid authoring content must remain available for inspection",
            )
            assertEquals(
                SourceConfigValidationConfig.RULES_VERSION,
                jdbcTemplate.queryForObject(
                    "SELECT rules_version FROM source_validation_results WHERE revision_id = ?",
                    String::class.java,
                    revision.id,
                ),
            )
            val stored = mockMvc.get("/api/v1/admin/sources/$api/revisions/$number/validation") {
                header("Authorization", "Bearer $adminToken")
            }.andExpect {
                status { isOk() }
                jsonPath("$.valid") { value(false) }
            }.andReturn().response.contentAsString
            assertFinding(objectMapper.readTree(stored).get("errors"), id, api)
            assertPublicArtifactAbsent(api, number)
        }
        assertPublicStateUnchanged(before)
    }

    @ParameterizedTest(name = "stale stored validity {0}")
    @MethodSource("unsupportedIds")
    fun `publish revalidates a historically valid draft without changing any publication or authoring state`(id: String) {
        val api = "StoredDeclaration"
        seedPublishedSource(api)
        createRevision(api, DeclarationValidationFixtures.completeSource(id, api)).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(false) }
        }
        markHistoricallyValid(api, 2)
        val before = publicState()
        val rowsBefore = jdbcTemplate.bootstrapMutationRows()

        val rejection = publish(api, 2).andExpect {
            status { isUnprocessableEntity() }
        }.andReturn().response.contentAsString
        assertFinding(objectMapper.readTree(rejection).get("errors"), id, api)
        assertEquals(rowsBefore, jdbcTemplate.bootstrapMutationRows(), "failed publication also rolls back its tentative validation record")
        assertEquals(2L, revisionCount(api), "the inspectable invalid draft is not deleted")
        assertEquals(1L, publishedRevisionCount(api))
        assertPublicStateUnchanged(before)
        assertPublicArtifactAbsent(api, 2)
    }

    @ParameterizedTest(name = "atomic semantic finalize {0}")
    @MethodSource("unsupportedIds")
    fun `semantic finalize rejects inside the outer transaction and a corrected draft reuses the next revision`(id: String) {
        val api = "FinalizeDeclaration"
        seedPublishedSource(api)
        val invalid = DeclarationValidationFixtures.completeSource(id, api)
        openAndSaveDraft(api, invalid)
        val path = "/api/v1/admin/sources/$api/editor-draft"
        val validation = mockMvc.post("$path/validate") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-2\"")
        }.andExpect {
            status { isOk() }
            jsonPath("$.valid") { value(false) }
        }.andReturn().response.contentAsString
        assertFinding(objectMapper.readTree(validation).get("errors"), id, api)
        val draftBefore = readDraft(api).andReturn().response.contentAsString
        val before = publicState()
        val rowsBefore = jdbcTemplate.bootstrapMutationRows()
        val validationsBefore = validationCount(api)
        val revisionAuditsBefore = auditCount("REVISION_CREATED", api)
        val finalizeAuditsBefore = auditCount("SOURCE_DRAFT_FINALIZED", api)

        val rejection = mockMvc.post("$path/finalize") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-2\"")
        }.andExpect {
            status { isUnprocessableEntity() }
        }.andReturn().response.contentAsString
        assertFinding(objectMapper.readTree(rejection).get("errors"), id, api)
        assertEquals(rowsBefore, jdbcTemplate.bootstrapMutationRows(), "revision, validation and REVISION_CREATED audit must all roll back")
        val draftAfter = readDraft(api).andExpect {
            header { string("ETag", "\"draft-2\"") }
            jsonPath("$.basedOnRevisionNumber") { value(1) }
            jsonPath("$.content") { value(toJson(invalid)) }
        }.andReturn().response.contentAsString
        assertEquals(draftBefore, draftAfter, "semantic rejection must not advance the editor baseline or alter the autosave")
        assertPublicStateUnchanged(before)
        assertPublicArtifactAbsent(api, 2)

        // The rejected createRevision really rolled back: finalization can now create r2, not r3.
        saveDraft(api, 2, SourceConfigFixtures.validGenericSource(api).copy(displayName = "Corrected declaration")).andExpect {
            status { isOk() }
            header { string("ETag", "\"draft-3\"") }
        }
        mockMvc.post("$path/finalize") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-3\"")
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"draft-4\"") }
            jsonPath("$.draft.basedOnRevisionNumber") { value(2) }
            jsonPath("$.revision.revisionNumber") { value(2) }
            jsonPath("$.revision.validation.valid") { value(true) }
        }
        assertEquals(2L, revisionCount(api))
        assertEquals(validationsBefore + 1, validationCount(api), "successful finalize stores only createRevision's single validation result")
        assertEquals(revisionAuditsBefore + 1, auditCount("REVISION_CREATED", api))
        assertEquals(finalizeAuditsBefore + 1, auditCount("SOURCE_DRAFT_FINALIZED", api))
        assertPublicStateUnchanged(before)
        assertPublicArtifactAbsent(api, 2)
    }

    @Test
    fun `quick publish refuses an unsupported counter and preserves editor baseline revisions audits and both public artifacts`() {
        val id = "page_counter_does_not_update_page_offset"
        val api = "QuickDeclaration"
        seedPublishedSource(api)
        openAndSaveDraft(api, DeclarationValidationFixtures.completeSource(id, api))
        val draftBefore = readDraft(api).andReturn().response.contentAsString
        val proof = issueStepUp()
        val before = publicState()
        val rowsBefore = jdbcTemplate.bootstrapMutationRows()

        val rejection = mockMvc.post("/api/v1/admin/sources/$api/editor-draft/publish") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"draft-2\"")
            header("X-Kira-Admin-Step-Up", proof)
        }.andExpect {
            status { isUnprocessableEntity() }
        }.andReturn().response.contentAsString
        assertFinding(objectMapper.readTree(rejection).get("errors"), id, api)
        assertEquals(rowsBefore, jdbcTemplate.bootstrapMutationRows())
        val draftAfter = readDraft(api).andExpect {
            header { string("ETag", "\"draft-2\"") }
            jsonPath("$.basedOnRevisionNumber") { value(1) }
        }.andReturn().response.contentAsString
        assertEquals(draftBefore, draftAfter)
        assertEquals(1L, revisionCount(api))
        assertPublicStateUnchanged(before)
        assertPublicArtifactAbsent(api, 2)
    }

    @Test
    fun `mixed changeset revalidates stale stored validity before any source revision or catalog is applied`() {
        val id = "json_scalar_path"
        val readyApi = "AReadyDeclaration"
        val invalidApi = "ZInvalidDeclaration"
        seedPublishedSource(readyApi)
        seedPublishedSource(invalidApi)
        createRevision(readyApi, SourceConfigFixtures.validGenericSource(readyApi).copy(displayName = "Ready revision")).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(true) }
        }
        createRevision(invalidApi, DeclarationValidationFixtures.completeSource(id, invalidApi)).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(false) }
        }
        markHistoricallyValid(invalidApi, 2)
        val created = mockMvc.post("/api/v1/admin/source-changesets") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Declaration boundary"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val changesetId = objectMapper.readTree(created).get("id").asText()
        val path = "/api/v1/admin/source-changesets/$changesetId"
        mockMvc.put(path) {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"changeset-1\"")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf(
                    "name" to "Declaration boundary",
                    "operations" to listOf(
                        mapOf("type" to "publish", "api" to readyApi, "revisionNumber" to 2),
                        mapOf("type" to "publish", "api" to invalidApi, "revisionNumber" to 2),
                    ),
                ),
            )
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"changeset-2\"") }
        }
        val proof = issueStepUp()
        val before = publicState()
        val rowsBefore = jdbcTemplate.bootstrapMutationRows()
        val validation = mockMvc.post("$path/validate") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"changeset-2\"")
        }.andExpect { status { isUnprocessableEntity() } }.andReturn().response.contentAsString
        assertFinding(objectMapper.readTree(validation).get("errors"), id, invalidApi)
        assertEquals(rowsBefore, jdbcTemplate.bootstrapMutationRows(), "changeset validation is a read-only rejection")

        val rejection = mockMvc.post("$path/apply") {
            header("Authorization", "Bearer $adminToken")
            header("If-Match", "\"changeset-2\"")
            header("X-Kira-Admin-Step-Up", proof)
        }.andExpect { status { isUnprocessableEntity() } }.andReturn().response.contentAsString
        assertFinding(objectMapper.readTree(rejection).get("errors"), id, invalidApi)
        assertEquals(rowsBefore, jdbcTemplate.bootstrapMutationRows(), "the valid first operation cannot partially publish")
        mockMvc.get(path) { header("Authorization", "Bearer $adminToken") }.andExpect {
            status { isOk() }
            header { string("ETag", "\"changeset-2\"") }
            jsonPath("$.status") { value("open") }
        }
        assertPublicStateUnchanged(before)
        assertPublicArtifactAbsent(readyApi, 2)
        assertPublicArtifactAbsent(invalidApi, 2)
    }

    private fun assertFinding(errors: JsonNode, id: String, api: String) {
        val expected = DeclarationValidationFixtures.fixture(id).expectedFindings.single()
        assertEquals(1, errors.size(), "the shared defect, not a missing server-required endpoint, must cause rejection")
        assertEquals(expected.code, errors[0].get("code").asText())
        assertEquals("sources[$api].${expected.path}", errors[0].get("path").asText())
        assertFalse(errors[0].get("message").asText().isBlank())
    }

    private fun seedPublishedSource(api: String) {
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(true) }
        }
        publish(api, 1).andExpect { status { isOk() } }
    }

    private fun markHistoricallyValid(api: String, number: Int) {
        val revision = sourceAdminService.getRevision(api, number).revision
        // Simulate a row created under the prior rules, without changing immutable revision bytes.
        assertEquals(
            1,
            jdbcTemplate.update(
                "UPDATE source_validation_results SET valid = TRUE, errors = '[]'::jsonb, warnings = '[]'::jsonb, rules_version = ? WHERE revision_id = ?",
                LEGACY_RULES_VERSION,
                revision.id,
            ),
        )
        assertTrue(sourceAdminService.getLatestValidation(api, number).isValid, "the stored-valid precondition must be real")
    }

    private fun openAndSaveDraft(api: String, source: SourceConfig) {
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

    private fun saveDraft(api: String, version: Int, source: SourceConfig): ResultActionsDsl = mockMvc.put("/api/v1/admin/sources/$api/editor-draft") {
        header("Authorization", "Bearer $adminToken")
        header("If-Match", "\"draft-$version\"")
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsString(mapOf("content" to toJson(source)))
    }

    private fun readDraft(api: String): ResultActionsDsl = mockMvc.get("/api/v1/admin/sources/$api/editor-draft") {
        header("Authorization", "Bearer $adminToken")
    }.andExpect { status { isOk() } }

    private fun validationCount(api: String): Long = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM source_validation_results v JOIN source_config_revisions r ON r.id = v.revision_id " +
            "JOIN source_configs s ON s.id = r.source_config_id WHERE s.api = ?",
        Long::class.java,
        api,
    )!!

    private fun auditCount(action: String, api: String): Long = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM audit_log WHERE action = ? AND detail->>'api' = ?",
        Long::class.java,
        action,
        api,
    )!!

    companion object {
        private const val LEGACY_RULES_VERSION = "schema1/rules-2026.09-hdr"

        @JvmStatic
        fun unsupportedIds(): List<String> = DeclarationValidationFixtures.unsupportedIds
    }
}
