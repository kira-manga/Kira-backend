package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDispositionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.journal.TestOrdinaryInventoryHttpFixtureV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.terminalHash
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** Real predecessor and fixed PREPARE owner; synthetic IAM/horizon fixtures are not release acceptance. */
internal object TestInstallationManifestPrepareCasesV1 {
    fun paidPrepare(tls: VersionBoundPersistenceConnectedFixture) = withInstallationManifestPredecessor(tls) { f, drain, probe ->
        drain.requireManifestPredecessor()
        assertTrue(drain.manifestCut().denial.firstInventory.versionCount > 0L, "The successor must read real nonempty applied pages after drain completion.")
        assertThrows<TestOrdinaryDrainExceptionV1> { drain.requireInventoryKind("OWNER_DELETE") }
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        val before = observed.state()
        val history = observed.previousHistory()
        val primary = observed.primaryImage()
        val seals = observed.runBytes("seal_set_bytes")
        val ordinary = manifestUnaffectedImage(f)
        val json = TestTerminalJsonV1(f.registration.process.consumers.journalConfiguration)
        val prior = json.progress(observed.runBytes("permanent_denial_bytes"))
        assertTrue(prior.installationReads().isEmpty())
        val snapshots = linkedMapOf<PersistencePhaseContext, Pair<TestOrdinaryDrainAccountingStateV1, Map<String, List<String>>>>()
        val committed = linkedMapOf<PersistencePhaseContext, TestOrdinaryDrainAccountingStateV1>()
        var atomicWrites = 0
        probe.before = { call ->
            if (!snapshots.containsKey(call.phase)) {
                snapshots[call.phase] = observed.state() to manifestRowsImage(f)
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() { committed[call.phase] = observed.state() }
                })
            }
        }
        probe.after = { call ->
            if (call.sql in manifestAtomicStatements || call.sql.startsWith("UPDATE complaint_capacity_counters")) {
                assertEquals(snapshots.getValue(call.phase).first, observed.state(), "Independent observer cannot see an unpaid run/counter/sidecar intermediate state.")
                assertEquals(snapshots.getValue(call.phase).second, manifestRowsImage(f), "Publication, reservation and sidecar become visible together.")
                atomicWrites++
            }
        }
        val original = TestRunInstallationManifestV1.begin(drain).also { probe.original = it }
        assertEquals(TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK, original.prepare())
        probe.before = {}; probe.after = {}; probe.assertReleased()
        assertEquals(4, snapshots.size)
        assertEquals(snapshots.keys, committed.keys)
        assertEquals(listOf(TestInstallationManifestStepV1.CAPTURE, TestInstallationManifestStepV1.CHUNK,
            TestInstallationManifestStepV1.PREPARE, TestInstallationManifestStepV1.COMPLETE), probe.calls.map { it.step }.distinct())
        assertEquals(listOf(TestInstallationManifestStepV1.CAPTURE, TestInstallationManifestStepV1.COMPLETE),
            probe.calls.filter { it.sql == TestOrdinaryDrainSqlV1.appliedPage }.map { it.step }.distinct())
        assertEquals(8, atomicWrites, "One progress merge, three inserts, three changed counters and one exact unused debit.")
        observed.assertTransfer(before, observed.state(), reserveSpend = MANIFEST_LITERAL)
        assertEquals(before.applied, observed.state().applied)
        assertEquals(before.sidecars + 1, observed.state().sidecars)
        assertEquals(history, observed.previousHistory()); assertEquals(ordinary, manifestUnaffectedImage(f))
        assertEquals(primary, observed.primaryImage())
        assertEquals(before.recoveryState, observed.state().recoveryState)
        assertEquals(before.promise, observed.state().promise); assertEquals(before.used, observed.state().used)
        assertEquals(before.appliedAt, observed.state().appliedAt)
        assertArrayEquals(seals, observed.runBytes("seal_set_bytes"))
        val progress = json.progress(observed.runBytes("permanent_denial_bytes"))
        assertEquals(prior.completedCuts(), progress.completedCuts(), "The already-paid ordinary cut and its lifetime run delta survive the merge.")
        assertEquals(2, progress.installationReads().size)
        val first = progress.installationReads().first(); val second = progress.installationReads().last()
        assertEquals(first.copy(startedAtEpochSecond = second.startedAtEpochSecond, completedAtEpochSecond = second.completedAtEpochSecond), second)
        assertEquals(original.leaseToken, first.fencingToken)
        assertEquals(f.history.actor.id.toString(), first.sourceHighWater.greatestReservationId)
        assertEquals(1L, first.sourceHighWater.enrolledCount); assertEquals(1L, first.installationCount)
        assertEquals(1L, first.retiredCount); assertEquals(0L, first.deletedCount)
        assertPreparedManifest(f, original, original.leaseToken)
        val calls = probe.calls.size
        assertThrows<TestInstallationManifestExceptionV1> { original.prepare() }
        assertEquals(calls, probe.calls.size, "A completed invocation is not reentrant.")
    }

    fun exactReplay(tls: VersionBoundPersistenceConnectedFixture) = withInstallationManifestPredecessor(tls) { f, drain, probe ->
        val first = TestRunInstallationManifestV1.begin(drain).also { probe.original = it }
        assertEquals(TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK, first.prepare())
        probe.assertReleased()
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        val before = observed.state()
        val image = observed.image().filterKeys { it != "complaint_journal_control" }
        val progress = observed.runBytes("permanent_denial_bytes")
        val seals = observed.runBytes("seal_set_bytes")
        probe.reset()
        val retry = TestRunInstallationManifestV1.begin(drain).also { probe.original = it }
        assertEquals(TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK, retry.prepare())
        probe.assertReleased()
        assertEquals(first.leaseToken + 1, retry.leaseToken)
        assertEquals(before, observed.state(), "An exact canonical winner cannot pay another publication, sidecar or run delta.")
        assertEquals(image, observed.image().filterKeys { it != "complaint_journal_control" }, "Existing rows, run and unrelated rows retain exact bytes and xmin.")
        assertArrayEquals(progress, observed.runBytes("permanent_denial_bytes")); assertArrayEquals(seals, observed.runBytes("seal_set_bytes"))
        assertTrue(probe.calls.none { it.sql in manifestAtomicStatements || it.sql.startsWith("UPDATE complaint_capacity_counters") })
        val currentRead = retry.capturedSource().progress.installationReads().first()
        val historicalRead = TestTerminalJsonV1(f.registration.process.consumers.journalConfiguration).progress(progress).installationReads().first()
        assertEquals(retry.leaseToken, currentRead.fencingToken)
        assertEquals(first.leaseToken, historicalRead.fencingToken)
        assertNotEquals(currentRead.sourceHighWater.sourceSha256, historicalRead.sourceHighWater.sourceSha256)
        assertEquals(currentRead.installationsSha256, historicalRead.installationsSha256)
        assertPreparedManifest(f, retry, first.leaseToken)
    }

    fun unfinishedDrainRefuses(tls: VersionBoundPersistenceConnectedFixture) = withOrdinaryDrainRun(tls, expireClosedSetupPredecessors = true) { f ->
        val drain = f.begin()
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        val before = observed.image()
        val calls = f.probe.calls.size
        val requests = f.provider.requests.size
        assertThrows<TestOrdinaryDrainExceptionV1> { TestRunInstallationManifestV1.begin(drain) }
        assertThrows<TestOrdinaryDrainExceptionV1> { drain.prepareInstallationManifest() }
        assertEquals(before, observed.image()); assertEquals(calls, f.probe.calls.size); assertEquals(requests, f.provider.requests.size)
        assertTrue(f.sealHttp.order.isEmpty())
        // Actually finish a failed drain invocation. A terminal boolean/cleanup alone must not
        // authorize the successor reader, even though a successful completed drain above can.
        f.probe.before = { throw TestOrdinaryDrainExceptionV1() }
        try {
            assertThrows<TestOrdinaryDrainExceptionV1> { drain.drain(f.approval(drain), f.rawEvidence, f.primaryCredentials, f.readCredentials) }
        } finally { f.probe.before = {} }
        f.probe.assertReleased(requireCommitted = false)
        val failedCalls = f.probe.calls.size
        assertTrue(failedCalls > calls)
        assertThrows<TestOrdinaryDrainExceptionV1> { drain.requireManifestPredecessor() }
        assertThrows<TestOrdinaryDrainExceptionV1> { TestRunInstallationManifestV1.begin(drain) }
        assertThrows<TestOrdinaryDrainExceptionV1> { drain.prepareInstallationManifest() }
        assertEquals(failedCalls, f.probe.calls.size); assertEquals(before, observed.image()); assertEquals(requests, f.provider.requests.size)
        assertTrue(f.sealHttp.order.isEmpty())
        f.assertReleased()
    }

    /** Independent fixed22 expectation: 278528 publication/reservation + 1340736 V21 sidecar; promise zero. */
    internal val MANIFEST_LITERAL = ComplaintCapacityVector.ZERO.with(ComplaintCapacityCounter.JOURNAL_PUBLICATIONS, 1)
        .with(ComplaintCapacityCounter.RECOVERY_RESERVATIONS, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 1_619_264)

    internal val manifestAtomicStatements = setOf(TestInstallationManifestSqlV1.insertPublication, TestInstallationManifestSqlV1.insertRecovery,
        TestInstallationManifestSqlV1.insertSidecar, TestOrdinaryDrainSqlV1.spendAndProgress, TestOrdinaryDrainSqlV1.spend)
}

