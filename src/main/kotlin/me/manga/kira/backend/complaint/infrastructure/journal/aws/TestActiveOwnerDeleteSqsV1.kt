package me.manga.kira.backend.complaint.infrastructure.journal.aws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.requireQueue
import me.manga.kira.backend.complaint.infrastructure.reconciliation.retainActiveQueueNativeFailure
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.awscore.defaultsmode.DefaultsMode
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.regions.PartitionMetadata
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Exact J queues, native one-second long poll and single owned ack. No injected queue/replay port. */
internal class TestActiveOwnerDeleteSqsV1 private constructor(
    internal val original: TestActiveOwnerDeleteQueueV1,
    private val credentials: AwsSessionCredentials,
    private val factory: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val nanoTime: () -> Long,
) : AutoCloseable {
    internal val declaration = original.routing.journalConfiguration.declaration()
    private val graph = AtomicReference<Client?>()
    private val busy = AtomicBoolean()
    private val stopped = AtomicBoolean()
    private val failure = AtomicReference<Throwable?>()
    private var current: Delivery? = null
    private var nextQueue = 0

    internal fun receive(deadLetter: Boolean): Delivery? = owned {
        requireQueue(nextQueue == if (deadLetter) 1 else 0)
        requireQueue(current == null)
        val selected = if (deadLetter) declaration.recovery.deadLetterQueue else declaration.recovery.queue
        val arn = selected.arn.split(':')
        val result = native { client ->
            val urlCall = Call(this, "GetQueueUrl", null, mapOf("QueueName" to arn[5], "QueueOwnerAWSAccountId" to arn[4]), nanoTime)
            val url = client.execute(urlCall) { sdk, overrides, raw ->
                val result = sdk.getQueueUrl(GetQueueUrlRequest.builder().queueName(arn[5]).queueOwnerAWSAccountId(arn[4]).overrideConfiguration(overrides).build())
                val node = raw()
                TestActiveQueueJsonV1.fields(node, setOf("QueueUrl"))
                requireQueue(result.sdkHttpResponse().statusCode() == 200 && result.queueUrl() == TestActiveQueueJsonV1.string(node, "QueueUrl"))
                result.queueUrl().also { requireQueue(it == "${client.endpoint}/${arn[4]}/${arn[5]}") }
            }
            val attributeNames = listOf("QueueArn", "KmsMasterKeyId", "RedrivePolicy")
            val attributes = Call(this, "GetQueueAttributes", url, mapOf("QueueUrl" to url, "AttributeNames" to attributeNames), nanoTime)
            client.execute(attributes) { sdk, overrides, raw ->
                val response = sdk.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(url).attributeNamesWithStrings(attributeNames)
                    .overrideConfiguration(overrides).build())
                val node = raw(); TestActiveQueueJsonV1.fields(node, setOf("Attributes"))
                val observed = node.get("Attributes")
                TestActiveQueueJsonV1.fields(observed, setOf("QueueArn", "KmsMasterKeyId"), setOf("RedrivePolicy"))
                val strings = observed.fields().asSequence().associate { requireQueue(it.value.isTextual); it.key to it.value.textValue() }
                requireQueue(response.sdkHttpResponse().statusCode() == 200 && response.attributesAsStrings() == strings)
                requireQueue(strings["QueueArn"] == selected.arn && strings["KmsMasterKeyId"] == selected.encryption.keyArn)
                if (deadLetter) requireQueue(strings["RedrivePolicy"] == null) else {
                    val policy = checkNotNull(strings["RedrivePolicy"]).toByteArray(Charsets.UTF_8)
                    val redrive = try { TestActiveQueueJsonV1.parse(policy, 4096) } finally { policy.fill(0) }
                    TestActiveQueueJsonV1.fields(redrive, setOf("deadLetterTargetArn", "maxReceiveCount"))
                    requireQueue(TestActiveQueueJsonV1.string(redrive, "deadLetterTargetArn") == declaration.recovery.deadLetterQueue.arn)
                    val count = redrive.get("maxReceiveCount")
                    requireQueue(count.isTextual || count.isIntegralNumber)
                    requireQueue(count.asText().matches(Regex("[1-9][0-9]{0,3}")) && count.asText().toInt() in 1..1000)
                }
            }
            val receive = Call(this, "ReceiveMessage", url, mapOf("QueueUrl" to url, "MaxNumberOfMessages" to 1,
                "WaitTimeSeconds" to 1, "VisibilityTimeout" to 30, "MessageSystemAttributeNames" to listOf("ApproximateReceiveCount")), nanoTime)
            requireQueue(receive.remainingMillis() >= 1500)
            client.execute(receive) { sdk, overrides, raw ->
                val response = sdk.receiveMessage(ReceiveMessageRequest.builder().queueUrl(url).maxNumberOfMessages(1)
                    .waitTimeSeconds(1).visibilityTimeout(30).messageSystemAttributeNamesWithStrings("ApproximateReceiveCount")
                    .overrideConfiguration(overrides).build())
                val node = raw(); TestActiveQueueJsonV1.fields(node, emptySet(), setOf("Messages"))
                requireQueue(response.sdkHttpResponse().statusCode() == 200 && response.messages().size <= 1)
                val messages = node.get("Messages")
                requireQueue(messages == null || messages.isArray && messages.size() <= 1)
                requireQueue((messages?.size() ?: 0) == response.messages().size)
                if (response.messages().isEmpty()) null else {
                    val message = response.messages().single(); val row = checkNotNull(messages).get(0)
                    TestActiveQueueJsonV1.fields(row, setOf("MessageId", "ReceiptHandle", "MD5OfBody", "Body", "Attributes"))
                    val id = TestActiveQueueJsonV1.string(row, "MessageId", 128)
                    val handle = TestActiveQueueJsonV1.string(row, "ReceiptHandle", 4096)
                    val body = TestActiveQueueJsonV1.string(row, "Body", TestActiveQueueJsonV1.MAX_NOTIFICATION_BYTES)
                    val md5 = TestActiveQueueJsonV1.string(row, "MD5OfBody", 32)
                    requireQueue(handle.all { it in '!'..'~' } && id.all { it in '!'..'~' })
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    try { requireQueue(md5 == HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes))) } finally { bytes.fill(0) }
                    TestActiveQueueJsonV1.fields(row.get("Attributes"), setOf("ApproximateReceiveCount"))
                    val count = TestActiveQueueJsonV1.string(row.get("Attributes"), "ApproximateReceiveCount", 10)
                    requireQueue(count.matches(Regex("[1-9][0-9]{0,9}")) && count.toLong() <= Int.MAX_VALUE)
                    requireQueue(message.messageId() == id && message.receiptHandle() == handle && message.body() == body && message.md5OfBody() == md5 &&
                        message.attributesAsStrings() == mapOf("ApproximateReceiveCount" to count) && message.messageAttributes().isEmpty())
                    Delivery(this, deadLetter, url, handle, body).also { current = it }
                }
            }
        }
        original.requireQueueNative(this) // Actual native graph closure, not returned SDK DTO equality, precedes this receipt.
        requireQueue(graph.get() == null)
        result?.released = true
        nextQueue++
        result
    }

    internal fun ack(delivery: Delivery) = owned {
        original.requireAckInput(this, delivery)
        requireDelivery(delivery)
        requireQueue(!delivery.ackClaimed); delivery.ackClaimed = true
        native { client ->
            val call = Call(this, "DeleteMessage", delivery.url, mapOf("QueueUrl" to delivery.url, "ReceiptHandle" to delivery.handle()), nanoTime)
            client.execute(call) { sdk, overrides, raw ->
                val response = sdk.deleteMessage(DeleteMessageRequest.builder().queueUrl(delivery.url).receiptHandle(delivery.handle()).overrideConfiguration(overrides).build())
                requireQueue(response.sdkHttpResponse().statusCode() == 200)
                TestActiveQueueJsonV1.fields(raw(), emptySet())
            }
        }
        original.requireQueueNative(this); requireQueue(graph.get() == null)
        delivery.acked = true; delivery.erase(); current = null
    }

    internal fun requireDelivery(delivery: Delivery) {
        requireQueue(delivery.owner === this && current === delivery && delivery.released && !delivery.acked && graph.get() == null)
        original.requireQueueNative(this)
    }
    internal fun allPollsReturned() { requireQueue(nextQueue == 2 && current == null && graph.get() == null && !busy.get() && failure.get() == null) }
    internal fun requireClosed() { requireQueue(stopped.get() && graph.get() == null && !busy.get() && failure.get() == null) }
    private fun <T> owned(action: () -> T): T {
        requireConnectionFree(); requireQueue(!stopped.get() && busy.compareAndSet(false, true))
        return try { check(); action().also { check() } } catch (problem: Throwable) {
            retainActiveQueueNativeFailure(failure, problem); throw checkNotNull(failure.get())
        } finally { busy.set(false) }
    }
    private fun <T> native(action: (Client) -> T): T {
        val client = Client(this, credentials, factory)
        requireQueue(graph.compareAndSet(null, client))
        return withJournalPublicationCleanup({ client.open(); action(client) }, ::releaseGraph)
    }
    private fun releaseGraph() {
        val client = graph.get() ?: return
        client.close(); requireQueue(graph.compareAndSet(client, null))
    }
    internal fun check() {
        requireConnectionFree(); failure.get()?.let { throw it }
        original.requireQueueNative(this); requireQueue(!stopped.get())
    }
    internal fun observeNativeFailure(problem: Throwable) {
        // Preserve signals at their actual native boundary, before an SDK can wrap them as an
        // ordinary client exception. This records failure only; it cannot grant release or retry.
        retainActiveQueueNativeFailure(failure, problem)
        original.observeFailure(problem)
    }
    internal fun remainingMillis(ceiling: Int): Int { check(); return original.remainingNativeMillis(minOf(ceiling, declaration.limits.deadlines.queueCallMillis)) }
    override fun close() {
        stopped.set(true)
        val problem = runCatching(::releaseGraph).exceptionOrNull()
        if (problem != null) retainActiveQueueNativeFailure(failure, problem)
        current?.erase()
        failure.get()?.let { throw it }
        requireQueue(!busy.get()) // A timer/cancel abort is not reclamation; late work keeps this owner poisoned.
    }
    override fun toString(): String = "ActiveOwnerDeleteSqs(native,partial,redacted)"

    internal class Delivery internal constructor(internal val owner: TestActiveOwnerDeleteSqsV1, val deadLetter: Boolean,
        internal val url: String, handle: String, body: String) {
        private var retainedHandle: String? = handle
        private var retainedBody: String? = body
        internal var released = false
        internal var ackClaimed = false
        internal var acked = false
        internal fun handle(): String = checkNotNull(retainedHandle)
        internal fun locator(): TestActiveQueueJsonV1.Locator {
            owner.requireDelivery(this)
            return TestActiveQueueJsonV1.notification(checkNotNull(retainedBody), owner.original.routing.journalConfiguration)
        }
        internal fun erase() { retainedHandle = null; retainedBody = null }
        override fun toString(): String = "QueueDelivery(original-owned,untrusted,redacted)"
    }

    internal class Call(internal val owner: TestActiveOwnerDeleteSqsV1, val action: String, val queueUrl: String?, fields: Map<String, Any>,
        private val nanoTime: () -> Long) {
        val expected: JsonNode = ObjectMapper().valueToTree(fields)
        private val started = nanoTime()
        private val allowance = owner.remainingMillis(Int.MAX_VALUE).toLong() * 1000000L
        private var lastElapsed = 0L
        private var expired = false
        @Synchronized fun remainingMillis(): Int {
            val elapsed = nanoTime() - started
            val remaining = (allowance - elapsed) / 1000000L
            if (elapsed < lastElapsed || elapsed < 0 || remaining <= 0) expired = true
            requireQueue(!expired); lastElapsed = elapsed
            return owner.remainingMillis(minOf(remaining, Int.MAX_VALUE.toLong()).toInt())
        }
        fun check() { remainingMillis() }
        override fun toString(): String = "QueueCall($action,redacted)"
    }

    private class Client(private val owner: TestActiveOwnerDeleteSqsV1, private val credentials: AwsSessionCredentials,
        private val factory: (remainingMillis: () -> Int) -> SdkHttpClient) : AutoCloseable {
        val endpoint: URI
        private var transport: BoundedTestActiveSqsHttpV1? = null
        private var sdk: SqsClient? = null
        @Volatile private var opening = false
        private val closed = AtomicBoolean()
        private val sdkCloseIssued = AtomicBoolean()
        private val closeFailure = AtomicReference<Throwable?>()
        init {
            val region = Region.of(owner.declaration.journalLocation.region)
            requireQueue(PartitionMetadata.of(region).id() == "aws" && region in SqsClient.serviceMetadata().regions())
            val supplied = SqsClient.serviceMetadata().endpointFor(region)
            endpoint = if (supplied.scheme == null) URI.create("https://$supplied") else supplied
            requireQueue(endpoint.scheme == "https" && endpoint.host == "sqs.${region.id()}.amazonaws.com" && endpoint.port == -1 &&
                endpoint.path.isEmpty() && endpoint.rawQuery == null && endpoint.rawUserInfo == null && endpoint.rawFragment == null)
        }
        fun open() {
            owner.check(); requireQueue(!closed.get() && sdk == null && transport == null); opening = true
            try {
                val bounded = BoundedTestActiveSqsHttpV1(owner, endpoint, credentials, factory).also { transport = it }
                bounded.open()
                owner.check(); requireQueue(!closed.get())
                val profiles = ProfileFile.aggregator().build()
                val millis = owner.declaration.limits.deadlines.queueCallMillis.toLong()
                sdk = SqsClient.builder().region(Region.of(owner.declaration.journalLocation.region)).endpointOverride(endpoint)
                    .credentialsProvider(StaticCredentialsProvider.create(credentials)).defaultsMode(DefaultsMode.STANDARD)
                    .dualstackEnabled(false).fipsEnabled(false).httpClient(bounded)
                    .overrideConfiguration(ClientOverrideConfiguration.builder().defaultProfileFile(profiles).defaultProfileName("kira-active-sqs-v1")
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()).apiCallTimeout(Duration.ofMillis(millis))
                        .apiCallAttemptTimeout(Duration.ofMillis(millis)).build()).build()
                if (closed.get()) { closeSdk(); requireQueue(false) }
                owner.check()
            } finally { opening = false }
        }
        fun <T> execute(call: Call, action: (SqsClient, AwsRequestOverrideConfiguration, () -> JsonNode) -> T): T {
            call.check(); requireQueue(call.owner === owner && !closed.get())
            val bounded = checkNotNull(transport)
            val duration = Duration.ofMillis(call.remainingMillis().toLong())
            val overrides = AwsRequestOverrideConfiguration.builder().apiCallTimeout(duration).apiCallAttemptTimeout(duration).build()
            return withJournalPublicationCleanup({
                bounded.begin(call)
                action(checkNotNull(sdk), overrides) { bounded.observation(call) }.also { call.check() }
            }) { bounded.finishRequest(); call.check() }
        }
        private fun closeSdk() { sdk?.let { if (sdkCloseIssued.compareAndSet(false, true)) it.close() } }
        @Synchronized override fun close() {
            val wasOpening = opening
            closed.set(true)
            val problem = runCatching {
                withJournalPublicationCleanup({ transport?.close() }, ::closeSdk)
                requireQueue(!wasOpening && !opening)
            }.exceptionOrNull()
            if (problem != null) retainActiveQueueNativeFailure(closeFailure, problem)
            closeFailure.get()?.let { throw it }
        }
    }
    companion object {
        internal fun begin(original: TestActiveOwnerDeleteQueueV1, credentials: AwsSessionCredentials,
            factory: (remainingMillis: () -> Int) -> SdkHttpClient, nanoTime: () -> Long) =
            TestActiveOwnerDeleteSqsV1(original, credentials, factory, nanoTime)
    }
}
