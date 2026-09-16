package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.UUID

class InstallationRecoveryReducerTest {
    @TestFactory
    fun `ordinary evidence preserves every identity or creates only a recovery reservation`(): List<DynamicTest> = startingStates.flatMap { start ->
        OrdinaryInstallationDeletion.entries.map { cause ->
            DynamicTest.dynamicTest("$start $cause") {
                val state = snapshot(start)
                val result = InstallationRecoveryReducer.reduce(state, InstallationRecoveryEvidence.OrdinaryDeletion(live, cause))
                assertEquals(
                    InstallationRecoveryDecision.Apply(
                        start ?: InstallationIdentityState.RECOVERY_RESERVED,
                        RecoveryCredentialEffect.PRESERVE,
                        RecoveryContentEffect.ERASE_AUTHORIZED_RESOURCES,
                        reserveIdentityCapacity = start == null,
                    ),
                    result,
                )
                assertEquals(snapshot(start), state)
            }
        }
    }

    @TestFactory
    fun `terminal evidence uses exact immutable targets for every starting state`(): List<DynamicTest> = startingStates.flatMap { start ->
        InstallationTerminalState.entries.map { target ->
            DynamicTest.dynamicTest("$start to $target") {
                val state = snapshot(start)
                val evidence = terminalEvidence(live, target)
                if (isConflicting(start, target)) {
                    assertRule(ComplaintRuleCode.CONFLICTING_TERMINAL_EVIDENCE) { InstallationRecoveryReducer.reduce(state, evidence) }
                } else {
                    val credentialEffect = when (target) {
                        InstallationTerminalState.RETIRED -> RecoveryCredentialEffect.REMOVE

                        InstallationTerminalState.DELETED -> when (start) {
                            InstallationIdentityState.ACTIVE, InstallationIdentityState.DELETION_PENDING -> RecoveryCredentialEffect.COMPLETE_DELETE_ALL
                            else -> RecoveryCredentialEffect.PRESERVE
                        }
                    }
                    assertEquals(
                        InstallationRecoveryDecision.Apply(
                            target.identityState,
                            credentialEffect,
                            if (target == InstallationTerminalState.DELETED) RecoveryContentEffect.ERASE_ALL_OWNED_CONTENT else RecoveryContentEffect.NONE,
                            reserveIdentityCapacity = start == null,
                        ),
                        InstallationRecoveryReducer.reduce(state, evidence),
                    )
                }
                assertEquals(snapshot(start), state)
            }
        }
    }

    @TestFactory
    fun `accepted test manifests project exact targets including active to deleted`(): List<DynamicTest> = startingStates.flatMap { start ->
        InstallationTerminalState.entries.map { target ->
            DynamicTest.dynamicTest("test $start to $target") {
                val state = snapshot(start, test).copy(testErasureComplete = true)
                val evidence = InstallationRecoveryEvidence.TestManifest(test, target)
                if (isConflicting(start, target)) {
                    assertRule(ComplaintRuleCode.CONFLICTING_TERMINAL_EVIDENCE) { InstallationRecoveryReducer.reduce(state, evidence) }
                } else {
                    assertEquals(
                        InstallationRecoveryDecision.Apply(
                            target.identityState,
                            RecoveryCredentialEffect.REMOVE,
                            RecoveryContentEffect.NONE,
                            reserveIdentityCapacity = start == null,
                        ),
                        InstallationRecoveryReducer.reduce(state, evidence),
                    )
                }
            }
        }
    }

