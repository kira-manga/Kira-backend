package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.CatalogGenesisAuthoringLifecycleTest
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhysicalEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisCustodyObservationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.catalog.LinuxGenesisReleaseFilesV1
import me.manga.kira.backend.complaint.infrastructure.catalog.boundedCatalogFreezeFailure
import me.manga.kira.backend.complaint.infrastructure.catalog.preferCatalogFreezeCleanup
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException

/** Real fixed-login PG, actual SDK marshalling and synthetic key signatures, actual Linux custody; never LIVE/cloud/human-approval evidence. */
internal class CatalogGenesisFreezeCases(private val f: CatalogGenesisFreezeFixture) {
    fun fixedAuthorRootAndLeastPrivilege() {
        val author = VersionBoundPersistenceConnectedFixture(f.tls.database, catalogAuthor = true)
        try {
            author.bind()
            assertTrue(author.scope.actors().none { it.hasEntered() })
            assertNotEquals(f.tls.acquired.descriptor, author.acquired.descriptor)
            author.startCatalogGenesisAuthoring()
            assertEquals(1, author.acquisitions)
            val pid = checkNotNull(
                f.observer.queryForObject(
                    "SELECT pid FROM pg_stat_activity WHERE datname = current_database() AND usename = ?",
                    Int::class.java,
                    CatalogGenesisFreezeFixture.AUTHOR,
                ),
            )
            author.observeTlsPid(pid) // Actual verify-full TLS/SCRAM session, without an unscoped author business loan.
            assertEquals(1, author.scope.catalogEntries().size)
            assertTrue(author.scope.entries().isEmpty() && author.scope.entries(deletion = true).isEmpty())
            CatalogGenesisAuthoringLifecycleTest.assertClosedRoutes(author.owner, author.scope, author.pools)
            assertFalse(actualPool(author.pools.ordinary).isRunning)
            assertFalse(actualPool(author.pools.deletion).isRunning)
            assertEquals(1, actualPool(author.pools.catalogCoordinator.dataSource).hikariPoolMXBean.totalConnections)
            assertEquals(
                true,
                f.observer.queryForObject(
                    "SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit AND NOT rolbypassrls " +
                        "AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member = r.oid) FROM pg_roles r WHERE rolname = ?",
                    Boolean::class.java,
                    CatalogGenesisFreezeFixture.AUTHOR,
                ),
            )
            for (column in listOf(
                "desired_configuration_hash",
                "desired_generation",
                "accepted_catalog_hash",
                "catalog_writer_generation",
                "publication_epoch",
            )) {
                assertColumnDenied("complaint_journal_control", column)
            }
            for (column in listOf("state", "completed_at", "projected_at", "object_version", "primary_evidence_bytes", "replica_evidence_bytes")) {
                assertColumnDenied("complaint_catalog_mutations", column)
            }
            for (right in listOf("DELETE", "TRUNCATE", "TRIGGER")) {
                assertEquals(
                    false,
                    f.observer.queryForObject(
                        "SELECT has_table_privilege(?, 'complaint_catalog_mutations', ?)",
                        Boolean::class.java,
                        CatalogGenesisFreezeFixture.AUTHOR,
                        right,
                    ),
                )
            }
        } finally {
            author.closeWith(f.tls)
        }
        assertTrue(f.invocations.isEmpty())
        assertEquals(0L, mutationCount())
    }

