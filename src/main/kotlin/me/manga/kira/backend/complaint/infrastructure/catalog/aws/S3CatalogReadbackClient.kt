package me.manga.kira.backend.complaint.infrastructure.catalog.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.catalogProviderCall
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.defaultsmode.DefaultsMode
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.ServiceMetadataAdvancedOption
import software.amazon.awssdk.regions.ServiceMetadataConfiguration
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.EncodingType
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse
import java.net.Proxy
import java.net.URI
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Finite transport ceilings, not proof of a hard native/DNS/cancellation completion deadline. */
internal class S3CatalogReadbackLimits(
    val requestTimeoutMillis: Long = 10_000,
    val connectTimeoutMillis: Int = 2_000,
    val readTimeoutMillis: Int = 2_000,
    val maximumListBytes: Int = 2 * 1024 * 1024,
    val maximumErrorBytes: Int = 64 * 1024,
    val maximumObjectBytes: Int = OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES,
) {
    init {
        requireCatalogReadback(requestTimeoutMillis in 1..60_000, CatalogReadbackFailure.INVALID_POLICY)
        requireCatalogReadback(connectTimeoutMillis.toLong() in 1..requestTimeoutMillis, CatalogReadbackFailure.INVALID_POLICY)
        requireCatalogReadback(readTimeoutMillis.toLong() in 1..requestTimeoutMillis, CatalogReadbackFailure.INVALID_POLICY)
        requireCatalogReadback(maximumListBytes in 1..4 * 1024 * 1024, CatalogReadbackFailure.INVALID_POLICY)
        requireCatalogReadback(maximumErrorBytes in 1..64 * 1024, CatalogReadbackFailure.INVALID_POLICY)
        requireCatalogReadback(maximumObjectBytes in 1..OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES, CatalogReadbackFailure.INVALID_POLICY)
    }

    override fun toString(): String = "S3CatalogReadbackLimits(bounded)"
}

