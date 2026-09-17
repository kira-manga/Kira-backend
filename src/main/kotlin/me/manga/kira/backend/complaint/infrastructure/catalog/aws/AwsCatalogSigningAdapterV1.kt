package me.manga.kira.backend.complaint.infrastructure.catalog.aws

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogGenesisCrypto
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleCrypto
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.awscore.defaultsmode.DefaultsMode
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.regions.PartitionMetadata
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.ServiceMetadataConfiguration
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.MessageType
import software.amazon.awssdk.services.kms.model.SignRequest
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec
import java.net.Proxy
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Independent routing pins only; no trust release, installed key, role, approved intent or publication authority. */
internal class CatalogSigningKeyV1(
    val keyId: String,
    val keyArn: String,
    val algorithmId: String,
    publicKeySpki: ByteArray,
    private val publicKeySha256: String,
) {
    private val spki = catalogSigningCall {
        requireCatalogSigning(OfflineBootstrapGrammar.referenceId(keyId) && keyArn.length <= 256)
        requireCatalogSigning(KEY_ARN.matches(keyArn) && algorithmId == OfflineTrustBundleProtocol.ALGORITHM_ID)
        requireCatalogSigning(publicKeySpki.size == OfflineTrustBundleProtocol.PUBLIC_KEY_BYTES && OfflineBootstrapGrammar.sha256(publicKeySha256))
        publicKeySpki.copyOf()
    }
    internal val region: String = keyArn.split(':')[3]

    internal fun publicKey(): RSAPublicKey {
        requireCatalogSigning(Sha256.hex(spki) == publicKeySha256)
        return OfflineTrustBundleCrypto.publicKey(spki)
    }

    override fun toString(): String = "CatalogSigningKeyV1(independent-pin,redacted,no-authority)"

    companion object {
        private val KEY_ARN = Regex(
            "arn:aws:kms:[a-z]{2}(?:-[a-z0-9]+){1,3}-[0-9]:[0-9]{12}:key/" +
                "(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|mrk-[0-9a-f]{32})",
        )
    }
}

/**
 * One fixed catalog-frame Sign through the genuine SDK, then actual cleanup before returning signature bytes.
 * Supplied manifest bytes are NOT authenticated/approved here. No journal data-key role, SQL, PUT, retry or bean.
 * Finite application/SDK/I/O limits do not claim hard native/DNS/cancellation-completion guarantees.
 */
