package me.manga.kira.backend.security.aws

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.security.JournalGeneratedDataKeyV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalException
import me.manga.kira.backend.security.OwnerDeleteAllJournalFailure
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.ARN
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.CONTEXT_KEY
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.DECRYPT_TARGET
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.GENERATE_TARGET
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.PRIVATE_TEXT
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.REGION
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.base64
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.context
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.decryptDocument
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.fields
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.frame
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.generateDocument
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.journal
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.keyBytes
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.request
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.url
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.wrappedBytes
import me.manga.kira.backend.security.withJournalDataKey
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
import software.amazon.awssdk.http.SdkHttpResponse
import java.io.IOException
import java.util.concurrent.CancellationException

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AwsJournalDataKeyAdapterTest {
    @Test
    fun `real SDK generates and decrypts J bound keys with signed exact context and stable cleared leases`() {
        val fixture = AwsJournalKmsFixture()
        val expectedContext = context(fixture.journal)
        val mutableContext = HashMap(expectedContext)
        val requested = request(fixture.journal, context = mutableContext)
        mutableContext.clear()
        (requested.encryptionContext() as MutableMap<String, String>).clear()
        val generatedReply = JournalKmsHttpReply(generateDocument()).apply { chunkSize = 3 }
        fixture.respond = { generatedReply }
        fixture.adapter().use { adapter ->
            assertSame(fixture.journal, adapter.journal)
            assertTrue(fixture.requests.isEmpty())
            val generated = adapter.generate(requested)
            assertEquals(ARN, generated.keyArn)
            assertSame(generated.plaintextKey, generated.plaintextKey)
            assertSame(generated.wrappedKey, generated.wrappedKey)
            assertArrayEquals(keyBytes(), generated.plaintextKey)
            assertArrayEquals(wrappedBytes(), generated.wrappedKey)
            assertEquals(0, generatedReply.aborts)
            assertEquals(0, generatedReply.closes)
            generatedReply.bytes.fill(0)
            var copiedKey: ByteArray? = null
            var copiedWrapped: ByteArray? = null
            generatedReply.onClose = {
                assertZero(generated.plaintextKey)
                assertZero(generated.wrappedKey)
            }
            val frozenWrapped = withJournalDataKey(requested, generated, generated = true) { key, wrapped ->
                assertNotSame(generated.plaintextKey, key)
                assertNotSame(generated.wrappedKey, wrapped)
                assertArrayEquals(keyBytes(), key)
                copiedKey = key
                copiedWrapped = wrapped
                checkNotNull(wrapped).copyOf()
            }
            assertZero(checkNotNull(copiedKey))
            assertZero(checkNotNull(copiedWrapped))
            assertReleased(generatedReply)
            val generation = fixture.requests.single()
            assertSigned(generation, GENERATE_TARGET)
            assertEquals(setOf("KeyId", "KeySpec", "EncryptionContext"), generation.fields().fieldNames().asSequence().toSet())
            assertEquals("AES_256", generation.fields()["KeySpec"].textValue())
            assertContext(generation, expectedContext)

            val decryptedReply = JournalKmsHttpReply(decryptDocument())
            fixture.respond = { decryptedReply }
            fixture.beforePrepare = { frozenWrapped.fill(0) } // The SDK request must already own an independent input copy.
            val plaintext = adapter.unwrap(requested, frozenWrapped)
            assertFalse(plaintext is JournalGeneratedDataKeyV1)
            assertSame(plaintext.plaintextKey, plaintext.plaintextKey)
            assertArrayEquals(keyBytes(), plaintext.plaintextKey)
            plaintext.close()
            assertZero(plaintext.plaintextKey)
            assertReleased(decryptedReply)
            val decryption = fixture.requests.last()
            assertSigned(decryption, DECRYPT_TARGET)
            assertEquals(
                setOf("KeyId", "CiphertextBlob", "EncryptionAlgorithm", "EncryptionContext"),
                decryption.fields().fieldNames().asSequence().toSet(),
            )
            assertEquals(base64(wrappedBytes()), decryption.fields()["CiphertextBlob"].textValue())
            assertEquals("SYMMETRIC_DEFAULT", decryption.fields()["EncryptionAlgorithm"].textValue())
            assertContext(decryption, expectedContext)

            fixture.beforePrepare = {}
            fixture.respond = { JournalKmsHttpReply(generateDocument(plaintext = ByteArray(32) { 9 })) }
            val fresh = adapter.generate(requested)
            assertArrayEquals(ByteArray(32) { 9 }, fresh.plaintextKey)
            fresh.close()
            assertEquals(3, fixture.requests.size) // Neither generation nor unwrap is a cached relookup.
            assertFalse(adapter.toString().contains(ARN))
        }
        assertEquals(1, fixture.closedClients)
        fixture.replies.forEach { assertEquals(1, it.eofProbes) }
    }

    @Test
    fun `untrusted requests cannot widen J context identity wrapped limits or the shared timeout`() {
        val fixture = AwsJournalKmsFixture()
        fixture.adapter().use { adapter ->
            val invalid = listOf(
                request(arn = "alias/journal"), request(arn = ARN.replace("123456789012", "123456789013")),
                request(arn = ARN.replace(REGION, "us-west-2")), request(arn = ARN.replace("66666666", "99999999")),
                request(context = emptyMap()), request(context = context() + ("caller-selected" to "value")),
                request(context = mapOf("caller-selected" to context().getValue(CONTEXT_KEY))),
                request(context = mapOf(CONTEXT_KEY to "A".repeat(8192 - CONTEXT_KEY.length + 1))),
                request(context = mapOf(CONTEXT_KEY to context().getValue(CONTEXT_KEY) + "=")),
                request(context = mapOf(CONTEXT_KEY to "not/base64url")),
                request(context = mapOf(CONTEXT_KEY to url(frame(fields().dropLast(1))))),
                request(context = mapOf(CONTEXT_KEY to url(frame(fields() + "trailing")))),
                request(context = mapOf(CONTEXT_KEY to url(frame(fields()).apply { repeat(4) { this[it] = 0xff.toByte() } }))),
            ) + listOf(0, -1, 6145, Int.MAX_VALUE).map { request(maximum = it) } +
                listOf(0, -1, 1001, Int.MAX_VALUE).map { request(timeout = it) } +
                fields().indices.map { index -> request(context = context { it[index] = "wrong" }) } +
                listOf("0", "-1", "01", "9223372036854775808").map { epoch -> request(context = context { it[16] = epoch }) } +
                listOf("π", "A".repeat(4097)).map { value -> request(context = context { it[18] = value }) }
            invalid.forEach { rejected { adapter.generate(it) } }
            listOf(ByteArray(0), wrappedBytes(6145)).forEach { wrapped ->
                val before = wrapped.copyOf()
                rejected { adapter.unwrap(request(), wrapped) }
                assertArrayEquals(before, wrapped)
            }
            assertTrue(fixture.requests.isEmpty())
            adapter.generate(request()).close() // Rejections did not strand a never-dispatched slot.
            val retained = context {
                it[17] = "route-a"
                it[11] = it[11].replace("/route-b/", "/route-a/")
            }
            adapter.generate(request(context = retained)).close()
            assertContext(fixture.requests.last(), retained) // A retained selection is not silently relabelled active.
            val unchangedInput = wrappedBytes()
            fixture.respond = { JournalKmsHttpReply(decryptDocument()) }
            adapter.unwrap(request(), unchangedInput).close()
            assertArrayEquals(wrappedBytes(), unchangedInput)
        }
        val widerJ = AwsJournalKmsFixture(journal(maximumWrappedBytes = 12_288))
        widerJ.adapter().use { adapter ->
            rejected { adapter.unwrap(request(widerJ.journal), wrappedBytes(6145)) }
            assertTrue(widerJ.requests.isEmpty()) // A larger J cannot widen the provider's 6144-byte bound.
        }
        val smallerJ = AwsJournalKmsFixture(journal(maximumWrappedBytes = 32))
        smallerJ.adapter().use { adapter ->
            rejected { adapter.generate(request(smallerJ.journal, maximum = 33)) }
            assertTrue(smallerJ.requests.isEmpty())
        }
    }

    @Test
    fun `actual response ARN algorithm key bytes and bounded material metadata are mandatory`() {
        val correct = generateDocument()
        listOf(
            correct.replace(ARN, "alias/journal"), correct.replace(ARN, ARN.replace("123456789012", "123456789013")),
            correct.replace(ARN, ARN.replace(REGION, "us-west-2")), correct.replace("\"KeyId\":\"$ARN\",", ""),
            correct.replace("\"$ARN\"", "null"), correct.replace("\"Plaintext\"", "\"CiphertextForRecipient\""),
            correct.dropLast(1) + ",\"CiphertextForRecipient\":null}",
            generateDocument(plaintext = ByteArray(31)), generateDocument(plaintext = ByteArray(33)),
            generateDocument(wrapped = ByteArray(0)), generateDocument(wrapped = wrappedBytes(6145)),
            correct.dropLast(1) + ",\"KeyMaterialId\":\"${"a".repeat(63)}\"}",
            correct.dropLast(1) + ",\"KeyMaterialId\":\"${"A".repeat(64)}\"}",
        ).forEach { rejectDocument(it) }
        listOf(
            decryptDocument().replace("SYMMETRIC_DEFAULT", "AES-256-GCM"),
            decryptDocument().replace("\"SYMMETRIC_DEFAULT\"", "null"),
            decryptDocument().replace(",\"EncryptionAlgorithm\":\"SYMMETRIC_DEFAULT\"", ""),
            decryptDocument().replace(ARN, ARN.replace("66666666", "99999999")),
            decryptDocument(plaintext = ByteArray(33)),
            decryptDocument().dropLast(1) + ",\"CiphertextBlob\":\"AA==\"}",
        ).forEach { rejectDocument(it, decrypt = true) }
        val fixture = AwsJournalKmsFixture()
        fixture.adapter().use { adapter ->
            fixture.respond = {
                JournalKmsHttpReply(generateDocument(wrapped = wrappedBytes(6144)).dropLast(1) + ",\"KeyMaterialId\":\"${"a".repeat(64)}\"}")
            }
            val generated = adapter.generate(request(maximum = 6144))
            assertEquals(6144, generated.wrappedKey.size)
            generated.close()
            fixture.respond = { JournalKmsHttpReply(decryptDocument().dropLast(1) + ",\"KeyMaterialId\":\"${"1".repeat(64)}\"}") }
            adapter.unwrap(request(), wrappedBytes(6144)).close()
        }
        val smaller = AwsJournalKmsFixture(journal(maximumWrappedBytes = 32))
        smaller.adapter().use { adapter -> rejected { adapter.generate(request(smaller.journal)) } }
        assertReleased(smaller.replies.single())
    }

    @Test
    fun `strict predecode JSON UTF8 and canonical Base64 reject coercion and allocation attacks`() {
        val correct = generateDocument()
        val encoded = base64(keyBytes())
        listOf(
            "", "null", "[]", "{}", correct + "{}", correct + PRIVATE_TEXT,
            correct.dropLast(1) + ",\"KeyId\":\"$ARN\"}",
            correct.dropLast(1) + ",\"K\\u0065yId\":\"$ARN\"}",
            correct.dropLast(1) + ",\"Unknown\":\"$PRIVATE_TEXT\"}",
            correct.replace("\"$encoded\"", "null"), correct.replace("\"$encoded\"", "1234"),
            correct.replace("\"$encoded\"", "true"), correct.replace("\"$encoded\"", "[]"),
            correct.replace("\"$encoded\"", "{\"nested\":\"$PRIVATE_TEXT\"}"),
            correct.replace("\"$encoded\"", "[".repeat(1000) + "0" + "]".repeat(1000)),
        ).forEach { rejectDocument(it) }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val badTail = encoded.dropLast(2) + alphabet[alphabet.indexOf(encoded[encoded.lastIndex - 1]) + 1] + "="
        listOf("", "AA", "AAA", "A===", "AA=A", "AA-_", "AA==\\n", "AB==", "AAB=", "AAAA=", "A A=", badTail).forEach {
            rejectDocument(correct.replace(encoded, it))
        }
        val prefix = "{\"KeyId\":\"".toByteArray()
        val suffix = correct.removePrefix("{\"KeyId\":\"a").toByteArray()
        listOf(
            byteArrayOf(0xc1.toByte(), 0xa1.toByte()),
            byteArrayOf(0xe0.toByte(), 0x81.toByte(), 0xa1.toByte()),
            byteArrayOf(0xf0.toByte(), 0x80.toByte(), 0x81.toByte(), 0xa1.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xe2.toByte(), 0x82.toByte()), byteArrayOf(0x80.toByte()),
        ).forEach { rejectReply(JournalKmsHttpReply(prefix + it + suffix)) }
        listOf(
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + correct.toByteArray(),
            correct.toByteArray(Charsets.UTF_16LE), correct.toByteArray(Charsets.UTF_16BE),
        ).forEach { rejectReply(JournalKmsHttpReply(it)) }
    }

    @Test
    fun `bounded HTTP framing rejects status headers length progress and trailing data with one attempt`() {
        listOf(301, 307, 400, 401, 403, 429, 500, 503).forEach { status ->
            val reply = JournalKmsHttpReply("{\"__type\":\"KMSInternalException\",\"message\":\"$PRIVATE_TEXT\"}").apply {
                this.status = status
            }
            rejectReply(reply)
            assertEquals(0, reply.reads)
        }
        listOf(
            "Content-Length" to listOf(Long.MAX_VALUE.toString()), "Content-Length" to listOf("-1"),
            "Content-Length" to listOf("0"), "Content-Length" to listOf("1", "1"), "Content-Length" to listOf("1, 1"),
            "Content-Encoding" to listOf("gzip"), "Content-Range" to listOf("bytes 0-4/8"),
            "Location" to listOf("https://synthetic-unapproved.invalid/"), "Content-Type" to listOf("text/plain"),
            "Content-Type" to listOf("application/x-amz-json-1.1", "application/x-amz-json-1.1"),
            "Transfer-Encoding" to listOf("chunked"), "X-Bad" to listOf("$PRIVATE_TEXT\n"),
            "X-Large" to listOf("x".repeat(65_537)), "X-Many" to List(5) { "x" },
        ).forEach { header ->
            val reply = JournalKmsHttpReply(generateDocument()).apply { headers = headers + header }
            rejectReply(reply)
            assertEquals(0, reply.reads)
        }
        listOf("short", "long", "stalled", "absent", "throw").forEach { fault ->
            val reply = JournalKmsHttpReply(generateDocument()).apply {
                when (fault) {
                    "short" -> headers = headers + ("Content-Length" to listOf((bytes.size + 1).toString()))
                    "long" -> headers = headers + ("Content-Length" to listOf((bytes.size - 1).toString()))
                    "stalled" -> chunkSize = 0
                    "absent" -> bodyPresent = false
                    else -> beforeRead = { throw IOException(PRIVATE_TEXT) }
                }
            }
            rejectReply(reply)
        }
        val maximum = JournalKmsJsonPreflight.MAX_RESPONSE_BYTES
        val excess = JournalKmsHttpReply(ByteArray(maximum + 1000) { ' '.code.toByte() }).apply { headers = headers - "Content-Length" }
        rejectReply(excess)
        assertEquals(maximum + 1, excess.bytesRead)
        val chunked = JournalKmsHttpReply(generateDocument()).apply {
            headers = headers - "Content-Length" + ("Transfer-Encoding" to listOf("chunked"))
            chunkSize = 3
        }
        val fixture = AwsJournalKmsFixture().apply { respond = { chunked } }
        fixture.adapter().use { it.generate(request()).close() }
        assertReleased(chunked)
        assertEquals(1, chunked.eofProbes)
        assertNormalizedHeaderLimitation()
    }

    @Test
    fun `explicit SDK inputs defeat ambient discovery and all acquisition work stays connection free`() {
        withProperties(
            mapOf(
                "aws.endpointUrlKms" to "http://synthetic-unapproved.invalid/",
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
            val fixture = AwsJournalKmsFixture()
            fixture.adapter().use { adapter ->
                adapter.generate(request()).close()
                assertSigned(fixture.requests.single(), GENERATE_TARGET)
                fixture.respond = { JournalKmsHttpReply(generateDocument()).apply { status = 503 } }
                rejected { adapter.generate(request()) }
                assertEquals(2, fixture.requests.size)
            }
        }
        listOf("zz-invalid-99", "us-gov-west-1", "cn-north-1", "us-iso-east-1").forEach { region ->
            val fixture = AwsJournalKmsFixture(journal(region = region))
            rejected { fixture.adapter() }
            assertEquals(0, fixture.createdClients)
        }
        val fixture = AwsJournalKmsFixture()
        withProperties(mapOf(SdkSystemSetting.AWS_PARTITIONS_FILE.property() to "/synthetic-must-not-read/partitions.json")) {
            rejected { fixture.adapter() }
        }
        rejected { fixture.adapter(credentials = AwsSessionCredentials.create("SYNTHETIC", "synthetic-secret", "bad\n$PRIVATE_TEXT")) }
        rejected { fixture.adapter(httpFactory = { throw IOException(PRIVATE_TEXT) }) }
        assertEquals(0, fixture.createdClients)
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertThrows(PersistencePhaseException::class.java) { fixture.adapter() }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        fixture.adapter().use { adapter -> assertConnectionFreeEntryAndRead(fixture, adapter) }
    }

    @Test
    fun `one monotonic deadline and sanitized cancellation interruption fatal and cleanup signals survive`() {
        listOf(-1L, 1_000_000_000L).forEach { afterValidation ->
            val fixture = AwsJournalKmsFixture().apply { onClockRead = { if (it == 2) now = afterValidation } }
            fixture.adapter().use { adapter -> rejected { adapter.generate(request()) } }
            assertTrue(fixture.requests.isEmpty()) // The slice begins before context validation, never at dispatch.
        }
        val beforeDispatch = AwsJournalKmsFixture().apply { afterPrepare = { now = 950_000_000L } }
        beforeDispatch.adapter().use { adapter -> rejected { adapter.generate(request(timeout = 900)) } }
        assertEquals(1, beforeDispatch.replies.single().aborts)
        assertEquals(0, beforeDispatch.replies.single().calls)
        val fixture = AwsJournalKmsFixture()
        val slow = JournalKmsHttpReply(generateDocument()).apply {
            chunkSize = 3
            beforeRead = { fixture.now += 300_000_000L }
        }
        fixture.respond = { slow }
        fixture.adapter().use { adapter -> rejected { adapter.generate(request(timeout = 900)) } }
        assertEquals(3, slow.reads)
        assertReleased(slow) // Several quick reads cannot reset the original deadline.
        val interrupted = AwsJournalKmsFixture()
        interrupted.adapter().use { adapter ->
            try {
                Thread.currentThread().interrupt()
                assertKmsSanitized(assertThrows(InterruptedException::class.java) { interrupted.adapter() })
                assertKmsSanitized(assertThrows(InterruptedException::class.java) { adapter.generate(request()) })
                assertTrue(Thread.currentThread().isInterrupted)
                assertTrue(interrupted.requests.isEmpty())
            } finally {
                Thread.interrupted()
            }
        }
        listOf("call", "read", "wrapped", "abort", "close").forEach { stage ->
            listOf("cancel", "interrupt", "fatal").forEach { signal -> assertSignal(stage, signal) }
        }
        val fatal = AssertionError("synthetic fatal sentinel")
        val prioritized = JournalKmsHttpReply(generateDocument()).apply {
            beforeRead = { throw SdkClientException.builder().message(PRIVATE_TEXT).cause(fatal).build() }
            onClose = { throw CancellationException(PRIVATE_TEXT) }
        }
        val priorityFixture = AwsJournalKmsFixture().apply { respond = { prioritized } }
        val adapter = priorityFixture.adapter()
        assertSame(fatal, assertThrows(AssertionError::class.java) { adapter.generate(request()) })
        assertKmsSanitized(assertThrows(CancellationException::class.java) { adapter.close() })
        assertReleased(prioritized)
    }

    @Test
    fun `lease custody and failed or late cleanup remain sticky without retries or resource reuse`() {
        val fixture = AwsJournalKmsFixture()
        val adapter = fixture.adapter()
        val lease = adapter.generate(request())
        rejected { adapter.generate(request()) }
        rejected { adapter.unwrap(request(), wrappedBytes()) }
        assertEquals(1, fixture.requests.size)
        lease.close()
        lease.close()
        assertZero(lease.plaintextKey)
        assertZero(lease.wrappedKey)
        assertReleased(fixture.replies.single())
        val next = adapter.generate(request())
        adapter.close() // Closing the owner also clears the currently transferred lease arrays.
        adapter.close()
        assertZero(next.plaintextKey)
        assertZero(next.wrappedKey)
        rejected { adapter.generate(request()) }
        assertEquals(2, fixture.requests.size)
        assertEquals(1, fixture.closedClients)
        fixture.replies.forEach(::assertReleased)
        listOf(false, true).forEach { abort -> assertStickyCleanup(abort) }
        listOf(false, true).forEach { preparing -> assertLateCleanup(preparing) }
        val closing = AwsJournalKmsFixture().apply { onClientClose = { throw IOException(PRIVATE_TEXT) } }
        val owner = closing.adapter()
        owner.generate(request()).close()
        repeat(2) { rejected(OwnerDeleteAllJournalFailure.KEY_CLEANUP_FAILURE) { owner.close() } }
        rejected { owner.generate(request()) }
        assertEquals(1, closing.closedClients)
    }

    private fun assertSigned(request: JournalKmsHttpRequest, target: String) {
        val http = request.http
        assertEquals(SdkHttpMethod.POST, http.method())
        assertEquals("https", http.protocol())
        assertEquals("kms.us-east-1.amazonaws.com", http.host())
        assertEquals(443, http.port())
        assertEquals("/", http.encodedPath())
        assertTrue(http.rawQueryParameters().isEmpty())
        assertEquals(target, request.target())
        assertEquals(ARN, request.fields()["KeyId"].textValue())
        assertEquals("synthetic-kms-session", http.firstMatchingHeader("X-Amz-Security-Token").orElseThrow())
        val authorization = http.firstMatchingHeader("Authorization").orElseThrow()
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=SYNTHETICKMSACCESSKEY/"))
        assertTrue(authorization.contains("/$REGION/kms/aws4_request"))
        assertTrue(authorization.contains("x-amz-target") && authorization.contains("x-amz-security-token"))
        assertTrue(Regex(".*Signature=[0-9a-f]{64}").matches(authorization))
    }

    private fun assertContext(request: JournalKmsHttpRequest, expected: Map<String, String>) {
        val observed = request.fields()["EncryptionContext"].properties().associate { it.key to it.value.textValue() }
        assertEquals(expected, observed)
    }

    private fun assertNormalizedHeaderLimitation() {
        val reply = JournalKmsHttpReply(generateDocument()).apply {
            headers = headers + ("content-type" to listOf("application/x-amz-json-1.1"))
        }
        assertEquals(3, reply.headers.size)
        val normalized = SdkHttpResponse.builder().statusCode(200).headers(reply.headers).build()
        assertEquals(2, normalized.headers().size)
        // The public SDK map has already collapsed case-equivalent names; acceptance does not prove raw wire uniqueness.
        val fixture = AwsJournalKmsFixture().apply { respond = { reply } }
        fixture.adapter().use { it.generate(request()).close() }
        assertReleased(reply)
    }

    private fun assertConnectionFreeEntryAndRead(fixture: AwsJournalKmsFixture, adapter: AwsJournalDataKeyAdapter) {
        TransactionSynchronizationManager.initSynchronization()
        try {
            assertThrows(PersistencePhaseException::class.java) { adapter.generate(request()) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        val resource = Any()
        TransactionSynchronizationManager.bindResource(resource, Any())
        try {
            assertThrows(PersistencePhaseException::class.java) { adapter.unwrap(request(), wrappedBytes()) }
        } finally {
            TransactionSynchronizationManager.unbindResource(resource)
        }
        assertTrue(fixture.requests.isEmpty())
        val reply = JournalKmsHttpReply(generateDocument()).apply {
            beforeRead = { TransactionSynchronizationManager.setActualTransactionActive(true) }
            onClose = { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()) }
        }
        fixture.respond = { reply }
        try {
            rejected { adapter.generate(request()) }
            assertEquals(1, reply.reads)
            assertReleased(reply) // A newly non-free phase cannot suppress necessary cleanup.
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
    }

    private fun assertSignal(stage: String, signal: String) {
        val fixture = AwsJournalKmsFixture()
        val failure = when (signal) {
            "cancel" -> CancellationException(PRIVATE_TEXT)
            "interrupt" -> InterruptedException(PRIVATE_TEXT)
            else -> AssertionError("synthetic fatal sentinel")
        }
        val reply = JournalKmsHttpReply(generateDocument()).apply {
            when (stage) {
                "call" -> beforeCall = { throw failure }
                "read" -> beforeRead = { throw failure }
                "wrapped" -> beforeCall = { throw SdkClientException.builder().message(PRIVATE_TEXT).cause(failure).build() }
                "abort" -> onAbort = { throw failure }
                else -> onClose = { throw failure }
            }
        }
        fixture.respond = { reply }
        val adapter = fixture.adapter()
        var transferred: JournalGeneratedDataKeyV1? = null
        try {
            val action = {
                transferred = adapter.generate(request())
                checkNotNull(transferred).close()
            }
            when (signal) {
                "cancel" -> assertKmsSanitized(assertThrows(CancellationException::class.java) { action() })
                "interrupt" -> {
                    assertKmsSanitized(assertThrows(InterruptedException::class.java) { action() })
                    assertTrue(Thread.currentThread().isInterrupted)
                }
                else -> assertSame(failure, assertThrows(AssertionError::class.java) { action() })
            }
            transferred?.let {
                assertZero(it.plaintextKey)
                assertZero(it.wrappedKey)
            }
            assertEquals(1, fixture.requests.size)
            assertEquals(1, reply.aborts)
            assertEquals(if (stage in setOf("call", "wrapped")) 0 else 1, reply.closes)
        } finally {
            Thread.interrupted()
            val closing = runCatching { adapter.close() }.exceptionOrNull()
            if (stage in setOf("abort", "close")) {
                if (signal == "fatal") assertSame(failure, closing) else assertKmsSanitized(checkNotNull(closing))
            } else {
                assertNull(closing)
            }
            Thread.interrupted()
        }
    }

    private fun assertStickyCleanup(abort: Boolean) {
        val fixture = AwsJournalKmsFixture()
        val reply = JournalKmsHttpReply(generateDocument()).apply {
            if (abort) onAbort = { throw IOException(PRIVATE_TEXT) } else onClose = { throw IOException(PRIVATE_TEXT) }
        }
        fixture.respond = { reply }
        val adapter = fixture.adapter()
        val lease = adapter.generate(request())
        repeat(2) { rejected(OwnerDeleteAllJournalFailure.KEY_CLEANUP_FAILURE) { lease.close() } }
        assertZero(lease.plaintextKey)
        assertZero(lease.wrappedKey)
        rejected { adapter.generate(request()) }
        repeat(2) { rejected(OwnerDeleteAllJournalFailure.KEY_CLEANUP_FAILURE) { adapter.close() } }
        assertReleased(reply)
        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.closedClients)
    }

    private fun assertLateCleanup(preparing: Boolean) {
        val fixture = AwsJournalKmsFixture()
        val adapter = fixture.adapter()
        val reply = JournalKmsHttpReply(generateDocument())
        val closeInFlight = { rejected(OwnerDeleteAllJournalFailure.KEY_CLEANUP_FAILURE) { adapter.close() } }
        if (preparing) fixture.afterPrepare = closeInFlight else reply.beforeCall = closeInFlight
        fixture.respond = { reply }
        rejected { adapter.generate(request()) }
        repeat(2) { rejected(OwnerDeleteAllJournalFailure.KEY_CLEANUP_FAILURE) { adapter.close() } }
        rejected { adapter.generate(request()) }
        assertEquals(if (preparing) 0 else 1, reply.calls)
        assertEquals(if (preparing) 0 else 1, reply.closes)
        assertEquals(1, reply.aborts)
        assertEquals(0, reply.reads)
        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.closedClients)
    }

    private fun rejectDocument(json: String, decrypt: Boolean = false) = rejectReply(JournalKmsHttpReply(json), decrypt)

    private fun rejectReply(reply: JournalKmsHttpReply, decrypt: Boolean = false) {
        val fixture = AwsJournalKmsFixture().apply { respond = { reply } }
        fixture.adapter().use { adapter ->
            rejected { if (decrypt) adapter.unwrap(request(), wrappedBytes()) else adapter.generate(request()) }
        }
        assertEquals(1, fixture.requests.size)
        assertEquals(1, reply.calls)
        assertEquals(1, reply.aborts)
        assertEquals(if (reply.bodyPresent) 1 else 0, reply.closes)
        assertEquals(1, fixture.closedClients)
    }

    private fun assertReleased(reply: JournalKmsHttpReply) {
        assertEquals(1, reply.calls)
        assertEquals(1, reply.aborts)
        assertEquals(1, reply.closes)
    }

    private fun assertZero(bytes: ByteArray) = assertArrayEquals(ByteArray(bytes.size), bytes)

    private fun rejected(code: OwnerDeleteAllJournalFailure = OwnerDeleteAllJournalFailure.KEY_FAILURE, action: () -> Unit) {
        val failure = assertThrows(OwnerDeleteAllJournalException::class.java) { action() }
        assertEquals(code, failure.code)
        assertKmsSanitized(failure)
    }

    private fun assertKmsSanitized(failure: Throwable) {
        assertFalse(failure.toString().contains(PRIVATE_TEXT))
        assertFalse(failure.toString().contains(ARN))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
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
