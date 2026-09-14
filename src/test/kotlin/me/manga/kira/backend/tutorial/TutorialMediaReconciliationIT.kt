package me.manga.kira.backend.tutorial

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.exception.ValidationFailedException
import me.manga.kira.backend.tutorial.application.TutorialMediaInUseException
import me.manga.kira.backend.tutorial.application.TutorialMediaReconciliationService
import me.manga.kira.backend.tutorial.application.TutorialSeedService
import me.manga.kira.backend.tutorial.application.TutorialService
import me.manga.kira.backend.tutorial.domain.LocalizedText
import me.manga.kira.backend.tutorial.domain.MediaFileSnapshot
import me.manga.kira.backend.tutorial.domain.MediaInventory
import me.manga.kira.backend.tutorial.domain.MediaReadResult
import me.manga.kira.backend.tutorial.domain.MediaSlot
import me.manga.kira.backend.tutorial.domain.MediaStorageIssue
import me.manga.kira.backend.tutorial.domain.MediaTransaction
import me.manga.kira.backend.tutorial.domain.StoredMedia
import me.manga.kira.backend.tutorial.domain.TutorialContent
import me.manga.kira.backend.tutorial.domain.TutorialMediaStorage
import me.manga.kira.backend.tutorial.domain.TutorialRepository
import me.manga.kira.backend.tutorial.domain.TutorialStep
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Cooperating transactions on separate real connections; enablement here is not installed writer fencing. */
class TutorialMediaReconciliationIT : TutorialMediaIntegrationSupport() {
    @Test
    fun `scanner waits for installed upload and its outer commit before inventory and fresh metadata`() = withMediaApplication { app ->
        val installed = CountDownLatch(1)
        val allowInsert = CountDownLatch(1)
        val innerReturned = CountDownLatch(1)
        val allowOuterCommit = CountDownLatch(1)
        val scannerAttempted = CountDownLatch(1)
        val holderPid = AtomicInteger()
        val scannerPid = AtomicInteger()
        val inventoried = AtomicBoolean()
        val provisional = AtomicReference<StoredMedia>()
        val uploadStorage = object : TutorialMediaStorage by app.storage {
            override fun installNew(media: StoredMedia, bytes: ByteArray) {
                app.storage.installNew(media, bytes)
                provisional.set(media)
                holderPid.set(backendPid(app))
                installed.countDown()
                await(allowInsert, "upload was not released after installation")
            }
        }
        val scannerStorage = object : TutorialMediaStorage by app.storage {
            override fun inventory(limit: Int): MediaInventory {
                inventoried.set(true)
                return app.storage.inventory(limit)
            }
        }
        val scanner = reconciler(app, scannerStorage, observingAcquisition(app, scannerPid, scannerAttempted))
        val uploader = app.mediaUsing(storage = uploadStorage)

        withWorkers(allowInsert, allowOuterCommit) { workers ->
            val uploadTask = workers.participant {
                requireNotNull(
                    app.transaction().execute {
                        val media = uploader.upload(upload())
                        innerReturned.countDown()
                        await(allowOuterCommit, "outer upload transaction was not released")
                        media
                    },
                )
            }
            await(installed, "upload did not reach installed-before-insert boundary")
            val asset = requireNotNull(provisional.get())
            assertNull(app.repository.findMedia(asset.id))
            assertVerified(app, asset)
            val sweep = workers.participant { scanner.reconcile() }
            await(scannerAttempted, "scanner did not attempt its own lock")
            assertAdvisoryWait(app, scannerPid.get(), holderPid.get(), sweep)
            assertFalse(inventoried.get(), "inventory must not precede lock acquisition")

            allowInsert.countDown()
            await(innerReturned, "the joined upload did not return to its outer transaction")
            assertAdvisoryWait(app, scannerPid.get(), holderPid.get(), sweep)
            assertFalse(inventoried.get(), "inner proxy return must not release the outer lock")
            assertNull(app.repository.findMedia(asset.id), "the outer transaction has not committed")
            allowOuterCommit.countDown()

            assertEquals(asset, uploadTask.get(WAIT_SECONDS, TimeUnit.SECONDS))
            val report = sweep.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertTrue(report.clean)
            assertEquals(0, report.quarantinedFiles)
            app.persistedMedia(asset)
            assertVerified(app, asset)
            assertNoQuarantine()
        }
    }