    fun signedAwaitingReleaseAndExactPinReuse() {
        val beforeControl = f.control()
        val first = f.invocation()
        val sqlPaths = linkedSetOf<PersistencePhasePath>()
        first.beforeSql = { step ->
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val path = poolTestField<PersistencePhasePath>(phase, "path")
            sqlPaths.add(path)
            val attempt = poolTestField<CatalogGenesisFreezeAttemptV1>(first.operator, "attempt")
            assertSame(attempt, poolTestField<CatalogGenesisFreezeAttemptV1>(phase, "catalogAuthorAttempt"))
            assertSame(poolTestField<PersistenceTimeBudget>(first.operator, "budget"), attempt.budget)
            assertTrue(exists(CatalogGenesisReleaseLeafV1.FREEZE_ARMED))
            if (step == "control") assertActualAuthorHolder(first)
            if (step == "signature") {
                assertTrue(exists(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTENCE_ARMED))
                assertArrayEquals(first.producedSignatures.single(), f.read(CatalogGenesisReleaseLeafV1.SIGNATURE))
                assertTrue(Files.isRegularFile(completenessPath(CatalogGenesisReleaseLeafV1.SIGNATURE)))
            }
        }
        first.beforeSign = {
            assertTrue(exists(CatalogGenesisReleaseLeafV1.FREEZE_PREPARED_NO_SIGNATURE))
            assertTrue(exists(CatalogGenesisReleaseLeafV1.FREEZE_SIGN_ARMED))
            assertFalse(exists(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTENCE_ARMED))
            assertUnsignedSql()
            assertFreshEmptyNamespaceCalls(first)
            val retained = poolTestField<CatalogGenesisFreezeAttemptV1>(first.operator, "attempt")
            // Even the exact retained attempt cannot enter an unscheduled phase from a connection-free provider callback.
            refused { first.coordinator.ownership.enterComplaintCatalogSnapshot(retained) }
            val copied = CatalogGenesisFreezeAttemptV1(first.operator, first.coordinator, retained.inputs, retained.budget)
            refused { copied.snapshot() } // Equal input/budget values never substitute for the original owned attempt.
            requireConnectionFree()
        }
        assertEquals("SIGNED_AWAITING_RELEASE", first.execute().state.name)
        first.assertReleased()
        assertEquals(setOf(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE, PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE), sqlPaths)
        assertEquals(
            listOf(
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE,
                PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE,
            ),
            first.phases.map { poolTestField<PersistencePhasePath>(it, "path") },
        )
        val retainedAttempt = poolTestField<CatalogGenesisFreezeAttemptV1>(first.operator, "attempt")
        first.phases.forEach { phase ->
            assertSame(retainedAttempt, poolTestField<CatalogGenesisFreezeAttemptV1>(phase, "catalogAuthorAttempt"))
            val work = poolTestField<PersistenceTimeBudget>(phase, "catalogAuthorWork")
            assertSame(retainedAttempt.budget, poolTestField<PersistenceTimeBudget>(work, "parent"))
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
        }
        assertEquals(beforeControl, f.control(), "No D, catalog head, writer identity, scan acknowledgment or COMPLETE/PROJECT is authorized.")
        val signature = first.producedSignatures.single()
        val envelope = expectedEnvelope(signature)
        assertArrayEquals(signature, f.read(CatalogGenesisReleaseLeafV1.SIGNATURE))
        assertArrayEquals(envelope, f.read(CatalogGenesisReleaseLeafV1.ENVELOPE))
        assertSignedSql(signature, envelope)
        assertFalse(exists(CatalogGenesisReleaseLeafV1.PIN))
        assertFalse(exists(CatalogGenesisReleaseLeafV1.FREEZE_OUTCOME))
        assertEquals(1L, mutationCount())
        assertEquals(listOf(1L, CatalogGenesisCapacity.storageBytes), actualCharges())
        val signedState = f.state()

        val wrongPin = f.writeInput("wrong-released-pin", "0".repeat(64).toByteArray())
        val wrong = f.invocation()
        refused { wrong.execute(resume = true, request = f.request(wrongPin)) }
        wrong.fixtureCleanup()
        assertTrue(wrong.signing.requests.isEmpty())
        assertEquals(signedState, f.state())
        assertFalse(exists(CatalogGenesisReleaseLeafV1.PIN))

        // This explicit synthetic input represents later independent pin release; computing the SHA above is not owner approval.
        val pinBytes = Sha256.hex(envelope).toByteArray(Charsets.US_ASCII)
        val releasedPin = f.writeInput("independently-released-pin", pinBytes)
        val resumed = f.invocation()
        assertEquals("FROZEN", resumed.execute(resume = true, request = f.request(releasedPin)).state.name)
        resumed.assertReleased()
        assertEquals(0, resumed.signing.createdClients)
        assertTrue(resumed.signing.requests.isEmpty())
        assertArrayEquals(pinBytes, f.read(CatalogGenesisReleaseLeafV1.PIN))
        assertTrue(exists(CatalogGenesisReleaseLeafV1.FREEZE_PIN_COMMITMENT_ARMED))
        assertTrue(exists(CatalogGenesisReleaseLeafV1.FREEZE_OUTCOME))
        assertArrayEquals(signature, f.read(CatalogGenesisReleaseLeafV1.SIGNATURE))
        assertArrayEquals(envelope, f.read(CatalogGenesisReleaseLeafV1.ENVELOPE))
        assertEquals(signedState, f.state(), "Exact resumed freeze is read-only SQL and does not charge capacity twice.")
    }

