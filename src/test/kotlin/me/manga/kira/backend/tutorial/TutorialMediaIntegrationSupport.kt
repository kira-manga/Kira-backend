package me.manga.kira.backend.tutorial

import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManagerFactory
import me.manga.kira.backend.KiraBackendApplication
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.config.KiraTutorialProperties
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.tutorial.application.TutorialMediaService
import me.manga.kira.backend.tutorial.domain.StoredMedia
import me.manga.kira.backend.tutorial.domain.TutorialMediaStorage
import me.manga.kira.backend.tutorial.domain.TutorialRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.springframework.aop.framework.ProxyFactory
import org.springframework.aop.support.AopUtils
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.sql.DriverManager
import java.time.Clock
import java.util.Base64
import java.util.Properties
import java.util.UUID
import javax.imageio.ImageIO

/** Reuses only the container, never AbstractIntegrationTest's database or historical media folder. */
abstract class TutorialMediaIntegrationSupport {
    @TempDir
    lateinit var mediaDirectory: Path

    private val signingKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    protected fun withMediaApplication(block: (MediaTestApplication) -> Unit) {
        val postgres = AbstractIntegrationTest.postgres
        val database = "tutorial_media_${UUID.randomUUID().toString().replace("-", "")}"
        require(database.matches(Regex("tutorial_media_[0-9a-f]{32}")))
        require(database !in setOf(postgres.databaseName, "postgres", "template0", "template1"))
        val credentials = Properties().apply {
            setProperty("user", postgres.username)
            setProperty("password", postgres.password)
        }
        val maintenanceUrl = databaseUrl(postgres.jdbcUrl, postgres.databaseName)
        var created = false
        // Ordinary DROP only, after our context/pool closes; cleanup cannot replace the original failure.
        AutoCloseable {
            if (created) databaseStatement(maintenanceUrl, credentials, "DROP DATABASE \"$database\"")
        }.use {
            databaseStatement(maintenanceUrl, credentials, "CREATE DATABASE \"$database\"") { created = true }
            val url = databaseUrl(postgres.jdbcUrl, database)
            application(url).use { application ->
                assertEquals(database, application.jdbc.queryForObject("SELECT current_database()", String::class.java))
                assertEquals(url, application.context.getBean(HikariDataSource::class.java).jdbcUrl)
                assertEquals(mediaDirectory.toAbsolutePath().normalize(), application.properties.mediaDirectory)
                assertFalse(application.properties.seedEnabled)
                assertFalse(application.properties.mediaReconciliationEnabled)
                assertTrue(AopUtils.isAopProxy(application.media), "exercise the production transactional proxy")
                block(application)
            }
        }
    }

    private fun application(url: String): MediaTestApplication {
        val postgres = AbstractIntegrationTest.postgres
        return MediaTestApplication(
            SpringApplicationBuilder(KiraBackendApplication::class.java, MediaCommitTestConfiguration::class.java)
                .web(WebApplicationType.SERVLET)
                .registerShutdownHook(false)
                .run(
                    "--spring.profiles.active=test,tutorial-media-boundary-test",
                    "--spring.datasource.url=$url",
                    "--spring.datasource.username=${postgres.username}",
                    "--spring.datasource.password=${postgres.password}",
                    "--spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
                    "--spring.datasource.hikari.maximum-pool-size=5",
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
                    "--kira.tutorial.media-reconciliation-enabled=false",
                    "--kira.tutorial.media-lock-timeout-millis=30000",
                    "--kira.security.jwt-secret=${JwtTestSupport.TEST_JWT_SECRET_BASE64}",
                    "--kira.security.throttle.backend=memory",
                    "--kira.security.throttle.instance-count=1",
                    "--kira.signing.enabled=true",
                    "--kira.signing.active-key-id=media-test-ephemeral",
                    "--kira.signing.private-key=${Base64.getEncoder().encodeToString(signingKeyPair.private.encoded)}",
                    "--kira.signing.verification-keys[0].key-id=media-test-ephemeral",
                    "--kira.signing.verification-keys[0].public-key=${Base64.getEncoder().encodeToString(signingKeyPair.public.encoded)}",
                    "--kira.completion.enabled=true",
                    "--kira.completion.provider=echo",
                    "--kira.completion.coordination-backend=memory",
                    "--kira.completion.instance-count=1",
                ),
        )
    }

    private fun databaseUrl(original: String, database: String): String {
        val controlled = mapOf("connectTimeout" to "10", "socketTimeout" to "30", "currentSchema" to "public")
        val preserved = original.substringAfter('?', "").split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') !in controlled }
        val query = preserved + controlled.map { (name, value) -> "$name=$value" }
        return original.substringBefore('?').substringBeforeLast('/') + "/$database?" + query.joinToString("&")
    }