internal class AwsCatalogSigningAdapterV1 private constructor(
    private val construction: Construction,
    private val key: CatalogSigningKeyV1,
    private val publicKey: RSAPublicKey,
    private val sdk: KmsClient,
    private val transport: BoundedCatalogSigningHttpClientV1,
) : AutoCloseable {
    private val used = AtomicBoolean()

    fun sign(canonicalManifestBytes: ByteArray): ByteArray = catalogSigningCall(checkInterrupted = false) {
        var manifest: ByteArray? = null
        var frame: ByteArray? = null
        var signature: ByteArray? = null
        val result = runCatching {
            withCatalogSigningCleanup(
                {
                    construction.requireRunning() // Even an already-expired/interrupted first Sign must clean up the open owner.
                    requireCatalogSigning(used.compareAndSet(false, true))
                    requireCatalogSigning(canonicalManifestBytes.size in 1..OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES)
                    val snapshot = canonicalManifestBytes.copyOf().also { manifest = it }
                    construction.requireRunning()
                    val message = OfflineCatalogGenesisCrypto.signatureFrame(key.keyId, snapshot).also { frame = it }
                    requireCatalogSigning(message.size in 1..CatalogSigningWireV1.MAX_FRAME_BYTES)
                    construction.requireRunning()
                    transport.begin(message)
                    val timeout = Duration.ofMillis(construction.remainingMillis().toLong())
                    val response = sdk.sign(
                        SignRequest.builder().keyId(key.keyArn).message(SdkBytes.fromByteArray(message)).messageType(MessageType.RAW)
                            .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PSS_SHA_256)
                            .overrideConfiguration(
                                AwsRequestOverrideConfiguration.builder().apiCallTimeout(timeout).apiCallAttemptTimeout(timeout).build(),
                            ).build(),
                    )
                    construction.requireRunning()
                    val observed = transport.observedSignature()
                    requireCatalogSigning(response.keyId() == key.keyArn && response.signingAlgorithmAsString() == key.algorithmId)
                    requireCatalogSigning(response.signingAlgorithm() == SigningAlgorithmSpec.RSASSA_PSS_SHA_256)
                    val blob = response.signature()
                    requireCatalogSigning(blob != null && blob.asByteBuffer().remaining() == OfflineTrustBundleProtocol.SIGNATURE_BYTES)
                    val bytes = checkNotNull(blob).asByteArray().also { signature = it }
                    requireCatalogSigning(Base64.getEncoder().encodeToString(bytes) == observed)
                    construction.requireRunning()
                    OfflineTrustBundleCrypto.verify(publicKey, message, bytes)
                    construction.requireRunning()
                    bytes
                },
                ::close,
            )
        }
        manifest?.fill(0)
        frame?.fill(0)
        val completed = runCatching {
            result.getOrThrow().also { construction.requireResultBudget() } // Cleanup and owned-buffer wiping consume the original budget.
        }
        if (completed.isFailure) signature?.fill(0)
        completed.getOrThrow()
    }

    override fun close() = construction.close()

    override fun toString(): String = "AwsCatalogSigningAdapterV1(one-fixed-signature,redacted,no-mutation-authority)"

    /** The caller must retain this owner BEFORE invoking any factory, including a factory that never returns its native owner. */
    internal class Construction(private val budget: PersistenceTimeBudget) : AutoCloseable {
        private val caller = Thread.currentThread()
        private val closed = AtomicBoolean()
        private var opened = false

        @Volatile private var stage = Stage.NEW

        @Volatile private var raw: SdkHttpClient? = null

        @Volatile private var transport: BoundedCatalogSigningHttpClientV1? = null

        @Volatile private var sdk: KmsClient? = null

        private var rawCloseIssued = false
        private var sdkCloseIssued = false
        private var closeFailure: Throwable? = null

        internal fun open(
            key: CatalogSigningKeyV1,
            credentials: AwsSessionCredentials,
            httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
        ): AwsCatalogSigningAdapterV1 {
            requireConnectionFree()
            return catalogSigningCall {
                synchronized(this) {
                    requireCatalogSigning(!opened && !closed.get())
                    opened = true
                }
                val result = runCatching {
                    requireRunning()
                    val publicKey = key.publicKey()
                    requireRunning()
                    validateCredentials(credentials)
                    val endpoint = regionalEndpoint(key.region)
                    requireRunning()
                    starting(Stage.OPENING_HTTP)
                    val http = httpFactory(::remainingMillis).also {
                        raw = it
                        stage = Stage.HTTP_RETURNED
                    }
                    requireRunning()
                    val bounded = BoundedCatalogSigningHttpClientV1(this, key, endpoint, credentials, http).also { transport = it }
                    val timeout = Duration.ofMillis(remainingMillis().toLong())
                    starting(Stage.OPENING_SDK)
                    val client = KmsClient.builder().region(Region.of(key.region)).credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .defaultsMode(DefaultsMode.STANDARD).dualstackEnabled(false).fipsEnabled(false).endpointOverride(endpoint)
                        .httpClient(bounded).overrideConfiguration(
                            ClientOverrideConfiguration.builder().defaultProfileFile(ProfileFile.aggregator().build()).defaultProfileName(PROFILE_NAME)
                                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                                .apiCallTimeout(timeout).apiCallAttemptTimeout(timeout).build(),
                        ).build().also {
                            sdk = it
                            stage = Stage.SDK_RETURNED
                        }
                    requireRunning()
                    AwsCatalogSigningAdapterV1(this, key, publicKey, client, bounded)
                }
                if (result.isFailure) return@catalogSigningCall withCatalogSigningCleanup({ result.getOrThrow() }, ::close)
                result.getOrThrow()
            }
        }

        @Synchronized
        private fun starting(next: Stage) {
            requireCatalogSigning(!closed.get()) // Atomic with close: never begin a native constructor after a successful empty close.
            stage = next
        }

        internal fun requireRunning() {
            requireResultBudget()
            requireCatalogSigning(opened && !closed.get())
        }

        internal fun remainingMillis(): Int {
            requireRunning()
            return budget.remainingMillis(MAX_CALL_MILLIS).toInt()
        }

        internal fun requireResultBudget() {
            requireConnectionFree()
            requireCatalogSigning(caller === Thread.currentThread())
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            budget.remainingMillis(MAX_CALL_MILLIS)
        }

        @Synchronized
        override fun close() {
            closed.set(true)
            val failure = runCatching {
                catalogSigningCall(CatalogSigningFailureV1.CLOSE_FAILURE, checkInterrupted = false) {
                    withCatalogSigningCleanup(
                        {
                            val bounded = transport
                            if (bounded != null) {
                                bounded.close()
                            } else {
                                raw?.let {
                                    if (!rawCloseIssued) {
                                        rawCloseIssued = true
                                        catalogSigningCall(CatalogSigningFailureV1.CLOSE_FAILURE, false, it::close)
                                    }
                                }
                            }
                        },
                        {
                            sdk?.let {
                                if (!sdkCloseIssued) {
                                    sdkCloseIssued = true
                                    catalogSigningCall(CatalogSigningFailureV1.CLOSE_FAILURE, false, it::close)
                                }
                            }
                        },
                    )
                    requireCatalogSigning(stage != Stage.OPENING_HTTP && stage != Stage.OPENING_SDK)
                }
            }.exceptionOrNull()
            if (failure != null && replaceCatalogSigningFailure(closeFailure, failure)) closeFailure = failure
            closeFailure?.let { throw it } // Unknown construction/close is sticky even if a late owner subsequently returns.
        }

        override fun toString(): String = "CatalogSigningConstruction(retained,redacted,no-authority)"

        private enum class Stage { NEW, OPENING_HTTP, HTTP_RETURNED, OPENING_SDK, SDK_RETURNED }
    }

    companion object {
        private const val PROFILE_NAME = "complaint-catalog-sign-v1"
        private const val MAX_CALL_MILLIS = 600_000L

        fun openOwned(construction: Construction, key: CatalogSigningKeyV1, credentials: AwsSessionCredentials): AwsCatalogSigningAdapterV1 =
            construction.open(key, credentials, ::catalogSigningUrlConnectionClient)

        /** Raw HTTP substitution only: the genuine SDK still marshals, SigV4 signs and decodes. No supplied KMS result or verifier. */
        fun withHttpFixture(
            construction: Construction,
            key: CatalogSigningKeyV1,
            credentials: AwsSessionCredentials,
            httpFactory: () -> SdkHttpClient,
        ): AwsCatalogSigningAdapterV1 = construction.open(key, credentials) { httpFactory() }

        private fun regionalEndpoint(regionId: String): URI {
            requireCatalogSigning(
                System.getProperty(SdkSystemSetting.AWS_PARTITIONS_FILE.property()) == null &&
                    System.getenv(SdkSystemSetting.AWS_PARTITIONS_FILE.environmentVariable()) == null,
            )
            val region = Region.regions().singleOrNull { it.id() == regionId }
            requireCatalogSigning(region != null)
            val metadata = KmsClient.serviceMetadata().reconfigure(
                ServiceMetadataConfiguration.builder().profileFile { ProfileFile.aggregator().build() }.profileName(PROFILE_NAME).build(),
            )
            requireCatalogSigning(region in metadata.regions() && metadata.signingRegion(checkNotNull(region)) == region)
            requireCatalogSigning(PartitionMetadata.of(checkNotNull(region)).id() == "aws")
            val observed = metadata.endpointFor(region)
            val endpoint = if (observed.scheme == null) URI.create("https://$observed") else observed
            requireCatalogSigning(
                endpoint.scheme == "https" && endpoint.host != null && endpoint.userInfo == null && endpoint.query == null &&
                    endpoint.fragment == null && endpoint.path in listOf("", "/") && endpoint.port in listOf(-1, 443),
            )
            return endpoint // Internally pinned SDK metadata, never a caller/ambient endpoint override.
        }

        private fun validateCredentials(credentials: AwsSessionCredentials) {
            requireCatalogSigning(credentials.accessKeyId().length in 1..128 && credentials.secretAccessKey().length in 1..256)
            requireCatalogSigning(credentials.sessionToken().length in 1..16_384)
            requireCatalogSigning(
                listOf(credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()).all { value -> value.all { it in '!'..'~' } },
            )
        }
    }
}

