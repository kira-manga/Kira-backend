package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture.Companion.getRequest
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture.Companion.listRequest
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture.Companion.primary
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture.Companion.replica
import me.manga.kira.backend.complaint.domain.catalog.CatalogListCursor
import me.manga.kira.backend.complaint.domain.catalog.CatalogListedVersion
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.core.SdkSystemSetting
import java.io.IOException
import java.util.concurrent.CancellationException

class S3CatalogReadbackAdapterTest {
    @Test
    fun `genuine signed dual chain passes through the real SDK and closes every raw response before evidence`() {
        val fixture = S3CatalogReadbackFixture()
        fixture.adapter().use { adapter ->
            val result = assertInstanceOf(CatalogReadbackResult.CurrentHeadObserved::class.java, fixture.catalog.verify(adapter))
            assertEquals(fixture.catalog.head().envelopeSha256, result.evidence.chain.tail.envelopeSha256)
            assertEquals(fixture.catalog.chain.generations.last().manifest.restoreInventory, result.evidence.chain.inventory)
            assertEquals(fixture.catalog.bytes.sumOf { it.size.toLong() }, result.evidence.primaryEncodedBytes)
            assertEquals(result.evidence.primaryEncodedBytes, result.evidence.replicaEncodedBytes)
        }
        assertEquals(12, fixture.requests.size)
        assertEquals(2, fixture.closedClients)
        fixture.replies.forEach { assertReleased(it) }
        listOf(primary, replica).forEach { location ->
            val requests = fixture.requests.filter { it.encodedPath().startsWith("/${location.bucket}") }
            assertEquals(6, requests.size)
            requests.forEach { request ->
                assertEquals("https", request.protocol())
                assertEquals("s3.${location.region}.amazonaws.com", request.host())
                assertEquals(location.accountId, request.firstMatchingHeader("x-amz-expected-bucket-owner").orElseThrow())
                assertTrue(request.firstMatchingHeader("Authorization").orElseThrow().contains("/${location.region}/s3/aws4_request"))
                assertEquals("synthetic-session", request.firstMatchingHeader("x-amz-security-token").orElseThrow())
            }
            val lists = requests.filter { it.rawQueryParameters().containsKey("versions") }
            assertFalse(lists.first().rawQueryParameters().containsKey("key-marker"))
            lists.forEachIndexed { index, request ->
                assertEquals("url", request.firstMatchingRawQueryParameter("encoding-type").orElseThrow())
                assertEquals("1", request.firstMatchingRawQueryParameter("max-keys").orElseThrow())
                assertTrue(request.rawQueryParameters().getValue("versions").all { it.isNullOrEmpty() })
                if (index > 0) {
                    assertEquals(CatalogReadbackProtocol.key(index.toLong()), request.firstMatchingRawQueryParameter("key-marker").orElseThrow())
                    assertEquals("catalog-version-$index", request.firstMatchingRawQueryParameter("version-id-marker").orElseThrow())
                }
            }
            requests.filterNot { it.rawQueryParameters().containsKey("versions") }.forEachIndexed { index, request ->
                assertEquals("/${location.bucket}/${CatalogReadbackProtocol.key(index + 1L)}", request.encodedPath())
                assertEquals("catalog-version-${index + 1}", request.firstMatchingRawQueryParameter("versionId").orElseThrow())
            }
        }
    }

    @Test
    fun `URL encoded keys and cursor keys decode once but version percent plus and XML escapes remain exact`() {
        val fixture = S3CatalogReadbackFixture()
        val version = "opaque%2F+&version"
        val cursor = CatalogListCursor(S3CatalogReadbackFixture.key, version)
        val request = listRequest(cursor = cursor)
        val reply = fixture.listReply(request, listOf(CatalogListedVersion(cursor.keyMarker, version, 7)))
        fixture.respond = { reply }
        fixture.adapter().use { adapter ->
            val page = adapter.listVersions(request)
            assertEquals(request, page.requestBinding)
            assertEquals(version, page.versions.single().versionId)
            assertEquals(cursor.keyMarker, page.versions.single().key)
        }
        assertEquals(version, fixture.requests.single().firstMatchingRawQueryParameter("version-id-marker").orElseThrow())
        assertReleased(reply)
    }

