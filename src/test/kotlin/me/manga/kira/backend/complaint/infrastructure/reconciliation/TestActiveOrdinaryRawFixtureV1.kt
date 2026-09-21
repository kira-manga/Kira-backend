package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReply
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealHttpFixtureV1
import me.manga.kira.backend.complaint.journal.JournalPublisherHttpRequest
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import me.manga.kira.backend.security.aws.JournalKmsHttpRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable raw factories supplied BEFORE full D. Only native STS/S3/KMS HTTP is replaced.
 * The distinct cold ordinary credentials are C's exact fixture inputs, never seal-bootstrap/target
 * credentials or the historical publisher fixture's static CREDENTIALS. Events are expectations,
 * not authorization, commit receipts, SDK observations or successful verification evidence.
 */
internal class TestActiveOrdinaryRawFixtureV1 {
    private var fixture: TestActiveOrdinarySealFixtureV1? = null
    private var expected = emptyList<TestOwnerDeleteJournalEventV1>()
    val sts = AwsJournalKmsFixture()
    var publisher: TestOwnerDeleteJournalPublisherFixture? = null
        private set
    val requestBudgets = mutableListOf<Pair<String, Int>>()
    var hideObjects = false
    var lostPutAcknowledgment = false
    var changeS3: (JournalPublisherHttpRequest, S3CatalogReply) -> Unit = { _, _ -> }
    private val assertion = AtomicReference<AssertionError?>()
    val factories = TestActiveOrdinaryRawHttpV1(
        { remaining -> native("STS", remaining, sts::httpClient) },
        { remaining -> native("KMS", remaining) { checkNotNull(publisher).kms.httpClient() } },
        { remaining -> native("S3", remaining) { checkNotNull(publisher).httpClient() } },
    )

    fun attach(f: TestActiveOrdinarySealFixtureV1) {
        check(fixture == null); fixture = f
        sts.beforePrepare = { checked { boundary() } }
        sts.onClientClose = { checked { f.assertSqlReleased() } }
        sts.respond = { request -> checked {
            boundary(); signed(request, "sts")
            assertEquals(mapOf("Action" to "GetCallerIdentity", "Version" to "2011-06-15"), TestOrdinarySealHttpFixtureV1.query(request))
            val account = TestOrdinarySealHttpFixtureV1.ACCOUNT
            val session = TestActiveFirstCutInputFixtureV1.ORDINARY_SESSION
            JournalKmsHttpReply("""<GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><GetCallerIdentityResult>""" +
                "<Arn>arn:aws:sts::$account:assumed-role/test-ordinary/$session</Arn><UserId>AROA${"A".repeat(17)}:$session</UserId><Account>$account</Account></GetCallerIdentityResult>" +
                "<ResponseMetadata><RequestId>active-ordinary-native-check</RequestId></ResponseMetadata></GetCallerIdentityResponse>").apply {
                headers = mapOf("Content-Type" to listOf("text/xml; charset=utf-8"), "Content-Length" to listOf(bytes.size.toString()))
            }
        } }
    }

    fun expect(events: List<TestOwnerDeleteJournalEventV1>) {
        check(expected.isEmpty() && publisher == null && events.isNotEmpty())
        val f = checkNotNull(fixture)
        expected = events.toList()
        val p = TestOwnerDeleteJournalPublisherFixture(f.process.consumers.journalRouting, events.first())
        publisher = p
        p.beforePrepare = { checked { boundary(); p.wall = f.native.now() } }
        p.onClientClose = { checked { f.assertSqlReleased() } }
        p.kms.onClientClose = { checked { f.assertSqlReleased() } }
        val kmsReply = p.kms.respond
        p.kms.respond = { request -> checked {
            signed(request, "kms")
            val raw = Base64.getUrlDecoder().decode(request.fields()["EncryptionContext"][AwsJournalKmsFixture.CONTEXT_KEY].asText())
            val fields = try { DataInputStream(ByteArrayInputStream(raw)).use { input -> buildList {
                while (input.available() != 0) {
                    val size = input.readInt(); assertTrue(size in 1..4096)
                    val bytes = input.readNBytes(size); assertEquals(size, bytes.size)
                    add(bytes.toString(Charsets.UTF_8)); bytes.fill(0)
                }
            } } } finally { raw.fill(0) }
            assertEquals(20, fields.size)
            p.assertKmsContext(request, expected.single { it.route.objectKey == fields[11] })
            kmsReply(request)
        } }
        p.respond = { request -> checked {
            boundary()
            val location = p.journal.declaration().journalLocation
            journalPublisherRawAssertSigned(request, location.region, location.accountId, TestActiveFirstCutInputFixtureV1.ordinaryCredentials)
            assertFalse("/seal-terminal/" in request.http.encodedPath() || "/live/" in request.http.encodedPath())
            val reply = if (hideObjects && request.kind == "LIST") p.listReply(emptyList(), request.http.rawQueryParameters().getValue("prefix").single())
                else p.statefulReply(request)
            (if (lostPutAcknowledgment && request.kind == "PUT") OwnerDeleteAllJournalPublisherFixture.errorReply(500) else reply)
                .also { changeS3(request, it) }
        } }
    }

    private fun signed(request: JournalKmsHttpRequest, service: String) {
        val f = checkNotNull(fixture)
        val credentials = TestActiveFirstCutInputFixtureV1.ordinaryCredentials
        assertEquals("https", request.http.protocol())
        assertEquals("$service.${f.process.consumers.journalConfiguration.declaration().journalLocation.region}.amazonaws.com", request.http.host())
        assertEquals(credentials.sessionToken(), request.http.firstMatchingHeader("x-amz-security-token").orElseThrow())
        val value = request.http.firstMatchingHeader("Authorization").orElseThrow()
        assertTrue(value.startsWith("AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId()}/") && "/$service/aws4_request" in value)
    }

    private fun native(kind: String, remaining: () -> Int, factory: () -> SdkHttpClient): SdkHttpClient {
        boundary()
        val raw = factory()
        return object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = checked {
                boundary()
                val budget = remaining(); assertTrue(budget in 1..5_000)
                requestBudgets.add(kind to budget)
                raw.prepareRequest(request)
            }
            override fun close() = raw.close()
            override fun clientName() = "SyntheticColdActiveOrdinary$kind"
        }
    }
    private fun boundary() { requireConnectionFree(); checkNotNull(fixture).assertProviderBoundary() }
    private fun <T> checked(action: () -> T): T = try { action() } catch (failure: AssertionError) { assertion.compareAndSet(null, failure); throw failure }
    fun assertDisposed() {
        assertEquals(sts.createdClients, sts.closedClients); assertEquals(sts.createdClients, sts.returnedClientCloses)
        sts.replies.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertEquals(if (it.bodyPresent) 1 else 0, it.closes) }
        publisher?.assertClientsClosed()
        assertion.get()?.let { throw it }
    }
    fun detach() { fixture = null; changeS3 = { _, _ -> }; assertion.get()?.let { throw it } }
}
