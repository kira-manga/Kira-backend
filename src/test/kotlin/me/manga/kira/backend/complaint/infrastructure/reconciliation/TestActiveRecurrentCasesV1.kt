package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveCheckpointHistoryV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actual PG/native producer cases when selected by the parent; SOURCE_ONLY / NOT_RUN here. */
internal object TestActiveRecurrentCasesV1 {
    fun genuineNonempty(tls: VersionBoundPersistenceConnectedFixture, family: ComplaintJournalDeletionKindV1, maximumVersions: Long = 10_000) {
        var stage = "SETUP"
        var observedFixture: TestActiveRecurrentFixtureV1? = null
        var reportedFailure: TestActiveRecurrentExceptionV1? = null
        fun report(failure: TestActiveRecurrentExceptionV1) {
            reportedFailure = failure
            try {
                // Retained observations only: these are NOT an attribution to a failing SQL call.
                // Product checkpoint cleanup may already have run before the same exception escapes.
                val calls = observedFixture?.probe?.calls
                val last = calls?.lastOrNull()
                val ordinal = if (last == null) 0 else calls?.count { it.phase === last.phase } ?: 0
                println("TEST_ACTIVE_RECURRENT_CASE_FAILURE stage=$stage code=RECURRENT_REFUSED " +
                    "fixtureReady=${observedFixture != null} observedStep=${observedFixture?.original?.step?.name ?: "NONE"} " +
                    "lastAttemptedSqlStep=${last?.step?.name ?: "NONE"} lastPhaseCallOrdinal=$ordinal")
            } catch (_: Throwable) { /* Diagnostics must not replace the original failure. */ }
        }
        try {
            withRecurrentFixture(tls, family, maximumVersions = maximumVersions) { f ->
                observedFixture = f
                try {
                    val initial = f.control()
                    val initialBytes = (initial.getValue("checkpoint_bytes") as ByteArray).copyOf()
                    val initialHash = (initial.getValue("checkpoint_hash") as ByteArray).copyOf()
                    val before = f.counters()
                    val domain = f.domainImage()
                    val oldSeal = f.immutableImage().getValue("complaint_test_active_seal_intents")
                    var archivedBeforeClear = false
                    val staged = mutableSetOf<Int>()
                    f.probe.before = { call -> if (call.sql == TestActiveRecurrentSqlV1.request) {
                        val holder = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                        val archive = holder.queryForMap("SELECT checkpoint_bytes,checkpoint_hash FROM complaint_test_active_checkpoint_history WHERE data_scope_id=? AND ordinal=1", f.scope)
                        assertArrayEquals(initialBytes, archive["checkpoint_bytes"] as ByteArray)
                        assertArrayEquals(initialHash, archive["checkpoint_hash"] as ByteArray)
                        assertArrayEquals(initialBytes, holder.queryForMap("SELECT checkpoint_bytes FROM complaint_journal_control WHERE data_scope_id=?", f.scope)["checkpoint_bytes"] as ByteArray)
                        archivedBeforeClear = true // Exact physical archive already exists in this actual REQUEST transaction.
                    } }
                    f.probe.after = { call -> if (call.sql == TestActiveRecurrentScanSqlV1.completeRun) {
                        val holder = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                        val pass = f.passNumber(); staged.add(pass)
                        val run = holder.queryForMap("SELECT * FROM complaint_journal_scan_runs WHERE data_scope_id=? AND pass=?", f.scope, pass)
                        assertEquals(1L, run["entry_count"]); assertEquals("COMPLETE", run["state"])
                        assertEquals(4_416L, run["active_recurrent_storage_bytes"])
                        assertNull(run["active_initial_seal_token"])
                        assertEquals(1L, holder.queryForObject("SELECT count(*) FROM complaint_journal_scan_entries WHERE data_scope_id=? AND pass=? AND replay_state='APPLIED'",
                            Long::class.java, f.scope, pass))
                    } }
                    stage = "BEGIN"
                    val original = f.begin() // Same single begin formerly evaluated as checkpoint's default argument.
                    stage = "CHECKPOINT"
                    val result = f.checkpoint(original)
                    stage = "ASSERTIONS"
                    val completed = assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, result)
                    f.assertSuccessful()
                    assertTrue(archivedBeforeClear); assertEquals(setOf(1, 2), staged)
                    assertEquals(2L, completed.cutoffEpoch); assertEquals(f.scope, completed.scope)
                    assertEquals(3L, f.control()["publication_epoch"])
                    val history = f.history()
                    assertEquals(2, history.size)
                    assertArrayEquals(initialBytes, history[0]["checkpoint_bytes"] as ByteArray)
                    assertArrayEquals(initialHash, history[0]["checkpoint_hash"] as ByteArray)
                    assertArrayEquals(f.control()["checkpoint_bytes"] as ByteArray, history[1]["checkpoint_bytes"] as ByteArray)
                    assertEquals(oldSeal, f.immutableImage().getValue("complaint_test_active_seal_intents"))
                    assertEquals(domain, f.domainImage())
                    f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2))
                    val doc = f.document()
                    assertEquals(1L, doc.objectCount); assertEquals(f.record.stored.bytes.size.toLong(), doc.byteCount)
                    assertEquals(completed.checkpointSha256, Sha256.hex(doc.canonicalBytes()))
                    assertEquals(listOf("STS", "PASS1", "GET1", "DECRYPT", "PASS2", "GET2", "DECRYPT"), f.raw.order)
                    assertEquals(listOf(0L, 1L), doc.ranges.map { it.eventCount })
                    assertEquals(manifest(f, 2, 2, listOf(f.record.stored.key to f.record.stored.version)).first, doc.ranges[1].manifestSha256)
                    assertEquals(manifest(f, 1, 2, listOf(f.record.stored.key to f.record.stored.version)).first, doc.first.manifestSha256)
                    val beforeRepeat = f.image(); val calls = f.probe.calls.size
                    assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(checkNotNull(f.original)) }
                    assertEquals(beforeRepeat, f.image()); assertEquals(calls, f.probe.calls.size)
                } catch (failure: TestActiveRecurrentExceptionV1) {
                    report(failure) // Before fixture unwinding; expected refusals stay inside assertThrows.
                    throw failure
                } finally { stage = "TEARDOWN" }
            }
        } catch (failure: TestActiveRecurrentExceptionV1) {
            if (reportedFailure !== failure) report(failure)
            throw failure
        }
    }

    fun nextRangeStillReadsEveryPriorNonemptyRange(tls: VersionBoundPersistenceConnectedFixture) = withRecurrentFixture(tls) { f ->
        assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint()); f.assertSuccessful()
        val priorBytes = (f.control().getValue("checkpoint_bytes") as ByteArray).copyOf()
        val priorHistory = f.history().map { it.getValue("entry_bytes") as ByteArray }
        val before = f.counters(); val domain = f.domainImage()
        val firstToken = f.intents().single().getValue("operation_token")
        val nativePuts = f.first.native.requests.count { it.kind == "PUT" }
        assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint()); f.assertSuccessful()
        val doc = f.document()
        assertEquals(3L, doc.cutoffEpoch); assertEquals(4L, f.control()["publication_epoch"])
        assertEquals(listOf(0L, 1L, 0L), doc.ranges.map { it.eventCount })
        assertEquals(1L, doc.objectCount)
        assertEquals(Sha256.hex(priorBytes), doc.predecessorCheckpointSha256)
        assertEquals(firstToken, f.intents().first().getValue("operation_token"))
        assertEquals(3, f.history().size)
        priorHistory.forEachIndexed { index, bytes -> assertArrayEquals(bytes, f.history()[index]["entry_bytes"] as ByteArray) }
        assertArrayEquals(priorBytes, f.history()[1]["checkpoint_bytes"] as ByteArray)
        assertEquals(nativePuts + 1, f.first.native.requests.count { it.kind == "PUT" })
        assertEquals(4, f.raw.requests.count { it.kind == "GET" }, "Two full nonempty reads on BOTH rotations, not just the new empty range.")
        assertEquals(domain, f.domainImage())
        f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY)
    }

    fun missingApplyIsOnlyPrivateRecoveryInput(tls: VersionBoundPersistenceConnectedFixture) = withRecurrentFixture(tls, applied = false) { f ->
        val before = f.counters(); val domain = f.domainImage()
        val original = f.begin()
        val result = assertInstanceOf(TestActiveRecurrentV1.RecoveryRequired::class.java, f.checkpoint(original))
        f.assertReleased()
        val input = result.input()
        assertSame(f.registration, input.registration); assertSame(f.assembly, input.assembly)
        assertSame(f.process.pools, input.resources); input.requireCurrentUse(f.registration, f.assembly, f.process.consumers.journalRouting)
        assertEquals(f.record.stored.version, input.versionId)
        assertEquals(Sha256.hex(f.record.stored.bytes), input.wireSha256)
        assertEquals(f.record.event.semanticSha256, input.event.semanticSha256)
        assertEquals(listOf(1), f.scans().map { (it["pass"] as Number).toInt() })
        assertEquals("PENDING", f.entries().single()["replay_state"])
        assertNull(f.control()["checkpoint_result"]); assertNull(f.history().last()["checkpoint_bytes"])
        assertEquals(0L, f.queue.count("complaint_deletion_journal_applied"))
        assertEquals(domain, f.domainImage())
        f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2) +
            TestActiveRecurrentStorageV1.SCAN_RUN + TestActiveRecurrentStorageV1.SCAN_ENTRY)
        val native = f.raw.order.toList()
        assertSame(result, result.resume(), "No Boolean or the input itself can become APPLY completion.")
        assertEquals(native, f.raw.order); assertNull(f.control()["checkpoint_result"])
        assertThrows<TestActiveRecurrentExceptionV1> { original.close() }
        assertThrows<TestActiveRecurrentExceptionV1> { result.input() }
        assertThrows<TestActiveRecurrentExceptionV1> { result.resume() }
        assertEquals(domain, f.domainImage())
    }

    fun genuinePreparedPublicationIsResolvedWithoutInventingApply(tls: VersionBoundPersistenceConnectedFixture) =
        withRecurrentFixture(tls, applied = false, verified = false) { f ->
            fun immutablePublication() = f.observer.queryForObject(
                "SELECT (to_jsonb(p)-ARRAY['state','object_version','ciphertext_hash','object_created_at','retain_until','verified_at','verification_bytes','verification_hash'])::text " +
                    "FROM complaint_journal_publications p WHERE data_scope_id=? AND event_id=?", String::class.java, f.scope, f.record.event.route.eventId)
            val immutable = immutablePublication()
            val domain = f.domainImage() - "complaint_journal_publications"
            val before = f.counters()
            val publisher = f.raw.deletion.publisher
            val gets = publisher.requests.count { it.kind == "GET" }
            val puts = publisher.requests.count { it.kind == "PUT" }
            val generated = publisher.generated()
            assertEquals("PREPARED", f.precursor.publication()["state"])
            assertNull(f.precursor.publication()["verification_bytes"])
            val result = assertInstanceOf(TestActiveRecurrentV1.RecoveryRequired::class.java, f.checkpoint())
            f.assertReleased()
            val publication = f.precursor.publication()
            assertEquals("VERIFIED", publication["state"])
            assertEquals(immutable, immutablePublication())
            assertEquals(f.record.stored.version, publication["object_version"])
            assertArrayEquals(recurrentBytes(Sha256.hex(f.record.stored.bytes)), publication["ciphertext_hash"] as ByteArray)
            val proof = publication["verification_bytes"] as ByteArray
            assertArrayEquals(recurrentBytes(Sha256.hex(proof)), publication["verification_hash"] as ByteArray)
            assertNull(publication["applied_at"])
            assertEquals(puts, publisher.requests.count { it.kind == "PUT" }, "The genuine preexisting object is reread rather than re-encrypted.")
            assertTrue(publisher.requests.count { it.kind == "GET" } > gets)
            assertEquals(generated, publisher.generated())
            assertTrue(f.probe.calls.any { it.step === TestActiveRecurrentStepV1.EVIDENCE && it.sql == TestActiveCutoffPublicationSqlV1.verified })
            assertEquals(0L, f.queue.count("complaint_deletion_journal_applied"))
            assertTrue(f.raw.queue.sqs.requests.isEmpty(), "Local PREPARED evidence is not a forged queue delivery.")
            assertEquals("PENDING", f.entries().single()["replay_state"])
            assertEquals(f.record.event.semanticSha256, result.input().event.semanticSha256)
            assertNull(f.control()["checkpoint_result"]); assertNull(f.history().last()["checkpoint_bytes"])
            assertEquals(domain, f.domainImage() - "complaint_journal_publications")
            f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2) +
                TestActiveRecurrentStorageV1.SCAN_RUN + TestActiveRecurrentStorageV1.SCAN_ENTRY)
            assertThrows<TestActiveRecurrentExceptionV1> { checkNotNull(f.original).close() }
            assertThrows<TestActiveRecurrentExceptionV1> { result.resume() }
        }

    fun captureWaitUsesFreshDatabaseTimeAndNoPooledHolderAcrossExclusiveEpoch(tls: VersionBoundPersistenceConnectedFixture) = withRecurrentFixture(tls) { f ->
        val ready = CountDownLatch(1)
        val stop = AtomicBoolean()
        OwnedCallerTestScope().use { callers ->
            val worker = callers.launch { f.first.independentTransaction { blocker, jdbc, holderPid ->
                jdbc.execute("SELECT pg_advisory_xact_lock_shared(hashtextextended('complaint-journal-epoch',0))")
                ready.countDown()
                var waitingPid: Int? = null
                awaitLifecycleFact(6_000) {
                    if (!stop.get()) {
                        jdbc.execute("SELECT pg_stat_clear_snapshot()") // This same blocker transaction must see the newly opened NONPOOLED waiter.
                        waitingPid = jdbc.query("SELECT a.pid FROM pg_stat_activity a WHERE a.datname=current_database() AND ?=ANY(pg_blocking_pids(a.pid)) " +
                            "AND EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid=a.pid AND l.locktype='advisory' AND l.mode='ExclusiveLock' AND NOT l.granted)",
                            { row, _ -> row.getInt(1) }, holderPid).singleOrNull()
                    }
                    stop.get() || waitingPid != null
                }
                check(!stop.get()) { "Recurrent producer stopped before its actual exclusive epoch wait." }
                assertEquals(0, f.runtime.pools.catalogCoordinator.activeSnapshotOwners())
                assertEquals("REQUESTED", f.control()["rotation_state"])
                assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE pid=? AND locktype='tuple'", Long::class.java, waitingPid))
                assertFalse(checkNotNull(waitingPid) in f.probe.observations.values.map { it.identity.first })
                val releasedAt = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() }))
                blocker.rollback()
                releasedAt
            } }
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint())
                val releasedAt = worker.value()
                f.assertSuccessful()
                assertTrue((f.intents().single()["captured_at"] as Timestamp).toInstant() >= releasedAt)
                assertTrue(f.nativeSessions.isNotEmpty())
            } finally { stop.set(true) }
        }
    }

    /** Independent LP32 oracle, not the producer manifest/fold implementation. */
    internal fun manifest(f: TestActiveRecurrentFixtureV1, start: Long, end: Long, objects: List<Pair<String, String>>): Pair<String, Long> {
        val fields = listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest",
            f.process.consumers.journalConfiguration.declaration().writer.generationId,
            f.process.consumers.journalConfiguration.ordinaryPrefix, "TEST", f.scope.toString(), start.toString(), end.toString(), objects.size.toString()) +
            objects.flatMap { listOf(it.first, it.second, Sha256.hex(f.record.stored.bytes)) }
        val bytes = ByteArrayOutputStream().use { stream ->
            DataOutputStream(stream).use { output -> fields.forEach { value -> val utf8 = value.toByteArray(); output.writeInt(utf8.size); output.write(utf8) } }
            stream.toByteArray()
        }
        return Sha256.hex(bytes) to bytes.size.toLong()
    }
}