    fun preparedUnsignedResignEligibility() {
        val first = f.invocation()
        val actualReply = first.signing.respond
        first.signing.respond = { request -> actualReply(request).apply { status = 503 } }
        refused { first.execute() }
        first.fixtureCleanup() // The original writer and custody really end before the new resume owner exists.
        assertEquals(1, first.signing.requests.size)
        assertTrue(exists(CatalogGenesisReleaseLeafV1.FREEZE_PREPARED_NO_SIGNATURE))
        assertTrue(exists(CatalogGenesisReleaseLeafV1.FREEZE_SIGN_ARMED))
        assertFalse(exists(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTENCE_ARMED))
        assertFalse(exists(CatalogGenesisReleaseLeafV1.FREEZE_PIN_COMMITMENT_ARMED))
        assertFalse(exists(CatalogGenesisReleaseLeafV1.SIGNATURE))
        assertUnsignedSql()
        val unsignedState = f.state()
        val charges = actualCharges()

        val prematurePin = f.writeInput("pin-before-any-signature", "0".repeat(64).toByteArray())
        val premature = f.invocation()
        refused { premature.execute(resume = true, request = f.request(prematurePin)) }
        premature.fixtureCleanup()
        assertTrue(premature.signing.requests.isEmpty())
        assertEquals(unsignedState, f.state())

        val fresh = f.invocation()
        fresh.beforeSign = {
            assertUnsignedSql()
            assertFreshEmptyNamespaceCalls(fresh)
        }
        assertEquals("SIGNED_AWAITING_RELEASE", fresh.execute(resume = true).state.name)
        fresh.assertReleased()
        assertEquals(
            1,
            fresh.signing.requests.size,
            "SIGN_ARMED is not a lifetime one-Sign rule when complete custody proves no persistence or pin commitment.",
        )
        assertEquals(charges, actualCharges())
        assertTrue(checkNotNull(fresh.jdbc).steps.none { it == "insert" || it.startsWith("charge:") })
        assertSignedSql(fresh.producedSignatures.single(), expectedEnvelope(fresh.producedSignatures.single()))
    }

