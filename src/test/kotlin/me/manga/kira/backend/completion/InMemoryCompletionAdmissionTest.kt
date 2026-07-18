package me.manga.kira.backend.completion

import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.completion.application.InMemoryCompletionAdmission
import me.manga.kira.backend.config.KiraCompletionProperties
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class InMemoryCompletionAdmissionTest {
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `per-user rate and daily quotas reject with stable codes`() {
        val userRate = InMemoryCompletionAdmission(properties(perUserPerMinute = 1), clock)
        val user = UUID.randomUUID()
        userRate.acquire(user).close()
        assertEquals("COMPLETION_USER_RATE_LIMIT", assertThrows<TooManyRequestsException> { userRate.acquire(user) }.code)

        val daily = InMemoryCompletionAdmission(properties(perUserPerMinute = 0, perUserDailyQuota = 1), clock)
        daily.acquire(user).close()
        assertEquals("COMPLETION_DAILY_QUOTA", assertThrows<TooManyRequestsException> { daily.acquire(user) }.code)
    }

    @Test
    fun `global concurrency is released exactly once`() {
        val admission = InMemoryCompletionAdmission(properties(globalConcurrency = 1), clock)
        val permit = admission.acquire(UUID.randomUUID())
        assertEquals(
            "COMPLETION_CONCURRENCY_LIMIT",
            assertThrows<TooManyRequestsException> { admission.acquire(UUID.randomUUID()) }.code,
        )
        permit.close()
        permit.close()
        assertDoesNotThrow { admission.acquire(UUID.randomUUID()).close() }
    }

    private fun properties(perUserPerMinute: Int = 0, perUserDailyQuota: Int = 0, globalConcurrency: Int = 8) = KiraCompletionProperties(
        perUserPerMinute = perUserPerMinute,
        globalPerMinute = 0,
        perUserDailyQuota = perUserDailyQuota,
        globalConcurrency = globalConcurrency,
    )
}
