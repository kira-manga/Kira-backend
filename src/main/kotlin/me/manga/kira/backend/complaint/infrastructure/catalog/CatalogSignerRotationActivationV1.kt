package me.manga.kira.backend.complaint.infrastructure.catalog

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
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutAcknowledgementV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Fixed schema1 actual2->3 producer on unchanged D7. Cold recovery never has Sign/PUT credentials. */
@Suppress("TooManyFunctions", "LargeClass")
internal class CatalogSignerRotationActivationV1 private constructor(
    internal val process: VersionBoundComplaintProcessConfiguration,
    internal val budget: PersistenceTimeBudget,
    signingHttpFactory: (() -> SdkHttpClient)?,
    putHttpFactory: (() -> SdkHttpClient)?,
    readbackHttpFactory: (() -> SdkHttpClient)?,
    private val clock: Clock,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val coordinator = process.pools.catalogCoordinator
    private val ownership = coordinator.ownership
    private val manager = coordinator.manager
    private val source = coordinator.dataSource
    private val desired = process.desiredSettings()
    private val openings = process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }
        .openings().map { it.publicDriverProperties() }
    private val username = checkNotNull(openings.map { it["user"] }.distinct().single())
    private val database = checkNotNull(openings.map { it["PGDBNAME"] }.distinct().single())
    private val assembly = CatalogSignerRotationActivationAssemblyV1(this, signingHttpFactory, putHttpFactory, readbackHttpFactory, clock)
    private var stage = Stage.NEW
    private var mode = Mode.RECOVER
    private var entered = false
    private var failed = false
    private var reserved = false
    private var released = false
    private var closed = false
    private var cleanupProven = false
    private var closeFailure: CatalogSignerRotationFreezeExceptionV1? = null
    private var publishMode = false
    private var request: CatalogSignerRotationFreezeRequestV1? = null
    private var custody: CatalogSignerRotationReleaseCustodyV1? = null
    private var selectedInputs: CatalogSignerRotationActivationInputsV1? = null
    internal val inputs: CatalogSignerRotationActivationInputsV1 get() = checkNotNull(selectedInputs)
    private var release: CatalogSignerRotationActivationReleaseV1? = null
    private var snapshot: LocalCatalogSnapshot? = null
    private var mutation: CatalogFrozenMutation? = null
    private var readback: CatalogDualLocationVerifier.Activation3Readback? = null
    private var pendingLeaseOperationToken: UUID? = null
    private var binding: CatalogCoordinatorLeaseBindingV1? = null
    private var campaign: CatalogCoordinatorLeaseCampaignV1? = null
    private var leaseReceipt: CatalogCoordinatorLeaseReceiptV1? = null
    private var dispatchWindow: CatalogCoordinatorLeaseCampaignV1.Window? = null
    private var leaseStartedAtNanos: Long? = null
    private var leaseBinding: Array<Any?>? = null
    private var acquireIssued = false
    private var initialReadIssued = false
    private var recheckIssued = false
    private var recheckCount = 0
    private var prepareIssued = false
    private var prepareArmed = false
    private var preparedMarkerWritten = false
    private var preparedOperation: CatalogSignerRotationActivationOperationV1? = null
    private var continuationConsumed = false
    private var continuationPrior: CatalogSignerRotationActivationV1? = null
    private var signArmIssued = false
    private var signArmed = false
    private var signConstructionIssued = false
    private var signatureSqlIssued = false
    private var signatureSqlArmed = false
    private var signatureAfter: CatalogFrozenMutation? = null
    private var signatureOperation: CatalogSignerRotationActivationOperationV1? = null
    private var completeIssued = false
    private var completeArmed = false
    private var projectIssued = false
    private var projectArmed = false
    private var publicationArmedHere = false
    private var putClaimed = false
    private var putConstructionIssued = false
    private var acknowledgement: CatalogPrimaryPutAcknowledgementV1? = null
    private var retainedHistory: CatalogSignerRotationActivationObservationV1? = null
    private var reconciliationOperation: CatalogSignerRotationActivationOperationV1? = null
    private var completedHistory: CatalogSignerRotationActivationObservationV1? = null
    private var projectedHistory: CatalogSignerRotationActivationObservationV1? = null
    private var completeOperation: CatalogSignerRotationActivationOperationV1? = null
    private var projectOperation: CatalogSignerRotationActivationOperationV1? = null
    private var pending: Pending? = null
    private var allowedResult: CatalogSignerRotationActivationStateV1? = null
    private var lastWall: Instant? = null
    private var selectedPath: PersistencePhasePath? = null
    private var selectedKind: CatalogSignerRotationActivationKindV1? = null
    private var inputConstructionClaimed = false
    private var activeInput: CatalogSignerRotationActivationInputV1? = null
    private var phaseEntered = false
    private var phaseRetainedForEntry = false
    private var originalPhase: PersistencePhaseContext? = null
    private var sqlCleanupUnproven = false
    private var outcomeUncertain = false
    private val originalSignal = AtomicReference<Throwable?>()

    init { requireConnectionFree(); requireProcess() }

    fun activate(request: CatalogSignerRotationFreezeRequestV1, newSigningCredentials: AwsSessionCredentials,
        primaryPutCredentials: AwsSessionCredentials, primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials): CatalogSignerRotationActivationResultV1 =
        run(Mode.INITIAL, request, newSigningCredentials, primaryPutCredentials, primaryReadCredentials, replicaReadCredentials, null)

    fun recover(request: CatalogSignerRotationFreezeRequestV1, primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials): CatalogSignerRotationActivationResultV1 =
        run(Mode.RECOVER, request, null, null, primaryReadCredentials, replicaReadCredentials, null)

    /** A new explicit allowance, never revival of the actual closed previous owner/campaign/slot. */
    fun continueUnattempted(request: CatalogSignerRotationFreezeRequestV1, previousInvocation: CatalogSignerRotationActivationV1,
        newSigningCredentials: AwsSessionCredentials, primaryPutCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials, replicaReadCredentials: AwsSessionCredentials): CatalogSignerRotationActivationResultV1 =
        run(Mode.CONTINUE, request, newSigningCredentials, primaryPutCredentials, primaryReadCredentials, replicaReadCredentials, previousInvocation)

    @Suppress("TooGenericExceptionCaught")
    private fun run(selectedMode: Mode, request: CatalogSignerRotationFreezeRequestV1, signCredentials: AwsSessionCredentials?,
        putCredentials: AwsSessionCredentials?, primary: AwsSessionCredentials, replica: AwsSessionCredentials,
        previous: CatalogSignerRotationActivationV1?): CatalogSignerRotationActivationResultV1 {
        requireSignerRotation(caller === Thread.currentThread(), CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        var state: CatalogSignerRotationActivationStateV1? = null
        var pendingResult: CatalogSignerRotationActivationResultV1? = null
        var failure: Throwable? = null
        try {
            requireConnectionFree(); requireRunning()
            requireSignerRotation(!entered && stage === Stage.NEW)
            entered = true; mode = selectedMode; publishMode = mode !== Mode.RECOVER; this.request = request
            if (mode === Mode.CONTINUE) {
                checkNotNull(previous).consumeContinuation(this, request) // Before any new custody/lease/provider attempt; never restored.
                continuationPrior = previous
            }
            coordinator.catalogRefreshCustody.reserveSignerRotationActivation(this); reserved = true
            stage = Stage.INPUTS
            selectedInputs = assembly.acquire(request)
            previous?.inputs?.requireSame(inputs)
            if (mode !== Mode.INITIAL) openExistingCustody(request)
            stage = Stage.SNAPSHOT
            val local = loadSnapshot(); requireSnapshotTuple(local); snapshot = local
            mutation = when (local) {
                is LocalCatalogSnapshot.Prepared -> local.mutation
                is LocalCatalogSnapshot.ProjectionPending -> checkNotNull(release).returnedMutation()
                is LocalCatalogSnapshot.Accepted -> if (local.head.generation == 3L) checkNotNull(release).returnedMutation() else null
                else -> null
            }
            if (local is LocalCatalogSnapshot.ProjectionPending) pendingLeaseOperationToken = UUID.fromString(local.projection.operationToken)
            release?.requireSnapshot(local)
            stage = Stage.READBACK; observeReadback(primary, replica)
            stage = Stage.ACQUIRE
            binding = CatalogCoordinatorLeaseBindingV1.fromActivation(this, process, checkNotNull(readback))
            acquireLease(checkNotNull(binding))
            stage = Stage.HISTORY; initialReadIssued = true
            retainedHistory = executeActivation(historyKind(initial = true)).observation.also(::requireSnapshotHistory)
            recheck(primary, replica)
            if (mode === Mode.INITIAL) prepare()
            if (mode === Mode.CONTINUE) {
                requireSignerRotation(snapshot is LocalCatalogSnapshot.Prepared && mutation?.signedEnvelopeBytes == null)
                checkNotNull(previous?.preparedOperation).observation.requireSame(checkNotNull(retainedHistory))
                checkNotNull(release).requireUnattemptedSignature(checkNotNull(retainedHistory))
            }
            state = when (snapshot) {
                is LocalCatalogSnapshot.Prepared -> {
                    if (mutation?.signedEnvelopeBytes == null) {
                        if (mode === Mode.RECOVER) recoverSignature()
                        else signPrepared(checkNotNull(signCredentials), primary, replica)
                    } else if (mode === Mode.RECOVER) {
                        // This marker attests identical stored bytes, not an earlier transaction/acquisition's outcome.
                        checkNotNull(release).signaturePersisted(checkNotNull(retainedHistory))
                    }
                    if (mutation?.signedEnvelopeBytes == null) CatalogSignerRotationActivationStateV1.PREPARED_UNSIGNED
                    else {
                        if (mode !== Mode.RECOVER) recheck(primary, replica)
                        if (checkNotNull(readback).state === CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_UNPUBLISHED &&
                            putCredentials != null) publishUnpublished(putCredentials, primary, replica)
                        finishObservedDelivery()
                    }
                }
                is LocalCatalogSnapshot.ProjectionPending -> {
                    completedHistory = checkNotNull(retainedHistory)
                    retainPending(checkNotNull(reconciliationOperation), checkNotNull(retainedHistory))
                    CatalogSignerRotationActivationStateV1.PROJECTION_PENDING
                }
                is LocalCatalogSnapshot.Accepted -> CatalogSignerRotationActivationStateV1.PROJECTED
                else -> throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
            }
            if (state === CatalogSignerRotationActivationStateV1.PROJECTION_PENDING) {
                allowedResult = state
                pendingResult = CatalogSignerRotationActivationResultV1.issuedBy(this, state)
            }
        } catch (problem: Throwable) {
            observeFailure(problem); failure = signerRotationSignal(problem); abort()
        } finally {
            if (pendingResult == null || failure != null) {
                try { close() } catch (cleanup: Throwable) { failure = preferSignerRotationCleanup(failure, cleanup) }
            }
        }
        failure?.let { throw boundedSignerRotationFailure(it) }
        pendingResult?.let { return it }
        allowedResult = checkNotNull(state)
        return CatalogSignerRotationActivationResultV1.issuedBy(this, checkNotNull(state))
    }

    private fun consumeContinuation(next: CatalogSignerRotationActivationV1, selected: CatalogSignerRotationFreezeRequestV1) {
        requireConnectionFree()
        requireSignerRotation(caller === Thread.currentThread() && process === next.process && next !== this && entered && closed &&
            cleanupProven && released && closeFailure == null && !sqlCleanupUnproven && !outcomeUncertain &&
            mode === Mode.INITIAL && preparedMarkerWritten && preparedOperation != null && !signArmIssued && !signArmed &&
            !signConstructionIssued && signatureAfter == null && !signatureSqlIssued && !publicationArmedHere && !continuationConsumed,
            CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        val old = checkNotNull(request)
        requireSignerRotation(old.releaseRoot == selected.releaseRoot && old.approvedIntent == selected.approvedIntent && old.approvalInputs == selected.approvalInputs)
        val actual = checkNotNull(preparedOperation).observation
        requireSignerRotation(actual.mutation?.signedEnvelopeBytes == null && actual.completedAt == null)
        actual.requireSame(checkNotNull(retainedHistory))
        continuationConsumed = true // Historical proof is not rechecked against the previous owner's now-spent deadline.
    }

    private fun openExistingCustody(request: CatalogSignerRotationFreezeRequestV1) {
        val held = CatalogSignerRotationReleaseCustodyV1.retainExistingActivation(request.releaseRoot, budget)
        custody = held
        val allocation = held.discoverExisting()
        val bound = held.read(CatalogSignerRotationReleaseLeafV1.BINDING)
            ?: throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        release = CatalogSignerRotationActivationReleaseV1(this, inputs, held, allocation, bound, false)
    }

    private fun prepare() {
        requireSignerRotation(snapshot is LocalCatalogSnapshot.Accepted && mutation == null && mode === Mode.INITIAL)
        stage = Stage.ALLOCATE
        val acquired = currentAcquisitionRecordValues().toList()
        val bound = signerRotationRecord("binding", *(acquired.take(9) +
            listOf(HexFormat.of().formatHex(capacityDigest()), acquired[9], acquired[10])).toTypedArray())
        val allocation = inputs.allocation(bound)
        val held = CatalogSignerRotationReleaseCustodyV1.retainActivation(checkNotNull(request).releaseRoot, allocation, budget)
        custody = held
        requireSignerRotation(held.open() === CatalogSignerRotationCustodyObservationV1.CREATED)
        release = CatalogSignerRotationActivationReleaseV1(this, inputs, held, allocation, bound, true)
        stage = Stage.ARM_PREPARE; prepareIssued = true
        checkNotNull(release).armPrepare(); prepareArmed = true
        stage = Stage.PREPARE
        val operation = executeActivation(CatalogSignerRotationActivationKindV1.PREPARE, inputs.unsigned())
        preparedOperation = operation // Genuine COMMITTED+released operation retained before durable outcome or further provider construction.
        retainedHistory = operation.observation
        acceptPreparedHistory(checkNotNull(retainedHistory))
        checkNotNull(release).prepared(checkNotNull(retainedHistory)); preparedMarkerWritten = true
    }

    private fun signPrepared(credentials: AwsSessionCredentials, primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        if (mode === Mode.INITIAL) recheck(primary, replica)
        requireSignerRotation(reconciliationOperation?.input?.kind === CatalogSignerRotationActivationKindV1.PREPARED_RECHECK)
        stage = Stage.ARM_SIGN; signArmIssued = true
        checkNotNull(release).armSignature(checkNotNull(retainedHistory)); signArmed = true
        stage = Stage.SIGN
        val signature = assembly.sign(credentials)
        signatureAfter = checkNotNull(release).preserveSignature(signature)
        inputs.requireSignedChain(checkNotNull(readback), checkNotNull(signatureAfter))
        persistSignature()
    }

    private fun recoverSignature() {
        requireSignerRotation(mode === Mode.RECOVER)
        val returned = checkNotNull(release).returnedMutation() ?: return // Cold absence never grants a new Sign.
        signatureAfter = returned
        inputs.requireSignedChain(checkNotNull(readback), returned)
        persistSignature()
    }

    private fun persistSignature() {
        stage = Stage.ARM_SIGNATURE; signatureSqlIssued = true
        checkNotNull(release).armSignaturePersistence(checkNotNull(signatureAfter)); signatureSqlArmed = true
        stage = Stage.SIGNATURE
        val operation = executeActivation(CatalogSignerRotationActivationKindV1.SIGNATURE, checkNotNull(signatureAfter))
        signatureOperation = operation; retainedHistory = operation.observation
        acceptPreparedHistory(checkNotNull(retainedHistory))
        checkNotNull(release).signaturePersisted(checkNotNull(retainedHistory))
    }

    private fun acceptPreparedHistory(value: CatalogSignerRotationActivationObservationV1) {
        requireSignerRotation(value.completedAt == null && value.projectedAt == null)
        val actual = checkNotNull(value.mutation); inputs.requireMutation(actual)
        mutation = actual
        snapshot = LocalCatalogSnapshot.Prepared(CatalogLocalHead(2L, inputs.manifest.previousEnvelopeSha256), actual)
    }

    private fun recheck(primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        requireSignerRotation(recheckCount < 3)
        recheckCount++; stage = Stage.READBACK; observeReadback(primary, replica)
        stage = Stage.RECHECK; recheckIssued = true
        val before = checkNotNull(retainedHistory)
        val operation = executeActivation(historyKind(initial = false))
        reconciliationOperation = operation
        val checked = operation.observation
        before.requireSame(checked); requireSnapshotHistory(checked); retainedHistory = checked
    }

    private fun historyKind(initial: Boolean): CatalogSignerRotationActivationKindV1 = when (val local = snapshot) {
        is LocalCatalogSnapshot.Prepared -> if (initial) CatalogSignerRotationActivationKindV1.INITIAL_PREPARED_READ else CatalogSignerRotationActivationKindV1.PREPARED_RECHECK
        is LocalCatalogSnapshot.ProjectionPending -> if (initial) CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ else CatalogSignerRotationActivationKindV1.PENDING_RECHECK
        is LocalCatalogSnapshot.Accepted -> if (local.head.generation == 2L) {
            if (initial) CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ else CatalogSignerRotationActivationKindV1.HEAD_RECHECK
        } else {
            if (initial) CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ else CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK
        }
        else -> throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
    }

    private fun observeReadback(primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        val local = checkNotNull(snapshot)
        val proof = assembly.observe(local, primary, replica)
        readback = proof
        proof.requireSnapshot(local); assembly.requireProof(proof); inputs.requirePrefix(proof)
        if (local is LocalCatalogSnapshot.Accepted && local.head.generation == 3L) requireSignerRotation(
            proof.observedEnvelopeBytes().contentEquals(checkNotNull(mutation?.signedEnvelopeBytes)))
        release?.requireHistoricalReadback(proof)
    }

    private fun publishUnpublished(credentials: AwsSessionCredentials, primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        requireSignerRotation(mode !== Mode.RECOVER && !checkNotNull(release).isArmed(), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        stage = Stage.ARM_PUBLICATION; checkNotNull(release).armPublication(); publicationArmedHere = true
        stage = Stage.PUBLISH; checkNotNull(release).claimPut()
        val value = assembly.put(credentials); acknowledgement = value; checkNotNull(release).acknowledged(value)
        stage = Stage.READBACK; observeReadback(primary, replica)
    }

    private fun finishObservedDelivery(): CatalogSignerRotationActivationStateV1 {
        val proof = checkNotNull(readback)
        val retained = checkNotNull(release)
        if (proof.state === CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_UNSIGNED ||
            proof.state === CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_UNPUBLISHED) {
            requireSignerRotation(mode === Mode.RECOVER)
            return CatalogSignerRotationActivationStateV1.SIGNED_UNPUBLISHED
        }
        requireSignerRotation(retained.isArmed(), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        stage = Stage.EVIDENCE
        return when (proof.state) {
            CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_AWAIT_REPLICATION -> {
                retained.awaitReplication(proof); CatalogSignerRotationActivationStateV1.AWAIT_REPLICATION
            }
            CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_DUAL_COPY -> {
                retained.preserveDual(proof); stage = Stage.ARM_COMPLETE; completeIssued = true
                retained.armComplete(); completeArmed = true; stage = Stage.COMPLETE
                val operation = executeActivation(CatalogSignerRotationActivationKindV1.COMPLETE)
                completeOperation = operation; completedHistory = operation.observation
                retained.completed(checkNotNull(completedHistory)); retainPending(operation, checkNotNull(completedHistory))
                CatalogSignerRotationActivationStateV1.PROJECTION_PENDING
            }
            else -> throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun project(): CatalogSignerRotationActivationResultV1 {
        requireSignerRotation(caller === Thread.currentThread(), CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        var success = false
        var failure: Throwable? = null
        try {
            requireConnectionFree(); requirePending()
            requireSignerRotation(stage === Stage.PENDING && !projectIssued)
            projectIssued = true; val held = checkNotNull(pending); held.spent = true; allowedResult = null
            stage = Stage.ARM_PROJECT; checkNotNull(release).armProject(held.observation); projectArmed = true
            stage = Stage.PROJECT
            val operation = executeActivation(CatalogSignerRotationActivationKindV1.PROJECT)
            projectOperation = operation; projectedHistory = operation.observation
            checkNotNull(release).projected(checkNotNull(projectedHistory)); requireRunning(); success = true
        } catch (problem: Throwable) {
            observeFailure(problem); failure = signerRotationSignal(problem); abort()
        } finally {
            try { close() } catch (cleanup: Throwable) { failure = preferSignerRotationCleanup(failure, cleanup) }
        }
        failure?.let { throw boundedSignerRotationFailure(it) }
        requireSignerRotation(success)
        allowedResult = CatalogSignerRotationActivationStateV1.PROJECTED
        return CatalogSignerRotationActivationResultV1.issuedBy(this, CatalogSignerRotationActivationStateV1.PROJECTED)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadSnapshot(): LocalCatalogSnapshot {
        selectPhase(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT)
        return try { coordinator.snapshot.loadSignerRotationActivation(this, inputs.reader.policyAt(sampleWallTime())) }
        catch (problem: Throwable) { phaseFailed(problem) } finally { selectedPath = null }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun acquireLease(selected: CatalogCoordinatorLeaseBindingV1) {
        requireSignerRotation(!acquireIssued); acquireIssued = true
        selectPhase(PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE)
        try {
            val actual = coordinator.lease.acquireActivation(this, selected)
            campaign = actual.campaign; leaseReceipt = actual.receipt
            dispatchWindow = actual.campaign.requireLocalWindow(); leaseStartedAtNanos = checkNotNull(dispatchWindow).startedAtNanos
            requireRunning()
            requireSignerRotation(actual.receipt.transition === CatalogCoordinatorLeaseTransitionV1.ACQUIRED &&
                actual.receipt.owner !in (release?.historicalOwners() ?: emptySet()) && actual.receipt.token > (release?.historicalLeaseFloor() ?: 0L) &&
                actual.campaign.owner == actual.receipt.owner && actual.campaign.token == actual.receipt.token &&
                actual.campaign.binding === selected && actual.receipt.expiresAt != null, CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
            leaseBinding = arrayOf(*selected.arguments(), actual.receipt.owner, actual.receipt.token, Timestamp.from(checkNotNull(actual.receipt.expiresAt)))
            requireActualLease()
        } catch (problem: Throwable) { campaign?.close(); phaseFailed(problem) } finally { selectedPath = null }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeActivation(kind: CatalogSignerRotationActivationKindV1, after: CatalogFrozenMutation? = null): CatalogSignerRotationActivationOperationV1 {
        if (kind === CatalogSignerRotationActivationKindV1.PROJECT) requirePendingIdentity() else requireActualLease()
        selectPhase(kind.path); selectedKind = kind; inputConstructionClaimed = false
        return try {
            val expected = if (kind in INITIAL_KINDS) null else if (kind === CatalogSignerRotationActivationKindV1.PROJECT) checkNotNull(pending).observation else retainedHistory
            val input = CatalogSignerRotationActivationInputV1.create(this, kind, expected, after); activeInput = input
            coordinator.signerRotationActivation.execute(input)
        } catch (problem: Throwable) { phaseFailed(problem) } finally { activeInput = null; selectedKind = null; selectedPath = null }
    }

    private fun retainPending(operation: CatalogSignerRotationActivationOperationV1, observation: CatalogSignerRotationActivationObservationV1) {
        requireConnectionFree(); requireSqlCleanup(); requireRunning()
        val completion = stage === Stage.COMPLETE && completeOperation === operation && operation.input.kind === CatalogSignerRotationActivationKindV1.COMPLETE
        val reconciled = stage === Stage.RECHECK && mode === Mode.RECOVER && reconciliationOperation === operation &&
            operation.input.kind === CatalogSignerRotationActivationKindV1.PENDING_RECHECK && observation === retainedHistory
        requireSignerRotation((completion || reconciled) && pending == null && operation.input.original === this && operation.observation === observation &&
            completedHistory === observation && observation.completedAt != null && observation.projectedAt == null)
        val actual = checkNotNull(campaign); val started = actual.requireLocalWindow().startedAtNanos
        requireSignerRotation(started == leaseStartedAtNanos); actual.close()
        pending = Pending(operation, operation.input, observation, budget, started); stage = Stage.PENDING; requirePending()
    }

    private fun requirePending() {
        requireConnectionFree(); requireRunning(); requireSqlCleanup(); requirePendingIdentity()
        val held = checkNotNull(pending)
        requireSignerRotation(!held.spent && !projectIssued && held.operation.observation === held.observation)
        assembly.requireProviderCleanup()
    }

    private fun requirePendingIdentity() {
        requireRunning(); val held = checkNotNull(pending)
        val operation = when (held.input.kind) {
            CatalogSignerRotationActivationKindV1.COMPLETE -> completeOperation
            CatalogSignerRotationActivationKindV1.PENDING_RECHECK -> reconciliationOperation
            else -> null
        }
        requireSignerRotation(held.allowance === budget && held.startedAtNanos == leaseStartedAtNanos && held.operation === operation &&
            held.operation.input === held.input && held.input.original === this && held.observation === completedHistory)
        requireOriginalDeadline(held.startedAtNanos)
    }

    private fun requireSnapshotTuple(local: LocalCatalogSnapshot) {
        if (mode === Mode.INITIAL) requireSignerRotation(local is LocalCatalogSnapshot.Accepted && local.head.generation == 2L &&
            local.head.envelopeSha256 == inputs.manifest.previousEnvelopeSha256, CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        else when (local) {
            is LocalCatalogSnapshot.Prepared -> {
                requireSignerRotation(local.head.generation == 2L && local.head.envelopeSha256 == inputs.manifest.previousEnvelopeSha256)
                inputs.requireMutation(local.mutation)
                local.mutation.signedEnvelopeBytes?.let { requireSignerRotation(it.contentEquals(checkNotNull(release).returnedMutation()?.signedEnvelopeBytes)) }
            }
            is LocalCatalogSnapshot.ProjectionPending -> requireSignerRotation(local.head.generation == 3L &&
                local.projection.operationToken == inputs.manifest.operationToken && local.head.envelopeSha256 == checkNotNull(release).expectedFrozenMutation().signedEnvelopeSha256 &&
                local.projection.signedEnvelopeBytes.contentEquals(checkNotNull(release).envelopeBytes()))
            is LocalCatalogSnapshot.Accepted -> requireSignerRotation(local.head.generation == 3L &&
                local.head.envelopeSha256 == checkNotNull(release).expectedFrozenMutation().signedEnvelopeSha256)
            else -> throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        }
        if (mode === Mode.CONTINUE) requireSignerRotation(local is LocalCatalogSnapshot.Prepared && local.mutation.signedEnvelopeBytes == null)
    }

    private fun requireSnapshotHistory(value: CatalogSignerRotationActivationObservationV1) {
        requireConnectionFree(); requireRunning()
        val proof = checkNotNull(readback); proof.requireSnapshot(checkNotNull(snapshot)); assembly.requireProof(proof)
        requireSignerRotation(value.genesis.signedEnvelopeBytes.contentEquals(proof.genesisBytes()) && value.overlap.signedEnvelopeBytes.contentEquals(proof.overlapBytes()))
        requireSignerRotation(sameArguments(value.overlapCopyArguments(), CatalogSignerRotationActivationInputV1.overlapCopies(proof)))
        val expectedState = when (val local = snapshot) {
            is LocalCatalogSnapshot.Prepared -> value.mutation != null && value.completedAt == null && value.projectedAt == null
            is LocalCatalogSnapshot.ProjectionPending -> value.completedAt != null && value.projectedAt == null
            is LocalCatalogSnapshot.Accepted -> if (local.head.generation == 2L) value.mutation == null else value.completedAt != null && value.projectedAt != null
            else -> false
        }
        requireSignerRotation(expectedState)
        value.mutation?.let { inputs.requireMutation(it); requireSignerRotation(sameSignerRotationMutation(it, checkNotNull(mutation))) }
        if (value.completedAt != null) requireSignerRotation(sameArguments(value.copyArguments(), CatalogSignerRotationActivationInputV1.copies(
            proof.objectVersion, proof.retainUntilEpochSecond, checkNotNull(proof.primaryEvidenceBytes()), checkNotNull(proof.replicaEvidenceBytes()))))
        if (value.mutation != null) release?.requireObservedHistory(value)
    }

    internal fun frozenMutationOrNull(): CatalogFrozenMutation? { requireRunning(); return mutation }
    internal fun frozenMutation(): CatalogFrozenMutation = checkNotNull(frozenMutationOrNull())
    internal fun isRecovery(): Boolean { requireRunning(); return mode === Mode.RECOVER }
    internal fun requireInputAcquisition(selected: CatalogSignerRotationFreezeRequestV1) {
        requireRunning(); requireSignerRotation(stage === Stage.INPUTS && request === selected && selectedInputs == null)
    }
    internal fun requireObservedSnapshot(selected: LocalCatalogSnapshot) {
        requireProviderRunning(); requireSignerRotation(stage === Stage.READBACK && snapshot === selected)
    }
    internal fun requireBindingInputs(candidate: VersionBoundComplaintProcessConfiguration, raw: CatalogDualLocationVerifier.Activation3Readback) {
        requireConnectionFree(); requireRunning(); requireSqlCleanup()
        requireSignerRotation(stage === Stage.ACQUIRE && !acquireIssued && binding == null && candidate === process && raw === readback)
        assembly.requireProof(raw); raw.requireSnapshot(checkNotNull(snapshot)); inputs.requirePrefix(raw)
    }

    internal fun requireSnapshotSelection(candidate: PersistencePhaseOwnership) {
        requireRunning()
        requireSignerRotation(
            candidate === ownership && stage === Stage.SNAPSHOT && selectedPath === PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requireLeaseSelection(candidate: PersistencePhaseOwnership, jdbc: JdbcTemplate, selected: CatalogCoordinatorLeaseBindingV1) {
        requireConnectionFree()
        requireLeaseAttempt(selected)
        requireSignerRotation(candidate === ownership && jdbc.dataSource === source, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        selected.requirePersistence(candidate, jdbc)
    }

    internal fun requireLeaseAttempt(selected: CatalogCoordinatorLeaseBindingV1) {
        requireRunning()
        requireSignerRotation(
            stage === Stage.ACQUIRE && acquireIssued && binding === selected && selectedPath === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        selected.requireActivationPurpose(this)
    }

    /** The pending selector comes only from this owner's actual released Pending3 snapshot. */
    internal fun pendingLeaseOperation(selected: CatalogCoordinatorLeaseBindingV1): UUID? {
        requireLeaseAttempt(selected)
        if (snapshot !is LocalCatalogSnapshot.ProjectionPending) {
            requireSignerRotation(pendingLeaseOperationToken == null, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
            return null
        }
        requireSignerRotation(mode === Mode.RECOVER, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        return checkNotNull(pendingLeaseOperationToken)
    }

    internal fun requireHistoricalLeaseFloor(selected: CatalogCoordinatorLeaseBindingV1, lockedToken: Long) {
        requireLeaseAttempt(selected)
        requireSignerRotation(lockedToken >= (release?.historicalLeaseFloor() ?: 0L), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
    }

    /** The existing LIVE control lock must remain held; no reopening of either administrative gate. */
    internal fun requireClosedLeaseControl(selected: CatalogCoordinatorLeaseBindingV1, jdbc: JdbcTemplate) {
        requireLeaseAttempt(selected)
        requireSignerRotation(
            jdbc.dataSource === source && originalPhase === PersistencePhaseOwnership.current(),
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        val gatesClosed = jdbc.query(
            "SELECT (maintenance_closed AND creation_closed) IS TRUE AS gates_closed FROM complaint_journal_control " +
                "WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("gates_closed") && !rows.wasNull() && !rows.next() },
        )
        requireSignerRotation(gatesClosed == true, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        requireLeaseAttempt(selected)
    }

    internal fun requireInputConstruction(
        kind: CatalogSignerRotationActivationKindV1,
        expected: CatalogSignerRotationActivationObservationV1?,
        after: CatalogFrozenMutation?,
    ) {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireSignerRotation(selectedKind === kind && !inputConstructionClaimed && activeInput == null, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        val allowed = when (kind) {
            CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PREPARED_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
            -> stage === Stage.HISTORY && initialReadIssued && expected == null && after == null && kind === historyKind(initial = true)

            CatalogSignerRotationActivationKindV1.HEAD_RECHECK,
            CatalogSignerRotationActivationKindV1.PREPARED_RECHECK,
            CatalogSignerRotationActivationKindV1.PENDING_RECHECK,
            CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK,
            -> stage === Stage.RECHECK && recheckIssued && expected === retainedHistory && after == null && kind === historyKind(initial = false)

            CatalogSignerRotationActivationKindV1.PREPARE -> {
                requireSignerRotation(after != null && sameSignerRotationMutation(after, inputs.unsigned()))
                stage === Stage.PREPARE && mode === Mode.INITIAL && prepareIssued && prepareArmed && expected === retainedHistory &&
                    expected?.mutation == null && preparedOperation == null
            }

            CatalogSignerRotationActivationKindV1.SIGNATURE ->
                stage === Stage.SIGNATURE && signatureSqlIssued && signatureSqlArmed && expected === retainedHistory &&
                    after === signatureAfter && after != null && signatureOperation == null

            CatalogSignerRotationActivationKindV1.COMPLETE ->
                stage === Stage.COMPLETE && completeIssued && completeArmed && expected === retainedHistory && after == null

            CatalogSignerRotationActivationKindV1.PROJECT -> {
                requirePendingIdentity()
                stage === Stage.PROJECT && projectIssued && projectArmed && checkNotNull(pending).spent && expected === pending?.observation && after == null
            }
        }
        requireSignerRotation(allowed, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        inputConstructionClaimed = true
    }

    internal fun readbackForInput(): CatalogDualLocationVerifier.Activation3Readback {
        requireConnectionFree()
        requireRunning()
        requireSignerRotation(selectedKind != null && inputConstructionClaimed && activeInput == null, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        val proof = checkNotNull(readback)
        proof.requireSnapshot(checkNotNull(snapshot))
        assembly.requireProof(proof)
        return proof
    }

    internal fun activationBindingArguments(): Array<Any?> {
        requireConnectionFree()
        requireRunning()
        requireSignerRotation(selectedKind != null && inputConstructionClaimed && activeInput == null, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        return checkNotNull(leaseBinding).map {
            when (it) {
                is ByteArray -> it.copyOf()
                is Timestamp -> Timestamp.from(it.toInstant())
                else -> it
            }
        }.toTypedArray()
    }

    internal fun currentAcquisitionRecordValues(): Array<String> {
        requireConnectionFree()
        requireRunning()
        val receipt = checkNotNull(leaseReceipt)
        val fields = checkNotNull(leaseBinding).take(9).map { if (it is ByteArray) HexFormat.of().formatHex(it) else it.toString() }
        return (fields + listOf(receipt.owner.toString(), receipt.token.toString(), checkNotNull(receipt.expiresAt).toString())).toTypedArray()
    }

    internal fun requireActivationPersistence(input: CatalogSignerRotationActivationInputV1, candidate: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning()
        requireSignerRotation(
            activeInput === input && input.original === this && input.kind === selectedKind && selectedPath === input.path &&
                candidate === ownership && jdbc.dataSource === source,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        if (input.kind === CatalogSignerRotationActivationKindV1.PROJECT) requirePendingIdentity()
        checkNotNull(binding).requireActivationPurpose(this)
        checkNotNull(binding).requirePersistence(candidate, jdbc) // Identity only. Actual B2/B3, lease and full33 are locked fixed SQL observations.
    }

    internal fun capacityDigest(): ByteArray {
        requireConnectionFree()
        requireRunning()
        return process.consumers.capacityPolicy.digestBytes()
    }

    internal fun requirePrepareArm(selected: CatalogSignerRotationActivationReleaseV1) {
        requireConnectionFree()
        requireActualLease()
        requireSqlCleanup()
        requireSignerRotation(
            stage === Stage.ARM_PREPARE && release === selected && mode === Mode.INITIAL && prepareIssued && !prepareArmed &&
                preparedOperation == null && mutation == null && retainedHistory?.mutation == null &&
                reconciliationOperation?.input?.kind === CatalogSignerRotationActivationKindV1.HEAD_RECHECK &&
                reconciliationOperation?.observation === retainedHistory && readback?.state === CatalogDualLocationVerifier.Activation3Readback.State.HEAD2,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        assembly.requireProof(checkNotNull(readback))
    }

    internal fun requirePrepared(selected: CatalogSignerRotationActivationReleaseV1, value: CatalogSignerRotationActivationObservationV1) {
        requireConnectionFree()
        requireActualLease()
        requireSqlCleanup()
        requireSignerRotation(
            stage === Stage.PREPARE && release === selected && prepareArmed && !preparedMarkerWritten &&
                preparedOperation?.input?.original === this && preparedOperation?.input?.kind === CatalogSignerRotationActivationKindV1.PREPARE &&
                preparedOperation?.observation === value && retainedHistory === value && value.mutation?.signedEnvelopeBytes == null &&
                value.completedAt == null && value.projectedAt == null,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requireSignArm(selected: CatalogSignerRotationActivationReleaseV1, value: CatalogSignerRotationActivationObservationV1) {
        requireConnectionFree()
        requireActualLease()
        requireSqlCleanup()
        requireSignerRotation(
            stage === Stage.ARM_SIGN && release === selected && publishMode && mode !== Mode.RECOVER && signArmIssued && !signArmed &&
                !signConstructionIssued && signatureAfter == null && !signatureSqlIssued && retainedHistory === value &&
                value.mutation?.signedEnvelopeBytes == null && value.completedAt == null && value.projectedAt == null &&
                reconciliationOperation?.input?.kind === CatalogSignerRotationActivationKindV1.PREPARED_RECHECK &&
                reconciliationOperation?.observation === value && readback?.state === CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_UNSIGNED,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        val predecessor = when (mode) {
            Mode.INITIAL -> {
                requireSignerRotation(preparedMarkerWritten)
                checkNotNull(preparedOperation)
            }
            Mode.CONTINUE -> {
                val previous = checkNotNull(continuationPrior)
                requireSignerRotation(previous.continuationConsumed && previous.process === process)
                checkNotNull(previous.preparedOperation)
            }
            Mode.RECOVER -> throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        }
        predecessor.observation.requireSame(value)
        assembly.requireProof(checkNotNull(readback))
    }

    internal fun requireSignConstruction() {
        requireProviderRunning()
        requireSignerRotation(
            stage === Stage.SIGN && mode !== Mode.RECOVER && signArmIssued && signArmed && !signConstructionIssued && signatureAfter == null,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        signConstructionIssued = true // Before construction or provider callbacks. No new call can be inferred from absent signature bytes.
    }

    internal fun requireSignatureReturn(selected: CatalogSignerRotationActivationReleaseV1) {
        requireConnectionFree()
        requireActualLease()
        requireSqlCleanup()
        requireSignerRotation(stage === Stage.SIGN && release === selected && signConstructionIssued && signArmed && signatureAfter == null)
        assembly.requireProviderCleanup()
    }

    internal fun requireSignatureSqlArm(selected: CatalogSignerRotationActivationReleaseV1, value: CatalogFrozenMutation) {
        requireConnectionFree()
        requireActualLease()
        requireSqlCleanup()
        requireSignerRotation(
            stage === Stage.ARM_SIGNATURE && release === selected && signatureSqlIssued && !signatureSqlArmed && signatureAfter === value &&
                value.signedEnvelopeBytes != null && mutation?.signedEnvelopeBytes == null &&
                reconciliationOperation?.input?.kind === CatalogSignerRotationActivationKindV1.PREPARED_RECHECK &&
                reconciliationOperation?.observation === retainedHistory && retainedHistory?.completedAt == null,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        inputs.requireSignedChain(checkNotNull(readback), value)
        assembly.requireProviderCleanup()
    }

    /** A byte marker is not an acquisition-bound outcome. Never accept a caller-supplied mutation or an uncommitted read. */
    internal fun requireSignaturePersisted(selected: CatalogSignerRotationActivationReleaseV1, value: CatalogSignerRotationActivationObservationV1) {
        requireConnectionFree()
        requireActualLease()
        requireSqlCleanup()
        val persistedHere = stage === Stage.SIGNATURE && signatureSqlArmed && signatureOperation?.input?.original === this &&
            signatureOperation?.input?.kind === CatalogSignerRotationActivationKindV1.SIGNATURE && signatureOperation?.observation === value
        val alreadyStored = stage === Stage.RECHECK && mode === Mode.RECOVER && !signatureSqlIssued && !signConstructionIssued &&
            reconciliationOperation?.input?.original === this && reconciliationOperation?.input?.kind === CatalogSignerRotationActivationKindV1.PREPARED_RECHECK &&
            reconciliationOperation?.observation === value
        requireSignerRotation(
            release === selected && retainedHistory === value && (persistedHere || alreadyStored) &&
                value.mutation?.signedEnvelopeBytes != null && value.completedAt == null && value.projectedAt == null,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        inputs.requireMutation(checkNotNull(value.mutation))
    }

    internal fun requirePublicationArm(selected: CatalogSignerRotationActivationReleaseV1) {
        requireConnectionFree()
        requireActualLease()
        requireSqlCleanup()
        requireSignerRotation(
            stage === Stage.ARM_PUBLICATION && release === selected && publishMode && mode !== Mode.RECOVER && !publicationArmedHere && !putClaimed &&
                signatureOperation != null && mutation?.signedEnvelopeBytes != null && reconciliationOperation?.observation === retainedHistory &&
                reconciliationOperation?.input?.kind === CatalogSignerRotationActivationKindV1.PREPARED_RECHECK &&
                readback?.state === CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_UNPUBLISHED,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        checkNotNull(signatureOperation).observation.requireSame(checkNotNull(retainedHistory))
        assembly.requireProof(checkNotNull(readback))
    }

    internal fun requirePublicationClaim(selected: CatalogSignerRotationActivationReleaseV1) {
        requireActualLease()
        requireSqlCleanup()
        requireSignerRotation(
            stage === Stage.PUBLISH && release === selected && publishMode && mode !== Mode.RECOVER && publicationArmedHere && !putClaimed,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        putClaimed = true
    }

    internal fun requirePutConstruction() {
        requireProviderRunning()
        requireSignerRotation(
            stage === Stage.PUBLISH && publishMode && mode !== Mode.RECOVER && publicationArmedHere && putClaimed && !putConstructionIssued,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        putConstructionIssued = true
    }

    internal fun requireAcknowledgement(selected: CatalogSignerRotationActivationReleaseV1, value: CatalogPrimaryPutAcknowledgementV1) {
        requireRunning()
        requireSignerRotation(stage === Stage.PUBLISH && release === selected && value === acknowledgement && putConstructionIssued)
        assembly.requireAcknowledgement(value)
    }

    internal fun requireDeliveryProof(selected: CatalogSignerRotationActivationReleaseV1, proof: CatalogDualLocationVerifier.Activation3Readback) {
        requireConnectionFree()
        requireActualLease()
        requireSqlCleanup()
        requireSignerRotation(stage === Stage.EVIDENCE && release === selected && proof === readback && retainedHistory != null)
        proof.requireSnapshot(checkNotNull(snapshot))
        assembly.requireProof(proof)
    }

    internal fun requireCompletionArm(selected: CatalogSignerRotationActivationReleaseV1, proof: CatalogDualLocationVerifier.Activation3Readback) {
        requireConnectionFree()
        requireActualLease()
        requireSqlCleanup()
        requireSignerRotation(
            stage === Stage.ARM_COMPLETE && release === selected && completeIssued && !completeArmed && proof === readback &&
                proof.state === CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_DUAL_COPY &&
                reconciliationOperation?.input?.kind === CatalogSignerRotationActivationKindV1.PREPARED_RECHECK &&
                reconciliationOperation?.observation === retainedHistory && retainedHistory?.mutation?.signedEnvelopeBytes != null && retainedHistory?.completedAt == null,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        assembly.requireProof(proof)
    }

    internal fun requireCompletedDelivery(selected: CatalogSignerRotationActivationReleaseV1, value: CatalogSignerRotationActivationObservationV1) {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireSignerRotation(
            stage === Stage.COMPLETE && release === selected && completeArmed && completedHistory === value && completeOperation?.observation === value,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requireProjectionArm(selected: CatalogSignerRotationActivationReleaseV1, value: CatalogSignerRotationActivationObservationV1) {
        requireConnectionFree()
        requirePendingIdentity()
        requireSqlCleanup()
        requireSignerRotation(stage === Stage.ARM_PROJECT && release === selected && projectIssued && !projectArmed && pending?.observation === value)
    }

    internal fun requireProjectedDelivery(selected: CatalogSignerRotationActivationReleaseV1, value: CatalogSignerRotationActivationObservationV1) {
        requireConnectionFree()
        requirePendingIdentity()
        requireSqlCleanup()
        requireSignerRotation(
            stage === Stage.PROJECT && release === selected && projectArmed && projectedHistory === value && projectOperation?.observation === value,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requireProviderRunning() {
        requireConnectionFree()
        requireRunning()
        requireSignerRotation(stage === Stage.READBACK || stage === Stage.SIGN || stage === Stage.PUBLISH, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        requireSqlCleanup()
        if (leaseStartedAtNanos != null) requireActualLease()
    }

    internal fun providerBudget(ceilingMillis: Long): PersistenceTimeBudget {
        requireProviderRunning()
        val leaseMillis = leaseStartedAtNanos?.let { (LEASE_NANOS - elapsedSince(it)) / 1_000_000L } ?: 10_000L
        requireSignerRotation(leaseMillis > 0, CatalogSignerRotationFreezeFailureV1.TIME_BUDGET_EXHAUSTED)
        return budget.capped(minOf(ceilingMillis, 10_000L, leaseMillis))
    }

    internal fun sampleWallTime(): Instant {
        requireConnectionFree()
        requireRunning()
        val value = clock.instant()
        requireRunning()
        requireSignerRotation(value.epochSecond in 0..253402300799L && lastWall?.let { !value.isBefore(it) } != false)
        lastWall = value
        return value
    }

    private fun selectPhase(path: PersistencePhasePath) {
        requireRunning()
        requireSqlCleanup()
        requireSignerRotation(selectedPath == null, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        selectedPath = path
        phaseEntered = false
        phaseRetainedForEntry = false
    }

    internal fun requirePhaseEntry(candidate: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireRunning()
        requireSignerRotation(candidate === ownership && selectedPath === path && !phaseEntered, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        when (path) {
            PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT -> requireSnapshotSelection(candidate)

            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE -> requireLeaseAttempt(checkNotNull(binding))

            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT,
            -> requireSignerRotation(activeInput?.path === path && activeInput?.kind === selectedKind && inputConstructionClaimed)

            else -> throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        }
        phaseEntered = true
    }

    internal fun authenticate(candidate: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning()
        requireSignerRotation(candidate === ownership && jdbc.dataSource === source && phaseEntered && selectedPath != null)
        val authenticated = jdbc.query(
            "SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("authenticated") && !rows.wasNull() && !rows.next() },
            username,
            username,
            database,
        )
        requireSignerRotation(authenticated == true, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        requireRunning()
    }

    internal fun retainPhase(phase: PersistencePhaseContext) {
        requireSignerRotation(
            caller === Thread.currentThread() && phaseEntered && selectedPath != null && originalPhase == null && !phaseRetainedForEntry &&
                !sqlCleanupUnproven && !outcomeUncertain,
            CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN,
        )
        originalPhase = phase // Before publication, permits, manager checkout, including a failed entry that never returns.
        phaseRetainedForEntry = true
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun observePhaseCleanup(phase: PersistencePhaseContext) {
        if (caller !== Thread.currentThread() || originalPhase !== phase) {
            sqlCleanupUnproven = true
            return
        }
        try {
            if (!phase.signerRotationActivationCleanupProven(this)) sqlCleanupUnproven = true
            if (phase.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) outcomeUncertain = true
            if (!sqlCleanupUnproven && !outcomeUncertain) originalPhase = null
        } catch (problem: Throwable) {
            sqlCleanupUnproven = true
            observeFailure(problem)
        }
    }

    private fun phaseFailed(problem: Throwable): Nothing {
        if (!phaseRetainedForEntry && problem is PersistencePhaseException && !problem.cleanupProven) sqlCleanupUnproven = true
        observeFailure(problem)
        throwIfSignalled()
        throw problem
    }

    private fun requireSqlCleanup() = requireSignerRotation(
        caller === Thread.currentThread() && !sqlCleanupUnproven && !outcomeUncertain && originalPhase == null,
        CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN,
    )

    internal fun observeFailure(problem: Throwable) {
        val signal = when {
            problem is Error -> problem

            problem is CancellationException -> CancellationException("Catalog signer rotation activation cancelled.")

            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ||
                (problem is CatalogReadbackException && problem.code === CatalogReadbackFailure.INTERRUPTED) ||
                (problem is CatalogSignerRotationFreezeExceptionV1 && problem.code === CatalogSignerRotationFreezeFailureV1.INTERRUPTED) ||
                (problem is CatalogSignerRotationCustodyExceptionV1 && problem.code === CatalogSignerRotationCustodyFailureV1.INTERRUPTED) ->
                InterruptedException("Catalog signer rotation activation interrupted.")

            else -> return
        }
        while (true) {
            val before = originalSignal.get()
            val retainBefore = before is Error || (before is CancellationException && signal !is Error) ||
                (before is InterruptedException && signal is InterruptedException)
            if (retainBefore) return
            if (originalSignal.compareAndSet(before, signal)) return
        }
    }

    internal fun throwIfSignalled() {
        originalSignal.get()?.let { throw signerRotationSignal(it) }
    }

    internal fun requireCustody(selected: CatalogReadbackRefreshCustodyV1) = requireSignerRotation(
        caller === Thread.currentThread() && coordinator.catalogRefreshCustody === selected,
        CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
    )

    internal fun requireRunning() {
        throwIfSignalled()
        requireSignerRotation(caller === Thread.currentThread() && !closed && !failed && !released, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        requireSignerRotation(!sqlCleanupUnproven && !outcomeUncertain, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        requireSignerRotation(!Thread.currentThread().isInterrupted, CatalogSignerRotationFreezeFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
        requireProcess()
        if (reserved) coordinator.catalogRefreshCustody.requireSignerRotationActivation(this)
        leaseStartedAtNanos?.let(::requireOriginalDeadline)
    }

    private fun requireActualLease() {
        requireRunning()
        requireSignerRotation(pending == null && campaign?.binding === binding, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        checkNotNull(campaign).requireSameWindow(checkNotNull(dispatchWindow))
        checkNotNull(campaign).requireRotationContinuity(budget)
    }

    private fun elapsedSince(started: Long): Long = ownership.nanoClock.nanoTime() - started

    private fun requireOriginalDeadline(started: Long) {
        val elapsed = elapsedSince(started)
        budget.remainingMillis(1)
        requireSignerRotation(elapsed in 0 until LEASE_NANOS, CatalogSignerRotationFreezeFailureV1.TIME_BUDGET_EXHAUSTED)
    }

    private fun requireProcess() {
        process.requireUnchangedConfiguration()
        requireSignerRotation(
            process.pools.catalogCoordinator === coordinator && coordinator.ownership === ownership && coordinator.manager === manager &&
                coordinator.dataSource === source && coordinator.catalogSignerRotationActivation && !coordinator.catalogSignerRotationRecovery &&
                !coordinator.catalogSignerRotationDelivery && !coordinator.catalogGenesisAuthoring && !coordinator.catalogGenesisFinalization &&
                !coordinator.desiredInstallationOperator &&
                !coordinator.catalogSignerRotationAuthoring && process.catalogReadback?.projectedCurrent == false && process.catalogSignerRotation != null &&
                desired.mode === ComplaintInstallationMode.LIVE && desired.scope == ComplaintDataScope.LIVE &&
                desired.implementationSchema == 1 && desired.desiredGeneration == 1L &&
                username != VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME &&
                username != VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        checkNotNull(process.catalogSignerRotation).requireRetained(process.pools, checkNotNull(process.catalogReadback))
        coordinator.requireResources()
    }

    private fun abort() {
        failed = true
        pending?.spent = true
        campaign?.close() // Local only. Never an old-B SQL renewal, release or replacement after head3.
    }

    override fun close() {
        requireSignerRotation(caller === Thread.currentThread(), CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        if (closed) {
            closeFailure?.let { throw it }
            return
        }
        closed = true
        stage = Stage.CLOSED
        closeFailure = CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        abort()
        val outcomes = listOf(
            runCatching(assembly::close),
            runCatching { custody?.close() },
            runCatching(::requireConnectionFree),
            runCatching(::requireSqlCleanup),
            runCatching(::throwIfSignalled),
            runCatching {
                requireSignerRotation(!Thread.currentThread().isInterrupted, CatalogSignerRotationFreezeFailureV1.INTERRUPTED)
                budget.remainingMillis(1)
            },
        )
        requireSignerRotationCleanup(outcomes)
        cleanupProven = true
        if (reserved) coordinator.catalogRefreshCustody.releaseSignerRotationActivationAfterCleanup(this)
        released = true
        closeFailure = null
    }

    internal fun requireActualCleanup() {
        requireSignerRotation(caller === Thread.currentThread() && closed && cleanupProven, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        requireSqlCleanup()
        requireConnectionFree()
    }

    internal fun requireResult(state: CatalogSignerRotationActivationStateV1) {
        requireConnectionFree()
        requireSignerRotation(allowedResult === state, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        when (state) {
            CatalogSignerRotationActivationStateV1.PROJECTION_PENDING -> {
                requireSignerRotation(stage === Stage.PENDING, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
                requirePending()
            }

            CatalogSignerRotationActivationStateV1.PREPARED_UNSIGNED, CatalogSignerRotationActivationStateV1.SIGNED_UNPUBLISHED,
            CatalogSignerRotationActivationStateV1.AWAIT_REPLICATION, CatalogSignerRotationActivationStateV1.PROJECTED -> {
                requireActualCleanup()
                requireSignerRotation(released && closeFailure == null, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
            }
        }
        budget.remainingMillis(1)
    }

    override fun toString(): String = "CatalogSignerRotationActivationV1(fixed-activation3,new-key-only,cold-never-Sign,redacted)"

    private class Pending(
        val operation: CatalogSignerRotationActivationOperationV1,
        val input: CatalogSignerRotationActivationInputV1,
        val observation: CatalogSignerRotationActivationObservationV1,
        val allowance: PersistenceTimeBudget,
        val startedAtNanos: Long,
    ) {
        var spent = false
    }

    private enum class Stage {
        NEW, INPUTS, SNAPSHOT, READBACK, ACQUIRE, HISTORY, RECHECK, ALLOCATE,
        ARM_PREPARE, PREPARE, ARM_SIGN, SIGN, ARM_SIGNATURE, SIGNATURE,
        ARM_PUBLICATION, PUBLISH, EVIDENCE, ARM_COMPLETE, COMPLETE, PENDING,
        ARM_PROJECT, PROJECT, CLOSED,
    }

    private enum class Mode { INITIAL, CONTINUE, RECOVER }

    companion object {
        private const val LEASE_NANOS = 30_000_000_000L
        private val INITIAL_KINDS = setOf(
            CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PREPARED_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
        )

        fun begin(process: VersionBoundComplaintProcessConfiguration): CatalogSignerRotationActivationV1 =
            CatalogSignerRotationActivationV1(process, startBudget(process), null, null, null, Clock.systemUTC())

        /** Raw HTTP/wall-time seams only. Actual named root, SQL, SDK/native construction and custody are unchanged. */
        internal fun withHttpFixture(
            process: VersionBoundComplaintProcessConfiguration,
            signing: () -> SdkHttpClient,
            put: () -> SdkHttpClient,
            readback: () -> SdkHttpClient,
            clock: Clock,
        ): CatalogSignerRotationActivationV1 = CatalogSignerRotationActivationV1(process, startBudget(process), signing, put, readback, clock)

        private fun startBudget(process: VersionBoundComplaintProcessConfiguration): PersistenceTimeBudget {
            requireConnectionFree()
            val writer = process.catalogSignerRotation ?: throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
            return PersistenceTimeBudget.start(writer.deployment.totalAttemptMillis, process.pools.catalogCoordinator.ownership.nanoClock)
        }

        private fun sameArguments(first: Array<Any?>, second: Array<Any?>): Boolean = first.size == second.size && first.indices.all { index ->
            val before = first[index]
            val after = second[index]
            if (before is ByteArray) after is ByteArray && before.contentEquals(after) else before == after
        }
    }
}
