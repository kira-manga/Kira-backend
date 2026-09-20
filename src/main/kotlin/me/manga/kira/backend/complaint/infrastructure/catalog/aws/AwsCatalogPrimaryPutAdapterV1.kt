package me.manga.kira.backend.complaint.infrastructure.catalog.aws

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.awscore.defaultsmode.DefaultsMode
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.regions.PartitionMetadata
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.ServiceMetadataAdvancedOption
import software.amazon.awssdk.regions.ServiceMetadataConfiguration
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.auth.scheme.S3AuthSchemeProvider
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm
import software.amazon.awssdk.services.s3.model.ObjectLockMode
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.Proxy
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Independent routing/byte/time claims, NOT authenticated manifest fields, signature persistence, approval or namespace absence. */
internal class CatalogPrimaryPutTargetV1(location: OfflineCatalogLocationV1, generation: Long, creationEpochSecond: Long, val envelopeSha256: String) {
    val location: OfflineCatalogLocationV1 = catalogPrimaryPutCall {
        requireCatalogPrimaryPut(location.role == "PRIMARY" && OfflineBootstrapGrammar.bucket(location.bucket) && !location.bucket.endsWith("--x-s3"))
        requireCatalogPrimaryPut(OfflineBootstrapGrammar.account(location.accountId) && OfflineBootstrapGrammar.region(location.region))
        requireCatalogPrimaryPut(OfflineBootstrapGrammar.sha256(envelopeSha256))
        location.copy()
    }
    val key: String = catalogPrimaryPutCall { CatalogReadbackProtocol.key(generation) }
    val createdAt: Instant = catalogPrimaryPutCall {
        requireCatalogPrimaryPut(creationEpochSecond in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND)
        Instant.ofEpochSecond(creationEpochSecond)
    }
    val retainUntil: Instant = catalogPrimaryPutCall {
        createdAt.atOffset(ZoneOffset.UTC).plusYears(VersionBoundCatalogReadbackConfigurationV1.CREATION_MINIMUM_YEARS).toInstant().also {
            requireCatalogPrimaryPut(it.epochSecond in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND)
        }
    }
    internal val checksum: String = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(envelopeSha256))

    override fun toString(): String = "CatalogPrimaryPutTargetV1(fixed-input,redacted,no-authority)"
}

/** A bounded observed HTTP acknowledgement only. No absence, dual-copy, persistence, release, approval or acceptance authority. */
internal class CatalogPrimaryPutAcknowledgementV1(val versionId: String, val envelopeSha256: String) {
    override fun toString(): String = "CatalogPrimaryPutAcknowledgementV1(observed-only,redacted,no-authority)"
}

/**
 * One genuine SDK conditional PRIMARY PUT, then actual cleanup before an observed acknowledgement.
 * Creation is the supplied signed-intent claim, not observed physical storage creation. No replica write or SDK retry.
 * Finite application/SDK/I/O bounds do not prove hard DNS/native cancellation completion or measured wire-once execution.
 */
