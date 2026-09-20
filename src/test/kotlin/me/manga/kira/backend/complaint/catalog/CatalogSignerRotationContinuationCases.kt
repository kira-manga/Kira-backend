package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.util.UUID

/** Known-unattempted second Sign cases on the same genuine D7 process, campaign and immutable first-signature custody. */
internal class CatalogSignerRotationContinuationCases(private val f: CatalogSignerRotationFreezeFixture) {
    private val core = CatalogSignerRotationFreezeCases(f)

    /** Real first Sign/CAS/custody and third READ rollback, not seeded SQL, fabricated signatures or a replacement campaign. */
    internal fun signatureOnePersistedPrefix(partial: CatalogSignerRotationFreezeInvocation = f.invocation()): CatalogSignerRotationFreezeInvocation {
        var readPhases = 0
        var refusedBeforeSecondArm = false
        f.jdbc.beforeSql = { step ->
            if (step == "control" && f.jdbc.calls.last().path === PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ && ++readPhases == 3) {
                refusedBeforeSecondArm = true
                error("Synthetic second Sign recheck refusal before its effect arm.")
            }
        }
        try {
            core.refused { partial.execute() }
        } finally {
            f.jdbc.beforeSql = {}
        }
        assertTrue(refusedBeforeSecondArm)
        partial.assertReleased()
        partial.readback.assertCompletedReadbacks(3)
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, partial.phases.last().databaseOutcome())
        assertEquals(1, partial.signing.requests.size)
        assertArrayEquals(partial.producedSignatures.single(), f.row()["signer_one_signature"] as ByteArray)
        assertNull(f.row()["signer_two_signature"])
        assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_ARMED))
        return partial
    }

    fun continueKnownSecondSign() {
        val control = f.d7.desired.control()
        val genesis = core.genesisJson()
        val counters = core.counterBalances()
        val previous = signatureOnePersistedPrefix()
        val neverEntered = f.invocation()
        neverEntered.operator.close()
        neverEntered.assertCleanedWithoutReservation()
        assertRefusedContinuation(neverEntered, code = CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, reserved = false)
        val leaves = f.snapshotLeaves()
        val callStart = f.jdbc.calls.size
        val continued = f.invocation()
        assertNotSame(previous.operator.budget, continued.operator.budget)
        assertSame(previous.attempt.process, continued.attempt.process)
        assertSame(previous.attempt.campaign, continued.attempt.campaign)
        assertSame(continued.operator.budget, continued.attempt.budget)
        continued.beforeSign = { slot ->
            assertEquals(1, slot)
            assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_ARMED))
            assertArrayEquals(previous.producedSignatures.single(), f.row()["signer_one_signature"] as ByteArray)
            assertNull(f.row()["signer_two_signature"])
            assertNull(f.row()["envelope_bytes"])
            val assembly: Any = poolTestField(continued.operator, "assembly")
            val signers: Array<*> = poolTestField(assembly, "signers")
            assertNull(signers[0])
            val cap: PersistenceTimeBudget = poolTestField(checkNotNull(signers[1]), "budget")
            assertSame(continued.operator.budget, poolTestField<PersistenceTimeBudget>(cap, "parent"))
            assertEquals(10_000_000_000L, poolTestField<Long>(cap, "allowanceNanos"))
            continued.readback.assertCompletedReadbacks(1)
        }
        f.jdbc.beforeSql = { step ->
            if (step == "signature") {
                assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_ARMED))
                assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.ENVELOPE))
                assertArrayEquals(continued.producedSignatures.single(), f.read(CatalogSignerRotationReleaseLeafV1.SIGNATURE_TWO))
            }
        }
        val result = try {
            continued.continueSecondSign(previous)
        } finally {
            f.jdbc.beforeSql = {}
        }
        continued.assertReleased()
        continued.readback.assertCompletedReadbacks(1)
        assertEquals(1, continued.signing.requests.size)
        assertEquals(1, continued.signing.createdClients)
        assertContinuationPhases(continued)
        val newSteps = f.jdbc.steps.drop(callStart)
        assertEquals(1, newSteps.count { it == "signature" })
        assertTrue(newSteps.none { it == "prepare" || it.startsWith("charge:") })
        val envelope = core.assertSignedSql(listOf(previous.producedSignatures.single(), continued.producedSignatures.single()))
        assertEquals(CatalogSignerRotationFreezeStateV1.SIGNED_PREPARED, result.state)
        assertEquals(2L, result.generation)
        assertEquals(f.manifest.operationToken, result.operationToken)
        assertEquals(Sha256.hex(envelope), result.envelopeSha256)
        f.assertLeavesUnchanged(leaves, allowAdditionalLeaves = true)
        CatalogSignerRotationReleaseLeafV1.entries.take(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME.ordinal + 1)
            .forEach { assertTrue(f.complete(it), it.name) }
        CatalogSignerRotationReleaseLeafV1.entries.drop(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME.ordinal + 1)
            .forEach { assertFalse(f.exists(it), it.name) }
        core.assertCharge(counters)
        core.assertHeadUnchanged(control, genesis)
        assertRefusedContinuation(continued) // A completed second Sign cannot be retried by another explicit continuation.
        core.assertReadOnlyResume()
        assertEquals(2, f.invocations.sumOf { it.signing.requests.size })
    }

    /** Lower guard, not a wrong-purpose delivery root: the genuine original process/campaign and exact input prefix remain usable. */
    fun deliveryHistoryCannotReenter(firstOnly: Boolean) {
        val previous = if (firstOnly) {
            signatureOnePersistedPrefix() // Positive actual known-clean/unattempted Sign2 eligibility, not an already-spent second Sign.
        } else {
            f.invocation().also {
                assertEquals(CatalogSignerRotationFreezeStateV1.SIGNED_PREPARED, it.execute().state)
                it.assertReleased()
                core.assertSignedSql(it.producedSignatures)
                core.assertReadOnlyResume() // Without downstream history this very same lower route really succeeds.
            }
        }
        val prefix = f.snapshotLeaves()
        createUnexpectedLeaf(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED, complete = true)
        // Synthetic negative presence only: these bytes are explicitly NOT a delivery effect, allocation or accepted result.
        for (complete in listOf(true, false)) {
            if (!complete) Files.delete(f.marker(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED))
            assertEquals(complete, f.complete(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED))
            val before = f.state()
            val leaves = f.snapshotLeaves()
            val calls = f.jdbc.calls.size
            if (firstOnly) {
                assertRefusedContinuation(previous)
                assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_ARMED))
            } else {
                val next = f.invocation()
                assertEquals(CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED, core.refused { next.execute(resume = true) }.code)
                next.assertReleased()
                assertEquals(0, next.signing.createdClients)
                assertEquals(0, next.readback.http.createdClients)
            }
            assertEquals(calls, f.jdbc.calls.size, "Downstream history refuses before SQL or either native Sign/readback route.")
            assertEquals(before, f.state())
            f.assertLeavesUnchanged(leaves)
            f.assertLeavesUnchanged(prefix, allowAdditionalLeaves = true)
            assertEquals(if (firstOnly) 1 else 2, f.invocations.sumOf { it.signing.requests.size })
        }
    }

    fun continuationRefusals(cut: CatalogSignerRotationContinuationCut) = when (cut) {
        CatalogSignerRotationContinuationCut.SECOND_ALREADY_ARMED -> attemptedSecondSignCannotContinue()

        CatalogSignerRotationContinuationCut.FIRST_SQL_PAIR_MISSING,
        CatalogSignerRotationContinuationCut.SECOND_ARM_PAIR_PARTIAL,
        CatalogSignerRotationContinuationCut.DOWNSTREAM_RECORD_PRESENT,
        -> uncertainContinuationCustody(cut)

        CatalogSignerRotationContinuationCut.CURRENT_BINDING -> changedContinuationAuthority(expiredLease = false)

        CatalogSignerRotationContinuationCut.EXPIRED_LEASE -> changedContinuationAuthority(expiredLease = true)

        CatalogSignerRotationContinuationCut.NEW_ALLOWANCE -> expiredContinuationAllowance()
    }

    private fun attemptedSecondSignCannotContinue() {
        val original = f.invocation()
        original.rejectSecondSignResponse = true // Returned raw error, not unknown native prepare custody or an invented Sign result.
        assertEquals(CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED, core.refused { original.execute() }.code)
        original.assertReleased()
        original.readback.assertCompletedReadbacks(3)
        assertEquals(2, original.signing.requests.size)
        assertEquals(2, original.signing.replies.size)
        val secondResponse = original.signing.replies[1]
        assertEquals(500, secondResponse.status)
        assertEquals(1, secondResponse.calls)
        assertEquals(0, secondResponse.reads)
        assertEquals(1, secondResponse.aborts)
        assertEquals(1, secondResponse.closes)
        assertEquals(1, original.producedSignatures.size)
        assertArrayEquals(original.producedSignatures.single(), f.row()["signer_one_signature"] as ByteArray)
        assertNull(f.row()["signer_two_signature"])
        assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED))
        assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_ARMED))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_RETURNED))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGNATURE_TWO))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_ARMED))
        val rejected = assertRefusedContinuation(original)
        val phase = rejected.phases.single() // Positively cleaned prior owner reached arm2 eligibility, not the sticky-cleanup guard.
        assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ, poolTestField<PersistencePhasePath>(phase, "path"))
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        assertTrue(phase.catalogSignerRotation.cleanupProven(rejected.attempt))
        assertEquals(2, f.invocations.sumOf { it.signing.requests.size })
    }

    private fun uncertainContinuationCustody(cut: CatalogSignerRotationContinuationCut) {
        val previous = signatureOnePersistedPrefix()
        when (cut) {
            CatalogSignerRotationContinuationCut.FIRST_SQL_PAIR_MISSING -> {
                Files.delete(f.marker(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED))
                assertFalse(f.complete(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED))
            }

            CatalogSignerRotationContinuationCut.SECOND_ARM_PAIR_PARTIAL -> {
                createUnexpectedLeaf(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_ARMED, complete = false)
                assertFalse(f.complete(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_ARMED))
            }

            CatalogSignerRotationContinuationCut.DOWNSTREAM_RECORD_PRESENT -> {
                createUnexpectedLeaf(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME, complete = true)
                assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME))
            }

            else -> error("Not a continuation custody cut.")
        }
        val refused = assertRefusedContinuation(previous)
        if (cut === CatalogSignerRotationContinuationCut.DOWNSTREAM_RECORD_PRESENT) {
            val phase = refused.phases.single() // Valid sealed pair reached eligibility, not an earlier mode/completeness refusal.
            assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ, poolTestField<PersistencePhasePath>(phase, "path"))
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.catalogSignerRotation.cleanupProven(refused.attempt))
        }
        assertEquals(1, f.invocations.sumOf { it.signing.requests.size })
        // Each cut owns a fresh ordinary fixture. Never remove the injected record or repair the original completeness pair.
    }

    private fun createUnexpectedLeaf(leaf: CatalogSignerRotationReleaseLeafV1, complete: Boolean) {
        val bytes = "synthetic-negative-record-not-an-effect-or-outcome".toByteArray(Charsets.US_ASCII)
        val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        Files.write(Files.createFile(f.path(leaf), permissions), bytes, WRITE)
        Files.setPosixFilePermissions(f.path(leaf), PosixFilePermissions.fromString("r--------"))
        if (complete) {
            val marker = ByteBuffer.allocate(68).putInt(bytes.size).put(Sha256.hex(bytes).toByteArray(Charsets.US_ASCII)).array()
            Files.write(Files.createFile(f.marker(leaf), permissions), marker, WRITE)
            Files.setPosixFilePermissions(f.marker(leaf), PosixFilePermissions.fromString("r--------"))
        }
    }

    private fun changedContinuationAuthority(expiredLease: Boolean) {
        val previous = signatureOnePersistedPrefix()
        val original = f.observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id)
        val column = if (expiredLease) "lease_expires_at" else "catalog_writer_generation"
        val leaves = f.snapshotLeaves()
        val rowVersion = core.mutationRowVersion()
        val calls = f.jdbc.calls.size
        val continued = f.invocation()
        var reads = 0
        var faulted: List<String>? = null
        val returnedSteps = mutableListOf<String>()
        f.jdbc.beforeSql = { step ->
            if (step == "control" && ++reads == 2) {
                faulted = changeAuthorityWithIndependentObserver(column, expiredLease)
            }
        }
        f.jdbc.afterSql = { step -> if (reads == 2) returnedSteps.add(step) }
        try {
            core.refused { continued.continueSecondSign(previous) }
            assertEquals(2, reads)
            continued.assertReleased()
            continued.readback.assertCompletedReadbacks(1)
            assertEquals(0, continued.signing.createdClients)
            assertEquals(
                listOf(PersistenceDatabaseOutcome.COMMITTED, PersistenceDatabaseOutcome.ROLLED_BACK),
                continued.phases.map { it.databaseOutcome() },
            )
            val lastSteps = f.jdbc.calls.filter { it.phase === continued.phases.last() }.map { it.step }
            val expectedSteps = if (expiredLease) listOf("control", "current-lease") else listOf("control")
            assertEquals(expectedSteps, lastSteps)
            assertEquals(expectedSteps, returnedSteps, "Each actual statement returned before its binding or DB-time lease predicate refused.")
            assertTrue(f.jdbc.steps.drop(calls).none { it == "prepare" || it == "signature" || it.startsWith("charge:") })
            assertEquals(checkNotNull(faulted), f.state())
            assertEquals(rowVersion, core.mutationRowVersion())
            f.assertLeavesUnchanged(leaves)
            assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_ARMED))
        } finally {
            f.jdbc.beforeSql = {}
            f.jdbc.afterSql = {}
            // Restore only the test-owned row fault for disposal, not renewal: no subsequent workflow is attempted.
            assertEquals(
                1,
                f.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", original[column], ComplaintDataScope.LIVE.id),
            )
        }
    }

    private fun changeAuthorityWithIndependentObserver(column: String, expiredLease: Boolean): List<String> {
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val resources = TransactionSynchronizationManager.getResourceMap().toMap()
        assertEquals(setOf(f.coordinator.dataSource), resources.keys)
        // Direct observer JDBC only: JdbcTemplate would enlist a second holder in the active caller's Spring synchronization.
        val state = checkNotNull(f.observer.dataSource).connection.use { connection ->
            assertTrue(connection.autoCommit)
            val replacement = if (expiredLease) "clock_timestamp() - interval '1 second'" else "?::uuid"
            connection.prepareStatement("UPDATE complaint_journal_control SET $column = $replacement WHERE data_scope_id = ?").use { statement ->
                if (!expiredLease) statement.setObject(1, UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
                statement.setObject(if (expiredLease) 1 else 2, ComplaintDataScope.LIVE.id)
                assertEquals(1, statement.executeUpdate())
            }
            observerState(connection)
        }
        assertSame(phase, PersistencePhaseOwnership.current())
        val remaining = TransactionSynchronizationManager.getResourceMap()
        assertEquals(resources.keys, remaining.keys)
        resources.forEach { (resource, holder) -> assertSame(holder, remaining[resource]) }
        return state
    }

    private fun observerState(connection: Connection): List<String> =
        observerRows(connection, "SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m ORDER BY operation_token") +
            observerRows(connection, "SELECT to_jsonb(c)::text FROM complaint_capacity_counters c ORDER BY name") +
            observerRows(connection, "SELECT to_jsonb(c)::text FROM complaint_journal_control c WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id).single()

    private fun observerRows(connection: Connection, sql: String, vararg arguments: Any): List<String> = connection.prepareStatement(sql).use { statement ->
        arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add(checkNotNull(rows.getString(1)))
            }
        }
    }

    private fun expiredContinuationAllowance() {
        val previous = signatureOnePersistedPrefix()
        val before = f.state()
        val leaves = f.snapshotLeaves()
        val calls = f.jdbc.calls.size
        val continued = f.invocation()
        assertNotSame(previous.operator.budget, continued.operator.budget)
        assertSame(continued.operator.budget, continued.attempt.budget)
        assertSame(f.clock, poolTestField<Any>(continued.operator.budget, "clock"))
        f.clock.extraNanos += 60_000_000_000L // Exhaust the new invocation; never reset either owner's clock or renew the campaign.
        core.refused { continued.continueSecondSign(previous) }
        assertEquals(
            PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED,
            assertThrows<PersistenceBoundaryException> { continued.operator.budget.remainingMillis(1) }.code,
        )
        assertTrue(poolTestField<Boolean>(previous.operator, "cleanupProven"))
        assertTrue(poolTestField<Boolean>(previous.attempt, "released"))
        assertFalse(poolTestField<Boolean>(continued.attempt, "reserved"))
        assertNull(core.activeRotation())
        assertEquals(calls, f.jdbc.calls.size)
        assertEquals(0, continued.signing.createdClients)
        assertEquals(0, continued.readback.http.createdClients)
        assertEquals(before, f.state())
        f.assertLeavesUnchanged(leaves)
        assertThrows<PersistencePhaseException> { f.invocation() } // No fresh owner can renew the now-expired genuine campaign.
    }

    internal fun assertRefusedContinuation(
        previous: CatalogSignerRotationFreezeInvocation,
        request: CatalogSignerRotationFreezeRequestV1 = f.request,
        code: CatalogSignerRotationFreezeFailureV1 = CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED,
        reserved: Boolean = true,
    ): CatalogSignerRotationFreezeInvocation {
        val before = f.state()
        val leaves = f.snapshotLeaves()
        val rowVersion = core.mutationRowVersion()
        val calls = f.jdbc.calls.size
        val next = f.invocation()
        assertEquals(code, core.refused { next.continueSecondSign(previous, request) }.code)
        if (reserved) next.assertReleased() else next.assertCleanedWithoutReservation()
        assertEquals(0, next.signing.createdClients)
        assertEquals(0, next.readback.http.createdClients)
        assertTrue(next.producedSignatures.isEmpty())
        assertTrue(f.jdbc.steps.drop(calls).none { it == "prepare" || it == "signature" || it.startsWith("charge:") })
        if (!reserved) assertEquals(calls, f.jdbc.calls.size)
        assertEquals(before, f.state())
        assertEquals(rowVersion, core.mutationRowVersion())
        f.assertLeavesUnchanged(leaves)
        return next
    }

    private fun assertContinuationPhases(invocation: CatalogSignerRotationFreezeInvocation) {
        assertEquals(
            listOf(
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
            ),
            invocation.phases.map { poolTestField<PersistencePhasePath>(it, "path") },
        )
        val binding = f.campaign.binding.arguments().toList() + listOf(f.campaign.owner, f.campaign.token)
        invocation.phases.forEach { phase ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.catalogSignerRotation.cleanupProven(invocation.attempt))
            assertTrue(f.jdbc.observations.getValue(phase).lease.completion.quiescent())
            val work: PersistenceTimeBudget = poolTestField(phase, "catalogSignerRotationWork")
            assertSame(invocation.operator.budget, poolTestField<PersistenceTimeBudget>(work, "parent"))
            f.jdbc.calls.filter { it.phase === phase && it.step in setOf("control", "current-lease") }.forEach {
                core.assertArguments(binding, it.arguments)
            }
        }
    }
}

internal enum class CatalogSignerRotationContinuationCut {
    SECOND_ALREADY_ARMED,
    FIRST_SQL_PAIR_MISSING,
    SECOND_ARM_PAIR_PARTIAL,
    DOWNSTREAM_RECORD_PRESENT,
    CURRENT_BINDING,
    EXPIRED_LEASE,
    NEW_ALLOWANCE,
}
