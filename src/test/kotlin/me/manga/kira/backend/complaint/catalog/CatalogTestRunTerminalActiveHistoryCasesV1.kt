package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalCasesV1.assertCharge
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalCasesV1.assertPrepared
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalCasesV1.assertProjected
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalCasesV1.unused
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialPrefixV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalActiveHistorySqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalPreconditionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalProjectionSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalResultV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalSqlV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.UUID

internal enum class TerminalCatalogHistoryRowFaultV1 { MISSING_A, FOREIGN_A_IDENTITY, REWRITTEN_A_XMIN }
internal enum class TerminalCatalogHistoryNativeFaultV1 { MISSING_A, SECOND_PASS_EXTRA_VERSION, CHANGED_RETENTION, CHANGED_LAST_MODIFIED }

/** Authored producer/refusal assertions only. No execution, provider qualification or substituted B/D completion. */
internal object CatalogTestRunTerminalActiveHistoryCasesV1 {
    fun successful(tls: VersionBoundPersistenceConnectedFixture) = withActiveHistoryTerminalCatalogRun(tls) { h ->
        val f = h.catalog
        val protected = h.preservedRows(); val counters = f.f.p.counters(); val reserve = unused(f)
        val nativeStart = f.f.sealHttp.terminalInventoryRequests.size; val journalStart = f.f.sealHttp.order.size
        val seals = f.record.sealSet.records()
        assertEquals(listOf(1L, 2L, 3L), seals.map { it.epochStartInclusive }); assertEquals(listOf(1L, 2L, 3L), seals.map { it.epochEndInclusive })
        assertEquals(seals[0].objectRef.canonicalSha256, seals[1].precedingSealSha256)
        assertEquals(seals[1].objectRef.canonicalSha256, seals[2].precedingSealSha256)
        assertEquals(2L, f.record.purge.document.preTerminalSeals.count); assertEquals(2L, f.record.purge.document.preTerminalInventory.count)
        assertEquals(3L, f.manifest.history.epochSeals.count); assertEquals(1L, f.record.installationManifest.summary.installationCount)
        assertTrue(f.observer.queryForObject("SELECT i.created_at < r.sealed_at AND i.charged_storage_bytes = 2097152 " +
            "FROM complaint_test_active_seal_intents i JOIN complaint_test_runs r USING (data_scope_id) WHERE i.data_scope_id = ?",
            Boolean::class.java, f.scope) == true, "A's pre-SEALED V26 is not reclassified as a post-SEALED V21 sidecar.")
        f.http.replicateOnPut = true
        val original = f.begin()
        assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, original.publish(f.unsigned, f.request()))
        original.requireActualCleanup(); f.probe.assertReleased(); f.probe.assertHistoryOrder(active = true); assertProjected(f)
        val charge = TestTerminalCapacityChargesV1.SCOPED_CATALOG + TestTerminalCapacityChargesV1.AUDIT
        assertCharge(f, counters, charge); assertEquals(reserve - charge, unused(f)); assertEquals(protected, h.preservedRows())
        assertEquals(3L, f.observer.queryForObject("SELECT count(*) FROM complaints WHERE data_scope_id = ?", Long::class.java, f.scope))
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", Long::class.java, f.scope))
        assertEquals(listOf("LIST", "LIST"), f.ordinaryRequests.map { it.kind }); assertTrue(f.ordinaryKeys.requests.isEmpty())
        val native = f.f.sealHttp.terminalInventoryRequests.drop(nativeStart)
        assertEquals(2 * f.d.targets.size, native.count { it.kind == "GET" })
        assertEquals(2 * ((f.d.targets.size + 1) / 2), native.count { it.kind == "LIST" })
        assertEquals(2, native.count { it.kind == "GET" && it.http.encodedPath().endsWith(h.initialSeal.objectRef.objectKey) })
        assertTrue(f.f.sealHttp.order.drop(journalStart).none { it == "GENERATE" || it == "PUT" })
        assertEquals(1, f.signatures.size); assertEquals(1, f.http.bodies.size)
        assertArrayEquals(f.unsigned, f.bytes("unsigned_bytes")); assertArrayEquals(f.bytes("envelope_bytes"), f.http.bodies.single())
        CatalogTestRunTerminalCasesV1.assertOrder(f.probe)
    }

    fun preparedRecovery(tls: VersionBoundPersistenceConnectedFixture) = withActiveHistoryTerminalCatalogRun(tls) { h ->
        val f = h.catalog; val protected = h.preservedRows(); val counters = f.f.p.counters(); val reserve = unused(f)
        val original = f.begin()
        assertEquals(CatalogTestRunTerminalResultV1.EXACT_PREPARED_AWAITING_DUAL_COPY, original.publish(f.unsigned, f.request()))
        original.requireActualCleanup(); assertPrepared(f); f.probe.assertHistoryOrder(active = true)
        assertEquals(protected, h.preservedRows()); assertCharge(f, counters, TestTerminalCapacityChargesV1.SCOPED_CATALOG)
        val unsigned = f.bytes("unsigned_bytes"); val envelope = f.bytes("envelope_bytes"); val files = f.files()
        val nativeStart = f.f.sealHttp.terminalInventoryRequests.size
        f.http.completeReplication() // Exact original PUT is copied; no re-encryption or supplied catalog evidence.
        f.freshRecovery { recovery, probe ->
            assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, recovery.resume(f.request()))
            probe.assertReleased(); probe.assertHistoryOrder(active = true); assertProjected(f)
            assertTrue(probe.calls.none { it.sql in setOf(CatalogTestRunTerminalSqlV1.insertPrepared,
                CatalogTestRunTerminalProjectionSqlV1.spendPrepared, CatalogTestRunTerminalSqlV1.persistSignature) })
            assertArrayEquals(unsigned, f.bytes("unsigned_bytes")); assertArrayEquals(envelope, f.bytes("envelope_bytes"))
            files.forEach { (path, hash) -> assertEquals(hash, f.files()[path]) }
        }
        assertEquals(protected, h.preservedRows())
        val charge = TestTerminalCapacityChargesV1.SCOPED_CATALOG + TestTerminalCapacityChargesV1.AUDIT
        assertCharge(f, counters, charge); assertEquals(reserve - charge, unused(f))
        assertEquals(1, f.signatures.size); assertEquals(1, f.http.bodies.size)
        assertEquals(2, f.f.sealHttp.terminalInventoryRequests.drop(nativeStart).count {
            it.kind == "GET" && it.http.encodedPath().endsWith(h.initialSeal.objectRef.objectKey)
        })
        assertThrows<RuntimeException> { original.publish(f.unsigned, f.request()) }
    }

    fun projectedReplay(tls: VersionBoundPersistenceConnectedFixture) = withActiveHistoryTerminalCatalogRun(tls) { h ->
        val f = h.catalog; f.http.replicateOnPut = true
        val original = f.begin()
        assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, original.publish(f.unsigned, f.request()))
        original.requireActualCleanup(); val before = h.fullImage(); val files = f.files()
        val nativeStart = f.f.sealHttp.terminalInventoryRequests.size
        f.freshRecovery { recovery, probe ->
            assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, recovery.resume(f.request()))
            probe.assertReleased(); probe.assertHistoryOrder(active = true); assertReadOnly(probe)
            assertEquals(before, h.fullImage()); assertEquals(files, f.files()); assertProjected(f)
        }
        assertEquals(1, f.signatures.size); assertEquals(1, f.http.bodies.size)
        assertEquals(2 * f.d.targets.size, f.f.sealHttp.terminalInventoryRequests.drop(nativeStart).count { it.kind == "GET" })
    }

    fun nonemptySuccessful(tls: VersionBoundPersistenceConnectedFixture, settledQueue: Boolean = false) =
        withNonemptyActiveHistoryTerminalCatalogRun(tls, if (settledQueue) TerminalCatalogQueueHistoryV1.SETTLED else TerminalCatalogQueueHistoryV1.ABSENT) { h ->
            val f = h.catalog; assertNonemptyHistory(h)
            val protected = h.preservedRows(); val counters = f.f.p.counters(); val reserve = unused(f)
            val nativeStart = f.f.sealHttp.terminalInventoryRequests.size; val journalStart = f.f.sealHttp.order.size
            f.http.replicateOnPut = true
            val original = f.begin()
            assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, original.publish(f.unsigned, f.request()))
            original.requireActualCleanup(); f.probe.assertReleased(); f.probe.assertHistoryOrder(active = true); assertProjected(f)
            val charge = TestTerminalCapacityChargesV1.SCOPED_CATALOG + TestTerminalCapacityChargesV1.AUDIT
            assertCharge(f, counters, charge); assertEquals(reserve - charge, unused(f))
            assertEquals(protected, h.preservedRows(), "A/V26 and optional B/V29 full rows+xmin and the one APPLIED family survive E unchanged.")
            assertNonemptyHistory(h); assertNonemptyNativePairs(h, nativeStart, journalStart, 2)
            assertEquals(1, f.signatures.size); assertEquals(1, f.http.bodies.size)
            assertArrayEquals(f.unsigned, f.bytes("unsigned_bytes")); assertArrayEquals(f.bytes("envelope_bytes"), f.http.bodies.single())
            CatalogTestRunTerminalCasesV1.assertOrder(f.probe)
        }

    fun settledQueuePreparedRecoveryAndReplay(tls: VersionBoundPersistenceConnectedFixture) =
        withNonemptyActiveHistoryTerminalCatalogRun(tls, TerminalCatalogQueueHistoryV1.SETTLED) { h ->
            val f = h.catalog; assertNonemptyHistory(h)
            val protected = h.preservedRows(); val counters = f.f.p.counters(); val reserve = unused(f)
            val nativeStart = f.f.sealHttp.terminalInventoryRequests.size; val journalStart = f.f.sealHttp.order.size
            val original = f.begin()
            assertEquals(CatalogTestRunTerminalResultV1.EXACT_PREPARED_AWAITING_DUAL_COPY, original.publish(f.unsigned, f.request()))
            original.requireActualCleanup(); f.probe.assertReleased(); f.probe.assertHistoryOrder(active = true); assertPrepared(f)
            assertCharge(f, counters, TestTerminalCapacityChargesV1.SCOPED_CATALOG); assertEquals(protected, h.preservedRows())
            val unsigned = f.bytes("unsigned_bytes"); val envelope = f.bytes("envelope_bytes"); val preparedFiles = f.files()
            f.http.completeReplication() // Copy only the actual original PUT. No new E Sign/PUT, A encryption or B action.
            f.freshRecovery { recovery, probe ->
                assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, recovery.resume(f.request()))
                probe.assertReleased(); probe.assertHistoryOrder(active = true); assertProjected(f)
                assertTrue(probe.calls.none { it.sql in setOf(CatalogTestRunTerminalSqlV1.insertPrepared,
                    CatalogTestRunTerminalProjectionSqlV1.spendPrepared, CatalogTestRunTerminalSqlV1.persistSignature) })
                assertArrayEquals(unsigned, f.bytes("unsigned_bytes")); assertArrayEquals(envelope, f.bytes("envelope_bytes"))
                preparedFiles.forEach { (path, hash) -> assertEquals(hash, f.files()[path]) }
            }
            val charge = TestTerminalCapacityChargesV1.SCOPED_CATALOG + TestTerminalCapacityChargesV1.AUDIT
            assertCharge(f, counters, charge); assertEquals(reserve - charge, unused(f)); assertEquals(protected, h.preservedRows())
            val projected = h.fullImage(); val projectedFiles = f.files()
            f.freshRecovery { recovery, probe ->
                assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, recovery.resume(f.request()))
                probe.assertReleased(); probe.assertHistoryOrder(active = true); assertReadOnly(probe)
                assertEquals(projected, h.fullImage()); assertEquals(projectedFiles, f.files())
            }
            assertNonemptyHistory(h); assertNonemptyNativePairs(h, nativeStart, journalStart, 6)
            assertCharge(f, counters, charge); assertEquals(reserve - charge, unused(f))
            assertEquals(1, f.signatures.size); assertEquals(1, f.http.bodies.size)
            assertThrows<RuntimeException> { original.publish(f.unsigned, f.request()) }
        }

    /** Real B refusal leaves POLLING. Actual D completes the primary/drain; it does not settle B. */
    fun genuinePollingQueueRefusesCatalog(tls: VersionBoundPersistenceConnectedFixture) =
        withNonemptyActiveHistoryTerminalCatalogRun(tls, TerminalCatalogQueueHistoryV1.POLLING) { h ->
            val f = h.catalog; assertNonemptyHistory(h)
            val b = checkNotNull(h.queue)
            assertEquals("POLLING", b.observation()?.get("state")); assertTrue(b.raw.ackRequests.isEmpty())
            val before = h.fullImage(); val nativeStart = f.f.sealHttp.terminalInventoryRequests.size
            val journal = f.f.sealHttp.order.toList(); val original = f.begin()
            assertThrows<CatalogTestRunTerminalExceptionV1> { original.publish(f.unsigned, f.request()) }
            f.probe.assertReleased(requireCommitted = false); assertReadOnly(f.probe)
            assertTrue(f.probe.calls.any { it.sql == CatalogTestRunTerminalActiveHistorySqlV1.queue })
            assertEquals(before, h.fullImage()); assertEquals(nativeStart, f.f.sealHttp.terminalInventoryRequests.size)
            assertEquals(journal, f.f.sealHttp.order)
            assertTrue(f.files().isEmpty() && f.signatures.isEmpty() && f.http.bodies.isEmpty())
            assertTrue(f.ordinaryRequests.isEmpty() && f.ordinaryKeys.requests.isEmpty())
            assertEquals("POLLING", b.observation()?.get("state")); assertTrue(b.raw.ackRequests.isEmpty())
            assertThrows<RuntimeException> { original.publish(f.unsigned, f.request()) }
            assertThrows<RuntimeException> { f.d.beginTerminalCatalog() }
        }

    fun rowRefusal(tls: VersionBoundPersistenceConnectedFixture, fault: TerminalCatalogHistoryRowFaultV1) = withActiveHistoryTerminalCatalogRun(tls) { h ->
        val f = h.catalog
        withChangedA(f, fault) {
            val before = h.fullImage(); val native = f.f.sealHttp.terminalInventoryRequests.size
            val original = f.begin()
            assertThrows<CatalogTestRunTerminalExceptionV1> { original.publish(f.unsigned, f.request()) }
            f.probe.assertReleased(requireCommitted = false); assertReadOnly(f.probe)
            assertEquals(before, h.fullImage()); assertEquals(native, f.f.sealHttp.terminalInventoryRequests.size)
            assertTrue(f.probe.calls.any { it.sql == CatalogTestRunTerminalActiveHistorySqlV1.initialSeal })
            assertTrue(f.files().isEmpty() && f.ordinaryRequests.isEmpty() && f.signatures.isEmpty() && f.http.bodies.isEmpty())
            assertThrows<RuntimeException> { original.publish(f.unsigned, f.request()) }
        }
        // Restoring fixture content cannot restore the spent original or D's private physical preimage.
        assertThrows<RuntimeException> { f.d.beginTerminalCatalog() }
    }

    fun preparedPhysicalRewriteRefuses(tls: VersionBoundPersistenceConnectedFixture) = withActiveHistoryTerminalCatalogRun(tls) { h ->
        val f = h.catalog; val original = f.begin()
        assertEquals(CatalogTestRunTerminalResultV1.EXACT_PREPARED_AWAITING_DUAL_COPY, original.publish(f.unsigned, f.request()))
        original.requireActualCleanup(); val files = f.files(); val nativeStart = f.f.sealHttp.terminalInventoryRequests.size
        f.http.completeReplication()
        withChangedA(f, TerminalCatalogHistoryRowFaultV1.REWRITTEN_A_XMIN) {
            val before = h.fullImage()
            f.freshRecovery { recovery, probe ->
                assertThrows<CatalogTestRunTerminalExceptionV1> { recovery.resume(f.request()) }
                probe.assertReleased(requireCommitted = false); assertReadOnly(probe)
                assertEquals(before, h.fullImage()); assertEquals(files, f.files())
                assertTrue(f.f.sealHttp.terminalInventoryRequests.size > nativeStart,
                    "Fresh recovery checks real history, then refuses its changed physical preimage against old custody before authority.")
            }
        }
        assertPrepared(f); assertEquals(1, f.signatures.size); assertEquals(1, f.http.bodies.size)
    }

    fun nativeRefusal(tls: VersionBoundPersistenceConnectedFixture, fault: TerminalCatalogHistoryNativeFaultV1) = withActiveHistoryTerminalCatalogRun(tls) { h ->
        val f = h.catalog; val native = f.f.sealHttp; val before = h.fullImage()
        val initialKey = h.initialSeal.objectRef.objectKey; val beforePasses = native.terminalRecoverySessions.size
        val beforeReads = native.terminalInventoryRequests.size
        val listing = native.terminalInventoryListing; val objects = native.terminalInventoryObject
        native.terminalInventoryListing = { pass, values ->
            val actual = listing(pass, values)
            when (fault) {
                TerminalCatalogHistoryNativeFaultV1.MISSING_A -> actual.filterNot { it.key == initialKey }
                TerminalCatalogHistoryNativeFaultV1.SECOND_PASS_EXTRA_VERSION -> if (pass == beforePasses + 2)
                    actual + actual.single { it.key == initialKey }.copy(version = "unexpected-A-version") else actual
                else -> actual
            }
        }
        native.terminalInventoryObject = { pass, value ->
            val actual = objects(pass, value)
            if (actual.key != initialKey) actual else when (fault) {
                TerminalCatalogHistoryNativeFaultV1.CHANGED_RETENTION -> actual.copy(retainUntil = actual.retainUntil.plusSeconds(1))
                TerminalCatalogHistoryNativeFaultV1.CHANGED_LAST_MODIFIED -> actual.copy(lastModified = actual.lastModified.minusSeconds(1))
                else -> actual
            }
        }
        try { assertThrows<CatalogTestRunTerminalExceptionV1> { f.begin().publish(f.unsigned, f.request()) } }
        finally { native.terminalInventoryListing = listing; native.terminalInventoryObject = objects }
        f.probe.assertReleased(requireCommitted = false); assertReadOnly(f.probe)
        assertEquals(before, h.fullImage()); assertTrue(f.files().isEmpty() && f.signatures.isEmpty() && f.http.bodies.isEmpty())
        assertThrows<RuntimeException> { f.d.beginTerminalCatalog() }
        if (fault === TerminalCatalogHistoryNativeFaultV1.SECOND_PASS_EXTRA_VERSION) assertTrue(
            native.terminalInventoryRequests.drop(beforeReads).count { it.kind == "GET" } >= f.d.targets.size,
            "The genuine first full pass completes; an extra A version in pass two is never filtered away.")
    }

    /** Old framing oracle over genuinely captured preimages, not a hash/condition supplied to E. */
    fun legacyTwoSealCompatibility(tls: VersionBoundPersistenceConnectedFixture) = withTerminalCatalogRun(tls) { f ->
        f.http.replicateOnPut = true
        val original = f.begin()
        assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, original.publish(f.unsigned, f.request()))
        original.requireActualCleanup(); f.probe.assertReleased(); f.probe.assertHistoryOrder(active = false)
        assertEquals(2, f.record.sealSet.records().size); assertEquals(2L, f.manifest.history.epochSeals.count)
        val condition = ownedCutField(original, "precondition") as CatalogTestRunTerminalPreconditionV1
        val operation = checkNotNull(ownedCutField(condition, "operation"))
        val pairs = (ownedCutField(operation, "pairs") as List<*>).map { it as String }
        val sidecars = (ownedCutField(operation, "sidecars") as List<*>).map { it as String }
        assertTrue((ownedCutField(condition, "ordinary") as List<*>).isEmpty())
        val oldFields = listOf("kira-test-terminal-precondition-v1", f.scope.toString(), f.record.previousEnvelopeSha256) +
            pairs.flatMap { listOf("P/L", it) } + sidecars.flatMap { listOf("V21", it) } +
            listOf("INSTALLATIONS", ownedCutField(operation, "sourceHash") as String)
        val oldDigest = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output -> oldFields.forEach { field ->
                val value = field.toByteArray(Charsets.UTF_8); output.writeInt(value.size); output.write(value)
            } }
            Sha256.hex(bytes.toByteArray())
        }
        assertEquals(oldDigest, condition.physicalSha256, "No empty A/B tags or counts are appended to the old custody precondition.")
        val binding = Files.walk(f.root).use { paths -> paths.filter { it.fileName.toString() == "binding" }.toList().single() }
        val fields = Json.parseToJsonElement(Files.readString(binding)).jsonArray.map { it.jsonPrimitive.content }
        assertEquals(15, fields.size); assertEquals("catalog-test-run-terminal-release-v1", fields[0]); assertEquals(oldDigest, fields[11])
        assertFalse(fields.any { it == "V26" || it == "V29" })
        assertArrayEquals(f.unsigned, f.bytes("unsigned_bytes")); assertArrayEquals(f.bytes("envelope_bytes"), f.http.primaryBytes)
    }

    /** Independent LP32 range roots over the original PUT, not a supplied inventory/readback. */
    private fun assertNonemptyHistory(h: CatalogTestRunTerminalActiveHistoryFixtureV1) {
        val f = h.catalog; val a = checkNotNull(h.deletion); val stored = checkNotNull(a.record).stored
        val seals = f.record.sealSet.records(); val journal = f.process.consumers.journalConfiguration
        assertEquals(listOf(1L, 2L, 3L), seals.map { it.epochStartInclusive })
        assertEquals(listOf(1L, 2L, 3L), seals.map { it.epochEndInclusive })
        assertEquals(seals[0].objectRef.canonicalSha256, seals[1].precedingSealSha256)
        assertEquals(seals[1].objectRef.canonicalSha256, seals[2].precedingSealSha256)
        assertEquals(3L, f.manifest.history.epochSeals.count); assertEquals(2L, f.record.purge.document.preTerminalSeals.count)
        assertEquals(3L, f.record.purge.document.preTerminalInventory.count, "The ordinary original PLUS both ordinary seals are retained.")
        assertEquals(1L, f.record.installationManifest.summary.installationCount)
        fun frame(start: Long, end: Long, nonempty: Boolean): ByteArray = ByteArrayOutputStream().use { bytes ->
            val fields = listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest", journal.declaration().writer.generationId,
                journal.ordinaryPrefix, "TEST", f.scope.toString(), start.toString(), end.toString(), if (nonempty) "1" else "0") +
                (if (nonempty) listOf(stored.key, stored.version, Sha256.hex(stored.bytes)) else emptyList())
            DataOutputStream(bytes).use { output -> fields.forEach { field ->
                val value = field.toByteArray(Charsets.UTF_8); output.writeInt(value.size); output.write(value)
            } }
            bytes.toByteArray()
        }
        val full = frame(1, 2, true); val finalOrdinary = frame(2, 2, true); val emptyInitial = frame(1, 1, false)
        val cut = f.record.progress.completedCuts().single { it.prefixKind === TestTerminalDenialPrefixV1.ORDINARY }
        assertEquals(1L, cut.epochStartInclusive); assertEquals(2L, cut.epochEndInclusive); assertEquals(full.size.toLong(), cut.framedByteCount)
        listOf(cut.denial.firstInventory, cut.denial.secondInventory).forEach { witness ->
            assertEquals(1L, witness.versionCount); assertEquals(stored.bytes.size.toLong(), witness.byteCount); assertEquals(Sha256.hex(full), witness.sha256)
        }
        val json = TestTerminalJsonV1(journal)
        val initialBytes = checkNotNull(f.observer.queryForObject("SELECT canonical_bytes FROM complaint_test_active_seal_intents WHERE data_scope_id = ?", ByteArray::class.java, f.scope))
        val ordinaryBytes = checkNotNull(f.observer.queryForObject("SELECT canonical_bytes FROM complaint_test_terminal_intents " +
            "WHERE data_scope_id = ? AND object_kind = 'EPOCH_SEAL' AND object_ordinal = 0", ByteArray::class.java, f.scope))
        try {
            val initial = json.epochSeal(initialBytes); val ordinary = json.epochSeal(ordinaryBytes)
            assertEquals(0L, initial.eventCount); assertEquals(Sha256.hex(emptyInitial), initial.eventManifestSha256)
            assertEquals(1L, ordinary.eventCount); assertEquals(Sha256.hex(finalOrdinary), ordinary.eventManifestSha256)
            assertEquals(seals[0].objectRef.canonicalSha256, Sha256.hex(initialBytes)); assertEquals(seals[1].objectRef.canonicalSha256, Sha256.hex(ordinaryBytes))
            assertNotEquals(cut.denial.firstInventory.sha256, ordinary.eventManifestSha256, "The whole 1..2 denial is not the 2..2 successor seal.")
        } finally { initialBytes.fill(0); ordinaryBytes.fill(0); full.fill(0); finalOrdinary.fill(0); emptyInitial.fill(0) }
        assertEquals(2097152L, f.observer.queryForObject("SELECT charged_storage_bytes FROM complaint_test_active_seal_intents WHERE data_scope_id = ?", Long::class.java, f.scope))
        assertEquals(if (h.queue == null) 0 else 1, h.historyRows().getValue("V29").size)
        h.queue?.let { assertEquals(8192L, (it.observation()?.get("storage_bytes") as Number).toLong()) }
        val applied = f.observer.queryForMap("SELECT object_key, object_version, event_id, encode(ciphertext_hash, 'hex') AS wire_hash, journal_epoch " +
            "FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", f.scope)
        assertEquals(stored.key, applied["object_key"]); assertEquals(stored.version, applied["object_version"])
        assertEquals(checkNotNull(a.event).route.eventId, applied["event_id"]); assertEquals(Sha256.hex(stored.bytes), applied["wire_hash"])
        assertEquals(2L, applied["journal_epoch"])
    }

    private fun assertNonemptyNativePairs(h: CatalogTestRunTerminalActiveHistoryFixtureV1, nativeStart: Int, journalStart: Int, passes: Int) {
        val f = h.catalog; checkNotNull(f.originalOrdinary).assertReadPairs(passes)
        val native = f.f.sealHttp.terminalInventoryRequests.drop(nativeStart)
        assertEquals(passes * f.d.targets.size, native.count { it.kind == "GET" })
        assertEquals(passes * ((f.d.targets.size + 1) / 2), native.count { it.kind == "LIST" })
        assertEquals(passes, native.count { it.kind == "GET" && it.http.encodedPath().endsWith(h.initialSeal.objectRef.objectKey) })
        assertTrue(f.f.sealHttp.order.drop(journalStart).none { it == "GENERATE" || it == "PUT" })
    }

    private fun assertReadOnly(probe: CatalogTestRunTerminalSqlProbeV1) {
        assertTrue(probe.calls.none { it.path in setOf(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_ACQUIRE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_RELEASE) })
        assertTrue(probe.calls.none { Regex("\\b(?:UPDATE|INSERT|DELETE)\\s+(?:INTO\\s+|FROM\\s+)?complaint_").containsMatchIn(it.sql) })
        assertTrue(probe.calls.none { it.sql == CatalogTestRunTerminalProjectionSqlV1.insertAudit })
    }

    /** Corrupt one disposable TEST relation only for refusal. Restoration never precedes a successful use. */
    private fun withChangedA(f: CatalogTestRunTerminalFixtureV1, fault: TerminalCatalogHistoryRowFaultV1, action: () -> Unit) {
        requireConnectionFree()
        val old = checkNotNull(f.observer.queryForObject("SELECT to_jsonb(i)::text FROM complaint_test_active_seal_intents i WHERE data_scope_id = ?", String::class.java, f.scope))
        val before = f.observer.queryForObject("SELECT xmin::text FROM complaint_test_active_seal_intents WHERE data_scope_id = ?", String::class.java, f.scope)
        changeA(f) { jdbc -> assertEquals(1, when (fault) {
            TerminalCatalogHistoryRowFaultV1.MISSING_A -> jdbc.update("DELETE FROM complaint_test_active_seal_intents WHERE data_scope_id = ?", f.scope)
            TerminalCatalogHistoryRowFaultV1.FOREIGN_A_IDENTITY -> jdbc.update("UPDATE complaint_test_active_seal_intents SET database_identity = ? WHERE data_scope_id = ?", UUID.randomUUID(), f.scope)
            TerminalCatalogHistoryRowFaultV1.REWRITTEN_A_XMIN -> jdbc.update("UPDATE complaint_test_active_seal_intents SET charged_storage_bytes = charged_storage_bytes WHERE data_scope_id = ?", f.scope)
        }) }
        try {
            if (fault === TerminalCatalogHistoryRowFaultV1.REWRITTEN_A_XMIN) {
                assertEquals(old, f.observer.queryForObject("SELECT to_jsonb(i)::text FROM complaint_test_active_seal_intents i WHERE data_scope_id = ?", String::class.java, f.scope))
                assertNotEquals(before, f.observer.queryForObject("SELECT xmin::text FROM complaint_test_active_seal_intents WHERE data_scope_id = ?", String::class.java, f.scope))
            }
            action()
        } finally { changeA(f) { jdbc ->
            jdbc.update("DELETE FROM complaint_test_active_seal_intents WHERE data_scope_id = ?", f.scope)
            assertEquals(1, jdbc.update("INSERT INTO complaint_test_active_seal_intents SELECT * FROM jsonb_populate_record(NULL::complaint_test_active_seal_intents, ?::jsonb)", old))
        } }
    }
    private fun changeA(f: CatalogTestRunTerminalFixtureV1, action: (JdbcTemplate) -> Unit) {
        requireConnectionFree()
        f.f.raw { connection ->
            connection.autoCommit = false
            val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
            try {
                jdbc.execute("ALTER TABLE complaint_test_active_seal_intents DISABLE TRIGGER complaint_test_active_seal_immutable")
                action(jdbc)
                jdbc.execute("ALTER TABLE complaint_test_active_seal_intents ENABLE TRIGGER complaint_test_active_seal_immutable")
                connection.commit()
            } catch (problem: Throwable) { connection.rollback(); throw problem }
        }
    }
}
