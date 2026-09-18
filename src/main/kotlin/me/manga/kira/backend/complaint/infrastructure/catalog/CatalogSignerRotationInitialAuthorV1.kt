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
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.time.Clock
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * One real TARGET-only D7 bootstrap and its retained initial-author session. The actual G1 refresh,
 * binding and single acquired campaign never escape. Each returned Freeze owner still uses its own
 * unchanged <=30s allowance; this session neither renews that allowance nor the actual DB-time lease.
 * The caller separately retains/prepares/retires the original target assembly, including on failure.
 */
@Suppress("TooManyFunctions", "LargeClass") // Concrete fixed stages, not a caller-selected phase/capability engine.
internal class CatalogSignerRotationInitialAuthorV1 private constructor(
    internal val process: VersionBoundComplaintProcessConfiguration,
    internal val bootstrapBudget: PersistenceTimeBudget,
    private val signingHttpFactory: (() -> SdkHttpClient)?,
    private val readbackHttpFactory: (() -> SdkHttpClient)?,
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
    internal val expected = CatalogGenesisInitialLiveBinding.fromRetained(process)
    private var stage = Stage.NEW
    private var entered = false
    private var reserved = false
    private var released = false
    private var failed = false
    private var closed = false
    private var cleanupProven = false
    private var closeFailure: CatalogSignerRotationFreezeExceptionV1? = null
    private var refresh: CurrentAcceptedCatalogRefreshV1? = null
    private var refreshAttempt: CatalogReadbackRefreshCustodyV1.Attempt? = null
    private var readback: CatalogDualLocationVerifier.GenesisReadback? = null
    private var refreshResult: CurrentAcceptedCatalogRefreshV1.Result? = null
    private var snapshotIssued = false
    private var completeIssued = false
    private var completed = false
    private var projectIssued = false
    private var projected = false
    private var binding: CatalogCoordinatorLeaseBindingV1? = null
    private var acquireIssued = false
    private var campaign: CatalogCoordinatorLeaseCampaignV1? = null
    private var bootstrapFinished = false
    private var invocation: CatalogSignerRotationFreezeV1? = null
    private var invocationAttempt: CatalogSignerRotationFreezeAttemptV1? = null
    private var selectedPath: PersistencePhasePath? = null
    private var phaseEntered = false
    private var phaseRetainedForEntry = false
    private var phaseAllowance: PersistenceTimeBudget? = null
    internal val phaseBudget: PersistenceTimeBudget get() = checkNotNull(phaseAllowance)
    private var originalPhase: PersistencePhaseContext? = null
    private var sqlCleanupUnproven = false
    private var leaseOutcomeUncertain = false
    private val originalSignal = AtomicReference<Throwable?>()

    init {
        requireConnectionFree()
        requireProcess()
    }

    /** Once only: real pinned SDK/raw G1 -> possible COMPLETE -> mandatory PROJECT -> real ACQUIRE. */
    @Suppress("TooGenericExceptionCaught")
    fun prepare(primaryReadCredentials: AwsSessionCredentials, replicaReadCredentials: AwsSessionCredentials) {
        requireSignerRotation(caller === Thread.currentThread(), CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        try {
            requireConnectionFree()
            requireBootstrapRunning()
            requireSignerRotation(!entered && stage === Stage.NEW, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
            entered = true
            coordinator.catalogRefreshCustody.reserveSignerRotationAuthor(this)
            reserved = true
            stage = Stage.REFRESH
            val producer = CurrentAcceptedCatalogRefreshV1.initialAuthor(
                this, primaryReadCredentials, replicaReadCredentials, readbackHttpFactory, clock,
            )
            refresh = producer // Before the first provider construction or snapshot phase.
            refreshResult = producer.refresh()
            requireBootstrapRunning()
            stage = Stage.ACQUIRE
            val selected = CatalogCoordinatorLeaseBindingV1.fromInitialAuthor(this, process, checkNotNull(refreshResult))
            binding = selected
            acquireIssued = true // Neither ambiguous SQL nor a failed response permits a second acquire in this session.
            inPhase(PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE) {
                val actual = coordinator.lease.acquireInitialAuthor(this, selected)
                campaign = actual.campaign // Retain the real return before any post-return clock/resource check.
                requireBootstrapRunning()
                requireSignerRotation(actual.campaign.binding === selected, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
                actual.campaign.requireRotationContinuity(bootstrapBudget)
            }
            requireBootstrapRunning()
            bootstrapFinished = true
            stage = Stage.READY
        } catch (problem: Throwable) {
            observeFailure(problem)
            failed = true
            campaign?.close()
            var failure = signerRotationSignal(problem)
            try {
                close()
            } catch (cleanup: Throwable) {
                failure = preferSignerRotationCleanup(failure, cleanup)
            }
            throw boundedSignerRotationFailure(failure)
        }
    }

    /** A clean prior invocation can be followed only on this same live campaign, never by rebootstrap/renewal. */
    @Suppress("TooGenericExceptionCaught")
    fun beginFreeze(): CatalogSignerRotationFreezeV1 {
        requireSignerRotation(caller === Thread.currentThread(), CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        try {
            requireConnectionFree()
            requireSession()
            requireSignerRotation(stage === Stage.READY && bootstrapFinished && invocationAttempt == null)
            invocation?.requireClosedForInitialAuthor(this) // Historical cleanup only, not its spent running deadline.
            stage = Stage.CREATING_INVOCATION
            val next = CatalogSignerRotationFreezeV1.beginInitialAuthor(
                this, process, checkNotNull(campaign), signingHttpFactory, readbackHttpFactory, clock,
            )
            invocation = next // Construction is cold; retain before any input, provider, slot or SQL effect.
            stage = Stage.READY
            return next
        } catch (problem: Throwable) {
            observeFailure(problem)
            failed = true
            campaign?.close()
            throwIfSignalled()
            throw boundedSignerRotationFailure(problem)
        }
    }

    internal fun requireRefreshReservation(producer: CurrentAcceptedCatalogRefreshV1) {
        requireConnectionFree()
        requireBootstrapRunning()
        requireSignerRotation(stage === Stage.REFRESH && refresh === producer && refreshAttempt == null)
    }

    internal fun attach(producer: CurrentAcceptedCatalogRefreshV1, selected: CatalogReadbackRefreshCustodyV1.Attempt) {
        requireSignerRotation(caller === Thread.currentThread() && stage === Stage.REFRESH && refresh === producer && refreshAttempt == null)
        selected.requireInitialAuthorIdentity(this, producer)
        refreshAttempt = selected // Before post-reservation clock/resource checks, even when attachment then fails.
        requireConnectionFree()
        requireBootstrapRunning()
        selected.requireInitialAuthor(this)
    }

    internal fun snapshot(initial: ByteArray, current: ByteArray, policy: CatalogReadbackPolicy): LocalCatalogSnapshot {
        requireBootstrapRunning()
        requireSignerRotation(stage === Stage.REFRESH && !snapshotIssued && readback == null)
        snapshotIssued = true
        return inPhase(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT) {
            coordinator.snapshot.loadInitialAuthor(initial, current, policy, this)
        }
    }

    internal fun preserveReadback(producer: CurrentAcceptedCatalogRefreshV1, fresh: CatalogDualLocationVerifier.GenesisReadback) {
        requireConnectionFree()
        requireBootstrapRunning()
        requireSqlCleanup()
        requireSignerRotation(stage === Stage.REFRESH && refresh === producer && snapshotIssued && readback == null && selectedPath == null)
        checkNotNull(refreshAttempt).requireProviderClosed()
        readback = fresh
    }

    internal fun complete(fresh: CatalogDualLocationVerifier.GenesisReadback) {
        requireBarrier(fresh, expected)
        requireSignerRotation(fresh.resume == GenesisResume.PREPARED && !completeIssued && !projectIssued)
        completeIssued = true
        inPhase(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE) {
            coordinator.genesis.completeInitialAuthor(fresh, expected, this)
        }
        completed = true
    }

    internal fun project(fresh: CatalogDualLocationVerifier.GenesisReadback): ProcessBoundCatalogGenesisProjection {
        requireBarrier(fresh, expected)
        requireSignerRotation(!projectIssued && (fresh.resume != GenesisResume.PREPARED || completed))
        projectIssued = true
        return inPhase(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT) {
            coordinator.genesis.projectInitialAuthor(fresh, expected, this)
        }.also { projected = true }
    }

    internal fun preserveProjection(fresh: CatalogDualLocationVerifier.GenesisReadback, projection: ProcessBoundCatalogGenesisProjection) {
        requireConnectionFree()
        requireBarrier(fresh, expected)
        requireSqlCleanup()
        requireSignerRotation(projected && selectedPath == null)
        projection.requireBinding(process, fresh)
    }

    internal fun requireBarrier(fresh: CatalogDualLocationVerifier.GenesisReadback, selected: CatalogGenesisInitialLiveBinding) {
        requireBootstrapRunning()
        requireSignerRotation(stage === Stage.REFRESH && readback === fresh && expected === selected)
        checkNotNull(refreshAttempt).requireProviderClosed()
    }

    internal fun requireBindingInputs(candidate: VersionBoundComplaintProcessConfiguration, result: CurrentAcceptedCatalogRefreshV1.Result) {
        requireBootstrapRunning()
        requireSqlCleanup()
        requireSignerRotation(stage === Stage.ACQUIRE && candidate === process && refreshResult === result && projected && binding == null)
        checkNotNull(refreshAttempt).requireInitialAuthorCleanup(this)
    }

    internal fun requireBoundProcess(selected: CatalogCoordinatorLeaseBindingV1, candidate: VersionBoundComplaintProcessConfiguration) {
        requireSession()
        requireSignerRotation(
            bootstrapFinished && binding === selected && candidate === process && campaign?.binding === selected &&
                (stage === Stage.READY || stage === Stage.CREATING_INVOCATION),
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requireLeaseSelection(candidate: PersistencePhaseOwnership, jdbc: JdbcTemplate, selected: CatalogCoordinatorLeaseBindingV1) {
        requireConnectionFree()
        requireLeaseAttempt(selected)
        requireSignerRotation(candidate === ownership && jdbc.dataSource === source)
        selected.requirePersistence(candidate, jdbc)
    }

    internal fun requireLeaseAttempt(selected: CatalogCoordinatorLeaseBindingV1) {
        requireBootstrapRunning()
        requireSignerRotation(
            stage === Stage.ACQUIRE && acquireIssued && binding === selected &&
                selectedPath === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
        )
        selected.requireInitialAuthorPurpose(this)
    }

    internal fun requireInvocationConstruction(candidate: VersionBoundComplaintProcessConfiguration, current: CatalogCoordinatorLeaseCampaignV1) {
        requireSession()
        requireSignerRotation(
            stage === Stage.CREATING_INVOCATION && bootstrapFinished && invocationAttempt == null && candidate === process && current === campaign,
        )
    }

    internal fun requireInvocation(selected: CatalogSignerRotationFreezeV1) {
        requireSession()
        requireSignerRotation(stage === Stage.READY && bootstrapFinished && invocation === selected)
    }

    internal fun retainInvocation(selected: CatalogSignerRotationFreezeAttemptV1, child: CatalogSignerRotationFreezeV1) {
        requireInvocation(child)
        requireSignerRotation(invocationAttempt == null && child.owns(selected) && selected.process === process && selected.campaign === campaign)
        invocationAttempt = selected
    }

    internal fun requireInvocationSlot(selected: CatalogSignerRotationFreezeAttemptV1) {
        requireSession()
        requireSignerRotation(stage === Stage.READY && invocationAttempt === selected && invocation?.owns(selected) == true)
    }

    internal fun requireInvocationPhase(selected: CatalogSignerRotationFreezeAttemptV1, candidate: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireInvocationSlot(selected)
        requireSqlCleanup()
        requireSignerRotation(
            candidate === ownership && path in setOf(
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
            ),
        )
    }

    /** Called after the actual child's own provider/file/phase cleanup, even when this session is closing. */
    internal fun releaseInvocationAfterCleanup(selected: CatalogSignerRotationFreezeAttemptV1) {
        requireSignerRotation(caller === Thread.currentThread() && invocationAttempt === selected)
        coordinator.catalogRefreshCustody.requireSignerRotationAuthor(this)
        selected.requireActualCleanup()
        invocationAttempt = null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> inPhase(path: PersistencePhasePath, action: () -> T): T {
        requireConnectionFree()
        requireBootstrapRunning()
        requireSqlCleanup()
        requireSignerRotation(selectedPath == null)
        selectedPath = path
        phaseAllowance = if (path === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE) {
            bootstrapBudget
        } else {
            checkNotNull(refreshAttempt?.initialAuthorBudget)
        }
        phaseEntered = false
        phaseRetainedForEntry = false
        return try {
            action()
        } catch (problem: Throwable) {
            if (!phaseRetainedForEntry && problem is PersistencePhaseException && !problem.cleanupProven) sqlCleanupUnproven = true
            observeFailure(problem)
            throwIfSignalled()
            throw problem
        } finally {
            selectedPath = null
        }
    }

    internal fun requirePhaseEntry(candidate: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireBootstrapRunning()
        requireSignerRotation(candidate === ownership && selectedPath === path && !phaseEntered)
        when (path) {
            PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT ->
                requireSignerRotation(stage === Stage.REFRESH && snapshotIssued && readback == null)
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE -> {
                requireSignerRotation(completeIssued && !completed && !projectIssued)
                requireBarrier(checkNotNull(readback), expected)
            }
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT -> {
                requireSignerRotation(projectIssued && !projected)
                requireBarrier(checkNotNull(readback), expected)
            }
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE -> requireLeaseAttempt(checkNotNull(binding))
            else -> throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        }
        phaseEntered = true
    }

    internal fun authenticate(candidate: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireBootstrapRunning()
        requireSignerRotation(candidate === ownership && jdbc.dataSource === source && phaseEntered && selectedPath != null)
        val authenticated = jdbc.query(
            "SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("authenticated") && !rows.wasNull() && !rows.next() },
            username, username, database,
        )
        requireSignerRotation(authenticated == true, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        requireBootstrapRunning()
    }

    internal fun retainPhase(phase: PersistencePhaseContext) {
        requireSignerRotation(
            caller === Thread.currentThread() && phaseEntered && selectedPath != null && originalPhase == null &&
                !phaseRetainedForEntry && !sqlCleanupUnproven && !leaseOutcomeUncertain,
            CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN,
        )
        originalPhase = phase // Before permit/publication, including entry failures that never return a phase.
        phaseRetainedForEntry = true
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun observePhaseCleanup(phase: PersistencePhaseContext) {
        if (caller !== Thread.currentThread() || originalPhase !== phase) {
            sqlCleanupUnproven = true
            return
        }
        try {
            if (!phase.signerRotationAuthorCleanupProven(this)) sqlCleanupUnproven = true
            if (selectedPath === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE &&
                phase.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN
            ) leaseOutcomeUncertain = true
            if (!sqlCleanupUnproven && !leaseOutcomeUncertain) originalPhase = null
        } catch (problem: Throwable) {
            sqlCleanupUnproven = true
            observeFailure(problem)
        }
    }

    private fun requireSqlCleanup() = requireSignerRotation(
        caller === Thread.currentThread() && originalPhase == null && !sqlCleanupUnproven && !leaseOutcomeUncertain,
        CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN,
    )

    internal fun requireRefreshCleanup(selected: CatalogReadbackRefreshCustodyV1.Attempt) {
        requireSignerRotation(caller === Thread.currentThread() && refreshAttempt === selected)
        requireSqlCleanup()
        throwIfSignalled()
        bootstrapBudget.remainingMillis(1)
    }

    internal fun requireReadbackRunning() {
        requireBootstrapRunning()
        requireSignerRotation(stage === Stage.REFRESH)
        checkNotNull(refreshAttempt?.initialAuthorBudget).remainingMillis(1)
    }

    internal fun requireBootstrapRunning() {
        requireSession()
        requireSignerRotation(!bootstrapFinished)
        bootstrapBudget.remainingMillis(1)
    }

    private fun requireSession() {
        throwIfSignalled()
        requireSignerRotation(
            caller === Thread.currentThread() && !closed && !failed && !released,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        requireSignerRotation(!sqlCleanupUnproven && !leaseOutcomeUncertain, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        requireSignerRotation(!Thread.currentThread().isInterrupted, CatalogSignerRotationFreezeFailureV1.INTERRUPTED)
        requireProcess()
        if (reserved) coordinator.catalogRefreshCustody.requireSignerRotationAuthor(this)
    }

    private fun requireProcess() {
        process.requireUnchangedConfiguration()
        requireSignerRotation(
            process.pools.catalogCoordinator === coordinator && coordinator.ownership === ownership && coordinator.manager === manager &&
                coordinator.dataSource === source && coordinator.catalogSignerRotationAuthoring && !coordinator.catalogSignerRotationRecovery &&
                process.catalogReadback?.projectedCurrent == false && process.catalogSignerRotation != null &&
                desired.mode === ComplaintInstallationMode.LIVE && desired.scope == ComplaintDataScope.LIVE &&
                desired.implementationSchema == 1 && desired.desiredGeneration == 1L &&
                username != VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME &&
                username != VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        checkNotNull(process.catalogSignerRotation).requireRetained(process.pools, checkNotNull(process.catalogReadback))
        coordinator.requireResources()
    }

    internal fun requireCustody(selected: CatalogReadbackRefreshCustodyV1) = requireSignerRotation(
        caller === Thread.currentThread() && coordinator.catalogRefreshCustody === selected, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
    )

    internal fun observeFailure(problem: Throwable) {
        val signal = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("Catalog signer rotation initial author cancelled.")
            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ||
                (problem is CatalogReadbackException && problem.code === CatalogReadbackFailure.INTERRUPTED) ||
                (problem is CatalogSignerRotationFreezeExceptionV1 && problem.code === CatalogSignerRotationFreezeFailureV1.INTERRUPTED) ->
                InterruptedException("Catalog signer rotation initial author interrupted.")
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

    override fun close() {
        requireSignerRotation(caller === Thread.currentThread(), CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        if (closed) {
            closeFailure?.let { throw it }
            return
        }
        closed = true
        stage = Stage.CLOSED
        closeFailure = CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        campaign?.close() // Stop locally first; never a SQL relinquishment, reacquire, refund or reset.
        val outcomes = listOf(
            runCatching { invocation?.close() }, runCatching { refresh?.close() }, runCatching { refreshAttempt?.closeProvider() },
            runCatching { refreshAttempt?.requireInitialAuthorCleanup(this) }, runCatching(::requireSqlCleanup),
            runCatching(::requireConnectionFree), runCatching(::throwIfSignalled),
            runCatching { requireSignerRotation(invocationAttempt == null, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN) },
        )
        requireSignerRotationCleanup(outcomes)
        cleanupProven = true
        if (reserved) coordinator.catalogRefreshCustody.releaseSignerRotationAuthorAfterCleanup(this)
        released = true
        closeFailure = null
    }

    internal fun requireActualCleanup() {
        requireSignerRotation(
            caller === Thread.currentThread() && closed && cleanupProven && invocationAttempt == null,
            CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN,
        )
        requireSqlCleanup()
        requireConnectionFree()
    }

    override fun toString(): String = "CatalogSignerRotationInitialAuthorV1(initial-G1,same-campaign,no-publication-or-runtime-admission,redacted)"
    private enum class Stage { NEW, REFRESH, ACQUIRE, READY, CREATING_INVOCATION, CLOSED }

    companion object {
        /** Retain before target preparation. This fixed startup/bootstrap allowance is NOT a Freeze invocation budget. */
        fun begin(process: VersionBoundComplaintProcessConfiguration): CatalogSignerRotationInitialAuthorV1 =
            CatalogSignerRotationInitialAuthorV1(process, startBudget(process), null, null, Clock.systemUTC())

        /** Raw HTTP/wall-time seams only; real named UNKNOWN root, process clock, SDK, SQL and custody remain mandatory. */
        internal fun withHttpFixtures(
            process: VersionBoundComplaintProcessConfiguration,
            signing: () -> SdkHttpClient,
            readback: () -> SdkHttpClient,
            clock: Clock,
        ): CatalogSignerRotationInitialAuthorV1 = CatalogSignerRotationInitialAuthorV1(process, startBudget(process), signing, readback, clock)

        private fun startBudget(process: VersionBoundComplaintProcessConfiguration): PersistenceTimeBudget {
            requireConnectionFree()
            return PersistenceTimeBudget.start(60_000, process.pools.catalogCoordinator.ownership.nanoClock)
        }
    }
}
