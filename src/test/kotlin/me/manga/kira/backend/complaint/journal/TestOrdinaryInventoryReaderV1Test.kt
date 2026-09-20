package me.manga.kira.backend.complaint.journal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReply
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainFixtureInputsV1
import me.manga.kira.backend.complaint.catalog.TestRunOrdinaryDrainFixtureV1
import me.manga.kira.backend.complaint.catalog.withOrdinaryDrainRun
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.encoded
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.xml
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.xmlReply
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.Arrays
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NOT_RUN test-only raw HTTP adapter, to be used with the genuine connected registered drain.
 * It adds all-prefix two-row LIST replies to the existing retained-object fixture. It cannot
 * create an original, denial, event, readback or SQL result, and GET still uses the SAME object's
 * real codec bytes and KMS fixture. Non-inventory requests (including seal traffic) are delegated.
 */
internal class TestOrdinaryInventoryHttpFixtureV1(private val provider: TestOwnerDeleteJournalPublisherFixture) : AutoCloseable {
    val prefix = provider.journal.ordinaryPrefix
    private val bucket = provider.journal.declaration().journalLocation.bucket
    private val delegate = provider.respond
    private val observed = mutableListOf<JournalPublisherHttpRequest>()
    private val interceptor: (JournalPublisherHttpRequest) -> S3CatalogReply = ::reply
    private var inventoryStarted = false
    val requests: List<JournalPublisherHttpRequest> get() = observed.toList()
    // A re-admitted paid cut legitimately starts at fresh versioned GET, with no new LIST.
    // This toggles the fixture's request filter only; it confers no native/original authority.
    var observeExactGetsWithoutPriorList = false
    var beforeRequest: (JournalPublisherHttpRequest) -> Unit = {}
    var transform: (JournalPublisherHttpRequest, S3CatalogReply) -> S3CatalogReply = { _, reply -> reply }
    // Stable key-only sorting deliberately does not sort opaque version IDs. Tests may arrange
    // several actual retained versions of the same key or a late/changed raw provider inventory.
    var listedObjects: () -> List<JournalPublisherObject> = { provider.objects.filter { it.key.startsWith(prefix) }.sortedBy { it.key } }

    init { provider.respond = interceptor }

    private fun reply(request: JournalPublisherHttpRequest): S3CatalogReply {
        val listing = request.kind == "LIST" && request.http.firstMatchingRawQueryParameter("prefix").orElse(null) == prefix
        val exactGet = (inventoryStarted || observeExactGetsWithoutPriorList) && request.kind == "GET" &&
            request.http.encodedPath().startsWith("/$bucket/$prefix")
        if (!listing && !exactGet) return delegate(request)
        inventoryStarted = true
        observed.add(request)
        requireConnectionFree()
        provider.assertSigned(request) // Independently recomputes SigV4, including BOTH raw marker values.
        assertTrue(request.body.isEmpty())
        beforeRequest(request)
        return transform(request, if (listing) listReply(request) else delegate(request))
    }

