package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestTerminalInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationCall
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationClose
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationSdkCall
import me.manga.kira.backend.complaint.infrastructure.journal.replaceJournalPublicationFailure
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestTerminalAttemptV1
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.awscore.defaultsmode.DefaultsMode
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
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
import software.amazon.awssdk.services.s3.model.ChecksumMode
import software.amazon.awssdk.services.s3.model.EncodingType
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Real pinned SDK, all-version two-entry pages and exact versioned GET only. The closed reader's
 * whole ordinary OR seal/terminal prefix is scanned, never a filtered writer/key subset. Raw bodies/XML are bounded before SDK
 * decoding; every exchange finishes before a page/body reaches the reader. There is no PUT API.
 */
internal class TestOrdinaryInventoryS3ClientV1 private constructor(
    private val reader: TestInventoryS3OriginV1,
    private val sdk: S3Client,
    private val transport: BoundedJournalSdkHttpClientV1,
    private val nanoTime: () -> Long,
) : AutoCloseable {
    private val busy = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val sdkCloseIssued = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()

    fun listPage(cursor: Cursor?): Page {
        val call = TestOrdinaryInventoryS3CallV1.list(reader, cursor, nanoTime)
        val location = call.declaration.journalLocation
        val response = execute(call) {
            val request = ListObjectVersionsRequest.builder().bucket(location.bucket).expectedBucketOwner(location.accountId)
                .prefix(call.objectKey).maxKeys(2).encodingType(EncodingType.URL).overrideConfiguration(overrides(call))
            if (call.keyMarker != null) request.keyMarker(call.keyMarker).versionIdMarker(call.versionIdMarker)
            sdk.listObjectVersions(request.build()).also {
                requireJournalPublication(transport.observation(call)?.response?.statusCode() == 200, JournalPublicationFailureV1.INVALID_LISTING)
            }
        }
        return journalPublicationCall(JournalPublicationFailureV1.INVALID_LISTING) {
            call.check()
            requireJournalPublication(
                response.sdkHttpResponse().statusCode() == 200 && response.name() == location.bucket && response.prefix() == call.objectKey &&
                    response.maxKeys() == 2 && response.encodingTypeAsString() == "url" && response.isTruncated() != null,
                JournalPublicationFailureV1.INVALID_LISTING,
            )
            requireJournalPublication(
                response.keyMarker().orEmpty() == call.keyMarker.orEmpty() && response.versionIdMarker().orEmpty() == call.versionIdMarker.orEmpty() &&
                    response.delimiter().isNullOrEmpty() && response.commonPrefixes().isEmpty() && response.deleteMarkers().isEmpty(),
                JournalPublicationFailureV1.INVALID_LISTING,
            )
            val versions = response.versions()
            requireJournalPublication(versions.size <= 2, JournalPublicationFailureV1.INVALID_LISTING)
            var previousKey = cursor?.key
            var previousVersion = cursor?.version
            val entries = versions.map { version ->
                val key = reader.requireInventoryKey(version.key())
                val id = requireJournalVersion(version.versionId())
                val size = version.size()
                val modified = version.lastModified()
                requireJournalPublication(
                    size != null && size in 1..call.declaration.limits.decoder.maximumEnvelopeBytes.toLong() &&
                        modified != null && version.isLatest() != null,
                    JournalPublicationFailureV1.INVALID_LISTING,
                )
                requireJournalPublication(
                    previousKey == null || (key >= checkNotNull(previousKey) && (key != previousKey || id != previousVersion)),
                    JournalPublicationFailureV1.INVALID_LISTING,
                )
                requireJournalPublication(cursor == null || key != cursor.key || id != cursor.version, JournalPublicationFailureV1.INVALID_LISTING)
                previousKey = key
                previousVersion = id
                Entry(key, id, checkNotNull(size), checkNotNull(modified))
            }
            val next = if (response.isTruncated() == true) {
                requireJournalPublication(entries.isNotEmpty(), JournalPublicationFailureV1.INVALID_LISTING)
                val key = reader.requireInventoryKey(response.nextKeyMarker())
                val version = requireJournalVersion(response.nextVersionIdMarker())
                val last = entries.last()
                requireJournalPublication(key == last.key && version == last.version, JournalPublicationFailureV1.INVALID_LISTING)
                requireJournalPublication(cursor == null || key != cursor.key || version != cursor.version, JournalPublicationFailureV1.INVALID_LISTING)
                Cursor(key, version)
            } else {
                requireJournalPublication(
                    response.nextKeyMarker().isNullOrEmpty() && response.nextVersionIdMarker().isNullOrEmpty(),
                    JournalPublicationFailureV1.INVALID_LISTING,
                )
                null
            }
            call.check()
            Page(entries, next)
        }
    }

    fun getVersion(key: String, version: String, attempt: TestOwnerDeleteCodecAttemptV1): JournalFetchedVersionV1 {
        return getVersion(TestOrdinaryInventoryS3CallV1.get(reader, key, version, attempt, nanoTime))
    }

    fun getVersion(key: String, version: String, attempt: TestTerminalAttemptV1): JournalFetchedVersionV1 =
        getVersion(TestOrdinaryInventoryS3CallV1.get(reader, key, version, attempt, nanoTime))

    private fun getVersion(call: TestOrdinaryInventoryS3CallV1): JournalFetchedVersionV1 {
        val location = call.declaration.journalLocation
        var owned: ByteArray? = null
        val result = runCatching {
            execute(call) {
                val response = sdk.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(location.bucket).expectedBucketOwner(location.accountId).key(call.objectKey)
                        .versionId(call.versionId).checksumMode(ChecksumMode.ENABLED).overrideConfiguration(overrides(call)).build(),
                )
                val observed = checkNotNull(transport.observation(call))
                val bytes = response.asByteArray().also { owned = it }
                requireJournalPublication(bytes.size == observed.size && Sha256.hex(bytes) == observed.wireSha256, JournalPublicationFailureV1.INVALID_READBACK)
                JournalFetchedVersionV1(response.response(), observed, bytes)
            }
        }
        if (result.isFailure) owned?.fill(0)
        return result.getOrThrow()
    }

    private fun <T> execute(call: TestOrdinaryInventoryS3CallV1, action: () -> T): T = journalPublicationSdkCall {
        requireConnectionFree()
        requireJournalPublication(call.reader === reader && !closed.get() && !failed.get() && busy.compareAndSet(false, true))
        val result = runCatching {
            withJournalPublicationCleanup(
                { call.check(); transport.begin(call); journalPublicationSdkCall(action = action).also { call.check() } },
                {
                    transport.finishRequest()
                    busy.set(false) // Only actual returned abort/body cleanup releases native custody.
                    call.check()
                },
            )
        }
        if (result.isFailure) failed.set(true)
        result.getOrThrow()
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

    override fun toString(): String = "TestOrdinaryInventoryS3ClientV1(original-read-only,redacted)"

    /** Bounded native listing facts only, not authenticated events or an inventory-completion proof. */
    class Entry(val key: String, val version: String, val size: Long, val lastModified: Instant) {
        override fun toString(): String = "TestOrdinaryInventoryEntryV1(native-list-facts,redacted)"
    }

    class Cursor(val key: String, val version: String) {
        override fun toString(): String = "TestOrdinaryInventoryCursorV1(opaque-version,redacted)"
    }

    class Page(entries: List<Entry>, val next: Cursor?) {
        val entries = entries.toList()
        override fun toString(): String = "TestOrdinaryInventoryPageV1(native-list-facts,redacted)"
    }

    /** Retains every returned native resource, including failure during a later construction step. */
    internal class Construction : AutoCloseable {
        private var opened = false
        private var closed = false
        private var opening = false
        private var raw: SdkHttpClient? = null
        private var transport: BoundedJournalSdkHttpClientV1? = null
        private var sdk: S3Client? = null
        private var owner: TestOrdinaryInventoryS3ClientV1? = null
        private var rawCloseIssued = false
        private var sdkCloseIssued = false
        private var failure: Throwable? = null

        fun open(
            reader: TestOrdinaryInventoryReaderV1,
            credentials: AwsSessionCredentials,
            httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
            nanoTime: () -> Long,
        ): TestOrdinaryInventoryS3ClientV1 = openOrigin(TestInventoryS3OriginV1.ordinary(reader), credentials, httpFactory, nanoTime)

        fun open(reader: TestTerminalInventoryReaderV1, credentials: AwsSessionCredentials,
            httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
            nanoTime: () -> Long,
        ): TestOrdinaryInventoryS3ClientV1 = openOrigin(TestInventoryS3OriginV1.terminal(reader), credentials, httpFactory, nanoTime)

        fun open(reader: me.manga.kira.backend.complaint.infrastructure.journal.TestActiveQueueJournalReaderV1, credentials: AwsSessionCredentials,
            httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient, nanoTime: () -> Long,
        ): TestOrdinaryInventoryS3ClientV1 = openOrigin(TestInventoryS3OriginV1.queue(reader), credentials, httpFactory, nanoTime)

        private fun openOrigin(
            reader: TestInventoryS3OriginV1,
            credentials: AwsSessionCredentials,
            httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
            nanoTime: () -> Long,
        ): TestOrdinaryInventoryS3ClientV1 = journalPublicationSdkCall {
            requireConnectionFree()
            reader.requireNativeRead()
            requireJournalPublication(!opened && !closed)
            opened = true
            val result = runCatching {
                val declaration = reader.routing.journalConfiguration.declaration()
                val location = declaration.journalLocation
                validateCredentials(credentials)
                requireJournalPublication(ambient(SdkSystemSetting.AWS_PARTITIONS_FILE).all { it == null } && !location.bucket.endsWith("--x-s3"))
                val region = Region.regions().singleOrNull { it.id() == location.region }
                requireJournalPublication(region != null)
                val profiles = ProfileFile.aggregator().build()
                val endpoint = endpoint(checkNotNull(region), profiles)
                reader.requireNativeRead()
                val bounded = BoundedJournalSdkHttpClientV1(endpoint, credentials.accessKeyId(), credentials.sessionToken()) { remaining ->
                    opening = true
                    httpFactory(remaining).also { raw = it; opening = false }
                }.also { transport = it }
                reader.requireNativeRead()
                opening = true
                val client = S3Client.builder().region(region).credentialsProvider(StaticCredentialsProvider.create(credentials)).defaultsMode(DefaultsMode.STANDARD)
                    .dualstackEnabled(false).fipsEnabled(false).crossRegionAccessEnabled(false).endpointOverride(endpoint).httpClient(bounded)
                    .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).accelerateModeEnabled(false).chunkedEncodingEnabled(false)
                            .useArnRegionEnabled(false).multiRegionEnabled(false).profileFile(profiles).profileName(PROFILE).build(),
                    )
                    .authSchemeProvider { params ->
                        S3AuthSchemeProvider.defaultProvider().resolveAuthScheme(params).map {
                            it.toBuilder().putSignerProperty(AwsV4FamilyHttpSigner.PAYLOAD_SIGNING_ENABLED, true).build()
                        }
                    }
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED).responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    .overrideConfiguration(
                        ClientOverrideConfiguration.builder().defaultProfileFile(profiles).defaultProfileName(PROFILE)
                            .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                            .apiCallTimeout(Duration.ofMillis(declaration.limits.deadlines.s3CallMillis.toLong()))
                            .apiCallAttemptTimeout(Duration.ofMillis(declaration.limits.deadlines.s3CallMillis.toLong())).build(),
                    ).build().also { sdk = it; opening = false }
                reader.requireNativeRead()
                TestOrdinaryInventoryS3ClientV1(reader, client, bounded, nanoTime).also { owner = it }
            }
            if (result.isFailure) withJournalPublicationCleanup({ result.getOrThrow() }, ::close) else result.getOrThrow()
        }

        @Synchronized
        override fun close() {
            closed = true
            val observed = runCatching {
                journalPublicationClose {
                    val complete = owner
                    if (complete != null) complete.close() else withJournalPublicationCleanup(
                        {
                            val bounded = transport
                            if (bounded != null) bounded.close() else raw?.let {
                                if (!rawCloseIssued) { rawCloseIssued = true; journalPublicationClose(it::close) }
                            }
                        },
                        { sdk?.let { if (!sdkCloseIssued) { sdkCloseIssued = true; journalPublicationClose(it::close) } } },
                    )
                    requireJournalPublication(!opening, JournalPublicationFailureV1.CLEANUP_FAILURE)
                }
            }.exceptionOrNull()
            if (observed != null && replaceJournalPublicationFailure(failure, observed)) failure = observed
            failure?.let { throw it }
        }
    }

    private companion object {
        const val PROFILE = "complaint-test-ordinary-inventory-s3-v1"

        fun overrides(call: TestOrdinaryInventoryS3CallV1): AwsRequestOverrideConfiguration {
            val remaining = Duration.ofMillis(call.remainingMillis().toLong())
            return AwsRequestOverrideConfiguration.builder().apiCallTimeout(remaining).apiCallAttemptTimeout(remaining).build()
        }

        fun validateCredentials(credentials: AwsSessionCredentials) {
            requireJournalPublication(credentials.accessKeyId().length in 1..128 && credentials.secretAccessKey().length in 1..256 && credentials.sessionToken().length in 1..16_384)
            requireJournalPublication(listOf(credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()).all { it.all { c -> c in '!'..'~' } })
        }

        fun ambient(setting: SdkSystemSetting): List<String?> = listOf(System.getProperty(setting.property()), System.getenv(setting.environmentVariable()))

        fun endpoint(region: Region, profiles: ProfileFile): URI {
            requireJournalPublication(ambient(SdkSystemSetting.AWS_S3_US_EAST_1_REGIONAL_ENDPOINT).all { it == null || it == "regional" })
            val metadata = S3Client.serviceMetadata().reconfigure(
                ServiceMetadataConfiguration.builder().profileFile { profiles }.profileName(PROFILE)
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
    }
}
