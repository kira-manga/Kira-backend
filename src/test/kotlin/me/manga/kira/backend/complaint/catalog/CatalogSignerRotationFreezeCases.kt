package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerRotationCapacityV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationManifestV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentJsonV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationFailureV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationInvocation
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredProcessProfileV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CurrentAcceptedCatalogRefreshV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.Signature
import java.sql.Timestamp
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real parsed first-D/G1/lease and SQL/SDK/Linux custody; neither cloud/human-approval evidence nor overlap publication/activation. */
internal class CatalogSignerRotationFreezeCases(private val f: CatalogSignerRotationFreezeFixture) {
    fun parsedBootstrapAndSignedPrepared() {
        assertEquals(DesiredProcessProfileV1.D7, f.d7.inputs.profile)
        assertSame(f.d7.inputs, f.d7.firstD.inputs)
        assertSame(f.d7.inputs.catalog, f.process.catalogReadback)
        assertSame(f.d7.inputs.catalogSignerRotation, checkNotNull(f.process.catalogSignerRotation).deployment)
        val effective = Json.parseToJsonElement(f.process.canonicalBytes().decodeToString()).jsonObject
        assertEquals(7, effective.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(1L, f.d7.refresh.catalogFor(f.process).chain.tail.generation)
        assertArrayEquals(f.d7.selectedHash, f.d7.desiredHash())
        assertSame(f.jdbc, f.campaign.jdbc)
        val beforeControl = f.d7.desired.control()
        val beforeGenesis = genesisJson()
        val beforeCounters = counterBalances()
        val original = f.invocation()
        val expectedBinding = f.campaign.binding.arguments().toList() + listOf(f.campaign.owner, f.campaign.token)
        f.jdbc.beforeSql = { step ->
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            assertSame(original.attempt, poolTestField<CatalogSignerRotationFreezeAttemptV1>(phase, "catalogSignerRotationAttempt"))
            val work: PersistenceTimeBudget = poolTestField(phase, "catalogSignerRotationWork")
            assertSame(original.operator.budget, poolTestField<PersistenceTimeBudget>(work, "parent"))
            if (step == "prepare") assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.PREPARE_ARMED))
            if (step == "signature") assertSignatureArm(original)
        }
        original.beforeSign = { slot ->
            val assembly: Any = poolTestField(original.operator, "assembly")
            val signers: Array<*> = poolTestField(assembly, "signers")
            val budget: PersistenceTimeBudget = poolTestField(checkNotNull(signers[slot]), "budget")
            assertSame(original.operator.budget, poolTestField<PersistenceTimeBudget>(budget, "parent"))
            assertEquals(10_000_000_000L, poolTestField<Long>(budget, "allowanceNanos"))
            assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.PREPARED))
            val arm = if (slot == 0) CatalogSignerRotationReleaseLeafV1.SIGN_ONE_ARMED else CatalogSignerRotationReleaseLeafV1.SIGN_TWO_ARMED
            assertTrue(f.complete(arm))
            assertNull(f.row()["envelope_bytes"])
            if (slot == 1) assertArrayEquals(original.producedSignatures[0], f.row()["signer_one_signature"] as ByteArray)
            original.readback.assertCompletedReadbacks(slot + 2) // Initial readback and a fresh closed-provider recheck before each Sign.
        }
        val result = original.execute()
        f.jdbc.beforeSql = {}
        assertEquals(CatalogSignerRotationFreezeStateV1.SIGNED_PREPARED, result.state)
        assertEquals(2L, result.generation)
        assertEquals(f.manifest.operationToken, result.operationToken)
        original.assertReleased()
        original.readback.assertCompletedReadbacks(3)
        assertEquals(2, original.signing.requests.size)
        assertTrue(original.signing.replies.all { it.eofProbes > 0 })
        assertOriginalPhases(original)
        f.jdbc.calls.filter { it.step in setOf("control", "current-lease") }.forEach { assertArguments(expectedBinding, it.arguments) }
        assertEquals(1, f.jdbc.steps.count { it == "prepare" })
        assertEquals(2, f.jdbc.steps.count { it == "signature" })
        assertEquals(2, f.jdbc.steps.count { it.startsWith("charge:") })
        val envelope = assertSignedSql(original.producedSignatures)
        assertEquals(Sha256.hex(envelope), result.envelopeSha256)
        CatalogSignerRotationReleaseLeafV1.entries.forEach { assertTrue(f.complete(it), it.name) }
        assertCharge(beforeCounters)
        assertHeadUnchanged(beforeControl, beforeGenesis)
        assertReadOnlyResume()
    }

    fun noRetrofitSelectedD2() {
        assertEquals(DesiredProcessProfileV1.D2, f.d7.inputs.profile)
        assertNull(f.process.catalogSignerRotation)
        assertEquals(1L, f.d7.refresh.catalogFor(f.process).chain.tail.generation)
        val before = f.state()
        val d7 = f.d7.document.copy(profile = "D7", catalogSignerRotation = CatalogSignerRotationD7Inputs.writer())
        var supersedeReached = false
        val rejectedDocument = assertThrows<ComplaintDesiredInstallationExceptionV1> {
            val proposed = ComplaintDesiredDeploymentJsonV1.parse(CatalogSignerRotationD7Inputs.bytes(d7.copy(desiredGeneration = 2)))
            supersedeReached = true
            DesiredInstallationInvocation(proposed).also(f.d7.desired.invocations::add).execute(expectedGeneration = 1)
        }
        assertEquals(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED, rejectedDocument.code)
        assertFalse(supersedeReached, "The proposed D7 generation2 is rejected by the real raw parser before supersede is reached.")
        val parsed = ComplaintDesiredDeploymentJsonV1.parse(CatalogSignerRotationD7Inputs.bytes(d7))
        val actualSupersede = DesiredInstallationInvocation(parsed).also(f.d7.desired.invocations::add)
        val mismatch = assertThrows<ComplaintDesiredInstallationExceptionV1> { actualSupersede.execute(expectedGeneration = 1) }
        assertEquals(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED, mismatch.code)
        assertEquals(0, actualSupersede.http.createdClients)
        assertNull(actualSupersede.target)
        actualSupersede.fixtureCleanup()
        val noWriter = assertThrows<PersistencePhaseException> { CatalogSignerRotationFreezeV1.begin(f.process, f.campaign) }
        assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, noWriter.code)
        assertTrue(f.invocations.isEmpty())
        assertTrue(f.jdbc.steps.isEmpty())
        assertEquals(before, f.state())
        f.d7.assertFrozenUnchanged()
    }

    fun changedFullBindingAndPredecessor() {
        val before = f.state()
        val badPredecessor = f.invocation()
        val changedPredecessor = isolatedRequest("predecessor", f.manifest.copy(previousEnvelopeSha256 = "f".repeat(64)))
        refused { badPredecessor.execute(request = changedPredecessor) }
        badPredecessor.assertReleased()
        assertTrue(f.jdbc.steps.isEmpty())
        assertEquals(0, badPredecessor.signing.createdClients)
        assertEquals(before, f.state())
        val original = f.observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id)
        val other = UUID.fromString("99999999-9999-4999-8999-999999999999")
        val changed = linkedMapOf<String, Any>(
            "desired_generation" to 2L,
            "desired_configuration_hash" to ByteArray(32) { 31 },
            "database_identity" to other,
            "restore_identity" to other,
            "event_writer_generation" to other,
            "accepted_catalog_generation" to 2L,
            "accepted_catalog_hash" to ByteArray(32) { 32 },
            "trust_bundle_hash" to ByteArray(32) { 33 },
            "catalog_writer_generation" to other,
            "lease_owner" to other,
            "lease_token" to 2L,
            "lease_expires_at" to checkNotNull(f.observer.queryForObject("SELECT clock_timestamp() - interval '1 second'", Timestamp::class.java)),
        )
        changed.forEach { (column, value) ->
            val callStart = f.jdbc.calls.size
            val invocation = f.invocation()
            try {
                assertEquals(
                    1,
                    f.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", value, ComplaintDataScope.LIVE.id),
                )
                val changedState = f.state()
                refused { invocation.execute(request = isolatedRequest("binding-$column", f.manifest)) }
                invocation.assertReleased()
                val expectedSteps = if (column == "lease_expires_at") listOf("control", "current-lease") else listOf("control")
                assertEquals(expectedSteps, f.jdbc.steps.drop(callStart), column)
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, invocation.phases.single().databaseOutcome())
                assertEquals(0, invocation.signing.createdClients)
                assertEquals(0, invocation.readback.http.createdClients)
                assertEquals(changedState, f.state())
            } finally {
                // Test-owned input fault only. No new budget/campaign or custody recovery is granted by restoring the row field.
                assertEquals(
                    1,
                    f.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", original[column], ComplaintDataScope.LIVE.id),
                )
            }
            assertEquals(before, f.state())
        }
        f.d7.assertFrozenUnchanged()
    }

    fun exactApprovalRefusals() {
        val before = f.state()
        val m = f.manifest
        val wrongBytes = approvalBytes(
            m.approvals.mapIndexed { index, approval ->
                if (index == 0) approval.copy(approvedAtEpochSecond = approval.approvedAtEpochSecond + 1) else approval
            },
        )
        val candidates = listOf(
            m to wrongBytes,
            m.copy(approvals = m.approvals.reversed()) to null,
            m.copy(approvals = listOf(m.approvals[0], m.approvals[0])) to null,
            m.copy(
                approvals = m.approvals.mapIndexed { index, approval ->
                    if (index == 1) approval.copy(approverId = "catalog-approver-z") else approval
                },
            ) to null,
            m.copy(creation = m.creation.copy(creatorId = "catalog-unapproved")) to null,
            m.copy(
                approvals = m.approvals.mapIndexed { index, approval ->
                    if (index == 0) approval.copy(approvedAtEpochSecond = m.creation.createdAtEpochSecond - 1) else approval
                },
            ) to null,
            m.copy(creation = m.creation.copy(createdAtEpochSecond = f.d7.freeze.manifest.creation.createdAtEpochSecond)) to null,
        )
        candidates.forEachIndexed { index, (candidate, approvals) ->
            val firstCall = f.jdbc.calls.size
            val invocation = f.invocation()
            refused { invocation.execute(request = isolatedRequest("approvals-$index", candidate, approvals)) }
            invocation.assertReleased()
            assertEquals(0, invocation.signing.createdClients)
            assertTrue(f.jdbc.steps.drop(firstCall).none { it == "prepare" || it == "signature" || it.startsWith("charge:") })
            assertEquals(before, f.state())
        }
        assertTrue(f.invocations.all { it.producedSignatures.isEmpty() })
        f.d7.assertFrozenUnchanged()
    }

    fun missingConflictingAndUncertainCustody() {
        val before = f.state()
        val missing = f.invocation()
        assertEquals(CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED, refused { missing.execute(resume = true) }.code)
        missing.assertReleased()
        assertEquals(0, missing.signing.createdClients)
        assertEquals(before, f.state())

        val partial = f.invocation()
        var readPhases = 0
        var refusedBeforeSecondArm = false
        f.jdbc.beforeSql = { step ->
            if (step == "control" && f.jdbc.calls.last().path === PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ && ++readPhases == 3) {
                refusedBeforeSecondArm = true
                error("Synthetic second Sign recheck refusal before its effect arm.")
            }
        }
        try {
            refused { partial.execute() }
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
        val prepared = f.state()
        val leaves = f.snapshotLeaves()
        // Known unattempted Sign2 is an INTERNAL continuation gap, not permission to Sign on resume or an automatic external-failure label.
        assertNoSignRecovery(f.request, prepared, leaves)
        val conflicting = f.request(
            f.manifest.copy(creation = f.manifest.creation.copy(createdAtEpochSecond = f.manifest.creation.createdAtEpochSecond + 1)),
        )
        assertNoSignRecovery(conflicting, prepared, leaves)
        Files.delete(f.marker(CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE)) // Deliberate local uncertainty, never repaired to obtain Sign.
        assertFalse(f.complete(CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE))
        assertNoSignRecovery(f.request, prepared, f.snapshotLeaves())
        assertEquals(1, f.invocations.sumOf { it.signing.requests.size })
        f.d7.assertFrozenUnchanged()
    }

    fun signatureCompletionFailureReusesFrozenBytes() {
        val beforeControl = f.d7.desired.control()
        val beforeGenesis = genesisJson()
        val counters = counterBalances()
        val first = f.invocation()
        var signaturesWritten = 0
        var committedSecond = false
        f.jdbc.afterSql = { step ->
            if (step == "signature" && ++signaturesWritten == 2) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        committedSecond = true
                        error("Synthetic second signature completion-tail failure.")
                    }
                })
            }
        }
        try {
            refused { first.execute() }
        } finally {
            f.jdbc.afterSql = {}
        }
        assertTrue(committedSecond)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, first.phases.last().databaseOutcome())
        assertTrue(first.phases.last().catalogSignerRotation.cleanupProven(first.attempt))
        first.assertReleased()
        first.readback.assertCompletedReadbacks(3)
        assertEquals(2, first.signing.requests.size)
        assertSignedSql(first.producedSignatures)
        assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_ARMED))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_PERSISTED))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME))
        val committed = f.state()
        val leaves = f.snapshotLeaves()
        val rowVersion = mutationRowVersion()
        val calls = f.jdbc.calls.size
        val resumed = f.invocation()
        assertNotSame(first.operator, resumed.operator)
        assertSame(first.attempt.campaign, resumed.attempt.campaign)
        assertEquals(CatalogSignerRotationFreezeStateV1.SIGNED_PREPARED, resumed.execute(resume = true).state)
        resumed.assertReleased()
        resumed.readback.assertCompletedReadbacks(1)
        assertEquals(0, resumed.signing.createdClients)
        assertEquals(committed, f.state())
        assertEquals(rowVersion, mutationRowVersion())
        assertTrue(f.jdbc.steps.drop(calls).none { it == "signature" || it == "prepare" || it.startsWith("charge:") })
        f.assertLeavesUnchanged(leaves, allowAdditionalLeaves = true)
        assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_PERSISTED))
        assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME))
        assertSignedSql(first.producedSignatures)
        assertCharge(counters)
        assertHeadUnchanged(beforeControl, beforeGenesis)
    }

    fun originalBudgetAndSignerCleanup(cut: CatalogSignerRotationCleanupCut) = when (cut) {
        CatalogSignerRotationCleanupCut.BEGIN_BUDGET -> expiredOriginalBudget()
        CatalogSignerRotationCleanupCut.SIGNER_CLOSE -> throwingSignerClose()
        CatalogSignerRotationCleanupCut.PHASE_CLEANUP -> unresolvedOriginalPhaseCleanup()
    }

    private fun expiredOriginalBudget() {
        val before = f.state()
        val expired = f.invocation()
        assertSame(f.clock, poolTestField<Any>(expired.operator.budget, "clock"))
        assertSame(expired.operator.budget, expired.attempt.budget)
        f.clock.extraNanos += 60_000_000_000L // Only exhaust; never reset this clock to revive an original campaign.
        refused { expired.execute() }
        assertEquals(
            PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED,
            assertThrows<PersistenceBoundaryException> { expired.operator.budget.remainingMillis(1) }.code,
        )
        refused { expired.execute(resume = true) }
        assertThrows<PersistencePhaseException> { f.invocation() } // A fresh owner cannot renew the expired original campaign.
        assertTrue(f.jdbc.steps.isEmpty())
        assertEquals(0, expired.signing.createdClients)
        assertEquals(0, expired.readback.http.createdClients)
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.PREPARE_ARMED))
        assertEquals(before, f.state())
    }

    private fun throwingSignerClose() {
        val beforeControl = f.d7.desired.control()
        val beforeGenesis = genesisJson()
        val closing = f.invocation()
        closing.signing.onClientClose = { throw IOException("synthetic-private-rotation-close-diagnostic") }
        assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, refused { closing.execute() }.code)
        assertEquals(1, closing.producedSignatures.size)
        assertEquals(1, closing.signing.closedClients)
        assertEquals(0, closing.signing.returnedClientCloses)
        assertUnsignedPrepared()
        assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_ARMED))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_RETURNED))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED))
        assertSame(closing.attempt, activeRotation())
        assertFalse(poolTestField<Boolean>(closing.attempt, "released"))
        val stopped = f.state()
        val calls = f.jdbc.calls.size
        refused { closing.execute(resume = true) }
        assertReplacementBlocked(closing.attempt)
        assertEquals(calls, f.jdbc.calls.size)
        assertEquals(stopped, f.state())
        assertEquals(1, closing.signing.requests.size)
        assertEquals(1, closing.signing.closedClients)
        assertHeadUnchanged(beforeControl, beforeGenesis)
        // No normal successful-cleanup assertion: the enclosing fixture later retires the original process and synthetic resources.
    }

    private fun unresolvedOriginalPhaseCleanup() {
        val beforeControl = f.d7.desired.control()
        val beforeGenesis = genesisJson()
        val original = f.invocation()
        val key = Any()
        val sentinel = Any()
        var bound = false
        var retained: PersistencePhaseContext? = null
        f.jdbc.afterSql = { step ->
            if (step == "prepare") {
                retained = checkNotNull(PersistencePhaseOwnership.current())
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        TransactionSynchronizationManager.bindResource(key, sentinel)
                        bound = true
                        error("Synthetic rotation completion with unresolved original Spring cleanup.")
                    }
                })
            }
        }
        try {
            assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, refused { original.execute() }.code)
            val phase = checkNotNull(retained)
            assertTrue(bound)
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.quarantined())
            assertSame(phase, PersistencePhaseOwnership.current())
            assertSame(phase, ownedCutField(original.attempt, "originalPhase"))
            assertFalse(phase.catalogSignerRotation.cleanupProven(original.attempt))
            assertTrue(poolTestField<Boolean>(original.attempt, "sqlCleanupUnproven"))
            assertSame(original.attempt, activeRotation())
            assertFalse(poolTestField<Boolean>(original.attempt, "released"))
            assertThrows<PersistencePhaseException> { f.invocation() }
            assertEquals(0, original.signing.createdClients)
        } finally {
            f.jdbc.afterSql = {}
            if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
            requireConnectionFree() // Remove only the injected resource and reconcile the same real phase; never repair its sticky failure.
        }
        val phase = checkNotNull(retained)
        assertNull(PersistencePhaseOwnership.current())
        assertEquals(0, f.coordinator.activeSnapshotOwners())
        assertTrue(phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
        assertFalse(phase.catalogSignerRotation.cleanupProven(original.attempt))
        assertSame(phase, ownedCutField(original.attempt, "originalPhase"))
        assertSame(original.attempt, activeRotation())
        assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, refused(original.operator::close).code)
        val calls = f.jdbc.calls.size
        assertReplacementBlocked(original.attempt)
        assertEquals(calls, f.jdbc.calls.size)
        assertUnsignedPrepared()
        assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.PREPARE_ARMED))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.PREPARED))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_ARMED))
        assertHeadUnchanged(beforeControl, beforeGenesis)
        // Original file cleanup may remain undispatched here. Outer process retirement disposes it without freeing this failed slot.
    }

    private fun assertReplacementBlocked(original: CatalogSignerRotationFreezeAttemptV1) {
        val replacement = f.invocation()
        refused { replacement.execute(resume = true) }
        assertEquals(0, replacement.signing.createdClients)
        assertEquals(0, replacement.readback.http.createdClients)
        assertTrue(replacement.phases.isEmpty())
        assertSame(original, activeRotation())
        val http = CatalogSignerRotationReadbackHttpFixture(f.d7)
        val refresh = CurrentAcceptedCatalogRefreshV1.withHttpFixture(
            f.process, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials,
            http::httpClient, f.d7.wallClock, f.clock::nanoTime,
        )
        val refusal = assertThrows<CatalogReadbackException> { refresh.use { it.refresh() } }
        assertEquals(CatalogReadbackFailure.LIMIT_EXCEEDED, refusal.code)
        assertEquals(0, http.http.createdClients)
        assertSame(original, activeRotation())
    }

    private fun assertNoSignRecovery(
        request: CatalogSignerRotationFreezeRequestV1,
        state: List<String>,
        leaves: Map<Path, Pair<Map<String, Any>, ByteArray>>,
    ) {
        for (resume in listOf(true, false)) {
            val callStart = f.jdbc.calls.size
            val invocation = f.invocation()
            assertEquals(CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED, refused { invocation.execute(resume, request) }.code)
            invocation.assertReleased()
            assertEquals(0, invocation.signing.createdClients)
            assertEquals(0, invocation.readback.http.createdClients)
            assertEquals(callStart, f.jdbc.calls.size)
            assertEquals(state, f.state())
            f.assertLeavesUnchanged(leaves)
        }
    }

    private fun assertReadOnlyResume() {
        val state = f.state()
        val leaves = f.snapshotLeaves()
        val rowVersion = mutationRowVersion()
        val calls = f.jdbc.calls.size
        val resumed = f.invocation()
        assertEquals(CatalogSignerRotationFreezeStateV1.SIGNED_PREPARED, resumed.execute(resume = true).state)
        resumed.assertReleased()
        resumed.readback.assertCompletedReadbacks(1)
        assertEquals(0, resumed.signing.createdClients)
        assertEquals(state, f.state())
        assertEquals(rowVersion, mutationRowVersion())
        assertTrue(f.jdbc.steps.drop(calls).none { it == "signature" || it == "prepare" || it.startsWith("charge:") })
        f.assertLeavesUnchanged(leaves)
    }

    private fun assertOriginalPhases(invocation: CatalogSignerRotationFreezeInvocation) {
        assertSame(invocation.operator.budget, invocation.attempt.budget)
        assertSame(f.clock, poolTestField<Any>(invocation.operator.budget, "clock"))
        assertEquals(
            listOf(
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
            ),
            invocation.phases.map { poolTestField<PersistencePhasePath>(it, "path") },
        )
        invocation.phases.forEach { phase ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.catalogSignerRotation.cleanupProven(invocation.attempt))
            assertTrue(f.jdbc.observations.getValue(phase).lease.completion.quiescent())
            val steps = f.jdbc.calls.filter { it.phase === phase }.map { it.step }
            assertEquals(listOf("control", "current-lease", "catalog", "history-lock", "counters"), steps.take(5))
            assertEquals(listOf("current-lease", "history-read", "current-lease"), steps.takeLast(3))
        }
    }

    private fun assertSignatureArm(invocation: CatalogSignerRotationFreezeInvocation) {
        val slot = invocation.producedSignatures.size - 1
        val signature = if (slot == 0) CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE else CatalogSignerRotationReleaseLeafV1.SIGNATURE_TWO
        val arm = if (slot == 0) CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED else CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_ARMED
        assertTrue(f.complete(signature))
        assertTrue(f.complete(arm))
        assertArrayEquals(invocation.producedSignatures[slot], f.read(signature))
        if (slot == 1) assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.ENVELOPE))
    }

    private fun assertSignedSql(signatures: List<ByteArray>): ByteArray {
        assertEquals(2, signatures.size)
        val ids = listOf("catalog-old", "catalog-new")
        signatures.forEachIndexed { index, bytes ->
            val pair = listOf(OfflineTrustBundleFixture.firstSigner, OfflineTrustBundleFixture.secondSigner)[index]
            assertTrue(
                Signature.getInstance("RSASSA-PSS").run {
                    setParameter(OfflineTrustBundleFixture.parameters)
                    initVerify(pair.public)
                    update(OfflineCatalogGenesisFixture.independentFrame(ids[index], f.intent))
                    verify(bytes)
                },
            )
        }
        val expected = OfflineCatalogRotationFixture.bytes(
            OfflineCatalogRotationEnvelopeV1(
                1, f.manifest,
                signatures.mapIndexed { index, bytes ->
                    OfflineCatalogGenesisSignatureV1(ids[index], "RSASSA_PSS_SHA_256", Base64.getEncoder().encodeToString(bytes))
                },
            ),
        )
        val row = f.row()
        assertArrayEquals(f.intent, row["unsigned_bytes"] as ByteArray)
        assertArrayEquals(f.approvals, row["approval_bytes"] as ByteArray)
        assertArrayEquals(signatures[0], row["signer_one_signature"] as ByteArray)
        assertArrayEquals(signatures[1], row["signer_two_signature"] as ByteArray)
        assertArrayEquals(signatures[0], f.read(CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE))
        assertArrayEquals(signatures[1], f.read(CatalogSignerRotationReleaseLeafV1.SIGNATURE_TWO))
        assertArrayEquals(expected, row["envelope_bytes"] as ByteArray)
        assertArrayEquals(expected, f.read(CatalogSignerRotationReleaseLeafV1.ENVELOPE))
        assertEquals(Sha256.hex(expected), HexFormat.of().formatHex(row["envelope_hash"] as ByteArray))
        assertPreparedOnly(row)
        return expected
    }

    private fun assertUnsignedPrepared() {
        val row = f.row()
        assertPreparedOnly(row)
        for (column in listOf("signer_one_signature", "signer_two_signature", "envelope_bytes", "envelope_hash")) assertNull(row[column], column)
        assertArrayEquals(f.intent, row["unsigned_bytes"] as ByteArray)
        assertTrue(f.jdbc.steps.none { it == "signature" })
    }

    private fun assertPreparedOnly(row: Map<String, Any?>) {
        assertEquals("PREPARED", row["state"])
        assertEquals("SIGNER_ROTATION_OVERLAP", row["operation_type"])
        assertEquals("ROTATION_OVERLAP", row["signer_policy"])
        assertEquals(1L, row["predecessor_generation"])
        assertEquals(2L, row["successor_generation"])
        assertEquals(Sha256.hex(f.d7.envelope), HexFormat.of().formatHex(row["predecessor_hash"] as ByteArray))
        for (column in listOf(
            "object_version", "retain_until", "primary_evidence_bytes", "primary_evidence_hash", "replica_evidence_bytes", "replica_evidence_hash",
            "completed_at", "projected_at",
        )) assertNull(row[column], column)
        assertEquals(2L, f.observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations", Long::class.java))
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations WHERE successor_generation >= 3", Long::class.java))
    }

    private fun assertHeadUnchanged(control: String, genesis: String) {
        assertEquals(control, f.d7.desired.control(), "No head/lease/desired/writer/gate/scan transition belongs to signed PREPARED2.")
        assertEquals(genesis, genesisJson())
        assertArrayEquals(f.d7.envelope, f.d7.genesisRow()["envelope_bytes"] as ByteArray)
        f.d7.assertFrozenUnchanged()
    }

    private fun counterBalances(): Map<String, List<Long>> = f.observer.query(
        "SELECT name, free_units, actual_units, recovery_reserved_units, test_reserved_units FROM complaint_capacity_counters ORDER BY name",
        { row, _ -> row.getString(1) to (2..5).map(row::getLong) },
    ).toMap()

    private fun assertCharge(before: Map<String, List<Long>>) {
        val after = counterBalances()
        assertEquals(before.keys, after.keys)
        before.forEach { (name, amounts) ->
            val charge = when (name) {
                "catalog_mutations" -> 1L
                "storage_bytes" -> CatalogSignerRotationCapacityV1.storageBytes
                else -> 0L
            }
            assertEquals(listOf(amounts[0] - charge, amounts[1] + charge, amounts[2], amounts[3]), after[name], name)
        }
    }

    private fun genesisJson(): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m WHERE operation_token = ?",
            String::class.java, UUID.fromString(f.d7.freeze.manifest.operationToken),
        ),
    )

    private fun mutationRowVersion(): String = checkNotNull(
        f.observer.queryForObject("SELECT xmin::text FROM complaint_catalog_mutations WHERE operation_token = ?", String::class.java, f.token),
    )

    private fun activeRotation(): Any? = poolTestField<AtomicReference<Any?>>(f.coordinator.catalogRefreshCustody, "active").get()

    private fun isolatedRequest(
        label: String,
        manifest: OfflineCatalogRotationManifestV1,
        approvals: ByteArray? = null,
    ): CatalogSignerRotationFreezeRequestV1 {
        // Separate negative inputs before any PREPARE. Alternate roots are not recovery permission for a started allocation.
        val root = Files.createDirectory(
            f.releaseRoot.resolve(label), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        return f.request(manifest, approvals ?: approvalBytes(manifest.approvals), root)
    }

    private fun approvalBytes(approvals: List<OfflineCatalogGenesisApprovalV1>): ByteArray =
        CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), approvals).toByteArray(Charsets.UTF_8)

    private fun assertArguments(expected: List<Any?>, actual: List<Any?>) {
        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { index, value ->
            if (value is ByteArray) assertArrayEquals(value, actual[index] as ByteArray) else assertEquals(value, actual[index])
        }
    }

    private fun refused(action: () -> Any?): CatalogSignerRotationFreezeExceptionV1 {
        val failure = assertThrows<CatalogSignerRotationFreezeExceptionV1> { action() }
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertEquals("Catalog signer rotation freeze refused: ${failure.code.name}.", failure.message)
        return failure
    }
}

internal enum class CatalogSignerRotationCleanupCut { BEGIN_BUDGET, SIGNER_CLOSE, PHASE_CLEANUP }
