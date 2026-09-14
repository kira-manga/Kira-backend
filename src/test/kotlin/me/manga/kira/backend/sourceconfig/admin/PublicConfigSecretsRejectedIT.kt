package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.HeaderFilterSafetyFixtures
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.application.SourceConfigValidationConfig
import me.manga.kira.backend.sourceconfig.domain.RevisionStatus
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.get

/**
 * PLAN §11 test 29 — `PublicConfigSecretsRejectedIT`: no credential material can be published (PLAN §8
 * rule 32). A `Cookie` header → rejected (`FORBIDDEN_HEADER`); `authorization: "Bearer real-token-xyz"`
 * → rejected (`SECRET_LIKE_HEADER`); `authorization: "Bearer null"` → ACCEPTED (the bundled placeholder);
 * a URL with user-info → rejected (`URL_USERINFO_FORBIDDEN`); and the FULL real bundled document passes
 * every secret-safety rule.
 */
class PublicConfigSecretsRejectedIT : AbstractAdminSourceIT() {
    override val bootstrapCatalogBeforeEach: Boolean = true

    @Autowired
    private lateinit var validator: SourceConfigValidator

    @Test
    fun `unsafe header drafts retain content and safe diagnostics but cannot change public v1 or v2`() {
        val baseline = SourceConfigFixtures.validGenericSource("HeaderBaseline")
        createSource(baseline).andExpect { status { isCreated() } }
        publish(baseline.api, 1).andExpect { status { isOk() } }
        val before = publicState()
        val unsafe = HeaderFilterSafetyFixtures.unsafeSource("HeaderDraft")

        // Otherwise valid: this 422 must be caused by the new name gate, not a bad default.
        val created = createSource(unsafe).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(false) }
            jsonPath("$.validation.errors.length()") { value(1) }
            jsonPath("$.validation.errors[0].code") { value("SECRET_LIKE_HEADER") }
        }.andReturn().response.contentAsString
        assertNoDiagnosticSentinels(created)
        val rejection = publish(unsafe.api, 1).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.errors.length()") { value(1) }
            jsonPath("$.errors[0].code") { value("SECRET_LIKE_HEADER") }
        }.andReturn().response.contentAsString
        assertNoDiagnosticSentinels(rejection)
        assertEquals(1L, revisionCount(unsafe.api), "invalid draft remains stored")
        assertPublicStateUnchanged(before)
        assertPublicArtifactAbsent(unsafe.api, 1)

        // Collected old errors must not leak values via inline, stored, or failed-publish diagnostics.
        val diagnostic = HeaderFilterSafetyFixtures.diagnosticSource(unsafe.api)
        val revisionResponse = createRevision(unsafe.api, diagnostic).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(false) }
        }.andReturn().response.contentAsString
        assertNoDiagnosticSentinels(revisionResponse)
        val inline = objectMapper.readTree(revisionResponse).get("validation")
        assertEquals(HeaderFilterSafetyFixtures.diagnosticErrorCounts, inline.get("errors").groupingBy { it.get("code").asText() }.eachCount())
        val revision = sourceAdminService.getRevision(unsafe.api, 2).revision
        assertEquals(RevisionStatus.DRAFT, revision.status)
        assertEquals(diagnostic, SourceConfigParser.parseCompatibleSource(revision.configCanonicalJson), "raw admin content is deliberately retained")
        val storedPayload = jdbcTemplate.queryForObject(
            "SELECT jsonb_build_object('valid', valid, 'errors', errors, 'warnings', warnings, 'rulesVersion', rules_version)::text " +
                "FROM source_validation_results WHERE revision_id = ? ORDER BY validated_at DESC LIMIT 1",
            String::class.java,
            revision.id,
        )!!
        assertNoDiagnosticSentinels(storedPayload)
        val stored = objectMapper.readTree(storedPayload)
        assertFalse(stored.get("valid").asBoolean())
        assertEquals(inline.get("errors"), stored.get("errors"))
        assertEquals(inline.get("warnings"), stored.get("warnings"))
        assertEquals(SourceConfigValidationConfig.RULES_VERSION, stored.get("rulesVersion").asText())
        val validationRead = mockMvc.get("/api/v1/admin/sources/${unsafe.api}/revisions/2/validation") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertNoDiagnosticSentinels(validationRead)
        assertEquals(inline, objectMapper.readTree(validationRead))

        val diagnosticRejection = publish(unsafe.api, 2).andExpect { status { isUnprocessableEntity() } }.andReturn().response.contentAsString
        assertNoDiagnosticSentinels(diagnosticRejection)
        assertEquals(inline.get("errors"), objectMapper.readTree(diagnosticRejection).get("errors"))
        assertEquals("draft", sourceStatus(unsafe.api))
        assertEquals(2L, revisionCount(unsafe.api), "rejection must not erase intentionally stored drafts")
        assertEquals(0L, publishedRevisionCount(unsafe.api))
        assertEquals(1L, publishedRevisionCount(baseline.api))
        assertPublicStateUnchanged(before)
        assertPublicArtifactAbsent(unsafe.api, 2)
        getPublicSource(unsafe.api).andExpect { status { isNotFound() } }
    }

    @Test
    fun `a forbidden Cookie header is rejected`() {
        val model = SourceConfigFixtures.validGenericSource("Cookie").copy(headers = mapOf("Cookie" to "session=x"))
        createSource(model).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(false) }
            jsonPath("$.validation.errors[?(@.code == 'FORBIDDEN_HEADER')]") { exists() }
        }
        publish("Cookie", 1).andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    fun `a real bearer token on authorization is rejected as secret-like`() {
        val model =
            SourceConfigFixtures.validGenericSource("Auth").copy(headers = mapOf("authorization" to "Bearer real-token-xyz"))
        createSource(model).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(false) }
            jsonPath("$.validation.errors[?(@.code == 'SECRET_LIKE_HEADER')]") { exists() }
        }
    }

    @Test
    fun `a whitespace-padded authorization header cannot bypass publication validation`() {
        val api = "PaddedAuth"
        val model =
            SourceConfigFixtures.validGenericSource(api).copy(
                headers = mapOf("Authorization " to "Bearer real-token-xyz"),
            )

        createSource(model).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(false) }
            jsonPath("$.validation.errors[?(@.code == 'HEADER_NAME_INVALID')]") { exists() }
        }
        publish(api, 1).andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    fun `the Bearer null placeholder is accepted`() {
        val model =
            SourceConfigFixtures.validGenericSource("Placeholder").copy(headers = mapOf("authorization" to "Bearer null"))
        createSource(model).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(true) }
        }
        publish("Placeholder", 1).andExpect { status { isOk() } }
    }

    @Test
    fun `a url with user-info is rejected`() {
        val model = SourceConfigFixtures.validGenericSource("UserInfo").copy(baseUrl = "https://user:pass@example.com")
        createSource(model).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(false) }
            jsonPath("$.validation.errors[?(@.code == 'URL_USERINFO_FORBIDDEN')]") { exists() }
        }
    }

    @Test
    fun `the full real bundled document passes all secret-safety rules`() {
        val document = SourceConfigParser.parseCompatibleDocument(SourceConfigFixtures.loadFixture("bundled-full.json"))
        val result = validator.validate(document)
        val secretCodes =
            setOf(
                "FORBIDDEN_HEADER",
                "SECRET_LIKE_HEADER",
                "URL_USERINFO_FORBIDDEN",
                "URL_INVALID",
                "URL_SCHEME_INVALID",
                "URL_FRAGMENT_FORBIDDEN",
                "URL_HOST_MISSING",
            )
        val offending = result.errors.filter { it.code in secretCodes }
        assertTrue(offending.isEmpty(), "bundled document must have zero secret-safety errors, got: $offending")
    }
}
