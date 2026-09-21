package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveCheckpointHistoryV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentJsonV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEpochSealV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealProofV1
import me.manga.kira.backend.complaint.infrastructure.terminal.VersionBoundTestOrdinarySealV1
import me.manga.kira.backend.complaint.infrastructure.terminal.testOrdinarySealVerificationBytesV1
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Detached exact control observation; no constructor can issue original/capture/native success. */
internal class TestActiveRecurrentCurrentV1 private constructor(row: ResultSet) : AutoCloseable {
    val sampledAt: Instant = recurrentTime(row, "sampled_at")
    val epoch = recurrentLong(row, "publication_epoch")
    val sequence = recurrentLong(row, "rotation_sequence")
    val id: UUID = checkNotNull(row.getObject("rotation_id", UUID::class.java))
    val state: String = checkNotNull(row.getString("rotation_state"))
    val scanRequested = row.getBoolean("scan_requested").also { requireRecurrent(!row.wasNull()) }
    val epochBefore = recurrentLong(row, "rotation_epoch_before")
    val epochAfter: Long? = recurrentNullableLong(row, "rotation_epoch_after")
    val requestOwner: UUID = checkNotNull(row.getObject("rotation_request_owner", UUID::class.java))
    val requestToken = recurrentLong(row, "rotation_request_token")
    val requestedAt: Instant = recurrentTime(row, "rotation_requested_at")
    val captureOwner: UUID? = row.getObject("rotation_capture_owner", UUID::class.java)
    val captureToken: Long? = recurrentNullableLong(row, "rotation_capture_token")
    val capturedAt: Instant? = row.getTimestamp("rotation_captured_at")?.toInstant()
    val leaseToken = recurrentLong(row, "lease_token")
    val lease: TestActiveFirstCutLeaseV1? = row.getObject("lease_owner", UUID::class.java)?.let {
        TestActiveFirstCutLeaseV1(it, leaseToken, recurrentTime(row, "lease_expires_at"))
    }
    val sealState: String? = row.getString("seal_state")
    val sealToken: UUID? = row.getObject("seal_operation_token", UUID::class.java)
    val sealEpoch: Long? = recurrentNullableLong(row, "seal_epoch")
    val sealWriter: UUID? = row.getObject("seal_writer_generation", UUID::class.java)
    val sealKey: String? = row.getString("seal_object_key")
    private val canonical = row.getBytes("seal_bytes")
    private val canonicalHash = row.getBytes("seal_hash")
    val sealVersion: String? = row.getString("seal_object_version")
    private val wireHash = row.getBytes("seal_ciphertext_hash")
    val sealRetention: Instant? = row.getTimestamp("seal_retain_until")?.toInstant()
    val sealVerifiedAt: Instant? = row.getTimestamp("seal_verified_at")?.toInstant()
    private val proof = row.getBytes("seal_verification_bytes")
    private val proofHash = row.getBytes("seal_verification_hash")
    private val checkpoint = row.getBytes("checkpoint_bytes")
    val checkpointSha256: String? = row.getBytes("checkpoint_hash")?.let(::recurrentHex)
    val checkpointedAt: Instant? = row.getTimestamp("checkpoint_completed_at")?.toInstant()
    private val checkpointColumns: Array<Any?> = arrayOf(
        recurrentNullableLong(row, "checkpoint_generation"), recurrentNullableLong(row, "checkpoint_fencing_token"),
        recurrentNullableLong(row, "checkpoint_catalog_generation"), row.getBytes("checkpoint_catalog_hash"),
        row.getObject("checkpoint_writer_generation", UUID::class.java)?.toString(), recurrentNullableLong(row, "checkpoint_cutoff_epoch"),
        row.getBytes("checkpoint_configuration_hash"), row.getObject("checkpoint_database_identity", UUID::class.java)?.toString(),
        row.getObject("checkpoint_restore_identity", UUID::class.java)?.toString(), recurrentNullableLong(row, "checkpoint_schema")?.toInt(),
        row.getTimestamp("checkpoint_started_at"), row.getTimestamp("checkpoint_completed_at"),
        recurrentNullableLong(row, "checkpoint_object_count"), recurrentNullableLong(row, "checkpoint_byte_count"),
        row.getString("checkpoint_result"), checkpoint, row.getBytes("checkpoint_hash"),
    )
    private val control = recurrentHash(row, "control_fingerprint")
    private val content = recurrentHash(row, "content_fingerprint")
    private val global = recurrentHash(row, "global_fingerprint")
    private val run = recurrentHash(row, "run_fingerprint")
    private val slot = row.getBytes("slot_fingerprint")

