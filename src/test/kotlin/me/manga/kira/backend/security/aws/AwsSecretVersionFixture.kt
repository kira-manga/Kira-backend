package me.manga.kira.backend.security.aws

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.security.ImmutableSecretVersion
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.SecretMaterialPurpose
import me.manga.kira.backend.security.VersionedSecretBinding
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.http.SdkHttpResponse
import java.io.InputStream
import java.util.Base64

/** Actual SecretsManagerClient/signing/unmarshalling, controlled public HTTP SPI only. No listeners, AWS or credential discovery. */
internal class AwsSecretVersionFixture {
    val requests = mutableListOf<SecretHttpRequest>()
    val replies = mutableListOf<SecretHttpReply>()
    var createdClients = 0
    var closedClients = 0
    var now = 0L
    var beforePrepare: () -> Unit = {}
    var onClientClose: () -> Unit = {}
    var respond: (SecretHttpRequest) -> SecretHttpReply = { reply() }

    fun resolver(
        region: String = REGION,
        credentials: AwsSessionCredentials = CREDENTIALS,
        limits: AwsSecretVersionLimits = AwsSecretVersionLimits(),
        httpFactory: () -> SdkHttpClient = ::httpClient,
    ): AwsSecretsManagerVersionResolver = AwsSecretsManagerVersionResolver.withHttpFixture(region, credentials, limits, httpFactory) { now }

    fun httpClient(): SdkHttpClient {
        createdClients++
        return object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest {
                beforePrepare()
                val bytes = request.contentStreamProvider().orElseThrow().newStream().use { it.readNBytes(SecretVersionJsonPreflight.MAX_REQUEST_BYTES + 1) }
                check(bytes.size <= SecretVersionJsonPreflight.MAX_REQUEST_BYTES)
                val captured = SecretHttpRequest(request.httpRequest(), bytes.toString(Charsets.UTF_8))
                requests.add(captured)
                val reply = respond(captured)
                replies.add(reply)
                return object : ExecutableHttpRequest {
                    override fun call(): HttpExecuteResponse {
                        reply.calls++
                        reply.beforeCall()
                        return reply.response()
                    }

                    override fun abort() {
                        reply.aborts++
                        reply.onAbort()
                    }
                }
            }

            override fun close() {
                closedClients++
                onClientClose()
            }

            override fun clientName(): String = "SyntheticSecretVersionSync"
        }
    }

    companion object {
        const val REGION = "eu-west-1"
        const val ARN = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:kira/fixture-secret-AbC123"
        const val VERSION = "64000000-0000-4000-8000-000000000001"
        const val PRIVATE_TEXT = "synthetic-private-secret-provider-text"
        val CREDENTIALS: AwsSessionCredentials = AwsSessionCredentials.create(
            "SYNTHETICACCESSKEY", "synthetic-secret-not-a-real-credential", "synthetic-session",
        )

        fun reference(number: Int = 1, arn: String = ARN): ImmutableSecretVersion =
            ImmutableSecretVersion.awsSecretsManager(arn, "64000000-0000-4000-8000-${number.toString(16).padStart(12, '0')}")

        fun binding(
            number: Int = 1,
            family: SecretMaterialFamily = SecretMaterialFamily.INSTALLATION_JWT,
            id: String = "installation-$number",
        ): VersionedSecretBinding = VersionedSecretBinding.of(family, SecretMaterialPurpose.HMAC_SHA256, id, reference(number))

        fun material(seed: Int = 1, size: Int = 32): ByteArray = ByteArray(size) { (it * 7 + seed).toByte() }

        fun document(version: ImmutableSecretVersion = reference(), material: ByteArray = material()): String =
            """{"ARN":"${version.resourceArn}","VersionId":"${version.versionId}","SecretBinary":"${Base64.getEncoder().encodeToString(material)}"}"""

        fun reply(version: ImmutableSecretVersion = reference(), material: ByteArray = material()): SecretHttpReply =
            SecretHttpReply(document(version, material).toByteArray(Charsets.UTF_8))
    }
}

internal class SecretHttpRequest(val http: SdkHttpRequest, val json: String) {
    fun fields(): Map<String, String> = ObjectMapper().readTree(json).properties().associate { it.key to it.value.textValue() }
    override fun toString(): String = "SecretHttpRequest(synthetic,redacted)"
}

/** Stream abort is deliberately a no-op as for URLConnection; executable abort and close are observed separately. */
internal class SecretHttpReply(val bytes: ByteArray) {
    var status = 200
    var headers: Map<String, List<String>> = mapOf(
        "Content-Length" to listOf(bytes.size.toString()),
        "Content-Type" to listOf("application/x-amz-json-1.1"),
    )
    var bodyPresent = true
    var chunkSize = Int.MAX_VALUE
    var calls = 0
    var reads = 0
    var eofProbes = 0
    var bytesRead = 0
    var closes = 0
    var aborts = 0
    var beforeCall: () -> Unit = {}
    var beforeRead: () -> Unit = {}
    var onClose: () -> Unit = {}
    var onAbort: () -> Unit = {}

    fun response(): HttpExecuteResponse {
        val response = HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(status).headers(headers).build())
        if (bodyPresent) {
            val body = object : InputStream() {
                private var position = 0

                override fun read(): Int {
                    val single = ByteArray(1)
                    return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xff
                }

                override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                    reads++
                    beforeRead()
                    if (position == bytes.size) {
                        eofProbes++
                        return -1
                    }
                    val count = minOf(chunkSize, length, bytes.size - position)
                    bytes.copyInto(destination, offset, position, position + count)
                    position += count
                    bytesRead += count
                    return count
                }

                override fun close() {
                    closes++
                    onClose()
                }
            }
            response.responseBody(AbortableInputStream.create(body))
        }
        return response.build()
    }
}
