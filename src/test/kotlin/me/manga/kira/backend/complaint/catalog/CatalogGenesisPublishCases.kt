package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPoolPreparation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.PoolLifecycle
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentDocumentV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.util.HexFormat
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Genuine freeze/first-D/current recheck, raw SDK and Linux custody. Synthetic fixtures are not cloud, approval or crash-durability qualification. */
internal class CatalogGenesisPublishCases(private val f: CatalogGenesisPublishFixture) {
    fun frozenFirstDRecheckAndSinglePrimaryPublication() {
        val before = f.state()
        val invocation = f.invocation()
        var lockedWork = false
        var putClosed = false
        var retainedReader: PersistenceTimeBudget? = null
        invocation.beforePhaseWork = { phase ->
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) = invocation.preserveAssertions {
                    assertFalse(readOnly)
                    assertOriginalBudget(invocation, phase)
                    assertRecheckLocks(invocation)
                    assertTrue(f.http.read.requests.isEmpty() && f.http.put.requests.isEmpty())
                    assertNull(ownedCutField(poolTestField<Any>(invocation.operator, "release"), "retainedArm"))
                    lockedWork = true
                }
            })
        }
        f.http.replicateOnPut = true
        f.http.beforePut = {
            assertTrue(lockedWork)
            assertReleasedRecheck(invocation)
            assertColdTarget(invocation)
            assertEmptyProbesClosed()
            // This callback is entered only AFTER the actual arm method returned from force/close/reread, not on mere sidecar appearance.
            assertTrue(f.complete(ARM))
            assertArm(invocation)
            assertFalse(f.exists(OUTCOME))
        }
        f.http.afterPutClientClose = {
            assertFalse(f.exists(OUTCOME), "The actual lower's cleanup must return before an ACK outcome is persisted.")
            putClosed = true
        }
        f.http.beforeRead = {
            val current: PersistenceTimeBudget = poolTestField(invocation.operator, "readerBudget")
            assertSame(invocation.operator.budget, poolTestField<PersistenceTimeBudget>(current, "parent"))
            if (retainedReader == null) retainedReader = current else assertSame(retainedReader, current)
            if (f.http.put.requests.isNotEmpty()) {
                assertTrue(putClosed)
                assertTrue(f.complete(OUTCOME))
            }
        }
        assertEquals(CatalogGenesisPublishStateV1.DUAL_COPY_OBSERVED, invocation.execute().state)
        invocation.assertReleased()
        assertReleasedRecheck(invocation)
        assertSinglePut()
        assertOutcome("publish-acknowledged")
        assertGenuineCopies(invocation)
        assertSecretInventory(invocation)
        assertEquals(before, f.state(), "Publication cannot COMPLETE, PROJECT, select D or charge counters.")
        f.assertUnchangedFreeze()
    }

    fun currentSelectedStateAndActualGraphRequired() {
        val original = f.state()
        for (change in listOf(
            "desired_configuration_hash = NULL",
            "desired_configuration_hash = decode(repeat('ab', 32), 'hex')",
            "desired_generation = 2",
            "publication_epoch = 2",
        )) {
            // Privileged synthetic control changes are negative setup only, never a supplied selection receipt.
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET $change WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id))
            val changed = f.state()
            val invocation = f.invocation()
            refused(CatalogGenesisPublishFailureV1.STATE_REFUSED) { invocation.execute() }
            invocation.fixtureCleanup()
            assertTrue(invocation.phases.isNotEmpty(), "A previously selected first-D result must not bypass the current locked recheck.")
            assertNoPublication()
            assertEquals(changed, f.state())
            f.selected.desired.restoreControl(f.selected.selectedControl)
        }
        val document = f.selected.document.copy(database = f.selected.document.database.copy(ordinaryCapacity = 3))
        val path = f.freeze.writeInput(
            "different-actual-cold-target.json",
            Json.encodeToString(ComplaintDesiredDeploymentDocumentV1.serializer(), document).toByteArray(),
        )
        val different = f.invocation()
        refused(CatalogGenesisPublishFailureV1.STATE_REFUSED) { different.execute(request = f.request(targetDeployment = path)) }
        different.fixtureCleanup()
        assertFalse(f.selected.selectedHash.contentEquals(checkNotNull(different.target).configurationHashBytes()))
        assertSecretInventory(different)
        assertNoPublication()
        val badPassword = f.invocation().apply { operatorPasswordOverride = "synthetic-wrong-config-operator-password".toByteArray() }
        refused { badPassword.execute() } // Wrong acquired material on the actual fixed-login route, not a supplied authentication Boolean or SET ROLE.
        badPassword.fixtureCleanup()
        assertSecretInventory(badPassword)
        // The original stock-pool warm-up retains its ticket after retirement; a live ledger is not invocation history.
        // These are warm-up entry/refusal facts, not an assertion of a particular native SQLSTATE or SCRAM diagnostic.
        val warmup: CatalogCoordinatorPoolPreparation = poolTestField(checkNotNull(badPassword.coordinator).dataSource, "catalogPreparation")
        val initialization: PoolLifecycle.Acquisition = poolTestField(warmup, "initialization")
        assertTrue(initialization.entered())
        assertTrue(initialization.actualFrameEnded())
        val warmupState: AtomicReference<PersistenceLifecycleObservation> = poolTestField(warmup, "state")
        assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, warmupState.get())
        assertTrue(badPassword.scopes.getValue("operatorOwner").catalogEntries().isEmpty())
        assertTrue(badPassword.phases.isEmpty())
        assertNoPublication()
        assertTrue(f.http.read.requests.isEmpty())
        assertEquals(original, f.state())
        f.assertUnchangedFreeze()
    }

    fun independentPinExistingCustodyAndEmptyProbeRequired() {
        val before = f.state()
        val missingPin = f.invocation()
        refused(CatalogGenesisPublishFailureV1.INPUT_REFUSED) { missingPin.execute(request = f.request(frozen = f.freeze.request())) }
        missingPin.fixtureCleanup()
        assertTrue(missingPin.secrets.requests.isEmpty())
        val pin = f.freeze.writeInput("different-independent-publisher-pin", "0".repeat(64).toByteArray())
        val wrong = f.invocation()
        refused { wrong.execute(request = f.request(frozen = f.freeze.request(pin))) }
        wrong.fixtureCleanup()
        assertTrue(wrong.secrets.requests.isEmpty())
        val bare = Files.createDirectory(
            f.freeze.parent.resolve("bare-publisher-release"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        val absent = f.freeze.parent.resolve("absent-publisher-release")
        for (root in listOf(bare, absent)) {
            val missing = f.invocation()
            refused { missing.execute(request = f.request(frozen = f.freeze.request(f.request.frozen.independentPin, root))) }
            missing.fixtureCleanup()
            assertTrue(missing.secrets.requests.isEmpty())
        }
        assertTrue(Files.list(bare).use { it.findAny().isEmpty }, "No new allocation or permanent lock may be created by publisher recovery.")
        assertFalse(Files.exists(absent, NOFOLLOW_LINKS))
        assertTrue(f.http.read.requests.isEmpty())
        f.http.primaryVersion = CatalogGenesisPublishHttpFixture.VERSION
        val occupied = f.invocation()
        refused { occupied.execute() }
        occupied.fixtureCleanup()
        assertTrue(f.http.read.requests.isNotEmpty())
        assertNoPublication()
        f.assertUnchangedFreeze()
        val reads = f.http.read.requests.size

        // Deliberately corrupt the fixture signature, its checksum sidecar and the preceding signature-hash comparison record.
        // Raw signature verification must refuse before subsequent envelope comparisons. Never repair/reuse these fixture leaves.
        corruptCompleteFixtureSignature()
        val signature = f.invocation()
        refused { signature.execute() }
        signature.fixtureCleanup()
        assertTrue(signature.secrets.requests.isEmpty())
        assertEquals(reads, f.http.read.requests.size)
        Files.delete(f.selected.completeness(CatalogGenesisReleaseLeafV1.PIN))
        val partial = f.invocation()
        refused { partial.execute() }
        partial.fixtureCleanup()
        assertFalse(Files.exists(f.selected.completeness(CatalogGenesisReleaseLeafV1.PIN), NOFOLLOW_LINKS))
        assertTrue(partial.secrets.requests.isEmpty())
        assertEquals(reads, f.http.read.requests.size)
        assertNoPublication()
        assertEquals(before, f.state())
    }

    fun recheckCompletionAndNamespaceCloseAreBarriers() {
        val before = f.state()
        val commit = f.invocation()
        var reached = false
        commit.beforePhaseWork = {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    reached = true
                    throw IOException(PRIVATE)
                }
            })
        }
        refused { commit.execute() }
        assertTrue(reached)
        commit.fixtureCleanup()
        assertTrue(f.http.read.requests.isEmpty())
        assertNoPublication()
        // A real afterCommit callback was reached; its exception is not, by itself, proof of a released COMMITTED phase.
        val late = f.invocation()
        f.http.afterReadPrepared = { late.clock.extraNanos += 60_000_000_000L }
        refused { late.execute() }
        f.http.afterReadPrepared = {}
        val uncalled = f.http.read.replies.single()
        assertEquals(1, uncalled.aborts, "The exact native request returned before expiry must remain retained for actual abort.")
        assertEquals(0, uncalled.calls)
        assertEquals(0, uncalled.reads)
        assertEquals(0, uncalled.closes, "No response body was returned; client close is not a substitute for request abort.")
        assertEquals(f.http.read.createdClients, f.http.read.closedClients)
        late.fixtureCleanup()
        assertNoPublication()
        var closed = 0
        f.http.afterReadClientClose = {
            closed++
            throw IOException(PRIVATE)
        }
        val namespace = f.invocation()
        refused(CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN) { namespace.execute() }
        f.http.afterReadClientClose = {}
        namespace.fixtureCleanup()
        assertTrue(closed > 0)
        assertReleasedRecheck(namespace)
        assertNoPublication()
        assertEquals(before, f.state())
        f.assertUnchangedFreeze()
    }

    fun durableArmCancellationAndEmptyRecoveryNeverReput() {
        val before = f.state()
        val invocation = f.invocation()
        var crossedDurableArm = false
        f.http.beforePut = {
            assertReleasedRecheck(invocation)
            assertEmptyProbesClosed()
            assertTrue(f.complete(ARM))
            crossedDurableArm = true
            throw CancellationException(PRIVATE)
        }
        assertSanitized(assertThrows<CancellationException> { invocation.execute() })
        f.http.beforePut = {}
        invocation.fixtureCleanup()
        assertTrue(crossedDurableArm)
        assertTrue(f.http.put.requests.isEmpty(), "The cut is before the real native call, but its durable arm still spends eligibility.")
        assertFalse(f.exists(OUTCOME))
        val arm = f.read(ARM)
        val clients = f.http.put.createdClients
        for (explicitRecovery in listOf(false, true)) {
            val recovery = f.invocation()
            refused(CatalogGenesisPublishFailureV1.RECOVERY_REQUIRED) { recovery.execute(recover = explicitRecovery) }
            recovery.fixtureCleanup()
            assertArrayEquals(arm, f.read(ARM))
            assertFalse(f.exists(OUTCOME))
            assertNoCopies()
            assertEquals(clients, f.http.put.createdClients, "Neither publish(existing arm) nor recover may construct another PUT client.")
        }
        assertTrue(f.http.put.requests.isEmpty())
        assertEquals(before, f.state())
        assertOriginalSignCount()
        f.assertUnchangedFreeze()
    }

    fun readerCapAtReturnedPutClientNeverDispatchesOrReputs() {
        val before = f.state()
        val invocation = f.invocation()
        var returnedClient: SdkHttpClient? = null
        var nativePrepares = 0
        f.http.beforePut = { nativePrepares++ }
        f.http.afterPutClientCreated = { client ->
            assertReleasedRecheck(invocation)
            assertColdTarget(invocation)
            assertEmptyProbesClosed()
            val settings = checkNotNull(checkNotNull(invocation.target).catalogReadback)
            assertEquals(10_000L, settings.totalAttemptMillis, "The genuine first-D selected this declared reader cap, not a test-installed D.")
            val reader: PersistenceTimeBudget = poolTestField(invocation.operator, "readerBudget")
            assertSame(invocation.operator.budget, poolTestField<PersistenceTimeBudget>(reader, "parent"))
            assertTrue(f.complete(ARM)) // The actual durable arm method already returned before this original client factory was called.
            assertArm(invocation)
            assertFalse(f.exists(OUTCOME))
            returnedClient = client
            invocation.clock.extraNanos += settings.totalAttemptMillis * 1_000_000L
            assertTrue(
                invocation.operator.budget.remainingMillis(1) > 0,
                "Only the stricter reader cap is exhausted; the original 60-second budget remains live.",
            )
            // Return the actual client normally. Production must retain it before its post-construction cap check can throw.
        }
        try {
            refused { invocation.execute() }
        } finally {
            f.http.afterPutClientCreated = {}
            f.http.beforePut = {}
        }
        assertSame(checkNotNull(returnedClient), poolTestField<SdkHttpClient>(poolTestField<Any>(invocation.operator, "putHttp"), "native"))
        assertEquals(1, f.http.put.createdClients)
        assertEquals(1, f.http.put.closedClients, "The exact returned client must close once despite the reader-only expiry.")
        assertEquals(0, nativePrepares)
        assertTrue(f.http.put.requests.isEmpty() && f.http.put.replies.isEmpty() && f.http.bodies.isEmpty())
        assertEquals(2, f.http.read.requests.size, "No diagnostic readback is allowed after the expired returned-client cut.")
        val reader: PersistenceTimeBudget = poolTestField(invocation.operator, "readerBudget")
        assertEquals(
            PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED,
            assertThrows<PersistenceBoundaryException> { reader.remainingMillis(1) }.code,
        )
        assertTrue(invocation.operator.budget.remainingMillis(1) > 0)
        invocation.fixtureCleanup()
        assertSecretInventory(invocation)
        assertTrue(f.complete(ARM))
        assertFalse(f.exists(OUTCOME))
        assertNoCopies()
        val arm = f.read(ARM)
        val recovery = f.invocation()
        refused(CatalogGenesisPublishFailureV1.RECOVERY_REQUIRED) { recovery.execute(recover = true) }
        recovery.fixtureCleanup()
        assertArrayEquals(arm, f.read(ARM))
        assertFalse(f.exists(OUTCOME))
        assertNoCopies()
        assertEquals(1, f.http.put.createdClients, "Fresh read-only recovery cannot refund the already-returned durable arm.")
        assertEquals(1, f.http.put.closedClients)
        assertTrue(f.http.put.requests.isEmpty() && f.http.put.replies.isEmpty() && f.http.bodies.isEmpty())
        assertEquals(before, f.state())
        assertOriginalSignCount()
        f.assertUnchangedFreeze()
    }

    fun lostAcknowledgementAndReplicationRecoveryAreStable() {
        val before = f.state()
        val first = f.invocation()
        f.http.afterPutAccepted = { throw IOException(PRIVATE) }
        refused { first.execute() }
        f.http.afterPutAccepted = {}
        first.fixtureCleanup()
        assertTrue(f.complete(ARM))
        assertFalse(f.exists(OUTCOME))
        assertEquals(1, f.http.put.replies.single().calls)
        assertEquals(CatalogGenesisPublishHttpFixture.VERSION, f.http.primaryVersion)
        assertEquals(0, f.http.put.replies.single().closes, "No response body returned from the deliberately lost acknowledgement.")
        assertEquals(1, f.http.put.replies.single().aborts)
        val arm = f.read(ARM)
        recover(CatalogGenesisPublishStateV1.AWAIT_REPLICATION)
        assertOutcome("publish-readback-recovered")
        assertNoCopies()
        val outcome = f.read(OUTCOME)
        f.http.primaryReplication = "COMPLETED"
        recover(CatalogGenesisPublishStateV1.AWAIT_REPLICATION)
        assertArrayEquals(outcome, f.read(OUTCOME))
        assertNoCopies()
        f.http.completeReplication()
        val dual = recover(CatalogGenesisPublishStateV1.DUAL_COPY_OBSERVED)
        assertGenuineCopies(dual)
        val primary = f.read(PRIMARY)
        val replica = f.read(REPLICA)
        recover(CatalogGenesisPublishStateV1.DUAL_COPY_OBSERVED)
        assertArrayEquals(primary, f.read(PRIMARY))
        assertArrayEquals(replica, f.read(REPLICA))
        assertArrayEquals(outcome, f.read(OUTCOME))
        assertArrayEquals(arm, f.read(ARM))
        assertSinglePut()
        assertOriginalSignCount()
        assertEquals(before, f.state())
        f.assertUnchangedFreeze()
    }

    fun acknowledgedOutcomeSurvivesCancelledReadbackAndConflicts() {
        val before = f.state()
        val first = f.invocation()
        var outcomeBeforeRead = false
        f.http.beforeRead = {
            if (f.http.put.requests.isNotEmpty()) {
                assertTrue(f.complete(OUTCOME))
                outcomeBeforeRead = true
                throw CancellationException(PRIVATE)
            }
        }
        try {
            assertSanitized(assertThrows<CancellationException> { first.execute() })
            assertFalse(Thread.currentThread().isInterrupted)
        } finally {
            f.http.beforeRead = {}
        }
        first.fixtureCleanup()
        assertTrue(outcomeBeforeRead)
        assertOutcome("publish-acknowledged")
        assertNoCopies()
        val outcome = f.read(OUTCOME)
        f.http.primaryVersion = "different-observed-version"
        refusedRecovery(outcome)
        f.http.primaryVersion = CatalogGenesisPublishHttpFixture.VERSION
        f.http.primaryRetention = f.http.retainUntil + 1
        refusedRecovery(outcome) // Even a longer retention must not replace the frozen creation-derived instant.
        f.http.primaryRetention = f.http.retainUntil
        f.http.duplicatePrimary = true
        refusedRecovery(outcome)
        f.http.duplicatePrimary = false
        f.http.completeReplication()
        assertGenuineCopies(recover(CatalogGenesisPublishStateV1.DUAL_COPY_OBSERVED))
        assertArrayEquals(outcome, f.read(OUTCOME), "Readback must preserve an actual ACK's provenance, not replace it with recovered provenance.")
        assertSinglePut()
        assertOriginalSignCount()
        assertEquals(before, f.state())
        f.assertUnchangedFreeze()
    }

    fun originalDeadlineAndFatalPutCloseCannotAdvance() {
        val before = f.state()
        val expired = f.invocation()
        expired.clock.extraNanos = 60_000_000_000L
        refused { expired.execute() } // Expired cleanup may conservatively dominate the bounded deadline reason.
        expired.fixtureCleanup()
        assertTrue(expired.secrets.requests.isEmpty())
        assertTrue(f.http.read.requests.isEmpty())
        assertNull(ownedCutField(expired.operator, "release"))
        assertNoPublication()
        val fatal = LinkageError("synthetic publisher fatal-close sentinel")
        f.http.afterPutClientClose = { throw fatal }
        val closing = f.invocation()
        assertSame(fatal, assertThrows<LinkageError> { closing.execute() })
        f.http.afterPutClientClose = {}
        closing.fixtureCleanup() // Test-only retirement of the ORIGINAL owners, never evidence of operational process retirement.
        refused(CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN) { closing.operator.close() }
        assertTrue(f.complete(ARM))
        assertFalse(f.exists(OUTCOME))
        assertNoCopies()
        assertEquals(2, f.http.read.requests.size, "A fatal PUT close cannot progress to readback or outcome persistence.")
        assertEquals(1, f.http.put.replies.single().calls)
        recover(CatalogGenesisPublishStateV1.AWAIT_REPLICATION)
        assertOutcome("publish-readback-recovered")
        assertSinglePut()
        assertEquals(before, f.state())
        f.assertUnchangedFreeze()
    }

    private fun assertOriginalBudget(invocation: CatalogGenesisPublishInvocation, phase: PersistencePhaseContext) {
        assertSame(invocation.attempt, poolTestField<CatalogGenesisPublishAttemptV1>(phase, "catalogPublisherAttempt"))
        assertSame(invocation.operator.budget, invocation.attempt.budget)
        assertSame(invocation.target, invocation.attempt.process)
        val work: PersistenceTimeBudget = poolTestField(phase, "catalogPublisherWork")
        assertSame(work, poolTestField<PersistenceTimeBudget>(phase, "work"))
        assertSame(invocation.operator.budget, poolTestField<PersistenceTimeBudget>(work, "parent"))
        for (name in listOf("files", "putConstruction")) {
            assertSame(invocation.operator.budget, poolTestField<PersistenceTimeBudget>(poolTestField<Any>(invocation.operator, name), "budget"))
        }
    }

    private fun assertReleasedRecheck(invocation: CatalogGenesisPublishInvocation) {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        val phase = invocation.phases.single()
        assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK, poolTestField(phase, "path"))
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        assertTrue(phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
        assertEquals(1, invocation.observations.size)
        assertEquals(0, checkNotNull(invocation.coordinator).activeSnapshotOwners())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertOriginalBudget(invocation, phase)
    }

    private fun assertRecheckLocks(invocation: CatalogGenesisPublishInvocation) {
        val holder = TransactionSynchronizationManager.getResource(checkNotNull(invocation.coordinator).dataSource) as ConnectionHolder
        holder.connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT count(*) FROM pg_locks WHERE pid = pg_backend_pid() AND locktype = 'advisory' AND granted AND objsubid = 1 AND (" +
                    "(mode = 'ShareLock' AND classid = ((hashtextextended('complaint-journal-epoch', 0) >> 32) & 4294967295)::oid " +
                    "AND objid = (hashtextextended('complaint-journal-epoch', 0) & 4294967295)::oid) OR " +
                    "(mode = 'ExclusiveLock' AND classid = ((hashtextextended('complaint-catalog-mutation', 0) >> 32) & 4294967295)::oid " +
                    "AND objid = (hashtextextended('complaint-catalog-mutation', 0) & 4294967295)::oid))",
            ).use { row ->
                assertTrue(row.next())
                assertEquals(2, row.getInt(1))
                assertFalse(row.next())
            }
        }
    }

    private fun assertColdTarget(invocation: CatalogGenesisPublishInvocation) {
        val target = checkNotNull(invocation.target)
        val scope = invocation.scopes.getValue("targetOwner")
        assertArrayEquals(f.selected.selectedCanonical, target.canonicalBytes())
        assertArrayEquals(f.selected.selectedHash, target.configurationHashBytes())
        assertTrue(scope.actors().none { it.hasEntered() })
        assertTrue(scope.entries().isEmpty() && scope.entries(deletion = true).isEmpty() && scope.catalogEntries().isEmpty())
        assertFalse(actualPool(target.pools.ordinary).isRunning)
        assertFalse(actualPool(target.pools.deletion).isRunning)
        assertFalse(actualPool(target.pools.catalogCoordinator.dataSource).isRunning)
        assertFalse(scope.owner.snapshot().ordinaryReady || scope.owner.snapshot().deletionReady)
    }

    private fun assertEmptyProbesClosed() {
        requireConnectionFree()
        assertEquals(2, f.http.read.requests.size)
        assertEquals(2, f.http.read.createdClients)
        assertEquals(f.http.read.createdClients, f.http.read.closedClients)
        assertEquals(
            OfflineTrustBundleFixture.locations.map { "/${it.bucket}" }.toSet(),
            f.http.read.requests.map { it.encodedPath().trimEnd('/') }.toSet(),
        )
        f.http.read.requests.forEach {
            assertEquals(SdkHttpMethod.GET, it.method())
            assertEquals("1", it.firstMatchingRawQueryParameter("max-keys").orElseThrow())
            assertTrue(it.rawQueryParameters().containsKey("versions"))
        }
        assertTrue(f.http.read.replies.all { it.calls == 1 && it.closes == 1 && it.eofProbes > 0 })
    }

    private fun assertSinglePut() {
        val request = f.http.put.requests.single()
        val bytes = f.selected.envelope
        val credentials = CatalogGenesisPublishHttpFixture.PUT_CREDENTIALS
        assertEquals(1, f.http.put.createdClients)
        assertEquals(1, f.http.put.closedClients)
        assertEquals(1, f.http.put.replies.single().calls)
        assertEquals(SdkHttpMethod.PUT, request.method())
        assertEquals("https", request.protocol())
        assertEquals("s3.${S3CatalogReadbackFixture.primary.region}.amazonaws.com", request.host())
        assertEquals("/${S3CatalogReadbackFixture.primary.bucket}/${CatalogReadbackProtocol.key(1)}", request.encodedPath())
        assertEquals("*", request.header("If-None-Match"))
        assertEquals(S3CatalogReadbackFixture.primary.accountId, request.header("x-amz-expected-bucket-owner"))
        assertEquals(credentials.sessionToken(), request.header("x-amz-security-token"))
        assertTrue(request.header("Authorization").contains("Credential=${credentials.accessKeyId()}/"))
        assertEquals(bytes.size.toString(), request.header("Content-Length"))
        assertEquals(Sha256.hex(bytes), request.header("x-amz-content-sha256"))
        assertEquals(CatalogGenesisPublishHttpFixture.checksum(bytes), request.header("x-amz-checksum-sha256"))
        assertEquals("SHA256", request.header("x-amz-sdk-checksum-algorithm"))
        assertEquals("COMPLIANCE", request.header("x-amz-object-lock-mode"))
        assertEquals(f.selected.retainUntil.toString(), request.header("x-amz-object-lock-retain-until-date"))
        assertEquals("1", request.header("amz-sdk-request").substringAfter("attempt=").substringBefore(';'))
        assertArrayEquals(bytes, f.http.bodies.single())
        assertTrue(f.http.read.requests.all { it.method() == SdkHttpMethod.GET }, "Neither read adapter has a replica write route.")
    }

    private fun assertArm(invocation: CatalogGenesisPublishInvocation) {
        val target = checkNotNull(invocation.target)
        val database = f.inputs.database
        val password = f.inputs.runtimePassword
        val binding = listOf(
            FORMAT, "publish-target", allocationHash(), HexFormat.of().formatHex(f.selected.selectedHash), Sha256.hex(f.selected.selectedCanonical),
            database.host, database.port.toString(), database.name, database.runtimeUsername, Sha256.hex(f.inputs.publicTrustPem()),
            password.logicalKeyId, password.version.resourceArn, password.version.versionId,
            Sha256.hex(target.consumers.journalConfiguration.canonicalBytes()), Sha256.hex(target.consumers.capacityPolicy.canonicalBytes()),
        )
        val expected = listOf(
            FORMAT,
            "publish-armed",
            allocationHash(),
            f.freeze.manifest.operationToken,
            f.selected.envelope.size.toString(),
            Sha256.hex(f.selected.envelope),
            String(f.read(CatalogGenesisReleaseLeafV1.PIN), Charsets.US_ASCII),
        ) + OfflineTrustBundleFixture.locations.flatMap { listOf(it.role, it.accountId, it.region, it.bucket) } + listOf(
            CatalogReadbackProtocol.key(1),
            f.freeze.manifest.creation.createdAtEpochSecond.toString(),
            f.http.retainUntil.toString(),
            "COMPLIANCE",
            "SHA256",
            CatalogGenesisPublishHttpFixture.checksum(f.selected.envelope),
            Sha256.hex(f.read(CatalogGenesisReleaseLeafV1.RETENTION_BINDINGS)),
            Sha256.hex(CanonicalJson.canonicalize(ListSerializer(String.serializer()), binding).toByteArray()),
        )
        assertEquals(expected, record(ARM))
    }

    private fun assertOutcome(kind: String) {
        assertTrue(f.complete(OUTCOME))
        assertEquals(
            listOf(
                FORMAT,
                kind,
                allocationHash(),
                Sha256.hex(f.read(ARM)),
                CatalogGenesisPublishHttpFixture.VERSION,
                Sha256.hex(f.selected.envelope),
                CatalogGenesisPublishHttpFixture.checksum(f.selected.envelope),
            ),
            record(OUTCOME),
        )
    }

    private fun assertGenuineCopies(invocation: CatalogGenesisPublishInvocation) {
        val actual: CatalogDualLocationVerifier.GenesisReadback = poolTestField(invocation.operator, "completeReadback")
        assertTrue(f.complete(PRIMARY) && f.complete(REPLICA))
        assertEquals(f.http.read.createdClients, f.http.read.closedClients)
        assertEquals(CatalogGenesisPublishHttpFixture.VERSION, actual.objectVersion)
        assertEquals(f.http.retainUntil, actual.retainUntilEpochSecond)
        assertArrayEquals(f.selected.envelope, actual.mutation().signedEnvelopeBytes)
        assertArrayEquals(actual.primaryEvidenceBytes(), f.read(PRIMARY))
        assertArrayEquals(actual.replicaEvidenceBytes(), f.read(REPLICA))
    }

    private fun assertSecretInventory(invocation: CatalogGenesisPublishInvocation) {
        val expected = f.inputs.allBindings().map { it.version.resourceArn to it.version.versionId }
        val actual = invocation.secrets.requests.map { it.fields().let { fields -> fields["SecretId"] to fields["VersionId"] } }
        assertEquals(expected.size, actual.size)
        assertEquals(expected.toSet(), actual.toSet())
        assertOriginalSignCount()
    }

    private fun assertOriginalSignCount() = assertEquals(1, f.freeze.invocations.sumOf { it.signing.requests.size })

    private fun recover(state: CatalogGenesisPublishStateV1): CatalogGenesisPublishInvocation = f.invocation().also {
        assertEquals(state, it.execute(recover = true).state)
        it.assertReleased()
        assertReleasedRecheck(it)
    }

    private fun refusedRecovery(outcome: ByteArray) {
        val invocation = f.invocation()
        refused { invocation.execute(recover = true) }
        invocation.fixtureCleanup()
        assertArrayEquals(outcome, f.read(OUTCOME))
        assertNoCopies()
        assertSinglePut()
    }

    private fun assertNoPublication() {
        assertTrue(f.http.put.requests.isEmpty())
        assertEquals(0, f.http.put.createdClients)
        assertFalse(f.exists(ARM))
        assertFalse(f.exists(OUTCOME))
        assertNoCopies()
    }

    private fun assertNoCopies() {
        assertFalse(f.exists(PRIMARY))
        assertFalse(f.exists(REPLICA))
    }

    private fun corruptCompleteFixtureSignature() {
        val leaf = CatalogGenesisReleaseLeafV1.SIGNATURE
        val bytes = f.read(leaf).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        overwriteExistingFixturePair(leaf, bytes)
        val armed = CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTENCE_ARMED
        val values = listOf(FORMAT, "signature-persistence-armed", allocationHash(), Sha256.hex(bytes))
        overwriteExistingFixturePair(armed, CanonicalJson.canonicalize(ListSerializer(String.serializer()), values).toByteArray())
        assertEquals(values, record(armed))
    }

    private fun overwriteExistingFixturePair(leaf: CatalogGenesisReleaseLeafV1, bytes: ByteArray) {
        val sidecar = ByteBuffer.allocate(68).putInt(bytes.size).put(Sha256.hex(bytes).toByteArray(Charsets.US_ASCII)).array()
        for ((path, replacement) in listOf(f.freeze.leafPath(leaf) to bytes, f.selected.completeness(leaf) to sidecar)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            Files.write(path, replacement)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"))
        }
        assertTrue(f.complete(leaf))
    }

    private fun allocationHash(): String = Sha256.hex(f.selected.frozenFiles.getValue(f.freeze.releaseRoot.resolve("genesis/allocation")))

    private fun record(leaf: CatalogGenesisReleaseLeafV1): List<String> =
        Json.decodeFromString(ListSerializer(String.serializer()), String(f.read(leaf), Charsets.UTF_8))

    private fun refused(code: CatalogGenesisPublishFailureV1? = null, action: () -> Unit) {
        val failure = assertThrows<CatalogGenesisPublishExceptionV1> { action() }
        if (code != null) assertEquals(code, failure.code)
        assertSanitized(failure)
    }

    private fun assertSanitized(failure: Throwable) {
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.toString().contains(PRIVATE))
        assertFalse(failure.toString().contains(CatalogGenesisPublishHttpFixture.VERSION))
        assertFalse(failure.toString().contains(f.freeze.releaseRoot.toString()))
    }

    private fun SdkHttpRequest.header(name: String): String = firstMatchingHeader(name).orElseThrow()

    companion object {
        private const val PRIVATE = "synthetic-private-publisher-provider-diagnostic"
        private const val FORMAT = "catalog-genesis-freeze-v1"
        private val ARM = CatalogGenesisReleaseLeafV1.PUBLISH_ARMED
        private val OUTCOME = CatalogGenesisReleaseLeafV1.PUBLISH_OUTCOME
        private val PRIMARY = CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE
        private val REPLICA = CatalogGenesisReleaseLeafV1.SECONDARY_READBACK_EVIDENCE
    }
}
