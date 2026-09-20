package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture.Companion.credentials
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.AwsCatalogPrimaryPutAdapterV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutTargetV1
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
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.CancellationException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Existing raw S3 fixture with a test-local body/lifecycle decorator; genuine SDK, no AWS/listener/SQL or supplied PUT result. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AwsCatalogPrimaryPutAdapterV1Test {
    @Test
    fun `real SDK sends one exact frozen primary conditional PUT and returns the actual version only after cleanup`() {
        val fixture = PutFixture()
        val input = envelope()
        val original = input.copyOf()
        fixture.beforePrepare = { input.fill(0) }
        val adapter = fixture.open()
        val observed = adapter.put(input)
        assertEquals(VERSION, observed.versionId)
        assertEquals(hash(original), observed.envelopeSha256)
        val request = fixture.raw.requests.single()
        assertEquals(SdkHttpMethod.PUT, request.method())
        assertEquals("https", request.protocol())
        assertEquals("s3.us-east-1.amazonaws.com", request.host())
        assertEquals(443, request.port())
        assertEquals("/${S3CatalogReadbackFixture.primary.bucket}/complaints/catalog/v1/00000000000000000001.json", request.encodedPath())
        assertTrue(request.rawQueryParameters().isEmpty() || request.rawQueryParameters() == mapOf("x-id" to listOf("PutObject")))
        assertEquals(S3CatalogReadbackFixture.primary.accountId, request.header("x-amz-expected-bucket-owner"))
        assertEquals(credentials.sessionToken(), request.header("x-amz-security-token"))
        assertEquals("*", request.header("If-None-Match"))
        assertEquals(original.size.toString(), request.header("Content-Length"))
        assertEquals("application/json", request.header("Content-Type"))
        assertEquals("SHA256", request.header("x-amz-sdk-checksum-algorithm"))
        assertEquals(checksum(original), request.header("x-amz-checksum-sha256"))
        assertEquals(hash(original), request.header("x-amz-content-sha256"))
        assertEquals("COMPLIANCE", request.header("x-amz-object-lock-mode"))
        val retained = Instant.ofEpochSecond(CREATED).atOffset(ZoneOffset.UTC).plusYears(10).toInstant()
        assertEquals(retained.toString(), request.header("x-amz-object-lock-retain-until-date"))
        assertTrue(request.headers().keys.none { it.startsWith("x-amz-meta-", ignoreCase = true) })
        assertArrayEquals(original, fixture.bodies.single())
        assertSigV4(request, fixture.bodies.single())
        assertCleanupAttempts(fixture)
        assertEquals(1, fixture.returnedClientCloses)
        assertEquals(1, fixture.raw.replies.single().eofProbes)
        rejected { adapter.put(original) }
        adapter.close()
        fixture.owner.close()
        assertCleanupAttempts(fixture)
        assertEquals(1, fixture.returnedClientCloses)
        assertFalse(observed.toString().contains(VERSION))
        assertFalse(fixture.target.toString().contains(S3CatalogReadbackFixture.primary.bucket))
        // Synthetic signed G1 bytes are only a realistic payload. This result is NOT approval, persisted G1 or dual-copy acceptance.
    }

    @Test
    fun `fixed catalog routing sessions bytes and creation retention refuse substitution without widening authority`() {
        listOf(0L, 65_537L).forEach { generation -> rejected { target(generation = generation) } }
        listOf(
            S3CatalogReadbackFixture.replica,
            S3CatalogReadbackFixture.primary.copy(bucket = "../other"),
            S3CatalogReadbackFixture.primary.copy(bucket = "synthetic--zone--x-s3"),
            S3CatalogReadbackFixture.primary.copy(accountId = "other"),
        ).forEach { location -> rejected { target(location = location) } }
        rejected { target(created = -1) }
        rejected { target(created = CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND) }
        rejected { target(digest = "not-a-hash") }
        listOf("zz-invalid-9", "us-gov-west-1").forEach { region ->
            val fixture = PutFixture(target(location = S3CatalogReadbackFixture.primary.copy(region = region)))
            rejected { fixture.open() }
            assertEquals(0, fixture.raw.createdClients)
        }
        listOf(ByteArray(0), ByteArray(OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES + 1), envelope().apply { this[0] = 0 }).forEach { bytes ->
            val fixture = PutFixture()
            rejected { fixture.open().put(bytes) }
            assertTrue(fixture.raw.requests.isEmpty())
            assertEquals(1, fixture.returnedClientCloses)
        }
        val future = PutFixture(target(created = NOW.plusSeconds(1).epochSecond))
        rejected { future.open() }
        assertEquals(0, future.raw.createdClients)
        val session = PutFixture()
        rejected { session.open(selectedCredentials = AwsSessionCredentials.create("SYNTHETIC", "synthetic-secret", "bad\n$PRIVATE")) }
        assertEquals(0, session.raw.createdClients)
        listOf(
            SdkSystemSetting.AWS_PARTITIONS_FILE.property() to "/synthetic-must-not-read/partitions.json",
            SdkSystemSetting.AWS_S3_US_EAST_1_REGIONAL_ENDPOINT.property() to "legacy",
        ).forEach { property ->
            withProperties(mapOf(property)) {
                val fixture = PutFixture()
                rejected { fixture.open() }
                assertEquals(0, fixture.raw.createdClients)
            }
        }
        withProperties(
            mapOf(
                "aws.endpointUrlS3" to "http://synthetic-unapproved.invalid/", "aws.endpointUrl" to "https://synthetic-unapproved.invalid/",
                "aws.region" to "us-west-2", "aws.accessKeyId" to "SYNTHETICOTHER", "aws.secretAccessKey" to "synthetic-other-secret",
                "aws.sessionToken" to "synthetic-other-session", "aws.profile" to "synthetic-must-not-read",
                "aws.configFile" to "/synthetic-must-not-read/config", "aws.sharedCredentialsFile" to "/synthetic-must-not-read/credentials",
                "aws.defaultsMode" to "auto", "aws.maxAttempts" to "5", "aws.retryMode" to "adaptive",
                "aws.useFipsEndpoint" to "true", "aws.useDualstackEndpoint" to "true",
            ),
        ) {
            val fixture = PutFixture()
            fixture.open().put(envelope())
            assertSigV4(fixture.raw.requests.single(), fixture.bodies.single())
            assertCleanupAttempts(fixture)
        }
    }

    @Test
    fun `raw PUT success is empty header evidence and conflicts redirects or malformed replies never become retry permission`() {
        listOf("absent", "unframed", "chunked").forEach { shape ->
            val fixture = PutFixture()
            val reply = acknowledgement().apply {
                if (shape == "absent") bodyPresent = false
                if (shape != "absent") headers = headers - "Content-Length"
                if (shape == "chunked") headers = headers + ("Transfer-Encoding" to listOf("chunked"))
            }
            fixture.raw.respond = { reply }
            assertEquals(VERSION, fixture.open().put(envelope()).versionId)
            assertCleanupAttempts(fixture, body = shape != "absent")
            assertEquals(if (shape == "absent") 0 else 1, reply.reads)
        }
        listOf(307, 403, 404, 409, 412, 503).forEach { status ->
            val reply = S3CatalogReply("<Error><Code>ConditionalRequestConflict</Code><Message>$PRIVATE</Message></Error>".toByteArray())
                .apply { this.status = status }
            rejectedReply(reply)
            assertEquals(0, reply.reads)
        }
        listOf(
            "x-amz-version-id" to listOf("null"), "x-amz-version-id" to listOf("v", "v"), "x-amz-version-id" to listOf("v".repeat(1025)),
            "x-amz-checksum-sha256" to listOf(checksum(ByteArray(3))), "x-amz-checksum-type" to listOf("COMPOSITE"),
            "Content-Length" to listOf("1"), "Content-Length" to listOf("0", "0"), "Transfer-Encoding" to listOf("chunked"),
            "Content-Encoding" to listOf("gzip"), "Location" to listOf("https://synthetic-unapproved.invalid/"),
            "x-amz-bucket-region" to listOf("us-west-2"), "x-amz-delete-marker" to listOf("true"), "x-amz-missing-meta" to listOf("1"),
        ).forEach { header ->
            val reply = acknowledgement().apply { headers = headers + header }
            rejectedReply(reply)
            assertEquals(0, reply.reads)
        }
        listOf("x-amz-version-id", "x-amz-checksum-sha256").forEach { missing ->
            rejectedReply(acknowledgement().apply { headers = headers - missing })
        }
        listOf(1, 0).forEach { chunk ->
            val reply = S3CatalogReply(PRIVATE.toByteArray()).apply {
                headers = acknowledgement().headers - "Content-Length"
                chunkSize = chunk
            }
            rejectedReply(reply)
            assertEquals(1, reply.reads) // Reject positive data or zero progress without decoding an alleged success body.
        }
        val lost = PutFixture()
        lost.raw.respond = { acknowledgement().apply { beforeCall = { throw IOException(PRIVATE) } } }
        val adapter = lost.open()
        rejected { adapter.put(envelope()) }
        rejected { adapter.put(envelope()) }
        assertCleanupAttempts(lost, body = false) // No returned body or invented version, and no second native PUT.
    }

    @Test
    fun `one original budget covers construction body transfer retention windows and cleanup including first call refusal`() {
        listOf(-1L, DEADLINE - 1L, DEADLINE).forEach { now ->
            val fixture = PutFixture()
            fixture.raw.now = now
            rejected { fixture.open() }
            assertEquals(0, fixture.raw.createdClients)
        }
        val opening = PutFixture()
        rejected { opening.open(httpFactory = { opening.httpClient().also { opening.raw.now = DEADLINE } }) }
        opening.owner.close()
        assertEquals(1, opening.returnedClientCloses)
        val entry = PutFixture()
        val opened = entry.open()
        entry.raw.now = DEADLINE
        rejected { opened.put(envelope()) }
        assertTrue(entry.raw.requests.isEmpty())
        assertEquals(1, entry.returnedClientCloses)
        val transfer = PutFixture()
        transfer.afterBody = { transfer.raw.now = DEADLINE }
        rejected { transfer.open().put(envelope()) }
        assertEquals(0, transfer.raw.replies.single().calls)
        assertEquals(1, transfer.raw.replies.single().aborts)
        assertEquals(1, transfer.returnedClientCloses)
        val reading = PutFixture()
        reading.raw.respond = { acknowledgement().apply { beforeRead = { reading.raw.now = DEADLINE } } }
        rejected { reading.open().put(envelope()) }
        assertEquals(1, reading.raw.replies.single().reads)
        assertCleanupAttempts(reading)
        val closing = PutFixture()
        closing.onClientClose = { closing.raw.now = DEADLINE }
        rejected { closing.open().put(envelope()) }
        assertCleanupAttempts(closing)
        assertEquals(1, closing.returnedClientCloses)
        val fullAttempt = PutFixture(allowanceMillis = 3_600_000)
        fullAttempt.wall = fullAttempt.target.createdAt.atOffset(ZoneOffset.UTC).plusYears(8).minusMinutes(30).toInstant()
        rejected { fullAttempt.open() } // A ten-minute API cap would incorrectly allow the original hour-long attempt.
        assertEquals(0, fullAttempt.raw.createdClients)
        val fractional = PutFixture()
        fractional.raw.now = 1L // RemainingMillis floors 9999.999999ms; dropping that fraction would incorrectly pass the two-year floor.
        fractional.wall = fractional.target.createdAt.atOffset(ZoneOffset.UTC).plusYears(8).toInstant().minusMillis(9_999)
        rejected { fractional.open() }
        assertEquals(0, fractional.raw.createdClients)
        val leap = target(created = Instant.parse("2024-02-29T12:00:00Z").epochSecond)
        assertEquals(Instant.parse("2034-02-28T12:00:00Z"), leap.retainUntil)
        val backwards = PutFixture()
        val current = backwards.open()
        backwards.wall = NOW.minusSeconds(1)
        rejected { current.put(envelope()) }
        assertTrue(backwards.raw.requests.isEmpty())
        assertEquals(1, backwards.returnedClientCloses)
    }

    @Test
    fun `unknown and late native construction or prepare retain custody without refund or a second PUT`() {
        val opening = PutFixture()
        var unreturned: SdkHttpClient? = null
        rejected(CatalogPrimaryPutFailureV1.CLOSE_FAILURE) {
            opening.open(httpFactory = {
                unreturned = opening.httpClient()
                throw IOException(PRIVATE)
            })
        }
        repeat(2) { rejected(CatalogPrimaryPutFailureV1.CLOSE_FAILURE) { opening.owner.close() } }
        assertEquals(1, opening.raw.createdClients)
        assertEquals(0, opening.raw.closedClients)
        checkNotNull(unreturned).close() // Test-only cleanup cannot give the adapter its missing construction-return proof.
        rejected(CatalogPrimaryPutFailureV1.CLOSE_FAILURE) { opening.owner.close() }
        val preparing = PutFixture()
        preparing.afterPrepare = { throw IOException(PRIVATE) }
        rejected(CatalogPrimaryPutFailureV1.CLOSE_FAILURE) { preparing.open().put(envelope()) }
        repeat(2) { rejected(CatalogPrimaryPutFailureV1.CLOSE_FAILURE) { preparing.owner.close() } }
        assertEquals(1, preparing.raw.requests.size)
        assertEquals(0, preparing.raw.replies.single().calls)
        assertEquals(0, preparing.raw.replies.single().aborts)
        assertEquals(1, preparing.returnedClientCloses)
        rejected { preparing.open() }
        val lateFactory = PutFixture()
        rejected(CatalogPrimaryPutFailureV1.CLOSE_FAILURE) {
            lateFactory.open(httpFactory = {
                rejected(CatalogPrimaryPutFailureV1.CLOSE_FAILURE) { lateFactory.owner.close() }
                lateFactory.httpClient()
            })
        }
        assertEquals(1, lateFactory.returnedClientCloses)
        listOf(true, false).forEach { duringPrepare ->
            val fixture = PutFixture()
            val stop = { rejected(CatalogPrimaryPutFailureV1.CLOSE_FAILURE) { fixture.owner.close() } }
            if (duringPrepare) fixture.afterPrepare = stop
            fixture.raw.respond = { acknowledgement().apply { if (!duringPrepare) beforeCall = stop } }
            rejected(CatalogPrimaryPutFailureV1.CLOSE_FAILURE) { fixture.open().put(envelope()) }
            repeat(2) { rejected(CatalogPrimaryPutFailureV1.CLOSE_FAILURE) { fixture.owner.close() } }
            val reply = fixture.raw.replies.single()
            assertEquals(if (duringPrepare) 0 else 1, reply.calls)
            assertEquals(if (duringPrepare) 0 else 1, reply.closes)
            assertEquals(1, reply.aborts)
            assertEquals(0, reply.reads)
            assertEquals(1, fixture.returnedClientCloses)
        }
    }

    @Test
    fun `failed close and sanitized cancellation interruption fatal or transaction signals cannot suppress cleanup`() {
        listOf("abort", "body", "client").forEach { failing ->
            val fixture = PutFixture()
            fixture.raw.respond = {
                acknowledgement().apply {
                    if (failing == "abort") onAbort = { throw IOException(PRIVATE) }
                    if (failing == "body") onClose = { throw IOException(PRIVATE) }
                }
            }
            if (failing == "client") fixture.onClientClose = { throw IOException(PRIVATE) }
            rejected(CatalogPrimaryPutFailureV1.CLOSE_FAILURE) { fixture.open().put(envelope()) }
            repeat(2) { rejected(CatalogPrimaryPutFailureV1.CLOSE_FAILURE) { fixture.owner.close() } }
            assertCleanupAttempts(fixture)
            assertEquals(if (failing == "client") 0 else 1, fixture.returnedClientCloses)
        }
        assertEntrySignalCleanup()
        val transaction = PutFixture()
        transaction.raw.respond = {
            acknowledgement().apply {
                beforeRead = { TransactionSynchronizationManager.setActualTransactionActive(true) }
                onClose = { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()) }
            }
        }
        try {
            rejected { transaction.open().put(envelope()) }
            assertCleanupAttempts(transaction)
            assertEquals(1, transaction.returnedClientCloses)
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        val fatal = AssertionError("synthetic fatal sentinel")
        listOf(CancellationException(PRIVATE), InterruptedException(PRIVATE), fatal).forEach { signal ->
            val fixture = PutFixture()
            fixture.raw.respond = {
                acknowledgement().apply { beforeRead = { throw SdkClientException.builder().message(PRIVATE).cause(signal).build() } }
            }
            try {
                val failure = assertThrows(signal.javaClass) { fixture.open().put(envelope()) }
                if (signal is Error) assertSame(fatal, failure) else assertSanitized(failure)
                if (signal is InterruptedException) assertTrue(Thread.currentThread().isInterrupted)
                assertCleanupAttempts(fixture)
                assertEquals(1, fixture.returnedClientCloses)
            } finally {
                Thread.interrupted()
            }
        }
    }

    private fun assertEntrySignalCleanup() {
        val opening = PutFixture()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertThrows(PersistencePhaseException::class.java) { opening.open() }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        assertEquals(0, opening.raw.createdClients)
        val beforePut = PutFixture()
        val opened = beforePut.open()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            rejected { opened.put(envelope()) }
            assertTrue(beforePut.raw.requests.isEmpty())
            assertEquals(1, beforePut.returnedClientCloses)
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        val interrupted = PutFixture()
        val toInterrupt = interrupted.open()
        Thread.currentThread().interrupt()
        try {
            assertSanitized(assertThrows(InterruptedException::class.java) { toInterrupt.put(envelope()) })
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(interrupted.raw.requests.isEmpty())
            assertEquals(1, interrupted.returnedClientCloses)
        } finally {
            Thread.interrupted()
        }
    }

    private fun rejectedReply(reply: S3CatalogReply) {
        val fixture = PutFixture()
        fixture.raw.respond = { reply }
        rejected { fixture.open().put(envelope()) }
        fixture.owner.close()
        assertCleanupAttempts(fixture)
        assertEquals(1, fixture.returnedClientCloses)
    }

    private fun assertCleanupAttempts(fixture: PutFixture, body: Boolean = true) {
        assertEquals(1, fixture.raw.requests.size)
        assertEquals(1, fixture.raw.replies.single().calls)
        assertEquals(1, fixture.raw.replies.single().aborts)
        assertEquals(if (body) 1 else 0, fixture.raw.replies.single().closes)
        assertEquals(1, fixture.raw.closedClients)
    }

    private fun rejected(code: CatalogPrimaryPutFailureV1 = CatalogPrimaryPutFailureV1.PUT_FAILURE, action: () -> Unit) {
        val failure = assertThrows(CatalogPrimaryPutExceptionV1::class.java) { action() }
        assertEquals(code, failure.code)
        assertSanitized(failure)
    }

    private fun assertSanitized(failure: Throwable) {
        assertFalse(failure.toString().contains(PRIVATE))
        assertFalse(failure.toString().contains(VERSION))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun assertSigV4(request: SdkHttpRequest, bytes: ByteArray) {
        val authorization = request.header("Authorization")
        val scope = authorization.substringAfter("Credential=${credentials.accessKeyId()}/").substringBefore(',')
        assertTrue(scope.endsWith("/us-east-1/s3/aws4_request"))
        val signed = authorization.substringAfter("SignedHeaders=").substringBefore(',').split(';')
        val query = request.rawQueryParameters().flatMap { (name, values) -> values.map { encoded(name) + "=" + encoded(it) } }.sorted().joinToString("&")
        val headers = signed.joinToString("") { "$it:${request.header(it).trim().replace(Regex("[ \\t]+"), " ")}\n" }
        val canonical = "${request.method()}\n${request.encodedPath()}\n$query\n$headers\n${signed.joinToString(";")}\n${hash(bytes)}"
        val date = request.header("x-amz-date")
        val day = hmac(("AWS4" + credentials.secretAccessKey()).toByteArray(), date.take(8))
        val region = hmac(day, "us-east-1")
        val service = hmac(region, "s3")
        val key = hmac(service, "aws4_request")
        val expected = hmac(key, "AWS4-HMAC-SHA256\n$date\n$scope\n${hash(canonical.toByteArray())}")
        assertEquals(HexFormat.of().formatHex(expected), authorization.substringAfter("Signature="))
        assertTrue(signed.containsAll(listOf("if-none-match", "x-amz-checksum-sha256", "x-amz-object-lock-retain-until-date")))
        assertEquals("1", request.header("amz-sdk-request").substringAfter("attempt=").substringBefore(';'))
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

    private class PutFixture(val target: CatalogPrimaryPutTargetV1 = target(), allowanceMillis: Long = 10_000) {
        val raw = S3CatalogReadbackFixture().apply { respond = { acknowledgement() } }
        val bodies = mutableListOf<ByteArray>()
        var beforePrepare: () -> Unit = {}
        var afterBody: () -> Unit = {}
        var afterPrepare: () -> Unit = {}
        var onClientClose: () -> Unit = {}
        var returnedClientCloses = 0
        var wall: Instant = NOW
        private val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this.also { require(zone == ZoneOffset.UTC) }
            override fun instant(): Instant = wall
        }
        val owner = AwsCatalogPrimaryPutAdapterV1.Construction(PersistenceTimeBudget.start(allowanceMillis, PersistenceNanoClock { raw.now }))

        fun open(selectedCredentials: AwsSessionCredentials = credentials, httpFactory: () -> SdkHttpClient = ::httpClient): AwsCatalogPrimaryPutAdapterV1 =
            AwsCatalogPrimaryPutAdapterV1.withHttpFixture(owner, target, selectedCredentials, httpFactory, clock)

        /** Only decorates the already-existing raw HTTP client: no alternate S3 client, authority, persistence or fixture response model. */
        fun httpClient(): SdkHttpClient {
            val native = raw.httpClient()
            return object : SdkHttpClient {
                override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest {
                    beforePrepare()
                    val bytes = request.contentStreamProvider().orElseThrow().newStream().use {
                        it.readNBytes(OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES + 1)
                    }
                    check(bytes.size <= OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES)
                    bodies.add(bytes)
                    afterBody()
                    return native.prepareRequest(request).also { afterPrepare() }
                }

                override fun close() {
                    native.close()
                    onClientClose()
                    returnedClientCloses++
                }

                override fun clientName(): String = "SyntheticCatalogPutRawDecorator"
            }
        }
    }

    companion object {
        private const val CREATED = 1_720_000_060L
        private const val DEADLINE = 10_000_000_000L
        private const val VERSION = "catalog-%2F+&=version-1"
        private const val PRIVATE = "synthetic-private-catalog-put-provider-diagnostic"
        private val NOW = Instant.parse("2030-01-02T03:04:05Z")
        private val envelopeBytes by lazy {
            val manifest = OfflineCatalogGenesisFixture.manifest(OfflineCatalogGenesisFixture.bundle())
            OfflineCatalogGenesisFixture.bytes(OfflineCatalogGenesisFixture.signed(manifest))
        }

        private fun envelope(): ByteArray = envelopeBytes.copyOf()

        private fun target(
            location: OfflineCatalogLocationV1 = S3CatalogReadbackFixture.primary,
            generation: Long = 1,
            created: Long = CREATED,
            digest: String = hash(envelope()),
        ): CatalogPrimaryPutTargetV1 = CatalogPrimaryPutTargetV1(location, generation, created, digest)

        private fun acknowledgement(): S3CatalogReply = S3CatalogReply(ByteArray(0)).apply {
            headers = headers + mapOf("x-amz-version-id" to listOf(VERSION), "x-amz-checksum-sha256" to listOf(checksum(envelope())))
        }

        private fun SdkHttpRequest.header(name: String): String = firstMatchingHeader(name).orElseThrow()
        private fun checksum(bytes: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~")
        private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value.toByteArray())
        }
    }
}
