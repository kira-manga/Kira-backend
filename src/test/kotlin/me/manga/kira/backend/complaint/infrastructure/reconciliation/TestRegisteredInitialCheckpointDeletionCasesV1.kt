package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Materially different first-primary families, not repeated synthetic epoch11 fixtures. */
internal object TestRegisteredInitialCheckpointDeletionCasesV1 {
    fun genuine(f: TestRegisteredInitialCheckpointDeletionFixtureV1, creationClosed: Boolean = false) {
        val before = f.counters(); val rows = f.image(); val audits = f.audits()
        if (creationClosed) {
            assertEquals(2, f.foreignUpdate("UPDATE complaint_journal_control SET creation_closed = true WHERE data_scope_id IN (?, ?)", UUID(0, 0), f.scope))
            assertEquals(2L, f.observer.queryForObject("SELECT count(*) FROM complaint_journal_control WHERE data_scope_id IN (?, ?) " +
                "AND creation_closed AND NOT maintenance_closed AND NOT scan_requested", Long::class.java, UUID(0, 0), f.scope))
        }
        val event = f.authorize()
        assertSame(event, f.event)
        assertEquals(f.family, event.comparison.eventKind)
        assertEquals(2L, event.comparison.epoch)
        assertEquals(f.reports.map { it.input.id }.sortedBy(UUID::toString), event.complaintIds())
        f.assertCharge(before)
        assertPrimary(f, "PREPARED")
        assertEquals(rows.getValue("complaints"), f.image().getValue("complaints"), "AUTH cannot erase content.")
        assertEquals(listOf(0, 0, 0, 0), f.native.counts(), "Reserved native lane opens no STS/KMS/S3 during AUTH.")
        assertEquals(1L, f.process.publicationLanes.activeOwners().totalOwners)
        val action = if (f.family == ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) "COMPLAINT_INSTALLATION_DELETE_AUTHORIZED" else "COMPLAINT_DELETE_AUTHORIZED"
        val delta = if (f.family == ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE) 2L else 1L
        assertEquals(audits + (action to ((audits[action] ?: 0L) + delta)), f.audits())
        if (f.family == ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE) {
            assertEquals(2, f.creators.map { it.actor.id }.distinct().size)
            assertEquals(2L, f.observer.queryForObject("SELECT enrolled_count FROM complaint_test_runs WHERE data_scope_id = ?", Long::class.java, f.scope))
        }
        f.proof?.let { proof ->
            assertEquals(true, f.observer.queryForObject("SELECT used_at IS NOT NULL AND scope = ? FROM admin_step_up_grants WHERE id = ?", Boolean::class.java,
                ScopedAdminStepUpScope.COMPLAINT.storedName, proof.grantId))
        }
        val authorized = f.image(); val paid = f.counters(); val paidAudits = f.audits()
        val currentChecks = f.deletion.calls.count { it.sql == TestRegisteredInitialCheckpointDeletionSqlV1.current }
        f.assertReload(verified = false)
        assertEquals(currentChecks, f.deletion.calls.count { it.sql == TestRegisteredInitialCheckpointDeletionSqlV1.current }, "Replay is not a fresh checkpoint gate.")
        assertEquals(authorized, f.image()); assertEquals(paid, f.counters()); assertEquals(paidAudits, f.audits())
        val record = f.publish()
        assertSame(event, record.event)
        assertSame(record.stored, f.native.publisher.objects.single())
        assertEquals(authorized, f.image(), "A genuine native readback is not committed SQL VERIFY.")
        assertPrimary(f, "PREPARED")
        if (f.family == ComplaintJournalDeletionKindV1.OWNER_DELETE) {
            val calls = f.deletion.calls.size
            assertThrows<Exception> { f.ownerPhases.verify(checkNotNull(f.readback)) }
            f.ownerPublisher.reserve().use { unrelatedLane ->
                assertThrows<Exception> { f.ownerPhases.verify(checkNotNull(f.ownerWork), checkNotNull(f.readback), unrelatedLane) }
            }
            assertEquals(calls, f.deletion.calls.size, "Generic readback or another real RESERVED lane is not this lane's cleaned output.")
        }
        f.verify()
        assertPrimary(f, "VERIFIED")
        val verify = f.deletion.calls.filter { it.path in VERIFY_PATHS }
        assertTrue(verify.isNotEmpty())
        assertFalse(verify.any { "complaint_journal_control" in it.sql || "complaint_capacity_counters" in it.sql || "complaint_test_runs" in it.sql },
            "Short VERIFY never reacquires control/counter/run locks.")
        verify.map { it.phase }.distinct().forEach { assertEquals(PersistenceDatabaseOutcome.COMMITTED, it.databaseOutcome()) }
        val verified = f.image(); val providers = f.native.counts()
        f.assertReload(verified = true)
        assertEquals(verified, f.image()); assertEquals(paid, f.counters()); assertEquals(paidAudits, f.audits())
        assertEquals(providers, f.native.counts())
        assertEquals(rows.getValue("complaints"), f.image().getValue("complaints"))
        f.assertReleased()
    }