/** Setup does not synthesize a drain result, source observation, sidecar, CONVERTED row or charge. */
internal fun withInstallationManifestPredecessor(tls: VersionBoundPersistenceConnectedFixture,
    action: (TestRunOrdinaryDrainFixtureV1, TestRunOrdinaryDrainV1, TestInstallationManifestSqlProbeV1) -> Unit) =
    withOrdinaryDrainRun(tls, expireClosedSetupPredecessors = true) { f ->
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val drain = f.begin()
            assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                drain.drain(f.approval(drain), f.rawEvidence, f.primaryCredentials, f.readCredentials))
            f.assertReleased()
            val ordinaryCalls = f.provider.requests.size
            val generated = f.provider.generated(); val decrypted = f.provider.decrypted()
            val nativeCalls = native.requests.size
            val sealCalls = f.sealHttp.requests.size; val sealOrder = f.sealHttp.order.toList()
            TestInstallationManifestSqlProbeV1(f).use { probe ->
                try { action(f, drain, probe) }
                catch (problem: Throwable) { runCatching { probe.reportUnexpectedFailure() }; throw problem }
            }
            f.assertReleased()
            assertEquals(ordinaryCalls, f.provider.requests.size); assertEquals(nativeCalls, native.requests.size)
            assertEquals(generated, f.provider.generated()); assertEquals(decrypted, f.provider.decrypted())
            assertEquals(sealCalls, f.sealHttp.requests.size); assertEquals(sealOrder, f.sealHttp.order,
                "PREPARE cannot perform STS, KMS, S3 or a repeated ordinary drain.")
        }
    }

