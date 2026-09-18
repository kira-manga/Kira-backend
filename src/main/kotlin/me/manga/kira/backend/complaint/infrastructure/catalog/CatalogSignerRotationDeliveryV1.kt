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
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * Fixed TARGET D7 signed-overlap2 publication/COMPLETE and separate same-owner PROJECT only.
 * No FreezeResult, supplied B, human approval claim or raw proof alone grants an effect. Recovery
 * cannot supply Sign/PUT credentials. The original release lock, refresh slot and allowance span
 * COMPLETE -> PROJECT; a private pending capability is issued only after actual commit AND release.
 *
 * INTERNAL limitation: cold pending2, already projected2 and unknown COMPLETE/PROJECT restart
 * resolution are deliberately refused. This is not a full interruption-safe delivery lifecycle.
 * The caller separately retains, prepares and retires its original TARGET-only process assembly.
 */
@Suppress("TooManyFunctions", "LargeClass") // Closed typed resource/phase guards stay with their actual original owner.
internal class CatalogSignerRotationDeliveryV1 private constructor(
    internal val process: VersionBoundComplaintProcessConfiguration,
    internal val budget: PersistenceTimeBudget,
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
    private val assembly = CatalogSignerRotationDeliveryAssemblyV1(this, putHttpFactory, readbackHttpFactory, clock)
    private var stage = Stage.NEW
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
    private var history: CatalogSignerRotationPreparedRecoveryInputsV1? = null
    private var selectedInputs: CatalogSignerRotationInputsV1? = null
    internal val inputs: CatalogSignerRotationInputsV1 get() = checkNotNull(selectedInputs)
    private var release: CatalogSignerRotationDeliveryReleaseV1? = null
    private var snapshot: LocalCatalogSnapshot.Prepared? = null
    private var readback: CatalogDualLocationVerifier.Overlap2Readback? = null
    private var binding: CatalogCoordinatorLeaseBindingV1? = null
    private var campaign: CatalogCoordinatorLeaseCampaignV1? = null
    private var leaseReceipt: CatalogCoordinatorLeaseReceiptV1? = null
    private var dispatchWindow: CatalogCoordinatorLeaseCampaignV1.Window? = null
    private var leaseStartedAtNanos: Long? = null
    private var leaseBinding: Array<Any?>? = null
    private var acquireIssued = false
    private var initialReadIssued = false
    private var recheckIssued = false
    private var completeIssued = false
    private var completeArmed = false
    private var projectIssued = false
    private var projectArmed = false
    private var publicationArmedHere = false
    private var putClaimed = false
    private var putConstructionIssued = false
    private var acknowledgement: CatalogPrimaryPutAcknowledgementV1? = null
    private var preparedHistory: CatalogSignerRotationFinalizationObservationV1? = null
    private var completedHistory: CatalogSignerRotationFinalizationObservationV1? = null
    private var projectedHistory: CatalogSignerRotationFinalizationObservationV1? = null
    private var completeOperation: CatalogSignerRotationFinalizationOperationV1? = null
    private var projectOperation: CatalogSignerRotationFinalizationOperationV1? = null
    private var pending: Pending? = null
    private var allowedResult: CatalogSignerRotationDeliveryStateV1? = null
    private var lastWall: Instant? = null
    private var selectedPath: PersistencePhasePath? = null
    private var selectedKind: CatalogSignerRotationFinalizationKindV1? = null
    private var inputConstructionClaimed = false
    private var activeInput: CatalogSignerRotationFinalizationInputV1? = null
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

    fun publish(
        request: CatalogSignerRotationFreezeRequestV1,
        primaryPutCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogSignerRotationDeliveryResultV1 = run(request, primaryPutCredentials, primaryReadCredentials, replicaReadCredentials)

    /** No write/sign credentials can be supplied to recovery, even if both listings currently lack successor2. */
    fun recover(
        request: CatalogSignerRotationFreezeRequestV1,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogSignerRotationDeliveryResultV1 = run(request, null, primaryReadCredentials, replicaReadCredentials)

    @Suppress("TooGenericExceptionCaught")
    private fun run(
        request: CatalogSignerRotationFreezeRequestV1,
        putCredentials: AwsSessionCredentials?,
        primary: AwsSessionCredentials,
        replica: AwsSessionCredentials,
    ): CatalogSignerRotationDeliveryResultV1 {
        requireSignerRotation(caller === Thread.currentThread(), CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        var state: CatalogSignerRotationDeliveryStateV1? = null
        var pendingResult: CatalogSignerRotationDeliveryResultV1? = null
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireSignerRotation(!entered && stage === Stage.NEW, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
            entered = true
            publishMode = putCredentials != null
            this.request = request
            coordinator.catalogRefreshCustody.reserveSignerRotationDelivery(this)
            reserved = true
            acquireInputs(request)
            stage = Stage.SNAPSHOT
            val observed = loadSnapshot()
            // Cold pending or projected replay is not authority for this first delivery slice.
            requireSignerRotation(observed is LocalCatalogSnapshot.Prepared, CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
            val prepared = observed as LocalCatalogSnapshot.Prepared
            requirePreparedTuple(prepared)
            snapshot = prepared
            stage = Stage.READBACK
            readback = assembly.observe(prepared, primary, replica)
            stage = Stage.ACQUIRE
            binding = CatalogCoordinatorLeaseBindingV1.fromDelivery(this, process, checkNotNull(readback))
            acquireLease(checkNotNull(binding))
            stage = Stage.HISTORY
            initialReadIssued = true
            preparedHistory = executeFinalization(CatalogSignerRotationFinalizationKindV1.INITIAL_READ).observation.also(::requirePreparedHistory)
            stage = Stage.READBACK
            readback = assembly.observe(prepared, primary, replica)
            stage = Stage.RECHECK
            recheckIssued = true
            val before = checkNotNull(preparedHistory)
            val checked = executeFinalization(CatalogSignerRotationFinalizationKindV1.RECHECK).observation
            before.requireSame(checked)
            requirePreparedHistory(checked)
            preparedHistory = checked
            if (checkNotNull(readback).state === CatalogDualLocationVerifier.Overlap2Readback.State.PREPARED_UNPUBLISHED) {
                publishUnpublished(putCredentials, primary, replica)
            }
            state = finishObservedDelivery()
            if (state === CatalogSignerRotationDeliveryStateV1.PROJECTION_PENDING) {
                allowedResult = state
                pendingResult = CatalogSignerRotationDeliveryResultV1.issuedBy(this, state)
            }
        } catch (problem: Throwable) {
            observeFailure(problem)
            failure = signerRotationSignal(problem)
            abort()
        } finally {
            if (pendingResult == null || failure != null) {
                try {
                    close()
                } catch (cleanup: Throwable) {
                    failure = preferSignerRotationCleanup(failure, cleanup)
                }
            }
        }
        failure?.let { throw boundedSignerRotationFailure(it) }
        pendingResult?.let { return it } // Original owner/slot/custody remain open, not replaced by a result capability.
        allowedResult = checkNotNull(state)
        return CatalogSignerRotationDeliveryResultV1.issuedBy(this, checkNotNull(state))
    }

    private fun acquireInputs(request: CatalogSignerRotationFreezeRequestV1) {
        stage = Stage.INPUTS
        val held = CatalogSignerRotationReleaseCustodyV1.retainExisting(request.releaseRoot, budget)
        custody = held
        val allocation = held.discoverExisting()
        history = CatalogSignerRotationPreparedRecoveryInputsV1.read(this, held, allocation)
        selectedInputs = assembly.acquire(request)
        release = CatalogSignerRotationDeliveryReleaseV1(this, inputs, held)
        requireRunning()
    }

    private fun publishUnpublished(putCredentials: AwsSessionCredentials?, primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        val retained = checkNotNull(release)
        // An old durable arm, a lost ACK or absent tail is never permission for another attempt.
        requireSignerRotation(putCredentials != null && !retained.isArmed(), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        stage = Stage.ARM_PUBLICATION
        retained.armPublication()
        publicationArmedHere = true
        stage = Stage.PUBLISH
        retained.claimPut()
        val observed = assembly.put(checkNotNull(putCredentials))
        acknowledgement = observed
        retained.acknowledged(observed)
        stage = Stage.READBACK
        readback = assembly.observe(checkNotNull(snapshot), primary, replica)
    }

    private fun finishObservedDelivery(): CatalogSignerRotationDeliveryStateV1 {
        val retained = checkNotNull(release)
        val proof = checkNotNull(readback)
        requireSignerRotation(retained.isArmed(), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        stage = Stage.EVIDENCE
        return when (proof.state) {
            CatalogDualLocationVerifier.Overlap2Readback.State.PREPARED_AWAIT_REPLICATION -> {
                retained.awaitReplication(proof)
                CatalogSignerRotationDeliveryStateV1.AWAIT_REPLICATION
            }

            CatalogDualLocationVerifier.Overlap2Readback.State.PREPARED_DUAL_COPY -> {
                retained.preserveDual(proof)
                stage = Stage.ARM_COMPLETE
                requireSignerRotation(!completeIssued, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
                completeIssued = true // Spent before durable arm or phase entry; any ambiguity stays spent.
                retained.armComplete()
                completeArmed = true
                stage = Stage.COMPLETE
                val operation = executeFinalization(CatalogSignerRotationFinalizationKindV1.COMPLETE)
                completeOperation = operation
                val completed = operation.observation
                completedHistory = completed
                retained.completed(completed)
                retainPending(operation, completed)
                CatalogSignerRotationDeliveryStateV1.PROJECTION_PENDING
            }

            else -> throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        }
    }

    /** A distinct transaction. No tuple/campaign/result argument can stand in for this owner's private pending capability. */
    @Suppress("TooGenericExceptionCaught")
    fun project(): CatalogSignerRotationDeliveryResultV1 {
        requireSignerRotation(caller === Thread.currentThread(), CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        var success = false
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requirePending()
            requireSignerRotation(stage === Stage.PENDING && !projectIssued, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
            projectIssued = true
            val held = checkNotNull(pending)
            held.spent = true // No late return, rollback or unknown commit can restore this eligibility.
            allowedResult = null
            stage = Stage.ARM_PROJECT
            checkNotNull(release).armProject(held.observation)
            projectArmed = true
            stage = Stage.PROJECT
            val operation = executeFinalization(CatalogSignerRotationFinalizationKindV1.PROJECT)
            projectOperation = operation
            projectedHistory = operation.observation
            checkNotNull(release).projected(checkNotNull(projectedHistory))
            requireRunning()
            success = true
        } catch (problem: Throwable) {
            observeFailure(problem)
            failure = signerRotationSignal(problem)
            abort()
        } finally {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure = preferSignerRotationCleanup(failure, cleanup)
            }
        }
        failure?.let { throw boundedSignerRotationFailure(it) }
        requireSignerRotation(success, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        allowedResult = CatalogSignerRotationDeliveryStateV1.PROJECTED
        return CatalogSignerRotationDeliveryResultV1.issuedBy(this, CatalogSignerRotationDeliveryStateV1.PROJECTED)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadSnapshot(): LocalCatalogSnapshot {
        selectPhase(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT)
        return try {
            coordinator.snapshot.loadSignerRotationDelivery(this, inputs.reader.policyAt(sampleWallTime()))
        } catch (problem: Throwable) {
            phaseFailed(problem)
        } finally {
            selectedPath = null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun acquireLease(selected: CatalogCoordinatorLeaseBindingV1) {
        requireSignerRotation(!acquireIssued, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        acquireIssued = true
        selectPhase(PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE)
        try {
            val actual = coordinator.lease.acquireDelivery(this, selected)
            campaign = actual.campaign // Retain original returned campaign/receipt before all post-return checks.
            leaseReceipt = actual.receipt
            dispatchWindow = actual.campaign.requireLocalWindow()
            leaseStartedAtNanos = checkNotNull(dispatchWindow).startedAtNanos
            requireRunning()
            val retained = checkNotNull(release)
            requireSignerRotation(
                actual.receipt.transition === CatalogCoordinatorLeaseTransitionV1.ACQUIRED && actual.receipt.owner !in retained.historicalOwners() &&
                    actual.receipt.token > retained.historicalLeaseFloor() && actual.campaign.owner == actual.receipt.owner &&
                    actual.campaign.token == actual.receipt.token && actual.campaign.binding === selected && actual.receipt.expiresAt != null,
                CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED,
            )
            leaseBinding = arrayOf(*selected.arguments(), actual.receipt.owner, actual.receipt.token, Timestamp.from(checkNotNull(actual.receipt.expiresAt)))
            requireActualLease()
        } catch (problem: Throwable) {
            campaign?.close()
            phaseFailed(problem)
        } finally {
            selectedPath = null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeFinalization(kind: CatalogSignerRotationFinalizationKindV1): CatalogSignerRotationFinalizationOperationV1 {
        val path = when (kind) {
            CatalogSignerRotationFinalizationKindV1.INITIAL_READ, CatalogSignerRotationFinalizationKindV1.RECHECK ->
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ

            CatalogSignerRotationFinalizationKindV1.COMPLETE -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE

            CatalogSignerRotationFinalizationKindV1.PROJECT -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT
        }
        if (kind === CatalogSignerRotationFinalizationKindV1.PROJECT) requirePendingIdentity() else requireActualLease()
        selectPhase(path)
        selectedKind = kind
        inputConstructionClaimed = false
        return try {
            val input = when (kind) {
                CatalogSignerRotationFinalizationKindV1.INITIAL_READ -> CatalogSignerRotationFinalizationInputV1.initial(this)

                CatalogSignerRotationFinalizationKindV1.RECHECK -> CatalogSignerRotationFinalizationInputV1.recheck(this, checkNotNull(preparedHistory))

                CatalogSignerRotationFinalizationKindV1.COMPLETE ->
                    CatalogSignerRotationFinalizationInputV1.complete(this, checkNotNull(preparedHistory), checkNotNull(readback))

                CatalogSignerRotationFinalizationKindV1.PROJECT -> CatalogSignerRotationFinalizationInputV1.project(this, checkNotNull(pending).observation)
            }
            activeInput = input
            coordinator.signerRotationFinalization.execute(input)
        } catch (problem: Throwable) {
            phaseFailed(problem)
        } finally {
            activeInput = null
            selectedKind = null
            selectedPath = null
        }
    }

    private fun retainPending(operation: CatalogSignerRotationFinalizationOperationV1, observation: CatalogSignerRotationFinalizationObservationV1) {
        requireConnectionFree()
        requireSqlCleanup()
        requireRunning()
        requireSignerRotation(
            stage === Stage.COMPLETE && pending == null && completeOperation === operation && operation.input.original === this &&
                operation.input.kind === CatalogSignerRotationFinalizationKindV1.COMPLETE && operation.observation === observation &&
                completedHistory === observation && observation.completedAt != null && observation.projectedAt == null,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        val actual = checkNotNull(campaign)
        val started = actual.requireLocalWindow().startedAtNanos // Capture BEFORE close nulls the actual Window/custody entry.
        requireSignerRotation(started == leaseStartedAtNanos, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        actual.close() // Local permanent retirement only. No stale B1 renew/relinquish/reacquire call occurs.
        pending = Pending(operation, operation.input, observation, budget, started)
        stage = Stage.PENDING
        requirePending()
    }

    private fun requirePending() {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requirePendingIdentity()
        val held = checkNotNull(pending)
        requireSignerRotation(!held.spent && !projectIssued, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        requireSignerRotation(held.operation.observation === held.observation, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        assembly.requireProviderCleanup()
    }

    /** No call to the retired campaign: retain only its original dispatch deadline plus original process/budget/operation identity. */
    private fun requirePendingIdentity() {
        requireRunning()
        val held = checkNotNull(pending)
        requireSignerRotation(
            held.allowance === budget && held.startedAtNanos == leaseStartedAtNanos && held.operation === completeOperation &&
                held.operation.input === held.input && held.input.original === this && held.observation === completedHistory,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        requireOriginalDeadline(held.startedAtNanos)
    }

    private fun requirePreparedTuple(local: LocalCatalogSnapshot.Prepared) {
        val retained = checkNotNull(release)
        requireSignerRotation(
            local.head.generation == 1L && local.head.envelopeSha256 == historicalPredecessorHash(),
            CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED,
        )
        inputs.requireMutation(local.mutation)
        val returned = retained.signatures()
        requireSignerRotation(local.mutation.signatureSlots.indices.all { local.mutation.signatureSlots[it].signatureBytes.contentEquals(returned[it]) })
        requireSignerRotation(
            local.mutation.signedEnvelopeBytes.contentEquals(retained.envelopeBytes()),
            CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED,
        )
    }

    private fun requirePreparedHistory(value: CatalogSignerRotationFinalizationObservationV1) {
        requireConnectionFree()
        requireRunning()
        requireSignerRotation(value.completedAt == null && value.projectedAt == null, CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        inputs.requireObservation(CatalogSignerRotationObservationV1(value.genesis, value.mutation))
        requireSignerRotation(sameSignerRotationMutation(value.mutation, frozenMutation()), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        checkNotNull(readback).requireSnapshot(checkNotNull(snapshot))
    }

    internal fun frozenMutation(): CatalogFrozenMutation {
        requireConnectionFree()
        requireRunning()
        return checkNotNull(snapshot).mutation
    }

    internal fun requireHistoricalCustody(selected: CatalogSignerRotationReleaseCustodyV1) {
        requireRunning()
        requireSignerRotation(stage === Stage.INPUTS && custody === selected && reserved, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
    }

    internal fun requireInputAcquisition(selected: CatalogSignerRotationFreezeRequestV1) {
        requireRunning()
        requireSignerRotation(
            stage === Stage.INPUTS && request === selected && history != null && selectedInputs == null,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requireInputHistory(selected: CatalogSignerRotationInputsV1) {
        requireInputAcquisition(selected.request)
        checkNotNull(history).requireInputs(process, selected)
    }

    internal fun historicalBindingRecord(): ByteArray = checkNotNull(history).bindingBytes()
    internal fun historicalPredecessorHash(): String = checkNotNull(history).predecessorHash

    internal fun requireDeliveryReleaseInputs(selected: CatalogSignerRotationInputsV1, held: CatalogSignerRotationReleaseCustodyV1) {
        requireHistoricalCustody(held)
        requireSignerRotation(selectedInputs === selected && release == null, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
    }

    internal fun requireObservedSnapshot(selected: LocalCatalogSnapshot.Prepared) {
        requireProviderRunning()
        requireSignerRotation(stage === Stage.READBACK && snapshot === selected, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
    }

    internal fun requireBindingInputs(candidate: VersionBoundComplaintProcessConfiguration, raw: CatalogDualLocationVerifier.Overlap2Readback) {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireSignerRotation(
            stage === Stage.ACQUIRE && !acquireIssued && binding == null && candidate === process && raw === readback,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        assembly.requireProof(raw)
        raw.requireSnapshot(checkNotNull(snapshot))
        checkNotNull(history).requireDeliveryReadback(raw)
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
        selected.requireDeliveryPurpose(this)
    }

    internal fun requireHistoricalLeaseFloor(selected: CatalogCoordinatorLeaseBindingV1, lockedToken: Long) {
        requireLeaseAttempt(selected)
        requireSignerRotation(lockedToken >= checkNotNull(release).historicalLeaseFloor(), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
    }

    /** Runs only under the already-locked LIVE row, both before the later-clock CAS and after its exact reread. */
    internal fun requireClosedLeaseControl(selected: CatalogCoordinatorLeaseBindingV1, jdbc: JdbcTemplate) {
        requireLeaseAttempt(selected)
        requireSignerRotation(
            jdbc.dataSource === source && originalPhase === PersistencePhaseOwnership.current(),
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        val closed = jdbc.query(
            "SELECT (maintenance_closed AND creation_closed) IS TRUE AS gates_closed FROM complaint_journal_control " +
                "WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("gates_closed") && !rows.wasNull() && !rows.next() },
        )
        requireSignerRotation(closed == true, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        requireLeaseAttempt(selected)
    }

    internal fun requireInputConstruction(
        kind: CatalogSignerRotationFinalizationKindV1,
        expected: CatalogSignerRotationFinalizationObservationV1?,
        proof: CatalogDualLocationVerifier.Overlap2Readback?,
    ) {
        requireRunning()
        requireSqlCleanup()
        requireSignerRotation(selectedKind === kind && !inputConstructionClaimed && activeInput == null, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        val allowed = when (kind) {
            CatalogSignerRotationFinalizationKindV1.INITIAL_READ -> stage === Stage.HISTORY && initialReadIssued && expected == null && proof == null

            CatalogSignerRotationFinalizationKindV1.RECHECK -> stage === Stage.RECHECK && recheckIssued && expected === preparedHistory && proof == null

            CatalogSignerRotationFinalizationKindV1.COMPLETE ->
                stage === Stage.COMPLETE && completeIssued && completeArmed && expected === preparedHistory && proof === readback

            CatalogSignerRotationFinalizationKindV1.PROJECT -> {
                requirePendingIdentity()
                stage === Stage.PROJECT && projectIssued && projectArmed && checkNotNull(pending).spent && expected === pending?.observation && proof == null
            }
        }
        requireSignerRotation(allowed, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        inputConstructionClaimed = true
    }

    internal fun finalizationBindingArguments(): Array<Any?> {
        requireConnectionFree()
        requireRunning()
        requireSignerRotation(selectedKind != null && inputConstructionClaimed && activeInput == null, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        return detachedBinding()
    }

    internal fun currentAcquisitionRecordValues(): Array<String> {
        requireConnectionFree()
        requireRunning()
        val receipt = checkNotNull(leaseReceipt)
        val fields = checkNotNull(leaseBinding).take(9).map { if (it is ByteArray) HexFormat.of().formatHex(it) else it.toString() }
        return (fields + listOf(receipt.owner.toString(), receipt.token.toString(), checkNotNull(receipt.expiresAt).toString())).toTypedArray()
    }

    private fun detachedBinding(): Array<Any?> = checkNotNull(leaseBinding).map {
        when (it) {
            is ByteArray -> it.copyOf()
            is Timestamp -> Timestamp.from(it.toInstant())
            else -> it
        }
    }.toTypedArray()

    internal fun requireFinalizationPersistence(input: CatalogSignerRotationFinalizationInputV1, candidate: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning()
        requireSignerRotation(
            activeInput === input && input.original === this && input.kind === selectedKind && selectedPath === input.path &&
                candidate === ownership && jdbc.dataSource === source,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        if (input.kind === CatalogSignerRotationFinalizationKindV1.PROJECT) requirePendingIdentity()
        checkNotNull(binding).requireDeliveryPurpose(this)
        checkNotNull(binding).requirePersistence(candidate, jdbc) // Identity only; current B1/B2/lease come from fixed SQL, never this old binding.
    }

    internal fun capacityDigest(): ByteArray {
        requireConnectionFree()
        requireRunning()
        return process.consumers.capacityPolicy.digestBytes()
    }

    internal fun requirePublicationArm(selected: CatalogSignerRotationDeliveryReleaseV1) {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireActualLease()
        requireSignerRotation(
            stage === Stage.ARM_PUBLICATION && release === selected && publishMode && !publicationArmedHere && !putClaimed &&
                recheckIssued && preparedHistory != null && readback?.state === CatalogDualLocationVerifier.Overlap2Readback.State.PREPARED_UNPUBLISHED,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        assembly.requireProof(checkNotNull(readback))
    }

    internal fun requirePublicationClaim(selected: CatalogSignerRotationDeliveryReleaseV1) {
        requireRunning()
        requireActualLease()
        requireSqlCleanup()
        requireSignerRotation(
            stage === Stage.PUBLISH && release === selected && publishMode && publicationArmedHere && !putClaimed,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        putClaimed = true
    }

    internal fun requirePutConstruction() {
        requireProviderRunning()
        requireSignerRotation(
            stage === Stage.PUBLISH && publishMode && publicationArmedHere && putClaimed && !putConstructionIssued,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        putConstructionIssued = true
    }

    internal fun requireAcknowledgement(selected: CatalogSignerRotationDeliveryReleaseV1, value: CatalogPrimaryPutAcknowledgementV1) {
        requireRunning()
        requireSignerRotation(stage === Stage.PUBLISH && release === selected && value === acknowledgement && putConstructionIssued)
        assembly.requireAcknowledgement(value)
    }

    internal fun requireDeliveryProof(selected: CatalogSignerRotationDeliveryReleaseV1, proof: CatalogDualLocationVerifier.Overlap2Readback) {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireActualLease()
        requireSignerRotation(stage === Stage.EVIDENCE && release === selected && proof === readback && preparedHistory != null)
        proof.requireSnapshot(checkNotNull(snapshot))
        assembly.requireProof(proof)
    }

    internal fun requireCompletionArm(selected: CatalogSignerRotationDeliveryReleaseV1, proof: CatalogDualLocationVerifier.Overlap2Readback) {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireActualLease()
        requireSignerRotation(
            stage === Stage.ARM_COMPLETE && release === selected && completeIssued && !completeArmed && proof === readback &&
                proof.state === CatalogDualLocationVerifier.Overlap2Readback.State.PREPARED_DUAL_COPY,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        assembly.requireProof(proof)
    }

    internal fun requireCompletedDelivery(selected: CatalogSignerRotationDeliveryReleaseV1, value: CatalogSignerRotationFinalizationObservationV1) {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireSignerRotation(
            stage === Stage.COMPLETE && release === selected && completeArmed && completedHistory === value && completeOperation?.observation === value,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requireProjectionArm(selected: CatalogSignerRotationDeliveryReleaseV1, value: CatalogSignerRotationFinalizationObservationV1) {
        requireConnectionFree()
        requirePendingIdentity()
        requireSqlCleanup()
        requireSignerRotation(stage === Stage.ARM_PROJECT && release === selected && projectIssued && !projectArmed && pending?.observation === value)
    }

    internal fun requireProjectedDelivery(selected: CatalogSignerRotationDeliveryReleaseV1, value: CatalogSignerRotationFinalizationObservationV1) {
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
        requireSignerRotation(stage === Stage.READBACK || stage === Stage.PUBLISH, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
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

            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT,
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
            if (!phase.signerRotationDeliveryCleanupProven(this)) sqlCleanupUnproven = true
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

            problem is CancellationException -> CancellationException("Catalog signer rotation delivery cancelled.")

            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ||
                (problem is CatalogReadbackException && problem.code === CatalogReadbackFailure.INTERRUPTED) ||
                (problem is CatalogSignerRotationFreezeExceptionV1 && problem.code === CatalogSignerRotationFreezeFailureV1.INTERRUPTED) ||
                (problem is CatalogSignerRotationCustodyExceptionV1 && problem.code === CatalogSignerRotationCustodyFailureV1.INTERRUPTED) ->
                InterruptedException("Catalog signer rotation delivery interrupted.")

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
        if (reserved) coordinator.catalogRefreshCustody.requireSignerRotationDelivery(this)
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
                coordinator.dataSource === source && coordinator.catalogSignerRotationDelivery && !coordinator.catalogSignerRotationRecovery &&
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
        campaign?.close() // Local only. Never an old-B SQL renewal, release or replacement after head2.
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
        if (reserved) coordinator.catalogRefreshCustody.releaseSignerRotationDeliveryAfterCleanup(this)
        released = true
        closeFailure = null
    }

    internal fun requireActualCleanup() {
        requireSignerRotation(caller === Thread.currentThread() && closed && cleanupProven, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        requireSqlCleanup()
        requireConnectionFree()
    }

    internal fun requireResult(state: CatalogSignerRotationDeliveryStateV1) {
        requireConnectionFree()
        requireSignerRotation(allowedResult === state, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        when (state) {
            CatalogSignerRotationDeliveryStateV1.PROJECTION_PENDING -> {
                requireSignerRotation(stage === Stage.PENDING, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
                requirePending()
            }

            CatalogSignerRotationDeliveryStateV1.AWAIT_REPLICATION, CatalogSignerRotationDeliveryStateV1.PROJECTED -> {
                requireActualCleanup()
                requireSignerRotation(released && closeFailure == null, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
            }
        }
        budget.remainingMillis(1)
    }

    override fun toString(): String = "CatalogSignerRotationDeliveryV1(fixed-overlap2,original-pending-only,no-activation3,redacted)"

    private class Pending(
        val operation: CatalogSignerRotationFinalizationOperationV1,
        val input: CatalogSignerRotationFinalizationInputV1,
        val observation: CatalogSignerRotationFinalizationObservationV1,
        val allowance: PersistenceTimeBudget,
        val startedAtNanos: Long,
    ) {
        var spent = false
    }

    private enum class Stage {
        NEW,
        INPUTS,
        SNAPSHOT,
        READBACK,
        ACQUIRE,
        HISTORY,
        RECHECK,
        ARM_PUBLICATION,
        PUBLISH,
        EVIDENCE,
        ARM_COMPLETE,
        COMPLETE,
        PENDING,
        ARM_PROJECT,
        PROJECT,
        CLOSED,
    }

    companion object {
        private const val LEASE_NANOS = 30_000_000_000L

        fun begin(process: VersionBoundComplaintProcessConfiguration): CatalogSignerRotationDeliveryV1 =
            CatalogSignerRotationDeliveryV1(process, startBudget(process), null, null, Clock.systemUTC())

        /** Raw HTTP/wall-time seams only. Real named root, original monotonic allowance, SDK, SQL and durable custody remain mandatory. */
        internal fun withHttpFixture(
            process: VersionBoundComplaintProcessConfiguration,
            put: () -> SdkHttpClient,
            readback: () -> SdkHttpClient,
            clock: Clock,
        ): CatalogSignerRotationDeliveryV1 = CatalogSignerRotationDeliveryV1(process, startBudget(process), put, readback, clock)

        private fun startBudget(process: VersionBoundComplaintProcessConfiguration): PersistenceTimeBudget {
            requireConnectionFree()
            val writer = process.catalogSignerRotation ?: throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
            return PersistenceTimeBudget.start(writer.deployment.totalAttemptMillis, process.pools.catalogCoordinator.ownership.nanoClock)
        }
    }
}
