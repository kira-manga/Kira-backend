package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListedVersion
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.CurrentAcceptedCatalogRefreshV1
import me.manga.kira.backend.support.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpRequest
import java.util.concurrent.atomic.AtomicReference

/** Only raw HTTP/time are synthetic. The existing SDK fixture supplies bodies; the actual refresh owns every other boundary. */
internal class CurrentAcceptedCatalogRefreshHttpFixture(private val database: ProcessBoundCatalogGenesisFixture) {
    val http = S3CatalogReadbackFixture()
    val clock = MutableClock(VersionBoundCatalogReadbackTestFixture.evaluatedAt)
    var now = 0L
    var afterHttpConstruction: (Int) -> Unit = {}
    var afterHttpClose: (Int) -> Unit = {}
    private val settings = checkNotNull(database.process.catalogReadback)
    private val assertionFailure = AtomicReference<AssertionError?>()
    private var connectionFreeCuts = 0

    init {
        http.respond = { request -> observed { reply(request) } }
    }

    fun owner(process: VersionBoundComplaintProcessConfiguration = database.process): CurrentAcceptedCatalogRefreshV1 =
        CurrentAcceptedCatalogRefreshV1.withHttpFixture(
            process,
            S3CatalogReadbackFixture.credentials,
            S3CatalogReadbackFixture.credentials,
            ::httpClient,
            clock,
            { now },
        )

    fun assertFullReadback(attempts: Int = 1) {
        assertCleanupCalls()
        assertEquals(attempts * 2, http.createdClients)
        assertEquals(attempts * 4, http.requests.size)
        assertEquals(attempts * 2, http.requests.count { it.rawQueryParameters().containsKey("versions") })
        assertEquals(http.requests.size, http.replies.size)
        http.replies.forEach {
            assertEquals(1, it.calls)
            assertTrue(it.eofProbes > 0)
        }
        assertTrue(connectionFreeCuts > http.requests.size)
    }

    /** Invocation counts do not turn a throwing synthetic close into a successful cleanup receipt. */
    fun assertCleanupCalls() {
        assertionFailure.get()?.let { throw it }
        database.released()
        assertEquals(http.createdClients, http.closedClients)
        http.replies.forEach {
            assertEquals(1, it.aborts)
            assertEquals(1, it.closes)
        }
    }

    private fun httpClient(): SdkHttpClient = observed {
        val delegate = http.httpClient()
        val ordinal = http.createdClients
        val owned = object : SdkHttpClient by delegate {
            override fun prepareRequest(request: HttpExecuteRequest) = observed { delegate.prepareRequest(request) }

            override fun close() = observed {
                assertTrue(http.replies.all { it.closes == 1 }, "Bodies must close before their raw HTTP client.")
                delegate.close()
                afterHttpClose(ordinal)
            }
        }
        afterHttpConstruction(ordinal)
        owned
    }

    private fun reply(request: SdkHttpRequest): S3CatalogReply {
        val location = settings.chainPolicy.trustBundlePolicy.expectedCatalogLocations.single {
            request.encodedPath() == "/${it.bucket}" || request.encodedPath().startsWith("/${it.bucket}/")
        }
        assertEquals("https", request.protocol())
        assertEquals("s3.${location.region}.amazonaws.com", request.host())
        assertEquals(location.accountId, request.firstMatchingHeader("x-amz-expected-bucket-owner").orElseThrow())
        assertTrue(request.firstMatchingHeader("Authorization").orElseThrow().contains("/${location.region}/s3/aws4_request"))
        assertEquals("synthetic-session", request.firstMatchingHeader("x-amz-security-token").orElseThrow())
        val key = CatalogReadbackProtocol.key(1)
        val reply = if (request.rawQueryParameters().containsKey("versions")) {
            assertEquals(settings.pageSize.toString(), request.firstMatchingRawQueryParameter("max-keys").orElseThrow())
            assertFalse(request.rawQueryParameters().containsKey("key-marker"))
            assertFalse(request.rawQueryParameters().containsKey("version-id-marker"))
            http.listReply(
                CatalogListRequest(location, CatalogReadbackProtocol.PREFIX, null, settings.pageSize),
                listOf(CatalogListedVersion(key, S3CatalogReadbackFixture.VERSION, database.genesisBytes.size.toLong())),
            )
        } else {
            assertEquals("/${location.bucket}/$key", request.encodedPath())
            assertEquals(S3CatalogReadbackFixture.VERSION, request.firstMatchingRawQueryParameter("versionId").orElseThrow())
            http.getReply(CatalogGetRequest(location, key, S3CatalogReadbackFixture.VERSION), database.genesisBytes).apply {
                val retainUntil = VersionBoundCatalogReadbackTestFixture.retainUntil.toString()
                headers = headers + ("x-amz-object-lock-retain-until-date" to listOf(retainUntil))
            }
        }
        return reply.apply {
            beforeCall = { observed {} }
            beforeRead = { observed {} }
            onAbort = { observed {} }
            onClose = { observed {} }
        }
    }

    private fun <T> observed(action: () -> T): T = try {
        database.released()
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
        connectionFreeCuts++
        action()
    } catch (failure: AssertionError) {
        assertionFailure.compareAndSet(null, failure)
        throw failure
    }
}
