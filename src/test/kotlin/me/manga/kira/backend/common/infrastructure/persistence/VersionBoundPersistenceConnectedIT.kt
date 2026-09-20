package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.catalog.ActivationEvidencePrefix
import me.manga.kira.backend.complaint.catalog.CatalogGenesisFreezeCases
import me.manga.kira.backend.complaint.catalog.CatalogGenesisPublishCases
import me.manga.kira.backend.complaint.catalog.CatalogGenesisPublishCliCases
import me.manga.kira.backend.complaint.catalog.CatalogGenesisTargetFinalizeCases
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationActivationCases
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationActivationCommitStage
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationActivationContinuationCases
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationActivationRecoveryCases
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationActivationRefusalCases
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationCleanupCut
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationColdCommitCut
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationColdRecoveryCases
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationColdRecoveryRefusalCases
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationColdRefusalCut
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationContinuationCases
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationContinuationCut
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationDeliveryCases
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationDeliveryFailureCases
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationFreezeCases
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationInitialAuthorCases
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationInitialAuthorCut
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationPreparedRecoveryCases
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationRecoveryIdentityCut
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationRecoveryLeaseCut
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationRecoveryProviderCut
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationRecoverySqlCut
import me.manga.kira.backend.complaint.catalog.CatalogTestRunActivationBoundaryCases
import me.manga.kira.backend.complaint.catalog.CatalogTestRunActivationCompletionBoundaryCases
import me.manga.kira.backend.complaint.catalog.CatalogTestRunActivationCompletionCases
import me.manga.kira.backend.complaint.catalog.CatalogTestRunActivationCompletionRecoveryCases
import me.manga.kira.backend.complaint.catalog.CatalogTestRunActivationPreparedCases
import me.manga.kira.backend.complaint.catalog.CatalogTestRunActivationProjectionCases
import me.manga.kira.backend.complaint.catalog.CatalogTestRunActivationProjectionRecoveryCases
import me.manga.kira.backend.complaint.catalog.CatalogTestRunActivationSignedCases
import me.manga.kira.backend.complaint.catalog.CatalogTestRunActivationSignedRecoveryCases
import me.manga.kira.backend.complaint.catalog.ComplaintTestNamespaceRegistrationCases
import me.manga.kira.backend.complaint.catalog.ComplaintTestInitialAdmissionCases
import me.manga.kira.backend.complaint.catalog.InitialAdmissionDriftCut
import me.manga.kira.backend.complaint.catalog.InitialAdmissionLifetimeCut
import me.manga.kira.backend.complaint.catalog.InitialAdmissionLockCut
import me.manga.kira.backend.complaint.catalog.InitialAdmissionProviderCut
import me.manga.kira.backend.complaint.catalog.InitialIdentityDriftCut
import me.manga.kira.backend.complaint.catalog.CoordinatorLeaseBoundaryCases
import me.manga.kira.backend.complaint.catalog.CoordinatorLeaseCases
import me.manga.kira.backend.complaint.catalog.TestRegistrationCompletionCut
import me.manga.kira.backend.complaint.catalog.TestRegistrationDriftCut
import me.manga.kira.backend.complaint.catalog.TestRegistrationProjectCut
import me.manga.kira.backend.complaint.catalog.TestRegistrationProviderCut
import me.manga.kira.backend.complaint.catalog.TestRunSealingCases
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealCasesV1
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealFailureCasesV1
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealHistoryCutV1
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealInstallationCutV1
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealIntakeCasesV1
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealIntakeCutV1
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealProviderCutV1
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealLifetimeCutV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealStepV1
import me.manga.kira.backend.complaint.catalog.TestRunVerifiedOwnerDeleteCases
import me.manga.kira.backend.complaint.catalog.TestRunPreparedOwnerDeleteCases
import me.manga.kira.backend.complaint.catalog.TestRunOwnerDeletePageCases
import me.manga.kira.backend.complaint.catalog.TestRunOwnerDeleteAllCasesV1
import me.manga.kira.backend.complaint.catalog.TestAllHistoryCutV1
import me.manga.kira.backend.complaint.catalog.TestPreparedDeleteRefusalCut
import me.manga.kira.backend.complaint.catalog.TestPreparedDeleteProviderCut
import me.manga.kira.backend.complaint.catalog.TestVerifiedDeleteRefusalCut
import me.manga.kira.backend.complaint.catalog.CutoffResolverCases
import me.manga.kira.backend.complaint.catalog.EpochMaintenanceClock
import me.manga.kira.backend.complaint.catalog.EpochMaintenanceGateCut
import me.manga.kira.backend.complaint.catalog.EpochRotationCases
import me.manga.kira.backend.complaint.catalog.EpochRotationMaintenanceCases
import me.manga.kira.backend.complaint.catalog.HeldEpochSealBudgetCases
import me.manga.kira.backend.complaint.catalog.HeldEpochSealCases
import me.manga.kira.backend.complaint.catalog.HeldEpochSealCleanupCases
import me.manga.kira.backend.complaint.catalog.HeldEpochSealClock
import me.manga.kira.backend.complaint.catalog.HeldSealBudgetCut
import me.manga.kira.backend.complaint.catalog.HeldSealCurrentCut
import me.manga.kira.backend.complaint.catalog.HeldSealPartialCut
import me.manga.kira.backend.complaint.catalog.HeldSealPreparationCut
import me.manga.kira.backend.complaint.catalog.HeldSealStopCut
import me.manga.kira.backend.complaint.catalog.ProcessBoundCatalogGenesisCases
import me.manga.kira.backend.complaint.catalog.SealCanonicalCases
import me.manga.kira.backend.complaint.catalog.TestActivationCustodyCut
import me.manga.kira.backend.complaint.catalog.TestActivationCompleteLifecycleCut
import me.manga.kira.backend.complaint.catalog.TestActivationCompleteSqlCut
import me.manga.kira.backend.complaint.catalog.TestActivationCompletionCopyCut
import me.manga.kira.backend.complaint.catalog.TestActivationDeliveryBoundaryCut
import me.manga.kira.backend.complaint.catalog.TestActivationDeliveryRaceCut
import me.manga.kira.backend.complaint.catalog.TestActivationPendingReplayCut
import me.manga.kira.backend.complaint.catalog.TestActivationProjectionBindingCut
import me.manga.kira.backend.complaint.catalog.TestActivationProjectionBoundaryCut
import me.manga.kira.backend.complaint.catalog.TestActivationProjectionCapacityCut
import me.manga.kira.backend.complaint.catalog.TestActivationProjectionLifetimeCut
import me.manga.kira.backend.complaint.catalog.TestActivationProjectionReplayCut
import me.manga.kira.backend.complaint.catalog.TestActivationProjectionSqlCut
import me.manga.kira.backend.complaint.catalog.TestActivationPutUnreturnedCut
import me.manga.kira.backend.complaint.catalog.TestActivationRefusalCut
import me.manga.kira.backend.complaint.catalog.TestActivationSignedCorruptionCut
import me.manga.kira.backend.complaint.catalog.TestActivationSignedInputCut
import me.manga.kira.backend.complaint.catalog.TestActivationSignedLifecycleCut
import me.manga.kira.backend.complaint.catalog.TestActivationSignedRaceCut
import me.manga.kira.backend.complaint.catalog.TestActivationSignedSqlCut
import me.manga.kira.backend.complaint.catalog.TestActivationSignedUnreturnedCut
import me.manga.kira.backend.complaint.catalog.assertTestActivationProjectLostCommitResponse
import me.manga.kira.backend.complaint.catalog.assertInitialAdmissionLostCommitResponse
import me.manga.kira.backend.complaint.catalog.withCatalogGenesisFreeze
import me.manga.kira.backend.complaint.catalog.withCatalogGenesisPublish
import me.manga.kira.backend.complaint.catalog.withCatalogGenesisTargetFinalize
import me.manga.kira.backend.complaint.catalog.withCatalogSignerRotationActivation
import me.manga.kira.backend.complaint.catalog.withCatalogSignerRotationDelivery
import me.manga.kira.backend.complaint.catalog.withCatalogSignerRotationFreeze
import me.manga.kira.backend.complaint.catalog.withCatalogSignerRotationInitialAuthor
import me.manga.kira.backend.complaint.catalog.withCoordinatorLease
import me.manga.kira.backend.complaint.catalog.withCoordinatorLeasePeer
import me.manga.kira.backend.complaint.catalog.withCurrentAcceptedCatalogRefresh
import me.manga.kira.backend.complaint.catalog.withCurrentProjectedCatalogRefresh
import me.manga.kira.backend.complaint.catalog.withCutoffResolverHistory
import me.manga.kira.backend.complaint.catalog.withEpochRotation
import me.manga.kira.backend.complaint.catalog.withHeldEpochSeal
import me.manga.kira.backend.complaint.catalog.withProcessBoundCatalogGenesis
import me.manga.kira.backend.complaint.domain.ComplaintInstallationEnrollment
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationCases
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationCompletionCases
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationRefusalCases
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDCases
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDCompletionCases
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDConcurrencyCases
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationCompletionCut
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationTestClock
import me.manga.kira.backend.complaint.infrastructure.admission.withDesiredInstallation
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
// Keep one serial database lifecycle; focused Cases helpers own the individual behaviors.
@Suppress("LargeClass")
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

    @Test
    fun `projected nonempty current refresh uses real SDK and exact released point history without mutation effects`() =
        withFixture { tls -> withCurrentProjectedCatalogRefresh(tls) { it.nonemptyExactProjectedReplay() } }

    @Test
    fun `projected current history refuses mismatched frozen copies and fresh reread drift`() =
        withFixture { tls -> withCurrentProjectedCatalogRefresh(tls) { it.exactHistoricalTupleAndFreshRereads() } }

    @Test
    fun `projected current refresh revalidates every full binding field after provider close`() =
        withFixture { tls -> withCurrentProjectedCatalogRefresh(tls) { it.fullBindingRevalidatedAfterRawReadback() } }

    @Test
    fun `projected current refresh refuses pending G1 unexplained tails and current trust floor rollback`() =
        withFixture { tls -> withCurrentProjectedCatalogRefresh(tls) { it.pendingUnexplainedTailAndIndependentFloor() } }

    @Test
    fun `projected current refresh shares the original G1 slot and total deadline through provider close`() =
        withFixture { tls -> withCurrentProjectedCatalogRefresh(tls) { it.originalBudgetAndCrossProfileSlot() } }

    @Test
    fun `projected current provider close failure keeps original custody against factory replacement`() =
        withFixture { tls -> withCurrentProjectedCatalogRefresh(tls) { it.providerCloseFailureKeepsOriginalSlot() } }

    @Test
    fun `projected current result needs known commit and actual release and failed originals never revive`() =
        withFixture { tls -> withCurrentProjectedCatalogRefresh(tls) { it.realCommitAndReleaseFailuresStaySealed() } }

    @Test
    fun `projected overlap current head revalidates both genuine signature slots`() =
        withFixture { tls -> withCurrentProjectedCatalogRefresh(tls, overlap = true) { it.genuineOverlapUsesBothExactSignatureSlots() } }

    @Test
    fun `joined genuine projected refresh admits exact generation lease transitions and refuses generation only drift`() =
        withFixture { tls -> withCurrentProjectedCatalogRefresh(tls) { it.joinedLeaseLifecycleAndGenerationFence() } }

    @Test
    fun `owned coordinator lease uses exact thirty second DB intervals and preserves nonlease state through renew release and reacquire`() =
        withFixture { tls -> withCoordinatorLease(tls) { CoordinatorLeaseCases(it).lifecycleAndUnrelatedState() } }

    @Test
    fun `owned coordinator lease separates local contention from DB expiry takeover and stale handles cannot change successors or wrap tokens`() =
        withPairedFixture { tls, peer ->
            withCoordinatorLease(tls) { original ->
                withCoordinatorLeasePeer(original, peer) { CoordinatorLeaseCases(original).contendersExpiryStaleHandlesAndOverflow(it) }
            }
        }

    @Test
    fun `owned coordinator lease rechecks every retained LIVE binding field and failed or substituted campaigns cannot revive`() =
        withFixture { tls -> withCoordinatorLease(tls) { CoordinatorLeaseCases(it).exactBindingAndFailedRenewalCannotRevive() } }

    @Test
    fun `owned coordinator lease samples DB time after real row lock waits and stays independent of exclusive epoch fence and later locks`() =
        withFixture { tls -> withCoordinatorLease(tls) { CoordinatorLeaseBoundaryCases(it).postLockClockAndFenceIndependence() } }

    @Test
    fun `owned coordinator lease releases no receipt before actual commit and cleanup or after SQL commit completion and quarantine failures`() =
        withFixture { tls -> withCoordinatorLease(tls) { CoordinatorLeaseBoundaryCases(it).sealedResultsCommitAndReleaseFailures() } }

    @Test
    fun `owned epoch rotation releases its committed request before a fresh same root capture waits for an independent shared holder`() =
        withFixture(epochRotation = true) { tls -> withEpochRotation(tls) { EpochRotationCases(it).requestReleaseAndFreshExclusiveCapture() } }

    @Test
    fun `owned epoch rotation returns exact durable retries and genuine commit refusals require a successor locked reread`() =
        withFixture(epochRotation = true) { tls -> withEpochRotation(tls) { EpochRotationCases(it).durableRetriesAndCommitRefusals() } }

    @Test
    fun `owned epoch rotation refuses expired lease and changed retained LIVE binding without altering the durable slot`() =
        withFixture(epochRotation = true) { tls -> withEpochRotation(tls) { EpochRotationCases(it).leaseAndBindingRefusals() } }

    @Test
    fun `owned epoch rotation shares one discovery deadline and failed requests cannot revive after cleanup`() =
        withFixture(epochRotation = true) { tls -> withEpochRotation(tls) { EpochRotationCases(it).discoveryDeadlineAndNonrevival() } }

    @Test
    fun `owned cutoff resolver includes every bounded historical publication and preserves APPLIED first proof in a stable manifest`() =
        withFixture(epochRotation = true) { tls ->
            withCutoffResolverHistory(tls, cutoffCount = 33, higherEpoch = true) { rotation, history ->
                CutoffResolverCases(rotation, history).use { it.nonemptyPagesAndAppliedFirstProof() }
            }
        }

    @Test
    fun `owned cutoff resolver retains unknown dispatch PREPARED and a genuine successor reads the same frozen key`() =
        withFixture(epochRotation = true) { tls ->
            withCutoffResolverHistory(tls) { rotation, history ->
                CutoffResolverCases(rotation, history).use { it.unknownDispatchAndFrozenKeySuccessor() }
            }
        }

    @Test
    fun `owned cutoff resolver refuses an encountered unsupported LIVE family rather than returning a partial manifest`() =
        withFixture(epochRotation = true) { tls ->
            withCutoffResolverHistory(tls, cutoffCount = 3) { rotation, history ->
                CutoffResolverCases(rotation, history).use { it.unsupportedFamilyRefusesWholeRange() }
            }
        }

    @Test
    fun `owned cutoff resolver rechecks full binding and DB lease across actual control and publication lock waits`() =
        withFixture(epochRotation = true) { tls ->
            withCutoffResolverHistory(tls) { rotation, history ->
                CutoffResolverCases(rotation, history).use { it.currentBindingAndLeaseLoss() }
            }
        }

    @Test
    fun `owned canonical seal links a complete nonempty cutoff preserves original successor intent and refuses stale entry authority`() =
        withFixture(epochRotation = true) { tls ->
            withCutoffResolverHistory(tls, cutoffCount = 2, higherEpoch = true) { rotation, history ->
                CutoffResolverCases(rotation, history).use { SealCanonicalCases(it).nonemptyIntentAndSuccessor() }
            }
        }

    @Test
    fun `owned canonical seal emits no result after actual control COMMIT refusal and a genuine successor rereads stored history`() =
        withFixture(epochRotation = true) { tls ->
            withCutoffResolverHistory(tls) { rotation, history ->
                CutoffResolverCases(rotation, history).use { SealCanonicalCases(it).deferredCommitRefusalAndSuccessor() }
            }
        }

    @Test
    fun `owned held seal commits and releases canonical intent before three STS calls and KMS while retaining lane and original issuer`() {
        val clock = HeldEpochSealClock()
        withFixture(epochRotation = true, nanoClock = clock) { tls ->
            withHeldEpochSeal(tls, clock, cutoffCount = 2) { HeldEpochSealCases(it).committedHeldAndFreshSuccessor() }
        }
    }

    @Test
    fun `owned held seal refuses rollback unknown commit and unreleased canonical completion before any sealer construction`() {
        HeldSealPreparationCut.entries.forEach { cut ->
            val clock = HeldEpochSealClock()
            withFixture(epochRotation = true, nanoClock = clock) { tls ->
                withHeldEpochSeal(tls, clock) { HeldEpochSealCases(it).prepareFailure(cut) }
            }
        }
    }

    @Test
    fun `owned held seal rejects missing D4 replacement lanes and real current campaign or binding loss across STS and KMS`() {
        HeldSealCurrentCut.entries.forEach { cut ->
            val clock = HeldEpochSealClock()
            withFixture(epochRotation = true, nanoClock = clock) { tls ->
                withHeldEpochSeal(tls, clock, d4 = cut != HeldSealCurrentCut.MISSING_D4) { HeldEpochSealCases(it).currentOwnerRefusal(cut) }
            }
        }
    }

    @Test
    fun `owned held seal preserves original J full session expiry and actual renewal dispatch limits through STS KMS and held cleanup`() {
        HeldSealBudgetCut.entries.forEach { cut ->
            val clock = HeldEpochSealClock()
            withFixture(epochRotation = true, nanoClock = clock) { tls ->
                withHeldEpochSeal(tls, clock) { HeldEpochSealBudgetCases(it).exhausted(cut) }
            }
        }
        val clock = HeldEpochSealClock()
        withFixture(epochRotation = true, nanoClock = clock) { tls ->
            withHeldEpochSeal(tls, clock) { HeldEpochSealBudgetCases(it).heldExpiryStillCloses() }
        }
    }

    @Test
    fun `owned held seal closes every arrived partial STS KMS resource and retains sticky unreturned construction or native close custody`() {
        HeldSealPartialCut.entries.forEach { cut ->
            val clock = HeldEpochSealClock()
            withFixture(epochRotation = true, nanoClock = clock) { tls ->
                withHeldEpochSeal(tls, clock) { HeldEpochSealCleanupCases(it).partial(cut) }
            }
        }
        val clock = HeldEpochSealClock()
        withFixture(epochRotation = true, nanoClock = clock) { tls ->
            withHeldEpochSeal(tls, clock) { HeldEpochSealCleanupCases(it).stickyNativeClose() }
        }
    }

    @Test
    fun `owned held seal foreign close shutdown and interruption leave native cleanup to the original caller without closing borrowed lanes`() {
        HeldSealStopCut.entries.forEach { cut ->
            val clock = HeldEpochSealClock()
            withFixture(epochRotation = true, nanoClock = clock) { tls ->
                withHeldEpochSeal(tls, clock) { HeldEpochSealCleanupCases(it).foreignStop(cut) }
            }
        }
    }

    @Test
    fun `catalog author fixed login opens only its original TLS coordinator and no legacy or unrelated phase`() = withFixture { tls ->
        withCatalogGenesisFreeze(tls) { CatalogGenesisFreezeCases(it).fixedAuthorRootAndLeastPrivilege() }
    }

    @Test
    fun `catalog author real PREPARE and SDK Sign retain exact bytes before CAS and only released pin freezes without another Sign`() = withFixture { tls ->
        withCatalogGenesisFreeze(tls) { CatalogGenesisFreezeCases(it).signedAwaitingReleaseAndExactPinReuse() }
    }

    @Test
    fun `catalog author complete unsigned receipt permits fresh prefreeze Sign after provider failure without charging again`() = withFixture { tls ->
        withCatalogGenesisFreeze(tls) { CatalogGenesisFreezeCases(it).preparedUnsignedResignEligibility() }
    }

    @Test
    fun `catalog author unresolved persistence arm refuses reSign with unsigned and restored absent SQL`() = withFixture { tls ->
        withCatalogGenesisFreeze(tls) { CatalogGenesisFreezeCases(it).unresolvedPersistenceArmCannotResign() }
    }

    @Test
    fun `catalog author signature commit completion failure recovers only exact retained bytes without Sign or new charge`() = withFixture { tls ->
        withCatalogGenesisFreeze(tls) { CatalogGenesisFreezeCases(it).signatureCompletionFailureReusesFrozenBytes() }
    }

    @Test
    fun `catalog author original begin budget and throwing signer cleanup never release a result or revive the owner`() = withFixture { tls ->
        withCatalogGenesisFreeze(tls) { CatalogGenesisFreezeCases(it).originalBudgetAndSignerCleanup() }
    }

    @Test
    fun `initial TARGET D7 bootstraps G1 and keeps one campaign across freeze continuation and no Sign resume`() = withFixture { tls ->
        withCatalogSignerRotationInitialAuthor(tls) { CatalogSignerRotationInitialAuthorCases(it).bootstrapAndSameSessionContinuation() }
    }

    @Test
    fun `initial TARGET D7 ACQUIRE UNKNOWN stays retained after original root retirement`() = withFixture { tls ->
        withCatalogSignerRotationInitialAuthor(tls) { CatalogSignerRotationInitialAuthorCases(it).acquireUnknownSurvivesOriginalRootRetirement() }
    }

    @Test
    fun `initial TARGET D7 original bootstrap budget cancellation and child fatal keep their session blocked`() {
        CatalogSignerRotationInitialAuthorCut.entries.forEach { cut ->
            withFixture { tls ->
                withCatalogSignerRotationInitialAuthor(tls) { CatalogSignerRotationInitialAuthorCases(it).originalBudgetAndSignals(cut) }
            }
        }
    }

    @Test
    fun `D7 parsed bootstrap and genuine G1 refresh freeze signed PREPARED overlap2 without advancing head`() = withFixture { tls ->
        withCatalogSignerRotationFreeze(tls) { CatalogSignerRotationFreezeCases(it).parsedBootstrapAndSignedPrepared() }
    }

    @Test
    fun `D7 writer cannot retrofit an already selected D2 deployment`() = withFixture { tls ->
        withCatalogSignerRotationFreeze(tls, profile = "D2") { CatalogSignerRotationFreezeCases(it).noRetrofitSelectedD2() }
    }

    @Test
    fun `signer rotation author rejects changed full B and predecessor before prepare or sign`() = withFixture { tls ->
        withCatalogSignerRotationFreeze(tls) { CatalogSignerRotationFreezeCases(it).changedFullBindingAndPredecessor() }
    }

    @Test
    fun `signer rotation author rejects wrong stale reordered or unallowlisted exact approvals`() = withFixture { tls ->
        withCatalogSignerRotationFreeze(tls) { CatalogSignerRotationFreezeCases(it).exactApprovalRefusals() }
    }

    @Test
    fun `signer rotation missing conflicting and uncertain custody never grants either Sign`() = withFixture { tls ->
        withCatalogSignerRotationFreeze(tls) { CatalogSignerRotationFreezeCases(it).missingConflictingAndUncertainCustody() }
    }

    @Test
    fun `signer rotation signature completion failure keeps frozen signatures and exact resume reuses bytes`() = withFixture { tls ->
        withCatalogSignerRotationFreeze(tls) { CatalogSignerRotationFreezeCases(it).signatureCompletionFailureReusesFrozenBytes() }
    }

    @Test
    fun `signer rotation original budget and signer cleanup refuse without a fresh allowance`() {
        CatalogSignerRotationCleanupCut.entries.forEach { cut ->
            withFixture { tls ->
                withCatalogSignerRotationFreeze(tls) { CatalogSignerRotationFreezeCases(it).originalBudgetAndSignerCleanup(cut) }
            }
        }
    }

    @Test
    fun `known unattempted second Sign continuation preserves signature one and uses only the new signer`() = withFixture { tls ->
        withCatalogSignerRotationFreeze(tls) { CatalogSignerRotationContinuationCases(it).continueKnownSecondSign() }
    }

    @Test
    fun `second Sign continuation refuses attempted uncertain stale or expired authority`() {
        CatalogSignerRotationContinuationCut.entries.forEach { cut ->
            withFixture { tls ->
                withCatalogSignerRotationFreeze(tls) { CatalogSignerRotationContinuationCases(it).continuationRefusals(cut) }
            }
        }
    }

    @Test
    fun `fresh TARGET D7 recovers both returned PREPARED2 with a new actual lease and no Sign`() =
        CatalogSignerRotationPreparedRecoveryCases.bothReturnedVariants { withFixture(test = it) }

    @Test
    fun `fresh PREPARED2 recovery refuses live historical regressed and maximum lease tokens before replay`() {
        CatalogSignerRotationRecoveryLeaseCut.entries.forEach { cut ->
            withFixture { tls ->
                withCatalogSignerRotationFreeze(tls) { CatalogSignerRotationPreparedRecoveryCases(it).historicalLeaseRefusal(cut) }
            }
        }
    }

    @Test
    fun `fresh PREPARED2 recovery rejects missing custody changed D regressed signature and fenced capacity race`() {
        CatalogSignerRotationRecoveryIdentityCut.entries.forEach { cut ->
            withFixture { tls ->
                withCatalogSignerRotationFreeze(tls) { CatalogSignerRotationPreparedRecoveryCases(it).custodyAndIdentityRefusal(cut) }
            }
        }
    }

    @Test
    fun `fresh PREPARED2 original SQL cleanup and ACQUIRE UNKNOWN remain sticky after physical retirement`() {
        CatalogSignerRotationRecoverySqlCut.entries.forEach { cut ->
            withFixture { tls ->
                withCatalogSignerRotationFreeze(tls) { CatalogSignerRotationPreparedRecoveryCases(it).originalSqlUncertainty(cut) }
            }
        }
    }

    @Test
    fun `fresh PREPARED2 original budget provider close and post lease cancellation cannot mint replay success`() {
        CatalogSignerRotationRecoveryProviderCut.entries.forEach { cut ->
            withFixture { tls ->
                withCatalogSignerRotationFreeze(tls) { CatalogSignerRotationPreparedRecoveryCases(it).originalBudgetAndProviderFailure(cut) }
            }
        }
    }

    @Test
    fun `fixed overlap2 lost ACK and lag recover read only before same owner COMPLETE and separate PROJECT`() = withFixture { tls ->
        withCatalogSignerRotationDelivery(tls) { CatalogSignerRotationDeliveryCases(it).lostAcknowledgementLagAndFreshReadOnlyCompletion() }
    }

    @Test
    fun `fixed overlap2 refuses current full B gates frozen bytes and live historical lease before PUT`() = withFixture { tls ->
        withCatalogSignerRotationDelivery(tls) {
            CatalogSignerRotationDeliveryFailureCases(it).currentBindingGatesFrozenTupleAndLiveLeaseRefuse()
        }
    }

    @Test
    fun `fixed overlap2 native cleanup and spent arm keep original custody without another PUT or Sign`() = withFixture { tls ->
        withCatalogSignerRotationDelivery(tls) {
            CatalogSignerRotationDeliveryFailureCases(it).nativeCleanupAndSpentArmNeverGrantAnotherPut()
        }
    }

    @Test
    fun `fixed overlap2 duplicate marker version retention and byte conflicts cannot COMPLETE or reput`() = withFixture { tls ->
        withCatalogSignerRotationDelivery(tls) { CatalogSignerRotationDeliveryCases(it).rawCopyConflictsCannotCompleteOrReput() }
    }

    @Test
    fun `fixed overlap2 cold pending2 needs fresh actual lease before separate PROJECT`() = withFixture { tls ->
        withCatalogSignerRotationDelivery(tls) { CatalogSignerRotationColdRecoveryCases(it).knownPendingNeedsFreshLease() }
    }

    @Test
    fun `fixed overlap2 cold COMPLETE arm reconciles actual prepared or pending without repeating committed effects`() {
        CatalogSignerRotationColdCommitCut.entries.forEach { cut ->
            withFixture { tls ->
                withCatalogSignerRotationDelivery(tls) { CatalogSignerRotationColdRecoveryCases(it).completeArmReconcilesActualState(cut) }
            }
        }
    }

    @Test
    fun `fixed overlap2 cold PROJECT arm reconciles actual pending or projected without rewriting provenance`() {
        CatalogSignerRotationColdCommitCut.entries.forEach { cut ->
            withFixture { tls ->
                withCatalogSignerRotationDelivery(tls) { CatalogSignerRotationColdRecoveryCases(it).projectArmReconcilesActualState(cut) }
            }
        }
    }

    @Test
    fun `fixed overlap2 cold recovery refuses partial custody and contradictory pending or raw copy evidence`() {
        CatalogSignerRotationColdRefusalCut.entries.forEach { cut ->
            withFixture { tls ->
                withCatalogSignerRotationDelivery(tls) { CatalogSignerRotationColdRecoveryRefusalCases(it).refuses(cut) }
            }
        }
    }

    @Test
    fun `fixed activation3 produces new key only from projected overlap2 and refuses its live historical lease`() = withFixture { tls ->
        withCatalogSignerRotationActivation(tls) { CatalogSignerRotationActivationCases(it).producesFromProjectedOverlap() }
    }

    @Test
    fun `fixed activation3 lost PUT ACK lag and retained signature UNKNOWN recover without another Sign or PUT`() {
        withFixture { tls ->
            withCatalogSignerRotationActivation(tls) { CatalogSignerRotationActivationCases(it).lostAcknowledgementAndLag() }
        }
        withFixture { tls ->
            withCatalogSignerRotationActivation(tls) {
                CatalogSignerRotationActivationRecoveryCases(it).retainedSignaturePersistsWithoutAnotherSignOrPut()
            }
        }
    }

    @Test
    fun `fixed activation3 COMPLETE and PROJECT gaps reconcile actual state without repairing old acquisition outcomes`() {
        val stages = listOf(CatalogSignerRotationActivationCommitStage.COMPLETE, CatalogSignerRotationActivationCommitStage.PROJECT)
        for (stage in stages) {
            for (cut in CatalogSignerRotationColdCommitCut.entries) {
                withFixture { tls ->
                    withCatalogSignerRotationActivation(tls) { CatalogSignerRotationActivationRecoveryCases(it).completeOrProjectGap(stage, cut) }
                }
            }
        }
    }

    @Test
    fun `fixed activation3 rejects wrong predecessor signer policy control drift and extra bounded history`() {
        withFixture { tls ->
            withCatalogSignerRotationActivation(tls) { CatalogSignerRotationActivationRefusalCases(it).wrongPredecessorAndSignerPolicy() }
        }
        withFixture { tls ->
            withCatalogSignerRotationActivation(tls) { CatalogSignerRotationActivationRefusalCases(it).independentLeaseOwnerAndPendingTokenDrift() }
        }
        withFixture { tls ->
            withCatalogSignerRotationActivation(tls) { CatalogSignerRotationActivationRefusalCases(it).boundedHistoryRejectsExtraProjectedFourthRow() }
        }
    }

    @Test
    fun `fixed activation3 known unattempted PREPARE uses one same process witness new lease and no recharge`() = withFixture { tls ->
        withCatalogSignerRotationActivation(tls) { CatalogSignerRotationActivationContinuationCases(it).knownUnattemptedSameProcessOnly() }
    }

    @Test
    fun `complete and partial delivery history blocks lower both returned resume and known unattempted second Sign`() {
        for (firstOnly in listOf(false, true)) {
            withFixture { tls ->
                withCatalogSignerRotationFreeze(tls) { CatalogSignerRotationContinuationCases(it).deliveryHistoryCannotReenter(firstOnly) }
            }
        }
    }

    @Test
    fun frozenReleaseFirstDAndTargetFinalize() = withFixture { tls ->
        withCatalogGenesisTargetFinalize(tls, profile = "D4") { CatalogGenesisTargetFinalizeCases(it).frozenReleaseFirstDAndTargetFinalize() }
    }

    @Test
    fun targetCliUsesOriginalFrozenRequestAndOwner() = withFixture { tls ->
        withCatalogGenesisTargetFinalize(tls, profile = "D4") { CatalogGenesisTargetFinalizeCases(it).targetCliUsesOriginalFrozenRequestAndOwner() }
    }

    @Test
    fun custodyFailurePreventsComplete() = withFixture { tls ->
        withCatalogGenesisTargetFinalize(tls) { CatalogGenesisTargetFinalizeCases(it).custodyFailurePreventsComplete() }
    }

    @Test
    fun freshReadbackClassifiesEveryResume() = withFixture { tls ->
        withCatalogGenesisTargetFinalize(tls) { CatalogGenesisTargetFinalizeCases(it).freshReadbackClassifiesEveryResume() }
    }

    @Test
    fun lockedCurrentDAndOriginalBarrierRequired() = withFixture { tls ->
        withCatalogGenesisTargetFinalize(tls) { CatalogGenesisTargetFinalizeCases(it).lockedCurrentDAndOriginalBarrierRequired() }
    }

    @Test
    fun originalBudgetAndProviderCloseStaySticky() = withFixture { tls ->
        withCatalogGenesisTargetFinalize(tls) { CatalogGenesisTargetFinalizeCases(it).originalBudgetAndProviderCloseStaySticky() }
    }

    @Test
    fun unknownCommitOrReleaseNeverSucceeds() = withFixture { tls ->
        withCatalogGenesisTargetFinalize(tls) { CatalogGenesisTargetFinalizeCases(it).unknownCommitOrReleaseNeverSucceeds() }
    }

    @Test
    fun `catalog publisher genuine freeze first D and current locked recheck precede one primary PUT and immutable dual copies`() = withFixture { tls ->
        withCatalogGenesisPublish(tls) { CatalogGenesisPublishCases(it).frozenFirstDRecheckAndSinglePrimaryPublication() }
    }

    @Test
    fun publisherCliUsesOriginalFrozenRequestAndReadOnlyRecovery() = withFixture { tls ->
        withCatalogGenesisPublish(tls) { CatalogGenesisPublishCliCases(it).publisherCliUsesOriginalFrozenRequestAndReadOnlyRecovery() }
    }

    @Test
    fun `catalog publisher rejects absent different and stale current D or a different actual graph and wrong fixed login`() = withFixture { tls ->
        withCatalogGenesisPublish(tls) { CatalogGenesisPublishCases(it).currentSelectedStateAndActualGraphRequired() }
    }

    @Test
    fun `catalog publisher requires independent pin complete existing custody raw signature and empty namespaces without repairs`() = withFixture { tls ->
        withCatalogGenesisPublish(tls) { CatalogGenesisPublishCases(it).independentPinExistingCustodyAndEmptyProbeRequired() }
    }

    @Test
    fun `catalog publisher actual recheck completion late read preparation and namespace close failures prevent arming or PUT`() = withFixture { tls ->
        withCatalogGenesisPublish(tls) { CatalogGenesisPublishCases(it).recheckCompletionAndNamespaceCloseAreBarriers() }
    }

    @Test
    fun `catalog publisher cancellation and reader-only expiry after durable arm never dispatch or recover into another PUT`() = withFixture { tls ->
        withCatalogGenesisPublish(tls) { CatalogGenesisPublishCases(it).durableArmCancellationAndEmptyRecoveryNeverReput() }
        withCatalogGenesisPublish(tls, catalogAttemptMillis = 10_000) { CatalogGenesisPublishCases(it).readerCapAtReturnedPutClientNeverDispatchesOrReputs() }
    }

    @Test
    fun `catalog publisher lost acknowledgement recovers pending completed and dual copies without rewriting stable provenance`() = withFixture { tls ->
        withCatalogGenesisPublish(tls) { CatalogGenesisPublishCases(it).lostAcknowledgementAndReplicationRecoveryAreStable() }
    }

    @Test
    fun `catalog publisher acknowledged outcome precedes cancelled readback and survives conflicting fresh observations`() = withFixture { tls ->
        withCatalogGenesisPublish(tls) { CatalogGenesisPublishCases(it).acknowledgedOutcomeSurvivesCancelledReadbackAndConflicts() }
    }

    @Test
    fun `catalog publisher original preacquisition deadline and fatal PUT close cannot advance or refund effect eligibility`() = withFixture { tls ->
        withCatalogGenesisPublish(tls) { CatalogGenesisPublishCases(it).originalDeadlineAndFatalPutCloseCannotAdvance() }
    }

    @Test
    fun `desired installer fixed operator opens only its real TLS coordinator and permanently refuses every unrelated route`() = withFixture { tls ->
        withDesiredInstallation(tls) { DesiredInstallationOperatorLifecycleTest.connected(it) }
    }

    @Test
    fun `desired installer authenticates separately from denied runtime and bootstrap commits only genuine D with read only retry`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintDesiredInstallationCases(it).authenticationBootstrapAndReadOnlyRetry() }
    }

    @Test
    fun `desired installer bootstrap reaches actual signed G1 SDK completion and projection with scan requested and intact initial guards`() =
        withFixture { tls ->
            withDesiredInstallation(tls) { ComplaintDesiredInstallationCases(it).genuineGenesisKeepsScanRequestedAndGuardsInitialState() }
        }

    @Test
    fun `desired installer supersession fences the live old full binding and exact retry preserves a genuinely acquired later lease`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintDesiredInstallationCases(it).supersessionFencesLiveCampaignAndRetryPreservesLaterLease() }
    }

    @Test
    fun `desired installer identical authenticated contender between released phases never repeats the lease fence`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintDesiredInstallationCases(it).twoAuthenticatedContenders(identical = true) }
    }

    @Test
    fun `desired installer different authenticated contender cannot borrow the original expected old binding`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintDesiredInstallationCases(it).twoAuthenticatedContenders(identical = false) }
    }

    @Test
    fun `desired installer pins read committed under role repeatable read and sees genuine pending history after an actual control lock wait`() =
        withFixture { tls ->
            withDesiredInstallation(tls) { ComplaintDesiredInstallationCases(it).readCommittedSeesPendingHistoryAfterActualControlWait() }
        }

    @Test
    fun `desired installer keeps phase one closure durable rejects changed old catalog binding and only a fresh owner recovers`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintDesiredInstallationCases(it).phaseOneInterruptionStaleOldBindingAndFreshRetry() }
    }

    @Test
    fun `desired installer refuses pending mutations projection and populated rotation or seal slots after durable gate closure`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintDesiredInstallationRefusalCases(it).pendingMutationProjectionAndSlots() }
    }

    @Test
    fun `desired installer refuses token and generation maxima and projected NULL D without a bootstrap reset escape`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintDesiredInstallationRefusalCases(it).maximaAndProjectedNullD() }
    }

    @Test
    fun `desired installer genuine deferred commit failure stays unknown and only a fresh authenticated bootstrap can recover`() = withFixture { tls ->
        withDesiredInstallation(tls) {
            ComplaintDesiredInstallationCompletionCases(it).commitAndReleaseFailure(DesiredInstallationCompletionCut.DEFERRED_COMMIT)
        }
    }

    @Test
    fun `desired installer afterCommit failure never returns success and fresh exact retry cannot rewrite committed state`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintDesiredInstallationCompletionCases(it).commitAndReleaseFailure(DesiredInstallationCompletionCut.AFTER_COMMIT) }
    }

    @Test
    fun `desired installer unresolved Spring release remains quarantined and failed original cannot revive after truthful cleanup`() = withFixture { tls ->
        withDesiredInstallation(tls) {
            ComplaintDesiredInstallationCompletionCases(it).commitAndReleaseFailure(DesiredInstallationCompletionCut.UNRESOLVED_RELEASE)
        }
    }

    /** A test-only contention pair, not a supported multi-instance deployment or another database lifecycle. */
    @Test
    fun `signed G1 first D authenticates least privilege selects only D and timestamp and exact retry remains read only`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintSignedGenesisFirstDCases(it).exactSignedFirstSelectionAndRetry() }
    }

    @Test
    fun `signed G1 first D refuses unsigned mismatched oversized and copy bearing history even for an identical selected target`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintSignedGenesisFirstDCases(it).unsignedMismatchedAndOversizedHistory() }
    }

    @Test
    fun `signed G1 first D requires sole all history and exact initial LIVE control without scope lease seal or head escape`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintSignedGenesisFirstDCases(it).allHistoryAndNoninitialControlAreRefused() }
    }

    @Test
    fun `signed G1 first D genuine epoch and catalog lock conflicts refuse before history without any mutation`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintSignedGenesisFirstDCases(it).epochAndCatalogLocksRefuseBeforeHistory() }
    }

    @Test
    fun `signed G1 first D pins actual read committed and sees genuine signature writer commit after unchanged control lock wait`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintSignedGenesisFirstDConcurrencyCases(it).signatureCommitVisibleAfterControlWait() }
    }

    @Test
    fun `signed G1 first D identical authenticated contender waits on LIVE and never repeats the D timestamp write`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintSignedGenesisFirstDConcurrencyCases(it).authenticatedContenders(identical = true) }
    }

    @Test
    fun `signed G1 first D different authenticated contender waits on LIVE and refuses the other selected target`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintSignedGenesisFirstDConcurrencyCases(it).authenticatedContenders(identical = false) }
    }

    @Test
    fun `signed G1 first D actual deferred commit failure stays unknown and only a fresh original owner may select`() = withFixture { tls ->
        withDesiredInstallation(tls) {
            ComplaintSignedGenesisFirstDCompletionCases(it).failureCannotEmitSuccess(DesiredInstallationCompletionCut.DEFERRED_COMMIT)
        }
    }

    @Test
    fun `signed G1 first D afterCommit failure cannot emit success and fresh exact retry preserves every committed byte`() = withFixture { tls ->
        withDesiredInstallation(tls) { ComplaintSignedGenesisFirstDCompletionCases(it).failureCannotEmitSuccess(DesiredInstallationCompletionCut.AFTER_COMMIT) }
    }

    @Test
    fun `signed G1 first D unresolved original release stays quarantined and truthful cleanup cannot revive its failed owner`() = withFixture { tls ->
        withDesiredInstallation(tls) {
            ComplaintSignedGenesisFirstDCompletionCases(it).failureCannotEmitSuccess(DesiredInstallationCompletionCut.UNRESOLVED_RELEASE)
        }
    }

    @Test
    fun testActivationPreparesExactUnsignedRowAndChargesOnlyOnce() = withFixture(testActivation = true) {
        CatalogTestRunActivationPreparedCases.prepareAndReload(it)
    }

    @Test
    fun testActivationFreshNamedOwnerReloadsExactPreparedAfterRealLeaseExpiry() = withFixture(testActivation = true) {
        CatalogTestRunActivationPreparedCases.freshOwnerRecovery(it)
    }

    @Test
    fun testActivationClosedPurposeAndNakedInputsRefuseBeforeSql() = withFixture(testActivation = true) {
        CatalogTestRunActivationBoundaryCases.closedPurposeAndNakedInputs(it)
    }

    @Test
    fun testActivationRefusesRawHistoryControlPolicyAndFutureReserveDrift() {
        TestActivationRefusalCut.entries.forEach { cut ->
            withFixture(testActivation = true) {
                CatalogTestRunActivationBoundaryCases.refusesUntrustedOrInsufficientPreimage(it, cut)
            }
        }
    }

    @Test
    fun testActivationSharedMaintenanceContentionRefusesBeforeEpochAndFreshAttemptSucceeds() = withFixture(testActivation = true) {
        CatalogTestRunActivationBoundaryCases.sharedMaintenanceBlocksBeforeEpochAndFreshAttemptSucceeds(it)
    }

    @Test
    fun testActivationCommitCancellationAndHttpCloseFailuresRetainOriginalCustody() {
        TestActivationCustodyCut.entries.forEach { cut ->
            withFixture(testActivation = true) {
                CatalogTestRunActivationBoundaryCases.failuresRetainOriginalCustody(it, cut)
            }
        }
    }

    @Test
    fun testActivationSignedPreparedUsesCurrentStableSignerAndColdExactReload() {
        for (prefix in listOf(ActivationEvidencePrefix.GENESIS, ActivationEvidencePrefix.ROTATED, ActivationEvidencePrefix.INVENTORY_ROTATED)) {
            withFixture(testActivation = true) { CatalogTestRunActivationSignedCases.stablePrefixAndExactReload(it, prefix) }
        }
    }

    @Test
    fun testActivationUnsignedDiagnosticAndColdRowsCannotGrantSign() = withFixture(testActivation = true) {
        CatalogTestRunActivationSignedCases.diagnosticAndColdUnsignedRefuse(it)
    }

    @Test
    fun testActivationUnreturnedDurableSignArmsNeverResign() {
        TestActivationSignedUnreturnedCut.entries.forEach { cut ->
            withFixture(testActivation = true) { CatalogTestRunActivationSignedRecoveryCases.unreturnedArmCannotSignAgain(it, cut) }
        }
    }

    @Test
    fun testActivationSavedReturnedBytesRecoverActualSqlGapsWithoutResign() {
        TestActivationSignedSqlCut.entries.forEach { cut ->
            val clock = DesiredInstallationTestClock()
            withFixture(testActivation = true, nanoClock = clock) { CatalogTestRunActivationSignedRecoveryCases.savedBytesRecoverWithoutResign(it, clock, cut) }
        }
    }

    @Test
    fun testActivationSignedOriginalBudgetCancellationAndFileCleanupStayClosed() {
        TestActivationSignedLifecycleCut.entries.forEach { cut ->
            val clock = DesiredInstallationTestClock()
            withFixture(testActivation = true, nanoClock = clock) { CatalogTestRunActivationSignedRecoveryCases.originalLifecycleCannotRevive(it, clock, cut) }
        }
    }

    @Test
    fun testActivationSignedPathRejectsKeyScopeFullDAndCanonicalPredecessorDrift() {
        TestActivationSignedInputCut.entries.forEach { cut ->
            withFixture(testActivation = true) { CatalogTestRunActivationSignedCases.inputRefusal(it, cut) }
        }
    }

    @Test
    fun testActivationSignedPathRetainsSameAllocationAndPostSignBindingRaces() {
        TestActivationSignedRaceCut.entries.forEach { cut ->
            withFixture(testActivation = true) { CatalogTestRunActivationSignedCases.originalAndAllocationRaces(it, cut) }
        }
    }

    @Test
    fun testActivationColdSignedRecoveryRejectsPartialCustodyAndChangedRows() {
        TestActivationSignedCorruptionCut.entries.forEach { cut ->
            withFixture(testActivation = true) { CatalogTestRunActivationSignedCases.coldCorruptionRefusesWithoutRepair(it, cut) }
        }
    }

    @Test
    fun testActivationCompletesWithOneConditionalPrimaryPutAndColdExactPendingReload() {
        for (prefix in listOf(ActivationEvidencePrefix.GENESIS, ActivationEvidencePrefix.ROTATED, ActivationEvidencePrefix.INVENTORY_ROTATED)) {
            withFixture(testActivation = true) { CatalogTestRunActivationCompletionCases.stablePrefixAndColdPending(it, prefix) }
        }
    }

    @Test
    fun testActivationLostPutAcknowledgementAndReplicaLagRecoverWithoutReput() = withFixture(testActivation = true) {
        CatalogTestRunActivationCompletionCases.lostAcknowledgementLagAndReadOnlyRecovery(it)
    }

    @Test
    fun testActivationAcknowledgedReplicaLagAndStrictPendingReloadDoNotAdoptPrepared() = withFixture(testActivation = true) {
        CatalogTestRunActivationCompletionCases.acknowledgedReplicaLagKeepsItsArmAndPendingReloadCannotAdoptPrepared(it)
    }

    @Test
    fun testActivationConflictingRawCopiesNeverCompleteOrReput() {
        TestActivationCompletionCopyCut.entries.forEach { cut ->
            withFixture(testActivation = true) { CatalogTestRunActivationCompletionCases.rawCopyConflictsNeverCompleteOrReput(it, cut) }
        }
    }

    @Test
    fun testActivationUnreturnedPutArmsNeverRepublishOrRepairOriginalCleanup() {
        TestActivationPutUnreturnedCut.entries.forEach { cut ->
            withFixture(testActivation = true) { CatalogTestRunActivationCompletionRecoveryCases.unreturnedPutArmCannotRepublish(it, cut) }
        }
    }

    @Test
    fun testActivationCompletionSqlGapsRecoverWithoutOldOutcomeRepair() {
        TestActivationCompleteSqlCut.entries.forEach { cut ->
            val clock = DesiredInstallationTestClock()
            withFixture(testActivation = true) { CatalogTestRunActivationCompletionRecoveryCases.completeSqlGapsRecoverWithoutOldOutcomeRepair(it, clock, cut) }
        }
    }

    @Test
    fun testActivationCompletionBudgetCancellationAndFileCleanupStayClosed() {
        TestActivationCompleteLifecycleCut.entries.forEach { cut ->
            val clock = DesiredInstallationTestClock()
            withFixture(testActivation = true) { CatalogTestRunActivationCompletionRecoveryCases.originalBudgetCancellationAndFileCleanupCannotRevive(it, clock, cut) }
        }
    }

    @Test
    fun testActivationUnsignedDiagnosticCannotGrantDeliveryOrCompletion() = withFixture(testActivation = true) {
        CatalogTestRunActivationCompletionBoundaryCases.unsignedDiagnosticCannotPublish(it)
    }

    @Test
    fun testActivationDeliveryRefusesLiveLeaseUnarmedRecoveryFullDGlobalDAndSignedRowDrift() {
        TestActivationDeliveryBoundaryCut.entries.forEach { cut ->
            withFixture(testActivation = true) { CatalogTestRunActivationCompletionBoundaryCases.prePutRefusal(it, cut) }
        }
    }

    @Test
    fun testActivationPostPutGlobalLeaseAndScopeRacesRefuseCompletion() {
        TestActivationDeliveryRaceCut.entries.forEach { cut ->
            withFixture(testActivation = true) { CatalogTestRunActivationCompletionBoundaryCases.postPutBindingRace(it, cut) }
        }
    }

    @Test
    fun testActivationPendingReloadRejectsChangedExactHeadTokenAndCompletionTuple() {
        TestActivationPendingReplayCut.entries.forEach { cut ->
            withFixture(testActivation = true) { CatalogTestRunActivationCompletionBoundaryCases.pendingReloadRefusesChangedExactPair(it, cut) }
        }
    }

    @Test
    fun testActivationProjectsAtomicPaidEffectAndRetainsClosedGates() = withFixture(testActivation = true) {
        CatalogTestRunActivationProjectionCases.atomicTenRowEffect(it, ActivationEvidencePrefix.INVENTORY_ROTATED)
    }

    @Test
    fun testActivationProjectionHonorsCapacityAndColdReplayDoesNotChargeAgain() {
        for (cut in listOf(TestActivationProjectionCapacityCut.EXACT_CREATION, TestActivationProjectionCapacityCut.ONE_OVER_CREATION)) {
            withFixture(testActivation = true) { CatalogTestRunActivationProjectionCases.capacityBoundary(it, cut) }
        }
    }

    @Test
    fun testActivationProjectionRechecksOriginalDCorePolicyHistoryAndRawBytes() {
        for (cut in listOf(
            TestActivationProjectionBindingCut.FULL_D,
            TestActivationProjectionBindingCut.GLOBAL_CORE_AFTER_CAPTURE,
            TestActivationProjectionBindingCut.CAPACITY_P_AFTER_CAPTURE,
            TestActivationProjectionBindingCut.HISTORY_AFTER_CAPTURE,
            TestActivationProjectionBindingCut.DIFFERENT_SIGNED_RAW,
        )) {
            withFixture(testActivation = true) { CatalogTestRunActivationProjectionCases.bindingRefusal(it, cut) }
        }
    }

    @Test
    fun testActivationProjectionRejectsForeignOrExhaustedLeasesAndConcurrentRoot() {
        for (cut in listOf(
            TestActivationProjectionBoundaryCut.FOREIGN_LEASE_AFTER_CAPTURE,
            TestActivationProjectionBoundaryCut.MAX_LEASE,
            TestActivationProjectionBoundaryCut.CONCURRENT_ROOT,
        )) {
            withFixture(testActivation = true) { CatalogTestRunActivationProjectionCases.lockingAndLeaseRefusal(it, cut) }
        }
    }

    @Test
    fun testActivationProjectionRollbackAndKnownCommitKeepOriginalOutcomeCustody() {
        for (cut in listOf(
            TestActivationProjectionSqlCut.BEFORE_PROJECT_ARM,
            TestActivationProjectionSqlCut.AFTER_COUNTERS,
            TestActivationProjectionSqlCut.AUDIT_TWO,
            TestActivationProjectionSqlCut.AUDIT_FOUR,
            TestActivationProjectionSqlCut.BEFORE_COMMIT,
            TestActivationProjectionSqlCut.DEFERRED_COMMIT_ROLLBACK_UNKNOWN,
            TestActivationProjectionSqlCut.KNOWN_COMMITTED_AFTER_COMMIT,
        )) {
            val clock = DesiredInstallationTestClock()
            withFixture(testActivation = true) { CatalogTestRunActivationProjectionRecoveryCases.sqlCut(it, clock, cut) }
        }
    }

    @Test
    fun testActivationProjectionLostCommitResponseRetainsUnknownAndColdReplaysReadOnly() {
        PgLifecycleTlsCommitForwarder(database.value).use { forwarder ->
            forwarder.start()
            VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, endpointPort = forwarder.port).use { tls ->
                tls.bind()
                assertTestActivationProjectLostCommitResponse(tls, forwarder)
            }
        }
    }

    @Test
    fun testActivationProjectionColdReplayRejectsPartialEffectsAndContradictoryCustody() {
        for (cut in listOf(
            TestActivationProjectionReplayCut.PREPARED,
            TestActivationProjectionReplayCut.MISSING_COMPLETION_OUTCOMES,
            TestActivationProjectionReplayCut.MISSING_PROJECT_ARM,
            TestActivationProjectionReplayCut.PARTIAL_NOTICE,
            TestActivationProjectionReplayCut.COHERENT_COUNTER_DRIFT,
            TestActivationProjectionReplayCut.OLD_PENDING_CATALOG_BACKUP,
            TestActivationProjectionReplayCut.CONTRADICTORY_PROJECT_OUTCOME,
            TestActivationProjectionReplayCut.PROJECT_LEASE_FLOOR,
        )) {
            val clock = DesiredInstallationTestClock()
            withFixture(testActivation = true) { CatalogTestRunActivationProjectionRecoveryCases.coldReplay(it, clock, cut) }
        }
    }

    @Test
    fun testActivationProjectionRetainsOriginalDeadlineCancellationAndCleanupFailures() {
        for (cut in listOf(
            TestActivationProjectionLifetimeCut.SECOND_RAW_DEADLINE,
            TestActivationProjectionLifetimeCut.CANCELLATION_AFTER_RUN,
            TestActivationProjectionLifetimeCut.ROOT_CLOSE,
            TestActivationProjectionLifetimeCut.PHASE_RELEASE,
        )) {
            val clock = DesiredInstallationTestClock()
            withFixture(testActivation = true) { CatalogTestRunActivationProjectionRecoveryCases.originalLifetime(it, clock, cut) }
        }
    }

    @Test
    fun testRegistrationFirstFreshUsesOwnRootAndAdapterWithoutReopening() = withFixture(testActivation = true) {
        ComplaintTestNamespaceRegistrationCases.firstFreshBindingIsConsumedByInstallation(it, shutdownRoot = false)
    }

    @Test
    fun testRegistrationLifetimeEndsWithOriginalRuntimeRoot() = withFixture(testActivation = true) {
        ComplaintTestNamespaceRegistrationCases.firstFreshBindingIsConsumedByInstallation(it, shutdownRoot = true)
    }

    @Test
    fun testRegistrationRequiresOwnDualCopyAndUntouchedFreshEffect() {
        TestRegistrationDriftCut.entries.forEach { cut ->
            withFixture(testActivation = true) { ComplaintTestNamespaceRegistrationCases.rawCopiesAndUsedEffectCannotRegister(it, cut) }
        }
    }

    @Test
    fun testRegistrationProviderSignalsAndCleanupCannotIssueOrRetry() {
        TestRegistrationProviderCut.entries.forEach { cut ->
            withFixture(testActivation = true) { ComplaintTestNamespaceRegistrationCases.providerFailureCannotIssueOrRetry(it, cut) }
        }
    }

    @Test
    fun testRegistrationTargetCommitAndReleaseFailuresAreSticky() {
        TestRegistrationCompletionCut.entries.forEach { cut ->
            withFixture(testActivation = true) { ComplaintTestNamespaceRegistrationCases.targetCompletionAndOriginalReleaseAreRequired(it, cut) }
        }
    }

    @Test
    fun testRegistrationRejectsChangedTargetNamedRootAndClosedContinuation() {
        for (closed in listOf(false, true)) {
            withFixture(testActivation = true) { ComplaintTestNamespaceRegistrationCases.changedTargetNamedRootAndClosedContinuationRefuse(it, closed) }
        }
    }

    @Test
    fun testRegistrationHistoricalProjectAndColdReplayCannotBecomeRegistration() = withFixture(testActivation = true) {
        ComplaintTestNamespaceRegistrationCases.historicalProjectAndColdReplayCannotRegister(it)
    }

    @Test
    fun testRegistrationFailedOriginalProjectNeverIssuesCompletion() {
        TestRegistrationProjectCut.entries.forEach { cut ->
            withFixture(testActivation = true) { ComplaintTestNamespaceRegistrationCases.failedOriginalProjectCannotHandoffCompletion(it, cut) }
        }
    }

    @Test
    fun testInitialAdmissionProtectedRootReleasesOnlyIdentityAndPreservesAccounting() = withFixture(testActivation = true) {
        ComplaintTestInitialAdmissionCases.protectedRootReleasesOnlyIdentity(it)
    }

    @Test
    fun testInitialAdmissionPreconstructedExchangeWaitsForActualCleanup() = withFixture(testActivation = true) {
        ComplaintTestInitialAdmissionCases.preconstructedExchangeWaitsForActualCleanup(it)
    }

    @Test
    fun testInitialAdmissionCommitAndCleanupFailuresNeverOpenLocalAdmission() {
        TestRegistrationCompletionCut.entries.forEach { cut ->
            withFixture(testActivation = true) { ComplaintTestInitialAdmissionCases.completionFailureCannotOpenLocalAdmission(it, cut) }
        }
    }

    @Test
    fun testInitialAdmissionLostCommitReplyCannotAdmitPreconstructedExchange() {
        PgLifecycleTlsCommitForwarder(database.value).use { forwarder ->
            forwarder.start()
            VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, endpointPort = forwarder.port).use { tls ->
                tls.bind()
                assertInitialAdmissionLostCommitResponse(tls, forwarder)
            }
        }
    }

    @Test
    fun testInitialAdmissionRawIdentityAndPhysicalEffectDriftRefuse() {
        InitialAdmissionDriftCut.entries.forEach { cut ->
            withFixture(testActivation = true) { ComplaintTestInitialAdmissionCases.rawIdentityAndPhysicalEffectDriftRefuse(it, cut) }
        }
    }

    @Test
    fun testInitialAdmissionProviderDeadlineAndSignalsAreSticky() {
        InitialAdmissionProviderCut.entries.forEach { cut ->
            withFixture(testActivation = true) { ComplaintTestInitialAdmissionCases.providerDeadlineAndSignalsAreSticky(it, cut) }
        }
    }

    @Test
    fun testInitialAdmissionMContentionAndLockOrderAreEnforced() {
        InitialAdmissionLockCut.entries.forEach { cut ->
            withFixture(testActivation = true) { ComplaintTestInitialAdmissionCases.fencesRefuseWithoutLockUpgrade(it, cut) }
        }
    }

    @Test
    fun testInitialAdmissionWrongRootSpentClosedAndTerminalRefuse() {
        InitialAdmissionLifetimeCut.entries.forEach { cut ->
            withFixture(testActivation = true) { ComplaintTestInitialAdmissionCases.wrongRootSpentClosedAndTerminalRefuse(it, cut) }
        }
    }

    @Test
    fun testInitialAdmissionEveryRealIdentityPhaseRechecksCurrentRows() {
        InitialIdentityDriftCut.entries.forEach { cut ->
            withFixture(testActivation = true) { ComplaintTestInitialAdmissionCases.everyRealIdentityPhaseRechecksCurrentRows(it, cut) }
        }
    }

    @Test
    fun testRunSealingBarrierAndPaidAuditReplay() = withFixture(testActivation = true) {
        TestRunSealingCases.barrierPaidAuditAndReplay(it)
    }

    @Test
    fun testRunSealingRollbackAndUnknownDoNotChargeAudit() {
        for (cut in listOf(TestRegistrationCompletionCut.BEFORE_COMMIT, TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN)) {
            withFixture(testActivation = true) { TestRunSealingCases.completionFailure(it, cut, auditPhase = true) }
        }
    }

    @Test
    fun testRunSealingLostOriginalReleaseCanResume() {
        for (cut in listOf(TestRegistrationCompletionCut.AFTER_COMMIT, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)) {
            for (auditPhase in listOf(false, true)) {
                withFixture(testActivation = true) { TestRunSealingCases.completionFailure(it, cut, auditPhase) }
            }
        }
    }

    @Test
    fun testRunOrdinarySealEmptyRunAtomicPhasesAndImmutableReplay() = withFixture(testActivation = true) {
        TestOrdinarySealCasesV1.emptyAndReplay(it)
    }

    @Test
    fun testRunOrdinarySealProtectedIntakeConnectsRegistrationAndNativeCustody() {
        TestOrdinarySealIntakeCutV1.entries.forEach { cut ->
            withFixture(testActivation = true) { TestOrdinarySealIntakeCasesV1.connected(it, cut) }
        }
    }

    @Test
    fun testRunOrdinarySealCompleteLocalHistoryIncludesFulfilledAndExpiredReceipts() {
        for (expired in listOf(false, true)) withFixture(testActivation = true) { TestOrdinarySealCasesV1.completeHistory(it, expired) }
        withFixture(testActivation = true) { TestOrdinarySealCasesV1.completeHistory(it, syntheticExpiredReceipt = false, recoveryReserved = true) }
    }

    @Test
    fun testRunOrdinarySealInstallationSourceRejectsPairsMembershipAndSameTargetChanges() {
        TestOrdinarySealInstallationCutV1.entries.filter { it !in setOf(TestOrdinarySealInstallationCutV1.FENCE, TestOrdinarySealInstallationCutV1.DEADLINE) }
            .forEach { cut -> withFixture(testActivation = true) { TestOrdinarySealCasesV1.invalidInstallationSource(it, cut) } }
    }

    @Test
    fun testRunOrdinarySealInstallationSourceKeepsOriginalFenceAndDeadline() {
        for (cut in listOf(TestOrdinarySealInstallationCutV1.FENCE, TestOrdinarySealInstallationCutV1.DEADLINE)) {
            withFixture(testActivation = true) { TestOrdinarySealCasesV1.invalidInstallationSource(it, cut) }
        }
    }

    @Test
    fun testRunOrdinarySealRefusesPendingOrphanForeignUnsupportedAndSecondPassChange() {
        TestOrdinarySealHistoryCutV1.entries.forEach { cut -> withFixture(testActivation = true) { TestOrdinarySealCasesV1.invalidHistory(it, cut) } }
    }

    @Test
    fun testRunOrdinarySealRequiresColdInputAndCannotRecreateVerifiedVersion() {
        withFixture(testActivation = true) { TestOrdinarySealCasesV1.absentColdInputRefuses(it) }
        withFixture(testActivation = true) { TestOrdinarySealCasesV1.missingVerifiedObjectNeverReput(it) }
    }

    @Test
    fun testRunOrdinarySealPrepareAndFreezeRequireCommittedOriginalRelease() {
        for (step in listOf(TestOrdinarySealStepV1.PREPARE, TestOrdinarySealStepV1.FREEZE)) {
            TestRegistrationCompletionCut.entries.forEach { cut ->
                withFixture(testActivation = true) { TestOrdinarySealFailureCasesV1.completion(it, step, cut) }
            }
        }
    }

    @Test
    fun testRunOrdinarySealVerifyCompletionCannotReencryptOrDoubleCharge() {
        TestRegistrationCompletionCut.entries.forEach { cut ->
            withFixture(testActivation = true) { TestOrdinarySealFailureCasesV1.completion(it, TestOrdinarySealStepV1.VERIFY, cut) }
        }
    }

    @Test
    fun testRunOrdinarySealLostPutAcknowledgmentUsesOneExactRepeat() = withFixture(testActivation = true) {
        TestOrdinarySealFailureCasesV1.lostPutAcknowledgment(it)
    }

    @Test
    fun testRunOrdinarySealRejectsWrongRoleVersionWireMetadataAndRetention() {
        TestOrdinarySealProviderCutV1.entries.forEach { cut -> withFixture(testActivation = true) { TestOrdinarySealFailureCasesV1.badProvider(it, cut) } }
    }

    @Test
    fun testRunOrdinarySealLifetimeSignalsAndDatabaseFenceVetoVerify() {
        TestOrdinarySealLifetimeCutV1.entries.forEach { cut -> withFixture(testActivation = true) { TestOrdinarySealFailureCasesV1.lifetime(it, cut) } }
    }

    @Test
    fun testRunOrdinarySealFailedNativeCloseRetainsSharedLane() = withFixture(testActivation = true) {
        TestOrdinarySealFailureCasesV1.failedNativeCloseKeepsLane(it)
    }

    @Test
    fun testRunVerifiedOwnerDeleteCompletesPrimaryAndExactReplay() = withFixture(testActivation = true) {
        TestRunVerifiedOwnerDeleteCases.verifiedPrimaryAndExactReplay(it)
    }

    @Test
    fun testRunVerifiedOwnerDeleteRefusesPreparedAndChangedAuthorityWithoutRepair() {
        TestVerifiedDeleteRefusalCut.entries.forEach { cut ->
            withFixture(testActivation = true) { TestRunVerifiedOwnerDeleteCases.refusesWithoutRepair(it, cut) }
        }
    }

    @Test
    fun testRunVerifiedOwnerDeleteReloadRequiresPositiveCommitAndOriginalRelease() {
        TestRegistrationCompletionCut.entries.forEach { cut ->
            withFixture(testActivation = true) { TestRunVerifiedOwnerDeleteCases.originalCompletionFailure(it, cut, applyPhase = false) }
        }
    }

    @Test
    fun testRunVerifiedOwnerDeleteApplyFailureAndLostAcknowledgmentReplay() {
        TestRegistrationCompletionCut.entries.forEach { cut ->
            withFixture(testActivation = true) { TestRunVerifiedOwnerDeleteCases.originalCompletionFailure(it, cut, applyPhase = true) }
        }
    }

    @Test
    fun testRunPreparedOwnerDeletePublishesPrimaryAndReplaysWithoutProvider() = withFixture(testActivation = true) {
        TestRunPreparedOwnerDeleteCases.primaryAndReplay(it)
    }

    @Test
    fun testRunPreparedOwnerDeleteRefusesUnownedOrSealedIntentBeforeDispatch() {
        TestPreparedDeleteRefusalCut.entries.forEach { cut ->
            withFixture(testActivation = true) { TestRunPreparedOwnerDeleteCases.refusesBeforeDispatch(it, cut) }
        }
    }

    @Test
    fun testRunPreparedOwnerDeleteProviderFailureOrLateCloseCannotVerify() {
        TestPreparedDeleteProviderCut.entries.forEach { cut ->
            withFixture(testActivation = true) { TestRunPreparedOwnerDeleteCases.providerBoundary(it, cut) }
        }
    }

    @Test
    fun testRunPreparedOwnerDeleteRequiresOriginalReloadAndVerifyCommitRelease() {
        for (verifyPhase in listOf(false, true)) {
            TestRegistrationCompletionCut.entries.forEach { cut ->
                withFixture(testActivation = true) { TestRunPreparedOwnerDeleteCases.completionBoundary(it, cut, verifyPhase) }
            }
        }
    }

    @Test
    fun testRunOwnerDeletePageCompletesMixedPrimariesWithoutReleasingReserve() = withFixture(testActivation = true) {
        TestRunOwnerDeletePageCases.mixedPageAndReplay(it)
    }

    @Test
    fun testRunOwnerDeletePageDoesNotSkipInvalidOrOrphanedWork() = withFixture(testActivation = true) {
        TestRunOwnerDeletePageCases.outstandingCorruptionIsNotSkipped(it)
    }

    @Test
    fun testRunOwnerDeletePageRequiresOriginalSelectionCommitAndRelease() = withFixture(testActivation = true) {
        TestRunOwnerDeletePageCases.selectionRequiresOriginalCommitAndRelease(it)
    }

    @Test
    fun testRunOwnerDeletePageSharesOuterBudgetAndCancellation() = withFixture(testActivation = true) {
        TestRunOwnerDeletePageCases.outerCancellationAndBudgetStopThePage(it)
    }

    @Test
    fun testRunOwnerDeleteAllPreparedZeroAndHundredApplyAndReplay() {
        for (count in listOf(0, 100)) withFixture(testActivation = true) { TestRunOwnerDeleteAllCasesV1.preparedAndReplay(it, count) }
    }

    @Test
    fun testRunOwnerDeleteAllStoredVerifiedAndAppliedReplayWithoutProviders() {
        for (applied in listOf(false, true)) withFixture(testActivation = true) { TestRunOwnerDeleteAllCasesV1.verifiedOrAppliedReplay(it, applied) }
    }

    @Test
    fun testRunOwnerDeleteAll101RollsBackAuthorizationWithoutProvider() = withFixture(testActivation = true) {
        TestRunOwnerDeleteAllCasesV1.hundredOneSentinel(it)
    }

    @Test
    fun testRunOwnerDeleteAllLateNativeCloseCannotIssueVerify() = withFixture(testActivation = true) {
        TestRunOwnerDeleteAllCasesV1.lateNativeClose(it)
    }

    @Test
    fun testRunOwnerDeleteAllFailedNativeCloseKeepsSharedPrivacyLane() = withFixture(testActivation = true) {
        TestRunOwnerDeleteAllCasesV1.failedNativeClose(it)
    }

    @Test
    fun testRunOwnerDeleteAllOriginalLostCommitCannotAdvanceOrRehabilitate() {
        for (path in listOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY)) {
            withFixture(testActivation = true) { TestRunOwnerDeleteAllCasesV1.lostCommitAcknowledgment(it, path) }
        }
    }

    @Test
    fun testOrdinarySealConsumesMixedDeleteFamiliesWithoutConvertingRecoveryReserve() = withFixture(testActivation = true) {
        TestRunOwnerDeleteAllCasesV1.mixedOrdinarySeal(it)
    }

    @Test
    fun testOrdinarySealRefusesPendingOrOrphanAllHistoryAndSecondPassXmin() {
        TestAllHistoryCutV1.entries.forEach { cut -> withFixture(testActivation = true) { TestRunOwnerDeleteAllCasesV1.ordinaryHistoryRefusal(it, cut) } }
    }

    @Test
    fun directEpochMaintenanceCloseDeadlinePreservesActualCleanupAndVetoesAcceptance() {
        for (resultSet in listOf(true, false)) {
            for (admission in listOf(false, true)) {
                val clock = EpochMaintenanceClock()
                withFixture(epochRotation = true, nanoClock = clock) { tls ->
                    withEpochRotation(tls) { EpochRotationMaintenanceCases.closeDeadline(it, clock, resultSet, admission) }
                }
            }
        }
    }

    @Test
    fun directEpochMaintenanceFreshGateAndActualIsolationPrecedeEpochCapture() {
        EpochMaintenanceGateCut.entries.forEach { cut ->
            val clock = EpochMaintenanceClock()
            withFixture(epochRotation = true, nanoClock = clock) { tls ->
                val invoke = { withEpochRotation(tls) { EpochRotationMaintenanceCases.gateAndIsolation(it, clock, cut) } }
                if (cut === EpochMaintenanceGateCut.ROLE_DEFAULT_REPEATABLE_READ) {
                    EpochRotationMaintenanceCases.withRoleRepeatableRead(tls, invoke)
                } else invoke()
            }
        }
    }

    private fun withPairedFixture(
        epochRotation: Boolean = false,
        test: (VersionBoundPersistenceConnectedFixture, VersionBoundPersistenceConnectedFixture) -> Unit,
    ) {
        val first = VersionBoundPersistenceConnectedFixture(database.value, epochRotation = epochRotation)
        var second: VersionBoundPersistenceConnectedFixture? = null
        AutoCloseable { second?.let(first::closeWith) ?: first.close() }.use {
            val peer = VersionBoundPersistenceConnectedFixture(database.value, epochRotation = epochRotation)
            second = peer // Cleanup owns both roots before either can bind or start.
            first.bind()
            peer.bind()
            test(first, peer)
        }
    }

    @Test
    fun testRegisteredBootstrapActualSpringMountAndClosedMaintenance() = withFixture(testActivation = true) {
        ComplaintInstallationBootstrapCases.mountedMaintenanceIsolation(it)
    }

    @Test
    fun testRegisteredBootstrapRejectsStaleActivationRestoreAndScope() = withFixture(testActivation = true) {
        ComplaintInstallationBootstrapCases.driftNeverSelectsFallback(it)
    }

    @Test
    fun testRegisteredBootstrapSealedPurgingPurgedNeverRebindEnrollment() = withFixture(testActivation = true) {
        ComplaintInstallationBootstrapCases.terminalScopeIsNeverRebound(it)
    }

    @Test
    fun testRegisteredBootstrapRequiresOriginalOwnerCommittedReadAndRelease() = withFixture(testActivation = true) {
        ComplaintInstallationBootstrapCases.originalResourcesAndRelease(it)
    }

    @Test
    fun testRegisteredBootstrapCompletionFailureAndFinalRevocationCannotRelease() = withFixture(testActivation = true) {
        ComplaintInstallationBootstrapCases.completionFailuresCannotRelease(it)
    }

    @Test
    fun testRegisteredBootstrapNativeUnknownCommitAndUnresolvedReleaseRefuse() = withFixture(testActivation = true) {
        ComplaintInstallationBootstrapCases.unknownCommitAndUnresolvedRelease(it)
    }

    @Test
    fun testRegisteredBootstrapUnavailableOwnerIsNotQuotaOrReadiness() = withFixture(testActivation = true) {
        ComplaintInstallationBootstrapCases.unavailableAdmissionIsNotQuotaOrReadiness(it)
    }

    private fun withFixture(
        client: ConnectedTlsClient = ConnectedTlsClient.MATCHED,
        profile: PersistencePoolLaunchProfile = PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY,
        epochRotation: Boolean = false,
        nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
        testActivation: Boolean = false,
        test: (VersionBoundPersistenceConnectedFixture) -> Unit,
    ) = VersionBoundPersistenceConnectedFixture(database.value, client, epochRotation, testActivation = testActivation).use { fixture ->
        fixture.bind(profile, nanoClock)
        test(fixture)
    }
}
