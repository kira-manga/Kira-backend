package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureV1
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalV1
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalFetchedVersionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalS3HttpWireV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOrdinaryInventoryS3ClientV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.journalMetadata
import me.manga.kira.backend.complaint.infrastructure.journal.aws.journalS3UrlConnectionClient
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveRecurrentScanV1
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.aws.AwsTestOwnerDeleteDataKeyAdapterV1
import me.manga.kira.backend.security.aws.journalKmsUrlConnectionClient
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Native half of one registered ordinary drain. Only the original can start/retain it; SQL owns
 * the paid staging, exact pair comparison and cut. No event/authority factory, ordinary PUT,
 * fabricated listing for recovery, unversioned GET or per-page deadline renewal exists here.
 *
 * A pass retains only its two-row native page and current authenticated event. Each S3 exchange,
 * KMS lease and fetched byte buffer is released before staging calls SQL. Both actual client
 * constructions close before pass completion or a recovery readback becomes usable. Timeouts are
 * finite local limits, not a claim of hard native/DNS cancellation completion.
 */
internal class TestOrdinaryInventoryReaderV1 private constructor(
    private val original: TestRunOrdinaryDrainV1?,
    private val credentials: AwsSessionCredentials,
    private val s3Http: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val kmsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val clock: Clock,
    private val nanoTime: () -> Long,
    private val catalog: CatalogTestRunTerminalV1? = null,
    private val erasure: TestRunErasureV1? = null,
    private val active: TestActiveRecurrentScanV1? = null,
) : AutoCloseable {
    init { requireJournalPublication(listOfNotNull(original, catalog, erasure, active).size == 1) }
    internal val routing = original?.routing ?: catalog?.routing ?: erasure?.routing ?: checkNotNull(active).routing
    private val writer = original?.writer ?: catalog?.writer ?: erasure?.writer ?: checkNotNull(active).writer
    private val scope = original?.scope ?: catalog?.scope ?: erasure?.scope ?: checkNotNull(active).scope
    private val cutoff = original?.cutoff ?: catalog?.cutoff ?: erasure?.cutoff ?: checkNotNull(active).cutoff
    internal val ordinaryPrefix = routing.journalConfiguration.ordinaryPrefix
    private val declaration = routing.journalConfiguration.declaration()
    private val budget = original?.budget ?: catalog?.budget ?: erasure?.budget ?: checkNotNull(active).budget
    private val retainedKeys = declaration.routing.keys.map { it.keyId }.toSet()
    private val routePrefix = "${ordinaryPrefix}writer/${writer}/epoch/"
    private val maximumVersions = declaration.limits.capacity.maximumRetainedVersions
    // Ciphertext is NOT the LP32 framing budget B. SQL enforces that separate paid staging bound.
    private val maximumCiphertextBytes = Math.multiplyExact(maximumVersions, declaration.limits.decoder.maximumEnvelopeBytes.toLong())
    private val maximumPages = Math.addExact(maximumVersions, 1L)
    private val retention = TestOwnerDeleteRetentionV1(routing, clock)
    private val started = nanoTime()
    private val allowanceNanos = declaration.limits.deadlines.scanMillis * 1_000_000L
    private var lastElapsed = 0L
    private var expired = false
    private val busy = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val failure = AtomicReference<Throwable?>()
    private val graph = AtomicReference<NativeGraph?>()

    @Volatile private var stage = Stage.READY
    @Volatile private var completedPasses = 0
    @Volatile private var currentReadback: Observed? = null
    private val catalogEntries = arrayOfNulls<List<CatalogOrdinaryInventoryEntryV1>>(2)

    init {
        // The supported original has four acquired keys; do not change the global TEST-J 1..4 grammar.
        requireJournalPublication(retainedKeys.size == 4 && routing.descriptors().map { it.logicalKeyId }.toSet() == retainedKeys)
        requireJournalPublication(routing.journalConfiguration.scope.id == scope && declaration.writer.generationId == writer && cutoff > 0)
        requireJournalPublication(maximumVersions > 0 && maximumCiphertextBytes > 0)
        remainingTotalMillis(1)
    }

    fun scanPass(pass: Int) = owned {
        requireJournalPublication((stage == Stage.READY || active != null && stage == Stage.RECOVERY_RELEASED) && pass in 1..2 && pass == completedPasses + 1)
        currentReadback = null
        stage = Stage.BEGIN_PASS
        val startedAt = now()
        if (original != null) original.beginInventoryPass(this, pass, startedAt) // No native graph has been opened.
        else if (catalog != null) catalog.beginOrdinaryInventoryPass(this, pass, startedAt)
        else if (erasure != null) erasure.beginOrdinaryInventoryPass(this, pass, startedAt)
        else checkNotNull(active).beginInventoryPass(this, pass, startedAt)
        requireReader()
        stage = Stage.INVENTORY
        var versionCount = 0L
        var ciphertextBytes = 0L
        var inspectedVersions = 0L
        var inspectedCiphertextBytes = 0L
        val observed = if (catalog == null && erasure == null) null else ArrayList<CatalogOrdinaryInventoryEntryV1>()
        val seen = if (catalog == null && erasure == null) null else HashSet<Pair<String, String>>()
        var catalogFramedBytes = 0L
        withJournalPublicationCleanup(
            {
                val native = openGraph()
                var cursor: TestOrdinaryInventoryS3ClientV1.Cursor? = null
                var pages = 0L
                while (true) {
                    active?.betweenNativeCalls(this)
                    requireNativeRead()
                    requireJournalPublication(pages < maximumPages, JournalPublicationFailureV1.LIMIT_EXCEEDED)
                    pages++
                    val page = native.s3().listPage(cursor)
                    requireJournalPublication(page.entries.size.toLong() <= maximumVersions - inspectedVersions, JournalPublicationFailureV1.LIMIT_EXCEEDED)
                    page.entries.forEach { listed ->
                        active?.betweenNativeCalls(this)
                        requireJournalPublication(listed.size <= maximumCiphertextBytes - inspectedCiphertextBytes, JournalPublicationFailureV1.LIMIT_EXCEEDED)
                        val readback = readVersion(native, listed.key, listed.version, listed)
                        requireJournalPublication(readback.ciphertextByteCount <= maximumCiphertextBytes - inspectedCiphertextBytes, JournalPublicationFailureV1.LIMIT_EXCEEDED)
                        inspectedVersions++; inspectedCiphertextBytes += readback.ciphertextByteCount
                        // ACTIVE writers continue in cutoff+1. Those versions are fully authenticated,
                        // counted against the same native limits and deliberately not staged/sealed.
                        if (active != null && readback.event.comparison.epoch > cutoff) return@forEach
                        versionCount++
                        ciphertextBytes += readback.ciphertextByteCount
                        // getVersion finished native S3 cleanup; open finished KMS lease cleanup;
                        // readVersion closed its byte buffer. SQL gets no stream, key lease or bytes.
                        currentReadback = readback
                        stage = Stage.STAGING
                        try {
                            if (original != null) original.stageInventoryVersion(this, pass, readback)
                            else if (active != null) active.stageInventoryVersion(this, pass, readback)
                            else {
                                // No paid staging PK exists in the read-only catalog recheck. Reject
                                // nonadjacent opaque-version cycles explicitly, without filtering.
                                requireJournalPublication(checkNotNull(seen).add(listed.key to listed.version), JournalPublicationFailureV1.INVALID_LISTING)
                                val detached = CatalogOrdinaryInventoryEntryV1.from(readback)
                                catalogFramedBytes = Math.addExact(catalogFramedBytes, detached.framedBytes())
                                requireJournalPublication(catalogFramedBytes <= declaration.limits.capacity.maximumScanStagingBytes &&
                                    checkNotNull(observed).size < Int.MAX_VALUE, JournalPublicationFailureV1.LIMIT_EXCEEDED)
                                if (catalog != null) catalog.stageOrdinaryInventoryVersion(this, pass, readback)
                                else checkNotNull(erasure).stageOrdinaryInventoryVersion(this, pass, readback)
                                checkNotNull(observed).add(detached)
                            }
                            requireReader()
                        } finally {
                            currentReadback = null
                            if (!closed.get() && failure.get() == null) stage = Stage.INVENTORY
                        }
                    }
                    val next = page.next ?: break
                    cursor = next
                    // A two-row page is the entire in-memory set. SQL's exact-pair PK rejects even
                    // nonadjacent same-key opaque-version cycles; no ON CONFLICT/filtering is used.
                }
            },
            ::releaseGraph,
        )
        requireReader()
        val completedAt = now()
        requireJournalPublication(!completedAt.isBefore(startedAt), JournalPublicationFailureV1.INVALID_LISTING)
        stage = Stage.PASS_RELEASED
        completedPasses = pass
        if (original != null) original.completeInventoryPass(this, pass, completedAt, versionCount, ciphertextBytes)
        else if (active != null) active.completeInventoryPass(this, pass, completedAt, versionCount, ciphertextBytes)
        else {
            val sorted = checkNotNull(observed).sortedWith { a, b -> TestOrdinaryDrainRowsV1.compare(a.locator, b.locator) }
            requireJournalPublication(sorted.size.toLong() == versionCount && (pass == 1 || sorted == catalogEntries[0]), JournalPublicationFailureV1.INVALID_READBACK)
            catalogEntries[pass - 1] = sorted
            if (catalog != null) catalog.completeOrdinaryInventoryPass(this, pass, completedAt, versionCount, ciphertextBytes)
            else checkNotNull(erasure).completeOrdinaryInventoryPass(this, pass, completedAt, versionCount, ciphertextBytes)
        }
        remainingTotalMillis(1)
        requireJournalPublication(!closed.get() && failure.get() == null)
        stage = Stage.READY
    }

    fun readForRecovery(key: String, version: String): TestOrdinaryInventoryReadbackV1 = owned {
        requireJournalPublication((original != null || active != null) && catalog == null && stage in setOf(Stage.READY, Stage.RECOVERY_RELEASED))
        currentReadback = null // An earlier observation cannot be reused as the current native read.
        stage = Stage.RECOVERY_LOCATOR
        val exactKey = checkedKey(key)
        val exactVersion = requireJournalVersion(version)
        // The original may have re-admitted its paid durable cut. That never sets this reader's
        // native pass-completion flags: only a new exact GET/decrypt can issue a recovery observation.
        if (original != null) original.requireRecoveryLocator(this, exactKey, exactVersion)
        else checkNotNull(active).requireRecoveryLocator(this, exactKey, exactVersion)
        requireReader()
        stage = Stage.RECOVERY
        val readback = withJournalPublicationCleanup(
            { readVersion(openGraph(), exactKey, exactVersion, null) },
            ::releaseGraph,
        )
        requireReader()
        stage = Stage.RECOVERY_RELEASED
        currentReadback = readback
        if (original != null) requireReleasedReadback(original, readback) else requireReleasedReadback(checkNotNull(active), readback)
        readback
    }

    internal fun requireCompletedPass(original: TestRunOrdinaryDrainV1, pass: Int) {
        requireReader()
        requireJournalPublication(this.original === original && pass in 1..2 && completedPasses >= pass && graph.get() == null)
        requireJournalPublication(stage in setOf(Stage.PASS_RELEASED, Stage.READY, Stage.RECOVERY_RELEASED))
    }

    internal fun requireReleasedReadback(original: TestRunOrdinaryDrainV1, readback: TestOrdinaryInventoryReadbackV1) {
        requireObserved(original, readback)
        requireJournalPublication(stage == Stage.RECOVERY_RELEASED && graph.get() == null)
    }

    internal fun requireCompletedPass(owner: TestActiveRecurrentScanV1, pass: Int) {
        requireReader()
        requireJournalPublication(active === owner && original == null && catalog == null && pass in 1..2 && completedPasses >= pass && graph.get() == null &&
            stage in setOf(Stage.PASS_RELEASED, Stage.READY, Stage.RECOVERY_RELEASED))
    }
    internal fun requireReleasedReadback(owner: TestActiveRecurrentScanV1, readback: TestOrdinaryInventoryReadbackV1) {
        requireActiveObserved(owner, readback)
        requireJournalPublication(stage == Stage.RECOVERY_RELEASED && graph.get() == null)
    }
    /** Actual construction/graph retirement, not a deadline or supplied cleanup bit. No SQL is invoked. */
    internal fun requireRetiredActive(owner: TestActiveRecurrentScanV1, requirePair: Boolean) {
        requireJournalPublication(active === owner && original == null && catalog == null && closed.get() && !busy.get() && graph.get() == null && stage == Stage.CLOSED)
        if (requirePair) requireJournalPublication(completedPasses == 2 && failure.get() == null)
    }

    /** The private wire/client alternative must still belong to the active original reader. */
    internal fun requireNativeRead() {
        requireReader()
        requireJournalPublication(busy.get() && stage in setOf(Stage.INVENTORY, Stage.RECOVERY))
    }

    internal fun remainingNativeMillis(ceilingMillis: Int): Int {
        requireNativeRead()
        return remainingTotalMillis(ceilingMillis)
    }

    internal fun requireInventoryKey(value: String?): String {
        requireNativeRead()
        return checkedKey(value)
    }

    private fun checkedKey(value: String?): String {
        requireJournalPublication(value != null && value.length in 1..1024 && value.all { it in '!'..'~' }, JournalPublicationFailureV1.INVALID_LISTING)
        val key = checkNotNull(value)
        requireJournalPublication(key.startsWith(routePrefix), JournalPublicationFailureV1.INVALID_LISTING)
        val fields = key.removePrefix(routePrefix).split('/')
        requireJournalPublication(fields.size == 3, JournalPublicationFailureV1.INVALID_LISTING)
        val epochText = fields[0]
        requireJournalPublication(epochText.length == 19 && epochText.all { it in '0'..'9' }, JournalPublicationFailureV1.INVALID_LISTING)
        val epoch = epochText.toLongOrNull()
        val maximumEpoch = if (active == null) cutoff else Math.addExact(cutoff, 1)
        requireJournalPublication(epoch != null && epoch in 1..maximumEpoch && epochText == epoch.toString().padStart(19, '0'), JournalPublicationFailureV1.INVALID_LISTING)
        requireJournalPublication(fields[1] in retainedKeys, JournalPublicationFailureV1.INVALID_LISTING)
        ComplaintIdentifiers.fingerprint(fields[2]).fill(0)
        return key
    }

    private fun readVersion(
        native: NativeGraph,
        key: String,
        version: String,
        listed: TestOrdinaryInventoryS3ClientV1.Entry?,
    ): Observed = journalPublicationCall(JournalPublicationFailureV1.INVALID_READBACK) {
        requireNativeRead()
        val codec = native.codec()
        val attempt = codec.startAttempt(budget)
        val fetched = native.s3().getVersion(key, version, attempt)
        withJournalPublicationCleanup(
            {
                val facts = cheapChecks(key, version, listed, fetched)
                requireNativeRead()
                val decoded = codec.openRegisteredOrdinary(declaration.journalLocation.bucket, key, fetched.bytes, attempt)
                requireJournalPublication(
                    decoded.event.belongsTo(routing) && decoded.event.route.objectKey == key && decoded.event.route.eventId == facts.eventId &&
                        decoded.wireSha256 == facts.wireSha256,
                    JournalPublicationFailureV1.INVALID_READBACK,
                )
                if (original != null) original.requireInventoryEvent(decoded.event)
                else if (catalog != null) catalog.requireOrdinaryInventoryEvent(decoded.event)
                else if (erasure != null) erasure.requireOrdinaryInventoryEvent(decoded.event)
                else checkNotNull(active).requireInventoryEvent(decoded.event)
                attempt.remainingMillis(1)
                val verifiedAt = retention.verify(facts.lastModified, facts.retainUntil, facts.requestedRetention)
                requireNativeRead()
                attempt.remainingMillis(1)
                Observed(this, decoded.event, version, facts.wireSha256, facts.lastModified, facts.retainUntil, verifiedAt, fetched.bytes.size.toLong())
            },
            fetched::close,
        )
    }

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

    private fun requireReader() {
        requireConnectionFree()
        failure.get()?.let { throw it }
        requireJournalPublication(!closed.get())
        if (original != null) original.requireInventoryReader(this)
        else if (catalog != null) catalog.requireOrdinaryInventoryReader(this)
        else if (erasure != null) erasure.requireOrdinaryInventoryReader(this)
        else checkNotNull(active).requireInventoryReader(this)
        remainingTotalMillis(1)
    }

    internal fun requireCompletedCatalogPass(owner: CatalogTestRunTerminalV1, pass: Int) {
        requireReader()
        requireJournalPublication(original == null && catalog === owner && pass in 1..2 && completedPasses >= pass && graph.get() == null &&
            stage in setOf(Stage.PASS_RELEASED, Stage.READY))
    }

    internal fun catalogComparisonEntries(owner: CatalogTestRunTerminalV1, pass: Int): List<CatalogOrdinaryInventoryEntryV1> {
        owner.requireRunning(); failure.get()?.let { throw it }
        requireJournalPublication(original == null && catalog === owner && pass in 1..2 && completedPasses >= pass && graph.get() == null &&
            stage in setOf(Stage.PASS_RELEASED, Stage.READY, Stage.CLOSED))
        return checkNotNull(catalogEntries[pass - 1]).toList()
    }

    internal fun requireRetiredCatalogPair(owner: CatalogTestRunTerminalV1) {
        requireConnectionFree(); owner.requireRunning(); failure.get()?.let { throw it }
        requireJournalPublication(original == null && catalog === owner && closed.get() && stage === Stage.CLOSED && !busy.get() && graph.get() == null &&
            completedPasses == 2 && catalogEntries[0] != null && catalogEntries[0] == catalogEntries[1])
    }

    private fun requireCatalogObserved(owner: CatalogTestRunTerminalV1, readback: TestOrdinaryInventoryReadbackV1) {
        requireReader()
        requireJournalPublication(original == null && catalog === owner && readback === currentReadback && stage === Stage.STAGING)
    }

    internal fun requireCompletedErasurePass(owner: TestRunErasureV1, pass: Int) {
        requireReader()
        requireJournalPublication(original == null && catalog == null && erasure === owner && pass in 1..2 && completedPasses >= pass && graph.get() == null &&
            stage in setOf(Stage.PASS_RELEASED, Stage.READY))
    }

    internal fun erasureComparisonEntries(owner: TestRunErasureV1, pass: Int): List<CatalogOrdinaryInventoryEntryV1> {
        owner.requireRunning(); failure.get()?.let { throw it }
        requireJournalPublication(original == null && catalog == null && erasure === owner && pass in 1..2 && completedPasses >= pass && graph.get() == null &&
            stage in setOf(Stage.PASS_RELEASED, Stage.READY, Stage.CLOSED))
        return checkNotNull(catalogEntries[pass - 1]).toList()
    }

    internal fun requireRetiredErasurePair(owner: TestRunErasureV1) {
        requireConnectionFree(); owner.requireRunning(); failure.get()?.let { throw it }
        requireJournalPublication(original == null && catalog == null && erasure === owner && closed.get() && stage === Stage.CLOSED && !busy.get() && graph.get() == null &&
            completedPasses == 2 && catalogEntries[0] != null && catalogEntries[0] == catalogEntries[1])
    }

    private fun requireErasureObserved(owner: TestRunErasureV1, readback: TestOrdinaryInventoryReadbackV1) {
        requireReader()
        requireJournalPublication(original == null && catalog == null && erasure === owner && readback === currentReadback && stage === Stage.STAGING)
    }

    private fun requireObserved(original: TestRunOrdinaryDrainV1, readback: TestOrdinaryInventoryReadbackV1) {
        requireReader()
        requireJournalPublication(this.original === original && readback === currentReadback)
        requireJournalPublication(stage == Stage.STAGING || (stage == Stage.RECOVERY_RELEASED && graph.get() == null))
    }

    private fun requireActiveObserved(owner: TestActiveRecurrentScanV1, readback: TestOrdinaryInventoryReadbackV1) {
        requireReader()
        requireJournalPublication(active === owner && original == null && catalog == null && currentReadback === readback &&
            (stage == Stage.STAGING || stage == Stage.RECOVERY_RELEASED && graph.get() == null))
    }

    @Synchronized
    private fun remainingTotalMillis(ceilingMillis: Int): Int {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        requireJournalPublication(ceilingMillis > 0)
        val elapsed = nanoTime() - started
        val remaining = (allowanceNanos - elapsed) / 1_000_000L
        if (expired || elapsed < 0 || elapsed < lastElapsed || remaining <= 0) {
            expired = true
            throw JournalPublicationExceptionV1(JournalPublicationFailureV1.DEADLINE_EXHAUSTED)
        }
        lastElapsed = elapsed
        val total = runCatching { budget.remainingMillis(minOf(remaining, ceilingMillis.toLong())) }
        if (total.isFailure) expired = true
        val allowed = total.getOrThrow().toInt()
        return active?.remainingNativeMillis(allowed) ?: allowed
    }

    private fun now(): Instant = clock.instant().also {
        OrdinaryJournalRetentionV1.requireInstant(it, false)
        remainingTotalMillis(1)
    }

    private fun openGraph(): NativeGraph {
        requireNativeRead()
        val owned = NativeGraph()
        requireJournalPublication(graph.compareAndSet(null, owned))
        owned.open(this, credentials, s3Http, kmsHttp, nanoTime)
        requireNativeRead()
        return owned
    }

    private fun releaseGraph() {
        val owned = graph.get() ?: return
        journalPublicationClose { owned.close() }
        requireJournalPublication(graph.compareAndSet(owned, null), JournalPublicationFailureV1.CLEANUP_FAILURE)
    }

    private fun rememberFailure(observed: Throwable) {
        failure.updateAndGet { prior -> if (replaceJournalPublicationFailure(prior, observed)) observed else prior }
        currentReadback = null
        stage = Stage.FAILED
    }

    private fun <T> owned(action: () -> T): T = journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
        requireConnectionFree()
        requireJournalPublication(!closed.get() && busy.compareAndSet(false, true))
        try {
            val result = runCatching {
                journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
                    requireReader()
                    requireJournalPublication(graph.get() == null)
                    action()
                }
            }
            val rejected = result.exceptionOrNull()
            if (rejected != null) {
                rememberFailure(rejected)
                withJournalPublicationCleanup({ throw rejected }) {
                    val closing = runCatching(::releaseGraph).exceptionOrNull()
                    if (closing != null) { rememberFailure(closing); throw closing }
                }
            } else result.getOrThrow()
        } finally {
            busy.set(false)
        }
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        currentReadback = null
        stage = Stage.CLOSED
        val closing = runCatching { journalPublicationClose { releaseGraph() } }.exceptionOrNull()
        if (closing != null) rememberFailure(closing)
        failure.get()?.let { throw it }
    }

    override fun toString(): String = "TestOrdinaryInventoryReaderV1(original-only,redacted,no-cut-authority)"

    private class Facts(val eventId: String, val wireSha256: String, val lastModified: Instant, val retainUntil: Instant, val requestedRetention: String)

    private class Observed(
        private val reader: TestOrdinaryInventoryReaderV1,
        override val event: TestOwnerDeleteJournalEventV1,
        override val versionId: String,
        override val wireSha256: String,
        override val lastModified: Instant,
        override val retainUntil: Instant,
        override val verifiedAt: Instant,
        override val ciphertextByteCount: Long,
    ) : TestOrdinaryInventoryReadbackV1 {
        override fun requireOriginal(original: TestRunOrdinaryDrainV1) = reader.requireObserved(original, this)
        override fun requireCatalog(original: CatalogTestRunTerminalV1) = reader.requireCatalogObserved(original, this)
        override fun requireErasure(original: TestRunErasureV1) = reader.requireErasureObserved(original, this)
        override fun requireActive(original: TestActiveRecurrentScanV1) = reader.requireActiveObserved(original, this)
        override fun toString(): String = "TestOrdinaryInventoryReadbackV1(current-native-observation,redacted,no-apply-authority)"
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
            reader: TestOrdinaryInventoryReaderV1,
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

    private enum class Stage { READY, BEGIN_PASS, INVENTORY, STAGING, PASS_RELEASED, RECOVERY_LOCATOR, RECOVERY, RECOVERY_RELEASED, FAILED, CLOSED }

    companion object {
        internal fun beginActive(original: TestActiveRecurrentScanV1, credentials: AwsSessionCredentials,
            s3: (remainingMillis: () -> Int) -> SdkHttpClient, kms: (remainingMillis: () -> Int) -> SdkHttpClient,
            clock: Clock, nanoTime: () -> Long): TestOrdinaryInventoryReaderV1 = journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
            requireConnectionFree(); original.requireInventoryStart()
            TestOrdinaryInventoryReaderV1(null, credentials, s3, kms, clock, nanoTime, active = original)
        }
        internal fun beginCatalog(
            original: CatalogTestRunTerminalV1, credentials: AwsSessionCredentials,
            s3: (() -> SdkHttpClient)? = null, kms: (() -> SdkHttpClient)? = null,
            clock: Clock = Clock.systemUTC(), nanoTime: () -> Long = System::nanoTime,
        ): TestOrdinaryInventoryReaderV1 = journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
            requireConnectionFree(); original.requireOrdinaryInventoryStart()
            TestOrdinaryInventoryReaderV1(null, credentials,
                if (s3 == null) ::journalS3UrlConnectionClient else { _ -> s3() },
                if (kms == null) ::journalKmsUrlConnectionClient else { _ -> kms() }, clock, nanoTime, original)
        }

        internal fun beginErasure(
            original: TestRunErasureV1, credentials: AwsSessionCredentials,
            s3: (() -> SdkHttpClient)? = null, kms: (() -> SdkHttpClient)? = null,
            clock: Clock = Clock.systemUTC(), nanoTime: () -> Long = System::nanoTime,
        ): TestOrdinaryInventoryReaderV1 = journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
            requireConnectionFree(); original.requireOrdinaryInventoryStart()
            TestOrdinaryInventoryReaderV1(null, credentials,
                if (s3 == null) ::journalS3UrlConnectionClient else { _ -> s3() },
                if (kms == null) ::journalKmsUrlConnectionClient else { _ -> kms() }, clock, nanoTime, erasure = original)
        }

        fun begin(
            original: TestRunOrdinaryDrainV1,
            credentials: AwsSessionCredentials,
            s3: (() -> SdkHttpClient)? = null,
            kms: (() -> SdkHttpClient)? = null,
            clock: Clock = Clock.systemUTC(),
            nanoTime: () -> Long = System::nanoTime,
        ): TestOrdinaryInventoryReaderV1 = journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
            requireConnectionFree()
            original.requireInventoryStart()
            TestOrdinaryInventoryReaderV1(
                original, credentials,
                if (s3 == null) ::journalS3UrlConnectionClient else { _ -> s3() },
                if (kms == null) ::journalKmsUrlConnectionClient else { _ -> kms() },
                clock, nanoTime,
            )
        }
    }
}

