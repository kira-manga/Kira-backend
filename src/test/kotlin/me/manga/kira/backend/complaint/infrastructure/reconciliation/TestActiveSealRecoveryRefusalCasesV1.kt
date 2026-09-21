package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.NeverOwnerDeleteAllDataKeys
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalTupleV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.sql.Timestamp
import java.util.HexFormat
import java.util.UUID

internal enum class ActiveSealRecoveryRowCutV1 { SCOPED, OTHER_WRITER, FOREIGN_KEY_ALIAS, HIGHER_EPOCH }

/** Negative synthetic ordinary rows are explicitly NOT AUTH/admission history. No trigger/constraint is disabled. */
internal object TestActiveSealRecoveryRefusalCasesV1 {
    fun localClosure(tls: VersionBoundPersistenceConnectedFixture, cut: ActiveSealRecoveryRowCutV1) =
        withActiveSealRecoveryHistory(tls, ActiveSealRecoveryHistoryCutV1.CANONICAL) { h ->
            SyntheticRecoveryPublicationV1(h, cut).use { row ->
                row.insert()
                TestActiveSealRecoveryFixtureV1(h).use { f ->
                    val before = TestActiveSealRecoveryObservationV1.image(h.observer)
                    val original = f.begin()
                    assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
                    f.assertReleased()
                    assertEquals(0L, original.leaseToken); assertTrue(h.native.order.isEmpty()); assertTrue(h.ordinary.sts.requests.isEmpty())
                    assertTrue(before == TestActiveSealRecoveryObservationV1.image(h.observer))
                    assertFalse(f.probe.calls.any { it.step !== TestActiveOrdinarySealRecoveryStepV1.READ })
                }
            }
        }

    fun nonemptyCanonicalCannotBeRelabelledEmptyAfterLocalRowsDisappear(tls: VersionBoundPersistenceConnectedFixture) =
        withActiveSealRecoveryOrigin(tls) { h ->
            SyntheticRecoveryPublicationV1(h, ActiveSealRecoveryRowCutV1.SCOPED).use { input ->
                input.insert(); h.ordinary.expect(listOf(input.event))
                // Actual A resolver/native verification/canonical producer over explicitly synthetic PREPARED input.
                // It is not an ordinary authorization history and does not claim a content producer.
                TestActiveSealRecoveryHistoryV1.cut(h, ActiveSealRecoveryHistoryCutV1.CANONICAL)
                val canonical = Json.parseToJsonElement((h.paid()["canonical_bytes"] as ByteArray).decodeToString()).jsonObject
                assertEquals("1", canonical.getValue("eventCount").jsonPrimitive.content)
                input.remove() // Fault-only loss of the synthetic local row; immutable canonical remains genuinely A-produced.
                assertEquals(0L, h.observer.queryForObject("SELECT count(*) FROM complaint_journal_publications", Long::class.java))
                TestActiveSealRecoveryFixtureV1(h).use { f ->
                    val before = TestActiveSealRecoveryObservationV1.image(h.observer)
                    val original = f.begin()
                    assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
                    f.assertReleased()
                    assertEquals(0L, original.leaseToken); assertTrue(h.native.order.isEmpty())
                    assertTrue(before == TestActiveSealRecoveryObservationV1.image(h.observer))
                    assertFalse(f.probe.calls.any { it.sql == TestActiveOrdinarySealRecoverySqlV1.epochPresent },
                        "Stored nonempty canonical is refused before an empty local observation can relabel it.")
                }
            }
        }

    fun currentFullDDriftRefusesBeforeRawOrLease(tls: VersionBoundPersistenceConnectedFixture) =
        withActiveSealRecoveryHistory(tls, ActiveSealRecoveryHistoryCutV1.CANONICAL) { h ->
            assertEquals(1, h.observer.update("UPDATE complaint_journal_control SET desired_generation = desired_generation + 1 WHERE data_scope_id = ?", h.scope))
            TestActiveSealRecoveryFixtureV1(h).use { f ->
                val before = TestActiveSealRecoveryObservationV1.image(h.observer); val raw = h.p.f.http.read.requests.size
                val original = f.begin()
                assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
                f.assertReleased()
                assertEquals(0L, original.leaseToken); assertTrue(h.native.order.isEmpty()); assertEquals(raw, h.p.f.http.read.requests.size)
                assertTrue(before == TestActiveSealRecoveryObservationV1.image(h.observer))
                assertNull(h.control()["seal_verification_bytes"])
            }
        }
}

/** One explicitly unpaid comparison-only row, inserted/deleted under the existing fixture principal. */
private class SyntheticRecoveryPublicationV1(private val h: TestActiveOrdinarySealFixtureV1, cut: ActiveSealRecoveryRowCutV1) : AutoCloseable {
    private val routing = h.process.consumers.journalRouting
    val event = TestOwnerDeleteJournalCodecV1(routing, NeverOwnerDeleteAllDataKeys()).canonicalize(
        TestOwnerDeleteJournalTupleV1(if (cut === ActiveSealRecoveryRowCutV1.HIGHER_EPOCH) 2 else 1, UUID.randomUUID(), 1,
            UUID.randomUUID(), ByteArray(32) { it.toByte() }, routing.journalConfiguration.scope), listOf(UUID.randomUUID()))
    private val scope = if (cut === ActiveSealRecoveryRowCutV1.FOREIGN_KEY_ALIAS) UUID.randomUUID() else h.scope
    private val writer = if (cut === ActiveSealRecoveryRowCutV1.OTHER_WRITER) UUID.randomUUID() else UUID.fromString(routing.journalConfiguration.declaration().writer.generationId)
    private val key = if (cut === ActiveSealRecoveryRowCutV1.FOREIGN_KEY_ALIAS)
        "${routing.journalConfiguration.ordinaryPrefix}writer/${routing.journalConfiguration.declaration().writer.generationId}/epoch/0000000000000000001/" else event.route.objectKey
    private var inserted = false

    fun insert() {
        check(!inserted)
        val bytes = event.canonicalBytes()
        try {
            assertEquals(1, h.observer.update("INSERT INTO complaint_journal_publications " +
                "(event_id,data_scope_id,test_only,writer_generation,journal_epoch,event_kind,target_count,routing_key_id,object_key,canonicalizer,event_bytes,semantic_hash,state,created_at) " +
                "VALUES (?,?,true,?,?,?,?,?,?,'kcj-1',?,?,'PREPARED',?)",
                event.route.eventId, scope, writer, event.comparison.epoch, event.comparison.eventKind.name, event.complaintIds().size,
                event.route.routingKeyId, key, bytes, HexFormat.of().parseHex(Sha256.hex(bytes)), Timestamp.from(h.native.now())))
            inserted = true
        } finally { bytes.fill(0) }
    }
    fun remove() {
        check(inserted)
        assertEquals(1, h.observer.update("DELETE FROM complaint_journal_publications WHERE event_id = ? AND data_scope_id = ?", event.route.eventId, scope))
        inserted = false
    }
    override fun close() { if (inserted) remove() }
}
