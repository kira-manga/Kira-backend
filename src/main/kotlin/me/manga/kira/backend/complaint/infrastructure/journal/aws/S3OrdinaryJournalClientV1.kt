package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationCall
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationClose
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationSdkCall
import me.manga.kira.backend.complaint.infrastructure.journal.replaceJournalPublicationFailure
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.awscore.defaultsmode.DefaultsMode
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.regions.PartitionMetadata
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.ServiceMetadataAdvancedOption
import software.amazon.awssdk.regions.ServiceMetadataConfiguration
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm
import software.amazon.awssdk.services.s3.model.ChecksumMode
import software.amazon.awssdk.services.s3.model.EncodingType
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse
import software.amazon.awssdk.services.s3.model.ObjectLockMode
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.net.Proxy
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Genuine SDK2.54.19, fixed J/location/credentials, conditional PUT and exact-key readback only. */
internal class S3OrdinaryJournalClientV1 private constructor(
    private val routing: VersionBoundComplaintJournalRouting,
    private val sdk: S3Client,
    private val transport: BoundedJournalSdkHttpClientV1,
    private val nanoTime: () -> Long,
) : AutoCloseable {
    private val busy = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val sdkCloseIssued = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()

    fun listExact(binding: JournalS3BindingV1): JournalListedVersionV1? {
        val call = JournalS3CallV1.list(binding, nanoTime)
        val response = execute(call) {
            sdk.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(call.declaration.journalLocation.bucket)
                    .expectedBucketOwner(call.declaration.journalLocation.accountId).prefix(binding.event.route.objectKey)
                    .maxKeys(2).encodingType(EncodingType.URL).overrideConfiguration(overrides(call)).build(),
            )
        }
        return journalPublicationCall(JournalPublicationFailureV1.INVALID_LISTING) { inventory(response, call) }
    }

    fun putIfAbsent(binding: JournalS3BindingV1, candidate: JournalS3CandidateV1): JournalPutObservationV1 {
        val call = JournalS3CallV1.put(binding, candidate, nanoTime)
        return execute(call) {
            val bytes = candidate.bytes()
            val result = try {
                runCatching {
                    journalPublicationSdkCall {
                        sdk.putObject(
                            PutObjectRequest.builder().bucket(call.declaration.journalLocation.bucket)
                                .expectedBucketOwner(call.declaration.journalLocation.accountId).key(binding.event.route.objectKey)
                                .ifNoneMatch("*").checksumAlgorithm(ChecksumAlgorithm.SHA256).checksumSHA256(candidate.checksum)
                                .objectLockMode(ObjectLockMode.COMPLIANCE).objectLockRetainUntilDate(candidate.retainUntil)
                                .contentType(JournalS3HttpWireV1.CONTENT_TYPE).contentLength(candidate.size.toLong()).metadata(candidate.metadata())
                                .overrideConfiguration(overrides(call)).build(),
                            RequestBody.fromBytes(bytes),
                        )
                    }
                }
            } finally {
                bytes.fill(0)
            }
            val failure = result.exceptionOrNull()
            if (failure == null) {
                acknowledged(result.getOrThrow(), checkNotNull(transport.observation(call)), candidate)
            } else {
                uncertainOrConflict(call, failure)
            }
        }
    }

    fun getVersion(binding: JournalS3BindingV1, versionId: String): JournalFetchedVersionV1 {
        val call = JournalS3CallV1.get(binding, versionId, nanoTime)
        var owned: ByteArray? = null
        val result = runCatching {
            execute(call) {
                val response = sdk.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(call.declaration.journalLocation.bucket)
                        .expectedBucketOwner(call.declaration.journalLocation.accountId).key(binding.event.route.objectKey).versionId(versionId)
                        .checksumMode(ChecksumMode.ENABLED).overrideConfiguration(overrides(call)).build(),
                )
                val observed = checkNotNull(transport.observation(call))
                val bytes = response.asByteArray().also { owned = it }
                requireJournalPublication(bytes.size == observed.size && Sha256.hex(bytes) == observed.wireSha256, JournalPublicationFailureV1.INVALID_READBACK)
                JournalFetchedVersionV1(response.response(), observed, bytes)
            }
        }
        if (result.isFailure) owned?.fill(0)
        return result.getOrThrow() // The native request and body have already been closed successfully.
    }

    private fun inventory(response: ListObjectVersionsResponse, call: JournalS3CallV1): JournalListedVersionV1? {
        requireJournalPublication(response.sdkHttpResponse().statusCode() == 200, JournalPublicationFailureV1.INVALID_LISTING)
        requireJournalPublication(
            response.name() == call.declaration.journalLocation.bucket && response.prefix() == call.binding.event.route.objectKey &&
                response.maxKeys() == 2 && response.encodingTypeAsString() == "url" && response.isTruncated() == false,
            JournalPublicationFailureV1.INVALID_LISTING,
        )
        requireJournalPublication(
            response.keyMarker().isNullOrEmpty() && response.versionIdMarker().isNullOrEmpty() && response.nextKeyMarker().isNullOrEmpty() &&
                response.nextVersionIdMarker().isNullOrEmpty() && response.delimiter().isNullOrEmpty() && response.commonPrefixes().isEmpty() &&
                response.deleteMarkers().isEmpty(),
            JournalPublicationFailureV1.INVALID_LISTING,
        )
        val versions = response.versions()
        requireJournalPublication(versions.size <= 2, JournalPublicationFailureV1.INVALID_LISTING)
        versions.forEach {
            requireJournalPublication(it.key() == call.binding.event.route.objectKey && it.isLatest() == true, JournalPublicationFailureV1.INVALID_LISTING)
            requireJournalVersion(it.versionId()) // SDK decodes encoded keys, NOT opaque version IDs. Never decode again.
            val size = it.size()
            requireJournalPublication(
                size != null && size in 1..call.declaration.limits.decoder.maximumEnvelopeBytes.toLong() && it.lastModified() != null,
                JournalPublicationFailureV1.INVALID_LISTING,
            )
        }
        requireJournalPublication(versions.size <= 1, JournalPublicationFailureV1.INVALID_LISTING)
        val version = versions.singleOrNull() ?: return null // Only this full successful empty response is absence.
        return JournalListedVersionV1(requireJournalVersion(version.versionId()), version.size(), version.lastModified())
    }

    private fun acknowledged(
        response: PutObjectResponse,
        observed: JournalS3HttpObservationV1,
        candidate: JournalS3CandidateV1,
    ): JournalPutObservationV1.Acknowledged {
        requireJournalPublication(
            response.sdkHttpResponse().statusCode() == 200 && observed.response.statusCode() == 200,
            JournalPublicationFailureV1.INVALID_PUT,
        )
        val headers = observed.response.headers()
        val version = requireJournalVersion(response.versionId())
        requireJournalPublication(version == JournalS3HttpWireV1.single(headers, "x-amz-version-id"), JournalPublicationFailureV1.INVALID_PUT)
        requireJournalPublication(
            response.checksumSHA256() == candidate.checksum && response.checksumSHA256() == JournalS3HttpWireV1.single(headers, "x-amz-checksum-sha256"),
            JournalPublicationFailureV1.CHECKSUM_MISMATCH,
        )
        requireJournalPublication(
            JournalS3HttpWireV1.single(headers, "x-amz-checksum-type") in listOf(null, "FULL_OBJECT"),
            JournalPublicationFailureV1.CHECKSUM_MISMATCH,
        )
        return JournalPutObservationV1.Acknowledged(version, candidate.wireSha256)
    }

    private fun uncertainOrConflict(call: JournalS3CallV1, failure: Throwable): JournalPutObservationV1 {
        if (failure !is JournalPublicationExceptionV1 || failure.code != JournalPublicationFailureV1.PROVIDER_FAILURE) throw failure
        val observed = transport.observation(call)
        val status = observed?.response?.statusCode()
        return when {
            status == 409 -> JournalPutObservationV1.Conflict

            status == 412 -> JournalPutObservationV1.PreconditionFailed

            status != null && status in 500..599 -> JournalPutObservationV1.Uncertain

            status == null -> {
                requireJournalPublication(transport.dispatched(call), JournalPublicationFailureV1.PROVIDER_FAILURE)
                JournalPutObservationV1.Uncertain
            }

            else -> throw failure // 403/404, an unvalidated body or a local rejection is never absence/retry permission.
        }
    }

    private fun <T> execute(call: JournalS3CallV1, action: () -> T): T = journalPublicationSdkCall {
        requireConnectionFree()
        requireJournalPublication(call.binding.routing === routing && !closed.get() && busy.compareAndSet(false, true))
        withJournalPublicationCleanup(
            {
                call.check()
                transport.begin(call)
                journalPublicationSdkCall(action = action).also { call.check() }
            },
            {
                transport.finishRequest()
                busy.set(false) // Native cleanup failure leaves this local owner occupied, not reusable.
                call.check()
            },
        )
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        val failure = runCatching {
            withJournalPublicationCleanup(transport::close) {
                if (sdkCloseIssued.compareAndSet(false, true)) journalPublicationClose { sdk.close() }
            }
        }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceJournalPublicationFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
    }

    override fun toString(): String = "S3OrdinaryJournalClientV1(dormant,J-bound,redacted)"

    companion object {
        fun create(
            routing: VersionBoundComplaintJournalRouting,
            credentials: AwsSessionCredentials,
            httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
            nanoTime: () -> Long,
        ): S3OrdinaryJournalClientV1 = journalPublicationSdkCall {
            requireConnectionFree()
            val location = routing.journalConfiguration.declaration().journalLocation
            validateCredentials(credentials)
            requireJournalPublication(ambient(SdkSystemSetting.AWS_PARTITIONS_FILE).all { it == null })
            requireJournalPublication(!location.bucket.endsWith("--x-s3")) // No S3 Express CreateSession/alternate credential machinery.
            val region = Region.regions().singleOrNull { it.id() == location.region }
            requireJournalPublication(region != null)
            val emptyProfile = ProfileFile.aggregator().build()
            val endpoint = regionalEndpoint(checkNotNull(region), emptyProfile)
            val transport = BoundedJournalSdkHttpClientV1(endpoint, credentials.accessKeyId(), credentials.sessionToken(), httpFactory)
            val built = runCatching {
                S3Client.builder().region(region).credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .defaultsMode(DefaultsMode.STANDARD).dualstackEnabled(false).fipsEnabled(false).crossRegionAccessEnabled(false)
                    .endpointOverride(endpoint).httpClient(transport)
                    .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).accelerateModeEnabled(false).chunkedEncodingEnabled(false)
                            .payloadSigningEnabled(true).useArnRegionEnabled(false).multiRegionEnabled(false)
                            .profileFile(emptyProfile).profileName(PROFILE_NAME).build(),
                    )
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    .overrideConfiguration(
                        ClientOverrideConfiguration.builder().defaultProfileFile(emptyProfile).defaultProfileName(PROFILE_NAME)
                            .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                            .apiCallTimeout(Duration.ofMillis(routing.journalConfiguration.declaration().limits.deadlines.s3CallMillis.toLong()))
                            .apiCallAttemptTimeout(
                                Duration.ofMillis(routing.journalConfiguration.declaration().limits.deadlines.s3CallMillis.toLong()),
                            ).build(),
                    ).build()
            }
            built.exceptionOrNull()?.let { failure -> return@journalPublicationSdkCall withJournalPublicationCleanup({ throw failure }, transport::close) }
            S3OrdinaryJournalClientV1(routing, built.getOrThrow(), transport, nanoTime)
        }

        private fun overrides(call: JournalS3CallV1): AwsRequestOverrideConfiguration {
            val remaining = Duration.ofMillis(call.remainingMillis().toLong())
            return AwsRequestOverrideConfiguration.builder().apiCallTimeout(remaining).apiCallAttemptTimeout(remaining).build()
        }

        private fun regionalEndpoint(region: Region, emptyProfile: ProfileFile): URI {
            requireJournalPublication(ambient(SdkSystemSetting.AWS_S3_US_EAST_1_REGIONAL_ENDPOINT).all { it == null || it == "regional" })
            val metadata = S3Client.serviceMetadata().reconfigure(
                ServiceMetadataConfiguration.builder().profileFile { emptyProfile }.profileName(PROFILE_NAME)
                    .putAdvancedOption(ServiceMetadataAdvancedOption.DEFAULT_S3_US_EAST_1_REGIONAL_ENDPOINT, "regional").build(),
            )
            requireJournalPublication(region in metadata.regions() && metadata.signingRegion(region) == region && PartitionMetadata.of(region).id() == "aws")
            val observed = metadata.endpointFor(region)
            val endpoint = if (observed.scheme == null) URI.create("https://$observed") else observed
            requireJournalPublication(
                endpoint.scheme == "https" && endpoint.host != null && endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null &&
                    endpoint.path in listOf("", "/") && endpoint.port in listOf(-1, 443),
            )
            return endpoint
        }

        private fun ambient(setting: SdkSystemSetting): List<String?> =
            listOf(System.getProperty(setting.property()), System.getenv(setting.environmentVariable()))

        private fun validateCredentials(credentials: AwsSessionCredentials) {
            requireJournalPublication(credentials.accessKeyId().length in 1..128 && credentials.secretAccessKey().length in 1..256)
            requireJournalPublication(credentials.sessionToken().length in 1..16_384)
            requireJournalPublication(
                credentials.accessKeyId().all { it in '!'..'~' } && credentials.secretAccessKey().all { it in '!'..'~' } &&
                    credentials.sessionToken().all { it in '!'..'~' },
            )
        }

        private const val PROFILE_NAME = "complaint-journal-ordinary-v1"
    }
}

