package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture.Companion.key
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture.Companion.primary
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture.Companion.replica
import me.manga.kira.backend.complaint.domain.catalog.CatalogListCursor
import me.manga.kira.backend.complaint.domain.catalog.CatalogListPage
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListedVersion
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisNamespaceObservationV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest

/** Existing real SDK/raw fixture only; transport/parser/close failure suites stay in S3CatalogReadbackAdapterTest. */
class CatalogGenesisNamespaceObservationV1Test {
    @Test
    fun `raw trust pins two actual SDK empty listings and returns historical observation before caller cleanup`() {
        val fixture = emptyFixture()
        val originalBudget = budget()
        fixture.adapter(trustBytes = trustBytes, policy = policy).use { adapter ->
            val observed = observe(adapter, originalBudget)
            assertEquals(Sha256.hex(trustBytes), observed.currentTrustBundleEnvelopeSha256)
            assertEquals(request(primary), observed.primaryRequest)
            assertEquals(request(replica), observed.replicaRequest)
            assertEquals(2, fixture.createdClients)
            assertEquals(0, fixture.closedClients) // An observation cannot claim that the caller's two SDK owners have closed.
            assertFalse(observed.toString().contains(primary.bucket))
            assertFalse(observed.toString().contains(observed.currentTrustBundleEnvelopeSha256))
            assertResponseCleanup(fixture, 2, clientsClosed = false)
        }
        assertResponseCleanup(fixture, 2)
        fixture.requests.zip(listOf(primary, replica)).forEach { (http, location) ->
            assertEquals(SdkHttpMethod.GET, http.method())
            assertEquals("https", http.protocol())
            assertEquals("s3.${location.region}.amazonaws.com", http.host())
            assertTrue(http.encodedPath() in listOf("/${location.bucket}", "/${location.bucket}/"))
            assertEquals(location.accountId, http.firstMatchingHeader("x-amz-expected-bucket-owner").orElseThrow())
            assertTrue(http.firstMatchingHeader("Authorization").orElseThrow().contains("/${location.region}/s3/aws4_request"))
            assertEquals("synthetic-session", http.firstMatchingHeader("x-amz-security-token").orElseThrow())
            assertEquals(PREFIX, http.firstMatchingRawQueryParameter("prefix").orElseThrow())
            assertEquals("1", http.firstMatchingRawQueryParameter("max-keys").orElseThrow())
            assertEquals("url", http.firstMatchingRawQueryParameter("encoding-type").orElseThrow())
            assertTrue(http.rawQueryParameters().getValue("versions").all { it.isNullOrEmpty() })
            assertFalse(http.rawQueryParameters().containsKey("key-marker"))
            assertFalse(http.rawQueryParameters().containsKey("version-id-marker"))
        }
        val unauthenticated = emptyFixture()
        unauthenticated.adapter(trustBytes = trustBytes, policy = policy).use { adapter ->
            val damaged = trustBytes.copyOf().apply { this[lastIndex] = 0 }
            assertThrows(OfflineTrustBundleException::class.java) {
                CatalogGenesisNamespaceObservationV1.observe(adapter, damaged, policy, budget())
            }
            assertThrows(OfflineTrustBundleException::class.java) {
                CatalogGenesisNamespaceObservationV1.observe(adapter, trustBytes, OfflineTrustBundleFixture.policy(minimumVersion = 8), budget())
            }
            assertTrue(unauthenticated.requests.isEmpty()) // The helper rechecks raw trust; an existing SDK adapter is not a trust handle.
            assertEquals(0, unauthenticated.closedClients)
        }
        assertResponseCleanup(unauthenticated, 0)
    }

    @Test
    fun `either namespace containing one actual version or delete marker refuses without object reads or pagination`() {
        listOf(primary, replica).forEach { occupied ->
            listOf(false, true).forEach { marker ->
                val fixture = emptyFixture()
                val empty = fixture.respond
                fixture.respond = { http ->
                    if (http.at(occupied)) {
                        val versions = if (marker) emptyList() else listOf(CatalogListedVersion(key, VERSION, 1))
                        val extra = if (marker) "<DeleteMarker><Key>$key</Key><VersionId>$VERSION</VersionId></DeleteMarker>" else ""
                        fixture.listReply(request(occupied), versions, extraXml = extra)
                    } else {
                        empty(http)
                    }
                }
                fixture.adapter(trustBytes = trustBytes, policy = policy).use { adapter ->
                    rejected { observe(adapter) }
                    assertEquals(0, fixture.closedClients)
                }
                assertResponseCleanup(fixture, if (occupied == primary) 1 else 2)
            }
        }
    }

