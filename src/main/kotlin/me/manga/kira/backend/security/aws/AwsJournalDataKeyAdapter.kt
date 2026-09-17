package me.manga.kira.backend.security.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.security.JournalCodecAttemptV1
import me.manga.kira.backend.security.JournalDataKeyPortV1
import me.manga.kira.backend.security.JournalDataKeyRequestV1
import me.manga.kira.backend.security.JournalGeneratedDataKeyV1
import me.manga.kira.backend.security.JournalPlaintextDataKeyV1
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.awscore.defaultsmode.DefaultsMode
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.regions.PartitionMetadata
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.ServiceMetadataConfiguration
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.DataKeySpec
import software.amazon.awssdk.services.kms.model.DecryptRequest
import software.amazon.awssdk.services.kms.model.EncryptionAlgorithmSpec
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest
import java.net.Proxy
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/**
 * Dormant actual SDK adapter for the retained J's ordinary OWNER_DELETE_ALL profile only. No key
 * discovery, arbitrary context, retry/cache, bean or activation/publication authority. Each successful
 * lease keeps exclusive HTTP custody until close; owned arrays are wiped, not all SDK/JVM copies.
 * Timeouts are finite application/I/O limits, not hard native/DNS/cancellation-completion guarantees.
 */
internal class AwsJournalDataKeyAdapter private constructor(
    val journal: ComplaintJournalConfigurationV1,
    private val profile: JournalKmsRequestProfile,
    private val sdk: KmsClient,
    private val transport: BoundedJournalKmsSdkHttpClient,
    private val nanoTime: () -> Long,
) : JournalDataKeyPortV1,
    AutoCloseable {
    private val lifecycle = Any()
    private val busy = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val activeLease = AtomicReference<KeyLease?>()
    private val sdkCloseIssued = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()

    override fun generate(request: JournalDataKeyRequestV1): JournalGeneratedDataKeyV1 = acquire(request, JournalKmsOperation.GENERATE, null, ::generateKey)

    override fun unwrap(request: JournalDataKeyRequestV1, wrappedKey: ByteArray): JournalPlaintextDataKeyV1 =
        acquire(request, JournalKmsOperation.DECRYPT, wrappedKey, ::decryptKey)

    private fun <T : KeyLease> acquire(
        request: JournalDataKeyRequestV1,
        operation: JournalKmsOperation,
        wrapped: ByteArray?,
        invoke: (JournalKmsCall) -> T,
    ): T {
        requireConnectionFree()
        val started = journalKmsSdkCall(nanoTime)
        journalKmsSdkCall { requireJournalKms(!closed.get() && busy.compareAndSet(false, true)) }
        var ownedCall: JournalKmsCall? = null
        var ownedLease: T? = null
        val result = runCatching {
            journalKmsSdkCall {
                val call = profile.prepare(request, operation, wrapped, started, nanoTime).also { ownedCall = it }
                call.check()
                transport.begin(call)
                val lease = invoke(call).also { ownedLease = it }
                call.clearInput() // No caller-derived wrapped input is retained by a returned lease.
                synchronized(lifecycle) {
                    call.check()
                    requireJournalKms(!closed.get() && activeLease.compareAndSet(null, lease))
                    lease
                }
            }
        }
        ownedCall?.clearInput()
        if (result.isFailure) {
            return withJournalKmsCleanup({ result.getOrThrow() }) {
                val lease = ownedLease
                if (lease == null) journalKmsClose { release(null) } else lease.close()
            }
        }
        return result.getOrThrow()
    }

    private fun generateKey(call: JournalKmsCall): GeneratedLease {
        val response = sdk.generateDataKey(
            GenerateDataKeyRequest.builder().keyId(call.keyArn).keySpec(DataKeySpec.AES_256).encryptionContext(call.context)
                .overrideConfiguration(overrides(call)).build(),
        )
        val report = transport.observed(call)
        val arn = responseArn(response.keyId(), report, call)
        requireJournalKms(response.ciphertextForRecipient() == null)
        val plaintext = checkedBlob(response.plaintext(), report.keySize, 32)
        val wrapped = checkedBlob(response.ciphertextBlob(), report.wrappedSize, call.maximumWrappedBytes)
        var keyCopy: ByteArray? = null
        var wrappedCopy: ByteArray? = null
        val copied = runCatching {
            // SDK AWS_JSON has already Base64-decoded these blobs. Never decode their bytes a second time.
            val key = plaintext.asByteArray().also { keyCopy = it }
            val encrypted = wrapped.asByteArray().also { wrappedCopy = it }
            call.check()
            GeneratedLease(arn, key, encrypted)
        }
        if (copied.isFailure) {
            keyCopy?.fill(0)
            wrappedCopy?.fill(0)
        }
        return copied.getOrThrow()
    }

    private fun decryptKey(call: JournalKmsCall): KeyLease {
        val response = sdk.decrypt(
            DecryptRequest.builder().keyId(call.keyArn).ciphertextBlob(SdkBytes.fromByteArray(checkNotNull(call.wrapped)))
                .encryptionContext(call.context).encryptionAlgorithm(EncryptionAlgorithmSpec.SYMMETRIC_DEFAULT)
                .overrideConfiguration(overrides(call)).build(),
        )
        val report = transport.observed(call)
        val arn = responseArn(response.keyId(), report, call)
        requireJournalKms(
            response.ciphertextForRecipient() == null && response.encryptionAlgorithm() == EncryptionAlgorithmSpec.SYMMETRIC_DEFAULT &&
                response.encryptionAlgorithmAsString() == report.algorithm,
        )
        val plaintext = checkedBlob(response.plaintext(), report.keySize, 32)
        val key = plaintext.asByteArray()
        return runCatching {
            call.check()
            KeyLease(arn, key) // Unwrap never returns a generated-key lease or retains wrapped input.
        }.getOrElse { failure ->
            key.fill(0)
            throw failure
        }
    }

    private fun responseArn(arn: String?, report: JournalKmsWireReport, call: JournalKmsCall): String {
        requireJournalKms(arn != null && arn == profile.keyArn && arn == call.keyArn && arn == report.keyArn)
        requireJournalKms(report.keySize == 32)
        return checkNotNull(arn) // The actual decoded response identity, not copied request labels.
    }

    private fun checkedBlob(blob: SdkBytes?, observedSize: Int?, maximum: Int): SdkBytes {
        requireJournalKms(blob != null && observedSize != null)
        val checked = checkNotNull(blob)
        val size = checked.asByteBuffer().remaining()
        requireJournalKms(size == observedSize && size in 1..maximum)
        return checked
    }

    private fun overrides(call: JournalKmsCall): AwsRequestOverrideConfiguration {
        val millis = call.remainingNanos() / 1_000_000
        requireJournalKms(millis >= 1) // SDK timers must not truncate a positive submillisecond slice to disabled zero.
        val remaining = Duration.ofMillis(millis)
        return AwsRequestOverrideConfiguration.builder().apiCallTimeout(remaining).apiCallAttemptTimeout(remaining).build()
    }

    private fun release(lease: KeyLease?) {
        transport.finishRequest()
        synchronized(lifecycle) {
            requireJournalKms(activeLease.get() == null || activeLease.get() === lease)
            if (lease != null) activeLease.compareAndSet(lease, null)
            busy.set(false) // Only proven cleanup releases the slot; a failed close remains non-reusable.
        }
    }

    @Synchronized
    override fun close() {
        val lease = synchronized(lifecycle) {
            closed.set(true)
            activeLease.get()
        }
        val failure = runCatching {
            withJournalKmsCleanup({ lease?.close() }) {
                withJournalKmsCleanup(transport::close) {
                    if (sdkCloseIssued.compareAndSet(false, true)) journalKmsClose { sdk.close() }
                }
            }
        }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceJournalKmsFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
    }

    private open inner class KeyLease(override val keyArn: String, override val plaintextKey: ByteArray) : JournalPlaintextDataKeyV1 {
        private var closeIssued = false
        private var cleanupFailure: Throwable? = null

        protected open fun clear() {
            plaintextKey.fill(0)
        }

        @Synchronized
        final override fun close() {
            clear()
            if (!closeIssued) {
                closeIssued = true
                cleanupFailure = runCatching { journalKmsClose { release(this) } }.exceptionOrNull()
            }
            cleanupFailure?.let { throw it }
        }

        override fun toString(): String = "JournalKmsKeyLease(owned,redacted)"
    }

    private inner class GeneratedLease(arn: String, key: ByteArray, override val wrappedKey: ByteArray) :
        KeyLease(arn, key),
        JournalGeneratedDataKeyV1 {
        override fun clear() {
            super.clear()
            wrappedKey.fill(0)
        }
    }

    override fun toString(): String = "AwsJournalDataKeyAdapter(J-bound,redacted,no-activation-authority)"

    /** Actual returned resources stay reachable even when a later constructor or its cleanup throws. */
    internal class Construction : AutoCloseable {
        private var stage = ConstructionStage.NEW
        private var opened = false
        private var closed = false
        private var raw: SdkHttpClient? = null
        private var transport: BoundedJournalKmsSdkHttpClient? = null
        private var sdk: KmsClient? = null
        private var owner: AwsJournalDataKeyAdapter? = null
        private var rawCloseIssued = false
        private var sdkCloseIssued = false
        private var closeFailure: Throwable? = null

        internal fun open(
            journal: ComplaintJournalConfigurationV1,
            credentials: AwsSessionCredentials,
            httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
            nanoTime: () -> Long,
            attempt: JournalCodecAttemptV1?,
        ): AwsJournalDataKeyAdapter {
            requireConnectionFree()
            return journalKmsSdkCall {
                requireJournalKms(!opened && !closed)
                opened = true
                val result = runCatching {
                    attempt?.remainingMillis(1)
                    val profile = JournalKmsRequestProfile(journal)
                    validateCredentials(credentials)
                    requireJournalKms(
                        System.getProperty(SdkSystemSetting.AWS_PARTITIONS_FILE.property()) == null &&
                            System.getenv(SdkSystemSetting.AWS_PARTITIONS_FILE.environmentVariable()) == null,
                    )
                    val region = Region.regions().singleOrNull { it.id() == profile.region }
                    requireJournalKms(region != null)
                    val emptyProfile = ProfileFile.aggregator().build()
                    val endpoint = regionalEndpoint(checkNotNull(region), emptyProfile)
                    attempt?.remainingMillis(1)
                    val transport = BoundedJournalKmsSdkHttpClient(
                        profile.region,
                        endpoint,
                        credentials.accessKeyId(),
                        credentials.sessionToken(),
                    ) { remaining ->
                        stage = ConstructionStage.OPENING_HTTP
                        httpFactory(remaining).also {
                            raw = it
                            stage = ConstructionStage.HTTP_RETURNED
                        }
                    }.also { this.transport = it }
                    attempt?.remainingMillis(1)
                    stage = ConstructionStage.OPENING_SDK
                    val sdk = KmsClient.builder().region(region).credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .defaultsMode(DefaultsMode.STANDARD).dualstackEnabled(false).fipsEnabled(false).endpointOverride(endpoint)
                        .httpClient(transport).overrideConfiguration(
                            ClientOverrideConfiguration.builder().defaultProfileFile(emptyProfile).defaultProfileName(PROFILE_NAME)
                                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                                .apiCallTimeout(Duration.ofMillis(profile.callLimitMillis.toLong()))
                                .apiCallAttemptTimeout(Duration.ofMillis(profile.callLimitMillis.toLong())).build(),
                        ).build().also {
                            this.sdk = it
                            stage = ConstructionStage.SDK_RETURNED
                        }
                    attempt?.remainingMillis(1)
                    AwsJournalDataKeyAdapter(journal, profile, sdk, transport, nanoTime).also { owner = it }
                }
                if (result.isFailure) return@journalKmsSdkCall withJournalKmsCleanup({ result.getOrThrow() }, ::close)
                result.getOrThrow()
            }
        }

        @Synchronized
        override fun close() {
            closed = true
            val failure = runCatching {
                journalKmsClose {
                    val adapter = owner
                    if (adapter != null) {
                        adapter.close()
                    } else {
                        withJournalKmsCleanup(
                            {
                                val wrapper = transport
                                if (wrapper != null) {
                                    wrapper.close()
                                } else {
                                    raw?.let {
                                        if (!rawCloseIssued) {
                                            rawCloseIssued = true
                                            journalKmsClose(it::close)
                                        }
                                    }
                                }
                            },
                            {
                                sdk?.let {
                                    if (!sdkCloseIssued) {
                                        sdkCloseIssued = true
                                        journalKmsClose(it::close)
                                    }
                                }
                            },
                        )
                    }
                    // A factory/build that threw without returning its owner has unobservable internals.
                    // Close every returned resource, but never turn that absence into a quiescence claim.
                    requireJournalKms(stage != ConstructionStage.OPENING_HTTP && stage != ConstructionStage.OPENING_SDK)
                }
            }.exceptionOrNull()
            if (failure != null && replaceJournalKmsFailure(closeFailure, failure)) closeFailure = failure
            closeFailure?.let { throw it }
        }

        override fun toString(): String = "JournalKmsConstruction(concrete,redacted)"

        private enum class ConstructionStage { NEW, OPENING_HTTP, HTTP_RETURNED, OPENING_SDK, SDK_RETURNED }
    }

    companion object {
        fun open(journal: ComplaintJournalConfigurationV1, credentials: AwsSessionCredentials): AwsJournalDataKeyAdapter =
            Construction().open(journal, credentials, ::journalKmsUrlConnectionClient, System::nanoTime, null)

        /** The only substitution is the public HTTP SPI; the genuine KmsClient still signs, marshals and decodes. */
        fun withHttpFixture(
            journal: ComplaintJournalConfigurationV1,
            credentials: AwsSessionCredentials,
            httpFactory: () -> SdkHttpClient,
            nanoTime: () -> Long = System::nanoTime,
        ): AwsJournalDataKeyAdapter = Construction().open(journal, credentials, { httpFactory() }, nanoTime, null)

        private fun regionalEndpoint(region: Region, emptyProfile: ProfileFile): URI {
            val metadata = KmsClient.serviceMetadata().reconfigure(
                ServiceMetadataConfiguration.builder().profileFile { emptyProfile }.profileName(PROFILE_NAME).build(),
            )
            requireJournalKms(region in metadata.regions() && metadata.signingRegion(region) == region && PartitionMetadata.of(region).id() == "aws")
            val observed = metadata.endpointFor(region)
            val endpoint = if (observed.scheme == null) URI.create("https://$observed") else observed
            requireJournalKms(
                endpoint.scheme == "https" && endpoint.host != null && endpoint.userInfo == null && endpoint.query == null &&
                    endpoint.fragment == null && endpoint.path in listOf("", "/") && endpoint.port in listOf(-1, 443),
            )
            return endpoint
        }

        private fun validateCredentials(credentials: AwsSessionCredentials) {
            requireJournalKms(credentials.accessKeyId().length in 1..128 && credentials.secretAccessKey().length in 1..256)
            requireJournalKms(credentials.sessionToken().length in 1..16_384)
            requireJournalKms(
                credentials.accessKeyId().all { it in '!'..'~' } && credentials.secretAccessKey().all { it in '!'..'~' } &&
                    credentials.sessionToken().all { it in '!'..'~' },
            )
        }

        private const val PROFILE_NAME = "complaint-journal-data-key-v1"
    }
}

/** Explicit no-proxy/no-redirect synchronous transport; configuring a connection does not connect it. */
internal fun journalKmsUrlConnectionClient(remainingMillis: () -> Int): SdkHttpClient = UrlConnectionHttpClient.create { uri ->
    requireConnectionFree()
    requireJournalKms(uri.scheme == "https")
    val timeout = remainingMillis()
    (uri.toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
        connectTimeout = timeout
        readTimeout = timeout
        instanceFollowRedirects = false
        useCaches = false
        allowUserInteraction = false
    }
}