internal fun manifestRowsImage(f: TestRunOrdinaryDrainFixtureV1): Map<String, List<String>> =
    checkNotNull(f.observer.dataSource).connection.use { connection ->
        // Raw independent observer, never a second Spring-bound participant on the original thread.
        mapOf(
            "publication" to "SELECT jsonb_build_array(to_jsonb(p), p.xmin::text)::text FROM complaint_journal_publications p " +
                "WHERE data_scope_id = ? AND event_kind = 'INSTALLATION_MANIFEST' ORDER BY event_id",
            "recovery" to "SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM complaint_recovery_capacity_reservations r " +
                "JOIN complaint_journal_publications p ON p.event_id = r.publication_ref WHERE r.data_scope_id = ? AND p.event_kind = 'INSTALLATION_MANIFEST' ORDER BY r.event_id",
            "sidecar" to "SELECT jsonb_build_array(to_jsonb(i), i.xmin::text)::text FROM complaint_test_terminal_intents i " +
                "WHERE data_scope_id = ? AND object_kind = 'INSTALLATION_MANIFEST' ORDER BY object_ordinal",
        ).mapValues { (_, sql) -> connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, f.scope)
            statement.executeQuery().use { row -> buildList { while (row.next()) add(row.getString(1)) } }
        } }
    }

private fun manifestUnaffectedImage(f: TestRunOrdinaryDrainFixtureV1): Map<String, List<String>> {
    val relevant = setOf("global", "catalog", "complaint_resource_ids", "complaints", "audits", "complaint_installation_ids", "app_installations",
        "complaint_idempotency_receipts", "complaint_deletion_journal_applied", "complaint_deletion_journal_retirements", "complaint_journal_scan_runs", "complaint_journal_scan_entries")
    return TestOrdinaryDrainAccountingObservationV1(f).image().filterKeys { it in relevant }
}