    @Test
    fun `a filename introduced after fixed inventory is retained until the next pass`() = withMediaApplication { app ->
        val originalBytes = byteArrayOf(1, 2, 3)
        val laterBytes = byteArrayOf(4, 5, 6)
        val original = orphan(originalBytes)
        lateinit var later: Path
        val changed = mutableListOf<String>()
        val storage = object : TutorialMediaStorage by app.storage {
            override fun inventory(limit: Int): MediaInventory {
                val fixed = app.storage.inventory(limit)
                // Deliberate external-arrival fixture, not a claim that noncooperating writers are admitted.
                later = orphan(laterBytes)
                return fixed
            }

            override fun quarantine(candidate: MediaFileSnapshot, beforeMutation: () -> Unit): Boolean {
                changed += candidate.filename
                return app.storage.quarantine(candidate, beforeMutation)
            }
        }

        val first = reconciler(app, storage).reconcile()

        assertEquals(1, first.quarantinedFiles)
        assertEquals(listOf(original.fileName.toString()), changed)
        assertFalse(Files.exists(original, NOFOLLOW_LINKS))
        assertArrayEquals(laterBytes, Files.readAllBytes(later))
        assertTrue(app.repository.listMedia().isEmpty())
        val next = reconciler(app).reconcile()
        assertEquals(1, next.quarantinedFiles)
        assertFalse(Files.exists(later, NOFOLLOW_LINKS))
        assertEquals(setOf(Sha256.hex(originalBytes), Sha256.hex(laterBytes)), preservedChecksums())
        val last = reconciler(app).reconcile()
        assertTrue(last.clean)
        assertEquals(2, last.retainedQuarantineFiles)
    }

    @ParameterizedTest
    @EnumSource(LockLossBoundary::class)
    fun `lost scanner connection never substitutes a transaction or mutates fixed or fresh names`(boundary: LockLossBoundary) = withMediaApplication { app ->
        val oldBytes = byteArrayOf(1, 2, 3)
        val oldFiles = listOf(orphan(oldBytes), orphan(oldBytes))
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val token = AtomicReference<MediaTransaction>()
        val acquisitions = AtomicInteger()
        val rowReads = AtomicInteger()
        val permittedMutations = AtomicInteger()
        val failedOperations = AtomicInteger()
        val originalFailure = AtomicReference<DelegateFailure>()

        fun <T> observeDelegate(operation: String, call: () -> T): T = try {
            call()
        } catch (failure: Throwable) {
            // Rollback can replace the terminal exception; retain the actual delegate failure unchanged.
            failedOperations.incrementAndGet()
            originalFailure.compareAndSet(null, DelegateFailure(operation, failure))
            throw failure
        }

        val repository = object : TutorialRepository by app.repository {
            override fun acquireMediaLock(): MediaTransaction {
                acquisitions.incrementAndGet()
                return app.repository.acquireMediaLock().also(token::set)
            }

            override fun listMedia(limit: Int): List<StoredMedia> {
                rowReads.incrementAndGet()
                if (boundary == LockLossBoundary.BEFORE_FRESH_READ) pauseScanner(paused, resume)
                val rows = observeDelegate("listMedia") { app.repository.listMedia(limit) }
                if (boundary == LockLossBoundary.AFTER_FRESH_READ) pauseScanner(paused, resume)
                return rows
            }

            override fun verifyMediaLock(transaction: MediaTransaction) {
                observeDelegate("verifyMediaLock") { app.repository.verifyMediaLock(transaction) }
            }
        }
        val storage = object : TutorialMediaStorage by app.storage {
            override fun quarantine(candidate: MediaFileSnapshot, beforeMutation: () -> Unit): Boolean = app.storage.quarantine(candidate) {
                beforeMutation()
                permittedMutations.incrementAndGet()
            }
        }
        val scanner = reconciler(app, storage, repository)

        withWorkers(resume) { workers ->
            val sweep = workers.participant { runCatching { scanner.reconcile() } }
            await(paused, "scanner did not pause at the selected fresh-read boundary")
            val originalTransaction = requireNotNull(token.get())
            terminateOwnedBackend(app, originalTransaction.backendPid)
            val fresh = app.persistedMedia(app.media.upload(upload(color = 0xff554433.toInt())))
            assertTrue(oldFiles.none { it.fileName.toString() == fresh.storageFilename })
            assertVerified(app, fresh)
            resume.countDown()

            val failure = sweep.get(WAIT_SECONDS, TimeUnit.SECONDS).exceptionOrNull()
            val observed = originalFailure.get()
            val diagnostics = "original=${failureTypesAndSqlStates(observed?.failure)}; terminal=${failureTypesAndSqlStates(failure)}"
            assertNotNull(failure, "loss of the original lock/connection must abort, never reconnect and continue; $diagnostics")
            assertEquals(1, failedOperations.get(), "require exactly one failed repository operation; $diagnostics")
            val delegate = requireNotNull(observed)
            val expectedOperation = when (boundary) {
                LockLossBoundary.BEFORE_FRESH_READ -> "listMedia"
                LockLossBoundary.AFTER_FRESH_READ -> "verifyMediaLock"
            }
            assertEquals(expectedOperation, delegate.operation, diagnostics)
            assertTrue(
                generateSequence(delegate.failure) { it.cause }.take(20).any {
                    it is SQLException && (it.sqlState?.startsWith("08") == true || it.sqlState == "57P01")
                },
                "require a real PostgreSQL/driver disconnection failure, not a synthetic gate exception; $diagnostics",
            )
            assertEquals(1, acquisitions.get())
            assertEquals(1, rowReads.get())
            assertEquals(0, permittedMutations.get())
            oldFiles.forEach { assertArrayEquals(oldBytes, Files.readAllBytes(it)) }
            assertEquals(fresh, app.repository.findMedia(fresh.id))
            assertVerified(app, fresh)
            assertNoQuarantine()
        }
    }