internal class AwsCatalogPrimaryPutAdapterV1 private constructor(
    private val construction: Construction,
    private val target: CatalogPrimaryPutTargetV1,
    private val sdk: S3Client,
    private val transport: BoundedCatalogPrimaryPutHttpClientV1,
) : AutoCloseable {
    private val used = AtomicBoolean()

    fun put(frozenEnvelopeBytes: ByteArray): CatalogPrimaryPutAcknowledgementV1 = catalogPrimaryPutCall(checkInterrupted = false) {
        var owned: ByteArray? = null
        val result = runCatching {
            withCatalogPrimaryPutCleanup(
                {
                    construction.requireRunning() // Even first-call expiry/interruption/transaction refusal must close an opened owner.
                    requireCatalogPrimaryPut(used.compareAndSet(false, true))
                    requireCatalogPrimaryPut(frozenEnvelopeBytes.size in 1..OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES)
                    val bytes = frozenEnvelopeBytes.copyOf().also { owned = it }
                    construction.requireRunning()
                    requireCatalogPrimaryPut(Sha256.hex(bytes) == target.envelopeSha256)
                    construction.requireRetention(target)
                    transport.begin(bytes)
                    val timeout = Duration.ofMillis(construction.remainingMillis().toLong())
                    val response = sdk.putObject(
                        PutObjectRequest.builder().bucket(target.location.bucket).expectedBucketOwner(target.location.accountId).key(target.key)
                            .ifNoneMatch("*").checksumAlgorithm(ChecksumAlgorithm.SHA256).checksumSHA256(target.checksum)
                            .objectLockMode(ObjectLockMode.COMPLIANCE).objectLockRetainUntilDate(target.retainUntil)
                            .contentType(CatalogPrimaryPutWireV1.CONTENT_TYPE).contentLength(bytes.size.toLong())
                            .overrideConfiguration(
                                AwsRequestOverrideConfiguration.builder().apiCallTimeout(timeout).apiCallAttemptTimeout(timeout).build(),
                            ).build(),
                        RequestBody.fromBytes(bytes),
                    )
                    construction.requireRunning()
                    val observed = transport.observedVersion()
                    requireCatalogPrimaryPut(response.sdkHttpResponse().statusCode() == 200)
                    requireCatalogPrimaryPut(response.versionId() == observed && CatalogReadbackProtocol.validVersion(response.versionId()))
                    requireCatalogPrimaryPut(response.checksumSHA256() == target.checksum)
                    construction.requireRetention(target)
                    CatalogPrimaryPutAcknowledgementV1(observed, target.envelopeSha256)
                },
                ::close,
            )
        }
        owned?.fill(0)
        result.getOrThrow().also { construction.requireResultBudget() } // Owned-buffer wiping and cleanup consume the original budget too.
    }

    override fun close() = construction.close()

    override fun toString(): String = "AwsCatalogPrimaryPutAdapterV1(one-conditional-primary-put,redacted,no-acceptance-authority)"

    /** Caller retains this owner BEFORE either native constructor, including a constructor that never returns its owner. */
    internal class Construction(private val budget: PersistenceTimeBudget) : AutoCloseable {
        private val caller = Thread.currentThread()
        private val closed = AtomicBoolean()
        private var opened = false
        private var clock: Clock? = null
        private var lastWall: Instant? = null

        @Volatile private var stage = Stage.NEW

        @Volatile private var raw: SdkHttpClient? = null

        @Volatile private var transport: BoundedCatalogPrimaryPutHttpClientV1? = null

        @Volatile private var sdk: S3Client? = null

        private var rawCloseIssued = false
        private var sdkCloseIssued = false
        private var closeFailure: Throwable? = null

        internal fun open(
            target: CatalogPrimaryPutTargetV1,
            credentials: AwsSessionCredentials,
            clock: Clock,
            httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
        ): AwsCatalogPrimaryPutAdapterV1 {
            requireConnectionFree()
            return catalogPrimaryPutCall {
                synchronized(this) {
                    requireCatalogPrimaryPut(!opened && !closed.get())
                    opened = true
                    this.clock = clock
                }
                val result = runCatching {
                    requireRunning()
                    validateCredentials(credentials)
                    val endpoint = regionalEndpoint(target.location.region)
                    requireRetention(target)
                    starting(Stage.OPENING_HTTP)
                    val http = httpFactory(::remainingMillis).also {
                        raw = it
                        stage = Stage.HTTP_RETURNED
                    }
                    requireRunning()
                    val bounded = BoundedCatalogPrimaryPutHttpClientV1(this, target, endpoint, credentials, http).also { transport = it }
                    val timeout = Duration.ofMillis(remainingMillis().toLong())
                    val emptyProfile = ProfileFile.aggregator().build()
                    starting(Stage.OPENING_SDK)
                    val client = S3Client.builder().region(Region.of(target.location.region)).credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .defaultsMode(DefaultsMode.STANDARD).dualstackEnabled(false).fipsEnabled(false).crossRegionAccessEnabled(false)
                        .endpointOverride(endpoint).httpClient(bounded)
                        .serviceConfiguration(
                            S3Configuration.builder().pathStyleAccessEnabled(true).accelerateModeEnabled(false).chunkedEncodingEnabled(false)
                                .useArnRegionEnabled(false).multiRegionEnabled(false).profileFile(emptyProfile).profileName(PROFILE_NAME).build(),
                        )
                        .authSchemeProvider { params ->
                            S3AuthSchemeProvider.defaultProvider().resolveAuthScheme(params).map { option ->
                                option.toBuilder().putSignerProperty(AwsV4FamilyHttpSigner.PAYLOAD_SIGNING_ENABLED, true).build()
                            }
                        }
                        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                        .overrideConfiguration(
                            ClientOverrideConfiguration.builder().defaultProfileFile(emptyProfile).defaultProfileName(PROFILE_NAME)
                                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                                .apiCallTimeout(timeout).apiCallAttemptTimeout(timeout).build(),
                        ).build().also {
                            sdk = it
                            stage = Stage.SDK_RETURNED
                        }
                    requireRunning()
                    AwsCatalogPrimaryPutAdapterV1(this, target, client, bounded)
                }
                if (result.isFailure) return@catalogPrimaryPutCall withCatalogPrimaryPutCleanup({ result.getOrThrow() }, ::close)
                result.getOrThrow()
            }
        }

        @Synchronized
        private fun starting(next: Stage) {
            requireCatalogPrimaryPut(!closed.get()) // Atomic with close; never start a native constructor after successful empty close.
            stage = next
        }

        internal fun requireRunning() {
            requireResultBudget()
            requireCatalogPrimaryPut(opened && !closed.get())
        }

        internal fun remainingMillis(): Int {
            requireRunning()
            return budget.remainingMillis(MAX_CALL_MILLIS).toInt()
        }

        internal fun requireResultBudget() {
            requireConnectionFree()
            requireCatalogPrimaryPut(caller === Thread.currentThread())
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            budget.remainingMillis(MAX_CALL_MILLIS)
        }

        internal fun requireRetention(target: CatalogPrimaryPutTargetV1) {
            requireRunning()
            // Sample the FULL remaining attempt before wall time, then conservatively cover the sub-millisecond floor loss.
            val remaining = Math.addExact(budget.remainingMillis(Long.MAX_VALUE), 1L)
            val now = checkNotNull(clock).instant()
            requireRunning()
            requireCatalogPrimaryPut(now.epochSecond in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND)
            requireCatalogPrimaryPut(lastWall?.let { !now.isBefore(it) } != false && !target.createdAt.isAfter(now))
            lastWall = now
            val floor = now.plusMillis(remaining).atOffset(ZoneOffset.UTC)
                .plusYears(VersionBoundCatalogReadbackConfigurationV1.REMAINING_MINIMUM_YEARS).toInstant()
            requireCatalogPrimaryPut(floor.epochSecond in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND && !target.retainUntil.isBefore(floor))
        }

        @Synchronized
        override fun close() {
            closed.set(true)
            val failure = runCatching {
                catalogPrimaryPutCall(CatalogPrimaryPutFailureV1.CLOSE_FAILURE, checkInterrupted = false) {
                    withCatalogPrimaryPutCleanup(
                        {
                            val bounded = transport
                            if (bounded != null) {
                                bounded.close()
                            } else {
                                raw?.let {
                                    if (!rawCloseIssued) {
                                        rawCloseIssued = true
                                        catalogPrimaryPutCall(CatalogPrimaryPutFailureV1.CLOSE_FAILURE, false, it::close)
                                    }
                                }
                            }
                        },
                        {
                            sdk?.let {
                                if (!sdkCloseIssued) {
                                    sdkCloseIssued = true
                                    catalogPrimaryPutCall(CatalogPrimaryPutFailureV1.CLOSE_FAILURE, false, it::close)
                                }
                            }
                        },
                    )
                    requireCatalogPrimaryPut(stage != Stage.OPENING_HTTP && stage != Stage.OPENING_SDK)
                }
            }.exceptionOrNull()
            if (failure != null && replaceCatalogPrimaryPutFailure(closeFailure, failure)) closeFailure = failure
            closeFailure?.let { throw it } // An unknown prior close stays unknown even if a late native owner is later disposed.
        }

        override fun toString(): String = "CatalogPrimaryPutConstructionV1(retained,redacted,no-authority)"

        private enum class Stage { NEW, OPENING_HTTP, HTTP_RETURNED, OPENING_SDK, SDK_RETURNED }
    }

    companion object {
        private const val PROFILE_NAME = "complaint-catalog-primary-put-v1"
        private const val MAX_CALL_MILLIS = 600_000L

        fun openOwned(construction: Construction, target: CatalogPrimaryPutTargetV1, credentials: AwsSessionCredentials): AwsCatalogPrimaryPutAdapterV1 =
            construction.open(target, credentials, Clock.systemUTC(), ::catalogPrimaryPutUrlConnectionClient)

        /** Raw HTTP/time substitution only: genuine S3Client marshals, payload-SigV4 signs and decodes. */
        fun withHttpFixture(
            construction: Construction,
            target: CatalogPrimaryPutTargetV1,
            credentials: AwsSessionCredentials,
            httpFactory: () -> SdkHttpClient,
            clock: Clock,
        ): AwsCatalogPrimaryPutAdapterV1 = construction.open(target, credentials, clock) { httpFactory() }

        private fun regionalEndpoint(regionId: String): URI {
            requireCatalogPrimaryPut(ambient(SdkSystemSetting.AWS_PARTITIONS_FILE).all { it == null })
            requireCatalogPrimaryPut(ambient(SdkSystemSetting.AWS_S3_US_EAST_1_REGIONAL_ENDPOINT).all { it == null || it == "regional" })
            val region = Region.regions().singleOrNull { it.id() == regionId }
            requireCatalogPrimaryPut(region != null)
            val metadata = S3Client.serviceMetadata().reconfigure(
                ServiceMetadataConfiguration.builder().profileFile { ProfileFile.aggregator().build() }.profileName(PROFILE_NAME)
                    .putAdvancedOption(ServiceMetadataAdvancedOption.DEFAULT_S3_US_EAST_1_REGIONAL_ENDPOINT, "regional").build(),
            )
            requireCatalogPrimaryPut(region in metadata.regions() && metadata.signingRegion(checkNotNull(region)) == region)
            requireCatalogPrimaryPut(PartitionMetadata.of(checkNotNull(region)).id() == "aws")
            val observed = metadata.endpointFor(region)
            val endpoint = if (observed.scheme == null) URI.create("https://$observed") else observed
            requireCatalogPrimaryPut(
                endpoint.scheme == "https" && endpoint.host != null && endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null &&
                    endpoint.path in listOf("", "/") && endpoint.port in listOf(-1, 443),
            )
            return endpoint // Pinned SDK metadata defeats ambient URLs; never a caller endpoint override.
        }

        private fun ambient(setting: SdkSystemSetting): List<String?> =
            listOf(System.getProperty(setting.property()), System.getenv(setting.environmentVariable()))

        private fun validateCredentials(credentials: AwsSessionCredentials) {
            requireCatalogPrimaryPut(credentials.accessKeyId().length in 1..128 && credentials.secretAccessKey().length in 1..256)
            requireCatalogPrimaryPut(credentials.sessionToken().length in 1..16_384)
            requireCatalogPrimaryPut(
                listOf(credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()).all { value -> value.all { it in '!'..'~' } },
            )
        }
    }
}

