package me.manga.kira.backend.security.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.security.ImmutableSecretVersion
import me.manga.kira.backend.security.SecretVersionException
import me.manga.kira.backend.security.SecretVersionFailure
import me.manga.kira.backend.security.SecretVersionResolver
import me.manga.kira.backend.security.SecretVersionSnapshot
import me.manga.kira.backend.security.requireSecretVersion
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
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
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse
import java.net.Proxy
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Finite application/transport ceilings, not a hard native/DNS/cancellation completion guarantee. */
internal class AwsSecretVersionLimits(
    val requestTimeoutMillis: Long = 10_000,
    val connectTimeoutMillis: Int = 2_000,
    val readTimeoutMillis: Int = 2_000,
    val maximumResponseBytes: Int = 128 * 1024,
    val maximumMaterialBytes: Int = SecretVersionJsonPreflight.MAX_MATERIAL_BYTES,
) {
    init {
        requireSecretVersion(requestTimeoutMillis in 1..60_000, SecretVersionFailure.INVALID_BINDING)
        requireSecretVersion(connectTimeoutMillis.toLong() in 1..requestTimeoutMillis, SecretVersionFailure.INVALID_BINDING)
        requireSecretVersion(readTimeoutMillis.toLong() in 1..requestTimeoutMillis, SecretVersionFailure.INVALID_BINDING)
        requireSecretVersion(maximumResponseBytes in 1..128 * 1024, SecretVersionFailure.INVALID_BINDING)
        requireSecretVersion(maximumMaterialBytes in 1..SecretVersionJsonPreflight.MAX_MATERIAL_BYTES, SecretVersionFailure.INVALID_BINDING)
    }

    override fun toString(): String = "AwsSecretVersionLimits(bounded)"
}

/**
 * Dormant exact-version reads in one explicit commercial region. No secret discovery, defaults,
 * stage/string fallback, cache, bean or activation authority. The caller owns and closes this resolver.
 */
