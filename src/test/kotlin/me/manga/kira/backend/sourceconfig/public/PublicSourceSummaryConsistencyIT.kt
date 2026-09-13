package me.manga.kira.backend.sourceconfig.public

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.EntityManager
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.admin.AbstractAdminSourceIT
import me.manga.kira.backend.sourceconfig.application.PublishOutcome
import me.manga.kira.backend.sourceconfig.application.SourceQueryService
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.model.IconSpec
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.support.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real HTTP readers retain their PostgreSQL snapshot while an independent publication commits. */
@Import(SummaryConsistencyTestConfig::class)
@Timeout(30)
class PublicSourceSummaryConsistencyIT : AbstractAdminSourceIT() {
    override val bootstrapCatalogBeforeEach: Boolean = true

    @Autowired
    private lateinit var query: SourceQueryService

    @Autowired
    private lateinit var documents: SummaryReadDocuments

    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `removal after document selection preserves the complete old summary generation`() {
        val initialSummaries = readSummaries()
        val target = originalTarget()
        val targetPublishedAt = publishNew(target)
        clock.advance(Duration.ofSeconds(1))
        val survivor = SourceConfigFixtures.validGenericSource("Alpha")
        val survivorPublishedAt = publishNew(survivor)
        clock.advance(Duration.ofSeconds(1))
        sourceAdminService.disable(target.api, admin.id)
        sourceAdminService.retire(target.api, admin.id)
        clock.advance(Duration.ofSeconds(1))

        val (old, fresh) = readAcrossCommit(targetPublishedAt) {
            sourceAdminService.remove(target.api, target.api, admin.id)
        }
        val survivorSummary = expectedSummary(survivor, survivorPublishedAt)
        assertEquals(initialSummaries + listOf(expectedSummary(target, targetPublishedAt, lifecycle = "removed"), survivorSummary), old)
        assertEquals(initialSummaries + listOf(survivorSummary), fresh)
    }

    @Test
    fun `new revision after document selection cannot relabel old content with new metadata`() {
        val initialSummaries = readSummaries()
        val original = originalTarget()
        val originalPublishedAt = publishNew(original)
        clock.advance(Duration.ofSeconds(1))
        val survivor = SourceConfigFixtures.validGenericSource("Alpha")
        val survivorPublishedAt = publishNew(survivor)
        val updated = original.copy(
            displayName = "Updated Zeta",
            language = "en",
            siteState = "WORKING",
            baseUrl = "https://new.example.com",
            icon = IconSpec(remoteUrl = "https://cdn.example.com/new.png"),
        )
        assertEquals(2, sourceAdminService.createRevision(original.api, toJson(updated), admin.id).revisionNumber)
        clock.advance(Duration.ofSeconds(1))
        val updatedPublishedAt = clock.instant()

        val (old, fresh) = readAcrossCommit(originalPublishedAt) {
            sourceAdminService.publish(original.api, 2, admin.id)
        }
        val survivorSummary = expectedSummary(survivor, survivorPublishedAt)
        assertEquals(initialSummaries + listOf(expectedSummary(original, originalPublishedAt), survivorSummary), old)
        assertEquals(initialSummaries + listOf(expectedSummary(updated, updatedPublishedAt, revision = 2), survivorSummary), fresh)
    }

    private fun originalTarget(): SourceConfig = SourceConfigFixtures.validGenericSource("Zeta").copy(
        displayName = "Original Zeta",
        language = "ar",
        siteState = "ADULT_18_PLUS",
        baseUrl = "https://old.example.com",
        icon = IconSpec(remoteUrl = "https://cdn.example.com/old.png"),
    )

    private fun publishNew(source: SourceConfig): Instant {
        // Real service proxies avoid coupling publication Clock control to HTTP bearer validation.
        sourceAdminService.createSource(toJson(source), admin.id)
        val publishedAt = clock.instant()
        assertFalse(sourceAdminService.publish(source.api, 1, admin.id).noOp)
        return publishedAt
    }

