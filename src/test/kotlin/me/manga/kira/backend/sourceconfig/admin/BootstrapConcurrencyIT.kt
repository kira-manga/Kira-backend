package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.common.exception.ApiException
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.application.DocumentAssemblyService
import me.manga.kira.backend.sourceconfig.application.GenericV2CutoverService
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPhase
import me.manga.kira.backend.sourceconfig.domain.NewSourceConfig
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.SourceConfigHead
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real transaction proxies and the actual PostgreSQL G lock, with bounded observation/cleanup. */
@Import(BootstrapConcurrencyTestConfiguration::class)
@Timeout(45)
class BootstrapConcurrencyIT : AbstractAdminSourceIT() {
    @Autowired
    private lateinit var cutover: GenericV2CutoverService

    @Autowired
    private lateinit var documents: PublishedDocumentRepository

    @Autowired
    private lateinit var hooks: BootstrapRaceHooks

    @Autowired
    private lateinit var assembly: DocumentAssemblyService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun prepareRace() {
        hooks.clear()
        assertTrue(AopUtils.isAopProxy(cutover))
        assertTrue(AopUtils.isAopProxy(sourceAdminService))
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    @AfterEach
    fun clearRaceHooks() {
        hooks.clear()
    }

    @Test
    fun `bootstrap owning G makes a creator wait and append only a later draft`() = withWorkers { workers, release ->
        val raw = approvedBootstrapPayload()
        val extra = SourceConfigFixtures.validGenericSource("CreatorAfterBootstrap")
        val bootstrapHeld = CountDownLatch(1)
        val creatorAttempted = CountDownLatch(1)
        val holderPid = AtomicInteger()
        val waiterPid = AtomicInteger()
        val heldOnce = AtomicBoolean()
        hooks.afterLock.set { participant, pid ->
            if (participant == FIRST && heldOnce.compareAndSet(false, true)) {
                holderPid.set(pid)
                bootstrapHeld.countDown()
                await(release, "bootstrap owner was not released")
            }
        }
        hooks.beforeLock.set { participant, pid ->
            if (participant == SECOND) {
                waiterPid.set(pid)
                creatorAttempted.countDown()
            }
        }

        val bootstrap = workers.participant(FIRST) { cutover.importBundled(raw, GenericV2CutoverService.CONFIRMATION, admin.id) }
        await(bootstrapHeld, "bootstrap never acquired G")
        val creator = workers.participant(SECOND) { sourceAdminService.createSource(toJson(extra), admin.id) }
        await(creatorAttempted, "creator never attempted G")
        assertActuallyBlocked(waiterPid.get(), holderPid.get(), creator)
        assertEquals(0L, sourceRowCount(), "the blocked creator must not insert ahead of bootstrap")

        release.countDown()
        val receipt = bootstrap.get(WAIT_SECONDS, TimeUnit.SECONDS)
        creator.get(WAIT_SECONDS, TimeUnit.SECONDS)
        val head = sourceAdminService.getSource(extra.api).head
        assertEquals("draft", head.status.wire)
        assertNull(head.currentPublishedRevisionId)
        assertEquals(45, head.position, "nextPosition must run after G, seeing all bootstrap heads")
        assertEquals(46L, sourceRowCount())
        assertEquals(GenericV2CutoverService.APPROVED_GENERIC_APIS, publicServedDocument().sources.map { it.api })
        assertEquals(receipt, documents.initialSourceCatalogState().receipt)
        assertEquals(receipt.documentRevision, latestPointer())
        assertEquals(1, hooks.allocations.get())
        assertOneBootstrapPublication()
        assertPublicArtifactAbsent(extra.api, 1)
    }

    @Test
    fun `creator owning G after insertion makes bootstrap wait then reject its retained extra head`() = withWorkers { workers, release ->
        val raw = approvedBootstrapPayload()
        val extra = SourceConfigFixtures.validGenericSource("CreatorBeforeBootstrap")
        val before = publicState()
        val inserted = CountDownLatch(1)
        val bootstrapAttempted = CountDownLatch(1)
        val holderPid = AtomicInteger()
        val waiterPid = AtomicInteger()
        hooks.afterLock.set { participant, pid -> if (participant == FIRST) holderPid.set(pid) }
        hooks.afterCreate.set { participant, head ->
            if (participant == FIRST) {
                assertEquals(extra.api, head.api)
                assertTrue(hooks.holdsG.get() == true)
                assertEquals(
                    1L,
                    jdbcTemplate.queryForObject("SELECT count(*) FROM source_configs WHERE id = ?", Long::class.java, head.id),
                    "the creator's insert must already exist on its transaction connection before bootstrap queues",
                )
                inserted.countDown()
                await(release, "creator owner was not released after its insert")
            }
        }
        hooks.beforeLock.set { participant, pid ->
            if (participant == SECOND) {
                waiterPid.set(pid)
                bootstrapAttempted.countDown()
            }
        }

        val creator = workers.participant(FIRST) { sourceAdminService.createSource(toJson(extra), admin.id) }
        await(inserted, "creator never inserted while owning G")
        val bootstrap = workers.participant(SECOND) { cutover.importBundled(raw, GenericV2CutoverService.CONFIRMATION, admin.id) }
        await(bootstrapAttempted, "bootstrap never attempted G")
        assertActuallyBlocked(waiterPid.get(), holderPid.get(), bootstrap)
        assertEquals(0L, sourceRowCount(), "the creator insert is still inside its uncommitted transaction")

        release.countDown()
        creator.get(WAIT_SECONDS, TimeUnit.SECONDS)
        val rejected = assertThrows(ExecutionException::class.java) { bootstrap.get(WAIT_SECONDS, TimeUnit.SECONDS) }
        val conflict = rejected.cause
        assertTrue(conflict is ApiException)
        assertEquals(BOOTSTRAP_REJECTED, (conflict as ApiException).code)
        assertEquals(InitialSourceCatalogPhase.PENDING, documents.initialSourceCatalogState().phase)
        assertNull(documents.initialSourceCatalogState().receipt)
        assertEquals(1L, sourceRowCount(), "all 45 bootstrap inserts must roll back, retaining only the creator's committed draft")
        assertEquals("draft", sourceStatus(extra.api))
        assertEquals(1L, revisionCount(extra.api))
        assertEquals(0, sourceAdminService.getSource(extra.api).head.position)
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM source_validation_results", Long::class.java))
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log", Long::class.java), "only creator source/revision audits survive")
        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action IN ('BUNDLED_IMPORTED', 'SOURCE_CATALOG_V2_CUTOVER')",
                Long::class.java,
            ),
        )
        assertPublicStateUnchanged(before)
        assertPublicArtifactAbsent(extra.api, 1)
        assertPublicArtifactAbsent("Azora", 1)
    }

    @Test
    fun `overlapping identical raw requests serialize to one publication and the same immutable receipt`() = withWorkers { workers, release ->
        val raw = approvedBootstrapPayload()
        val held = CountDownLatch(1)
        val retryAttempted = CountDownLatch(1)
        val holderPid = AtomicInteger()
        val waiterPid = AtomicInteger()
        val heldOnce = AtomicBoolean()
        hooks.afterLock.set { participant, pid ->
            if (participant == FIRST && heldOnce.compareAndSet(false, true)) {
                holderPid.set(pid)
                held.countDown()
                await(release, "first identical request was not released")
            }
        }
        hooks.beforeLock.set { participant, pid ->
            if (participant == SECOND) {
                waiterPid.set(pid)
                retryAttempted.countDown()
            }
        }

        val first = workers.participant(FIRST) { cutover.importBundled(raw, GenericV2CutoverService.CONFIRMATION, admin.id) }
        await(held, "first identical request never acquired G")
        val retry = workers.participant(SECOND) { cutover.importBundled(raw.copyOf(), GenericV2CutoverService.CONFIRMATION, admin.id) }
        await(retryAttempted, "identical retry never attempted G")
        assertActuallyBlocked(waiterPid.get(), holderPid.get(), retry)

        release.countDown()
        val origin = first.get(WAIT_SECONDS, TimeUnit.SECONDS)
        assertEquals(origin, retry.get(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(origin, documents.initialSourceCatalogState().receipt)
        assertEquals(origin.documentRevision, latestPointer())
        assertEquals(bootstrapSha256(raw), origin.payloadSha256)
        assertEquals(45L, sourceRowCount())
        assertEquals(45L, jdbcTemplate.queryForObject("SELECT count(*) FROM source_config_revisions", Long::class.java))
        assertEquals(45L, jdbcTemplate.queryForObject("SELECT count(*) FROM source_validation_results", Long::class.java))
        assertEquals(1, hooks.allocations.get(), "count real allocator invocations, never require sequence reuse after failures")
        assertOneBootstrapPublication()
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log", Long::class.java), "only one import and one completion audit")
    }

    @Test
    fun `normal COMPLETE materialization acquires G even when its transactional caller did not`() = withWorkers { workers, release ->
        val origin = bootstrapInitialCatalog()
        val held = CountDownLatch(1)
        val materializerAttempted = CountDownLatch(1)
        val holderPid = AtomicInteger()
        val waiterPid = AtomicInteger()
        hooks.afterLock.set { participant, pid ->
            if (participant == FIRST) {
                holderPid.set(pid)
                held.countDown()
                await(release, "independent G owner was not released")
            }
        }
        hooks.beforeLock.set { participant, pid ->
            if (participant == SECOND) {
                waiterPid.set(pid)
                materializerAttempted.countDown()
            }
        }

        val holder = workers.participant(FIRST) {
            TransactionTemplate(transactionManager).execute { documents.lockPublicationState() }
        }
        await(held, "independent transaction never acquired G")
        val materializer = workers.participant(SECOND) {
            // Intentionally no caller-side lock: the shared materializer itself must acquire it.
            TransactionTemplate(transactionManager).execute { assembly.materialize(admin.id) }
        }
        await(materializerAttempted, "normal materializer did not attempt its own G acquisition")
        assertActuallyBlocked(waiterPid.get(), holderPid.get(), materializer)
        assertEquals(1L, snapshotCount())

        release.countDown()
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS)
        val publication = requireNotNull(materializer.get(WAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue(publication.documentRevision > origin.documentRevision)
        assertEquals(publication.documentRevision, latestPointer())
        assertEquals(2L, snapshotCount())
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT count(*) FROM published_source_catalogs", Long::class.java))
        assertEquals(origin, documents.initialSourceCatalogState().receipt)
        assertEquals(1, hooks.allocations.get())
    }

    private fun assertOneBootstrapPublication() {
        assertEquals(1L, snapshotCount())
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM published_source_catalogs", Long::class.java))
        assertEquals(12L, jdbcTemplate.queryForObject("SELECT count(*) FROM published_source_catalog_entries", Long::class.java))
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'BUNDLED_IMPORTED'", Long::class.java))
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'SOURCE_CATALOG_V2_CUTOVER'", Long::class.java))
    }

    private fun assertActuallyBlocked(waiterPid: Int, holderPid: Int, waiter: Future<*>) {
        assertTrue(waiterPid > 0 && holderPid > 0 && waiterPid != holderPid, "writers use independent PostgreSQL transaction connections")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        // Observe the server's actual wait, not an isDone/sleep-based scheduling guess. Bound both
        // the observation loop and each query; the writer also has a transaction-local lock timeout.
        while (System.nanoTime() < deadline) {
            assertFalse(waiter.isDone, "contender passed G while the first transaction still holds it")
            val blockedQuery = jdbcTemplate.query(
                { connection ->
                    connection.prepareStatement(
                        "SELECT query FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock' " +
                            "AND ? = ANY(pg_blocking_pids(pid))",
                    ).apply {
                        queryTimeout = 2
                        setInt(1, waiterPid)
                        setInt(2, holderPid)
                    }
                },
                { rs, _ -> rs.getString("query") },
            ).firstOrNull()
            if (blockedQuery != null) {
                assertTrue(blockedQuery.lowercase().contains("document_publication_state"))
                assertTrue(blockedQuery.lowercase().contains("for update"))
                return
            }
        }
        fail<Unit>("PostgreSQL never reported the contender waiting on the holder's actual G row lock")
    }

    private fun <T> ExecutorService.participant(name: String, action: () -> T): Future<T> = submit(
        Callable {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            hooks.participant.set(name)
            try {
                action().also {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "service proxy must commit before returning")
                }
            } finally {
                hooks.participant.remove()
                hooks.holdsG.remove()
            }
        },
    )

    private fun withWorkers(action: (ExecutorService, CountDownLatch) -> Unit) {
        val workers = Executors.newFixedThreadPool(2)
        val release = CountDownLatch(1)
        try {
            action(workers, release)
        } finally {
            hooks.clearCallbacks()
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "bootstrap race workers did not exit after release/cancellation")
        }
    }

    private fun await(latch: CountDownLatch, message: String) = assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), message)

    private companion object {
        const val FIRST = "first"
        const val SECOND = "second"
        const val WAIT_SECONDS = 10L
    }
}

