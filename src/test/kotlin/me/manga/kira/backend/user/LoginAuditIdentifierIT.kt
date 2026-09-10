package me.manga.kira.backend.user

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.audit.domain.AuditEntry
import me.manga.kira.backend.audit.domain.AuditRepository
import me.manga.kira.backend.audit.domain.NewAuditEntry
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import java.time.Instant

/** Real PostgreSQL regression for the normalized-email bound and failed-login audit namespace. */
class LoginAuditIdentifierIT
@Autowired
constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val users: UserRepository,
    private val audit: AuditRepository,
) : AbstractIntegrationTest() {

    private val password = "correct horse battery staple"
    private val wrongPassword = "not the account password"
    private val domain = "@example.com"

    @Test
    fun `128 129 and 320 character credential failures share a generic response and one bounded audit row`() {
        val unknown = emailWithLength(128, 'u')
        val wrong = emailWithLength(129, 'w')
        val disabled = emailWithLength(320, 'd')
        assertEquals(listOf(128, 129, 320), listOf(unknown, wrong, disabled).map { it.codePointCount(0, it.length) })
        register(wrong)
        register(disabled)
        users.setEnabled(requireNotNull(users.findByEmail(disabled)).id, false)

        val genericResponse = failedLogin(unknown)
        assertEquals(genericResponse, failedLogin(wrong))
        assertEquals(genericResponse, failedLogin(disabled, password))
        assertEquals(3, failedEntries().size)
    }

    @Test
    fun `normalization variants correlate but identifiers differing after a shared 128 character prefix do not`() {
        failedLogin(" \tMIXED.É@EXAMPLE.COM  ")
        failedLogin("mixed.é@example.com")
        val prefix = "p".repeat(128)
        val first = prefix + "a" + domain
        val second = prefix + "b" + domain
        assertEquals(first.take(128), second.take(128))
        failedLogin(first)
        failedLogin(second)

        val failures = failedEntries()
        assertEquals(failures[0].entityId, failures[1].entityId)
        assertNotEquals(failures[2].entityId, failures[3].entityId)
    }

    @Test
    fun `a padded supplementary 320 code point email registers stores and logs in without truncation`() {
        val normalized = "😀".repeat(308) + domain
        assertEquals(320, normalized.codePointCount(0, normalized.length))
        assertTrue(normalized.length > 320)
        assertTrue(normalized.toByteArray(Charsets.UTF_8).size > 320)
        register(" \t${normalized.uppercase()} \n")

        val stored = requireNotNull(users.findByEmail(normalized))
        assertEquals(normalized, stored.email)
        assertEquals(320, jdbcTemplate.queryForObject("SELECT char_length(email) FROM users WHERE id = ?", Int::class.java, stored.id))
        request("login", "\n${normalized.uppercase()}  ").andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { isNotEmpty() }
            jsonPath("$.role") { value("USER") }
        }
        assertTrue(failedEntries().isEmpty())
    }

    @Test
    fun `normalized 321 code point login and lowercase expanding registration reject without persistence`() {
        val expanding = "a".repeat(307) + "İ" + domain
        assertEquals(320, expanding.codePointCount(0, expanding.length))
        val usersBefore = userCount()
        val auditBefore = auditEntries()

        for ((action, email) in listOf("login" to emailWithLength(321, 'x'), "register" to expanding)) {
            val normalized = email.trim().lowercase()
            assertEquals(321, normalized.codePointCount(0, normalized.length))
            val response =
                request(action, email).andExpect {
                    status { isBadRequest() }
                    content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                    jsonPath("$.errors[0].code") { value("EMAIL_TOO_LONG") }
                    jsonPath("$.detail") { value("Email must be at most 320 Unicode code points after normalization.") }
                }.andReturn().response.contentAsString

            assertFalse(response.contains(email))
            assertFalse(response.contains(normalized))
            assertFalse(response.contains(password))
            assertFalse(response.contains("DATA_INTEGRITY_CONFLICT"))
            assertEquals(usersBefore, userCount())
            assertEquals(auditBefore, auditEntries())
        }
    }

    @Test
    fun `legacy raw identifiers remain unchanged even when their text matches a new fingerprint`() {
        val email = "history@example.com"
        // Legacy nonblank identifiers could themselves look like a fingerprint: type is part of identity.
        val prefixShapedRawIdentifier = "email-sha256-v1:${Sha256.hexUtf8(email)}"
        audit.record(
            NewAuditEntry(
                actorUserId = null,
                action = AuditAction.LOGIN_FAILED.wire,
                entityType = "user",
                entityId = prefixShapedRawIdentifier,
                detailJson = "{}",
                createdAt = Instant.EPOCH,
            ),
        )
        val legacy = auditEntries().single()

        failedLogin(email)

        val after = auditEntries()
        assertEquals(legacy, after.single { it.id == legacy.id })
        val current = after.single { it.id != legacy.id }
        assertEquals("user", legacy.entityType)
        assertEquals(prefixShapedRawIdentifier, legacy.entityId)
        assertEquals(legacy.entityId, current.entityId)
        assertNotEquals(legacy.entityType, current.entityType)
    }

    private fun failedLogin(email: String, submittedPassword: String = wrongPassword): JsonNode {
        val before = auditEntries()
        val response =
            request("login", email, submittedPassword).andExpect {
                status { isUnauthorized() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.detail") { value("Invalid email or password.") }
                jsonPath("$.errors[0].code") { value("INVALID_CREDENTIALS") }
            }.andReturn().response.contentAsString

        val after = auditEntries()
        assertEquals(before.size + 1, after.size)
        val failures = after.filter { it.action == AuditAction.LOGIN_FAILED.wire }
        assertEquals(before.count { it.action == AuditAction.LOGIN_FAILED.wire } + 1, failures.size)
        val entry = failures.single { candidate -> before.none { it.id == candidate.id } }
        assertNull(entry.actorUserId)
        assertEquals("login_identifier", entry.entityType)
        assertEquals("email-sha256-v1:${Sha256.hexUtf8(email.trim().lowercase())}", entry.entityId)
        assertEquals(80, entry.entityId.length)
        assertEquals("{}", entry.detailJson)
        assertFalse(response.contains(email))
        assertFalse(response.contains(submittedPassword))
        return objectMapper.readTree(response)
    }

    private fun register(email: String) {
        request("register", email).andExpect {
            status { isCreated() }
            jsonPath("$.email") { value(email.trim().lowercase()) }
        }
    }

    private fun request(action: String, email: String, submittedPassword: String = password): ResultActionsDsl = mockMvc.post("/api/v1/auth/$action") {
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to submittedPassword))
    }

    private fun emailWithLength(length: Int, character: Char): String = character.toString().repeat(length - domain.length) + domain

    private fun auditEntries(): List<AuditEntry> = audit.findPage(0, 100).items.sortedBy { it.id }

    private fun failedEntries(): List<AuditEntry> = auditEntries().filter { it.action == AuditAction.LOGIN_FAILED.wire }

    private fun userCount(): Long = requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Long::class.java))
}
