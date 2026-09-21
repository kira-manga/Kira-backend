package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointDeletionInputV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalReadbackV1
import me.manga.kira.backend.complaint.journal.JournalPublisherObject
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import me.manga.kira.backend.security.aws.JournalKmsHttpRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import java.util.concurrent.atomic.AtomicReference

/**
 * Raw factories are selected before protected input/full D. The existing native publisher fixture
 * alone handles STS/KMS/S3. No event, envelope, object version, work or verification is manufactured
 * here: expectations start with the private store's actually committed/released AUTH event.
 */
internal class TestRegisteredInitialCheckpointDeletionRawFixtureV1(
    queueHttp: TestActiveOwnerDeleteQueueHttpInputV1? = null,
    shortFreshness: Boolean = false,
) {
    val ordinary = TestActiveOrdinaryRawFixtureV1()
    val checkpoint = TestActiveInitialCheckpointRawFixtureV1()
    val factories = ordinary.factories.let {
        TestActiveOrdinaryRawHttpV1(it.sts,
            { remaining -> native("KMS", remaining) { publisher.kms.httpClient() } },
            { remaining -> native("S3", remaining) { publisher.httpClient() } }, checkpoint.input,
            initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.PROFILE),
            shortInitialCheckpointFreshness = shortFreshness, activeOwnerDeleteQueue = queueHttp,
            initialCheckpointDeletion = TestInitialCheckpointDeletionInputV1(1, VersionBoundTestInitialCheckpointDeletionV1.PROFILE))
    }
    private var owner: TestRegisteredInitialCheckpointDeletionFixtureV1? = null
    private var expected: TestOwnerDeleteJournalEventV1? = null
    private var generatedContext: Map<String, String>? = null
    private var oldStsBefore: (() -> Unit)? = null
    private var nativePublisher: TestOwnerDeleteJournalPublisherFixture? = null
    private var originalKeyReply: ((JournalKmsHttpRequest) -> JournalKmsHttpReply)? = null
    private val assertion = AtomicReference<AssertionError?>()
    val requestBudgets = mutableListOf<Pair<String, Int>>()
    val publisher: TestOwnerDeleteJournalPublisherFixture get() = checkNotNull(nativePublisher)

    fun attach(fixture: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        check(owner == null); owner = fixture
        val before = ordinary.sts.beforePrepare
        oldStsBefore = before
        ordinary.sts.beforePrepare = { checked { before(); fixture.assertSqlReleased() } }
    }

    fun expect(event: TestOwnerDeleteJournalEventV1) {
        requireConnectionFree(); check(expected == null)
        val f = checkNotNull(owner)
        f.assertSqlReleased()
        expected = event
        // Capture the existing raw fixture's key responder BEFORE adding the producer's signing
        // assertions. A later queue has its own credentials; it must keep this original key map,
        // not borrow the producer's signature or substitute a plaintext key.
        val p = TestOwnerDeleteJournalPublisherFixture(f.process.consumers.journalRouting, event)
        nativePublisher = p
        p.beforePrepare = { checked { boundary(); p.wall = f.first.native.now() } }
        p.onClientClose = { checked { boundary() } }
        p.kms.onClientClose = { checked { boundary() } }
        val respond = p.kms.respond
        originalKeyReply = respond
        p.kms.respond = { request -> checked {
            boundary()
            val credentials = TestActiveFirstCutInputFixtureV1.ordinaryCredentials
            val region = p.journal.declaration().journalLocation.region
            assertEquals("https", request.http.protocol())
            assertEquals("kms.$region.amazonaws.com", request.http.host())
            assertEquals(credentials.sessionToken(), request.http.firstMatchingHeader("x-amz-security-token").orElseThrow())
            val signed = request.http.firstMatchingHeader("Authorization").orElseThrow()
            assertTrue(signed.startsWith("AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId()}/") && "/kms/aws4_request" in signed)
            p.assertKmsContext(request, event)
            val reply = respond(request) // Original raw key owner retains its own wrapped-key mapping.
            if (request.target() == AwsJournalKmsFixture.GENERATE_TARGET) {
                val fields = request.fields()["EncryptionContext"]
                val observed = fields.fields().asSequence().associate { it.key to it.value.asText() }
                assertEquals(setOf(AwsJournalKmsFixture.CONTEXT_KEY), observed.keys)
                check(generatedContext == null)
                generatedContext = observed.toMap()
            }
            reply
        } }
        p.respond = { request -> checked {
            boundary()
            val location = p.journal.declaration().journalLocation
            journalPublisherRawAssertSigned(request, location.region, location.accountId, TestActiveFirstCutInputFixtureV1.ordinaryCredentials)
            p.statefulReply(request)
        } }
    }

    /** Passive record after real native PUT/readback/cleanup, including before persistence VERIFY. */
    fun observed(readback: TestOwnerDeleteJournalReadbackV1): TestRegisteredInitialDeletionNativeRecordV1 {
        requireConnectionFree(); checkNotNull(owner).assertSqlReleased()
        val event = checkNotNull(expected)
        val p = publisher
        val stored = p.objects.single { it.key == event.route.objectKey }
        val put = p.requests.single { it.kind == "PUT" }
        if (event.comparison.eventKind == ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) {
            // ALL's private work is retained, but testEvent restores a fresh comparison value.
            // Equality here never replaces the real lane/readback or original PUT custody below.
            val observed = readback.event
            assertTrue(event.belongsTo(p.routing)); assertTrue(observed.belongsTo(p.routing))
            assertEquals(event.route, observed.route)
            assertArrayEquals(event.canonicalBytes(), observed.canonicalBytes())
            assertEquals(event.semanticSha256, observed.semanticSha256)
            assertEquals(event.complaintIds(), observed.complaintIds())
            val expectedTuple = event.tuple; val observedTuple = observed.tuple
            assertEquals(expectedTuple.eventKind, observedTuple.eventKind)
            assertEquals(expectedTuple.scope, observedTuple.scope)
            assertEquals(expectedTuple.epoch, observedTuple.epoch)
            assertEquals(expectedTuple.actorKind, observedTuple.actorKind)
            assertEquals(expectedTuple.actorId, observedTuple.actorId)
            assertEquals(expectedTuple.credentialVersion, observedTuple.credentialVersion)
            assertEquals(expectedTuple.operationKey, observedTuple.operationKey)
            assertEquals(expectedTuple.encodedFingerprint(), observedTuple.encodedFingerprint())
        } else assertSame(event, readback.event)
        assertArrayEquals(put.body, stored.bytes)
        assertEquals(stored.version, readback.versionId)
        assertEquals(Sha256.hex(stored.bytes), readback.wireSha256)
        assertEquals(stored.lastModified, readback.lastModified)
        assertEquals(stored.retainUntil, readback.retainUntil)
        assertEquals(2L, event.comparison.epoch)
        assertEquals(1, p.generated()); assertEquals(1, p.decrypted())
        ordinary.assertDisposed(); p.assertClientsClosed()
        return TestRegisteredInitialDeletionNativeRecordV1(event, stored, checkNotNull(generatedContext), p, checkNotNull(originalKeyReply))
    }

    fun counts(): List<Int> = listOf(ordinary.sts.requests.size, nativePublisher?.kms?.requests?.size ?: 0,
        nativePublisher?.requests?.size ?: 0, ordinary.requestBudgets.size + requestBudgets.size)

    private fun boundary() {
        requireConnectionFree()
        val f = checkNotNull(owner)
        f.assertSqlReleased(); f.checkpoint.sealer.assertProviderBoundary()
    }

    /** Existing HTTP SPI only, with the real recipe's remaining native budget; no new provider. */
    private fun native(kind: String, remaining: () -> Int, create: () -> SdkHttpClient): SdkHttpClient {
        boundary()
        val raw = create()
        return object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = checked {
                boundary()
                val budget = remaining(); assertTrue(budget in 1..5_000)
                requestBudgets.add(kind to budget)
                raw.prepareRequest(request)
            }
            override fun close() = raw.close()
            override fun clientName() = "SyntheticRegisteredInitialDeletion$kind"
        }
    }

    fun detach(fixture: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        check(owner === fixture)
        ordinary.sts.beforePrepare = checkNotNull(oldStsBefore)
        owner = null
        ordinary.assertDisposed(); nativePublisher?.assertClientsClosed()
        assertNoLostAssertions()
    }
    private fun <T> checked(action: () -> T): T = try { action() } catch (failure: AssertionError) { assertion.compareAndSet(null, failure); throw failure }
    fun assertNoLostAssertions() { assertion.get()?.let { throw it } }
}