    init {
        requireRecurrent(row.getBoolean("valid") && !row.wasNull() && sequence in 1..14 && id.version() == 4 && epochBefore in 1 until Long.MAX_VALUE)
        requireRecurrent(requestToken > 0 && requestedAt <= sampledAt && leaseToken >= requestToken)
        requireRecurrent((lease == null) == (row.getTimestamp("lease_expires_at") == null))
        if (state == "REQUESTED") requireRecurrent(sequence >= 2 && epoch == epochBefore && scanRequested && captureOwner == null &&
            captureToken == null && capturedAt == null && epochAfter == null && sealState == null && checkpoint == null)
        else requireRecurrent(state == "CAPTURED" && !scanRequested && epoch == epochBefore + 1 && epochAfter == epoch &&
            captureOwner?.version() == 4 && checkNotNull(captureToken) >= requestToken && leaseToken >= captureToken &&
            checkNotNull(capturedAt) in requestedAt..sampledAt)
        if (sealState == null) requireRecurrent(listOf(sealToken, sealEpoch, sealWriter, sealKey, canonical, canonicalHash, sealVersion,
            wireHash, sealRetention, sealVerifiedAt, proof, proofHash).all { it == null })
        else {
            requireRecurrent(sealToken == id && sealEpoch == epochBefore && sealKey != null && sealWriter != null &&
                checkNotNull(canonical).size in 1..65536 && canonicalHash?.size == 32 && Sha256.hex(canonical) == recurrentHex(canonicalHash))
            if (sealState == "SEAL_PREPARED") requireRecurrent(listOf(sealVersion, wireHash, sealRetention, sealVerifiedAt, proof, proofHash).all { it == null })
            else requireRecurrent(sealState == "SEAL_VERIFIED" && TestActiveRecurrentJsonV1.opaque(checkNotNull(sealVersion)) &&
                wireHash?.size == 32 && checkNotNull(sealVerifiedAt) <= sampledAt && checkNotNull(sealRetention) > sampledAt &&
                checkNotNull(proof).size in 1..65536 && proofHash?.size == 32 && Sha256.hex(proof) == recurrentHex(proofHash))
        }
        if (checkpoint == null) requireRecurrent(checkpointColumns.all { it == null })
        else requireRecurrent(checkpoint.size in 1..65536 && Sha256.hex(checkpoint) == checkpointSha256 && sealState == "SEAL_VERIFIED")
    }

    fun controlFingerprint(): ByteArray = recurrentBytes(control)
    fun slotFingerprint(): ByteArray = checkNotNull(slot).copyOf().also { requireRecurrent(it.size == 32) }
    fun checkpointBytes(): ByteArray = checkNotNull(checkpoint).copyOf()
    fun verificationBytes(): ByteArray = checkNotNull(proof).copyOf()
    fun requireLease(owner: UUID, token: Long) {
        val actual = checkNotNull(lease)
        requireRecurrent(actual.owner == owner && actual.token == token && actual.expiresAt > sampledAt)
    }
    fun requireSamePhysical(other: TestActiveRecurrentCurrentV1) = requireRecurrent(control == other.control && global == other.global && run == other.run && slot.contentEquals(other.slot))
    fun requireSameContent(other: TestActiveRecurrentCurrentV1) = requireRecurrent(content == other.content && slot.contentEquals(other.slot))
    fun requireSameGlobal(other: TestActiveRecurrentCurrentV1) = requireRecurrent(global == other.global)
    fun requireSameRun(other: TestActiveRecurrentCurrentV1) = requireRecurrent(run == other.run)
    fun requireSameRequest(other: TestActiveRecurrentCurrentV1) = requireRecurrent(id == other.id && sequence == other.sequence && epochBefore == other.epochBefore &&
        requestOwner == other.requestOwner && requestToken == other.requestToken && requestedAt == other.requestedAt)

