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
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialPrefixV1
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplyRows
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplySql
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalActiveHistorySqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalPreconditionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalProjectionSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalResultV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalSqlV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueFixtureV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointDeletionFixtureV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainPersistenceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalBindingV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
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

    /** Both variants use B's original native readback; PREPARED does not borrow a SQL VERIFY. */
    fun allQueueSuccessful(tls: VersionBoundPersistenceConnectedFixture, verifyPublication: Boolean) =
        withNonemptyActiveHistoryTerminalCatalogRun(tls, TerminalCatalogQueueHistoryV1.SETTLED,
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, verifyPublication) { h ->
            val f = h.catalog; val b = checkNotNull(h.queue)
            assertEquals(verifyPublication, b.verifiedPublication)
            assertRetainedAllQueuePrimary(b, "CONVERTED"); assertNonemptyHistory(h)
            assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaints WHERE data_scope_id = ?", Long::class.java, f.scope))
            assertEquals(1L, f.record.installationManifest.summary.deletedCount)
            val protected = h.preservedRows(); val counters = f.f.p.counters(); val reserve = unused(f)
            val nativeStart = f.f.sealHttp.terminalInventoryRequests.size; val journalStart = f.f.sealHttp.order.size
            f.http.replicateOnPut = true
            val original = f.begin()
            assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, original.publish(f.unsigned, f.request()))
            original.requireActualCleanup(); f.probe.assertReleased(); f.probe.assertHistoryOrder(active = true); assertProjected(f)
            val charge = TestTerminalCapacityChargesV1.SCOPED_CATALOG + TestTerminalCapacityChargesV1.AUDIT
            assertCharge(f, counters, charge); assertEquals(reserve - charge, unused(f))
            assertEquals(protected, h.preservedRows(), "ALL queue proof, terminal identity, both TTLs and V26/V29 remain physical originals through E.")
            assertRetainedAllQueuePrimary(b, "CONVERTED"); assertNonemptyHistory(h); assertNonemptyNativePairs(h, nativeStart, journalStart, 2)
            assertEquals(1, f.signatures.size); assertEquals(1, f.http.bodies.size)
            assertArrayEquals(f.unsigned, f.bytes("unsigned_bytes")); assertArrayEquals(f.bytes("envelope_bytes"), f.http.bodies.single())
            CatalogTestRunTerminalCasesV1.assertOrder(f.probe)
        }

    fun allFamilyEvidenceRefuses(tls: VersionBoundPersistenceConnectedFixture) = allComparisonRefusals(tls, listOf(
        AllFault.MISSING_N, AllFault.N_FINGERPRINT, AllFault.MISSING_P, AllFault.P_VERSION,
        AllFault.MISSING_E, AllFault.E_HASH, AllFault.E_TIME, AllFault.MISSING_L, AllFault.L_PROMISE,
    ))

    fun allPrimarySummaryRefuses(tls: VersionBoundPersistenceConnectedFixture) = allComparisonRefusals(tls, listOf(
        AllFault.MISSING_SUMMARY, AllFault.DUPLICATE_SUMMARY, AllFault.BOTH_SUMMARIES, AllFault.SUMMARY_EVENT,
        AllFault.SUMMARY_TIME, AllFault.SUMMARY_ACTOR, AllFault.SUMMARY_EXTRA_DETAIL, AllFault.SUMMARY_STRING_COUNT,
        AllFault.SUMMARY_REMOVED, AllFault.SUMMARY_RESOURCES, AllFault.SUMMARY_INSTALLATIONS, AllFault.REMOVAL_ACTOR, AllFault.UNKNOWN_U,
    ))

    /** B produces every successful transition; only the labeled missing/restored input is SQL.
     * Reconstructed N/P/L leaves old aggregate charges behind and therefore does NOT qualify a
     * consistent restore or whole-run D reserve closure. The separate later-domain case does D. */
    fun allRecoveredChronology(tls: VersionBoundPersistenceConnectedFixture, reconstructed: Boolean) =
        withSealedNonemptyActiveHistoryTerminalRun(tls, TerminalCatalogQueueHistoryV1.SETTLED,
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, true,
            if (reconstructed) TerminalCatalogAllRecoveryHistoryV1.RECONSTRUCTED_PUBLICATION_AND_RECEIPTS
            else TerminalCatalogAllRecoveryHistoryV1.LATER_DOMAIN_AND_RESOURCE) { f, a, queue, inputs, _ ->
            val b = checkNotNull(queue); val charge = TerminalCatalogAllLiteralChargesV1
            val used = charge.applied + (if (reconstructed) charge.audit.scaled(4) else charge.resource + charge.audit.scaled(2))
            val times = assertRetainedAllHistory(b, used)
            if (reconstructed) {
                assertTrue(times.v < times.a && times.a <= times.t && times.t < times.r)
                assertEquals(times.t, times.d); assertTrue(times.receiptCreated > times.t)
            } else {
                assertTrue(times.a <= times.v && times.v <= times.t && times.t < times.d)
                assertEquals(times.d, times.r); assertTrue(times.receiptCreated <= times.t)
                assertNotEquals(times.receiptExpiry, times.verifierExpiry,
                    "The original receipt's T+192h is not rewritten to the later domain repair's D+192h.")
            }
            val summaries = b.observer.queryForList("SELECT created_at, detail->>'eventId' AS event_id, " +
                "(detail->>'removed')::int AS removed, (detail->>'reconstructed')::int AS resources, " +
                "(detail->>'installation')::int AS installations FROM audit_log WHERE complaint_data_scope_id = ? " +
                "AND action = 'COMPLAINT_RECOVERY_APPLIED' ORDER BY created_at, id", b.scope)
            assertEquals(if (reconstructed) 5 else 2, summaries.size)
            summaries.forEachIndexed { index, summary ->
                assertEquals(b.record.event.route.eventId, summary["event_id"])
                assertEquals(if (index == 0 || !reconstructed) 1 else 0, summary["removed"])
                assertEquals(if (index > 0 && !reconstructed) 1 else 0, summary["resources"])
                assertEquals(0, summary["installations"])
            }
            assertEquals(times.t, (summaries.first()["created_at"] as Timestamp).toInstant())
            assertEquals(times.r, (summaries.last()["created_at"] as Timestamp).toInstant())
            assertEquals(1L, b.count("complaint_deletion_journal_applied"))
            CatalogTerminalHistoryDrainProbeV1(f, a).use { probe ->
                val before = terminalCatalogAllComparisonRows(f.observer, f.scope)
                repeat(2) {
                    probe.replayAll() // Two fresh originals, not a reset of the first consumed continuation.
                    assertEquals(before, terminalCatalogAllComparisonRows(f.observer, f.scope),
                        "Completed registered replay is comparison-only, including counters, audits and every xmin.")
                }
                assertEquals(times, assertRetainedAllHistory(b, used))
                if (reconstructed) {
                    assertTrue(f.inventoryRequests.isEmpty() && f.inventoryKeys.requests.isEmpty())
                    assertEquals("PARTIAL", b.observer.queryForObject("SELECT state FROM complaint_recovery_capacity_reservations " +
                        "WHERE event_id = ?", String::class.java, b.record.event.route.eventId))
                } else drainRetainedAllHistory(f, a, inputs, probe, used)
            }
        }

    /** B's frozen reference history is not a performed second PUT or key rollover. Only the
     * original primary's own queue readback can complete N/P before C's registered replay/D. */
    fun allHistoricalChronology(tls: VersionBoundPersistenceConnectedFixture, prepared: Boolean) =
        withSealedNonemptyActiveHistoryTerminalRun(tls, TerminalCatalogQueueHistoryV1.SETTLED,
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, !prepared,
            if (prepared) TerminalCatalogAllRecoveryHistoryV1.HISTORICAL_ALIAS_BEFORE_PRIMARY_VERIFY
            else TerminalCatalogAllRecoveryHistoryV1.HISTORICAL_ALIAS_BEFORE_PRIMARY_COMPLETION) { f, a, queue, inputs, historical ->
            val b = checkNotNull(queue); val alias = checkNotNull(historical); val charge = TerminalCatalogAllLiteralChargesV1
            val used = charge.applied + charge.appliedEvent + charge.audit
            val times = assertRetainedAllHistory(b, used)
            assertTrue(times.a <= times.d && times.d < times.t); assertEquals(times.t, times.r)
            if (prepared) assertTrue(times.d < times.v && times.v <= times.t, "Primary's own persisted native VERIFY follows the alias erasure.")
            else assertTrue(times.v < times.d, "The existing genuine primary VERIFY is earlier and remains unchanged.")
            assertTrue(times.verifierExpiry < times.receiptExpiry,
                "Earlier alias D+192h is the credential boundary; later primary T+192h never extends it.")
            assertEquals(times.d, checkNotNull(b.observer.queryForObject("SELECT applied_at FROM complaint_deletion_journal_applied " +
                "WHERE object_key = ? AND object_version = ? AND data_scope_id = ?", Timestamp::class.java, alias.stored.key, alias.stored.version, b.scope)).toInstant())
            assertEquals(2L, b.count("complaint_deletion_journal_applied")); b.assertExpectedAppliedObjects()
            val summaries = b.observer.queryForList("SELECT created_at, detail->>'eventId' AS event_id, (detail->>'removed')::int AS removed, " +
                "(actor_user_id IS NULL AND complaint_actor_kind = 'SYSTEM' AND entity_type = 'complaint_scope' AND entity_id = ? " +
                "AND detail = jsonb_build_object('eventId', detail->>'eventId', 'removed', (detail->>'removed')::int, " +
                "'reconstructed', 0, 'installation', 0)) AS exact FROM audit_log WHERE complaint_data_scope_id = ? " +
                "AND action = 'COMPLAINT_RECOVERY_APPLIED' ORDER BY created_at, id", b.scope.toString(), b.scope)
            assertEquals(2, summaries.size)
            assertEquals(listOf(alias.event.route.eventId, b.record.event.route.eventId), summaries.map { it["event_id"] })
            assertEquals(listOf(1, 0), summaries.map { it["removed"] }); assertTrue(summaries.all { it["exact"] == true })
            assertEquals(listOf(times.d, times.t), summaries.map { (it["created_at"] as Timestamp).toInstant() })
            assertEquals(0L, b.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? " +
                "AND action = 'COMPLAINT_INSTALLATION_DELETED'", Long::class.java, b.scope))
            // Comparison-only savepoints; no failed D or registered capability is ever reset.
            allComparisonRefusals(f, a, listOf(AllFault.ALIAS_BEFORE_A, AllFault.ALIAS_AFTER_R))
            CatalogTerminalHistoryDrainProbeV1(f, a).use { probe ->
                val before = terminalCatalogAllComparisonRows(f.observer, f.scope)
                repeat(2) {
                    probe.replayAll()
                    assertEquals(before, terminalCatalogAllComparisonRows(f.observer, f.scope),
                        "Retained alias-family replay preserves both original expiries, proofs, U, audit and xmin.")
                }
                assertEquals(times, assertRetainedAllHistory(b, used))
                drainRetainedAllHistory(f, a, inputs, probe, used, alias)
            }
            b.assertExpectedAppliedObjects()
        }

    /** Alias-only B -> real SEALED run -> a fresh registered original. This is neither a second
     * queue delivery nor an HTTP-authentication test, and never calls the terminal D/E producers. */
    fun registeredPendingAllAfterAlias(tls: VersionBoundPersistenceConnectedFixture, prepared: Boolean) =
        withSealedNonemptyActiveHistoryTerminalRun(tls, TerminalCatalogQueueHistoryV1.SETTLED,
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, !prepared,
            if (prepared) TerminalCatalogAllRecoveryHistoryV1.HISTORICAL_ALIAS_BEFORE_PRIMARY_VERIFY
            else TerminalCatalogAllRecoveryHistoryV1.HISTORICAL_ALIAS_BEFORE_PRIMARY_COMPLETION,
            completeHistoricalPrimary = false) { f, a, queue, _, historical ->
            val b = checkNotNull(queue); val alias = checkNotNull(historical); val charge = TerminalCatalogAllLiteralChargesV1
            assertEquals(if (prepared) "PREPARED" else "VERIFIED", a.publication()["state"])
            assertEquals("AUTHORIZED_DELETE", b.receipt()["state"]); assertEquals(b.proofBeforeQueue, b.publicationProof())
            assertEquals(b.receiptBeforeQueue, b.receiptIdentity()); assertSame(alias.stored, b.raw.deliveredStored)
            assertEquals(1, b.raw.ackRequests.size); b.assertExpectedAppliedObjects()
            val firstDeletion = checkNotNull(b.observer.queryForObject("SELECT applied_at FROM complaint_deletion_journal_applied " +
                "WHERE object_key = ? AND object_version = ?", Timestamp::class.java, alias.stored.key, alias.stored.version)).toInstant()
            val reserve = b.observer.queryForMap("SELECT state, reserved_amounts::text, converted_amounts::text, converted_at " +
                "FROM complaint_recovery_capacity_reservations WHERE event_id = ?", b.record.event.route.eventId)
            assertEquals("PARTIAL", reserve["state"]); assertEquals(firstDeletion, (reserve["converted_at"] as Timestamp).toInstant())
            assertEquals(charge.promise.toLongArray().joinToString(",", "{", "}"), reserve["reserved_amounts"])
            assertEquals(charge.applied.toLongArray().joinToString(",", "{", "}"), reserve["converted_amounts"])
            fun reservationIdentity() = b.observer.queryForObject("SELECT (to_jsonb(l) - ARRAY['state','converted_amounts','converted_at'])::text " +
                "FROM complaint_recovery_capacity_reservations l WHERE event_id = ?", String::class.java, b.record.event.route.eventId)
            fun publicationIdentity() = b.observer.queryForObject("SELECT (to_jsonb(p) - ARRAY['state','object_version','ciphertext_hash'," +
                "'object_created_at','retain_until','verified_at','verification_bytes','verification_hash','applied_at'])::text " +
                "FROM complaint_journal_publications p WHERE event_id = ?", String::class.java, b.record.event.route.eventId)
            val y = reservationIdentity(); val primaryIdentity = publicationIdentity()
            val before = terminalCatalogAllComparisonRows(f.observer, f.scope); val counters = b.counters()
            CatalogTerminalHistoryDrainProbeV1(f, a).use { probe ->
                val native = CatalogTerminalOriginalOrdinaryHttpV1(a.process.consumers.journalConfiguration, b.record, probe::assertReleased)
                try {
                    probe.completePendingAll(prepared, native::primaryClient, native.keys::httpClient)
                    if (prepared) native.assertPrimaryReadback() else native.assertUnused()
                    val delta = charge.appliedEvent + charge.audit // Exactly 1 E + 1 audit = 98,304 bytes, no content refund.
                    assertAllHistoryTransfer(counters, b.counters(), use = delta)
                    val after = terminalCatalogAllComparisonRows(f.observer, f.scope)
                    val changed = setOf("installation_deletion_receipts", "complaint_journal_publications", "complaint_deletion_journal_applied",
                        "complaint_recovery_capacity_reservations", "audit", "counters")
                    assertEquals(before - changed, after - changed,
                        "First domain D, verifier D+192h, both versions and all domain/run/queue/control xmin are immutable.")
                    assertEquals(y, reservationIdentity()); assertEquals(primaryIdentity, publicationIdentity())
                    assertEquals(b.receiptBeforeQueue, b.receiptIdentity())
                    if (!prepared) assertEquals(b.proofBeforeQueue, b.publicationProof(), "VERIFIED resume cannot rewrite its original proof or retention.")
                    val oldApplied = before.getValue("complaint_deletion_journal_applied").single()
                    assertEquals(2, after.getValue("complaint_deletion_journal_applied").size)
                    assertTrue(oldApplied in after.getValue("complaint_deletion_journal_applied"), "Alias E, including xmin, is not rewritten.")
                    assertEquals(before.getValue("audit").size + 1, after.getValue("audit").size)
                    assertEquals(before.getValue("audit"), after.getValue("audit").dropLast(1), "Every existing SYSTEM alias/removal audit stays byte-for-byte/xmin unchanged.")
                    b.expectAppliedObjects(alias.stored, b.record.stored); b.assertExpectedAppliedObjects()
                    val times = assertRetainedAllHistory(b, charge.applied + delta)
                    assertEquals(firstDeletion, times.d); assertTrue(times.a <= times.d && times.d < times.t); assertEquals(times.t, times.r)
                    if (prepared) assertTrue(times.d < times.v && times.v <= times.t, "Primary's own released readback supplies the later V, not alias R.")
                    else assertTrue(times.v < times.d)
                    assertTrue(times.verifierExpiry < times.receiptExpiry)
                    val summary = b.observer.queryForMap("SELECT created_at, xmin::text AS stamp, " +
                        "(actor_user_id IS NULL AND complaint_actor_kind = 'INSTALLATION' AND entity_type = 'complaint_scope' AND entity_id = ? " +
                        "AND detail = jsonb_build_object('version', ?::bigint, 'removed', 0, 'reconstructed', 0)) AS exact " +
                        "FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_INSTALLATION_DELETED'",
                        b.scope.toString(), b.record.event.tuple.credentialVersion + 1L, b.scope)
                    assertEquals(true, summary["exact"]); assertEquals(times.t, (summary["created_at"] as Timestamp).toInstant())
                    val appliedStamp = b.observer.queryForObject("SELECT xmin::text FROM complaint_deletion_journal_applied WHERE object_key = ? AND object_version = ?",
                        String::class.java, b.record.stored.key, b.record.stored.version)
                    assertEquals(appliedStamp, summary["stamp"], "Existing counted audit adapter inserts on this primary APPLY transaction, not an ordinary/JPA holder.")
                    for ((table, key) in listOf("installation_deletion_receipts" to "publication_ref", "complaint_journal_publications" to "event_id",
                        "complaint_recovery_capacity_reservations" to "event_id")) {
                        assertEquals(appliedStamp, b.observer.queryForObject("SELECT xmin::text FROM $table WHERE $key = ?", String::class.java, b.record.event.route.eventId))
                    }
                    assertEquals(1L, b.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED'", Long::class.java, b.scope))
                    probe.replayAll() // Fresh registered completed replay; the first original was already proven consumed before SQL.
                    assertEquals(after, terminalCatalogAllComparisonRows(f.observer, f.scope), "C's completed-family replay performs no writes or refreshes.")
                    assertEquals(times, assertRetainedAllHistory(b, charge.applied + delta)); b.assertExpectedAppliedObjects()
                    assertTrue(f.inventoryRequests.isEmpty() && f.inventoryKeys.requests.isEmpty())
                } finally { native.assertClosed() }
            }
        }

    /** Fresh genuine PREPARED history for each corruption, then an actual registered RELOAD.
     * These committed negative inputs are disposable; none seed successful proof or history. */
    fun registeredPendingAllAliasReloadRefusals(tls: VersionBoundPersistenceConnectedFixture) {
        for (fault in listOf(PendingAllFault.MISSING_E, PendingAllFault.UNKNOWN_U, PendingAllFault.SUMMARY_REMOVED, PendingAllFault.MISSING_VERIFIER)) {
            withSealedNonemptyActiveHistoryTerminalRun(tls, TerminalCatalogQueueHistoryV1.SETTLED,
                ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, false, TerminalCatalogAllRecoveryHistoryV1.HISTORICAL_ALIAS_BEFORE_PRIMARY_VERIFY,
                completeHistoricalPrimary = false) { f, a, queue, _, _ ->
                val b = checkNotNull(queue); val before = terminalCatalogAllComparisonRows(f.observer, f.scope)
                b.adversarialTransaction { corruptPendingAll(it, a, fault) }
                val damaged = terminalCatalogAllComparisonRows(f.observer, f.scope)
                assertNotEquals(before, damaged, fault.name); assertEquals(before - fault.table, damaged - fault.table)
                CatalogTerminalHistoryDrainProbeV1(f, a).use { probe ->
                    val native = CatalogTerminalOriginalOrdinaryHttpV1(a.process.consumers.journalConfiguration, b.record, probe::assertReleased)
                    try {
                        val expected = if (fault === PendingAllFault.MISSING_VERIFIER) OwnerDeleteAllApplySql.test(a.process.consumers.journalConfiguration.scope).LOCK_CREDENTIAL
                            else TestOrdinaryDrainSqlV1.appliedFamily
                        probe.refusePendingAll(expected, native::primaryClient, native.keys::httpClient)
                        native.assertUnused()
                        assertEquals(damaged, terminalCatalogAllComparisonRows(f.observer, f.scope), "Refused RELOAD cannot publish, complete, repair, refund or alter any xmin: ${fault.name}")
                        assertEquals("PREPARED", a.publication()["state"]); assertEquals("AUTHORIZED_DELETE", b.receipt()["state"])
                        assertEquals(b.proofBeforeQueue, b.publicationProof())
                        assertTrue(f.inventoryRequests.isEmpty() && f.inventoryKeys.requests.isEmpty())
                    } finally { native.assertClosed() }
                }
            }
        }
    }

    /** Both faults occur inside a real APPLY after a valid VERIFIED RELOAD has committed and
     * released. Actual rollback restores their bytes/xmin too; no spent original is reset. */
    fun registeredPendingAllAliasApplyRefusals(tls: VersionBoundPersistenceConnectedFixture) {
        for (fault in listOf(PendingAllFault.MISSING_E, PendingAllFault.CHANGED_VERIFIER)) {
            withSealedNonemptyActiveHistoryTerminalRun(tls, TerminalCatalogQueueHistoryV1.SETTLED,
                ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, true, TerminalCatalogAllRecoveryHistoryV1.HISTORICAL_ALIAS_BEFORE_PRIMARY_COMPLETION,
                completeHistoricalPrimary = false) { f, a, queue, _, _ ->
                val b = checkNotNull(queue); val before = terminalCatalogAllComparisonRows(f.observer, f.scope)
                CatalogTerminalHistoryDrainProbeV1(f, a).use { probe ->
                    val native = CatalogTerminalOriginalOrdinaryHttpV1(a.process.consumers.journalConfiguration, b.record, probe::assertReleased)
                    try {
                        probe.refusePendingAll(TestOrdinaryDrainSqlV1.appliedFamily, native::primaryClient, native.keys::httpClient) { jdbc ->
                            corruptPendingAll(jdbc, a, fault)
                            val damaged = jdbc.queryForList("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM ${fault.table} t " +
                                "WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text", String::class.java, f.scope)
                            assertNotEquals(before.getValue(fault.table), damaged, fault.name)
                        }
                        native.assertUnused()
                        assertEquals(before, terminalCatalogAllComparisonRows(f.observer, f.scope), "Real APPLY rollback preserves all N/P/E/Y/U/domain/audit/counter bytes and xmin: ${fault.name}")
                        assertEquals("VERIFIED", a.publication()["state"]); assertEquals("AUTHORIZED_DELETE", b.receipt()["state"])
                        assertEquals(b.proofBeforeQueue, b.publicationProof()); b.assertExpectedAppliedObjects()
                        assertTrue(f.inventoryRequests.isEmpty() && f.inventoryKeys.requests.isEmpty())
                    } finally { native.assertClosed() }
                }
            }
        }
    }

    private enum class PendingAllFault(val table: String) {
        MISSING_E("complaint_deletion_journal_applied"), UNKNOWN_U("complaint_recovery_capacity_reservations"), SUMMARY_REMOVED("audit"),
        MISSING_VERIFIER("app_installations"), CHANGED_VERIFIER("app_installations"),
    }

    private fun corruptPendingAll(jdbc: JdbcTemplate, a: TestRegisteredInitialCheckpointDeletionFixtureV1, fault: PendingAllFault) {
        when (fault) {
            PendingAllFault.MISSING_E -> corruptAllComparison(jdbc, a, AllFault.MISSING_E)
            PendingAllFault.UNKNOWN_U -> corruptAllComparison(jdbc, a, AllFault.UNKNOWN_U)
            PendingAllFault.SUMMARY_REMOVED -> corruptAllComparison(jdbc, a, AllFault.SUMMARY_REMOVED)
            PendingAllFault.MISSING_VERIFIER -> assertEquals(1, jdbc.update("DELETE FROM app_installations WHERE id = ? AND data_scope_id = ?", a.actor.id, a.scope))
            PendingAllFault.CHANGED_VERIFIER -> assertEquals(1, jdbc.update("UPDATE app_installations SET secret_verifier = " +
                "set_byte(secret_verifier, 0, get_byte(secret_verifier, 0) # 1) WHERE id = ? AND data_scope_id = ?", a.actor.id, a.scope))
        }
    }

    fun allRetainedTimelineRefuses(tls: VersionBoundPersistenceConnectedFixture) = allComparisonRefusals(tls, listOf(
        AllFault.A_AFTER_T, AllFault.V_AFTER_T, AllFault.R_BEFORE_T, AllFault.R_UNACCOUNTED_AFTER_T,
        AllFault.D_NOT_SUMMARIZED, AllFault.D_PAIRING, AllFault.E_TIME, AllFault.UNKNOWN_U,
    ))

    /** Missing credential uses an actual fresh registered refusal. Expiry boundaries exercise
     * only the shared strict comparison policy at future scalar times, not elapsed DB time or
     * HTTP authentication. No row is backdated, no clock is injected and no credential reinserted. */
    fun allRegisteredReplayRefusals(tls: VersionBoundPersistenceConnectedFixture) =
        withSealedNonemptyActiveHistoryTerminalRun(tls, TerminalCatalogQueueHistoryV1.SETTLED,
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, true) { f, a, queue, _, _ ->
            val b = checkNotNull(queue)
            assertRetainedAllQueuePrimary(b, "PARTIAL")
            CatalogTerminalHistoryDrainProbeV1(f, a).use { probe ->
                val before = terminalCatalogAllComparisonRows(f.observer, f.scope)
                // These deliberately independent scalar expiries are policy inputs, NOT a
                // purported retained row history. SQL still enforces each exact 192h formula.
                val future = (b.receipt()["expires_at"] as Timestamp).toInstant()
                val earlier = future.minusNanos(1_000); val later = future.plusNanos(1_000)
                assertTrue(OwnerDeleteAllApplyRows.completedReplayWindowsLive(later, later, future))
                assertFalse(OwnerDeleteAllApplyRows.completedReplayWindowsLive(earlier, later, future))
                assertFalse(OwnerDeleteAllApplyRows.completedReplayWindowsLive(future, later, future))
                assertFalse(OwnerDeleteAllApplyRows.completedReplayWindowsLive(later, earlier, future))
                assertFalse(OwnerDeleteAllApplyRows.completedReplayWindowsLive(later, future, future))
                assertFalse(OwnerDeleteAllApplyRows.completedReplayWindowsLive(earlier, earlier, future))
                assertFalse(OwnerDeleteAllApplyRows.completedReplayWindowsLive(null, later, future))
                assertFalse(OwnerDeleteAllApplyRows.completedReplayWindowsLive(later, null, future))
                assertEquals(before, terminalCatalogAllComparisonRows(f.observer, f.scope))
                probe.replayAll()
                assertEquals(before, terminalCatalogAllComparisonRows(f.observer, f.scope))
                assertEquals(1, f.observer.update("DELETE FROM app_installations WHERE id = ? AND data_scope_id = ?", a.actor.id, f.scope))
                val damaged = terminalCatalogAllComparisonRows(f.observer, f.scope)
                assertEquals(before - "app_installations", damaged - "app_installations")
                assertTrue(damaged.getValue("app_installations").isEmpty())
                probe.refuseMissingAllVerifier()
                assertEquals(damaged, terminalCatalogAllComparisonRows(f.observer, f.scope),
                    "Missing verifier refuses without N/P/E/L/domain, audit, counters or xmin mutation.")
                assertTrue(f.inventoryRequests.isEmpty() && f.inventoryKeys.requests.isEmpty())
            }
        }

    /** Read actual retained bytes/rows. These scalar observations never supply recovery authority. */
    private fun assertRetainedAllHistory(b: TestActiveOwnerDeleteQueueFixtureV1, used: ComplaintCapacityVector): AllTimeline {
        val record = b.record; val event = record.event; val stored = record.stored
        val p = b.observer.queryForMap("SELECT * FROM complaint_journal_publications WHERE event_id = ? AND data_scope_id = ?",
            event.route.eventId, b.scope)
        val n = b.receipt()
        assertEquals("APPLIED", p["state"]); assertEquals("COMPLETED", n["state"])
        assertEquals(event.tuple.operationKey, n["deletion_key"]); assertEquals(event.tuple.credentialVersion, n["submitted_credential_version"])
        assertArrayEquals(event.tuple.fingerprintBytes(), n["fingerprint"] as ByteArray)
        val canonical = event.canonicalBytes()
        try {
            assertArrayEquals(canonical, p["event_bytes"] as ByteArray)
            assertEquals(Sha256.hex(canonical), HexFormat.of().formatHex(p["semantic_hash"] as ByteArray))
        } finally { canonical.fill(0) }
        assertEquals(event.route.eventId, p["event_id"]); assertEquals(event.route.routingKeyId, p["routing_key_id"])
        assertEquals(stored.key, p["object_key"]); assertEquals(stored.version, p["object_version"])
        assertEquals(event.comparison.epoch, p["journal_epoch"]); assertEquals(event.complaintIds().size, p["target_count"])
        assertEquals(Sha256.hex(stored.bytes), HexFormat.of().formatHex(p["ciphertext_hash"] as ByteArray))
        assertEquals(stored.lastModified, (p["object_created_at"] as Timestamp).toInstant())
        assertEquals(stored.retainUntil, (p["retain_until"] as Timestamp).toInstant())
        val binding = OwnerDeleteAllJournalBindingV1(b.precursor.process.consumers.journalRouting)
        val bytes = p["verification_bytes"] as ByteArray
        val proof = OwnerDeleteAllVerificationCodecV1(binding).parse(bytes, binding.fromTest(event))
        assertEquals(Sha256.hex(bytes), HexFormat.of().formatHex(p["verification_hash"] as ByteArray))
        assertEquals(stored.key, proof.objectKey); assertEquals(stored.version, proof.objectVersion)
        assertEquals(Sha256.hex(stored.bytes), proof.ciphertextSha256)
        assertEquals(stored.lastModified, Instant.parse(proof.objectCreatedAt)); assertEquals(stored.retainUntil, Instant.parse(proof.retainUntil))
        assertEquals(p["writer_generation"].toString(), proof.writerGeneration)
        assertEquals(p["verified_at"], Timestamp.from(Instant.parse(proof.verifiedAt)))
        assertEquals(p["created_at"], n["authorized_at"]); assertEquals(p["applied_at"], n["completed_at"])
        assertEquals(p["event_id"], n["publication_ref"]); assertEquals(p["event_id"], n["external_event_id"])
        assertEquals(p["journal_epoch"], n["external_epoch"]); assertEquals(stored.version, n["external_object_version"])
        assertArrayEquals(p["ciphertext_hash"] as ByteArray, n["external_ciphertext_hash"] as ByteArray)
        val e = b.observer.queryForMap("SELECT * FROM complaint_deletion_journal_applied WHERE object_key = ? AND object_version = ?", stored.key, stored.version)
        for (name in listOf("event_id", "object_key", "object_version", "writer_generation", "journal_epoch", "event_kind", "target_count", "data_scope_id", "test_only", "applied_at")) {
            assertEquals(p[name], e[name], name)
        }
        assertArrayEquals(p["ciphertext_hash"] as ByteArray, e["ciphertext_hash"] as ByteArray)
        val l = b.observer.queryForMap("SELECT state, reserved_amounts::text, converted_amounts::text, converted_at " +
            "FROM complaint_recovery_capacity_reservations WHERE event_id = ? AND data_scope_id = ?", event.route.eventId, b.scope)
        assertEquals("PARTIAL", l["state"])
        assertEquals(TerminalCatalogAllLiteralChargesV1.promise.toLongArray().joinToString(",", "{", "}"), l["reserved_amounts"])
        assertEquals(used.toLongArray().joinToString(",", "{", "}"), l["converted_amounts"])
        val identity = b.observer.queryForMap("SELECT state, terminal_at FROM complaint_installation_ids WHERE id = ? AND data_scope_id = ?", b.precursor.actor.id, b.scope)
        val credential = b.observer.queryForMap("SELECT state, credential_version, deleted_at, verifier_expires_at FROM app_installations WHERE id = ? AND data_scope_id = ?",
            b.precursor.actor.id, b.scope)
        assertEquals("DELETED", identity["state"]); assertEquals("DELETED", credential["state"])
        assertEquals(event.tuple.credentialVersion + 1L, credential["credential_version"])
        assertEquals(identity["terminal_at"], credential["deleted_at"])
        val times = AllTimeline((p["created_at"] as Timestamp).toInstant(), Instant.parse(proof.verifiedAt), (p["applied_at"] as Timestamp).toInstant(),
            (identity["terminal_at"] as Timestamp).toInstant(), (l["converted_at"] as Timestamp).toInstant(), (n["created_at"] as Timestamp).toInstant(),
            (n["expires_at"] as Timestamp).toInstant(), (credential["verifier_expires_at"] as Timestamp).toInstant())
        assertTrue(times.a <= times.t && times.v <= times.t && times.t <= times.r && times.d in times.a..times.r)
        assertEquals(times.t.plus(Duration.ofHours(192)), times.receiptExpiry)
        assertEquals(times.d.plus(Duration.ofHours(192)), times.verifierExpiry)
        // Pure boundary comparison of these actual retained expiries, not an elapsed-time or
        // HTTP replay. Later-domain and early-alias histories independently choose the first expiry.
        val firstExpiry = minOf(times.receiptExpiry, times.verifierExpiry)
        assertTrue(OwnerDeleteAllApplyRows.completedReplayWindowsLive(times.receiptExpiry, times.verifierExpiry, firstExpiry.minusNanos(1_000)))
        assertFalse(OwnerDeleteAllApplyRows.completedReplayWindowsLive(times.receiptExpiry, times.verifierExpiry, firstExpiry))
        assertEquals(0L, b.observer.queryForObject("SELECT count(*) FROM complaints WHERE owner_id = ?", Long::class.java, b.precursor.actor.id))
        return times
    }

    private data class AllTimeline(val a: Instant, val v: Instant, val t: Instant, val d: Instant, val r: Instant,
        val receiptCreated: Instant, val receiptExpiry: Instant, val verifierExpiry: Instant)

    /**
     * Real B+SEALED fixture once per selector. These are comparison-only refusals, NOT failed
     * D/E originals: each deliberate SQL fault is rolled back to its exact original xmin using
     * a savepoint. Nothing restores a spent capability or seeds a new successful family.
     */
    private fun allComparisonRefusals(tls: VersionBoundPersistenceConnectedFixture, faults: List<AllFault>) =
        withSealedNonemptyActiveHistoryTerminalRun(tls, TerminalCatalogQueueHistoryV1.SETTLED,
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, true) { f, a, b, _, _ ->
            assertRetainedAllQueuePrimary(checkNotNull(b), "PARTIAL")
            allComparisonRefusals(f, a, faults)
            assertRetainedAllQueuePrimary(b, "PARTIAL")
        }

    private fun allComparisonRefusals(f: TestRunPurgeFixtureV1, a: TestRegisteredInitialCheckpointDeletionFixtureV1, faults: List<AllFault>) {
        val context = TestRunOrdinaryDrainV1.withHttpFixture(a.registration, a.deletionOwner, a.deletion, a.audit,
            Clock.systemUTC(), System::nanoTime, { error("Comparison tests cannot dispatch S3.") }, { error("Comparison tests cannot dispatch KMS.") })
        val before = terminalCatalogAllComparisonRows(f.observer, f.scope)
        val originalCalls = a.deletion.calls.size
        a.raw { connection ->
            connection.autoCommit = false
            val source = SingleConnectionDataSource(connection, true)
            val input = JdbcTemplate(source)
            val comparison = object : JdbcTemplate(source) {
                override fun update(sql: String, vararg args: Any?): Int = throw AssertionError("ALL fact comparisons must never write SQL.")
            }
            try {
                requireAllComparison(comparison, context, a) // Uncorrupted genuine facts pass; no SQL-error false-positive oracle.
                assertEquals(before, terminalCatalogAllComparisonRows(input, f.scope))
                faults.forEach { fault ->
                    val savepoint = connection.setSavepoint()
                    try {
                        corruptAllComparison(input, a, fault)
                        val damaged = terminalCatalogAllComparisonRows(input, f.scope)
                        assertNotEquals(before, damaged, fault.name)
                        val failure = assertThrows<RuntimeException>(fault.name) { requireAllComparison(comparison, context, a) }
                        assertTrue(failure is TestOrdinaryDrainExceptionV1 || failure is NoSuchElementException || failure is IllegalStateException,
                            "Refusal must be a row/comparison invariant, not an SQL-dispatch error: ${fault.name}")
                        assertEquals(damaged, terminalCatalogAllComparisonRows(input, f.scope), "No comparison write, refund or authority: ${fault.name}")
                    } finally { connection.rollback(savepoint); connection.releaseSavepoint(savepoint) }
                    assertEquals(before, terminalCatalogAllComparisonRows(input, f.scope), "Rollback restores actual bytes AND xmin: ${fault.name}")
                }
            } finally { connection.rollback() }
        }
        assertEquals(before, terminalCatalogAllComparisonRows(f.observer, f.scope))
        assertEquals(originalCalls, a.deletion.calls.size, "Comparison helpers never invoke a registered owner, VERIFY or APPLY.")
        assertTrue(f.inventoryRequests.isEmpty() && f.inventoryKeys.requests.isEmpty())
        a.assertReleased(); f.assertReleased()
    }

    private fun requireAllComparison(jdbc: JdbcTemplate, context: TestRunOrdinaryDrainV1, a: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val event = checkNotNull(a.event)
        val facts = TestOrdinaryDrainPersistenceV1.FamilyFacts(a.process.consumers.journalRouting, event.comparison.epoch)
        val run = jdbc.query(TestOrdinaryDrainSqlV1.runWithActiveHistory.removeSuffix(" FOR UPDATE OF r"),
            { row, _ -> TestOrdinaryDrainRowsV1.Run(row, context) }, *a.registration.sealingRunArguments()).single()
        val value = TestOrdinaryDrainPersistenceV1.closedPrimary(jdbc, facts, event.route.eventId)
        val family = TestOrdinaryDrainPersistenceV1.readFamilyFacts(jdbc, facts, value, checkNotNull(value.publication.verifiedAt))
        TestOrdinaryDrainPersistenceV1.requirePrimaryFacts(jdbc, facts, run, value, family, converted = false, lockDomain = false)
    }

    private enum class AllFault {
        MISSING_N, N_FINGERPRINT, MISSING_P, P_VERSION, MISSING_E, E_HASH, E_TIME, MISSING_L, L_PROMISE,
        MISSING_SUMMARY, DUPLICATE_SUMMARY, BOTH_SUMMARIES, SUMMARY_EVENT, SUMMARY_TIME, SUMMARY_ACTOR,
        SUMMARY_EXTRA_DETAIL, SUMMARY_STRING_COUNT, SUMMARY_REMOVED, SUMMARY_RESOURCES, SUMMARY_INSTALLATIONS, REMOVAL_ACTOR, UNKNOWN_U,
        A_AFTER_T, V_AFTER_T, R_BEFORE_T, R_UNACCOUNTED_AFTER_T, D_NOT_SUMMARIZED, D_PAIRING,
        ALIAS_BEFORE_A, ALIAS_AFTER_R,
    }

    /** Disposable comparison inputs only. No mutation here is a producer or a recovery fixture. */
    private fun corruptAllComparison(jdbc: JdbcTemplate, a: TestRegisteredInitialCheckpointDeletionFixtureV1, fault: AllFault) {
        val scope = a.scope
        fun change(table: String, assignment: String): Int = jdbc.update("UPDATE $table SET $assignment WHERE data_scope_id = ?", scope)
        fun summary(assignment: String): Int = jdbc.update("UPDATE audit_log SET $assignment WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED'", scope)
        val count = when (fault) {
            AllFault.MISSING_N -> jdbc.update("DELETE FROM installation_deletion_receipts WHERE data_scope_id = ?", scope)
            AllFault.N_FINGERPRINT -> change("installation_deletion_receipts", "fingerprint = set_byte(fingerprint, 0, get_byte(fingerprint, 0) # 1)")
            AllFault.MISSING_P -> {
                // Only this negative breaches the retained N/P foreign key. Rollback restores it;
                // no trigger bypass, deleted row or synthesized native evidence enters a positive.
                jdbc.execute("SET LOCAL session_replication_role = 'replica'")
                try { jdbc.update("DELETE FROM complaint_journal_publications WHERE data_scope_id = ?", scope) }
                finally { jdbc.execute("SET LOCAL session_replication_role = 'origin'") }
            }
            AllFault.P_VERSION -> change("complaint_journal_publications", "object_version = object_version || '-mismatch'")
            AllFault.MISSING_E -> jdbc.update("DELETE FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", scope)
            AllFault.E_HASH -> change("complaint_deletion_journal_applied", "ciphertext_hash = set_byte(ciphertext_hash, 0, get_byte(ciphertext_hash, 0) # 1)")
            AllFault.E_TIME -> {
                assertEquals(true, jdbc.queryForObject("SELECT verified_at < applied_at FROM complaint_journal_publications WHERE data_scope_id = ?", Boolean::class.java, scope))
                jdbc.update("UPDATE complaint_deletion_journal_applied e SET applied_at = p.verified_at FROM complaint_journal_publications p " +
                    "WHERE e.data_scope_id = ? AND e.event_id = p.event_id", scope) // Still within the old VERIFY..last range, but not N/P completion.
            }
            AllFault.MISSING_L -> jdbc.update("DELETE FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", scope)
            AllFault.L_PROMISE -> change("complaint_recovery_capacity_reservations", "reserved_amounts[2] = reserved_amounts[2] - 1")
            AllFault.MISSING_SUMMARY -> jdbc.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED'", scope)
            AllFault.DUPLICATE_SUMMARY, AllFault.BOTH_SUMMARIES -> {
                val duplicate = fault === AllFault.DUPLICATE_SUMMARY
                // Explicit negative id avoids advancing a nontransactional identity sequence.
                jdbc.update("INSERT INTO audit_log (id, actor_user_id, action, entity_type, entity_id, detail, created_at, complaint_data_scope_id, complaint_actor_kind) " +
                    "OVERRIDING SYSTEM VALUE SELECT -id, actor_user_id, " + (if (duplicate) "action" else "'COMPLAINT_INSTALLATION_DELETED'") +
                    ", entity_type, entity_id, " + (if (duplicate) "detail" else "jsonb_build_object('version', ${checkNotNull(a.event).tuple.credentialVersion + 1L}, 'removed', 1, 'reconstructed', 0)") +
                    ", created_at, complaint_data_scope_id, " + (if (duplicate) "complaint_actor_kind" else "'INSTALLATION'") +
                    " FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED'", scope)
            }
            AllFault.SUMMARY_EVENT -> {
                val event = checkNotNull(a.event)
                val other = a.process.consumers.journalRouting.derive(event.tuple).candidates().first { it.eventId != event.route.eventId }
                jdbc.update("UPDATE audit_log SET detail = jsonb_set(detail, '{eventId}', to_jsonb(?::text)) " +
                    "WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED'", other.eventId, scope)
            }
            AllFault.SUMMARY_TIME -> summary("created_at = created_at + interval '1 microsecond'")
            AllFault.SUMMARY_ACTOR -> summary("complaint_actor_kind = 'INSTALLATION'")
            AllFault.SUMMARY_EXTRA_DETAIL -> summary("detail = detail || '{\"unexpected\": 1}'::jsonb")
            AllFault.SUMMARY_STRING_COUNT -> summary("detail = jsonb_set(detail, '{removed}', '\"1\"'::jsonb)")
            AllFault.SUMMARY_REMOVED -> summary("detail = jsonb_set(detail, '{removed}', '2'::jsonb)")
            AllFault.SUMMARY_RESOURCES -> summary("detail = jsonb_set(detail, '{reconstructed}', '1'::jsonb)")
            AllFault.SUMMARY_INSTALLATIONS -> summary("detail = jsonb_set(detail, '{installation}', '1'::jsonb)")
            AllFault.REMOVAL_ACTOR -> jdbc.update("UPDATE audit_log SET complaint_actor_kind = 'INSTALLATION' WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_DELETED'", scope)
            AllFault.UNKNOWN_U -> change("complaint_recovery_capacity_reservations", "converted_amounts[2] = converted_amounts[2] + 1, converted_amounts[21] = converted_amounts[21] + 65536")
            AllFault.A_AFTER_T -> {
                assertEquals(1, change("complaint_journal_publications", "created_at = applied_at + interval '1 microsecond'"))
                jdbc.update("UPDATE installation_deletion_receipts n SET authorized_at = p.created_at FROM complaint_journal_publications p " +
                    "WHERE n.data_scope_id = ? AND n.publication_ref = p.event_id", scope)
            }
            AllFault.V_AFTER_T -> {
                // Re-encode comparison bytes only for a deliberate negative, never a native VERIFY.
                val p = jdbc.queryForMap("SELECT verification_bytes, applied_at FROM complaint_journal_publications WHERE data_scope_id = ?", scope)
                val binding = OwnerDeleteAllJournalBindingV1(a.process.consumers.journalRouting)
                val codec = OwnerDeleteAllVerificationCodecV1(binding)
                val proof = codec.parse(p["verification_bytes"] as ByteArray, binding.fromTest(checkNotNull(a.event)))
                val at = (p["applied_at"] as Timestamp).toInstant().plusNanos(1_000)
                val bytes = codec.canonicalBytes(proof.copy(verifiedAt = at.toString()))
                try { jdbc.update("UPDATE complaint_journal_publications SET verified_at = ?, verification_bytes = ?, verification_hash = decode(?, 'hex') " +
                    "WHERE data_scope_id = ?", Timestamp.from(at), bytes, Sha256.hex(bytes), scope) }
                finally { bytes.fill(0) }
            }
            AllFault.R_BEFORE_T -> {
                assertEquals(true, jdbc.queryForObject("SELECT verified_at < applied_at FROM complaint_journal_publications WHERE data_scope_id = ?", Boolean::class.java, scope))
                jdbc.update("UPDATE complaint_recovery_capacity_reservations l SET converted_at = p.verified_at FROM complaint_journal_publications p " +
                    "WHERE l.data_scope_id = ? AND l.event_id = p.event_id", scope)
            }
            AllFault.R_UNACCOUNTED_AFTER_T -> change("complaint_recovery_capacity_reservations", "converted_at = converted_at + interval '1 microsecond'")
            AllFault.D_NOT_SUMMARIZED, AllFault.D_PAIRING -> {
                assertEquals(true, jdbc.queryForObject("SELECT created_at <= verified_at AND verified_at < applied_at FROM complaint_journal_publications " +
                    "WHERE data_scope_id = ?", Boolean::class.java, scope))
                if (fault === AllFault.D_NOT_SUMMARIZED) assertEquals(1, jdbc.update("UPDATE complaint_installation_ids i SET terminal_at = p.verified_at " +
                    "FROM complaint_journal_publications p WHERE i.data_scope_id = ? AND p.data_scope_id = i.data_scope_id", scope))
                // Keep the exact 192h CHECK satisfied. D mismatch/unsupported D, not SQL rejection, is the oracle.
                jdbc.update("UPDATE app_installations c SET deleted_at = p.verified_at, verifier_expires_at = p.verified_at + interval '192 hours' " +
                    "FROM complaint_journal_publications p WHERE c.data_scope_id = ? AND p.data_scope_id = c.data_scope_id", scope)
            }
            AllFault.ALIAS_BEFORE_A, AllFault.ALIAS_AFTER_R -> jdbc.update("UPDATE complaint_deletion_journal_applied e SET applied_at = " +
                (if (fault === AllFault.ALIAS_BEFORE_A) "p.created_at - interval '1 microsecond'" else "l.converted_at + interval '1 microsecond'") +
                " FROM complaint_journal_publications p JOIN complaint_recovery_capacity_reservations l ON l.event_id = p.event_id " +
                "WHERE e.data_scope_id = ? AND p.data_scope_id = e.data_scope_id AND e.event_id <> p.event_id", scope)
        }
        assertEquals(1, count, fault.name)
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
