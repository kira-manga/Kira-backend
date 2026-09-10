package me.manga.kira.backend.tutorial

import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

class TutorialSlugBoundaryIT : AbstractIntegrationTest() {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var users: UserRepository

    @Autowired lateinit var jwtService: JwtService

    @ParameterizedTest(name = "{displayName} [{index}] {0} length={1}")
    @CsvSource("CATEGORY, 64", "CATEGORY, 65", "TUTORIAL, 96", "TUTORIAL, 97")
    fun `ADMIN creation enforces entity slug boundaries`(identity: Identity, length: Int) {
        val slug = "a".repeat(length)
        val admin = users.create("slug-boundary-admin@example.test", "{noop}unused", Role.ADMIN)
        val token = jwtService.issue(admin).value
        val identitiesBefore = identityCount(identity)
        val auditsBefore = createAuditCount(identity)

        val response = mockMvc.post("/api/v1/admin/${identity.resource}") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"slug":"$slug","position":0,"featuredPosition":0}"""
        }

        if (length == identity.maximumLength) {
            response.andExpect {
                status { isCreated() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
                jsonPath("$.slug") { value(slug) }
                jsonPath("$.position") { value(0) }
                if (identity == Identity.CATEGORY) {
                    jsonPath("$.featuredPosition") { doesNotExist() }
                } else {
                    jsonPath("$.featuredPosition") { value(0) }
                }
            }
            assertEquals(identitiesBefore + 1, identityCount(identity))
            assertEquals(slug, jdbcTemplate.queryForObject("SELECT slug FROM ${identity.table} WHERE slug = ?", String::class.java, slug))
            if (identity == Identity.TUTORIAL) {
                assertEquals(0, requireNotNull(jdbcTemplate.queryForObject("SELECT featured_position FROM tutorials WHERE slug = ?", Int::class.java, slug)))
            }
            assertEquals(auditsBefore + 1, createAuditCount(identity))
        } else {
            response.andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.status") { value(400) }
                jsonPath("$.detail") { value("slug must be at most ${identity.maximumLength} characters.") }
                jsonPath("$.errors.length()") { value(1) }
                jsonPath("$.errors[0].path") { value("slug") }
                jsonPath("$.errors[0].code") { value("TOO_LONG") }
                jsonPath("$.errors[0].message") { value("must be at most ${identity.maximumLength} characters") }
            }
            assertEquals(identitiesBefore, identityCount(identity))
            assertEquals(auditsBefore, createAuditCount(identity))
        }
    }

    private fun identityCount(identity: Identity): Int = requireNotNull(
        jdbcTemplate.queryForObject("SELECT count(*) FROM ${identity.table}", Int::class.java),
    )

    private fun createAuditCount(identity: Identity): Int = requireNotNull(
        jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Int::class.java, identity.createAction.wire),
    )

    enum class Identity(val resource: String, val table: String, val maximumLength: Int, val createAction: AuditAction) {
        CATEGORY("tutorial-categories", "tutorial_categories", 64, AuditAction.TUTORIAL_CATEGORY_CREATED),
        TUTORIAL("tutorials", "tutorials", 96, AuditAction.TUTORIAL_CREATED),
    }
}
