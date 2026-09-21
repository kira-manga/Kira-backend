package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.NeverOwnerDeleteAllDataKeys
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.TestAdminBatchDeleteJournalTupleV1
import me.manga.kira.backend.security.TestAdminDeleteJournalTupleV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalTupleV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.util.HexFormat
import java.util.UUID

internal enum class ActiveSyntheticRowCut { FOREIGN_WRITER, FOREIGN_KEY_ALIAS, UNSUPPORTED_FAMILY, MALFORMED_CANONICAL }

/**
 * SYNTHETIC PREPARED resolver inputs, NOT genuine epoch1 AUTH/content/admission history. Direct SQL
 * inserts deliberately issue no receipt, grant, charge, checkpoint or health proof. Only A's actual
 * native STS/S3/KMS/readback plus full-preimage CAS may create VERIFIED. Even the optional later
 * VERIFIED->APPLIED mutation is comparison-only, not a product APPLY or domain deletion.
 */
internal object TestActiveSyntheticResolverCasesV1 {
    fun fourFamiliesAcrossPages(tls: VersionBoundPersistenceConnectedFixture) = withActiveSealFixture(tls) { f ->
        SyntheticPreparedInputs(f).use { inputs ->
            val events = List(36) { inputs.event(it) }
            val higher = inputs.event(100, epoch = 2)
            events.forEach { inputs.insert(it) }; inputs.insert(higher)
            f.ordinary.expect(events)
            val before = f.image(); val counters = f.first.counters()
            val higherImage = inputs.image(higher)
            var keyQueries = 0
            var comparisonOnlyApplied = false
            var preservedProof: String? = null
            f.probe.before = { call -> if (call.step === TestActiveOrdinarySealStepV1.EVIDENCE) {
                f.ordinary.assertDisposed()
                assertEquals(0L, f.process.publicationLanes.activeOwners().totalOwners, "Native publication custody really ends before evidence SQL.")
            } }
            f.probe.after = { call -> if (call.sql == TestActiveCutoffPublicationSqlV1.keyPage) {
                keyQueries++
                if (keyQueries == 3) {
                    val chosen = events.first()
                    preservedProof = inputs.image(chosen, proofOnly = true)
                    assertEquals(1, f.first.initial.foreignUpdate(
                        "UPDATE complaint_journal_publications SET state = 'APPLIED', applied_at = verified_at WHERE event_id = ? AND state = 'VERIFIED'", chosen.route.eventId))
                    assertEquals(preservedProof, inputs.image(chosen, proofOnly = true))
                    comparisonOnlyApplied = true
                }
            } }
            val result = f.seal()
            f.assertReleased()
            assertTrue(comparisonOnlyApplied); assertEquals(6, keyQueries)
            assertEquals(3, f.probe.calls.count { it.sql == TestActiveCutoffPublicationSqlV1.epochPage })
            assertEquals(36, f.probe.calls.count { it.sql == TestActiveCutoffPublicationSqlV1.verified })
            assertEquals(36, f.ordinary.sts.requests.size)
            val raw = checkNotNull(f.ordinary.publisher)
            assertEquals(36, raw.generated()); assertEquals(36, raw.decrypted())
            assertEquals(36, raw.requests.count { it.kind == "PUT" }); assertEquals(36, raw.requests.count { it.kind == "GET" })
            assertEquals(events.map { it.comparison.eventKind }.toSet(), setOf(ComplaintJournalDeletionKindV1.OWNER_DELETE,
                ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE))
            assertEquals(higherImage, inputs.image(higher), "Legitimate higher-epoch PREPARED work is not selected, rejected or resolved.")
            assertTrue(raw.objects.none { it.key == higher.route.objectKey })
            assertEquals(preservedProof, inputs.image(events.first(), proofOnly = true))
            assertEquals(counters, f.first.counters())
            TestActiveOrdinarySealCasesV1.assertUnchangedExcept(f, before,
                setOf("complaint_journal_control", "complaint_test_active_seal_intents", "complaint_journal_publications"))
            TestActiveOrdinarySealCasesV1.assertVerifiedOnly(f, result, 36)
            assertTrue(f.image().getValue("complaint_deletion_journal_applied").isEmpty(), "Comparison-only APPLIED is not actual domain APPLY.")
        }
    }

