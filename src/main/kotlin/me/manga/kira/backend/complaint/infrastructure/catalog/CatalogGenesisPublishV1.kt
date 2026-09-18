package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.SystemPersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationFailureV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.AwsCatalogPrimaryPutAdapterV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutAcknowledgementV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutTargetV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.VersionedSecretBinding
import me.manga.kira.backend.security.aws.AwsSecretVersionLimits
import me.manga.kira.backend.security.aws.AwsSecretsManagerVersionResolver
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException

/**
 * One original programmatic G1 publisher/recovery invocation. The real current fixed-operator recheck
 * is inside this owner, before its sole conditional PRIMARY PUT. No signing, replica PUT, SQL
 * COMPLETE/PROJECT, process activation, executable/CLI or portable publication capability.
 */
@Suppress("TooManyFunctions") // Fixed actual constructors, typed proof checks and cleanup stay visible instead of a generic stage/resource registry.
internal class CatalogGenesisPublishV1 private constructor(
    internal val budget: PersistenceTimeBudget,
    private val wallClock: Clock,
    private val secretHttpFactory: (() -> SdkHttpClient)?,
    private val putHttpFactory: (() -> SdkHttpClient)?,
    private val readbackHttpFactory: (() -> SdkHttpClient)?,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val files = CatalogGenesisPublishInputsV1(budget)
    private val assembly = ComplaintDesiredProcessAssemblyV1()
    private val putConstruction = AwsCatalogPrimaryPutAdapterV1.Construction(budget)
    private val namespaceConstruction = S3CatalogReadbackAdapter.Construction()
    private val readbackConstruction = S3CatalogReadbackAdapter.Construction()
    private val completeConstruction = S3CatalogReadbackAdapter.Construction()
    private val putHttp = CatalogGenesisPublishHttpV1(this)
    private val namespaceHttp = CatalogGenesisPublishHttpPairV1(this)
    private val readbackHttp = CatalogGenesisPublishHttpPairV1(this)
    private val completeHttp = CatalogGenesisPublishHttpPairV1(this)
    private var release: CatalogGenesisPublishReleaseV1? = null
    private var attempt: CatalogGenesisPublishAttemptV1? = null
    private var readerBudget: PersistenceTimeBudget? = null
    private var deliveryTarget: CatalogPrimaryPutTargetV1? = null
    private var lastWall: Instant? = null
    private var namespaceObservation: CatalogGenesisNamespaceObservationV1? = null
    private var acknowledgement: CatalogPrimaryPutAcknowledgementV1? = null
    private var diagnostic: CatalogReadbackResult? = null
    private var completeReadback: CatalogDualLocationVerifier.GenesisReadback? = null
    private var resolver: AwsSecretsManagerVersionResolver? = null
    private var resolverOpening = false
    private var resolverCloseIssued = false
    private var entered = false
    private var closed = false
    private var closeFailure: CatalogGenesisPublishExceptionV1? = null

    fun publish(
        request: CatalogGenesisPublishRequestV1,
        secretCredentials: AwsSessionCredentials,
        primaryPutCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
        sealerCredentials: AwsSessionCredentials? = null,
    ): CatalogGenesisPublishResultV1 = run(request, secretCredentials, primaryPutCredentials, primaryReadCredentials, replicaReadCredentials, sealerCredentials)

    /** Recovery cannot even supply a PUT credential. Missing acknowledgement never proves that a previous request was not sent. */
    fun recover(
        request: CatalogGenesisPublishRequestV1,
        secretCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
        sealerCredentials: AwsSessionCredentials? = null,
    ): CatalogGenesisPublishResultV1 = run(request, secretCredentials, null, primaryReadCredentials, replicaReadCredentials, sealerCredentials)

    @Suppress("TooGenericExceptionCaught") // Actual retained cleanup precedes bounded failure mapping; no raw SQL/provider/path graph escapes.
    private fun run(
        request: CatalogGenesisPublishRequestV1,
        secretCredentials: AwsSessionCredentials,
        primaryPutCredentials: AwsSessionCredentials?,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
        sealerCredentials: AwsSessionCredentials?,
    ): CatalogGenesisPublishResultV1 {
        requirePublication(caller === Thread.currentThread(), CatalogGenesisPublishFailureV1.PROCESS_REFUSED)
        var result: CatalogGenesisPublishResultV1? = null
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requirePublication(!entered, CatalogGenesisPublishFailureV1.PROCESS_REFUSED)
            entered = true
            files.acquire(request)
            val retainedRelease = CatalogGenesisPublishReleaseV1(this, files.frozen, files.independentPin(), budget)
            release = retainedRelease
            retainedRelease.openExisting()
            val deployment = files.deployment
            val acquired = deployment.allBindings().map { acquire(it, secretCredentials) }
            assembly.assemble(deployment, acquired, sealerCredentials) // Full genuine TARGET inventory stays UNKNOWN/cold; separate operator only starts.
            requireRunning()
            val target = assembly.target
            val comparison = retainedRelease.bindTarget(target, deployment)
            val retainedAttempt = CatalogGenesisPublishAttemptV1(this, assembly.coordinator, target, retainedRelease, comparison, budget)
            attempt = retainedAttempt
            retainedRelease.attach(retainedAttempt)
            assembly.prepareOperator(budget)
            retainedAttempt.recheck() // Current nonnull actual D + exact signed PREPARED G1, and actual commit/release before every delivery stage.
            val settings = checkNotNull(target.catalogReadback)
            readerBudget = budget.capped(settings.totalAttemptMillis) // One unchanged stricter cap through probes, PUT, both reads, records and cleanup.
            deliveryTarget = retainedRelease.primaryTarget()
            val evaluatedAt = sampleDeliveryTime()
            val policy = settings.policyAt(evaluatedAt)
            requirePublication(checkNotNull(deliveryTarget).retainUntil.epochSecond >= policy.requiredRetainUntilEpochSecond)
            val local = retainedRelease.prepared(policy)
            if (!retainedRelease.isArmed()) {
                requirePublication(primaryPutCredentials != null) // Read-only recovery never constructs first-effect eligibility.
                observeEmptyNamespaces(settings, primaryReadCredentials, replicaReadCredentials)
                sampleDeliveryTime()
                retainedRelease.arm()
                sampleDeliveryTime()
                put(retainedRelease, settings, checkNotNull(primaryPutCredentials))
            }
            val state = observeDelivery(settings, policy, local, evaluatedAt, primaryReadCredentials, replicaReadCredentials)
            sampleDeliveryTime()
            result = CatalogGenesisPublishResultV1(state)
        } catch (problem: Throwable) {
            failure = catalogPublicationSignal(problem)
            attempt?.abort()
        } finally {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure = preferCatalogFreezeCleanup(failure, cleanup)
            }
        }
        failure?.let { throw boundedPublicationFailure(it) }
        return checkNotNull(result)
    }

    private fun put(retained: CatalogGenesisPublishReleaseV1, settings: VersionBoundCatalogReadbackConfigurationV1, credentials: AwsSessionCredentials) {
        requireProviderWork()
        retained.claimPut() // Durable new arm only. Spent before HTTP/SDK construction, never renewed by identical old arm bytes.
        val destination = checkNotNull(deliveryTarget)
        val adapter = putConstruction.open(destination, credentials, wallClock) {
            // Exact native join checks the SAME reader cap without weakening the lower's original retention budget.
            putHttp.open(readerLimits(settings), putHttpFactory)
        }
        requireProviderWork() // A late returned SDK constructor cannot dispatch the already-armed PUT.
        val observed = adapter.put(retained.envelopeBytes()) // Genuine If-None-Match:* PRIMARY request + actual SDK/HTTP cleanup before acknowledgement.
        acknowledgement = observed
        sampleDeliveryTime()
        retained.acknowledged(observed)
        sampleDeliveryTime()
    }

    private fun observeEmptyNamespaces(settings: VersionBoundCatalogReadbackConfigurationV1, primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        requireProviderWork()
        val observed = withCatalogFreezeCleanup(
            {
                val actual = openReader(namespaceConstruction, namespaceHttp, settings, primary, replica)
                CatalogGenesisNamespaceObservationV1.observe(
                    actual,
                    settings.currentBundleBytes(),
                    settings.chainPolicy.trustBundlePolicy,
                    budget,
                ).also { requireProviderWork() }
            },
            ::closeNamespaceReader,
        )
        requireProviderWork()
        namespaceObservation = observed // Only genuine terminal-empty BOTH listings and actual original provider cleanup reach this assignment.
    }

    private fun observeDelivery(
        settings: VersionBoundCatalogReadbackConfigurationV1,
        policy: CatalogReadbackPolicy,
        local: LocalCatalogSnapshot.PreparedGenesis,
        evaluatedAt: Instant,
        primary: AwsSessionCredentials,
        replica: AwsSessionCredentials,
    ): CatalogGenesisPublishStateV1 {
        requireProviderWork()
        val observed = withCatalogFreezeCleanup(
            {
                val actual = openReader(readbackConstruction, readbackHttp, settings, primary, replica)
                CatalogDualLocationVerifier.verifyReadback(actual, settings.initialBundleBytes(), settings.currentBundleBytes(), policy, local)
                    .also { requireProviderWork() }
            },
            ::closeDiagnosticReader,
        )
        sampleDeliveryTime()
        diagnostic = observed
        return when (observed) {
            is CatalogReadbackResult.GenesisAwaitReplication -> {
                checkNotNull(release).awaitReplication(observed)
                CatalogGenesisPublishStateV1.AWAIT_REPLICATION
            }

            is CatalogReadbackResult.PreparedCompletionEvidence -> {
                val fresh = readComplete(settings, policy, local, primary, replica)
                settings.verifyGenesis(fresh, evaluatedAt)
                requirePublication(
                    observed.operationToken == local.mutation.operationToken && fresh.objectVersion == observed.evidence.objectVersion &&
                        fresh.retainUntilEpochSecond == observed.evidence.retainUntilEpochSecond,
                )
                sampleDeliveryTime()
                completeReadback = fresh
                checkNotNull(release).completedCopies(fresh)
                CatalogGenesisPublishStateV1.DUAL_COPY_OBSERVED
            }

            else -> throw CatalogGenesisPublishExceptionV1(CatalogGenesisPublishFailureV1.RECOVERY_REQUIRED) // Empty AFTER an arm remains uncertain.
        }
    }

    private fun readComplete(
        settings: VersionBoundCatalogReadbackConfigurationV1,
        policy: CatalogReadbackPolicy,
        local: LocalCatalogSnapshot.PreparedGenesis,
        primary: AwsSessionCredentials,
        replica: AwsSessionCredentials,
    ): CatalogDualLocationVerifier.GenesisReadback = withCatalogFreezeCleanup(
        {
            val actual = openReader(completeConstruction, completeHttp, settings, primary, replica)
            CatalogDualLocationVerifier.GenesisReadback.verify(
                actual,
                settings.initialBundleBytes(),
                settings.currentBundleBytes(),
                policy,
                local,
            ).also { requireProviderWork() } // Never promotes the diagnostic result into this private raw verifier handoff.
        },
        ::closeCompleteReader,
    )

    private fun openReader(
        construction: S3CatalogReadbackAdapter.Construction,
        http: CatalogGenesisPublishHttpPairV1,
        settings: VersionBoundCatalogReadbackConfigurationV1,
        primary: AwsSessionCredentials,
        replica: AwsSessionCredentials,
    ): CatalogReadbackPort {
        requireProviderWork()
        val limits = readerLimits(settings)
        val actual = S3CatalogReadbackAdapter.openOwned(
            construction,
            settings.currentBundleBytes(),
            settings.chainPolicy.trustBundlePolicy,
            primary,
            replica,
            limits,
            { http.open(limits, readbackHttpFactory) },
            System::nanoTime, // Pure lower elapsed clock. Parent/cap guards run only AFTER the exact native request/body has custody.
        )
        requireProviderWork()
        return TimedGenesisPublishReadbackV1(actual, this)
    }

    private fun readerLimits(settings: VersionBoundCatalogReadbackConfigurationV1): S3CatalogReadbackLimits {
        requireProviderWork()
        val fixed = settings.sdkLimits
        val millis = checkNotNull(readerBudget).remainingMillis(fixed.requestTimeoutMillis)
        return S3CatalogReadbackLimits(
            millis,
            minOf(millis, fixed.connectTimeoutMillis.toLong()).toInt(),
            minOf(millis, fixed.readTimeoutMillis.toLong()).toInt(),
            fixed.maximumListBytes,
            fixed.maximumErrorBytes,
            fixed.maximumObjectBytes,
        )
    }

    private fun closePut() = withCatalogFreezeCleanup(putConstruction::close, putHttp::close)
    private fun closeNamespaceReader() = withCatalogFreezeCleanup(namespaceConstruction::close, namespaceHttp::close)
    private fun closeDiagnosticReader() = withCatalogFreezeCleanup(readbackConstruction::close, readbackHttp::close)
    private fun closeCompleteReader() = withCatalogFreezeCleanup(completeConstruction::close, completeHttp::close)

    private fun acquire(binding: VersionedSecretBinding, credentials: AwsSessionCredentials): AcquiredVersionedSecret {
        requireConnectionFree()
        requireRunning()
        requirePublication(resolver == null && !resolverOpening, CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN)
        val millis = budget.remainingMillis(5_000)
        val limits = AwsSecretVersionLimits(millis, minOf(millis, 2_000).toInt(), minOf(millis, 2_000).toInt())
        val region = binding.version.resourceArn.split(':')[3]
        resolverOpening = true
        resolverCloseIssued = false
        val opened = if (secretHttpFactory == null) {
            AwsSecretsManagerVersionResolver.open(region, credentials, limits)
        } else {
            AwsSecretsManagerVersionResolver.withHttpFixture(region, credentials, limits, secretHttpFactory)
        }
        resolver = opened
        resolverOpening = false
        return withCatalogFreezeCleanup(
            {
                requireRunning()
                AcquiredVersionedSecret.acquire(binding, opened).also { requireRunning() }
            },
            {
                closeResolver()
                requireRunning()
            },
        )
    }

    private fun closeResolver() {
        requireConnectionFree()
        if (!resolverCloseIssued) {
            resolverCloseIssued = true
            resolver?.close()
            resolver = null
        }
        requirePublication(resolver == null && !resolverOpening, CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN)
    }

    internal fun owns(candidate: CatalogGenesisPublishAttemptV1): Boolean = !closed && caller === Thread.currentThread() && attempt === candidate
    internal fun ownsRelease(candidate: CatalogGenesisPublishReleaseV1): Boolean = !closed && caller === Thread.currentThread() && release === candidate

    internal fun requireNamespaceClosed(candidate: CatalogGenesisPublishReleaseV1) {
        requireProviderWork()
        requirePublication(ownsRelease(candidate) && namespaceObservation != null)
        closeNamespaceReader() // Sticky original construction AND exact native custody proof, never a caller-supplied cleanup Boolean.
        requireProviderWork()
    }

    internal fun requireAcknowledgement(candidate: CatalogGenesisPublishReleaseV1, observed: CatalogPrimaryPutAcknowledgementV1) {
        requireProviderWork()
        requirePublication(ownsRelease(candidate) && acknowledgement === observed)
        closePut()
        requireProviderWork()
    }

    internal fun requireDiagnostic(candidate: CatalogGenesisPublishReleaseV1, observed: CatalogReadbackResult.GenesisAwaitReplication) {
        requireProviderWork()
        requirePublication(ownsRelease(candidate) && diagnostic === observed)
        closeDiagnosticReader()
        requireProviderWork()
    }

    internal fun requireCompleteReadback(candidate: CatalogGenesisPublishReleaseV1, fresh: CatalogDualLocationVerifier.GenesisReadback) {
        requireProviderWork()
        requirePublication(ownsRelease(candidate) && completeReadback === fresh)
        closeCompleteReader()
        requireProviderWork()
    }

    /** Fixed phase checks use local identities and the original monotonic budget only, never filesystem/provider work. */
    internal fun requireRunning() {
        requirePublication(caller === Thread.currentThread() && !closed, CatalogGenesisPublishFailureV1.PROCESS_REFUSED)
        requirePublication(!Thread.currentThread().isInterrupted, CatalogGenesisPublishFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
        readerBudget?.remainingMillis(1)
    }

    internal fun requireProviderWork() {
        requireConnectionFree()
        requireRunning()
        checkNotNull(attempt).requireSelected()
    }

    private fun sampleDeliveryTime(): Instant {
        requireProviderWork()
        return checkResultTime().also { requireProviderWork() }
    }

    /** Signed creation + ten calendar years remains frozen; cover the WHOLE original remaining attempt, including cleanup. */
    private fun checkResultTime(): Instant {
        requireConnectionFree()
        val remaining = Math.addExact(budget.remainingMillis(Long.MAX_VALUE), 1L)
        val now = wallClock.instant()
        requirePublication(now.epochSecond in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND && lastWall?.let { !now.isBefore(it) } != false)
        val target = checkNotNull(deliveryTarget)
        val floor = now.plusMillis(remaining).atOffset(ZoneOffset.UTC).plusYears(VersionBoundCatalogReadbackConfigurationV1.REMAINING_MINIMUM_YEARS).toInstant()
        requirePublication(!target.createdAt.isAfter(now) && !target.retainUntil.isBefore(floor))
        lastWall = now
        budget.remainingMillis(1)
        readerBudget?.remainingMillis(1)
        return now
    }

    override fun close() {
        requirePublication(caller === Thread.currentThread(), CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN)
        if (closed) {
            closeFailure?.let { throw it }
            return
        }
        closed = true
        closeFailure = CatalogGenesisPublishExceptionV1(CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN)
        attempt?.abort()
        val outcomes = listOf(
            runCatching(assembly::close), // Stop ALL original roots before awaiting the shared Timer proof.
            runCatching(::closePut),
            runCatching(::closeNamespaceReader),
            runCatching(::closeDiagnosticReader),
            runCatching(::closeCompleteReader),
            runCatching(files::close),
            runCatching(::closeResolver),
            runCatching { assembly.requireCleanup(budget) },
            runCatching { release?.close() }, // An unresolved active phase can forbid filesystem dispatch; do not invent release.
        )
        var failure: Throwable? = null
        outcomes.forEach { outcome -> outcome.exceptionOrNull()?.let { failure = preferCatalogFreezeCleanup(failure, it) } }
        failure?.let { throw boundedPublicationFailure(preferCatalogFreezeCleanup(it, checkNotNull(closeFailure))) }
        requirePublication(!Thread.currentThread().isInterrupted, CatalogGenesisPublishFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
        readerBudget?.remainingMillis(1)
        if (deliveryTarget != null) checkResultTime()
        closeFailure = null
    }

    override fun toString(): String = "CatalogGenesisPublishV1(one-original-attempt,redacted,no-runtime-authority)"

    companion object {
        fun begin(clock: PersistenceNanoClock = SystemPersistenceNanoClock): CatalogGenesisPublishV1 =
            CatalogGenesisPublishV1(PersistenceTimeBudget.start(60_000, clock), Clock.systemUTC(), null, null, null)

        /** Raw HTTP/time substitution only. Actual acquired graph, fixed-login SQL, AWS SDK, raw verifier and Linux custody execute. */
        internal fun withHttpFixtures(
            secretHttpFactory: () -> SdkHttpClient,
            putHttpFactory: () -> SdkHttpClient,
            readbackHttpFactory: () -> SdkHttpClient,
            clock: PersistenceNanoClock = SystemPersistenceNanoClock,
            wallClock: Clock = Clock.systemUTC(),
        ): CatalogGenesisPublishV1 = CatalogGenesisPublishV1(
            PersistenceTimeBudget.start(60_000, clock),
            wallClock,
            secretHttpFactory,
            putHttpFactory,
            readbackHttpFactory,
        )
    }
}

/** Returned only after total owned cleanup; a historical delivery observation, never a COMPLETE/PROJECT or admission handle. */
internal class CatalogGenesisPublishResultV1 internal constructor(val state: CatalogGenesisPublishStateV1) {
    override fun toString(): String = "CatalogGenesisPublishResultV1(historical-only,no-acceptance-authority)"
}

internal enum class CatalogGenesisPublishStateV1 { AWAIT_REPLICATION, DUAL_COPY_OBSERVED }

internal enum class CatalogGenesisPublishFailureV1 {
    INPUT_REFUSED,
    PROCESS_REFUSED,
    AUTHENTICATION_REFUSED,
    STATE_REFUSED,
    RECOVERY_REQUIRED,
    TIME_BUDGET_EXHAUSTED,
    INTERRUPTED,
    CLEANUP_UNPROVEN,
}

internal class CatalogGenesisPublishExceptionV1(val code: CatalogGenesisPublishFailureV1) :
    RuntimeException("Catalog genesis publication refused: ${code.name}.")

internal fun requirePublication(condition: Boolean, code: CatalogGenesisPublishFailureV1 = CatalogGenesisPublishFailureV1.RECOVERY_REQUIRED) {
    if (!condition) throw CatalogGenesisPublishExceptionV1(code)
}

@Suppress("InstanceOfCheckForException")
private fun boundedPublicationFailure(problem: Throwable): CatalogGenesisPublishExceptionV1 {
    val failure = catalogPublicationSignal(problem)
    if (failure is Error || failure is CancellationException || failure is InterruptedException) throw failure
    if (failure is CatalogGenesisPublishExceptionV1) return failure
    val code = when {
        failure is PersistencePhaseException && !failure.cleanupProven -> CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN

        failure is PersistenceBoundaryException && failure.code === PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED ->
            CatalogGenesisPublishFailureV1.TIME_BUDGET_EXHAUSTED

        failure is PersistencePhaseException && failure.code === PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED ->
            CatalogGenesisPublishFailureV1.TIME_BUDGET_EXHAUSTED

        failure is CatalogPrimaryPutExceptionV1 -> if (failure.code === CatalogPrimaryPutFailureV1.CLOSE_FAILURE) {
            CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN
        } else {
            CatalogGenesisPublishFailureV1.RECOVERY_REQUIRED
        }

        else -> publicationInputOrReadbackFailure(failure)
    }
    return CatalogGenesisPublishExceptionV1(code)
}

@Suppress("InstanceOfCheckForException")
private fun publicationInputOrReadbackFailure(failure: Throwable): CatalogGenesisPublishFailureV1 = when (failure) {
    is CatalogReadbackException -> when (failure.code) {
        CatalogReadbackFailure.CLOSE_FAILURE -> CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN
        CatalogReadbackFailure.LIMIT_EXCEEDED -> CatalogGenesisPublishFailureV1.TIME_BUDGET_EXHAUSTED
        else -> CatalogGenesisPublishFailureV1.RECOVERY_REQUIRED
    }

    is CatalogGenesisCustodyExceptionV1 -> when (failure.code) {
        CatalogGenesisCustodyFailureV1.TIME_BUDGET -> CatalogGenesisPublishFailureV1.TIME_BUDGET_EXHAUSTED
        CatalogGenesisCustodyFailureV1.CLEANUP_UNCERTAIN -> CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN
        else -> CatalogGenesisPublishFailureV1.RECOVERY_REQUIRED
    }

    is CatalogGenesisFreezeExceptionV1 -> when (failure.code) {
        CatalogGenesisFreezeFailureV1.TIME_BUDGET_EXHAUSTED -> CatalogGenesisPublishFailureV1.TIME_BUDGET_EXHAUSTED
        CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN -> CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN
        else -> CatalogGenesisPublishFailureV1.INPUT_REFUSED
    }

    is ComplaintDesiredInstallationExceptionV1 -> when (failure.code) {
        ComplaintDesiredInstallationFailureV1.INPUT_REFUSED -> CatalogGenesisPublishFailureV1.INPUT_REFUSED
        ComplaintDesiredInstallationFailureV1.AUTHENTICATION_REFUSED -> CatalogGenesisPublishFailureV1.AUTHENTICATION_REFUSED
        ComplaintDesiredInstallationFailureV1.STATE_REFUSED -> CatalogGenesisPublishFailureV1.STATE_REFUSED
        ComplaintDesiredInstallationFailureV1.TIME_BUDGET_EXHAUSTED -> CatalogGenesisPublishFailureV1.TIME_BUDGET_EXHAUSTED
        ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN -> CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN
        else -> CatalogGenesisPublishFailureV1.PROCESS_REFUSED
    }

    else -> CatalogGenesisPublishFailureV1.PROCESS_REFUSED
}

@Suppress("InstanceOfCheckForException")
internal fun catalogPublicationSignal(failure: Throwable): Throwable {
    val typedInterruption = when (failure) {
        is CatalogGenesisPublishExceptionV1 -> failure.code === CatalogGenesisPublishFailureV1.INTERRUPTED
        is CatalogGenesisFreezeExceptionV1 -> failure.code === CatalogGenesisFreezeFailureV1.INTERRUPTED
        is CatalogGenesisCustodyExceptionV1 -> failure.code === CatalogGenesisCustodyFailureV1.INTERRUPTED
        is CatalogReadbackException -> failure.code === CatalogReadbackFailure.INTERRUPTED
        is ComplaintDesiredInstallationExceptionV1 -> failure.code === ComplaintDesiredInstallationFailureV1.INTERRUPTED
        else -> false
    }
    if (failure is InterruptedException || failure is InterruptedIOException || typedInterruption) Thread.currentThread().interrupt()
    return when {
        failure is Error -> failure
        failure is CancellationException -> CancellationException("Catalog genesis publication cancelled.")
        Thread.currentThread().isInterrupted -> InterruptedException("Catalog genesis publication interrupted.")
        else -> failure
    }
}

/** The real port remains private to this attempt. Request/body boundaries cannot refresh the reader or parent allowance. */
private class TimedGenesisPublishReadbackV1(private val actual: S3CatalogReadbackAdapter, private val owner: CatalogGenesisPublishV1) : CatalogReadbackPort {
    override fun listVersions(request: CatalogListRequest) = checked { actual.listVersions(request) }

    override fun openVersion(request: CatalogGetRequest): CatalogVersionBody {
        owner.requireProviderWork()
        val body = actual.openVersion(request)
        val admitted = runCatching { owner.requireProviderWork() }
        if (admitted.isFailure) return withCatalogFreezeCleanup({ throw checkNotNull(admitted.exceptionOrNull()) }, body::close)
        return object : CatalogVersionBody {
            override fun metadata() = checked { body.metadata() }
            override fun read(destination: ByteArray, offset: Int, length: Int): Int = checked { body.read(destination, offset, length) }
            override fun close() = body.close() // Never skip actual cleanup on expiry/interruption.
        }
    }

    private fun <T> checked(action: () -> T): T {
        owner.requireProviderWork()
        return action().also { owner.requireProviderWork() }
    }
}