/** Only the reader's private Observed implements this; locators/SQL rows cannot reconstruct it. */
internal sealed interface TestOrdinaryInventoryReadbackV1 : TestOwnerDeleteJournalReadbackV1 {
    val ciphertextByteCount: Long
    fun requireOriginal(original: TestRunOrdinaryDrainV1)
    fun requireCatalog(original: CatalogTestRunTerminalV1)
    fun requireErasure(original: TestRunErasureV1)
    fun requireActive(original: TestActiveRecurrentScanV1)
}

/** Bounded scalar native comparison only; no event/plaintext/credentials or portable proof is retained. */
internal data class CatalogOrdinaryInventoryEntryV1(
    val key: String, val version: String, val wireSha256: String, val canonicalSha256: String,
    val eventId: String, val kind: String, val epoch: Long, val ciphertextBytes: Long,
    val lastModified: Instant, val retainUntil: Instant,
) {
    val locator: Pair<String, String> get() = key to version
    fun fields(): List<String> = listOf(key, version, wireSha256)
    fun framedBytes(): Long = EpochSealFramesV1.frame(fields()).let { try { it.size.toLong() } finally { it.fill(0) } }
    override fun toString(): String = "CatalogOrdinaryInventoryEntryV1(native-comparison-only,redacted)"
    companion object {
        internal fun from(read: TestOrdinaryInventoryReadbackV1): CatalogOrdinaryInventoryEntryV1 = CatalogOrdinaryInventoryEntryV1(
            read.event.route.objectKey, read.versionId, read.wireSha256, read.event.semanticSha256, read.event.route.eventId,
            read.event.comparison.eventKind.name, read.event.comparison.epoch, read.ciphertextByteCount, read.lastModified, read.retainUntil)
    }
}