    @TestFactory
    fun `all reservation credential combinations are checked before any effect`(): List<DynamicTest> = startingStates.flatMap { start ->
        (listOf(null) + InstallationCredentialState.entries).map { credentialState ->
            DynamicTest.dynamicTest("reservation=$start credential=$credentialState") {
                val state = snapshot(start).copy(credential = credentialState?.let { InstallationCredentialSnapshot(live, it) })
                val valid = when (start) {
                    null, InstallationIdentityState.RECOVERY_RESERVED, InstallationIdentityState.RETIRED -> credentialState == null
                    InstallationIdentityState.ACTIVE -> credentialState == InstallationCredentialState.ACTIVE
                    InstallationIdentityState.DELETION_PENDING -> credentialState == InstallationCredentialState.DELETION_PENDING
                    InstallationIdentityState.DELETED -> credentialState == null || credentialState == InstallationCredentialState.DELETED
                }
                if (valid) {
                    assertTrue(InstallationRecoveryReducer.reduce(state, ordinary(live)) is InstallationRecoveryDecision.Apply)
                } else {
                    assertRule(ComplaintRuleCode.INVALID_INSTALLATION_PAIR) { InstallationRecoveryReducer.reduce(state, ordinary(live)) }
                }
            }
        }
    }

    @Test
    fun `owner content cannot exist without an active or pending credential pair`() {
        for (start in listOf(null, InstallationIdentityState.RECOVERY_RESERVED, InstallationIdentityState.RETIRED, InstallationIdentityState.DELETED)) {
            val invalid = snapshot(start).copy(hasOwnedContent = true)
            assertRule(ComplaintRuleCode.INVALID_INSTALLATION_PAIR) { InstallationRecoveryReducer.reduce(invalid, ordinary(live)) }
        }
    }

    @Test
    fun `identity and scope mismatches are rejected on evidence reservation and credential`() {
        val state = snapshot(InstallationIdentityState.ACTIVE)
        val otherId = ScopedInstallationId(UUID.fromString("33333333-3333-4333-8333-333333333333"), live.scope)
        for ((wrong, code) in listOf(
            otherId to ComplaintRuleCode.INSTALLATION_IDENTITY_MISMATCH,
            test to ComplaintRuleCode.INSTALLATION_SCOPE_MISMATCH,
        )) {
            assertRule(code) { InstallationRecoveryReducer.reduce(state, ordinary(wrong)) }
            assertRule(code) {
                InstallationRecoveryReducer.reduce(
                    state.copy(reservation = InstallationReservationSnapshot(wrong, InstallationIdentityState.ACTIVE)),
                    ordinary(live),
                )
            }
            assertRule(code) {
                InstallationRecoveryReducer.reduce(
                    state.copy(credential = InstallationCredentialSnapshot(wrong, InstallationCredentialState.ACTIVE)),
                    ordinary(live),
                )
            }
        }
    }

    @Test
    fun `retirement defers content and receipt dependencies without granting erasure authority`() {
        for (start in listOf(InstallationIdentityState.ACTIVE, InstallationIdentityState.DELETION_PENDING)) {
            val withBoth = snapshot(start).copy(hasOwnedContent = true, hasBlockingReceipts = true)
            val expected = InstallationRecoveryDecision.Deferred(RecoveryDependency.RETIREMENT_CONTENT_OR_RECEIPTS)
            assertEquals(expected, InstallationRecoveryReducer.reduce(withBoth, InstallationRecoveryEvidence.Retirement(live)))
            // A separate authenticated ordinary erasure may run while retirement remains VERIFIED.
            val erasure = InstallationRecoveryReducer.reduce(withBoth, ordinary(live)) as InstallationRecoveryDecision.Apply
            assertEquals(RecoveryContentEffect.ERASE_AUTHORIZED_RESOURCES, erasure.contentEffect)
            assertEquals(RecoveryCredentialEffect.PRESERVE, erasure.credentialEffect)
            val afterContentErasure = withBoth.copy(hasOwnedContent = false)
            assertEquals(expected, InstallationRecoveryReducer.reduce(afterContentErasure, InstallationRecoveryEvidence.Retirement(live)))
            val afterReceiptExpiry = afterContentErasure.copy(hasBlockingReceipts = false)
            assertEquals(
                InstallationRecoveryDecision.Apply(InstallationIdentityState.RETIRED, RecoveryCredentialEffect.REMOVE, RecoveryContentEffect.NONE, false),
                InstallationRecoveryReducer.reduce(afterReceiptExpiry, InstallationRecoveryEvidence.Retirement(live)),
            )
            assertTrue(withBoth.hasOwnedContent)
            assertTrue(withBoth.hasBlockingReceipts)
        }
    }