    private fun listReply(request: JournalPublisherHttpRequest): S3CatalogReply {
        val query = request.http.rawQueryParameters()
        assertEquals(listOf("2"), query["max-keys"])
        assertEquals(listOf("url"), query["encoding-type"])
        assertEquals(query.containsKey("key-marker"), query.containsKey("version-id-marker"))
        val keyMarker = query["key-marker"]?.single()
        val versionMarker = query["version-id-marker"]?.single()
        val objects = listedObjects()
        val offset = if (keyMarker == null) 0 else {
            val found = objects.indexOfFirst { it.key == keyMarker && it.version == versionMarker }
            assertTrue(found >= 0, "Continuation must name a real prior fixture version, not a made-up marker.")
            found + 1
        }
        val page = objects.drop(offset).take(2)
        val truncated = objects.size > offset + page.size
        return xmlReply(buildString {
            append("<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
            append("<Name>$bucket</Name><Prefix>${encoded(prefix)}</Prefix>")
            append("<KeyMarker>${keyMarker?.let(::encoded).orEmpty()}</KeyMarker>")
            append("<VersionIdMarker>${versionMarker?.let(::xml).orEmpty()}</VersionIdMarker>")
            append("<MaxKeys>2</MaxKeys><IsTruncated>$truncated</IsTruncated><EncodingType>url</EncodingType>")
            page.forEachIndexed { index, value ->
                val latest = objects.indexOfFirst { it.key == value.key } == offset + index
                append("<Version><Key>${encoded(value.key)}</Key><VersionId>${xml(value.version)}</VersionId><IsLatest>$latest</IsLatest>")
                append("<LastModified>${value.lastModified}</LastModified><Size>${value.bytes.size}</Size><StorageClass>STANDARD</StorageClass></Version>")
            }
            if (truncated) {
                append("<NextKeyMarker>${encoded(page.last().key)}</NextKeyMarker>")
                append("<NextVersionIdMarker>${xml(page.last().version)}</NextVersionIdMarker>")
            }
            append("</ListVersionsResult>")
        })
    }

    override fun close() {
        // This adapter owns only its installed reply callback, never a native client's cleanup.
        if (provider.respond === interceptor) provider.respond = delegate
    }
}

/**
 * NOT_COMPILED / NOT_RUN. Real registered AUTH/VERIFY/APPLY, the genuine drain original, owned
 * PostgreSQL TLS phases, pinned S3/KMS SDKs and actual TEST codec. Only raw provider replies and
 * explicit failure hooks are synthetic; none supplies a successful event, pass, cut or SQL result.
 * The denial statement is independently signed SYNTHETIC fixture evidence, not installed policy.
 *
 * Same-key multiversion positives deliberately stop AFTER paid WITNESS and BEFORE recovery. The
 * existing applied-family reducer admits one exact version per event ID; these cases do not claim
 * successful recovery/CONVERTED/sealing for several versions of that same event. The separate
 * accounting suite owns full closeout using four distinct retained-key aliases.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Suppress("LargeClass")
class TestOrdinaryInventoryReaderV1ConnectedIT {
    private val database = lazy {
        PgLifecycleDatabaseFixture(TestOrdinaryInventoryReaderV1ConnectedIT::class.java).also { it.start() }
    }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `actual ASCII two-pass pagination pays only the pair with opaque exact markers`() = pagination(unicode = false)

    @Test
    fun `actual UTF8 two-pass pagination pays only the pair with released native custody`() = pagination(unicode = true)

    private fun pagination(unicode: Boolean) = withFixture { f ->
        val versions = if (unicode) listOf("\uD800\uDC00+/%2F=&", "\uE000+/%25?") else listOf("z+/%2F=&", "a+/%25?")
        val objects = retainedVersions(f, versions)
        assertEquals(4, f.provider.journal.declaration().routing.keys.size)
        var inserts = 0
        var finishes = 0
        var stoppedBeforeRecovery = false
        f.probe.before = { call ->
            if (call.sql == TestOrdinaryDrainSqlV1.insertEntry) {
                assertNativeExchangesClosed(f)
                inserts++
            }
            if (call.sql == TestOrdinaryDrainSqlV1.finishRun) {
                f.provider.assertClientsClosed()
                finishes++
            }
            if (call.step === TestOrdinaryDrainStepV1.RECOVERY_PAGE) {
                stoppedBeforeRecovery = true
                throw IOException(PRIVATE_FAILURE)
            }
        }
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val original = f.begin()
            assertEquals(6, refused(f, original))
            f.probe.before = {}
            assertTrue(stoppedBeforeRecovery, "The controlled stop is after the real committed WITNESS, not a manufactured completion.")
            assertEquals(6, inserts); assertEquals(2, finishes)
            assertPaidPair(f, original, objects)
            assertEquals(listOf("LIST", "GET", "GET", "LIST", "GET").let { it + it }, native.requests.map { it.kind })
            val lists = native.requests.filter { it.kind == "LIST" }
            lists.forEachIndexed { index, request ->
                val query = request.http.rawQueryParameters()
                assertEquals(listOf(native.prefix), query["prefix"])
                if (index % 2 == 0) {
                    assertFalse(query.containsKey("key-marker")); assertFalse(query.containsKey("version-id-marker"))
                } else {
                    assertEquals(listOf(objects[1].key), query["key-marker"])
                    assertEquals(listOf(objects[1].version), query["version-id-marker"])
                }
            }
            assertEquals((objects + objects).map { it.version }, native.requests.filter { it.kind == "GET" }.map {
                it.http.firstMatchingRawQueryParameter("versionId").orElseThrow()
            })
            // Native inventory order is deliberately NOT SQL's deterministic unsigned UTF-8 fold order.
            val sorted = objects.sortedWith(versionOrder)
            assertFalse(objects.map { it.version } == sorted.map { it.version })
            assertEquals(PersistenceDatabaseOutcome.COMMITTED,
                f.probe.calls.first { it.step === TestOrdinaryDrainStepV1.WITNESS }.phase.databaseOutcome())
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK,
                f.probe.calls.first { it.step === TestOrdinaryDrainStepV1.RECOVERY_PAGE }.phase.databaseOutcome())
            f.probe.assertReleased(requireCommitted = false)
        }
    }

    @Test
    fun `R counts every retained version and refuses an over-budget page before its first GET`() = withFixture(
        TestOrdinaryDrainFixtureInputsV1(maximumRetainedVersions = 2),
    ) { f ->
        retainedVersions(f, listOf("z+/%2F=&", "a+/%25?"))
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            assertEquals(2, refused(f, f.begin()))
            assertEquals(listOf("LIST", "GET", "GET", "LIST"), native.requests.map { it.kind })
            assertUnwitnessed(f, listOf(2))
            assertEquals(2, f.probe.calls.count { it.sql == TestOrdinaryDrainSqlV1.insertEntry })
            f.probe.assertReleased()
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [1, 1024])
    fun `signed pre-D B is LP32 framing and never the ciphertext byte bound`(bound: Long) = withFixture(
        TestOrdinaryDrainFixtureInputsV1(maximumFramedBytes = bound),
    ) { f ->
        val objects = f.provider.objects.toList()
        assertEquals(1, objects.size)
        assertTrue(objects.single().bytes.size > 1024, "The preselected positive B must really be smaller than this native envelope.")
        var stoppedBeforeRecovery = false
        f.probe.before = { call ->
            if (bound == 1024L && call.step === TestOrdinaryDrainStepV1.RECOVERY_PAGE) {
                stoppedBeforeRecovery = true
                throw IOException(PRIVATE_FAILURE)
            }
        }
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val original = f.begin()
            assertEquals(if (bound == 1L) 1 else 2, refused(f, original))
            f.probe.before = {}
            val framed = manifest(f, original, objects).second
            assertTrue(framed in 2L..1024L)
            if (bound == 1L) {
                assertEquals(listOf("LIST", "GET"), native.requests.map { it.kind })
                assertUnwitnessed(f, listOf(0))
                val inserts = f.probe.calls.filter { it.sql == TestOrdinaryDrainSqlV1.insertEntry }
                assertEquals(1, inserts.size, "A real native read reached SQL; its INSERT and over-B run update roll back together.")
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, inserts.single().phase.databaseOutcome())
            } else {
                assertTrue(stoppedBeforeRecovery)
                assertEquals(listOf("LIST", "GET", "LIST", "GET"), native.requests.map { it.kind })
                assertPaidPair(f, original, objects)
            }
            f.probe.assertReleased(requireCommitted = false)
        }
    }

    @Test
    fun `nonadjacent opaque pair repeat is rejected by the actual staging primary key without deduplication`() = withFixture { f ->
        val objects = retainedVersions(f, listOf("z+/%2F=&", "a+/%25?"))
        var listCalls = 0
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            native.listedObjects = {
                if (++listCalls == 1) objects else listOf(objects[0], objects[1], objects[0], objects[2])
            }
            assertEquals(3, refused(f, f.begin()))
            assertEquals(2, listCalls)
            assertEquals(listOf("LIST", "GET", "GET", "LIST", "GET"), native.requests.map { it.kind })
            assertEquals(listOf(objects[0].version, objects[1].version, objects[0].version), native.requests.filter { it.kind == "GET" }.map {
                it.http.firstMatchingRawQueryParameter("versionId").orElseThrow()
            })
            assertUnwitnessed(f, listOf(2))
            val inserts = f.probe.calls.filter { it.sql == TestOrdinaryDrainSqlV1.insertEntry }
            assertEquals(3, inserts.size)
            assertEquals(listOf(PersistenceDatabaseOutcome.COMMITTED, PersistenceDatabaseOutcome.COMMITTED, PersistenceDatabaseOutcome.ROLLED_BACK),
                inserts.map { it.phase.databaseOutcome() })
            f.probe.assertReleased(requireCommitted = false)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["writer", "future-epoch", "routing-key", "terminal-family", "unknown-family"])
    fun `whole ordinary prefix refuses hostile listed keys rather than filtering them to absence`(fault: String) = withFixture { f ->
        val actual = f.provider.objects.single()
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val original = f.begin()
            native.listedObjects = {
                val writer = f.provider.journal.declaration().writer.generationId
                val root = "${native.prefix}writer/$writer/epoch/"
                val fields = actual.key.removePrefix(root).split('/')
                val hostile = when (fault) {
                    "writer" -> actual.key.replace("${native.prefix}writer/$writer/", "${native.prefix}writer/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/")
                    "future-epoch" -> root + (original.cutoff + 1).toString().padStart(19, '0') + "/${fields[1]}/${fields[2]}"
                    "routing-key" -> "$root${fields[0]}/unknown-retained-key/${fields[2]}"
                    "terminal-family" -> "${native.prefix}terminal/${fields[2]}"
                    "unknown-family" -> "${native.prefix}unrecognized/${fields[2]}"
                    else -> error("Unknown fixture selector.")
                }
                assertTrue(hostile.startsWith(native.prefix)); assertFalse(hostile == actual.key)
                listOf(actual.copy(key = hostile)) // Raw listing only; no successful event exists at this key.
            }
            assertEquals(0, refused(f, original))
            assertEquals(listOf("LIST"), native.requests.map { it.kind })
            assertUnwitnessed(f, listOf(0))
            assertTrue(f.probe.calls.none { it.step === TestOrdinaryDrainStepV1.APPEND })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-key-marker", "missing-version-marker", "wrong-next-version", "wrong-echo", "no-progress", "ambiguous-end", "delete-marker", "oversized-claim", "oversized-body"])
    fun `real signed LIST rejects missing mismatched looping and oversized raw pagination before an untrusted GET`(fault: String) = withFixture { f ->
        val objects = retainedVersions(f, listOf("z+/%2F=&", "a+/%25?"))
        val secondPage = fault in setOf("wrong-echo", "no-progress")
        var changed = 0
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            native.transform = { request, reply ->
                if (request.kind != "LIST" || request.http.rawQueryParameters().containsKey("key-marker") != secondPage) reply else {
                    changed++
                    when (fault) {
                        "missing-key-marker" -> changeXml(reply) { it.replace(Regex("<NextKeyMarker>[^<]*</NextKeyMarker>"), "") }
                        "missing-version-marker" -> changeXml(reply) { it.replace(Regex("<NextVersionIdMarker>[^<]*</NextVersionIdMarker>"), "") }
                        "wrong-next-version" -> changeXml(reply) { it.replace(Regex("<NextVersionIdMarker>[^<]*</NextVersionIdMarker>"), "<NextVersionIdMarker>not-the-last-version</NextVersionIdMarker>") }
                        "wrong-echo" -> changeXml(reply) { it.replace(Regex("<VersionIdMarker>[^<]*</VersionIdMarker>"), "<VersionIdMarker>not-the-request-marker</VersionIdMarker>") }
                        "no-progress" -> changeXml(reply) { it.replace("<VersionId>${xml(objects[2].version)}</VersionId>", "<VersionId>${xml(objects[1].version)}</VersionId>") }
                        "ambiguous-end" -> changeXml(reply) { it.replace("<IsTruncated>true</IsTruncated>", "<IsTruncated></IsTruncated>") }
                        "delete-marker" -> changeXml(reply) { it.replace("</ListVersionsResult>", "<DeleteMarker><Key>${encoded(objects[0].key)}</Key><VersionId>deleted</VersionId></DeleteMarker></ListVersionsResult>") }
                        "oversized-claim", "oversized-body" -> S3CatalogReply(ByteArray(16 * 1024 + 1) { ' '.code.toByte() }).apply {
                            if (fault == "oversized-body") headers = mapOf("Content-Length" to listOf((16 * 1024).toString()))
                        }
                        else -> error("Unknown fixture selector.")
                    }
                }
            }
            assertEquals(if (secondPage) 2 else 0, refused(f, f.begin()))
            assertEquals(1, changed)
            assertEquals(if (secondPage) listOf("LIST", "GET", "GET", "LIST") else listOf("LIST"), native.requests.map { it.kind })
            assertUnwitnessed(f, listOf(if (secondPage) 2 else 0))
            if (fault == "oversized-claim") assertEquals(0, checkNotNull(native.requests.last().reply).reads)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["listed-size", "listed-time", "version", "checksum", "checksum-type", "metadata", "event-id", "governance", "expired-retention", "content-type", "short-length", "long-length", "missing-body", "extra-body", "zero-progress", "redirect"])
    fun `actual versioned GET binds LIST facts bytes metadata checksum and retention before staging`(fault: String) = withFixture { f ->
        val value = f.provider.objects.single()
        var changed = 0
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            native.transform = { request, reply ->
                val listingFault = fault in setOf("listed-size", "listed-time")
                if (request.kind != (if (listingFault) "LIST" else "GET")) reply else {
                    changed++
                    when (fault) {
                        "listed-size" -> changeXml(reply) { it.replace("<Size>${value.bytes.size}</Size>", "<Size>${value.bytes.size + 1}</Size>") }
                        "listed-time" -> changeXml(reply) { it.replace("<LastModified>${value.lastModified}</LastModified>", "<LastModified>${value.lastModified.minusSeconds(1)}</LastModified>") }
                        "version" -> reply.header("x-amz-version-id", "different+/%2F=&")
                        "checksum" -> reply.header("x-amz-checksum-sha256", Base64.getEncoder().encodeToString(ByteArray(32)))
                        "checksum-type" -> reply.header("x-amz-checksum-type", "COMPOSITE")
                        "metadata" -> reply.header("x-amz-meta-unrecognized", PRIVATE_FAILURE)
                        "event-id" -> reply.header("x-amz-meta-kira-journal-event-id", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)))
                        "governance" -> reply.header("x-amz-object-lock-mode", "GOVERNANCE")
                        "expired-retention" -> reply.header("x-amz-object-lock-retain-until-date", Instant.EPOCH.toString())
                        "content-type" -> reply.header("Content-Type", "text/plain")
                        "short-length" -> reply.header("Content-Length", (value.bytes.size - 1).toString())
                        "long-length" -> reply.header("Content-Length", (value.bytes.size + 1).toString())
                        "missing-body" -> reply.apply { bodyPresent = false }
                        "extra-body" -> S3CatalogReply(reply.bytes + 0.toByte()).apply { headers = reply.headers }
                        "zero-progress" -> reply.apply { chunkSize = 0 }
                        "redirect" -> reply.apply { status = 307 }.header("Location", "https://not-followed.invalid/private-fixture")
                        else -> error("Unknown fixture selector.")
                    }
                }
            }
            assertEquals(if (fault == "event-id") 1 else 0, refused(f, f.begin()))
            assertEquals(1, changed)
            assertEquals(listOf("LIST", "GET"), native.requests.map { it.kind })
            assertUnwitnessed(f, listOf(0))
            assertTrue(f.probe.calls.none { it.step === TestOrdinaryDrainStepV1.APPEND })
        }
    }

    @Test
    fun `same-count replacement across actually completed inventories cannot acquire a paid cut`() = changedInventory("replacement")

    @Test
    fun `a late retained version across actually completed inventories cannot acquire a paid cut`() = changedInventory("late-version")

    private fun changedInventory(change: String) = withFixture { f ->
        val objects = retainedVersions(f, listOf("late+/%25?"))
        var lists = 0
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            native.listedObjects = { if (++lists == 1) listOf(objects[0]) else if (change == "replacement") listOf(objects[1]) else objects }
            assertEquals(if (change == "replacement") 2 else 3, refused(f, f.begin()))
            assertEquals(2, lists)
            assertUnwitnessed(f, if (change == "replacement") listOf(1, 1) else listOf(1, 2), listOf("COMPLETE", "COMPLETE"))
            val pair = scans(f)
            assertFalse(pair[0].root == pair[1].root)
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK,
                f.probe.calls.first { it.step === TestOrdinaryDrainStepV1.WITNESS }.phase.databaseOutcome())
            f.probe.assertReleased(requireCommitted = false)
        }
    }

    @Test
    fun `actual S3 client close failure prevents native pass completion authority`() = cleanupFailure("s3-client-close")

    @ParameterizedTest
    @ValueSource(strings = ["list-close", "list-abort", "get-close", "get-abort", "kms-body-close", "kms-abort"])
    fun `native body or abort cleanup failure remains refusal before staging authority`(fault: String) = cleanupFailure(fault)

    private fun cleanupFailure(fault: String) = withFixture { f ->
        val previousClientClose = f.provider.onClientClose
        val previousKmsReply = f.provider.kms.respond
        var failures = 0
        fun fail(): Nothing { failures++; throw IOException(PRIVATE_FAILURE) }
        if (fault == "s3-client-close") f.provider.onClientClose = { previousClientClose(); fail() }
        if (fault.startsWith("kms-")) f.provider.kms.respond = { request ->
            previousKmsReply(request).apply {
                assertEquals(AwsJournalKmsFixture.DECRYPT_TARGET, request.target())
                if (fault == "kms-body-close") onClose = { fail() } else onAbort = { fail() }
            }
        }
        try {
            TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
                native.transform = { request, reply ->
                    reply.apply {
                        if (fault.startsWith("list-") && request.kind == "LIST" || fault.startsWith("get-") && request.kind == "GET") {
                            if (fault.endsWith("close")) onClose = { fail() } else onAbort = { fail() }
                        }
                    }
                }
                assertEquals(if (fault == "s3-client-close" || fault.startsWith("kms-")) 1 else 0, refused(f, f.begin()))
                assertEquals(1, failures, "The actual failing native close/abort is issued once, not retried into success.")
                assertEquals(if (fault.startsWith("list-")) listOf("LIST") else listOf("LIST", "GET"), native.requests.map { it.kind })
                assertUnwitnessed(f, listOf(if (fault == "s3-client-close") 1 else 0))
                assertTrue(f.probe.calls.none { it.step === TestOrdinaryDrainStepV1.COMPLETE_PASS })
                if (fault != "s3-client-close") assertTrue(f.probe.calls.none { it.step === TestOrdinaryDrainStepV1.APPEND })
            }
        } finally {
            f.provider.onClientClose = previousClientClose
            f.provider.kms.respond = previousKmsReply
        }
    }

    @Test
    fun `reentrant close while the actual S3 factory opens retains and closes its late returned client`() = withFixture { f ->
        val original = f.begin()
        val beforeS3 = f.provider.s3ClientsCreated
        val beforeKms = f.provider.kms.createdClients
        val previousOpen = f.provider.beforeOpen
        var interleavings = 0
        f.provider.beforeOpen = {
            previousOpen()
            interleavings++
            val reader = ownedCutField(original, "native") as TestOrdinaryInventoryReaderV1
            assertEquals(JournalPublicationFailureV1.CLEANUP_FAILURE, assertThrows<JournalPublicationExceptionV1> { reader.close() }.code)
        }
        try {
            TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
                assertEquals(0, refused(f, original))
                assertEquals(1, interleavings)
                assertTrue(native.requests.isEmpty())
                assertEquals(beforeS3 + 1, f.provider.s3ClientsCreated)
                assertEquals(beforeS3 + 1, f.provider.s3ClientsClosed)
                assertEquals(beforeKms + 1, f.provider.kms.createdClients)
                assertEquals(beforeKms + 1, f.provider.kms.returnedClientCloses)
                assertUnwitnessed(f, listOf(0))
            }
        } finally { f.provider.beforeOpen = previousOpen }
        // This deterministic construction/close interleave is NOT a claim of multithread race coverage.
    }

    @ParameterizedTest
    @ValueSource(strings = ["regression", "expiry"])
    fun `native monotonic regression or expiry stays failed after clock recovery and original consumption`(fault: String) = withFixture { f ->
        var nanos = 0L
        val allowance = f.provider.journal.declaration().limits.deadlines.scanMillis * 1_000_000L
        val original = f.begin(nanoTime = { nanos })
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            native.transform = { _, reply -> reply.apply { beforeRead = { nanos = if (fault == "regression") -1 else allowance + 1 } } }
            assertEquals(0, refused(f, original))
            assertEquals(listOf("LIST"), native.requests.map { it.kind })
            assertUnwitnessed(f, listOf(0))
            nanos = 0
            val before = invocationCounts(f)
            val reader = ownedCutField(original, "native") as TestOrdinaryInventoryReaderV1
            assertEquals(JournalPublicationFailureV1.DEADLINE_EXHAUSTED, assertThrows<JournalPublicationExceptionV1> { reader.close() }.code)
            assertEquals(before, invocationCounts(f))
        }
    }

    @Test
    fun `the second pass does not renew the original native scan deadline`() = withFixture { f ->
        var nanos = 0L
        val allowance = f.provider.journal.declaration().limits.deadlines.scanMillis * 1_000_000L
        val firstPassEnds = allowance - 250_000_000L
        assertTrue(f.provider.journal.declaration().limits.deadlines.s3CallMillis > 250)
        val previousClose = f.provider.onClientClose
        var advanced = false
        var lists = 0
        f.provider.onClientClose = {
            previousClose()
            if (!advanced) { advanced = true; nanos = firstPassEnds }
        }
        try {
            TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
                native.transform = { request, reply ->
                    if (request.kind == "LIST" && ++lists == 2) reply.beforeRead = { nanos = allowance + 1 }
                    reply
                }
                assertEquals(1, refused(f, f.begin(nanoTime = { nanos })))
                assertTrue(advanced); assertEquals(2, lists)
                assertTrue(allowance + 1 - firstPassEnds < f.provider.journal.declaration().limits.deadlines.s3CallMillis * 1_000_000L)
                assertEquals(listOf("LIST", "GET", "LIST"), native.requests.map { it.kind })
                assertUnwitnessed(f, listOf(1, 0), listOf("COMPLETE", "SCANNING"))
                assertEquals(1, f.probe.calls.count { it.sql == TestOrdinaryDrainSqlV1.finishRun })
            }
        } finally { f.provider.onClientClose = previousClose }
        // The injected clock covers the native reader/call/codec clocks. The root's separate
        // PersistenceTimeBudget uses the actual coordinator ownership clock and is not faked here.
    }

    @ParameterizedTest
    @ValueSource(strings = ["OWNER_DELETE_ALL", "EPOCH_SEAL"])
    fun `authenticated unsupported payload families never become a staged ordinary event`(kind: String) = withFixture { f ->
        val wire = authenticatedUnsupportedPayload(f, kind)
        try {
            f.provider.stored = f.provider.objectFor(wire)
        } finally { wire.fill(0) }
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            assertEquals(1, refused(f, f.begin()), "The same wrapped key is really unwrapped before closed payload validation rejects the authenticated bytes.")
            assertEquals(listOf("LIST", "GET"), native.requests.map { it.kind })
            assertUnwitnessed(f, listOf(0))
            assertTrue(f.probe.calls.none { it.step === TestOrdinaryDrainStepV1.APPEND })
        }
    }

    private fun withFixture(
        inputs: TestOrdinaryDrainFixtureInputsV1 = TestOrdinaryDrainFixtureInputsV1(),
        action: (TestRunOrdinaryDrainFixtureV1) -> Unit,
    ) = VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use { tls ->
        tls.bind()
        withOrdinaryDrainRun(tls, inputs = inputs, expireClosedSetupPredecessors = true, action = action)
    }

    private fun retainedVersions(f: TestRunOrdinaryDrainFixtureV1, versions: List<String>): List<JournalPublisherObject> {
        val primary = f.provider.objects.single()
        versions.forEach { f.provider.objects.add(primary.copy(version = it)) }
        return f.provider.objects.toList().also { assertEquals(it.size, it.map { value -> value.key to value.version }.toSet().size) }
    }

    /** Exact generic boundary, no ordinary PUT/key generation/seal and no reuse of a consumed original. */
    private fun refused(f: TestRunOrdinaryDrainFixtureV1, original: TestRunOrdinaryDrainV1): Int {
        val generated = f.provider.generated()
        val decrypted = f.provider.decrypted()
        val puts = f.provider.requests.count { it.kind == "PUT" }
        val seal = f.sealHttp.requests.size to f.sealHttp.order.size
        val approval = f.approval(original)
        val failure = assertThrows<TestOrdinaryDrainExceptionV1> {
            original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials)
        }
        assertEquals("TEST ordinary drain refused.", failure.message)
        assertNull(failure.cause); assertTrue(failure.suppressed.isEmpty()); assertTrue(failure.stackTrace.isEmpty())
        assertFalse(failure.toString().contains(PRIVATE_FAILURE))
        f.assertReleased()
        assertEquals(generated, f.provider.generated())
        assertEquals(puts, f.provider.requests.count { it.kind == "PUT" })
        assertEquals(seal, f.sealHttp.requests.size to f.sealHttp.order.size)
        val used = invocationCounts(f)
        assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials) }
        assertEquals(used, invocationCounts(f), "A failed original cannot restart any SQL, native read or successor call.")
        return f.provider.decrypted() - decrypted
    }

    private fun invocationCounts(f: TestRunOrdinaryDrainFixtureV1): List<Int> = listOf(
        f.provider.requests.size, f.provider.kms.requests.size, f.probe.calls.size, f.jdbc.calls.size, f.sealHttp.requests.size, f.sealHttp.order.size,
    )

    private fun assertNativeExchangesClosed(f: TestRunOrdinaryDrainFixtureV1) {
        f.provider.assertS3ExchangesClosed()
        f.provider.kms.replies.forEach { reply ->
            assertEquals(1, reply.calls); assertEquals(1, reply.aborts)
            assertEquals(if (reply.bodyPresent) 1 else 0, reply.closes)
        }
        // SQL callback already owns its real single holder. Do not run another JdbcTemplate here:
        // Spring could bind a second datasource. These assertions observe native custody only.
    }

    private data class Scan(val pass: Int, val state: String, val count: Long, val framedBytes: Long, val root: String?)

    private fun scans(f: TestRunOrdinaryDrainFixtureV1): List<Scan> {
        requireConnectionFree()
        return f.observer.query("SELECT pass, state, entry_count, entry_bytes, encode(manifest_hash, 'hex') AS root " +
            "FROM complaint_journal_scan_runs WHERE data_scope_id = ? ORDER BY pass", { row, _ ->
            Scan(row.getInt("pass"), row.getString("state"), row.getLong("entry_count"), row.getLong("entry_bytes"), row.getString("root"))
        }, f.scope)
    }

    private fun assertUnwitnessed(f: TestRunOrdinaryDrainFixtureV1, counts: List<Int>, states: List<String> = List(counts.size) { "SCANNING" }) {
        val runs = scans(f)
        assertEquals((1..counts.size).toList(), runs.map { it.pass })
        assertEquals(counts.map(Int::toLong), runs.map { it.count }); assertEquals(states, runs.map { it.state })
        assertEquals(counts.sum().toLong(), f.observer.queryForObject("SELECT count(*) FROM complaint_journal_scan_entries WHERE data_scope_id = ?", Long::class.java, f.scope))
        assertNull(f.observer.queryForObject("SELECT permanent_denial_bytes FROM complaint_test_runs WHERE data_scope_id = ?", ByteArray::class.java, f.scope))
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_test_terminal_intents WHERE data_scope_id = ?", Long::class.java, f.scope))
        assertTrue(f.probe.calls.none { it.step in setOf(TestOrdinaryDrainStepV1.RECOVERY_PAGE, TestOrdinaryDrainStepV1.CONVERT, TestOrdinaryDrainStepV1.RECYCLE, TestOrdinaryDrainStepV1.SEAL) })
        assertTrue(f.sealHttp.requests.isEmpty()); assertTrue(f.sealHttp.order.isEmpty())
    }

    private fun assertPaidPair(f: TestRunOrdinaryDrainFixtureV1, original: TestRunOrdinaryDrainV1, objects: List<JournalPublisherObject>) {
        val expected = manifest(f, original, objects)
        val runs = scans(f)
        assertEquals(listOf(1, 2), runs.map { it.pass }); assertEquals(listOf("COMPLETE", "COMPLETE"), runs.map { it.state })
        assertEquals(listOf(objects.size.toLong(), objects.size.toLong()), runs.map { it.count })
        runs.forEach { assertEquals(expected.first, it.root); assertEquals(expected.second, it.framedBytes) }
        assertEquals(objects.size * 2L, f.observer.queryForObject("SELECT count(*) FROM complaint_journal_scan_entries WHERE data_scope_id = ? AND replay_state = 'PENDING'", Long::class.java, f.scope))
        val bytes = f.observer.queryForObject("SELECT permanent_denial_bytes FROM complaint_test_runs WHERE data_scope_id = ?", ByteArray::class.java, f.scope)
        assertNotNull(bytes)
        val progress = TestTerminalJsonV1(f.provider.journal).progress(checkNotNull(bytes))
        assertTrue(progress.installationReads().isEmpty())
        val cut = progress.completedCuts().single()
        assertEquals(expected.second, cut.framedByteCount)
        listOf(cut.denial.firstInventory, cut.denial.secondInventory).forEach {
            assertEquals(objects.size.toLong(), it.versionCount)
            assertEquals(objects.sumOf { value -> value.bytes.size.toLong() }, it.byteCount)
            assertEquals(expected.first, it.sha256)
        }
        assertTrue(cut.denial.secondInventory.startedAtEpochSecond - cut.denial.firstInventory.completedAtEpochSecond >= cut.denial.acceptedRequestBoundSeconds)
        assertTrue(f.probe.calls.any { it.sql == TestOrdinaryDrainSqlV1.spendAndProgress })
        assertTrue(f.probe.calls.none { it.step in setOf(TestOrdinaryDrainStepV1.CONVERT, TestOrdinaryDrainStepV1.RECYCLE, TestOrdinaryDrainStepV1.SEAL) })
        assertTrue(f.sealHttp.requests.isEmpty()); assertTrue(f.sealHttp.order.isEmpty())
    }

    /** Independent literal LP32 framing and unsigned UTF-8 version order; no product fold helper. */
    private fun manifest(f: TestRunOrdinaryDrainFixtureV1, original: TestRunOrdinaryDrainV1, objects: List<JournalPublisherObject>): Pair<String, Long> {
        val fields = listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest", original.writer, f.provider.journal.ordinaryPrefix,
            "TEST", f.scope.toString(), "1", original.cutoff.toString(), objects.size.toString()) +
            objects.sortedWith(versionOrder).flatMap { listOf(it.key, it.version, digest(it.bytes)) }
        val bytes = frame(fields)
        return try { digest(bytes) to bytes.size.toLong() } finally { bytes.fill(0) }
    }

    private fun changeXml(reply: S3CatalogReply, change: (String) -> String): S3CatalogReply {
        val original = reply.bytes.toString(Charsets.UTF_8)
        val changed = change(original)
        assertFalse(original == changed, "The selected raw XML fault must actually be installed.")
        return xmlReply(changed)
    }

    private fun S3CatalogReply.header(name: String, value: String): S3CatalogReply = apply { headers = headers + (name to listOf(value)) }

    /**
     * Deliberately hostile TEST bytes under the SAME synthetic known key/wrapped-key/header.
     * JCE builds a tag-valid replacement payload; the real native GET/KMS/codec must still reject it.
     * Reusing this known test nonce/key is not a production publication recipe or an event factory.
     */
    private fun authenticatedUnsupportedPayload(f: TestRunOrdinaryDrainFixtureV1, kind: String): ByteArray {
        val owned = mutableListOf<ByteArray>()
        fun keep(value: ByteArray): ByteArray = value.also(owned::add)
        try {
            val original = f.provider.objects.single().bytes
            val input = ByteBuffer.wrap(original)
            assertEquals(0x4b4a4556, input.int); assertEquals(1, input.int)
            fun section(): ByteArray = keep(ByteArray(input.int).also { input.get(it) })
            val headerBytes = section(); val wrapped = section(); section()
            assertFalse(input.hasRemaining())
            val header = Json.parseToJsonElement(headerBytes.toString(Charsets.UTF_8)).jsonObject
            val payload = Json.parseToJsonElement(keep(f.provider.event.canonicalBytes()).toString(Charsets.UTF_8)).jsonObject
            assertEquals("OWNER_DELETE", payload.getValue("eventKind").jsonPrimitive.content)
            val changed = keep(JsonObject((payload + ("eventKind" to JsonPrimitive(kind))).toSortedMap()).toString().toByteArray(Charsets.UTF_8))
            val wrappedBase64 = Base64.getEncoder().encodeToString(wrapped)
            val keyReply = f.provider.kms.requests.zip(f.provider.kms.replies).filter { it.first.target() == AwsJournalKmsFixture.GENERATE_TARGET }
                .map { Json.parseToJsonElement(it.second.bytes.toString(Charsets.UTF_8)).jsonObject }
                .single { it.getValue("CiphertextBlob").jsonPrimitive.content == wrappedBase64 }
            val key = keep(Base64.getDecoder().decode(keyReply.getValue("Plaintext").jsonPrimitive.content))
            val nonce = keep(Base64.getUrlDecoder().decode(header.getValue("nonce").jsonPrimitive.content))
            assertEquals(32, key.size); assertEquals(12, nonce.size)
            val aad = keep(frame(listOf("kira-complaint-journal-aad-v1", "1", "KJEV", "1", headerBytes.size.toString()) +
                HEADER_ORDER.map { header.getValue(it).jsonPrimitive.content } +
                listOf(wrapped.size.toString(), Base64.getUrlEncoder().withoutPadding().encodeToString(wrapped), (changed.size + 16).toString())))
            fun cipher(mode: Int) = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad)
            }
            val ciphertext = keep(cipher(Cipher.ENCRYPT_MODE).doFinal(changed))
            assertArrayEquals(changed, keep(cipher(Cipher.DECRYPT_MODE).doFinal(ciphertext)))
            return ByteBuffer.allocate(20 + headerBytes.size + wrapped.size + ciphertext.size).apply {
                putInt(0x4b4a4556).putInt(1)
                listOf(headerBytes, wrapped, ciphertext).forEach { putInt(it.size); put(it) }
            }.array()
        } finally { owned.forEach { it.fill(0) } }
    }

    private fun frame(fields: List<String>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output -> fields.forEach {
            val value = it.toByteArray(Charsets.UTF_8)
            try { output.writeInt(value.size); output.write(value) } finally { value.fill(0) }
        } }
        bytes.toByteArray()
    }

    private fun digest(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private companion object {
        const val PRIVATE_FAILURE = "synthetic-private-native-inventory-failure"
        val versionOrder = Comparator<JournalPublisherObject> { left, right ->
            val key = left.key.compareTo(right.key)
            if (key != 0) key else Arrays.compareUnsigned(left.version.toByteArray(Charsets.UTF_8), right.version.toByteArray(Charsets.UTF_8))
        }
        val HEADER_ORDER = listOf("envelopeSchemaVersion", "payloadSchemaVersion", "canonicalizerId", "objectKind", "encryptionAlgorithm", "dataKeyMode",
            "kmsKeyId", "kmsKeyArn", "bucket", "objectKey", "writerGeneration", "ordinaryPrefix", "dataScopeKind", "dataScopeId",
            "publicationEpoch", "routingKeyId", "eventId", "nonce")
    }
}
