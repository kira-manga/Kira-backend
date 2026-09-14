package me.manga.kira.backend.tutorial

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.tutorial.application.TutorialSeedService
import me.manga.kira.backend.tutorial.application.TutorialService
import me.manga.kira.backend.tutorial.domain.MediaStorageIssue
import me.manga.kira.backend.tutorial.domain.StoredMedia
import me.manga.kira.backend.tutorial.domain.TutorialMediaStorage
import me.manga.kira.backend.tutorial.domain.TutorialMediaStorageException
import me.manga.kira.backend.tutorial.domain.TutorialRepository
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.sql.SQLException
import java.util.UUID

/** No test-managed rollback transaction: each call reaches the real proxy's physical commit. */
class TutorialMediaTransactionIT : TutorialMediaIntegrationSupport() {
    @Test
    fun `upload deferred database rejection happens after target return without destructive file compensation`() = withMediaApplication { app ->
        rejectAtCommit(app, "INSERT")
        lateinit var provisional: StoredMedia
        val observation = app.transactions.observeNextCommit {
            provisional = app.repository.listMedia().single()
            assertStoredBytes(provisional)
        }

        val failure = assertThrows(RuntimeException::class.java) { app.media.upload(upload()) }

        assertDeferredRejection(failure, observation)
        assertTrue(app.repository.listMedia().isEmpty())
        assertEquals(0, app.auditCount("TUTORIAL_MEDIA_UPLOADED"))
        assertEquals(setOf(provisional.storageFilename), managedFiles())
        assertStoredBytes(provisional)
    }

    @Test
    fun `delete deferred database rejection retains original row audit history and bytes`() = withMediaApplication { app ->
        val asset = app.persistedMedia(app.media.importSeedAsset(mediaPng(), published = false))
        rejectAtCommit(app, "DELETE")
        val observation = app.transactions.observeNextCommit {
            assertNull(app.repository.findMedia(asset.id), "the DELETE statement already returned inside the transaction")
            assertStoredBytes(asset)
        }

        val failure = assertThrows(RuntimeException::class.java) { app.media.delete(asset.id) }

        assertDeferredRejection(failure, observation)
        assertEquals(asset, app.repository.findMedia(asset.id))
        assertEquals(1, app.auditCount("TUTORIAL_MEDIA_UPLOADED"))
        assertEquals(0, app.auditCount("TUTORIAL_MEDIA_DELETED"))
        assertStoredBytes(asset)
    }

    @Test
    fun `real seed imports join the outer transaction and deferred rejection rolls back all database state`() = withMediaApplication { app ->
        val seed = app.context.getBean(TutorialSeedService::class.java)
        assertTrue(AopUtils.isAopProxy(seed))
        rejectAtCommit(app, "INSERT")
        var provisional = emptyList<StoredMedia>()
        val observation = app.transactions.observeNextCommit {
            provisional = app.repository.listMedia()
            assertEquals(6, provisional.size)
            assertEquals(4, app.repository.categoryCount())
            assertEquals(4, app.repository.tutorialCount())
            provisional.forEach(::assertStoredBytes)
            assertEquals(
                true,
                app.jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid() " +
                        "AND classid = ?::oid AND objid = ?::oid AND objsubid = 2 AND granted)",
                    Boolean::class.java,
                    0x4b495241,
                    0x544d4544,
                ),
                "the media advisory lock still belongs to the actual outer seed transaction at doCommit",
            )
        }

        val failure = assertThrows(RuntimeException::class.java) { seed.seedIfEmpty() }