internal fun assertPreparedManifest(f: TestRunOrdinaryDrainFixtureV1, original: TestRunInstallationManifestV1, preparingFence: Long) {
    val json = TestTerminalJsonV1(f.registration.process.consumers.journalConfiguration)
    val read = json.progress(TestOrdinaryDrainAccountingObservationV1(f).runBytes("permanent_denial_bytes")).installationReads().first()
    val rows = f.observer.query("""
        SELECT p.event_id, p.data_scope_id, p.writer_generation, p.journal_epoch, p.event_kind, p.target_count, p.routing_key_id,
            p.object_key, p.event_bytes, p.semantic_hash, p.state AS publication_state,
            r.state AS recovery_state, r.reserved_amounts, r.converted_amounts, r.converted_at,
            i.state AS sidecar_state, i.object_ordinal, i.preparing_fencing_token, i.canonical_bytes, i.canonical_hash,
            (p.created_at = r.created_at AND p.created_at = i.created_at AND p.event_id = r.event_id AND p.event_id = i.object_id
                AND p.object_key = i.object_key AND p.routing_key_id = i.routing_key_id AND p.writer_generation = i.writer_generation
                AND p.journal_epoch = i.epoch_start AND p.journal_epoch = i.epoch_end AND i.publication_ref = p.event_id
                AND p.test_only AND r.test_only AND i.test_only AND r.data_scope_id = p.data_scope_id AND i.data_scope_id = p.data_scope_id
                AND p.object_version IS NULL AND p.ciphertext_hash IS NULL AND p.verification_bytes IS NULL AND p.applied_at IS NULL
                AND i.wire_bytes IS NULL AND i.wire_hash IS NULL AND i.checksum_sha256 IS NULL AND i.content_type IS NULL
                AND i.object_lock_mode IS NULL AND i.retain_until IS NULL AND i.metadata_bytes IS NULL AND i.metadata_hash IS NULL AND i.frozen_at IS NULL) AS exact
        FROM complaint_journal_publications p JOIN complaint_recovery_capacity_reservations r ON r.publication_ref = p.event_id
            JOIN complaint_test_terminal_intents i ON i.publication_ref = p.event_id
        WHERE p.data_scope_id = ? AND p.event_kind = 'INSTALLATION_MANIFEST' ORDER BY i.object_ordinal
    """.trimIndent(), { row, _ ->
        assertTrue(row.getBoolean("exact")); assertEquals(f.scope, row.getObject("data_scope_id", UUID::class.java))
        assertEquals(original.writer, row.getObject("writer_generation", UUID::class.java).toString())
        assertEquals(original.epoch, row.getLong("journal_epoch")); assertEquals(0, row.getInt("object_ordinal"))
        assertEquals(preparingFence, row.getLong("preparing_fencing_token"))
        assertEquals("PREPARED", row.getString("publication_state")); assertEquals("RESERVED", row.getString("recovery_state"))
        assertEquals("CANONICAL", row.getString("sidecar_state")); assertEquals(1, row.getInt("target_count"))
        val reserved = row.getArray("reserved_amounts")
        try { assertEquals(List(22) { 0L }, (reserved.array as Array<*>).map { (it as Number).toLong() }) } finally { reserved.free() }
        assertNull(row.getArray("converted_amounts")); assertNull(row.getTimestamp("converted_at"))
        val bytes = row.getBytes("event_bytes")
        assertArrayEquals(bytes, row.getBytes("canonical_bytes")); assertArrayEquals(row.getBytes("semantic_hash"), row.getBytes("canonical_hash"))
        val declaration = json.installationManifest(bytes)
        assertEquals(terminalHash(bytes), java.util.HexFormat.of().formatHex(row.getBytes("semantic_hash")))
        assertEquals(original.runContext, declaration.context().run); assertEquals(row.getString("event_id"), declaration.eventId)
        assertEquals(original.writer, declaration.writerGeneration); assertEquals(original.epoch, declaration.publicationEpoch)
        assertEquals(0, declaration.chunkIndex); assertEquals(1, declaration.chunkCount)
        assertEquals(read.installationsSha256, declaration.installationsSha256)
        assertEquals(listOf(f.history.actor.id.toString()), declaration.entries().map { it.installationId })
        assertEquals(listOf(TestTerminalDispositionV1.RETIRED), declaration.entries().map { it.disposition })
        row.getString("event_id")
    }, f.scope)
    assertEquals(1, rows.size)
    assertTrue(f.observer.queryForObject("""
        SELECT state = 'SEALED' AND purging_at IS NULL AND purged_at IS NULL
            AND event_manifest_count IS NULL AND event_manifest_root IS NULL
            AND installation_manifest_count IS NULL AND installation_manifest_root IS NULL AND installation_chunk_count IS NULL
            AND retired_count IS NULL AND deleted_count IS NULL AND terminal_event_id IS NULL AND terminal_object_key IS NULL
            AND terminal_object_version IS NULL AND terminal_ciphertext_hash IS NULL
            AND terminal_catalog_generation IS NULL AND terminal_catalog_hash IS NULL
        FROM complaint_test_runs WHERE data_scope_id = ?
    """.trimIndent(), Boolean::class.java, f.scope) == true)
    assertTrue(f.observer.queryForObject("SELECT lease_owner IS NULL AND lease_expires_at IS NULL AND lease_token = ? " +
        "FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, original.leaseToken, f.scope) == true)
}
