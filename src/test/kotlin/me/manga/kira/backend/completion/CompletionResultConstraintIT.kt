package me.manga.kira.backend.completion

import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class CompletionResultConstraintIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var users: UserRepository

    @Test
    fun `terminal result must contain exactly one of result or error`() {
        val user = users.create("xor-${UUID.randomUUID()}@example.test", "{noop}hash", Role.USER)
        val requestId = UUID.randomUUID()
        val at = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC)
        jdbcTemplate.update(
            "INSERT INTO completion_requests(id, user_id, provider, model, prompt, status, created_at, updated_at) " +
                "VALUES (?, ?, 'test', 'model', 'prompt', 'FAILED', ?, ?)",
            requestId,
            user.id,
            at,
            at,
        )

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                "INSERT INTO completion_results(request_id, result, error, error_code, created_at) VALUES (?, NULL, NULL, NULL, ?)",
                requestId,
                at,
            )
        }
    }
}