    /** Passive exact source/time comparisons shared with the registered ordinary reader. */
    fun requireIntents(sources: List<TestActiveRecurrentIntentV1>) {
        requireRecurrent(sequence == sources.size.toLong())
        requireIntent(sources.last())
        requireRecurrent(sources.all { it.requestedAt <= sampledAt && it.capturedAt?.let { at -> at <= sampledAt } != false &&
            it.payload?.binding?.createdAt?.let { at -> at <= sampledAt } != false && it.payload?.frozenAt?.let { at -> at <= sampledAt } != false })
    }

    fun requireIntent(intent: TestActiveRecurrentIntentV1) {
        requireRecurrent(intent.token == id && intent.ordinal.toLong() == sequence && intent.epochEnd == epochBefore && intent.requestOwner == requestOwner &&
            intent.requestToken == requestToken && intent.requestedAt == requestedAt && intent.captureOwner == captureOwner && intent.captureToken == captureToken &&
            intent.capturedAt == capturedAt && intent.epochAfter == epochAfter && slot?.let(::recurrentHex) == intent.fingerprint)
        val payload = intent.payload
        if (payload == null) requireRecurrent(sealState == null) else {
            requireRecurrent(sealToken == intent.token && sealWriter.toString() == intent.identity.writer.toString() && sealKey == payload.binding.objectKey &&
                canonicalHash?.let(::recurrentHex) == payload.canonicalSha256)
            val bytes = payload.canonicalBytes()
            try { requireRecurrent(bytes.contentEquals(canonical)) } finally { bytes.fill(0) }
            if (sealState == "SEAL_VERIFIED") requireRecurrent(payload.wireSha256 == wireHash?.let(::recurrentHex))
        }
    }

    fun proof(intent: TestActiveRecurrentIntentV1, acquisition: VersionBoundTestOrdinarySealV1): TestActiveRecurrentVerificationV1 {
        requireIntent(intent); requireRecurrent(sealState == "SEAL_VERIFIED")
        return TestActiveRecurrentVerificationV1.parse(checkNotNull(proof), checkNotNull(proofHash).let(::recurrentHex), checkNotNull(intent.payload),
            checkNotNull(sealVersion), checkNotNull(sealRetention), checkNotNull(sealVerifiedAt), sampledAt, acquisition)
    }

    /** Exact all17 columns, full D/J/birth history and native seal identity; age is not freshness authority. */
    fun requireCheckpoint(intent: TestActiveRecurrentIntentV1, history: TestActiveCheckpointHistoryV1?): Long {
        requireIntent(intent)
        val bytes = checkNotNull(checkpoint)
        val columns: Array<Any?>
        val framed: Long
        if (intent.ordinal == 1) {
            val doc = TestInitialCheckpointCurrentCodecV1.checkpoint(bytes)
            val i = intent.identity
            requireRecurrent(doc.scope == i.scope.toString() && doc.desiredGeneration == i.desiredGeneration && doc.configurationSha256 == recurrentHex(i.configurationHash()) &&
                doc.journalConfigurationSha256 == recurrentHex(i.journalHash()) && doc.databaseIdentity == i.databaseIdentity.toString() && doc.restoreIdentity == i.restoreIdentity.toString() &&
                doc.catalogGeneration == i.acceptedCatalogGeneration && doc.catalogSha256 == recurrentHex(i.acceptedCatalogHash()) && doc.trustBundleSha256 == recurrentHex(i.trustBundleHash()) &&
                doc.catalogWriterGeneration == i.catalogWriter.toString() && doc.writerGeneration == i.writer.toString() && doc.sealOperationToken == intent.token.toString() &&
                doc.sealObjectKey == sealKey && doc.sealObjectVersion == sealVersion && doc.sealCanonicalSha256 == checkNotNull(intent.payload).canonicalSha256 &&
                doc.sealCiphertextSha256 == checkNotNull(intent.payload).wireSha256 && doc.manifestSha256 == intent.seal().eventManifestSha256 && intent.seal().eventCount == 0L &&
                doc.startedAt >= checkNotNull(sealVerifiedAt) && doc.completedAt <= sampledAt && doc.fencingToken > intent.preparingToken())
            columns = TestActiveInitialCheckpointRowsV1.documentArguments(doc, bytes, checkNotNull(checkpointSha256)); framed = doc.manifestFramedBytes
        } else {
            val doc = TestActiveRecurrentCheckpointDocumentV1.parse(bytes)
            doc.requireHistory(checkNotNull(history))
            requireRecurrent(doc.identity.same(recurrentHistoryIdentity(intent.identity)) && doc.scanId == intent.token.toString() &&
                doc.predecessorCheckpointSha256 == intent.predecessorCheckpointSha256 && doc.completedAt <= sampledAt)
            columns = recurrentDocumentArguments(doc, bytes, checkNotNull(checkpointSha256)); framed = history.last.manifestFramedBytes
        }
        try { requireRecurrent(recurrentArgumentsEqual(columns, checkpointColumns)) } finally { columns.forEach { if (it is ByteArray) it.fill(0) } }
        requireRecurrent((checkpointColumns[1] as Long) <= leaseToken && checkNotNull(checkpointedAt) <= sampledAt)
        return framed
    }
    override fun close() {
        listOf(canonical, canonicalHash, wireHash, proof, proofHash, checkpoint, slot).forEach { it?.fill(0) }
        checkpointColumns.forEach { if (it is ByteArray) it.fill(0) }
    }
    override fun toString(): String = "RecurrentCurrent(detached-bounded-control,no-authority)"
    companion object { fun copy(row: ResultSet): TestActiveRecurrentCurrentV1 = TestActiveRecurrentCurrentV1(row) }
}

