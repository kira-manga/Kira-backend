package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceComplaintMaintenanceGateV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunTerminalRecordV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEventContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEvidenceDigestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInventoryWitnessV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPurgeV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteRows
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutIdentityV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutAcknowledgementV1
import me.manga.kira.backend.complaint.infrastructure.journal.OrdinaryJournalRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestTerminalInventoryReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestTerminalInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.terminal.AdmittedCatalogOrdinaryDenialV1
import me.manga.kira.backend.complaint.infrastructure.terminal.AdmittedCatalogTerminalDenialV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDenialAuthorityPolicyV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainPersistenceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainActiveHistoryV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalDenialAuthorityPolicyV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceTargetV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceSourceV1
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestPostTerminalInventoryFoldV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalFramesV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Bytes/credentials/locator only. No supplied row, denial ticket, successful predecessor or native proof. */
internal class CatalogTestRunTerminalRequestV1(
    val custodyRoot: Path,
    val ordinaryApproval: ByteArray,
    val ordinaryRawEvidence: List<ByteArray>,
    val terminalApproval: ByteArray,
    val terminalRawEvidence: List<ByteArray>,
    val ordinaryReadCredentials: AwsSessionCredentials,
    val signingCredentials: AwsSessionCredentials,
    val primaryPutCredentials: AwsSessionCredentials,
    val primaryReadCredentials: AwsSessionCredentials,
    val replicaReadCredentials: AwsSessionCredentials,
) {
    override fun toString(): String = "CatalogTestRunTerminalRequestV1(untrusted-inputs,redacted,no-authority)"
}

/**
 * One genuine completed-D child OR one separately claimed fresh-process resumer. Neither constructor
 * accepts historical DTOs as authority. Fresh signed/raw denial admission and both actual native
 * pairs precede a new catalog lease. The only successful local effect is exact-token PURGING.
 */
