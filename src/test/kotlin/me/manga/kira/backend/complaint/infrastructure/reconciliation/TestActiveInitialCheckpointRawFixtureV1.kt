package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReply
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealHttpFixtureV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointInputV1
import me.manga.kira.backend.complaint.journal.JournalPublisherHttpRequest
import me.manga.kira.backend.complaint.journal.JournalPublisherObject
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
import me.manga.kira.backend.complaint.journal.journalPublisherRawGetReply
import me.manga.kira.backend.complaint.journal.journalPublisherRawHttpClient
import me.manga.kira.backend.complaint.journal.journalPublisherRawListDocument
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCryptoReferenceV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import me.manga.kira.backend.security.aws.JournalKmsHttpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import java.util.concurrent.atomic.AtomicReference

/** TEST raw inputs only. No target, registration, old result, paid row or successful observation can enter here. */
internal class TestActiveInitialCheckpointHttpInputV1(
    val sts: (remainingMillis: () -> Int) -> SdkHttpClient,
    val kms: (remainingMillis: () -> Int) -> SdkHttpClient,
    val s3: (remainingMillis: () -> Int) -> SdkHttpClient,
) {
    val input = TestActiveInitialCheckpointInputV1(1, TestActiveInitialCheckpointDocumentV1.PROFILE, SESSION)
    val credentials: AwsSessionCredentials get() = CREDENTIALS
    companion object {
        const val SESSION = "synthetic-initial-checkpoint"
        val CREDENTIALS: AwsSessionCredentials = AwsSessionCredentials.create("SYNTHETICCHECKPOINTKEY",
            "synthetic-checkpoint-secret-not-a-real-credential", "synthetic-checkpoint-session")
    }
}

/**
 * Selected before D. Only the public native HTTP SPI is substituted; real SDK/SigV4, bounded raw
 * XML/JSON, AEAD and KMS decrypt run in MAIN. Reads the ACTUAL object stored by the preceding A PUT.
 * No object depot across JVMs is claimed. Distinct scanner credentials never reuse seal/ordinary keys.
 */
internal class TestActiveInitialCheckpointRawFixtureV1 {
    private var fixture: TestActiveInitialCheckpointFixtureV1? = null
    val sts = AwsJournalKmsFixture()
    val kms = AwsJournalKmsFixture()
    val requests = mutableListOf<JournalPublisherHttpRequest>()
    val order = mutableListOf<String>()
    val requestBudgets = mutableListOf<Pair<String, Int>>()
    var s3Created = 0
        private set
    var s3Closed = 0
        private set
    var s3CloseReturned = 0
        private set
    var beforeS3: (JournalPublisherHttpRequest) -> Unit = {}
    var changeS3: (JournalPublisherHttpRequest, S3CatalogReply) -> Unit = { _, _ -> }
    var changeList: (JournalPublisherHttpRequest, String) -> String = { _, xml -> xml }
    var changeSts: (JournalKmsHttpReply) -> Unit = {}
    var onNativeClose: () -> Unit = {}
    var ordinaryObjects: (Int) -> List<JournalPublisherObject> = { emptyList() }
    private val assertion = AtomicReference<AssertionError?>()
    val input = TestActiveInitialCheckpointHttpInputV1(
        { remaining -> native("STS", remaining, sts::httpClient) },
        { remaining -> native("KMS", remaining, kms::httpClient) },
        { remaining -> native("S3", remaining, ::s3Client) },
    )

    init {
        sts.beforePrepare = { checked { boundary() } }; kms.beforePrepare = { checked { boundary() } }
        sts.onClientClose = { checked { closeBoundary() } }; kms.onClientClose = { checked { closeBoundary() } }
        sts.respond = { request -> checked {
            boundary(); signed(request, "sts"); order.add("STS")
            assertEquals(mapOf("Action" to "GetCallerIdentity", "Version" to "2011-06-15"), TestOrdinarySealHttpFixtureV1.query(request))
            val account = TestOrdinarySealHttpFixtureV1.ACCOUNT
            val session = TestActiveInitialCheckpointHttpInputV1.SESSION
            JournalKmsHttpReply("""<GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><GetCallerIdentityResult>""" +
                "<Arn>arn:aws:sts::$account:assumed-role/test-recovery/$session</Arn><UserId>AROA${"C".repeat(17)}:$session</UserId><Account>$account</Account></GetCallerIdentityResult>" +
                "<ResponseMetadata><RequestId>initial-checkpoint-read-identity</RequestId></ResponseMetadata></GetCallerIdentityResponse>").apply {
                headers = mapOf("Content-Type" to listOf("text/xml; charset=utf-8"), "Content-Length" to listOf(bytes.size.toString()))
                changeSts(this)
            }
        } }
        kms.respond = { request -> checked {
            boundary(); signed(request, "kms"); order.add("DECRYPT")
            assertEquals(AwsJournalKmsFixture.DECRYPT_TARGET, request.target(), "No GenerateDataKey route.")
            val f = checkNotNull(fixture)
            val parts = TestTerminalCryptoReferenceV1.parts(checkNotNull(f.sealer.native.stored).bytes)
            val header = Json.parseToJsonElement(parts.header.toString(Charsets.UTF_8)).jsonObject
            val context = TestTerminalCryptoReferenceV1.context(TestTerminalCodecKindV1.EPOCH_SEAL, header)
            assertEquals(context, request.fields()["EncryptionContext"].fields().asSequence().associate { it.key to it.value.textValue() })
            val key = f.process.consumers.journalConfiguration.declaration().encryption.keyArn
            assertEquals(key, request.fields()["KeyId"].textValue())
            JournalKmsHttpReply(AwsJournalKmsFixture.decryptDocument(key, AwsJournalKmsFixture.keyBytes()))
        } }
    }

