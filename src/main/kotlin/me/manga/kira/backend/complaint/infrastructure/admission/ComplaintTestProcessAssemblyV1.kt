package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePoolLaunchProfile
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustRelease
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.SystemPersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundTestActivationConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.preferCatalogFreezeCleanup
import me.manga.kira.backend.complaint.infrastructure.catalog.withCatalogFreezeCleanup
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.terminal.VersionBoundTestOrdinarySealV1
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.JwtKeyProvider
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.VersionBoundInstallationJwtConfiguration
import me.manga.kira.backend.security.VersionBoundTestComplaintConsumerConfigurationV1
import me.manga.kira.backend.security.VersionBoundTestComplaintConsumerInputsV1
import me.manga.kira.backend.security.VersionedSecretBinding
import me.manga.kira.backend.security.aws.AwsSecretVersionLimits
import me.manga.kira.backend.security.aws.AwsSecretsManagerVersionResolver
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.concurrent.CancellationException

/**
 * Retained BEFORE file/provider construction. One protected TEST recipe becomes the actual cold
 * normal-pool target supplied to existing PROJECT/registration, not a DTO beside another graph.
 * Input ancestors/authorization MUST be independently protected: stable-file checks authenticate
 * neither the submitter nor the declarations. No beans, settings lookup, operator or ready flag.
 */
