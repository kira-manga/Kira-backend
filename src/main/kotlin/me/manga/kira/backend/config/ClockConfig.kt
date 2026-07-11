package me.manga.kira.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * A single injected [Clock] (UTC). Time-dependent components (the auth-throttle store now; the
 * publication-snapshot `generatedAt`/`created_at` instant in later phases — PLAN §5/§9) take the
 * clock by injection so tests can substitute a controllable one instead of reading wall-clock time.
 */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
