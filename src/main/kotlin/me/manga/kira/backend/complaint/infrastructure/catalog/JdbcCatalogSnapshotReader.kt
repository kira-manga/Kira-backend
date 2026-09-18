package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenProjection
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainLimits
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisProtocol
import me.manga.kira.backend.complaint.domain.catalog.SignatureCheckedOfflineTrustBundle
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.sql.ResultSet
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** One fixed V14 observation. No locks, updates, provider access, Spring registration or admission capability. */
internal class JdbcCatalogSnapshotReader(private val jdbc: JdbcTemplate) {
    fun read(): CatalogSnapshotReadOperation = CatalogSnapshotReadOperation.capture(jdbc)

    internal fun authenticateGenesisAuthor(attempt: CatalogGenesisFreezeAttemptV1, ownership: PersistencePhaseOwnership) = attempt.authenticate(ownership, jdbc)

    override fun toString(): String = "JdbcCatalogSnapshotReader(read-only,no-authority)"
}

/** Exact operation custody is retained before SQL; even its copied rows stay sealed until known commit AND release. */
internal class CatalogSnapshotReadOperation private constructor(private val phase: PersistencePhaseContext, private val jdbc: JdbcTemplate) {
    private var stage = Stage.RETAINED
    private var captured: CatalogSnapshotRows? = null

    fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected

    fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE

    val rows: CatalogSnapshotRows
        get() {
            phase.catalogSnapshot.requireCommitted(this)
            return checkNotNull(captured)
        }

    @Suppress("TooGenericExceptionCaught")
    private fun read() {
        try {
            requireAt(Stage.RETAINED)
            stage = Stage.READING
            val copied = jdbc.query(
                CATALOG_SNAPSHOT_SQL,
                ResultSetExtractor { result ->
                    check(result.next())
                    val rows = CatalogSnapshotRows.copy(result)
                    check(!result.next()) // SQL also caps at two: a second row is a cardinality failure, never an arbitrary winner.
                    rows
                },
            )
            // JdbcTemplate has returned through actual ResultSet/Statement cleanup, not merely its mapper callback.
            requireAt(Stage.READING)
            captured = checkNotNull(copied)
            stage = Stage.COMPLETE
        } catch (problem: Throwable) {
            stage = Stage.FAILED
            phase.recordFailure(problem)
            PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
            throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        }
    }

    private fun requireAt(expected: Stage) {
        phase.catalogSnapshot.requireRetained(this, jdbc)
        check(stage === expected)
    }

    override fun toString(): String = "CatalogSnapshotReadOperation(sealed-observation,no-authority)"

    private enum class Stage { RETAINED, READING, COMPLETE, FAILED }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun capture(jdbc: JdbcTemplate): CatalogSnapshotReadOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.catalogSnapshot.requireOperation(jdbc)
                val operation = CatalogSnapshotReadOperation(phase, jdbc)
                phase.catalogSnapshot.retain(operation, jdbc)
                operation.read()
                return operation
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}

/** Detached bounded scalars/bytes only. No JDBC objects and no caller-accessible mutable row buffers. */
internal class CatalogSnapshotRows private constructor(private val control: StoredCatalogControl?, private val pending: StoredCatalogMutation?) {
    fun observeGenesisPreparation(limits: OfflineCatalogChainLimits): UnverifiedGenesisPreparation {
        requireConnectionFree()
        present(control).requireNeverAccepted()
        val stored = present(pending)
        stored.requireShape()
        val observed = UnverifiedGenesisPreparation(stored.mutation(limits))
        val frozen = CatalogLocalSnapshotVerifier.validateMutation(observed.mutation, limits)
        stored.requireGenesisTuple(frozen)
        return observed
    }

