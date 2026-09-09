package me.manga.kira.backend.tutorial

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.config.KiraTutorialProperties
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.tutorial.application.TutorialConflictException
import me.manga.kira.backend.tutorial.application.TutorialMediaInUseException
import me.manga.kira.backend.tutorial.application.TutorialMediaService
import me.manga.kira.backend.tutorial.application.TutorialService
import me.manga.kira.backend.tutorial.application.TutorialStartupValidator
import me.manga.kira.backend.tutorial.domain.CategoryContent
import me.manga.kira.backend.tutorial.domain.LocalizedText
import me.manga.kira.backend.tutorial.domain.MediaSlot
import me.manga.kira.backend.tutorial.domain.StoredCategory
import me.manga.kira.backend.tutorial.domain.StoredRevision
import me.manga.kira.backend.tutorial.domain.StoredTutorial
import me.manga.kira.backend.tutorial.domain.TutorialContent
import me.manga.kira.backend.tutorial.domain.TutorialLifecycle
import me.manga.kira.backend.tutorial.domain.TutorialRepository
import me.manga.kira.backend.tutorial.domain.TutorialStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

class TutorialLifecycleIT : AbstractIntegrationTest() {
    @Autowired lateinit var tutorials: TutorialService

    @Autowired lateinit var media: TutorialMediaService

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var tutorialProperties: KiraTutorialProperties

    @Autowired lateinit var repository: TutorialRepository

    @Autowired lateinit var startupValidator: TutorialStartupValidator

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `seed media import restores a missing file`() {
        val bytes = png()
        val asset = media.importSeedAsset(bytes)
        val path = tutorialProperties.mediaDirectory.resolve(asset.storageFilename)
        Files.delete(path)

        val restored = media.importSeedAsset(bytes)

        assertEquals(asset.id, restored.id)
        assertTrue(Files.isRegularFile(path))
    }

    @Test
    fun `publish rollback archive and immutable referenced media follow public contract`() {
        val text = LocalizedText("English", "العربية")
        val category = tutorials.createCategory("basics")
        val categoryRevision = tutorials.createCategoryRevision(category.category.id, CategoryContent(text, "book"))
        tutorials.publishCategory(category.category.id, categoryRevision.revision.revisionNumber)

        val asset = media.importSeedAsset(png())
        val tutorial = tutorials.createTutorial("dynamic-guide", featuredPosition = 0)
        val revision = tutorials.createTutorialRevision(
            tutorial.tutorial.id,
            category.category.id,
            TutorialContent(text, text, text, text, text, MediaSlot(asset.id, text), listOf(TutorialStep("first", text, text))),
        )

        mockMvc.get("/api/v1/tutorials/dynamic-guide").andExpect { status { isNotFound() } }
        tutorials.publishTutorial(tutorial.tutorial.id, revision.revision.revisionNumber)

        val response = mockMvc.get("/api/v1/tutorials/dynamic-guide").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.slug") { value("dynamic-guide") }
            header { string("Cache-Control", "public, max-age=60, stale-if-error=86400") }
            header { exists("ETag") }
        }.andReturn().response
        mockMvc.get("/api/v1/tutorials/dynamic-guide") { header("If-None-Match", requireNotNull(response.getHeader("ETag"))) }
            .andExpect { status { isNotModified() } }