    fun malformedRowsRefuseBeforeNativeSeal(tls: VersionBoundPersistenceConnectedFixture, cut: ActiveSyntheticRowCut) = withActiveSealFixture(tls) { f ->
        SyntheticPreparedInputs(f).use { inputs ->
            val event = inputs.event(0)
            val journal = f.process.consumers.journalConfiguration
            inputs.insert(event,
                scope = if (cut === ActiveSyntheticRowCut.FOREIGN_KEY_ALIAS) UUID.randomUUID() else f.scope,
                writer = if (cut === ActiveSyntheticRowCut.FOREIGN_WRITER) UUID.randomUUID() else UUID.fromString(journal.declaration().writer.generationId),
                key = if (cut === ActiveSyntheticRowCut.FOREIGN_KEY_ALIAS) "${journal.ordinaryPrefix}writer/${journal.declaration().writer.generationId}/epoch/0000000000000000001/" else event.route.objectKey,
                kind = if (cut === ActiveSyntheticRowCut.UNSUPPORTED_FAMILY) "RETENTION" else event.comparison.eventKind.name,
                bytes = if (cut === ActiveSyntheticRowCut.MALFORMED_CANONICAL) "{}".toByteArray() else event.canonicalBytes())
            f.ordinary.expect(listOf(event))
            val original = f.begin()
            assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
            f.assertSqlReleased(); f.ordinary.assertDisposed(); f.native.assertDisposed()
            assertEquals("RESERVED", f.paid()["state"]); assertNull(f.control()["seal_state"])
            assertTrue(f.native.order.isEmpty()); assertTrue(f.ordinary.sts.requests.isEmpty())
            assertFalse(f.probe.calls.any { it.step === TestActiveOrdinarySealStepV1.CANONICAL })
            assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
        }
    }

    fun unresolvedNativePublicationCannotSeal(tls: VersionBoundPersistenceConnectedFixture) = withActiveSealFixture(tls) { f ->
        SyntheticPreparedInputs(f).use { inputs ->
            val event = inputs.event(0); inputs.insert(event); f.ordinary.expect(listOf(event))
            f.ordinary.hideObjects = true; f.ordinary.lostPutAcknowledgment = true
            val original = f.begin()
            assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
            f.assertSqlReleased(); f.ordinary.assertDisposed()
            val raw = checkNotNull(f.ordinary.publisher)
            assertEquals(1, raw.generated()); assertEquals(0, raw.decrypted())
            assertEquals(3, raw.requests.count { it.kind == "LIST" }); assertEquals(0, raw.requests.count { it.kind == "GET" })
            val puts = raw.requests.filter { it.kind == "PUT" }; assertEquals(2, puts.size)
            assertArrayEquals(puts[0].body, puts[1].body)
            assertEquals(puts[0].header("x-amz-object-lock-retain-until-date"), puts[1].header("x-amz-object-lock-retain-until-date"))
            assertEquals("PREPARED", f.observer.queryForObject("SELECT state FROM complaint_journal_publications WHERE event_id = ?", String::class.java, event.route.eventId))
            assertEquals("RESERVED", f.paid()["state"]); assertNull(f.control()["seal_state"])
            assertTrue(f.native.order.isEmpty())
            assertFalse(f.probe.calls.any { it.step === TestActiveOrdinarySealStepV1.EVIDENCE })
        }
    }

    fun evidenceCommitUnknownCannotRehabilitateOriginal(tls: VersionBoundPersistenceConnectedFixture) = withActiveSealFixture(tls) { f ->
        SyntheticPreparedInputs(f).use { inputs ->
            val event = inputs.event(0); inputs.insert(event); f.ordinary.expect(listOf(event))
            var selected: PersistencePhaseContext? = null
            f.probe.after = { call -> if (selected == null && call.sql == TestActiveCutoffPublicationSqlV1.verified) {
                selected = call.phase
                f.ordinary.assertDisposed()
                val jdbc = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                jdbc.execute("CREATE TEMP TABLE kira_active_evidence_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, jdbc.update("INSERT INTO kira_active_evidence_commit_cut VALUES (1), (1)"))
            } }
            val original = f.begin()
            assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
            f.assertSqlReleased(); f.ordinary.assertDisposed()
            assertEquals(PersistenceDatabaseOutcome.UNKNOWN, checkNotNull(selected).databaseOutcome())
            assertEquals(1, checkNotNull(f.ordinary.publisher).objects.size, "Actual object visibility is not SQL original success.")
            assertEquals("PREPARED", f.observer.queryForObject("SELECT state FROM complaint_journal_publications WHERE event_id = ?", String::class.java, event.route.eventId))
            assertTrue(f.native.order.isEmpty()); assertEquals("RESERVED", f.paid()["state"])
            val image = f.image(); val calls = checkNotNull(f.ordinary.publisher).requests.size
            assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
            assertEquals(image, f.image()); assertEquals(calls, checkNotNull(f.ordinary.publisher).requests.size)
        }
    }
}

/** Synthetic comparison rows only. Never relabel this as genuine ordinary authorization/history. */
private class SyntheticPreparedInputs(private val f: TestActiveOrdinarySealFixtureV1) : AutoCloseable {
    private val routing = f.process.consumers.journalRouting
    private val codec = TestOwnerDeleteJournalCodecV1(routing, NeverOwnerDeleteAllDataKeys())
    private val owned = linkedMapOf<String, UUID>()

