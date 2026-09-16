package me.manga.kira.backend.completion

import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.completion.application.CompletionService
import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.CompletionProvider
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(OverloadProviderConfig::class)
@TestPropertySource(
    properties = [
        "kira.completion.provider=overload-test",
        "kira.completion.executor-threads=1",
        "kira.completion.queue-capacity=1",
        "kira.completion.queue-timeout=PT0.1S",
        "kira.completion.timeout=PT5S",
    ],
)
class CompletionOverloadIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var service: CompletionService

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var provider: OverloadTestProvider

    @Test
    fun `queue timeout persists a failure returns 503 and cancels queued provider work`() {
        val user = users.create("overload@example.com", "{noop}unused", Role.USER)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val first = caller.submit { service.create(user.id, "hold", null) }
            provider.started.await(2, TimeUnit.SECONDS)

            val error = assertThrows<ServiceUnavailableException> { service.create(user.id, "must-not-run", null) }
            assertEquals("COMPLETION_OVERLOADED", error.code)
            assertEquals(
                1,
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM completion_results WHERE error_code = 'PROVIDER_UNAVAILABLE'",
                    Int::class.java,
                ),
            )

            provider.release.countDown()
            first.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("hold"), provider.prompts)
        } finally {
            provider.release.countDown()
            caller.shutdownNow()
        }
    }
}

@TestConfiguration
class OverloadProviderConfig {
    @Bean
    fun overloadTestProvider() = OverloadTestProvider()
}

class OverloadTestProvider : CompletionProvider {
    override val name = "overload-test"
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val prompts = mutableListOf<String>()

    override fun complete(prompt: String, model: String): CompletionOutcome {
        synchronized(prompts) { prompts += prompt }
        if (prompt == "hold") {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        return CompletionOutcome.Success("ok", 1)
    }
}
