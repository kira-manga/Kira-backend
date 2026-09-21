package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.catalog.SignedActivationObservation
import me.manga.kira.backend.complaint.catalog.withTestActiveFirstCutSuccessor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows

/** One genuine C-successor -> A2 native EMPTY-seal join. Source authored only; no process/health claim. */
internal object TestActiveRecoveredOrdinarySealCasesV1 {
    fun genuineCapturedSuccessorSealsEmpty(tls: VersionBoundPersistenceConnectedFixture) {
        val ordinary = TestActiveOrdinaryRawFixtureV1()
        withTestActiveFirstCutSuccessor(tls, ordinaryRawHttp = ordinary.factories) { successor ->
            val first = successor.first
            val uncharged = first.counters()
            val captured = first.capture()
            first.awaitNativeReclaimed(); first.assertCharge(uncharged)
            val charged = first.counters()
            val paid = first.paidImage()
            val captureToken = first.control().getValue("rotation_capture_token") as Long

            // Both higher tokens come from actual successor commits, never direct control mutation.
            successor.recover()
            assertEquals(captureToken + 1, first.control()["lease_token"])
            val recovered = successor.recover()
            val currentToken = first.control().getValue("lease_token") as Long
            assertEquals(captureToken + 2, currentToken)
            assertNull(first.control()["lease_owner"]); assertNull(first.control()["lease_expires_at"])
            assertEquals(paid, first.paidImage()); assertEquals(charged, first.counters())
            assertTrue(successor.nativeSessions.isEmpty(), "CAPTURED successors never perform another capture.")

            // Reuse A's exact raw factories, passive M-only SQL probe and proof/cleanup assertions.
            // The fixture's genuine original Captured is NOT passed to A: only recovered is claimed.
            TestActiveOrdinarySealFixtureV1(first, captured, ordinary).use { f ->
                val before = f.image()
                val history = paidHistory(f)
                var frozenWinner: List<String>? = null
                f.native.beforeS3 = { request ->
                    f.assertProviderBoundary()
                    assertEquals("WIRE_FROZEN", f.paid()["state"])
                    val actual = f.image().getValue("complaint_test_active_seal_intents")
                    if (frozenWinner == null) frozenWinner = actual else assertEquals(frozenWinner, actual)
                    if (request.kind == "PUT") assertArrayEquals(f.paid()["wire_bytes"] as ByteArray, request.body)
                }
                f.native.onNativeClose = {
                    f.assertSqlReleased()
                    assertEquals(1L, f.process.publicationLanes.activeOwners().totalOwners,
                        "Actual native close precedes shared-J seal-lane release.")
                }
                val original = TestActiveOrdinarySealV1.withHttpFixture(recovered, f.registration, first.assembly,
                    f.p.f.http::readClient, SignedActivationObservation.WALL_CLOCK)
                assertNull(f.probe.original); f.probe.original = original
                val verified = f.seal(original)
                f.assertReleased()
                assertEquals(currentToken + 1, original.leaseToken, "A must exceed current control, not just old request/capture.")
                assertEquals(original.leaseToken, f.control()["lease_token"])
                assertEquals(original.leaseToken, f.paid()["preparing_fencing_token"])
                assertEquals(history, paidHistory(f), "Every paid birth/capture identity and the one 2MiB charge stay exact.")
                assertEquals(charged, first.counters())
                assertEquals(frozenWinner, f.image().getValue("complaint_test_active_seal_intents"))
                TestActiveOrdinarySealCasesV1.assertUnchangedExcept(f, before,
                    setOf("complaint_journal_control", "complaint_test_active_seal_intents"))
                TestActiveOrdinarySealCasesV1.assertVerifiedOnly(f, verified, 0)
                assertTrue(ordinary.sts.requests.isEmpty(), "EMPTY is not a fabricated ordinary content publication.")
                assertEquals(listOf("LIST", "PUT", "LIST", "GET"), f.native.requests.map { it.kind })
                assertEquals(1, f.native.order.count { it == "GENERATE" })
                assertEquals(1, f.native.order.count { it == "DECRYPT" })
                assertEquals(2, f.probe.calls.count { it.sql == TestActiveCutoffPublicationSqlV1.keyPage })

                val after = f.image(); val providers = f.native.order.toList(); val calls = f.probe.calls.size
                assertThrows<RuntimeException> { recovered.claimSeal(f.registration, first.assembly) }
                assertThrows<RuntimeException> { TestActiveOrdinarySealV1.begin(recovered, f.registration, first.assembly) }
                assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
                assertEquals(after, f.image()); assertEquals(providers, f.native.order); assertEquals(calls, f.probe.calls.size)
            }
        }
    }

    private fun paidHistory(f: TestActiveOrdinarySealFixtureV1) =
        Json.parseToJsonElement(f.first.paidImage().single()).jsonArray[0].jsonObject.filterKeys { it !in sealFields }

    // These alone legitimately change during A's CANONICAL/FREEZE steps; all other V26 fields are historical.
    private val sealFields = setOf("state", "object_id", "object_key", "routing_key_id", "preparing_fencing_token", "seal_encoding_hash",
        "canonicalizer", "canonical_bytes", "canonical_hash", "retention_floor", "created_at", "wire_bytes", "wire_hash", "checksum_sha256",
        "content_type", "object_lock_mode", "retain_until", "metadata_bytes", "metadata_hash", "frozen_at")
}
