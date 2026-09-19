package me.manga.kira.backend.complaint.domain.terminal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256

/** Fixed TEST syntax ceilings, not admitted capacity, a registered run or provider authority. */
internal object TestTerminalProfileV1 {
    const val PROFILE = "TEST_TERMINAL_V1"
    const val SCHEMA_VERSION = 1
    const val INSTALLATION_MANIFEST = "INSTALLATION_MANIFEST"
    const val TEST_RUN_PURGE = "TEST_RUN_PURGE"
    const val EPOCH_SEAL = "EPOCH_SEAL"
    const val MAX_ENTRIES_PER_CHUNK = 500
    const val MAX_MANIFEST_CHUNKS = 4096
    const val MAX_PRE_TERMINAL_SEALS = 15
    const val MAX_SEALS = 16
    const val MAX_DENIAL_RANGES = 15
    const val MAX_PLAINTEXT_BYTES = 65_536
    const val MAX_ENVELOPE_BYTES = 98_304
    const val MAX_HEADER_BYTES = 4096
    const val MAX_WRAPPED_KEY_BYTES = 28_636
    const val MAX_JSON_DEPTH = 6
    const val MAX_OBJECT_FIELDS = 32
    const val MAX_JSON_TOKENS = 8192
    const val MAX_STRING_BYTES = 1024
    const val MAX_CATALOG_GENERATION = 65_536L
    const val MAX_INSTALLATIONS = MAX_ENTRIES_PER_CHUNK * 1L * MAX_MANIFEST_CHUNKS

    private val encoding = CanonicalJson.canonicalize(
        TestTerminalEncodingV1.serializer(),
        TestTerminalEncodingV1(PROFILE, SCHEMA_VERSION),
    ).toByteArray(Charsets.UTF_8)
    val encodingSha256: String = Sha256.hex(encoding)

    fun encodingBytes(): ByteArray = encoding.copyOf()
}

internal enum class TestTerminalFailureV1 { INVALID_INPUT, LIMIT_EXCEEDED, KEY_FAILURE }

internal class TestTerminalExceptionV1(val code: TestTerminalFailureV1) :
    RuntimeException("TEST terminal syntax rejected input: ${code.name}", null, false, false)

internal fun requireTestTerminal(condition: Boolean, code: TestTerminalFailureV1 = TestTerminalFailureV1.INVALID_INPUT) {
    if (!condition) throw TestTerminalExceptionV1(code)
}

@Serializable
internal enum class TestTerminalDispositionV1 { RETIRED, DELETED }

@Serializable
internal enum class TestTerminalSealRoleV1 { ORDINARY, TERMINAL }

@Serializable
internal data class TestTerminalEncodingV1(val profile: String, val schemaVersion: Int) {
    init {
        requireTestTerminal(profile == TestTerminalProfileV1.PROFILE && schemaVersion == TestTerminalProfileV1.SCHEMA_VERSION)
    }
}

/** Immutable declarations only. They cannot attest to an accepted activation or full configuration. */
internal data class TestTerminalRunContextV1(
    val dataScopeId: String,
    val activationCatalogGeneration: Long,
    val activationCatalogSha256: String,
    val configurationSha256: String,
    val terminalEncodingSha256: String,
) {
    init { TestTerminalSyntaxV1.run(this) }
    override fun toString(): String = "TestTerminalRunContextV1(redacted,no-authority)"
}

internal data class TestTerminalEventContextV1(
    val run: TestTerminalRunContextV1,
    val eventId: String,
    val publicationEpoch: Long,
    val writerGeneration: String,
) {
    init { TestTerminalSyntaxV1.event(this) }
    override fun toString(): String = "TestTerminalEventContextV1(redacted,no-authority)"
}

@Serializable
internal data class TestTerminalInstallationEntryV1(val installationId: String, val disposition: TestTerminalDispositionV1) {
    init { TestTerminalSyntaxV1.uuid(installationId) }
    override fun toString(): String = "TestTerminalInstallationEntryV1(redacted,no-authority)"
}