@TestConfiguration
class BootstrapConcurrencyTestConfiguration {
    @Bean
    fun bootstrapRaceHooks() = BootstrapRaceHooks()

    @Bean
    @Primary
    fun bootstrapRaceDocuments(
        @Qualifier("jpaPublishedDocumentRepositoryAdapter") delegate: PublishedDocumentRepository,
        hooks: BootstrapRaceHooks,
        jdbcTemplate: JdbcTemplate,
    ): PublishedDocumentRepository = BootstrapRaceDocuments(delegate, hooks, jdbcTemplate)

    @Bean
    @Primary
    fun bootstrapRaceSources(
        @Qualifier("jpaSourceConfigRepositoryAdapter") delegate: SourceConfigRepository,
        hooks: BootstrapRaceHooks,
    ): SourceConfigRepository = BootstrapRaceSources(delegate, hooks)
}

class BootstrapRaceHooks {
    val participant = ThreadLocal<String>()
    val holdsG = ThreadLocal<Boolean>()
    val allocations = AtomicInteger()
    val beforeLock = AtomicReference<((String, Int) -> Unit)?>(null)
    val afterLock = AtomicReference<((String, Int) -> Unit)?>(null)
    val afterCreate = AtomicReference<((String, SourceConfigHead) -> Unit)?>(null)