/**
 * Observation, NOT work/proof/eligibility. B may serve this exact original PUT and delegate Decrypt
 * to the same raw KMS responder. No key bytes, re-encryption or new producer envelope are exposed.
 */
internal class TestRegisteredInitialDeletionNativeRecordV1 internal constructor(
    val event: TestOwnerDeleteJournalEventV1,
    val stored: JournalPublisherObject,
    context: Map<String, String>,
    private val original: TestOwnerDeleteJournalPublisherFixture,
    private val originalKeyReply: (JournalKmsHttpRequest) -> JournalKmsHttpReply,
) {
    val kmsContext: Map<String, String> = context.toMap()
    fun decrypt(request: JournalKmsHttpRequest): JournalKmsHttpReply {
        requireConnectionFree()
        assertSame(stored, original.objects.single { it.key == stored.key })
        assertEquals(AwsJournalKmsFixture.DECRYPT_TARGET, request.target())
        val supplied = request.fields()["EncryptionContext"].fields().asSequence().associate { it.key to it.value.asText() }
        assertEquals(kmsContext, supplied)
        assertTrue(original.requests.any { it.kind == "PUT" && it.body.contentEquals(stored.bytes) })
        return originalKeyReply(request)
    }
    override fun toString(): String = "TestRegisteredInitialDeletionNativeRecordV1(passive-original-PUT,no-authority)"
}
