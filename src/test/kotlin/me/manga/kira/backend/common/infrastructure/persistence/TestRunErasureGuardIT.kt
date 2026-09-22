package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.catalog.TerminalEnrollmentFixturePostconditionV1
import me.manga.kira.backend.complaint.catalog.withActiveHistoryTerminalCatalogRun
import me.manga.kira.backend.complaint.catalog.withNonemptyActiveHistoryTerminalCatalogRun
import me.manga.kira.backend.complaint.catalog.withTerminalCatalogRun
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureSqlV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.sql.SQLException

/** Negative SQL/transport specimens over genuine C/A/D/E. No successful history is fabricated. NOT_RUN. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRunErasureGuardIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRunErasureGuardIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun threeRoutineOwnersRefuseErasureBeforeSqlNativeWhileFourthPrivacySlotRemainsAvailable() = withFixture { tls ->
        withTerminalCatalogRun(tls) { f -> TestRunErasureFixtureV1(f).use { h ->
            val e = h.projectE(); val original = h.child(e); val before = h.image(); val native = h.nativeImage()
            OwnedCallerTestScope().use { callers ->
                val gate = callers.gate()
                val holder = callers.launch {
                    val permits = arrayListOf<LocalPersistencePermit>()
                    try { repeat(3) { permits += checkNotNull(h.admission.tryRoutineDeletion()) }; gate.hold() }
                    finally { permits.asReversed().forEach { assertTrue(it.releaseAfterQuiescence()) } }
                    true
                }
                try {
                    // Foreign caller: refusal must be routine saturation, not this caller's
                    // outstanding-permit entry guard. No physical resource was borrowed by holder.
                    gate.awaitEntered(); assertFalse(LocalPersistencePermit.callerHasOutstandingPermit())
                    assertEquals(3, h.admission.activeOwners().routineOwners); assertNull(h.admission.tryRoutineDeletion())
                    assertThrows<TestRunErasureExceptionV1> { original.erase(h.request()) }
                    assertTrue(h.jdbc.calls.isEmpty()); assertEquals(before, h.image()); assertEquals(native, h.nativeImage())
                    val privacy = h.admission.tryPrivacyDeletion(); assertNotNull(privacy)
                    try {
                        assertEquals(4, h.admission.activeOwners().totalOwners); assertEquals(1, h.admission.activeOwners().privacyOwners)
                        assertNull(h.admission.tryRoutineDeletion()); assertNull(h.admission.tryPrivacyDeletion())
                    } finally { assertTrue(checkNotNull(privacy).releaseAfterQuiescence()) }
                } finally { gate.release() }
                assertTrue(holder.value())
            }
            h.assertReleased(false)
            assertThrows<RuntimeException> { original.erase(h.request()) }; assertThrows<RuntimeException> { e.beginErasure(h.ownership, h.jdbc) }
            assertEquals(before, h.image()); assertEquals(native, h.nativeImage())
        } }
    }

    @Test fun v30KeepsSealedAndDependentPurgingDeleteRefusalAndAllOriginalInsertUpdateNoopAndForeignKeys() = withFixture { tls ->
        withActiveHistoryTerminalCatalogRun(tls) { active -> TestRunErasureFixtureV1(active.catalog).use { h ->
            assertEquals("SEALED", h.state()); val before = h.image()
            erasureSqlState("23514") { h.observer.update("DELETE FROM complaint_test_active_seal_intents WHERE data_scope_id = ?", h.scope) }
            erasureSqlState("23514") { h.observer.update("INSERT INTO complaint_test_active_seal_intents SELECT * FROM complaint_test_active_seal_intents WHERE data_scope_id = ?", h.scope) }
            erasureSqlState("23514") { h.observer.update("UPDATE complaint_test_active_seal_intents SET requested_at = requested_at + interval '1 microsecond' WHERE data_scope_id = ?", h.scope) }
            erasureSqlState("23514") { h.observer.update("UPDATE complaint_test_active_seal_intents SET canonical_bytes = canonical_bytes || decode('00','hex') WHERE data_scope_id = ?", h.scope) }
            erasureSqlState("23514") { h.observer.update("UPDATE complaint_test_active_seal_intents SET wire_bytes = wire_bytes || decode('00','hex') WHERE data_scope_id = ?", h.scope) }
            erasureSqlState("23514") { h.observer.update("UPDATE complaint_test_active_seal_intents SET data_scope_id = '00000000-0000-0000-0000-000000000000' WHERE data_scope_id = ?", h.scope) }
            erasureSqlState("23503") { h.observer.update("DELETE FROM complaint_test_runs WHERE data_scope_id = ?", h.scope) }
            erasureSqlState("23503") { h.observer.update("DELETE FROM complaint_journal_control WHERE data_scope_id = ?", h.scope) }
            assertEquals(0, h.observer.update("UPDATE complaint_test_active_seal_intents SET state = state WHERE data_scope_id = ?", h.scope))
            assertEquals(before, h.image(), "Refused and no-op statements must not churn the original A row or any counter.")
            h.projectE(); val purging = h.image()
            erasureSqlState("23514") { h.observer.update("DELETE FROM complaint_test_active_seal_intents WHERE data_scope_id = ?", h.scope) }
            assertEquals(purging, h.image()); h.assertReleased(false)
        } }
    }

    @Test fun v30RefusesExpiredScopedLeaseAtActualOtherwiseCompleteFinalPrefix() = v30FinalNegative { h ->
        "UPDATE complaint_journal_control SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE data_scope_id = '${h.scope}'::uuid"
    }

    @Test fun v30RefusesOpenMaintenanceGateAtActualOtherwiseCompleteFinalPrefix() = v30FinalNegative { h ->
        "UPDATE complaint_journal_control SET maintenance_closed = false WHERE data_scope_id = '${h.scope}'::uuid"
    }

    @Test fun v30RefusesMissingTerminalProjectionAtActualOtherwiseCompleteFinalPrefix() = v30FinalNegative { h ->
        "UPDATE complaint_catalog_mutations SET projected_at = NULL WHERE operation_token = '${h.catalog.token}'::uuid"
    }

    @Test fun v30RefusesDifferentGlobalAcceptedHeadAtActualOtherwiseCompleteFinalPrefix() = v30FinalNegative {
        "UPDATE complaint_journal_control SET accepted_catalog_hash = decode(repeat('ab',32),'hex') WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'"
    }

    @Test fun v30RefusesWrongPermanentDispositionAtActualOtherwiseCompleteFinalPrefix() = v30FinalNegative { h ->
        "UPDATE complaint_installation_ids SET state = 'DELETED' WHERE data_scope_id = '${h.scope}'::uuid AND state = 'RETIRED'"
    }

    @Test fun changedOrdinaryRawDenialCannotUseSuccessfulEAsReplacementAuthority() = withFixture { tls ->
        withTerminalCatalogRun(tls) { f -> TestRunErasureFixtureV1(f).use { h ->
            val e = h.projectE(); val before = h.image(); val native = h.nativeImage()
            val wrong = f.request().ordinaryRawEvidence.map(ByteArray::copyOf)
            wrong.first()[0] = (wrong.first()[0].toInt() xor 1).toByte()
            try {
                assertThrows<TestRunErasureExceptionV1> { h.child(e).erase(h.request(ordinaryRaw = wrong)) }
                h.assertReleased(false); assertEquals(before, h.image()); assertNoErasureWrites(h)
                assertEquals(native.signs, h.nativeImage().signs); assertEquals(native.puts, h.nativeImage().puts)
                assertEquals(native.terminalGets, h.nativeImage().terminalGets); assertEquals(native.journalWrites, h.nativeImage().journalWrites)
            } finally { wrong.forEach { it.fill(0) } }
        } }
    }

    @Test fun currentManifestConflictRefusesEvenAValidDeletedPairInsteadOfRepairingItToRetired() = withFixture { tls ->
        withTerminalCatalogRun(tls, enrolled = true,
            enrollmentPostcondition = TerminalEnrollmentFixturePostconditionV1.PURGING_WITH_CONFLICTING_DELETED_PAIR) { f -> TestRunErasureFixtureV1(f).use { h ->
            val e = h.projectE()
            // Corruption specimen AFTER real E. The terminal manifest says RETIRED. Removing
            // this credential and choosing the opposite valid terminal pair never grants success.
            assertEquals(1, h.observer.update("DELETE FROM app_installations WHERE data_scope_id = ?", h.scope))
            assertEquals(1, h.observer.update("UPDATE complaint_installation_ids SET state = 'DELETED', terminal_at = clock_timestamp() WHERE data_scope_id = ?", h.scope))
            val before = h.image()
            assertThrows<TestRunErasureExceptionV1> { h.child(e).erase(h.request()) }
            h.assertReleased(false); assertNoErasureWrites(h); assertEquals(before, h.image())
        } }
    }

    @Test fun reopenedVerifiedOrdinaryWorkAfterERefusesBeforeNativeOrAnyErasurePayment() = nonemptySqlNegative(beforeNative = true) { h ->
        assertEquals(1, h.observer.update("UPDATE complaint_journal_publications SET state = 'VERIFIED', applied_at = NULL WHERE data_scope_id = ? AND event_kind = 'OWNER_DELETE'", h.scope))
    }

    @Test fun unresolvedDeletionPendingResourceAfterERefusesBeforeNativeOrAnyErasurePayment() = withFixture { tls ->
        withNonemptyActiveHistoryTerminalCatalogRun(tls) { active -> TestRunErasureFixtureV1(active.catalog).use { h ->
            val primary = checkNotNull(active.deletion)
            val target = primary.reports.single().input.id
            assertEquals(listOf(target), checkNotNull(primary.record).event.complaintIds())
            val e = h.projectE()
            fun otherResources() = h.observer.queryForList(
                "SELECT encode(sha256(convert_to(jsonb_build_array(to_jsonb(r),r.xmin::text)::text,'UTF8')),'hex') " +
                    "FROM complaint_resource_ids r WHERE data_scope_id = ? AND id <> ? ORDER BY id",
                String::class.java, h.scope, target)
            val untouched = otherResources(); assertEquals(2, untouched.size, "The two original activation NOTICE resources are not the A target.")
            assertEquals(1, h.observer.update("UPDATE complaint_resource_ids SET state = 'DELETION_PENDING', deleted_at = NULL " +
                "WHERE data_scope_id = ? AND id = ? AND test_only AND state = 'DELETED' AND deleted_at IS NOT NULL", h.scope, target))
            assertEquals(untouched, otherResources(), "The negative specimen changes only the actual registered CREATE/A target, not other rows or xmin.")
            val before = h.image(); val native = h.nativeImage()
            assertThrows<TestRunErasureExceptionV1> { h.child(e).erase(h.request()) }
            h.assertReleased(false); assertNoErasureWrites(h); assertEquals(before, h.image()); assertEquals(native, h.nativeImage())
        } }
    }

    @Test fun remainingAppliedBeforePrimaryVerificationIsNotAcceptedMerelyBecauseItsNativeObjectExists() = nonemptySqlNegative { h ->
        assertEquals(1, h.observer.update("UPDATE complaint_deletion_journal_applied a SET applied_at = p.verified_at - interval '1 microsecond' " +
            "FROM complaint_journal_publications p WHERE a.event_id = p.event_id AND a.data_scope_id = ?", h.scope))
    }

    @Test fun remainingAppliedAfterCumulativeConversionIsNotAcceptedMerelyBecauseItsNativeObjectExists() = nonemptySqlNegative { h ->
        assertEquals(1, h.observer.update("UPDATE complaint_deletion_journal_applied a SET applied_at = l.converted_at + interval '1 microsecond' " +
            "FROM complaint_recovery_capacity_reservations l WHERE a.event_id = l.event_id AND a.data_scope_id = ?", h.scope))
    }

    @Test fun changedConvertedAppliedChargeCannotBecomeASecondHistoricalRefundEntitlement() = nonemptySqlNegative { h ->
        val used = h.observer.query("SELECT converted_amounts FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ? AND state = 'CONVERTED'",
            { row, _ -> erasureTestVector(row, "converted_amounts") }, h.scope).single()
        val extra = ComplaintCapacityVector.units(ComplaintCapacityCounter.JOURNAL_APPLIED, 1) + ComplaintCapacityVector.units(ComplaintCapacityCounter.STORAGE_BYTES, 32768)
        assertEquals(1, h.observer.update("UPDATE complaint_recovery_capacity_reservations SET converted_amounts = ?::bigint[] WHERE data_scope_id = ? AND state = 'CONVERTED'",
            (used + extra).toLongArray(), h.scope))
    }

    @Test fun sourceMutationAfterAllNativeReadsRollsBackInsteadOfErasingAgainstTheEarlierSnapshot() = withFixture { tls ->
        withActiveHistoryTerminalCatalogRun(tls) { active -> TestRunErasureFixtureV1(active.catalog).use { h ->
            val e = h.projectE(); val before = h.image(); var changed = false
            h.jdbc.before = { call -> if (!changed && call.path === PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_BATCH && call.sql == TestRunErasureSqlV1.lockRun) {
                changed = true
                assertEquals(1, JdbcTemplate(h.runtime.pools.deletion).update("UPDATE complaint_test_runs SET installation_manifest_root = decode(repeat('ab',32),'hex') WHERE data_scope_id = ?", h.scope))
            } }
            try { assertThrows<TestRunErasureExceptionV1> { h.child(e).erase(h.request()) } } finally { h.jdbc.before = {} }
            assertTrue(changed); h.assertReleased(false); assertNoErasureWrites(h); assertEquals(before, h.image())
            assertTrue(h.jdbc.observations.keys.any { it.databaseOutcome() === PersistenceDatabaseOutcome.ROLLED_BACK })
        } }
    }

    /** One negative per genuine FINAL, within its unchanged two-second bound. A server exception
     * block rolls back the specimen BEFORE the original SQL resumes; no JDBC transaction control.
     * Only the five fixed statements above and typed fixture UUIDs enter this private SQL body. */
    private fun v30FinalNegative(changeSql: (TestRunErasureFixtureV1) -> String) = withFixture { tls ->
        withActiveHistoryTerminalCatalogRun(tls) { active -> TestRunErasureFixtureV1(active.catalog).use { h ->
            val e = h.projectE(); val before = h.baseline(); var checked = 0
            h.jdbc.before = { call -> if (call.sql == TestRunErasureSqlV1.deleteActive) {
                assertEquals(PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_FINAL, call.path); assertEquals(0, checked)
                val actual = JdbcTemplate(h.runtime.pools.deletion).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
                listOf("app_installations", "complaint_resource_ids", "complaints", "complaint_journal_publications",
                    "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied", "complaint_test_terminal_intents").forEach { table ->
                    assertEquals(0L, actual.queryForObject("SELECT count(*) FROM $table WHERE data_scope_id = ?", Long::class.java, h.scope))
                }
                val preimage = h.allPhysicalRows(actual)
                actual.execute("""
                    DO ${'$'}erasure_guard${'$'}
                    DECLARE
                        changed bigint;
                        at_delete boolean := false;
                    BEGIN
                        BEGIN
                            ${changeSql(h)};
                            GET DIAGNOSTICS changed = ROW_COUNT;
                            IF changed IS DISTINCT FROM 1 THEN
                                RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'Expected one guard specimen mutation';
                            END IF;
                            at_delete := true;
                            DELETE FROM complaint_test_active_seal_intents WHERE data_scope_id = '${h.scope}'::uuid;
                            RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'Expected active row DELETE refusal';
                        EXCEPTION WHEN SQLSTATE '23514' THEN
                            IF NOT at_delete THEN RAISE; END IF;
                        END;
                    END;
                    ${'$'}erasure_guard${'$'};
                """.trimIndent())
                assertEquals(preimage, h.allPhysicalRows(actual), "The actual guard refusal must restore every row/xmin before original F resumes.")
                checked++
            } }
            try { assertEquals(TestRunErasureResultV1.PURGED, h.child(e).erase(h.request())) } finally { h.jdbc.before = {} }
            assertEquals(1, checked); h.assertReleased(); h.assertPurged(before); h.jdbc.assertOrder()
        } }
    }

    private fun nonemptySqlNegative(beforeNative: Boolean = false, change: (TestRunErasureFixtureV1) -> Unit) = withFixture { tls ->
        withNonemptyActiveHistoryTerminalCatalogRun(tls) { active -> TestRunErasureFixtureV1(active.catalog).use { h ->
            val e = h.projectE(); change(h); val before = h.image(); val native = h.nativeImage()
            assertThrows<TestRunErasureExceptionV1> { h.child(e).erase(h.request()) }
            h.assertReleased(false); assertNoErasureWrites(h); assertEquals(before, h.image())
            if (beforeNative) assertEquals(native, h.nativeImage())
        } }
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use { it.bind(); action(it) }
}

private fun erasureSqlState(expected: String, action: () -> Unit) {
    val problem = assertThrows<DataAccessException> { action() }
    val sql = generateSequence(problem as Throwable?) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
    assertEquals(expected, checkNotNull(sql).sqlState)
}

private fun assertNoErasureWrites(f: TestRunErasureFixtureV1) {
    val mutation = Regex("(?m)^\\s*(?:UPDATE|DELETE|INSERT)\\b")
    assertFalse(f.jdbc.calls.any { mutation.containsMatchIn(it.sql) })
}
