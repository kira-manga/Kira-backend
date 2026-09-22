package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointDeletionInputV1
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import me.manga.kira.backend.security.aws.JournalKmsHttpRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference

/**
 * Only existing native HTTP SPIs are substituted. Expected ALL bytes are restored from the real
 * HTTP AUTH commit, never supplied as work, verification, completion or a replacement owner.
 * B serves that actual PUT/version and delegates Decrypt to the original raw KMS key-map owner.
 */
internal class TestRegisteredOwnerDeleteAllHttpRawFixtureV1(shortFreshness: Boolean) {
    val ordinary = TestActiveOrdinaryRawFixtureV1()
    val checkpoint = TestActiveInitialCheckpointRawFixtureV1()
    val queue = TestActiveOwnerDeleteQueueRawFixtureV1()
    @Volatile var fixture: TestRegisteredOwnerDeleteAllHttpFixtureV1? = null
    @Volatile var expected: RegisteredOwnerDeleteAllHttpRequestV1? = null
    @Volatile var publisher: TestOwnerDeleteJournalPublisherFixture? = null
        private set
    var failReadback = false
    private var keyReply: ((JournalKmsHttpRequest) -> JournalKmsHttpReply)? = null
    private var keyContext: Map<String, String>? = null
    private val assertion = AtomicReference<AssertionError?>()
    val factories = TestActiveOrdinaryRawHttpV1(ordinary.factories.sts,
        { remaining -> native(remaining) { selected().kms.httpClient() } },
        { remaining -> native(remaining) { selected().httpClient() } }, checkpoint.input,
        initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.PROFILE),
        shortInitialCheckpointFreshness = shortFreshness, activeOwnerDeleteQueue = queue.input,
        initialCheckpointDeletion = TestInitialCheckpointDeletionInputV1(1, VersionBoundTestInitialCheckpointDeletionV1.PROFILE))

    private fun selected(): TestOwnerDeleteJournalPublisherFixture {
        publisher?.let { return it }
        val f = checkNotNull(fixture); val input = checkNotNull(expected)
        f.assertSqlReleased(); f.assertPending(input, verified = false)
        assertEquals(1L, f.first.process.publicationLanes.activeOwners().totalOwners)
        val row = f.publication(input)
        val event = TestOwnerDeleteJournalCodecV1.restoreCanonical(f.first.process.consumers.journalRouting,
            row.getValue("event_bytes") as ByteArray, row.getValue("routing_key_id") as String)
        assertEquals(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, event.tuple.eventKind)
        assertEquals(input.installation.id, event.tuple.actorId); assertEquals(input.installation.scope, event.tuple.scope)
        assertEquals(input.credentialVersion, event.tuple.credentialVersion); assertEquals(input.key, event.tuple.operationKey)
        assertEquals(input.targets.sortedBy { it.toString() }, event.complaintIds()); assertEquals(2L, event.tuple.epoch)
        assertArrayEquals(row.getValue("event_bytes") as ByteArray, event.canonicalBytes())
        val p = TestOwnerDeleteJournalPublisherFixture(f.first.process.consumers.journalRouting, event)
        publisher = p
        p.beforePrepare = { checked { f.assertSqlReleased(); p.wall = f.first.native.now() } }
        p.onClientClose = { checked { f.assertSqlReleased() } }; p.kms.onClientClose = { checked { f.assertSqlReleased() } }
        val reply = p.kms.respond.also { keyReply = it }
        p.kms.respond = { request -> checked {
            f.assertSqlReleased(); p.assertKmsContext(request, event)
            val credentials = TestActiveFirstCutInputFixtureV1.ordinaryCredentials
            assertEquals(credentials.sessionToken(), request.http.firstMatchingHeader("x-amz-security-token").orElseThrow())
            assertTrue(request.http.firstMatchingHeader("Authorization").orElseThrow().startsWith("AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId()}/"))
            if (request.target() == AwsJournalKmsFixture.GENERATE_TARGET) {
                assertNull(keyContext)
                keyContext = request.fields()["EncryptionContext"].fields().asSequence().associate { it.key to it.value.textValue() }
            }
            reply(request)
        } }
        p.respond = { request -> checked {
            f.assertSqlReleased()
            val location = p.journal.declaration().journalLocation
            journalPublisherRawAssertSigned(request, location.region, location.accountId, TestActiveFirstCutInputFixtureV1.ordinaryCredentials)
            if (failReadback && request.kind == "GET") OwnerDeleteAllJournalPublisherFixture.errorReply(503) else p.statefulReply(request)
        } }
        return p
    }

    private fun native(remaining: () -> Int, create: () -> SdkHttpClient): SdkHttpClient = checked {
        checkNotNull(fixture).assertSqlReleased(); val client = create()
        object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = checked {
                checkNotNull(fixture).assertSqlReleased(); assertTrue(remaining() in 1..5_000); client.prepareRequest(request)
            }
            override fun close() = client.close()
            override fun clientName() = "SyntheticRegisteredOwnerDeleteAllHttp"
        }
    }

    fun record(): TestRegisteredInitialDeletionNativeRecordV1 {
        assertDisposed(); val p = checkNotNull(publisher); val stored = p.objects.single()
        val publication = checkNotNull(fixture).publication(checkNotNull(expected))
        assertEquals("VERIFIED", publication["state"]); assertEquals(stored.version, publication["object_version"])
        assertEquals(Sha256.hex(stored.bytes), HexFormat.of().formatHex(publication["ciphertext_hash"] as ByteArray))
        assertArrayEquals(p.requests.single { it.kind == "PUT" }.body, stored.bytes)
        assertEquals(1, p.generated()); assertEquals(1, p.decrypted())
        return TestRegisteredInitialDeletionNativeRecordV1(p.event, stored, checkNotNull(keyContext), p, checkNotNull(keyReply))
    }

    fun counts(): List<Int> {
        val first = checkNotNull(fixture).first
        return listOf(first.native.sts.requests.size, first.native.kms.requests.size, first.native.requests.size, first.p.f.http.read.requests.size,
            ordinary.sts.requests.size, publisher?.kms?.requests?.size ?: 0, publisher?.requests?.size ?: 0,
            checkpoint.sts.requests.size, checkpoint.kms.requests.size, checkpoint.requests.size,
            queue.sts.requests.size, queue.kms.requests.size, queue.requests.size, queue.sqs.requests.size)
    }
    fun assertDisposed() { ordinary.assertDisposed(); publisher?.assertClientsClosed(); checkpoint.assertDisposed(); queue.assertDisposed(); assertion.get()?.let { throw it } }
    private fun <T> checked(action: () -> T): T = try { action() } catch (failure: AssertionError) { assertion.compareAndSet(null, failure); throw failure }
}