    fun unresolvedPersistenceArmCannotResign() {
        val first = f.invocation()
        var cut = false
        first.afterSample = {
            val complete = completenessPath(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTENCE_ARMED)
            if (!cut && Files.isRegularFile(complete)) {
                if (Files.size(complete) == 68L &&
                    Files.getPosixFilePermissions(complete) == PosixFilePermissions.fromString("r--------") &&
                    !exists(CatalogGenesisReleaseLeafV1.SIGNATURE)
                ) {
                    cut = true
                    first.clock.extraNanos += 60_000_000_000L
                }
            }
        }
        refused { first.execute() }
        assertTrue(cut, "The cut must follow actual persistence arming, not an unrelated acquisition failure.")
        first.fixtureCleanup()
        assertEquals(1, first.producedSignatures.size)
        assertTrue(exists(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTENCE_ARMED))
        assertFalse(exists(CatalogGenesisReleaseLeafV1.SIGNATURE))
        val retained = poolTestField<CatalogGenesisFreezeAttemptV1>(first.operator, "attempt")
        // Original handles/lock already closed. This read-only custody check proves complete current records, not earlier fsync or effect outcomes.
        CatalogGenesisReleaseCustodyV1.retain(f.releaseRoot, retained.inputs.allocation, PersistenceTimeBudget.start(10_000)).use { observer ->
            assertEquals(CatalogGenesisCustodyObservationV1.IDENTICAL_OBSERVED, observer.open())
            assertArrayEquals(
                f.read(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTENCE_ARMED),
                observer.read(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTENCE_ARMED),
            )
            assertNull(observer.read(CatalogGenesisReleaseLeafV1.SIGNATURE))
        }
        assertUnsignedSql()
        val unsignedState = f.state()
        val resumed = f.invocation()
        refused { resumed.execute(resume = true) }
        resumed.fixtureCleanup()
        assertEquals(0, resumed.signing.createdClients)
        assertEquals(unsignedState, f.state())

        // Explicit test-only SQL-loss cut, not a restore authority or a fresh allocation. Durable uncertainty must outlive SQL absence.
        assertEquals(1, f.observer.update("DELETE FROM complaint_catalog_mutations WHERE operation_token = ?", token()))
        val restoredAbsent = f.state()
        val absent = f.invocation()
        refused { absent.execute(resume = true) }
        absent.fixtureCleanup()
        assertEquals(0, absent.signing.createdClients)
        assertEquals(restoredAbsent, f.state())
        assertFalse(exists(CatalogGenesisReleaseLeafV1.PIN))
    }