internal class CatalogTestRunTerminalV1 private constructor(
    private val predecessor: TestRunTerminalQuiescenceV1?,
    private val recovery: CatalogTestRunTerminalPreparedRecoveryV1?,
    internal val process: VersionBoundTestNamespaceProcessV1,
    installationLimit: Long,
) : AutoCloseable {
    internal val routing = process.consumers.journalRouting
    private val coordinator = process.pools.catalogCoordinator
    private val ownership = coordinator.ownership
    private val manager = coordinator.manager
    private val source = coordinator.dataSource
    internal val acquisition = process.ordinarySeal ?: throw CatalogTestRunTerminalExceptionV1()
    internal val expectedDeclaration = CatalogTestRunTerminalCanonicalV4.fromRetained(process, installationLimit)
    internal val budget = startBudget(process)
    internal val scope = routing.journalConfiguration.scope.id
    internal val writer = routing.journalConfiguration.declaration().writer.generationId
    private val ordinaryAuthority = process.ordinaryDenial ?: throw CatalogTestRunTerminalExceptionV1()
    private val terminalAuthority = process.terminalDenial ?: throw CatalogTestRunTerminalExceptionV1()
    private val caller = Thread.currentThread()
    private val failure = AtomicReference<Throwable?>()
    private val openings = process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }
        .openings().map { it.publicDriverProperties() }
    private val username = checkNotNull(openings.map { it["user"] }.distinct().single())
    private val database = checkNotNull(openings.map { it["PGDBNAME"] }.distinct().single())
    private var started = false
    private var closed = false
    private var reserved = false
    private var released = false
    private var cleanupProven = false
    private var cleanupUncertain = false
    private var phaseEntered = false
    private var phase: PersistencePhaseContext? = null
    private var catalogJdbc: JdbcTemplate? = null
    private var preflightJdbc: JdbcTemplate? = null
    private var stage = Stage.NEW
    private var selecting: CatalogTestRunTerminalKindV1? = null
    private var selectionClaimed = false
    private var selectedPhase: CatalogTestRunTerminalPhaseV1? = null
    private var selectedPreflight: CatalogTestRunTerminalPreflightKindV1? = null
    private var freezeClaimed = false
    private var token: UUID? = recovery?.token
    private var frozen: CatalogTestRunTerminalFrozenV1? = null
    private var record: CatalogTestRunTerminalRecordV1? = null
    private var snapshot: CatalogTestRunTerminalSnapshotV1? = null
    private var precondition: CatalogTestRunTerminalPreconditionV1? = null
    private var currentCheckedSnapshot: CatalogTestRunTerminalSnapshotV1? = null
    private var ordinary: AdmittedCatalogOrdinaryDenialV1? = null
    private var terminal: AdmittedCatalogTerminalDenialV1? = null
    private var ordinaryReader: TestOrdinaryInventoryReaderV1? = null
    private var terminalReader: TestTerminalInventoryReaderV1? = null
    private var ordinaryStartClaimed = false
    private var terminalStartClaimed = false
    private var ordinaryRetired = false
    private var terminalRetired = false
    private var ordinaryPass = 0
    private var terminalPass = 0
    private var ordinaryStartedAt: Instant? = null
    private var terminalStartedAt: Instant? = null
    private val ordinaryWitnesses = arrayOfNulls<TestTerminalInventoryWitnessV1>(2)
    private val terminalWitnesses = arrayOfNulls<TestTerminalInventoryWitnessV1>(2)
    private var ordinaryReadback: TestOrdinaryInventoryReadbackV1? = null
    private var terminalTarget: TestTerminalQuiescenceTargetV1? = null
    private var predecessorControl: List<Any?>? = null
    private val predecessorBindings = linkedMapOf<Triple<TestTerminalQuiescenceSourceV1, TestTerminalDurableKindV1, Int>, TestTerminalDurableBindingV1>()
    private val initialPublications = linkedMapOf<String, PublicationFacts>()
    private var initialPurgeBinding: TestTerminalDurableBindingV1? = null
    private var native: CatalogTestRunTerminalAssemblyV1? = null
    private var nativeClosed = false
    private var files: CatalogTestRunActivationReleaseCustodyV1? = null
    private var release: CatalogTestRunTerminalReleaseV1? = null
    private var capturedRelease: CatalogTestRunTerminalReleaseV1? = null
    private var releaseRetentionClaimed = false
    private var releaseCaptureClaimed = false
    private var signed: CatalogTestRunTerminalSignedV1? = null
    private var latestProof: CatalogTestRunTerminalDeliveryReadbackV1? = null
    private var dualProof: CatalogTestRunTerminalDeliveryReadbackV1? = null
    private var dualSnapshot: CatalogTestRunTerminalSnapshotV1? = null
    private var completeSnapshot: CatalogTestRunTerminalSnapshotV1? = null
    private var projectedReload: CatalogTestRunTerminalSnapshotV1? = null
    private var completedResult: CatalogTestRunTerminalResultV1? = null
    private var erasureClaimed = false
    private var erasureChild: TestRunErasureV1? = null
    private var lease: CatalogTestRunActivationLeaseV1? = null
    private var leaseOwner: UUID? = null
    private var leaseStartedAt: Long? = null
    private var leaseReleased = false
    private var projectedReplay = false
    private var prepareArmed = false
    private var signArmed = false
    private var signDispatched = false
    private var signReturned = false
    private var signatureSqlArmed = false
    private var publicationArmed = false
    private var putDispatched = false
    private var acknowledgement: CatalogPrimaryPutAcknowledgementV1? = null
    private var completeArmed = false
    private var projectArmed = false
    private var lastWallTime: Instant? = null
    private var fixtureConfigured = false
    private var signingHttp: (() -> SdkHttpClient)? = null
    private var putHttp: (() -> SdkHttpClient)? = null
    private var readbackHttp: (() -> SdkHttpClient)? = null
    private var ordinaryS3: (() -> SdkHttpClient)? = null
    private var ordinaryKms: (() -> SdkHttpClient)? = null
    private var clock: Clock = Clock.systemUTC()

    internal val runContext get() = checkNotNull(record).context()
    internal val cutoff: Long get() = checkNotNull(frozen).ordinaryEpoch
    internal val epoch: Long get() = checkNotNull(frozen).terminalEpoch

    init {
        requireConnectionFree(); requireTestTerminalCatalog((predecessor == null) != (recovery == null))
        process.requireRegistrationTarget(); acquisition.requireRetained(routing, process.publicationLanes)
        ordinaryAuthority.requireJournal(routing.journalConfiguration); terminalAuthority.requireJournal(routing.journalConfiguration)
        if (predecessor != null) {
            requireTestTerminalCatalog(predecessor.registration.process === process && predecessor.routing === routing && predecessor.acquisition === acquisition)
            predecessor.retainCatalogChild(this)
        } else checkNotNull(recovery).retainOriginal(this)
    }

    /** Changes only raw HTTP/wall-time fixtures on this ALREADY genuine, unstarted original. */
    internal fun withHttpFixtures(signing: () -> SdkHttpClient, put: () -> SdkHttpClient, readback: () -> SdkHttpClient,
        ordinaryS3: () -> SdkHttpClient, ordinaryKms: () -> SdkHttpClient, clock: Clock): CatalogTestRunTerminalV1 {
        requireConnectionFree(); requireTestTerminalCatalog(caller === Thread.currentThread() && !started && !closed && !fixtureConfigured)
        fixtureConfigured = true; signingHttp = signing; putHttp = put; readbackHttp = readback
        this.ordinaryS3 = ordinaryS3; this.ordinaryKms = ordinaryKms; this.clock = clock
        return this
    }

    fun publish(unsignedCanonicalBytes: ByteArray, request: CatalogTestRunTerminalRequestV1): CatalogTestRunTerminalResultV1 {
        requireTestTerminalCatalog(predecessor != null && recovery == null)
        return run(unsignedCanonicalBytes, request)
    }

    internal fun resumePrepared(original: CatalogTestRunTerminalPreparedRecoveryV1, request: CatalogTestRunTerminalRequestV1): CatalogTestRunTerminalResultV1 {
        requireTestTerminalCatalog(recovery === original && predecessor == null); original.requireOriginal(this)
        return run(null, request)
    }

    /** Historical successful-original handoff only; no E lease, budget, reader or provider authority is transferred. */
    internal fun beginErasure(deletionOwner: PersistencePhaseOwnership, deletionJdbc: JdbcTemplate): TestRunErasureV1 {
        requireActualCleanup(); requireCompletedProjection(); requireProjectedReload()
        requireTestTerminalCatalog(!erasureClaimed && erasureChild == null)
        erasureClaimed = true // Spent BEFORE construction; a failed constructor cannot be retried.
        val child = TestRunErasureV1.begin(this, deletionOwner, deletionJdbc) // Construction only; no IO or admission.
        erasureChild = child // Retain exact identity BEFORE exposing it or permitting the child's first work.
        return child
    }

    /** May run under the child's own SQL holder: inspect only this original's already-closed historical state. */
    internal fun requireErasureChild(candidate: TestRunErasureV1) {
        requireCompletedProjection()
        requireTestTerminalCatalog(erasureClaimed && erasureChild === candidate)
    }

    /** The child separately authenticates current raw dual history; these are comparisons, never exported authority. */
    internal fun requireErasureLineage(child: TestRunErasureV1, context: TestTerminalRunContextV1,
        terminalCatalogGeneration: Long, terminalCatalogSha256: String) {
        requireActualCleanup(); requireErasureChild(child); requireProjectedReload()
        requireTestTerminalCatalog(context == runContext && terminalCatalogGeneration == checkNotNull(frozen).generation &&
            terminalCatalogSha256 == checkNotNull(signed).envelopeSha256)
    }

    private fun run(unsigned: ByteArray?, request: CatalogTestRunTerminalRequestV1): CatalogTestRunTerminalResultV1 {
        requireConnectionFree(); requireTestTerminalCatalog(caller === Thread.currentThread() && !started && !closed)
        started = true
        var result: CatalogTestRunTerminalResultV1? = null
        try {
            requireRunning(); coordinator.catalogRefreshCustody.reserveTestTerminal(this); reserved = true
            native = CatalogTestRunTerminalAssemblyV1(this, signingHttp, putHttp, readbackHttp, clock)
            if (recovery == null) freeze(checkNotNull(unsigned))
            executeCatalog(CatalogTestRunTerminalKindV1.CAPTURE)
            if (recovery != null) freeze(checkNotNull(checkNotNull(snapshot).terminal).unsignedBytes())
            requireTestTerminalCatalog(checkNotNull(frozen).token == selectedToken())
            checkNotNull(snapshot).run.requireFrozen(checkNotNull(frozen))
            checkNotNull(snapshot).activeHistory.requireFrozen(checkNotNull(frozen))
            checkNotNull(snapshot).terminal?.requireFrozen(checkNotNull(frozen))
            requireInitialLineage()
            stage = Stage.EVIDENCE
            ordinary = ordinaryAuthority.readmitCatalog(this, request.ordinaryApproval, request.ordinaryRawEvidence)
            terminal = terminalAuthority.readmitCatalog(this, request.terminalApproval, request.terminalRawEvidence)
            current()
            recheckInventories(request.ordinaryReadCredentials)
            current()
            if (recovery != null) openCustody(request.custodyRoot)
            observe(request) // Full V1/2 -> V3 and both successor listings BEFORE PREPARE/Sign.
            if (recovery == null) requireTestTerminalCatalog(checkNotNull(latestProof).state === CatalogTestRunTerminalDeliveryReadbackV1.State.UNPUBLISHED)
            current()
            result = if (snapshot?.terminal?.projectedAt != null) confirmProjectedReplay() else advancePrepared(request)
            requireRunning()
        } catch (problem: Throwable) { observeFailure(problem) }
        finally { runCatching(::close).exceptionOrNull()?.let(::observeFailure) }
        throwIfSignalled(); requireActualCleanup()
        requireTestTerminalCatalog(released && (leaseReleased || projectedReplay) && result != null)
        val completed = checkNotNull(result)
        if (completed === CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED) requireProjectedReload()
        completedResult = completed // Only AFTER every actual native/file/JDBC/custody/lease cleanup succeeded.
        return completed
    }

    /** An already projected exact row is confirmed read-only, including the global control's xmin. */
    private fun confirmProjectedReplay(): CatalogTestRunTerminalResultV1 {
        requireTestTerminalCatalog(recovery != null && lease == null && leaseOwner == null && currentCheckedSnapshot === snapshot &&
            latestProof?.state === CatalogTestRunTerminalDeliveryReadbackV1.State.DUAL_COPY && snapshot?.terminal?.projectedAt != null)
        dualProof = latestProof; dualSnapshot = snapshot
        closeNative()
        executeCatalog(CatalogTestRunTerminalKindV1.RELOAD)
        val final = checkNotNull(snapshot); val row = checkNotNull(final.terminal)
        requireDualSource(); checkNotNull(dualProof).requireSource(final, checkNotNull(frozen), checkNotNull(signed))
        row.requireSigned(checkNotNull(signed)); row.requireDual(checkNotNull(dualProof))
        final.run.requireProjected(checkNotNull(frozen), checkNotNull(signed), checkNotNull(row.projectedAt))
        checkNotNull(release).requireSnapshot(final)
        projectedReload = final // Comparison material only until run() has returned through actual cleanup.
        projectedReplay = true
        return CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED
    }

    private fun advancePrepared(request: CatalogTestRunTerminalRequestV1): CatalogTestRunTerminalResultV1 {
        leaseOwner = UUID.randomUUID(); leaseStartedAt = ownership.nanoClock.nanoTime()
        executeCatalog(CatalogTestRunTerminalKindV1.ACQUIRE)
        if (recovery == null) openCustody(request.custodyRoot)
        // Historical arms are compared before this original adds ANY new arm.
        checkNotNull(release).requireAcquisition(checkNotNull(lease))
        if (recovery == null) {
            stage = Stage.PREPARE_ARM; checkNotNull(release).armPrepare(); prepareArmed = true
            executeCatalog(CatalogTestRunTerminalKindV1.PREPARE)
            checkNotNull(release).requireSnapshot(checkNotNull(snapshot))
        }
        current() // Independently released full current read immediately before the Sign decision.
        stage = Stage.SIGN_ARM
        signArmed = checkNotNull(release).armSign()
        if (signArmed) {
            stage = Stage.SIGN; checkNotNull(release).consumeSign()
            val bytes = checkNotNull(native).sign(checkNotNull(frozen), request.signingCredentials)
            try {
                signReturned = true; stage = Stage.SIGN_RETURN
                signed = checkNotNull(release).preserveSignature(bytes)
            } finally { bytes.fill(0) }
        } else {
            signed = checkNotNull(checkNotNull(release).availableSignature())
            stage = Stage.SIGN_RETURN; checkNotNull(release).preserveRecoveredSignature(checkNotNull(signed))
        }
        stage = Stage.SIGNATURE_ARM; checkNotNull(release).armSignaturePersistence(); signatureSqlArmed = true
        executeCatalog(CatalogTestRunTerminalKindV1.SIGNATURE)
        executeCatalog(CatalogTestRunTerminalKindV1.RELOAD) // Exact released signature/wire SQL BEFORE any PUT.
        checkNotNull(release).requireSnapshot(checkNotNull(snapshot))
        current(); observe(request)
        if (checkNotNull(latestProof).state === CatalogTestRunTerminalDeliveryReadbackV1.State.UNPUBLISHED) {
            stage = Stage.PUBLICATION_ARM
            publicationArmed = checkNotNull(release).armPublication(checkNotNull(latestProof))
            if (publicationArmed) {
                stage = Stage.PUT; checkNotNull(release).consumePublication()
                acknowledgement = checkNotNull(native).put(checkNotNull(frozen), checkNotNull(signed), request.primaryPutCredentials)
                stage = Stage.ACKNOWLEDGEMENT; checkNotNull(release).acknowledge(checkNotNull(acknowledgement))
                observe(request)
            }
        }
        val result = if (checkNotNull(latestProof).state === CatalogTestRunTerminalDeliveryReadbackV1.State.DUAL_COPY) {
            dualProof = latestProof; dualSnapshot = snapshot
            current()
            stage = Stage.DUAL; checkNotNull(release).preserveDual(checkNotNull(dualProof))
            closeNative()
            stage = Stage.COMPLETE_ARM; checkNotNull(release).armComplete(checkNotNull(dualProof)); completeArmed = true
            executeCatalog(CatalogTestRunTerminalKindV1.COMPLETE); completeSnapshot = snapshot
            current()
            stage = Stage.PROJECT_ARM; checkNotNull(release).armProject(checkNotNull(dualProof)); projectArmed = true
            executeCatalog(CatalogTestRunTerminalKindV1.PROJECT)
            executeCatalog(CatalogTestRunTerminalKindV1.RELOAD)
            checkNotNull(release).requireSnapshot(checkNotNull(snapshot))
            val final = checkNotNull(snapshot)
            final.run.requireProjected(checkNotNull(frozen), checkNotNull(signed), checkNotNull(final.terminal?.projectedAt))
            projectedReload = final // The actual final RELOAD, not the PROJECT operation's returned row.
            CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED
        } else {
            closeNative()
            CatalogTestRunTerminalResultV1.EXACT_PREPARED_AWAITING_DUAL_COPY
        }
        executeCatalog(CatalogTestRunTerminalKindV1.RELEASE); leaseReleased = true
        requireRunning()
        return result
    }

    private fun freeze(bytes: ByteArray) {
        stage = Stage.FREEZE
        val value = CatalogTestRunTerminalFrozenV1.capture(this, bytes)
        if (recovery == null) token = value.token else requireTestTerminalCatalog(value.token == recovery.token)
        frozen = value; record = value.manifest().terminalRecord
        requireTestTerminalCatalog(value.scope == scope && value.writer.toString() == writer && value.targets.size.toLong() <=
            routing.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions)
    }

    private fun current(): CatalogTestRunTerminalPreflightOperationV1 {
        val result = executePreflight(CatalogTestRunTerminalPreflightKindV1.CURRENT)
        currentCheckedSnapshot = snapshot
        return result
    }

    private fun executePreflight(kind: CatalogTestRunTerminalPreflightKindV1): CatalogTestRunTerminalPreflightOperationV1 {
        requireConnectionFree(); requireRunning(); requireNoPhase()
        requireTestTerminalCatalog(selectedPreflight == null && selectedPhase == null && ordinary != null && terminal != null)
        selectedPreflight = kind
        var operation: CatalogTestRunTerminalPreflightOperationV1? = null
        try {
            val result = coordinator.testRunTerminalCatalogPreflight.execute(this).also { operation = it }
            val value = result.precondition(); value.requireOwner(this)
            precondition?.requireSame(value) ?: run { precondition = value }
            requireRunning(); return result
        } catch (problem: Throwable) { operation?.discardRow(); throw problem }
        finally { selectedPreflight = null }
    }

    private fun executeCatalog(kind: CatalogTestRunTerminalKindV1) {
        requireConnectionFree(); requireRunning(); requireNoPhase()
        requireTestTerminalCatalog(selectedPhase == null && selectedPreflight == null)
        if (kind !== CatalogTestRunTerminalKindV1.CAPTURE) requireRetiredInventories()
        selecting = kind; selectionClaimed = false
        val input = try { CatalogTestRunTerminalPhaseV1.select(this, kind, if (kind === CatalogTestRunTerminalKindV1.CAPTURE) null else snapshot,
            frozen, signed, if (kind in setOf(CatalogTestRunTerminalKindV1.COMPLETE, CatalogTestRunTerminalKindV1.PROJECT)) dualProof else null,
            if (kind === CatalogTestRunTerminalKindV1.ACQUIRE) null else lease, if (kind === CatalogTestRunTerminalKindV1.ACQUIRE) leaseOwner else null) }
        finally { selecting = null }
        selectedPhase = input
        try {
            val operation = coordinator.testRunTerminalCatalog.execute(input)
            when (kind) {
                CatalogTestRunTerminalKindV1.ACQUIRE -> lease = operation.lease()
                CatalogTestRunTerminalKindV1.RELEASE -> Unit
                else -> { snapshot = operation.snapshot(); currentCheckedSnapshot = null }
            }
            requireRunning()
        } finally { selectedPhase = null }
    }

    private fun openCustody(root: Path) {
        stage = Stage.CUSTODY
        val input = checkNotNull(frozen)
        val binding: ByteArray
        val allocation: ByteArray
        val created: Boolean
        if (recovery == null) {
            binding = CatalogTestRunTerminalReleaseV1.binding(this, input)
            allocation = CatalogTestRunTerminalReleaseV1.allocation(input, this, binding)
            files = CatalogTestRunActivationReleaseCustodyV1.retainTerminal(this, root, allocation)
            created = checkNotNull(files).open() === CatalogTestRunActivationCustodyObservationV1.CREATED
            requireTestTerminalCatalog(created) // An initial child never takes over an older allocation.
        } else {
            files = CatalogTestRunActivationReleaseCustodyV1.retainExistingTerminal(this, root)
            allocation = checkNotNull(files).discoverExisting()
            binding = checkNotNull(checkNotNull(files).read(CatalogTestRunActivationReleaseLeafV1.BINDING))
            created = false
        }
        release = CatalogTestRunTerminalReleaseV1.open(this, input, checkNotNull(files), allocation, binding, created)
        signed = checkNotNull(release).availableSignature()
    }

    private fun observe(request: CatalogTestRunTerminalRequestV1) {
        stage = Stage.OBSERVATION
        val proof = checkNotNull(native).observe(checkNotNull(snapshot), checkNotNull(frozen), signed,
            request.primaryReadCredentials, request.replicaReadCredentials)
        latestProof = proof
        proof.requireSource(checkNotNull(snapshot), checkNotNull(frozen), signed)
        release?.requireObservation(proof)
    }

    private fun closeNative() {
        stage = Stage.CLOSE_NATIVE
        checkNotNull(native).close(); checkNotNull(native).requireCleanup(); nativeClosed = true
        dualProof?.let { checkNotNull(native).requireClosedObservation(it) }
    }

    private fun recheckInventories(credentials: AwsSessionCredentials) {
        stage = Stage.ORDINARY_INVENTORY
        waitUntil(checkNotNull(ordinary).firstStartAfter())
        ordinaryReader = TestOrdinaryInventoryReaderV1.beginCatalog(this, credentials, ordinaryS3, ordinaryKms, clock, acquisition.nanoTime)
        checkNotNull(ordinaryReader).scanPass(1)
        val a = checkNotNull(ordinary).statement
        waitUntil(nextPass(checkNotNull(ordinaryWitnesses[0]), a.acceptedRequestBoundSeconds, a.utcUncertaintySeconds))
        checkNotNull(ordinaryReader).scanPass(2)
        checkNotNull(ordinaryReader).close(); checkNotNull(ordinaryReader).requireRetiredCatalogPair(this); ordinaryRetired = true
        stage = Stage.TERMINAL_INVENTORY
        waitUntil(checkNotNull(terminal).firstStartAfter())
        terminalReader = TestTerminalInventoryReaderV1.beginCatalog(this)
        checkNotNull(terminalReader).scanPass(1)
        val b = checkNotNull(terminal).statement
        waitUntil(nextPass(checkNotNull(terminalWitnesses[0]), b.acceptedRequestBoundSeconds, b.utcUncertaintySeconds))
        checkNotNull(terminalReader).scanPass(2)
        checkNotNull(terminalReader).close(); checkNotNull(terminalReader).requireRetiredCatalogPair(this); terminalRetired = true
        requireRetiredInventories()
    }

    internal fun requireOrdinaryInventoryStart() {
        requireConnectionFree(); requireRunning(); checkNotNull(ordinary).requireOriginal(this)
        requireTestTerminalCatalog(stage === Stage.ORDINARY_INVENTORY && precondition != null && ordinaryReader == null && !ordinaryStartClaimed)
        ordinaryStartClaimed = true
    }
    internal fun requireTerminalInventoryStart() {
        requireConnectionFree(); requireRunning(); checkNotNull(terminal).requireOriginal(this)
        requireTestTerminalCatalog(stage === Stage.TERMINAL_INVENTORY && ordinaryRetired && terminalReader == null && !terminalStartClaimed)
        terminalStartClaimed = true
    }
    internal fun requireOrdinaryInventoryReader(value: TestOrdinaryInventoryReaderV1) {
        requireConnectionFree(); requireRunning(); requireNoPhase(); checkNotNull(ordinary).requireOriginal(this)
        requireTestTerminalCatalog(stage === Stage.ORDINARY_INVENTORY && ordinaryReader === value && !ordinaryRetired)
        requireEvidenceTime(sampleWallTime())
    }
    internal fun requireTerminalInventoryReader(value: TestTerminalInventoryReaderV1) {
        requireConnectionFree(); requireRunning(); requireNoPhase(); checkNotNull(terminal).requireOriginal(this)
        requireTestTerminalCatalog(stage === Stage.TERMINAL_INVENTORY && terminalReader === value && !terminalRetired)
        requireEvidenceTime(sampleWallTime())
    }
    internal fun beginOrdinaryInventoryPass(reader: TestOrdinaryInventoryReaderV1, pass: Int, at: Instant) {
        requireOrdinaryInventoryReader(reader)
        val a = checkNotNull(ordinary)
        val after = if (pass == 1) a.firstStartAfter() else nextPass(checkNotNull(ordinaryWitnesses[0]), a.statement.acceptedRequestBoundSeconds, a.statement.utcUncertaintySeconds)
        requireTestTerminalCatalog(pass in 1..2 && pass == ordinaryPass + 1 && at >= after && at <= sampleWallTime() &&
            at.epochSecond >= checkNotNull(record).progress.completedCuts()[0].denial.secondInventory.completedAtEpochSecond)
        ordinaryPass = pass; ordinaryStartedAt = at
    }
    internal fun beginTerminalInventoryPass(reader: TestTerminalInventoryReaderV1, pass: Int, at: Instant) {
        requireTerminalInventoryReader(reader)
        val b = checkNotNull(terminal)
        val after = if (pass == 1) b.firstStartAfter() else nextPass(checkNotNull(terminalWitnesses[0]), b.statement.acceptedRequestBoundSeconds, b.statement.utcUncertaintySeconds)
        requireTestTerminalCatalog(pass in 1..2 && pass == terminalPass + 1 && at >= after && at <= sampleWallTime() &&
            at.epochSecond >= checkNotNull(record).progress.completedCuts()[1].denial.secondInventory.completedAtEpochSecond)
        terminalPass = pass; terminalStartedAt = at
    }
    internal fun requireOrdinaryInventoryEvent(event: TestOwnerDeleteJournalEventV1) {
        requireOrdinaryInventoryReader(checkNotNull(ordinaryReader))
        requireTestTerminalCatalog(event.belongsTo(routing))
        TestOrdinaryDrainPersistenceV1.FamilyFacts(routing, cutoff).requireEvent(event)
    }
    internal fun stageOrdinaryInventoryVersion(reader: TestOrdinaryInventoryReaderV1, pass: Int, value: TestOrdinaryInventoryReadbackV1) {
        requireOrdinaryInventoryReader(reader); value.requireCatalog(this) // Actual observation before entering SQL, never inside a mapper.
        requireTestTerminalCatalog(pass == ordinaryPass && ordinaryReadback == null && terminalTarget == null)
        ordinaryReadback = value
        try { executePreflight(CatalogTestRunTerminalPreflightKindV1.ORDINARY_VERSION) }
        finally { ordinaryReadback = null }
    }
    internal fun expectedTerminalRow(reader: TestTerminalInventoryReaderV1, key: String, version: String): TestTerminalDurableRowV1 {
        requireTerminalInventoryReader(reader); requireTestTerminalCatalog(terminalTarget == null && ordinaryReadback == null)
        terminalTarget = terminalTargets().single { it.objectRef.objectKey == key && it.objectRef.objectVersion == version }
        try { return executePreflight(CatalogTestRunTerminalPreflightKindV1.TERMINAL_ROW).takeRow() }
        finally { terminalTarget = null }
    }
    internal fun stageTerminalInventoryVersion(reader: TestTerminalInventoryReaderV1, pass: Int, value: TestTerminalInventoryReadbackV1) {
        requireTerminalInventoryReader(reader); value.requireCatalog(this)
        requireTestTerminalCatalog(pass == terminalPass && terminalTargets().any { it.objectRef == value.entry.objectRef && it.kind == value.entry.kind })
        checkNotNull(snapshot).activeHistory.requireNative(value.entry)
    }
    internal fun completeOrdinaryInventoryPass(reader: TestOrdinaryInventoryReaderV1, pass: Int, at: Instant, count: Long, ciphertextBytes: Long) {
        requireOrdinaryInventoryReader(reader); reader.requireCompletedCatalogPass(this, pass)
        requireTestTerminalCatalog(pass == ordinaryPass && ordinaryWitnesses[pass - 1] == null && at >= checkNotNull(ordinaryStartedAt) && at <= sampleWallTime())
        val entries = reader.catalogComparisonEntries(this, pass)
        checkNotNull(precondition).requireOrdinaryInventory(entries)
        val hash = MessageDigest.getInstance("SHA-256")
        var framed = EpochSealFramesV1.update(hash, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", writer,
            routing.journalConfiguration.ordinaryPrefix, "TEST", scope.toString(), "1", cutoff.toString(), count.toString()))
        entries.forEach { framed = Math.addExact(framed, EpochSealFramesV1.update(hash, it.fields())) }
        val cut = checkNotNull(record).progress.completedCuts()[0]
        val root = TestTerminalFramesV1.finish(hash)
        requireTestTerminalCatalog(framed == cut.framedByteCount && framed <= routing.journalConfiguration.declaration().limits.capacity.maximumScanStagingBytes)
        requireWitness(cut.denial.firstInventory, count, ciphertextBytes, root); requireWitness(cut.denial.secondInventory, count, ciphertextBytes, root)
        ordinaryWitnesses[pass - 1] = TestTerminalInventoryWitnessV1(checkNotNull(ordinaryStartedAt).epochSecond,
            OrdinaryJournalRetentionV1.ceilingSecond(at).epochSecond, count, ciphertextBytes, root)
    }
    internal fun completeTerminalInventoryPass(reader: TestTerminalInventoryReaderV1, pass: Int, at: Instant, value: TestPostTerminalInventoryFoldV1.Summary) {
        requireTerminalInventoryReader(reader); reader.requireCompletedCatalogPass(this, pass)
        requireTestTerminalCatalog(pass == terminalPass && terminalWitnesses[pass - 1] == null && at >= checkNotNull(terminalStartedAt) && at <= sampleWallTime())
        val cut = checkNotNull(record).progress.completedCuts()[1]
        requireTestTerminalCatalog(value.versionCount == terminalTargets().size.toLong() && value.framedByteCount == cut.framedByteCount &&
            value.entryFramedByteCount in 1..value.framedByteCount)
        requireWitness(cut.denial.firstInventory, value.versionCount, value.ciphertextByteCount, value.sha256)
        requireWitness(cut.denial.secondInventory, value.versionCount, value.ciphertextByteCount, value.sha256)
        terminalWitnesses[pass - 1] = TestTerminalInventoryWitnessV1(checkNotNull(terminalStartedAt).epochSecond,
            OrdinaryJournalRetentionV1.ceilingSecond(at).epochSecond, value.versionCount, value.ciphertextByteCount, value.sha256)
    }
    private fun requireRetiredInventories() {
        requireConnectionFree(); requireRunning(); requireTestTerminalCatalog(ordinaryRetired && terminalRetired)
        checkNotNull(ordinaryReader).requireRetiredCatalogPair(this); checkNotNull(terminalReader).requireRetiredCatalogPair(this)
    }
    internal fun terminalTargets(): List<TestTerminalQuiescenceTargetV1> { requireRunning(); return checkNotNull(frozen).targets.toList() }

    internal fun requireOrdinaryAuthority(value: TestOrdinaryDenialAuthorityPolicyV1) {
        requireRunning(); requireTestTerminalCatalog(value === ordinaryAuthority && process.ordinaryDenial === value && frozen != null && snapshot != null)
    }
    internal fun requireTerminalAuthority(value: TestTerminalDenialAuthorityPolicyV1) {
        requireRunning(); requireTestTerminalCatalog(value === terminalAuthority && process.terminalDenial === value && frozen != null && snapshot != null)
    }
    internal fun requireOrdinaryDenialContext(value: TestOrdinaryDenialStatementV1, evidence: TestTerminalEvidenceDigestV1) {
        requireConnectionFree(); requireOrdinaryAuthority(ordinaryAuthority)
        val journal = routing.journalConfiguration; val declaration = journal.declaration(); val role = declaration.authorities.ordinary
        requireTestTerminalCatalog(stage === Stage.EVIDENCE && ordinary == null && value.dataScopeId == runContext.dataScopeId &&
            value.activationCatalogGeneration == runContext.activationCatalogGeneration && value.activationCatalogSha256 == runContext.activationCatalogSha256 &&
            value.configurationSha256 == runContext.configurationSha256 && value.terminalEncodingSha256 == runContext.terminalEncodingSha256 &&
            value.initialWriterRegistrySha256 == process.catalogActivation.initialWriterRegistrySha256 && value.writerGeneration == writer &&
            value.databaseIdentity == process.databaseIdentity.toString() && value.restoreIdentity == process.restoreIdentity.toString() &&
            value.epochStartInclusive == 1L && value.epochEndInclusive == cutoff && value.ordinaryPrefix == journal.ordinaryPrefix &&
            value.bucket == declaration.journalLocation.bucket && value.accountId == declaration.journalLocation.accountId && value.region == declaration.journalLocation.region &&
            value.roleId == role.roleId && value.policy.policyId == role.policy.policyId && value.policy.version == role.policy.version && value.policy.sha256 == role.policy.sha256 &&
            value.evidenceRetainUntilEpochSecond > sampleWallTime().epochSecond)
        val cut = checkNotNull(record).progress.completedCuts()[0].denial
        requireTestTerminalCatalog(cut.roleId == value.roleId && cut.policy == value.policy && cut.denialEffectiveAtEpochSecond == value.denialEffectiveAtEpochSecond &&
            cut.lastSessionExpiryEpochSecond == value.lastSessionExpiryEpochSecond && cut.acceptedRequestBoundSeconds == value.acceptedRequestBoundSeconds &&
            cut.policyEvidence == evidence && cut.boundEvidence == value.boundEvidence)
    }
    internal fun requireTerminalDenialContext(value: TestTerminalDenialStatementV1, evidence: TestTerminalEvidenceDigestV1) {
        requireConnectionFree(); requireTerminalAuthority(terminalAuthority)
        val journal = routing.journalConfiguration; val declaration = journal.declaration(); val role = declaration.authorities.sealTerminal
        val record = checkNotNull(record)
        requireTestTerminalCatalog(stage === Stage.EVIDENCE && terminal == null && ordinary != null && value.dataScopeId == runContext.dataScopeId &&
            value.activationCatalogGeneration == runContext.activationCatalogGeneration && value.activationCatalogSha256 == runContext.activationCatalogSha256 &&
            value.configurationSha256 == runContext.configurationSha256 && value.terminalEncodingSha256 == runContext.terminalEncodingSha256 &&
            value.initialWriterRegistrySha256 == process.catalogActivation.initialWriterRegistrySha256 && value.writerGeneration == writer &&
            value.databaseIdentity == process.databaseIdentity.toString() && value.restoreIdentity == process.restoreIdentity.toString() &&
            value.epochStartInclusive == 1L && value.epochEndInclusive == epoch && value.sealTerminalPrefix == journal.sealTerminalPrefix &&
            value.bucket == declaration.journalLocation.bucket && value.accountId == declaration.journalLocation.accountId && value.region == declaration.journalLocation.region &&
            value.sealedCandidate.completedTerminalSeal == record.sealSet.records().last() &&
            value.sealedCandidate.completeSealSetSha256 == Sha256.hex(checkNotNull(frozen).sealSetBytes()) && value.sealedCandidate.purge == record.purge.objectRef &&
            value.roleId == role.roleId && value.policy.policyId == role.policy.policyId && value.policy.version == role.policy.version && value.policy.sha256 == role.policy.sha256 &&
            value.evidenceRetainUntilEpochSecond > sampleWallTime().epochSecond)
        val cut = record.progress.completedCuts()[1].denial
        requireTestTerminalCatalog(cut.roleId == value.roleId && cut.policy == value.policy && cut.denialEffectiveAtEpochSecond == value.denialEffectiveAtEpochSecond &&
            cut.lastSessionExpiryEpochSecond == value.lastSessionExpiryEpochSecond && cut.acceptedRequestBoundSeconds == value.acceptedRequestBoundSeconds &&
            cut.policyEvidence == evidence && cut.boundEvidence == value.boundEvidence)
    }

    private fun requireInitialLineage() {
        requireConnectionFree(); val d = predecessor ?: return
        d.requireCatalogChild(this)
        val value = checkNotNull(frozen); val record = checkNotNull(record); val json = TestTerminalJsonV1(routing.journalConfiguration)
        requireTestTerminalCatalog(value.progressBytes().contentEquals(json.encodeProgress(d.authenticatedProgress())) &&
            value.sealSetBytes().contentEquals(json.encodeSealSet(d.fullSealSet)) && value.targets == d.targets &&
            runContext == d.runContext && cutoff == d.control.cutoff && epoch == d.epoch && record.installationManifest.summary == d.manifest.authenticatedSummary())
        val chunks = d.manifest.capturedSource()
        requireTestTerminalCatalog(record.installationManifest.chunks.size == chunks.count)
        record.installationManifest.chunks.forEachIndexed { index, chunk ->
            val descriptor = chunks.chunk(index); val actual = d.manifest.authenticatedFacts(index)
            requireTestTerminalCatalog(chunk.chunkIndex == descriptor.ordinal && chunk.eventId == actual.id && chunk.objectRef == actual.objectRef &&
                chunk.installationCount == descriptor.count.toLong() && chunk.entriesSha256 == descriptor.entriesSha256)
            initialPublications[actual.id] = PublicationFacts(actual.id, actual.objectRef, actual.createdAt, actual.lastModified, actual.retainUntil, actual.verifiedAt, actual.verificationSha256)
        }
        val actual = d.purge.authenticatedFactsForSeal(); val roots = d.purge.capturedRoots()
        initialPublications[actual.id] = PublicationFacts(actual.id, actual.objectRef, actual.createdAt, actual.lastModified, actual.retainUntil, actual.verifiedAt, actual.verificationSha256)
        initialPurgeBinding = d.purge.frozenRow().binding // Immutable scalar binding, never read a closed row's bytes.
        val purge = TestTerminalPurgeV1.create(TestTerminalEventContextV1(runContext, actual.id, epoch, writer), cutoff, d.ordinarySeal,
            roots.seals, roots.inventory, d.manifest.authenticatedSummary())
        requireTestTerminalCatalog(record.purge.objectRef == actual.objectRef && value.purgeBytes().contentEquals(json.encodePurge(purge)))
    }

    internal fun requirePredecessorControl(operation: CatalogTestRunTerminalPreflightOperationV1, row: ResultSet) {
        requirePreflightOperation(operation)
        val fields = listOf(row.getLong("publication_epoch"), row.getLong("lease_token"), row.getLong("rotation_sequence"),
            row.getObject("rotation_id", UUID::class.java), row.getLong("rotation_epoch_before"), row.getLong("rotation_capture_token"),
            row.getTimestamp("rotation_captured_at")?.toInstant(), TestOrdinaryDrainRowsV1.hash(row, "history_hash"))
        predecessor?.let { d -> requireTestTerminalCatalog(fields == listOf(epoch + 1L, d.leaseToken, d.control.sequence,
            d.control.captureId, d.control.cutoff, d.control.captureFence, d.control.capturedAt, d.control.historyHash)) }
        predecessorControl?.let { requireTestTerminalCatalog(it == fields) } ?: run { predecessorControl = fields }
    }
    internal fun requirePredecessorSidecar(operation: CatalogTestRunTerminalPreflightOperationV1, target: TestTerminalQuiescenceTargetV1,
        binding: TestTerminalDurableBindingV1) {
        requirePreflightOperation(operation)
        requireTestTerminalCatalog(target in checkNotNull(frozen).targets && target.kind.name == binding.objectKind.name &&
            target.ordinal == binding.objectOrdinal && target.id == binding.objectId && target.objectRef.objectKey == binding.objectKey)
        predecessor?.let { d ->
            if (target.source === TestTerminalQuiescenceSourceV1.V26_ACTIVE_SEAL) {
                val history = checkNotNull(d.control.initialHistory)
                requireTestTerminalCatalog(binding.objectKind === TestTerminalDurableKindV1.EPOCH_SEAL && binding.objectOrdinal == 0 &&
                    binding.operationToken == history.operationToken.toString() && target.objectRef == history.reference.objectRef &&
                    binding.objectId == history.reference.sealId && binding.epochStartInclusive == 1L && binding.epochEndInclusive == 1L &&
                    binding.createdAt <= history.checkpointCompletedAt)
            } else when (binding.objectKind) {
                TestTerminalDurableKindV1.INSTALLATION_MANIFEST -> requireTestTerminalCatalog(binding.preparingFencingToken in (d.drain.leaseToken + 1)..d.manifest.preparation.leaseToken &&
                    binding.createdAt == checkNotNull(initialPublications[binding.objectId]).createdAt)
                TestTerminalDurableKindV1.TEST_RUN_PURGE -> requireTestTerminalCatalog(binding == initialPurgeBinding)
                TestTerminalDurableKindV1.EPOCH_SEAL -> if (binding.objectOrdinal == 0) requireTestTerminalCatalog(binding.operationToken == d.control.captureId.toString() &&
                    binding.preparingFencingToken in d.control.captureFence..d.drain.leaseToken)
                else requireTestTerminalCatalog(binding.objectOrdinal == 1 && binding.operationToken == d.terminalSeal.intentId.toString() &&
                    binding.preparingFencingToken == d.terminalSeal.leaseToken)
            }
        }
        val key = Triple(target.source, binding.objectKind, binding.objectOrdinal)
        predecessorBindings[key]?.let { requireTestTerminalCatalog(it == binding) } ?: run { predecessorBindings[key] = binding }
    }

    /** Historical comparison only inside one of this original's fixed holders; never a new lease. */
    internal fun requireHistoryRead(jdbc: JdbcTemplate) {
        requireRunning()
        requireTestTerminalCatalog(phaseEntered && phase != null && (selectedPhase != null || selectedPreflight != null) &&
            (catalogJdbc === jdbc || preflightJdbc === jdbc))
    }
    internal fun requirePredecessorActiveHistory(jdbc: JdbcTemplate) {
        requireHistoryRead(jdbc)
        // Bind D's private A preimage on the initial CAPTURE, before E can move the global head.
        // Later reads compare that exact E snapshot; D's old activation-head predicate is not
        // reinterpreted as authority after E's COMPLETE has installed the terminal head.
        if (snapshot == null) predecessor?.let { TestOrdinaryDrainActiveHistoryV1.requireCurrent(jdbc, it.drain, it.control.initialHistory) }
    }
    internal fun requirePredecessorGlobal(generation: Long, hash: ByteArray?) {
        requireRunning(); requireTestTerminalCatalog(phaseEntered && phase != null)
        predecessor?.let { d ->
            val identity = TestActiveFirstCutIdentityV1.fromRegistration(d.registration)
            val expected = identity.globalConfigurationHash()
            try { requireTestTerminalCatalog(generation == identity.globalDesiredGeneration && hash.contentEquals(expected)) }
            finally { expected?.fill(0) }
        }
        // Fresh PREPARED recovery is comparison-only until its existing E custody binds the exact
        // captured global core (including B). It never invents an initial RELEASE registration.
    }
    internal fun requirePredecessorPublication(operation: CatalogTestRunTerminalPreflightOperationV1, id: String, ref: TestTerminalObjectRefV1,
        createdAt: Instant, objectCreatedAt: Instant, retainUntil: Instant, verifiedAt: Instant, verificationSha256: String) {
        requirePreflightOperation(operation)
        if (predecessor != null) requireTestTerminalCatalog(initialPublications[id] == PublicationFacts(id, ref, createdAt, objectCreatedAt, retainUntil, verifiedAt, verificationSha256))
    }

    internal fun selectedToken(): UUID = checkNotNull(token)
    internal fun requireFrozenCapture() {
        requireConnectionFree(); requireRunning(); requireTestTerminalCatalog(stage === Stage.FREEZE && frozen == null && !freezeClaimed &&
            (recovery == null && snapshot == null || recovery != null && snapshot?.terminal?.token == recovery.token))
        freezeClaimed = true
    }
    internal fun requirePhaseSelection(kind: CatalogTestRunTerminalKindV1) {
        requireConnectionFree(); requireRunning(); requireNoPhase()
        requireTestTerminalCatalog(selecting === kind && !selectionClaimed && selectedPhase == null && selectedPreflight == null)
        selectionClaimed = true
    }
    internal fun requireSelectedPhase(input: CatalogTestRunTerminalPhaseV1, owner: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requirePersistence(owner, jdbc, preflight = false)
        requireTestTerminalCatalog(selectedPhase === input && input.original === this && selectedPreflight == null)
    }
    internal fun requirePreflightPersistence(owner: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requirePersistence(owner, jdbc, preflight = true)
        requireTestTerminalCatalog(selectedPhase == null && selectedPreflight != null)
    }
    private fun requirePersistence(owner: PersistencePhaseOwnership, jdbc: JdbcTemplate, preflight: Boolean) {
        requireRunning(); requireTestTerminalCatalog(owner === ownership && jdbc.dataSource === source)
        if (preflight) { if (preflightJdbc == null) preflightJdbc = jdbc; requireTestTerminalCatalog(preflightJdbc === jdbc && catalogJdbc !== jdbc) }
        else { if (catalogJdbc == null) catalogJdbc = jdbc; requireTestTerminalCatalog(catalogJdbc === jdbc && preflightJdbc !== jdbc) }
    }
    internal fun requirePhaseEntry(owner: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireConnectionFree(); requireRunning(); requireNoPhase()
        requireTestTerminalCatalog(owner === ownership && path === (selectedPhase?.path ?: PersistencePhasePath.COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PREFLIGHT) &&
            (selectedPhase != null || selectedPreflight != null))
        requireEvidenceTime(sampleWallTime())
        if (selectedPhase?.kind in setOf(CatalogTestRunTerminalKindV1.COMPLETE, CatalogTestRunTerminalKindV1.PROJECT)) {
            requireTestTerminalCatalog(nativeClosed); checkNotNull(native).requireClosedObservation(checkNotNull(dualProof))
        }
        phaseEntered = true
    }
    internal fun retainPhase(value: PersistencePhaseContext) { requireRunning(); requireTestTerminalCatalog(phaseEntered && phase == null); phase = value }
    internal fun observePhaseCleanup(value: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== value || !value.testRunTerminalCatalogCleanupProven(this)) {
                cleanupUncertain = true; observeFailure(CatalogTestRunTerminalExceptionV1())
            } else {
                phase = null; phaseEntered = false
                if (value.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) { cleanupUncertain = true; observeFailure(CatalogTestRunTerminalExceptionV1()) }
            }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }
    internal fun authenticate(owner: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requirePersistence(owner, jdbc, selectedPreflight != null)
        requireTestTerminalCatalog(jdbc.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { row -> row.next() && row.getBoolean("authenticated") && !row.wasNull() && !row.next() }, username, username, database) == true)
        requirePersistence(owner, jdbc, selectedPreflight != null)
    }
    internal fun requireMaintenanceGate(owner: PersistencePhaseOwnership, path: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning(); requireTestTerminalCatalog(owner === ownership && phaseEntered && phase != null &&
            path === (selectedPhase?.path ?: PersistencePhasePath.COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PREFLIGHT))
        val captured = snapshot
        if (captured == null) {
            if (predecessor != null) predecessor.registration.requireSealingGate(gate)
            else requireTestTerminalCatalog(selectedPhase?.kind === CatalogTestRunTerminalKindV1.CAPTURE && gate.matchesTerminalCapture(selectedToken(), scope))
        } else {
            val activation = captured.activation
            requireTestTerminalCatalog(if (captured.terminal == null) gate.matchesProjected(activation.token, scope, activation.unsignedBytes(), activation.unsignedHash())
                else gate.matchesTerminalTuple(selectedToken(), scope, checkNotNull(frozen).unsignedBytes(), checkNotNull(frozen).unsignedHash(),
                    activation.token, activation.unsignedBytes(), activation.unsignedHash()))
        }
    }

    internal fun preflightKind(): CatalogTestRunTerminalPreflightKindV1 { requireRunning(); return checkNotNull(selectedPreflight) }
    internal fun preflightFrozen(): CatalogTestRunTerminalFrozenV1 { preflightKind(); return checkNotNull(frozen) }
    internal fun preflightSnapshot(): CatalogTestRunTerminalSnapshotV1 { preflightKind(); return checkNotNull(snapshot) }
    internal fun controlArguments(selectedScope: UUID): Array<Any?> = arrayOf(scope, process.desiredGeneration, process.implementationSchema,
        process.configurationHashBytes(), process.databaseIdentity, process.restoreIdentity, UUID.fromString(writer),
        UUID.fromString(process.catalogActivation.initialWriterRegistry().catalogWriter.generationId), terminalCatalogHex(process.catalogReadback.currentTrustBundleSha256),
        process.catalogReadback.chainPolicy.limits.maximumGenerations, selectedScope)
    internal fun runArguments(activation: CatalogTestRunTerminalMutationV1): Array<Any?> = arrayOf(scope, process.configurationHashBytes(),
        expectedDeclaration.activation.run().installationLimit, activation.generation, terminalCatalogHex(checkNotNull(activation.head).envelopeSha256),
        Timestamp.from(checkNotNull(checkNotNull(activation.completed).projectedAt)), OwnerDeleteRows.array(checkNotNull(snapshot).run.plan.originalUnusedReserve))
    internal fun preflightOrdinaryReadback(operation: CatalogTestRunTerminalPreflightOperationV1): TestOrdinaryInventoryReadbackV1? {
        requirePreflightOperation(operation)
        requireTestTerminalCatalog((selectedPreflight === CatalogTestRunTerminalPreflightKindV1.ORDINARY_VERSION) == (ordinaryReadback != null))
        return ordinaryReadback // Previously authenticated connection-free; identity only inside SQL.
    }
    internal fun preflightTerminalTarget(operation: CatalogTestRunTerminalPreflightOperationV1): TestTerminalQuiescenceTargetV1? {
        requirePreflightOperation(operation)
        requireTestTerminalCatalog((selectedPreflight === CatalogTestRunTerminalPreflightKindV1.TERMINAL_ROW) == (terminalTarget != null))
        return terminalTarget
    }
    private fun requirePreflightOperation(operation: CatalogTestRunTerminalPreflightOperationV1) {
        requireRunning(); requireTestTerminalCatalog(selectedPreflight != null && phaseEntered && operation.original === this && operation.belongsTo(checkNotNull(phase)))
    }
    internal fun requirePreflightSqlTime(operation: CatalogTestRunTerminalPreflightOperationV1, at: Instant) { requirePreflightOperation(operation); requireEvidenceTime(at) }
    internal fun requireCatalogSqlTime(operation: CatalogTestRunTerminalOperationV1, at: Instant) { requireCatalogOperation(operation); requireEvidenceTime(at) }
    internal fun requireCurrentCatalogSnapshot(operation: CatalogTestRunTerminalOperationV1, value: CatalogTestRunTerminalSnapshotV1) {
        requireCatalogOperation(operation)
        if (operation.input.kind === CatalogTestRunTerminalKindV1.CAPTURE) {
            requireTestTerminalCatalog(snapshot == null && (recovery != null) == (value.terminal != null))
            value.terminal?.let { requireTestTerminalCatalog(it.token == selectedToken()) }
        } else {
            requireTestTerminalCatalog(ordinaryRetired && terminalRetired && precondition != null)
            checkNotNull(snapshot).requireSame(value)
        }
    }
    private fun requireCatalogOperation(operation: CatalogTestRunTerminalOperationV1) {
        requireRunning(); requireTestTerminalCatalog(phaseEntered && selectedPhase === operation.input && operation.input.original === this && operation.belongsTo(checkNotNull(phase)))
    }
    internal fun requireCatalogDispatch(operation: CatalogTestRunTerminalOperationV1) {
        requireCatalogOperation(operation)
        requireTestTerminalCatalog(lease != null && !leaseReleased && ordinaryRetired && terminalRetired)
        when (operation.input.kind) {
            CatalogTestRunTerminalKindV1.PREPARE -> requireTestTerminalCatalog(prepareArmed && recovery == null && currentCheckedSnapshot === snapshot)
            CatalogTestRunTerminalKindV1.SIGNATURE -> requireTestTerminalCatalog(signatureSqlArmed && signed != null)
            CatalogTestRunTerminalKindV1.COMPLETE -> { requireTestTerminalCatalog(completeArmed && nativeClosed && currentCheckedSnapshot === snapshot); requireDualSource() }
            CatalogTestRunTerminalKindV1.PROJECT -> { requireTestTerminalCatalog(projectArmed && nativeClosed && currentCheckedSnapshot === snapshot); requireProjectionProof(operation, checkNotNull(dualProof)) }
            else -> throw CatalogTestRunTerminalExceptionV1()
        }
    }
    internal fun requireProjectionProof(operation: CatalogTestRunTerminalOperationV1, proof: CatalogTestRunTerminalDeliveryReadbackV1) {
        requireCatalogOperation(operation); requireDualSource()
        requireTestTerminalCatalog(operation.input.kind === CatalogTestRunTerminalKindV1.PROJECT && proof === dualProof && operation.input.expected === completeSnapshot && snapshot === completeSnapshot)
        val before = checkNotNull(dualSnapshot); val after = checkNotNull(completeSnapshot)
        before.requireCore(after); before.run.requireSame(after.run); before.counters.requireSame(after.counters)
        checkNotNull(after.terminal).requireCompletionTransition(checkNotNull(before.terminal))
    }
    private fun requireDualSource() {
        requireTestTerminalCatalog(nativeClosed && dualProof != null && dualProof === latestProof)
        checkNotNull(native).requireClosedObservation(checkNotNull(dualProof))
        checkNotNull(dualProof).requireSource(checkNotNull(dualSnapshot), checkNotNull(frozen), checkNotNull(signed))
    }

    internal fun custodySnapshot(): CatalogTestRunTerminalSnapshotV1 { requireConnectionFree(); requireRunning(); return checkNotNull(snapshot) }
    internal fun custodyLease(): CatalogTestRunActivationLeaseV1 { requireConnectionFree(); requireRunning(); requireTestTerminalCatalog(!leaseReleased); return checkNotNull(lease) }
    internal fun custodyPreconditionSha256(): String { requireConnectionFree(); requireRunning(); return checkNotNull(precondition).physicalSha256 }
    internal fun requireReleaseRetention(existing: Boolean) {
        requireConnectionFree(); requireRunning(); requireRetiredInventories()
        requireTestTerminalCatalog(stage === Stage.CUSTODY && files == null && !releaseRetentionClaimed && existing == (recovery != null) && (existing || lease != null))
        releaseRetentionClaimed = true
    }
    internal fun requireReleaseCapture(value: CatalogTestRunTerminalReleaseV1, custody: CatalogTestRunActivationReleaseCustodyV1, input: CatalogTestRunTerminalFrozenV1) {
        requireConnectionFree(); requireRunning()
        requireTestTerminalCatalog(stage === Stage.CUSTODY && files === custody && frozen === input && release == null && capturedRelease == null &&
            releaseRetentionClaimed && !releaseCaptureClaimed)
        // Retain the exact candidate BEFORE its constructor may fail; no constructor-return identity guess.
        capturedRelease = value; releaseCaptureClaimed = true
    }
    internal fun requireReleaseUse(value: CatalogTestRunTerminalReleaseV1) {
        requireConnectionFree(); requireRunning(); requireNoPhase()
        requireTestTerminalCatalog(release === value && capturedRelease === value && files != null && !leaseReleased)
    }
    internal fun requirePrepareArm(value: CatalogTestRunTerminalReleaseV1) {
        requireReleaseUse(value); requireTestTerminalCatalog(stage === Stage.PREPARE_ARM && !prepareArmed && recovery == null && lease != null && snapshot?.terminal == null &&
            currentCheckedSnapshot === snapshot && latestProof?.state === CatalogTestRunTerminalDeliveryReadbackV1.State.UNPUBLISHED)
    }
    internal fun requireSignArm(value: CatalogTestRunTerminalReleaseV1) {
        requireReleaseUse(value); requireTestTerminalCatalog(stage === Stage.SIGN_ARM && !signDispatched && snapshot?.terminal != null && currentCheckedSnapshot === snapshot)
    }
    internal fun requireSignConstruction(value: CatalogTestRunTerminalAssemblyV1, input: CatalogTestRunTerminalFrozenV1) {
        requireProviderRunning(); requireTestTerminalCatalog(stage === Stage.SIGN && native === value && frozen === input && signArmed && !signDispatched && signed == null)
        signDispatched = true
    }
    internal fun requireSignatureReturn(value: CatalogTestRunTerminalReleaseV1) {
        requireReleaseUse(value); requireTestTerminalCatalog(stage === Stage.SIGN_RETURN && signDispatched && signReturned && signed == null)
    }
    internal fun requireRecoveredSignature(value: CatalogTestRunTerminalReleaseV1, signature: CatalogTestRunTerminalSignedV1) {
        requireReleaseUse(value); requireTestTerminalCatalog(stage === Stage.SIGN_RETURN && recovery != null && !signDispatched && signature === signed && signature === value.availableSignature())
    }
    internal fun requireSignaturePersistenceArm(value: CatalogTestRunTerminalReleaseV1) {
        requireReleaseUse(value); requireTestTerminalCatalog(stage === Stage.SIGNATURE_ARM && !signatureSqlArmed && signed === value.availableSignature())
    }
    internal fun requireObservationConstruction(value: CatalogTestRunTerminalAssemblyV1, before: CatalogTestRunTerminalSnapshotV1,
        input: CatalogTestRunTerminalFrozenV1, signature: CatalogTestRunTerminalSignedV1?) {
        requireProviderRunning(); requireTestTerminalCatalog(stage === Stage.OBSERVATION && native === value && !nativeClosed && before === snapshot && frozen === input && signature === signed &&
            ordinaryRetired && terminalRetired && currentCheckedSnapshot === snapshot)
    }
    internal fun requireRawObservation(before: CatalogTestRunTerminalSnapshotV1, input: CatalogTestRunTerminalFrozenV1, signature: CatalogTestRunTerminalSignedV1?) {
        requireObservationConstruction(checkNotNull(native), before, input, signature)
    }
    internal fun requireReleaseObservation(value: CatalogTestRunTerminalReleaseV1, proof: CatalogTestRunTerminalDeliveryReadbackV1) {
        requireReleaseUse(value); requireTestTerminalCatalog(stage === Stage.OBSERVATION && latestProof === proof)
        checkNotNull(native).requireObservation(proof); proof.requireSource(checkNotNull(snapshot), checkNotNull(frozen), signed)
    }
    internal fun requirePublicationArm(value: CatalogTestRunTerminalReleaseV1, proof: CatalogTestRunTerminalDeliveryReadbackV1) {
        requireReleaseUse(value); requireTestTerminalCatalog(stage === Stage.PUBLICATION_ARM && !putDispatched && proof === latestProof && snapshot?.terminal?.signed == true && signatureSqlArmed)
        checkNotNull(native).requireObservation(proof); proof.requireSource(checkNotNull(snapshot), checkNotNull(frozen), checkNotNull(signed))
    }
    internal fun requirePutConstruction(value: CatalogTestRunTerminalAssemblyV1, input: CatalogTestRunTerminalFrozenV1, signature: CatalogTestRunTerminalSignedV1) {
        requireProviderRunning(); requireTestTerminalCatalog(stage === Stage.PUT && native === value && input === frozen && signature === signed && publicationArmed && !putDispatched)
        putDispatched = true
    }
    internal fun requirePublicationAcknowledgement(value: CatalogTestRunTerminalReleaseV1, actual: CatalogPrimaryPutAcknowledgementV1) {
        requireReleaseUse(value); requireTestTerminalCatalog(stage === Stage.ACKNOWLEDGEMENT && putDispatched && actual === acknowledgement)
        checkNotNull(native).requireAcknowledgement(actual)
    }
    internal fun requireDualPreservation(value: CatalogTestRunTerminalReleaseV1, proof: CatalogTestRunTerminalDeliveryReadbackV1) {
        requireReleaseUse(value); requireTestTerminalCatalog(stage === Stage.DUAL && proof === dualProof && proof === latestProof && currentCheckedSnapshot === snapshot)
        checkNotNull(native).requireObservation(proof); proof.requireSource(checkNotNull(dualSnapshot), checkNotNull(frozen), checkNotNull(signed))
    }
    internal fun requireCompleteArm(value: CatalogTestRunTerminalReleaseV1, proof: CatalogTestRunTerminalDeliveryReadbackV1) {
        requireReleaseUse(value); requireTestTerminalCatalog(stage === Stage.COMPLETE_ARM && !completeArmed && proof === dualProof); requireDualSource()
    }
    internal fun requireProjectArm(value: CatalogTestRunTerminalReleaseV1, proof: CatalogTestRunTerminalDeliveryReadbackV1) {
        requireReleaseUse(value); requireTestTerminalCatalog(stage === Stage.PROJECT_ARM && !projectArmed && completeSnapshot === snapshot && proof === dualProof); requireDualSource()
    }

    internal fun requireProviderRunning() { requireConnectionFree(); requireRunning(); requireNoPhase(); requireEvidenceTime(sampleWallTime()) }
    internal fun sampleWallTime(): Instant {
        requireConnectionFree(); requireRunning()
        val at = acquisition.sampleUtc() // Retained UTC owner, never called from a JDBC mapper.
        requireTestTerminalCatalog(lastWallTime?.let { at >= it } != false); lastWallTime = at
        requireRunning(); return at
    }
    private fun requireEvidenceTime(at: Instant) {
        requireTestTerminalCatalog(at.epochSecond in 0..253_402_300_799L)
        ordinary?.let { requireTestTerminalCatalog(it.statement.evidenceRetainUntilEpochSecond > at.epochSecond) }
        terminal?.let { requireTestTerminalCatalog(it.statement.evidenceRetainUntilEpochSecond > at.epochSecond) }
    }
    internal fun requireCustody(value: CatalogReadbackRefreshCustodyV1) {
        // Identity only: must NOT recursively call value.requireTestTerminal(this).
        requireTestTerminalCatalog(caller === Thread.currentThread() && coordinator.catalogRefreshCustody === value)
    }
    internal fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("TEST terminal catalog interrupted.")
        requireTestTerminalCatalog(caller === Thread.currentThread() && started && !closed && !released && !cleanupUncertain)
        budget.remainingMillis(1); process.requireRegistrationTarget()
        requireTestTerminalCatalog(process.pools.catalogCoordinator === coordinator && coordinator.ownership === ownership && coordinator.manager === manager && coordinator.dataSource === source)
        acquisition.requireRetained(routing, process.publicationLanes)
        predecessor?.requireCatalogChild(this); recovery?.requireOriginal(this)
        if (reserved) coordinator.catalogRefreshCustody.requireTestTerminal(this)
        leaseStartedAt?.let { requireTestTerminalCatalog(ownership.nanoClock.nanoTime() - it in 0 until LEASE_NANOS) }
    }
    private fun requireNoPhase() = requireTestTerminalCatalog(phase == null && !phaseEntered)
    private fun waitUntil(after: Instant) {
        requireTestTerminalCatalog(after.epochSecond in 0..253_402_300_799L)
        while (true) {
            requireConnectionFree(); requireRunning()
            if (sampleWallTime() >= after) return
            LockSupport.parkNanos(budget.remainingMillis(25) * 1_000_000L)
        }
    }
    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST terminal catalog cancelled.")
            problem is InterruptedException || problem is InterruptedIOException || problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED ->
                InterruptedException("TEST terminal catalog interrupted.")
            else -> CatalogTestRunTerminalExceptionV1()
        }
        while (true) {
            val old = failure.get()
            if (old is Error || old is CancellationException && retained !is Error || old is InterruptedException && retained !is Error && retained !is CancellationException ||
                old != null && retained is CatalogTestRunTerminalExceptionV1) return
            if (failure.compareAndSet(old, retained)) return
        }
    }
    internal fun throwIfSignalled() { failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it } }
    override fun close() {
        requireConnectionFree(); requireTestTerminalCatalog(caller === Thread.currentThread())
        if (closed) { throwIfSignalled(); requireTestTerminalCatalog(cleanupProven); return }
        closed = true; stage = Stage.CLOSED
        val outcomes = listOf(runCatching { ordinaryReader?.close() }, runCatching { terminalReader?.close() },
            runCatching { native?.close() }, runCatching { files?.close() }, runCatching {
                requireNoPhase(); requireTestTerminalCatalog(!cleanupUncertain && (lease == null || leaseReleased))
                if (started) { budget.remainingMillis(1); requireTestTerminalCatalog(!Thread.currentThread().isInterrupted) }
            })
        outcomes.forEach { it.exceptionOrNull()?.let(::observeFailure) }
        throwIfSignalled(); cleanupProven = true
        if (reserved) coordinator.catalogRefreshCustody.releaseTestTerminalAfterCleanup(this)
        released = true
    }
    internal fun requireActualCleanup() {
        requireConnectionFree(); requireNoPhase(); throwIfSignalled()
        requireTestTerminalCatalog(caller === Thread.currentThread() && closed && cleanupProven && !cleanupUncertain && (lease == null || leaseReleased))
    }

    /** Deliberately never calls requireRunning(): E's deadline and ownership ended before this historical child. */
    private fun requireCompletedProjection() {
        throwIfSignalled(); requireNoPhase()
        requireTestTerminalCatalog(caller === Thread.currentThread() && started && closed && stage === Stage.CLOSED && cleanupProven &&
            !cleanupUncertain && reserved && released && selecting == null && selectedPhase == null && selectedPreflight == null &&
            completedResult === CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED &&
            projectedReload != null && projectedReload === snapshot && nativeClosed && native != null &&
            ordinaryRetired && ordinaryReader != null && terminalRetired && terminalReader != null &&
            files != null && release != null && release === capturedRelease && releaseRetentionClaimed && releaseCaptureClaimed &&
            dualProof != null && dualProof === latestProof && dualSnapshot != null && frozen != null && signed != null && record != null &&
            (projectedReplay && lease == null && leaseOwner == null && leaseStartedAt == null ||
                !projectedReplay && lease != null && leaseOwner != null && leaseStartedAt != null && leaseReleased))
    }

    /** Rechecks retained actual native/final-row lineage without resurrecting E or reading custody after close. */
    private fun requireProjectedReload() {
        requireActualCleanup()
        requireTestTerminalCatalog(released && projectedReload != null && projectedReload === snapshot)
        requireDualSource()
        val final = checkNotNull(projectedReload); val row = checkNotNull(final.terminal)
        val input = checkNotNull(frozen); val signature = checkNotNull(signed)
        input.requireOwner(this); final.requireHead(); row.requireSigned(signature); row.requireDual(checkNotNull(dualProof))
        final.run.requirePayment(prepared = true, projected = true)
        final.run.requireProjected(input, signature, checkNotNull(row.projectedAt))
    }
    override fun toString(): String = "CatalogTestRunTerminalV1(original-only,bounded-history,PURGING-only,redacted)"

    private enum class Stage { NEW, FREEZE, EVIDENCE, ORDINARY_INVENTORY, TERMINAL_INVENTORY, CUSTODY, OBSERVATION,
        PREPARE_ARM, SIGN_ARM, SIGN, SIGN_RETURN, SIGNATURE_ARM, PUBLICATION_ARM, PUT, ACKNOWLEDGEMENT, DUAL, CLOSE_NATIVE, COMPLETE_ARM, PROJECT_ARM, CLOSED }
    private data class PublicationFacts(val id: String, val ref: TestTerminalObjectRefV1, val createdAt: Instant, val objectCreatedAt: Instant,
        val retainUntil: Instant, val verifiedAt: Instant, val verificationSha256: String)
    companion object {
        private const val LEASE_NANOS = 30_000_000_000L
        internal fun begin(predecessor: TestRunTerminalQuiescenceV1): CatalogTestRunTerminalV1 = CatalogTestRunTerminalV1(predecessor, null,
            predecessor.registration.process, predecessor.registration.sealingRunArguments()[2] as Long)
        internal fun forRecovery(recovery: CatalogTestRunTerminalPreparedRecoveryV1): CatalogTestRunTerminalV1 =
            CatalogTestRunTerminalV1(null, recovery, recovery.process, recovery.installationLimit)
        private fun startBudget(process: VersionBoundTestNamespaceProcessV1): PersistenceTimeBudget {
            requireConnectionFree(); process.requireRegistrationTarget()
            // Fresh bounded sum, NOT D's spent deadline: two native-pair allowances, configured catalog
            // work, three capped full-chain rounds, and twelve 2s catalog/control phases. Per-version
            // preflights consume the native-pair allowance; no per-page/lease/deadline renewal.
            val scan = process.consumers.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong()
            val rounds = Math.multiplyExact(3L, minOf(process.catalogReadback.totalAttemptMillis, 10_000L))
            val total = Math.addExact(Math.multiplyExact(2L, scan), Math.addExact(process.catalogActivation.totalAttemptMillis, Math.addExact(rounds, 24_000L)))
            return PersistenceTimeBudget.start(total, process.pools.catalogCoordinator.ownership.nanoClock)
        }
        private fun nextPass(first: TestTerminalInventoryWitnessV1, bound: Long, uncertainty: Long): Instant =
            Instant.ofEpochSecond(Math.addExact(first.completedAtEpochSecond, Math.addExact(bound, Math.multiplyExact(2L, uncertainty))))
        private fun requireWitness(old: TestTerminalInventoryWitnessV1, count: Long, bytes: Long, hash: String) =
            requireTestTerminalCatalog(old.versionCount == count && old.byteCount == bytes && old.sha256 == hash)
    }
}

internal enum class CatalogTestRunTerminalResultV1 {
    DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED,
    EXACT_PREPARED_AWAITING_DUAL_COPY,
}
