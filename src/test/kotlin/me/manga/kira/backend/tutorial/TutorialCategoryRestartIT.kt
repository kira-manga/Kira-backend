package me.manga.kira.backend.tutorial

import com.zaxxer.hikari.HikariDataSource
import me.manga.kira.backend.KiraBackendApplication
import me.manga.kira.backend.config.KiraAdminSeedProperties
import me.manga.kira.backend.config.KiraTutorialProperties
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.tutorial.application.PublicTutorialView
import me.manga.kira.backend.tutorial.application.TutorialMediaService
import me.manga.kira.backend.tutorial.application.TutorialService
import me.manga.kira.backend.tutorial.domain.CategoryContent
import me.manga.kira.backend.tutorial.domain.LocalizedText
import me.manga.kira.backend.tutorial.domain.MediaSlot
import me.manga.kira.backend.tutorial.domain.StoredCategory
import me.manga.kira.backend.tutorial.domain.StoredMedia
import me.manga.kira.backend.tutorial.domain.StoredRevision
import me.manga.kira.backend.tutorial.domain.StoredTutorial
import me.manga.kira.backend.tutorial.domain.TutorialContent
import me.manga.kira.backend.tutorial.domain.TutorialLifecycle
import me.manga.kira.backend.tutorial.domain.TutorialRepository
import me.manga.kira.backend.tutorial.domain.TutorialStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID
import javax.imageio.ImageIO

/** Owns both contexts, but not the shared container or any Spring TestContext-cached context. */
class TutorialCategoryRestartIT {
    // JUnit removes only this owned directory after both contexts and the database have been closed.
    @TempDir lateinit var mediaDirectory: Path

    @Test
    fun `archived category and retained published child survive a full application context restart`() {
        withOwnedDatabase { database, url ->
            val fixture = application(url).use { application ->
                assertOwnedStorage(application.context, database)
                archivedFixture(application.context)
            }

            // No reset, seed, restore or data rewrite between starts. The first pool is closed.
            // SpringApplication.run executes the real startup runners before returning this context.
            application(url).use { application ->
                val context = application.context
                assertOwnedStorage(context, database)
                val repository = context.getBean(TutorialRepository::class.java)
                val tutorials = context.getBean(TutorialService::class.java)
                assertEquals(fixture.category, repository.findCategory(fixture.category.id))
                assertEquals(fixture.tutorial, repository.findTutorial(fixture.tutorial.id))
                assertEquals(fixture.categoryRevisions, repository.listCategoryRevisions(fixture.category.id))
                assertEquals(fixture.tutorialRevisions, repository.listTutorialRevisions(fixture.tutorial.id))
                assertEquals(listOf(fixture.media), repository.listMedia())

                val servletContext = assertInstanceOf(ServletWebServerApplicationContext::class.java, context)
                val mockMvc = MockMvcBuilders.webAppContextSetup(servletContext)
                    .apply<DefaultMockMvcBuilder>(springSecurity()).build()
                listOf(
                    "/api/v1/tutorial-categories",
                    "/api/v1/tutorials",
                    "/api/v1/tutorials?category=${fixture.category.slug}",
                    "/api/v1/tutorials?featured=true",
                ).forEach { path ->
                    mockMvc.get(path).andExpect {
                        status { isOk() }
                        content { json("[]") }
                    }
                }
                mockMvc.get("/api/v1/tutorials/${fixture.tutorial.slug}").andExpect { status { isNotFound() } }
                mockMvc.get("/api/v1/tutorial-media/${fixture.media.id}").andExpect {
                    status { isOk() }
                    content { bytes(fixture.mediaBytes.toByteArray()) }
                }

                val restored = tutorials.restoreCategory(fixture.category.id).category
                assertEquals(fixture.category.copy(status = TutorialLifecycle.PUBLISHED, updatedAt = restored.updatedAt), restored)
                assertEquals(fixture.tutorial, repository.findTutorial(fixture.tutorial.id))
                assertEquals(fixture.tutorialRevisions, repository.listTutorialRevisions(fixture.tutorial.id))
                assertEquals(listOf(fixture.publicTutorial.category), tutorials.publicCategories())
                assertEquals(listOf(fixture.publicTutorial), tutorials.publicTutorials(fixture.category.slug, true))
                assertEquals(fixture.publicTutorial, tutorials.publicTutorial(fixture.tutorial.slug))
                mockMvc.get("/api/v1/tutorials/${fixture.tutorial.slug}").andExpect { status { isOk() } }
            }
        }
    }