    private fun readAcrossCommit(sourcePublishedAt: Instant, mutate: () -> PublishOutcome): Pair<List<JsonNode>, List<JsonNode>> {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        assertTrue(AopUtils.isAopProxy(query))
        assertTrue(AopUtils.isAopProxy(sourceAdminService))
        val oldRevision = checkNotNull(latestPointer())
        val selected = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        documents.afterRead.set { document ->
            selected.countDown()
            val snapshot = checkNotNull(document)
            assertEquals(oldRevision, snapshot.documentRevision)
            assertNotEquals(sourcePublishedAt, snapshot.createdAt, "source publication time is not document creation time")
            assertReaderTransaction()
            assertTrue(release.await(5, TimeUnit.SECONDS), "reader was not released")
        }
        try {
            val reader = workers.submit(Callable { readSummaries() })
            assertTrue(selected.await(5, TimeUnit.SECONDS), "reader did not select its document")
            val writer = workers.submit(
                Callable {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                    mutate().also { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()) }
                },
            )
            val published = writer.get(5, TimeUnit.SECONDS)
            assertFalse(published.noOp)
            assertTrue(published.documentRevision > oldRevision)
            // An independent read observes the pointer only after the writer's real proxy has committed.
            assertEquals(published.documentRevision, latestPointer())
            assertFalse(reader.isDone, "reader must still be held after the writer commits")
            release.countDown()
            return reader.get(5, TimeUnit.SECONDS) to readSummaries()
        } finally {
            documents.afterRead.set(null)
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "summary race workers did not exit")
        }
    }

    private fun assertReaderTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
        val jpaPid = (entityManager.createNativeQuery("SELECT pg_backend_pid()").singleResult as Number).toInt()
        val jdbcState = jdbcTemplate.queryForMap(
            "SELECT pg_backend_pid() AS backend_pid, current_setting('transaction_isolation') AS isolation_level, " +
                "current_setting('transaction_read_only') AS read_only",
        )
        assertEquals(jpaPid, (jdbcState.getValue("backend_pid") as Number).toInt(), "JPA and JDBC must share the reader connection")
        assertEquals("repeatable read", jdbcState["isolation_level"])
        assertEquals("on", jdbcState["read_only"])
    }

    private fun readSummaries(): List<JsonNode> {
        val response = getPublicSources().andExpect { status { isOk() } }.andReturn().response
        val body = objectMapper.readTree(response.contentAsByteArray)
        assertTrue(body.isArray)
        return body.toList()
    }

    private fun expectedSummary(source: SourceConfig, publishedAt: Instant, revision: Int = 1, lifecycle: String = "active"): JsonNode {
        val fields = linkedMapOf<String, Any>(
            "api" to source.api,
            "displayName" to source.displayName,
            "language" to source.language,
            "engine" to source.engine,
            "lifecycle" to lifecycle,
            "siteState" to source.siteState,
            "adult" to (source.siteState == "ADULT_18_PLUS"),
            "baseUrl" to source.baseUrl,
            "revisionNumber" to revision,
            "publishedAt" to publishedAt.toString(),
        )
        source.icon?.remoteUrl?.let { fields["iconRemoteUrl"] = it }
        return objectMapper.valueToTree(fields)
    }
}

@TestConfiguration
class SummaryConsistencyTestConfig {
    @Bean
    @Primary
    fun summaryDocuments(@Qualifier("jpaPublishedDocumentRepositoryAdapter") delegate: PublishedDocumentRepository) = SummaryReadDocuments(delegate)

    @Bean
    @Primary
    fun summaryClock(): MutableClock = MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS))
}

class SummaryReadDocuments(private val delegate: PublishedDocumentRepository) : PublishedDocumentRepository by delegate {
    val afterRead = AtomicReference<((PublishedDocument?) -> Unit)?>()

    override fun findByRevision(revision: Long): PublishedDocument? = delegate.findByRevision(revision).also { afterRead.getAndSet(null)?.invoke(it) }
}
