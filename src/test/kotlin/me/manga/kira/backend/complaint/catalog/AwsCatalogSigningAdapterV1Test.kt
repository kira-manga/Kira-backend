package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.AwsCatalogSigningAdapterV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogSigningExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogSigningFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogSigningKeyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogSigningWireV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.CREDENTIALS
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpMethod
import java.io.IOException
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.concurrent.CancellationException

/** Existing raw HTTP fixture only: actual KmsClient marshalling/SigV4/decoding and real synthetic RSA-PSS, no AWS/listener/SQL. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AwsCatalogSigningAdapterV1Test {
    @Test
    fun `real SDK signs exact catalog RAW frame under independent pins and returns bytes only after cleanup`() {
        val publicBytes = OfflineTrustBundleFixture.firstSigner.public.encoded
        val key = key(publicBytes = publicBytes)
        publicBytes.fill(0) // The configured pin owns a bounded defensive copy.
        val fixture = SigningFixture(key)
        val input = manifest()
        val original = input.copyOf()
        fixture.raw.beforePrepare = { input.fill(0) }
        fixture.raw.respond = { request ->
            val message = Base64.getDecoder().decode(request.fields()["Message"].textValue())
            JournalKmsHttpReply(document(sign(message))).apply { chunkSize = 3 }
        }
        val adapter = fixture.open()
        val signature = adapter.sign(input)
        assertEquals(384, signature.size)
        val frame = OfflineCatalogGenesisFixture.independentFrame(KEY_ID, original)
        val verifier = Signature.getInstance("RSASSA-PSS").apply {
            setParameter(OfflineTrustBundleFixture.parameters)
            initVerify(OfflineTrustBundleFixture.firstSigner.public)
            update(frame)
        }
        assertTrue(verifier.verify(signature))
        val request = fixture.raw.requests.single()
        assertEquals(setOf("KeyId", "Message", "MessageType", "SigningAlgorithm"), request.fields().fieldNames().asSequence().toSet())
        assertEquals(KEY_ARN, request.fields()["KeyId"].textValue())
        assertEquals("RAW", request.fields()["MessageType"].textValue())
        assertEquals(ALGORITHM, request.fields()["SigningAlgorithm"].textValue())
        assertArrayEquals(frame, Base64.getDecoder().decode(request.fields()["Message"].textValue()))
        assertEquals("TrentService.Sign", request.target())
        assertEquals(SdkHttpMethod.POST, request.http.method())
        assertEquals("https", request.http.protocol())
        assertEquals("kms.us-east-1.amazonaws.com", request.http.host())
        assertEquals(443, request.http.port())
        assertEquals("/", request.http.encodedPath())
        assertTrue(request.http.rawQueryParameters().isEmpty())
        val authorization = request.http.firstMatchingHeader("Authorization").orElseThrow()
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=${CREDENTIALS.accessKeyId()}/"))
        assertTrue(authorization.contains("/us-east-1/kms/aws4_request"))
        assertTrue(authorization.contains("x-amz-security-token") && authorization.contains("x-amz-target"))
        assertEquals(CREDENTIALS.sessionToken(), request.http.firstMatchingHeader("X-Amz-Security-Token").orElseThrow())
        assertCleanupAttempts(fixture)
        assertEquals(1, fixture.raw.returnedClientCloses)
        assertEquals(1, fixture.raw.replies.single().eofProbes)
        rejected { adapter.sign(original) } // A second operation cannot reuse the one-shot signer or get a fresh budget.
        adapter.close()
        fixture.owner.close()
        assertCleanupAttempts(fixture)
        assertFalse(key.toString().contains(KEY_ARN))
        assertFalse(adapter.toString().contains(KEY_ID))
    }

    @Test
    fun `pin validation and explicit SDK inputs reject retargeting and ambient credentials endpoints and retries`() {
        listOf("alias/catalog", KEY_ARN.replace("arn:aws:", "arn:aws-cn:"), "$KEY_ARN/trailing").forEach { arn -> rejected { key(arn = arn) } }
        rejected { key(id = "bad key") }
        rejected { key(algorithm = "RSASSA_PKCS1_V1_5_SHA_256") }
        rejected { key(publicBytes = ByteArray(423)) }
        listOf(
            key(hash = "0".repeat(64)),
            key(publicBytes = ByteArray(422)),
            key(arn = KEY_ARN.replace("us-east-1", "zz-invalid-9")),
            key(arn = KEY_ARN.replace("us-east-1", "us-gov-west-1")),
        ).forEach { pin ->
            val fixture = SigningFixture(pin)
            rejected { fixture.open() }
            fixture.owner.close()
            assertEquals(0, fixture.raw.createdClients)
        }
        val invalidCredentials = SigningFixture()
        rejected { invalidCredentials.open(credentials = AwsSessionCredentials.create("SYNTHETIC", "synthetic-secret", "bad\n$PRIVATE")) }
        assertEquals(0, invalidCredentials.raw.createdClients)
        withProperties(mapOf(SdkSystemSetting.AWS_PARTITIONS_FILE.property() to "/synthetic-must-not-read/partitions.json")) {
            val fixture = SigningFixture()
            rejected { fixture.open() }
            assertEquals(0, fixture.raw.createdClients)
        }
        withProperties(
            mapOf(
                "aws.endpointUrlKms" to "http://synthetic-unapproved.invalid/", "aws.endpointUrl" to "https://synthetic-unapproved.invalid/",
                "aws.region" to "us-west-2", "aws.accessKeyId" to "SYNTHETICOTHER", "aws.secretAccessKey" to "synthetic-other-secret",
                "aws.sessionToken" to "synthetic-other-session", "aws.profile" to "synthetic-must-not-read",
                "aws.configFile" to "/synthetic-must-not-read/config", "aws.sharedCredentialsFile" to "/synthetic-must-not-read/credentials",
                "aws.defaultsMode" to "auto", "aws.maxAttempts" to "5", "aws.retryMode" to "adaptive",
                "aws.useFipsEndpoint" to "true", "aws.useDualstackEndpoint" to "true",
            ),
        ) {
            val fixture = SigningFixture()
            fixture.open().sign(manifest())
            assertEquals("kms.us-east-1.amazonaws.com", fixture.raw.requests.single().http.host())
            assertEquals(CREDENTIALS.sessionToken(), fixture.raw.requests.single().http.firstMatchingHeader("X-Amz-Security-Token").orElseThrow())
            assertCleanupAttempts(fixture)
        }
    }

    @Test
    fun `strict raw JSON signature identity and local cryptography refuse malformed retargeted or wrong domain replies`() {
        val frame = OfflineCatalogGenesisFixture.independentFrame(KEY_ID, manifest())
        val valid = document(sign(frame))
        listOf(
            valid.replace(KEY_ARN, AwsJournalKmsFixture.ARN), valid.replace(ALGORITHM, "RSASSA_PSS_SHA_384"),
            valid.dropLast(1) + ",\"K\\u0065yId\":\"$KEY_ARN\"}", valid.dropLast(1) + ",\"Unknown\":\"$PRIVATE\"}",
            valid + "{}", "[]", "{\"KeyId\":\"$KEY_ARN\",\"SigningAlgorithm\":\"$ALGORITHM\",\"Signature\":1234}",
            document(ByteArray(383)), document(ByteArray(385)), document(ByteArray(384)).replace("A".repeat(512), "A".repeat(511) + "="),
            document(sign(frame, OfflineTrustBundleFixture.secondSigner)), document(sign(MessageDigest.getInstance("SHA-256").digest(frame))),
            document(sign(OfflineCatalogGenesisFixture.independentFrame("catalog-other", manifest()))),
        ).forEach { rejectedReply(JournalKmsHttpReply(it)) }
        rejectedReply(JournalKmsHttpReply(valid.toByteArray() + byteArrayOf(0x80.toByte())))
        rejectedReply(JournalKmsHttpReply(valid.toByteArray(Charsets.UTF_16LE)))
        // No approval assertion is manufactured: even the positive is signature bytes over supplied bytes, not an accepted generation.
    }

    @Test
    fun `bounded HTTP refuses error bodies redirects oversized framing and stalled reads without another SDK attempt`() {
        listOf(307, 403, 503).forEach { status ->
            val reply = JournalKmsHttpReply("{\"__type\":\"KMSInternalException\",\"message\":\"$PRIVATE\"}").apply { this.status = status }
            rejectedReply(reply)
            assertEquals(0, reply.reads)
        }
        listOf(
            "Content-Length" to listOf("4097"),
            "Content-Length" to listOf("4", "4"),
            "Transfer-Encoding" to listOf("chunked"),
            "Content-Encoding" to listOf("gzip"),
            "Location" to listOf("https://synthetic-unapproved.invalid/"),
        ).forEach { header ->
            val reply = JournalKmsHttpReply(document(ByteArray(384))).apply { headers = headers + header }
            rejectedReply(reply)
            assertEquals(0, reply.reads)
        }
        val stalled = JournalKmsHttpReply(document(ByteArray(384))).apply { chunkSize = 0 }
        rejectedReply(stalled)
        assertEquals(1, stalled.reads)
        val excess = JournalKmsHttpReply(ByteArray(CatalogSigningWireV1.MAX_HTTP_BYTES + 1) { ' '.code.toByte() }).apply {
            headers = headers - "Content-Length"
        }
        rejectedReply(excess)
        assertEquals(CatalogSigningWireV1.MAX_HTTP_BYTES + 1, excess.bytesRead)
        val short = JournalKmsHttpReply(document(ByteArray(384))).apply {
            headers = headers + ("Content-Length" to listOf((bytes.size + 1).toString()))
        }
        rejectedReply(short)
        assertEquals(1, short.eofProbes)
    }

    @Test
    fun `original supplied budget spans construction framing reads verification and actual cleanup with no reset`() {
        listOf(-1L, 999_999_999L, 1_000_000_000L).forEach { elapsed ->
            val fixture = SigningFixture()
            fixture.raw.now = elapsed
            rejected { fixture.open() }
            assertEquals(0, fixture.raw.createdClients)
        }
        val constructing = SigningFixture()
        rejected {
            constructing.open(httpFactory = { constructing.raw.httpClient().also { constructing.raw.now = 1_000_000_000L } })
        }
        constructing.owner.close() // A returned client is known and was actually closed, unlike a throwing native factory.
        assertEquals(1, constructing.raw.closedClients)
        val beforeSign = SigningFixture()
        val opened = beforeSign.open()
        beforeSign.raw.now = 1_000_000_000L
        rejected { opened.sign(manifest()) }
        assertTrue(beforeSign.raw.requests.isEmpty())
        assertEquals(1, beforeSign.raw.closedClients)
        assertEquals(1, beforeSign.raw.returnedClientCloses)
        beforeSign.owner.close()
        val preparing = SigningFixture()
        preparing.raw.afterPrepare = { preparing.raw.now = 1_000_000_000L }
        rejected { preparing.open().sign(manifest()) }
        assertEquals(0, preparing.raw.replies.single().calls)
        assertEquals(1, preparing.raw.replies.single().aborts)
        preparing.owner.close()
        val reading = SigningFixture()
        reading.raw.respond = {
            JournalKmsHttpReply(document(ByteArray(384))).apply {
                chunkSize = 1
                beforeRead = { reading.raw.now += 250_000_000L }
            }
        }
        rejected { reading.open().sign(manifest()) }
        assertEquals(4, reading.raw.replies.single().reads)
        assertCleanupAttempts(reading)
        val closing = SigningFixture()
        closing.raw.onClientClose = { closing.raw.now = 1_000_000_000L }
        rejected { closing.open().sign(manifest()) } // Valid signature already verified; late cleanup still withholds the return.
        assertCleanupAttempts(closing)
        listOf(ByteArray(0), ByteArray(OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES + 1)).forEach { input ->
            val fixture = SigningFixture()
            rejected { fixture.open().sign(input) }
            assertTrue(fixture.raw.requests.isEmpty())
            assertEquals(1, fixture.raw.closedClients)
        }
    }

    @Test
    fun `unknown factory and throwing native prepare never refund custody or become reusable after close`() {
        val constructing = SigningFixture()
        var unreturned: SdkHttpClient? = null
        rejected(CatalogSigningFailureV1.CLOSE_FAILURE) {
            constructing.open(httpFactory = {
                unreturned = constructing.raw.httpClient()
                throw IOException(PRIVATE)
            })
        }
        repeat(2) { rejected(CatalogSigningFailureV1.CLOSE_FAILURE) { constructing.owner.close() } }
        assertEquals(1, constructing.raw.createdClients)
        assertEquals(0, constructing.raw.closedClients)
        checkNotNull(unreturned).close() // Test-only native owner cleanup cannot become the adapter's missing construction proof.
        rejected(CatalogSigningFailureV1.CLOSE_FAILURE) { constructing.owner.close() }
        val preparing = SigningFixture()
        preparing.raw.afterPrepare = { throw IOException(PRIVATE) } // Delegate created an executable but never returned it to the adapter.
        rejected(CatalogSigningFailureV1.CLOSE_FAILURE) { preparing.open().sign(manifest()) }
        repeat(2) { rejected(CatalogSigningFailureV1.CLOSE_FAILURE) { preparing.owner.close() } }
        assertEquals(1, preparing.raw.requests.size)
        assertEquals(0, preparing.raw.replies.single().aborts)
        assertEquals(0, preparing.raw.replies.single().calls)
        assertEquals(1, preparing.raw.closedClients)
        rejected { preparing.open() }
    }

    @Test
    fun `late returned native owners and failed abort body or client close remain retained and never return signature bytes`() {
        val constructing = SigningFixture()
        rejected(CatalogSigningFailureV1.CLOSE_FAILURE) {
            constructing.open(httpFactory = {
                rejected(CatalogSigningFailureV1.CLOSE_FAILURE) { constructing.owner.close() }
                constructing.raw.httpClient()
            })
        }
        assertEquals(1, constructing.raw.closedClients)
        listOf(true, false).forEach { preparing ->
            val fixture = SigningFixture()
            val stop = { rejected(CatalogSigningFailureV1.CLOSE_FAILURE) { fixture.owner.close() } }
            if (preparing) fixture.raw.afterPrepare = stop
            fixture.raw.respond = { request ->
                JournalKmsHttpReply(document(sign(Base64.getDecoder().decode(request.fields()["Message"].textValue())))).apply {
                    if (!preparing) beforeCall = stop
                }
            }
            rejected(CatalogSigningFailureV1.CLOSE_FAILURE) { fixture.open().sign(manifest()) }
            repeat(2) { rejected(CatalogSigningFailureV1.CLOSE_FAILURE) { fixture.owner.close() } }
            val reply = fixture.raw.replies.single()
            assertEquals(if (preparing) 0 else 1, reply.calls)
            assertEquals(if (preparing) 0 else 1, reply.closes)
            assertEquals(1, reply.aborts)
            assertEquals(0, reply.reads)
            assertEquals(1, fixture.raw.closedClients)
        }
        listOf("abort", "body", "client").forEach { failing ->
            val fixture = SigningFixture()
            fixture.raw.respond = { request ->
                JournalKmsHttpReply(document(sign(Base64.getDecoder().decode(request.fields()["Message"].textValue())))).apply {
                    if (failing == "abort") onAbort = { throw IOException(PRIVATE) }
                    if (failing == "body") onClose = { throw IOException(PRIVATE) }
                }
            }
            if (failing == "client") fixture.raw.onClientClose = { throw IOException(PRIVATE) }
            rejected(CatalogSigningFailureV1.CLOSE_FAILURE) { fixture.open().sign(manifest()) }
            repeat(2) { rejected(CatalogSigningFailureV1.CLOSE_FAILURE) { fixture.owner.close() } }
            assertCleanupAttempts(fixture)
            assertEquals(if (failing == "client") 0 else 1, fixture.raw.returnedClientCloses)
        }
    }

    @Test
    fun `connection free checks and sanitized cancellation interruption and fatal signals cannot suppress cleanup`() {
        val opening = SigningFixture()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertThrows(PersistencePhaseException::class.java) { opening.open() }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        assertEquals(0, opening.raw.createdClients)
        val beforeSign = SigningFixture()
        val opened = beforeSign.open()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            rejected { opened.sign(manifest()) }
            assertTrue(beforeSign.raw.requests.isEmpty())
            assertEquals(1, beforeSign.raw.closedClients)
            assertEquals(1, beforeSign.raw.returnedClientCloses)
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        val interrupted = SigningFixture()
        val toInterrupt = interrupted.open()
        Thread.currentThread().interrupt()
        try {
            assertSanitized(assertThrows(InterruptedException::class.java) { toInterrupt.sign(manifest()) })
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(interrupted.raw.requests.isEmpty())
            assertEquals(1, interrupted.raw.closedClients)
            assertEquals(1, interrupted.raw.returnedClientCloses)
        } finally {
            Thread.interrupted()
        }
        val fixture = SigningFixture()
        fixture.raw.respond = {
            JournalKmsHttpReply(document(ByteArray(384))).apply {
                beforeRead = { TransactionSynchronizationManager.setActualTransactionActive(true) }
                onClose = { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()) }
            }
        }
        try {
            rejected { fixture.open().sign(manifest()) }
            assertCleanupAttempts(fixture)
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        val fatal = AssertionError("synthetic fatal sentinel")
        listOf(CancellationException(PRIVATE), InterruptedException(PRIVATE), fatal).forEach { signal ->
            val selected = SigningFixture()
            selected.raw.respond = {
                JournalKmsHttpReply(document(ByteArray(384))).apply {
                    beforeRead = { throw SdkClientException.builder().message(PRIVATE).cause(signal).build() }
                }
            }
            try {
                val failure = assertThrows(signal.javaClass) { selected.open().sign(manifest()) }
                if (signal is Error) assertSame(fatal, failure) else assertSanitized(failure)
                if (signal is InterruptedException) assertTrue(Thread.currentThread().isInterrupted)
                assertCleanupAttempts(selected)
            } finally {
                Thread.interrupted()
            }
        }
    }

    private fun rejectedReply(reply: JournalKmsHttpReply) {
        val fixture = SigningFixture()
        fixture.raw.respond = { reply }
        rejected { fixture.open().sign(manifest()) }
        fixture.owner.close()
        assertCleanupAttempts(fixture)
    }

    private fun assertCleanupAttempts(fixture: SigningFixture) {
        assertEquals(1, fixture.raw.requests.size)
        assertEquals(1, fixture.raw.replies.single().calls)
        assertEquals(1, fixture.raw.replies.single().aborts)
        assertEquals(1, fixture.raw.replies.single().closes)
        assertEquals(1, fixture.raw.closedClients)
    }

    private fun rejected(code: CatalogSigningFailureV1 = CatalogSigningFailureV1.SIGN_FAILURE, action: () -> Unit) {
        val failure = assertThrows(CatalogSigningExceptionV1::class.java) { action() }
        assertEquals(code, failure.code)
        assertSanitized(failure)
    }

    private fun assertSanitized(failure: Throwable) {
        assertFalse(failure.toString().contains(PRIVATE))
        assertFalse(failure.toString().contains(KEY_ARN))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun withProperties(values: Map<String, String>, action: () -> Unit) {
        val previous = values.keys.associateWith(System::getProperty)
        try {
            values.forEach { (name, value) -> System.setProperty(name, value) }
            action()
        } finally {
            previous.forEach { (name, value) -> if (value == null) System.clearProperty(name) else System.setProperty(name, value) }
        }
    }

    private class SigningFixture(private val key: CatalogSigningKeyV1 = key()) {
        val raw = AwsJournalKmsFixture().apply {
            respond = { request -> JournalKmsHttpReply(document(sign(Base64.getDecoder().decode(request.fields()["Message"].textValue())))) }
        }
        val owner = AwsCatalogSigningAdapterV1.Construction(PersistenceTimeBudget.start(1_000, PersistenceNanoClock { raw.now }))

        fun open(credentials: AwsSessionCredentials = CREDENTIALS, httpFactory: () -> SdkHttpClient = raw::httpClient): AwsCatalogSigningAdapterV1 =
            AwsCatalogSigningAdapterV1.withHttpFixture(owner, key, credentials, httpFactory)
    }

    companion object {
        private const val KEY_ID = "catalog-old"
        private const val KEY_ARN = "arn:aws:kms:us-east-1:123456789012:key/77777777-7777-4777-8777-777777777777"
        private const val ALGORITHM = "RSASSA_PSS_SHA_256"
        private const val PRIVATE = "synthetic-private-catalog-provider-diagnostic"
        private val manifestBytes by lazy {
            OfflineCatalogGenesisFixture.manifestBytes(OfflineCatalogGenesisFixture.manifest(OfflineCatalogGenesisFixture.bundle()))
        }

        private fun manifest(): ByteArray = manifestBytes.copyOf()

        private fun key(
            id: String = KEY_ID,
            arn: String = KEY_ARN,
            algorithm: String = ALGORITHM,
            publicBytes: ByteArray = OfflineTrustBundleFixture.firstSigner.public.encoded,
            hash: String = Sha256.hex(publicBytes),
        ): CatalogSigningKeyV1 = CatalogSigningKeyV1(id, arn, algorithm, publicBytes, hash)

        private fun sign(frame: ByteArray, key: KeyPair = OfflineTrustBundleFixture.firstSigner): ByteArray = Signature.getInstance("RSASSA-PSS").run {
            setParameter(OfflineTrustBundleFixture.parameters)
            initSign(key.private)
            update(frame)
            sign()
        }

        private fun document(signature: ByteArray): String =
            """{"KeyId":"$KEY_ARN","SigningAlgorithm":"$ALGORITHM","Signature":"${Base64.getEncoder().encodeToString(signature)}"}"""
    }
}
