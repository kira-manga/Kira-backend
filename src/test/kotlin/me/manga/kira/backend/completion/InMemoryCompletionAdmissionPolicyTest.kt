package me.manga.kira.backend.completion

import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.completion.application.InMemoryCompletionAdmission
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.support.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import java.util.UUID

class InMemoryCompletionAdmissionPolicyTest : CompletionAdmissionPolicyContract() {
    override fun fixture(properties: KiraCompletionProperties): CompletionAdmissionPolicyFixture = MemoryFixture(properties)

    @ParameterizedTest(name = "inclusive cutoff: {0}")
    @EnumSource(CompletionRateDimension::class)
    fun `an event exactly one window old still counts and the next millisecond frees it`(dimension: CompletionRateDimension) {
        fixture(dimension.properties(1)).use { test ->
            val user = UUID.randomUUID()
            test.first.acquire(user).close()
            test.advance(dimension.window)
            val failure = assertThrows<TooManyRequestsException> { test.second.acquire(user) }
            assertEquals(dimension.code, failure.code)
            assertEquals(dimension.window.seconds, failure.retryAfterSeconds)
            test.advance(Duration.ofMillis(1))
            test.second.acquire(user).close()
        }
    }

    private class MemoryFixture(properties: KiraCompletionProperties) : CompletionAdmissionPolicyFixture {
        private val clock = MutableClock()
        override val first = InMemoryCompletionAdmission(properties, clock)
        override val second = first // Two callers share the one declared memory authority, not two independent processes.

        override fun advance(duration: Duration) = clock.advance(duration)

        override fun close() = Unit
    }
}
