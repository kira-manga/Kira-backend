package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalActiveHistoryCasesV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogRecurrentRowFaultV1
import me.manga.kira.backend.complaint.catalog.withRecurrentTerminalCatalogRun
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveCheckpointHistoryV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainActiveHistorySqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureSqlV1 as Sql
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.SQLException
import java.util.UUID

/** Genuine N2 A/B/recurrent/D/E -> original F. No supplied completed rows or native proof. NOT_RUN. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRunErasureRecurrentIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRunErasureRecurrentIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun genuineN2HistoryPurgesInFkOrderRefundsEveryPhysicalRowOnceAndReplaysReadOnly() = withFixture { tls ->
        withRecurrentTerminalCatalogRun(tls, activeSeals = 2) { active, recurrent ->
            TestRunErasureFixtureV1(active.catalog).use { h ->
                assertStructuralGuards(h)
                val e = h.projectE()
                val protected = h.image()
                val expectedCommitment = checkNotNull(commitment(h.observer, h.scope)); assertEquals(64, expectedCommitment.length)
                sqlState("23514") { h.observer.update("UPDATE complaint_test_runs SET recurrent_erasure_history_hash = NULL WHERE data_scope_id = ?", h.scope) }
                sqlState("23514") { h.observer.update("UPDATE complaint_test_runs SET recurrent_erasure_history_hash = " +
                    "set_byte(recurrent_erasure_history_hash,0,get_byte(recurrent_erasure_history_hash,0) # 1) WHERE data_scope_id = ?", h.scope) }
                listOf("complaint_test_active_checkpoint_history", "complaint_test_active_recurrent_seal_intents").forEach { table ->
                    sqlState("23514") { h.observer.update("DELETE FROM $table WHERE data_scope_id = ?", h.scope) }
                }
                assertEquals(protected, h.image(), "PURGING alone is not permission to remove dependent history.")
                val before = h.baseline(); val history = historyRows(h.observer, h.scope); val expected = retirements(h)
                assertEquals(5, history.size, "Four paid physical history rows and the permanent B observation.")
                assertEquals(listOf(1L, 2L, 3L, 4L), h.catalog.record.sealSet.records().map { it.epochStartInclusive })
                val recurrentKey = TestActiveCheckpointHistoryV1.Entry.parse(recurrent.history().last()["entry_bytes"] as ByteArray).objectKey
                val native = h.nativeImage(); val reads = h.catalog.f.sealHttp.terminalInventoryRequests.size
                val original = h.child(e)
                assertEquals(protected, h.image()); assertEquals(native, h.nativeImage()); assertTrue(h.jdbc.calls.isEmpty())
                assertThrows<RuntimeException> { e.beginErasure(h.ownership, h.jdbc) }
                var preservedBatches = 0
                h.jdbc.after = { call -> if (call.sql == Sql.releaseLease) {
                    assertEquals(PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_BATCH, call.path)
                    val actual = JdbcTemplate(h.runtime.pools.deletion)
                    assertEquals(history, historyRows(actual, h.scope),
                        "Every source/archive/checkpoint/xmin and B survive each real bounded batch on its own holder.")
                    assertEquals(expectedCommitment, commitment(actual, h.scope), "No batch can recapture or clear E's immutable recurrent custody.")
                    preservedBatches++
                } }
                try { assertEquals(TestRunErasureResultV1.PURGED, original.erase(h.request())) }
                finally { h.jdbc.after = {} }
                assertTrue(preservedBatches > 0); h.assertReleased(); h.jdbc.assertOrder()
                assertCompleteHistoryReads(h); assertRetirementOrder(h, expected); h.assertPurged(before)
                assertEquals(expectedCommitment, commitment(h.observer, h.scope), "PURGED retains E's original custody; it is not a refundable history row.")
                assertNativeRound(h, native, reads)
                val purged = h.image(); val files = h.catalog.files(); val endedNative = h.nativeImage(); val calls = h.jdbc.calls.size
                assertThrows<RuntimeException> { original.erase(h.request()) }
                assertThrows<RuntimeException> { e.beginErasure(h.ownership, h.jdbc) }
                assertEquals(calls, h.jdbc.calls.size); assertEquals(purged, h.image()); assertEquals(endedNative, h.nativeImage())

                val replayReads = h.catalog.f.sealHttp.terminalInventoryRequests.size
                withFreshTestRunErasure(h.catalog) { fresh ->
                    val replay = fresh.fresh()
                    assertEquals(TestRunErasureResultV1.PURGED_HISTORY_VERIFIED_ONLY, replay.erase(fresh.request()))
                    fresh.assertReleased(); assertReadOnlyErasure(fresh); assertEquals(purged, fresh.image()); assertEquals(files, h.catalog.files())
                    assertNativeRound(fresh, endedNative, replayReads)
                    val replayCalls = fresh.jdbc.calls.size; val replayNative = fresh.nativeImage()
                    assertThrows<RuntimeException> { replay.erase(fresh.request()) }
                    assertThrows<RuntimeException> { fresh.fresh() }
                    assertEquals(replayCalls, fresh.jdbc.calls.size); assertEquals(replayNative, fresh.nativeImage()); assertEquals(purged, fresh.image())
                }
                // SQL history is genuinely absent only AFTER FINAL. A new reader still needs the
                // exact original recurrent object; it cannot recreate a sidecar or accept its absence.
                val listing = h.catalog.f.sealHttp.terminalInventoryListing
                var missing = false
                h.catalog.f.sealHttp.terminalInventoryListing = { pass, values ->
                    val actual = listing(pass, values)
                    if (actual.any { it.key == recurrentKey }) missing = true
                    actual.filterNot { it.key == recurrentKey }
                }
                val beforeMissing = h.nativeImage()
                try {
                    withFreshTestRunErasure(h.catalog) { fresh ->
                        assertThrows<TestRunErasureExceptionV1> { fresh.fresh().erase(fresh.request()) }
                        assertTrue(missing); fresh.assertReleased(false); assertReadOnlyErasure(fresh)
                        assertEquals(purged, fresh.image()); assertEquals(files, h.catalog.files())
                        assertEquals(beforeMissing.signs, fresh.nativeImage().signs); assertEquals(beforeMissing.puts, fresh.nativeImage().puts)
                        assertEquals(beforeMissing.journalWrites, fresh.nativeImage().journalWrites)
                    }
                } finally { h.catalog.f.sealHttp.terminalInventoryListing = listing }
                assertThrows<RuntimeException> { original.erase(h.request()) }; assertEquals(purged, h.image())
            }
        }
    }

    @Test fun expiredLeaseAfterRealRecurrentRemovalRollsBackFinalBeforeFreshExactContinuation() = withFixture { tls ->
        withRecurrentTerminalCatalogRun(tls, activeSeals = 2) { active, _ ->
            TestRunErasureFixtureV1(active.catalog).use { h ->
                val e = h.projectE(); val before = h.baseline(); val history = historyRows(h.observer, h.scope); val expected = retirements(h)
                val original = h.child(e)
                var finalPhase: PersistencePhaseContext? = null; var finalImage: List<String>? = null; var cuts = 0
                h.jdbc.before = { call -> if (call.path === PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_FINAL && finalPhase == null) {
                    finalPhase = call.phase
                    finalImage = h.allPhysicalRows(JdbcTemplate(h.runtime.pools.deletion))
                } }
                h.jdbc.after = { call -> if (call.sql == Sql.deleteRecurrentActive) {
                    assertEquals(PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_FINAL, call.path); assertEquals(0, cuts)
                    assertEquals(expected.take(2), observedRetirements(h), "The actual H2 and I2 DELETEs must have returned before the lease cut.")
                    val actual = JdbcTemplate(h.runtime.pools.deletion)
                    assertTrue(actual.queryForObject("WITH e AS (SELECT ?::uuid AS scope) SELECT " +
                        "NOT EXISTS (SELECT 1 FROM complaint_test_active_checkpoint_history h, e WHERE h.data_scope_id = e.scope AND h.ordinal = 2) AND " +
                        "NOT EXISTS (SELECT 1 FROM complaint_test_active_recurrent_seal_intents i, e WHERE i.data_scope_id = e.scope) AND " +
                        "EXISTS (SELECT 1 FROM complaint_test_active_checkpoint_history h, e WHERE h.data_scope_id = e.scope AND h.ordinal = 1) AND " +
                        "EXISTS (SELECT 1 FROM complaint_test_active_seal_intents i, e WHERE i.data_scope_id = e.scope)", Boolean::class.java, h.scope) == true,
                        "H2/I2 are physically gone on the actual FINAL holder while H1/V26 still survive; a zero-row DELETE is not the cut.")
                    assertEquals(1, actual.update(
                        "UPDATE complaint_journal_control SET lease_expires_at = clock_timestamp() - interval '1 second' " +
                            "WHERE data_scope_id = ? AND lease_owner = ? AND lease_expires_at > clock_timestamp()", h.scope, original.attemptId))
                    cuts++
                } }
                try {
                    assertThrows<TestRunErasureExceptionV1> {
                        try { original.erase(h.request()) }
                        finally { assertEquals(1, cuts, "The selected actual FINAL fault must run before judging refusal.") }
                    }
                } finally { h.jdbc.before = {}; h.jdbc.after = {} }
                h.assertReleased(false)
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, checkNotNull(finalPhase).databaseOutcome())
                h.jdbc.observations.keys.filter { it !== finalPhase }.forEach { assertEquals(PersistenceDatabaseOutcome.COMMITTED, it.databaseOutcome()) }
                assertEquals(checkNotNull(finalImage), h.allPhysicalRows(), "Rollback restores every physical row/xmin and all counters, not only row counts.")
                assertEquals(history, historyRows(h.observer, h.scope)); assertEquals("PURGING", h.state())
                assertEquals(expected.take(2), observedRetirements(h))
                assertTrue(h.jdbc.calls.none { it.path === PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_FINAL && it.sql in setOf(Sql.deleteActive, Sql.deleteControl, Sql.purgeRun) })
                val partial = h.image(); val native = h.nativeImage(); val calls = h.jdbc.calls.size
                assertThrows<RuntimeException> { original.erase(h.request()) }
                assertThrows<RuntimeException> { e.beginErasure(h.ownership, h.jdbc) }
                assertEquals(partial, h.image()); assertEquals(native, h.nativeImage()); assertEquals(calls, h.jdbc.calls.size)
                withFreshTestRunErasure(h.catalog) { fresh ->
                    assertEquals(TestRunErasureResultV1.PURGED, fresh.fresh().erase(fresh.request()))
                    fresh.assertReleased(); fresh.jdbc.assertOrder(); assertCompleteHistoryReads(fresh)
                    assertRetirementOrder(fresh, expected); fresh.assertPurged(before)
                }
                assertThrows<RuntimeException> { original.erase(h.request()) }
            }
        }
    }

    @Test fun sameValueSourceAndArchiveRewritesCannotSupplyOriginalPhysicalCustody() {
        // Two independently damaged lineages, not a cross-product: direct-child source custody
        // and genuinely cold archive custody. Changed bytes/xmin never become a fresh authority.
        listOf(TerminalCatalogRecurrentRowFaultV1.REWRITTEN_SOURCE_XMIN, TerminalCatalogRecurrentRowFaultV1.REWRITTEN_ARCHIVE_XMIN).forEach { fault ->
            withFixture { tls -> withRecurrentTerminalCatalogRun(tls, activeSeals = 2) { active, recurrent ->
                TestRunErasureFixtureV1(active.catalog).use { h ->
                    val e = h.projectE(); val original = h.child(e)
                    val token = recurrent.history().last()["operation_token"] as UUID
                    val table = if (fault === TerminalCatalogRecurrentRowFaultV1.REWRITTEN_SOURCE_XMIN)
                        "complaint_test_active_recurrent_seal_intents" else "complaint_test_active_checkpoint_history"
                    fun row() = h.observer.query("SELECT to_jsonb(t)::text AS body, t.xmin::text AS xmin FROM $table t WHERE operation_token = ?",
                        { value, _ -> value.getString("body") to value.getString("xmin") }, token).single()
                    val old = row()
                    CatalogTestRunTerminalActiveHistoryCasesV1.withChangedRecurrentHistory(h.catalog.f, fault) {
                        assertGuardsEnabled(h.observer) // The negative setup transaction has returned/committed before F begins.
                        val changed = row(); assertEquals(old.first, changed.first); assertNotEquals(old.second, changed.second, fault.name)
                        val before = h.image(); val files = h.catalog.files()
                        fun refuses(f: TestRunErasureFixtureV1, value: TestRunErasureV1) {
                            val returned = arrayListOf<TestRunErasureSqlProbeV1.Call>(); val beforeNative = f.nativeImage()
                            f.jdbc.after = { returned.add(it) }
                            try { assertThrows<TestRunErasureExceptionV1>(fault.name) { value.erase(f.request()) } }
                            finally { f.jdbc.after = {} }
                            f.assertReleased(false); assertReadOnlyErasure(f); assertEquals(before, f.image()); assertEquals(files, h.catalog.files())
                            assertEquals(f.jdbc.calls, returned, "Every recorded read returned before E's persisted custody comparison refused.")
                            assertCompleteHistoryReads(f)
                            assertEquals(Sql.control, returned.last().sql); assertEquals(f.scope, returned.last().arguments.last())
                            assertTrue(returned.none { it.sql == Sql.counts }); assertEquals(beforeNative, f.nativeImage())
                            val calls = f.jdbc.calls.size; val native = f.nativeImage()
                            assertThrows<RuntimeException> { value.erase(f.request()) }
                            assertEquals(calls, f.jdbc.calls.size); assertEquals(native, f.nativeImage()); assertEquals(before, f.image())
                        }
                        if (fault === TerminalCatalogRecurrentRowFaultV1.REWRITTEN_SOURCE_XMIN) refuses(h, original)
                        else {
                            original.close() // Unstarted direct child supplies no comparison or authority to the cold owner.
                            withFreshTestRunErasure(h.catalog) { fresh -> refuses(fresh, fresh.fresh()) }
                            assertTrue(h.jdbc.calls.isEmpty())
                        }
                    }
                    // Helper restoration is teardown only. No fresh or repaired successful F is attempted here.
                    assertThrows<RuntimeException> { e.beginErasure(h.ownership, h.jdbc) }
                }
            } }
        }
    }

    @Test fun missingLatestArchiveCannotBeInventedFromRetainedNativeHistory() = withFixture { tls ->
        withRecurrentTerminalCatalogRun(tls, activeSeals = 2) { active, _ ->
            TestRunErasureFixtureV1(active.catalog).use { h ->
                val e = h.projectE(); val original = h.child(e)
                CatalogTestRunTerminalActiveHistoryCasesV1.withChangedRecurrentHistory(h.catalog.f, TerminalCatalogRecurrentRowFaultV1.MISSING_LATEST_ARCHIVE) {
                    assertGuardsEnabled(h.observer)
                    assertEquals(1L, h.count("complaint_test_active_checkpoint_history")); assertEquals(1L, h.count("complaint_test_active_recurrent_seal_intents"))
                    assertEquals(0L, h.observer.queryForObject("SELECT count(*) FROM complaint_test_active_checkpoint_history WHERE data_scope_id = ? AND ordinal = 2", Long::class.java, h.scope))
                    val before = h.image(); val files = h.catalog.files(); val native = h.nativeImage()
                    val returned = arrayListOf<TestRunErasureSqlProbeV1.Call>()
                    h.jdbc.after = { returned.add(it) }
                    try { assertThrows<TestRunErasureExceptionV1> { original.erase(h.request()) } }
                    finally { h.jdbc.after = {} }
                    h.assertReleased(false); assertReadOnlyErasure(h); assertEquals(before, h.image()); assertEquals(files, h.catalog.files())
                    assertEquals(native, h.nativeImage(), "An incomplete local history refuses before any replacement native history acquisition.")
                    assertEquals(h.jdbc.calls, returned); assertEquals(TestOrdinaryDrainActiveHistorySqlV1.archiveIdentity, returned.last().sql)
                    val calls = h.jdbc.calls.size
                    assertThrows<RuntimeException> { original.erase(h.request()) }
                    assertEquals(calls, h.jdbc.calls.size); assertEquals(before, h.image())
                }
                assertThrows<RuntimeException> { e.beginErasure(h.ownership, h.jdbc) }
            }
        }
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use { it.bind(); action(it) }

    private fun assertStructuralGuards(h: TestRunErasureFixtureV1) {
        assertEquals("SEALED", h.state()); assertGuardsEnabled(h.observer)
        val before = h.image()
        assertEquals(null, commitment(h.observer, h.scope))
        sqlState("23514") { h.observer.update("UPDATE complaint_test_runs SET recurrent_erasure_history_hash = decode(repeat('ab',32),'hex') WHERE data_scope_id = ?", h.scope) }
        listOf("complaint_test_active_recurrent_seal_intents" to "requested_at", "complaint_test_active_checkpoint_history" to "checkpointed_at").forEach { (table, time) ->
            sqlState("23514") { h.observer.update("DELETE FROM $table WHERE data_scope_id = ?", h.scope) }
            sqlState("23514") { h.observer.update("INSERT INTO $table SELECT * FROM $table WHERE data_scope_id = ?", h.scope) }
            sqlState("23514") { h.observer.update("UPDATE $table SET $time = $time + interval '1 microsecond' WHERE data_scope_id = ?", h.scope) }
            assertEquals(0, h.observer.update("UPDATE $table SET charged_storage_bytes = charged_storage_bytes WHERE data_scope_id = ?", h.scope))
        }
        sqlState("23503") { h.observer.update("DELETE FROM complaint_journal_control WHERE data_scope_id = ?", h.scope) }
        assertEquals(listOf("fk_complaint_active_history_initial", "fk_complaint_active_history_recurrent", "fk_complaint_recurrent_predecessor"),
            h.observer.queryForList("SELECT conname FROM pg_constraint WHERE contype = 'f' AND confdeltype = 'r' AND confupdtype = 'r' " +
                "AND conname IN ('fk_complaint_active_history_initial','fk_complaint_active_history_recurrent','fk_complaint_recurrent_predecessor') ORDER BY conname", String::class.java))
        assertEquals(before, h.image(), "The new retirement branch cannot loosen insertion, immutable updates, no-op xmin, or restrictive FKs.")
    }

    private fun assertGuardsEnabled(jdbc: JdbcTemplate) {
        assertEquals(listOf("complaint_test_active_history_immutable:O", "complaint_test_active_recurrent_seal_immutable:O"),
            jdbc.queryForList("SELECT tgname || ':' || tgenabled::text FROM pg_trigger WHERE " +
                "(tgrelid = 'complaint_test_active_checkpoint_history'::regclass AND tgname = 'complaint_test_active_history_immutable') OR " +
                "(tgrelid = 'complaint_test_active_recurrent_seal_intents'::regclass AND tgname = 'complaint_test_active_recurrent_seal_immutable') ORDER BY tgname", String::class.java))
    }

    private fun commitment(jdbc: JdbcTemplate, scope: UUID): String? = jdbc.query(
        "SELECT encode(recurrent_erasure_history_hash,'hex') FROM complaint_test_runs WHERE data_scope_id = ?", { row, _ -> row.getString(1) }, scope).single()

    private fun sqlState(expected: String, action: () -> Unit) {
        val problem = assertThrows<DataAccessException> { action() }
        val sql = generateSequence(problem as Throwable?) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
        assertEquals(expected, checkNotNull(sql).sqlState)
    }

    private fun historyRows(jdbc: JdbcTemplate, scope: UUID): List<String> = jdbc.queryForList(
        "WITH e AS MATERIALIZED (SELECT ?::uuid AS scope) " + listOf("complaint_test_active_seal_intents", "complaint_test_active_recurrent_seal_intents",
            "complaint_test_active_checkpoint_history", "complaint_test_active_queue_observations").joinToString(" UNION ALL ") { table ->
            "SELECT '$table:' || encode(sha256(convert_to(jsonb_build_array(to_jsonb(t),t.xmin::text)::text,'UTF8')),'hex') AS physical " +
                "FROM $table t CROSS JOIN e WHERE t.data_scope_id = e.scope"
        } + " ORDER BY physical", String::class.java, scope)

    private data class Physical(val token: UUID, val ordinal: Long, val argumentHash: String)
    private data class Removal(val sql: String, val arguments: List<Any?>)
    private val retirementSql get() = setOf(Sql.deleteActiveHistory, Sql.deleteRecurrentActive, Sql.deleteActive)

    /** Independent exact-row comparisons only; these observations never enter the eraser as authority. */
    private fun retirements(h: TestRunErasureFixtureV1): List<Removal> {
        fun rows(table: String, ordinal: String): List<Physical> = h.observer.query(
            "SELECT operation_token, $ordinal AS ordinal, charged_storage_bytes, " +
                "sha256(convert_to((to_jsonb(t) || jsonb_build_object('row_xmin',t.xmin::text))::text,'UTF8')) AS fingerprint " +
                "FROM $table t WHERE data_scope_id = ? ORDER BY $ordinal", { row, _ ->
                assertEquals(2097152L, row.getLong("charged_storage_bytes"))
                Physical(row.getObject("operation_token", UUID::class.java), row.getLong("ordinal"), Sha256.hex(row.getBytes("fingerprint")))
            }, h.scope)
        val initial = rows("complaint_test_active_seal_intents", "rotation_sequence").single()
        val recurrent = rows("complaint_test_active_recurrent_seal_intents", "rotation_sequence").single()
        val archives = rows("complaint_test_active_checkpoint_history", "ordinal")
        assertEquals(1L, initial.ordinal); assertEquals(2L, recurrent.ordinal); assertEquals(listOf(1L, 2L), archives.map { it.ordinal })
        assertEquals(listOf(initial.token, recurrent.token), archives.map { it.token })
        assertEquals(8192L, h.observer.queryForObject("SELECT storage_bytes FROM complaint_test_active_queue_observations WHERE data_scope_id = ? AND state = 'SETTLED'", Long::class.java, h.scope))
        return archives.asReversed().flatMap { archive ->
            val source = if (archive.ordinal == 1L) initial else recurrent
            listOf(Removal(Sql.deleteActiveHistory, listOf(archive.token, h.scope, archive.ordinal, archive.argumentHash)),
                if (archive.ordinal == 1L) Removal(Sql.deleteActive, listOf(source.token, h.scope, source.argumentHash))
                else Removal(Sql.deleteRecurrentActive, listOf(source.token, h.scope, source.ordinal, source.argumentHash)))
        }
    }

    private fun observedRetirements(h: TestRunErasureFixtureV1): List<Removal> = h.jdbc.calls.filter { it.sql in retirementSql }.map { call ->
        assertEquals(PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_FINAL, call.path)
        Removal(call.sql, call.arguments.map { if (it is Number) it.toLong() else it })
    }

    private fun assertRetirementOrder(h: TestRunErasureFixtureV1, expected: List<Removal>) {
        assertEquals(4, expected.size); assertEquals(expected, observedRetirements(h), "H2 -> I2 -> H1 -> V26, each exact physical row once.")
        val final = h.jdbc.calls.filter { it.path === PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_FINAL }.map { it.sql }
        assertEquals(expected.map { it.sql } + Sql.deleteControl, final.filter { it in retirementSql || it == Sql.deleteControl })
        assertTrue(final.indexOf(Sql.deleteActiveHistory) > final.indexOfLast { it == Sql.deletePublication })
        assertTrue(final.indexOf(Sql.purgeRun) > final.indexOf(Sql.deleteControl))
        assertEquals(0L, h.count("complaint_test_active_seal_intents")); assertEquals(0L, h.count("complaint_test_active_recurrent_seal_intents"))
        assertEquals(0L, h.count("complaint_test_active_checkpoint_history")); assertEquals(1L, h.count("complaint_test_active_queue_observations"))
    }

    private fun assertCompleteHistoryReads(h: TestRunErasureFixtureV1) {
        h.jdbc.calls.groupBy { it.phase }.values.forEach { calls ->
            val sql = calls.map { it.sql }
            listOf(TestOrdinaryDrainActiveHistorySqlV1.sourceIdentity, TestOrdinaryDrainActiveHistorySqlV1.archiveIdentity,
                TestOrdinaryDrainActiveHistorySqlV1.rows, TestOrdinaryDrainActiveHistorySqlV1.currentLast).forEach { required ->
                assertTrue(required in sql, "Every original-owned READ/BATCH/FINAL must reread the complete ordered history before destructive work.")
            }
        }
    }

    private fun assertNativeRound(h: TestRunErasureFixtureV1, before: TestRunErasureNativeImageV1, start: Int) {
        val after = h.nativeImage(); val gets = h.catalog.f.sealHttp.terminalInventoryRequests.drop(start).filter { it.kind == "GET" }
        assertEquals(2 * h.catalog.d.targets.size, gets.size)
        h.catalog.d.targets.forEach { target ->
            val exact = gets.filter { it.http.encodedPath().endsWith("/${target.objectRef.objectKey}") }
            assertEquals(2, exact.size); exact.forEach { assertEquals(listOf(target.objectRef.objectVersion), it.http.rawQueryParameters()["versionId"]) }
        }
        assertEquals(before.ordinaryGets + 2, after.ordinaryGets)
        assertEquals(before.signs, after.signs); assertEquals(before.puts, after.puts); assertEquals(before.journalWrites, after.journalWrites)
        assertFalse(h.jdbc.calls.isEmpty())
    }
}
