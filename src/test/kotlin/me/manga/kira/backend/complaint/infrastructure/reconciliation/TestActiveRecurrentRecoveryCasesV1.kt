package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessException
import java.sql.SQLException
import java.sql.Timestamp
import java.util.UUID

internal enum class RecurrentSealRecoveryCut { CANONICAL, FROZEN_NATIVE }
internal enum class RecurrentPaidPrefixCut { FIRST_STAGED, PAIR_COMPLETE, FIRST_REFUND_COMMITTED }

/** Recovery is a DIFFERENT original after actual lease expiry. Neither visible rows nor teardown repair the failed original. */
internal object TestActiveRecurrentRecoveryCasesV1 {
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
