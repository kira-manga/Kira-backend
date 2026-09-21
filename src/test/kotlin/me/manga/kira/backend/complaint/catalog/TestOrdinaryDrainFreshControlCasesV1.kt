package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingSqlV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Exact MAIN SQL comparisons only, not an admitted drain/denial capability or native-success oracle. */
internal object TestOrdinaryDrainFreshControlCasesV1 {
    fun initialFlagAndNoninitialRefusal(tls: VersionBoundPersistenceConnectedFixture) = withUnusedSealedPurgeRun(tls) { f ->
        val counters = f.p.counters()
        compareControl(f.observer, f.scope, expected = true)
        // Negative-only drift. No flag clearing, history fabrication, reset or later successful drain.
        assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET publication_epoch = 2 " +
            "WHERE data_scope_id = ? AND test_only AND publication_epoch = 1 AND rotation_sequence = 0 AND scan_requested", f.scope))
        compareControl(f.observer, f.scope, expected = false)
        assertEquals(counters, f.p.counters())
        assertNoNative(f)
    }

    fun historicalRequestStillRefused(tls: VersionBoundPersistenceConnectedFixture) =
        withOrdinaryDrainRun(tls, expireClosedSetupPredecessors = true) { f ->
            // This predecessor explicitly has SYNTHETIC old seal/checkpoint comparisons. It is
            // not relabelled as the genuine fresh PROJECT branch and no drain is run in this test.
            assertTrue(f.observer.queryForObject("SELECT rotation_sequence = 0 AND NOT scan_requested " +
                "AND seal_state = 'SEAL_VERIFIED' AND checkpoint_generation > 0 FROM complaint_journal_control WHERE data_scope_id = ?",
                Boolean::class.java, f.scope) == true)
            val counters = f.history.p.counters()
            val providers = f.provider.requests.size
            val seals = f.sealHttp.order.toList()
            compareControl(f.observer, f.scope, expected = true)
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET scan_requested = true " +
                "WHERE data_scope_id = ? AND test_only AND rotation_sequence = 0 AND NOT scan_requested", f.scope))
            compareControl(f.observer, f.scope, expected = false)
            assertEquals(counters, f.history.p.counters())
            assertEquals(providers, f.provider.requests.size)
            assertEquals(seals, f.sealHttp.order)
            f.assertReleased()
        }

    fun capturedStillRequiresRequest(tls: VersionBoundPersistenceConnectedFixture) = withUnusedSealedPurgeRun(tls) { f ->
        val counters = f.p.counters()
        val original = f.beginDrain()
        // Real OPEN/zero-primary page/CAPTURE, then intentionally malformed approval. No signed
        // statement, native inventory, paid witness or successful original is manufactured.
        assertThrows<TestOrdinaryDrainExceptionV1> {
            original.drain(ByteArray(0), f.rawEvidence, AwsJournalKmsFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS)
        }
        assertEquals(TestOrdinaryDrainStepV1.ADMISSION, original.step, "Refusal must follow the actual fresh range capture.")
        // Read the actual retained probe; no phase/original is constructed or injected. The fresh
        // exception is SELECT-only and both current comparisons bracket the unfiltered page.
        val deletion = ownedCutField(f, "deletion") as TestOrdinaryDrainSqlProbeV1
        val calls = deletion.calls
        val selected = calls.indices.single { calls[it].sql == OwnerDeletePersistenceSql.SELECT_REGISTERED_PRIMARY_PAGE }
        val controls = calls.indices.filter { calls[it].sql == TestOrdinaryDrainSqlV1.control }
        val leases = calls.indices.filter { calls[it].sql == TestOrdinarySealSqlV1.lease }
        assertEquals(2, controls.size)
        assertEquals(2, leases.size)
        assertTrue(controls[0] < leases[0] && leases[0] < selected && selected < controls[1] && controls[1] < leases[1])
        assertTrue(calls.indices.single { calls[it].sql == TestRunSealingSqlV1.lockRun } in (leases[0] + 1) until selected)
        assertTrue(calls.all { it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD && it.primaryOriginal != null })
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, calls.map { it.phase }.distinct().single().databaseOutcome())
        f.assertNoPreviousHistory()
        assertTrue(f.observer.queryForObject("SELECT rotation_sequence = 1 AND rotation_state = 'CAPTURED' " +
            "AND scan_requested AND publication_epoch = 2 AND rotation_epoch_before = 1 " +
            "FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, f.scope) == true)
        compareControl(f.observer, f.scope, expected = true)
        assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET scan_requested = false " +
            "WHERE data_scope_id = ? AND test_only AND rotation_sequence = 1 AND rotation_state = 'CAPTURED' AND scan_requested", f.scope))
        compareControl(f.observer, f.scope, expected = false)
        assertEquals(counters, f.p.counters())
        assertNoNative(f)
    }

    private fun compareControl(jdbc: JdbcTemplate, scope: UUID, expected: Boolean) {
        fun image() = checkNotNull(jdbc.queryForObject(
            "SELECT to_jsonb(c)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, scope))
        val before = image()
        // Observer connection reads the fixed predicate; it does NOT enter an original/phase or
        // inject this Boolean into MAIN. The positive end-to-end case remains unusedAndReplay.
        val actual = jdbc.query(TestOrdinaryDrainSqlV1.control, { row, _ ->
            row.getBoolean("valid").also { assertFalse(row.wasNull()) }
        }, scope)
        assertEquals(listOf(expected), actual)
        assertEquals(before, image(), "Control comparison must not clear the request or alter any history/lease field.")
    }

    private fun assertNoNative(f: TestRunPurgeFixtureV1) {
        f.assertReleased()
        assertTrue(f.inventoryRequests.isEmpty())
        assertTrue(f.inventoryKeys.requests.isEmpty())
        assertTrue(f.sealHttp.order.isEmpty())
    }
}