/** The sole list is private. Factories snapshot bounded input; getters never expose retained storage. */
@Serializable
@Suppress("LongParameterList") // The closed, flattened wire fields are deliberately not a nested context object.
internal class TestTerminalInstallationManifestV1 private constructor(
    val schemaVersion: Int, val eventId: String, val publicationEpoch: Long, val writerGeneration: String,
    val dataScopeKind: String, val dataScopeId: String, val activationCatalogGeneration: Long,
    val activationCatalogSha256: String, val configurationSha256: String, val terminalEncodingSha256: String,
    val eventKind: String, val chunkIndex: Int, val chunkCount: Int, val installationCount: Long,
    val retiredCount: Long, val deletedCount: Long, val installationsSha256: String, val entriesSha256: String,
    @SerialName("entries") private val storedEntries: List<TestTerminalInstallationEntryV1>,
) {
    init { TestTerminalSyntaxV1.installationManifest(this) }
    fun entries(): List<TestTerminalInstallationEntryV1> = storedEntries.toList()
    fun context(): TestTerminalEventContextV1 = TestTerminalEventContextV1(
        TestTerminalRunContextV1(dataScopeId, activationCatalogGeneration, activationCatalogSha256, configurationSha256, terminalEncodingSha256),
        eventId, publicationEpoch, writerGeneration,
    )
    override fun toString(): String = "TestTerminalInstallationManifestV1(redacted,syntax-only)"

    companion object {
        fun create(
            context: TestTerminalEventContextV1,
            chunkIndex: Int,
            chunkCount: Int,
            installationsSha256: String,
            entries: List<TestTerminalInstallationEntryV1>,
        ): TestTerminalInstallationManifestV1 {
            val snapshot = TestTerminalSyntaxV1.snapshot(entries, TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK)
            val retired = snapshot.count { it.disposition == TestTerminalDispositionV1.RETIRED }.toLong()
            return TestTerminalInstallationManifestV1(
                1, context.eventId, context.publicationEpoch, context.writerGeneration, "TEST", context.run.dataScopeId,
                context.run.activationCatalogGeneration, context.run.activationCatalogSha256, context.run.configurationSha256,
                context.run.terminalEncodingSha256, TestTerminalProfileV1.INSTALLATION_MANIFEST, chunkIndex, chunkCount,
                snapshot.size.toLong(), retired, snapshot.size.toLong() - retired, installationsSha256,
                TestTerminalSyntaxV1.entriesSha256(snapshot), snapshot,
            )
        }
    }
}

@Serializable
internal data class TestTerminalObjectRefV1(
    val objectKey: String, val objectVersion: String, val ciphertextSha256: String, val canonicalSha256: String,
) {
    init { TestTerminalSyntaxV1.objectRef(this) }
    override fun toString(): String = "TestTerminalObjectRefV1(redacted,syntax-only)"
}

@Serializable
internal data class TestTerminalSealRefV1(
    val role: TestTerminalSealRoleV1, val writerGeneration: String, val epochStartInclusive: Long, val epochEndInclusive: Long,
    val sealId: String, val precedingSealSha256: String, @SerialName("object") val objectRef: TestTerminalObjectRefV1,
) {
    init { TestTerminalSyntaxV1.sealRef(this) }
    override fun toString(): String = "TestTerminalSealRefV1(redacted,syntax-only)"
}

@Serializable
internal data class TestTerminalCountHashV1(val count: Long, val sha256: String) {
    init {
        requireTestTerminal(count >= 0)
        TestTerminalSyntaxV1.hash(sha256)
    }
    override fun toString(): String = "TestTerminalCountHashV1(declaration-only)"
}

@Serializable
internal data class TestTerminalManifestSummaryV1(
    val installationCount: Long, val retiredCount: Long, val deletedCount: Long, val chunkCount: Int,
    val installationsSha256: String, val chunksSha256: String,
) {
    init { TestTerminalSyntaxV1.manifestSummary(this) }
    override fun toString(): String = "TestTerminalManifestSummaryV1(declaration-only)"
}

