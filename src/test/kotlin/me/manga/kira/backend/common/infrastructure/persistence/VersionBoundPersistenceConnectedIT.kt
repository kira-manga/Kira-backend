package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.catalog.ProcessBoundCatalogGenesisCases
import me.manga.kira.backend.complaint.catalog.withCurrentAcceptedCatalogRefresh
import me.manga.kira.backend.complaint.catalog.withProcessBoundCatalogGenesis
import me.manga.kira.backend.complaint.domain.ComplaintInstallationEnrollment
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Files
import java.sql.SQLException

/** Real owned PostgreSQL TLS, not a protocol peer, provider attestation, production profile or complete-D/activation proof. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class VersionBoundPersistenceConnectedIT {
    private val database = lazy { PgLifecycleDatabaseFixture(VersionBoundPersistenceConnectedIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `one acquired version and CA drive three distinct real TLS pools and the existing catalog transaction`() = withFixture { f ->
        f.start()
        assertEquals(1, f.acquisitions)
        assertEquals(f.acquired.descriptor, f.configuration.descriptor.authenticationPassword)
        assertEquals(PersistenceLifecycleObservation.READY, f.pools.deletion.prepareDeletion())
        assertEquals(PersistenceLifecycleObservation.READY, f.pools.catalogCoordinator.prepare())
        val pids = listOf(f.tlsPid(f.pools.ordinary), f.tlsPid(f.pools.deletion), f.tlsPid(f.pools.catalogCoordinator.dataSource))
        assertEquals(3, pids.toSet().size)
        assertEquals(1, f.scope.entries().size)
        assertEquals(4, f.scope.entries(deletion = true).size)
        assertEquals(1, f.scope.catalogEntries().size)
        assertEquals(PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION, f.scope.entries().single().policy)
        assertTrue(f.scope.entries(deletion = true).all { it.policy === PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION })
        assertEquals(PersistenceDriverAttemptPolicy.TRACKED_CATALOG_CONJUNCTION, f.scope.catalogEntries().single().policy)
        requireConnectionFree()

        val catalog = f.pools.catalogCoordinator
        val phase = catalog.ownership.enterComplaintCatalogSnapshot()
        try {
            phase.begin()
            val holder = TransactionSynchronizationManager.getResource(catalog.dataSource) as ConnectionHolder
            assertEquals(setOf(catalog.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
            assertEquals(pids.last(), f.tlsPid(holder.connection))
            assertTrue(holder.connection.isReadOnly)
        } finally {
            // No catalog operation/acceptance was supplied; the genuine named read-only transaction must roll back.
            phase.recordFailure(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
            phase.finish()
        }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, phase.databaseOutcome())
        assertTrue(phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
        assertEquals(0, catalog.activeSnapshotOwners())
        assertTrue(Files.isRegularFile(f.trustPath()))
        requireConnectionFree()
        // Fixture close requires every actual pool/root drain before trust removal and observes these PG sessions end.
    }

    @Test
    fun `actual enrollment commit and rollback release the version bound TLS lease and named ordinary owner`() = withFixture { f ->
        f.start()
        f.tlsPid(f.pools.ordinary)
        f.withEnrollment { enrollment ->
            val first = enrollment.candidate()
            assertEquals(first.installation, enrollment.execute(first).installation)
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, enrollment.observations.last().second.phase.databaseOutcome())
            enrollment.assertReleased()
            val before = enrollment.state()
            val failure = assertThrows<PersistencePhaseException> {
                enrollment.attempt(
                    enrollment.candidate(),
                    ComplaintInstallationEnrollment { candidate ->
                        enrollment.store.enroll(candidate)
                        throw SyntheticInstallationEnrollmentFailure()
                    },
                )
            }
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
            assertTrue(failure.cleanupProven)
            assertEquals(before, enrollment.state())
            enrollment.assertReleased()
            assertNull(PersistencePhaseOwnership.current())
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            f.tlsPid(f.pools.ordinary) // A new actual checkout succeeds only after the named owner/lease has released.
        }
    }

    @Test
    fun `wrong acquired CA hostname and password fail after real driver entry without fallback or retained processing`() {
        // Same server/material first, independent of JUnit method order. The second loopback is also verified at startup.
        withFixture { control ->
            control.start()
            control.tlsPid(control.pools.ordinary)
        }
        for (client in listOf(ConnectedTlsClient.WRONG_CA, ConnectedTlsClient.WRONG_HOST, ConnectedTlsClient.WRONG_PASSWORD)) {
            withFixture(client) { f ->
                f.start()
                val request = f.owner.prepareOrdinaryRequest()
                val result = assertInstanceOf(PersistenceFactoryResult.Failed::class.java, request.execute(), client.name)
                assertEquals(PersistenceFactoryFailure.CREATE_FAILED, result.reason, client.name)
                val entry = checkNotNull(request.admittedEntry(f.scope.binding()))
                awaitLifecycleFact { result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED && f.scope.entries().isEmpty() }
                assertTrue(entry.openingFacts.driverEntered.get() && entry.openingFacts.driverEnded.get(), client.name)
                assertEquals(PersistencePhysicalOpening.FAILED, entry.openingFacts.outcome.get(), client.name)
                val failure = checkNotNull(entry.openingFacts.failure())
                assertEquals(PersistenceOpeningFailureSite.DRIVER_CONNECT, failure.site, client.name)
                assertEquals(PersistenceFailureType.SQL, failure.type, client.name)
                // Existing closed facts intentionally retain no SQLState/cause; this is not per-cause diagnostic attestation.
                assertNull(entry.raw.get(), client.name)
                assertEquals(PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION, entry.policy, client.name)
                assertEquals(PersistenceTerminalDisposition.TRACKED_DISPOSED, checkNotNull(entry.terminalWork).disposition(), client.name)
                val transport = assertInstanceOf(PersistenceTransportSnapshot.Available::class.java, checkNotNull(entry.transports).snapshot())
                assertTrue(checkNotNull(transport.primary).rawReturned, client.name)
                assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, checkNotNull(transport.primary).firstClose, client.name)
                assertFalse(f.owner.snapshot().weakEvidenceUsed, client.name)
                assertEquals(1, f.acquisitions, client.name)
                requireConnectionFree()
            }
        }
    }

    @Test
    fun `UNKNOWN stays closed even with acquired credentials prepared CA and the real TLS endpoint`() =
        withFixture(profile = PersistencePoolLaunchProfile.UNKNOWN) { f ->
            assertEquals(PersistenceLifecycleActivation.FAILED, f.pools.ordinary.start())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, f.pools.deletion.prepareDeletion())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, f.pools.catalogCoordinator.prepare())
            for (source in listOf(f.pools.ordinary, f.pools.deletion, f.pools.catalogCoordinator.dataSource)) {
                assertThrows<SQLException> { source.connection }
                assertFalse(actualPool(source).isRunning)
            }
            assertFalse(f.owner.snapshot().ordinaryReady || f.owner.snapshot().deletionReady || f.owner.snapshot().catalogCoordinatorReady)
            assertTrue(f.scope.actors().none { it.hasEntered() })
            requireConnectionFree()
        }

    @Test
    fun `computed D completed HTTP replay rejects changed control and stays read only during matching maintenance and provider outage`() =
        withFixture { tls -> withCurrentOwnerDeleteAll(tls) { it.completedReplay() } }

    @Test
    fun `computed D active and authorized preflights reject stale control before semantic or provider work`() =
        withFixture { tls -> withCurrentOwnerDeleteAll(tls) { it.staleActiveAndAuthorized() } }

    @Test
    fun `computed D is rechecked after original preflight and readback at AUTH RELOAD VERIFY and APPLY`() =
        withFixture { tls -> withCurrentOwnerDeleteAll(tls) { it.lockedPhases() } }

    @Test
    fun `process bound G1 projection requires exact nonnull D original identities and committed released custody while legacy null D stays diagnostic`() =
        withFixture { tls -> withProcessBoundCatalogGenesis(tls) { ProcessBoundCatalogGenesisCases(it).projectReplayAndNullDSeparation() } }

    @Test
    fun `process bound G1 final reread rejects D drift and commit or completion failure never releases a projection receipt`() =
        withFixture { tls -> withProcessBoundCatalogGenesis(tls) { ProcessBoundCatalogGenesisCases(it).currentDriftAndCommitReleaseFailures() } }

    @Test
    fun `owned G1 refresh joins real SDK readback to prepared completion projection and exact retry`() =
        withFixture { tls -> withCurrentAcceptedCatalogRefresh(tls) { it.preparedRefreshCompletesProjectsAndRetries() } }

    @Test
    fun `owned G1 refresh rejects substituted process settings and stale desired configuration`() =
        withFixture { tls -> withCurrentAcceptedCatalogRefresh(tls) { it.wrongBindingAndStaleHistoryAreRejected() } }

    @Test
    fun `owned G1 refresh closes late construction and readback before deadline refusal with exact recovery`() =
        withFixture { tls -> withCurrentAcceptedCatalogRefresh(tls) { it.expiredRefreshClosesBeforePersistence() } }

    @Test
    fun `owned G1 refresh cleanup failure retains original coordinator custody against replacement`() =
        withFixture { tls -> withCurrentAcceptedCatalogRefresh(tls) { it.failedProviderCleanupPoisonsOriginalCoordinator() } }

    private fun withFixture(
        client: ConnectedTlsClient = ConnectedTlsClient.MATCHED,
        profile: PersistencePoolLaunchProfile = PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY,
        test: (VersionBoundPersistenceConnectedFixture) -> Unit,
    ) = VersionBoundPersistenceConnectedFixture(database.value, client).use { fixture ->
        fixture.bind(profile)
        test(fixture)
    }
}