    @Test
    fun `trust is genuinely authenticated before either client factory and unknown location never dispatches`() {
        val fixture = S3CatalogReadbackFixture()
        val damaged = fixture.catalog.current.copyOf().apply { this[lastIndex] = 0 }
        assertThrows(OfflineTrustBundleException::class.java) { fixture.adapter(trustBytes = damaged) }
        assertEquals(0, fixture.createdClients)
        fixture.adapter().use { adapter ->
            val foreign = primary.copy(accountId = "999999999999")
            reject(CatalogReadbackFailure.INVALID_POLICY) { adapter.listVersions(listRequest(foreign)) }
            reject(CatalogReadbackFailure.INVALID_POLICY) { adapter.openVersion(getRequest(foreign)) }
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `unsupported regions and ambient partition or legacy endpoint metadata are rejected without opening transport`() {
        val fixture = S3CatalogReadbackFixture()
        val locations = listOf(primary.copy(region = "eu-test-1"), replica)
        val bundle = OfflineTrustBundleFixture.bytes(OfflineTrustBundleFixture.signed(OfflineTrustBundleFixture.body().copy(catalogLocations = locations)))
        reject(CatalogReadbackFailure.INVALID_POLICY) { fixture.adapter(bundle, OfflineTrustBundleFixture.policy(catalogLocations = locations)) }
        listOf(SdkSystemSetting.AWS_PARTITIONS_FILE, SdkSystemSetting.AWS_S3_US_EAST_1_REGIONAL_ENDPOINT).forEach { setting ->
            val previous = System.getProperty(setting.property())
            try {
                System.setProperty(setting.property(), "synthetic-unapproved-override")
                reject(CatalogReadbackFailure.INVALID_POLICY) { fixture.adapter() }
            } finally {
                if (previous == null) System.clearProperty(setting.property()) else System.setProperty(setting.property(), previous)
            }
        }
        assertEquals(0, fixture.createdClients)
    }

    @Test
    fun `partial two client construction closes the first client and sanitizes the factory failure`() {
        val fixture = S3CatalogReadbackFixture()
        var factories = 0
        reject(CatalogReadbackFailure.PROVIDER_FAILURE) {
            fixture.adapter(httpFactory = { if (factories++ == 0) fixture.httpClient() else throw IOException(PRIVATE_TEXT) })
        }
        assertEquals(2, factories)
        assertEquals(1, fixture.closedClients)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `LIST maps observed fields rather than recreating missing or different values from the request`() {
        val fixture = S3CatalogReadbackFixture()
        val correct = fixture.listReply().bytes.toString(Charsets.UTF_8)
        listOf(
            correct.replace("<Name>${primary.bucket}</Name>", "<Name>wrong-bucket</Name>"),
            correct.replace("<MaxKeys>1</MaxKeys>", "<MaxKeys>2</MaxKeys>"),
            correct.replace("<EncodingType>url</EncodingType>", ""),
            correct.replace("<Size>${S3CatalogReadbackFixture.content.size}</Size>", ""),
            correct.replace("<VersionId>${S3CatalogReadbackFixture.VERSION}</VersionId>", "<VersionId>null</VersionId>"),
            correct.replace("<IsTruncated>false</IsTruncated>", "<IsTruncated>true</IsTruncated>"),
            correct.replace("<KeyMarker></KeyMarker>", "<KeyMarker>wrong-marker</KeyMarker>"),
        ).forEach { xml ->
            val reply = S3CatalogReply(xml.toByteArray())
            fixture.respond = { reply }
            fixture.adapter().use { reject(CatalogReadbackFailure.INVALID_LISTING) { it.listVersions(listRequest()) } }
            assertReleased(reply)
        }
    }

    @Test
    fun `actual SDK delete markers remain visible to the unchanged verifier rather than being filtered away`() {
        val fixture = S3CatalogReadbackFixture()
        val encodedKey = S3CatalogReadbackFixture.encoded(S3CatalogReadbackFixture.key)
        val reply = fixture.listReply(
            versions = emptyList(),
            extraXml = "<DeleteMarker><Key>$encodedKey</Key><VersionId>deleted-version</VersionId></DeleteMarker>",
        )
        fixture.respond = { reply }
        fixture.adapter().use { adapter ->
            val page = adapter.listVersions(listRequest())
            assertTrue(page.versions.isEmpty())
            assertEquals(S3CatalogReadbackFixture.key, page.deleteMarkers.single().key)
            assertEquals("deleted-version", page.deleteMarkers.single().versionId)
        }
        assertEquals(1, fixture.requests.size)
        assertReleased(reply)
    }

    @Test
    fun `GET exact metadata is observed and missing different duplicate or unsuitable headers fail closed`() {
        listOf(
            "x-amz-version-id" to emptyList(),
            "x-amz-version-id" to listOf("wrong-version"),
            "x-amz-version-id" to listOf(S3CatalogReadbackFixture.VERSION, S3CatalogReadbackFixture.VERSION),
            "x-amz-object-lock-mode" to listOf("GOVERNANCE"),
            "x-amz-object-lock-retain-until-date" to listOf("2024-07-04T04:00:00.001Z"),
            "x-amz-replication-status" to listOf("REPLICA"),
            "x-amz-delete-marker" to listOf("true"),
            "Content-Range" to listOf("bytes 0-3/5"),
            "Content-Encoding" to listOf("gzip"),
            "x-amz-bucket-region" to listOf("us-west-2"),
        ).forEach { (name, values) ->
            val fixture = S3CatalogReadbackFixture()
            val reply = fixture.getReply().apply { headers = if (values.isEmpty()) headers - name else headers + (name to values) }
            fixture.respond = { reply }
            fixture.adapter().use { adapter -> safe(assertThrows(CatalogReadbackException::class.java) { adapter.openVersion(getRequest()) }) }
            assertEquals(0, reply.reads)
            assertReleased(reply)
        }
    }

    @Test
    fun `replica replication status must be REPLICA and a primary PENDING observation is not upgraded to completed`() {
        val fixture = S3CatalogReadbackFixture()
        val primaryReply = fixture.getReply().apply { headers = headers + ("x-amz-replication-status" to listOf("PENDING")) }
        fixture.respond = { primaryReply }
        fixture.adapter().use { adapter ->
            val body = adapter.openVersion(getRequest())
            assertEquals("PENDING", body.metadata().replicationStatus)
            body.close()
        }
        val replicaReply = fixture.getReply(getRequest(replica)).apply { headers = headers + ("x-amz-replication-status" to listOf("COMPLETED")) }
        fixture.respond = { replicaReply }
        fixture.adapter().use { adapter -> reject(CatalogReadbackFailure.REPLICATION_MISMATCH) { adapter.openVersion(getRequest(replica)) } }
        assertReleased(primaryReply)
        assertReleased(replicaReply)
    }

    @Test
    fun `SDK HTTP error and redirect responses produce exactly one attempt with no automatic fallback or pagination`() {
        listOf(301, 307, 403, 500, 503).forEach { status ->
            val fixture = S3CatalogReadbackFixture()
            val reply = S3CatalogReply("<Error><Code>SlowDown</Code><Message>$PRIVATE_TEXT</Message></Error>".toByteArray()).apply {
                this.status = status
                headers = headers + ("Location" to listOf("https://synthetic-unapproved.invalid/"))
            }
            fixture.respond = { reply }
            fixture.adapter().use { adapter -> reject(CatalogReadbackFailure.PROVIDER_FAILURE) { adapter.listVersions(listRequest()) } }
            assertEquals(1, fixture.requests.size)
            assertReleased(reply)
        }
    }

    @Test
    fun `list decode rejects malformed deeply nested entity and over-element XML before SDK recursive traversal`() {
        val prefix = "<ListVersionsResult>"
        listOf(
            "<ListVersionsResult><broken></ListVersionsResult>",
            prefix + "<x>".repeat(32) + "</x>".repeat(32) + "</ListVersionsResult>",
            prefix + "<x/>".repeat(170) + "</ListVersionsResult>",
            prefix + "<!--token-->".repeat(1281) + "</ListVersionsResult>",
            "<!DOCTYPE root [<!ENTITY private SYSTEM 'file:///synthetic-must-not-open'>]><root>&private;</root>",
        ).forEach { xml ->
            val fixture = S3CatalogReadbackFixture()
            val reply = S3CatalogReply(xml.toByteArray())
            fixture.respond = { reply }
            fixture.adapter().use { adapter -> reject(CatalogReadbackFailure.PROVIDER_FAILURE) { adapter.listVersions(listRequest()) } }
            assertReleased(reply)
        }
    }

    @Test
    fun `body exact bytes and EOF are read once with explicit idempotent close and no implicit second request`() {
        val fixture = S3CatalogReadbackFixture()
        val reply = fixture.getReply().apply { chunkSize = 3 }
        fixture.respond = { reply }
        fixture.adapter().use { adapter ->
            val body = adapter.openVersion(getRequest())
            assertEquals(getRequest(), body.metadata().requestBinding)
            assertEquals(0, reply.reads)
            val content = ByteArray(reply.bytes.size)
            var offset = 0
            while (offset < content.size) offset += body.read(content, offset, content.size - offset)
            assertArrayEquals(reply.bytes, content)
            assertEquals(-1, body.read(ByteArray(1), 0, 1))
            assertEquals(-1, body.read(ByteArray(1), 0, 1))
            assertEquals(1, reply.eofProbes)
            reject(CatalogReadbackFailure.INVALID_READBACK) { adapter.openVersion(getRequest()) }
            body.close()
            body.close()
        }
        assertEquals(1, fixture.requests.size)
        assertReleased(reply)
    }

    @Test
    fun `short long zero-progress and failed GET reads close and abort before propagating a sanitized failure`() {
        listOf("short", "long", "zero", "throw").forEach { fault ->
            val fixture = S3CatalogReadbackFixture()
            val reply = fixture.getReply().apply {
                when (fault) {
                    "short" -> headers = headers + ("Content-Length" to listOf((bytes.size + 1).toString()))
                    "long" -> headers = headers + ("Content-Length" to listOf((bytes.size - 1).toString()))
                    "zero" -> chunkSize = 0
                    else -> beforeRead = { throw IOException(PRIVATE_TEXT) }
                }
            }
            fixture.respond = { reply }
            fixture.adapter().use { adapter ->
                val body = adapter.openVersion(getRequest())
                reject(CatalogReadbackFailure.PROVIDER_FAILURE) {
                    val destination = ByteArray(reply.bytes.size + 1)
                    repeat(3) { body.read(destination, 0, destination.size) }
                }
            }
            assertReleased(reply)
        }
    }

    @Test
    fun `connection-free rejection precedes factory dispatch and each body read without losing cleanup`() {
        val fixture = S3CatalogReadbackFixture()
        val reply = fixture.getReply()
        fixture.respond = { reply }
        fixture.adapter().use { adapter ->
            val body = adapter.openVersion(getRequest())
            TransactionSynchronizationManager.setActualTransactionActive(true)
            try {
                assertThrows(PersistencePhaseException::class.java) { fixture.adapter() }
                assertThrows(PersistencePhaseException::class.java) { adapter.listVersions(listRequest()) }
                assertThrows(PersistencePhaseException::class.java) { adapter.openVersion(getRequest()) }
                assertThrows(PersistencePhaseException::class.java) { body.read(ByteArray(1), 0, 1) }
                assertEquals(0, reply.reads)
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
        }
        assertEquals(2, fixture.createdClients)
        assertEquals(1, fixture.requests.size)
        assertReleased(reply)
    }

    @Test
    fun `cancellation and interruption preserve identity and flags through SDK wrapping and body cleanup`() {
        listOf(false to false, false to true, true to false, true to true).forEach { (duringRead, interrupt) ->
            val fixture = S3CatalogReadbackFixture()
            val reply = fixture.getReply()
            val fail = { if (interrupt) throw InterruptedException(PRIVATE_TEXT) else throw CancellationException(PRIVATE_TEXT) }
            if (duringRead) reply.beforeRead = fail else reply.beforeCall = fail
            fixture.respond = { reply }
            try {
                fixture.adapter().use { adapter ->
                    val action = {
                        adapter.openVersion(getRequest()).read(ByteArray(1), 0, 1)
                        Unit
                    }
                    if (interrupt) {
                        reject(CatalogReadbackFailure.INTERRUPTED, action)
                        assertTrue(Thread.currentThread().isInterrupted)
                    } else {
                        safe(assertThrows(CancellationException::class.java) { action() })
                    }
                }
            } finally {
                Thread.interrupted()
            }
            assertEquals(1, reply.aborts)
            assertEquals(if (duringRead) 1 else 0, reply.closes)
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun `finite elapsed checks cover returned streaming bodies without sleeps or claiming native cancellation completion`() {
        val fixture = S3CatalogReadbackFixture()
        val limits = S3CatalogReadbackLimits()
        val reply = fixture.getReply().apply { beforeRead = { fixture.now = (limits.requestTimeoutMillis + 1) * 1_000_000 } }
        fixture.respond = { reply }
        fixture.adapter(limits = limits).use { adapter ->
            val body = adapter.openVersion(getRequest())
            assertEquals(0, reply.reads)
            assertEquals(0L, fixture.now)
            reject(CatalogReadbackFailure.PROVIDER_FAILURE) { body.read(ByteArray(1), 0, 1) }
        }
        assertEquals(1, reply.reads)
        assertReleased(reply)
    }

    private fun assertReleased(reply: S3CatalogReply) {
        assertEquals(1, reply.calls)
        assertEquals(1, reply.aborts)
        assertEquals(1, reply.closes)
    }

    private fun reject(code: CatalogReadbackFailure, action: () -> Unit) {
        val failure = assertThrows(CatalogReadbackException::class.java) { action() }
        assertEquals(code, failure.code)
        safe(failure)
    }

    private fun safe(failure: Exception) {
        assertFalse(failure.message.orEmpty().contains(PRIVATE_TEXT))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private companion object {
        const val PRIVATE_TEXT = "synthetic-private-provider-text"
    }
}