    fun validate(initialBytes: ByteArray, current: SignatureCheckedOfflineTrustBundle, policy: CatalogReadbackPolicy): LocalCatalogSnapshot {
        requireConnectionFree()
        val observed = present(control)
        val head = observed.head(current, policy)
        if (head == null) {
            if (pending == null) return LocalCatalogSnapshot.NeverAccepted
            pending.requireShape()
            val local = LocalCatalogSnapshot.PreparedGenesis(pending.mutation(policy.chain.limits))
            val checked = CatalogLocalSnapshotVerifier.validate(local, initialBytes, current, policy)
            pending.requireGenesisTuple(present(checked.frozen))
            return checked.supplied
        }
        if (pending == null) {
            valid(observed.projectionToken == null)
            val local = LocalCatalogSnapshot.Accepted(head)
            return CatalogLocalSnapshotVerifier.validate(local, initialBytes, current, policy).supplied
        }
        pending.requireShape()
        val local = when (pending.state.value) {
            "PREPARED" -> {
                valid(observed.projectionToken == null)
                LocalCatalogSnapshot.Prepared(head, pending.mutation(policy.chain.limits))
            }

            "COMPLETED" -> {
                valid(observed.projectionToken == pending.token)
                LocalCatalogSnapshot.ProjectionPending(
                    head,
                    CatalogFrozenProjection(present(pending.token).toString(), present(pending.envelope.bytes), digest(pending.envelope.hash)),
                )
            }

            else -> throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
        }
        val checked = CatalogLocalSnapshotVerifier.validate(local, initialBytes, current, policy)
        pending.requireTuple(present(checked.frozen), observed.writer)
        return checked.supplied
    }

    override fun toString(): String = "CatalogSnapshotRows(detached-observation,no-authority)"

    companion object {
        internal fun copy(row: ResultSet): CatalogSnapshotRows = CatalogSnapshotRows(StoredCatalogControl.copy(row), StoredCatalogMutation.copy(row))
    }
}

private class StoredCatalogControl(
    private val scope: UUID,
    private val testOnly: Boolean?,
    private val generation: Long?,
    private val hash: ByteArray?,
    private val trustHash: ByteArray?,
    val writer: UUID?,
    val projectionToken: UUID?,
    private val bounded: Boolean?,
) {
    fun head(current: SignatureCheckedOfflineTrustBundle, policy: CatalogReadbackPolicy): CatalogLocalHead? {
        requireScope()
        if (generation == null) {
            requireNeverAccepted()
            return null
        }
        valid(generation in 1..policy.chain.limits.maximumGenerations)
        valid(digest(trustHash) == current.envelopeSha256)
        val writerId = present(writer).toString()
        valid(OfflineBootstrapGrammar.uuidV4(writerId) && writerId in policy.chain.currentWriterGenerationIds)
        valid(writerId == current.body.bootstrapAuthority.catalogWriterGenerationId)
        return CatalogLocalHead(generation, digest(hash))
    }

    fun requireNeverAccepted() {
        requireScope()
        valid(generation == null)
        valid(hash == null && trustHash == null && writer == null && projectionToken == null)
    }

    private fun requireScope() {
        requireCatalogReadback(bounded == true, CatalogReadbackFailure.LIMIT_EXCEEDED)
        valid(scope.toString() == OfflineBootstrapGrammar.LIVE_SCOPE_ID && testOnly == false)
    }

    companion object {
        fun copy(row: ResultSet): StoredCatalogControl? {
            val scope = row.getObject("control_scope", UUID::class.java) ?: return null
            return StoredCatalogControl(
                scope,
                row.boolean("control_test_only"),
                row.long("accepted_catalog_generation"),
                row.bytes("accepted_catalog_hash"),
                row.bytes("trust_bundle_hash"),
                row.getObject("control_writer", UUID::class.java),
                row.getObject("pending_projection_token", UUID::class.java),
                row.boolean("control_bounded"),
            )
        }
    }
}

