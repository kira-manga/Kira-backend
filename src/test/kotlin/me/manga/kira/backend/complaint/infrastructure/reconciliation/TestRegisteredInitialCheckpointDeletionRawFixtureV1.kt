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
    initialCheckpointDeletion: TestInitialCheckpointDeletionInputV1 = TestInitialCheckpointDeletionInputV1(1, VersionBoundTestInitialCheckpointDeletionV1.PROFILE),
) {
    val ordinary = TestActiveOrdinaryRawFixtureV1()
    val checkpoint = TestActiveInitialCheckpointRawFixtureV1()
    val factories = ordinary.factories.let {
        TestActiveOrdinaryRawHttpV1(it.sts,
            { remaining -> native("KMS", remaining) { checkNotNull(publishing.get()).publisher.kms.httpClient() } },
            { remaining -> native("S3", remaining) { checkNotNull(publishing.get()).publisher.httpClient() } }, checkpoint.input,
            initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.PROFILE),
            shortInitialCheckpointFreshness = shortFreshness, activeOwnerDeleteQueue = queueHttp,
            initialCheckpointDeletion = initialCheckpointDeletion)
    }
    private var firstOwner: TestRegisteredInitialCheckpointDeletionFixtureV1? = null
    private val owners = linkedSetOf<TestRegisteredInitialCheckpointDeletionFixtureV1>()
    private val publications = linkedMapOf<TestRegisteredInitialCheckpointDeletionFixtureV1, NativePublication>()
    private val publishing = AtomicReference<NativePublication?>()
    private var oldStsBefore: (() -> Unit)? = null
    private val assertion = AtomicReference<AssertionError?>()
    val requestBudgets = mutableListOf<Pair<String, Int>>()
    /** The original fixture's observation remains stable when a different request later publishes. */
    val publisher: TestOwnerDeleteJournalPublisherFixture get() = publisher(checkNotNull(firstOwner))
    fun publisher(fixture: TestRegisteredInitialCheckpointDeletionFixtureV1): TestOwnerDeleteJournalPublisherFixture =
        publications.getValue(fixture).publisher

    fun attach(fixture: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        check(owners.add(fixture))
        if (firstOwner == null) {
            firstOwner = fixture
            val before = ordinary.sts.beforePrepare
            oldStsBefore = before
            ordinary.sts.beforePrepare = { checked { before(); boundary(publishing.get()?.owner ?: fixture) } }
        } else {
            val first = checkNotNull(firstOwner)
            assertSame(first.process, fixture.process); assertSame(first.binding, fixture.binding)
            assertSame(first.deletionOwner, fixture.deletionOwner); assertSame(first.deletion, fixture.deletion)
        }
    }

    fun expect(f: TestRegisteredInitialCheckpointDeletionFixtureV1, event: TestOwnerDeleteJournalEventV1) {
        requireConnectionFree(); check(f in owners && f !in publications)
        f.assertSqlReleased()
        assertSame(event, f.event)
        // Capture the existing raw fixture's key responder BEFORE adding the producer's signing
        // assertions. A later queue has its own credentials; it must keep this original key map,
        // not borrow the producer's signature or substitute a plaintext key.
        val p = TestOwnerDeleteJournalPublisherFixture(f.process.consumers.journalRouting, event)
        p.beforePrepare = { checked { boundary(f); p.wall = f.first.native.now() } }
        p.onClientClose = { checked { boundary(f) } }
        p.kms.onClientClose = { checked { boundary(f) } }
        val respond = p.kms.respond
        val original = NativePublication(f, event, p, respond)
        publications[f] = original
        p.kms.respond = { request -> checked {
            boundary(f)
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
                check(original.generatedContext == null)
                original.generatedContext = observed.toMap()
            }
            reply
        } }
        p.respond = { request -> checked {
            boundary(f)
            val location = p.journal.declaration().journalLocation
            journalPublisherRawAssertSigned(request, location.region, location.accountId, TestActiveFirstCutInputFixtureV1.ordinaryCredentials)
            p.statefulReply(request)
        } }
    }

    /** Dispatch only this fresh native original; earlier owner/event/PUT/key contexts are immutable. */
    fun <T> publish(f: TestRegisteredInitialCheckpointDeletionFixtureV1, action: () -> T): T {
        requireConnectionFree(); check(f in owners)
        val original = publications.getValue(f)
        check(publishing.compareAndSet(null, original))
        return try { action() } finally { check(publishing.compareAndSet(original, null)) }
    }

    /** Passive record after real native PUT/readback/cleanup, including before persistence VERIFY. */
    fun observed(f: TestRegisteredInitialCheckpointDeletionFixtureV1, readback: TestOwnerDeleteJournalReadbackV1): TestRegisteredInitialDeletionNativeRecordV1 {
        requireConnectionFree(); f.assertSqlReleased()
        val original = publications.getValue(f)
        val event = original.event
        val p = original.publisher
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
        assertEquals(f.expectedEpoch, event.comparison.epoch)
        assertEquals(1, p.generated()); assertEquals(1, p.decrypted())
        ordinary.assertDisposed(); p.assertClientsClosed()
        return TestRegisteredInitialDeletionNativeRecordV1(event, stored, checkNotNull(original.generatedContext), p, original.keyReply)
    }

    fun counts(): List<Int> = listOf(ordinary.sts.requests.size, publications.values.sumOf { it.publisher.kms.requests.size },
        publications.values.sumOf { it.publisher.requests.size }, ordinary.requestBudgets.size + requestBudgets.size)

    private fun boundary(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        requireConnectionFree()
        f.assertSqlReleased(); f.checkpoint.sealer.assertProviderBoundary()
    }

    /** Existing HTTP SPI only, with the real recipe's remaining native budget; no new provider. */
    private fun native(kind: String, remaining: () -> Int, create: () -> SdkHttpClient): SdkHttpClient {
        val original = checkNotNull(publishing.get())
        boundary(original.owner)
        val raw = create()
        return object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = checked {
                assertSame(original, publishing.get()); boundary(original.owner)
                val budget = remaining(); assertTrue(budget in 1..5_000)
                requestBudgets.add(kind to budget)
                raw.prepareRequest(request)
            }
            override fun close() = raw.close()
            override fun clientName() = "SyntheticRegisteredInitialDeletion$kind"
        }
    }

    fun detach(fixture: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        check(owners.remove(fixture)); check(publishing.get() == null)
        if (firstOwner === fixture) {
            check(owners.isEmpty())
            ordinary.sts.beforePrepare = checkNotNull(oldStsBefore)
        }
        ordinary.assertDisposed(); publications[fixture]?.publisher?.assertClientsClosed()
        assertNoLostAssertions()
    }
    private class NativePublication(val owner: TestRegisteredInitialCheckpointDeletionFixtureV1,
        val event: TestOwnerDeleteJournalEventV1, val publisher: TestOwnerDeleteJournalPublisherFixture,
        val keyReply: (JournalKmsHttpRequest) -> JournalKmsHttpReply) {
        var generatedContext: Map<String, String>? = null
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
