package me.manga.kira.backend.observability

import me.manga.kira.backend.support.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@TestPropertySource(
    properties =
    [
        "management.endpoints.web.exposure.include=health,prometheus",
        // Spring Boot's test bootstrap disables metric exporters unless a test opts in.
        "management.prometheus.metrics.export.enabled=true",
    ],
)
class MetricsEndpointIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `internal Prometheus endpoint exposes HTTP JVM database and bounded completion metrics`() {
        val body = mockMvc.get("/actuator/prometheus").andExpect { status { isOk() } }.andReturn().response.contentAsString

        assertTrue(body.contains("jvm_memory_used_bytes"))
        assertTrue(body.contains("hikaricp_connections"))
        assertTrue(body.contains("kira_completion_executor_active"))
        assertTrue(body.contains("kira_completion_queue_remaining"))
    }
}
