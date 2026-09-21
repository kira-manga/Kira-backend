package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.security.terminalFrame
import me.manga.kira.backend.security.terminalHash
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.sql.Timestamp
import java.time.Instant

/** Genuine initial EMPTY histories; new result is historical-only and never consumed as A/checkpoint/health. */
internal object TestActiveSealRecoveryCasesV1 {
    fun continueInitial(tls: VersionBoundPersistenceConnectedFixture, cut: ActiveSealRecoveryHistoryCutV1, enrolled: Boolean = false, horizon: Instant? = null) =
        withActiveSealRecoveryHistory(tls, cut, enrolled, horizon) { h -> TestActiveSealRecoveryFixtureV1(h).use { f ->
            require(cut !== ActiveSealRecoveryHistoryCutV1.VERIFIED)
            f.awaitOldLease()
            val before = TestActiveSealRecoveryObservationV1.image(h.observer)
            val order = h.native.order.size; val requests = h.native.requests.size
            var actualClose = 0
            h.native.onNativeClose = {
                f.probe.assertPhysicallyReleased()
                assertEquals(1L, h.process.publicationLanes.activeOwners().totalOwners)
                assertFalse(f.probe.calls.any { it.step === TestActiveOrdinarySealRecoveryStepV1.VERIFY })
                actualClose++
            }
            var frozenWinner: List<String>? = null
            h.native.beforeS3 = { request ->
                val paid = TestActiveSealRecoveryObservationV1.image(h.observer).getValue(TestActiveSealRecoveryObservationV1.PAID)
                if (frozenWinner == null) frozenWinner = paid else assertTrue(frozenWinner == paid)
                if (request.kind == "PUT") assertArrayEquals(h.paid()["wire_bytes"] as ByteArray, request.body)
            }
            f.probe.before = { call -> if (call.step === TestActiveOrdinarySealRecoveryStepV1.VERIFY) {
                assertTrue(actualClose > 0); h.native.assertDisposed()
                assertEquals(0L, h.process.publicationLanes.activeOwners().totalOwners)
            } }
            val original = f.begin()
            val result = f.recover(original)
            f.assertReleased(); f.probe.assertCommittedAndReleased()
            assertNotNull(frozenWinner)
            assertTrue(frozenWinner == TestActiveSealRecoveryObservationV1.image(h.observer).getValue(TestActiveSealRecoveryObservationV1.PAID))
            TestActiveSealRecoveryObservationV1.completed(before, h.observer, h.scope)
            val paid = h.paid(); val control = h.control(); val stored = checkNotNull(h.native.stored)
            assertEquals(original.attemptId, control["lease_owner"]); assertEquals(original.leaseToken, control["lease_token"])
            assertEquals(h.scope, result.scope); assertEquals(stored.key, result.objectKey); assertEquals(stored.version, result.version)
            assertEquals(Sha256.hex(stored.bytes), result.ciphertextSha256); assertArrayEquals(paid["wire_bytes"] as ByteArray, stored.bytes)
            val canonical = Json.parseToJsonElement((paid["canonical_bytes"] as ByteArray).decodeToString()).jsonObject
            val journal = h.process.consumers.journalConfiguration
            val manifest = terminalHash(terminalFrame(listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest",
                journal.declaration().writer.generationId, journal.ordinaryPrefix, "TEST", h.scope.toString(), "1", "1", "0")))
            assertEquals("0", canonical.getValue("eventCount").jsonPrimitive.content)
            assertEquals(manifest, canonical.getValue("eventManifestSha256").jsonPrimitive.content)
            assertEquals(paid["preparing_fencing_token"].toString(), canonical.getValue("preparingFencingToken").jsonPrimitive.content)
            val nativeOrder = h.native.order.drop(order)
            assertEquals(if (cut === ActiveSealRecoveryHistoryCutV1.CANONICAL) 1 else 0, nativeOrder.count { it == "GENERATE" })
            assertEquals(1, nativeOrder.count { it == "DECRYPT" })
            assertEquals(if (cut === ActiveSealRecoveryHistoryCutV1.EXISTING_WIRE) listOf("LIST", "GET") else listOf("LIST", "PUT", "LIST", "GET"),
                h.native.requests.drop(requests).map { it.kind })
            assertEquals(cut === ActiveSealRecoveryHistoryCutV1.CANONICAL, f.probe.calls.any { it.step === TestActiveOrdinarySealRecoveryStepV1.FREEZE })
            assertEquals(2, f.probe.calls.filter { it.step === TestActiveOrdinarySealRecoveryStepV1.EMPTY && it.sql == TestActiveOrdinarySealRecoverySqlV1.keyPresent }.size)
            assertTrue(h.ordinary.sts.requests.isEmpty(), "The EMPTY continuation has no ordinary resolver/publisher.")
            val image = TestActiveSealRecoveryObservationV1.image(h.observer); val providers = h.native.order.toList(); val calls = f.probe.calls.size
            assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
            assertTrue(image == TestActiveSealRecoveryObservationV1.image(h.observer)); assertEquals(providers, h.native.order); assertEquals(calls, f.probe.calls.size)
        } }

    fun livePriorLeaseRefusesWithoutStealOrWaiting(tls: VersionBoundPersistenceConnectedFixture) =
        withActiveSealRecoveryHistory(tls, ActiveSealRecoveryHistoryCutV1.CANONICAL) { h -> TestActiveSealRecoveryFixtureV1(h).use { f ->
            assertEquals(true, h.observer.queryForObject("SELECT lease_owner IS NOT NULL AND lease_expires_at > clock_timestamp() FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, h.scope))
            val before = TestActiveSealRecoveryObservationV1.image(h.observer)
            val original = f.begin()
            assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
            f.assertReleased()
            assertEquals(0L, original.leaseToken); assertTrue(h.native.order.isEmpty())
            assertTrue(f.probe.calls.any { it.sql == TestActiveOrdinarySealRecoverySqlV1.acquire })
            assertTrue(before == TestActiveSealRecoveryObservationV1.image(h.observer))
            assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
        } }

    fun verifiedHistoryBelongsToCheckpointNotThisOriginal(tls: VersionBoundPersistenceConnectedFixture) =
        withActiveSealRecoveryHistory(tls, ActiveSealRecoveryHistoryCutV1.VERIFIED) { h -> TestActiveSealRecoveryFixtureV1(h).use { f ->
            val before = TestActiveSealRecoveryObservationV1.image(h.observer); val rawReads = h.p.f.http.read.requests.size; val providers = h.native.order.toList()
            val original = f.begin()
            assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
            f.assertReleased()
            assertTrue(before == TestActiveSealRecoveryObservationV1.image(h.observer)); assertEquals(rawReads, h.p.f.http.read.requests.size)
            assertEquals(providers, h.native.order); assertEquals(0L, original.leaseToken)
            assertFalse(f.probe.calls.any { it.step !== TestActiveOrdinarySealRecoveryStepV1.READ })
        } }

    /** Preserved contract limitation, not a successful recovery: old immutable retention cannot fund a fresh missing-object request. */
    fun staleAbsentFrozenRetentionRefusesWithoutRegeneration(tls: VersionBoundPersistenceConnectedFixture) =
        withActiveSealRecoveryHistory(tls, ActiveSealRecoveryHistoryCutV1.WIRE_FROZEN, horizon = Instant.parse("2030-01-01T00:00:00Z")) { h ->
            TestActiveSealRecoveryFixtureV1(h).use { f ->
                f.awaitOldLease()
                val before = TestActiveSealRecoveryObservationV1.image(h.observer); val paid = h.first.paidImage(); val order = h.native.order.size
                assertTrue((h.paid()["retain_until"] as Timestamp).toInstant().isAfter(Instant.now()))
                assertNull(h.native.stored)
                val original = f.begin()
                assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
                f.assertReleased()
                assertEquals(listOf("LIST"), h.native.requests.map { it.kind })
                assertEquals(0, h.native.order.drop(order).count { it == "GENERATE" || it == "DECRYPT" })
                assertFalse(f.probe.calls.any { it.step in setOf(TestActiveOrdinarySealRecoveryStepV1.FREEZE, TestActiveOrdinarySealRecoveryStepV1.VERIFY) })
                assertTrue(paid == h.first.paidImage()); assertNull(h.native.stored)
                val after = TestActiveSealRecoveryObservationV1.image(h.observer)
                assertTrue(before.filterKeys { it != "complaint_journal_control" } == after.filterKeys { it != "complaint_journal_control" })
                assertEquals("SEAL_PREPARED", h.control()["seal_state"]); assertNull(h.control()["seal_verification_bytes"])
                assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
            }
        }
}