    fun clearCallbacks() {
        beforeLock.set(null)
        afterLock.set(null)
        afterCreate.set(null)
    }

    fun clear() {
        clearCallbacks()
        allocations.set(0)
    }
}

/** Delegates every lock/allocator to the real adapter; hooks never replace transaction authority. */
class BootstrapRaceDocuments(
    private val delegate: PublishedDocumentRepository,
    private val hooks: BootstrapRaceHooks,
    private val jdbcTemplate: JdbcTemplate,
) : PublishedDocumentRepository by delegate {
    override fun lockPublicationState(): Long? {
        val participant = hooks.participant.get() ?: return delegate.lockPublicationState()
        check(TransactionSynchronizationManager.isActualTransactionActive())
        check(!TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'")
        val pid = requireNotNull(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Int::class.java))
        hooks.beforeLock.get()?.invoke(participant, pid)
        val pointer = delegate.lockPublicationState()
        hooks.holdsG.set(true)
        hooks.afterLock.get()?.invoke(participant, pid)
        return pointer
    }

    override fun nextDocumentRevision(): Long {
        if (hooks.participant.get() != null) hooks.allocations.incrementAndGet()
        return delegate.nextDocumentRevision()
    }
}

class BootstrapRaceSources(private val delegate: SourceConfigRepository, private val hooks: BootstrapRaceHooks) : SourceConfigRepository by delegate {
    override fun existsByApi(api: String): Boolean {
        requireObservedG()
        return delegate.existsByApi(api)
    }

    override fun nextPosition(): Int {
        requireObservedG()
        return delegate.nextPosition()
    }

    override fun create(spec: NewSourceConfig): SourceConfigHead {
        requireObservedG()
        return delegate.create(spec).also { head ->
            hooks.participant.get()?.let { hooks.afterCreate.get()?.invoke(it, head) }
        }
    }

    private fun requireObservedG() {
        if (hooks.participant.get() != null) {
            check(hooks.holdsG.get() == true) { "inventory identity, position and insert must run only after the real G acquisition" }
        }
    }
}