private class StoredCatalogMutation(
    val token: UUID?,
    private val operation: String?,
    private val scope: UUID?,
    private val testOnly: Boolean?,
    private val chain: StoredCatalogChain,
    private val approval: StoredCatalogDocument,
    private val unsigned: StoredCatalogDocument,
    val envelope: StoredCatalogDocument,
    private val canonicalizer: String?,
    private val signers: StoredCatalogSigners,
    private val copies: StoredCatalogCopies,
    val state: StoredCatalogState,
    private val bounded: Boolean?,
) {
    fun requireShape() {
        requireCatalogReadback(bounded == true, CatalogReadbackFailure.LIMIT_EXCEEDED)
        valid(token != null && OfflineBootstrapGrammar.uuidV4(token.toString()))
        valid(scope == null && testOnly == null) // These closed profiles are global, not a test/LIVE-scope fallback.
        valid(canonicalizer == CanonicalJson.CANON_VERSION)
        approval.check(required = true)
        unsigned.check(required = true)
        envelope.check(required = false)
        state.check(envelope.bytes != null)
        copies.check(envelope.bytes != null, state.value == "COMPLETED")
    }

    fun mutation(limits: OfflineCatalogChainLimits): CatalogFrozenMutation {
        val bytes = present(unsigned.bytes)
        return CatalogFrozenMutation(
            CatalogFrozenManifestParser.schemaVersion(bytes, limits),
            present(token).toString(),
            bytes,
            digest(unsigned.hash),
            envelope.bytes,
            envelope.hash?.let(::digest),
            signers.slots(),
        )
    }

    fun requireTuple(frozen: FrozenCatalogGeneration, controlWriter: UUID?) {
        valid(chain.writer == controlWriter)
        if (operation == "GENESIS") {
            // Only COMPLETED/pending G1 reaches this head-bearing branch; PREPARED still requires all five fields absent.
            valid(state.value == "COMPLETED")
            requireGenesisClaims(frozen)
        } else {
            requireFrozenTuple(frozen)
            requireOperation(frozen)
        }
    }

    fun requireGenesisTuple(frozen: FrozenCatalogGeneration) {
        valid(state.value == "PREPARED" && operation == "GENESIS")
        requireGenesisClaims(frozen)
    }

    private fun requireGenesisClaims(frozen: FrozenCatalogGeneration) {
        valid(frozen.schemaVersion == 1 && frozen.claims.operation == "GENESIS" && frozen.claims.generation == 1L)
        valid(frozen.claims.previousEnvelopeSha256 == OfflineCatalogGenesisProtocol.ZERO_PREDECESSOR_SHA256)
        val policy = frozen.claims.requiredSignerPolicy
        valid(policy.mode == "SINGLE" && policy.threshold == "ALL_MEMBERS" && policy.members.size == 1)
        requireFrozenTuple(frozen)
    }

    private fun requireFrozenTuple(frozen: FrozenCatalogGeneration) {
        val claims = frozen.claims
        valid(token.toString() == claims.operationToken && chain.writer.toString() == claims.catalogWriterGenerationId)
        valid(chain.successor == claims.generation && chain.predecessor == claims.generation - 1)
        valid(digest(chain.previousHash) == claims.previousEnvelopeSha256)
        valid(copies.objectKey == CatalogReadbackProtocol.key(claims.generation))
        valid(present(unsigned.bytes).contentEquals(frozen.manifestBytes))
        valid(signers.policy == claims.requiredSignerPolicy.mode)
        CatalogLocalSnapshotVerifier.requireStoredSignatures(frozen, signers.slots())
        val expectedApproval = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), claims.approvals)
            .toByteArray(Charsets.UTF_8)
        valid(present(approval.bytes).contentEquals(expectedApproval))
    }

    /** New read-only V14/profile bridge, not permission to execute an operation or accept a source/copy. */
    private fun requireOperation(frozen: FrozenCatalogGeneration) {
        val expected = when (frozen.claims.operation) {
            OfflineCatalogChainProtocol.ROTATION_OVERLAP -> "SIGNER_ROTATION_OVERLAP"
            OfflineCatalogChainProtocol.ROTATION_ACTIVATE -> "SIGNER_ROTATION_ACTIVATION"
            CatalogLogicalInventoryProtocol.REGISTER_SOURCE, CatalogLogicalInventoryProtocol.ADD_COPY -> "RESTORE_SOURCE_ACCEPTANCE"
            else -> throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
        }
        valid(operation == expected)
        if (frozen.schemaVersion == 2) {
            val delta = present(frozen.inventoryDelta)
            val sources = if (frozen.claims.operation == CatalogLogicalInventoryProtocol.REGISTER_SOURCE) 1 else 0
            val copies = if (expected == "RESTORE_SOURCE_ACCEPTANCE") 1 else 0
            valid(delta.addedSourceIds.size == sources && delta.addedCopyIds.size == copies)
            valid(delta.addedSourceIds.all(OfflineBootstrapGrammar::uuidV4) && delta.addedCopyIds.all(OfflineBootstrapGrammar::uuidV4))
        } else {
            valid(expected != "RESTORE_SOURCE_ACCEPTANCE")
        }
    }

    companion object {
        fun copy(row: ResultSet): StoredCatalogMutation? {
            val token = row.getObject("operation_token", UUID::class.java) ?: return null
            return StoredCatalogMutation(
                token,
                row.getString("operation_type"),
                row.getObject("mutation_scope", UUID::class.java),
                row.boolean("mutation_test_only"),
                StoredCatalogChain(
                    row.long("predecessor_generation"),
                    row.bytes("predecessor_hash"),
                    row.long("successor_generation"),
                    row.getObject("mutation_writer", UUID::class.java),
                ),
                StoredCatalogDocument.copy(row, "approval"),
                StoredCatalogDocument.copy(row, "unsigned"),
                StoredCatalogDocument.copy(row, "envelope"),
                row.getString("canonicalizer"),
                StoredCatalogSigners.copy(row),
                StoredCatalogCopies.copy(row),
                StoredCatalogState.copy(row),
                row.boolean("mutation_bounded"),
            )
        }
    }
}