private fun catalogSigningUrlConnectionClient(remainingMillis: () -> Int): SdkHttpClient = UrlConnectionHttpClient.create { uri ->
    requireConnectionFree()
    requireCatalogSigning(uri.scheme == "https")
    val timeout = remainingMillis()
    (uri.toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
        connectTimeout = timeout
        readTimeout = timeout
        instanceFollowRedirects = false
        useCaches = false
        allowUserInteraction = false
    }
}

internal enum class CatalogSigningFailureV1 { SIGN_FAILURE, CLOSE_FAILURE }

internal class CatalogSigningExceptionV1(val code: CatalogSigningFailureV1) : RuntimeException("Catalog signing rejected: ${code.name}.")

internal fun requireCatalogSigning(condition: Boolean) {
    if (!condition) throw CatalogSigningExceptionV1(CatalogSigningFailureV1.SIGN_FAILURE)
}

/** Sanitize ordinary/SDK diagnostic graphs; preserve only bounded cancellation/interruption/fatal classification. */
@Suppress("TooGenericExceptionCaught", "SwallowedException") // Provider diagnostic graphs must not escape this explicit cause-free boundary.
internal fun <T> catalogSigningCall(
    code: CatalogSigningFailureV1 = CatalogSigningFailureV1.SIGN_FAILURE,
    checkInterrupted: Boolean = true,
    action: () -> T,
): T = try {
    if (checkInterrupted && Thread.currentThread().isInterrupted) throw InterruptedException()
    catalogSigningSignal(action)
} catch (_: CancellationException) {
    throw CancellationException("Catalog signing cancelled.")
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    throw InterruptedException("Catalog signing interrupted.")
} catch (failure: CatalogSigningExceptionV1) {
    throw CatalogSigningExceptionV1(if (code == CatalogSigningFailureV1.CLOSE_FAILURE) code else failure.code)
} catch (_: Exception) {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Catalog signing interrupted.")
    throw CatalogSigningExceptionV1(code)
}

private fun <T> catalogSigningSignal(action: () -> T): T = try {
    action()
} catch (failure: SdkException) {
    val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
    causes.filterIsInstance<Error>().firstOrNull()?.let { throw it }
    if (causes.any { it is CancellationException }) throw CancellationException()
    if (Thread.currentThread().isInterrupted || causes.any { it is InterruptedException }) throw InterruptedException()
    throw failure
}

internal fun <T> withCatalogSigningCleanup(action: () -> T, cleanup: () -> Unit): T {
    val result = runCatching { catalogSigningSignal(action) }
    val closing = runCatching { catalogSigningSignal(cleanup) }.exceptionOrNull()
    if (closing != null && replaceCatalogSigningFailure(result.exceptionOrNull(), closing)) throw closing
    return result.getOrThrow()
}

internal fun replaceCatalogSigningFailure(previous: Throwable?, closing: Throwable): Boolean = when {
    closing is Error || previous == null -> true
    previous is Error -> false
    closing is CancellationException -> true
    previous is CancellationException -> false
    closing is InterruptedException -> true
    previous is InterruptedException -> false
    else -> closing is CatalogSigningExceptionV1 && closing.code == CatalogSigningFailureV1.CLOSE_FAILURE
}