/** V26/V31 source header and immutable payload, not a cross-source authority adapter. */
internal class TestActiveRecurrentIntentV1(row: ResultSet, val source: TestActiveCheckpointHistoryV1.Source,
    val identity: TestActiveFirstCutIdentityV1) : AutoCloseable {
    val token: UUID = checkNotNull(row.getObject("operation_token", UUID::class.java))
    val ordinal = Math.toIntExact(recurrentLong(row, "rotation_sequence"))
    val epochStart = recurrentLong(row, "epoch_start")
    val epochEnd = recurrentLong(row, "epoch_end")
    val epochAfter = recurrentNullableLong(row, "epoch_after")
    val requestOwner: UUID = checkNotNull(row.getObject("request_owner", UUID::class.java))
    val requestToken = recurrentLong(row, "request_token")
    val requestedAt = recurrentTime(row, "requested_at")
    val captureOwner: UUID? = row.getObject("capture_owner", UUID::class.java)
    val captureToken: Long? = recurrentNullableLong(row, "capture_token")
    val capturedAt: Instant? = row.getTimestamp("captured_at")?.toInstant()
    val state: String = checkNotNull(row.getString("state"))
    val predecessorToken: UUID? = row.getObject("predecessor_operation_token", UUID::class.java)
    val predecessorCheckpointSha256: String? = row.getBytes("predecessor_checkpoint_hash")?.let(::recurrentHex)
    val predecessorHistorySha256: String? = row.getBytes("predecessor_history_hash")?.let(::recurrentHex)
    val fingerprint = recurrentHash(row, "slot_fingerprint")
    var payload: TestTerminalDurableRowV1? = null
        private set
    init {
        requireRecurrent(row.getBoolean("valid") && !row.wasNull() && token.version() == 4 && row.getBoolean("test_only") && !row.wasNull())
        requireRecurrent(row.getObject("data_scope_id", UUID::class.java) == identity.scope && recurrentTime(row, "run_created_at") == identity.runCreatedAt &&
            recurrentLong(row, "implementation_schema") == identity.implementationSchema.toLong() && recurrentLong(row, "desired_generation") == identity.desiredGeneration &&
            recurrentHash(row, "configuration_hash") == recurrentHex(identity.configurationHash()) && recurrentHash(row, "journal_configuration_hash") == recurrentHex(identity.journalHash()) &&
            row.getObject("database_identity", UUID::class.java) == identity.databaseIdentity && row.getObject("restore_identity", UUID::class.java) == identity.restoreIdentity &&
            row.getObject("writer_generation", UUID::class.java) == identity.writer && recurrentLong(row, "activation_catalog_generation") == identity.generation &&
            recurrentHash(row, "activation_catalog_hash") == recurrentHex(identity.activationCatalogHash()) && recurrentLong(row, "accepted_catalog_generation") == identity.acceptedCatalogGeneration &&
            recurrentHash(row, "accepted_catalog_hash") == recurrentHex(identity.acceptedCatalogHash()) && recurrentHash(row, "trust_bundle_hash") == recurrentHex(identity.trustBundleHash()) &&
            row.getObject("catalog_writer_generation", UUID::class.java) == identity.catalogWriter && recurrentLong(row, "charged_storage_bytes") == TestActiveRecurrentStorageV1.INTENT_STORAGE_BYTES)
        requireRecurrent(ordinal in 1..14 && (ordinal == 1) == (source == TestActiveCheckpointHistoryV1.Source.V26_INITIAL) && epochStart > 0 && epochEnd in epochStart until Long.MAX_VALUE &&
            requestToken > 0 && requestOwner.version() == 4 && requestedAt >= identity.runCreatedAt)
        if (ordinal == 1) requireRecurrent(epochStart == 1L && epochEnd == 1L && predecessorToken == null && predecessorCheckpointSha256 == null && predecessorHistorySha256 == null)
        else requireRecurrent(epochStart >= 2 && predecessorToken?.version() == 4 && TestActiveRecurrentJsonV1.hash(checkNotNull(predecessorCheckpointSha256)) &&
            TestActiveRecurrentJsonV1.hash(checkNotNull(predecessorHistorySha256)))
        if (captureOwner == null) requireRecurrent(captureToken == null && capturedAt == null && epochAfter == null && state == "RESERVED")
        else requireRecurrent(captureOwner.version() == 4 && checkNotNull(captureToken) >= requestToken && checkNotNull(capturedAt) >= requestedAt && epochAfter == epochEnd + 1)
    }
    fun bindPayload(row: ResultSet) {
        requireRecurrent(payload == null && state in setOf("CANONICAL", "WIRE_FROZEN") && row.getBoolean("valid") && !row.wasNull() &&
            row.getString("state") == state && recurrentHash(row, "seal_encoding_hash") == identity.sealEncodingSha256)
        val binding = TestTerminalDurableBindingV1(token.toString(), recurrentRunContext(identity), recurrentHex(identity.journalHash()), TestTerminalDurableKindV1.EPOCH_SEAL,
            ordinal - 1, checkNotNull(row.getString("object_id")), checkNotNull(row.getString("object_key")), checkNotNull(row.getString("routing_key_id")),
            identity.writer.toString(), epochStart, epochEnd, recurrentLong(row, "preparing_fencing_token"), recurrentTime(row, "retention_floor"), recurrentTime(row, "created_at"))
        requireRecurrent(binding.preparingFencingToken > checkNotNull(captureToken) && binding.createdAt >= checkNotNull(capturedAt))
        val bytes = checkNotNull(row.getBytes("canonical_bytes"))
        val canonical = try { TestTerminalDurableRowV1.canonical(binding, bytes) } finally { bytes.fill(0) }
        try {
            requireRecurrent(canonical.canonicalSha256 == recurrentHash(row, "canonical_hash"))
            if (state == "CANONICAL") payload = canonical else {
                val wire = checkNotNull(row.getBytes("wire_bytes"))
                val frozen = try { TestTerminalDurableRowV1.frozen(canonical, wire, recurrentTime(row, "retain_until"), recurrentTime(row, "frozen_at")) } finally { wire.fill(0) }
                try {
                    requireRecurrent(frozen.wireSha256 == recurrentHash(row, "wire_hash") && frozen.metadataSha256 == recurrentHash(row, "metadata_hash") &&
                        frozen.checksumSha256 == row.getString("checksum_sha256") && frozen.contentType == row.getString("content_type") && frozen.objectLockMode == row.getString("object_lock_mode"))
                    val actual = checkNotNull(row.getBytes("metadata_bytes")); val expected = checkNotNull(frozen.metadataBytes())
                    try { requireRecurrent(actual.contentEquals(expected)) } finally { actual.fill(0); expected.fill(0) }
                    payload = frozen; canonical.close()
                } catch (problem: Throwable) { frozen.close(); throw problem }
            }
            seal()
        } catch (problem: Throwable) { canonical.close(); payload?.close(); payload = null; throw problem }
    }
    fun preparingToken(): Long = checkNotNull(payload).binding.preparingFencingToken
    fun seal(): TestTerminalEpochSealV1 {
        val row = checkNotNull(payload); val bytes = row.canonicalBytes()
        try {
            TestActiveRecurrentJsonV1.objectBytes(bytes, 65536)
            val value = CanonicalJson.json.decodeFromString(TestTerminalEpochSealV1.serializer(), bytes.toString(Charsets.UTF_8))
            val canonical = CanonicalJson.canonicalize(TestTerminalEpochSealV1.serializer(), value).toByteArray(Charsets.UTF_8)
            try { requireRecurrent(bytes.contentEquals(canonical)) } finally { canonical.fill(0) }
            requireRecurrent(value.schemaVersion == 1 && value.objectKind == "EPOCH_SEAL" && value.dataScopeKind == "TEST" &&
                value.sealId == row.binding.objectId && value.writerGeneration == identity.writer.toString() && value.dataScopeId == identity.scope.toString() &&
                value.epochStartInclusive == epochStart && value.epochEndInclusive == epochEnd && value.preparingFencingToken == row.binding.preparingFencingToken)
            requireRecurrent(if (ordinal == 1) value.precedingSealSha256.isEmpty() else TestActiveRecurrentJsonV1.hash(value.precedingSealSha256))
            return value
        } finally { bytes.fill(0) }
    }
    fun requireSame(other: TestActiveRecurrentIntentV1) = requireRecurrent(source == other.source && token == other.token && fingerprint == other.fingerprint)
    override fun close() { payload?.close() }
    override fun toString(): String = "RecurrentIntent(exact-V26-or-V31-source,no-authority)"
}

