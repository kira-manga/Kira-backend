package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.DeleteAllCounter
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.infrastructure.AdminDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplySql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.journal.JournalPublisherObject
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.sql.SQLException
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

internal enum class RecurrentSealRecoveryCut { CANONICAL, FROZEN_NATIVE }
internal enum class RecurrentPaidPrefixCut { FIRST_STAGED, PAIR_COMPLETE, FIRST_REFUND_COMMITTED }
internal enum class RecurrentApplyBookkeepingCut { MISSING_N, MISSING_L, MISSING_NPL }
internal enum class RecurrentRetainedAllInventoryCut { MISSING_ALIAS, ALIAS_VERSION, PRIMARY_OVERLAP_HASH, FOREIGN_ALIAS, PRIMARY_ROW_ABA, ALIAS_ROW_ABA }
internal enum class RecurrentRetainedAllHistoryCut { MISSING_N, MISSING_L, ORPHAN_E, TORN_USED }

/** First missing-primary APPLY retains its live original; failed/UNKNOWN originals still require a DIFFERENT original after lease expiry. */
internal object TestActiveRecurrentRecoveryCasesV1 {
    /** One real A producer + labeled protocol history, actual B alias APPLY, then original-bound Split2. */
    fun retainedAllAliasThenActualPrimary(tls: VersionBoundPersistenceConnectedFixture) =
        withRecurrentFixture(tls, ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, applied = false, retainedAllAlias = true, maximumVersions = 2) { f ->
            val alias = checkNotNull(f.historicalAll)
            val before = f.counters(); val audits = f.queue.audits(); val erased = allErasedImage(f)
            val primary = f.queue.domainImage().filterKeys { it in setOf("complaint_journal_publications", "installation_deletion_receipts") }
            val aliasMarker = f.queue.domainImage().getValue("complaint_deletion_journal_applied").single()
            val initial = f.immutableImage().getValue("complaint_test_active_seal_intents")
            val initialCheckpoint = (f.control().getValue("checkpoint_bytes") as ByteArray).copyOf()
            val queueNative = f.raw.queue.order.toList(); val aPuts = f.raw.deletion.publisher.requests.count { it.kind == "PUT" }
            val aKeys = f.raw.deletion.publisher.generated()
            f.queue.expectAppliedObjects(alias.stored, f.record.stored) // Assertion only; nothing is supplied to MAIN.
            val staged = linkedSetOf<Int>()
            f.probe.after = { call -> if (call.sql == TestActiveRecurrentScanSqlV1.completeRun) {
                val holder = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                val pass = f.passNumber(); staged.add(pass)
                assertEquals(2L, holder.queryForObject("SELECT entry_count FROM complaint_journal_scan_runs WHERE data_scope_id=? AND pass=?", Long::class.java, f.scope, pass))
                assertEquals(if (pass == 1) 1L else 2L, holder.queryForObject(
                    "SELECT count(*) FROM complaint_journal_scan_entries WHERE data_scope_id=? AND pass=? AND replay_state='APPLIED'", Long::class.java, f.scope, pass))
            } }
            val waiting = assertInstanceOf(TestActiveRecurrentV1.RecoveryRequired::class.java, f.checkpoint())
            val input = waiting.input()
            assertEquals(f.record.stored.key, input.event.route.objectKey); assertEquals(f.record.stored.version, input.versionId)
            assertEquals(Sha256.hex(f.record.stored.bytes), input.wireSha256)
            assertEquals(primary, f.queue.domainImage().filterKeys { it in primary.keys })
            assertEquals(1L, f.queue.count("complaint_deletion_journal_applied")); assertTrue(f.applyCalls.isEmpty())
            assertNull(f.control()["checkpoint_result"])
            var rechecked = false
            f.probe.before = { call -> if (!rechecked && call.step == TestActiveRecurrentStepV1.RECHECK_ENTRY && f.applyObservations.isNotEmpty()) {
                val applied = f.applyObservations.keys.single()
                val leaf = ownedCutField(applied, "testRecurrentApply") as TestActiveRecurrentApplyV1
                assertSame(input, leaf.input); assertSame(f.original, leaf.original)
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, applied.databaseOutcome()); assertTrue(applied.testActiveRecurrentApplyCleanupProven(leaf))
                val holder = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                assertEquals(2L, holder.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id=?", Long::class.java, f.scope))
                assertEquals("PENDING", holder.queryForObject("SELECT replay_state FROM complaint_journal_scan_entries WHERE data_scope_id=? AND pass=1 AND object_key=? AND object_version=?",
                    String::class.java, f.scope, f.record.stored.key, f.record.stored.version))
                assertNull(holder.queryForObject("SELECT checkpoint_result FROM complaint_journal_control WHERE data_scope_id=?", String::class.java, f.scope))
                rechecked = true
            } }
            assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.apply(waiting))
            f.assertSuccessful(); assertTrue(rechecked); assertEquals(setOf(1, 2), staged); assertEquals(1, f.applyObservations.size)
            f.queue.assertExpectedAppliedObjects(); f.queue.assertOnlyAuthorizedReportsErased()
            assertEquals(erased, allErasedImage(f), "No second erasure, domain xmin rewrite or credential TTL extension.")
            assertTrue(aliasMarker in f.queue.domainImage().getValue("complaint_deletion_journal_applied"))
            assertEquals("APPLIED", f.precursor.publication()["state"]); assertEquals("COMPLETED", f.queue.receipt()["state"])
            assertEquals(f.record.stored.version, f.queue.receipt()["external_object_version"])
            assertEquals(f.record.event.route.eventId, f.queue.receipt()["external_event_id"])
            assertEquals(f.queue.proofBeforeQueue, f.queue.publicationProof()); assertEquals(f.queue.receiptBeforeQueue, f.queue.receiptIdentity())
            assertEquals(audits + ("COMPLAINT_RECOVERY_APPLIED" to (audits.getOrDefault("COMPLAINT_RECOVERY_APPLIED", 0L) + 1)), f.queue.audits())
            val use = OwnerDeleteLiteralCharges.appliedOnly + OwnerDeleteLiteralCharges.audit
            assertRetainedAllTransfer(f, before, use)
            assertEquals((materialized(f) + use).toLongArray().joinToString(",", "{", "}"), f.observer.queryForObject(
                "SELECT converted_amounts::text FROM complaint_recovery_capacity_reservations WHERE data_scope_id=? AND event_id=? AND state='PARTIAL'",
                String::class.java, f.scope, f.record.event.route.eventId))
            val sql = OwnerDeleteAllApplySql.test(f.precursor.dataScope)
            assertEquals(1, f.applyCalls.count { it.sql == sql.INSERT_APPLIED })
            assertEquals(1, f.applyCalls.count { it.sql == sql.COMPLETE_RECEIPT }); assertEquals(1, f.applyCalls.count { it.sql == sql.MARK_APPLIED })
            assertFalse(f.applyCalls.any { it.sql in setOf(sql.DELETE_CONTENT, sql.DELETE_CREDENTIAL, sql.DELETE_INSTALLATION) })
            assertFalse(f.applyCalls.any { it.sql == TestActiveRecurrentScanSqlV1.markApplied || it.sql == TestActiveRecurrentSqlV1.success })
            assertEquals(initial, f.immutableImage().getValue("complaint_test_active_seal_intents"))
            assertArrayEquals(initialCheckpoint, f.history().first()["checkpoint_bytes"] as ByteArray)
            assertRetainedAllNativeCoverage(f, replayedPrimary = true)
            assertEquals(queueNative, f.raw.queue.order); assertEquals(aPuts, f.raw.deletion.publisher.requests.count { it.kind == "PUT" }); assertEquals(aKeys, f.raw.deletion.publisher.generated())
        }

    fun retainedAllAlreadyAppliedFamily(tls: VersionBoundPersistenceConnectedFixture) =
        withRecurrentFixture(tls, ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, retainedAllAlias = true, maximumVersions = 2) { f ->
            val before = f.counters(); val domain = f.domainImage(); val queueNative = f.raw.queue.order.toList()
            val initial = f.immutableImage().getValue("complaint_test_active_seal_intents")
            assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint())
            f.assertSuccessful(); assertTrue(f.applyCalls.isEmpty() && f.applyObservations.isEmpty())
            f.queue.assertExpectedAppliedObjects(); assertEquals(domain, f.domainImage())
            assertRetainedAllTransfer(f, before, ComplaintCapacityVector.ZERO)
            assertEquals(initial, f.immutableImage().getValue("complaint_test_active_seal_intents"))
            assertRetainedAllNativeCoverage(f, replayedPrimary = false); assertEquals(queueNative, f.raw.queue.order)
        }

    /** Missing/drifting physical history is never filled by a plausible expected-set row. */
    fun retainedAllInventoryCannotHideDrift(tls: VersionBoundPersistenceConnectedFixture, cut: RecurrentRetainedAllInventoryCut) =
        withRecurrentFixture(tls, ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, retainedAllAlias = true) { f ->
            val alias = checkNotNull(f.historicalAll).stored
            var changed = false
            when (cut) {
                RecurrentRetainedAllInventoryCut.MISSING_ALIAS, RecurrentRetainedAllInventoryCut.ALIAS_VERSION ->
                    f.raw.listing = { _, values -> changed = true; if (cut == RecurrentRetainedAllInventoryCut.MISSING_ALIAS) values.filterNot { it.key == alias.key }
                        else values.map { if (it.key == alias.key) it.copy(version = "hostile-different-alias-version") else it } }
                RecurrentRetainedAllInventoryCut.PRIMARY_OVERLAP_HASH -> {
                    assertEquals(1, f.observer.update("UPDATE complaint_deletion_journal_applied SET ciphertext_hash=? WHERE object_key=? AND object_version=?",
                        ByteArray(32) { 9 }, f.record.stored.key, f.record.stored.version)); changed = true
                }
                RecurrentRetainedAllInventoryCut.FOREIGN_ALIAS -> {
                    assertEquals(1, f.observer.update("UPDATE complaint_deletion_journal_applied SET data_scope_id=? WHERE object_key=? AND object_version=?", UUID.randomUUID(), alias.key, alias.version))
                    changed = true
                }
                RecurrentRetainedAllInventoryCut.PRIMARY_ROW_ABA, RecurrentRetainedAllInventoryCut.ALIAS_ROW_ABA ->
                    f.probe.after = { call -> if (!changed && call.sql == TestActiveRecurrentSqlV1.manifestPage) {
                        // Same real holder after the first bounded page was read: semantic bytes
                        // stay identical but the physical xmin changes before pass two.
                        val holder = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                        val count = if (cut == RecurrentRetainedAllInventoryCut.PRIMARY_ROW_ABA)
                            holder.update("UPDATE complaint_journal_publications SET state=state WHERE event_id=?", f.record.event.route.eventId)
                        else holder.update("UPDATE complaint_deletion_journal_applied SET applied_at=applied_at WHERE object_key=? AND object_version=?", alias.key, alias.version)
                        assertEquals(1, count); changed = true
                    } }
            }
            val domain = f.domainImage(); val initial = f.immutableImage().getValue("complaint_test_active_seal_intents")
            val initialCheckpoint = (f.control().getValue("checkpoint_bytes") as ByteArray).copyOf()
            try {
                assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint() }
                f.assertReleased(); assertTrue(changed); assertTrue(f.applyCalls.isEmpty())
                assertNull(f.control()["checkpoint_result"])
                assertEquals(initial, f.immutableImage().getValue("complaint_test_active_seal_intents"))
                assertFalse(f.probe.calls.any { it.step == TestActiveRecurrentStepV1.SUCCESS })
                // REQUEST archives INITIAL; only VERIFY adds the uncheckpointed recurrent row.
                val history = f.history()
                if (cut in setOf(RecurrentRetainedAllInventoryCut.MISSING_ALIAS, RecurrentRetainedAllInventoryCut.ALIAS_VERSION)) {
                    assertEquals(2, history.size); assertEquals("V31_RECURRENT", history.last()["source"])
                    assertNull(history.last()["checkpoint_bytes"])
                    assertEquals(domain, f.domainImage()); assertTrue(f.raw.order.contains("PASS1")); assertFalse(f.raw.order.contains("PASS2"))
                } else {
                    assertEquals(1, history.size)
                    assertTrue(f.raw.order.isEmpty() && f.scans().isEmpty(), "Conflicting overlap/foreign row/physical drift cannot freeze or scan a manifest.")
                    assertEquals("RESERVED", f.intents().single()["state"])
                    assertFalse(f.probe.calls.any { it.step == TestActiveRecurrentStepV1.CANONICAL })
                }
                assertEquals("V26_INITIAL", history.first()["source"])
                assertArrayEquals(initialCheckpoint, history.first()["checkpoint_bytes"] as ByteArray)
            } finally {
                // Restore only the explicitly foreign row for disposable fixture teardown; no retry.
                if (cut == RecurrentRetainedAllInventoryCut.FOREIGN_ALIAS)
                    assertEquals(1, f.observer.update("UPDATE complaint_deletion_journal_applied SET data_scope_id=? WHERE object_key=? AND object_version=?", f.scope, alias.key, alias.version))
            }
        }

    /** Actual alias E already exists; native authentication must not turn missing/torn N/P/L/U into coverage. */
    fun retainedAllHistoryMustRemainWhole(tls: VersionBoundPersistenceConnectedFixture, cut: RecurrentRetainedAllHistoryCut) =
        withRecurrentFixture(tls, ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, applied = false, retainedAllAlias = true) { f ->
            f.queue.adversarialTransaction { jdbc ->
                if (cut in setOf(RecurrentRetainedAllHistoryCut.MISSING_N, RecurrentRetainedAllHistoryCut.ORPHAN_E))
                    assertEquals(1, jdbc.update("DELETE FROM installation_deletion_receipts WHERE data_scope_id=? AND publication_ref=?", f.scope, f.record.event.route.eventId))
                if (cut in setOf(RecurrentRetainedAllHistoryCut.MISSING_L, RecurrentRetainedAllHistoryCut.ORPHAN_E))
                    assertEquals(1, jdbc.update("DELETE FROM complaint_recovery_capacity_reservations WHERE data_scope_id=? AND event_id=?", f.scope, f.record.event.route.eventId))
                if (cut == RecurrentRetainedAllHistoryCut.ORPHAN_E)
                    assertEquals(1, jdbc.update("DELETE FROM complaint_journal_publications WHERE data_scope_id=? AND event_id=?", f.scope, f.record.event.route.eventId))
                if (cut == RecurrentRetainedAllHistoryCut.TORN_USED) {
                    val storage = ComplaintCapacityCounter.STORAGE_BYTES.storedOrdinal
                    assertEquals(1, jdbc.update("UPDATE complaint_recovery_capacity_reservations SET converted_amounts[?]=converted_amounts[?]+1 WHERE data_scope_id=? AND event_id=?",
                        storage, storage, f.scope, f.record.event.route.eventId))
                }
            }
            val domain = f.domainImage(); val counters = f.counters()
            assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint() }
            f.assertReleased(); assertEquals(domain, f.domainImage()); assertTrue(f.applyCalls.isEmpty())
            assertEquals(1L, f.queue.count("complaint_deletion_journal_applied"))
            assertTrue(f.entries().all { it["replay_state"] == "PENDING" && it["object_key"] == f.record.stored.key },
                "A lexically earlier pending primary may be staged, but the torn alias cannot become APPLIED coverage.")
            assertNull(f.control()["checkpoint_result"]); assertNull(f.history().last()["checkpoint_bytes"])
            assertTrue(f.raw.order.contains("GET1") && f.raw.order.contains("DECRYPT")); assertFalse(f.raw.order.contains("PASS2"))
            assertTrue(f.probe.calls.any { it.step == TestActiveRecurrentStepV1.APPEND && it.sql == TestActiveRecurrentSqlV1.retainedAllPublication(f.precursor.dataScope) })
            assertFalse(f.probe.calls.any { it.sql == TestActiveRecurrentScanSqlV1.markApplied || it.step == TestActiveRecurrentStepV1.SUCCESS })
            assertTrue(f.probe.observations.keys.any { it.databaseOutcome() == PersistenceDatabaseOutcome.ROLLED_BACK })
            f.assertCharge(counters, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2) +
                TestActiveRecurrentStorageV1.scanCharge(f.scans().size.toLong(), f.entries().size.toLong()))
        }

    private fun allErasedImage(f: TestActiveRecurrentFixtureV1) = f.domainImage().filterKeys {
        it in setOf("complaint_installation_ids", "app_installations", "complaint_resource_ids", "complaints")
    }
    private fun assertRetainedAllTransfer(f: TestActiveRecurrentFixtureV1, before: Map<ComplaintCapacityCounter, DeleteAllCounter>, use: ComplaintCapacityVector) {
        val charge = TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2)
        val after = f.counters()
        before.forEach { (counter, old) -> assertEquals(old.copy(free = old.free - charge[counter], actual = old.actual + charge[counter] + use[counter],
            recovery = old.recovery - use[counter]), after.getValue(counter), counter.storedName) }
    }
    private fun assertRetainedAllNativeCoverage(f: TestActiveRecurrentFixtureV1, replayedPrimary: Boolean) {
        val objects = listOf(f.record.stored, checkNotNull(f.historicalAll).stored).sortedBy { it.key }
        val doc = f.document()
        assertEquals(2L, doc.objectCount); assertEquals(objects.sumOf { it.bytes.size.toLong() }, doc.byteCount)
        assertEquals(listOf(0L, 2L), doc.ranges.map { it.eventCount })
        assertEquals(retainedAllManifest(f, 2, objects), doc.ranges.last().manifestSha256)
        assertEquals(retainedAllManifest(f, 1, objects), doc.first.manifestSha256)
        assertEquals(doc.first.manifestSha256, doc.second.manifestSha256)
        assertEquals(1, f.raw.order.count { it == "PASS1" }); assertEquals(1, f.raw.order.count { it == "PASS2" })
        assertEquals(if (replayedPrimary) 3 else 2, f.raw.order.count { it == "GET1" }); assertEquals(2, f.raw.order.count { it == "GET2" })
        assertEquals(if (replayedPrimary) 5 else 4, f.raw.order.count { it == "DECRYPT" })
        assertEquals(1L, f.queue.count("complaint_journal_publications"))
    }
    /** Independent LP32 framing over each object's OWN ciphertext, not the MAIN union/builder. */
    private fun retainedAllManifest(f: TestActiveRecurrentFixtureV1, start: Int, objects: List<JournalPublisherObject>): String {
        val fields = listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest", f.process.consumers.journalConfiguration.declaration().writer.generationId,
            f.process.consumers.journalConfiguration.ordinaryPrefix, "TEST", f.scope.toString(), start.toString(), "2", objects.size.toString()) +
            objects.flatMap { listOf(it.key, it.version, Sha256.hex(it.bytes)) }
        val bytes = ByteArrayOutputStream().use { stream ->
            DataOutputStream(stream).use { output -> fields.forEach { value -> val encoded = value.toByteArray(Charsets.UTF_8); output.writeInt(encoded.size); output.write(encoded) } }
            stream.toByteArray()
        }
        return Sha256.hex(bytes)
    }

    fun firstMissingPrimary(tls: VersionBoundPersistenceConnectedFixture, family: ComplaintJournalDeletionKindV1) =
        withRecurrentFixture(tls, family, applied = false) { f ->
            val before = f.counters()
            assertEquals("AUTHORIZED_DELETE", f.queue.receipt()["state"])
            assertEquals("VERIFIED", f.precursor.publication()["state"])
            assertEquals(0L, f.queue.count("complaint_deletion_journal_applied"))
            val waiting = assertInstanceOf(TestActiveRecurrentV1.RecoveryRequired::class.java, f.checkpoint())
            f.assertReleased()
            val token = (f.control().getValue("lease_token") as Number).toLong()
            val input = waiting.input()
            var independentlyRechecked = false
            f.probe.before = { call ->
                if (!independentlyRechecked && call.step == TestActiveRecurrentStepV1.RECHECK_ENTRY && f.applyObservations.isNotEmpty()) {
                    val applied = f.applyObservations.keys.single()
                    val leaf = ownedCutField(applied, "testRecurrentApply") as TestActiveRecurrentApplyV1
                    assertSame(input, leaf.input)
                    assertEquals(PersistenceDatabaseOutcome.COMMITTED, applied.databaseOutcome())
                    assertTrue(applied.testActiveRecurrentApplyCleanupProven(leaf))
                    // Same real coordinator holder: APPLY completed but has NOT marked its staging
                    // row or checkpoint. Only this later independent producer recheck may proceed.
                    val jdbc = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id=?", Long::class.java, f.scope))
                    assertEquals("PENDING", jdbc.queryForObject("SELECT replay_state FROM complaint_journal_scan_entries WHERE data_scope_id=? AND pass=1", String::class.java, f.scope))
                    assertNull(jdbc.queryForObject("SELECT checkpoint_result FROM complaint_journal_control WHERE data_scope_id=?", String::class.java, f.scope))
                    independentlyRechecked = true
                }
            }
            val completed = assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.apply(waiting))
            assertTrue(independentlyRechecked); assertEquals(token, completed.fencingToken, "No per-event lease or original is acquired.")
            f.assertSuccessful(); assertEquals(1, f.applyObservations.size)
            assertPrimaryApplied(f)
            assertTransfer(f, before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2))
            assertFalse(f.applyCalls.any { it.sql == TestActiveRecurrentScanSqlV1.markApplied || it.sql == TestActiveRecurrentSqlV1.success })
            assertTrue(f.probe.calls.any { it.step == TestActiveRecurrentStepV1.RECHECK_ENTRY && it.sql == TestActiveRecurrentScanSqlV1.markApplied })
            assertEquals(listOf("STS", "PASS1", "GET1", "DECRYPT", "GET1", "DECRYPT", "PASS2", "GET2", "DECRYPT"), f.raw.order)
            assertTrue(f.raw.queue.order.isEmpty() && f.raw.queue.sqs.requests.isEmpty() && f.raw.queue.ackRequests.isEmpty())
        }

    /** Existing actual PG/Spring cuts, not a claimed lost-TLS-COMMIT-response qualification. */
    fun missingPrimaryCommit(tls: VersionBoundPersistenceConnectedFixture, family: ComplaintJournalDeletionKindV1, cut: RecurrentCommitCut) =
        withRecurrentFixture(tls, family, applied = false) { f ->
            require(cut in setOf(RecurrentCommitCut.BEFORE_COMMIT, RecurrentCommitCut.AFTER_COMMIT, RecurrentCommitCut.DEFERRED_COMMIT_UNKNOWN))
            val waiting = assertInstanceOf(TestActiveRecurrentV1.RecoveryRequired::class.java, f.checkpoint())
            val domain = f.domainImage(); val counters = f.counters(); val native = f.raw.order.toList()
            var selected: PersistencePhaseContext? = null
            f.afterApply = { call -> if (selected == null && call.sql == appliedSql(f)) {
                selected = call.phase
                if (cut == RecurrentCommitCut.DEFERRED_COMMIT_UNKNOWN) {
                    val jdbc = JdbcTemplate(f.runtime.pools.deletion)
                    jdbc.execute("CREATE TEMP TABLE kira_recurrent_apply_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                    assertEquals(2, jdbc.update("INSERT INTO kira_recurrent_apply_commit_cut VALUES (1),(1)"))
                } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) {
                        if (cut == RecurrentCommitCut.BEFORE_COMMIT) error("Synthetic recurrent APPLY before-COMMIT refusal.")
                    }
                    override fun afterCommit() {
                        if (cut == RecurrentCommitCut.AFTER_COMMIT) error("Synthetic recurrent APPLY after-COMMIT refusal, not a dropped TLS response.")
                    }
                })
            } }
            assertThrows<TestActiveRecurrentExceptionV1> { f.apply(waiting) }
            f.afterApply = {}; f.assertReleased()
            val phase = checkNotNull(selected)
            val outcome = when (cut) {
                RecurrentCommitCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
                RecurrentCommitCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
                else -> PersistenceDatabaseOutcome.COMMITTED
            }
            assertEquals(outcome, phase.databaseOutcome())
            assertTrue(phase.testActiveRecurrentApplyCleanupProven(ownedCutField(phase, "testRecurrentApply") as TestActiveRecurrentApplyV1))
            assertEquals("PENDING", f.entries().single()["replay_state"])
            assertNull(f.control()["checkpoint_result"]); assertNull(f.history().last()["checkpoint_bytes"])
            assertFalse(f.probe.calls.any { it.sql == TestActiveRecurrentScanSqlV1.markApplied })
            assertEquals(native, f.raw.order, "No second pass follows failed/UNKNOWN APPLY.")
            if (outcome == PersistenceDatabaseOutcome.COMMITTED) {
                assertPrimaryApplied(f); assertTransfer(f, counters, ComplaintCapacityVector.ZERO)
            } else {
                assertEquals(domain, f.domainImage()); assertEquals(counters, f.counters())
                assertEquals(0L, f.queue.count("complaint_deletion_journal_applied"))
            }
            assertFailedBridgeRefused(f, waiting)
        }

    fun differentOriginalCannotAdoptPendingNativeInput(tls: VersionBoundPersistenceConnectedFixture) = withRecurrentFixture(tls, applied = false) { f ->
        val waiting = assertInstanceOf(TestActiveRecurrentV1.RecoveryRequired::class.java, f.checkpoint())
        val input = waiting.input()
        val other = TestActiveRecurrentV1.begin(f.registration, f.assembly)
        val rows = f.image(); val sql = f.probe.calls.size; val native = f.raw.order.toList()
        assertThrows<TestActiveRecurrentExceptionV1> { input.requireOriginal(other) }
        assertThrows<TestActiveRecurrentExceptionV1> {
            TestActiveRecurrentApplyV1.prepare(other, input, f.precursor.deletionOwner, f.precursor.deletion, f.precursor.audit)
        }
        assertEquals(rows, f.image()); assertEquals(sql, f.probe.calls.size); assertEquals(native, f.raw.order); assertTrue(f.applyCalls.isEmpty())
        assertThrows<TestActiveRecurrentExceptionV1> { other.close() }
        assertSame(input, waiting.input())
        assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.apply(waiting))
        f.assertSuccessful(); assertPrimaryApplied(f)
    }

    fun wrongDeletionTemplatePoisonsOnlyTheAttemptWithoutApplying(tls: VersionBoundPersistenceConnectedFixture) {
        var stage = "FIXTURE_SETUP"
        var bodyStage = stage
        var bodyFailure: Throwable? = null
        try {
            withRecurrentFixture(tls, applied = false) { f ->
                try {
                    stage = "INITIAL_CHECKPOINT"
                    val waiting = assertInstanceOf(TestActiveRecurrentV1.RecoveryRequired::class.java, f.checkpoint())
                    stage = "BASELINE"
                    val domain = f.domainImage(); val counters = f.counters(); val native = f.raw.order.toList()
                    stage = "EXPECTED_TEMPLATE_REFUSAL"
                    assertThrows<TestActiveRecurrentExceptionV1> {
                        waiting.apply(f.precursor.deletionOwner, JdbcTemplate(f.runtime.pools.ordinary), f.precursor.audit)
                    }
                    stage = "RELEASE_POSTCONDITIONS"
                    f.assertReleased(); assertTrue(f.applyCalls.isEmpty())
                    assertEquals(domain, f.domainImage()); assertEquals(counters, f.counters()); assertEquals(native, f.raw.order)
                    assertNull(f.control()["checkpoint_result"])
                    stage = "FAILED_ORIGINAL_REFUSAL"
                    assertFailedBridgeRefused(f, waiting)
                } catch (failure: Throwable) {
                    bodyStage = stage; bodyFailure = failure
                    throw failure
                } finally { stage = "FIXTURE_CLEANUP" }
            }
        } catch (failure: Throwable) {
            // Report only after existing teardown; retain the body stage if unwind replaces its exception.
            bodyFailure?.let { observeWrongTemplateFailure(bodyStage, it) }
            if (failure !== bodyFailure) observeWrongTemplateFailure(stage, failure)
            throw failure
        }
    }

    private fun observeWrongTemplateFailure(stage: String, failure: Throwable) {
        try {
            val category = when (failure) {
                is TestActiveRecurrentExceptionV1 -> "RECURRENT"
                is PersistencePhaseException -> "PHASE"
                is AssertionError -> "ASSERTION"
                else -> "OTHER"
            }
            val phaseCode = (failure as? PersistencePhaseException)?.code?.name ?: "NONE"
            println("TEST_ACTIVE_RECURRENT_WRONG_TEMPLATE_FAILURE stage=$stage category=$category phaseCode=$phaseCode")
        } catch (_: Throwable) { /* Diagnostics never replace the original failure or perform cleanup. */ }
    }

    /** Real ownership admission contention, after leaf admission but before any context or checkout. */
    fun unusedDeletionEntryRefusalReleasesCustodyButNeverRevivesTheOriginal(tls: VersionBoundPersistenceConnectedFixture) =
        withRecurrentFixture(tls, applied = false) { f ->
            val waiting = assertInstanceOf(TestActiveRecurrentV1.RecoveryRequired::class.java, f.checkpoint())
            val failed = checkNotNull(f.original); val input = waiting.input()
            val token = (f.control().getValue("lease_token") as Number).toLong()
            val domain = f.domainImage(); val counters = f.counters(); val native = f.raw.order.toList()
            val deletion = f.precursor.deletion.observations.toMap()
            val recipeSlot = ownedCutField(checkNotNull(f.process.activeRecurrent), "active") as AtomicReference<*>
            val readbackSlot = ownedCutField(f.runtime.pools.catalogCoordinator.catalogRefreshCustody, "active") as AtomicReference<*>
            assertSame(failed, recipeSlot.get()); assertSame(failed, readbackSlot.get())
            assertEquals(true, ownedCutField(failed, "nativeClaimed")); assertEquals(true, ownedCutField(failed, "readbackReserved"))
            val admissionCut = ownedCutField(f.precursor.deletionOwner, "admissionCut") as ReentrantLock
            OwnedCallerTestScope().use { callers ->
                val hold = callers.gate()
                val blocker = callers.launch {
                    assertTrue(admissionCut.tryLock())
                    try { hold.hold() } finally { admissionCut.unlock() }
                }
                try {
                    hold.awaitEntered()
                    assertTrue(admissionCut.isLocked); assertFalse(admissionCut.isHeldByCurrentThread)
                    assertThrows<TestActiveRecurrentExceptionV1> { f.apply(waiting) }
                    val leaf = ownedCutField(failed, "applying") as TestActiveRecurrentApplyV1
                    assertEquals(true, ownedCutField(leaf, "phaseEntryClaimed"))
                    // Refusal must occur with the captured allowance still live, not qualify a
                    // different earlier budget cut. This samples it; no deadline is replaced.
                    assertEquals(1L, (ownedCutField(leaf, "allowance") as PersistenceTimeBudget).remainingMillis(1))
                } finally { hold.release() }
                blocker.value()
            }
            val leaf = ownedCutField(failed, "applying") as TestActiveRecurrentApplyV1
            assertSame(failed, leaf.original); assertSame(input, leaf.input)
            assertEquals(true, ownedCutField(leaf, "started")); assertEquals(false, ownedCutField(leaf, "completed"))
            assertEquals(false, ownedCutField(leaf, "phaseEntryInFlight")); assertEquals(false, ownedCutField(leaf, "phaseEntered"))
            assertNull(ownedCutField(leaf, "phase")); assertEquals(false, ownedCutField(leaf, "cleanupUncertain"))
            leaf.requirePhysicalReleased(); f.assertReleased()
            assertTrue(f.applyCalls.isEmpty() && f.applyObservations.isEmpty()); assertEquals(deletion, f.precursor.deletion.observations)
            assertEquals(domain, f.domainImage()); assertEquals(counters, f.counters()); assertEquals(native, f.raw.order)
            assertEquals(0L, f.queue.count("complaint_deletion_journal_applied"))
            assertEquals("PENDING", f.entries().single()["replay_state"])
            assertNull(f.control()["checkpoint_result"]); assertNull(f.history().last()["checkpoint_bytes"])
            assertFalse(f.probe.calls.any { it.sql == TestActiveRecurrentScanSqlV1.markApplied })
            assertEquals(false, ownedCutField(failed, "nativeClaimed")); assertEquals(false, ownedCutField(failed, "readbackReserved"))
            assertEquals(true, ownedCutField(failed, "cleanupProven")); assertNull(recipeSlot.get()); assertNull(readbackSlot.get())
            assertFailedBridgeRefused(f, waiting)

            val lease = f.control()
            assertEquals(token, (lease.getValue("lease_token") as Number).toLong())
            assertEquals(failed.attemptId, lease["lease_owner"]); assertTrue(lease["lease_expires_at"] is Timestamp)
            awaitInitialCheckpointLeaseExpiry(f.observer, f.scope)
            val recovered = assertInstanceOf(TestActiveRecurrentV1.RecoveryRequired::class.java, f.checkpoint())
            assertNotSame(failed, f.original); assertNotSame(input, recovered.input())
            assertSame(f.original, recipeSlot.get()); assertSame(f.original, readbackSlot.get())
            val completed = assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.apply(recovered))
            assertTrue(completed.fencingToken > token)
            f.assertSuccessful(); assertEquals(1, f.applyObservations.size); assertPrimaryApplied(f)
            assertNull(recipeSlot.get()); assertNull(readbackSlot.get())
            assertFailedBridgeRefused(f, waiting)
        }

    /** Deliberate negative SQL restore inputs only; missing N/P/L remains an unsupported obligation. */
    fun missingBookkeepingCannotBeSynthesized(tls: VersionBoundPersistenceConnectedFixture, family: ComplaintJournalDeletionKindV1, cut: RecurrentApplyBookkeepingCut) =
        withRecurrentFixture(tls, family, applied = false) { f ->
            val waiting = assertInstanceOf(TestActiveRecurrentV1.RecoveryRequired::class.java, f.checkpoint())
            f.queue.adversarialTransaction { jdbc ->
                if (cut != RecurrentApplyBookkeepingCut.MISSING_L) {
                    val table = if (family == ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) "installation_deletion_receipts" else "complaint_idempotency_receipts"
                    assertEquals(1, jdbc.update("DELETE FROM $table WHERE data_scope_id=? AND publication_ref=?", f.scope, f.record.event.route.eventId))
                }
                if (cut != RecurrentApplyBookkeepingCut.MISSING_N)
                    assertEquals(1, jdbc.update("DELETE FROM complaint_recovery_capacity_reservations WHERE data_scope_id=? AND event_id=?", f.scope, f.record.event.route.eventId))
                if (cut == RecurrentApplyBookkeepingCut.MISSING_NPL)
                    assertEquals(1, jdbc.update("DELETE FROM complaint_journal_publications WHERE data_scope_id=? AND event_id=?", f.scope, f.record.event.route.eventId))
            }
            val domain = f.domainImage(); val counters = f.counters()
            assertThrows<TestActiveRecurrentExceptionV1> { f.apply(waiting) }
            f.assertReleased(); assertEquals(domain, f.domainImage()); assertEquals(counters, f.counters())
            assertTrue(f.applyCalls.isNotEmpty()); assertFalse(f.applyCalls.any { it.sql.trimStart().startsWith("INSERT") })
            assertEquals(0L, f.queue.count("complaint_deletion_journal_applied")); assertNull(f.control()["checkpoint_result"])
            assertFailedBridgeRefused(f, waiting)
        }

    private fun assertPrimaryApplied(f: TestActiveRecurrentFixtureV1) {
        val q = f.queue
        q.assertExpectedAppliedObjects(); q.assertOnlyAuthorizedReportsErased()
        listOf("complaint_idempotency_receipts", "installation_deletion_receipts", "complaint_journal_publications",
            "complaint_recovery_capacity_reservations", "complaint_installation_ids", "app_installations", "complaint_resource_ids").forEach {
            assertEquals(q.countsBeforeQueue.getValue(it), q.count(it), "No N/P/L or credential synthesis: $it")
        }
        assertEquals("APPLIED", f.precursor.publication()["state"]); assertEquals("COMPLETED", q.receipt()["state"])
        assertEquals(f.record.stored.version, q.receipt()["external_object_version"]); assertEquals(f.record.event.route.eventId, q.receipt()["external_event_id"])
        assertEquals(q.receiptBeforeQueue, q.receiptIdentity()); assertEquals(q.proofBeforeQueue, q.publicationProof()); assertEquals(q.grantBeforeQueue, q.grantImage())
        val audits = q.auditsBeforeQueue.toMutableMap()
        audits["COMPLAINT_DELETED"] = audits.getOrDefault("COMPLAINT_DELETED", 0L) + f.precursor.reports.size
        audits["COMPLAINT_RECOVERY_APPLIED"] = audits.getOrDefault("COMPLAINT_RECOVERY_APPLIED", 0L) + 1
        assertEquals(audits, q.audits())
        val used = materialized(f).toLongArray().joinToString(",", "{", "}")
        assertEquals(used, f.observer.queryForObject("SELECT converted_amounts::text FROM complaint_recovery_capacity_reservations WHERE data_scope_id=? AND state='PARTIAL'", String::class.java, f.scope))
        assertTrue(f.raw.queue.sqs.requests.isEmpty() && f.raw.queue.ackRequests.isEmpty())
    }
    private fun materialized(f: TestActiveRecurrentFixtureV1) = OwnerDeleteLiteralCharges.appliedOnly +
        OwnerDeleteLiteralCharges.audit.scaled(f.precursor.reports.size.toLong() + 1)
    private fun assertTransfer(f: TestActiveRecurrentFixtureV1, before: Map<ComplaintCapacityCounter, DeleteAllCounter>, recurrentCharge: ComplaintCapacityVector) {
        val after = f.counters(); assertEquals(22, after.size)
        before.forEach { (counter, old) ->
            val used = materialized(f)[counter]; val refund = OwnerDeleteLiteralCharges.content[counter] * f.precursor.reports.size
            val charge = recurrentCharge[counter]
            assertEquals(old.copy(free = old.free + refund - charge, actual = old.actual + used - refund + charge, recovery = old.recovery - used),
                after.getValue(counter), counter.storedName)
        }
    }
    private fun appliedSql(f: TestActiveRecurrentFixtureV1) = when (f.precursor.family) {
        ComplaintJournalDeletionKindV1.OWNER_DELETE -> OwnerDeletePersistenceSql.INSERT_APPLIED
        ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> OwnerDeleteAllApplySql.test(f.precursor.dataScope).INSERT_APPLIED
        else -> AdminDeletePersistenceSql.INSERT_APPLIED
    }
    private fun assertFailedBridgeRefused(f: TestActiveRecurrentFixtureV1, waiting: TestActiveRecurrentV1.RecoveryRequired) {
        val rows = f.image(); val counters = f.counters(); val sql = f.probe.calls.size to f.applyCalls.size; val native = f.raw.order.toList()
        assertThrows<TestActiveRecurrentExceptionV1> { waiting.input() }
        assertThrows<TestActiveRecurrentExceptionV1> { waiting.resume() }
        assertThrows<TestActiveRecurrentExceptionV1> { f.apply(waiting) }
        assertEquals(rows, f.image()); assertEquals(counters, f.counters()); assertEquals(sql, f.probe.calls.size to f.applyCalls.size); assertEquals(native, f.raw.order)
        assertTrue(f.raw.queue.order.isEmpty())
    }

    fun immutablePreparedResume(tls: VersionBoundPersistenceConnectedFixture, cut: RecurrentSealRecoveryCut) = withRecurrentFixture(tls) { f ->
        val before = f.counters(); val domain = f.domainImage()
        val initial = f.immutableImage().getValue("complaint_test_active_seal_intents")
        var reached = false
        val stopping = if (cut === RecurrentSealRecoveryCut.CANONICAL) TestActiveRecurrentStepV1.FREEZE else TestActiveRecurrentStepV1.VERIFY
        f.probe.before = { call -> if (!reached && call.step === stopping && call.sql == TestActiveRecurrentSqlV1.authenticate) {
            reached = true; error("Synthetic recurrent cut after committed immutable preparation.")
        } }
        val failed = f.begin()
        assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(failed) }
        assertTrue(reached); f.assertReleased(); assertNull(f.control()["checkpoint_result"])
        val saved = f.intents().single()
        assertEquals(if (cut === RecurrentSealRecoveryCut.CANONICAL) "CANONICAL" else "WIRE_FROZEN", saved["state"])
        assertEquals("SEAL_PREPARED", f.control()["seal_state"]); assertEquals(1, f.history().size); assertTrue(f.scans().isEmpty())
        val canonical = canonicalImage(f)
        val frozen = f.immutableImage().getValue("complaint_test_active_recurrent_seal_intents")
        val puts = f.first.native.requests.count { it.kind == "PUT" }
        val generated = f.first.native.order.count { it == "GENERATE" }
        val oldToken = (f.control().getValue("lease_token") as Number).toLong()
        f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY)
        f.probe.before = {}; f.probe.after = {}
        assertLiveLeaseRefused(f)
        awaitInitialCheckpointLeaseExpiry(f.observer, f.scope)
        val completed = assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint())
        f.assertSuccessful()
        assertTrue(completed.fencingToken > oldToken)
        assertEquals(saved["operation_token"], f.intents().single()["operation_token"])
        assertEquals(canonical, canonicalImage(f), "Key/canonical/time/preparing fence and predecessor identity are not replaced on recovery.")
        if (cut === RecurrentSealRecoveryCut.FROZEN_NATIVE) {
            assertEquals(frozen, f.immutableImage().getValue("complaint_test_active_recurrent_seal_intents"))
            assertEquals(puts, f.first.native.requests.count { it.kind == "PUT" }, "The actual stored frozen winner is reread, not PUT again.")
            assertEquals(generated, f.first.native.order.count { it == "GENERATE" })
        } else assertEquals(puts + 1, f.first.native.requests.count { it.kind == "PUT" })
        assertEquals(initial, f.immutableImage().getValue("complaint_test_active_seal_intents")); assertEquals(domain, f.domainImage())
        f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2))
        val image = f.image(); val sql = f.probe.calls.size; val providers = f.raw.order.toList()
        assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(failed) }
        assertEquals(image, f.image()); assertEquals(sql, f.probe.calls.size); assertEquals(providers, f.raw.order)
    }

    fun recoverPaidPrefix(tls: VersionBoundPersistenceConnectedFixture, cut: RecurrentPaidPrefixCut) = withRecurrentFixture(tls) { f ->
        val before = f.counters(); val domain = f.domainImage()
        val failed = stopPaidPrefix(f, cut)
        val rows = f.scans(); val entries = f.entries()
        val passes = if (cut === RecurrentPaidPrefixCut.PAIR_COMPLETE) listOf(1, 2)
            else listOf(if (cut === RecurrentPaidPrefixCut.FIRST_REFUND_COMMITTED) 2 else 1)
        assertEquals(passes, rows.map { (it.getValue("pass") as Number).toInt() }); assertEquals(rows.size, entries.size)
        assertEquals(if (cut === RecurrentPaidPrefixCut.FIRST_STAGED) "SCANNING" else "COMPLETE", rows.first()["state"])
        assertTrue(entries.all { it["replay_state"] == "APPLIED" })
        val frozen = f.immutableImage().getValue("complaint_test_active_recurrent_seal_intents")
        val oldToken = (rows.first().getValue("fencing_token") as Number).toLong()
        val providers = f.raw.order.toList(); val puts = f.first.native.requests.count { it.kind == "PUT" }
        val permanent = TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2)
        f.assertCharge(before, permanent + TestActiveRecurrentStorageV1.scanCharge(rows.size.toLong(), entries.size.toLong()))
        if (cut === RecurrentPaidPrefixCut.FIRST_REFUND_COMMITTED) {
            assertTrue(f.probe.calls.filter { it.step === TestActiveRecurrentStepV1.CLEAN && it.sql == TestActiveRecurrentScanSqlV1.deleteRun }
                .all { it.phase.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED })
            assertEquals(1, f.probe.calls.count { it.step === TestActiveRecurrentStepV1.CLEAN && it.sql == TestActiveRecurrentScanSqlV1.deleteRun })
        }
        f.probe.before = {}; f.probe.after = {}
        awaitInitialCheckpointLeaseExpiry(f.observer, f.scope)
        val completed = assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint())
        f.assertSuccessful(); assertTrue(completed.fencingToken > oldToken)
        assertEquals(frozen, f.immutableImage().getValue("complaint_test_active_recurrent_seal_intents"))
        assertEquals(puts, f.first.native.requests.count { it.kind == "PUT" })
        assertEquals(listOf("STS", "PASS1", "GET1", "DECRYPT", "PASS2", "GET2", "DECRYPT"), f.raw.order.drop(providers.size),
            "Old COMPLETE/PENDING physical rows are cleanup obligations, never a substitute for either native pass.")
        f.assertCharge(before, permanent); assertEquals(domain, f.domainImage())
        val current = f.image(); val calls = f.probe.calls.size
        assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(failed) }
        assertEquals(current, f.image()); assertEquals(calls, f.probe.calls.size)
    }

    fun immutableHistoryIntentAndStagingGuards(tls: VersionBoundPersistenceConnectedFixture) = withRecurrentFixture(tls) { f ->
        stopPaidPrefix(f, RecurrentPaidPrefixCut.PAIR_COMPLETE)
        f.probe.before = {}; f.probe.after = {}
        val before = f.image(); val counters = f.counters()
        val token = f.intents().single().getValue("operation_token")
        for (sql in listOf(
            "UPDATE complaint_test_active_recurrent_seal_intents SET journal_configuration_hash=decode(repeat('07',32),'hex') WHERE operation_token=?",
            "UPDATE complaint_test_active_recurrent_seal_intents SET predecessor_checkpoint_hash=decode(repeat('08',32),'hex') WHERE operation_token=?",
            "UPDATE complaint_test_active_recurrent_seal_intents SET predecessor_history_hash=decode(repeat('09',32),'hex') WHERE operation_token=?",
            "UPDATE complaint_test_active_recurrent_seal_intents SET preparing_fencing_token=preparing_fencing_token+1 WHERE operation_token=?",
            "UPDATE complaint_test_active_recurrent_seal_intents SET canonical_bytes=canonical_bytes||decode('00','hex') WHERE operation_token=?",
            "UPDATE complaint_test_active_recurrent_seal_intents SET wire_bytes=wire_bytes||decode('00','hex') WHERE operation_token=?",
            "UPDATE complaint_test_active_recurrent_seal_intents SET charged_storage_bytes=charged_storage_bytes-1 WHERE operation_token=?",
            "DELETE FROM complaint_test_active_recurrent_seal_intents WHERE operation_token=?",
        )) assertThrows<DataAccessException> { f.observer.update(sql, token) }
        for (sql in listOf(
            "UPDATE complaint_test_active_checkpoint_history SET entry_bytes=entry_bytes||decode('00','hex') WHERE data_scope_id=? AND ordinal=2",
            "UPDATE complaint_test_active_checkpoint_history SET object_version='foreign-version' WHERE data_scope_id=? AND ordinal=2",
            "UPDATE complaint_test_active_checkpoint_history SET charged_storage_bytes=charged_storage_bytes-1 WHERE data_scope_id=? AND ordinal=2",
            "UPDATE complaint_test_active_checkpoint_history SET checkpointed_at=clock_timestamp() WHERE data_scope_id=? AND ordinal=1",
            "DELETE FROM complaint_test_active_checkpoint_history WHERE data_scope_id=? AND ordinal=2",
            "UPDATE complaint_test_active_seal_intents SET rotation_sequence=2 WHERE data_scope_id=?",
        )) assertThrows<DataAccessException> { f.observer.update(sql, f.scope) }
        assertThrows<DataAccessException> { f.observer.update(
            "UPDATE complaint_test_active_checkpoint_history h SET checkpoint_bytes=p.checkpoint_bytes,checkpoint_hash=p.checkpoint_hash,checkpointed_at=p.checkpointed_at " +
                "FROM complaint_test_active_checkpoint_history p WHERE p.data_scope_id=h.data_scope_id AND p.ordinal=1 AND h.ordinal=2 AND h.data_scope_id=?", f.scope) }
        for (sql in listOf(
            "UPDATE complaint_journal_scan_runs SET active_recurrent_storage_bytes=4415 WHERE scan_id=? AND pass=1",
            "UPDATE complaint_journal_scan_runs SET active_recurrent_seal_token=NULL,active_recurrent_storage_bytes=NULL WHERE scan_id=? AND pass=1",
            "UPDATE complaint_journal_scan_runs SET fencing_token=fencing_token+1 WHERE scan_id=? AND pass=1",
            "UPDATE complaint_journal_scan_runs SET maximum_entries=maximum_entries+1 WHERE scan_id=? AND pass=1",
            "UPDATE complaint_journal_scan_runs SET restore_identity=gen_random_uuid() WHERE scan_id=? AND pass=1",
            "UPDATE complaint_journal_scan_entries SET object_version='foreign-version' WHERE scan_id=? AND pass=1",
            "UPDATE complaint_journal_scan_entries SET ciphertext_hash=decode(repeat('08',32),'hex') WHERE scan_id=? AND pass=1",
            "UPDATE complaint_journal_scan_entries SET entry_bytes=entry_bytes+1 WHERE scan_id=? AND pass=1",
            "UPDATE complaint_journal_scan_entries SET replay_state='PENDING' WHERE scan_id=? AND pass=1",
        )) assertThrows<DataAccessException> { f.observer.update(sql, token) }
        assertThrows<DataAccessException> { f.observer.update(
            "UPDATE complaint_test_runs SET state='SEALED',sealed_at=clock_timestamp() WHERE data_scope_id=? AND state='ACTIVE'", f.scope) }
        // Otherwise well-formed foreign V14 parent, visible ONLY inside this rollback-only negative
        // transaction. No such row is fed to the original or made into paid cleanup/refund evidence.
        f.first.independentTransaction { _, jdbc, _ ->
            val foreign = UUID.randomUUID()
            assertEquals(1, jdbc.update("INSERT INTO complaint_journal_scan_runs (scan_id,pass,data_scope_id,test_only,restore_identity,desired_generation,fencing_token," +
                "writer_generation,cutoff_epoch,maximum_entries,maximum_bytes,entry_count,entry_bytes,state,started_at) " +
                "SELECT ?,pass,data_scope_id,test_only,restore_identity,desired_generation,fencing_token,writer_generation,cutoff_epoch,maximum_entries,maximum_bytes," +
                "0,0,'SCANNING',started_at FROM complaint_journal_scan_runs WHERE scan_id=? AND pass=1", foreign, token))
            val rejected = assertThrows<DataAccessException> { jdbc.update("UPDATE complaint_journal_scan_entries SET scan_id=? WHERE scan_id=? AND pass=1", foreign, token) }
            val postgres = rejected.mostSpecificCause as SQLException
            assertEquals("23514", postgres.sqlState)
            val server = checkNotNull(postgres.javaClass.getMethod("getServerErrorMessage").invoke(postgres))
            assertEquals("Invalid recurrent scan replay", server.javaClass.getMethod("getMessage").invoke(server))
        }
        assertEquals(before, f.image()); assertEquals(counters, f.counters())
        assertNull(f.control()["checkpoint_result"]); assertNull(f.history().last()["checkpoint_bytes"])
    }

    internal fun stopPaidPrefix(f: TestActiveRecurrentFixtureV1, cut: RecurrentPaidPrefixCut): TestActiveRecurrentV1 {
        var reached = false; var cleans = 0
        f.probe.before = { call ->
            val selected = when (cut) {
                RecurrentPaidPrefixCut.FIRST_STAGED -> call.step === TestActiveRecurrentStepV1.COMPLETE_PASS && f.passNumber() == 1 && call.sql == TestActiveRecurrentScanSqlV1.completeRun
                RecurrentPaidPrefixCut.PAIR_COMPLETE -> call.step === TestActiveRecurrentStepV1.CLEAN && call.sql == TestActiveRecurrentSqlV1.authenticate
                RecurrentPaidPrefixCut.FIRST_REFUND_COMMITTED -> if (call.step === TestActiveRecurrentStepV1.CLEAN && call.sql == TestActiveRecurrentSqlV1.authenticate) ++cleans == 2 else false
            }
            if (!reached && selected) { reached = true; error("Synthetic recurrent interruption after actual paid/native prefix.") }
        }
        val failed = f.begin()
        assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(failed) }
        assertTrue(reached); f.assertReleased(); assertNull(f.control()["checkpoint_result"])
        return failed
    }

    private fun assertLiveLeaseRefused(f: TestActiveRecurrentFixtureV1) {
        val before = f.image(); val counters = f.counters(); val native = f.raw.order.toList()
        assertTrue(f.observer.queryForObject("SELECT clock_timestamp()<lease_expires_at FROM complaint_journal_control WHERE data_scope_id=?", Boolean::class.java, f.scope) == true)
        assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint() }
        f.assertReleased(); assertEquals(before, f.image()); assertEquals(counters, f.counters()); assertEquals(native, f.raw.order)
    }

    private fun canonicalImage(f: TestActiveRecurrentFixtureV1): List<String> = f.observer.queryForList(
        "SELECT (to_jsonb(i)-ARRAY['state','wire_bytes','wire_hash','checksum_sha256','content_type','object_lock_mode','retain_until','metadata_bytes','metadata_hash','frozen_at'])::text " +
            "FROM complaint_test_active_recurrent_seal_intents i WHERE data_scope_id=? ORDER BY rotation_sequence", String::class.java, f.scope)
}