    @Test
    fun `an absent reservation still waits for a restored live receipt dependency before retirement`() {
        val state = snapshot(null).copy(hasBlockingReceipts = true)
        assertEquals(
            InstallationRecoveryDecision.Deferred(RecoveryDependency.RETIREMENT_CONTENT_OR_RECEIPTS),
            InstallationRecoveryReducer.reduce(state, InstallationRecoveryEvidence.Retirement(live)),
        )
    }

    @Test
    fun `test manifest cannot target live scope or project before mandated erasure`() {
        val liveState = snapshot(InstallationIdentityState.ACTIVE).copy(testErasureComplete = true)
        assertRule(ComplaintRuleCode.INVALID_TEST_MANIFEST) {
            InstallationRecoveryReducer.reduce(liveState, InstallationRecoveryEvidence.TestManifest(live, InstallationTerminalState.DELETED))
        }
        val testState = snapshot(InstallationIdentityState.ACTIVE, test)
        for (incomplete in listOf(
            testState,
            testState.copy(testErasureComplete = true, hasOwnedContent = true),
            testState.copy(testErasureComplete = true, hasBlockingReceipts = true),
        )) {
            assertEquals(
                InstallationRecoveryDecision.Deferred(RecoveryDependency.TEST_ERASURE),
                InstallationRecoveryReducer.reduce(incomplete, InstallationRecoveryEvidence.TestManifest(test, InstallationTerminalState.DELETED)),
            )
        }
    }

    @Test
    fun `purged test history verifies without recreating mutable or applied rows`() {
        for (terminal in InstallationTerminalState.entries) {
            val state = snapshot(terminal.identityState, test).copy(
                credential = null,
                testErasureComplete = true,
                projectionMode = InstallationProjectionMode.PURGED_TEST_HISTORY,
            )
            for (evidence in listOf(ordinary(test), terminalEvidence(test, terminal), InstallationRecoveryEvidence.TestManifest(test, terminal))) {
                assertEquals(InstallationRecoveryDecision.VerifyOnly, InstallationRecoveryReducer.reduce(state, evidence))
            }
            val other = if (terminal == InstallationTerminalState.RETIRED) InstallationTerminalState.DELETED else InstallationTerminalState.RETIRED
            assertRule(ComplaintRuleCode.CONFLICTING_TERMINAL_EVIDENCE) {
                InstallationRecoveryReducer.reduce(state, InstallationRecoveryEvidence.TestManifest(test, other))
            }
        }
    }

    @Test
    fun `purged mode cannot hide an invalid or incomplete projection`() {
        val valid = snapshot(InstallationIdentityState.RETIRED, test).copy(
            testErasureComplete = true,
            projectionMode = InstallationProjectionMode.PURGED_TEST_HISTORY,
        )
        for (invalid in listOf(
            valid.copy(testErasureComplete = false),
            valid.copy(hasBlockingReceipts = true),
            valid.copy(reservation = null),
            valid.copy(reservation = InstallationReservationSnapshot(test, InstallationIdentityState.RECOVERY_RESERVED)),
            valid.copy(
                reservation = InstallationReservationSnapshot(test, InstallationIdentityState.DELETED),
                credential = InstallationCredentialSnapshot(test, InstallationCredentialState.DELETED),
            ),
            snapshot(InstallationIdentityState.RETIRED).copy(testErasureComplete = true, projectionMode = InstallationProjectionMode.PURGED_TEST_HISTORY),
        )) {
            assertRule(ComplaintRuleCode.INVALID_PURGED_TEST_STATE) { InstallationRecoveryReducer.reduce(invalid, ordinary(invalid.installation)) }
        }
    }