private class StoredCatalogChain(val predecessor: Long?, val previousHash: ByteArray?, val successor: Long?, val writer: UUID?)

private class StoredCatalogDocument(val bytes: ByteArray?, val hash: ByteArray?) {
    fun check(required: Boolean) {
        if (bytes == null) {
            valid(!required && hash == null)
        } else {
            valid(bytes.isNotEmpty() && Sha256.hex(bytes) == digest(hash))
        }
    }

    companion object {
        fun copy(row: ResultSet, prefix: String): StoredCatalogDocument = StoredCatalogDocument(row.bytes("${prefix}_bytes"), row.bytes("${prefix}_hash"))
    }
}

private class StoredCatalogSigners(
    val policy: String?,
    private val firstId: String?,
    private val firstAlgorithm: String?,
    private val firstSignature: ByteArray?,
    private val secondId: String?,
    private val secondAlgorithm: String?,
    private val secondSignature: ByteArray?,
) {
    fun slots(): List<CatalogFrozenSignatureSlot> {
        val first = CatalogFrozenSignatureSlot(present(firstId), present(firstAlgorithm), firstSignature)
        return when (policy) {
            "SINGLE" -> {
                valid(secondId == null && secondAlgorithm == null && secondSignature == null)
                listOf(first)
            }

            "ROTATION_OVERLAP" -> {
                valid(secondId != firstId)
                listOf(first, CatalogFrozenSignatureSlot(present(secondId), present(secondAlgorithm), secondSignature))
            }

            else -> throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
        }
    }

    companion object {
        fun copy(row: ResultSet): StoredCatalogSigners = StoredCatalogSigners(
            row.getString("signer_policy"),
            row.getString("signer_one_id"),
            row.getString("signer_one_algorithm"),
            row.bytes("signer_one_signature"),
            row.getString("signer_two_id"),
            row.getString("signer_two_algorithm"),
            row.bytes("signer_two_signature"),
        )
    }
}

private class StoredCatalogCopies(
    val objectKey: String?,
    private val version: String?,
    private val retainUntil: Instant?,
    private val primary: StoredCatalogDocument,
    private val replica: StoredCatalogDocument,
) {
    fun check(signed: Boolean, completed: Boolean) {
        primary.check(required = completed)
        replica.check(required = completed)
        if (version == null) {
            valid(retainUntil == null && primary.bytes == null && replica.bytes == null)
        } else {
            valid(CatalogReadbackProtocol.validVersion(version) && signed && retainUntil != null)
            valid(present(retainUntil).epochSecond in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND)
        }
    }

    companion object {
        fun copy(row: ResultSet): StoredCatalogCopies = StoredCatalogCopies(
            row.getString("object_key"),
            row.getString("object_version"),
            row.getTimestamp("retain_until")?.toInstant(),
            StoredCatalogDocument.copy(row, "primary_evidence"),
            StoredCatalogDocument.copy(row, "replica_evidence"),
        )
    }
}

