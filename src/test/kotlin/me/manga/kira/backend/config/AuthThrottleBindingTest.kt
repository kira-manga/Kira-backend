package me.manga.kira.backend.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.time.Duration

class AuthThrottleBindingTest {
    @Test
    fun `documented lease environment override binds and enforces its bound`() {
        fun bind(value: String): KiraSecurityProperties {
            val environment = SystemEnvironmentPropertySource(
                "fixture-systemEnvironment",
                mapOf<String, Any>("KIRA_SECURITY_THROTTLE_LOGINATTEMPTTTL" to value),
            )
            return Binder(ConfigurationPropertySources.from(environment))
                .bind("kira.security", Bindable.of(KiraSecurityProperties::class.java))
                .get()
        }

        assertEquals(Duration.ofSeconds(45), bind("45s").throttle.loginAttemptTtl)
        assertThrows<BindException> { bind("6m") }
    }
}
