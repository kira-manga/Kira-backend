package me.manga.kira.backend.security.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochSealCustodyV1
import me.manga.kira.backend.security.EpochSealAttemptV1
import me.manga.kira.backend.security.EpochSealCodecV1
import me.manga.kira.backend.security.EpochSealContentV1
import me.manga.kira.backend.security.EpochSealEnvelopeV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.awscore.defaultsmode.DefaultsMode
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.regions.PartitionMetadata
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.ServiceMetadataConfiguration
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
import java.net.Proxy
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/**
 * Cold, one-shot lower STS protocol owner. Three real signed SDK requests, no default credentials,
 * arbitrary policies/tags, refresh or retries. The closed consumer checks actual retained post-commit
 * custody; standalone observation remains a lower fixture, never current or activation authority.
 *
 * Expectations/identity do not prove installed trust, effective principal isolation or policies.
 * PutObjectRetention is needed for an initial locked PUT; this session profile cannot attest that
 * standalone retention extension is impossible. The exact LIST prefix may lexically match siblings;
 * a future fixed readback must still reject any key other than the committed key. KMS context is
 * unchanged and opaque. No raw credential accessor or caller-selected S3/KMS client factory exists.
 */
internal class AwsEpochSealStsAdapter private constructor(
    private val routing: VersionBoundComplaintJournalRouting,
    private val sourceCredentials: AwsSessionCredentials,
    private val binding: AwsEpochSealStsBinding,
    private val limits: AwsEpochSealStsLimits,
    private val httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val kmsHttpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val nanoTime: () -> Long,
    private val wallClock: () -> Instant,
) : AutoCloseable {
    private val lifecycle = Any()
    private val used = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()
    private val kmsConstruction = AwsJournalDataKeyAdapter.Construction()

    @Volatile private var source: EpochSealStsClientOwner? = null

    @Volatile private var target: EpochSealStsClientOwner? = null

    @Volatile private var session: Session? = null

    /** Syntax/response binding only: an arbitrary key or historical prepared DTO cannot become publication authority here. */
    fun acquire(exactSealObjectKey: String, attempt: EpochSealAttemptV1): ObservedSession {
        requireConnectionFree()
        return epochSealStsCall {
            requireEpochSealSts(!closed.get() && used.compareAndSet(false, true), EpochSealStsFailure.INVALID_INPUT)
            val result = runCatching {
                attempt.requireOwner(routing)
                val acquisition = EpochSealStsAcquisition(attempt, nanoTime)
                acquisition.remainingMillis(1)
                val policy = EpochSealStsPolicy.forKey(routing.journalConfiguration, exactSealObjectKey)
                val name = "kira-seal-${UUID.randomUUID()}"
                val region = region()
                val source = newClient(sourceCredentials, region, acquisition, isTarget = false)
                identity(source, acquisition, binding.sourceAccountId, binding.sourceArn, binding.sourceUserId)
                val timing = EpochSealStsSessionTiming(attempt, limits, nanoTime, wallClock)
                val assumed = assume(source, acquisition, policy, name, timing)
                acquisition.remainingMillis(1)
                val target = newClient(assumed.credentials, region, acquisition, isTarget = true)
                val identity = identity(target, acquisition, binding.targetAccountId, binding.targetArn(name), "${binding.targetRoleId}:$name")
                timing.requireUsable(assumed.expiration)
                synchronized(lifecycle) {
                    acquisition.remainingMillis(1)
                    requireEpochSealSts(!closed.get(), EpochSealStsFailure.ACQUISITION_FAILED)
                    Session(exactSealObjectKey, attempt, assumed, identity, timing).also { session = it }
                }
            }
            if (result.isFailure) return@epochSealStsCall withEpochSealStsCleanup({ result.getOrThrow() }, ::close)
            result.getOrThrow()
        }
    }

    /** No observation escapes: only this exact registered original operation can use the acquired private session. */
    internal fun acquireOwned(custody: CatalogEpochSealCustodyV1, attempt: EpochSealAttemptV1, content: EpochSealContentV1) {
        custody.requireSts(this, attempt, content)
        acquire(content.route.objectKey, attempt)
        custody.requireSts(this, attempt, content)
    }

    internal fun sealOwned(custody: CatalogEpochSealCustodyV1, attempt: EpochSealAttemptV1, content: EpochSealContentV1): EpochSealEnvelopeV1 =
        epochSealStsCall {
            custody.requireSts(this, attempt, content)
            val selected = synchronized(lifecycle) {
                requireEpochSealSts(!closed.get() && session != null)
                checkNotNull(session)
            }
            selected.seal(custody, attempt, content)
        }

    private fun newClient(
        credentials: AwsSessionCredentials,
        region: Region,
        acquisition: EpochSealStsAcquisition,
        isTarget: Boolean,
    ): EpochSealStsClientOwner {
        val owner = synchronized(lifecycle) {
            requireEpochSealSts(!closed.get())
            EpochSealStsClientOwner(region, credentials, limits, acquisition, httpFactory).also {
                if (isTarget) target = it else source = it // Retain BEFORE any HTTP factory/SDK construction.
            }
        }
        owner.open()
        acquisition.remainingMillis(1)
        return owner
    }

    private fun call(action: String, parameters: Map<String, String>, acquisition: EpochSealStsAcquisition): EpochSealStsCall =
        EpochSealStsCall(action, parameters, acquisition, acquisition.remainingMillis(limits.requestTimeoutMillis), nanoTime)

    private fun identity(owner: EpochSealStsClientOwner, acquisition: EpochSealStsAcquisition, account: String, arn: String, userId: String): Identity {
        val call = call("GetCallerIdentity", emptyMap(), acquisition)
        return owner.execute(
            call,
            { sdk, overrides -> sdk.getCallerIdentity(GetCallerIdentityRequest.builder().overrideConfiguration(overrides).build()) },
        ) { response: GetCallerIdentityResponse, report: EpochSealStsWireReport ->
            report.requireValue("Account", response.account())
            report.requireValue("Arn", response.arn())
            report.requireValue("UserId", response.userId())
            requireEpochSealSts(response.account() == account && response.arn() == arn && response.userId() == userId, EpochSealStsFailure.IDENTITY_MISMATCH)
            Identity(response.account(), response.arn(), response.userId())
        }
    }

    private fun assume(
        source: EpochSealStsClientOwner,
        acquisition: EpochSealStsAcquisition,
        policy: String,
        name: String,
        timing: EpochSealStsSessionTiming,
    ): Assumed {
        val call = call(
            "AssumeRole",
            mapOf("RoleArn" to binding.targetRoleArn, "RoleSessionName" to name, "Policy" to policy, "DurationSeconds" to "900"),
            acquisition,
        )
        return source.execute(
            call,
            { sdk, overrides ->
                sdk.assumeRole(
                    AssumeRoleRequest.builder().roleArn(binding.targetRoleArn).roleSessionName(name).policy(policy)
                        .durationSeconds(EpochSealStsPolicy.SESSION_SECONDS).overrideConfiguration(overrides).build(),
                )
            },
        ) { response, report ->
            val user = response.assumedRoleUser()
            requireEpochSealSts(user != null)
            report.requireValue("Arn", checkNotNull(user).arn())
            report.requireValue("AssumedRoleId", user.assumedRoleId())
            requireEpochSealSts(
                user.arn() == binding.targetArn(name) && user.assumedRoleId() == "${binding.targetRoleId}:$name",
                EpochSealStsFailure.IDENTITY_MISMATCH,
            )
            val credentials = response.credentials()
            requireEpochSealSts(credentials != null)
            val actual = checkNotNull(credentials)
            report.requireCredential("AccessKeyId", actual.accessKeyId())
            report.requireCredential("SecretAccessKey", actual.secretAccessKey())
            report.requireCredential("SessionToken", actual.sessionToken())
            @Suppress("DEPRECATION")
            val packed = response.packedPolicySize()
            report.requireOptionalSize("PackedPolicySize", packed, 100)
            report.requireOptionalSize("SessionTokenUtilization", response.sessionTokenUtilization(), 100)
            report.requireOptionalSize("SessionTokenSize", response.sessionTokenSize(), 16_384, actual.sessionToken().length)
            requireEpochSealSts(actual.accessKeyId().length in 16..128 && actual.accessKeyId().all { it in 'A'..'Z' || it in '0'..'9' })
            val expiration = report.expiration()
            requireEpochSealSts(actual.expiration() == expiration)
            timing.requireUsable(expiration)
            val acquired = AwsSessionCredentials.create(actual.accessKeyId(), actual.secretAccessKey(), actual.sessionToken())
            validateCredentials(acquired)
            requireEpochSealSts(acquired.accessKeyId() != sourceCredentials.accessKeyId(), EpochSealStsFailure.IDENTITY_MISMATCH)
            Assumed(acquired, expiration)
        }
    }

    /** The only concrete implementation is private, produced only after all three actual responses. */
    sealed interface ObservedSession : AutoCloseable {
        val expiration: Instant
        val accountId: String
        val arn: String
        val roleId: String
        val sessionName: String
        fun checkUsable()
    }

    private inner class Session(
        private val objectKey: String,
        private val original: EpochSealAttemptV1,
        assumed: Assumed,
        observed: Identity,
        private val timing: EpochSealStsSessionTiming,
    ) : ObservedSession {
        // SDK/credentials never leave the closed same-key/J/attempt consumer.
        private var credentials: AwsSessionCredentials? = assumed.credentials
        private val encoded = AtomicBoolean()
        override val expiration: Instant = assumed.expiration
        override val accountId: String = observed.accountId
        override val arn: String = observed.arn
        override val roleId: String = observed.userId.substringBefore(':')
        override val sessionName: String = observed.userId.substringAfter(':')

        override fun checkUsable() = epochSealStsCall {
            requireConnectionFree()
            synchronized(lifecycle) {
                requireEpochSealSts(!closed.get() && credentials != null && session === this, EpochSealStsFailure.SESSION_EXPIRED)
                timing.requireUsable(expiration)
            }
        }

        fun seal(custody: CatalogEpochSealCustodyV1, attempt: EpochSealAttemptV1, content: EpochSealContentV1): EpochSealEnvelopeV1 {
            custody.requireSts(this@AwsEpochSealStsAdapter, attempt, content)
            requireEpochSealSts(original === attempt && objectKey == content.route.objectKey && content.belongsTo(routing))
            checkUsable()
            requireEpochSealSts(encoded.compareAndSet(false, true), EpochSealStsFailure.INVALID_INPUT)
            val material = synchronized(lifecycle) { checkNotNull(credentials) }
            val keys = kmsConstruction.openEpochSeal(
                routing.journalConfiguration,
                material,
                kmsHttpFactory,
                nanoTime,
                sealAttempt = original,
            )
            custody.requireSts(this@AwsEpochSealStsAdapter, original, content)
            checkUsable()
            val candidate = EpochSealCodecV1(routing, keys, nanoTime = nanoTime).seal(content, original)
            val checked = runCatching {
                custody.requireSts(this@AwsEpochSealStsAdapter, original, content)
                checkUsable()
                candidate
            }
            if (checked.isFailure) return withEpochSealStsCleanup({ checked.getOrThrow() }, candidate::close)
            return checked.getOrThrow()
        }

        fun discard() {
            credentials = null
        } // Strings/SDK copies cannot be claimed zeroized; close does not revoke AWS sessions.
        override fun close() = this@AwsEpochSealStsAdapter.close()
        override fun toString(): String = "ObservedEpochSealStsSession(private-custody,redacted,no-publication-authority)"
    }

    @Synchronized
    override fun close() {
        synchronized(lifecycle) {
            closed.set(true)
            session?.discard()
        }
        val failure = runCatching {
            withEpochSealStsCleanup(
                { epochSealStsClose { kmsConstruction.close() } },
                { withEpochSealStsCleanup({ target?.close() }, { source?.close() }) },
            )
        }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceEpochSealStsFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
    }

    private class Assumed(val credentials: AwsSessionCredentials, val expiration: Instant) {
        override fun toString(): String = "AssumedEpochSealStsResponse(redacted)"
    }

    private class Identity(val accountId: String, val arn: String, val userId: String) {
        override fun toString(): String = "ObservedEpochSealStsIdentity(redacted,no-policy-proof)"
    }

    private fun region(): Region {
        requireEpochSealSts(
            System.getProperty(SdkSystemSetting.AWS_PARTITIONS_FILE.property()) == null &&
                System.getenv(SdkSystemSetting.AWS_PARTITIONS_FILE.environmentVariable()) == null,
            EpochSealStsFailure.INVALID_INPUT,
        )
        val id = routing.journalConfiguration.declaration().journalLocation.region
        val region = Region.regions().singleOrNull { it.id() == id }
        requireEpochSealSts(region != null && PartitionMetadata.of(region).id() == "aws", EpochSealStsFailure.INVALID_INPUT)
        val emptyProfile = ProfileFile.aggregator().build()
        val metadata = StsClient.serviceMetadata().reconfigure(
            ServiceMetadataConfiguration.builder().profileFile { emptyProfile }.profileName(PROFILE_NAME).build(),
        )
        requireEpochSealSts(region in metadata.regions(), EpochSealStsFailure.INVALID_INPUT)
        return checkNotNull(region)
    }

    override fun toString(): String = "AwsEpochSealStsAdapter(cold-lower-protocol,redacted,no-policy-or-current-authority)"

    companion object {
        /** Cold only; the caller must retain this concrete owner before acquisition/client construction. */
        fun open(
            routing: VersionBoundComplaintJournalRouting,
            sourceCredentials: AwsSessionCredentials,
            binding: AwsEpochSealStsBinding,
            limits: AwsEpochSealStsLimits = AwsEpochSealStsLimits(),
        ): AwsEpochSealStsAdapter = create(
            routing,
            sourceCredentials,
            binding,
            limits,
            { remaining -> urlClient(limits, remaining) },
            ::journalKmsUrlConnectionClient,
            System::nanoTime,
            Instant::now,
        )

        /** Actual StsClient signing/Query/XML behavior; only its public HTTP SPI and clocks are substituted. */
        fun withHttpFixture(
            routing: VersionBoundComplaintJournalRouting,
            sourceCredentials: AwsSessionCredentials,
            binding: AwsEpochSealStsBinding,
            limits: AwsEpochSealStsLimits,
            httpFactory: () -> SdkHttpClient,
            nanoTime: () -> Long = System::nanoTime,
            wallClock: () -> Instant = Instant::now,
            kmsHttpFactory: (remainingMillis: () -> Int) -> SdkHttpClient = ::journalKmsUrlConnectionClient,
        ): AwsEpochSealStsAdapter = create(routing, sourceCredentials, binding, limits, { httpFactory() }, kmsHttpFactory, nanoTime, wallClock)

        private fun create(
            routing: VersionBoundComplaintJournalRouting,
            credentials: AwsSessionCredentials,
            binding: AwsEpochSealStsBinding,
            limits: AwsEpochSealStsLimits,
            factory: (remainingMillis: () -> Int) -> SdkHttpClient,
            kmsFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
            nanoTime: () -> Long,
            wallClock: () -> Instant,
        ): AwsEpochSealStsAdapter {
            requireConnectionFree()
            return epochSealStsCall {
                validateCredentials(credentials)
                requireEpochSealSts(
                    binding.targetAccountId == routing.journalConfiguration.declaration().journalLocation.accountId,
                    EpochSealStsFailure.INVALID_INPUT,
                )
                AwsEpochSealStsAdapter(routing, credentials, binding, limits, factory, kmsFactory, nanoTime, wallClock)
            }
        }

        private fun validateCredentials(credentials: AwsSessionCredentials) {
            requireEpochSealSts(credentials.accessKeyId().length in 1..128 && credentials.secretAccessKey().length in 1..256, EpochSealStsFailure.INVALID_INPUT)
            requireEpochSealSts(credentials.sessionToken().length in 1..16_384, EpochSealStsFailure.INVALID_INPUT)
            requireEpochSealSts(
                credentials.accessKeyId().all { it in '!'..'~' } && credentials.secretAccessKey().all { it in '!'..'~' } &&
                    credentials.sessionToken().all { it in '!'..'~' },
                EpochSealStsFailure.INVALID_INPUT,
            )
        }

        private fun urlClient(limits: AwsEpochSealStsLimits, remainingMillis: () -> Int): SdkHttpClient = UrlConnectionHttpClient.create { uri ->
            requireConnectionFree()
            requireEpochSealSts(uri.scheme == "https")
            val remaining = remainingMillis()
            (uri.toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
                connectTimeout = minOf(limits.connectTimeoutMillis, remaining)
                readTimeout = minOf(limits.readTimeoutMillis, remaining)
                instanceFollowRedirects = false
                useCaches = false
                allowUserInteraction = false
            }
        }

        internal const val PROFILE_NAME = "complaint-epoch-seal-sts-v1"
    }
}

