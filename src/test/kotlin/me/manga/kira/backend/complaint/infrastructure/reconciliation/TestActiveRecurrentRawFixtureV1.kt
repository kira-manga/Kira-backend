package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReply
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealHttpFixtureV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointDeletionInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import me.manga.kira.backend.complaint.journal.JournalPublisherHttpRequest
import me.manga.kira.backend.complaint.journal.JournalPublisherObject
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
import me.manga.kira.backend.complaint.journal.journalPublisherRawGetReply
import me.manga.kira.backend.complaint.journal.journalPublisherRawHttpClient
import me.manga.kira.backend.complaint.journal.journalPublisherRawListDocument
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import me.manga.kira.backend.security.aws.JournalKmsHttpRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import java.util.concurrent.atomic.AtomicReference

/**
 * Cold HTTP factories, not a process/result factory. The service's initial-empty and later-nonempty
 * responses evolve after the actual PUT. The original object/wrapped-key map stay bound to that
 * producer. An opt-in retained ALL alias uses separately labeled protocol-history bytes only
 * after the genuine B queue has authenticated/applied it; it is not a second AUTH/PUT history.
 * No encryption/key/proof/marker is synthesized by this reader.
 * Fault functions corrupt raw responses only. Not a real provider or two-process qualification.
 */