        assertThrows(TutorialMediaInUseException::class.java) { media.delete(asset.id) }
        tutorials.rollbackTutorial(tutorial.tutorial.id, 1)
        tutorials.archiveTutorial(tutorial.tutorial.id)
        mockMvc.get("/api/v1/tutorials/dynamic-guide").andExpect { status { isNotFound() } }
        tutorials.restoreTutorial(tutorial.tutorial.id)
        mockMvc.get("/api/v1/tutorials/dynamic-guide").andExpect { status { isOk() } }
    }

    @Test
    fun `category archive gates visibility without mutating retained children or media`() {
        val category = publishedCategory()
        val asset = media.importSeedAsset(png(), published = false)
        val normal = child(category.id, "normal-guide", asset.id)
        val featured = child(category.id, "featured-guide", asset.id, featuredPosition = 0)
        val archived = child(category.id, "archived-guide", asset.id, featuredPosition = 1)
        tutorials.archiveTutorial(archived.id)
        val draft = child(category.id, "draft-guide", asset.id, featuredPosition = 2, publish = false)
        val children = listOf(normal, featured, archived, draft)
        val retained = children.associate { it.id to snapshot(it.id) }
        val categoryRevisions = repository.listCategoryRevisions(category.id)
        val categoryAudits = auditCounts(AuditService.ENTITY_TUTORIAL_CATEGORY, category.id)
        val retainedMedia = media.list()
        val mediaBytes = Files.readAllBytes(tutorialProperties.mediaDirectory.resolve(asset.storageFilename))
        val publicCategories = tutorials.publicCategories()
        val publicTutorials = tutorials.publicTutorials(null, null)

        assertEquals(TutorialLifecycle.ARCHIVED, retained.getValue(archived.id).tutorial.status)
        assertEquals(archived.publishedRevisionId, retained.getValue(archived.id).tutorial.publishedRevisionId)
        assertEquals(TutorialLifecycle.DRAFT, retained.getValue(draft.id).tutorial.status)
        assertNull(retained.getValue(draft.id).tutorial.publishedRevisionId)
        assertTutorialLists(category.slug, listOf(normal.slug, featured.slug), listOf(featured.slug), listOf(normal.slug))

        tutorials.archiveCategory(category.id)

        assertCategoryState(category, TutorialLifecycle.ARCHIVED)
        assertEquals(retained, children.associate { it.id to snapshot(it.id) })
        assertEquals(categoryRevisions, repository.listCategoryRevisions(category.id))
        assertEquals(retainedMedia, media.list())
        assertCategoryHidden(category, children.map { it.slug })
        // This is committed origin state, not a claim that previously cached responses were revoked.
        validateStartup()
        assertPublicMedia(asset.id, mediaBytes)
        assertThrows(TutorialMediaInUseException::class.java) { media.delete(asset.id) }
        assertEquals(
            incremented(categoryAudits, AuditAction.TUTORIAL_CATEGORY_ARCHIVED),
            auditCounts(AuditService.ENTITY_TUTORIAL_CATEGORY, category.id),
        )

        tutorials.restoreCategory(category.id)

        assertCategoryState(category, TutorialLifecycle.PUBLISHED)
        assertEquals(retained, children.associate { it.id to snapshot(it.id) })
        assertEquals(categoryRevisions, repository.listCategoryRevisions(category.id))
        assertEquals(retainedMedia, media.list())
        assertEquals(publicCategories, tutorials.publicCategories())
        assertEquals(publicTutorials, tutorials.publicTutorials(null, null))
        mockMvc.get("/api/v1/tutorial-categories").andExpect {
            status { isOk() }
            jsonPath("$[*].slug") { value(listOf(category.slug)) }
        }
        assertTutorialLists(category.slug, listOf(normal.slug, featured.slug), listOf(featured.slug), listOf(normal.slug))
        listOf(normal, featured).forEach { mockMvc.get("/api/v1/tutorials/${it.slug}").andExpect { status { isOk() } } }
        listOf(archived, draft).forEach { mockMvc.get("/api/v1/tutorials/${it.slug}").andExpect { status { isNotFound() } } }
        assertPublicMedia(asset.id, mediaBytes)
        assertThrows(TutorialMediaInUseException::class.java) { media.delete(asset.id) }
        assertEquals(
            incremented(categoryAudits, AuditAction.TUTORIAL_CATEGORY_ARCHIVED, AuditAction.TUTORIAL_CATEGORY_RESTORED),
            auditCounts(AuditService.ENTITY_TUTORIAL_CATEGORY, category.id),
        )
        validateStartup()
    }

    @Test
    fun `startup still rejects corrupt pointers category states and hidden published media`() {
        val category = publishedCategory()
        val asset = media.importSeedAsset(png(), published = false)
        val tutorial = child(category.id, "integrity-guide", asset.id)
        validateStartup()

        invalidReferences("published tutorial without a pointer") {
            assertEquals(1, jdbcTemplate.update("UPDATE tutorials SET published_revision_id = NULL WHERE id = ?", tutorial.id))
        }
        invalidReferences("published category without a pointer") {
            assertEquals(1, jdbcTemplate.update("UPDATE tutorial_categories SET published_revision_id = NULL WHERE id = ?", category.id))
        }
        invalidReferences("draft category with a retained pointer") {
            assertEquals(1, jdbcTemplate.update("UPDATE tutorial_categories SET status = 'DRAFT' WHERE id = ?", category.id))
        }
        invalidReferences("archived category without a pointer") {
            assertEquals(
                1,
                jdbcTemplate.update("UPDATE tutorial_categories SET status = 'ARCHIVED', published_revision_id = NULL WHERE id = ?", category.id),
            )
        }

        tutorials.archiveCategory(category.id)
        validateStartup()
        invalidReferences("unpublished media behind an archived category") {
            assertEquals(1, jdbcTemplate.update("UPDATE tutorial_media SET published = false WHERE id = ?", asset.id))
        }

        val path = tutorialProperties.mediaDirectory.resolve(asset.storageFilename)
        val held = path.resolveSibling("${asset.id}-${UUID.randomUUID()}.held")
        Files.move(path, held)
        try {
            val failure = assertThrows(IllegalStateException::class.java) { validateStartup() }
            assertEquals("published tutorial media file is missing: ${asset.id}", failure.message)
        } finally {
            Files.move(held, path)
        }
        validateStartup()
    }

    @ParameterizedTest
    @EnumSource(PublicationAction::class)
    fun `archive winning the category lock rejects publication without side effects`(action: PublicationAction) {
        val fixture = publicationFixture()
        val before = snapshot(fixture.tutorial.id)
        val mediaBefore = media.list()
        val categoryRevisions = repository.listCategoryRevisions(fixture.category.id)
        val categoryAudits = auditCounts(AuditService.ENTITY_TUTORIAL_CATEGORY, fixture.category.id)

        val result = categoryRace(
            winner = { tutorials.archiveCategory(fixture.category.id) },
            waiter = { publishOrRollback(action, fixture) },
        )

        // Old rollback can block later at its revision INSERT's FK lock after a stale category read.
        // Blocking alone is not the regression oracle: it must reject, with every side effect absent.
        val failure = assertInstanceOf(TutorialConflictException::class.java, result.exceptionOrNull())
        assertEquals(HttpStatus.CONFLICT, failure.status)
        assertEquals("TUTORIAL_CATEGORY_NOT_PUBLISHED", failure.code)
        assertEquals(before, snapshot(fixture.tutorial.id))
        assertEquals(mediaBefore, media.list())
        assertEquals(categoryRevisions, repository.listCategoryRevisions(fixture.category.id))
        assertCategoryState(fixture.category, TutorialLifecycle.ARCHIVED)
        assertEquals(
            incremented(categoryAudits, AuditAction.TUTORIAL_CATEGORY_ARCHIVED),
            auditCounts(AuditService.ENTITY_TUTORIAL_CATEGORY, fixture.category.id),
        )
        assertCategoryHidden(fixture.category, listOf(fixture.tutorial.slug))
        validateStartup()
    }

    @ParameterizedTest
    @EnumSource(PublicationAction::class)
    fun `publication holds the category lock through commit before archive hides its child`(action: PublicationAction) {
        val fixture = publicationFixture()
        val before = snapshot(fixture.tutorial.id)
        val mediaBefore = media.list()
        val categoryRevisions = repository.listCategoryRevisions(fixture.category.id)
        val categoryAudits = auditCounts(AuditService.ENTITY_TUTORIAL_CATEGORY, fixture.category.id)

        categoryRace(
            winner = { publishOrRollback(action, fixture) },
            waiter = { tutorials.archiveCategory(fixture.category.id) },
        ).getOrThrow()

        val after = snapshot(fixture.tutorial.id)
        val published = after.revisions.single { it.id == after.tutorial.publishedRevisionId }
        val expected = before.revisions.single {
            it.revisionNumber == if (action == PublicationAction.PUBLISH) fixture.publishRevision else fixture.rollbackRevision
        }
        assertEquals(TutorialLifecycle.PUBLISHED, after.tutorial.status)
        assertEquals(
            before.tutorial.copy(publishedRevisionId = published.id, updatedAt = after.tutorial.updatedAt),
            after.tutorial,
        )
        assertEquals(expected.contentJson, published.contentJson)
        assertEquals(expected.categoryId, published.categoryId)
        if (action == PublicationAction.PUBLISH) {
            assertEquals(expected.id, published.id)
            assertEquals(before.revisions, after.revisions)
            assertEquals(before.references, after.references)
            assertEquals(incremented(before.auditCounts, AuditAction.TUTORIAL_PUBLISHED), after.auditCounts)
        } else {
            assertNotEquals(expected.id, published.id)
            assertEquals(before.revisions.maxOf { it.revisionNumber } + 1, published.revisionNumber)
            assertEquals(before.revisions.size + 1, after.revisions.size)
            assertEquals(before.revisions, after.revisions.filterNot { it.id == published.id })
            assertEquals(before.references, after.references.filterNot { it.revisionId == published.id })
            assertEquals(
                before.references.filter { it.revisionId == expected.id }.map { it.slot to it.mediaId },
                after.references.filter { it.revisionId == published.id }.map { it.slot to it.mediaId },
            )
            assertEquals(
                incremented(before.auditCounts, AuditAction.TUTORIAL_REVISION_CREATED, AuditAction.TUTORIAL_PUBLISHED, AuditAction.TUTORIAL_ROLLBACK),
                after.auditCounts,
            )
        }
        assertEquals(mediaBefore.map { if (it.id == fixture.unpublishedMediaId) it.copy(published = true) else it }, media.list())
        assertEquals(categoryRevisions, repository.listCategoryRevisions(fixture.category.id))
        assertCategoryState(fixture.category, TutorialLifecycle.ARCHIVED)
        assertEquals(
            incremented(categoryAudits, AuditAction.TUTORIAL_CATEGORY_ARCHIVED),
            auditCounts(AuditService.ENTITY_TUTORIAL_CATEGORY, fixture.category.id),
        )
        assertCategoryHidden(fixture.category, listOf(fixture.tutorial.slug))
        validateStartup()
    }

    enum class PublicationAction { PUBLISH, ROLLBACK }

    private fun publishedCategory(): StoredCategory {
        val category = tutorials.createCategory("category-gate")
        val revision = tutorials.createCategoryRevision(category.category.id, CategoryContent(LocalizedText("Category", "فئة"), "book"))
        return tutorials.publishCategory(category.category.id, revision.revision.revisionNumber).category
    }

    private fun child(categoryId: UUID, slug: String, mediaId: UUID, featuredPosition: Int? = null, publish: Boolean = true): StoredTutorial {
        val tutorial = tutorials.createTutorial(slug, featuredPosition = featuredPosition).tutorial
        val revision = tutorials.createTutorialRevision(tutorial.id, categoryId, content(mediaId, slug))
        return if (publish) tutorials.publishTutorial(tutorial.id, revision.revision.revisionNumber).tutorial else tutorial
    }

    private fun content(mediaId: UUID, title: String): TutorialContent {
        val text = LocalizedText(title, "العربية")
        return TutorialContent(text, text, text, text, text, MediaSlot(mediaId, text), listOf(TutorialStep("first", text, text)))
    }

    private fun publicationFixture(): PublicationFixture {
        val category = publishedCategory()
        val currentMedia = media.importSeedAsset(png(), published = false)
        val unpublishedMedia = media.importSeedAsset(png(0xff336699.toInt()), published = false)
        val tutorial = tutorials.createTutorial("publication-guide", featuredPosition = 0).tutorial
        // An older retained draft gives rollback a real unpublished asset to protect on rejection.
        val historical = tutorials.createTutorialRevision(tutorial.id, category.id, content(unpublishedMedia.id, "Historical"))
        val current = tutorials.createTutorialRevision(tutorial.id, category.id, content(currentMedia.id, "Current"))
        val published = tutorials.publishTutorial(tutorial.id, current.revision.revisionNumber).tutorial
        val next = tutorials.createTutorialRevision(tutorial.id, category.id, content(unpublishedMedia.id, "Next"))
        assertFalse(requireNotNull(repository.findMedia(unpublishedMedia.id)).published)
        return PublicationFixture(category, published, next.revision.revisionNumber, historical.revision.revisionNumber, unpublishedMedia.id)
    }

    private fun publishOrRollback(action: PublicationAction, fixture: PublicationFixture) {
        when (action) {
            PublicationAction.PUBLISH -> tutorials.publishTutorial(fixture.tutorial.id, fixture.publishRevision)
            PublicationAction.ROLLBACK -> tutorials.rollbackTutorial(fixture.tutorial.id, fixture.rollbackRevision)
        }
    }

    private fun assertTutorialLists(category: String, all: List<String>, featured: List<String>, normal: List<String>) {
        listOf(
            "/api/v1/tutorials" to all,
            "/api/v1/tutorials?category=$category" to all,
            "/api/v1/tutorials?featured=true" to featured,
            "/api/v1/tutorials?category=$category&featured=true" to featured,
            "/api/v1/tutorials?featured=false" to normal,
            "/api/v1/tutorials?category=$category&featured=false" to normal,
        ).forEach { (path, slugs) ->
            mockMvc.get(path).andExpect {
                status { isOk() }
                jsonPath("$[*].slug") { value(slugs) }
            }
        }
    }

    private fun assertCategoryHidden(category: StoredCategory, slugs: List<String>) {
        mockMvc.get("/api/v1/tutorial-categories").andExpect {
            status { isOk() }
            content { json("[]") }
        }
        assertTutorialLists(category.slug, emptyList(), emptyList(), emptyList())
        slugs.forEach { mockMvc.get("/api/v1/tutorials/$it").andExpect { status { isNotFound() } } }
    }

    private fun assertCategoryState(before: StoredCategory, status: TutorialLifecycle) {
        val after = requireNotNull(repository.findCategory(before.id))
        assertEquals(before.copy(status = status, updatedAt = after.updatedAt), after)
    }

    private fun assertPublicMedia(id: UUID, expected: ByteArray) {
        mockMvc.get("/api/v1/tutorial-media/$id").andExpect {
            status { isOk() }
            header { string("Cache-Control", "public, max-age=31536000, immutable") }
            content { bytes(expected) }
        }
    }

    private fun snapshot(id: UUID) = TutorialSnapshot(
        tutorial = requireNotNull(repository.findTutorial(id)),
        revisions = repository.listTutorialRevisions(id),
        references = jdbcTemplate.query(
            "SELECT rm.revision_id, rm.media_id, rm.slot_key FROM tutorial_revision_media rm " +
                "JOIN tutorial_revisions r ON r.id = rm.revision_id WHERE r.tutorial_id = ? ORDER BY rm.revision_id, rm.slot_key",
            { row, _ -> RevisionMedia(row.getObject("revision_id", UUID::class.java), row.getObject("media_id", UUID::class.java), row.getString("slot_key")) },
            id,
        ),
        auditCounts = auditCounts(AuditService.ENTITY_TUTORIAL, id),
    )

    private fun auditCounts(entityType: String, id: UUID): Map<String, Long> = jdbcTemplate.query(
        "SELECT action, count(*) FROM audit_log WHERE entity_type = ? AND entity_id = ? GROUP BY action",
        { row, _ -> row.getString(1) to row.getLong(2) },
        entityType,
        id.toString(),
    ).toMap()

    private fun incremented(before: Map<String, Long>, vararg actions: AuditAction): Map<String, Long> = actions.fold(before) { counts, action ->
        counts + (action.wire to (counts.getOrDefault(action.wire, 0L) + 1))
    }

    private fun validateStartup() = startupValidator.run(DefaultApplicationArguments())

    private fun invalidReferences(name: String, corrupt: () -> Unit) {
        inTransaction { transaction ->
            try {
                corrupt()
                val failure = assertThrows(IllegalStateException::class.java, { validateStartup() }, name)
                assertEquals("invalid published tutorial/category/media references detected", failure.message, name)
            } finally {
                transaction.setRollbackOnly()
            }
        }
        validateStartup()
    }

    private fun inTransaction(block: (TransactionStatus) -> Unit) {
        TransactionTemplate(transactionManager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            timeout = 60
        }.executeWithoutResult { transaction ->
            jdbcTemplate.execute("SET LOCAL lock_timeout = '20s'")
            jdbcTemplate.execute("SET LOCAL statement_timeout = '30s'")
            block(transaction)
        }
    }

    private fun categoryRace(winner: () -> Unit, waiter: () -> Unit): Result<Unit> {
        val pool = Executors.newFixedThreadPool(2)
        val winnerApplied = CountDownLatch(1)
        val waiterEntered = CountDownLatch(1)
        val releaseWinner = CountDownLatch(1)
        val winnerPid = AtomicInteger()
        val waiterPid = AtomicInteger()
        // use releases/terminates in finally and preserves an earlier failure if cleanup also fails.
        return AutoCloseable {
            releaseWinner.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(35, TimeUnit.SECONDS), "owned transaction workers did not terminate")
        }.use {
            val winning = pool.submit<Unit> {
                inTransaction {
                    winnerPid.set(requireNotNull(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                    winner()
                    // The real proxied service has returned; its category lock must still be held.
                    winnerApplied.countDown()
                    assertTrue(releaseWinner.await(40, TimeUnit.SECONDS), "winner was not released")
                }
            }
            assertTrue(winnerApplied.await(10, TimeUnit.SECONDS), "winner did not finish its service call")
            val waiting = pool.submit<Result<Unit>> {
                runCatching {
                    inTransaction {
                        waiterPid.set(requireNotNull(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                        waiterEntered.countDown()
                        waiter()
                    }
                }
            }
            assertTrue(waiterEntered.await(10, TimeUnit.SECONDS), "waiter did not enter its transaction")
            assertNotEquals(winnerPid.get(), waiterPid.get())
            awaitPostgresBlocker(waiterPid.get(), winnerPid.get())
            releaseWinner.countDown()
            winning.get(45, TimeUnit.SECONDS)
            waiting.get(45, TimeUnit.SECONDS)
        }
    }

    private fun awaitPostgresBlocker(waiterPid: Int, blockerPid: Int) {
        requireNotNull(jdbcTemplate.dataSource).connection.use { connection ->
            connection.prepareStatement("SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?))").use { statement ->
                statement.queryTimeout = 2
                statement.setInt(1, blockerPid)
                statement.setInt(2, waiterPid)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                do {
                    val blocked = statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertNotEquals(waiterPid, rows.getInt(1), "observer must use a third connection")
                        assertNotEquals(blockerPid, rows.getInt(1), "observer must use a third connection")
                        rows.getBoolean(2)
                    }
                    if (blocked) return
                } while (System.nanoTime() < deadline)
            }
        }
        error("PostgreSQL did not report backend $waiterPid blocked by $blockerPid before winner release")
    }

    private data class PublicationFixture(
        val category: StoredCategory,
        val tutorial: StoredTutorial,
        val publishRevision: Int,
        val rollbackRevision: Int,
        val unpublishedMediaId: UUID,
    )

    private data class TutorialSnapshot(
        val tutorial: StoredTutorial,
        val revisions: List<StoredRevision>,
        val references: List<RevisionMedia>,
        val auditCounts: Map<String, Long>,
    )

    private data class RevisionMedia(val revisionId: UUID, val mediaId: UUID, val slot: String)

    private fun png(pixel: Int = 0): ByteArray = ByteArrayOutputStream().use { output ->
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, pixel)
        ImageIO.write(image, "png", output)
        output.toByteArray()
    }
}