    private fun archivedFixture(context: ConfigurableApplicationContext): RestartFixture {
        val tutorials = context.getBean(TutorialService::class.java)
        val media = context.getBean(TutorialMediaService::class.java)
        val repository = context.getBean(TutorialRepository::class.java)
        assertEquals(0, repository.categoryCount())
        assertEquals(0, repository.tutorialCount())
        assertTrue(repository.listMedia().isEmpty())
        val text = LocalizedText("Restart guide", "دليل إعادة التشغيل")
        val category = tutorials.createCategory("restart-category").category
        val categoryRevision = tutorials.createCategoryRevision(category.id, CategoryContent(text, "book"))
        val publishedCategory = tutorials.publishCategory(category.id, categoryRevision.revision.revisionNumber).category
        val asset = media.importSeedAsset(png(), published = false)
        val tutorial = tutorials.createTutorial("restart-guide", featuredPosition = 0).tutorial
        val revision = tutorials.createTutorialRevision(
            tutorial.id,
            category.id,
            TutorialContent(text, text, text, text, text, MediaSlot(asset.id, text), listOf(TutorialStep("first", text, text))),
        )
        val published = tutorials.publishTutorial(tutorial.id, revision.revision.revisionNumber).tutorial
        val fixture = RestartFixture(
            category = publishedCategory,
            tutorial = published,
            categoryRevisions = repository.listCategoryRevisions(category.id),
            tutorialRevisions = repository.listTutorialRevisions(tutorial.id),
            media = requireNotNull(repository.findMedia(asset.id)),
            mediaBytes = Files.readAllBytes(mediaDirectory.resolve(asset.storageFilename)).toList(),
            publicTutorial = tutorials.publicTutorial(tutorial.slug),
        )

        val archived = tutorials.archiveCategory(category.id).category
        assertEquals(publishedCategory.copy(status = TutorialLifecycle.ARCHIVED, updatedAt = archived.updatedAt), archived)
        assertEquals(TutorialLifecycle.PUBLISHED, published.status)
        assertEquals(published, repository.findTutorial(tutorial.id))
        assertTrue(tutorials.publicCategories().isEmpty())
        assertTrue(tutorials.publicTutorials(category.slug, true).isEmpty())
        return fixture.copy(category = archived)
    }

    private fun assertOwnedStorage(context: ConfigurableApplicationContext, database: String) {
        val jdbc = context.getBean(JdbcTemplate::class.java)
        assertEquals(database, jdbc.queryForObject("SELECT current_database()", String::class.java))
        assertEquals("public", jdbc.queryForObject("SELECT current_schema()", String::class.java))
        val properties = context.getBean(KiraTutorialProperties::class.java)
        assertEquals(mediaDirectory.toAbsolutePath().normalize(), properties.mediaDirectory.toAbsolutePath().normalize())
        assertFalse(properties.seedEnabled)
        assertFalse(context.getBean(KiraAdminSeedProperties::class.java).seedEnabled)
        assertEquals(setOf("test"), context.environment.activeProfiles.toSet())
        assertTrue(requireNotNull(assertInstanceOf(ServletWebServerApplicationContext::class.java, context).webServer).port > 0)
    }

