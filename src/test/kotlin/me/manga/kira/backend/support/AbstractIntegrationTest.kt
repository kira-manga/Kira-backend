package me.manga.kira.backend.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Base class for Spring integration tests (PLAN §11).
 *
 * A single shared PostgreSQL container (Testcontainers "singleton" pattern) is started once for
 * the whole JVM and reused by every test that extends this class — this keeps the suite fast.
 * `@ServiceConnection` wires the running container into Spring Boot automatically (it supplies
 * `spring.datasource.*`, so Flyway + JPA point at the container). The container is never
 * explicitly stopped; the Testcontainers reaper (Ryuk) removes it when the JVM exits.
 *
 * The image tag is pinned to `postgres:17.6-alpine` (same major.minor as docker-compose and the
 * production target — PLAN §3 / Open Q10) so tests exercise real PostgreSQL behavior (jsonb,
 * partial unique indexes, identity columns, timestamptz) rather than an H2 approximation.
 */
@SpringBootTest
abstract class AbstractIntegrationTest {
    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:17.6-alpine"))
                .also { it.start() }
    }
}
