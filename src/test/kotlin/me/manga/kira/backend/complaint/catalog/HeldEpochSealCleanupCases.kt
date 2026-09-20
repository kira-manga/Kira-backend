package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochSealCustodyV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real returned construction owners and the existing owned-caller gates. A request to stop is never native completion. */
internal class HeldEpochSealCleanupCases(private val h: HeldEpochSealTestFixture) {
    fun partial(cut: HeldSealPartialCut) {
        val leader = h.capture()
        var kmsPrepareEntries = 0
        when (cut) {
            HeldSealPartialCut.TARGET_FACTORY -> h.wire.beforeStsFactory = { if (it == 2) throw IOException(HeldEpochSealHttpFixture.PRIVATE_TEXT) }

            HeldSealPartialCut.KMS_FACTORY -> h.wire.beforeKmsFactory = { throw IOException(HeldEpochSealHttpFixture.PRIVATE_TEXT) }

            HeldSealPartialCut.KMS_PREPARE -> h.wire.kms.beforePrepare = {
                h.wire.boundary()
                kmsPrepareEntries++ // Inside the actual raw HTTP prepareRequest, before it can return an executable.
                throw IOException(HeldEpochSealHttpFixture.PRIVATE_TEXT)
            }

            HeldSealPartialCut.KMS_BODY -> h.wire.changeKmsReply = { it.beforeRead = { throw IOException(HeldEpochSealHttpFixture.PRIVATE_TEXT) } }
        }
        if (cut != HeldSealPartialCut.KMS_BODY) h.expectRetainedCleanup()
        h.wire.assertSanitized(assertThrows<PersistencePhaseException> { h.open(leader.campaign) })
        assertEquals(if (cut == HeldSealPartialCut.TARGET_FACTORY) 2 else 3, h.wire.sts.requests.size)
        assertEquals(if (cut == HeldSealPartialCut.TARGET_FACTORY) 1 else 2, h.wire.sts.createdClients)
        assertEquals(h.wire.sts.createdClients, h.wire.sts.returnedClientCloses, "Every actually arrived STS client must still close.")
        assertEquals(if (cut == HeldSealPartialCut.KMS_BODY) 1 else 0, h.wire.kms.requests.size)
        assertEquals(h.wire.kms.createdClients, h.wire.kms.returnedClientCloses)
        h.wire.assertExchangesClosed()
        h.assertCanonical(leader.receipt.token)
        if (cut == HeldSealPartialCut.KMS_BODY) {
            h.assertReleased()
        } else {
            val retained = checkNotNull(h.activeAttempt())
            assertEquals(1L, h.lanes.activeOwners().totalOwners, "A factory or native prepare throw cannot prove its unreturned partial work ended.")
            val traffic = h.wire.requests.toList()
            repeat(2) { h.wire.assertSanitized(assertThrows<RuntimeException> { h.wire.acquisition.close() }) }
            if (cut == HeldSealPartialCut.KMS_PREPARE) {
                h.wire.assertSanitized(assertThrows<PersistencePhaseException> { h.open(leader.campaign) })
                assertEquals(1, kmsPrepareEntries, "No retry may reenter the native prepare that did not return an executable.")
                assertEquals(2, h.wire.sts.createdClients)
                assertEquals(1, h.wire.kms.createdClients)
                assertTrue(h.wire.kms.replies.isEmpty(), "No KMS executable or response body was returned, despite the arrived HTTP client closing.")
            }
            assertSame(retained, h.activeAttempt())
            assertEquals(1L, h.lanes.activeOwners().totalOwners)
            assertEquals(traffic, h.wire.requests)
            assertEquals(h.wire.sts.createdClients, h.wire.sts.closedClients, "Sticky cleanup must not close an arrived client twice.")
            assertEquals(h.wire.kms.createdClients, h.wire.kms.closedClients, "Even an unreturned prepare must not close its HTTP client twice.")
        }
    }

    fun stickyNativeClose() {
        val leader = h.capture()
        val held = h.open(leader.campaign)
        val retained = checkNotNull(h.activeAttempt())
        val canonical = h.assertCanonical(leader.receipt.token)
        h.wire.kms.onClientClose = {
            h.wire.boundary()
            throw IOException(HeldEpochSealHttpFixture.PRIVATE_TEXT)
        }
        h.expectRetainedCleanup()
        repeat(2) { h.wire.assertSanitized(assertThrows<RuntimeException> { held.close() }) }
        assertEquals(1, h.wire.kms.closedClients)
        assertEquals(0, h.wire.kms.returnedClientCloses)
        assertEquals(2, h.wire.sts.returnedClientCloses, "A KMS close failure must not prevent either returned STS client from being closed.")
        assertSame(retained, h.activeAttempt())
        assertEquals(1L, h.lanes.activeOwners().totalOwners)
        assertEquals(canonical, h.sealRow())
        assertThrows<PersistencePhaseException> { retained.requireRunning() }
        h.wire.assertExchangesClosed()
    }

