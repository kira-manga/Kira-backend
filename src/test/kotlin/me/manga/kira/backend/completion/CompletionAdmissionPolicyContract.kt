package me.manga.kira.backend.completion

import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.completion.application.CompletionAdmission
import me.manga.kira.backend.config.KiraCompletionProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import java.util.UUID

/** The identical admission decisions run against memory and the real Redis adapter. */
@Timeout(20)
abstract class CompletionAdmissionPolicyContract {
    protected abstract fun fixture(properties: KiraCompletionProperties): CompletionAdmissionPolicyFixture

    @ParameterizedTest(name = "rolling boundary: {0}")
    @EnumSource(CompletionRateDimension::class)
    fun `later events survive the first event window boundary`(dimension: CompletionRateDimension) {
        fixture(dimension.properties(10)).use { test ->
            val user = UUID.randomUUID()
            test.first.acquire(user).close()
            test.advance(dimension.window.minusSeconds(30))
            repeat(9) { test.second.acquire(user).close() }
            test.advance(Duration.ofSeconds(31))

            // The first event is old, but the nine later events are still inside the rolling window.
            test.first.acquire(user).close()
            assertRateDenied(test.second, user, dimension)
        }
    }

    @ParameterizedTest(name = "retry guidance: {0}")
    @EnumSource(CompletionRateDimension::class)
    fun `retry guidance stays a full window and a rejection does not restart that window`(dimension: CompletionRateDimension) {
        fixture(dimension.properties(1)).use { test ->
            val user = UUID.randomUUID()
            test.first.acquire(user).close()
            test.advance(dimension.window.minusSeconds(30))
            assertRateDenied(test.second, user, dimension) // Not a 30-second remaining-time countdown.
            test.advance(Duration.ofSeconds(31))
            test.second.acquire(user).close()
        }
    }

    @Test
    fun `global rejection cannot leave a newer user charge after the global events expire`() {
        fixture(properties(user = 2, global = 3, daily = 4)).use { test ->
            val original = UUID.randomUUID()
            val rejected = UUID.randomUUID()
            repeat(2) { test.first.acquire(original).close() }
            test.second.acquire(UUID.randomUUID()).close()
            test.advance(Duration.ofSeconds(30))
            repeat(2) { assertRateDenied(test.second, rejected, CompletionRateDimension.GLOBAL_MINUTE) }
            test.advance(Duration.ofSeconds(31))

            repeat(2) { test.second.acquire(rejected).close() }
            test.first.acquire(UUID.randomUUID()).close()
        }
    }

    @Test
    fun `daily rejection cannot consume user or global minute allowances`() {
        fixture(properties(user = 2, global = 3, daily = 1)).use { test ->
            val user = UUID.randomUUID()
            test.first.acquire(user).close()
            test.advance(Duration.ofDays(1).minusSeconds(30))
            repeat(2) { assertRateDenied(test.second, user, CompletionRateDimension.USER_DAY) }
            test.advance(Duration.ofSeconds(31))

            test.second.acquire(user).close()
            repeat(2) { test.first.acquire(UUID.randomUUID()).close() }
        }
    }

    @Test
    fun `concurrency rejections cannot exhaust minute or daily budgets after the owner releases`() {
        fixture(properties(user = 2, global = 3, daily = 3, capacity = 1)).use { test ->
            val rejected = UUID.randomUUID()
            test.first.acquire(UUID.randomUUID()).use {
                repeat(2) { assertCapacityDenied(test.second, rejected) }
            }
            repeat(2) { test.second.acquire(rejected).close() }
        }
    }

    @Test
    fun `disabled rate dimensions still enforce capacity but never impose a request budget`() {
        fixture(properties(capacity = 2)).use { test ->
            val user = UUID.randomUUID()
            test.first.acquire(user).use {
                test.second.acquire(user).use {
                    assertCapacityDenied(test.first, user)
                }
                repeat(12) { test.second.acquire(user).close() }
            }
        }
    }

    private fun assertRateDenied(admission: CompletionAdmission, user: UUID, dimension: CompletionRateDimension) {
        val failure = assertThrows<TooManyRequestsException> { admission.acquire(user) }
        assertEquals(dimension.code, failure.code)
        assertEquals(dimension.window.seconds, failure.retryAfterSeconds)
    }

    private fun assertCapacityDenied(admission: CompletionAdmission, user: UUID) {
        val failure = assertThrows<ServiceUnavailableException> { admission.acquire(user) }
        assertEquals("COMPLETION_CONCURRENCY_LIMIT", failure.code)
        assertEquals(1L, failure.retryAfterSeconds)
    }

    protected fun properties(user: Int = 0, global: Int = 0, daily: Int = 0, capacity: Int = 8) = KiraCompletionProperties(
        perUserPerMinute = user,
        globalPerMinute = global,
        perUserDailyQuota = daily,
        globalConcurrency = capacity,
    )
}

interface CompletionAdmissionPolicyFixture : AutoCloseable {
    val first: CompletionAdmission
    val second: CompletionAdmission

    /** Memory advances its Clock; Redis ages only this isolated fixture's stored rate events/expiry. */
    fun advance(duration: Duration)
}

enum class CompletionRateDimension(val window: Duration, val code: String) {
    USER_MINUTE(Duration.ofMinutes(1), "COMPLETION_USER_RATE_LIMIT"),
    GLOBAL_MINUTE(Duration.ofMinutes(1), "COMPLETION_GLOBAL_RATE_LIMIT"),
    USER_DAY(Duration.ofDays(1), "COMPLETION_DAILY_QUOTA"),
    ;

    fun properties(limit: Int) = KiraCompletionProperties(
        perUserPerMinute = if (this == USER_MINUTE) limit else 0,
        globalPerMinute = if (this == GLOBAL_MINUTE) limit else 0,
        perUserDailyQuota = if (this == USER_DAY) limit else 0,
        globalConcurrency = 8,
    )
}
