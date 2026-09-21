package me.manga.kira.backend.complaint.infrastructure.journal

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalFetchedVersionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalListedVersionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalS3HttpWireV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestActiveInitialCheckpointS3ClientV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveInitialCheckpointV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.requireInitialCheckpoint
import me.manga.kira.backend.security.TestTerminalAttemptV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalContentV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.aws.AwsTestTerminalDataKeyAdapterV1
import me.manga.kira.backend.security.aws.EpochSealStsClientOwner
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * One closed read-only graph of the current original. Exact native STS + seal LIST/GET/AEAD/KMS,
 * then independent whole-prefix pass constructions; no PUT/Generate, arbitrary proof or entry API.
 * Each proof is private-origin and issued only after its ACTUAL native graph/buffers have closed.
 */
internal class TestActiveInitialCheckpointReaderV1 private constructor(
    private val original: TestActiveInitialCheckpointV1,
    private val recipe: VersionBoundTestActiveInitialCheckpointV1,
    credentials: AwsSessionCredentials,
    private val s3Factory: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val kmsFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val clock: Clock,
    private val nanoTime: () -> Long,
) : AutoCloseable {
    internal val routing = original.routing
    private val attempt = original.codecAttempt
    private var material: AwsSessionCredentials? = credentials
    private var stage = Stage.NEW
    @Volatile private var closed = false
    private var failed = false
    private var principal: EpochSealStsClientOwner? = null
    private val sealConstruction = TestActiveInitialCheckpointS3ClientV1.Construction()
    private val keysConstruction = AwsTestTerminalDataKeyAdapterV1.Construction()
    private val passConstructions = List(2) { TestActiveInitialCheckpointS3ClientV1.Construction() }
    private var fetched: JournalFetchedVersionV1? = null
    private var content: TestTerminalContentV1? = null
    private var sealClosed = false
    private val passClosed = BooleanArray(2)
    private var cleanupFailure: Throwable? = null
    private var facts: Facts? = null
    private var sealProof: SealProof? = null
    private val passes = arrayOfNulls<EmptyPass>(2)
    private var lastUtc: Instant? = null
    private var pendingPass: PassFacts? = null

    internal fun verifySeal(): SealProof {
        requireConnectionFree(); original.requireReader(this)
        requireInitialCheckpoint(stage === Stage.NEW && !closed && !failed)
        stage = Stage.AUTHENTICATING
        return guarded {
            attempt.bindInitialCheckpointReader(this)
            withJournalPublicationCleanup({
                recipe.authenticate(this, attempt)
                stage = Stage.SEAL
                requireSealRead()
                val credentials = checkNotNull(material)
                val s3 = sealConstruction.open(this, credentials, s3Factory, nanoTime)
                val keys = keysConstruction.open(routing.journalConfiguration, credentials, attempt, kmsFactory)
                val row = original.frozenRow()
                val bytes = row.canonicalBytes()
                try {
                    content = original.codec.restoreCanonical(TestTerminalCodecKindV1.EPOCH_SEAL, bytes, row.binding.routingKeyId,
                        row.binding.objectKey, row.canonicalSha256, attempt)
                    val parsed = TestTerminalJsonV1(routing.journalConfiguration).epochSeal(bytes)
                    requireInitialCheckpoint(parsed.epochStartInclusive == 1L && parsed.epochEndInclusive == 1L && parsed.eventCount == 0L &&
                        parsed.eventManifestSha256 == original.manifestSha256 && parsed.precedingSealSha256 == "" &&
                        parsed.preparingFencingToken == row.binding.preparingFencingToken && parsed.preparingFencingToken < original.leaseToken &&
                        parsed.writerGeneration == original.writer && parsed.dataScopeKind == "TEST" && parsed.dataScopeId == original.scope.toString() &&
                        parsed.sealId == row.binding.objectId)
                } finally { bytes.fill(0) }
                val listed = s3.listExactSeal()
                val actual = s3.getExactSeal().also { fetched = it }
                val observed = cheapChecks(row, listed, actual)
                recipe.retention.verifyRetention(observed.first, observed.second, checkNotNull(row.retainUntil))
                requireSealRead()
                val expected = checkNotNull(content)
                val decoded = original.codec.open(routing.journalConfiguration.declaration().journalLocation.bucket,
                    row.binding.objectKey, expected, actual.bytes, attempt, keys)
                requireInitialCheckpoint(decoded.content === expected && decoded.wireSha256 == row.wireSha256)
                val now = recipe.retention.verifyRetention(observed.first, observed.second, checkNotNull(row.retainUntil)).truncatedTo(ChronoUnit.MICROS)
                requireSealRead(); attempt.remainingMillis(1)
                requireInitialCheckpoint(TestActiveInitialCheckpointDocumentV1.time(now) && !now.isBefore(original.sealControl().verifiedAt))
                facts = Facts(listed.versionId, observed.first, observed.second, now)
            }, ::closeSeal)
            requireSealRead(); attempt.remainingMillis(1)
            val proof = SealProof.issue(this)
            sealProof = proof; stage = Stage.SEAL_RELEASED
            proof
        }
    }

    internal fun emptyPass(number: Int): EmptyPass {
        requireConnectionFree(); original.requireReader(this)
        requireInitialCheckpoint(number in 1..2 && original.passNumber == number && passes[number - 1] == null &&
            stage === (if (number == 1) Stage.SEAL_RELEASED else Stage.FIRST_RELEASED))
        val staged = original.stagedPass(number)
        stage = if (number == 1) Stage.FIRST else Stage.SECOND
        return guarded {
            val began = sampleUtc()
            requireInitialCheckpoint(!began.isBefore(staged.startedAt))
            val construction = passConstructions[number - 1]
            withJournalPublicationCleanup({
                val s3 = construction.open(this, checkNotNull(material), s3Factory, nanoTime)
                requirePassRead()
                s3.requireEmptyOrdinaryPrefix() // maxKeys2 on the ENTIRE ordinary prefix, not a supplied subset.
                requirePassRead()
            }, { closePass(number) })
            requirePassRead()
            val ended = sampleUtc()
            requireInitialCheckpoint(!ended.isBefore(began) && !ended.isBefore(staged.startedAt))
            pendingPass = PassFacts(number, staged.startedAt, ended)
            val proof = EmptyPass.issue(this)
            pendingPass = null
            passes[number - 1] = proof
            stage = if (number == 1) Stage.FIRST_RELEASED else Stage.SECOND_RELEASED
            proof
        }
    }

    internal fun requireRecipe(selected: VersionBoundTestActiveInitialCheckpointV1) {
        requireNativeRead(); requireInitialCheckpoint(selected === recipe)
    }
    internal fun retainPrincipal(owner: EpochSealStsClientOwner) {
        requireNativeRead(); requireInitialCheckpoint(stage === Stage.AUTHENTICATING && principal == null); principal = owner
    }
    internal fun requireAttempt(selected: TestTerminalAttemptV1) {
        requireNativeRead(); requireInitialCheckpoint(selected === attempt && stage in setOf(Stage.AUTHENTICATING, Stage.SEAL))
    }
    internal fun requireNativeRead() {
        original.requireReader(this)
        requireInitialCheckpoint(!closed && !failed && cleanupFailure == null && stage in setOf(Stage.AUTHENTICATING, Stage.SEAL, Stage.FIRST, Stage.SECOND))
    }
    internal fun requireSealRead() { requireNativeRead(); requireInitialCheckpoint(stage === Stage.SEAL) }
    internal fun requirePassRead() { requireNativeRead(); requireInitialCheckpoint(stage === Stage.FIRST || stage === Stage.SECOND) }
    internal fun sealObjectKey(): String { requireSealRead(); return original.frozenRow().binding.objectKey }
    internal fun sealVersion(): String { requireSealRead(); return original.sealControl().version }
    internal fun remainingNativeMillis(ceiling: Int): Int {
        requireNativeRead()
        val remaining = original.nativeContinuationMillis(ceiling)
        return if (stage === Stage.AUTHENTICATING || stage === Stage.SEAL) minOf(remaining, attempt.remainingMillis(remaining)) else remaining
    }
    private fun sampleUtc(): Instant {
        requireNativeRead()
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        requireNativeRead()
        requireInitialCheckpoint(TestActiveInitialCheckpointDocumentV1.time(now) && lastUtc?.let { !now.isBefore(it) } != false)
        lastUtc = now
        return now
    }
    private fun <T> guarded(work: () -> T): T = try { work() } catch (problem: Throwable) {
        failed = true; original.observeFailure(problem); original.throwIfSignalled(); throw problem
    }
    private fun cleanup(work: () -> Unit) {
        val problem = runCatching(work).exceptionOrNull()
        if (problem != null) {
            if (replaceJournalPublicationFailure(cleanupFailure, problem)) cleanupFailure = problem
            original.observeFailure(problem)
        }
    }
    @Synchronized
    private fun closeSeal() {
        cleanup { fetched?.close(); fetched = null }
        cleanup { content?.close(); content = null }
        cleanup(keysConstruction::close)
        cleanup(sealConstruction::close)
        cleanup { principal?.close() }
        if (cleanupFailure == null) sealClosed = true
        cleanupFailure?.let { original.throwIfSignalled(); throw it }
    }
    @Synchronized
    private fun closePass(number: Int) {
        cleanup(passConstructions[number - 1]::close)
        if (cleanupFailure == null) passClosed[number - 1] = true
        cleanupFailure?.let { original.throwIfSignalled(); throw it }
    }
    @Synchronized
    override fun close() {
        requireConnectionFree()
        closed = true
        cleanup(::closeSeal)
        cleanup { closePass(1) }; cleanup { closePass(2) }
        material = null
        if (cleanupFailure == null) stage = Stage.CLOSED
        cleanupFailure?.let { original.throwIfSignalled(); throw it }
    }
    /** Local physical-cleanup fact only; can be checked in the later SQL phase without a native/SQL overlap. */
    internal fun requireClosed() {
        requireInitialCheckpoint(closed && stage === Stage.CLOSED && sealClosed && passClosed.all { it } && cleanupFailure == null &&
            fetched == null && content == null && material == null)
    }
    private fun requireSealProof(selected: SealProof, candidate: TestActiveInitialCheckpointV1) {
        original.requireProofOwner(this)
        requireInitialCheckpoint(candidate === original && selected === sealProof && sealClosed && !failed && cleanupFailure == null)
    }
    private fun requirePassProof(selected: EmptyPass, candidate: TestActiveInitialCheckpointV1) {
        original.requireProofOwner(this)
        requireInitialCheckpoint(candidate === original && passes[selected.number - 1] === selected && passClosed[selected.number - 1] &&
            sealProof != null && sealClosed && !failed && cleanupFailure == null)
    }

    class SealProof private constructor(private val reader: TestActiveInitialCheckpointReaderV1, facts: Facts) {
        val version = facts.version
        val lastModified = facts.lastModified
        val retainUntil = facts.retainUntil
        val verifiedAt = facts.verifiedAt
        internal fun requireOriginal(original: TestActiveInitialCheckpointV1) = reader.requireSealProof(this, original)
        internal fun canonicalVerificationBytes(row: TestTerminalDurableRowV1, at: Instant = verifiedAt): ByteArray {
            requireInitialCheckpoint(at.nano % 1000 == 0 && !at.isBefore(lastModified) && !at.isAfter(verifiedAt) && retainUntil.isAfter(at))
            return CanonicalJson.canonicalize(buildJsonObject {
                put("schemaVersion", 1); put("objectKind", "EPOCH_SEAL"); put("role", "ORDINARY")
                put("dataScopeId", row.binding.run.dataScopeId); put("writerGeneration", row.binding.writerGeneration)
                put("epochStartInclusive", row.binding.epochStartInclusive); put("epochEndInclusive", row.binding.epochEndInclusive)
                put("operationToken", row.binding.operationToken); put("configurationSha256", row.binding.run.configurationSha256)
                put("journalConfigurationSha256", row.binding.journalConfigurationSha256)
                put("objectKey", row.binding.objectKey); put("objectId", row.binding.objectId); put("objectVersion", version)
                put("canonicalSha256", row.canonicalSha256); put("ciphertextSha256", checkNotNull(row.wireSha256))
                put("lastModified", lastModified.toString()); put("requestedRetainUntil", checkNotNull(row.retainUntil).toString())
                put("retainUntil", retainUntil.toString()); put("objectLockMode", "COMPLIANCE"); put("verifiedAt", at.toString())
            }).toByteArray(Charsets.UTF_8)
        }

        override fun toString(): String = "InitialCheckpointSealProof(native-authenticated-released,redacted)"
        companion object {
            internal fun issue(reader: TestActiveInitialCheckpointReaderV1): SealProof {
                reader.requireSealRead()
                requireInitialCheckpoint(reader.sealClosed && reader.cleanupFailure == null && reader.sealProof == null &&
                    reader.fetched == null && reader.content == null)
                return SealProof(reader, checkNotNull(reader.facts))
            }
        }
    }
    class EmptyPass private constructor(private val reader: TestActiveInitialCheckpointReaderV1, val number: Int,
        val summary: TestActiveInitialCheckpointDocumentV1.Pass) {
        internal fun requireOriginal(original: TestActiveInitialCheckpointV1) = reader.requirePassProof(this, original)
        override fun toString(): String = "InitialCheckpointEmptyPass(actual-released-native,no-current-authority)"
        companion object {
            internal fun issue(reader: TestActiveInitialCheckpointReaderV1): EmptyPass {
                reader.requirePassRead()
                val facts = checkNotNull(reader.pendingPass)
                val number = facts.number
                requireInitialCheckpoint(number in 1..2 && reader.original.passNumber == number && reader.passes[number - 1] == null &&
                    reader.passClosed[number - 1] && reader.cleanupFailure == null && reader.sealClosed && reader.sealProof != null)
                val row = reader.original.stagedPass(number)
                requireInitialCheckpoint(facts.startedAt == row.startedAt)
                return EmptyPass(reader, number, TestActiveInitialCheckpointDocumentV1.Pass(facts.startedAt, facts.endedAt))
            }
        }
    }
    private class PassFacts(val number: Int, val startedAt: Instant, val endedAt: Instant)
    private class Facts(val version: String, val lastModified: Instant, val retainUntil: Instant, val verifiedAt: Instant)
    private enum class Stage { NEW, AUTHENTICATING, SEAL, SEAL_RELEASED, FIRST, FIRST_RELEASED, SECOND, SECOND_RELEASED, CLOSED }
    override fun toString(): String = "InitialCheckpointReader(concrete-read-only-native-custody,redacted)"
    companion object {
        internal fun begin(original: TestActiveInitialCheckpointV1, recipe: VersionBoundTestActiveInitialCheckpointV1,
            credentials: AwsSessionCredentials, s3: (remainingMillis: () -> Int) -> SdkHttpClient,
            kms: (remainingMillis: () -> Int) -> SdkHttpClient, clock: Clock, nanoTime: () -> Long): TestActiveInitialCheckpointReaderV1 {
            requireConnectionFree(); recipe.requireOwned(original)
            return TestActiveInitialCheckpointReaderV1(original, recipe, credentials, s3, kms, clock, nanoTime)
        }
        private fun cheapChecks(row: TestTerminalDurableRowV1, listed: JournalListedVersionV1, fetched: JournalFetchedVersionV1): Pair<Instant, Instant> {
            val response = fetched.response
            val raw = fetched.observed.response
            val headers = raw.headers()
            requireInitialCheckpoint(raw.statusCode() == 200 && response.sdkHttpResponse().statusCode() == 200 &&
                requireJournalVersion(response.versionId()) == listed.versionId && JournalS3HttpWireV1.single(headers, "x-amz-version-id") == listed.versionId)
            val wire = checkNotNull(row.wireBytes())
            try { requireInitialCheckpoint(MessageDigest.isEqual(wire, fetched.bytes)) } finally { wire.fill(0) }
            requireInitialCheckpoint(response.contentLength() == listed.size && fetched.bytes.size.toLong() == listed.size &&
                fetched.observed.size == fetched.bytes.size && fetched.observed.wireSha256 == row.wireSha256 && Sha256.hex(fetched.bytes) == row.wireSha256)
            requireInitialCheckpoint(response.deleteMarker() != true && response.contentRange() == null && response.contentEncoding() == null && response.expiration() == null)
            requireInitialCheckpoint(response.contentType() == JournalS3HttpWireV1.CONTENT_TYPE && JournalS3HttpWireV1.single(headers, "Content-Type") == JournalS3HttpWireV1.CONTENT_TYPE)
            requireInitialCheckpoint(response.metadata() == row.metadata() && JournalS3HttpWireV1.metadata(headers) == row.metadata() && (response.missingMeta() == null || response.missingMeta() == 0))
            val checksum = JournalS3HttpWireV1.single(headers, "x-amz-checksum-sha256")
            requireInitialCheckpoint(checksum == row.checksumSha256 && response.checksumSHA256() == checksum &&
                JournalS3HttpWireV1.single(headers, "x-amz-checksum-type") in listOf(null, "FULL_OBJECT"))
            val hash = MessageDigest.getInstance("SHA-256").digest(fetched.bytes)
            try { requireInitialCheckpoint(Base64.getEncoder().encodeToString(hash) == checksum) } finally { hash.fill(0) }
            requireInitialCheckpoint(response.objectLockModeAsString() == "COMPLIANCE" && JournalS3HttpWireV1.single(headers, "x-amz-object-lock-mode") == "COMPLIANCE")
            val created = checkNotNull(response.lastModified())
            val retained = checkNotNull(response.objectLockRetainUntilDate())
            requireInitialCheckpoint(created == listed.lastModified &&
                ZonedDateTime.parse(checkNotNull(JournalS3HttpWireV1.single(headers, "Last-Modified")), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() == created &&
                Instant.parse(checkNotNull(JournalS3HttpWireV1.single(headers, "x-amz-object-lock-retain-until-date"))) == retained)
            return created to retained
        }
    }
}