/** Exactly one SDK request/body owner per location. No caller-selected S3 client, endpoint or credentials provider. */
internal class S3CatalogReadbackClient private constructor(
    val location: OfflineCatalogLocationV1,
    private val sdk: S3Client,
    private val transport: BoundedCatalogSdkHttpClient,
) : AutoCloseable {
    private val busy = AtomicBoolean()
    private val closed = AtomicBoolean()

    fun list(request: CatalogListRequest): ListObjectVersionsResponse {
        enter()
        return withS3Cleanup(
            action = {
                sdkReadbackCall {
                    sdk.listObjectVersions(
                        ListObjectVersionsRequest.builder()
                            .bucket(location.bucket)
                            .expectedBucketOwner(location.accountId)
                            .prefix(request.prefix)
                            .maxKeys(request.maxKeys)
                            .keyMarker(request.cursor?.keyMarker)
                            .versionIdMarker(request.cursor?.versionIdMarker)
                            .encodingType(EncodingType.URL)
                            .build(),
                    )
                }
            },
            cleanup = ::release,
        )
    }

    // A returned stream transfers its one retained request to the body. Every construction failure keeps cleanup here.
    @Suppress("TooGenericExceptionCaught")
    fun open(request: CatalogGetRequest): CatalogVersionBody {
        enter()
        var stream: ResponseInputStream<GetObjectResponse>? = null
        try {
            val opened = sdkReadbackCall {
                sdk.getObject(
                    GetObjectRequest.builder()
                        .bucket(location.bucket)
                        .expectedBucketOwner(location.accountId)
                        .key(request.key)
                        .versionId(request.versionId)
                        .build(),
                    // SDK's first-read timer is not a body deadline. The owned transport retains the original elapsed budget.
                    ResponseTransformer.toInputStream<GetObjectResponse>(Duration.ZERO),
                )
            }
            stream = opened
            return S3CatalogVersionBody(request, opened) { closeBody(opened) }
        } catch (failure: Throwable) {
            return withS3Cleanup({ throw failure }) { closeBody(stream) }
        }
    }

    private fun enter() {
        requireConnectionFree()
        requireCatalogReadback(!closed.get() && busy.compareAndSet(false, true), CatalogReadbackFailure.INVALID_READBACK)
    }

    private fun closeBody(stream: ResponseInputStream<GetObjectResponse>?) = withS3Cleanup(
        {
            catalogProviderCall(CatalogReadbackFailure.CLOSE_FAILURE) { stream?.close() }
            Unit
        },
        ::release,
    )

    private fun release() {
        transport.finishRequest() // Failed close retains the slot; it cannot silently become another request's resource.
        busy.set(false)
    }

    override fun close() {
        closed.set(true)
        withS3Cleanup(
            { transport.close() },
            { catalogProviderCall(CatalogReadbackFailure.CLOSE_FAILURE) { sdk.close() } },
        )
    }

    override fun toString(): String = "S3CatalogReadbackClient(read-only,redacted)"

    /** Concrete per-location construction custody, retained before any HTTP/SDK constructor is invoked. */
    internal class Construction(private val attempt: CatalogReadbackRefreshCustodyV1.Attempt? = null) : AutoCloseable {
        private var stage = Stage.NEW
        private var opened = false
        private var closed = false
        private var raw: SdkHttpClient? = null
        private var transport: BoundedCatalogSdkHttpClient? = null
        private var sdk: S3Client? = null
        private var closeIssued = false
        private var closeFailure: Throwable? = null

        internal fun open(
            location: OfflineCatalogLocationV1,
            credentials: AwsSessionCredentials,
            limits: S3CatalogReadbackLimits,
            httpFactory: () -> SdkHttpClient,
            nanoTime: () -> Long,
        ): S3CatalogReadbackClient {
            requireConnectionFree()
            requireCatalogReadback(!opened && !closed, CatalogReadbackFailure.INVALID_POLICY)
            opened = true
            val result = runCatching { construct(location, credentials, limits, httpFactory, nanoTime) }
            if (result.isFailure) return withS3Cleanup({ result.getOrThrow() }, ::close)
            return result.getOrThrow()
        }

        private fun construct(
            location: OfflineCatalogLocationV1,
            credentials: AwsSessionCredentials,
            limits: S3CatalogReadbackLimits,
            httpFactory: () -> SdkHttpClient,
            nanoTime: () -> Long,
        ): S3CatalogReadbackClient {
            requireConnectionFree()
            validateCredentials(credentials)
            requireCatalogReadback(ambientValues(SdkSystemSetting.AWS_PARTITIONS_FILE).all { it == null }, CatalogReadbackFailure.INVALID_POLICY)
            val region = Region.regions().singleOrNull { it.id() == location.region }
                ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_POLICY)
            val emptyProfile = ProfileFile.aggregator().build()
            val endpoint = catalogProviderCall(CatalogReadbackFailure.INVALID_POLICY) { regionalEndpoint(region, emptyProfile) }
            attempt?.requireRunning()
            stage = Stage.OPENING_HTTP
            val raw = catalogProviderCall { httpFactory() }.also { this.raw = it; stage = Stage.HTTP_RETURNED }
            attempt?.requireRunning() // Record the returned owner before checking deadline/stop; cleanup must still close it.
            val transport = BoundedCatalogSdkHttpClient(raw, location, endpoint, limits, nanoTime).also { this.transport = it }
            attempt?.requireRunning()
            stage = Stage.OPENING_SDK
            val sdk = sdkReadbackCall {
                    S3Client.builder()
                        .region(region)
                        .credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .defaultsMode(DefaultsMode.STANDARD)
                        .dualstackEnabled(false)
                        .fipsEnabled(false)
                        .crossRegionAccessEnabled(false)
                        .endpointOverride(endpoint) // Explicit SDK-derived endpoint wins over profile/system endpoint URLs.
                        .httpClient(transport)
                        .serviceConfiguration(
                            S3Configuration.builder().pathStyleAccessEnabled(true).accelerateModeEnabled(false)
                                .useArnRegionEnabled(false).multiRegionEnabled(false).profileFile(emptyProfile).profileName("catalog-readback").build(),
                        )
                        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                        .overrideConfiguration(
                            ClientOverrideConfiguration.builder()
                                .defaultProfileFile(emptyProfile).defaultProfileName("catalog-readback")
                                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                                .apiCallTimeout(Duration.ofMillis(limits.requestTimeoutMillis))
                                .apiCallAttemptTimeout(Duration.ofMillis(limits.requestTimeoutMillis))
                                .build(),
                        )
                        .build()
            }.also { this.sdk = it; stage = Stage.SDK_RETURNED }
            attempt?.requireRunning()
            return S3CatalogReadbackClient(location, sdk, transport)
        }

        /** Original synchronous caller owns cleanup. A failed constructor or close can never become a release receipt. */
        override fun close() {
            closed = true
            if (!closeIssued) {
                closeIssued = true
                closeFailure = runCatching {
                    withS3Cleanup(
                        { transport?.close() ?: raw?.let { catalogProviderCall(CatalogReadbackFailure.CLOSE_FAILURE) { it.close() } } },
                        { sdk?.let { catalogProviderCall(CatalogReadbackFailure.CLOSE_FAILURE) { it.close() } } },
                    )
                    requireCatalogReadback(stage != Stage.OPENING_HTTP && stage != Stage.OPENING_SDK, CatalogReadbackFailure.CLOSE_FAILURE)
                }.exceptionOrNull()
            }
            closeFailure?.let { throw it }
        }

        override fun toString(): String = "CatalogS3ConstructionV1(concrete,redacted)"

        private enum class Stage { NEW, OPENING_HTTP, HTTP_RETURNED, OPENING_SDK, SDK_RETURNED }
    }

    companion object {
        fun create(
            location: OfflineCatalogLocationV1,
            credentials: AwsSessionCredentials,
            limits: S3CatalogReadbackLimits,
            httpFactory: () -> SdkHttpClient,
            nanoTime: () -> Long,
        ): S3CatalogReadbackClient = Construction().open(location, credentials, limits, httpFactory, nanoTime)

        private fun regionalEndpoint(region: Region, emptyProfile: ProfileFile): URI {
            // This SDK setting can otherwise alter us-east-1 metadata before endpointOverride is applied.
            val ambient = ambientValues(SdkSystemSetting.AWS_S3_US_EAST_1_REGIONAL_ENDPOINT)
            requireCatalogReadback(ambient.all { it == null || it == "regional" }, CatalogReadbackFailure.INVALID_POLICY)
            val metadata = S3Client.serviceMetadata().reconfigure(
                ServiceMetadataConfiguration.builder().profileFile { emptyProfile }.profileName("catalog-readback")
                    .putAdvancedOption(ServiceMetadataAdvancedOption.DEFAULT_S3_US_EAST_1_REGIONAL_ENDPOINT, "regional").build(),
            )
            requireCatalogReadback(region in metadata.regions() && metadata.signingRegion(region) == region, CatalogReadbackFailure.INVALID_POLICY)
            val observed = metadata.endpointFor(region)
            val endpoint = if (observed.scheme == null) URI.create("https://$observed") else observed
            requireCatalogReadback(
                endpoint.scheme == "https" && endpoint.host != null && endpoint.userInfo == null && endpoint.query == null &&
                    endpoint.fragment == null && endpoint.path in listOf("", "/") && endpoint.port in listOf(-1, 443),
                CatalogReadbackFailure.INVALID_POLICY,
            )
            return endpoint
        }

        private fun ambientValues(setting: SdkSystemSetting): List<String?> =
            listOf(System.getProperty(setting.property()), System.getenv(setting.environmentVariable()))

        private fun validateCredentials(credentials: AwsSessionCredentials) {
            requireCatalogReadback(credentials.accessKeyId().length in 1..128, CatalogReadbackFailure.INVALID_POLICY)
            requireCatalogReadback(credentials.secretAccessKey().length in 1..256, CatalogReadbackFailure.INVALID_POLICY)
            requireCatalogReadback(credentials.sessionToken().length in 1..16_384, CatalogReadbackFailure.INVALID_POLICY)
            requireCatalogReadback(
                credentials.accessKeyId().all { it in '!'..'~' } && credentials.secretAccessKey().all { it in '!'..'~' } &&
                    credentials.sessionToken().all { it in '!'..'~' },
                CatalogReadbackFailure.INVALID_POLICY,
            )
        }
    }
}

