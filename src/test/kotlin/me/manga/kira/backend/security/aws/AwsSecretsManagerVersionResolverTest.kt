package me.manga.kira.backend.security.aws

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.SecretVersionException
import me.manga.kira.backend.security.SecretVersionFailure
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture.Companion.ARN
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture.Companion.PRIVATE_TEXT
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture.Companion.REGION
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture.Companion.VERSION
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture.Companion.binding
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture.Companion.document
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture.Companion.material
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture.Companion.reference
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture.Companion.reply
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.http.SdkHttpMethod
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CancellationException

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AwsSecretsManagerVersionResolverTest {
    @Test
    fun `actual SDK signs exactly full ARN and VersionId and returns binary decoded once`() {
        val fixture = AwsSecretVersionFixture()
        val bytes = "QUJD".toByteArray() // Decoding the SDK's already-decoded bytes again would silently change these to ABC.
        val response = reply(material = bytes).apply { chunkSize = 3 }
        fixture.respond = { response }
        val expected = binding()
        fixture.resolver().use { resolver ->
            assertTrue(fixture.requests.isEmpty())
            val acquired = AcquiredVersionedSecret.acquire(expected, resolver)
            assertEquals(expected.version, acquired.descriptor.version)
            assertEquals(expected.family, acquired.descriptor.family)
            assertEquals(expected.purpose, acquired.descriptor.purpose)
            assertEquals(expected.logicalKeyId, acquired.descriptor.logicalKeyId)
            assertNotSame(expected.version, acquired.descriptor.version)
            response.bytes.fill(0)
            acquired.useMaterial { assertArrayEquals(bytes, it) }
            val request = fixture.requests.single()
            assertEquals(mapOf("SecretId" to ARN, "VersionId" to VERSION), request.fields())
            assertEquals(SdkHttpMethod.POST, request.http.method())
            assertEquals("https", request.http.protocol())
            assertEquals("secretsmanager.eu-west-1.amazonaws.com", request.http.host())
            assertEquals(443, request.http.port())
            assertEquals("/", request.http.encodedPath())
            assertTrue(request.http.rawQueryParameters().isEmpty())
            assertEquals("secretsmanager.GetSecretValue", request.http.firstMatchingHeader("X-Amz-Target").orElseThrow())
            assertEquals("synthetic-session", request.http.firstMatchingHeader("X-Amz-Security-Token").orElseThrow())
            val authorization = request.http.firstMatchingHeader("Authorization").orElseThrow()
            assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=SYNTHETICACCESSKEY/"))
            assertTrue(authorization.contains("/$REGION/secretsmanager/aws4_request"))
            assertTrue(authorization.contains("x-amz-target") && authorization.contains("x-amz-security-token"))
            assertTrue(Regex(".*Signature=[0-9a-f]{64}").matches(authorization))
            assertFalse(resolver.toString().contains(ARN))
        }
        assertReleased(response)
        assertEquals(1, response.eofProbes)
        assertEquals(1, fixture.closedClients)
    }

    @Test
    fun `one through 65536 arbitrary binary bytes work with absent length and never become text`() {
        val fixture = AwsSecretVersionFixture()
        fixture.resolver().use { resolver ->
            listOf(1, 65_536).forEach { size ->
                val bytes = material(size = size)
                val response = reply(material = bytes).apply { headers = headers - "Content-Length" }
                fixture.respond = { response }
                AcquiredVersionedSecret.acquire(binding(), resolver).useMaterial { assertArrayEquals(bytes, it) }
                assertReleased(response)
                assertEquals(1, response.eofProbes)
            }
        }
        assertEquals(2, fixture.requests.size)
        assertEquals(1, fixture.closedClients)
    }

    @Test
    fun `response identity is independently required with no ARN VersionId stage or string fallback`() {
        val correct = document()
        val invalid = listOf(
            document(reference(2)),
            document(reference(arn = ARN.replace("123456789012", "123456789013"))),
            document(reference(arn = ARN.replace("fixture-secret", "another-secret"))),
            document(reference(arn = ARN.replace(REGION, "us-west-2"))),
            correct.replace("\"ARN\":\"$ARN\",", ""),
            correct.replace("\"VersionId\":\"$VERSION\",", ""),
            correct.replace("\"$ARN\"", "null"),
            correct.replace("\"$VERSION\"", "null"),
            correct.replace(ARN, "kira/fixture-secret"),
            correct.replace(VERSION, "AWSCURRENT"),
            correct.replace(VERSION, VERSION.uppercase().replace("64000000", "6400000A")),
            correct.replace("SecretBinary", "SecretString"),
            correct.dropLast(1) + ",\"SecretString\":null}",
            correct.dropLast(1) + ",\"VersionStage\":\"AWSCURRENT\"}",
        )
        invalid.forEach(::rejectDocument)
    }

    @Test
    fun `strict preflight rejects SDK coercion duplicate escaped names trailing roots unknown fields and nested input`() {
        val correct = document()
        val encoded = Base64.getEncoder().encodeToString(material())
        listOf(
            "", "null", "[]", "{}", correct + "{}", correct + PRIVATE_TEXT,
            correct.dropLast(1) + ",\"ARN\":\"$ARN\"}",
            correct.dropLast(1) + ",\"A\\u0052N\":\"$ARN\"}",
            correct.dropLast(1) + ",\"Unknown\":\"$PRIVATE_TEXT\"}",
            correct.replace("\"$encoded\"", "null"),
            correct.replace("\"$encoded\"", "1234"),
            correct.replace("\"$encoded\"", "true"),
            correct.replace("\"$encoded\"", "[]"),
            correct.replace("\"$encoded\"", "{\"nested\":\"$PRIVATE_TEXT\"}"),
            correct.replace("\"$encoded\"", "[".repeat(1000) + "0" + "]".repeat(1000)),
            correct.dropLast(1) + ",\"Name\":\"${"x".repeat(257)}\"}",
            correct.dropLast(1) + ",\"VersionStages\":[${List(21) { "\"retained\"" }.joinToString()}]}",
            correct.dropLast(1) + ",\"CreatedDate\":\"1700000000\"}",
        ).forEach(::rejectDocument)
        listOf(
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + correct.toByteArray(),
            correct.toByteArray(Charsets.UTF_16LE),
            correct.toByteArray(Charsets.UTF_16BE),
            correct.replace(ARN, "malformed").toByteArray().let { it.apply { this[9] = 0xff.toByte() } },
        ).forEach { rejectReply(SecretHttpReply(it)) }
    }

    @Test
    fun `wire UTF8 rejects overlong surrogate out of range and truncated sequences before either JSON parser`() {
        val prefix = "{\"ARN\":\"".toByteArray()
        val suffix = document().removePrefix("{\"ARN\":\"a").toByteArray()
        // First three would decode to the expected initial 'a' under a permissive overlong decoder.
        listOf(
            byteArrayOf(0xc1.toByte(), 0xa1.toByte()),
            byteArrayOf(0xe0.toByte(), 0x81.toByte(), 0xa1.toByte()),
            byteArrayOf(0xf0.toByte(), 0x80.toByte(), 0x81.toByte(), 0xa1.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xe2.toByte(), 0x82.toByte()),
            byteArrayOf(0x80.toByte()),
        ).forEach { invalid -> rejectReply(SecretHttpReply(prefix + invalid + suffix)) }
    }

    @Test
    fun `canonical Base64 and exact decoded ceiling are checked before permissive SDK blob decoding`() {
        val encoded = Base64.getEncoder().encodeToString(material())
        val invalid = listOf("", "AA", "AAA", "A===", "AA=A", "AA-_", "AA==\\n", "AB==", "AAB=", "AAAA=", "A A=") +
            listOf(65_537, 65_538, 65_539).map { Base64.getEncoder().encodeToString(material(size = it)) }
        invalid.forEach { value -> rejectDocument(document().replace(encoded, value)) }
        val fixture = AwsSecretVersionFixture()
        val response = reply(material = material(size = 33))
        fixture.respond = { response }
        fixture.resolver(limits = AwsSecretVersionLimits(maximumMaterialBytes = 32)).use { resolver ->
            rejected { resolver.resolve(reference()) }
        }
        assertReleased(response)
    }

    @Test
    fun `bounded optional metadata never demands a current stage or replaces exact returned identity`() {
        val fixture = AwsSecretVersionFixture()
        val response = SecretHttpReply(
            (document().dropLast(1) + ",\"Name\":\"fixture\",\"VersionStages\":[\"retained-π\"],\"CreatedDate\":1700000000.125}").toByteArray(),
        )
        fixture.respond = { response }
        fixture.resolver().use { resolver ->
            assertEquals(reference(), resolver.resolve(reference()).version)
        }
        assertReleased(response)
    }

    @Test
    fun `wire ceilings content lengths progress and EOF fail closed without unbounded allocation`() {
        listOf("huge", "negative", "duplicate", "short", "long", "zero", "absent-body", "throw").forEach { fault ->
            val response = reply().apply {
                when (fault) {
                    "huge" -> headers = headers + ("Content-Length" to listOf(Long.MAX_VALUE.toString()))
                    "negative" -> headers = headers + ("Content-Length" to listOf("-1"))
                    "duplicate" -> headers = headers + ("Content-Length" to listOf(bytes.size.toString(), bytes.size.toString()))
                    "short" -> headers = headers + ("Content-Length" to listOf((bytes.size + 1).toString()))
                    "long" -> headers = headers + ("Content-Length" to listOf((bytes.size - 1).toString()))
                    "zero" -> chunkSize = 0
                    "absent-body" -> bodyPresent = false
                    else -> beforeRead = { throw IOException(PRIVATE_TEXT) }
                }
            }
            rejectReply(response)
            if (fault in setOf("huge", "negative", "duplicate", "absent-body")) assertEquals(0, response.reads)
        }
        val maximum = AwsSecretVersionLimits().maximumResponseBytes
        val response = SecretHttpReply(ByteArray(maximum + 1000) { ' '.code.toByte() }).apply { headers = headers - "Content-Length" }
        rejectReply(response)
        assertEquals(maximum + 1, response.bytesRead)
    }

    @Test
    fun `redirect error compressed and ambiguous header responses are refused unread with one SDK attempt`() {
        listOf(301, 302, 307, 403, 429, 500, 503).forEach { status ->
            val response = SecretHttpReply("{\"__type\":\"InternalServiceError\",\"Message\":\"$PRIVATE_TEXT\"}".toByteArray()).apply {
                this.status = status
                headers = headers + ("Location" to listOf("https://synthetic-unapproved.invalid/"))
            }
            rejectReply(response)
            assertEquals(0, response.reads)
        }
        listOf(
            "Content-Encoding" to listOf("gzip"),
            "Content-Range" to listOf("bytes 0-4/8"),
            "Content-Type" to listOf("text/plain"),
            "content-type" to listOf("application/x-amz-json-1.1"),
            "Content-Type" to listOf("application/x-amz-json-1.1", "application/x-amz-json-1.1"),
            "Transfer-Encoding" to listOf("chunked"),
            "X-Bad" to listOf("$PRIVATE_TEXT\n"),
            "X-Large" to listOf("x".repeat(65_537)),
        ).forEach { header ->
            val response = reply().apply { headers = headers + header }
            rejectReply(response)
            assertEquals(0, response.reads)
        }
    }

    @Test
    fun `ambient endpoint profile credentials defaults retries and regions cannot replace explicit SDK inputs`() {
        withProperties(
            mapOf(
                "aws.endpointUrlSecretsManager" to "http://synthetic-unapproved.invalid/",
                "aws.endpointUrl" to "https://synthetic-unapproved.invalid/",
                "aws.region" to "us-west-2",
                "aws.accessKeyId" to "SYNTHETICOTHERACCESS",
                "aws.secretAccessKey" to "synthetic-other-secret",
                "aws.sessionToken" to "synthetic-other-session",
                "aws.profile" to "synthetic-must-not-resolve",
                "aws.configFile" to "/synthetic-must-not-read/config",
                "aws.sharedCredentialsFile" to "/synthetic-must-not-read/credentials",
                "aws.defaultsMode" to "auto",
                "aws.maxAttempts" to "5",
                "aws.retryMode" to "adaptive",
                "aws.useFipsEndpoint" to "true",
                "aws.useDualstackEndpoint" to "true",
            ),
        ) {
            val fixture = AwsSecretVersionFixture()
            fixture.resolver().use { resolver ->
                resolver.resolve(reference())
                val request = fixture.requests.single().http
                assertEquals("secretsmanager.eu-west-1.amazonaws.com", request.host())
                assertTrue(request.firstMatchingHeader("Authorization").orElseThrow().contains("Credential=SYNTHETICACCESSKEY/"))
                assertEquals("synthetic-session", request.firstMatchingHeader("X-Amz-Security-Token").orElseThrow())
                fixture.respond = { reply().apply { status = 503 } }
                rejected { resolver.resolve(reference()) }
            }
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test
    fun `unsupported region partitions ambient metadata and invalid sessions fail before HTTP factory or lookup`() {
        val fixture = AwsSecretVersionFixture()
        listOf("", "latest", "zz-invalid-99", "us-gov-west-1", "cn-north-1", "us-iso-east-1", "fips-us-east-1").forEach { region ->
            assertSanitized(assertThrows(SecretVersionException::class.java) { fixture.resolver(region = region) })
        }
        withProperties(mapOf(SdkSystemSetting.AWS_PARTITIONS_FILE.property() to "/synthetic-must-not-read/partitions.json")) {
            assertSanitized(assertThrows(SecretVersionException::class.java) { fixture.resolver() })
        }
        val invalid = AwsSessionCredentials.create("SYNTHETICACCESSKEY", "synthetic-secret", "bad\n$PRIVATE_TEXT")
        assertSanitized(assertThrows(SecretVersionException::class.java) { fixture.resolver(credentials = invalid) })
        assertEquals(0, fixture.createdClients)
        fixture.resolver().use { resolver ->
            assertSanitized(assertThrows(SecretVersionException::class.java) { resolver.resolve(reference(arn = ARN.replace(REGION, "us-west-2"))) })
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `connection-free gates precede construction and dispatch and guard each body read`() {
        val fixture = AwsSecretVersionFixture()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertThrows(PersistencePhaseException::class.java) { fixture.resolver() }
            assertEquals(0, fixture.createdClients)
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        fixture.resolver().use { resolver ->
            TransactionSynchronizationManager.initSynchronization()
            try {
                assertThrows(PersistencePhaseException::class.java) { resolver.resolve(reference()) }
                assertTrue(fixture.requests.isEmpty())
            } finally {
                TransactionSynchronizationManager.clearSynchronization()
            }
            val response = reply().apply { beforeRead = { TransactionSynchronizationManager.setActualTransactionActive(true) } }
            fixture.respond = { response }
            try {
                rejected { resolver.resolve(reference()) }
                assertEquals(1, response.reads)
                assertReleased(response)
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
        }
    }

    @Test
    fun `cancellation and interruption survive SDK wrapping and acquisition with bounded diagnostics and cleanup`() {
        listOf("call", "read", "wrapped", "close").forEach { stage ->
            listOf(false, true).forEach { interrupted ->
                val fixture = AwsSecretVersionFixture()
                val failure = if (interrupted) InterruptedException(PRIVATE_TEXT) else CancellationException(PRIVATE_TEXT)
                val response = reply().apply {
                    when (stage) {
                        "call" -> beforeCall = { throw failure }
                        "read" -> beforeRead = { throw failure }
                        "wrapped" -> beforeCall = { throw SdkClientException.builder().message(PRIVATE_TEXT).cause(failure).build() }
                        else -> onClose = { throw failure }
                    }
                }
                fixture.respond = { response }
                val resolver = fixture.resolver()
                try {
                    if (interrupted) {
                        val rejected = assertThrows(SecretVersionException::class.java) { AcquiredVersionedSecret.acquire(binding(), resolver) }
                        assertEquals(SecretVersionFailure.INTERRUPTED, rejected.code)
                        assertSanitized(rejected)
                        assertTrue(Thread.currentThread().isInterrupted)
                    } else {
                        assertSanitized(assertThrows(CancellationException::class.java) { AcquiredVersionedSecret.acquire(binding(), resolver) })
                    }
                    assertEquals(1, fixture.requests.size)
                    assertEquals(1, response.aborts)
                    assertEquals(if (stage == "call" || stage == "wrapped") 0 else 1, response.closes)
                } finally {
                    Thread.interrupted()
                    val closeFailure = runCatching { resolver.close() }.exceptionOrNull()
                    if (stage == "close") assertSanitized(checkNotNull(closeFailure)) else assertNull(closeFailure)
                    Thread.interrupted()
                }
            }
        }
    }

    @Test
    fun `preexisting interruption and finite elapsed budget refuse work without sleeps or cancellation completion claims`() {
        val fixture = AwsSecretVersionFixture()
        fixture.resolver().use { resolver ->
            try {
                Thread.currentThread().interrupt()
                assertThrows(InterruptedException::class.java) { fixture.resolver() }
                val failure = assertThrows(SecretVersionException::class.java) { AcquiredVersionedSecret.acquire(binding(), resolver) }
                assertEquals(SecretVersionFailure.INTERRUPTED, failure.code)
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
            assertTrue(fixture.requests.isEmpty())
            val response = reply().apply { beforeRead = { fixture.now = 10_000_000_000L } }
            fixture.respond = { response }
            rejected { resolver.resolve(reference()) }
            assertEquals(1, response.reads)
            assertReleased(response)
        }
    }

    @Test
    fun `factory failures are sanitized and failed abort or body close retains the request with no hidden retry`() {
        val fixture = AwsSecretVersionFixture()
        rejected { fixture.resolver(httpFactory = { throw IOException(PRIVATE_TEXT) }) }
        assertEquals(0, fixture.createdClients)
        listOf(false, true).forEach { abort ->
            val controlled = AwsSecretVersionFixture()
            val response = reply().apply {
                if (abort) onAbort = { throw IOException(PRIVATE_TEXT) } else onClose = { throw IOException(PRIVATE_TEXT) }
            }
            controlled.respond = { response }
            val resolver = controlled.resolver()
            rejected { resolver.resolve(reference()) }
            rejected { resolver.resolve(reference()) }
            repeat(2) { rejected { resolver.close() } }
            assertReleased(response)
            assertEquals(1, controlled.requests.size)
            assertEquals(1, controlled.closedClients)
        }
    }

    @Test
    fun `fatal errors preserve identity while every arrived native resource is cleaned once`() {
        val fixture = AwsSecretVersionFixture()
        val fatal = AssertionError("synthetic fatal sentinel")
        val response = reply().apply { beforeRead = { throw fatal } }
        fixture.respond = { response }
        fixture.resolver().use { resolver ->
            assertSame(fatal, assertThrows(AssertionError::class.java) { AcquiredVersionedSecret.acquire(binding(), resolver) })
        }
        assertReleased(response)
        assertEquals(1, fixture.closedClients)
    }

    @Test
    fun `a closed in-flight request cannot release custody before its late response is closed`() {
        val fixture = AwsSecretVersionFixture()
        val resolver = fixture.resolver()
        val response = reply().apply { beforeCall = { rejected { resolver.close() } } }
        fixture.respond = { response }
        rejected { resolver.resolve(reference()) }
        repeat(2) { rejected { resolver.close() } }
        rejected { resolver.resolve(reference()) }
        assertReleased(response)
        assertEquals(0, response.reads)
        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.closedClients)
    }

    private fun rejectDocument(json: String) = rejectReply(SecretHttpReply(json.toByteArray(Charsets.UTF_8)))

    private fun rejectReply(response: SecretHttpReply) {
        val fixture = AwsSecretVersionFixture()
        fixture.respond = { response }
        fixture.resolver().use { resolver -> rejected { resolver.resolve(reference()) } }
        assertEquals(1, fixture.requests.size)
        assertEquals(1, response.calls)
        assertEquals(1, response.aborts)
        assertEquals(if (response.bodyPresent) 1 else 0, response.closes)
        assertEquals(1, fixture.closedClients)
    }

    private fun assertReleased(response: SecretHttpReply) {
        assertEquals(1, response.calls)
        assertEquals(1, response.aborts)
        assertEquals(1, response.closes)
    }

    private fun rejected(action: () -> Unit) {
        val failure = assertThrows(SecretVersionException::class.java) { action() }
        assertEquals(SecretVersionFailure.RESOLVER_FAILURE, failure.code)
        assertSanitized(failure)
    }

    private fun withProperties(values: Map<String, String>, action: () -> Unit) {
        val prior = values.keys.associateWith(System::getProperty)
        try {
            values.forEach { (name, value) -> System.setProperty(name, value) }
            action()
        } finally {
            prior.forEach { (name, value) -> if (value == null) System.clearProperty(name) else System.setProperty(name, value) }
        }
    }
}

internal fun assertSanitized(failure: Throwable) {
    assertFalse(failure.toString().contains(PRIVATE_TEXT))
    assertFalse(failure.toString().contains(ARN))
    assertFalse(failure.toString().contains(VERSION))
    assertNull(failure.cause)
    assertTrue(failure.suppressed.isEmpty())
}
