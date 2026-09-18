package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListCursor
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListedVersion
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/** Raw SDK transport with retained genuine G1/optional overlap2 predecessors. New tail bytes come only from the actual PUT call. */
internal class CatalogGenesisPublishHttpFixture(
    private val envelope: ByteArray,
    createdAt: Long,
    predecessorBytes: ByteArray? = null,
    private val predecessorRetainUntil: Long? = null,
    overlapBytes: ByteArray? = null,
    private val overlapRetainUntil: Long? = null,
) {
    private val predecessor = predecessorBytes?.copyOf()
    private val overlap = overlapBytes?.copyOf()
    private val generation = if (overlap != null) 3L else if (predecessor != null) 2L else 1L
    private val objectKey = CatalogReadbackProtocol.key(generation)
    private val publishedVersion = when (generation) {
        1L -> VERSION
        2L -> OVERLAP2_VERSION
        else -> ACTIVATION3_VERSION
    }
    val put = S3CatalogReadbackFixture()
    val read = S3CatalogReadbackFixture()
    val bodies = mutableListOf<ByteArray>()
    val retainUntil = Instant.ofEpochSecond(createdAt).atOffset(ZoneOffset.UTC).plusYears(10).toEpochSecond()
    var primaryVersion: String? = null
    var replicaVersion: String? = null
    var primaryReplication = "PENDING"
    var primaryBytes = if (predecessor == null) envelope.copyOf() else ByteArray(0)
    var replicaBytes = if (predecessor == null) envelope.copyOf() else ByteArray(0)
    var primaryRetention = retainUntil
    var replicaRetention = retainUntil
    var replicateOnPut = false
    var duplicatePrimary = false
    var deleteMarkerRole: String? = null
    var beforePut: () -> Unit = {}
    var afterPutClientCreated: (SdkHttpClient) -> Unit = {}
    var afterPutAccepted: () -> Unit = {}
    var afterPutClientClose: () -> Unit = {}
    var beforeRead: () -> Unit = {}
    var afterReadPrepared: () -> Unit = {}
    var afterReadClientClose: () -> Unit = {}
    var afterPutBody: () -> Unit = {}
    private val assertion = AtomicReference<AssertionError?>()

    init {
        check((predecessor == null) == (predecessorRetainUntil == null))
        check((overlap == null) == (overlapRetainUntil == null) && (overlap == null || predecessor != null))
        put.respond = { request -> preserveAssertions { putReply(request).observeLifecycle() } }
        read.respond = { request -> preserveAssertions { readReply(request).observeLifecycle().apply { beforeCall = ::connectionFreeObservation } } }
    }

    fun putClient(): SdkHttpClient {
        requireConnectionFree()
        val native = put.httpClient()
        return object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = preserveAssertions {
                requireConnectionFree()
                beforePut()
                val body = request.contentStreamProvider().orElseThrow().newStream().use {
                    it.readNBytes(OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES + 1)
                }
                assertTrue(body.size <= OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES)
                bodies.add(body)
                afterPutBody()
                native.prepareRequest(request)
            }

            override fun close() = preserveAssertions {
                requireConnectionFree()
                native.close()
                afterPutClientClose()
            }

            override fun clientName(): String = "SyntheticGenesisPublisherPut"
        }.also { client -> preserveAssertions { afterPutClientCreated(client) } }
    }

    fun readClient(): SdkHttpClient {
        requireConnectionFree()
        val native = read.httpClient()
        return object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = preserveAssertions {
                native.prepareRequest(request).also { afterReadPrepared() }
            }

            override fun close() = preserveAssertions {
                requireConnectionFree()
                native.close()
                afterReadClientClose()
            }

            override fun clientName(): String = "SyntheticGenesisPublisherRead"
        }
    }

    fun completeReplication() {
        check(primaryVersion != null)
        primaryReplication = "COMPLETED"
        replicaVersion = primaryVersion
        replicaBytes = primaryBytes.copyOf()
    }

    fun assertNoLostAssertions() {
        assertion.get()?.let { throw it }
    }

    private fun putReply(request: SdkHttpRequest): S3CatalogReply {
        requireConnectionFree()
        assertEquals(SdkHttpMethod.PUT, request.method())
        assertEquals("/${S3CatalogReadbackFixture.primary.bucket}/$objectKey", request.encodedPath())
        val body = bodies.last().copyOf()
        return S3CatalogReply(ByteArray(0)).apply {
            headers = headers + mapOf(
                "x-amz-version-id" to listOf(publishedVersion),
                "x-amz-checksum-sha256" to listOf(checksum(body)),
            )
            beforeCall = {
                preserveAssertions {
                    requireConnectionFree()
                    // The test's synthetic server accepts this actual HTTP call before a deliberately lost acknowledgement.
                    primaryBytes = body.copyOf()
                    primaryVersion = publishedVersion
                    if (replicateOnPut) completeReplication()
                    afterPutAccepted()
                }
            }
        }
    }

    private fun readReply(request: SdkHttpRequest): S3CatalogReply {
        requireConnectionFree()
        beforeRead()
        assertEquals(SdkHttpMethod.GET, request.method())
        val location = OfflineTrustBundleFixture.locations.single { request.encodedPath().startsWith("/${it.bucket}") }
        assertEquals(location.accountId, request.firstMatchingHeader("x-amz-expected-bucket-owner").orElseThrow())
        assertEquals(S3CatalogReadbackFixture.credentials.sessionToken(), request.firstMatchingHeader("x-amz-security-token").orElseThrow())
        val version = version(location)
        if (request.rawQueryParameters().containsKey("versions")) {
            val maximum = request.firstMatchingRawQueryParameter("max-keys").orElseThrow().toInt()
            val prefix = request.firstMatchingRawQueryParameter("prefix").orElseThrow()
            assertEquals(CatalogReadbackProtocol.PREFIX, prefix)
            val previous = predecessor?.let {
                CatalogListedVersion(CatalogReadbackProtocol.key(1), S3CatalogReadbackFixture.VERSION, it.size.toLong())
            }
            val second = overlap?.let { CatalogListedVersion(CatalogReadbackProtocol.key(2), OVERLAP2_VERSION, it.size.toLong()) }
            val listed = version?.let { CatalogListedVersion(objectKey, it, bytes(location).size.toLong()) }
            val versions = listOfNotNull(previous, second, listed).toMutableList()
            if (duplicatePrimary && location.role == "PRIMARY") versions.add(checkNotNull(listed).copy(versionId = "conflicting-second-version"))
            val marker = if (deleteMarkerRole == location.role) {
                "<DeleteMarker><Key>$objectKey</Key><VersionId>synthetic-delete-marker</VersionId></DeleteMarker>"
            } else {
                ""
            }
            val cursor = request.firstMatchingRawQueryParameter("key-marker").orElse(null)?.let {
                CatalogListCursor(it, request.firstMatchingRawQueryParameter("version-id-marker").orElseThrow())
            }
            val offset = if (cursor == null) {
                0
            } else {
                val found = versions.indexOfFirst { it.key == cursor.keyMarker && it.versionId == cursor.versionIdMarker }
                check(found >= 0)
                found + 1
            }
            val page = versions.drop(offset).take(maximum)
            val next = if (offset + page.size < versions.size) {
                page.last().let { CatalogListCursor(it.key, checkNotNull(it.versionId)) }
            } else {
                null
            }
            return read.listReply(CatalogListRequest(location, prefix, cursor, maximum), page, next, extraXml = marker)
        }
        if (predecessor != null && request.encodedPath() == "/${location.bucket}/${CatalogReadbackProtocol.key(1)}") {
            assertEquals(S3CatalogReadbackFixture.VERSION, request.firstMatchingRawQueryParameter("versionId").orElseThrow())
            return read.getReply(CatalogGetRequest(location, CatalogReadbackProtocol.key(1), S3CatalogReadbackFixture.VERSION), predecessor).apply {
                val retention = Instant.ofEpochSecond(checkNotNull(predecessorRetainUntil)).toString()
                headers = headers + ("x-amz-object-lock-retain-until-date" to listOf(retention))
            }
        }
        if (overlap != null && request.encodedPath() == "/${location.bucket}/${CatalogReadbackProtocol.key(2)}") {
            assertEquals(OVERLAP2_VERSION, request.firstMatchingRawQueryParameter("versionId").orElseThrow())
            return read.getReply(CatalogGetRequest(location, CatalogReadbackProtocol.key(2), OVERLAP2_VERSION), overlap).apply {
                val retention = Instant.ofEpochSecond(checkNotNull(overlapRetainUntil)).toString()
                headers = headers + ("x-amz-object-lock-retain-until-date" to listOf(retention))
            }
        }
        assertEquals("/${location.bucket}/$objectKey", request.encodedPath())
        assertEquals(version, request.firstMatchingRawQueryParameter("versionId").orElseThrow())
        return read.getReply(CatalogGetRequest(location, objectKey, checkNotNull(version)), bytes(location)).apply {
            val retention = if (location.role == "PRIMARY") primaryRetention else replicaRetention
            headers = headers + mapOf(
                "x-amz-object-lock-retain-until-date" to listOf(Instant.ofEpochSecond(retention).toString()),
                "x-amz-replication-status" to listOf(if (location.role == "PRIMARY") primaryReplication else "REPLICA"),
            )
        }
    }

    private fun version(location: OfflineCatalogLocationV1): String? = if (location.role == "PRIMARY") primaryVersion else replicaVersion

    private fun bytes(location: OfflineCatalogLocationV1): ByteArray = if (location.role == "PRIMARY") primaryBytes else replicaBytes

    private fun S3CatalogReply.observeLifecycle(): S3CatalogReply = apply {
        beforeRead = ::connectionFreeObservation
        onClose = ::connectionFreeObservation
        onAbort = ::connectionFreeObservation
    }

    private fun connectionFreeObservation() = preserveAssertions { requireConnectionFree() }

    private fun <T> preserveAssertions(action: () -> T): T = try {
        action()
    } catch (failure: AssertionError) {
        assertion.compareAndSet(null, failure)
        throw failure
    }

    companion object {
        const val VERSION = "synthetic-g1-published-version"
        const val OVERLAP2_VERSION = "synthetic-overlap2-published-version"
        const val ACTIVATION3_VERSION = "synthetic-activation3-published-version"
        val PUT_CREDENTIALS: AwsSessionCredentials =
            AwsSessionCredentials.create("SYNTHETICPUBLISHKEY", "synthetic-publish-secret", "synthetic-publish-session")

        fun checksum(bytes: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