/** Uses SDK's public factory extension, not a second HTTP implementation or ambient ProxySelector. No socket is opened here. */
internal fun catalogUrlConnectionClient(limits: S3CatalogReadbackLimits): SdkHttpClient = UrlConnectionHttpClient.create { uri ->
    requireCatalogReadback(uri.scheme == "https", CatalogReadbackFailure.INVALID_POLICY)
    (uri.toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
        connectTimeout = limits.connectTimeoutMillis
        readTimeout = limits.readTimeoutMillis
        instanceFollowRedirects = false
        useCaches = false
        allowUserInteraction = false
    }
}

/** Preserve only bounded cancellation/interruption classifications from SDK wrapping; all provider text uses the existing boundary. */
internal fun <T> sdkReadbackCall(action: () -> T): T = catalogProviderCall {
    try {
        action()
    } catch (failure: SdkException) {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
        if (causes.any { it is CancellationException }) throw CancellationException()
        if (Thread.currentThread().isInterrupted || causes.any { it is InterruptedException }) throw InterruptedException()
        throw failure
    }
}

/** Same cancellation/close precedence as W04g, without attaching raw suppressed failures or skipping a cleanup on Error. */
internal fun <T> withS3Cleanup(action: () -> T, cleanup: () -> Unit): T {
    val result = runCatching(action)
    val failedClose = runCatching(cleanup).exceptionOrNull()
    val pending = result.exceptionOrNull()
    if (failedClose != null && replaceS3Failure(pending, failedClose)) throw failedClose
    return result.getOrThrow()
}

internal fun replaceS3Failure(pending: Throwable?, closing: Throwable): Boolean = when {
    closing is Error || pending == null -> true
    pending is Error -> false
    closing is CancellationException -> true
    pending is CancellationException -> false
    closing is CatalogReadbackException && closing.code == CatalogReadbackFailure.INTERRUPTED -> true
    else -> false
}