private fun catalogPrimaryPutUrlConnectionClient(remainingMillis: () -> Int): SdkHttpClient = UrlConnectionHttpClient.create { uri ->
    requireConnectionFree()
    requireCatalogPrimaryPut(uri.scheme == "https")
    val timeout = remainingMillis()
    (uri.toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
        connectTimeout = timeout
        readTimeout = timeout
        instanceFollowRedirects = false
        useCaches = false
        allowUserInteraction = false
    }
}

internal enum class CatalogPrimaryPutFailureV1 { PUT_FAILURE, CLOSE_FAILURE }

internal class CatalogPrimaryPutExceptionV1(val code: CatalogPrimaryPutFailureV1) : RuntimeException("Catalog primary PUT rejected: ${code.name}.")

internal fun requireCatalogPrimaryPut(condition: Boolean) {
    if (!condition) throw CatalogPrimaryPutExceptionV1(CatalogPrimaryPutFailureV1.PUT_FAILURE)
}

/** Deliberately cause-free provider boundary; no private provider diagnostics or suppressed graph may escape. */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun <T> catalogPrimaryPutCall(
    code: CatalogPrimaryPutFailureV1 = CatalogPrimaryPutFailureV1.PUT_FAILURE,
    checkInterrupted: Boolean = true,
    action: () -> T,
): T = try {
    if (checkInterrupted && Thread.currentThread().isInterrupted) throw InterruptedException()
    catalogPrimaryPutSignal(action)
} catch (_: CancellationException) {
    throw CancellationException("Catalog primary PUT cancelled.")
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    throw InterruptedException("Catalog primary PUT interrupted.")
} catch (failure: CatalogPrimaryPutExceptionV1) {
    throw CatalogPrimaryPutExceptionV1(if (code == CatalogPrimaryPutFailureV1.CLOSE_FAILURE) code else failure.code)
} catch (_: Exception) {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Catalog primary PUT interrupted.")
    throw CatalogPrimaryPutExceptionV1(code)
}