internal class TestActiveRecurrentRawFixtureV1(
    initialCheckpointCreate: TestInitialCheckpointCreateInputV1 = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.PROFILE),
    shortFreshness: Boolean = false,
    initialCheckpointDeletion: TestInitialCheckpointDeletionInputV1 = TestInitialCheckpointDeletionInputV1(1, VersionBoundTestInitialCheckpointDeletionV1.PROFILE),
    ordinaryPoolSize: Int = 2,
) {
    val queue = TestActiveOwnerDeleteQueueRawFixtureV1()
    // Both HTTP contexts and this dispatcher exist before D. The first consumed context keeps
    // its actual record, event, delivery counters and owner; it is never retargeted for new work.
    private val freshQueue = TestActiveOwnerDeleteQueueRawFixtureV1()
    private val selectedQueue = AtomicReference(queue)
    private var freshQueueUsed = false
    private val queueInput = TestActiveOwnerDeleteQueueHttpInputV1(
        { remaining -> selectedQueue.get().input.sts(remaining) },
        { remaining -> selectedQueue.get().input.kms(remaining) },
        { remaining -> selectedQueue.get().input.s3(remaining) },
        { remaining -> selectedQueue.get().input.sqs(remaining) })
    val deletion = TestRegisteredInitialCheckpointDeletionRawFixtureV1(queueInput, initialCheckpointDeletion = initialCheckpointDeletion)
    val sts = AwsJournalKmsFixture()
    val kms = AwsJournalKmsFixture()
    val requests = mutableListOf<JournalPublisherHttpRequest>()
    val order = mutableListOf<String>()
    val budgets = mutableListOf<Pair<String, Int>>()
    private var fixture: TestActiveRecurrentFixtureV1? = null
    private var serviceReader: ServiceReader? = null
    private val process get() = serviceReader?.producer ?: checkNotNull(fixture).process
    private val record get() = serviceReader?.record ?: checkNotNull(fixture).record
    private val listedObjects = linkedMapOf<Pair<String, String>, JournalPublisherObject>()
    private val assertion = AtomicReference<AssertionError?>()
    var s3Created = 0
        private set
    var s3Closed = 0
        private set
    var s3CloseReturned = 0
        private set
    var listing: (Int, List<JournalPublisherObject>) -> List<JournalPublisherObject> = { _, values -> values }
    var listDocument: (Int, String) -> String = { _, text -> text }
    var beforeS3: (JournalPublisherHttpRequest) -> Unit = {}
    var changeS3: (JournalPublisherHttpRequest, S3CatalogReply) -> Unit = { _, _ -> }
    var changeSts: (JournalKmsHttpReply) -> Unit = {}
    var onNativeClose: () -> Unit = {}
    private val input = TestActiveInitialCheckpointHttpInputV1(
        { remaining -> if (fixture == null && serviceReader == null) deletion.checkpoint.input.sts(remaining) else native("STS", remaining, sts::httpClient) },
        { remaining -> if (fixture == null && serviceReader == null) deletion.checkpoint.input.kms(remaining) else native("KMS", remaining, kms::httpClient) },
        { remaining -> if (fixture == null && serviceReader == null) deletion.checkpoint.input.s3(remaining) else native("S3", remaining, ::s3Client) })
    val factories = deletion.factories.let { original ->
        TestActiveOrdinaryRawHttpV1(original.sts, original.kms, original.s3, initialCheckpoint = input,
            initialCheckpointCreate = initialCheckpointCreate, shortInitialCheckpointFreshness = shortFreshness,
            initialCheckpointDeletion = original.initialCheckpointDeletion,
            activeOwnerDeleteQueue = original.activeOwnerDeleteQueue,
            activeRecurrent = TestActiveRecurrentInputV1(1, TestActiveRecurrentStorageV1.PROFILE, TestActiveInitialCheckpointHttpInputV1.SESSION),
            ordinaryPoolSize = ordinaryPoolSize)
    }

    init {
        sts.beforePrepare = { checked { boundary() } }; kms.beforePrepare = { checked { boundary() } }
        sts.onClientClose = { checked { boundary(); onNativeClose() } }
        kms.onClientClose = { checked { boundary(); onNativeClose() } }
        sts.respond = { request -> checked {
            boundary(); signed(request, "sts"); order.add("STS")
            assertEquals(mapOf("Action" to "GetCallerIdentity", "Version" to "2011-06-15"), TestOrdinarySealHttpFixtureV1.query(request))
            val account = TestOrdinarySealHttpFixtureV1.ACCOUNT
            val session = TestActiveInitialCheckpointHttpInputV1.SESSION
            JournalKmsHttpReply("""<GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><GetCallerIdentityResult>""" +
                "<Arn>arn:aws:sts::$account:assumed-role/test-recovery/$session</Arn><UserId>AROA${"C".repeat(17)}:$session</UserId><Account>$account</Account></GetCallerIdentityResult>" +
                "<ResponseMetadata><RequestId>recurrent-reader</RequestId></ResponseMetadata></GetCallerIdentityResponse>").apply {
                headers = mapOf("Content-Type" to listOf("text/xml; charset=utf-8"), "Content-Length" to listOf(bytes.size.toString()))
                changeSts(this)
            }
        } }
        kms.respond = { request -> checked {
            boundary(); signed(request, "kms"); order.add("DECRYPT")
            assertEquals(AwsJournalKmsFixture.DECRYPT_TARGET, request.target(), "Read-only recurrent recovery never generates a key.")
            val original = record
            val historical = fixture?.historicalAll
            val context = request.fields()["EncryptionContext"].fields().asSequence().associate { it.key to it.value.textValue() }
            when {
                context == original.kmsContext -> original.decrypt(request) // Original producer responder, not copied plaintext.
                historical != null && context == historical.kmsContext -> historical.decrypt(request) // Explicit existing protocol-history map, not producer evidence.
                else -> JournalKmsHttpReply("""{"__type":"InvalidCiphertextException","message":"Synthetic context mismatch"}""").apply {
                    status = 400 // A tampered-key NEGATIVE cannot obtain either wrapped key under a different context.
                }
            }
        } }
    }
    fun attach(f: TestActiveRecurrentFixtureV1) {
        check(fixture == null && serviceReader == null); fixture = f
        listedObjects[f.record.stored.key to f.record.stored.version] = f.record.stored
        f.historicalAll?.stored?.let { listedObjects[it.key to it.version] = it }
        assertTrue(requests.isEmpty() && sts.requests.isEmpty() && kms.requests.isEmpty())
    }
    fun detach(f: TestActiveRecurrentFixtureV1) { check(fixture === f); fixture = null; listedObjects.clear(); assertNoLostAssertions() }
    /** Original A data/routing only; callbacks observe B, whose MAIN inputs remain raw HTTP. */
    fun attachProducerForService(producer: VersionBoundTestNamespaceProcessV1, record: TestRegisteredInitialDeletionNativeRecordV1,
        providerBoundary: () -> Unit, passNumber: () -> Int): AutoCloseable {
        check(fixture == null && serviceReader == null)
        check(record.event.belongsTo(producer.consumers.journalRouting))
        assertTrue(requests.isEmpty() && sts.requests.isEmpty() && kms.requests.isEmpty())
        val selected = ServiceReader(producer, record, providerBoundary, passNumber)
        serviceReader = selected
        listedObjects[record.stored.key to record.stored.version] = record.stored
        return AutoCloseable {
            check(serviceReader === selected); serviceReader = null; listedObjects.clear(); assertNoLostAssertions()
        }
    }
    private class ServiceReader(val producer: VersionBoundTestNamespaceProcessV1, val record: TestRegisteredInitialDeletionNativeRecordV1,
        val providerBoundary: () -> Unit, val passNumber: () -> Int)
    /** One independent additional raw B owner; every retry is a fresh genuine begin on its fixed record. */
    fun <T> withFreshQueue(action: (TestActiveOwnerDeleteQueueRawFixtureV1) -> T): T {
        checkNotNull(fixture).assertReleased()
        check(!freshQueueUsed); freshQueueUsed = true
        check(selectedQueue.compareAndSet(queue, freshQueue))
        return try { action(freshQueue) } finally { check(selectedQueue.compareAndSet(freshQueue, queue)) }
    }
    fun queueProviderCounts(): List<Int> = listOf(queue, freshQueue).flatMap { selected -> listOf(
        selected.sts.requests.size, selected.kms.requests.size, selected.sqs.requests.size,
        selected.requests.size, selected.budgets.size) }
    private fun s3Client(): SdkHttpClient {
        boundary(); s3Created++
        return journalPublisherRawHttpClient(requests, { checked { boundary() } }, {}, {
            s3Closed++; checked { boundary(); onNativeClose() }; s3CloseReturned++
        }, ::reply)
    }
    private fun reply(request: JournalPublisherHttpRequest): S3CatalogReply = checked {
        boundary()
        val j = process.consumers.journalConfiguration
        val location = j.declaration().journalLocation
        val pass = serviceReader?.passNumber?.invoke() ?: checkNotNull(fixture).passNumber()
        journalPublisherRawAssertSigned(request, location.region, location.accountId, input.credentials)
        assertTrue(request.body.isEmpty()); assertFalse(request.kind == "PUT")
        beforeS3(request)
        val reply = when (request.kind) {
            "LIST" -> {
                order.add("PASS$pass")
                assertEquals(listOf(j.ordinaryPrefix), request.http.rawQueryParameters()["prefix"])
                assertEquals(listOf("2"), request.http.rawQueryParameters()["max-keys"])
                assertTrue(request.http.rawQueryParameters().keys.none { it in setOf("delimiter", "key-marker", "version-id-marker", "start-after") })
                val inventory = (listOf(record.stored) + listOfNotNull(fixture?.historicalAll?.stored)).sortedWith { a, b ->
                    TestOrdinaryDrainRowsV1.compare(a.key to a.version, b.key to b.version)
                }
                val versions = listing(pass, inventory)
                check(versions.size <= 3) // At most the two honestly sourced objects plus bounded hostile response input.
                listedObjects.clear()
                inventory.forEach { listedObjects[it.key to it.version] = it }
                versions.forEach { listedObjects[it.key to it.version] = it }
                OwnerDeleteAllJournalPublisherFixture.xmlReply(listDocument(pass, journalPublisherRawListDocument(location.bucket, j.ordinaryPrefix, versions)))
            }
            "GET" -> {
                order.add("GET$pass")
                val versions = checkNotNull(request.http.rawQueryParameters()["versionId"])
                assertEquals(1, versions.size)
                val value = checkNotNull(listedObjects[request.http.encodedPath().removePrefix("/${location.bucket}/") to versions.single()])
                assertEquals("/${location.bucket}/${value.key}", request.http.encodedPath())
                journalPublisherRawGetReply(location.region, value.copy(bytes = value.bytes.copyOf()))
            }
            else -> error("No recurrent write HTTP route.")
        }
        reply.also { changeS3(request, it) }
    }
    private fun signed(request: JournalKmsHttpRequest, service: String) {
        val location = process.consumers.journalConfiguration.declaration().journalLocation
        assertEquals("https", request.http.protocol()); assertEquals("$service.${location.region}.amazonaws.com", request.http.host())
        assertEquals(input.credentials.sessionToken(), request.http.firstMatchingHeader("x-amz-security-token").orElseThrow())
        val auth = request.http.firstMatchingHeader("Authorization").orElseThrow()
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=${input.credentials.accessKeyId()}/") && "/$service/aws4_request" in auth)
    }
    private fun native(kind: String, remaining: () -> Int, create: () -> SdkHttpClient): SdkHttpClient {
        boundary()
        val raw = create()
        return object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = checked {
                boundary(); val budget = remaining(); assertTrue(budget in 1..5_000); budgets.add(kind to budget)
                raw.prepareRequest(request)
            }
            override fun close() = raw.close()
            override fun clientName(): String = "SyntheticRecurrent$kind"
        }
    }
    private fun boundary() { requireConnectionFree(); serviceReader?.providerBoundary?.invoke() ?: checkNotNull(fixture).assertSqlReleased() }
    private fun <T> checked(action: () -> T): T = try { action() } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }
    fun assertNoLostAssertions() { assertion.get()?.let { throw it }; queue.assertNoLostAssertions(); freshQueue.assertNoLostAssertions() }
    fun resetFaults() { listing = { _, values -> values }; listDocument = { _, text -> text }; beforeS3 = {}; changeS3 = { _, _ -> }; changeSts = {}; onNativeClose = {} }
    fun assertDisposed(returned: Boolean = true) {
        assertEquals(s3Created, s3Closed)
        if (returned) assertEquals(s3Created, s3CloseReturned)
        listOf(sts, kms).forEach {
            assertEquals(it.createdClients, it.closedClients)
            if (returned) assertEquals(it.createdClients, it.returnedClientCloses)
            it.replies.forEach { reply -> assertEquals(1, reply.calls); assertEquals(1, reply.aborts); assertEquals(if (reply.bodyPresent) 1 else 0, reply.closes) }
        }
        requests.forEach {
            assertEquals(1, it.calls); assertEquals(1, it.aborts)
            it.reply?.let { reply -> assertEquals(if (it.responseReturned && reply.bodyPresent) 1 else 0, reply.closes) }
        }
        assertNoLostAssertions()
    }
}
