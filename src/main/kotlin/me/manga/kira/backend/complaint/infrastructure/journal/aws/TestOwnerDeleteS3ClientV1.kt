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
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
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
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner
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
import software.amazon.awssdk.services.s3.model.ChecksumMode
import software.amazon.awssdk.services.s3.model.EncodingType
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ObjectLockMode
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Separate fixed TEST facade; raw HTTP bounds, XML inspection, observations and native cleanup are shared. */
internal class TestOwnerDeleteS3ClientV1 private constructor(
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val sdk: S3Client,
    private val transport: BoundedJournalSdkHttpClientV1,
    private val nanoTime: () -> Long,
) : AutoCloseable {
    private val busy = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val sdkCloseIssued = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()

    fun listExact(binding: TestOwnerDeleteS3BindingV1): JournalListedVersionV1? {
        val call = TestOwnerDeleteS3CallV1.list(binding, nanoTime)
        val location = call.declaration.journalLocation
        val response = execute(call) { sdk.listObjectVersions(ListObjectVersionsRequest.builder().bucket(location.bucket).expectedBucketOwner(location.accountId)
            .prefix(call.objectKey).maxKeys(2).encodingType(EncodingType.URL).overrideConfiguration(overrides(call)).build()) }
        return journalPublicationCall(JournalPublicationFailureV1.INVALID_LISTING) {
            requireJournalPublication(response.sdkHttpResponse().statusCode() == 200 && response.name() == location.bucket && response.prefix() == call.objectKey &&
                response.maxKeys() == 2 && response.encodingTypeAsString() == "url" && response.isTruncated() == false)
            requireJournalPublication(response.keyMarker().isNullOrEmpty() && response.versionIdMarker().isNullOrEmpty() && response.nextKeyMarker().isNullOrEmpty() &&
                response.nextVersionIdMarker().isNullOrEmpty() && response.delimiter().isNullOrEmpty() && response.commonPrefixes().isEmpty() && response.deleteMarkers().isEmpty())
            val versions = response.versions()
            requireJournalPublication(versions.size <= 1, JournalPublicationFailureV1.INVALID_LISTING)
            versions.singleOrNull()?.let {
                requireJournalPublication(it.key() == call.objectKey && it.isLatest() == true && it.size() != null &&
                    it.size() in 1..call.declaration.limits.decoder.maximumEnvelopeBytes.toLong() && it.lastModified() != null)
                JournalListedVersionV1(requireJournalVersion(it.versionId()), it.size(), it.lastModified())
            }
        }
    }
    fun putIfAbsent(binding: TestOwnerDeleteS3BindingV1, candidate: TestOwnerDeleteS3CandidateV1): JournalPutObservationV1 {
        val call = TestOwnerDeleteS3CallV1.put(binding, candidate, nanoTime)
        val location = call.declaration.journalLocation
        return execute(call) {
            val bytes = candidate.bytes()
            val sent = try { runCatching { journalPublicationSdkCall {
                sdk.putObject(PutObjectRequest.builder().bucket(location.bucket).expectedBucketOwner(location.accountId).key(call.objectKey).ifNoneMatch("*")
                    .checksumAlgorithm(ChecksumAlgorithm.SHA256).checksumSHA256(candidate.checksum).objectLockMode(ObjectLockMode.COMPLIANCE)
                    .objectLockRetainUntilDate(candidate.retainUntil).contentType(JournalS3HttpWireV1.CONTENT_TYPE).contentLength(candidate.size.toLong())
                    .metadata(candidate.metadata()).overrideConfiguration(overrides(call)).build(), RequestBody.fromBytes(bytes))
            } } } finally { bytes.fill(0) }
            val failure = sent.exceptionOrNull()
            if (failure != null) {
                if (failure !is JournalPublicationExceptionV1 || failure.code != JournalPublicationFailureV1.PROVIDER_FAILURE) throw failure
                val status = transport.observation(call)?.response?.statusCode()
                when {
                    status == 409 -> JournalPutObservationV1.Conflict
                    status == 412 -> JournalPutObservationV1.PreconditionFailed
                    status != null && status in 500..599 -> JournalPutObservationV1.Uncertain
                    status == null && transport.dispatched(call) -> JournalPutObservationV1.Uncertain
                    else -> throw failure
                }
            } else {
                val response = sent.getOrThrow()
                val observed = checkNotNull(transport.observation(call))
                val headers = observed.response.headers()
                val version = requireJournalVersion(response.versionId())
                requireJournalPublication(response.sdkHttpResponse().statusCode() == 200 && observed.response.statusCode() == 200 &&
                    version == JournalS3HttpWireV1.single(headers, "x-amz-version-id"), JournalPublicationFailureV1.INVALID_PUT)
                requireJournalPublication(response.checksumSHA256() == candidate.checksum && response.checksumSHA256() == JournalS3HttpWireV1.single(headers, "x-amz-checksum-sha256") &&
                    JournalS3HttpWireV1.single(headers, "x-amz-checksum-type") in listOf(null, "FULL_OBJECT"), JournalPublicationFailureV1.CHECKSUM_MISMATCH)
                JournalPutObservationV1.Acknowledged(version, candidate.wireSha256)
            }
        }
    }
    fun getVersion(binding: TestOwnerDeleteS3BindingV1, versionId: String): JournalFetchedVersionV1 {
        val call = TestOwnerDeleteS3CallV1.get(binding, versionId, nanoTime)
        val location = call.declaration.journalLocation
        var owned: ByteArray? = null
        val result = runCatching { execute(call) {
            val response = sdk.getObjectAsBytes(GetObjectRequest.builder().bucket(location.bucket).expectedBucketOwner(location.accountId).key(call.objectKey)
                .versionId(call.versionId).checksumMode(ChecksumMode.ENABLED).overrideConfiguration(overrides(call)).build())
            val observed = checkNotNull(transport.observation(call))
            val bytes = response.asByteArray().also { owned = it }
            requireJournalPublication(bytes.size == observed.size && Sha256.hex(bytes) == observed.wireSha256, JournalPublicationFailureV1.INVALID_READBACK)
            JournalFetchedVersionV1(response.response(), observed, bytes)
        } }
        if (result.isFailure) owned?.fill(0)
        return result.getOrThrow()
    }
    private fun <T> execute(call: TestOwnerDeleteS3CallV1, action: () -> T): T = journalPublicationSdkCall {
        requireConnectionFree()
        requireJournalPublication(call.routing === routing && !closed.get() && busy.compareAndSet(false, true))
        withJournalPublicationCleanup({ call.check(); transport.begin(call); journalPublicationSdkCall(action = action).also { call.check() } }, {
            transport.finishRequest()
            busy.set(false)
            call.check()
        })
    }
    @Synchronized override fun close() {
        closed.set(true)
        val failure = runCatching { withJournalPublicationCleanup(transport::close) { if (sdkCloseIssued.compareAndSet(false, true)) journalPublicationClose { sdk.close() } } }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceJournalPublicationFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
    }
    internal class Construction : AutoCloseable {
        private var opened = false
        private var closed = false
        private var opening = false
        private var raw: SdkHttpClient? = null
        private var transport: BoundedJournalSdkHttpClientV1? = null
        private var sdk: S3Client? = null
        private var owner: TestOwnerDeleteS3ClientV1? = null
        private var rawCloseIssued = false
        private var sdkCloseIssued = false
        private var failure: Throwable? = null
        fun open(routing: TestOwnerDeleteJournalRoutingV1, credentials: AwsSessionCredentials, httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
            nanoTime: () -> Long, attempt: TestOwnerDeleteCodecAttemptV1): TestOwnerDeleteS3ClientV1 = journalPublicationSdkCall {
            requireConnectionFree()
            requireJournalPublication(!opened && !closed)
            opened = true
            val result = runCatching {
                attempt.requireOwner(routing); attempt.remainingMillis(1)
                val location = routing.journalConfiguration.declaration().journalLocation
                requireJournalPublication(credentials.accessKeyId().length in 1..128 && credentials.secretAccessKey().length in 1..256 && credentials.sessionToken().length in 1..16384)
                requireJournalPublication(listOf(credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()).all { it.all { c -> c in '!'..'~' } })
                requireJournalPublication(ambient(SdkSystemSetting.AWS_PARTITIONS_FILE).all { it == null } && !location.bucket.endsWith("--x-s3"))
                val region = Region.regions().singleOrNull { it.id() == location.region }
                requireJournalPublication(region != null)
                val profiles = ProfileFile.aggregator().build()
                val endpoint = endpoint(checkNotNull(region), profiles)
                attempt.remainingMillis(1)
                val bounded = BoundedJournalSdkHttpClientV1(endpoint, credentials.accessKeyId(), credentials.sessionToken()) { remaining ->
                    opening = true
                    httpFactory(remaining).also { raw = it; opening = false }
                }.also { transport = it }
                attempt.remainingMillis(1)
                opening = true
                val client = S3Client.builder().region(region).credentialsProvider(StaticCredentialsProvider.create(credentials)).defaultsMode(DefaultsMode.STANDARD)
                    .dualstackEnabled(false).fipsEnabled(false).crossRegionAccessEnabled(false).endpointOverride(endpoint).httpClient(bounded)
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).accelerateModeEnabled(false).chunkedEncodingEnabled(false)
                        .useArnRegionEnabled(false).multiRegionEnabled(false).profileFile(profiles).profileName(PROFILE).build())
                    .authSchemeProvider { params -> S3AuthSchemeProvider.defaultProvider().resolveAuthScheme(params).map {
                        it.toBuilder().putSignerProperty(AwsV4FamilyHttpSigner.PAYLOAD_SIGNING_ENABLED, true).build()
                    } }.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED).responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    .overrideConfiguration(ClientOverrideConfiguration.builder().defaultProfileFile(profiles).defaultProfileName(PROFILE)
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                        .apiCallTimeout(Duration.ofMillis(routing.journalConfiguration.declaration().limits.deadlines.s3CallMillis.toLong()))
                        .apiCallAttemptTimeout(Duration.ofMillis(routing.journalConfiguration.declaration().limits.deadlines.s3CallMillis.toLong())).build())
                    .build().also { sdk = it; opening = false }
                attempt.remainingMillis(1)
                TestOwnerDeleteS3ClientV1(routing, client, bounded, nanoTime).also { owner = it }
            }
            if (result.isFailure) withJournalPublicationCleanup({ result.getOrThrow() }, ::close) else result.getOrThrow()
        }
        @Synchronized override fun close() {
            closed = true
            val observed = runCatching { journalPublicationClose {
                val complete = owner
                if (complete != null) complete.close() else withJournalPublicationCleanup({
                    val bounded = transport
                    if (bounded != null) bounded.close() else raw?.let { if (!rawCloseIssued) { rawCloseIssued = true; journalPublicationClose(it::close) } }
                }, { sdk?.let { if (!sdkCloseIssued) { sdkCloseIssued = true; journalPublicationClose(it::close) } } })
                requireJournalPublication(!opening, JournalPublicationFailureV1.CLEANUP_FAILURE)
            } }.exceptionOrNull()
            if (observed != null && replaceJournalPublicationFailure(failure, observed)) failure = observed
            failure?.let { throw it }
        }
    }
    private companion object {
        const val PROFILE = "complaint-test-owner-delete-s3-v1"
        fun overrides(call: TestOwnerDeleteS3CallV1): AwsRequestOverrideConfiguration {
            val remaining = Duration.ofMillis(call.remainingMillis().toLong())
            return AwsRequestOverrideConfiguration.builder().apiCallTimeout(remaining).apiCallAttemptTimeout(remaining).build()
        }
        fun ambient(setting: SdkSystemSetting): List<String?> = listOf(System.getProperty(setting.property()), System.getenv(setting.environmentVariable()))
        fun endpoint(region: Region, profiles: ProfileFile): URI {
            requireJournalPublication(ambient(SdkSystemSetting.AWS_S3_US_EAST_1_REGIONAL_ENDPOINT).all { it == null || it == "regional" })
            val metadata = S3Client.serviceMetadata().reconfigure(ServiceMetadataConfiguration.builder().profileFile { profiles }.profileName(PROFILE)
                .putAdvancedOption(ServiceMetadataAdvancedOption.DEFAULT_S3_US_EAST_1_REGIONAL_ENDPOINT, "regional").build())
            requireJournalPublication(region in metadata.regions() && metadata.signingRegion(region) == region && PartitionMetadata.of(region).id() == "aws")
            val observed = metadata.endpointFor(region)
            val endpoint = if (observed.scheme == null) URI.create("https://$observed") else observed
            requireJournalPublication(endpoint.scheme == "https" && endpoint.host != null && endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null &&
                endpoint.path in listOf("", "/") && endpoint.port in listOf(-1, 443))
            return endpoint
        }
    }
}