/** Per-connection native I/O ceilings come from the active subcall of the original attempt; no ambient proxy or redirects. */
internal fun journalS3UrlConnectionClient(remainingMillis: () -> Int): SdkHttpClient = UrlConnectionHttpClient.create { uri ->
    requireConnectionFree()
    requireJournalPublication(uri.scheme == "https")
    val timeout = remainingMillis()
    (uri.toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
        connectTimeout = timeout
        readTimeout = timeout
        instanceFollowRedirects = false
        useCaches = false
        allowUserInteraction = false
    }
}

internal class JournalListedVersionV1(val versionId: String, val size: Long, val lastModified: Instant) {
    override fun toString(): String = "JournalListedVersionV1(observed-only,redacted)"
}

internal sealed interface JournalPutObservationV1 {
    class Acknowledged(val versionId: String, val wireSha256: String) : JournalPutObservationV1 {
        override fun toString(): String = "JournalPutObservationV1.Acknowledged(observed-only,redacted)"
    }
    data object Conflict : JournalPutObservationV1
    data object PreconditionFailed : JournalPutObservationV1
    data object Uncertain : JournalPutObservationV1
}

internal class JournalFetchedVersionV1(val response: GetObjectResponse, val observed: JournalS3HttpObservationV1, val bytes: ByteArray) : AutoCloseable {
    override fun close() = bytes.fill(0)
    override fun toString(): String = "JournalFetchedVersionV1(closed-native-exchange,redacted)"
}