    @Test
    fun `no-credential deleted reconstruction never requests a replay credential transition`() {
        val reconstructed = snapshot(InstallationIdentityState.DELETED).copy(credential = null)
        val result = InstallationRecoveryReducer.reduce(reconstructed, InstallationRecoveryEvidence.DeleteAll(live)) as InstallationRecoveryDecision.Apply
        assertEquals(RecoveryCredentialEffect.PRESERVE, result.credentialEffect)
        assertFalse(result.reserveIdentityCapacity)
        val retained = snapshot(InstallationIdentityState.DELETED)
        assertEquals(result, InstallationRecoveryReducer.reduce(retained, InstallationRecoveryEvidence.DeleteAll(live)))
        // PRESERVE means neither extending a retained verifier window nor recreating an expired one.
        assertNull(reconstructed.credential)
    }

    @Test
    fun `placeholder resolution does not depend on an applied event ledger retained through compaction`() {
        val absent = snapshot(null)
        val reserved = InstallationRecoveryReducer.reduce(absent, ordinary(live)) as InstallationRecoveryDecision.Apply
        assertEquals(InstallationIdentityState.RECOVERY_RESERVED, reserved.identityState)
        // Restore only the persisted reservation; no in-memory event provenance is supplied.
        val afterCompaction = snapshot(reserved.identityState)
        val terminal = InstallationRecoveryReducer.reduce(afterCompaction, InstallationRecoveryEvidence.DeleteAll(live)) as InstallationRecoveryDecision.Apply
        assertEquals(InstallationIdentityState.DELETED, terminal.identityState)
        assertEquals(RecoveryCredentialEffect.PRESERVE, terminal.credentialEffect)
        assertFalse(terminal.reserveIdentityCapacity)
    }

    private fun snapshot(start: InstallationIdentityState?, installation: ScopedInstallationId = live): InstallationRecoverySnapshot {
        val credentialState = when (start) {
            InstallationIdentityState.ACTIVE -> InstallationCredentialState.ACTIVE
            InstallationIdentityState.DELETION_PENDING -> InstallationCredentialState.DELETION_PENDING
            InstallationIdentityState.DELETED -> InstallationCredentialState.DELETED
            else -> null
        }
        return InstallationRecoverySnapshot(
            installation,
            start?.let { InstallationReservationSnapshot(installation, it) },
            credentialState?.let { InstallationCredentialSnapshot(installation, it) },
        )
    }

    private fun ordinary(installation: ScopedInstallationId): InstallationRecoveryEvidence =
        InstallationRecoveryEvidence.OrdinaryDeletion(installation, OrdinaryInstallationDeletion.OWNER_REPORT)

    private fun terminalEvidence(installation: ScopedInstallationId, target: InstallationTerminalState): InstallationRecoveryEvidence = when (target) {
        InstallationTerminalState.RETIRED -> InstallationRecoveryEvidence.Retirement(installation)
        InstallationTerminalState.DELETED -> InstallationRecoveryEvidence.DeleteAll(installation)
    }

    private fun isConflicting(start: InstallationIdentityState?, target: InstallationTerminalState): Boolean =
        (start == InstallationIdentityState.RETIRED && target == InstallationTerminalState.DELETED) ||
            (start == InstallationIdentityState.DELETED && target == InstallationTerminalState.RETIRED)

    private fun assertRule(code: ComplaintRuleCode, action: () -> Unit) {
        assertEquals(code, assertThrows(ComplaintRuleException::class.java) { action() }.code)
    }

    private val startingStates = listOf(null) + InstallationIdentityState.entries
    private val id = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val live = ScopedInstallationId(id, ComplaintDataScope.LIVE)
    private val test = ScopedInstallationId(id, ComplaintDataScope.of(UUID.fromString("22222222-2222-4222-8222-222222222222")))
}
