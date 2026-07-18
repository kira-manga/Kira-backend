package me.manga.kira.backend.completion

import me.manga.kira.backend.completion.application.CompletionRetentionService
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class CompletionRetentionIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var retention: CompletionRetentionService

    @Autowired
    private lateinit var users: UserRepository

    @Test
    fun `cleanup removes expired terminal and crash-left running prompt data`() {
        val user = users.create("retention@example.com", "{noop}unused", Role.USER)
        val old = Instant.now().minusSeconds(8 * 86_400L)
        val succeeded = insertRequest(user.id, "SUCCEEDED", old, "expired success prompt")
        jdbcTemplate.update(
            "INSERT INTO completion_results(request_id, result, error, error_code, created_at) VALUES (?, ?, NULL, NULL, ?)",
            succeeded,
            "expired result",
            Timestamp.from(old),
        )
        insertRequest(user.id, "RUNNING", old, "crash-left prompt")

        assertEquals(2, retention.cleanExpired())
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM completion_requests", Int::class.java))
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM completion_results", Int::class.java))
    }

    private fun insertRequest(userId: UUID, status: String, createdAt: Instant, prompt: String): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO completion_requests(id, user_id, provider, model, prompt, status, created_at, updated_at)
            VALUES (?, ?, 'test', 'test-model', ?, ?, ?, ?)
            """.trimIndent(),
            id,
            userId,
            prompt,
            status,
            Timestamp.from(createdAt),
            Timestamp.from(createdAt),
        )
        return id
    }
}