    fun event(index: Int, epoch: Long = 1): TestOwnerDeleteJournalEventV1 {
        requireConnectionFree()
        val scope = routing.journalConfiguration.scope
        val fingerprint = ByteArray(32) { (it + index).toByte() }
        val actor = UUID.randomUUID(); val operation = UUID.randomUUID()
        return when (index % 4) {
            0 -> codec.canonicalize(TestOwnerDeleteJournalTupleV1(epoch, actor, 1, operation, fingerprint, scope), listOf(UUID.randomUUID()))
            1 -> codec.canonicalize(TestOwnerDeleteJournalTupleV1(epoch, actor, 1, operation, fingerprint, scope, ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL), emptyList())
            2 -> codec.canonicalizeAdmin(TestAdminDeleteJournalTupleV1(epoch, actor, operation, fingerprint, scope, UUID.randomUUID(), UUID.randomUUID()), UUID.randomUUID())
            else -> codec.canonicalizeAdminBatch(TestAdminBatchDeleteJournalTupleV1(epoch, actor, operation, fingerprint, scope, UUID.randomUUID(),
                List(2) { UUID.randomUUID() }.sortedBy(UUID::toString)), List(2) { UUID.randomUUID() }.sortedBy(UUID::toString))
        }
    }

    fun insert(event: TestOwnerDeleteJournalEventV1, scope: UUID = f.scope,
        writer: UUID = UUID.fromString(routing.journalConfiguration.declaration().writer.generationId), key: String = event.route.objectKey,
        kind: String = event.comparison.eventKind.name, bytes: ByteArray = event.canonicalBytes()) {
        requireConnectionFree()
        try {
            assertEquals(1, f.observer.update("INSERT INTO complaint_journal_publications " +
                "(event_id,data_scope_id,test_only,writer_generation,journal_epoch,event_kind,target_count,routing_key_id,object_key,canonicalizer,event_bytes,semantic_hash,state,created_at) " +
                "VALUES (?,?,true,?,?,?,?,?,?,'kcj-1',?,?,'PREPARED',?)",
                event.route.eventId, scope, writer, event.comparison.epoch, kind, event.complaintIds().size, event.route.routingKeyId, key,
                bytes, HexFormat.of().parseHex(Sha256.hex(bytes)), Timestamp.from(f.native.now())))
            check(owned.put(event.route.eventId, scope) == null)
        } finally { bytes.fill(0) }
    }

    fun image(event: TestOwnerDeleteJournalEventV1, proofOnly: Boolean = false): String = checkNotNull(f.observer.dataSource).connection.use { connection ->
        val expression = if (proofOnly) "(to_jsonb(p) - ARRAY['state','applied_at'])::text" else "jsonb_build_array(to_jsonb(p), p.xmin::text)::text"
        connection.prepareStatement("SELECT $expression FROM complaint_journal_publications p WHERE event_id = ?").use { statement ->
            statement.setString(1, event.route.eventId)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getString(1).also { assertFalse(rows.next()) } }
        }
    }

    override fun close() {
        requireConnectionFree(); f.probe.before = {}; f.probe.after = {}
        owned.forEach { (id, scope) -> assertEquals(1, f.observer.update("DELETE FROM complaint_journal_publications WHERE event_id = ? AND data_scope_id = ?", id, scope)) }
    }
}