    @Test
    fun `incomplete malformed or misbound pages cannot be combined into empty namespace evidence`() {
        listOf(primary, replica).forEach { affected ->
            listOf("truncated", "dangling-cursor", "wrong-binding", "malformed").forEach { shape ->
                val fixture = emptyFixture()
                val empty = fixture.respond
                fixture.respond = { http ->
                    val reply = empty(http)
                    when {
                        !http.at(affected) -> reply
                        shape == "truncated" -> fixture.listReply(request(affected), emptyList(), CatalogListCursor(key, VERSION))
                        shape == "malformed" -> S3CatalogReply(reply.bytes.copyOf(reply.bytes.size - 1))
                        else -> reply
                    }
                }
                fixture.adapter(trustBytes = trustBytes, policy = policy).use { adapter ->
                    // Negative-only corruption AFTER a genuine SDK listing: the helper must independently check the port's exact binding and terminal tuple.
                    val provider = object : CatalogReadbackPort by adapter {
                        override fun listVersions(request: CatalogListRequest): CatalogListPage {
                            val page = adapter.listVersions(request)
                            if (request.location != affected) return page
                            return when (shape) {
                                "wrong-binding" -> page.copy(requestBinding = request.copy(location = affected.copy(accountId = "999999999999")))
                                "dangling-cursor" -> page.copy(nextCursor = CatalogListCursor(key, VERSION))
                                else -> page
                            }
                        }
                    }
                    val failure = if (shape == "malformed") CatalogReadbackFailure.PROVIDER_FAILURE else CatalogReadbackFailure.INVALID_LISTING
                    rejected(failure) { observe(provider) }
                }
                assertResponseCleanup(fixture, if (affected == primary) 1 else 2)
            }
        }
    }

    @Test
    fun `one caller budget refuses expired entry and late first or combined second listing while caller still closes owners`() {
        listOf("entry", "first", "second").forEach { phase ->
            val fixture = emptyFixture()
            var elapsed = 0L
            val originalBudget = PersistenceTimeBudget.start(1_000, PersistenceNanoClock { elapsed })
            val empty = fixture.respond
            fixture.respond = { http ->
                empty(http).apply {
                    onClose = {
                        if (phase == "first") elapsed = 1_000_000_000L
                        if (phase == "second") elapsed += 600_000_000L
                    }
                }
            }
            // Keep the existing transport clock separate: these failures must come from the caller's ORIGINAL cross-listing budget.
            fixture.adapter(trustBytes = trustBytes, policy = policy).use { adapter ->
                if (phase == "entry") elapsed = 1_000_000_000L
                repeat(2) {
                    val failure = assertThrows(PersistenceBoundaryException::class.java) { observe(adapter, originalBudget) }
                    assertEquals(PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED, failure.code)
                }
                assertEquals(0L, fixture.now)
                assertEquals(0, fixture.closedClients)
            }
            val expected = when (phase) {
                "entry" -> 0
                "first" -> 1
                else -> 2
            }
            assertResponseCleanup(fixture, expected)
        }
    }

    private fun emptyFixture(): S3CatalogReadbackFixture = S3CatalogReadbackFixture().apply {
        respond = { http -> listReply(request(listOf(primary, replica).single { http.at(it) }), emptyList()) }
    }

    private fun observe(provider: CatalogReadbackPort, originalBudget: PersistenceTimeBudget = budget()): CatalogGenesisNamespaceObservationV1 =
        CatalogGenesisNamespaceObservationV1.observe(provider, trustBytes, policy, originalBudget)

    private fun assertResponseCleanup(fixture: S3CatalogReadbackFixture, requests: Int, clientsClosed: Boolean = true) {
        assertEquals(requests, fixture.requests.size)
        assertEquals(if (clientsClosed) 2 else 0, fixture.closedClients)
        fixture.requests.forEach { assertTrue(it.rawQueryParameters().containsKey("versions")) }
        fixture.replies.forEach {
            assertEquals(1, it.calls)
            assertEquals(1, it.aborts)
            assertEquals(1, it.closes)
        }
    }

    private fun rejected(code: CatalogReadbackFailure = CatalogReadbackFailure.INVALID_LISTING, action: () -> Unit) {
        val failure = assertThrows(CatalogReadbackException::class.java) { action() }
        assertEquals(code, failure.code)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    companion object {
        private const val PREFIX = "complaints/catalog/v1/"
        private const val VERSION = "synthetic-namespace-version-1"
        private val trustBytes by lazy { OfflineTrustBundleFixture.bytes(OfflineCatalogGenesisFixture.bundle()) }
        private val policy: OfflineTrustBundlePolicy by lazy { OfflineTrustBundleFixture.policy() }

        private fun budget(): PersistenceTimeBudget = PersistenceTimeBudget.start(10_000, PersistenceNanoClock { 0 })
        private fun request(location: OfflineCatalogLocationV1): CatalogListRequest = CatalogListRequest(location, PREFIX, null, 1)
        private fun SdkHttpRequest.at(location: OfflineCatalogLocationV1): Boolean = encodedPath() in listOf("/${location.bucket}", "/${location.bucket}/")
    }
}
