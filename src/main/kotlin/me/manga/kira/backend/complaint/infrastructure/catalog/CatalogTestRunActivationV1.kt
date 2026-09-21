package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceComplaintMaintenanceGateV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogSigningExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogSigningFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutAcknowledgementV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutFailureV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * Original cold TEST owner for PREPARE/freeze, separately armed delivery and atomic first PROJECT.
 * Old diagnostic receipts grant no next phase. Projection keeps both gates closed and supplies no
 * run admission, reopening, registration or global provider/ingress-drain assertion.
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class CatalogTestRunActivationV1 private constructor(
    internal val process: VersionBoundTestNamespaceProcessV1,
    installationLimit: Long,
    internal val budget: PersistenceTimeBudget,
    readbackFactory: (() -> SdkHttpClient)?,
    private val clock: Clock,
    signingFactory: (() -> SdkHttpClient)? = null,
    putFactory: (() -> SdkHttpClient)? = null,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val coordinator = process.pools.catalogCoordinator
    private val ownership = coordinator.ownership
    private val manager = coordinator.manager
    private val source = coordinator.dataSource
    private val openings = process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }
        .openings().map { it.publicDriverProperties() }
    private val username = checkNotNull(openings.map { it["user"] }.distinct().single())
    private val database = checkNotNull(openings.map { it["PGDBNAME"] }.distinct().single())
    internal val expectedDeclaration = CatalogTestRunActivationCanonicalV3.fromRetained(process, installationLimit)
    private val predecessor = CatalogTestRunActivationPredecessorV1(this, readbackFactory)
    private val assembly = CatalogTestRunActivationAssemblyV1(this, signingFactory)
    private val deliveryAssembly = CatalogTestRunActivationDeliveryAssemblyV1(this, putFactory, readbackFactory, clock)
    private var stage = Stage.NEW
    private var entered = false
    private var recovering = false
    private var freezing = false
    private var delivering = false
    private var deliveryPublishing = false
    private var projecting = false
    private var failed = false
    private var reserved = false
    private var released = false
    private var closed = false
    private var cleanupProven = false
    private var closeFailure: CatalogTestRunActivationExceptionV1? = null
    private var frozen: CatalogTestRunActivationFrozenV1? = null
    private var snapshotOperation: CatalogTestRunActivationOperationV1? = null
    private var originalSnapshot: CatalogTestRunActivationSnapshotV1? = null
    private var expectedSnapshot: CatalogTestRunActivationSnapshotV1? = null
    private var leaseOwner: UUID? = null
    private var leaseStartedAtNanos: Long? = null
    private var leaseOperation: CatalogTestRunActivationOperationV1? = null
    private var lease: CatalogTestRunActivationLeaseV1? = null
    private var preparedOperation: CatalogTestRunActivationOperationV1? = null
    private var preparedSnapshot: CatalogTestRunActivationSnapshotV1? = null
    private var reloadOperation: CatalogTestRunActivationOperationV1? = null
    private var custody: CatalogTestRunActivationReleaseCustodyV1? = null
    private var release: CatalogTestRunActivationReleaseV1? = null
    private var prepareArmIssued = false
    private var prepareArmed = false
    private var signReadbackRechecked = false
    private var signArmIssued = false
    private var signArmed = false
    private var signConstructionIssued = false
    private var signatureSqlArmIssued = false
    private var signatureSqlArmed = false
    private var signed: CatalogTestRunActivationSignedV1? = null
    private var signatureOperation: CatalogTestRunActivationOperationV1? = null
    private var signedReloadOperation: CatalogTestRunActivationOperationV1? = null
    private var deliveryRelease: CatalogTestRunActivationDeliveryReleaseV1? = null
    private var deliveryReadback: CatalogTestRunActivationDeliveryReadbackV1? = null
    private var deliveryReloadOperation: CatalogTestRunActivationOperationV1? = null
    private var publicationArmIssued = false
    private var publicationArmed = false
    private var putConstructionIssued = false
    private var publicationAcknowledgement: CatalogPrimaryPutAcknowledgementV1? = null
    private var completeArmIssued = false
    private var completeArmed = false
    private var completeOperation: CatalogTestRunActivationOperationV1? = null
    private var pendingReloadOperation: CatalogTestRunActivationOperationV1? = null
    private var projectionReadbacks = 0
    private var projectionCaptureOperation: CatalogTestRunActivationOperationV1? = null
    private var projectionCapturedSnapshot: CatalogTestRunActivationSnapshotV1? = null
    private var projectionReloadOperation: CatalogTestRunActivationOperationV1? = null
    private var projectionGrant: ProjectionGrant? = null
    private var projectArmIssued = false
    private var projectArmed = false
    private var projectionDispatchIssued = false
    private var projectOperation: CatalogTestRunActivationOperationV1? = null
    private var projectedReloadOperation: CatalogTestRunActivationOperationV1? = null
    private var allowedResult = false
    private var allowedSignedResult = false
    private var allowedCompletedResult = false
    private var allowedProjectedResult = false
    private var registrationTarget: VersionBoundTestNamespaceProcessV1? = null
    private var completionContinuationIssued = false
    private var lastWall: Instant? = null
    private var selectedKind: CatalogTestRunActivationKindV1? = null
    private var inputConstructionClaimed = false
    private var activeInput: CatalogTestRunActivationInputV1? = null
    private var phaseEntered = false
    private var phaseRetainedForEntry = false
    private var originalPhase: PersistencePhaseContext? = null
    private var sqlCleanupUnproven = false
    private var outcomeUncertain = false
    private val originalSignal = AtomicReference<Throwable?>()

    init {
        requireConnectionFree()
        requireProcess()
    }

    fun prepare(unsignedCanonicalBytes: ByteArray, primaryReadCredentials: AwsSessionCredentials, replicaReadCredentials: AwsSessionCredentials): CatalogTestRunPreparedV1 =
        run(false, unsignedCanonicalBytes, primaryReadCredentials, replicaReadCredentials)

    /** A new owner and fresh lease, exact original PREPARED bytes only. Never replacement, double charge or reopening. */
    fun reloadPrepared(unsignedCanonicalBytes: ByteArray, primaryReadCredentials: AwsSessionCredentials, replicaReadCredentials: AwsSessionCredentials): CatalogTestRunPreparedV1 =
        run(true, unsignedCanonicalBytes, primaryReadCredentials, replicaReadCredentials)

    /** One new durable allocation/PREPARE/Sign; stops at released signed PREPARED with the accepted head unchanged. */
    fun prepareAndFreeze(
        releaseRoot: Path,
        unsignedCanonicalBytes: ByteArray,
        signingCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogTestRunSignedPreparedV1 = runSigned(false, releaseRoot, unsignedCanonicalBytes, signingCredentials, primaryReadCredentials, replicaReadCredentials)

    /** Existing custody and exact returned bytes only. There is deliberately no Sign credential or diagnostic-receipt input. */
    fun recoverSignature(
        releaseRoot: Path,
        unsignedCanonicalBytes: ByteArray,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogTestRunSignedPreparedV1 = runSigned(true, releaseRoot, unsignedCanonicalBytes, null, primaryReadCredentials, replicaReadCredentials)

    /** Existing original Sign custody only; one newly armed PRIMARY conditional PUT. Never re-Sign or PROJECT. */
    fun deliverAndComplete(
        releaseRoot: Path,
        unsignedCanonicalBytes: ByteArray,
        primaryPutCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogTestRunCompletedPendingV1 = runDelivery(releaseRoot, unsignedCanonicalBytes, primaryPutCredentials, primaryReadCredentials, replicaReadCredentials)

    /** Existing PUT arm and genuine raw dual copies only; there is no PUT/Sign credential or old receipt input. */
    fun recoverCompletion(
        releaseRoot: Path,
        unsignedCanonicalBytes: ByteArray,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogTestRunCompletedPendingV1 = runDelivery(releaseRoot, unsignedCanonicalBytes, null, primaryReadCredentials, replicaReadCredentials)

    /** Strict already-COMPLETED/pending replay. No prepared adoption, new PUT, Sign or projection. */
    fun reloadPending(
        releaseRoot: Path,
        unsignedCanonicalBytes: ByteArray,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogTestRunCompletedPendingV1 = runDelivery(releaseRoot, unsignedCanonicalBytes, null, primaryReadCredentials, replicaReadCredentials, pendingOnly = true)

    /** Fresh cold owner and original custody only. No supplied prior receipt, run declaration, Sign or PUT credential. */
    fun projectCompleted(
        releaseRoot: Path,
        unsignedCanonicalBytes: ByteArray,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogTestRunProjectedV1 {
        runProjection(releaseRoot, unsignedCanonicalBytes, primaryReadCredentials, replicaReadCredentials)
        return CatalogTestRunProjectedV1.issuedBy(this)
    }

    /** Only a freshly dispatched first PROJECT can hand completion to one separate runtime target. */
    fun projectForRegistration(
        target: VersionBoundTestNamespaceProcessV1,
        releaseRoot: Path,
        unsignedCanonicalBytes: ByteArray,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogTestRunFirstProjectionV1 {
        requireConnectionFree()
        requireRunning()
        requireTestActivation(!entered && stage === Stage.NEW && registrationTarget == null)
        target.requireRegistrationTarget()
        registrationTarget = target
        runProjection(releaseRoot, unsignedCanonicalBytes, primaryReadCredentials, replicaReadCredentials)
        return CatalogTestRunFirstProjectionV1.issuedBy(this, target)
    }

    internal fun firstProjectionState(target: VersionBoundTestNamespaceProcessV1): CatalogTestRunFirstProjectionV1.State {
        val (input, value, _) = projectedReceiptInput()
        requireTestActivation(registrationTarget === target && !completionContinuationIssued &&
            originalSnapshot?.completedTail?.projectedAt == null && projectOperation != null && projectionDispatchIssued &&
            projectionGrant?.spent == true && projectOperation?.input?.original === this)
        checkNotNull(projectOperation).requireReleased()
        target.requireRegistrationTarget()
        completionContinuationIssued = true
        return CatalogTestRunFirstProjectionV1.State(input, value, checkNotNull(expectedSnapshot))
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod")
    private fun runProjection(root: Path, bytes: ByteArray, primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        requireTestActivation(caller === Thread.currentThread(), CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
        var success = false
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireTestActivation(!entered && stage === Stage.NEW)
            entered = true
            recovering = true
            delivering = true
            projecting = true
            coordinator.catalogRefreshCustody.reserveTestRunActivation(this)
            reserved = true
            stage = Stage.CAPTURE
            frozen = CatalogTestRunActivationFrozenV1.capture(this, bytes)
            val allocation = openExistingCustody(root)
            deliveryRelease = CatalogTestRunActivationDeliveryReleaseV1(this, checkNotNull(frozen), checkNotNull(signed),
                checkNotNull(release), checkNotNull(custody), allocation, publishing = false, projecting = true)
            stage = Stage.SNAPSHOT
            snapshotOperation = execute(CatalogTestRunActivationKindV1.SNAPSHOT)
            originalSnapshot = checkNotNull(snapshotOperation).snapshot
            expectedSnapshot = originalSnapshot
            checkNotNull(deliveryRelease).requireSnapshot(checkNotNull(originalSnapshot))
            requireTestActivation(originalSnapshot?.completedTail != null, CatalogTestRunActivationFailureV1.STATE_REFUSED)
            if (registrationTarget != null) requireTestActivation(originalSnapshot?.completedTail?.projectedAt == null, CatalogTestRunActivationFailureV1.STATE_REFUSED)
            observeDelivery(primary, replica)
            requireTestActivation(deliveryReadback?.state === CatalogTestRunActivationDeliveryReadbackV1.State.DUAL_COPY)
            stage = Stage.ACQUIRE
            leaseOwner = UUID.randomUUID()
            leaseStartedAtNanos = ownership.nanoClock.nanoTime()
            leaseOperation = execute(CatalogTestRunActivationKindV1.LEASE_ACQUIRE)
            lease = checkNotNull(leaseOperation).lease
            requireActualLease()
            checkNotNull(deliveryRelease).requireAcquisition(checkNotNull(lease))
            stage = Stage.PROJECT_CAPTURE
            projectionCaptureOperation = execute(CatalogTestRunActivationKindV1.PROJECT_RELOAD)
            expectedSnapshot = checkNotNull(projectionCaptureOperation).snapshot
            projectionCapturedSnapshot = expectedSnapshot
            checkNotNull(deliveryRelease).requireSnapshot(checkNotNull(expectedSnapshot))
            observeDelivery(primary, replica) // Second genuinely cleaned raw traversal, never a reused first proof.
            stage = Stage.PROJECT_RECHECK
            projectionReloadOperation = execute(CatalogTestRunActivationKindV1.PROJECT_RELOAD)
            expectedSnapshot = checkNotNull(projectionReloadOperation).snapshot
            checkNotNull(deliveryRelease).requireSnapshot(checkNotNull(expectedSnapshot))
            if (checkNotNull(checkNotNull(expectedSnapshot).completedTail).projectedAt == null) {
                retainProjectionGrant()
                stage = Stage.ARM_PROJECT
                spendProjectionGrant() // Spent before root I/O, then bound to the one original PROJECT phase.
                projectArmIssued = true
                checkNotNull(deliveryRelease).armProject(checkNotNull(deliveryReadback))
                projectArmed = true
                stage = Stage.PROJECT
                projectOperation = execute(CatalogTestRunActivationKindV1.PROJECT)
                expectedSnapshot = checkNotNull(projectOperation).snapshot
                checkNotNull(deliveryRelease).projected(checkNotNull(expectedSnapshot))
            }
            stage = Stage.PROJECTED_RELOAD
            projectedReloadOperation = execute(CatalogTestRunActivationKindV1.PROJECTED_RELOAD)
            expectedSnapshot = checkNotNull(projectedReloadOperation).snapshot
            checkNotNull(deliveryRelease).projectedReloaded(checkNotNull(expectedSnapshot))
            requireActualLease()
            requireSqlCleanup()
            deliveryAssembly.requireProviderCleanup()
            success = true
        } catch (problem: Throwable) {
            observeFailure(problem)
            failed = true
            failure = problem
        } finally {
            runCatching(::close).exceptionOrNull()?.let { failure = preferSignerRotationCleanup(failure, it) }
        }
        throwIfSignalled()
        failure?.let { throw boundedTestActivationFailure(it) }
        requireTestActivation(success)
        allowedProjectedResult = true
    }

    private fun retainProjectionGrant() {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        val operation = checkNotNull(projectionReloadOperation)
        operation.requireReleased()
        val snapshot = operation.snapshot
        requireTestActivation(projecting && stage === Stage.PROJECT_RECHECK && projectionGrant == null && projectionReadbacks == 2 &&
            operation.input.original === this && operation.input.kind === CatalogTestRunActivationKindV1.PROJECT_RELOAD &&
            operation.input.expected === projectionCaptureOperation?.snapshot && snapshot === expectedSnapshot && snapshot.projectionRows != null &&
            snapshot.completedTail?.projectedAt == null && operation.input.deliveryProof === deliveryReadback && operation.input.lease === lease)
        requireRawVerified()
        projectionGrant = ProjectionGrant(operation, operation.input, snapshot, checkNotNull(deliveryReadback), checkNotNull(lease))
    }

    private fun spendProjectionGrant() {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        val grant = requireProjectionGrant()
        requireTestActivation(stage === Stage.ARM_PROJECT && !grant.spent && !projectArmIssued && !projectArmed)
        grant.operation.requireReleased()
        grant.spent = true
    }

    private fun requireProjectionGrant(): ProjectionGrant {
        val grant = checkNotNull(projectionGrant)
        requireTestActivation(projecting && !deliveryPublishing && grant.operation === projectionReloadOperation && grant.input === grant.operation.input &&
            grant.input.original === this && grant.input.frozen === frozen && grant.input.signed === signed &&
            grant.input.projecting && grant.snapshot === expectedSnapshot && grant.input.expected === projectionCapturedSnapshot &&
            grant.proof === deliveryReadback && grant.lease === lease && projectionReadbacks == 2)
        return grant
    }

    /** Original selected PROJECT operation only. No detached grant/receipt crosses this boundary. */
    internal fun requireProjectionDispatch(operation: CatalogTestRunActivationOperationV1) {
        requireRunning()
        val grant = requireProjectionGrant()
        requireTestActivation(stage === Stage.PROJECT && grant.spent && projectArmIssued && projectArmed && !projectionDispatchIssued &&
            activeInput === operation.input && selectedKind === CatalogTestRunActivationKindV1.PROJECT &&
            originalPhase === PersistencePhaseOwnership.current() && operation.input.expected === grant.snapshot)
        projectionDispatchIssued = true
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod")
    private fun runDelivery(
        root: Path,
        bytes: ByteArray,
        putCredentials: AwsSessionCredentials?,
        primary: AwsSessionCredentials,
        replica: AwsSessionCredentials,
        pendingOnly: Boolean = false,
    ): CatalogTestRunCompletedPendingV1 {
        requireTestActivation(caller === Thread.currentThread(), CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
        var success = false
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireTestActivation(!entered && stage === Stage.NEW)
            entered = true
            recovering = true // Only existing freeze custody and existing signed SQL; never another PREPARE or Sign.
            delivering = true
            deliveryPublishing = putCredentials != null
            coordinator.catalogRefreshCustody.reserveTestRunActivation(this)
            reserved = true
            stage = Stage.CAPTURE
            frozen = CatalogTestRunActivationFrozenV1.capture(this, bytes)
            val allocation = openExistingCustody(root)
            deliveryRelease = CatalogTestRunActivationDeliveryReleaseV1(this, checkNotNull(frozen), checkNotNull(signed),
                checkNotNull(release), checkNotNull(custody), allocation, deliveryPublishing)
            stage = Stage.SNAPSHOT
            snapshotOperation = execute(CatalogTestRunActivationKindV1.SNAPSHOT)
            originalSnapshot = checkNotNull(snapshotOperation).snapshot
            expectedSnapshot = originalSnapshot
            checkNotNull(deliveryRelease).requireSnapshot(checkNotNull(originalSnapshot))
            requireTestActivation(!pendingOnly || originalSnapshot?.completedTail != null, CatalogTestRunActivationFailureV1.STATE_REFUSED)
            requireSqlCleanup()
            observeDelivery(primary, replica)
            if (deliveryPublishing) {
                requireTestActivation(checkNotNull(deliveryReadback).state === CatalogTestRunActivationDeliveryReadbackV1.State.UNPUBLISHED)
            } else {
                requireTestActivation(checkNotNull(deliveryReadback).state === CatalogTestRunActivationDeliveryReadbackV1.State.DUAL_COPY,
                    CatalogTestRunActivationFailureV1.DELIVERY_PENDING)
            }
            stage = Stage.ACQUIRE
            leaseOwner = UUID.randomUUID()
            leaseStartedAtNanos = ownership.nanoClock.nanoTime()
            leaseOperation = execute(CatalogTestRunActivationKindV1.LEASE_ACQUIRE)
            lease = checkNotNull(leaseOperation).lease
            requireActualLease()
            checkNotNull(deliveryRelease).requireAcquisition(checkNotNull(lease))
            stage = Stage.DELIVERY_RELOAD
            deliveryReloadOperation = execute(CatalogTestRunActivationKindV1.DELIVERY_RELOAD)
            expectedSnapshot = checkNotNull(deliveryReloadOperation).snapshot
            checkNotNull(deliveryRelease).requireSnapshot(checkNotNull(expectedSnapshot))
            if (deliveryPublishing) {
                stage = Stage.ARM_PUBLICATION
                publicationArmIssued = true
                checkNotNull(deliveryRelease).armPublication(checkNotNull(deliveryReadback))
                publicationArmed = true
                stage = Stage.PUT
                val acknowledgement = deliveryAssembly.put(checkNotNull(frozen), checkNotNull(signed), checkNotNull(putCredentials))
                publicationAcknowledgement = acknowledgement
                checkNotNull(deliveryRelease).acknowledged(acknowledgement)
                observeDelivery(primary, replica)
            }
            stage = Stage.DELIVERY_EVIDENCE
            val proof = checkNotNull(deliveryReadback)
            if (proof.state === CatalogTestRunActivationDeliveryReadbackV1.State.AWAIT_REPLICATION) {
                checkNotNull(deliveryRelease).awaitReplication(proof)
                throw CatalogTestRunActivationExceptionV1(CatalogTestRunActivationFailureV1.DELIVERY_PENDING)
            }
            requireTestActivation(proof.state === CatalogTestRunActivationDeliveryReadbackV1.State.DUAL_COPY, CatalogTestRunActivationFailureV1.DELIVERY_PENDING)
            checkNotNull(deliveryRelease).preserveDual(proof)
            stage = Stage.ARM_COMPLETE
            completeArmIssued = true
            checkNotNull(deliveryRelease).armComplete(proof)
            completeArmed = true
            stage = Stage.COMPLETE
            completeOperation = execute(CatalogTestRunActivationKindV1.COMPLETE)
            expectedSnapshot = checkNotNull(completeOperation).snapshot
            checkNotNull(deliveryRelease).completed(checkNotNull(expectedSnapshot))
            stage = Stage.PENDING_RELOAD
            pendingReloadOperation = execute(CatalogTestRunActivationKindV1.PENDING_RELOAD)
            expectedSnapshot = checkNotNull(pendingReloadOperation).snapshot
            checkNotNull(deliveryRelease).pendingReloaded(checkNotNull(expectedSnapshot))
            requireActualLease()
            requireSqlCleanup()
            deliveryAssembly.requireProviderCleanup()
            success = true
        } catch (problem: Throwable) {
            observeFailure(problem)
            failed = true
            failure = problem
        } finally {
            runCatching(::close).exceptionOrNull()?.let { failure = preferSignerRotationCleanup(failure, it) }
        }
        throwIfSignalled()
        failure?.let { throw boundedTestActivationFailure(it) }
        requireTestActivation(success)
        allowedCompletedResult = true
        return CatalogTestRunCompletedPendingV1.issuedBy(this)
    }

    private fun observeDelivery(primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        stage = Stage.DELIVERY_READBACK
        deliveryReadback = deliveryAssembly.observe(checkNotNull(originalSnapshot), checkNotNull(frozen), checkNotNull(signed), primary, replica)
        checkNotNull(deliveryRelease).requireReadback(checkNotNull(deliveryReadback))
        if (projecting) projectionReadbacks++
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod")
    private fun runSigned(
        recovery: Boolean,
        root: Path,
        bytes: ByteArray,
        signCredentials: AwsSessionCredentials?,
        primary: AwsSessionCredentials,
        replica: AwsSessionCredentials,
    ): CatalogTestRunSignedPreparedV1 {
        requireTestActivation(caller === Thread.currentThread(), CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
        var success = false
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireTestActivation(!entered && stage === Stage.NEW)
            entered = true
            recovering = recovery
            freezing = true
            coordinator.catalogRefreshCustody.reserveTestRunActivation(this)
            reserved = true
            stage = Stage.CAPTURE
            frozen = CatalogTestRunActivationFrozenV1.capture(this, bytes)
            if (recovering) openExistingCustody(root)
            stage = Stage.SNAPSHOT
            snapshotOperation = execute(CatalogTestRunActivationKindV1.SNAPSHOT)
            originalSnapshot = checkNotNull(snapshotOperation).snapshot
            expectedSnapshot = originalSnapshot
            release?.requireSnapshot(checkNotNull(originalSnapshot))
            requireSqlCleanup()
            stage = Stage.READBACK
            predecessor.verify(checkNotNull(originalSnapshot), checkNotNull(frozen), primary, replica)
            predecessor.requireVerified(checkNotNull(originalSnapshot), checkNotNull(frozen))
            stage = Stage.ACQUIRE
            leaseOwner = UUID.randomUUID()
            leaseStartedAtNanos = ownership.nanoClock.nanoTime()
            leaseOperation = execute(CatalogTestRunActivationKindV1.LEASE_ACQUIRE)
            lease = checkNotNull(leaseOperation).lease
            requireActualLease()
            release?.requireAcquisition(checkNotNull(lease))
            if (!recovering) {
                allocateAndArmPrepare(root)
                stage = Stage.PREPARE
                preparedOperation = execute(CatalogTestRunActivationKindV1.PREPARE)
                preparedSnapshot = checkNotNull(preparedOperation).snapshot
                expectedSnapshot = preparedSnapshot
                checkNotNull(release).prepared(checkNotNull(preparedSnapshot))
                stage = Stage.RECHECK_READBACK
                predecessor.verify(checkNotNull(originalSnapshot), checkNotNull(frozen), primary, replica)
                predecessor.requireVerified(checkNotNull(originalSnapshot), checkNotNull(frozen))
                signReadbackRechecked = true
            }
            if (checkNotNull(expectedSnapshot).signedTail == null) {
                stage = Stage.RELOAD
                reloadOperation = execute(CatalogTestRunActivationKindV1.PREPARED_RELOAD)
                expectedSnapshot = checkNotNull(reloadOperation).snapshot
                checkNotNull(release).requireSnapshot(checkNotNull(expectedSnapshot))
            } else {
                requireTestActivation(recovering) // Cold signed replay still gets a fresh exact locked SIGNATURE phase below.
            }
            if (!recovering) {
                stage = Stage.ARM_SIGN
                signArmIssued = true
                checkNotNull(release).armSignature(checkNotNull(expectedSnapshot))
                signArmed = true
                stage = Stage.SIGN
                val signature = assembly.sign(checkNotNull(frozen), checkNotNull(signCredentials))
                signed = checkNotNull(release).preserveSignature(signature)
            }
            stage = Stage.ARM_SIGNATURE
            signatureSqlArmIssued = true
            checkNotNull(release).armSignaturePersistence(checkNotNull(signed))
            signatureSqlArmed = true
            stage = Stage.SIGNATURE
            signatureOperation = execute(CatalogTestRunActivationKindV1.SIGNATURE)
            expectedSnapshot = checkNotNull(signatureOperation).snapshot
            checkNotNull(release).signaturePersisted(checkNotNull(expectedSnapshot))
            stage = Stage.SIGNED_RELOAD
            signedReloadOperation = execute(CatalogTestRunActivationKindV1.SIGNED_RELOAD)
            expectedSnapshot = checkNotNull(signedReloadOperation).snapshot
            checkNotNull(release).frozen(checkNotNull(expectedSnapshot))
            requireActualLease()
            requireSqlCleanup()
            assembly.requireCleanup()
            success = true
        } catch (problem: Throwable) {
            observeFailure(problem)
            failed = true
            failure = problem
        } finally {
            runCatching(::close).exceptionOrNull()?.let { failure = preferSignerRotationCleanup(failure, it) }
        }
        throwIfSignalled()
        failure?.let { throw boundedTestActivationFailure(it) }
        requireTestActivation(success)
        allowedSignedResult = true
        return CatalogTestRunSignedPreparedV1.issuedBy(this)
    }

    private fun openExistingCustody(root: Path): ByteArray {
        stage = Stage.EXISTING_CUSTODY
        val held = CatalogTestRunActivationReleaseCustodyV1.retainExisting(root, budget)
        custody = held // Retained before any filesystem/native construction.
        val allocation = held.discoverExisting()
        val binding = checkNotNull(held.read(CatalogTestRunActivationReleaseLeafV1.BINDING))
        release = CatalogTestRunActivationReleaseV1(this, checkNotNull(frozen), held, allocation, binding, created = false)
        signed = checkNotNull(release).returnedSignature()
        return allocation
    }

    private fun allocateAndArmPrepare(root: Path) {
        stage = Stage.ALLOCATE
        val input = checkNotNull(frozen)
        val binding = CatalogTestRunActivationReleaseV1.binding(this, input)
        val allocation = CatalogTestRunActivationReleaseV1.allocation(input, this, binding)
        val held = CatalogTestRunActivationReleaseCustodyV1.retain(root, allocation, budget)
        custody = held
        requireTestActivation(held.open() === CatalogTestRunActivationCustodyObservationV1.CREATED)
        release = CatalogTestRunActivationReleaseV1(this, input, held, allocation, binding, created = true)
        stage = Stage.ARM_PREPARE
        prepareArmIssued = true
        checkNotNull(release).armPrepare()
        prepareArmed = true
    }

    @Suppress("TooGenericExceptionCaught")
    private fun run(reload: Boolean, bytes: ByteArray, primary: AwsSessionCredentials, replica: AwsSessionCredentials): CatalogTestRunPreparedV1 {
        requireTestActivation(caller === Thread.currentThread(), CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
        var success = false
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireTestActivation(!entered && stage === Stage.NEW)
            entered = true
            recovering = reload
            coordinator.catalogRefreshCustody.reserveTestRunActivation(this)
            reserved = true
            stage = Stage.CAPTURE
            frozen = CatalogTestRunActivationFrozenV1.capture(this, bytes)
            stage = Stage.SNAPSHOT
            snapshotOperation = execute(CatalogTestRunActivationKindV1.SNAPSHOT)
            originalSnapshot = checkNotNull(snapshotOperation).snapshot
            expectedSnapshot = originalSnapshot
            requireSqlCleanup()
            stage = Stage.READBACK
            predecessor.verify(checkNotNull(originalSnapshot), checkNotNull(frozen), primary, replica)
            predecessor.requireVerified(checkNotNull(originalSnapshot), checkNotNull(frozen))
            stage = Stage.ACQUIRE
            leaseOwner = UUID.randomUUID() // Never generate randomness under a connection, monitor or SQL lock.
            leaseStartedAtNanos = ownership.nanoClock.nanoTime() // Includes checkout/acquisition; never restarted at receipt return.
            leaseOperation = execute(CatalogTestRunActivationKindV1.LEASE_ACQUIRE)
            lease = checkNotNull(leaseOperation).lease
            requireActualLease()
            if (!recovering) {
                stage = Stage.PREPARE
                preparedOperation = execute(CatalogTestRunActivationKindV1.PREPARE)
                preparedSnapshot = checkNotNull(preparedOperation).snapshot
                expectedSnapshot = preparedSnapshot
            }
            stage = Stage.RELOAD
            reloadOperation = execute(CatalogTestRunActivationKindV1.PREPARED_RELOAD)
            expectedSnapshot = checkNotNull(reloadOperation).snapshot
            requireActualLease()
            requireSqlCleanup()
            success = true
        } catch (problem: Throwable) {
            observeFailure(problem)
            failed = true
            failure = problem
        } finally {
            runCatching(::close).exceptionOrNull()?.let { failure = preferSignerRotationCleanup(failure, it) }
        }
        throwIfSignalled()
        failure?.let { throw boundedTestActivationFailure(it) }
        requireTestActivation(success)
        allowedResult = true
        return CatalogTestRunPreparedV1.issuedBy(this)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execute(kind: CatalogTestRunActivationKindV1): CatalogTestRunActivationOperationV1 {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireTestActivation(selectedKind == null && activeInput == null)
        selectedKind = kind
        inputConstructionClaimed = false
        phaseEntered = false
        phaseRetainedForEntry = false
        return try {
            val input = CatalogTestRunActivationInputV1.create(this, kind)
            activeInput = input
            coordinator.testRunActivation.execute(input)
        } catch (problem: Throwable) {
            if (!phaseRetainedForEntry && problem is PersistencePhaseException && !problem.cleanupProven) sqlCleanupUnproven = true
            observeFailure(problem)
            throwIfSignalled()
            throw problem
        } finally {
            activeInput = null
            selectedKind = null
        }
    }

    internal fun requireInputCapture() {
        requireConnectionFree()
        requireRunning()
        requireTestActivation(stage === Stage.CAPTURE && entered && frozen == null && originalSnapshot == null)
    }

    internal fun requireInputConstruction(kind: CatalogTestRunActivationKindV1) {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireTestActivation(selectedKind === kind && !inputConstructionClaimed && activeInput == null)
        val allowed = when (kind) {
            CatalogTestRunActivationKindV1.SNAPSHOT -> stage === Stage.SNAPSHOT && snapshotOperation == null && expectedSnapshot == null && lease == null
            CatalogTestRunActivationKindV1.LEASE_ACQUIRE -> stage === Stage.ACQUIRE && snapshotOperation != null && expectedSnapshot === originalSnapshot &&
                leaseOperation == null && lease == null && leaseOwner != null && leaseStartedAtNanos != null
            CatalogTestRunActivationKindV1.PREPARE -> !projecting && stage === Stage.PREPARE && !recovering && preparedOperation == null &&
                expectedSnapshot === originalSnapshot && leaseOperation != null && lease != null &&
                (!freezing || (prepareArmIssued && prepareArmed && release != null))
            CatalogTestRunActivationKindV1.PREPARED_RELOAD -> !projecting && stage === Stage.RELOAD && reloadOperation == null && leaseOperation != null && lease != null &&
                ((recovering && preparedOperation == null && expectedSnapshot === originalSnapshot) ||
                    (!recovering && preparedOperation != null && preparedSnapshot === expectedSnapshot))
            CatalogTestRunActivationKindV1.SIGNATURE -> !projecting && freezing && stage === Stage.SIGNATURE && signatureOperation == null &&
                signatureSqlArmIssued && signatureSqlArmed && signed != null && leaseOperation != null && lease != null &&
                ((reloadOperation != null && reloadOperation?.snapshot === expectedSnapshot) ||
                    (recovering && reloadOperation == null && expectedSnapshot === originalSnapshot && expectedSnapshot?.signedTail != null))
            CatalogTestRunActivationKindV1.SIGNED_RELOAD -> !projecting && freezing && stage === Stage.SIGNED_RELOAD && signedReloadOperation == null &&
                signatureOperation != null && signatureOperation?.snapshot === expectedSnapshot && signed != null && signatureSqlArmed
            CatalogTestRunActivationKindV1.DELIVERY_RELOAD -> !projecting && delivering && stage === Stage.DELIVERY_RELOAD && deliveryReloadOperation == null &&
                leaseOperation != null && lease != null && signed != null && deliveryReadback != null && expectedSnapshot === originalSnapshot
            CatalogTestRunActivationKindV1.COMPLETE -> !projecting && delivering && stage === Stage.COMPLETE && completeOperation == null &&
                completeArmIssued && completeArmed && signed != null && leaseOperation != null && lease != null &&
                deliveryReloadOperation?.snapshot === expectedSnapshot && deliveryReadback?.state === CatalogTestRunActivationDeliveryReadbackV1.State.DUAL_COPY
            CatalogTestRunActivationKindV1.PENDING_RELOAD -> !projecting && delivering && stage === Stage.PENDING_RELOAD && pendingReloadOperation == null &&
                completeOperation != null && completeOperation?.snapshot === expectedSnapshot && signed != null && completeArmed
            CatalogTestRunActivationKindV1.PROJECT_RELOAD -> projecting && signed != null && leaseOperation != null && lease != null &&
                deliveryReadback?.state === CatalogTestRunActivationDeliveryReadbackV1.State.DUAL_COPY && projectionGrant == null &&
                ((stage === Stage.PROJECT_CAPTURE && projectionCaptureOperation == null && projectionReloadOperation == null &&
                    expectedSnapshot === originalSnapshot && projectionReadbacks == 1) ||
                    (stage === Stage.PROJECT_RECHECK && projectionCaptureOperation != null && projectionReloadOperation == null &&
                        expectedSnapshot === projectionCapturedSnapshot && projectionReadbacks == 2))
            CatalogTestRunActivationKindV1.PROJECT -> projecting && stage === Stage.PROJECT && projectOperation == null &&
                projectArmIssued && projectArmed && !projectionDispatchIssued && projectionGrant?.spent == true &&
                projectionReloadOperation?.snapshot === expectedSnapshot && expectedSnapshot?.completedTail?.projectedAt == null
            CatalogTestRunActivationKindV1.PROJECTED_RELOAD -> projecting && stage === Stage.PROJECTED_RELOAD && projectedReloadOperation == null &&
                projectionReadbacks == 2 && lease != null && signed != null && expectedSnapshot?.completedTail?.projectedAt != null &&
                ((projectOperation != null && projectOperation?.snapshot === expectedSnapshot && projectionDispatchIssued && projectArmed) ||
                    (projectOperation == null && projectionReloadOperation?.snapshot === expectedSnapshot && projectionGrant == null && !projectArmIssued))
        }
        requireTestActivation(allowed, CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
        if (kind !== CatalogTestRunActivationKindV1.SNAPSHOT) {
            requireRawVerified()
        }
        inputConstructionClaimed = true
    }

    private fun requireInputValues() {
        requireConnectionFree()
        requireRunning()
        requireTestActivation(selectedKind != null && inputConstructionClaimed && activeInput == null)
    }

    internal fun frozenInput(): CatalogTestRunActivationFrozenV1 {
        requireInputValues()
        return checkNotNull(frozen)
    }

    internal fun expectedSnapshot(): CatalogTestRunActivationSnapshotV1? {
        requireInputValues()
        return expectedSnapshot
    }

    internal fun currentLease(): CatalogTestRunActivationLeaseV1? {
        requireInputValues()
        return lease
    }

    internal fun acquisitionOwner(): UUID? {
        requireInputValues()
        return leaseOwner
    }

    internal fun recovering(): Boolean {
        requireInputValues()
        return recovering
    }

    internal fun signedInput(): CatalogTestRunActivationSignedV1? {
        requireInputValues()
        requireTestActivation(signed == null || ((freezing || delivering) && signed?.frozen === frozen))
        return signed
    }

    internal fun delivering(): Boolean { requireInputValues(); return delivering }
    internal fun projecting(): Boolean { requireInputValues(); return projecting }

    internal fun deliveryProofInput(): CatalogTestRunActivationDeliveryReadbackV1? {
        requireInputValues()
        requireTestActivation(deliveryReadback == null || delivering)
        return deliveryReadback
    }

    internal fun requireInput(input: CatalogTestRunActivationInputV1, candidate: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning()
        requireTestActivation(
            candidate === ownership && jdbc.dataSource === source && activeInput === input && input.original === this &&
                input.kind === selectedKind && input.frozen === frozen && input.expected === expectedSnapshot && input.lease === lease &&
                input.leaseOwner == leaseOwner && input.recovering == recovering && input.signed === signed && inputConstructionClaimed &&
                input.delivering == delivering && input.projecting == projecting && input.deliveryProof === deliveryReadback,
            CatalogTestRunActivationFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requirePhaseEntry(candidate: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireRunning()
        requireTestActivation(candidate === ownership && selectedKind?.path === path && path.catalogTestRunActivation && !phaseEntered &&
            activeInput?.path === path && activeInput?.original === this && inputConstructionClaimed, CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
        phaseEntered = true
    }

    internal fun authenticate(candidate: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireInput(checkNotNull(activeInput), candidate, jdbc)
        requireTestActivation(phaseEntered && originalPhase === PersistencePhaseOwnership.current())
        val authenticated = jdbc.query(
            "SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("authenticated") && !rows.wasNull() && !rows.next() }, username, username, database,
        )
        requireTestActivation(authenticated == true, CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
        requireRunning()
    }

    internal fun retainPhase(phase: PersistencePhaseContext) {
        requireTestActivation(caller === Thread.currentThread() && phaseEntered && selectedKind != null && originalPhase == null &&
            !phaseRetainedForEntry && !sqlCleanupUnproven && !outcomeUncertain, CatalogTestRunActivationFailureV1.CLEANUP_UNPROVEN)
        originalPhase = phase // Before publication/permits/checkout, including failed entry that never returns.
        phaseRetainedForEntry = true
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun observePhaseCleanup(phase: PersistencePhaseContext) {
        if (caller !== Thread.currentThread() || originalPhase !== phase) {
            sqlCleanupUnproven = true
            return
        }
        try {
            if (!phase.testRunActivationCleanupProven(this)) sqlCleanupUnproven = true
            if (phase.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) outcomeUncertain = true
            if (!sqlCleanupUnproven && !outcomeUncertain) originalPhase = null
        } catch (problem: Throwable) {
            sqlCleanupUnproven = true
            observeFailure(problem)
        }
    }

    internal fun requireInitialMaintenancePrepare(candidate: PersistencePhaseOwnership) {
        requireMaintenanceSelection(candidate, PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE)
        requireTestActivation(!projecting && !recovering && stage === Stage.PREPARE && preparedOperation == null && expectedSnapshot === originalSnapshot)
        predecessor.requireVerified(checkNotNull(originalSnapshot), checkNotNull(frozen))
        requireActualLease()
    }

    internal fun requireMaintenanceGate(candidate: PersistencePhaseOwnership, path: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireMaintenanceSelection(candidate, path)
        val input = checkNotNull(activeInput)
        when (input.kind) {
            CatalogTestRunActivationKindV1.SNAPSHOT -> throw CatalogTestRunActivationExceptionV1(CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
            CatalogTestRunActivationKindV1.PREPARE -> {
                requireInitialMaintenancePrepare(candidate)
                gate.requireUnownedOpen()
            }
            CatalogTestRunActivationKindV1.LEASE_ACQUIRE -> when {
                projecting -> requireProjectionGate(gate)
                delivering -> requireDeliveryGate(gate)
                recovering -> requirePreparedGate(gate)
                else -> gate.requireUnownedOpen()
            }
            CatalogTestRunActivationKindV1.PREPARED_RELOAD -> {
                requireTestActivation(!projecting && (recovering || (preparedOperation != null && preparedSnapshot === expectedSnapshot)))
                requirePreparedGate(gate)
            }
            CatalogTestRunActivationKindV1.SIGNATURE, CatalogTestRunActivationKindV1.SIGNED_RELOAD -> {
                requireTestActivation(!projecting && freezing && signatureSqlArmed && signed != null && input.signed === signed)
                requireActualLease()
                requirePreparedGate(gate)
            }
            CatalogTestRunActivationKindV1.DELIVERY_RELOAD, CatalogTestRunActivationKindV1.COMPLETE, CatalogTestRunActivationKindV1.PENDING_RELOAD -> {
                requireTestActivation(!projecting && delivering && signed != null && input.signed === signed && input.deliveryProof === deliveryReadback)
                requireActualLease()
                requireDeliveryGate(gate)
            }
            CatalogTestRunActivationKindV1.PROJECT_RELOAD, CatalogTestRunActivationKindV1.PROJECT, CatalogTestRunActivationKindV1.PROJECTED_RELOAD -> {
                requireTestActivation(projecting && delivering && !deliveryPublishing && signed != null && input.signed === signed && input.deliveryProof === deliveryReadback)
                requireActualLease()
                requireProjectionGate(gate)
            }
        }
    }

    private fun requireMaintenanceSelection(candidate: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireRunning()
        val input = checkNotNull(activeInput)
        requireTestActivation(candidate === ownership && originalPhase === PersistencePhaseOwnership.current() && phaseEntered && inputConstructionClaimed &&
            input.original === this && selectedKind === input.kind && input.path === path && input.frozen === frozen &&
            input.expected === expectedSnapshot && input.lease === lease && input.recovering == recovering && input.signed === signed &&
            input.delivering == delivering && input.projecting == projecting && input.deliveryProof === deliveryReadback,
            CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
        requireRawVerified()
    }

    private fun requirePreparedGate(gate: PersistenceComplaintMaintenanceGateV1) {
        val input = checkNotNull(frozen)
        requireTestActivation(gate.matchesPrepared(input.token, input.scope, input.unsignedBytes(), input.unsignedHash()), CatalogTestRunActivationFailureV1.STATE_REFUSED)
    }

    private fun requireDeliveryGate(gate: PersistenceComplaintMaintenanceGateV1) {
        requireTestActivation(!projecting && delivering && deliveryRelease != null && signed != null)
        val input = checkNotNull(frozen)
        val matches = if (checkNotNull(expectedSnapshot).completedTail != null) {
            gate.matchesPending(input.token, input.scope, input.unsignedBytes(), input.unsignedHash())
        } else gate.matchesPrepared(input.token, input.scope, input.unsignedBytes(), input.unsignedHash())
        requireTestActivation(matches, CatalogTestRunActivationFailureV1.STATE_REFUSED)
    }

    private fun requireProjectionGate(gate: PersistenceComplaintMaintenanceGateV1) {
        requireTestActivation(projecting && delivering && !deliveryPublishing && deliveryRelease != null && signed != null)
        val input = checkNotNull(frozen)
        val completed = checkNotNull(checkNotNull(expectedSnapshot).completedTail)
        val matches = if (completed.projectedAt == null) gate.matchesPending(input.token, input.scope, input.unsignedBytes(), input.unsignedHash())
        else gate.matchesProjected(input.token, input.scope, input.unsignedBytes(), input.unsignedHash())
        requireTestActivation(matches, CatalogTestRunActivationFailureV1.STATE_REFUSED)
    }

    private fun requireRawVerified() {
        if (delivering) {
            val proof = checkNotNull(deliveryReadback)
            proof.requireSource(checkNotNull(originalSnapshot), checkNotNull(frozen), checkNotNull(signed))
            deliveryAssembly.requireVerified(proof)
        } else predecessor.requireVerified(checkNotNull(originalSnapshot), checkNotNull(frozen))
    }

    internal fun requireReadback(selected: CatalogTestRunActivationPredecessorV1, snapshot: CatalogTestRunActivationSnapshotV1, input: CatalogTestRunActivationFrozenV1) {
        requireProviderRunning()
        requireTestActivation(predecessor === selected && originalSnapshot === snapshot && snapshotOperation?.snapshot === snapshot && frozen === input)
    }

    internal fun requireProviderRunning() {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireTestActivation((!delivering && stage === Stage.READBACK && leaseStartedAtNanos == null) ||
            (stage === Stage.RECHECK_READBACK && freezing && !recovering && preparedOperation != null && prepareArmed && lease != null && !signReadbackRechecked) ||
            (delivering && stage === Stage.DELIVERY_READBACK && deliveryRelease != null && signed != null &&
                ((leaseStartedAtNanos == null && deliveryReloadOperation == null && projectionReadbacks == 0) ||
                    (!projecting && deliveryPublishing && putConstructionIssued && publicationAcknowledgement != null && deliveryReloadOperation != null) ||
                    (projecting && !deliveryPublishing && projectionReadbacks == 1 && projectionCaptureOperation != null &&
                        expectedSnapshot === projectionCapturedSnapshot && projectionReloadOperation == null && lease != null))) ||
            (!projecting && delivering && deliveryPublishing && stage === Stage.PUT && publicationArmed && putConstructionIssued && publicationAcknowledgement == null),
            CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
    }

    internal fun requireRawDeliverySnapshot(snapshot: CatalogTestRunActivationSnapshotV1, input: CatalogTestRunActivationFrozenV1, value: CatalogTestRunActivationSignedV1) {
        requireProviderRunning()
        requireTestActivation(delivering && originalSnapshot === snapshot && frozen === input && signed === value)
        if (projecting) snapshot.requireProjection(input, value) else snapshot.requireDelivery(input, value)
    }

    internal fun custodySnapshot(): CatalogTestRunActivationSnapshotV1 {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireTestActivation(freezing || delivering)
        return checkNotNull(expectedSnapshot)
    }

    internal fun custodyLease(): CatalogTestRunActivationLeaseV1 {
        requireConnectionFree()
        requireSqlCleanup()
        requireTestActivation(freezing || delivering)
        requireActualLease()
        return checkNotNull(lease)
    }

    internal fun requireReleaseCapture(selected: CatalogTestRunActivationReleaseCustodyV1, input: CatalogTestRunActivationFrozenV1, created: Boolean) {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireTestActivation((freezing || delivering) && custody === selected && frozen === input && release == null && created == !recovering &&
            ((created && stage === Stage.ALLOCATE && expectedSnapshot === originalSnapshot && lease != null) ||
                (!created && stage === Stage.EXISTING_CUSTODY && snapshotOperation == null && lease == null)))
    }

    private fun requireRelease(selected: CatalogTestRunActivationReleaseV1) {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireTestActivation(freezing && release === selected && custody != null)
        requireActualLease()
        predecessor.requireVerified(checkNotNull(originalSnapshot), checkNotNull(frozen))
    }

    internal fun requirePrepareArm(selected: CatalogTestRunActivationReleaseV1) {
        requireRelease(selected)
        requireTestActivation(stage === Stage.ARM_PREPARE && !recovering && prepareArmIssued && !prepareArmed && preparedOperation == null &&
            expectedSnapshot === originalSnapshot)
    }

    internal fun requirePrepared(selected: CatalogTestRunActivationReleaseV1, snapshot: CatalogTestRunActivationSnapshotV1) {
        requireRelease(selected)
        requireTestActivation(stage === Stage.PREPARE && !recovering && prepareArmed && preparedOperation?.snapshot === snapshot && expectedSnapshot === snapshot)
    }

    internal fun requireSignArm(selected: CatalogTestRunActivationReleaseV1, snapshot: CatalogTestRunActivationSnapshotV1) {
        requireRelease(selected)
        requireTestActivation(stage === Stage.ARM_SIGN && !recovering && prepareArmed && preparedOperation != null && signReadbackRechecked &&
            signArmIssued && !signArmed && !signConstructionIssued && signed == null &&
            reloadOperation?.snapshot === snapshot && expectedSnapshot === snapshot && snapshot.signedTail == null)
    }

    internal fun requireSignConstruction(selected: CatalogTestRunActivationAssemblyV1, input: CatalogTestRunActivationFrozenV1) {
        requireRelease(checkNotNull(release))
        requireTestActivation(assembly === selected && frozen === input && stage === Stage.SIGN && !recovering && signReadbackRechecked &&
            signArmIssued && signArmed && !signConstructionIssued && signed == null && reloadOperation?.snapshot === expectedSnapshot)
        signConstructionIssued = true // No retry if budget, native construction, dispatch or returned-byte custody fails next.
    }

    internal fun requireSignProviderRunning(selected: CatalogTestRunActivationAssemblyV1) {
        requireRelease(checkNotNull(release))
        requireTestActivation(assembly === selected && stage === Stage.SIGN && !recovering && signArmed && signConstructionIssued && signed == null)
    }

    internal fun requireSignatureReturn(selected: CatalogTestRunActivationReleaseV1) {
        requireRelease(selected)
        requireTestActivation(stage === Stage.SIGN && !recovering && signArmed && signConstructionIssued && signed == null)
        assembly.requireCleanup()
    }

    internal fun requireSignatureSqlArm(selected: CatalogTestRunActivationReleaseV1, value: CatalogTestRunActivationSignedV1) {
        requireRelease(selected)
        requireTestActivation(stage === Stage.ARM_SIGNATURE && signatureSqlArmIssued && !signatureSqlArmed && signed === value && value.frozen === frozen &&
            ((reloadOperation != null && reloadOperation?.snapshot === expectedSnapshot) ||
                (recovering && expectedSnapshot === originalSnapshot && expectedSnapshot?.signedTail != null)))
        assembly.requireCleanup()
    }

    internal fun requireSignaturePersisted(selected: CatalogTestRunActivationReleaseV1, snapshot: CatalogTestRunActivationSnapshotV1) {
        requireRelease(selected)
        requireTestActivation(stage === Stage.SIGNATURE && signatureSqlArmed && signatureOperation?.snapshot === snapshot &&
            expectedSnapshot === snapshot && snapshot.signedTail != null)
    }

    internal fun requireSignedReload(selected: CatalogTestRunActivationReleaseV1, snapshot: CatalogTestRunActivationSnapshotV1) {
        requireRelease(selected)
        requireTestActivation(stage === Stage.SIGNED_RELOAD && signatureSqlArmed && signatureOperation != null && signedReloadOperation?.snapshot === snapshot &&
            expectedSnapshot === snapshot && snapshot.signedTail != null)
    }

    internal fun requireDeliveryReleaseCapture(
        freeze: CatalogTestRunActivationReleaseV1,
        selected: CatalogTestRunActivationReleaseCustodyV1,
        input: CatalogTestRunActivationFrozenV1,
        value: CatalogTestRunActivationSignedV1,
        publishing: Boolean,
        projection: Boolean,
    ) {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireTestActivation(delivering && !freezing && recovering && stage === Stage.EXISTING_CUSTODY && release === freeze &&
            custody === selected && frozen === input && signed === value && value.frozen === input &&
            publishing == deliveryPublishing && projection == projecting && (!projection || !publishing) &&
            deliveryRelease == null && snapshotOperation == null && lease == null)
    }

    internal fun requireDeliveryObservation(
        selected: CatalogTestRunActivationDeliveryAssemblyV1,
        snapshot: CatalogTestRunActivationSnapshotV1,
        input: CatalogTestRunActivationFrozenV1,
        value: CatalogTestRunActivationSignedV1,
    ) {
        requireProviderRunning()
        requireTestActivation(deliveryAssembly === selected && stage === Stage.DELIVERY_READBACK &&
            originalSnapshot === snapshot && snapshotOperation?.snapshot === snapshot && frozen === input && signed === value)
    }

    internal fun requireDeliveryProof(selected: CatalogTestRunActivationDeliveryReleaseV1, proof: CatalogTestRunActivationDeliveryReadbackV1) {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireTestActivation(delivering && !freezing && deliveryRelease === selected && deliveryReadback === proof)
        requireRawVerified()
    }

    private fun requireDeliveryRelease(selected: CatalogTestRunActivationDeliveryReleaseV1) {
        requireDeliveryProof(selected, checkNotNull(deliveryReadback))
        requireActualLease()
    }

    internal fun requirePublicationArm(selected: CatalogTestRunActivationDeliveryReleaseV1, proof: CatalogTestRunActivationDeliveryReadbackV1) {
        requireDeliveryRelease(selected)
        requireTestActivation(!projecting && stage === Stage.ARM_PUBLICATION && deliveryPublishing && publicationArmIssued && !publicationArmed &&
            !putConstructionIssued && publicationAcknowledgement == null && proof === deliveryReadback &&
            proof.state === CatalogTestRunActivationDeliveryReadbackV1.State.UNPUBLISHED &&
            deliveryReloadOperation?.snapshot === expectedSnapshot && expectedSnapshot?.completedTail == null)
    }

    internal fun requirePutConstruction(selected: CatalogTestRunActivationDeliveryAssemblyV1, input: CatalogTestRunActivationFrozenV1, value: CatalogTestRunActivationSignedV1) {
        requireDeliveryRelease(checkNotNull(deliveryRelease))
        requireTestActivation(!projecting && deliveryAssembly === selected && frozen === input && signed === value && stage === Stage.PUT && deliveryPublishing &&
            publicationArmIssued && publicationArmed && !putConstructionIssued && publicationAcknowledgement == null &&
            deliveryReloadOperation?.snapshot === expectedSnapshot && expectedSnapshot?.completedTail == null)
        putConstructionIssued = true // Spent before durable reread, budget checks, or native SDK/raw-client construction.
        checkNotNull(deliveryRelease).consumePublicationArm()
    }

    internal fun requirePublicationAcknowledgement(selected: CatalogTestRunActivationDeliveryReleaseV1, value: CatalogPrimaryPutAcknowledgementV1) {
        requireDeliveryRelease(selected)
        requireTestActivation(!projecting && stage === Stage.PUT && deliveryPublishing && publicationArmed && putConstructionIssued && publicationAcknowledgement === value)
        deliveryAssembly.requireAcknowledgement(value)
    }

    internal fun requireDeliveryEvidence(selected: CatalogTestRunActivationDeliveryReleaseV1, proof: CatalogTestRunActivationDeliveryReadbackV1) {
        requireDeliveryRelease(selected)
        requireTestActivation(!projecting && stage === Stage.DELIVERY_EVIDENCE && proof === deliveryReadback && deliveryReloadOperation?.snapshot === expectedSnapshot)
    }

    internal fun requireCompletionArm(selected: CatalogTestRunActivationDeliveryReleaseV1, proof: CatalogTestRunActivationDeliveryReadbackV1) {
        requireDeliveryRelease(selected)
        requireTestActivation(!projecting && stage === Stage.ARM_COMPLETE && completeArmIssued && !completeArmed && completeOperation == null &&
            proof === deliveryReadback && proof.state === CatalogTestRunActivationDeliveryReadbackV1.State.DUAL_COPY &&
            deliveryReloadOperation?.snapshot === expectedSnapshot)
    }

    internal fun requireCompletedDelivery(selected: CatalogTestRunActivationDeliveryReleaseV1, snapshot: CatalogTestRunActivationSnapshotV1) {
        requireDeliveryRelease(selected)
        requireTestActivation(!projecting && stage === Stage.COMPLETE && completeArmed && completeOperation?.snapshot === snapshot &&
            expectedSnapshot === snapshot && snapshot.completedTail != null)
    }

    internal fun requirePendingReload(selected: CatalogTestRunActivationDeliveryReleaseV1, snapshot: CatalogTestRunActivationSnapshotV1) {
        requireDeliveryRelease(selected)
        requireTestActivation(!projecting && stage === Stage.PENDING_RELOAD && completeArmed && completeOperation != null &&
            pendingReloadOperation?.snapshot === snapshot && expectedSnapshot === snapshot && snapshot.completedTail != null)
    }

    internal fun requireProjectArm(selected: CatalogTestRunActivationDeliveryReleaseV1, proof: CatalogTestRunActivationDeliveryReadbackV1) {
        requireDeliveryRelease(selected)
        val grant = requireProjectionGrant()
        requireTestActivation(stage === Stage.ARM_PROJECT && grant.spent && projectArmIssued && !projectArmed && !projectionDispatchIssued &&
            projectOperation == null && proof === grant.proof && projectionReloadOperation?.snapshot === expectedSnapshot &&
            expectedSnapshot?.completedTail?.projectedAt == null && expectedSnapshot?.projectionRows != null)
    }

    internal fun requireProjected(selected: CatalogTestRunActivationDeliveryReleaseV1, snapshot: CatalogTestRunActivationSnapshotV1) {
        requireDeliveryRelease(selected)
        requireTestActivation(projecting && stage === Stage.PROJECT && projectArmed && projectionGrant?.spent == true && projectionDispatchIssued &&
            projectOperation?.input?.original === this && projectOperation?.input?.kind === CatalogTestRunActivationKindV1.PROJECT &&
            projectOperation?.snapshot === snapshot && snapshot === expectedSnapshot && snapshot.completedTail?.projectedAt != null && snapshot.projectionRows != null)
    }

    internal fun requireProjectedReload(selected: CatalogTestRunActivationDeliveryReleaseV1, snapshot: CatalogTestRunActivationSnapshotV1) {
        requireDeliveryRelease(selected)
        requireTestActivation(projecting && stage === Stage.PROJECTED_RELOAD && projectionReadbacks == 2 &&
            projectedReloadOperation?.input?.original === this && projectedReloadOperation?.input?.kind === CatalogTestRunActivationKindV1.PROJECTED_RELOAD &&
            projectedReloadOperation?.snapshot === snapshot && snapshot === expectedSnapshot && snapshot.completedTail?.projectedAt != null && snapshot.projectionRows != null)
    }

    internal fun sampleWallTime(): Instant {
        requireConnectionFree()
        requireRunning()
        val sampled = clock.instant()
        requireRunning()
        requireTestActivation(sampled.epochSecond in 0..253402300799L && lastWall?.let { !sampled.isBefore(it) } != false)
        lastWall = sampled
        return sampled
    }

    internal fun requireCustody(selected: CatalogReadbackRefreshCustodyV1) = requireTestActivation(
        caller === Thread.currentThread() && coordinator.catalogRefreshCustody === selected, CatalogTestRunActivationFailureV1.PROCESS_REFUSED,
    )

    private fun requireRunning() {
        throwIfSignalled()
        requireTestActivation(caller === Thread.currentThread() && !closed && !failed && !released, CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
        requireTestActivation(!sqlCleanupUnproven && !outcomeUncertain, CatalogTestRunActivationFailureV1.CLEANUP_UNPROVEN)
        requireTestActivation(!Thread.currentThread().isInterrupted, CatalogTestRunActivationFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
        requireProcess()
        if (reserved) coordinator.catalogRefreshCustody.requireTestRunActivation(this)
        leaseStartedAtNanos?.let { started ->
            val elapsed = ownership.nanoClock.nanoTime() - started
            requireTestActivation(elapsed in 0 until LEASE_NANOS, CatalogTestRunActivationFailureV1.TIME_BUDGET_EXHAUSTED)
        }
    }

    private fun requireActualLease() {
        requireRunning()
        requireTestActivation(leaseOperation?.input?.original === this && leaseOperation?.input?.kind === CatalogTestRunActivationKindV1.LEASE_ACQUIRE &&
            lease != null && lease?.owner == leaseOwner && leaseStartedAtNanos != null, CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
    }

    private fun requireSqlCleanup() = requireTestActivation(
        caller === Thread.currentThread() && !sqlCleanupUnproven && !outcomeUncertain && originalPhase == null,
        CatalogTestRunActivationFailureV1.CLEANUP_UNPROVEN,
    )

    private fun requireProcess() {
        process.requireUnchangedConfiguration()
        requireTestActivation(process.pools.catalogCoordinator === coordinator && coordinator.ownership === ownership && coordinator.manager === manager &&
            coordinator.dataSource === source && coordinator.catalogTestRunActivation && !coordinator.catalogSignerRotationActivation &&
            !coordinator.catalogSignerRotationRecovery && !coordinator.catalogSignerRotationDelivery && !coordinator.catalogSignerRotationAuthoring &&
            !coordinator.catalogGenesisAuthoring && !coordinator.catalogGenesisFinalization && !coordinator.desiredInstallationOperator &&
            (process.pools.epochRotation == null) == (process.activeFirstCut == null) && process.consumers.journalConfiguration.scope.testOnly &&
            username != VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME &&
            username != VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME, CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
        coordinator.requireResources()
    }

    internal fun observeFailure(problem: Throwable) {
        val signal = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("Catalog TEST activation cancelled.")
            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ||
                (problem is CatalogReadbackException && problem.code === CatalogReadbackFailure.INTERRUPTED) ||
                (problem is CatalogSignerRotationFreezeExceptionV1 && problem.code === CatalogSignerRotationFreezeFailureV1.INTERRUPTED) ||
                (problem is CatalogTestRunActivationCustodyExceptionV1 && problem.code === CatalogTestRunActivationCustodyFailureV1.INTERRUPTED) ||
                (problem is CatalogTestRunActivationExceptionV1 && problem.code === CatalogTestRunActivationFailureV1.INTERRUPTED) ->
                InterruptedException("Catalog TEST activation interrupted.")
            else -> return
        }
        while (true) {
            val before = originalSignal.get()
            if (before is Error || (before is CancellationException && signal !is Error) || (before is InterruptedException && signal is InterruptedException)) return
            if (originalSignal.compareAndSet(before, signal)) return
        }
    }

    internal fun throwIfSignalled() {
        originalSignal.get()?.let { signal ->
            if (signal is InterruptedException) Thread.currentThread().interrupt()
            throw signal
        }
    }

    override fun close() {
        requireTestActivation(caller === Thread.currentThread(), CatalogTestRunActivationFailureV1.CLEANUP_UNPROVEN)
        if (closed) {
            closeFailure?.let { throw it }
            return
        }
        closed = true
        failed = true
        stage = Stage.CLOSED
        closeFailure = CatalogTestRunActivationExceptionV1(CatalogTestRunActivationFailureV1.CLEANUP_UNPROVEN)
        val outcomes = listOf(
            runCatching(predecessor::close), runCatching(assembly::close), runCatching(deliveryAssembly::close), runCatching { custody?.close() },
            runCatching(::requireConnectionFree), runCatching(::requireSqlCleanup), runCatching(::throwIfSignalled),
            runCatching {
                requireTestActivation(!Thread.currentThread().isInterrupted, CatalogTestRunActivationFailureV1.INTERRUPTED)
                budget.remainingMillis(1)
            },
        )
        var failure: Throwable? = null
        outcomes.forEach { outcome -> outcome.exceptionOrNull()?.let { observeFailure(it); failure = preferSignerRotationCleanup(failure, it) } }
        throwIfSignalled()
        failure?.let { throw boundedTestActivationFailure(preferSignerRotationCleanup(it, checkNotNull(closeFailure))) }
        cleanupProven = true
        if (reserved) coordinator.catalogRefreshCustody.releaseTestRunActivationAfterCleanup(this)
        released = true
        closeFailure = null
    }

    internal fun requireActualCleanup() {
        requireConnectionFree()
        requireSqlCleanup()
        requireTestActivation(caller === Thread.currentThread() && closed && cleanupProven, CatalogTestRunActivationFailureV1.CLEANUP_UNPROVEN)
    }

    internal fun preparedReceiptInput(): CatalogTestRunActivationFrozenV1 {
        requireActualCleanup()
        requireTestActivation(!projecting && !delivering && !freezing && allowedResult && released && closeFailure == null && reloadOperation?.input?.original === this &&
            reloadOperation?.input?.kind === CatalogTestRunActivationKindV1.PREPARED_RELOAD && reloadOperation?.snapshot === expectedSnapshot)
        checkNotNull(reloadOperation).requireReleased()
        budget.remainingMillis(1)
        return checkNotNull(frozen)
    }

    internal fun signedReceiptInput(): Pair<CatalogTestRunActivationFrozenV1, CatalogTestRunActivationSignedV1> {
        requireActualCleanup()
        requireTestActivation(!projecting && freezing && allowedSignedResult && released && closeFailure == null &&
            signedReloadOperation?.input?.original === this && signedReloadOperation?.input?.kind === CatalogTestRunActivationKindV1.SIGNED_RELOAD &&
            signedReloadOperation?.snapshot === expectedSnapshot && expectedSnapshot?.signedTail != null)
        checkNotNull(signatureOperation).requireReleased()
        checkNotNull(signedReloadOperation).requireReleased()
        assembly.requireCleanup()
        budget.remainingMillis(1)
        return checkNotNull(frozen) to checkNotNull(signed)
    }

    internal fun completedReceiptInput(): Triple<CatalogTestRunActivationFrozenV1, CatalogTestRunActivationSignedV1, CatalogTestRunActivationCompletedTailV1> {
        requireActualCleanup()
        requireTestActivation(!projecting && delivering && !freezing && allowedCompletedResult && released && closeFailure == null &&
            pendingReloadOperation?.input?.original === this && pendingReloadOperation?.input?.kind === CatalogTestRunActivationKindV1.PENDING_RELOAD &&
            pendingReloadOperation?.snapshot === expectedSnapshot && expectedSnapshot?.completedTail != null)
        checkNotNull(completeOperation).requireReleased()
        checkNotNull(pendingReloadOperation).requireReleased()
        deliveryAssembly.requireProviderCleanup()
        budget.remainingMillis(1)
        return Triple(checkNotNull(frozen), checkNotNull(signed), checkNotNull(checkNotNull(expectedSnapshot).completedTail))
    }

    internal fun projectedReceiptInput(): Triple<CatalogTestRunActivationFrozenV1, CatalogTestRunActivationSignedV1, CatalogTestRunActivationCompletedTailV1> {
        requireActualCleanup()
        requireTestActivation(projecting && delivering && !freezing && !deliveryPublishing && allowedProjectedResult && released && closeFailure == null &&
            projectionReadbacks == 2 && projectedReloadOperation?.input?.original === this &&
            projectedReloadOperation?.input?.kind === CatalogTestRunActivationKindV1.PROJECTED_RELOAD &&
            projectedReloadOperation?.snapshot === expectedSnapshot && expectedSnapshot?.completedTail?.projectedAt != null && expectedSnapshot?.projectionRows != null)
        checkNotNull(projectionCaptureOperation).requireReleased()
        checkNotNull(projectionReloadOperation).requireReleased()
        projectOperation?.requireReleased()
        checkNotNull(projectedReloadOperation).requireReleased()
        deliveryAssembly.requireProviderCleanup()
        budget.remainingMillis(1)
        return Triple(checkNotNull(frozen), checkNotNull(signed), checkNotNull(checkNotNull(expectedSnapshot).completedTail))
    }

    override fun toString(): String = if (projecting) {
        "CatalogTestRunActivationV1(first-PROJECT-closed,no-admission-or-reopen-authority,redacted)"
    } else if (delivering) {
        "CatalogTestRunActivationV1(COMPLETED-pending-boundary,no-PROJECT-or-run-authority,redacted)"
    } else if (freezing) {
        "CatalogTestRunActivationV1(signed-PREPARED-boundary,no-PUT-or-run-authority,redacted)"
    } else {
        "CatalogTestRunActivationV1(PREPARED-only,not-run-or-Sign-authority,redacted)"
    }

    private enum class Stage {
        NEW, CAPTURE, EXISTING_CUSTODY, SNAPSHOT, READBACK, ACQUIRE, ALLOCATE, ARM_PREPARE, PREPARE,
        RECHECK_READBACK, RELOAD, ARM_SIGN, SIGN, ARM_SIGNATURE, SIGNATURE, SIGNED_RELOAD,
        DELIVERY_READBACK, DELIVERY_RELOAD, ARM_PUBLICATION, PUT, DELIVERY_EVIDENCE, ARM_COMPLETE, COMPLETE, PENDING_RELOAD,
        PROJECT_CAPTURE, PROJECT_RECHECK, ARM_PROJECT, PROJECT, PROJECTED_RELOAD, CLOSED,
    }

    /** Never returned or accepted by another owner. Its original released input/proof/snapshot/lease stay bound for one dispatch. */
    private class ProjectionGrant(
        val operation: CatalogTestRunActivationOperationV1,
        val input: CatalogTestRunActivationInputV1,
        val snapshot: CatalogTestRunActivationSnapshotV1,
        val proof: CatalogTestRunActivationDeliveryReadbackV1,
        val lease: CatalogTestRunActivationLeaseV1,
    ) { var spent = false }

    companion object {
        private const val LEASE_NANOS = 30_000_000_000L

        fun begin(process: VersionBoundTestNamespaceProcessV1, installationLimit: Long): CatalogTestRunActivationV1 =
            CatalogTestRunActivationV1(process, installationLimit, startBudget(process), null, Clock.systemUTC())

        /** Raw read-only HTTP/wall-time seams; actual named root, SDK/native ownership and original SQL cleanup are unchanged. */
        internal fun withHttpFixture(
            process: VersionBoundTestNamespaceProcessV1,
            installationLimit: Long,
            readback: () -> SdkHttpClient,
            clock: Clock,
        ): CatalogTestRunActivationV1 = CatalogTestRunActivationV1(process, installationLimit, startBudget(process), readback, clock)

        /** Raw HTTP seams only: no provider result, signer substitution, SQL snapshot or cleanup permit is accepted. */
        internal fun withHttpFixtures(
            process: VersionBoundTestNamespaceProcessV1,
            installationLimit: Long,
            signing: () -> SdkHttpClient,
            readback: () -> SdkHttpClient,
            clock: Clock,
        ): CatalogTestRunActivationV1 = CatalogTestRunActivationV1(process, installationLimit, startBudget(process), readback, clock, signing)

        /** Raw PUT/read-only seams only; no Sign credentials, returned proof or asserted cleanup. */
        internal fun withDeliveryHttpFixtures(
            process: VersionBoundTestNamespaceProcessV1,
            installationLimit: Long,
            put: () -> SdkHttpClient,
            readback: () -> SdkHttpClient,
            clock: Clock,
        ): CatalogTestRunActivationV1 = CatalogTestRunActivationV1(process, installationLimit, startBudget(process), readback, clock, putFactory = put)

        private fun startBudget(process: VersionBoundTestNamespaceProcessV1): PersistenceTimeBudget {
            requireConnectionFree()
            process.requireUnchangedConfiguration()
            return PersistenceTimeBudget.start(process.catalogActivation.totalAttemptMillis, process.pools.catalogCoordinator.ownership.nanoClock)
        }
    }
}

/** Diagnostic receipt only. No production API accepts it as a run, publication, drained-provider or current-state capability. */
internal class CatalogTestRunPreparedV1 private constructor(val operationToken: UUID, val dataScopeId: UUID, val generation: Long, val unsignedSha256: String) {
    override fun toString(): String = "CatalogTestRunPreparedV1(PREPARED,no-Sign-or-run-authority,redacted)"

    companion object {
        internal fun issuedBy(original: CatalogTestRunActivationV1): CatalogTestRunPreparedV1 {
            val input = original.preparedReceiptInput()
            return CatalogTestRunPreparedV1(input.token, input.scope, input.generation, HexFormat.of().formatHex(input.unsignedHash()))
        }
    }
}

/** Diagnostic signed checkpoint only. No run, publication, registration, provider drain or reopen authority. */
internal class CatalogTestRunSignedPreparedV1 private constructor(
    val operationToken: UUID,
    val dataScopeId: UUID,
    val generation: Long,
    val unsignedSha256: String,
    val envelopeSha256: String,
) {
    override fun toString(): String = "CatalogTestRunSignedPreparedV1(PREPARED,no-publication-or-run-authority,redacted)"

    companion object {
        internal fun issuedBy(original: CatalogTestRunActivationV1): CatalogTestRunSignedPreparedV1 {
            val (input, signed) = original.signedReceiptInput()
            return CatalogTestRunSignedPreparedV1(input.token, input.scope, input.generation, HexFormat.of().formatHex(input.unsignedHash()), signed.envelopeSha256)
        }
    }
}

/** Diagnostic pending checkpoint only. No production API accepts this as PROJECT, registration, drain or reopen permission. */
internal class CatalogTestRunCompletedPendingV1 private constructor(
    val operationToken: UUID,
    val dataScopeId: UUID,
    val generation: Long,
    val unsignedSha256: String,
    val envelopeSha256: String,
    val objectVersion: String,
    val completedAt: Instant,
) {
    override fun toString(): String = "CatalogTestRunCompletedPendingV1(COMPLETED-pending,no-PROJECT-or-run-authority,redacted)"

    companion object {
        internal fun issuedBy(original: CatalogTestRunActivationV1): CatalogTestRunCompletedPendingV1 {
            val (input, signed, completed) = original.completedReceiptInput()
            return CatalogTestRunCompletedPendingV1(input.token, input.scope, input.generation, HexFormat.of().formatHex(input.unsignedHash()),
                signed.envelopeSha256, completed.objectVersion, completed.completedAt)
        }
    }
}

/** Historical diagnostic only. ACTIVE rows and this receipt cannot issue credentials, enable routing or reopen gates. */
internal class CatalogTestRunProjectedV1 private constructor(
    val operationToken: UUID,
    val dataScopeId: UUID,
    val generation: Long,
    val unsignedSha256: String,
    val envelopeSha256: String,
    val objectVersion: String,
    val completedAt: Instant,
    val projectedAt: Instant,
) {
    override fun toString(): String = "CatalogTestRunProjectedV1(exact-historical-effect,no-admission-or-reopen-authority,redacted)"

    companion object {
        internal fun issuedBy(original: CatalogTestRunActivationV1): CatalogTestRunProjectedV1 {
            val (input, signed, completed) = original.projectedReceiptInput()
            return CatalogTestRunProjectedV1(input.token, input.scope, input.generation, HexFormat.of().formatHex(input.unsignedHash()),
                signed.envelopeSha256, completed.objectVersion, completed.completedAt, checkNotNull(completed.projectedAt))
        }
    }
}

internal enum class CatalogTestRunActivationFailureV1 { INPUT_REFUSED, PROCESS_REFUSED, STATE_REFUSED, DELIVERY_PENDING, TIME_BUDGET_EXHAUSTED, INTERRUPTED, CLEANUP_UNPROVEN }

/** Bounded only: no SQL, provider, credential, approval or original diagnostic graph is attached. */
internal class CatalogTestRunActivationExceptionV1(val code: CatalogTestRunActivationFailureV1) : RuntimeException("Catalog TEST activation refused: ${code.name}.")

internal fun requireTestActivation(condition: Boolean, code: CatalogTestRunActivationFailureV1 = CatalogTestRunActivationFailureV1.INPUT_REFUSED) {
    if (!condition) throw CatalogTestRunActivationExceptionV1(code)
}

@Suppress("InstanceOfCheckForException")
private fun boundedTestActivationFailure(problem: Throwable): CatalogTestRunActivationExceptionV1 {
    if (problem is Error || problem is CancellationException || problem is InterruptedException) throw problem
    if (problem is CatalogTestRunActivationExceptionV1) return problem
    val code = when {
        problem is PersistencePhaseException && !problem.cleanupProven -> CatalogTestRunActivationFailureV1.CLEANUP_UNPROVEN
        problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED -> CatalogTestRunActivationFailureV1.TIME_BUDGET_EXHAUSTED
        problem is PersistenceBoundaryException && problem.code === PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED -> CatalogTestRunActivationFailureV1.TIME_BUDGET_EXHAUSTED
        problem is CatalogReadbackException && problem.code === CatalogReadbackFailure.LIMIT_EXCEEDED -> CatalogTestRunActivationFailureV1.TIME_BUDGET_EXHAUSTED
        problem is CatalogTestRunActivationCustodyExceptionV1 && problem.code === CatalogTestRunActivationCustodyFailureV1.TIME_BUDGET ->
            CatalogTestRunActivationFailureV1.TIME_BUDGET_EXHAUSTED
        problem is CatalogTestRunActivationCustodyExceptionV1 && problem.code === CatalogTestRunActivationCustodyFailureV1.CLEANUP_UNCERTAIN ->
            CatalogTestRunActivationFailureV1.CLEANUP_UNPROVEN
        problem is CatalogSigningExceptionV1 && problem.code === CatalogSigningFailureV1.CLOSE_FAILURE -> CatalogTestRunActivationFailureV1.CLEANUP_UNPROVEN
        problem is CatalogPrimaryPutExceptionV1 && problem.code === CatalogPrimaryPutFailureV1.CLOSE_FAILURE -> CatalogTestRunActivationFailureV1.CLEANUP_UNPROVEN
        else -> CatalogTestRunActivationFailureV1.STATE_REFUSED
    }
    return CatalogTestRunActivationExceptionV1(code)
}