        assertDeferredRejection(failure, observation)
        assertTrue(app.repository.listMedia().isEmpty())
        assertEquals(0, app.repository.categoryCount())
        assertEquals(0, app.repository.tutorialCount())
        assertEquals(0, app.jdbc.queryForObject("SELECT count(*) FROM audit_log", Int::class.java))
        assertEquals(provisional.map { it.storageFilename }.toSet(), managedFiles())
        provisional.forEach(::assertStoredBytes)
    }

    @Test
    fun `lost acknowledgement inside doCommit reports UNKNOWN and retains committed upload bytes`() = withMediaApplication { app ->
        lateinit var provisional: StoredMedia
        val observation = app.transactions.observeNextCommit(loseAcknowledgement = true) {
            provisional = app.repository.listMedia().single()
        }

        assertThrows(TransactionSystemException::class.java) { app.media.upload(upload()) }

        assertUnknownAfterRealCommit(observation)
        assertEquals(provisional, app.repository.findMedia(provisional.id))
        assertEquals(1, app.auditCount("TUTORIAL_MEDIA_UPLOADED"))
        assertStoredBytes(provisional)
    }

    @Test
    fun `lost delete acknowledgement reports UNKNOWN and does not run committed-only unlink`() = withMediaApplication { app ->
        val asset = app.persistedMedia(app.media.importSeedAsset(mediaPng(), published = false))
        val observation = app.transactions.observeNextCommit(loseAcknowledgement = true) {
            assertNull(app.repository.findMedia(asset.id))
            assertStoredBytes(asset)
        }

        assertThrows(TransactionSystemException::class.java) { app.media.delete(asset.id) }

        assertUnknownAfterRealCommit(observation)
        assertNull(app.repository.findMedia(asset.id))
        assertEquals(1, app.auditCount("TUTORIAL_MEDIA_DELETED"))
        assertStoredBytes(asset)
        // This retained rowless file is a reconciliation input, not evidence that DELETE rolled back.
    }

    @Test
    fun `checked I O after media insert rolls back the real transaction without deleting the installed file`() = withMediaApplication { app ->
        val checked = IOException("fixture checked failure after insert")
        var provisional: StoredMedia? = null
        val repository = object : TutorialRepository by app.repository {
            override fun createMedia(media: StoredMedia): StoredMedia {
                provisional = app.repository.createMedia(media)
                throw checked
            }
        }
        val media = app.mediaUsing(repository = repository)

        val failure = assertThrows(Exception::class.java) { media.upload(upload()) }

        assertSame(checked, causes(failure).firstOrNull { it is IOException })
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        assertNotNull(provisional, "the real INSERT must execute before the injected checked exception")
        assertTrue(app.repository.listMedia().isEmpty())
        assertEquals(0, app.auditCount("TUTORIAL_MEDIA_UPLOADED"))
        assertStoredBytes(requireNotNull(provisional))
    }

    @Test
    fun `checked failure in outer seed after joined imports rolls back those successful inner calls`() = withMediaApplication { app ->
        val checked = IOException("fixture checked failure at the outer seed identity guard")
        var imported = emptyList<StoredMedia>()
        val repository = object : TutorialRepository by app.repository {
            override fun categoryCount(): Int {
                imported = app.repository.listMedia()
                throw checked
            }
        }
        val seed = app.proxy(
            TutorialSeedService(
                repository,
                app.context.getBean(TutorialService::class.java),
                app.media,
                app.context.getBean(ObjectMapper::class.java),
            ),
            TutorialSeedService::class.java,
        )

        val failure = assertThrows(Exception::class.java) { seed.seedIfEmpty() }

        assertSame(checked, causes(failure).firstOrNull { it is IOException })
        assertEquals(6, imported.size, "all inner media proxies returned before this outer checked failure")
        assertTrue(app.repository.listMedia().isEmpty())
        assertEquals(0, app.auditCount("TUTORIAL_MEDIA_UPLOADED"))
        assertEquals(0, app.repository.categoryCount())
        imported.forEach(::assertStoredBytes)
    }

    @Test
    fun `committed delete cleanup I O failure does not change successful database outcome`() = withMediaApplication { app ->
        val asset = app.persistedMedia(app.media.importSeedAsset(mediaPng(), published = false))
        var cleanupCalls = 0
        val storage = object : TutorialMediaStorage by app.storage {
            override fun deleteCommitted(media: StoredMedia) {
                assertEquals(asset, media)
                cleanupCalls++
                throw IOException("fixture unlink failure")
            }
        }
        val media = app.mediaUsing(storage = storage)
        val observation = app.transactions.observeNextCommit { assertStoredBytes(asset) }

        assertDoesNotThrow { media.delete(asset.id) }

        assertTrue(observation.databaseCommitReturned)
        assertEquals(listOf(TransactionSynchronization.STATUS_COMMITTED), observation.completions)
        assertEquals(1, cleanupCalls)
        assertNull(app.repository.findMedia(asset.id))
        assertEquals(1, app.auditCount("TUTORIAL_MEDIA_DELETED"))
        assertStoredBytes(asset)
    }

    @Test
    fun `normal delete unlinks only after the known successful outer commit`() = withMediaApplication { app ->
        val asset = app.persistedMedia(app.media.importSeedAsset(mediaPng(), published = false))
        val observation = app.transactions.observeNextCommit { assertStoredBytes(asset) }

        app.transaction().executeWithoutResult {
            app.media.delete(asset.id)
            assertNull(app.repository.findMedia(asset.id))
            assertStoredBytes(asset)
        }

        assertTrue(observation.databaseCommitReturned)
        assertEquals(listOf(TransactionSynchronization.STATUS_COMMITTED), observation.completions)
        assertNull(app.repository.findMedia(asset.id))
        assertEquals(1, app.auditCount("TUTORIAL_MEDIA_DELETED"))
        assertFalse(Files.exists(mediaDirectory.resolve(asset.storageFilename), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `seed checksum reuse requires every recorded content field and canonical filename to agree`() = withMediaApplication { app ->
        val input = mediaPng()
        val asset = app.persistedMedia(app.media.importSeedAsset(input, published = false))
        val originalBytes = Files.readAllBytes(mediaDirectory.resolve(asset.storageFilename))
        val mismatches = listOf(
            "width" to asset.width + 1,
            "height" to asset.height + 1,
            "byte_size" to asset.byteSize + 1,
            "content_type" to "image/jpeg",
            "storage_filename" to "${UUID.randomUUID()}.png",
        )

        mismatches.forEach { (column, value) ->
            app.transaction().executeWithoutResult { status ->
                // Controlled fixture corruption, rolled back separately for each recorded field.
                assertEquals(1, app.jdbc.update("UPDATE tutorial_media SET $column = ? WHERE id = ?", value, asset.id))
                val mismatched = requireNotNull(app.repository.findMedia(asset.id))

                val failure = assertThrows(TutorialMediaStorageException::class.java) { app.media.importSeedAsset(input) }

                assertEquals(MediaStorageIssue.INVALID_METADATA, failure.issue, column)
                assertEquals(mismatched, app.repository.findMedia(asset.id), "seed must not rewrite recorded history")
                assertArrayEquals(originalBytes, Files.readAllBytes(mediaDirectory.resolve(asset.storageFilename)))
                assertEquals(setOf(asset.storageFilename), managedFiles())
                assertFalse(Files.exists(mediaDirectory.resolve("quarantine"), LinkOption.NOFOLLOW_LINKS))
                status.setRollbackOnly()
            }
            assertEquals(asset, app.repository.findMedia(asset.id))
        }
        assertEquals(1, app.auditCount("TUTORIAL_MEDIA_UPLOADED"))
    }

    private fun rejectAtCommit(app: MediaTestApplication, operation: String) {
        require(operation in setOf("INSERT", "DELETE"))
        app.jdbc.execute(
            """
            CREATE FUNCTION reject_media_at_commit() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'fixture deferred media rejection' USING ERRCODE = '23514';
            END;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        app.jdbc.execute(
            "CREATE CONSTRAINT TRIGGER media_commit_rejection AFTER $operation ON tutorial_media " +
                "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_media_at_commit()",
        )
        assertEquals(
            true,
            app.jdbc.queryForObject(
                "SELECT tgdeferrable AND tginitdeferred FROM pg_trigger WHERE tgname = 'media_commit_rejection'",
                Boolean::class.java,
            ),
        )
    }

    private fun assertDeferredRejection(failure: RuntimeException, observation: MediaCommitObservation) {
        assertTrue(observation.commitEntered, "the target returned before the real transaction manager attempted commit")
        assertFalse(observation.databaseCommitReturned)
        assertTrue(causes(failure).any { it is SQLException && it.sqlState == "23514" }, "require actual PostgreSQL deferred rejection")
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    private fun assertUnknownAfterRealCommit(observation: MediaCommitObservation) {
        assertTrue(observation.commitEntered)
        assertTrue(observation.databaseCommitReturned)
        assertEquals(listOf(TransactionSynchronization.STATUS_UNKNOWN), observation.completions)
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    private fun assertStoredBytes(media: StoredMedia) {
        val path = mediaDirectory.resolve(media.storageFilename)
        assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        val bytes = Files.readAllBytes(path)
        assertEquals(media.byteSize, bytes.size.toLong())
        assertEquals(media.sha256, Sha256.hex(bytes))
    }

    private fun managedFiles(): Set<String> = Files.list(mediaDirectory).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .map { it.fileName.toString() }
            .filter { it.matches(Regex("[0-9a-f-]{36}\\.(jpg|png)")) }
            .toList().toSet()
    }

    private fun upload() = MockMultipartFile("file", "fixture.png", "image/png", mediaPng())

    private fun causes(failure: Throwable): List<Throwable> = generateSequence(failure) { it.cause }.take(20).toList()
}
