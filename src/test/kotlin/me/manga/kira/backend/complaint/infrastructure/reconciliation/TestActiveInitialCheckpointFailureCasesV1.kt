package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReply
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

internal enum class InitialCheckpointListingCut { EPOCH_ONE, NEWER_EPOCH, UNKNOWN_NAMESPACE, TWO_VERSIONS, DELETE_MARKER, TRUNCATED, CONTINUATION }
internal enum class InitialCheckpointSealCut { PRINCIPAL, VERSION, WIRE, METADATA, RETENTION, LOCK_MODE }
internal enum class InitialCheckpointBindingCut { DESIRED_HASH, RESTORE, DATABASE, CATALOG, LEASE_EXPIRED, LEASE_REPLACED, CANCELLATION, LATE_NATIVE_CLOSE }
internal enum class InitialCheckpointCommitCut { BEFORE_COMMIT, AFTER_COMMIT, DEFERRED_COMMIT_UNKNOWN, UNRESOLVED_RELEASE }

/** Failure-only raw/transaction boundaries. AfterCommit and deferred failure are NOT an actually dropped TLS COMMIT reply. */
internal object TestActiveInitialCheckpointFailureCasesV1 {
    fun wholePrefix(tls: VersionBoundPersistenceConnectedFixture, cut: InitialCheckpointListingCut, pass: Int = 1) =
        withInitialCheckpointFixture(tls) { f ->
            val counters = f.counters()
            val prefix = f.process.consumers.journalConfiguration.ordinaryPrefix
            val stored = checkNotNull(f.sealer.native.stored)
            val key = prefix + when (cut) {
                InitialCheckpointListingCut.NEWER_EPOCH -> "writer/${f.process.consumers.journalConfiguration.declaration().writer.generationId}/epoch/0000000000000000002/key/event"
                InitialCheckpointListingCut.UNKNOWN_NAMESPACE -> "unknown-family/not-an-event"
                else -> "writer/${f.process.consumers.journalConfiguration.declaration().writer.generationId}/epoch/0000000000000000001/key/event"
            }
            f.raw.ordinaryObjects = { number ->
                if (number != pass || cut in setOf(InitialCheckpointListingCut.DELETE_MARKER, InitialCheckpointListingCut.TRUNCATED, InitialCheckpointListingCut.CONTINUATION)) emptyList()
                else listOf(stored.copy(key = key, version = "ordinary-version-1", bytes = byteArrayOf(1))).let { values ->
                    if (cut === InitialCheckpointListingCut.TWO_VERSIONS) values + values.single().copy(version = "ordinary-version-2") else values
                }
            }
            f.raw.changeList = { request, xml ->
                if (request.kind == "LIST" && request.http.rawQueryParameters()["prefix"]?.single() == prefix && checkNotNull(f.probe.original).passNumber == pass) {
                    when (cut) {
                        InitialCheckpointListingCut.DELETE_MARKER -> xml.replace("</ListVersionsResult>",
                            "<DeleteMarker><Key>${OwnerDeleteAllJournalPublisherFixture.encoded(key)}</Key><VersionId>deleted-version</VersionId><IsLatest>true</IsLatest>" +
                                "<LastModified>${stored.lastModified}</LastModified></DeleteMarker></ListVersionsResult>")
                        InitialCheckpointListingCut.TRUNCATED -> xml.replace("<IsTruncated>false</IsTruncated>", "<IsTruncated>true</IsTruncated>")
                        InitialCheckpointListingCut.CONTINUATION -> xml.replace("</ListVersionsResult>", "<NextKeyMarker>foreign</NextKeyMarker></ListVersionsResult>")
                        else -> xml
                    }
                } else xml
            }
            val original = f.begin()
            assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(original) }
            f.assertReleased(); assertNull(f.control()["checkpoint_result"])
            assertEquals(pass, f.scanRows().size); assertEquals("SCANNING", f.scanRows().last()["state"])
            TestActiveInitialCheckpointCasesV1.assertOrdinaryDelta(counters, f.counters(), pass)
            assertFalse(f.probe.calls.any { it.step === TestActiveInitialCheckpointStepV1.SUCCESS })
            val image = f.image(); val providers = f.raw.order.toList()
            assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(original) }
            assertEquals(image, f.image()); assertEquals(providers, f.raw.order)
        }

    fun seal(tls: VersionBoundPersistenceConnectedFixture, cut: InitialCheckpointSealCut) = withInitialCheckpointFixture(tls) { f ->
        val counters = f.counters()
        f.raw.changeSts = { reply -> if (cut === InitialCheckpointSealCut.PRINCIPAL) {
            val changed = reply.bytes.decodeToString().replace("AROA" + "C".repeat(17), "AROA" + "D".repeat(17)).toByteArray()
            assertEquals(reply.bytes.size, changed.size); changed.copyInto(reply.bytes)
        } }
        f.raw.changeS3 = { request, reply -> if (request.kind == "GET") {
            val header = when (cut) {
                InitialCheckpointSealCut.VERSION -> "x-amz-version-id" to "foreign-version"
                InitialCheckpointSealCut.METADATA -> "x-amz-meta-kira-journal-event-id" to "A".repeat(43)
                InitialCheckpointSealCut.RETENTION -> "x-amz-object-lock-retain-until-date" to Instant.now().minusSeconds(1).toString()
                InitialCheckpointSealCut.LOCK_MODE -> "x-amz-object-lock-mode" to "GOVERNANCE"
                else -> null
            }
            header?.let { (key, value) -> reply.headers = reply.headers.filterKeys { !it.equals(key, true) } + (key to listOf(value)) }
            if (cut === InitialCheckpointSealCut.WIRE) reply.bytes[reply.bytes.lastIndex] = (reply.bytes.last().toInt() xor 1).toByte()
        } }
        val original = f.begin()
        assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(original) }
        f.assertReleased(); assertTrue(f.scanRows().isEmpty()); assertEquals(counters, f.counters())
        assertNull(f.control()["checkpoint_result"]); assertEquals("SEAL_VERIFIED", f.control()["seal_state"])
        assertFalse(f.raw.order.any { it.startsWith("PASS") || it == "DECRYPT" }, "Cheap wrong-role/winner checks precede KMS/native passes.")
        assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(original) }
    }

    fun currentBinding(tls: VersionBoundPersistenceConnectedFixture, cut: InitialCheckpointBindingCut) = withInitialCheckpointFixture(tls) { f ->
        var reached = false
        val prefix = f.process.consumers.journalConfiguration.ordinaryPrefix
        f.raw.beforeS3 = { request -> if (!reached && request.kind == "LIST" && request.http.rawQueryParameters()["prefix"]?.single() == prefix) {
            reached = true
            when (cut) {
                InitialCheckpointBindingCut.DESIRED_HASH -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?", ByteArray(32) { 7 }, f.scope))
                InitialCheckpointBindingCut.RESTORE -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_control SET restore_identity = ? WHERE data_scope_id = ?", UUID.randomUUID(), f.scope))
                InitialCheckpointBindingCut.DATABASE -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_control SET database_identity = ? WHERE data_scope_id = ?", UUID.randomUUID(), f.scope))
                InitialCheckpointBindingCut.CATALOG -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_control SET accepted_catalog_hash = ? WHERE data_scope_id = ?", ByteArray(32) { 8 }, UUID(0L, 0L)))
                InitialCheckpointBindingCut.LEASE_EXPIRED -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_control SET lease_expires_at = clock_timestamp() - interval '1 millisecond' WHERE data_scope_id = ?", f.scope))
                InitialCheckpointBindingCut.LEASE_REPLACED -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_control SET lease_owner = ?, lease_token = lease_token + 1 WHERE data_scope_id = ?", UUID.randomUUID(), f.scope))
                InitialCheckpointBindingCut.CANCELLATION -> throw CancellationException("Synthetic checkpoint cancellation.")
                InitialCheckpointBindingCut.LATE_NATIVE_CLOSE -> Unit
            }
        } }
        f.raw.onNativeClose = {
            if (cut === InitialCheckpointBindingCut.LATE_NATIVE_CLOSE && f.raw.order.lastOrNull() == "PASS1") {
                f.sealer.native.offsetNanos += 11_000_000_000L
            }
        }
        val original = f.begin()
        if (cut === InitialCheckpointBindingCut.CANCELLATION) {
            assertThrows<CancellationException> { f.checkpoint(original) }; assertThrows<CancellationException> { f.checkpoint(original) }
        } else {
            assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(original) }
            assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(original) }
        }
        assertTrue(reached); f.raw.resetFaults(); f.assertReleased()
        assertNull(f.control()["checkpoint_result"]); assertEquals(1, f.scanRows().size)
        assertFalse(f.probe.calls.any { it.step === TestActiveInitialCheckpointStepV1.SUCCESS })
    }

    fun changedActualRawCatalogRefusesBeforeScannerNative(tls: VersionBoundPersistenceConnectedFixture) = withInitialCheckpointFixture(tls) { f ->
        val read = f.sealer.p.f.http.read
        val originalRespond = read.respond
        val before = f.image()
        var readAttempted = false
        read.respond = { request ->
            readAttempted = true
            val reply = originalRespond(request)
            S3CatalogReply(reply.bytes.copyOf().also { if (it.isNotEmpty()) it[0] = 0 }).apply {
                status = reply.status; headers = reply.headers; bodyPresent = reply.bodyPresent; chunkSize = reply.chunkSize
                beforeCall = reply.beforeCall; beforeRead = reply.beforeRead; onClose = reply.onClose; onAbort = reply.onAbort
            }
        }
        try { assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint() } }
        finally { read.respond = originalRespond }
        assertTrue(readAttempted); f.assertReleased(); assertTrue(f.raw.order.isEmpty()); assertEquals(before, f.image())
    }

    fun successCommit(tls: VersionBoundPersistenceConnectedFixture, cut: InitialCheckpointCommitCut) = withInitialCheckpointFixture(tls) { f ->
        var selected: PersistencePhaseContext? = null
        var before: Map<String, List<String>>? = null
        val resource = Any(); val sentinel = Any(); var bound = false
        f.probe.before = { call -> if (call.step === TestActiveInitialCheckpointStepV1.SUCCESS && before == null) before = f.image() }
        f.probe.after = { call -> if (selected == null && call.step === TestActiveInitialCheckpointStepV1.SUCCESS && call.sql == TestActiveInitialCheckpointSqlV1.success) {
            selected = call.phase
            if (cut === InitialCheckpointCommitCut.DEFERRED_COMMIT_UNKNOWN) {
                val jdbc = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                jdbc.execute("CREATE TEMP TABLE kira_initial_checkpoint_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, jdbc.update("INSERT INTO kira_initial_checkpoint_commit_cut VALUES (1), (1)"))
            } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) {
                    if (cut === InitialCheckpointCommitCut.BEFORE_COMMIT) error("Synthetic checkpoint before-COMMIT cut.")
                }
                override fun afterCommit() {
                    if (cut === InitialCheckpointCommitCut.UNRESOLVED_RELEASE) {
                        TransactionSynchronizationManager.bindResource(resource, sentinel); bound = true
                    }
                    if (cut !== InitialCheckpointCommitCut.BEFORE_COMMIT) error("Synthetic checkpoint after-COMMIT return cut, not a dropped TLS reply.")
                }
            })
        } }
        val original = f.begin()
        try {
            assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(original) }
            if (cut === InitialCheckpointCommitCut.UNRESOLVED_RELEASE) {
                assertTrue(bound); assertSame(selected, PersistencePhaseOwnership.current()); assertTrue(checkNotNull(selected).quarantined())
            }
        } finally {
            f.probe.before = {}; f.probe.after = {}
            if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resource))
            requireConnectionFree() // Reclaims only the quarantine. Never repairs the original result.
        }
        f.assertReleased()
        val outcome = when (cut) {
            InitialCheckpointCommitCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
            InitialCheckpointCommitCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
            else -> PersistenceDatabaseOutcome.COMMITTED
        }
        assertEquals(outcome, checkNotNull(selected).databaseOutcome())
        if (outcome !== PersistenceDatabaseOutcome.COMMITTED) {
            assertEquals(before, f.image()); assertEquals(2, f.scanRows().size); assertNull(f.control()["checkpoint_result"])
        } else {
            assertTrue(f.scanRows().isEmpty()); assertEquals("SUCCESS", f.control()["checkpoint_result"])
        }
        assertEquals(listOf("STS", "SEAL_LIST", "SEAL_GET", "DECRYPT", "PASS1", "PASS2"), f.raw.order)
        val providers = f.raw.order.toList(); val image = f.image(); val sql = f.probe.calls.size
        assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(original) }
        assertEquals(sql, f.probe.calls.size); assertEquals(providers, f.raw.order); assertEquals(image, f.image())
        if (outcome === PersistenceDatabaseOutcome.COMMITTED && cut !== InitialCheckpointCommitCut.UNRESOLVED_RELEASE) {
            assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(f.begin(restart = true)) }
            assertEquals(providers, f.raw.order); assertEquals(image, f.image())
        }
    }

    fun failedNativeClosePoisonsTheOriginalReadSlot(tls: VersionBoundPersistenceConnectedFixture) {
        var reached = false
        assertThrows<RuntimeException> { withInitialCheckpointFixture(tls) { f ->
            f.raw.onNativeClose = { if (!reached) { reached = true; error("Synthetic native close return failure.") } }
            val original = f.begin()
            assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(original) }
            assertTrue(reached); f.assertSqlReleased(); f.raw.assertDisposed(requireReturnedClose = false)
            assertNull(f.control()["checkpoint_result"]); assertTrue(f.scanRows().isEmpty())
            val image = f.image(); val providers = f.raw.order.toList(); val closes = f.raw.sts.closedClients
            assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(original) }
            assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(f.begin(restart = true)) }
            assertEquals(image, f.image()); assertEquals(providers, f.raw.order)
            assertEquals(closes, f.raw.sts.closedClients, "Retry cannot invent a native close receipt or free the poisoned slot.")
        } }
        assertTrue(reached, "The expected outer failure must be the deliberate sticky close, not setup.")
    }

}