/** Stored proof syntax plus physical equality only. Fresh native issuer is still separately required. */
internal class TestActiveRecurrentVerificationV1 private constructor(val version: String, val lastModified: Instant, val retainUntil: Instant,
    val verifiedAt: Instant, private val bytes: ByteArray, val sha256: String) : AutoCloseable {
    fun canonicalBytes(): ByteArray = bytes.copyOf()
    fun requireNative(native: TestActiveRecurrentNativeSealV1, row: TestTerminalDurableRowV1) {
        val observed = native.releasedProof()
        requireRecurrent(observed.version == version && observed.lastModified == lastModified && observed.retainUntil == retainUntil && observed.verifiedAt >= verifiedAt)
        val actual = observed.canonicalBytes(row, verifiedAt)
        try { requireRecurrent(bytes.contentEquals(actual)) } finally { actual.fill(0) }
    }
    fun requireSame(other: TestActiveRecurrentVerificationV1) = requireRecurrent(bytes.contentEquals(other.bytes) && sha256 == other.sha256)
    override fun close() { bytes.fill(0) }
    override fun toString(): String = "RecurrentVerification(stored-comparison-only,redacted)"
    companion object {
        fun parse(bytes: ByteArray, hash: String, row: TestTerminalDurableRowV1, version: String, until: Instant, at: Instant, sampledAt: Instant,
            acquisition: VersionBoundTestOrdinarySealV1): TestActiveRecurrentVerificationV1 {
            requireRecurrent(Sha256.hex(bytes) == hash && row.state == TestTerminalDurableStateV1.WIRE_FROZEN)
            val objectValue = TestActiveRecurrentJsonV1.objectBytes(bytes, 65536)
            val modified = TestActiveRecurrentJsonV1.instant(objectValue, "lastModified")
            val minimum = maxOf(checkNotNull(row.retainUntil), VersionBoundTestOrdinarySealV1.tenYears(modified), acquisition.retention.lastPreRunRestoreHorizon.plusSeconds(31 * 86_400L))
            requireRecurrent(modified.nano == 0 && until.nano == 0 && modified >= checkNotNull(row.frozenAt) && at >= modified && at <= sampledAt &&
                until >= minimum && until > sampledAt.plusMillis(acquisition.retention.utcUncertainty.maximumMillis))
            val canonical = testOrdinarySealVerificationBytesV1(row, version, modified, until, at)
            try { requireRecurrent(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
            return TestActiveRecurrentVerificationV1(version, modified, until, at, bytes.copyOf(), hash)
        }
    }
}

internal fun recurrentHistoryIdentity(i: TestActiveFirstCutIdentityV1): TestActiveCheckpointHistoryV1.Identity = TestActiveCheckpointHistoryV1.Identity(
    i.scope.toString(), i.runCreatedAt, i.implementationSchema, i.desiredGeneration, recurrentHex(i.configurationHash()), recurrentHex(i.journalHash()),
    i.databaseIdentity.toString(), i.restoreIdentity.toString(), i.writer.toString(), i.generation, recurrentHex(i.activationCatalogHash()),
    i.acceptedCatalogGeneration, recurrentHex(i.acceptedCatalogHash()), recurrentHex(i.trustBundleHash()), i.catalogWriter.toString())
internal fun recurrentRunContext(i: TestActiveFirstCutIdentityV1): TestTerminalRunContextV1 = TestTerminalRunContextV1(i.scope.toString(), i.generation,
    recurrentHex(i.activationCatalogHash()), recurrentHex(i.configurationHash()), i.sealEncodingSha256)
internal fun recurrentCopyPayload(row: TestTerminalDurableRowV1): TestTerminalDurableRowV1 {
    val bytes = row.canonicalBytes()
    val canonical = try { TestTerminalDurableRowV1.canonical(row.binding, bytes) } finally { bytes.fill(0) }
    if (row.state == TestTerminalDurableStateV1.CANONICAL) return canonical
    return try {
        val wire = checkNotNull(row.wireBytes())
        try { TestTerminalDurableRowV1.frozen(canonical, wire, checkNotNull(row.retainUntil), checkNotNull(row.frozenAt)) }
        finally { wire.fill(0) }
    } finally { canonical.close() }
}
internal fun recurrentSameCanonical(a: TestTerminalDurableRowV1, b: TestTerminalDurableRowV1) {
    requireRecurrent(a.binding == b.binding && a.canonicalSha256 == b.canonicalSha256)
    val left = a.canonicalBytes(); val right = b.canonicalBytes()
    try { requireRecurrent(left.contentEquals(right)) } finally { left.fill(0); right.fill(0) }
}
internal fun recurrentSameFrozen(a: TestTerminalDurableRowV1, b: TestTerminalDurableRowV1) {
    recurrentSameCanonical(a, b)
    requireRecurrent(a.state == TestTerminalDurableStateV1.WIRE_FROZEN && b.state == a.state && a.wireSha256 == b.wireSha256 &&
        a.metadataSha256 == b.metadataSha256 && a.checksumSha256 == b.checksumSha256 && a.retainUntil == b.retainUntil && a.frozenAt == b.frozenAt)
    val left = checkNotNull(a.wireBytes()); val right = checkNotNull(b.wireBytes())
    try { requireRecurrent(left.contentEquals(right)) } finally { left.fill(0); right.fill(0) }
}
internal fun recurrentDocumentArguments(v: TestActiveRecurrentCheckpointDocumentV1, bytes: ByteArray, hash: String): Array<Any?> = arrayOf(
    v.identity.desiredGeneration, v.fencingToken, v.identity.catalogGeneration, recurrentBytes(v.identity.catalogSha256), v.identity.writerGeneration,
    v.cutoffEpoch, recurrentBytes(v.identity.configurationSha256), v.identity.databaseIdentity, v.identity.restoreIdentity, v.identity.implementationSchema,
    Timestamp.from(v.startedAt), Timestamp.from(v.completedAt), v.objectCount, v.byteCount, "SUCCESS", bytes.copyOf(), recurrentBytes(hash))
internal fun recurrentArgumentsEqual(a: Array<Any?>, b: Array<Any?>): Boolean = a.size == b.size && a.indices.all { index ->
    val x = a[index]; val y = b[index]; if (x is ByteArray && y is ByteArray) x.contentEquals(y) else x == y
}
internal fun recurrentHex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)
internal fun recurrentBytes(hex: String): ByteArray = HexFormat.of().parseHex(hex)
internal fun recurrentHash(row: ResultSet, name: String): String = checkNotNull(row.getBytes(name)).let { requireRecurrent(it.size == 32); recurrentHex(it) }
internal fun recurrentLong(row: ResultSet, name: String): Long = row.getLong(name).also { requireRecurrent(!row.wasNull()) }
internal fun recurrentNullableLong(row: ResultSet, name: String): Long? = row.getLong(name).let { if (row.wasNull()) null else it }
internal fun recurrentTime(row: ResultSet, name: String): Instant = checkNotNull(row.getTimestamp(name)).toInstant().also { requireRecurrent(TestActiveRecurrentJsonV1.time(it)) }
internal fun requireRecurrent(value: Boolean) { if (!value) throw TestActiveRecurrentExceptionV1() }
internal class TestActiveRecurrentExceptionV1 : RuntimeException("ACTIVE TEST recurrent reconciliation refused.", null, false, false)
