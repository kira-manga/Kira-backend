package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.CatalogGenesisFinalizationLifecycleTest
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhysicalEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationCompletionCut
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationInputFixture
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisInitialLiveBinding
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CurrentAcceptedCatalogRefreshV1
import me.manga.kira.backend.complaint.infrastructure.catalog.GenesisResume
import me.manga.kira.backend.complaint.infrastructure.catalog.LinuxGenesisReleaseFilesV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Six connected groups only: real freeze/first-D/TARGET, SDK raw readback and Linux custody. No cloud/deployment or crash-durability claim. */
@Suppress("LargeClass") // Keep six joined state/recovery groups and their original-owner assertions together, not split across new fixture abstractions.
internal class CatalogGenesisTargetFinalizeCases(private val f: CatalogGenesisTargetFinalizeFixture) {
    fun frozenReleaseFirstDAndTargetFinalize() {
        val counters = f.counters()
        val frozenMutation = frozenMutation()
        val control = preservedControl()
        val invocation = f.invocation()
        val pids = linkedSetOf<Int>()
        var closedProvider = false
        var durableBeforeSql = false
        var beforeCommit = false
        var afterCommit = false
        invocation.afterHttpClose = {
            if (invocation.http.closedClients == invocation.http.createdClients) {
                closedProvider = true
                val scope = checkNotNull(invocation.scope)
                CatalogGenesisFinalizationLifecycleTest.assertClosedRoutes(scope.owner, scope, checkNotNull(invocation.target).pools)
                assertEquals(1, scope.catalogEntries().size)
                assertEquals(1, actualPool(invocation.coordinator.dataSource).hikariPoolMXBean.totalConnections)
                assertEquals(
                    1L,
                    f.observer.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND usename = ?",
                        Long::class.java,
                        PgLifecycleDatabaseSettings.CANDIDATE,
                    ),
                )
                assertArrayEquals(f.selectedCanonical, checkNotNull(invocation.target).canonicalBytes())
                assertArrayEquals(f.selectedHash, checkNotNull(invocation.target).configurationHashBytes())
                assertFalse(f.exists(CatalogGenesisReleaseLeafV1.FINALIZE_ARMED))
                assertHistory(GenesisResume.PREPARED)
            }
        }
        invocation.afterSample = {
            val fresh = retainedReadback(invocation)
            if (!durableBeforeSql && fresh != null && PersistencePhaseOwnership.current() == null) {
                assertTrue(closedProvider)
                assertEquals(2, invocation.returnedHttpCloses)
                assertDurableBarrier(invocation, fresh)
                assertHistory(GenesisResume.PREPARED)
                durableBeforeSql = true
            }
        }
        invocation.beforeSql = { step ->
            assertTrue(durableBeforeSql, "Durability and actual provider return must precede the first COMPLETE checkout/work.")
            assertOriginalBudget(invocation, checkNotNull(PersistencePhaseOwnership.current()))
            if (step == "control") pids.add(assertActualTargetHolder(invocation))
        }
        invocation.afterSql = { step ->
            if (path() == PROJECT && step == "initial") {
                val phase = checkNotNull(PersistencePhaseOwnership.current())
                val operation = retainedOperation(phase)
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) = invocation.preserveAssertions {
                        assertFalse(readOnly)
                        assertTrue(operation.completedFor(phase))
                        assertProjectionRefused(operation, PersistenceDatabaseOutcome.NONE, cleanup = false)
                        assertFalse(f.exists(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME))
                        beforeCommit = true
                    }

                    override fun afterCommit() = invocation.preserveAssertions {
                        assertProjectionRefused(operation, PersistenceDatabaseOutcome.COMMITTED, cleanup = false)
                        assertFalse(f.exists(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME))
                        afterCommit = true
                    }
                })
            }
        }
        invocation.execute()
        invocation.assertFullReadback()
        invocation.assertReleased()
        assertTrue(durableBeforeSql && beforeCommit && afterCommit)
        assertEquals(1, pids.size)
        assertPhases(invocation, listOf(SNAPSHOT, COMPLETE, PROJECT))
        invocation.phases.forEach { assertCommittedAndReleased(it) }
        assertTargetSecretInventory(invocation)
        assertProjected()
        assertOutcome()
        assertEquals(counters, f.counters())
        assertEquals(frozenMutation, frozenMutation())
        assertEquals(control, preservedControl())
        f.assertFrozenUnchanged()
    }

    fun custodyFailurePreventsComplete() {
        val before = f.state()
        val empty = Files.createDirectory(
            f.freeze.parent.resolve("missing-finalize-allocation"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        val missingRequest = CatalogGenesisFinalizeRequestV1(f.freeze.request(f.request.frozen.independentPin, empty), f.request.targetDeployment)
        val missing = f.invocation()
        refused { missing.execute(missingRequest) }
        missing.fixtureCleanup()
        assertTrue(Files.list(empty).use { it.findAny().isEmpty }, "Existing-only finalization must create neither allocation nor permanent lock.")
        assertTrue(missing.secrets.requests.isEmpty() && missing.http.requests.isEmpty())
        val authorResume = f.freeze.invocation()
        assertThrows<CatalogGenesisFreezeExceptionV1> { authorResume.execute(resume = true, request = missingRequest.frozen) }
        authorResume.fixtureCleanup()
        assertTrue(Files.list(empty).use { it.findAny().isEmpty }, "The author's named resume also refuses without creating custody.")
        assertTrue(authorResume.phases.isEmpty() && authorResume.signing.requests.isEmpty())
        for (entry in listOf(f.freeze.releaseRoot.resolve(".custody.lock"), f.freeze.releaseRoot.resolve("genesis/allocation.complete"))) {
            val incomplete = f.invocation()
            withMissingFixtureEntry(entry, incomplete) {
                refused { incomplete.execute() }
                assertFalse(Files.exists(entry, NOFOLLOW_LINKS))
                assertTrue(incomplete.secrets.requests.isEmpty() && incomplete.http.requests.isEmpty())
            }
        }
        val obstructed = f.invocation()
        obstructed.afterHttpClose = {
            if (obstructed.http.closedClients == obstructed.http.createdClients) {
                Files.createDirectory(
                    f.freeze.leafPath(CatalogGenesisReleaseLeafV1.FINALIZE_ARMED),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
            }
        }
        refused { obstructed.execute() }
        obstructed.fixtureCleanup()
        obstructed.assertFullReadback()
        assertPhases(obstructed, listOf(SNAPSHOT))
        assertTrue(obstructed.sql.isEmpty())
        assertFalse(f.exists(CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE))
        assertFalse(f.exists(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME))
        val retry = f.invocation()
        refused { retry.execute() }
        retry.fixtureCleanup()
        assertTrue(retry.secrets.requests.isEmpty() && retry.http.requests.isEmpty() && retry.phases.isEmpty())
        assertTrue(Files.isDirectory(f.freeze.leafPath(CatalogGenesisReleaseLeafV1.FINALIZE_ARMED), NOFOLLOW_LINKS))
        assertFalse(Files.exists(f.completeness(CatalogGenesisReleaseLeafV1.FINALIZE_ARMED), NOFOLLOW_LINKS))
        assertEquals(before, f.state(), "No partial/conflicting custody is repaired or promoted into COMPLETE.")
        f.assertFrozenUnchanged()
    }

    fun freshReadbackClassifiesEveryResume() {
        val counters = f.counters()
        val invocation = f.invocation()
        invocation.beforeSql = { step ->
            if (path() == PROJECT && step == "control") error("Synthetic pre-PROJECT work interruption.")
        }
        refused { invocation.execute() }
        invocation.fixtureCleanup()
        invocation.assertFullReadback()
        assertPhases(invocation, listOf(SNAPSHOT, COMPLETE, PROJECT))
        assertCommittedAndReleased(invocation.phases.single { phasePath(it) == COMPLETE })
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, invocation.phases.single { phasePath(it) == PROJECT }.databaseOutcome())
        assertHistory(GenesisResume.PROJECTION_PENDING)
        assertFalse(f.exists(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME))
        val originalFresh = checkNotNull(retainedReadback(invocation))
        assertEquals(GenesisResume.PREPARED, originalFresh.resume)
        val stable = stableEvidence()
        val completedAt = completionTime()
        val pending = f.invocation()
        pending.wallClock.advance(Duration.ofMinutes(1))
        pending.execute()
        pending.assertReleased()
        pending.assertFullReadback()
        assertPhases(pending, listOf(SNAPSHOT, PROJECT))
        val pendingFresh = checkNotNull(retainedReadback(pending))
        assertNotSame(originalFresh, pendingFresh)
        assertEquals(GenesisResume.PROJECTION_PENDING, pendingFresh.resume)
        assertTrue(pendingFresh.evaluatedAtEpochSecond > originalFresh.evaluatedAtEpochSecond)
        assertEquals(completedAt, completionTime())
        assertStable(stable)
        assertProjected()
        assertOutcome()
        val projected = f.state()
        val outcome = f.freeze.read(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME)
        val replay = f.invocation()
        replay.wallClock.advance(Duration.ofMinutes(2))
        replay.execute()
        replay.assertReleased()
        replay.assertFullReadback()
        assertPhases(replay, listOf(SNAPSHOT, PROJECT))
        assertEquals(GenesisResume.PROJECTED_REPLAY, checkNotNull(retainedReadback(replay)).resume)
        assertFalse(replay.sql.any { it.second in setOf("complete", "head", "project", "initial") })
        assertEquals(projected, f.state(), "An exact locked replay changes no row, timestamp or counter.")
        assertStable(stable)
        assertArrayEquals(outcome, f.freeze.read(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME))
        assertEquals(counters, f.counters())
        assertSticky(invocation)
        f.assertFrozenUnchanged()
    }

    fun lockedCurrentDAndOriginalBarrierRequired() {
        val original = f.state()
        var oldReadback: CatalogDualLocationVerifier.GenesisReadback? = null
        for (changed in listOf<ByteArray?>(null, ByteArray(32) { 93 })) {
            val invocation = f.invocation()
            invocation.afterHttpClose = {
                if (invocation.http.closedClients == invocation.http.createdClients) {
                    assertEquals(
                        1,
                        f.observer.update(
                            "UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?",
                            changed,
                            ComplaintDataScope.LIVE.id,
                        ),
                    )
                }
            }
            refused { invocation.execute() }
            invocation.fixtureCleanup()
            invocation.assertFullReadback()
            assertPhases(invocation, listOf(SNAPSHOT, COMPLETE))
            assertEquals(listOf(COMPLETE to "control"), invocation.sql)
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, invocation.phases.last().databaseOutcome())
            assertHistory(GenesisResume.PREPARED)
            assertFalse(f.exists(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME))
            oldReadback = checkNotNull(retainedReadback(invocation))
            // Restore only this negative fixture's deliberate corruption; the positive D came from the real first-D command.
            f.desired.restoreControl(f.selectedControl)
            assertEquals(original, f.state())
        }
        withNullDOnCompletionHead {
            val reread = f.invocation()
            refused { reread.execute() }
            reread.fixtureCleanup()
            reread.assertFullReadback()
            assertTrue(reread.sql.contains(COMPLETE to "complete") && reread.sql.contains(COMPLETE to "head"))
            assertEquals(2, reread.sql.count { it == (COMPLETE to "control") })
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, reread.phases.last().databaseOutcome())
            assertEquals(original, f.state(), "The real post-write reread must reject NULL D and roll back COMPLETE/head together.")
        }
        assertOriginalBarrier(checkNotNull(oldReadback))
        assertProjected()
        f.assertFrozenUnchanged()
    }

    fun originalBudgetAndProviderCloseStaySticky() {
        val before = f.state()
        val expired = f.invocation()
        expired.clock.extraNanos += ORIGINAL_ALLOWANCE_NANOS
        refused { expired.execute() }
        expired.fixtureCleanup()
        assertTrue(expired.secrets.requests.isEmpty() && expired.http.requests.isEmpty())
        assertNull(expired.scope)
        assertSticky(expired)
        val acquiring = f.invocation()
        acquiring.secrets.onClientClose = { acquiring.clock.extraNanos += ORIGINAL_ALLOWANCE_NANOS }
        refused { acquiring.execute() }
        acquiring.fixtureCleanup()
        assertEquals(1, acquiring.secrets.requests.size)
        assertEquals(1, acquiring.secrets.closedClients)
        assertTrue(acquiring.http.requests.isEmpty())
        assertNull(acquiring.scope)
        assertSticky(acquiring)
        for (throwingClose in listOf(false, true)) {
            val closing = f.invocation()
            closing.afterHttpClose = {
                if (closing.http.closedClients == closing.http.createdClients) {
                    if (throwingClose) throw IOException(PRIVATE_CLOSE_CANARY)
                    closing.clock.extraNanos += ORIGINAL_ALLOWANCE_NANOS
                }
            }
            refused { closing.execute() }
            closing.fixtureCleanup()
            assertPhases(closing, listOf(SNAPSHOT))
            assertTrue(closing.sql.isEmpty())
            assertEquals(4, closing.http.requests.size)
            assertEquals(2, closing.http.closedClients)
            assertEquals(if (throwingClose) 1 else 2, closing.returnedHttpCloses)
            assertFalse(f.exists(CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE))
            assertFalse(f.exists(CatalogGenesisReleaseLeafV1.FINALIZE_ARMED))
            assertSticky(closing)
        }
        assertEquals(before, f.state())
        activeCompleteExpiry(before)
        f.assertFrozenUnchanged()
    }

    fun unknownCommitOrReleaseNeverSucceeds() {
        val preserved = preservedControl()
        val frozenMutation = frozenMutation()
        val counters = f.counters()
        val failed = mutableListOf<CatalogGenesisMutationOperation>()
        for (cut in DesiredInstallationCompletionCut.entries) {
            val before = f.state()
            val invocation = f.invocation()
            val fault = CompletionFault()
            invocation.afterSql = { step ->
                if (path() == PROJECT && step == "control" && fault.operation == null) {
                    armCompletionFault(invocation, fault, cut)
                }
            }
            withDeferredProjectionCommit(cut) {
                try {
                    refused { invocation.execute() }
                    assertTrue(fault.beforeCommit)
                    assertEquals(cut != DesiredInstallationCompletionCut.DEFERRED_COMMIT, fault.afterCommit)
                    val phase = invocation.phases.last()
                    val expected = outcome(cut)
                    assertProjectionRefused(checkNotNull(fault.operation), expected, cleanup = cut != DesiredInstallationCompletionCut.UNRESOLVED_RELEASE)
                    assertEquals(expected, phase.databaseOutcome())
                    assertFalse(f.exists(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME))
                    if (cut == DesiredInstallationCompletionCut.UNRESOLVED_RELEASE) {
                        assertSame(phase, PersistencePhaseOwnership.current())
                        assertTrue(phase.quarantined())
                        assertEquals(1, invocation.coordinator.activeSnapshotOwners())
                        assertFalse(releaseFiles(invocation).cleanupComplete(), "Unreleased original phase must retain undispatched custody cleanup.")
                    }
                } finally {
                    fault.removeSentinel()
                    requireConnectionFree() // Only the original caller can reconcile its actual Spring quarantine.
                    invocation.fixtureCleanup()
                }
            }
            invocation.assertFullReadback()
            val operation = checkNotNull(fault.operation)
            failed.add(operation)
            assertProjectionRefused(operation, outcome(cut), cleanup = true)
            assertSticky(invocation)
            if (cut == DesiredInstallationCompletionCut.DEFERRED_COMMIT) {
                assertHistory(GenesisResume.PROJECTION_PENDING)
                assertPhases(invocation, listOf(SNAPSHOT, COMPLETE, PROJECT))
            } else {
                assertProjected()
                assertPhases(invocation, listOf(SNAPSHOT, PROJECT))
                if (cut == DesiredInstallationCompletionCut.UNRESOLVED_RELEASE) assertEquals(before, f.state())
            }
        }
        val stable = stableEvidence()
        val projected = f.state()
        val retry = f.invocation()
        retry.execute()
        retry.assertFullReadback()
        retry.assertReleased()
        assertPhases(retry, listOf(SNAPSHOT, PROJECT))
        assertEquals(projected, f.state())
        assertStable(stable)
        assertOutcome()
        failed.forEach { assertThrows<PersistencePhaseException> { it.processBoundProjection } }
        assertEquals(preserved, preservedControl())
        assertEquals(frozenMutation, frozenMutation())
        assertEquals(counters, f.counters())
        f.assertFrozenUnchanged()
    }

    private fun assertOriginalBarrier(oldReadback: CatalogDualLocationVerifier.GenesisReadback) {
        val foreign = ComplaintDesiredProcessAssemblyV1()
        val invocation = f.invocation()
        var checked = false
        invocation.afterSample = {
            val fresh = retainedReadback(invocation)
            if (!checked && fresh != null && PersistencePhaseOwnership.current() == null) {
                checked = true
                val original = invocation.attempt
                val target = checkNotNull(invocation.target)
                assertArrayEquals(target.canonicalBytes(), foreign.target.canonicalBytes())
                assertNotSame(target, foreign.target)
                assertNotSame(target.pools.catalogCoordinator, foreign.target.pools.catalogCoordinator)
                val copied = CatalogGenesisFinalizeAttemptV1(invocation.operator, target, invocation.release, original.budget)
                val wrongProcess = CatalogGenesisFinalizeAttemptV1(invocation.operator, foreign.target, invocation.release, original.budget)
                assertThrows<CatalogGenesisFinalizeExceptionV1> { copied.complete(fresh) }
                assertThrows<CatalogGenesisFinalizeExceptionV1> { wrongProcess.project(fresh) }
                assertThrows<CatalogGenesisFinalizeExceptionV1> { original.requireBarrier(oldReadback, original.expected) }
                assertThrows<CatalogGenesisFinalizeExceptionV1> { original.requireBarrier(fresh, CatalogGenesisInitialLiveBinding.fromRetained(target)) }
                assertThrows<CatalogGenesisFinalizeExceptionV1> { invocation.release.requireBarrier(foreign.target, fresh) }
                assertThrows<CatalogGenesisFinalizeExceptionV1> { invocation.coordinator.ownership.enterComplaintCatalogSnapshot(original) }
                assertThrows<CatalogGenesisFinalizeExceptionV1> { foreign.target.pools.catalogCoordinator.ownership.enterComplaintCatalogSnapshot(original) }
                assertThrows<PersistencePhaseException> { invocation.coordinator.genesis.completeGenesis(fresh, original.expected) }
                assertThrows<PersistencePhaseException> { invocation.coordinator.genesis.projectGenesisForProcess(fresh, original.expected) }
                assertEquals(0, invocation.coordinator.activeSnapshotOwners())
                assertEquals(0, foreign.target.pools.catalogCoordinator.activeSnapshotOwners())
                requireConnectionFree()
            }
        }
        try {
            val all = DesiredInstallationInputFixture.acquired(f.inputs, PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD.toByteArray())
            val targetSecrets = all.filter {
                targetFinalizerBindingFields(it.descriptor) != targetFinalizerBindingFields(f.inputs.operatorPassword)
            }
            foreign.assembleTargetFinalizer(f.inputs, targetSecrets, null)
            invocation.execute()
            invocation.assertFullReadback()
            invocation.assertReleased()
            assertTrue(checked)
        } finally {
            val stopped = runCatching(foreign::close)
            val retired = runCatching(invocation::fixtureCleanup)
            val foreignRetired = runCatching { foreign.requireCleanup(PersistenceTimeBudget.start(2_000)) }
            rethrowTargetFinalizeFixtureFailures(listOf(stopped, retired, foreignRetired))
        }
    }

    private fun activeCompleteExpiry(before: List<String>) {
        val invocation = f.invocation()
        var originalEntry: PersistencePhysicalEntry? = null
        invocation.beforeSql = { step ->
            assertEquals(COMPLETE, path())
            assertEquals("control", step)
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            assertOriginalBudget(invocation, phase)
            assertActualTargetHolder(invocation)
            originalEntry = checkNotNull(invocation.scope).catalogEntries().single()
            invocation.clock.extraNanos += ORIGINAL_ALLOWANCE_NANOS
        }
        refused { invocation.execute() }
        val phase = invocation.phases.last()
        assertEquals(COMPLETE, phasePath(phase))
        assertNotEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        val emergency: PersistenceTimeBudget = poolTestField(phase, "emergency")
        assertEquals(
            PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED,
            assertThrows<PersistenceBoundaryException> { emergency.remainingMillis(1) }.code,
        )
        assertTrue(checkNotNull(originalEntry).retirementRequested.get())
        assertTrue(checkNotNull(invocation.scope).root.shutdown.get())
        val files = releaseFiles(invocation)
        if (!files.cleanupComplete()) {
            val custody: CatalogGenesisReleaseCustodyV1 = poolTestField(invocation.release, "custody")
            assertFalse(poolTestField<Boolean>(custody, "closeIssued"))
        }
        invocation.fixtureCleanup() // Same original retirement, not on-time production cleanup or a successful stage result.
        assertTrue(files.cleanupComplete())
        assertTrue(checkNotNull(originalEntry).scopeEnded && checkNotNull(checkNotNull(originalEntry).terminalWork).bodyExited())
        assertEquals(before, f.state())
        assertFalse(f.exists(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME))
        assertSticky(invocation)
    }

    private fun armCompletionFault(invocation: CatalogGenesisTargetFinalizeInvocation, fault: CompletionFault, cut: DesiredInstallationCompletionCut) {
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val operation = retainedOperation(phase)
        fault.operation = operation
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun beforeCommit(readOnly: Boolean) = invocation.preserveAssertions {
                assertFalse(readOnly)
                assertTrue(operation.completedFor(phase))
                assertProjectionRefused(operation, PersistenceDatabaseOutcome.NONE, cleanup = false)
                fault.beforeCommit = true
            }

            override fun afterCommit() = invocation.preserveAssertions {
                assertProjectionRefused(operation, PersistenceDatabaseOutcome.COMMITTED, cleanup = false)
                fault.afterCommit = true
                if (cut == DesiredInstallationCompletionCut.UNRESOLVED_RELEASE) {
                    TransactionSynchronizationManager.bindResource(fault.key, fault.sentinel)
                    fault.bound = true
                }
                error("Synthetic TARGET projection completion-tail canary.")
            }
        })
    }

    private fun assertDurableBarrier(invocation: CatalogGenesisTargetFinalizeInvocation, fresh: CatalogDualLocationVerifier.GenesisReadback) {
        requireConnectionFree()
        assertSame(fresh, retainedReadback(invocation))
        assertEquals(f.retainUntil.epochSecond, fresh.retainUntilEpochSecond)
        assertDurableLeaf(CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE, fresh.primaryEvidenceBytes())
        assertDurableLeaf(CatalogGenesisReleaseLeafV1.SECONDARY_READBACK_EVIDENCE, fresh.replicaEvidenceBytes())
        val target = checkNotNull(invocation.target)
        val allocationHash = Sha256.hex(Files.readAllBytes(f.freeze.releaseRoot.resolve("genesis/allocation")))
        val targetBinding = record(
            "finalize-target",
            allocationHash,
            Sha256.hex(target.canonicalBytes()),
            Sha256.hex(target.consumers.journalConfiguration.canonicalBytes()),
            Sha256.hex(target.consumers.capacityPolicy.canonicalBytes()),
            Sha256.hex(f.envelope),
        )
        assertDurableLeaf(
            CatalogGenesisReleaseLeafV1.FINALIZE_ARMED,
            record(
                "finalize-armed",
                allocationHash,
                Sha256.hex(targetBinding),
                fresh.envelopeSha256,
                fresh.objectVersion,
                f.retainUntil.epochSecond.toString(),
                Sha256.hex(fresh.primaryEvidenceBytes()),
                Sha256.hex(fresh.replicaEvidenceBytes()),
            ),
        )
    }

    private fun assertOutcome() {
        requireConnectionFree()
        val allocationHash = Sha256.hex(Files.readAllBytes(f.freeze.releaseRoot.resolve("genesis/allocation")))
        assertDurableLeaf(
            CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME,
            record("finalized", allocationHash, Sha256.hex(f.freeze.read(CatalogGenesisReleaseLeafV1.FINALIZE_ARMED))),
        )
    }

    private fun assertDurableLeaf(leaf: CatalogGenesisReleaseLeafV1, expected: ByteArray) {
        assertArrayEquals(expected, f.freeze.read(leaf))
        assertEquals(PosixFilePermissions.fromString("r--------"), Files.getPosixFilePermissions(f.freeze.leafPath(leaf), NOFOLLOW_LINKS))
        val complete = ByteBuffer.allocate(68).putInt(expected.size).put(Sha256.hex(expected).toByteArray(Charsets.US_ASCII)).array()
        assertArrayEquals(complete, Files.readAllBytes(f.completeness(leaf)))
    }

    private fun assertActualTargetHolder(invocation: CatalogGenesisTargetFinalizeInvocation): Int {
        val coordinator = invocation.coordinator
        val holder = TransactionSynchronizationManager.getResource(coordinator.dataSource) as ConnectionHolder
        assertEquals(setOf(coordinator.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(1, coordinator.activeSnapshotOwners())
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, holder.connection.transactionIsolation)
        assertFalse(holder.connection.isReadOnly)
        return holder.connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT pg_backend_pid(), session_user, current_user, current_database(), " +
                    "(SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()), " +
                    "EXISTS (SELECT 1 FROM pg_locks WHERE pid = pg_backend_pid() AND locktype = 'advisory' AND mode = 'ShareLock' AND granted " +
                    "AND classid = ((hashtextextended('complaint-journal-epoch', 0) >> 32) & 4294967295)::oid " +
                    "AND objid = (hashtextextended('complaint-journal-epoch', 0) & 4294967295)::oid AND objsubid = 1)",
            ).use { row ->
                assertTrue(row.next())
                assertEquals(PgLifecycleDatabaseSettings.CANDIDATE, row.getString(2))
                assertEquals(PgLifecycleDatabaseSettings.CANDIDATE, row.getString(3))
                assertEquals(PgLifecycleDatabaseSettings.DATABASE, row.getString(4))
                assertTrue(row.getBoolean(5) && row.getBoolean(6) && !row.wasNull())
                row.getInt(1).also { assertFalse(row.next()) }
            }
        }
    }

    private fun assertOriginalBudget(invocation: CatalogGenesisTargetFinalizeInvocation, phase: PersistencePhaseContext) {
        val attempt = invocation.attempt
        assertSame(attempt, poolTestField<CatalogGenesisFinalizeAttemptV1>(phase, "catalogFinalizerAttempt"))
        assertSame(invocation.operator.budget, attempt.budget)
        val work: PersistenceTimeBudget = poolTestField(phase, "catalogFinalizerWork")
        assertSame(work, poolTestField<PersistenceTimeBudget>(phase, "work"))
        assertSame(attempt.phaseBudget, poolTestField<PersistenceTimeBudget>(work, "parent"))
        assertSame(attempt.budget, poolTestField<PersistenceTimeBudget>(attempt.phaseBudget, "parent"))
    }

    private fun assertTargetSecretInventory(invocation: CatalogGenesisTargetFinalizeInvocation) {
        val actual = invocation.secrets.requests.map { it.fields().let { fields -> fields["SecretId"] to fields["VersionId"] } }
        val expected = f.inputs.targetBindings().map { it.version.resourceArn to it.version.versionId }
        assertEquals(expected, actual)
        assertFalse(actual.any { it.first == f.request.frozen.database.authenticationPassword.version.resourceArn })
        assertFalse(actual.any { it.first == f.inputs.operatorPassword.version.resourceArn })
        val authorProvenance = f.freeze.read(CatalogGenesisReleaseLeafV1.PUBLIC_TARGET_BINDINGS).decodeToString()
        assertTrue(authorProvenance.contains(f.request.frozen.database.authenticationPassword.version.resourceArn))
        assertFalse(authorProvenance.contains(f.inputs.runtimePassword.version.resourceArn))
        assertNull(ownedCutField(invocation.assembly, "operatorOwner"))
    }

    private fun assertSticky(invocation: CatalogGenesisTargetFinalizeInvocation) {
        requireConnectionFree()
        val sql = invocation.sql.toList()
        val secrets = invocation.secrets.requests.size
        val http = invocation.http.requests.size
        val state = f.state()
        refused { invocation.execute() }
        val target = invocation.target
        if (target != null) {
            val active: AtomicReference<*> = poolTestField(target.pools.catalogCoordinator.catalogRefreshCustody, "active")
            val originalSlot = checkNotNull(active.get())
            assertSame(poolTestField<Any>(invocation.attempt, "refresh"), originalSlot, "The original failed refresh slot remains retained.")
            // This is retired-graph constructor refusal, not evidence that a replacement reached live slot reservation.
            val retiredGraph = assertThrows<PersistenceBoundaryException> {
                CurrentAcceptedCatalogRefreshV1.withHttpFixture(
                    target,
                    S3CatalogReadbackFixture.credentials,
                    S3CatalogReadbackFixture.credentials,
                    { error("A retired graph must not create a replacement provider.") },
                    invocation.wallClock,
                )
            }
            assertEquals(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED, retiredGraph.code)
            assertSame(originalSlot, active.get())
        }
        assertEquals(sql, invocation.sql)
        assertEquals(secrets, invocation.secrets.requests.size)
        assertEquals(http, invocation.http.requests.size)
        assertEquals(state, f.state())
    }

    private fun assertPhases(invocation: CatalogGenesisTargetFinalizeInvocation, expected: List<PersistencePhasePath>) {
        assertEquals(expected, invocation.phases.map(::phasePath))
        invocation.phases.forEach { assertOriginalBudget(invocation, it) }
    }

    private fun assertCommittedAndReleased(phase: PersistencePhaseContext) {
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        assertTrue(phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
        assertFalse(phase.quarantined())
    }

    private fun assertHistory(expected: GenesisResume) {
        val predicate = when (expected) {
            GenesisResume.PREPARED -> "state = 'PREPARED' AND completed_at IS NULL AND projected_at IS NULL AND object_version IS NULL"
            GenesisResume.PROJECTION_PENDING -> "state = 'COMPLETED' AND completed_at IS NOT NULL AND projected_at IS NULL AND object_version IS NOT NULL"
            GenesisResume.PROJECTED_REPLAY -> "state = 'COMPLETED' AND completed_at IS NOT NULL AND projected_at IS NOT NULL AND object_version IS NOT NULL"
        }
        assertEquals(
            true,
            f.observer.queryForObject("SELECT $predicate FROM complaint_catalog_mutations WHERE operation_token = ?", Boolean::class.java, f.token),
        )
    }

    private fun assertProjected() {
        assertHistory(GenesisResume.PROJECTED_REPLAY)
        assertEquals(
            true,
            f.observer.queryForObject(
                "SELECT maintenance_closed AND creation_closed AND publication_epoch = 1 AND desired_generation = 1 " +
                    "AND desired_configuration_hash = ? AND accepted_catalog_generation = 1 AND accepted_catalog_hash = ? " +
                    "AND trust_bundle_hash = ? AND catalog_writer_generation = ? AND pending_projection_token IS NULL " +
                    "AND database_identity = ? AND restore_identity = ? AND event_writer_generation = ? " +
                    "FROM complaint_journal_control WHERE data_scope_id = ?",
                Boolean::class.java, f.selectedHash, hash(f.envelope), hash(f.freeze.current), UUID.fromString(f.freeze.manifest.catalogWriterGenerationId),
                f.inputs.databaseIdentity, f.inputs.restoreIdentity,
                UUID.fromString(
                    f.inputs.journal.declaration().writer.generationId,
                ),
                ComplaintDataScope.LIVE.id,
            ),
        )
    }

    private fun preservedControl(): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT (to_jsonb(c) - ARRAY['accepted_catalog_generation','accepted_catalog_hash','trust_bundle_hash','catalog_writer_generation'," +
                "'pending_projection_token','database_identity','restore_identity','event_writer_generation','updated_at'])::text " +
                "FROM complaint_journal_control c WHERE data_scope_id = ?",
            String::class.java,
            ComplaintDataScope.LIVE.id,
        ),
    )

    private fun frozenMutation(): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT (to_jsonb(m) - ARRAY['state','completed_at','projected_at','object_version','retain_until','primary_evidence_bytes'," +
                "'primary_evidence_hash','replica_evidence_bytes','replica_evidence_hash'])::text FROM complaint_catalog_mutations m WHERE operation_token = ?",
            String::class.java,
            f.token,
        ),
    )

    private fun completionTime(): String = checkNotNull(
        f.observer.queryForObject("SELECT completed_at::text FROM complaint_catalog_mutations WHERE operation_token = ?", String::class.java, f.token),
    )

    private fun stableEvidence(): Map<CatalogGenesisReleaseLeafV1, ByteArray> = listOf(
        CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE,
        CatalogGenesisReleaseLeafV1.SECONDARY_READBACK_EVIDENCE,
        CatalogGenesisReleaseLeafV1.FINALIZE_ARMED,
    ).associateWith(f.freeze::read)

    private fun assertStable(expected: Map<CatalogGenesisReleaseLeafV1, ByteArray>) = expected.forEach { (leaf, bytes) -> assertDurableLeaf(leaf, bytes) }

    private fun withMissingFixtureEntry(entry: Path, invocation: CatalogGenesisTargetFinalizeInvocation, work: () -> Unit) {
        val held = f.freeze.parent.resolve("held-${entry.fileName}")
        Files.move(entry, held) // Test-only absence; never reopen the lock or let production restore this entry.
        val result = runCatching(work)
        val retired = runCatching(invocation::fixtureCleanup)
        val restored = runCatching {
            requireConnectionFree()
            assertTrue(invocation.cleanupVerified, "The same original owner must retire before the test restores the removed entry.")
            f.assertNoTargetSessions()
            Files.move(held, entry) // Undo only negative-fixture setup after actual original owner retirement.
        }
        rethrowTargetFinalizeFixtureFailures(listOf(result, retired, restored))
    }

    private fun withNullDOnCompletionHead(work: () -> Unit) {
        val name = "kira_finalizer_reread_${UUID.randomUUID().toString().replace("-", "")}"
        f.desired.independentTransaction { connection, jdbc ->
            jdbc.execute(
                "CREATE FUNCTION public.$name() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " +
                    "IF session_user = '${PgLifecycleDatabaseSettings.CANDIDATE}' AND NEW.pending_projection_token IS NOT NULL " +
                    "AND OLD.pending_projection_token IS NULL THEN NEW.desired_configuration_hash := NULL; END IF; RETURN NEW; END; $$",
            )
            jdbc.execute("CREATE TRIGGER $name BEFORE UPDATE ON complaint_journal_control FOR EACH ROW EXECUTE FUNCTION public.$name()")
            connection.commit()
        }
        try {
            work() // Actual same-transaction corruption, without adding D-write privilege or returning fabricated JDBC data.
        } finally {
            dropFixtureTrigger(name)
        }
    }

    private fun withDeferredProjectionCommit(cut: DesiredInstallationCompletionCut, work: () -> Unit) {
        if (cut != DesiredInstallationCompletionCut.DEFERRED_COMMIT) {
            work()
            return
        }
        val name = "kira_finalizer_commit_${UUID.randomUUID().toString().replace("-", "")}"
        f.desired.independentTransaction { connection, jdbc ->
            jdbc.execute(
                "CREATE FUNCTION public.$name() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " +
                    "IF session_user = '${PgLifecycleDatabaseSettings.CANDIDATE}' THEN " +
                    "RAISE EXCEPTION 'Synthetic TARGET deferred-commit canary.' USING ERRCODE = '23000'; END IF; RETURN NEW; END; $$",
            )
            jdbc.execute(
                "CREATE CONSTRAINT TRIGGER $name AFTER UPDATE ON complaint_journal_control DEFERRABLE INITIALLY DEFERRED FOR EACH ROW " +
                    "WHEN (OLD.pending_projection_token IS NOT NULL AND NEW.pending_projection_token IS NULL) EXECUTE FUNCTION public.$name()",
            )
            connection.commit()
        }
        try {
            work()
        } finally {
            dropFixtureTrigger(name)
        }
    }

    private fun dropFixtureTrigger(name: String) {
        requireConnectionFree()
        f.desired.independentTransaction { connection, jdbc ->
            jdbc.execute("DROP TRIGGER $name ON complaint_journal_control")
            jdbc.execute("DROP FUNCTION public.$name()")
            connection.commit()
        }
    }

    private fun assertProjectionRefused(operation: CatalogGenesisMutationOperation, expected: PersistenceDatabaseOutcome, cleanup: Boolean) {
        val refused = assertThrows<PersistencePhaseException> { operation.processBoundProjection }
        assertEquals(expected, refused.databaseOutcome)
        assertEquals(cleanup, refused.cleanupProven)
    }

    private fun retainedOperation(phase: PersistencePhaseContext): CatalogGenesisMutationOperation = poolTestField(phase.catalogGenesis, "retained")
    private fun retainedReadback(invocation: CatalogGenesisTargetFinalizeInvocation): CatalogDualLocationVerifier.GenesisReadback? =
        (ownedCutField(invocation.operator, "attempt") as? CatalogGenesisFinalizeAttemptV1)?.let {
            ownedCutField(it, "readback") as? CatalogDualLocationVerifier.GenesisReadback
        }

    private fun releaseFiles(invocation: CatalogGenesisTargetFinalizeInvocation): LinuxGenesisReleaseFilesV1 =
        poolTestField(poolTestField<CatalogGenesisReleaseCustodyV1>(invocation.release, "custody"), "files")

    private fun phasePath(phase: PersistencePhaseContext): PersistencePhasePath = poolTestField(phase, "path")
    private fun path(): PersistencePhasePath = phasePath(checkNotNull(PersistencePhaseOwnership.current()))
    private fun outcome(cut: DesiredInstallationCompletionCut): PersistenceDatabaseOutcome =
        if (cut == DesiredInstallationCompletionCut.DEFERRED_COMMIT) PersistenceDatabaseOutcome.UNKNOWN else PersistenceDatabaseOutcome.COMMITTED

    private fun record(kind: String, vararg values: String): ByteArray =
        Json.encodeToString(ListSerializer(String.serializer()), listOf("catalog-genesis-freeze-v1", kind) + values).toByteArray()

    private fun hash(bytes: ByteArray): ByteArray = HexFormat.of().parseHex(Sha256.hex(bytes))
    private fun refused(work: () -> Unit): CatalogGenesisFinalizeExceptionV1 = assertThrows<CatalogGenesisFinalizeExceptionV1> { work() }.also {
        assertEquals("Catalog genesis finalization refused: ${it.code.name}.", it.message)
        assertNull(it.cause)
        assertTrue(it.suppressed.isEmpty())
        assertFalse(it.message.orEmpty().contains(PRIVATE_CLOSE_CANARY))
    }

    private class CompletionFault {
        var operation: CatalogGenesisMutationOperation? = null
        var beforeCommit = false
        var afterCommit = false
        val key = Any()
        val sentinel = Any()
        var bound = false
        fun removeSentinel() {
            if (bound) {
                assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
                bound = false
            }
        }
    }

    companion object {
        private val SNAPSHOT = PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT
        private val COMPLETE = PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE
        private val PROJECT = PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT
        private const val ORIGINAL_ALLOWANCE_NANOS = 60_000_000_000L
        private const val PRIVATE_CLOSE_CANARY = "synthetic-private-target-close-diagnostic"
    }
}