    @Test
    fun `overlapping scanners serialize and second pass only reports preserved quarantine`() = withMediaApplication { app ->
        val firstBytes = byteArrayOf(1, 2, 3)
        val secondBytes = byteArrayOf(4, 5, 6)
        val firstFile = orphan(firstBytes)
        val staging = Files.write(mediaDirectory.resolve(".upload-interrupted-fixture.png"), secondBytes)
        val inventoried = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondAttempted = CountDownLatch(1)
        val holderPid = AtomicInteger()
        val waiterPid = AtomicInteger()
        val storage = object : TutorialMediaStorage by app.storage {
            override fun inventory(limit: Int): MediaInventory {
                val fixed = app.storage.inventory(limit)
                holderPid.set(backendPid(app))
                inventoried.countDown()
                await(release, "first scanner was not released")
                return fixed
            }
        }
        val first = reconciler(app, storage)
        val second = reconciler(app, repository = observingAcquisition(app, waiterPid, secondAttempted))

        withWorkers(release) { workers ->
            val holder = workers.participant { first.reconcile() }
            await(inventoried, "first scanner did not capture its inventory")
            val waiter = workers.participant { second.reconcile() }
            await(secondAttempted, "second scanner did not attempt its own lock")
            assertAdvisoryWait(app, waiterPid.get(), holderPid.get(), waiter)
            release.countDown()

            val firstReport = holder.get(WAIT_SECONDS, TimeUnit.SECONDS)
            val secondReport = waiter.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertEquals(2, firstReport.quarantinedFiles)
            assertEquals(setOf(MediaStorageIssue.ORPHAN, MediaStorageIssue.STAGING), firstReport.findings.map { it.issue }.toSet())
            assertTrue(secondReport.clean)
            assertEquals(0, secondReport.quarantinedFiles)
            assertEquals(2, secondReport.retainedQuarantineFiles)
            assertFalse(Files.exists(firstFile, NOFOLLOW_LINKS))
            assertFalse(Files.exists(staging, NOFOLLOW_LINKS))
            assertEquals(setOf(Sha256.hex(firstBytes), Sha256.hex(secondBytes)), preservedChecksums())
        }
    }