    fun foreignStop(cut: HeldSealStopCut) {
        val leader = h.capture()
        val returned = AtomicReference<CatalogEpochSealCustodyV1?>()
        val originalCallerInterrupted = AtomicBoolean()
        val heldFailure = AtomicReference<Throwable?>()
        val unrelated = if (cut == HeldSealStopCut.COLD_OWNER_DURING_KMS) checkNotNull(h.lanes.tryRoutinePublication()) else null
        try {
            OwnedCallerTestScope().use { callers ->
                val gate = callers.gate()
                if (cut != HeldSealStopCut.FOREIGN_HELD_CLOSE) h.wire.changeKmsReply = { it.beforeCall = gate::hold }
                val worker = callers.launch {
                    val result = runCatching {
                        val owner = h.open(leader.campaign)
                        returned.set(owner)
                        if (cut == HeldSealStopCut.FOREIGN_HELD_CLOSE) gate.hold()
                    }
                    try {
                        returned.get()?.let { heldFailure.set(runCatching(it::close).exceptionOrNull()) }
                        originalCallerInterrupted.set(Thread.currentThread().isInterrupted)
                    } finally {
                        Thread.interrupted() // After observation, so shared fixture cleanup does not inherit this deliberate interrupt.
                    }
                    result
                }
                try {
                    gate.awaitEntered()
                    assertEquals(3, h.wire.sts.requests.size)
                    assertEquals(1, h.wire.kms.requests.size)
                    assertNotNull(h.activeAttempt())
                    assertEquals(if (unrelated == null) 1L else 2L, h.lanes.activeOwners().totalOwners)
                    assertEquals(0, h.wire.sts.closedClients + h.wire.kms.closedClients)
                    when (cut) {
                        HeldSealStopCut.COLD_OWNER_DURING_KMS -> {
                            h.wire.assertSanitized(assertThrows<RuntimeException> { h.wire.acquisition.close() })
                            // Closing only the cold acquisition must not stop or clear its borrowed entire lane registry.
                            checkNotNull(h.lanes.tryRoutinePublication()).close()
                        }

                        HeldSealStopCut.INTERRUPTED_KMS -> worker.thread.interrupt()

                        HeldSealStopCut.FOREIGN_HELD_CLOSE -> {
                            h.wire.assertSanitized(assertThrows<RuntimeException> { checkNotNull(returned.get()).close() })
                        }
                    }
                    assertEquals(
                        0,
                        h.wire.sts.closedClients + h.wire.kms.closedClients,
                        "Foreign stop/interrupt cannot close a caller's in-flight native work.",
                    )
                    assertNotNull(h.activeAttempt())
                } finally {
                    gate.release()
                }
                val result = worker.value()
                if (cut == HeldSealStopCut.FOREIGN_HELD_CLOSE) {
                    assertTrue(result.isSuccess)
                    assertNotNull(returned.get())
                } else {
                    h.wire.assertSanitized(checkNotNull(result.exceptionOrNull()))
                    assertNull(returned.get())
                }
                heldFailure.get()?.let(h.wire::assertSanitized)
                assertEquals(cut == HeldSealStopCut.INTERRUPTED_KMS, originalCallerInterrupted.get())
                assertNull(h.activeAttempt())
                assertEquals(if (unrelated == null) 0L else 1L, h.lanes.activeOwners().totalOwners)
                assertEquals(2, h.wire.sts.returnedClientCloses)
                assertEquals(1, h.wire.kms.returnedClientCloses)
                assertFalse(Thread.currentThread().isInterrupted)
            }
        } finally {
            unrelated?.close()
        }
        h.assertCanonical(leader.receipt.token)
        h.assertReleased()
    }
}

internal enum class HeldSealPartialCut { TARGET_FACTORY, KMS_FACTORY, KMS_PREPARE, KMS_BODY }
internal enum class HeldSealStopCut { COLD_OWNER_DURING_KMS, INTERRUPTED_KMS, FOREIGN_HELD_CLOSE }
