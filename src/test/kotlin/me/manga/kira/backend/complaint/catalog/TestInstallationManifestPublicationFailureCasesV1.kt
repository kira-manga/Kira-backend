package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestPublicationSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestPublicationStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.journal.TestOrdinaryInventoryHttpFixtureV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** Only actual transaction/provider failure cuts. No fake receipt, original, proof, commit or cleanup. */
internal object TestInstallationManifestPublicationFailureCasesV1 {
    fun unfinishedAndFailedPrepare(tls: VersionBoundPersistenceConnectedFixture) =
        withOrdinaryDrainRun(tls, expireClosedSetupPredecessors = true, manifestPublication = true) { f ->
            TestOrdinaryInventoryHttpFixtureV1(f.provider).use {
                val drain = f.begin()
                assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                    drain.drain(f.approval(drain), f.rawEvidence, f.primaryCredentials, f.readCredentials))
                val observed = TestOrdinaryDrainAccountingObservationV1(f)
                val image = observed.image()
                val providers = f.sealHttp.order.toList()
                TestInstallationManifestSqlProbeV1(f).use { probe ->
                    val preparation = TestRunInstallationManifestV1.begin(drain).also { probe.original = it }
                    assertThrows<TestInstallationManifestExceptionV1> { preparation.beginPublication() }
                    assertThrows<TestInstallationManifestExceptionV1> { drain.publishInstallationManifest() }
                    assertTrue(probe.calls.isEmpty())
                    probe.before = { error("Synthetic PREPARE original refusal.") }
                    assertThrows<TestInstallationManifestExceptionV1> { preparation.prepare() }
                    probe.before = {}; probe.assertReleased(requireCommitted = false)
                    val calls = probe.calls.size
                    assertThrows<TestInstallationManifestExceptionV1> { preparation.beginPublication() }
                    assertThrows<TestInstallationManifestExceptionV1> { drain.publishInstallationManifest() }
                    assertEquals(calls, probe.calls.size)
                }
                assertEquals(image, observed.image()); assertEquals(providers, f.sealHttp.order)
                f.assertReleased()
            }
        }

    fun staleAuthorityAfterLoad(tls: VersionBoundPersistenceConnectedFixture) = withManifestPublicationRun(tls) { f, preparation, probe ->
        val paid = TestOrdinaryDrainAccountingObservationV1(f).state()
        val rows = manifestRowsImage(f)
        val requests = f.sealHttp.requests.size
        var changed = false
        val foreign = UUID.randomUUID()
        f.sealHttp.changeSts = { stage, _ ->
            if (stage == 3) {
                requireConnectionFree()
                manifestRaw(f) { connection ->
                    connection.prepareStatement("UPDATE complaint_journal_control SET lease_owner = ?, lease_token = lease_token + 1 WHERE data_scope_id = ?").use { statement ->
                        statement.setObject(1, foreign); statement.setObject(2, f.scope); assertEquals(1, statement.executeUpdate())
                    }
                }
                changed = true
            }
        }
        val original = preparation.beginPublication().also { probe.original = it }
        assertThrows<TestInstallationManifestExceptionV1> { original.publish() }
        f.sealHttp.changeSts = { _, _ -> }; probe.assertReleased(requireCommitted = false)
        assertTrue(changed)
        assertEquals(rows, manifestRowsImage(f)); assertEquals(paid, TestOrdinaryDrainAccountingObservationV1(f).state())
        assertEquals(requests, f.sealHttp.requests.size, "A released LOAD cannot replace the fresh fence required before wire freeze/S3.")
        assertTrue(probe.calls.any { it.step === TestInstallationManifestPublicationStepV1.FREEZE })
        assertTrue(probe.calls.none { it.sql == TestInstallationManifestPublicationSqlV1.freeze || it.step === TestInstallationManifestPublicationStepV1.VERIFY })
    }

    fun loadConsumesOriginalPublicationDeadline(tls: VersionBoundPersistenceConnectedFixture) = withManifestPublicationRun(tls) { f, preparation, probe ->
        val rows = manifestRowsImage(f)
        val order = f.sealHttp.order.toList()
        var selected: PersistencePhaseContext? = null
        probe.after = { call ->
            if (selected == null && call.step === TestInstallationManifestPublicationStepV1.LOAD && call.sql == TestOrdinarySealSqlV1.lease) {
                selected = call.phase
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        f.sealHttp.offsetNanos += f.registration.process.consumers.journalConfiguration.declaration().limits.deadlines.publicationAttemptMillis * 1_000_000L
                    }
                })
            }
        }
        val original = preparation.beginPublication().also { probe.original = it }
        assertThrows<TestInstallationManifestExceptionV1> { original.publish() }
        probe.after = {}; probe.assertReleased(requireCommitted = false)
        assertTrue(selected != null)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(selected).databaseOutcome())
        assertEquals(rows, manifestRowsImage(f)); assertEquals(order, f.sealHttp.order)
        assertTrue(probe.calls.none { it.step in setOf(TestInstallationManifestPublicationStepV1.FREEZE, TestInstallationManifestPublicationStepV1.VERIFY) })
    }

    fun completion(tls: VersionBoundPersistenceConnectedFixture, step: TestInstallationManifestPublicationStepV1,
        cut: TestRegistrationCompletionCut, preconditionOnRetry: Boolean = false) = withManifestPublicationRun(tls,
        realTimeRetentionRetry = step === TestInstallationManifestPublicationStepV1.FREEZE && cut === TestRegistrationCompletionCut.AFTER_COMMIT) { f, preparation, probe ->
        require(step in setOf(TestInstallationManifestPublicationStepV1.FREEZE, TestInstallationManifestPublicationStepV1.VERIFY))
        require(!preconditionOnRetry || step === TestInstallationManifestPublicationStepV1.VERIFY && cut === TestRegistrationCompletionCut.BEFORE_COMMIT)
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        val paid = observed.state()
        val unchanged = manifestInvariantImage(f)
        val requests = f.sealHttp.requests.size
        var selected: PersistencePhaseContext? = null
        val resource = Any(); val sentinel = Any(); var bound = false
        val selectedSql = if (step === TestInstallationManifestPublicationStepV1.FREEZE) TestInstallationManifestPublicationSqlV1.freeze
            else TestInstallationManifestPublicationSqlV1.verify
        probe.after = { call ->
            if (selected == null && call.step === step && call.sql == selectedSql) {
                selected = call.phase
                if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                    val jdbc = JdbcTemplate(checkNotNull(probe.dataSource))
                    jdbc.execute("CREATE TEMP TABLE kira_manifest_publication_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                    assertEquals(2, jdbc.update("INSERT INTO kira_manifest_publication_commit_cut VALUES (1), (1)"))
                } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) {
                        if (cut === TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic manifest publication beforeCommit refusal.")
                    }
                    override fun afterCommit() {
                        if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                            TransactionSynchronizationManager.bindResource(resource, sentinel); bound = true
                        }
                        if (cut !== TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic manifest publication acknowledgment loss.")
                    }
                })
            }
        }
        val original = preparation.beginPublication().also { probe.original = it }
        try {
            assertThrows<TestInstallationManifestExceptionV1> { original.publish() }
            assertTrue(selected != null, "The actual owned UPDATE must reach the selected commit cut.")
            if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                assertTrue(bound); assertSame(selected, PersistencePhaseOwnership.current())
                assertTrue(checkNotNull(selected).quarantined())
                assertFalse(checkNotNull(selected).testInstallationManifestPublicationCleanupProven(original))
                assertFalse(checkNotNull(selected).testInstallationManifestPublicationResourcesRetired(original))
                assertThrows<RuntimeException> { preparation.beginPublication() }
            }
        } finally {
            probe.after = {}
            if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resource))
            requireConnectionFree() // Reconcile physical retirement; no successful-outcome rehabilitation.
        }
        probe.assertReleased(requireCommitted = false)
        val outcome = when (cut) {
            TestRegistrationCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
            TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
            else -> PersistenceDatabaseOutcome.COMMITTED
        }
        assertEquals(outcome, checkNotNull(selected).databaseOutcome())
        val frozen = step === TestInstallationManifestPublicationStepV1.VERIFY || outcome === PersistenceDatabaseOutcome.COMMITTED
        val verified = step === TestInstallationManifestPublicationStepV1.VERIFY && outcome === PersistenceDatabaseOutcome.COMMITTED
        assertEquals(listOf((if (verified) "VERIFIED" else "PREPARED") to (if (frozen) "WIRE_FROZEN" else "CANONICAL")), manifestStatePairs(f))
        assertEquals(paid, observed.state()); assertEquals(unchanged, manifestInvariantImage(f))
        if (step === TestInstallationManifestPublicationStepV1.FREEZE) assertEquals(requests, f.sealHttp.requests.size)
        assertTrue(probe.calls.none { it.step === TestInstallationManifestPublicationStepV1.COMPLETE })
        assertThrows<TestInstallationManifestExceptionV1> { original.authenticatedChunk(0) }
        val calls = probe.calls.size
        assertThrows<TestInstallationManifestExceptionV1> { original.publish() }
        assertEquals(calls, probe.calls.size)
        if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
            assertFalse(checkNotNull(selected).testInstallationManifestPublicationCleanupProven(original), "Sticky failure is not erased by physical retirement.")
            assertTrue(checkNotNull(selected).testInstallationManifestPublicationResourcesRetired(original))
            val order = f.sealHttp.order.toList()
            // Even physically retired failure is not a transferable lease. This fresh child needs
            // actual free/expired authority, and must fail before any provider while it is live.
            probe.reset()
            val premature = preparation.beginPublication().also { probe.original = it }
            assertThrows<TestInstallationManifestExceptionV1> { premature.publish() }
            probe.assertReleased(requireCommitted = false)
            assertEquals(order, f.sealHttp.order)
        }
        val rowImage = manifestRowsImage(f)
        val beforeRetry = f.sealHttp.order.size
        val beforeRequests = f.sealHttp.requests.size
        val retry = if (step === TestInstallationManifestPublicationStepV1.FREEZE && cut === TestRegistrationCompletionCut.AFTER_COMMIT) {
            TestInstallationManifestRetentionCasesV1.retryAfterLostFreeze(f, preparation, probe, original)
        } else {
            observed.expireLeaseForRetry(); probe.reset() // Other cuts retain explicit synthetic fixture expiry.
            var lists = 0
            if (preconditionOnRetry) f.sealHttp.beforeS3 = { request -> if (request.kind == "LIST") f.sealHttp.hideObject = ++lists == 1 }
            TestInstallationManifestPublicationCasesV1.publish(preparation, probe)
        }
        f.sealHttp.beforeS3 = {}; f.sealHttp.hideObject = false
        probe.assertReleased()
        assertEquals(if (frozen) 0 else 1, f.sealHttp.order.drop(beforeRetry).count { it == "GENERATE" })
        if (frozen) assertEquals(rowImage.getValue("sidecar"), manifestRowsImage(f).getValue("sidecar"), "A durable random wire winner is never re-encrypted or rewritten.")
        if (verified) assertEquals(rowImage, manifestRowsImage(f), "Lost VERIFY acknowledgment reloads the exact first verified bytes/xmin.")
        if (preconditionOnRetry) {
            assertEquals(listOf("LIST", "PUT", "LIST", "GET"), f.sealHttp.requests.drop(beforeRequests).map { it.kind })
            assertEquals(412, f.sealHttp.requests.drop(beforeRequests).single { it.kind == "PUT" }.reply?.status)
        }
        assertEquals(paid, observed.state()); assertEquals(unchanged, manifestInvariantImage(f))
        TestInstallationManifestPublicationCasesV1.assertNoAccountingOrDomainWrites(probe)
        assertPublishedManifests(f, retry)
    }
}
