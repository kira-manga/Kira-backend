package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealHttpFixtureV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import java.util.concurrent.atomic.AtomicReference

/** Raw STS only: HTTP callers observe their own SQL-free boundary, never borrow the sealer caller's cleanup proof. */
internal class TestRegisteredDeletionHttpStsFixtureV1(
    private val sqlReleased: () -> Unit,
    private val region: () -> String,
) {
    private val raw = AwsJournalKmsFixture()
    private val assertion = AtomicReference<AssertionError?>()
    val requestCount: Int get() = raw.requests.size

    init {
        raw.beforePrepare = { checked { boundary() } }
        raw.onClientClose = { checked { boundary() } }
        raw.respond = { request -> checked {
            boundary()
            val credentials = TestActiveFirstCutInputFixtureV1.ordinaryCredentials
            assertEquals("https", request.http.protocol())
            assertEquals("sts.${region()}.amazonaws.com", request.http.host())
            assertEquals(credentials.sessionToken(), request.http.firstMatchingHeader("x-amz-security-token").orElseThrow())
            val authorization = request.http.firstMatchingHeader("Authorization").orElseThrow()
            assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId()}/") && "/sts/aws4_request" in authorization)
            assertEquals(mapOf("Action" to "GetCallerIdentity", "Version" to "2011-06-15"), TestOrdinarySealHttpFixtureV1.query(request))
            val account = TestOrdinarySealHttpFixtureV1.ACCOUNT
            val session = TestActiveFirstCutInputFixtureV1.ORDINARY_SESSION
            JournalKmsHttpReply("""<GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><GetCallerIdentityResult>""" +
                "<Arn>arn:aws:sts::$account:assumed-role/test-ordinary/$session</Arn><UserId>AROA${"A".repeat(17)}:$session</UserId><Account>$account</Account></GetCallerIdentityResult>" +
                "<ResponseMetadata><RequestId>registered-http-native-check</RequestId></ResponseMetadata></GetCallerIdentityResponse>").apply {
                headers = mapOf("Content-Type" to listOf("text/xml; charset=utf-8"), "Content-Length" to listOf(bytes.size.toString()))
            }
        } }
    }

    fun httpClient(remaining: () -> Int): SdkHttpClient = checked {
        boundary() // Retain assertion failures even if the original raw-client acquisition never returns.
        val client = raw.httpClient()
        object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = checked {
                boundary()
                assertTrue(remaining() in 1..5_000)
                client.prepareRequest(request)
            }
            override fun close() = client.close()
            override fun clientName() = "SyntheticRegisteredDeletionHttpSts"
        }
    }

    fun assertDisposed() {
        assertEquals(raw.createdClients, raw.closedClients)
        assertEquals(raw.createdClients, raw.returnedClientCloses)
        raw.replies.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertEquals(if (it.bodyPresent) 1 else 0, it.closes) }
        assertion.get()?.let { throw it }
    }

    private fun boundary() { requireConnectionFree(); sqlReleased() }
    private fun <T> checked(action: () -> T): T = try { action() } catch (failure: AssertionError) { assertion.compareAndSet(null, failure); throw failure }
}