    private fun application(url: String): OwnedApplication {
        val postgres = AbstractIntegrationTest.postgres
        return OwnedApplication(
            SpringApplicationBuilder(KiraBackendApplication::class.java)
                .web(WebApplicationType.SERVLET)
                .registerShutdownHook(false)
                .run(
                    // Command-line properties override application-test.yml, unlike builder defaults.
                    "--spring.profiles.active=test",
                    "--spring.datasource.url=$url",
                    "--spring.datasource.username=${postgres.username}",
                    "--spring.datasource.password=${postgres.password}",
                    "--spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
                    "--spring.datasource.hikari.maximum-pool-size=4",
                    "--spring.datasource.hikari.minimum-idle=1",
                    "--spring.datasource.hikari.connection-timeout=10000",
                    "--spring.datasource.hikari.validation-timeout=3000",
                    "--spring.flyway.enabled=true",
                    "--spring.flyway.url=$url",
                    "--spring.flyway.user=${postgres.username}",
                    "--spring.flyway.password=${postgres.password}",
                    "--spring.flyway.default-schema=public",
                    "--spring.flyway.schemas=public",
                    "--spring.jpa.hibernate.ddl-auto=validate",
                    "--spring.jpa.properties.hibernate.default_schema=public",
                    "--spring.lifecycle.timeout-per-shutdown-phase=10s",
                    "--server.address=127.0.0.1",
                    "--server.port=0",
                    "--kira.admin.seed-enabled=false",
                    "--kira.tutorial.seed-enabled=false",
                    "--kira.tutorial.media-directory=${mediaDirectory.toAbsolutePath().normalize()}",
                    "--kira.security.jwt-secret=${JwtTestSupport.TEST_JWT_SECRET_BASE64}",
                    "--kira.security.throttle.backend=memory",
                    "--kira.security.throttle.instance-count=1",
                    "--kira.signing.enabled=false",
                    "--kira.completion.enabled=true",
                    "--kira.completion.provider=echo",
                    "--kira.completion.coordination-backend=memory",
                    "--kira.completion.instance-count=1",
                ),
        )
    }

    private fun withOwnedDatabase(block: (String, String) -> Unit) {
        val postgres = AbstractIntegrationTest.postgres
        // A second schema cannot isolate the unchanged database-wide pg_sequences startup query.
        val database = "tutorial_restart_${UUID.randomUUID().toString().replace("-", "")}"
        require(database.matches(Regex("tutorial_restart_[0-9a-f]{32}")) && database.length <= 63)
        require(database !in setOf(postgres.databaseName, "postgres", "template0", "template1"))
        val maintenanceUrl = databaseUrl(postgres.jdbcUrl, postgres.databaseName)
        val credentials = Properties().apply {
            setProperty("user", postgres.username)
            setProperty("password", postgres.password)
        }
        var created = false
        // use preserves the original startup/assertion failure and suppresses any cleanup failure.
        AutoCloseable {
            if (created) databaseStatement(maintenanceUrl, credentials, "DROP DATABASE \"$database\"")
        }.use {
            databaseStatement(maintenanceUrl, credentials, "CREATE DATABASE \"$database\"") { created = true }
            block(database, databaseUrl(postgres.jdbcUrl, database))
        }
    }

    private fun databaseUrl(original: String, database: String): String {
        val controlled = mapOf("connectTimeout" to "10", "socketTimeout" to "30", "currentSchema" to "public")
        val preserved = original.substringAfter('?', "").split('&').filter { it.isNotEmpty() && it.substringBefore('=') !in controlled }
        val query = preserved + controlled.map { (name, value) -> "$name=$value" }
        return original.substringBefore('?').substringBeforeLast('/') + "/$database?" + query.joinToString("&")
    }

    private fun databaseStatement(url: String, credentials: Properties, sql: String, afterExecute: () -> Unit = {}) {
        DriverManager.getConnection(url, credentials).use { connection ->
            assertTrue(connection.autoCommit, "CREATE and DROP DATABASE require an independent autocommit connection")
            connection.createStatement().use { statement ->
                statement.queryTimeout = 15
                statement.execute(sql)
                afterExecute()
            }
        }
    }

    private class OwnedApplication(val context: ConfigurableApplicationContext) : AutoCloseable {
        override fun close() {
            val pool = context.use { it.getBean(HikariDataSource::class.java) }
            assertFalse(context.isActive)
            assertTrue(pool.isClosed, "the owned pool must be closed before restart or ordinary DROP DATABASE")
        }
    }

    private data class RestartFixture(
        val category: StoredCategory,
        val tutorial: StoredTutorial,
        val categoryRevisions: List<StoredRevision>,
        val tutorialRevisions: List<StoredRevision>,
        val media: StoredMedia,
        val mediaBytes: List<Byte>,
        val publicTutorial: PublicTutorialView,
    )

    private fun png(): ByteArray = ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", output)
        output.toByteArray()
    }
}
