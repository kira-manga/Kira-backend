package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

internal enum class ActiveSealRecoveryCompletionCutV1 { BEFORE_COMMIT, AFTER_COMMIT, DEFERRED_COMMIT_UNKNOWN }

/** Raw negative faults only, never a substituted successful SDK/SQL row/result or cleanup receipt. */
internal object TestActiveSealRecoveryFailureCasesV1 {
    fun provider(tls: VersionBoundPersistenceConnectedFixture, cut: ActiveSealProviderCut) =
        withActiveSealRecoveryHistory(tls, ActiveSealRecoveryHistoryCutV1.WIRE_FROZEN) { h -> TestActiveSealRecoveryFixtureV1(h).use { f ->
            f.awaitOldLease()
            val paid = h.first.paidImage(); val order = h.native.order.size
            h.native.changeSts = { stage, reply -> if (stage == 3 && cut === ActiveSealProviderCut.WRONG_ROLE) {
                val changed = reply.bytes.decodeToString().replace("AROA" + "B".repeat(17), "AROA" + "C".repeat(17)).toByteArray()
                assertEquals(reply.bytes.size, changed.size); changed.copyInto(reply.bytes)
            } }
            h.native.beforeS3 = { request -> if (request.kind == "GET" && cut === ActiveSealProviderCut.WIRE) {
                val stored = checkNotNull(h.native.stored)
                h.native.stored = stored.copy(bytes = stored.bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            } }
            h.native.changeS3 = { request, reply -> if (request.kind == "GET") {
                val change = when (cut) {
                    ActiveSealProviderCut.VERSION -> "x-amz-version-id" to "foreign-version"
                    ActiveSealProviderCut.METADATA -> "x-amz-meta-kira-journal-event-id" to "A".repeat(43)
                    ActiveSealProviderCut.LOCK_MODE -> "x-amz-object-lock-mode" to "GOVERNANCE"
                    ActiveSealProviderCut.RETENTION -> "x-amz-object-lock-retain-until-date" to Instant.now().minusSeconds(1).toString()
                    else -> null
                }
                change?.let { (name, value) -> reply.headers = reply.headers.filterKeys { !it.equals(name, true) } + (name to listOf(value)) }
            } }
            val original = f.begin()
            assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
            f.assertReleased()
            assertEquals(0, h.native.order.drop(order).count { it == "GENERATE" || it == "DECRYPT" }, "Existing wire never generates; cheap metadata/wire refusal precedes AEAD.")
            assertTrue(paid == h.first.paidImage()); assertEquals("SEAL_PREPARED", h.control()["seal_state"]); assertNull(h.control()["seal_verification_bytes"])
            assertFalse(f.probe.calls.any { it.step === TestActiveOrdinarySealRecoveryStepV1.VERIFY })
            val image = TestActiveSealRecoveryObservationV1.image(h.observer); val providers = h.native.order.toList(); val calls = f.probe.calls.size
            assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
            assertTrue(image == TestActiveSealRecoveryObservationV1.image(h.observer)); assertEquals(providers, h.native.order); assertEquals(calls, f.probe.calls.size)
        } }

    fun completion(tls: VersionBoundPersistenceConnectedFixture, step: TestActiveOrdinarySealRecoveryStepV1, cut: ActiveSealRecoveryCompletionCutV1) =
        withActiveSealRecoveryHistory(tls, ActiveSealRecoveryHistoryCutV1.CANONICAL) { h -> TestActiveSealRecoveryFixtureV1(h).use { f ->
            require(step in setOf(TestActiveOrdinarySealRecoveryStepV1.FREEZE, TestActiveOrdinarySealRecoveryStepV1.VERIFY))
            f.awaitOldLease()
            var selected: PersistencePhaseContext? = null
            var before: Map<String, List<String>>? = null
            f.probe.before = { call -> if (call.step === step && before == null) before = TestActiveSealRecoveryObservationV1.image(h.observer) }
            f.probe.after = { call ->
                val sql = if (step === TestActiveOrdinarySealRecoveryStepV1.FREEZE) TestActiveOrdinarySealRecoverySqlV1.freeze else TestActiveOrdinarySealRecoverySqlV1.verifyControl
                if (selected == null && call.step === step && call.sql == sql) {
                    selected = call.phase
                    if (cut === ActiveSealRecoveryCompletionCutV1.DEFERRED_COMMIT_UNKNOWN) {
                        val jdbc = JdbcTemplate(h.runtime.pools.catalogCoordinator.dataSource)
                        jdbc.execute("CREATE TEMP TABLE kira_empty_seal_recovery_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, jdbc.update("INSERT INTO kira_empty_seal_recovery_commit_cut VALUES (1), (1)"))
                    } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) { if (cut === ActiveSealRecoveryCompletionCutV1.BEFORE_COMMIT) error("Synthetic recovery pre-COMMIT fault.") }
                        override fun afterCommit() { if (cut === ActiveSealRecoveryCompletionCutV1.AFTER_COMMIT) error("Synthetic recovery lost COMMIT acknowledgment.") }
                    })
                }
            }
            val original = f.begin()
            try { assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) } }
            finally { f.probe.before = {}; f.probe.after = {} }
            assertNotNull(selected); f.assertReleased()
            val outcome = when (cut) {
                ActiveSealRecoveryCompletionCutV1.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
                ActiveSealRecoveryCompletionCutV1.AFTER_COMMIT -> PersistenceDatabaseOutcome.COMMITTED
                ActiveSealRecoveryCompletionCutV1.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
            }
            assertEquals(outcome, checkNotNull(selected).databaseOutcome())
            // Deferred constraint is actual native COMMIT failure/rollback, not durable TLS UNKNOWN acceptance.
            if (outcome !== PersistenceDatabaseOutcome.COMMITTED) assertTrue(before == TestActiveSealRecoveryObservationV1.image(h.observer))
            if (step === TestActiveOrdinarySealRecoveryStepV1.FREEZE) assertTrue(h.native.requests.isEmpty())
            assertEquals(if (step === TestActiveOrdinarySealRecoveryStepV1.VERIFY && outcome === PersistenceDatabaseOutcome.COMMITTED) "SEAL_VERIFIED" else "SEAL_PREPARED", h.control()["seal_state"])
            val image = TestActiveSealRecoveryObservationV1.image(h.observer); val providers = h.native.order.toList(); val calls = f.probe.calls.size
            assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
            assertTrue(image == TestActiveSealRecoveryObservationV1.image(h.observer)); assertEquals(providers, h.native.order); assertEquals(calls, f.probe.calls.size)
        } }

    fun lifetime(tls: VersionBoundPersistenceConnectedFixture, cut: ActiveSealLifetimeCut) =
        withActiveSealRecoveryHistory(tls, ActiveSealRecoveryHistoryCutV1.WIRE_FROZEN) { h -> TestActiveSealRecoveryFixtureV1(h).use { f ->
            f.awaitOldLease()
            val paid = h.first.paidImage(); var observed = false
            h.native.beforeS3 = { request -> if (request.kind == "GET") {
                when (cut) {
                    ActiveSealLifetimeCut.CANCELLATION -> { observed = true; throw CancellationException("Synthetic recovery cancellation.") }
                    ActiveSealLifetimeCut.LEASE_EXPIRED -> {
                        assertEquals(1, h.observer.update("UPDATE complaint_journal_control SET lease_expires_at = clock_timestamp() - interval '1 millisecond' WHERE data_scope_id = ?", h.scope)); observed = true
                    }
                    ActiveSealLifetimeCut.LEASE_REPLACED -> {
                        assertEquals(1, h.observer.update("UPDATE complaint_journal_control SET lease_owner = ?, lease_token = lease_token + 1 WHERE data_scope_id = ?", UUID.randomUUID(), h.scope)); observed = true
                    }
                    else -> Unit
                }
            } }
            h.native.onNativeClose = {
                assertEquals(1L, h.process.publicationLanes.activeOwners().totalOwners)
                if (cut === ActiveSealLifetimeCut.LATE_NATIVE_CLOSE && !observed) { observed = true; h.native.offsetNanos += 11_000_000_000L }
            }
            val original = f.begin()
            if (cut === ActiveSealLifetimeCut.CANCELLATION) {
                assertThrows<CancellationException> { f.recover(original) }; assertThrows<CancellationException> { f.recover(original) }
            } else {
                assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }; assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
            }
            h.native.onNativeClose = {}
            assertTrue(observed); f.assertReleased(); assertTrue(paid == h.first.paidImage())
            assertEquals("SEAL_PREPARED", h.control()["seal_state"]); assertNull(h.control()["seal_verification_bytes"])
            assertFalse(f.probe.calls.any { it.step === TestActiveOrdinarySealRecoveryStepV1.VERIFY })
        } }

    fun failedNativeCloseCannotReleaseOrVerify(tls: VersionBoundPersistenceConnectedFixture) {
        var asserted = false
        assertThrows<RuntimeException> { withActiveSealRecoveryHistory(tls, ActiveSealRecoveryHistoryCutV1.WIRE_FROZEN) { h ->
            TestActiveSealRecoveryFixtureV1(h).use { f ->
                f.awaitOldLease(); var failed = false
                h.native.onNativeClose = {
                    assertEquals(1L, h.process.publicationLanes.activeOwners().totalOwners)
                    if (!failed) { failed = true; error("Synthetic native close refusal; release is unproven.") }
                }
                val original = f.begin()
                assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
                assertTrue(failed); f.probe.assertPhysicallyReleased()
                assertEquals(1L, h.process.publicationLanes.activeOwners().totalOwners)
                assertFalse(f.probe.calls.any { it.step === TestActiveOrdinarySealRecoveryStepV1.VERIFY })
                h.native.assertDisposed(requireReturnedClose = false)
                assertThrows<RuntimeException> { checkNotNull(h.process.ordinarySeal).close() }
                assertEquals(1L, h.process.publicationLanes.activeOwners().totalOwners)
                assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { f.recover(original) }
                asserted = true
            }
        } }
        assertTrue(asserted, "Expected failure is deliberately unresolved actual close, not unrelated fixture failure.")
    }
}