    fun attach(f: TestActiveInitialCheckpointFixtureV1) { check(fixture == null); fixture = f }
    fun detach(f: TestActiveInitialCheckpointFixtureV1) { check(fixture === f); fixture = null }
    fun resetFaults() { beforeS3 = {}; changeS3 = { _, _ -> }; changeList = { _, xml -> xml }; changeSts = {}; onNativeClose = {}; ordinaryObjects = { emptyList() } }

    private fun s3Client(): SdkHttpClient {
        boundary(); s3Created++
        return journalPublisherRawHttpClient(requests, { checked { boundary() } }, {}, {
            s3Closed++; checked { closeBoundary() }; s3CloseReturned++
        }, ::s3Reply)
    }
    private fun s3Reply(request: JournalPublisherHttpRequest): S3CatalogReply = checked {
        boundary()
        val f = checkNotNull(fixture)
        val j = f.process.consumers.journalConfiguration
        val location = j.declaration().journalLocation
        val seal = checkNotNull(f.sealer.native.stored)
        journalPublisherRawAssertSigned(request, location.region, location.accountId, input.credentials)
        assertTrue(request.kind == "LIST" || request.kind == "GET", "Read-only native graph has no PUT.")
        beforeS3(request)
        val reply = if (request.kind == "LIST") {
            val prefix = request.http.rawQueryParameters().getValue("prefix").single()
            assertEquals("2", request.http.rawQueryParameters().getValue("max-keys").single())
            assertTrue(prefix == seal.key || prefix == j.ordinaryPrefix)
            assertTrue(request.http.rawQueryParameters().keys.none { it in setOf("delimiter", "key-marker", "version-id-marker", "start-after") })
            val values = if (prefix == seal.key) { order.add("SEAL_LIST"); listOf(seal) }
                else { val pass = checkNotNull(f.probe.original).passNumber; order.add("PASS$pass"); ordinaryObjects(pass) }
            OwnerDeleteAllJournalPublisherFixture.xmlReply(changeList(request, journalPublisherRawListDocument(location.bucket, prefix, values)))
        } else {
            order.add("SEAL_GET")
            assertEquals("/${location.bucket}/${seal.key}", request.http.encodedPath())
            assertEquals(seal.version, request.http.rawQueryParameters().getValue("versionId").single())
            // This response owns a fresh native-copy body, not the retained object depot's byte array.
            journalPublisherRawGetReply(location.region, seal.copy(bytes = seal.bytes.copyOf()))
        }
        reply.also { changeS3(request, it) }
    }
    private fun signed(request: JournalKmsHttpRequest, service: String) {
        val location = checkNotNull(fixture).process.consumers.journalConfiguration.declaration().journalLocation
        assertEquals("https", request.http.protocol()); assertEquals("$service.${location.region}.amazonaws.com", request.http.host())
        assertEquals(input.credentials.sessionToken(), request.http.firstMatchingHeader("x-amz-security-token").orElseThrow())
        val auth = request.http.firstMatchingHeader("Authorization").orElseThrow()
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=${input.credentials.accessKeyId()}/") && "/$service/aws4_request" in auth)
    }
    private fun native(kind: String, remaining: () -> Int, factory: () -> SdkHttpClient): SdkHttpClient {
        boundary()
        val raw = factory()
        return object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = checked {
                boundary(); val budget = remaining(); assertTrue(budget in 1..5_000); requestBudgets.add(kind to budget)
                raw.prepareRequest(request)
            }
            override fun close() = raw.close()
            override fun clientName(): String = "SyntheticInitialCheckpoint$kind"
        }
    }
    private fun boundary() { requireConnectionFree(); checkNotNull(fixture).assertProviderBoundary() }
    private fun closeBoundary() { requireConnectionFree(); checkNotNull(fixture).assertSqlReleased(); onNativeClose() }
    private fun <T> checked(work: () -> T): T = try { work() } catch (failure: AssertionError) { assertion.compareAndSet(null, failure); throw failure }
    fun assertNoLostAssertions() { assertion.get()?.let { throw it } }
    fun assertDisposed(requireReturnedClose: Boolean = true) {
        assertEquals(s3Created, s3Closed)
        if (requireReturnedClose) assertEquals(s3Created, s3CloseReturned)
        listOf(sts, kms).forEach { f ->
            assertEquals(f.createdClients, f.closedClients)
            if (requireReturnedClose) assertEquals(f.createdClients, f.returnedClientCloses)
            f.replies.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertEquals(if (it.bodyPresent) 1 else 0, it.closes) }
        }
        requests.forEach { request ->
            assertEquals(1, request.calls); assertEquals(1, request.aborts)
            request.reply?.let { assertEquals(if (request.responseReturned && it.bodyPresent) 1 else 0, it.closes) }
        }
        assertNoLostAssertions()
    }
}
