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
 * Original cold TEST owner for released snapshot/readback/lease/PREPARE and one explicitly durable freeze path.
 * The old prepare/reload diagnostic path has no Sign custody. Neither path has PUT, COMPLETED, PROJECT,
 * reopening, a run issuer or any global provider/ingress-drain assertion.
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class CatalogTestRunActivationV1 private constructor(
    internal val process: VersionBoundTestNamespaceProcessV1,
    installationLimit: Long,
    internal val budget: PersistenceTimeBudget,
    readbackFactory: (() -> SdkHttpClient)?,
    private val clock: Clock,
    signingFactory: (() -> SdkHttpClient)? = null,
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
    private var stage = Stage.NEW
    private var entered = false
    private var recovering = false
    private var freezing = false
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
    private var allowedResult = false
    private var allowedSignedResult = false
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

    private fun openExistingCustody(root: Path) {
        stage = Stage.EXISTING_CUSTODY
        val held = CatalogTestRunActivationReleaseCustodyV1.retainExisting(root, budget)
        custody = held // Retained before any filesystem/native construction.
        val allocation = held.discoverExisting()
        val binding = checkNotNull(held.read(CatalogTestRunActivationReleaseLeafV1.BINDING))
        release = CatalogTestRunActivationReleaseV1(this, checkNotNull(frozen), held, allocation, binding, created = false)
        signed = checkNotNull(release).returnedSignature()
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
            CatalogTestRunActivationKindV1.PREPARE -> stage === Stage.PREPARE && !recovering && preparedOperation == null &&
                expectedSnapshot === originalSnapshot && leaseOperation != null && lease != null &&
                (!freezing || (prepareArmIssued && prepareArmed && release != null))
            CatalogTestRunActivationKindV1.PREPARED_RELOAD -> stage === Stage.RELOAD && reloadOperation == null && leaseOperation != null && lease != null &&
                ((recovering && preparedOperation == null && expectedSnapshot === originalSnapshot) ||
                    (!recovering && preparedOperation != null && preparedSnapshot === expectedSnapshot))
            CatalogTestRunActivationKindV1.SIGNATURE -> freezing && stage === Stage.SIGNATURE && signatureOperation == null &&
                signatureSqlArmIssued && signatureSqlArmed && signed != null && leaseOperation != null && lease != null &&
                ((reloadOperation != null && reloadOperation?.snapshot === expectedSnapshot) ||
                    (recovering && reloadOperation == null && expectedSnapshot === originalSnapshot && expectedSnapshot?.signedTail != null))
            CatalogTestRunActivationKindV1.SIGNED_RELOAD -> freezing && stage === Stage.SIGNED_RELOAD && signedReloadOperation == null &&
                signatureOperation != null && signatureOperation?.snapshot === expectedSnapshot && signed != null && signatureSqlArmed
        }
        requireTestActivation(allowed, CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
        if (kind !== CatalogTestRunActivationKindV1.SNAPSHOT) {
            predecessor.requireVerified(checkNotNull(originalSnapshot), checkNotNull(frozen))
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
        requireTestActivation(signed == null || (freezing && signed?.frozen === frozen))
        return signed
    }

    internal fun requireInput(input: CatalogTestRunActivationInputV1, candidate: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning()
        requireTestActivation(
            candidate === ownership && jdbc.dataSource === source && activeInput === input && input.original === this &&
                input.kind === selectedKind && input.frozen === frozen && input.expected === expectedSnapshot && input.lease === lease &&
                input.leaseOwner == leaseOwner && input.recovering == recovering && input.signed === signed && inputConstructionClaimed,
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
        requireTestActivation(!recovering && stage === Stage.PREPARE && preparedOperation == null && expectedSnapshot === originalSnapshot)
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
            CatalogTestRunActivationKindV1.LEASE_ACQUIRE -> if (recovering) requirePreparedGate(gate) else gate.requireUnownedOpen()
            CatalogTestRunActivationKindV1.PREPARED_RELOAD -> {
                requireTestActivation(recovering || (preparedOperation != null && preparedSnapshot === expectedSnapshot))
                requirePreparedGate(gate)
            }
            CatalogTestRunActivationKindV1.SIGNATURE, CatalogTestRunActivationKindV1.SIGNED_RELOAD -> {
                requireTestActivation(freezing && signatureSqlArmed && signed != null && input.signed === signed)
                requireActualLease()
                requirePreparedGate(gate)
            }
        }
    }

    private fun requireMaintenanceSelection(candidate: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireRunning()
        val input = checkNotNull(activeInput)
        requireTestActivation(candidate === ownership && originalPhase === PersistencePhaseOwnership.current() && phaseEntered && inputConstructionClaimed &&
            input.original === this && selectedKind === input.kind && input.path === path && input.frozen === frozen &&
            input.expected === expectedSnapshot && input.lease === lease && input.recovering == recovering && input.signed === signed,
            CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
        predecessor.requireVerified(checkNotNull(originalSnapshot), checkNotNull(frozen))
    }

    private fun requirePreparedGate(gate: PersistenceComplaintMaintenanceGateV1) {
        val input = checkNotNull(frozen)
        requireTestActivation(gate.matchesPrepared(input.token, input.scope, input.unsignedBytes(), input.unsignedHash()), CatalogTestRunActivationFailureV1.STATE_REFUSED)
    }

    internal fun requireReadback(selected: CatalogTestRunActivationPredecessorV1, snapshot: CatalogTestRunActivationSnapshotV1, input: CatalogTestRunActivationFrozenV1) {
        requireProviderRunning()
        requireTestActivation(predecessor === selected && originalSnapshot === snapshot && snapshotOperation?.snapshot === snapshot && frozen === input)
    }

    internal fun requireProviderRunning() {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireTestActivation((stage === Stage.READBACK && leaseStartedAtNanos == null) ||
            (stage === Stage.RECHECK_READBACK && freezing && !recovering && preparedOperation != null && prepareArmed && lease != null && !signReadbackRechecked),
            CatalogTestRunActivationFailureV1.PROCESS_REFUSED)
    }

    internal fun custodySnapshot(): CatalogTestRunActivationSnapshotV1 {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireTestActivation(freezing)
        return checkNotNull(expectedSnapshot)
    }

    internal fun custodyLease(): CatalogTestRunActivationLeaseV1 {
        requireConnectionFree()
        requireSqlCleanup()
        requireTestActivation(freezing)
        requireActualLease()
        return checkNotNull(lease)
    }

    internal fun requireReleaseCapture(selected: CatalogTestRunActivationReleaseCustodyV1, input: CatalogTestRunActivationFrozenV1, created: Boolean) {
        requireConnectionFree()
        requireRunning()
        requireSqlCleanup()
        requireTestActivation(freezing && custody === selected && frozen === input && release == null && created == !recovering &&
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
            process.pools.epochRotation == null && process.consumers.journalConfiguration.scope.testOnly &&
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
            runCatching(predecessor::close), runCatching(assembly::close), runCatching { custody?.close() },
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
        requireTestActivation(!freezing && allowedResult && released && closeFailure == null && reloadOperation?.input?.original === this &&
            reloadOperation?.input?.kind === CatalogTestRunActivationKindV1.PREPARED_RELOAD && reloadOperation?.snapshot === expectedSnapshot)
        checkNotNull(reloadOperation).requireReleased()
        budget.remainingMillis(1)
        return checkNotNull(frozen)
    }

    internal fun signedReceiptInput(): Pair<CatalogTestRunActivationFrozenV1, CatalogTestRunActivationSignedV1> {
        requireActualCleanup()
        requireTestActivation(freezing && allowedSignedResult && released && closeFailure == null &&
            signedReloadOperation?.input?.original === this && signedReloadOperation?.input?.kind === CatalogTestRunActivationKindV1.SIGNED_RELOAD &&
            signedReloadOperation?.snapshot === expectedSnapshot && expectedSnapshot?.signedTail != null)
        checkNotNull(signatureOperation).requireReleased()
        checkNotNull(signedReloadOperation).requireReleased()
        assembly.requireCleanup()
        budget.remainingMillis(1)
        return checkNotNull(frozen) to checkNotNull(signed)
    }

    override fun toString(): String = if (freezing) {
        "CatalogTestRunActivationV1(signed-PREPARED-boundary,no-PUT-or-run-authority,redacted)"
    } else {
        "CatalogTestRunActivationV1(PREPARED-only,not-run-or-Sign-authority,redacted)"
    }

    private enum class Stage {
        NEW, CAPTURE, EXISTING_CUSTODY, SNAPSHOT, READBACK, ACQUIRE, ALLOCATE, ARM_PREPARE, PREPARE,
        RECHECK_READBACK, RELOAD, ARM_SIGN, SIGN, ARM_SIGNATURE, SIGNATURE, SIGNED_RELOAD, CLOSED,
    }

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

internal enum class CatalogTestRunActivationFailureV1 { INPUT_REFUSED, PROCESS_REFUSED, STATE_REFUSED, TIME_BUDGET_EXHAUSTED, INTERRUPTED, CLEANUP_UNPROVEN }

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
        else -> CatalogTestRunActivationFailureV1.STATE_REFUSED
    }
    return CatalogTestRunActivationExceptionV1(code)
}