internal class ComplaintTestProcessAssemblyV1 private constructor(
    private val nanoClock: PersistenceNanoClock,
    private val wallClock: () -> Instant,
    private val runtimeLaunchProfile: PersistencePoolLaunchProfile,
    private val secretHttpFixture: (() -> SdkHttpClient)?,
    private val stsHttpFixture: ((remainingMillis: () -> Int) -> SdkHttpClient)?,
    private val kmsHttpFixture: ((remainingMillis: () -> Int) -> SdkHttpClient)?,
    private val s3HttpFixture: ((remainingMillis: () -> Int) -> SdkHttpClient)?,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val setupBudget = PersistenceTimeBudget.start(60_000, nanoClock)
    private var entered = false
    private var ready = false
    @Volatile private var stopping = false
    private var channel: SeekableByteChannel? = null
    private var channelOpening = false
    private var channelCloseIssued = false
    private var resolver: AwsSecretsManagerVersionResolver? = null
    private var resolverOpening = false
    private var resolverCloseIssued = false
    private var resolverCloseFailed = false
    private var persistence: VersionBoundPersistenceConfiguration? = null
    private var owner: PersistenceJdbcLifecycleOwner? = null
    private var lanes: JournalPublicationLanesV1? = null
    private var seal: VersionBoundTestOrdinarySealV1? = null
    private var assembled: VersionBoundTestNamespaceProcessV1? = null
    private var closeFailure: Throwable? = null

    val target: VersionBoundTestNamespaceProcessV1
        get() {
            requireTestDeployment(ready && !stopping, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            return checkNotNull(assembled).also { it.requireUnchangedConfiguration() }
        }

    /** Actual lifecycle owner, never another pool configuration or a way to change UNKNOWN launch. */
    internal val lifecycleOwner: PersistenceJdbcLifecycleOwner
        get() {
            target.requireUnchangedConfiguration()
            return checkNotNull(owner)
        }

    @Suppress("TooGenericExceptionCaught")
    fun assemble(manifest: Path, secretCredentials: AwsSessionCredentials, sealBootstrapCredentials: AwsSessionCredentials) {
        var failureCode = ComplaintTestDeploymentFailureV1.INPUT_REFUSED
        try {
            checkpoint()
            requireTestDeployment(!entered, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            entered = true
            val inputs = readManifest(manifest)
            checkpoint() // All grammar/identity/policy validation precedes the first immutable lookup.
            failureCode = ComplaintTestDeploymentFailureV1.PROVIDER_REFUSED
            val acquired = inputs.allBindings().map { acquire(it, secretCredentials) }
            failureCode = ComplaintTestDeploymentFailureV1.PROCESS_REFUSED
            assembleAcquired(inputs, acquired, sealBootstrapCredentials)
            checkpoint()
            checkNotNull(assembled).requireUnchangedConfiguration()
            ready = true
        } catch (problem: Throwable) {
            // An interrupted/expired setup still attempts every original close, without renewed setup time.
            val failure = preferCatalogFreezeCleanup(null, problem)
            val cleanup = runCatching { closeOwned(setupBudget) }.exceptionOrNull()
            throw boundedTestDeploymentFailure(if (cleanup == null) failure else preferCatalogFreezeCleanup(failure, cleanup), failureCode)
        }
    }

    private fun readManifest(path: Path): ComplaintTestDeploymentInputsV1 {
        checkpoint()
        requireTestDeployment(
            path.fileSystem === FileSystems.getDefault() && path.isAbsolute && path == path.normalize() && path.toString().length <= 4096,
            ComplaintTestDeploymentFailureV1.INPUT_REFUSED,
        )
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        requireTestDeployment(
            before.isRegularFile && before.fileKey() != null && before.size() in 1..ComplaintTestDeploymentJsonV1.MAX_BYTES.toLong(),
            ComplaintTestDeploymentFailureV1.INPUT_REFUSED,
        )
        checkpoint()
        channelOpening = true
        val opened = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).also {
            channel = it
            channelOpening = false
        }
        return withCatalogFreezeCleanup({
            checkpoint()
            val buffer = ByteBuffer.allocate(ComplaintTestDeploymentJsonV1.MAX_BYTES + 1)
            while (buffer.hasRemaining()) {
                checkpoint()
                if (opened.read(buffer) == -1) break
            }
            checkpoint()
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            requireTestDeployment(
                after.isRegularFile && after.fileKey() == before.fileKey() && after.size() == before.size() &&
                    after.lastModifiedTime() == before.lastModifiedTime() && buffer.position().toLong() == before.size(),
                ComplaintTestDeploymentFailureV1.INPUT_REFUSED,
            )
            ComplaintTestDeploymentJsonV1.parse(buffer.array().copyOf(buffer.position())).also { checkpoint() }
        }, {
            closeChannel()
            checkpoint()
        })
    }

    private fun acquire(binding: VersionedSecretBinding, credentials: AwsSessionCredentials): AcquiredVersionedSecret {
        checkpoint()
        requireTestDeployment(resolver == null && !resolverOpening && !resolverCloseFailed, ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
        val requestMillis = setupBudget.remainingMillis(5_000)
        val limits = AwsSecretVersionLimits(requestMillis, minOf(2_000L, requestMillis).toInt(), minOf(2_000L, requestMillis).toInt())
        val region = binding.version.resourceArn.split(':')[3]
        resolverOpening = true
        val fixture = secretHttpFixture
        val opened = if (fixture == null) {
            AwsSecretsManagerVersionResolver.open(region, credentials, limits)
        } else {
            AwsSecretsManagerVersionResolver.withHttpFixture(region, credentials, limits, fixture, nanoClock::nanoTime)
        }
        resolver = opened // Before the first SDK request. An unreturned construction remains ambiguous, never a usable target.
        resolverOpening = false
        resolverCloseIssued = false
        return withCatalogFreezeCleanup({
            checkpoint() // Charge native/SDK construction to the original setup budget before dispatch.
            AcquiredVersionedSecret.acquire(binding, opened).also { acquired ->
                requireTestDeployment(sameBinding(binding, acquired.descriptor), ComplaintTestDeploymentFailureV1.PROVIDER_REFUSED)
                checkpoint()
            }
        }, {
            closeResolver()
            checkpoint()
        })
    }

    private fun assembleAcquired(
        inputs: ComplaintTestDeploymentInputsV1,
        acquired: List<AcquiredVersionedSecret>,
        credentials: AwsSessionCredentials,
    ) {
        checkpoint()
        val expected = inputs.allBindings()
        requireTestDeployment(expected.size == acquired.size, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        val secrets = expected.associateWith { binding -> acquired.single { sameBinding(it.descriptor, binding) } }
        fun secret(binding: VersionedSecretBinding): AcquiredVersionedSecret = checkNotNull(secrets[binding])
        val user = JwtKeyProvider.fromAcquired(secret(inputs.userKey), inputs.jwtSettings)
        val jwt = VersionBoundInstallationJwtConfiguration.fromAcquired(inputs.installationActiveKeyId, inputs.installationKeys().map(::secret), user)
        val routing = TestOwnerDeleteJournalRoutingV1.fromAcquired(inputs.journal, inputs.routingKeys().map(::secret))
        val consumers = VersionBoundTestComplaintConsumerConfigurationV1.fromAcquired(
            jwt, inputs.capacity, inputs.journal,
            VersionBoundTestComplaintConsumerInputsV1(
                secret(inputs.admissionCurrent), inputs.admissionPrevious?.let(::secret), inputs.cursorActiveKeyId, inputs.cursorKeys().map(::secret), routing,
            ), inputs.consumerSettings,
        )
        val db = inputs.database
        val configuration = VersionBoundPersistenceConfiguration.fromAcquired(
            secret(inputs.runtimePassword), db.host, db.port, db.name, db.runtimeUsername, db.ordinaryCapacity,
            inputs.publicTrustPem(), inputs.protectedTrustParent,
        ).also { persistence = it }
        checkpoint()
        val retainedOwner = configuration.bindLifecycleOwner().also { owner = it } // BEFORE shell binding or any partial-pool failure.
        val pools = retainedOwner.bindVersionBoundPools(runtimeLaunchProfile, nanoClock)
        val publication = JournalPublicationLanesV1(inputs.journal).also { lanes = it }
        val activation = VersionBoundTestActivationConfigurationV1.fromRetained(
            pools, inputs.catalog, inputs.journal, inputs.activationSigningKey, inputs.initialWriterRegistryBytes(), inputs.activationTotalAttemptMillis,
        )
        val ordinarySeal = VersionBoundTestOrdinarySealV1.fromIndependentInputs(
            routing, publication, inputs.sealerMapping, inputs.retention, credentials, inputs.sealerSessionName,
            inputs.sealerLimits, nanoClock::nanoTime, wallClock, stsHttpFixture, kmsHttpFixture, s3HttpFixture,
        ).also { seal = it } // BEFORE full-D encoding. The seal borrows publication; this assembly owns it.
        checkpoint()
        assembled = VersionBoundTestNamespaceProcessV1.fromRetained(
            consumers, pools, inputs.implementationSchema, inputs.desiredGeneration, inputs.databaseIdentity, inputs.restoreIdentity,
            publication, inputs.catalog, activation, ordinarySeal,
        )
        // No public-trust preparation, JDBC connection, STS/KMS/S3 construction, activation or registration was performed.
    }

    private fun checkpoint() {
        requireConnectionFree()
        requireTestDeployment(caller === Thread.currentThread() && !stopping, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        requireTestDeployment(!Thread.currentThread().isInterrupted, ComplaintTestDeploymentFailureV1.INTERRUPTED)
        setupBudget.remainingMillis(1)
    }

    private fun closeChannel() {
        if (!channelCloseIssued) {
            channelCloseIssued = true
            channel?.close()
            channel = null
        }
        requireTestDeployment(channel == null && !channelOpening, ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
    }

    private fun closeResolver() {
        val selected = resolver
        if (selected != null && !resolverCloseIssued) {
            resolverCloseIssued = true
            val result = runCatching(selected::close)
            if (result.isSuccess) {
                resolver = null
            } else {
                resolverCloseFailed = true
                throw preferCatalogFreezeCleanup(
                    result.exceptionOrNull(),
                    ComplaintTestDeploymentExceptionV1(ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN),
                )
            }
        }
        requireTestDeployment(resolver == null && !resolverOpening && !resolverCloseFailed, ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
    }

    /** Setup time never governs subsequent runtime operations; shutdown has its own bounded original attempt. */
    override fun close() {
        if (stopping) {
            closeFailure?.let { throw it }
            return
        }
        val timing = runCatching { if (ready) PersistenceTimeBudget.start(10_000, nanoClock) else setupBudget }
        closeOwned(timing.getOrNull(), timing.exceptionOrNull()) // Even a failed clock cannot skip actual stop/close attempts.
    }

    private fun closeOwned(budget: PersistenceTimeBudget?, timingFailure: Throwable? = null) {
        requireConnectionFree()
        requireTestDeployment(caller === Thread.currentThread(), ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
        if (stopping) {
            closeFailure?.let { throw it }
            return
        }
        stopping = true // Permanently invalidate any retained target before stopping even one native owner.
        var failure: Throwable? = timingFailure?.let { preferCatalogFreezeCleanup(null, it) }
        fun attempt(work: () -> Unit) {
            runCatching(work).exceptionOrNull()?.let { failure = preferCatalogFreezeCleanup(failure, it) }
        }
        attempt { owner?.requestShutdown() }
        attempt { closeChannel() }
        attempt { closeResolver() }
        attempt { seal?.close() }
        attempt { lanes?.close() }
        attempt { owner?.versionBoundPools?.close() }
        attempt {
            owner?.let { retained ->
                requireTestDeployment(budget != null, ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
                val originalBudget = checkNotNull(budget)
                requireTestDeployment(
                    retained.observeShutdown(originalBudget) === PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED,
                    ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN,
                )
                // Never delete trust on a merely requested/expired/ambiguous root, pool or shared Timer shutdown.
                requireTestDeployment(
                    retained.releasePublicTrustAfterShutdown() === PersistencePublicTrustRelease.RELEASED,
                    ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN,
                )
                originalBudget.remainingMillis(1)
            }
        }
        failure?.let {
            val bounded = boundedTestDeploymentFailure(
                preferCatalogFreezeCleanup(it, ComplaintTestDeploymentExceptionV1(ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)),
                ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN,
            )
            closeFailure = bounded
            throw bounded
        }
        if (Thread.currentThread().isInterrupted) {
            throw ComplaintTestDeploymentExceptionV1(ComplaintTestDeploymentFailureV1.INTERRUPTED).also { closeFailure = it }
        }
    }

    override fun toString(): String = "ComplaintTestProcessAssemblyV1(protected-TEST-intake,cold-normal-target,redacted,no-authority)"

    private fun sameBinding(left: VersionedSecretBinding, right: VersionedSecretBinding): Boolean =
        left.family == right.family && left.purpose == right.purpose && left.logicalKeyId == right.logicalKeyId && left.version == right.version

    companion object {
        /** Begin BEFORE manifest I/O; default launch remains UNKNOWN and cannot be upgraded on this owner. */
        fun begin(): ComplaintTestProcessAssemblyV1 = ComplaintTestProcessAssemblyV1(
            SystemPersistenceNanoClock, Instant::now, PersistencePoolLaunchProfile.UNKNOWN, null, null, null, null,
        )

        /** Only raw provider HTTP/clocks and the existing explicit cold controlled launch selection may vary in tests. */
        internal fun withHttpFixture(
            secretHttpFactory: () -> SdkHttpClient,
            nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
            wallClock: () -> Instant = Instant::now,
            runtimeLaunchProfile: PersistencePoolLaunchProfile = PersistencePoolLaunchProfile.UNKNOWN,
            sts: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            kms: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            s3: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
        ): ComplaintTestProcessAssemblyV1 = ComplaintTestProcessAssemblyV1(nanoClock, wallClock, runtimeLaunchProfile, secretHttpFactory, sts, kms, s3)
    }
}

internal enum class ComplaintTestDeploymentFailureV1 { INPUT_REFUSED, PROCESS_REFUSED, PROVIDER_REFUSED, TIME_BUDGET_EXHAUSTED, INTERRUPTED, CLEANUP_UNPROVEN }

internal class ComplaintTestDeploymentExceptionV1(val code: ComplaintTestDeploymentFailureV1) :
    RuntimeException("TEST deployment intake refused.", null, false, false)

internal fun requireTestDeployment(condition: Boolean, code: ComplaintTestDeploymentFailureV1) {
    if (!condition) throw ComplaintTestDeploymentExceptionV1(code)
}

private fun boundedTestDeploymentFailure(problem: Throwable, otherwise: ComplaintTestDeploymentFailureV1): Throwable = when (problem) {
    is Error -> problem
    is CancellationException -> CancellationException("TEST deployment intake cancelled.")
    is InterruptedException -> {
        Thread.currentThread().interrupt()
        ComplaintTestDeploymentExceptionV1(ComplaintTestDeploymentFailureV1.INTERRUPTED)
    }
    is ComplaintTestDeploymentExceptionV1 -> problem
    is PersistenceBoundaryException -> ComplaintTestDeploymentExceptionV1(
        if (problem.code === PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) ComplaintTestDeploymentFailureV1.TIME_BUDGET_EXHAUSTED else otherwise,
    )
    else -> ComplaintTestDeploymentExceptionV1(otherwise)
}