/** All actually returned partial resources remain reachable; an unreturned factory/build is never proven quiescent. */
internal class EpochSealStsClientOwner(
    private val region: Region,
    private val credentials: AwsSessionCredentials,
    private val limits: AwsEpochSealStsLimits,
    private val acquisition: EpochSealStsAcquisition,
    private val httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
) : AutoCloseable {
    private val lifecycle = Any()
    private val closed = AtomicBoolean()
    private val sdkCloseIssued = AtomicBoolean()
    private val rawCloseIssued = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()

    @Volatile private var stage = Stage.NEW

    @Volatile private var raw: SdkHttpClient? = null

    @Volatile private var transport: BoundedEpochSealStsHttpClient? = null

    @Volatile private var sdk: StsClient? = null

    fun open() {
        val result = runCatching {
            acquisition.remainingMillis(1)
            synchronized(lifecycle) {
                requireEpochSealSts(!closed.get() && stage == Stage.NEW)
                stage = Stage.OPENING_HTTP
            }
            val raw = httpFactory { checkNotNull(transport).remainingConnectionMillis() }
            synchronized(lifecycle) {
                this.raw = raw
                stage = Stage.HTTP_RETURNED
                requireEpochSealSts(!closed.get())
                // Only the fixed commercial regional endpoint, including us-east-1. Never the global STS endpoint.
                transport = BoundedEpochSealStsHttpClient(
                    region.id(),
                    URI.create("https://sts.${region.id()}.amazonaws.com"),
                    credentials.accessKeyId(),
                    credentials.sessionToken(),
                    raw,
                    limits.maxResponseBytes,
                )
            }
            acquisition.remainingMillis(1)
            synchronized(lifecycle) {
                requireEpochSealSts(!closed.get())
                stage = Stage.OPENING_SDK
            }
            val built = StsClient.builder().region(region).credentialsProvider(StaticCredentialsProvider.create(credentials))
                .defaultsMode(DefaultsMode.STANDARD).dualstackEnabled(false).fipsEnabled(false)
                .endpointOverride(URI.create("https://sts.${region.id()}.amazonaws.com"))
                .httpClient(checkNotNull(transport)).overrideConfiguration(
                    ClientOverrideConfiguration.builder().defaultProfileFile(ProfileFile.aggregator().build())
                        .defaultProfileName(AwsEpochSealStsAdapter.PROFILE_NAME)
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                        .apiCallTimeout(Duration.ofMillis(limits.requestTimeoutMillis.toLong()))
                        .apiCallAttemptTimeout(Duration.ofMillis(limits.requestTimeoutMillis.toLong())).build(),
                ).build()
            synchronized(lifecycle) {
                sdk = built
                stage = Stage.SDK_RETURNED
                requireEpochSealSts(!closed.get())
            }
            acquisition.remainingMillis(1)
        }
        if (result.isFailure) withEpochSealStsCleanup({ result.getOrThrow() }, ::close)
    }

    fun <R, T> execute(call: EpochSealStsCall, invoke: (StsClient, AwsRequestOverrideConfiguration) -> R, inspect: (R, EpochSealStsWireReport) -> T): T {
        val transport = checkNotNull(transport)
        return withEpochSealStsCleanup(
            {
                requireEpochSealSts(!closed.get())
                transport.begin(call)
                val duration = Duration.ofMillis(call.remainingMillis().toLong())
                val overrides = AwsRequestOverrideConfiguration.builder().apiCallTimeout(duration).apiCallAttemptTimeout(duration).build()
                val response = epochSealStsCall { invoke(checkNotNull(sdk), overrides) }
                val answer = inspect(response, transport.observed(call))
                call.check()
                requireEpochSealSts(!closed.get())
                answer
            },
            transport::finishRequest,
        )
    }

    @Synchronized
    override fun close() {
        val resources = synchronized(lifecycle) {
            closed.set(true)
            Triple(transport, raw, sdk)
        }
        val failure = runCatching {
            withEpochSealStsCleanup(
                {
                    val wrapper = resources.first
                    if (wrapper != null) {
                        wrapper.close()
                    } else {
                        resources.second?.let { if (rawCloseIssued.compareAndSet(false, true)) epochSealStsClose(it::close) }
                    }
                },
                { resources.third?.let { if (sdkCloseIssued.compareAndSet(false, true)) epochSealStsClose(it::close) } },
            )
            requireEpochSealSts(stage !in setOf(Stage.OPENING_HTTP, Stage.OPENING_SDK), EpochSealStsFailure.CLEANUP_FAILED)
        }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceEpochSealStsFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
    }

    private enum class Stage { NEW, OPENING_HTTP, HTTP_RETURNED, OPENING_SDK, SDK_RETURNED }
    override fun toString(): String = "EpochSealStsClientOwner(concrete,redacted)"
}
