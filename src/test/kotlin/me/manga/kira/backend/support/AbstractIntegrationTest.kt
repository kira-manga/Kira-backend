package me.manga.kira.backend.support

import me.manga.kira.backend.security.AuthThrottleService
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Base class for Spring integration tests (PLAN §11).
 *
 * A single shared PostgreSQL container (Testcontainers "singleton" pattern) is started once for the
 * whole JVM and reused by every test that extends this class — this keeps the suite fast.
 * `@ServiceConnection` wires the running container into Spring Boot automatically. The image tag is
 * pinned to `postgres:17.6-alpine` (same major.minor as docker-compose and the production target —
 * PLAN §3 / Open Q10) so tests exercise real PostgreSQL behavior (jsonb, partial unique indexes,
 * identity columns, timestamptz) rather than an H2 approximation.
 *
 * The `test` profile (`application-test.yml`) supplies a throwaway JWT secret and disables admin
 * seeding by default (PLAN §6). `@AutoConfigureMockMvc` gives HTTP-level ITs a MockMvc with the real
 * security filter chain applied.
 *
 * Because the container is shared across test classes, [resetState] clears the only mutable Phase-3
 * state between test methods: the `users` table and the in-memory auth-throttle store. `security_state`
 * is a seeded singleton and is left untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    protected lateinit var authThrottleService: AuthThrottleService

    @BeforeEach
    fun resetState() {
        jdbcTemplate.update("DELETE FROM users")
        authThrottleService.clearAll()
    }

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:17.6-alpine"))
                .also { it.start() }
    }
}