private class StoredCatalogState(
    val value: String?,
    private val created: Instant?,
    private val completed: Instant?,
    private val projected: Instant?,
    private val finite: Boolean?,
) {
    fun check(signed: Boolean) {
        valid(finite == true && created != null && projected == null)
        valid(present(created).epochSecond in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND)
        valid(completed == null || completed.epochSecond in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND)
        valid((value == "PREPARED" && completed == null) || (value == "COMPLETED" && completed != null && signed))
    }

    companion object {
        fun copy(row: ResultSet): StoredCatalogState = StoredCatalogState(
            row.getString("state"),
            row.getTimestamp("created_at")?.toInstant(),
            row.getTimestamp("completed_at")?.toInstant(),
            row.getTimestamp("projected_at")?.toInstant(),
            row.boolean("mutation_finite_times"),
        )
    }
}

private fun ResultSet.boolean(column: String): Boolean? = getBoolean(column).let { if (wasNull()) null else it }

private fun ResultSet.long(column: String): Long? = getLong(column).let { if (wasNull()) null else it }

private fun ResultSet.bytes(column: String): ByteArray? = getBytes(column)?.copyOf()

private fun valid(condition: Boolean) = requireCatalogReadback(condition, CatalogReadbackFailure.INVALID_LOCAL_STATE)

private fun <T : Any> present(value: T?): T = value ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)

private fun digest(value: ByteArray?): String {
    valid(value?.size == 32)
    return HexFormat.of().formatHex(present(value))
}

