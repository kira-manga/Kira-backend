package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.SystemPersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentJsonV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredProcessAssemblyV1
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.VersionedSecretBinding
import me.manga.kira.backend.security.aws.AwsSecretVersionLimits
import me.manga.kira.backend.security.aws.AwsSecretsManagerVersionResolver
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.util.concurrent.CancellationException

/** Original freeze public inputs stay AUTHOR provenance; targetDeployment is the real target recipe, never supplied D. */
internal class CatalogGenesisFinalizeRequestV1(val frozen: CatalogGenesisFreezeRequestV1, val targetDeployment: Path) {
    override fun toString(): String = "CatalogGenesisFinalizeRequestV1(independent-input-paths,redacted)"
}

/** One programmatic TARGET finalization invocation. No launcher, PUT/first-D call, runtime activation or recovery approval. */
@Suppress("TooManyFunctions") // Each real acquisition/cleanup owner remains explicit rather than a generic workflow/resource registry.
internal class CatalogGenesisFinalizeV1 private constructor(
    internal val budget: PersistenceTimeBudget,
    private val clock: PersistenceNanoClock,
    private val wallClock: Clock,
    private val secretHttpFactory: (() -> SdkHttpClient)?,
    private val readbackHttpFactory: (() -> SdkHttpClient)?,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val files = CatalogGenesisFreezeInputFilesV1(budget)
    private val assembly = ComplaintDesiredProcessAssemblyV1()
    private var release: CatalogGenesisFinalizeReleaseV1? = null
    private var attempt: CatalogGenesisFinalizeAttemptV1? = null
    private var refresh: CurrentAcceptedCatalogRefreshV1? = null
    private var resolver: AwsSecretsManagerVersionResolver? = null
    private var resolverOpening = false
    private var resolverCloseIssued = false
    private var targetChannel: SeekableByteChannel? = null
    private var targetOpening = false
    private var targetCloseIssued = false
    private var entered = false
    private var closed = false
    private var closeFailure: CatalogGenesisFinalizeExceptionV1? = null

    // Parameterized one-shot stage, not a JVM finalizer. All retained cleanup precedes value-free failure mapping; uncertainty is never retried.
    @Suppress("TooGenericExceptionCaught", "ExceptionRaisedInUnexpectedLocation")
    fun finalize(
        request: CatalogGenesisFinalizeRequestV1,
        secretCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
        sealerCredentials: AwsSessionCredentials? = null,
    ): CatalogGenesisFinalizeResultV1 {
        requireFinalization(caller === Thread.currentThread(), CatalogGenesisFinalizeFailureV1.PROCESS_REFUSED)
        var result: CatalogGenesisFinalizeResultV1? = null
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireFinalization(!entered, CatalogGenesisFinalizeFailureV1.PROCESS_REFUSED)
            entered = true
            val pinPath = requireNotNull(request.frozen.independentPin)
            val frozen = files.readInputs(request.frozen)
            val pin = files.readPin(pinPath) // Fresh independent input, not the pin synthesized from a stored envelope hash.
            val retainedRelease = CatalogGenesisFinalizeReleaseV1(frozen, pin, budget)
            release = retainedRelease
            retainedRelease.openExisting()
            requireRunning()
            val inputs = readTarget(request.targetDeployment)
            inputs.requireTargetFinalizerProfile()
            val acquired = inputs.targetBindings().map { acquire(it, secretCredentials) }
            assembly.assembleTargetFinalizer(inputs, acquired, sealerCredentials)
            val target = assembly.target
            retainedRelease.bindTarget(target, inputs)
            requireRunning()
            assembly.prepareTargetFinalizer(budget)
            val retainedAttempt = CatalogGenesisFinalizeAttemptV1(this, target, retainedRelease, budget)
            attempt = retainedAttempt
            val producer = CurrentAcceptedCatalogRefreshV1.finalizing(
                retainedAttempt,
                primaryReadCredentials,
                replicaReadCredentials,
                readbackHttpFactory,
                wallClock,
                clock::nanoTime,
            )
            refresh = producer
            producer.refresh() // Its in-line pre-effect barrier and actual committed/released outcome precede return.
            requireRunning()
            result = CatalogGenesisFinalizeResultV1()
        } catch (problem: Throwable) {
            failure = finalizationSignal(problem)
            attempt?.abort()
        } finally {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure = preferCatalogFreezeCleanup(failure, cleanup)
            }
        }
        failure?.let { throw boundedFinalizationFailure(it) }
        return checkNotNull(result)
    }

    private fun readTarget(path: Path): ComplaintDesiredDeploymentInputsV1 {
        requireConnectionFree()
        requireRunning()
        requireFinalization(
            path.fileSystem === FileSystems.getDefault() && path.isAbsolute && path == path.normalize() && path.toString().length <= 4096,
        )
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        requireFinalization(
            before.isRegularFile && before.fileKey() != null && before.size() in 1..ComplaintDesiredDeploymentJsonV1.MAX_BYTES.toLong(),
        )
        requireRunning()
        targetOpening = true
        val channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        targetChannel = channel
        targetOpening = false
        return withCatalogFreezeCleanup(
            {
                requireRunning()
                val buffer = ByteBuffer.allocate(ComplaintDesiredDeploymentJsonV1.MAX_BYTES + 1)
                while (buffer.hasRemaining()) {
                    requireRunning()
                    if (channel.read(buffer) == -1) break
                }
                requireRunning()
                val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                requireFinalization(
                    after.isRegularFile && after.fileKey() == before.fileKey() && after.size() == before.size() &&
                        after.lastModifiedTime() == before.lastModifiedTime() && buffer.position().toLong() == before.size(),
                )
                ComplaintDesiredDeploymentJsonV1.parse(buffer.array().copyOf(buffer.position())).also { requireRunning() }
            },
            {
                closeTargetChannel()
                requireRunning()
            },
        )
    }

    private fun acquire(binding: VersionedSecretBinding, credentials: AwsSessionCredentials): AcquiredVersionedSecret {
        requireConnectionFree()
        requireRunning()
        requireFinalization(resolver == null && !resolverOpening, CatalogGenesisFinalizeFailureV1.CLEANUP_UNPROVEN)
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
        requireFinalization(resolver == null && !resolverOpening, CatalogGenesisFinalizeFailureV1.CLEANUP_UNPROVEN)
    }

    private fun closeTargetChannel() {
        requireConnectionFree()
        if (!targetCloseIssued) {
            targetCloseIssued = true
            targetChannel?.close()
            targetChannel = null
        }
        requireFinalization(targetChannel == null && !targetOpening, CatalogGenesisFinalizeFailureV1.CLEANUP_UNPROVEN)
    }

    internal fun owns(candidate: CatalogGenesisFinalizeAttemptV1): Boolean = !closed && caller === Thread.currentThread() && attempt === candidate
    internal fun ownsRefresh(candidate: CurrentAcceptedCatalogRefreshV1): Boolean = !closed && caller === Thread.currentThread() && refresh === candidate

    /** No connection-free precondition here: fixed phase checks only compare retained local facts and the original clock. */
    internal fun requireRunning() {
        requireFinalization(caller === Thread.currentThread() && !closed, CatalogGenesisFinalizeFailureV1.PROCESS_REFUSED)
        requireFinalization(!Thread.currentThread().isInterrupted, CatalogGenesisFinalizeFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
    }

    override fun close() {
        requireFinalization(caller === Thread.currentThread(), CatalogGenesisFinalizeFailureV1.CLEANUP_UNPROVEN)
        if (closed) {
            closeFailure?.let { throw it }
            return
        }
        closed = true
        closeFailure = CatalogGenesisFinalizeExceptionV1(CatalogGenesisFinalizeFailureV1.CLEANUP_UNPROVEN)
        attempt?.abort()
        val outcomes = listOf(
            runCatching { refresh?.close() },
            runCatching(assembly::close), // Stop all original roots before awaiting any shared Timer proof.
            runCatching(files::close),
            runCatching(::closeTargetChannel),
            runCatching(::closeResolver),
            runCatching { assembly.requireCleanup(budget) },
            runCatching { release?.close() }, // Active phase quarantine may forbid dispatch: retain it, never fake release.
        )
        var failure: Throwable? = null
        outcomes.forEach { outcome -> outcome.exceptionOrNull()?.let { failure = preferCatalogFreezeCleanup(failure, it) } }
        failure?.let { throw boundedFinalizationFailure(preferCatalogFreezeCleanup(it, checkNotNull(closeFailure))) }
        requireFinalization(!Thread.currentThread().isInterrupted, CatalogGenesisFinalizeFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
        closeFailure = null
    }

    override fun toString(): String = "CatalogGenesisFinalizeV1(one-original-target-attempt,redacted,no-runtime-authority)"

    companion object {
        fun begin(clock: PersistenceNanoClock = SystemPersistenceNanoClock): CatalogGenesisFinalizeV1 =
            CatalogGenesisFinalizeV1(PersistenceTimeBudget.start(60_000, clock), clock, Clock.systemUTC(), null, null)

        /** Raw HTTP/clock substitution only; actual secrets, TARGET assembly, SDK/raw verifier, SQL and filesystem execute. */
        internal fun withHttpFixtures(
            secretHttpFactory: () -> SdkHttpClient,
            readbackHttpFactory: () -> SdkHttpClient,
            clock: PersistenceNanoClock = SystemPersistenceNanoClock,
            wallClock: Clock = Clock.systemUTC(),
        ): CatalogGenesisFinalizeV1 = CatalogGenesisFinalizeV1(
            PersistenceTimeBudget.start(60_000, clock),
            clock,
            wallClock,
            secretHttpFactory,
            readbackHttpFactory,
        )
    }
}

/** Returned only after actual full cleanup; historical software result, never an activation or restore capability. */
internal class CatalogGenesisFinalizeResultV1 internal constructor() {
    override fun toString(): String = "CatalogGenesisFinalizeResultV1(projected,historical-only)"
}

internal enum class CatalogGenesisFinalizeFailureV1 {
    INPUT_REFUSED,
    PROCESS_REFUSED,
    AUTHENTICATION_REFUSED,
    RECOVERY_REQUIRED,
    TIME_BUDGET_EXHAUSTED,
    INTERRUPTED,
    CLEANUP_UNPROVEN,
}

internal class CatalogGenesisFinalizeExceptionV1(val code: CatalogGenesisFinalizeFailureV1) :
    RuntimeException("Catalog genesis finalization refused: ${code.name}.")

internal fun requireFinalization(condition: Boolean, code: CatalogGenesisFinalizeFailureV1 = CatalogGenesisFinalizeFailureV1.RECOVERY_REQUIRED) {
    if (!condition) throw CatalogGenesisFinalizeExceptionV1(code)
}

@Suppress("InstanceOfCheckForException")
private fun boundedFinalizationFailure(problem: Throwable): CatalogGenesisFinalizeExceptionV1 {
    val failure = finalizationSignal(problem)
    if (failure is Error || failure is CancellationException || failure is InterruptedException) throw failure
    if (failure is CatalogGenesisFinalizeExceptionV1) return failure
    val code = when {
        failure is PersistencePhaseException && !failure.cleanupProven -> CatalogGenesisFinalizeFailureV1.CLEANUP_UNPROVEN

        failure is PersistenceBoundaryException && failure.code === PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED ->
            CatalogGenesisFinalizeFailureV1.TIME_BUDGET_EXHAUSTED

        failure is PersistencePhaseException && failure.code === PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED ->
            CatalogGenesisFinalizeFailureV1.TIME_BUDGET_EXHAUSTED

        failure is CatalogReadbackException && failure.code === CatalogReadbackFailure.LIMIT_EXCEEDED ->
            CatalogGenesisFinalizeFailureV1.TIME_BUDGET_EXHAUSTED

        failure is CatalogGenesisCustodyExceptionV1 -> CatalogGenesisFinalizeFailureV1.RECOVERY_REQUIRED

        else -> CatalogGenesisFinalizeFailureV1.PROCESS_REFUSED
    }
    return CatalogGenesisFinalizeExceptionV1(code)
}

@Suppress("InstanceOfCheckForException")
private fun finalizationSignal(failure: Throwable): Throwable {
    val typedInterruption = failure is CatalogGenesisFinalizeExceptionV1 && failure.code === CatalogGenesisFinalizeFailureV1.INTERRUPTED
    if (failure is InterruptedException || failure is InterruptedIOException || typedInterruption) {
        Thread.currentThread().interrupt()
    }
    return when {
        failure is Error -> failure
        failure is CancellationException -> CancellationException("Catalog genesis finalization cancelled.")
        Thread.currentThread().isInterrupted -> InterruptedException("Catalog genesis finalization interrupted.")
        else -> failure
    }
}