@Serializable
@Suppress("LongParameterList") // Required flattened canonical fields, not a general-purpose constructor surface.
internal class TestTerminalPurgeV1 private constructor(
    val schemaVersion: Int, val eventId: String, val publicationEpoch: Long, val writerGeneration: String,
    val dataScopeKind: String, val dataScopeId: String, val activationCatalogGeneration: Long,
    val activationCatalogSha256: String, val configurationSha256: String, val terminalEncodingSha256: String,
    val eventKind: String, val finalOrdinaryEpoch: Long, val finalOrdinarySeal: TestTerminalSealRefV1,
    val preTerminalSeals: TestTerminalCountHashV1, val preTerminalInventory: TestTerminalCountHashV1,
    val installationManifest: TestTerminalManifestSummaryV1,
) {
    init { TestTerminalSyntaxV1.purge(this) }
    fun context(): TestTerminalEventContextV1 = TestTerminalEventContextV1(
        TestTerminalRunContextV1(dataScopeId, activationCatalogGeneration, activationCatalogSha256, configurationSha256, terminalEncodingSha256),
        eventId, publicationEpoch, writerGeneration,
    )
    override fun toString(): String = "TestTerminalPurgeV1(redacted,syntax-only,no-purge-authority)"

    companion object {
        fun create(
            context: TestTerminalEventContextV1,
            finalOrdinaryEpoch: Long,
            finalOrdinarySeal: TestTerminalSealRefV1,
            preTerminalSeals: TestTerminalCountHashV1,
            preTerminalInventory: TestTerminalCountHashV1,
            installationManifest: TestTerminalManifestSummaryV1,
        ): TestTerminalPurgeV1 = TestTerminalPurgeV1(
            1, context.eventId, context.publicationEpoch, context.writerGeneration, "TEST", context.run.dataScopeId,
            context.run.activationCatalogGeneration, context.run.activationCatalogSha256, context.run.configurationSha256,
            context.run.terminalEncodingSha256, TestTerminalProfileV1.TEST_RUN_PURGE, finalOrdinaryEpoch, finalOrdinarySeal,
            preTerminalSeals, preTerminalInventory, installationManifest,
        )
    }
}

@Serializable
internal class TestTerminalSealSetV1 private constructor(
    val schemaVersion: Int, val dataScopeId: String, val activationCatalogGeneration: Long, val activationCatalogSha256: String,
    @SerialName("records") private val storedRecords: List<TestTerminalSealRefV1>,
) {
    init { TestTerminalSyntaxV1.sealSet(this) }
    fun records(): List<TestTerminalSealRefV1> = storedRecords.toList()
    override fun toString(): String = "TestTerminalSealSetV1(redacted,syntax-only)"

    companion object {
        fun create(
            dataScopeId: String, activationCatalogGeneration: Long, activationCatalogSha256: String, records: List<TestTerminalSealRefV1>,
        ): TestTerminalSealSetV1 = TestTerminalSealSetV1(
            1, dataScopeId, activationCatalogGeneration, activationCatalogSha256,
            TestTerminalSyntaxV1.snapshot(records, TestTerminalProfileV1.MAX_SEALS),
        )
    }
}

@Serializable
internal data class TestTerminalPolicyRefV1(val policyId: String, val version: Long, val sha256: String) {
    init {
        TestTerminalSyntaxV1.referenceId(policyId)
        requireTestTerminal(version > 0)
        TestTerminalSyntaxV1.hash(sha256)
    }
    override fun toString(): String = "TestTerminalPolicyRefV1(redacted,declaration-only)"
}

@Serializable
internal data class TestTerminalEvidenceDigestV1(val sha256: String, val byteCount: Long) {
    init {
        TestTerminalSyntaxV1.hash(sha256)
        requireTestTerminal(byteCount > 0)
    }
    override fun toString(): String = "TestTerminalEvidenceDigestV1(declaration-only,no-evidence)"
}

@Serializable
internal data class TestTerminalInventoryWitnessV1(
    val startedAtEpochSecond: Long, val completedAtEpochSecond: Long, val versionCount: Long, val byteCount: Long, val sha256: String,
) {
    init { TestTerminalSyntaxV1.inventoryWitness(this) }
    override fun toString(): String = "TestTerminalInventoryWitnessV1(declaration-only,no-inventory-proof)"
}

@Serializable
internal data class TestTerminalDenialCutV1(
    val roleId: String, val policy: TestTerminalPolicyRefV1, val denialEffectiveAtEpochSecond: Long,
    val lastSessionExpiryEpochSecond: Long, val acceptedRequestBoundSeconds: Long,
    val policyEvidence: TestTerminalEvidenceDigestV1, val boundEvidence: TestTerminalEvidenceDigestV1,
    val firstInventory: TestTerminalInventoryWitnessV1, val secondInventory: TestTerminalInventoryWitnessV1,
) {
    init { TestTerminalSyntaxV1.denialCut(this) }
    override fun toString(): String = "TestTerminalDenialCutV1(redacted,declaration-only,no-drain-proof)"
}