// Every variable-width projected value is CASE-gated BEFORE pgjdbc materializes it. Bounds violations
// remain explicit flags, so an oversized optional field cannot silently become an accepted absence.
// Both CTEs and the outer statement are bounded; one statement gives control + GLOBAL pending one MVCC view.
private const val CATALOG_SNAPSHOT_SQL = """
    WITH control AS (
        SELECT data_scope_id, test_only, accepted_catalog_generation, accepted_catalog_hash,
               trust_bundle_hash, catalog_writer_generation, pending_projection_token,
               ((accepted_catalog_hash IS NULL OR octet_length(accepted_catalog_hash) <= 32)
                AND (trust_bundle_hash IS NULL OR octet_length(trust_bundle_hash) <= 32)) AS bounded
        FROM complaint_journal_control
        WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'
        LIMIT 2
    ), pending AS (
        SELECT operation_token, operation_type, data_scope_id, test_only, predecessor_generation,
               predecessor_hash, successor_generation, catalog_writer_generation, approval_bytes, approval_hash,
               canonicalizer, unsigned_bytes, unsigned_hash, signer_policy, signer_one_id, signer_one_algorithm,
               signer_one_signature, signer_two_id, signer_two_algorithm, signer_two_signature, envelope_bytes,
               envelope_hash, object_key, object_version, retain_until, primary_evidence_bytes, primary_evidence_hash,
               replica_evidence_bytes, replica_evidence_hash, state, created_at, completed_at, projected_at,
               ((operation_type IS NULL OR octet_length(operation_type) <= 64)
                AND (predecessor_hash IS NULL OR octet_length(predecessor_hash) <= 32)
                AND (approval_bytes IS NULL OR octet_length(approval_bytes) <= 4096)
                AND (approval_hash IS NULL OR octet_length(approval_hash) <= 32)
                AND (canonicalizer IS NULL OR octet_length(canonicalizer) <= 16)
                AND (unsigned_bytes IS NULL OR octet_length(unsigned_bytes) <= CASE WHEN successor_generation = 1 THEN 131072 ELSE 8388608 END)
                AND (unsigned_hash IS NULL OR octet_length(unsigned_hash) <= 32)
                AND (signer_policy IS NULL OR octet_length(signer_policy) <= 24)
                AND (signer_one_id IS NULL OR octet_length(signer_one_id) <= 128)
                AND (signer_one_algorithm IS NULL OR octet_length(signer_one_algorithm) <= 128)
                AND (signer_one_signature IS NULL OR octet_length(signer_one_signature) <= 1024)
                AND (signer_two_id IS NULL OR octet_length(signer_two_id) <= 128)
                AND (signer_two_algorithm IS NULL OR octet_length(signer_two_algorithm) <= 128)
                AND (signer_two_signature IS NULL OR octet_length(signer_two_signature) <= 1024)
                AND (envelope_bytes IS NULL OR octet_length(envelope_bytes) <= CASE WHEN successor_generation = 1 THEN 131072 ELSE 8388608 END)
                AND (envelope_hash IS NULL OR octet_length(envelope_hash) <= 32)
                AND (object_key IS NULL OR octet_length(object_key) <= 1024)
                AND (object_version IS NULL OR octet_length(object_version) <= 1024)
                AND (primary_evidence_bytes IS NULL OR octet_length(primary_evidence_bytes) <= 65536)
                AND (primary_evidence_hash IS NULL OR octet_length(primary_evidence_hash) <= 32)
                AND (replica_evidence_bytes IS NULL OR octet_length(replica_evidence_bytes) <= 65536)
                AND (replica_evidence_hash IS NULL OR octet_length(replica_evidence_hash) <= 32)
                AND (state IS NULL OR octet_length(state) <= 16)) AS bounded,
               (isfinite(created_at) AND (retain_until IS NULL OR isfinite(retain_until))
                AND (completed_at IS NULL OR isfinite(completed_at))
                AND (projected_at IS NULL OR isfinite(projected_at))) AS finite_times
        FROM complaint_catalog_mutations
        WHERE state = 'PREPARED' OR (state = 'COMPLETED' AND projected_at IS NULL)
        LIMIT 2
    )
    SELECT c.data_scope_id AS control_scope, c.test_only AS control_test_only,
           c.accepted_catalog_generation, c.catalog_writer_generation AS control_writer, c.pending_projection_token,
           c.bounded AS control_bounded,
           CASE WHEN c.bounded THEN c.accepted_catalog_hash END AS accepted_catalog_hash,
           CASE WHEN c.bounded THEN c.trust_bundle_hash END AS trust_bundle_hash,
           p.operation_token, p.data_scope_id AS mutation_scope, p.test_only AS mutation_test_only,
           p.predecessor_generation, p.successor_generation, p.catalog_writer_generation AS mutation_writer,
           p.bounded AS mutation_bounded, p.finite_times AS mutation_finite_times,
           CASE WHEN p.bounded THEN p.operation_type END AS operation_type,
           CASE WHEN p.bounded THEN p.predecessor_hash END AS predecessor_hash,
           CASE WHEN p.bounded THEN p.approval_bytes END AS approval_bytes,
           CASE WHEN p.bounded THEN p.approval_hash END AS approval_hash,
           CASE WHEN p.bounded THEN p.canonicalizer END AS canonicalizer,
           CASE WHEN p.bounded THEN p.unsigned_bytes END AS unsigned_bytes,
           CASE WHEN p.bounded THEN p.unsigned_hash END AS unsigned_hash,
           CASE WHEN p.bounded THEN p.signer_policy END AS signer_policy,
           CASE WHEN p.bounded THEN p.signer_one_id END AS signer_one_id,
           CASE WHEN p.bounded THEN p.signer_one_algorithm END AS signer_one_algorithm,
           CASE WHEN p.bounded THEN p.signer_one_signature END AS signer_one_signature,
           CASE WHEN p.bounded THEN p.signer_two_id END AS signer_two_id,
           CASE WHEN p.bounded THEN p.signer_two_algorithm END AS signer_two_algorithm,
           CASE WHEN p.bounded THEN p.signer_two_signature END AS signer_two_signature,
           CASE WHEN p.bounded THEN p.envelope_bytes END AS envelope_bytes,
           CASE WHEN p.bounded THEN p.envelope_hash END AS envelope_hash,
           CASE WHEN p.bounded THEN p.object_key END AS object_key,
           CASE WHEN p.bounded THEN p.object_version END AS object_version,
           CASE WHEN p.bounded THEN p.primary_evidence_bytes END AS primary_evidence_bytes,
           CASE WHEN p.bounded THEN p.primary_evidence_hash END AS primary_evidence_hash,
           CASE WHEN p.bounded THEN p.replica_evidence_bytes END AS replica_evidence_bytes,
           CASE WHEN p.bounded THEN p.replica_evidence_hash END AS replica_evidence_hash,
           CASE WHEN p.bounded THEN p.state END AS state,
           CASE WHEN p.finite_times THEN p.created_at END AS created_at,
           CASE WHEN p.finite_times THEN p.completed_at END AS completed_at,
           CASE WHEN p.finite_times THEN p.projected_at END AS projected_at,
           CASE WHEN p.finite_times THEN p.retain_until END AS retain_until
    FROM (VALUES (1)) AS anchor(n)
    LEFT JOIN control c ON TRUE
    LEFT JOIN pending p ON TRUE
    LIMIT 2
"""