internal class AwsSecretsManagerVersionResolver private constructor(
    private val region: String,
    private val sdk: SecretsManagerClient,
    private val transport: BoundedSecretSdkHttpClient,
    private val limits: AwsSecretVersionLimits,
) : SecretVersionResolver,
    AutoCloseable {
    private val busy = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val sdkCloseIssued = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()

    override fun resolve(version: ImmutableSecretVersion): SecretVersionSnapshot {
        requireConnectionFree()
        requireSecretVersion(version.resourceArn.split(':')[3] == region, SecretVersionFailure.INVALID_REFERENCE)
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Secret-version resolution interrupted.")
        requireSecretVersion(!closed.get() && busy.compareAndSet(false, true), SecretVersionFailure.RESOLVER_FAILURE)
        return withSecretCleanup(
            {
                transport.begin(version)
                val response = secretSdkCall {
                    sdk.getSecretValue(
                        GetSecretValueRequest.builder().secretId(version.resourceArn).versionId(version.versionId).build(),
                    )
                }
                secretProviderCall {
                    val report = transport.observed(version)
                    requireSecretVersion(!closed.get(), SecretVersionFailure.RESOLVER_FAILURE)
                    snapshot(response, report, version)
                }
            },
            {
                transport.finishRequest() // A failed cleanup retains the slot and cannot become a different lookup's resource.
                busy.set(false)
            },
        )
    }

    private fun snapshot(response: GetSecretValueResponse, report: SecretVersionWireReport, requested: ImmutableSecretVersion): SecretVersionSnapshot {
        val arn = response.arn()
        val version = response.versionId()
        requireSecretVersion(arn != null && version != null && response.secretString() == null, SecretVersionFailure.RESOLVER_FAILURE)
        // Read the SDK's independently decoded response fields, never copy request labels beside material.
        val observed = ImmutableSecretVersion.awsSecretsManager(requireNotNull(arn), requireNotNull(version))
        requireSecretVersion(observed == requested && observed == report.version, SecretVersionFailure.REFERENCE_MISMATCH)
        val binary = response.secretBinary() ?: throw SecretVersionException(SecretVersionFailure.INVALID_MATERIAL)
        val size = binary.asByteBuffer().remaining()
        requireSecretVersion(size == report.materialSize && size in 1..limits.maximumMaterialBytes, SecretVersionFailure.INVALID_MATERIAL)
        // AWS_JSON's SDK_BYTES decoder has already decoded Base64. These are bytes, not another encoded string.
        val material = binary.asByteArray()
        return try {
            SecretVersionSnapshot(observed, material)
        } finally {
            material.fill(0)
        }
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        val failure = runCatching {
            withSecretCleanup(transport::close) {
                if (sdkCloseIssued.compareAndSet(false, true)) secretProviderCall { sdk.close() }
            }
        }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceSecretFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
    }

    override fun toString(): String = "AwsSecretsManagerVersionResolver(exact-version,redacted,no-activation-authority)"

    companion object {
        /** Explicit session credentials only. Construction does not dispatch a request or discover credentials/region. */
        fun open(
            region: String,
            credentials: AwsSessionCredentials,
            limits: AwsSecretVersionLimits = AwsSecretVersionLimits(),
        ): AwsSecretsManagerVersionResolver = create(region, credentials, limits, { secretUrlConnectionClient(limits) }, System::nanoTime)

        /** Controlled HTTP substitution still uses the real SDK's signing, AWS-JSON marshalling and response decoding. */
        fun withHttpFixture(
            region: String,
            credentials: AwsSessionCredentials,
            limits: AwsSecretVersionLimits,
            httpFactory: () -> SdkHttpClient,
            nanoTime: () -> Long = System::nanoTime,
        ): AwsSecretsManagerVersionResolver = create(region, credentials, limits, httpFactory, nanoTime)

        private fun create(
            regionId: String,
            credentials: AwsSessionCredentials,
            limits: AwsSecretVersionLimits,
            httpFactory: () -> SdkHttpClient,
            nanoTime: () -> Long,
        ): AwsSecretsManagerVersionResolver {
            requireConnectionFree()
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Secret-version resolution interrupted.")
            validateCredentials(credentials)
            requireSecretVersion(
                System.getProperty(SdkSystemSetting.AWS_PARTITIONS_FILE.property()) == null &&
                    System.getenv(SdkSystemSetting.AWS_PARTITIONS_FILE.environmentVariable()) == null,
                SecretVersionFailure.INVALID_BINDING,
            )
            requireSecretVersion(regionId.length <= 64 && REGION.matches(regionId), SecretVersionFailure.INVALID_REFERENCE)
            val region = Region.regions().singleOrNull { it.id() == regionId }
                ?: throw SecretVersionException(SecretVersionFailure.INVALID_REFERENCE)
            val emptyProfile = ProfileFile.aggregator().build()
            val endpoint = secretProviderCall { regionalEndpoint(region, emptyProfile) }
            val transport = BoundedSecretSdkHttpClient(secretProviderCall(httpFactory), regionId, endpoint, limits, nanoTime)
            val built = runCatching {
                secretSdkCall {
                    SecretsManagerClient.builder()
                        .region(region)
                        .credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .defaultsMode(DefaultsMode.STANDARD)
                        .dualstackEnabled(false)
                        .fipsEnabled(false)
                        .endpointOverride(endpoint) // Explicit SDK-derived URL takes priority over profile/system endpoint overrides.
                        .httpClient(transport)
                        .overrideConfiguration(
                            ClientOverrideConfiguration.builder()
                                .defaultProfileFile(emptyProfile).defaultProfileName(PROFILE_NAME)
                                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                                .apiCallTimeout(Duration.ofMillis(limits.requestTimeoutMillis))
                                .apiCallAttemptTimeout(Duration.ofMillis(limits.requestTimeoutMillis))
                                .build(),
                        )
                        .build()
                }
            }
            built.exceptionOrNull()?.let { failure -> return withSecretCleanup({ throw failure }, transport::close) }
            return AwsSecretsManagerVersionResolver(regionId, built.getOrThrow(), transport, limits)
        }

        private fun regionalEndpoint(region: Region, emptyProfile: ProfileFile): URI {
            val metadata = SecretsManagerClient.serviceMetadata().reconfigure(
                ServiceMetadataConfiguration.builder().profileFile { emptyProfile }.profileName(PROFILE_NAME).build(),
            )
            requireSecretVersion(
                region in metadata.regions() && metadata.signingRegion(region) == region && PartitionMetadata.of(region).id() == "aws",
                SecretVersionFailure.INVALID_REFERENCE,
            )
            val observed = metadata.endpointFor(region)
            val endpoint = if (observed.scheme == null) URI.create("https://$observed") else observed
            requireSecretVersion(
                endpoint.scheme == "https" && endpoint.host != null && endpoint.userInfo == null && endpoint.query == null &&
                    endpoint.fragment == null && endpoint.path in listOf("", "/") && endpoint.port in listOf(-1, 443),
                SecretVersionFailure.INVALID_BINDING,
            )
            return endpoint
        }

        private fun validateCredentials(credentials: AwsSessionCredentials) {
            requireSecretVersion(credentials.accessKeyId().length in 1..128, SecretVersionFailure.INVALID_BINDING)
            requireSecretVersion(credentials.secretAccessKey().length in 1..256, SecretVersionFailure.INVALID_BINDING)
            requireSecretVersion(credentials.sessionToken().length in 1..16_384, SecretVersionFailure.INVALID_BINDING)
            requireSecretVersion(
                credentials.accessKeyId().all { it in '!'..'~' } && credentials.secretAccessKey().all { it in '!'..'~' } &&
                    credentials.sessionToken().all { it in '!'..'~' },
                SecretVersionFailure.INVALID_BINDING,
            )
        }

        private const val PROFILE_NAME = "immutable-secret-version"
        private val REGION = Regex("[a-z]{2}(?:-[a-z0-9]+){1,3}-[0-9]{1,2}")
    }
}

/** SDK URLConnection implementation, explicit no-proxy factory; configuring the connection does not connect it. */
internal fun secretUrlConnectionClient(limits: AwsSecretVersionLimits): SdkHttpClient = UrlConnectionHttpClient.create { uri ->
    requireSecretVersion(uri.scheme == "https", SecretVersionFailure.INVALID_BINDING)
    (uri.toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
        connectTimeout = limits.connectTimeoutMillis
        readTimeout = limits.readTimeoutMillis
        instanceFollowRedirects = false
        useCaches = false
        allowUserInteraction = false
    }
}
