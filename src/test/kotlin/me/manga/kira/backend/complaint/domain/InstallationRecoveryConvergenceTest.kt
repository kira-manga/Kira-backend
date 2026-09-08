package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Exercises the actual pure reducer with an immutable, synthetic effect interpreter. It does not
 * certify a database transaction, credential expiry, journal verification or a production scanner.
 */
class InstallationRecoveryConvergenceTest {
    @Test
    fun `ordinary then delete-all converges in every order with duplicate and repeated passes`() {
        val ordinary = ordinary()
        val deleteAll = InstallationRecoveryEvidence.DeleteAll(installation)
        val orders = listOf(
            listOf(ordinary, deleteAll, ordinary),
            listOf(deleteAll, ordinary, ordinary),
            listOf(ordinary, ordinary, deleteAll),
        )
        for (start in listOf(
            null,
            InstallationIdentityState.ACTIVE,
            InstallationIdentityState.DELETION_PENDING,
            InstallationIdentityState.RECOVERY_RESERVED,
            InstallationIdentityState.DELETED,
        )) {
            val initial = initial(start)
            val outcomes = orders.map { order ->
                val outcome = order.fold(initial) { state, event -> apply(state, event) }
                assertEquals(InstallationIdentityState.DELETED, outcome.snapshot.reservation?.state)
                assertTrue(outcome.resources.isEmpty())
                assertEquals(1, outcome.identityCharges)
                assertEquals(outcome, order.fold(outcome) { state, event -> apply(state, event) })
                outcome
            }
            assertEquals(1, outcomes.toSet().size)
            if (start == null || start == InstallationIdentityState.RECOVERY_RESERVED) assertNull(outcomes.first().snapshot.credential)
        }
    }

    @Test
    fun `ordinary and genuine retirement converge after dependency discharge in either order`() {
        val retirement = InstallationRecoveryEvidence.Retirement(installation)
        for (start in listOf(null, InstallationIdentityState.ACTIVE, InstallationIdentityState.DELETION_PENDING, InstallationIdentityState.RECOVERY_RESERVED)) {
            val initial = initial(start).let { it.copy(resources = if (it.resources.isEmpty()) emptySet() else setOf("report-a")) }
            for (order in listOf(listOf(ordinary(), retirement), listOf(retirement, ordinary()))) {
                val outcome = drain(initial, order)
                assertEquals(InstallationIdentityState.RETIRED, outcome.snapshot.reservation?.state)
                assertNull(outcome.snapshot.credential)
                assertTrue(outcome.resources.isEmpty())
                assertEquals(1, outcome.identityCharges)
                assertEquals(outcome, drain(outcome, order))
            }
        }
    }

    @Test
    fun `incomplete retirement history defers rather than completing or removing unrelated content`() {
        val initial = initial(InstallationIdentityState.ACTIVE)
        val retirement = InstallationRecoveryEvidence.Retirement(installation)
        val result = InstallationRecoveryReducer.reduce(initial.lockedSnapshot(), retirement)
        assertTrue(result is InstallationRecoveryDecision.Deferred)
        assertThrows(UnresolvedDependencies::class.java) { drain(initial, listOf(retirement, ordinary())) }
        assertEquals(setOf("report-a", "not-in-event-snapshot"), initial.resources)
        assertEquals(InstallationIdentityState.ACTIVE, initial.snapshot.reservation?.state)
    }

    @Test
    fun `conflicting genuine retirement and deletion fails in both orders`() {
        val retirement = InstallationRecoveryEvidence.Retirement(installation)
        val deletion = InstallationRecoveryEvidence.DeleteAll(installation)
        for (events in listOf(listOf(retirement, deletion), listOf(deletion, retirement))) {
            val failure = assertThrows(ComplaintRuleException::class.java) { events.fold(initial(null)) { state, event -> apply(state, event) } }
            assertEquals(ComplaintRuleCode.CONFLICTING_TERMINAL_EVIDENCE, failure.code)
        }
    }