    @Test
    fun `lock token stays in outer transaction and prior local timeout is restored immediately`() = withMediaApplication { app ->
        lateinit var token: MediaTransaction
        val observation = app.transactions.observeNextCommit { app.repository.verifyMediaLock(token) }
        app.transaction().executeWithoutResult {
            app.jdbc.execute("SET LOCAL lock_timeout = '1379ms'")
            val priorTimeout = app.jdbc.queryForObject("SHOW lock_timeout", String::class.java)
            token = app.repository.acquireMediaLock()
            assertEquals(backendPid(app), token.backendPid)
            assertEquals(app.jdbc.queryForObject("SELECT txid_current()", Long::class.java), token.transactionId)
            assertEquals(priorTimeout, app.jdbc.queryForObject("SHOW lock_timeout", String::class.java))
            app.media.upload(upload())
            assertEquals(token, app.repository.acquireMediaLock(), "joined media calls must not substitute a transaction")
            assertEquals(priorTimeout, app.jdbc.queryForObject("SHOW lock_timeout", String::class.java))
            app.repository.verifyMediaLock(token)
        }

        assertTrue(observation.databaseCommitReturned)
        assertProxiedMediaGuard("tutorial media requires an active physical transaction") { app.repository.verifyMediaLock(token) }
        assertEquals(
            false,
            app.jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND pid = ?)", Boolean::class.java, token.backendPid),
        )
        app.transaction().executeWithoutResult { status ->
            val replacement = app.repository.acquireMediaLock()
            assertNotEquals(token.transactionId, replacement.transactionId)
            assertProxiedMediaGuard("tutorial media transaction identity changed") { app.repository.verifyMediaLock(token) }
            status.setRollbackOnly()
        }
    }

    @ParameterizedTest
    @EnumSource(OuterTransactionMode::class)
    fun `actual server outer seed state is checked instead of only Spring hints`(mode: OuterTransactionMode) = withMediaApplication { app ->
        val installs = AtomicInteger()
        val storage = object : TutorialMediaStorage by app.storage {
            override fun installNew(media: StoredMedia, bytes: ByteArray) {
                installs.incrementAndGet()
                app.storage.installNew(media, bytes)
            }
        }
        val seed = app.proxy(
            TutorialSeedService(
                app.repository,
                app.context.getBean(TutorialService::class.java),
                app.mediaUsing(storage = storage),
                app.context.getBean(ObjectMapper::class.java),
            ),
            TutorialSeedService::class.java,
        )

        app.transaction().executeWithoutResult { status ->
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            when (mode) {
                OuterTransactionMode.READ_ONLY -> {
                    app.jdbc.execute("SET TRANSACTION READ ONLY")
                    assertEquals("on", app.jdbc.queryForObject("SHOW transaction_read_only", String::class.java))
                }

                OuterTransactionMode.REPEATABLE_READ -> {
                    app.jdbc.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
                    assertEquals("repeatable read", app.jdbc.queryForObject("SHOW transaction_isolation", String::class.java))
                }
            }
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly(), "the hint deliberately differs from server state")
            assertProxiedMediaGuard("tutorial media requires effective writable READ_COMMITTED isolation") { seed.seedIfEmpty() }
            status.setRollbackOnly()
        }

        assertEquals(0, installs.get())
        assertTrue(app.repository.listMedia().isEmpty())
        assertEquals(0, app.repository.categoryCount())
        assertEquals(0, app.jdbc.queryForObject("SELECT count(*) FROM audit_log", Int::class.java))
        assertTrue(Files.list(mediaDirectory).use { it.findAny().isEmpty })
    }

    @Test
    fun `committed retained reference wins before a waiting delete decision and refusal audit commits`() = withMediaApplication { app ->
        val tutorials = app.context.getBean(TutorialService::class.java)
        val category = tutorials.createCategory("media-lock-category").category
        val tutorial = tutorials.createTutorial("media-lock-tutorial").tutorial
        val asset = app.persistedMedia(app.media.importSeedAsset(mediaPng(), published = false))
        val referenced = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deleteAttempted = CountDownLatch(1)
        val holderPid = AtomicInteger()
        val waiterPid = AtomicInteger()
        val delete = app.mediaUsing(repository = observingAcquisition(app, waiterPid, deleteAttempted))

        withWorkers(release) { workers ->
            val holder = workers.participant {
                app.transaction().executeWithoutResult {
                    tutorials.createTutorialRevision(tutorial.id, category.id, content(asset.id))
                    holderPid.set(backendPid(app))
                    referenced.countDown()
                    await(release, "outer reference transaction was not released")
                }
            }
            await(referenced, "reference creation did not return within its outer transaction")
            val waiter = workers.participant { runCatching { delete.delete(asset.id) } }
            await(deleteAttempted, "delete did not attempt the media lock")
            assertAdvisoryWait(app, waiterPid.get(), holderPid.get(), waiter)
            release.countDown()

            holder.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertInstanceOf(TutorialMediaInUseException::class.java, waiter.get(WAIT_SECONDS, TimeUnit.SECONDS).exceptionOrNull())
            assertEquals(1, app.repository.mediaReferenceCount(asset.id))
            assertEquals(asset, app.repository.findMedia(asset.id))
            assertEquals(1, app.auditCount("TUTORIAL_MEDIA_DELETION_REFUSED"))
            assertEquals(0, app.auditCount("TUTORIAL_MEDIA_DELETED"))
            assertVerified(app, asset)
        }
    }

    @Test
    fun `delete commit wins before a waiting revision reads media and no stale reference is created`() = withMediaApplication { app ->
        val tutorials = app.context.getBean(TutorialService::class.java)
        val category = tutorials.createCategory("delete-lock-category").category
        val tutorial = tutorials.createTutorial("delete-lock-tutorial").tutorial
        val asset = app.persistedMedia(app.media.importSeedAsset(mediaPng(), published = false))
        val deleted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val referenceAttempted = CountDownLatch(1)
        val holderPid = AtomicInteger()
        val waiterPid = AtomicInteger()

        withWorkers(release) { workers ->
            val holder = workers.participant {
                app.transaction().executeWithoutResult {
                    app.media.delete(asset.id)
                    holderPid.set(backendPid(app))
                    deleted.countDown()
                    await(release, "outer deletion transaction was not released")
                }
            }
            await(deleted, "delete did not return within its outer transaction")
            assertVerified(app, asset)
            val waiter = workers.participant {
                runCatching {
                    app.transaction().execute {
                        waiterPid.set(backendPid(app))
                        referenceAttempted.countDown()
                        tutorials.createTutorialRevision(tutorial.id, category.id, content(asset.id))
                    }
                }
            }
            await(referenceAttempted, "revision creator did not enter its real outer transaction")
            assertAdvisoryWait(app, waiterPid.get(), holderPid.get(), waiter)
            release.countDown()

            holder.get(WAIT_SECONDS, TimeUnit.SECONDS)
            val failure = assertInstanceOf(ValidationFailedException::class.java, waiter.get(WAIT_SECONDS, TimeUnit.SECONDS).exceptionOrNull())
            assertTrue(failure.errors.any { it.code == "UNKNOWN_MEDIA" })
            assertNull(app.repository.findMedia(asset.id))
            assertEquals(0, app.repository.mediaReferenceCount(asset.id))
            assertTrue(app.repository.listTutorialRevisions(tutorial.id).isEmpty())
            assertEquals(0, app.auditCount("TUTORIAL_REVISION_CREATED"))
            assertEquals(1, app.auditCount("TUTORIAL_MEDIA_DELETED"))
            assertFalse(Files.exists(mediaDirectory.resolve(asset.storageFilename), NOFOLLOW_LINKS))
        }
    }

    private fun reconciler(
        app: MediaTestApplication,
        storage: TutorialMediaStorage = app.storage,
        repository: TutorialRepository = app.repository,
    ): TutorialMediaReconciliationService = app.proxy(
        TutorialMediaReconciliationService(repository, storage, app.properties.copy(mediaReconciliationEnabled = true)),
        TutorialMediaReconciliationService::class.java,
    )

    private fun observingAcquisition(app: MediaTestApplication, pid: AtomicInteger, attempted: CountDownLatch): TutorialRepository =
        object : TutorialRepository by app.repository {
            override fun acquireMediaLock(): MediaTransaction {
                pid.set(backendPid(app))
                attempted.countDown()
                return app.repository.acquireMediaLock()
            }
        }

    private fun assertAdvisoryWait(app: MediaTestApplication, waiterPid: Int, holderPid: Int, waiter: Future<*>) {
        assertTrue(waiterPid > 0 && holderPid > 0 && waiterPid != holderPid, "require independent real transaction connections")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            assertFalse(waiter.isDone, "contender completed while the original transaction still holds the lock")
            val query = app.jdbc.query(
                { connection ->
                    connection.prepareStatement(
                        "SELECT query FROM pg_stat_activity WHERE pid = ? AND datname = current_database() " +
                            "AND wait_event_type = 'Lock' AND wait_event = 'advisory' AND ? = ANY(pg_blocking_pids(pid))",
                    ).apply {
                        queryTimeout = 2
                        setInt(1, waiterPid)
                        setInt(2, holderPid)
                    }
                },
                { rs, _ -> rs.getString(1) },
            ).firstOrNull()
            if (query != null) {
                assertTrue(query.lowercase().contains("pg_advisory_xact_lock"))
                return
            }
        }
        fail<Unit>("PostgreSQL never reported the contender waiting on the holder's advisory transaction lock")
    }

    private fun terminateOwnedBackend(app: MediaTestApplication, pid: Int) {
        assertTrue(pid > 0)
        val terminated = app.jdbc.query(
            { connection ->
                connection.prepareStatement(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                        "WHERE pid = ? AND datname = current_database() AND pid <> pg_backend_pid()",
                ).apply {
                    queryTimeout = 2
                    setInt(1, pid)
                }
            },
            { rs, _ -> rs.getBoolean(1) },
        ).single()
        assertTrue(terminated, "terminate only the captured scanner connection in this test's owned database")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            val remaining = app.jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE pid = ?", Int::class.java, pid)
            if (remaining == 0) return
        }
        fail<Unit>("owned scanner backend did not exit after PostgreSQL accepted termination")
    }

    private fun pauseScanner(paused: CountDownLatch, resume: CountDownLatch) {
        paused.countDown()
        await(resume, "scanner was not released after its connection-loss fixture")
    }

    private fun assertProxiedMediaGuard(expectedMessage: String, block: () -> Unit) {
        val failure = assertThrows(InvalidDataAccessApiUsageException::class.java) { block() }
        val guard = assertInstanceOf(IllegalStateException::class.java, failure.cause)
        assertEquals(expectedMessage, guard.message)
    }

    private fun failureTypesAndSqlStates(failure: Throwable?): String = generateSequence(failure) { it.cause }.take(20).joinToString(" -> ") {
        val type = it.javaClass.name.take(160)
        val sqlState = (it as? SQLException)?.sqlState?.take(5)
        if (sqlState == null) type else "$type[SQLSTATE=$sqlState]"
    }.ifEmpty { "none" }

    private fun <T> ExecutorService.participant(block: () -> T): Future<T> = submit(
        Callable {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            try {
                block()
            } finally {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "the outer proxy must complete before worker return")
            }
        },
    )

    private fun withWorkers(vararg release: CountDownLatch, block: (ExecutorService) -> Unit) {
        val workers = Executors.newFixedThreadPool(2)
        try {
            block(workers)
        } finally {
            release.forEach { it.countDown() }
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "owned workers must exit before context/pool/database cleanup")
        }
    }

    private fun await(latch: CountDownLatch, message: String) = assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), message)

    private fun backendPid(app: MediaTestApplication): Int = requireNotNull(app.jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java))

    private fun orphan(bytes: ByteArray): Path = Files.write(mediaDirectory.resolve("${UUID.randomUUID()}.png"), bytes)

    private fun assertVerified(app: MediaTestApplication, media: StoredMedia) {
        val actual = assertInstanceOf(MediaReadResult.Verified::class.java, app.storage.readVerified(media)).bytes
        assertEquals(media.byteSize, actual.size.toLong())
        assertEquals(media.sha256, Sha256.hex(actual))
    }

    private fun assertNoQuarantine() = assertFalse(Files.exists(mediaDirectory.resolve("quarantine"), NOFOLLOW_LINKS))

    private fun preservedChecksums(): Set<String> = Files.list(mediaDirectory.resolve("quarantine")).use { entries ->
        entries.map { Sha256.hex(Files.readAllBytes(it.resolve("content"))) }.toList().toSet()
    }

    private fun upload(color: Int = 0) = MockMultipartFile("file", "fixture.png", "image/png", mediaPng(color))

    private fun content(mediaId: UUID): TutorialContent {
        val text = LocalizedText("English", "العربية")
        return TutorialContent(text, text, text, text, text, MediaSlot(mediaId, text), listOf(TutorialStep("first", text, text)))
    }

    enum class LockLossBoundary { BEFORE_FRESH_READ, AFTER_FRESH_READ }
    enum class OuterTransactionMode { READ_ONLY, REPEATABLE_READ }

    private data class DelegateFailure(val operation: String, val failure: Throwable)

    private companion object {
        const val WAIT_SECONDS = 15L
    }
}
