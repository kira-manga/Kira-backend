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

/** Existing raw SDK transport with a retained signed predecessor chain. New tail bytes come only from the actual PUT call. */
internal class CatalogGenesisPublishHttpFixture(
    private val envelope: ByteArray,
    createdAt: Long,
    predecessorBytes: ByteArray? = null,
    predecessorRetainUntil: Long? = null,
    overlapBytes: ByteArray? = null,
    overlapRetainUntil: Long? = null,
    prefixBytes: List<ByteArray>? = null,
    prefixRetainUntil: Long? = null,
) {
    private val retainedPrefix = if (prefixBytes != null) {
        prefixBytes.mapIndexed { index, bytes -> RetainedCopy(bytes.copyOf(), "catalog-version-${index + 1}", checkNotNull(prefixRetainUntil)) }
    } else {
        listOfNotNull(
            predecessorBytes?.let { RetainedCopy(it.copyOf(), S3CatalogReadbackFixture.VERSION, checkNotNull(predecessorRetainUntil)) },
            overlapBytes?.let { RetainedCopy(it.copyOf(), OVERLAP2_VERSION, checkNotNull(overlapRetainUntil)) },
        )
    }
    private val generation = retainedPrefix.size + 1L
    private val objectKey = CatalogReadbackProtocol.key(generation)
    val publishedVersion = if (prefixBytes != null) "synthetic-test-activation-g$generation" else when (generation) {
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
    var primaryBytes = if (retainedPrefix.isEmpty()) envelope.copyOf() else ByteArray(0)
    var replicaBytes = if (retainedPrefix.isEmpty()) envelope.copyOf() else ByteArray(0)
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
        check((predecessorBytes == null) == (predecessorRetainUntil == null))
        check((overlapBytes == null) == (overlapRetainUntil == null) && (overlapBytes == null || predecessorBytes != null))
        check((prefixBytes == null) == (prefixRetainUntil == null))
        check(prefixBytes == null || (prefixBytes.isNotEmpty() && predecessorBytes == null && overlapBytes == null))
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
            val versions = retainedPrefix.mapIndexed { index, copy ->
                CatalogListedVersion(CatalogReadbackProtocol.key(index + 1L), copy.version, copy.bytes.size.toLong())
            }.toMutableList().apply {
                version?.let { add(CatalogListedVersion(objectKey, it, bytes(location).size.toLong())) }
            }
            if (duplicatePrimary && location.role == "PRIMARY") {
                checkNotNull(version)
                versions.add(CatalogListedVersion(objectKey, "conflicting-second-version", bytes(location).size.toLong()))
            }
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
        val previous = retainedPrefix.indices.firstOrNull { request.encodedPath() == "/${location.bucket}/${CatalogReadbackProtocol.key(it + 1L)}" }
        if (previous != null) {
            val copy = retainedPrefix[previous]
            val key = CatalogReadbackProtocol.key(previous + 1L)
            assertEquals(copy.version, request.firstMatchingRawQueryParameter("versionId").orElseThrow())
            return read.getReply(CatalogGetRequest(location, key, copy.version), copy.bytes).apply {
                val retention = Instant.ofEpochSecond(copy.retainUntil).toString()
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

    private class RetainedCopy(val bytes: ByteArray, val version: String, val retainUntil: Long)

    companion object {
        const val VERSION = "synthetic-g1-published-version"
        const val OVERLAP2_VERSION = "synthetic-overlap2-published-version"
        const val ACTIVATION3_VERSION = "synthetic-activation3-published-version"
        val PUT_CREDENTIALS: AwsSessionCredentials =
            AwsSessionCredentials.create("SYNTHETICPUBLISHKEY", "synthetic-publish-secret", "synthetic-publish-session")

        fun checksum(bytes: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