    @Test
    fun `discarding a planned effect before commit cannot mutate input or consume capacity`() {
        val before = initial(null)
        val planned = InstallationRecoveryReducer.reduce(before.lockedSnapshot(), ordinary())
        assertTrue(planned is InstallationRecoveryDecision.Apply)
        assertEquals(0, before.identityCharges)
        assertNull(before.snapshot.reservation)
        assertNull(before.snapshot.credential)
        val after = apply(before, ordinary())
        assertEquals(1, after.identityCharges)
        // Reconstruct only persisted fields after a simulated restart, not a hidden in-memory flag.
        val restored = after.copy(snapshot = after.snapshot.copy())
        assertEquals(after, apply(restored, ordinary()))
        assertFalse((InstallationRecoveryReducer.reduce(restored.lockedSnapshot(), ordinary()) as InstallationRecoveryDecision.Apply).reserveIdentityCapacity)
    }

    private data class Scenario(val snapshot: InstallationRecoverySnapshot, val resources: Set<String>, val identityCharges: Int) {
        fun lockedSnapshot(): InstallationRecoverySnapshot = snapshot.copy(hasOwnedContent = resources.isNotEmpty())
    }

    private fun initial(start: InstallationIdentityState?): Scenario {
        val credentialState = when (start) {
            InstallationIdentityState.ACTIVE -> InstallationCredentialState.ACTIVE
            InstallationIdentityState.DELETION_PENDING -> InstallationCredentialState.DELETION_PENDING
            InstallationIdentityState.DELETED -> InstallationCredentialState.DELETED
            else -> null
        }
        return Scenario(
            InstallationRecoverySnapshot(
                installation,
                start?.let { InstallationReservationSnapshot(installation, it) },
                credentialState?.let { InstallationCredentialSnapshot(installation, it) },
            ),
            if (credentialState in setOf(InstallationCredentialState.ACTIVE, InstallationCredentialState.DELETION_PENDING)) {
                setOf("report-a", "not-in-event-snapshot")
            } else {
                emptySet()
            },
            if (start == null) 0 else 1,
        )
    }

    private fun apply(before: Scenario, event: InstallationRecoveryEvidence): Scenario {
        val decision = InstallationRecoveryReducer.reduce(before.lockedSnapshot(), event)
        if (decision is InstallationRecoveryDecision.Deferred) throw UnresolvedDependencies()
        if (decision == InstallationRecoveryDecision.VerifyOnly) return before
        decision as InstallationRecoveryDecision.Apply
        val credential = when (decision.credentialEffect) {
            RecoveryCredentialEffect.PRESERVE -> before.snapshot.credential

            RecoveryCredentialEffect.REMOVE -> null

            RecoveryCredentialEffect.COMPLETE_DELETE_ALL -> {
                checkNotNull(before.snapshot.credential).copy(state = InstallationCredentialState.DELETED)
            }
        }
        val resources = when (decision.contentEffect) {
            RecoveryContentEffect.NONE -> before.resources
            RecoveryContentEffect.ERASE_AUTHORIZED_RESOURCES -> before.resources - "report-a"
            RecoveryContentEffect.ERASE_ALL_OWNED_CONTENT -> emptySet()
        }
        return Scenario(
            before.snapshot.copy(
                reservation = InstallationReservationSnapshot(installation, decision.identityState),
                credential = credential,
                hasOwnedContent = resources.isNotEmpty(),
            ),
            resources,
            before.identityCharges + if (decision.reserveIdentityCapacity) 1 else 0,
        )
    }

    /** Test driver only; real bounded scanner/lease/transaction progress is a separate integration gate. */
    private fun drain(initial: Scenario, events: List<InstallationRecoveryEvidence>): Scenario {
        var state = initial
        var remaining = events
        while (remaining.isNotEmpty()) {
            val deferred = mutableListOf<InstallationRecoveryEvidence>()
            var progressed = false
            for (event in remaining) {
                val decision = InstallationRecoveryReducer.reduce(state.lockedSnapshot(), event)
                if (decision is InstallationRecoveryDecision.Deferred) {
                    deferred += event
                } else {
                    val next = apply(state, event)
                    progressed = progressed || next != state
                    state = next
                }
            }
            if (deferred.isNotEmpty() && !progressed) throw UnresolvedDependencies()
            remaining = deferred
        }
        return state
    }

    private class UnresolvedDependencies : RuntimeException()

    private fun ordinary(): InstallationRecoveryEvidence =
        InstallationRecoveryEvidence.OrdinaryDeletion(installation, OrdinaryInstallationDeletion.OWNER_REPORT)

    private val installation = ScopedInstallationId(UUID.fromString("11111111-1111-4111-8111-111111111111"), ComplaintDataScope.LIVE)
}
