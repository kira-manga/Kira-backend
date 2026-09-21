package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalFetchedVersionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalS3HttpWireV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOrdinaryInventoryS3ClientV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.journalMetadata
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.requireQueue
import me.manga.kira.backend.complaint.infrastructure.reconciliation.retainActiveQueueNativeFailure
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.aws.AwsTestOwnerDeleteDataKeyAdapterV1
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Exact notification locator GET only. Native receipt is not ACTIVE recovery permission or scan proof. */
internal class TestActiveQueueJournalReaderV1 private constructor(
    private val original: TestActiveOwnerDeleteQueueV1,
    private val credentials: AwsSessionCredentials,
    private val s3Factory: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val kmsFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val clock: Clock,
    private val nanoTime: () -> Long,
) : AutoCloseable {
    internal val routing = original.routing
    internal val ordinaryPrefix = routing.journalConfiguration.ordinaryPrefix
    internal val budget = original.budget
    private val declaration = routing.journalConfiguration.declaration()
    private val retention = TestOwnerDeleteRetentionV1(routing, clock)
    private val graph = AtomicReference<NativeGraph?>()
    private val busy = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val failure = AtomicReference<Throwable?>()
    private var observed: Observed? = null

    internal fun read(): TestActiveQueueReadbackV1 {
        requireConnectionFree(); original.requireJournalNative(this)
        requireQueue(!closed.get() && observed == null && busy.compareAndSet(false, true))
        return try {
            val native = NativeGraph(); requireQueue(graph.compareAndSet(null, native))
            val readback = withJournalPublicationCleanup({
                native.open(this, credentials, s3Factory, kmsFactory, nanoTime)
                requireNativeRead()
                val locator = original.journalLocator(this)
                val key = requireInventoryKey(locator.key); val version = requireJournalVersion(locator.version)
                val attempt = native.codec().startAttempt(budget)
                val fetched = native.s3().getVersion(key, version, attempt)
                withJournalPublicationCleanup({
                    requireQueue(fetched.bytes.size.toLong() == locator.size)
                    val facts = cheapChecks(key, version, null, fetched)
                    val decoded = native.codec().openRegisteredOrdinary(declaration.journalLocation.bucket, key, fetched.bytes, attempt)
                    requireQueue(decoded.event.belongsTo(routing) && decoded.event.route.objectKey == key && decoded.event.route.eventId == facts.eventId &&
                        decoded.wireSha256 == facts.wireSha256)
                    original.requireDecoded(this, decoded.event)
                    val verifiedAt = retention.verify(facts.lastModified, facts.retainUntil, facts.requestedRetention)
                    requireNativeRead(); attempt.remainingMillis(1)
                    Observed(this, decoded.event, version, facts.wireSha256, facts.lastModified, facts.retainUntil, verifiedAt)
                }, fetched::close)
            }, ::releaseGraph)
            requireNativeRead(); requireQueue(graph.get() == null)
            observed = readback
            readback
        } catch (problem: Throwable) { retainActiveQueueNativeFailure(failure, problem); throw checkNotNull(failure.get()) }
        finally { busy.set(false) }
    }
    internal fun requireNativeRead() {
        requireConnectionFree(); original.requireJournalNative(this)
        requireQueue(!closed.get() && busy.get()); failure.get()?.let { throw it }
        original.remainingNativeMillis(1)
    }
    internal fun remainingNativeMillis(ceiling: Int): Int { requireNativeRead(); return original.remainingNativeMillis(ceiling) }
    internal fun requireInventoryKey(value: String?): String {
        requireNativeRead()
        val locator = original.journalLocator(this)
        requireQueue(value != null && value == locator.key)
        val key = checkNotNull(value)
        val prefix = "${ordinaryPrefix}writer/${declaration.writer.generationId}/epoch/"
        requireQueue(key.startsWith(prefix))
        val fields = key.removePrefix(prefix).split('/')
        requireQueue(fields.size == 3 && fields[0].length == 19 && fields[0].all { it in '0'..'9' })
        val epoch = fields[0].toLongOrNull(); requireQueue(epoch != null && epoch > 0 && epoch.toString().padStart(19, '0') == fields[0])
        requireQueue(fields[1] in declaration.routing.keys.map { it.keyId })
        ComplaintIdentifiers.fingerprint(fields[2]).fill(0)
        return key
    }
    private fun requireObserved(original: TestActiveOwnerDeleteQueueV1, value: Observed) {
        requireConnectionFree(); requireQueue(this.original === original && observed === value && !closed.get() && failure.get() == null &&
            !busy.get() && graph.get() == null)
        original.requireJournalNative(this)
    }
    private fun releaseGraph() {
        val native = graph.get() ?: return
        native.close(); requireQueue(graph.compareAndSet(native, null))
    }
    internal fun requireClosed() = requireQueue(closed.get() && graph.get() == null && !busy.get() && failure.get() == null)
    override fun close() {
        closed.set(true)
        val problem = runCatching(::releaseGraph).exceptionOrNull()
        if (problem != null) retainActiveQueueNativeFailure(failure, problem)
        failure.get()?.let { throw it }
        requireQueue(!busy.get())
    }
    override fun toString() = "ActiveQueueJournalReader(exact-native-get,redacted,no-apply-authority)"
    private fun cheapChecks(
        key: String,
        version: String,
        listed: TestOrdinaryInventoryS3ClientV1.Entry?,
        fetched: JournalFetchedVersionV1,
    ): Facts {
        val response = fetched.response
        val raw = fetched.observed.response
        val headers = raw.headers()
        requireJournalPublication(raw.statusCode() == 200 && response.sdkHttpResponse().statusCode() == 200, JournalPublicationFailureV1.INVALID_READBACK)
        requireJournalPublication(
            requireJournalVersion(response.versionId()) == version && JournalS3HttpWireV1.single(headers, "x-amz-version-id") == version,
            JournalPublicationFailureV1.INVALID_READBACK,
        )
        requireJournalPublication(
            response.contentLength() == fetched.bytes.size.toLong() && fetched.observed.size == fetched.bytes.size &&
                response.deleteMarker() != true && response.contentRange() == null && response.contentEncoding() == null && response.expiration() == null,
            JournalPublicationFailureV1.INVALID_READBACK,
        )
        requireJournalPublication(
            response.contentType() == JournalS3HttpWireV1.CONTENT_TYPE && JournalS3HttpWireV1.single(headers, "Content-Type") == JournalS3HttpWireV1.CONTENT_TYPE,
            JournalPublicationFailureV1.INVALID_READBACK,
        )
        val wireSha256 = wholeWireChecksum(fetched)
        val metadata = JournalS3HttpWireV1.metadata(headers)
        val eventId = metadata["kira-journal-event-id"] ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.INVALID_READBACK)
        ComplaintIdentifiers.fingerprint(eventId).fill(0)
        val requested = metadata["kira-journal-retain-until"] ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.RETENTION_MISMATCH)
        requireJournalPublication(
            metadata == journalMetadata(eventId, wireSha256, requested) && response.metadata() == metadata && (response.missingMeta() == null || response.missingMeta() == 0),
            JournalPublicationFailureV1.INVALID_READBACK,
        )
        requireJournalPublication(
            response.objectLockModeAsString() == "COMPLIANCE" && JournalS3HttpWireV1.single(headers, "x-amz-object-lock-mode") == "COMPLIANCE",
            JournalPublicationFailureV1.RETENTION_MISMATCH,
        )
        val lastModified = response.lastModified() ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.RETENTION_MISMATCH)
        val retainUntil = response.objectLockRetainUntilDate() ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.RETENTION_MISMATCH)
        val rawModified = JournalS3HttpWireV1.single(headers, "Last-Modified") ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.RETENTION_MISMATCH)
        val rawRetention = JournalS3HttpWireV1.single(headers, "x-amz-object-lock-retain-until-date")
            ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.RETENTION_MISMATCH)
        requireJournalPublication(
            Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(rawModified)) == lastModified && Instant.parse(rawRetention) == retainUntil,
            JournalPublicationFailureV1.RETENTION_MISMATCH,
        )
        if (listed != null) requireJournalPublication(
            listed.key == key && listed.version == version && listed.size == fetched.bytes.size.toLong() && listed.lastModified == lastModified,
            JournalPublicationFailureV1.INVALID_READBACK,
        )
        retention.verify(lastModified, retainUntil, requested)
        return Facts(eventId, wireSha256, lastModified, retainUntil, requested)
    }

    private fun wholeWireChecksum(fetched: JournalFetchedVersionV1): String {
        val headers = fetched.observed.response.headers()
        val checksum = JournalS3HttpWireV1.single(headers, "x-amz-checksum-sha256")
            ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.CHECKSUM_MISMATCH)
        requireJournalPublication(
            checksum.length == 44 && fetched.response.checksumSHA256() == checksum &&
                JournalS3HttpWireV1.single(headers, "x-amz-checksum-type") in listOf(null, "FULL_OBJECT"),
            JournalPublicationFailureV1.CHECKSUM_MISMATCH,
        )
        val supplied = Base64.getDecoder().decode(checksum)
        val computed = MessageDigest.getInstance("SHA-256").digest(fetched.bytes)
        try {
            requireJournalPublication(
                supplied.size == 32 && Base64.getEncoder().encodeToString(supplied) == checksum && MessageDigest.isEqual(supplied, computed),
                JournalPublicationFailureV1.CHECKSUM_MISMATCH,
            )
        } finally {
            supplied.fill(0)
            computed.fill(0)
        }
        val hash = Sha256.hex(fetched.bytes)
        requireJournalPublication(hash == fetched.observed.wireSha256, JournalPublicationFailureV1.CHECKSUM_MISMATCH)
        return hash
    }


    private class Facts(val eventId: String, val wireSha256: String, val lastModified: Instant, val retainUntil: Instant, val requestedRetention: String)
    private class Observed(private val reader: TestActiveQueueJournalReaderV1,
        override val event: TestOwnerDeleteJournalEventV1, override val versionId: String, override val wireSha256: String,
        override val lastModified: Instant, override val retainUntil: Instant, override val verifiedAt: Instant) : TestActiveQueueReadbackV1 {
        override fun requireOriginal(original: TestActiveOwnerDeleteQueueV1) = reader.requireObserved(original, this)
        override fun toString() = "ActiveQueueReadback(exact-native,redacted,no-apply-authority)"
    }
    private class NativeGraph : AutoCloseable {
        private val s3Construction = TestOrdinaryInventoryS3ClientV1.Construction()
        private val keysConstruction = AwsTestOwnerDeleteDataKeyAdapterV1.Construction()
        private var opened = false
        private var closed = false
        @Volatile private var opening = false
        private var s3: TestOrdinaryInventoryS3ClientV1? = null
        private var codec: TestOwnerDeleteJournalCodecV1? = null
        private var failure: Throwable? = null

        fun open(
            reader: TestActiveQueueJournalReaderV1,
            credentials: AwsSessionCredentials,
            s3Http: (remainingMillis: () -> Int) -> SdkHttpClient,
            kmsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
            nanoTime: () -> Long,
        ) {
            reader.requireNativeRead()
            synchronized(this) {
                requireJournalPublication(!opened && !closed)
                opened = true
                opening = true
            }
            try {
                val constructionAttempt = TestOwnerDeleteCodecAttemptV1(reader.routing, nanoTime, reader.budget)
                val keys = keysConstruction.open(reader.routing.journalConfiguration, credentials, kmsHttp, nanoTime, constructionAttempt)
                reader.requireNativeRead()
                s3 = s3Construction.open(reader, credentials, s3Http, nanoTime)
                reader.requireNativeRead()
                codec = TestOwnerDeleteJournalCodecV1(reader.routing, keys, nanoTime = nanoTime)
            } finally {
                opening = false
            }
        }

        fun s3(): TestOrdinaryInventoryS3ClientV1 = checkNotNull(s3)
        fun codec(): TestOwnerDeleteJournalCodecV1 = checkNotNull(codec)

        @Synchronized
        override fun close() {
            val constructionWasActive = opening
            closed = true
            val closing = runCatching {
                withJournalPublicationCleanup(
                    { journalPublicationClose { s3Construction.close() } },
                    { journalPublicationClose { keysConstruction.close() } },
                )
                // A concurrent close during construction cannot detach this graph before a late
                // returned client has been observed and closed. The original retains it on failure.
                requireJournalPublication(!constructionWasActive && !opening, JournalPublicationFailureV1.CLEANUP_FAILURE)
            }.exceptionOrNull()
            if (closing != null && replaceJournalPublicationFailure(failure, closing)) failure = closing
            failure?.let { throw it }
        }
    }

    companion object {
        internal fun begin(original: TestActiveOwnerDeleteQueueV1, credentials: AwsSessionCredentials,
            s3: (remainingMillis: () -> Int) -> SdkHttpClient, kms: (remainingMillis: () -> Int) -> SdkHttpClient,
            clock: Clock, nanoTime: () -> Long) = TestActiveQueueJournalReaderV1(original, credentials, s3, kms, clock, nanoTime)
    }
}

internal sealed interface TestActiveQueueReadbackV1 : TestOwnerDeleteJournalReadbackV1 {
    fun requireOriginal(original: TestActiveOwnerDeleteQueueV1)
}