    fun signatureCompletionFailureReusesFrozenBytes() {
        val beforeControl = f.control()
        val first = f.invocation()
        var afterCommit = false
        first.afterSql = { step ->
            if (step == "signature") {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        afterCommit = true
                        error("Synthetic catalog author signature completion-tail failure.")
                    }
                })
            }
        }
        refused { first.execute() }
        assertTrue(afterCommit)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(first.jdbc?.phase).databaseOutcome())
        first.fixtureCleanup()
        val signature = first.producedSignatures.single()
        val envelope = expectedEnvelope(signature)
        assertArrayEquals(signature, f.read(CatalogGenesisReleaseLeafV1.SIGNATURE))
        assertTrue(exists(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTENCE_ARMED))
        assertFalse(exists(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTED))
        assertSignedSql(signature, envelope)
        val committed = f.state()
        val resumed = f.invocation()
        assertEquals("SIGNED_AWAITING_RELEASE", resumed.execute(resume = true).state.name)
        resumed.assertReleased()
        assertEquals(0, resumed.signing.createdClients)
        assertArrayEquals(signature, f.read(CatalogGenesisReleaseLeafV1.SIGNATURE))
        assertArrayEquals(envelope, f.read(CatalogGenesisReleaseLeafV1.ENVELOPE))
        assertTrue(exists(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTED))
        assertEquals(committed, f.state())
        assertEquals(beforeControl, f.control())
    }

    fun originalBudgetAndSignerCleanup() {
        val before = f.state()
        val expired = f.invocation()
        expired.clock.extraNanos += 60_000_000_000L // Time starts at begin, before any request file or provider acquisition.
        refused { expired.execute() }
        expired.fixtureCleanup()
        assertEquals(0, expired.secrets.createdClients)
        assertEquals(0, expired.signing.createdClients)
        assertEquals(0, expired.namespace.createdClients)
        assertNull(expired.scope)
        assertFalse(exists(CatalogGenesisReleaseLeafV1.FREEZE_ARMED))
        assertEquals(before, f.state())

        activeAuthorPhaseExpiryDoesNotRevive()

        val closing = f.invocation()
        closing.signing.onClientClose = { throw IOException("synthetic-private-freeze-close-diagnostic") }
        refused { closing.execute() }
        closing.fixtureCleanup()
        assertEquals(1, closing.producedSignatures.size)
        assertEquals(1, closing.signing.closedClients)
        assertEquals(0, closing.signing.returnedClientCloses)
        assertFalse(exists(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTENCE_ARMED))
        assertFalse(exists(CatalogGenesisReleaseLeafV1.SIGNATURE))
        assertUnsignedSql()
        refused { closing.execute(resume = true) }
        assertEquals(1, closing.signing.requests.size)
        assertFalse(exists(CatalogGenesisReleaseLeafV1.FREEZE_OUTCOME))

        failurePrecedencePreservesSignals()
    }

    private fun activeAuthorPhaseExpiryDoesNotRevive() {
        val before = f.state()
        // Test-only isolation from the subsequent signer-close subcase, never alternate-root recovery permission.
        val isolatedRoot = Files.createDirectory(
            f.parent.resolve("expired-phase-release"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        val request = f.request(releaseRoot = isolatedRoot)
        val active = f.invocation()
        var retainedPhase: PersistencePhaseContext? = null
        var retainedEntry: PersistencePhysicalEntry? = null
        active.beforeSql = { step ->
            assertEquals("control", step)
            assertNull(retainedPhase)
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            retainedPhase = phase
            assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE, poolTestField<PersistencePhasePath>(phase, "path"))
            val attempt = poolTestField<CatalogGenesisFreezeAttemptV1>(active.operator, "attempt")
            assertSame(attempt, poolTestField<CatalogGenesisFreezeAttemptV1>(phase, "catalogAuthorAttempt"))
            assertSame(poolTestField<PersistenceTimeBudget>(active.operator, "budget"), attempt.budget)
            val work = poolTestField<PersistenceTimeBudget>(phase, "catalogAuthorWork")
            assertSame(work, poolTestField<PersistenceTimeBudget>(phase, "work"))
            assertSame(attempt.budget, poolTestField<PersistenceTimeBudget>(work, "parent"))
            assertActualAuthorHolder(active) // Real authentication and shared epoch exist before this cut.
            retainedEntry = checkNotNull(active.scope).catalogEntries().single().also { assertFalse(it.retirementRequested.get()) }
            active.clock.extraNanos += 60_000_000_000L
        }
        assertEquals(CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN, refused { active.execute(request = request) }.code)
        val phase = checkNotNull(retainedPhase)
        val entry = checkNotNull(retainedEntry)
        assertSame(phase, active.phases.single())
        assertEquals(listOf("control"), checkNotNull(active.jdbc).steps)
        assertNotEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        val emergency = poolTestField<PersistenceTimeBudget>(phase, "emergency")
        assertEquals(
            PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED,
            assertThrows<PersistenceBoundaryException> { emergency.remainingMillis(1) }.code,
        )
        assertTrue(entry.retirementRequested.get(), "The original physical entry must receive retirement even at zero remaining allowance.")
        assertTrue(checkNotNull(active.scope).root.shutdown.get(), "Original root shutdown must be attempted before fixture teardown.")
        val release = poolTestField<Any>(active.operator, "release")
        val custody = poolTestField<CatalogGenesisReleaseCustodyV1>(release, "custody")
        val files = poolTestField<LinuxGenesisReleaseFilesV1>(custody, "files")
        if (!files.cleanupComplete()) {
            assertFalse(
                poolTestField<Boolean>(custody, "closeIssued"),
                "Quarantined custody may retain an undispatched close, never an invented completed close.",
            )
        }
        assertEquals(before, f.state())
        assertEquals(0, active.signing.createdClients)
        assertEquals(0, active.namespace.createdClients)
        assertTrue(Files.isRegularFile(isolatedRoot.resolve("genesis").resolve(CatalogGenesisReleaseLeafV1.FREEZE_ARMED.fileName)))
        assertFalse(Files.exists(isolatedRoot.resolve("genesis").resolve(CatalogGenesisReleaseLeafV1.FREEZE_PREPARED_NO_SIGNATURE.fileName)))
        val acquisitions = active.secrets.requests.size
        refused { active.execute(resume = true, request = request) }
        assertEquals(acquisitions, active.secrets.requests.size)
        assertTrue(active.signing.requests.isEmpty())
        assertEquals(before, f.state())
        active.fixtureCleanup() // Actual later retirement is not an on-time production cleanup receipt or a released result.
        assertTrue(active.cleanupVerified)
        assertTrue(entry.scopeEnded && checkNotNull(entry.terminalWork).bodyExited())
        assertTrue(checkNotNull(active.scope).catalogEntries().isEmpty())
        assertTrue(files.cleanupComplete())
        refused { active.execute(resume = true, request = request) }
        assertEquals(acquisitions, active.secrets.requests.size)
        assertTrue(active.signing.requests.isEmpty())
        assertEquals(before, f.state())
        assertFalse(exists(CatalogGenesisReleaseLeafV1.FREEZE_ARMED))
    }

    private fun failurePrecedencePreservesSignals() {
        val thread = Thread.currentThread()
        val previouslyInterrupted = Thread.interrupted()
        try {
            val diagnostic = IOException("synthetic-private-freeze-close-diagnostic")
            val originalInterrupt = InterruptedException("synthetic-private-freeze-interruption").apply { initCause(diagnostic) }
            assertFalse(thread.isInterrupted) // A blocking API may already have cleared its flag when it throws.
            val interrupted = assertThrows<InterruptedException> {
                throw boundedCatalogFreezeFailure(preferCatalogFreezeCleanup(originalInterrupt, diagnostic))
            }
            assertEquals("Catalog genesis freeze interrupted.", interrupted.message)
            assertNull(interrupted.cause)
            assertTrue(interrupted.suppressed.isEmpty())
            assertTrue(Thread.interrupted())

            val fatal = LinkageError("synthetic-fatal-freeze-signal")
            assertSame(
                fatal,
                assertThrows<LinkageError> {
                    val prioritized = preferCatalogFreezeCleanup(originalInterrupt, fatal)
                    throw boundedCatalogFreezeFailure(preferCatalogFreezeCleanup(prioritized, diagnostic))
                },
            )
            assertTrue(Thread.interrupted(), "A higher-priority fatal signal does not erase the original interruption.")

            val originalCancellation = CancellationException("synthetic-private-freeze-cancellation").apply { initCause(diagnostic) }
            val cancelled = assertThrows<CancellationException> {
                val prioritized = preferCatalogFreezeCleanup(originalInterrupt, originalCancellation)
                throw boundedCatalogFreezeFailure(preferCatalogFreezeCleanup(prioritized, diagnostic))
            }
            assertEquals("Catalog genesis freeze cancelled.", cancelled.message)
            assertNull(cancelled.cause)
            assertTrue(cancelled.suppressed.isEmpty())
            assertTrue(Thread.interrupted(), "Cancellation remains cancellation without erasing the original interruption.")
        } finally {
            Thread.interrupted()
            if (previouslyInterrupted) thread.interrupt()
        }
    }

    private fun assertActualAuthorHolder(invocation: CatalogGenesisFreezeInvocation) {
        val holder = TransactionSynchronizationManager.getResource(invocation.coordinator.dataSource) as ConnectionHolder
        assertEquals(setOf(invocation.coordinator.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
        holder.connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT session_user, current_user, current_setting('transaction_read_only'), " +
                    "(SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()), " +
                    "EXISTS (SELECT 1 FROM pg_locks WHERE pid = pg_backend_pid() AND locktype = 'advisory' AND mode = 'ShareLock' AND granted)",
            ).use { row ->
                assertTrue(row.next())
                assertEquals(CatalogGenesisFreezeFixture.AUTHOR, row.getString(1))
                assertEquals(CatalogGenesisFreezeFixture.AUTHOR, row.getString(2))
                assertEquals("off", row.getString(3))
                assertTrue(row.getBoolean(4) && !row.wasNull())
                assertTrue(row.getBoolean(5))
                assertFalse(row.next())
            }
        }
    }

    private fun assertColumnDenied(table: String, column: String) {
        assertEquals(
            false,
            f.observer.queryForObject("SELECT has_column_privilege(?, ?, ?, 'UPDATE')", Boolean::class.java, CatalogGenesisFreezeFixture.AUTHOR, table, column),
        )
    }

    private fun assertFreshEmptyNamespaceCalls(invocation: CatalogGenesisFreezeInvocation) {
        assertEquals(2, invocation.namespace.requests.size)
        assertEquals(2, invocation.namespace.replies.sumOf { it.calls })
        assertEquals(2, invocation.namespace.replies.sumOf { it.closes })
        assertTrue(invocation.namespace.requests.all { it.firstMatchingRawQueryParameter("max-keys").orElseThrow() == "1" })
    }

    private fun assertUnsignedSql() {
        assertEquals(
            true,
            f.observer.queryForObject(
                "SELECT state = 'PREPARED' AND signer_one_signature IS NULL AND envelope_bytes IS NULL AND envelope_hash IS NULL " +
                    "AND unsigned_bytes = ? FROM complaint_catalog_mutations WHERE operation_token = ?",
                Boolean::class.java,
                f.intent,
                token(),
            ),
        )
    }

    private fun assertSignedSql(signature: ByteArray, envelope: ByteArray) {
        val row = f.observer.queryForMap("SELECT * FROM complaint_catalog_mutations WHERE operation_token = ?", token())
        assertEquals("PREPARED", row["state"])
        assertArrayEquals(signature, row["signer_one_signature"] as ByteArray)
        assertArrayEquals(envelope, row["envelope_bytes"] as ByteArray)
        for (field in listOf("completed_at", "projected_at", "object_version", "primary_evidence_bytes", "replica_evidence_bytes")) assertNull(row[field])
    }

    private fun expectedEnvelope(signature: ByteArray): ByteArray = OfflineCatalogGenesisFixture.bytes(
        OfflineCatalogGenesisEnvelopeV1(
            1,
            f.manifest,
            listOf(OfflineCatalogGenesisSignatureV1("catalog-old", "RSASSA_PSS_SHA_256", Base64.getEncoder().encodeToString(signature))),
        ),
    )

    private fun actualCharges(): List<Long> = f.observer.queryForList(
        "SELECT actual_units FROM complaint_capacity_counters WHERE name IN ('catalog_mutations', 'storage_bytes') ORDER BY name",
        Long::class.java,
    )

    private fun mutationCount(): Long = checkNotNull(f.observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations", Long::class.java))
    private fun token(): UUID = UUID.fromString(f.manifest.operationToken)
    private fun exists(leaf: CatalogGenesisReleaseLeafV1): Boolean = Files.isRegularFile(f.leafPath(leaf))
    private fun completenessPath(leaf: CatalogGenesisReleaseLeafV1) = f.leafPath(leaf).resolveSibling(leaf.fileName + ".complete")

    private fun refused(action: () -> Unit): CatalogGenesisFreezeExceptionV1 {
        val failure = assertThrows<CatalogGenesisFreezeExceptionV1> { action() }
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.toString().contains("synthetic-private-freeze-close-diagnostic"))
        return failure
    }
}
