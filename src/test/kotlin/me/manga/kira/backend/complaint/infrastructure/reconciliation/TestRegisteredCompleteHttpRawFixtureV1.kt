package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointDeletionInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminBatchStatusInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminContentInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminReadInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminStatusInputV1
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Raw native SPI only. The event is observed after real HTTP AUTH, never supplied as work or VERIFY authority. */
internal class TestRegisteredCompleteHttpRawFixtureV1 {
    val ordinary = TestActiveOrdinaryRawFixtureV1()
    val checkpoint = TestActiveInitialCheckpointRawFixtureV1()
    val queue = TestActiveOwnerDeleteQueueRawFixtureV1()
    @Volatile var fixture: TestRegisteredCompleteHttpFixtureV1? = null
    @Volatile var expected: RegisteredAdminDeleteHttpAttemptV1? = null
    @Volatile var grant: UUID? = null
    @Volatile var failReadback = false
    @Volatile var publisher: TestOwnerDeleteJournalPublisherFixture? = null
        private set
    private var keyReply: ((JournalKmsHttpRequest) -> JournalKmsHttpReply)? = null
    private var keyContext: Map<String, String>? = null
    private val assertion = AtomicReference<AssertionError?>()
    private val sts = TestRegisteredDeletionHttpStsFixtureV1(
        { checkNotNull(fixture).assertSqlReleased() },
        { checkNotNull(fixture).first.process.consumers.journalConfiguration.declaration().journalLocation.region })
    val factories = TestActiveOrdinaryRawHttpV1(sts::httpClient,
        { remaining -> native(remaining) { selected().kms.httpClient() } },
        { remaining -> native(remaining) { selected().httpClient() } }, checkpoint.input,
        initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.EDIT_PROFILE),
        initialCheckpointDeletion = TestInitialCheckpointDeletionInputV1(1, VersionBoundTestInitialCheckpointDeletionV1.PROFILE),
        activeOwnerDeleteQueue = queue.input,
        adminRead = TestRegisteredAdminReadInputV1(1, TestRegisteredAdminReadInputV1.PROFILE, 60),
        adminContent = TestRegisteredAdminContentInputV1(1, TestRegisteredAdminContentInputV1.PROFILE, 60),
        adminStatus = TestRegisteredAdminStatusInputV1(1, TestRegisteredAdminStatusInputV1.PROFILE, 60),
        adminBatchStatus = TestRegisteredAdminBatchStatusInputV1(1, TestRegisteredAdminBatchStatusInputV1.PROFILE, 60))

    private fun selected(): TestOwnerDeleteJournalPublisherFixture {
        publisher?.let { return it }
        val f = checkNotNull(fixture); val attempt = checkNotNull(expected)
        f.assertSqlReleased(); f.assertPending(attempt, checkNotNull(grant), verified = false)
        assertEquals(1L, f.first.process.publicationLanes.activeOwners().totalOwners)
        val row = f.publication(attempt)
        val event = TestOwnerDeleteJournalCodecV1.restoreCanonical(f.first.process.consumers.journalRouting,
            row.getValue("event_bytes") as ByteArray, row.getValue("routing_key_id") as String)
        val tuple = event.adminComparison
        assertEquals(attempt.operation, tuple.eventKind.name); assertEquals(f.admin.users[0].id, tuple.actorId)
        assertEquals(f.admin.scope, tuple.scope); assertEquals(attempt.key, tuple.operationKey)
        assertEquals(grant, tuple.consumedGrantId); assertNull(tuple.credentialVersion)
        assertEquals(attempt.ids, event.complaintIds()); assertEquals(2L, tuple.epoch)
        assertArrayEquals(f.receipt(attempt).getValue("fingerprint") as ByteArray, tuple.fingerprintBytes())
        assertArrayEquals(row.getValue("event_bytes") as ByteArray, event.canonicalBytes())
        val p = TestOwnerDeleteJournalPublisherFixture(f.first.process.consumers.journalRouting, event).also { publisher = it }
        p.beforePrepare = { checked { f.assertSqlReleased(); p.wall = f.first.native.now() } }
        p.onClientClose = { checked { f.assertSqlReleased() } }; p.kms.onClientClose = { checked { f.assertSqlReleased() } }
        val reply = p.kms.respond.also { keyReply = it } // Original KMS map, before producer-specific signing assertions.
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
            override fun clientName() = "SyntheticRegisteredCompleteAdminHttp"
        }
    }

    fun record(): TestRegisteredInitialDeletionNativeRecordV1 {
        assertDisposed(); val p = checkNotNull(publisher); val stored = p.objects.single()
        val row = checkNotNull(fixture).publication(checkNotNull(expected))
        assertEquals("VERIFIED", row["state"]); assertEquals(stored.version, row["object_version"])
        assertEquals(Sha256.hex(stored.bytes), HexFormat.of().formatHex(row["ciphertext_hash"] as ByteArray))
        assertArrayEquals(p.requests.single { it.kind == "PUT" }.body, stored.bytes)
        assertEquals(1, p.generated()); assertEquals(1, p.decrypted())
        return TestRegisteredInitialDeletionNativeRecordV1(p.event, stored, checkNotNull(keyContext), p, checkNotNull(keyReply))
    }
    fun counts(): List<Int> = checkNotNull(fixture).admin.providerCounts() + listOf(ordinary.sts.requests.size, sts.requestCount,
        publisher?.requests?.size ?: 0, publisher?.kms?.requests?.size ?: 0, queue.requests.size, queue.sts.requests.size, queue.kms.requests.size, queue.sqs.requests.size)
    fun assertDisposed() { ordinary.assertDisposed(); sts.assertDisposed(); checkpoint.assertDisposed(); publisher?.assertClientsClosed(); queue.assertDisposed(); assertion.get()?.let { throw it } }
    private fun <T> checked(action: () -> T): T = try { action() } catch (failure: AssertionError) { assertion.compareAndSet(null, failure); throw failure }
}