    fun preparedOnly(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val before = f.counters()
        f.authorize(); assertPrimary(f, "PREPARED"); f.assertCharge(before)
        f.assertReload(verified = false)
        assertEquals(listOf(0, 0, 0, 0), f.native.counts())
        assertNull(f.readback); assertNull(f.record)
        assertEquals(1L, f.process.publicationLanes.activeOwners().totalOwners)
        // Deliberately stop without PUB/VERIFY. The enclosing fixture closes the actual RESERVED owner.
    }

    fun assertPrimary(f: TestRegisteredInitialCheckpointDeletionFixtureV1, state: String) {
        val event = checkNotNull(f.event)
        val row = f.publication()
        assertEquals(event.route.eventId, row["event_id"]); assertEquals(event.route.objectKey, row["object_key"])
        assertEquals(f.family.name, row["event_kind"]); assertEquals(2L, (row.getValue("journal_epoch") as Number).toLong())
        assertEquals(f.reports.size, (row.getValue("target_count") as Number).toInt())
        assertEquals(state, row["state"]); assertNull(row["applied_at"])
        val bytes = event.canonicalBytes()
        try { assertArrayEquals(bytes, row["event_bytes"] as ByteArray) } finally { bytes.fill(0) }
        if (state == "PREPARED") { assertNull(row["verification_bytes"]); assertNull(row["object_version"]) }
        else { assertNotNull(row["verification_bytes"]); assertEquals(checkNotNull(f.record).stored.version, row["object_version"]) }
        assertEquals(1L, f.observer.queryForObject("SELECT count(*) FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ? " +
            "AND event_id = ? AND publication_ref = event_id AND test_only AND state = 'RESERVED' AND accounting_version = 1 " +
            "AND reserved_amounts = ?::bigint[] AND converted_amounts IS NULL AND converted_at IS NULL", Long::class.java,
            f.scope, event.route.eventId, f.recoveryCharge.toLongArray().joinToString(",", "{", "}")))
        val normal = checkNotNull(f.observer.queryForObject("SELECT count(*) FROM complaint_idempotency_receipts WHERE data_scope_id = ? " +
            "AND operation IN ('OWNER_DELETE','ADMIN_DELETE','ADMIN_BATCH_DELETE')", Long::class.java, f.scope))
        val all = checkNotNull(f.observer.queryForObject("SELECT count(*) FROM installation_deletion_receipts WHERE data_scope_id = ?", Long::class.java, f.scope))
        assertEquals(1L, normal + all)
        val receiptTable = if (f.family == ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) "installation_deletion_receipts" else "complaint_idempotency_receipts"
        assertEquals(1L, f.observer.queryForObject("SELECT count(*) FROM $receiptTable WHERE data_scope_id = ? AND test_only " +
            "AND state = 'AUTHORIZED_DELETE' AND publication_ref = ? AND authorized_at = ? AND outcome IS NULL " +
            "AND response_status IS NULL AND completed_at IS NULL AND expires_at IS NULL AND external_event_id IS NULL " +
            "AND external_epoch IS NULL AND external_object_version IS NULL AND external_ciphertext_hash IS NULL", Long::class.java,
            f.scope, event.route.eventId, row["created_at"]))
        for (table in listOf("complaint_deletion_journal_applied", "complaint_deletion_journal_retirements")) {
            assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM $table WHERE data_scope_id = ?", Long::class.java, f.scope))
        }
    }

    private val VERIFY_PATHS = setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY,
        PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY)
}
