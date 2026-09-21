package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.catalog.withTestActiveFirstCut
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveOrdinarySealRecoveryInputV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

/** Every cut is a genuine initial A history, born with the optional C recovery recipe before full D. */
internal enum class ActiveSealRecoveryHistoryCutV1 { CANONICAL, WIRE_FROZEN, EXISTING_WIRE, VERIFIED }

internal fun withActiveSealRecoveryHistory(
    tls: VersionBoundPersistenceConnectedFixture,
    cut: ActiveSealRecoveryHistoryCutV1,
    enrolled: Boolean = false,
    horizon: Instant? = null,
    recoveryInput: TestActiveOrdinarySealRecoveryInputV1? = null,
    action: (TestActiveOrdinarySealFixtureV1) -> Unit,
) = withActiveSealRecoveryOrigin(tls, enrolled, horizon, recoveryInput) { history ->
    TestActiveSealRecoveryHistoryV1.cut(history, cut)
    action(history)
}

internal fun withActiveSealRecoveryOrigin(
    tls: VersionBoundPersistenceConnectedFixture,
    enrolled: Boolean = false,
    horizon: Instant? = null,
    recoveryInput: TestActiveOrdinarySealRecoveryInputV1? = null,
    action: (TestActiveOrdinarySealFixtureV1) -> Unit,
) {
    val ordinary = TestActiveOrdinaryRawFixtureV1()
    val raw = ordinary.factories
    val selected = if (recoveryInput == null) raw else TestActiveOrdinaryRawHttpV1(raw.sts, raw.kms, raw.s3, activeSealRecovery = recoveryInput)
    withTestActiveFirstCut(tls, ordinaryRawHttp = selected, activeSealRecovery = true, sealRecoveryHorizon = horizon) { first ->
        if (enrolled) first.initial.withExchange { exchange ->
            exchange.enroll(first.initial.candidate()); exchange.assertReleased()
            assertEquals(1L, first.observer.queryForObject("SELECT enrolled_count FROM complaint_test_runs WHERE data_scope_id = ?", Long::class.java, first.scope))
        }
        val captured = first.capture()
        first.awaitNativeReclaimed()
        TestActiveOrdinarySealFixtureV1(first, captured, ordinary).use(action)
    }
}

/** Failure-only afterCommit fault: known COMMITTED + actual release, NOT a durable TLS UNKNOWN history. */
internal object TestActiveSealRecoveryHistoryV1 {
    fun cut(f: TestActiveOrdinarySealFixtureV1, cut: ActiveSealRecoveryHistoryCutV1) {
        val counters = f.first.counters()
        var selected: PersistencePhaseContext? = null
        f.native.lostPutAcknowledgment = cut === ActiveSealRecoveryHistoryCutV1.EXISTING_WIRE
        f.native.onNativeClose = {
            f.assertSqlReleased()
            assertEquals(1L, f.process.publicationLanes.activeOwners().totalOwners)
        }
        f.probe.after = { call ->
            val matches = when (cut) {
                ActiveSealRecoveryHistoryCutV1.CANONICAL -> call.step === TestActiveOrdinarySealStepV1.CANONICAL && call.sql == TestActiveOrdinarySealSqlV1.prepareControl
                ActiveSealRecoveryHistoryCutV1.WIRE_FROZEN -> call.step === TestActiveOrdinarySealStepV1.FREEZE && call.sql == TestActiveOrdinarySealSqlV1.freeze
                ActiveSealRecoveryHistoryCutV1.EXISTING_WIRE -> call.step === TestActiveOrdinarySealStepV1.RENEW && call.sql == TestActiveOrdinarySealSqlV1.renew &&
                    f.native.stored != null && f.native.s3Closed > 0
                ActiveSealRecoveryHistoryCutV1.VERIFIED -> call.step === TestActiveOrdinarySealStepV1.VERIFY && call.sql == TestActiveOrdinarySealSqlV1.verifyControl
            }
            if (matches && selected == null) {
                selected = call.phase
                if (cut === ActiveSealRecoveryHistoryCutV1.EXISTING_WIRE) {
                    f.native.assertDisposed()
                    assertEquals(0L, f.process.publicationLanes.activeOwners().totalOwners, "This cut follows actual native cleanup, not just PUT visibility.")
                }
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() { error("Synthetic initial A post-COMMIT lost acknowledgment; no success is supplied.") }
                })
            }
        }
        val original = f.begin()
        try { assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) } }
        finally { f.probe.after = {}; f.native.onNativeClose = {} }
        assertNotNull(selected, "The genuine history must reach its selected fault; setup failure is not qualification.")
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(selected).databaseOutcome())
        f.assertReleased()
        assertTrue(counters == f.first.counters(), "Initial A cannot recharge C's paid slot.")
        assertEquals(if (cut === ActiveSealRecoveryHistoryCutV1.CANONICAL) "CANONICAL" else "WIRE_FROZEN", f.paid()["state"])
        assertEquals(if (cut === ActiveSealRecoveryHistoryCutV1.VERIFIED) "SEAL_VERIFIED" else "SEAL_PREPARED", f.control()["seal_state"])
        if (cut === ActiveSealRecoveryHistoryCutV1.CANONICAL) assertTrue(f.native.order.isEmpty())
        if (cut in setOf(ActiveSealRecoveryHistoryCutV1.CANONICAL, ActiveSealRecoveryHistoryCutV1.WIRE_FROZEN)) {
            assertNull(f.native.stored); assertTrue(f.native.requests.isEmpty())
        } else {
            val stored = checkNotNull(f.native.stored)
            assertArrayEquals(f.paid()["wire_bytes"] as ByteArray, stored.bytes)
            assertArrayEquals(f.native.requests.single { it.kind == "PUT" }.body, stored.bytes)
            assertEquals(listOf("LIST", "PUT", "LIST", "GET"), f.native.requests.map { it.kind })
            if (cut === ActiveSealRecoveryHistoryCutV1.EXISTING_WIRE) {
                assertEquals(500, f.native.requests.single { it.kind == "PUT" }.reply?.status)
                assertFalse(f.probe.calls.any { it.step === TestActiveOrdinarySealStepV1.VERIFY })
            }
        }
        val image = f.image(); val calls = f.probe.calls.size; val providers = f.native.order.toList()
        assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
        assertThrows<RuntimeException> { TestActiveOrdinarySealV1.begin(f.captured, f.registration, f.first.assembly) }
        assertTrue(image == f.image()); assertEquals(calls, f.probe.calls.size); assertEquals(providers, f.native.order)
    }
}
