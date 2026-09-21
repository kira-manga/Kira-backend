package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.catalog.withTestActiveFirstCut
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/** Same-JVM fresh assembly, real SQL/native HTTP boundary. Neither historical result becomes authority. */
internal object TestActiveRecoveryCheckpointConnectionV1 {
    fun recoveredSealFeedsFreshNativeCheckpoint(tls: VersionBoundPersistenceConnectedFixture) {
        val ordinary = TestActiveOrdinaryRawFixtureV1()
        val raw = TestActiveInitialCheckpointRawFixtureV1()
        val http = ordinary.factories.let { TestActiveOrdinaryRawHttpV1(it.sts, it.kms, it.s3, raw.input) }
        withTestActiveFirstCut(tls, ordinaryRawHttp = http, activeSealRecovery = true) { first ->
            assertNotNull(first.process.activeOrdinarySealRecovery)
            assertNotNull(first.process.initialCheckpoint)
            first.initial.withExchange { exchange -> exchange.enroll(first.initial.candidate()); exchange.assertReleased() }
            val captured = first.capture()
            first.awaitNativeReclaimed()
            TestActiveOrdinarySealFixtureV1(first, captured, ordinary).use { history ->
                TestActiveSealRecoveryHistoryV1.cut(history, ActiveSealRecoveryHistoryCutV1.CANONICAL)
                val counters = first.counters()
                TestActiveSealRecoveryFixtureV1(history).use { recovery ->
                    recovery.awaitOldLease()
                    recovery.recover() // Historical Completed deliberately discarded, never a reconstructed A Verified.
                    recovery.assertReleased()
                    recovery.probe.assertCommittedAndReleased()
                }
                assertEquals("SEAL_VERIFIED", history.control()["seal_state"])
                assertNull(history.control()["checkpoint_result"])
                assertNotNull(history.native.stored)
                assertTrue(raw.order.isEmpty(), "Recovery cannot borrow the independently retained checkpoint reader.")
                val paid = first.paidImage()
                awaitInitialCheckpointLeaseExpiry(history.observer, history.scope)
                TestActiveInitialCheckpointFixtureV1(history, null, raw).use { checkpoint ->
                    checkpoint.withFreshAssembly { fresh ->
                        assertNull(fresh.verified, "Restart uses current native and SQL proof, not an old A capability.")
                        assertNotNull(fresh.process.activeOrdinarySealRecovery)
                        val completed = fresh.checkpoint(fresh.begin(restart = true))
                        fresh.assertReleased()
                        assertEquals(listOf("STS", "SEAL_LIST", "SEAL_GET", "DECRYPT", "PASS1", "PASS2"), raw.order)
                        TestActiveInitialCheckpointCasesV1.assertOrdinaryDelta(counters, fresh.counters(), 0)
                        TestActiveInitialCheckpointCasesV1.assertCheckpoint(fresh, completed)
                        assertTrue(paid == first.paidImage(), "The existing paid canonical/wire slot must remain unchanged.")
                    }
                }
            }
        }
    }
}