    private fun databaseStatement(url: String, credentials: Properties, sql: String, afterExecute: () -> Unit = {}) {
        DriverManager.getConnection(url, credentials).use { connection ->
            assertTrue(connection.autoCommit)
            connection.createStatement().use { statement ->
                statement.queryTimeout = 15
                statement.execute(sql)
                afterExecute()
            }
        }
    }
}

class MediaTestApplication(val context: ConfigurableApplicationContext) : AutoCloseable {
    val jdbc: JdbcTemplate = context.getBean(JdbcTemplate::class.java)
    val repository: TutorialRepository = context.getBean(TutorialRepository::class.java)
    val storage: TutorialMediaStorage = context.getBean(TutorialMediaStorage::class.java)
    val media: TutorialMediaService = context.getBean(TutorialMediaService::class.java)
    val properties: KiraTutorialProperties = context.getBean(KiraTutorialProperties::class.java)
    val transactions: MediaBoundaryTransactionManager = context.getBean(MediaBoundaryTransactionManager::class.java)

    fun transaction(): TransactionTemplate = TransactionTemplate(transactions)

    /** Keep the real annotation rollback rules when injecting one narrowly faulting domain port. */
    fun mediaUsing(storage: TutorialMediaStorage = this.storage, repository: TutorialRepository = this.repository): TutorialMediaService = proxy(
        TutorialMediaService(
            repository,
            storage,
            context.getBean(CurrentUser::class.java),
            context.getBean(AuditService::class.java),
            context.getBean(Clock::class.java),
        ),
        TutorialMediaService::class.java,
    )

    fun <T : Any> proxy(target: T, type: Class<T>): T {
        val interceptor = TransactionInterceptor().apply {
            setTransactionManager(transactions)
            setTransactionAttributeSource(AnnotationTransactionAttributeSource())
            afterPropertiesSet()
        }
        val factory = ProxyFactory(target).apply {
            isProxyTargetClass = true
            addAdvice(interceptor)
        }
        return type.cast(factory.getProxy(type.classLoader)).also { assertTrue(AopUtils.isAopProxy(it)) }
    }

    fun auditCount(action: String): Int = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Int::class.java, action) ?: 0

    fun persistedMedia(returned: StoredMedia): StoredMedia = requireNotNull(repository.findMedia(returned.id)).also { stored ->
        // PostgreSQL timestamps round to microseconds; content/identity metadata must still agree exactly.
        assertEquals(returned.copy(createdAt = stored.createdAt), stored)
    }

    override fun close() {
        val pool = context.use { it.getBean(HikariDataSource::class.java) }
        assertFalse(context.isActive)
        assertTrue(pool.isClosed, "close our pool before dropping only our generated database")
    }
}

@TestConfiguration(proxyBeanMethods = false)
@Profile("tutorial-media-boundary-test")
class MediaCommitTestConfiguration {
    @Bean
    @Primary
    fun transactionManager(entityManagerFactory: EntityManagerFactory): MediaBoundaryTransactionManager = MediaBoundaryTransactionManager(entityManagerFactory)
}

/** Simulates only the lost-ack boundary, not a network partition, server crash or durable power loss. */
class MediaBoundaryTransactionManager(factory: EntityManagerFactory) : JpaTransactionManager(factory) {
    private val nextCommit = ThreadLocal<MediaCommitObservation?>()

    init {
        setRollbackOnCommitFailure(false)
    }

    fun observeNextCommit(loseAcknowledgement: Boolean = false, beforeCommit: () -> Unit = {}): MediaCommitObservation {
        check(nextCommit.get() == null) { "unconsumed commit observation" }
        return MediaCommitObservation(loseAcknowledgement, beforeCommit).also(nextCommit::set)
    }

    override fun doCommit(status: DefaultTransactionStatus) {
        val observation = nextCommit.get()
        nextCommit.remove()
        if (observation != null) {
            observation.commitEntered = true
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    observation.completions.add(status)
                }
            })
            observation.beforeCommit()
        }
        super.doCommit(status)
        observation?.databaseCommitReturned = true
        if (observation?.loseAcknowledgement == true) {
            // Still inside doCommit: AbstractPlatformTransactionManager has NOT dispatched the outcome.
            throw TransactionSystemException("simulated acknowledgement loss after actual database commit")
        }
    }
}

class MediaCommitObservation(val loseAcknowledgement: Boolean, val beforeCommit: () -> Unit) {
    var commitEntered = false
    var databaseCommitReturned = false
    val completions = mutableListOf<Int>()
}

internal fun mediaPng(color: Int = 0): ByteArray = ByteArrayOutputStream().use { output ->
    val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, color)
    check(ImageIO.write(image, "png", output))
    output.toByteArray()
}
