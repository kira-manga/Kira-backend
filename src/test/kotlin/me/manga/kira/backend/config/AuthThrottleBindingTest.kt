package me.manga.kira.backend.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.time.Duration

class AuthThrottleBindingTest {
    @Test
    fun `documented lease environment override binds and enforces its bound`() {
        fun bind(value: String): KiraSecurityProperties {
            val source = SystemEnvironmentPropertySource(
                "fixture-systemEnvironment",
                mapOf<String, Any>("KIRA_SECURITY_THROTTLE_LOGINATTEMPTTTL" to value),
            )
            val environment = StandardEnvironment().apply {
                propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
                propertySources.addFirst(source)
            }
            return Binder.get(environment)
                .bind("kira.security", Bindable.of(KiraSecurityProperties::class.java))
                .get()
        }

        assertEquals(Duration.ofSeconds(45), bind("45s").throttle.loginAttemptTtl)
        assertThrows<BindException> { bind("6m") }
    }
}