private fun <T> catalogPrimaryPutSignal(action: () -> T): T = try {
    action()
} catch (failure: SdkException) {
    val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
    causes.filterIsInstance<Error>().firstOrNull()?.let { throw it }
    if (causes.any { it is CancellationException }) throw CancellationException()
    if (Thread.currentThread().isInterrupted || causes.any { it is InterruptedException }) throw InterruptedException()
    throw failure
}

internal fun <T> withCatalogPrimaryPutCleanup(action: () -> T, cleanup: () -> Unit): T {
    val result = runCatching { catalogPrimaryPutSignal(action) }
    val closing = runCatching { catalogPrimaryPutSignal(cleanup) }.exceptionOrNull()
    if (closing != null && replaceCatalogPrimaryPutFailure(result.exceptionOrNull(), closing)) throw closing
    return result.getOrThrow()
}

internal fun replaceCatalogPrimaryPutFailure(previous: Throwable?, closing: Throwable): Boolean = when {
    closing is Error || previous == null -> true
    previous is Error -> false
    closing is CancellationException -> true
    previous is CancellationException -> false
    closing is InterruptedException -> true
    previous is InterruptedException -> false
    else -> closing is CatalogPrimaryPutExceptionV1 && closing.code == CatalogPrimaryPutFailureV1.CLOSE_FAILURE
}