@Serializable
internal data class TestTerminalWriterDenialV1(
    val writerGeneration: String, val ordinaryPrefix: String, val sealTerminalPrefix: String,
    val ordinary: TestTerminalDenialCutV1, val terminal: TestTerminalDenialCutV1,
) {
    init {
        TestTerminalSyntaxV1.uuid(writerGeneration)
        requireTestTerminal(ordinary.roleId != terminal.roleId)
    }
    override fun toString(): String = "TestTerminalWriterDenialV1(redacted,declaration-only)"
}

@Serializable
internal class TestTerminalDenialSetV1 private constructor(
    val schemaVersion: Int, val dataScopeId: String, val activationCatalogGeneration: Long, val activationCatalogSha256: String,
    @SerialName("ranges") private val storedRanges: List<TestTerminalWriterDenialV1>,
) {
    init { TestTerminalSyntaxV1.denialSet(this) }
    fun ranges(): List<TestTerminalWriterDenialV1> = storedRanges.toList()
    override fun toString(): String = "TestTerminalDenialSetV1(redacted,declaration-only,no-permanent-denial-proof)"

    companion object {
        fun create(
            dataScopeId: String, activationCatalogGeneration: Long, activationCatalogSha256: String, ranges: List<TestTerminalWriterDenialV1>,
        ): TestTerminalDenialSetV1 = TestTerminalDenialSetV1(
            1, dataScopeId, activationCatalogGeneration, activationCatalogSha256,
            TestTerminalSyntaxV1.snapshot(ranges, TestTerminalProfileV1.MAX_DENIAL_RANGES),
        )
    }
}

/** Separate TEST seal syntax; it neither widens the LIVE codec nor issues a seal attempt. */
@Serializable
internal data class TestTerminalEpochSealV1(
    val schemaVersion: Int, val objectKind: String, val sealId: String, val writerGeneration: String,
    val dataScopeKind: String, val dataScopeId: String, val epochStartInclusive: Long, val epochEndInclusive: Long,
    val eventCount: Long, val eventManifestSha256: String, val precedingSealSha256: String, val preparingFencingToken: Long,
) {
    init { TestTerminalSyntaxV1.epochSeal(this) }
    override fun toString(): String = "TestTerminalEpochSealV1(redacted,syntax-only,no-seal-authority)"
}

/** Header declarations only. No wire, AEAD, KMS-context authentication or provider binding is issued here. */
@Serializable
@Suppress("LongParameterList")
internal data class TestTerminalEventHeaderV1(
    val envelopeSchemaVersion: Int, val payloadSchemaVersion: Int, val canonicalizerId: String, val objectKind: String,
    val encryptionAlgorithm: String, val dataKeyMode: String, val kmsKeyId: String, val kmsKeyArn: String,
    val bucket: String, val objectKey: String, val writerGeneration: String, val sealTerminalPrefix: String,
    val dataScopeKind: String, val dataScopeId: String, val publicationEpoch: Long, val routingKeyId: String, val eventId: String, val nonce: String,
) {
    init { TestTerminalSyntaxV1.eventHeader(this) }
    override fun toString(): String = "TestTerminalEventHeaderV1(redacted,unauthenticated-syntax)"
}

@Serializable
@Suppress("LongParameterList")
internal data class TestTerminalSealHeaderV1(
    val envelopeSchemaVersion: Int, val payloadSchemaVersion: Int, val canonicalizerId: String, val objectKind: String,
    val encryptionAlgorithm: String, val dataKeyMode: String, val kmsKeyId: String, val kmsKeyArn: String,
    val bucket: String, val objectKey: String, val writerGeneration: String, val sealTerminalPrefix: String,
    val dataScopeKind: String, val dataScopeId: String, val epochStartInclusive: Long, val epochEndInclusive: Long,
    val routingKeyId: String, val sealId: String, val nonce: String,
) {
    init { TestTerminalSyntaxV1.sealHeader(this) }
    override fun toString(): String = "TestTerminalSealHeaderV1(redacted,unauthenticated-syntax)"
}
