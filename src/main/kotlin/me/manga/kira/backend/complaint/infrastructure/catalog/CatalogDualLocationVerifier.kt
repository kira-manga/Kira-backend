package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.Serializable
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.CatalogTailEvidence
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import java.util.Base64
import java.util.concurrent.CancellationException

/** Raw supplied observations are verified here; diagnostic result constructors never mint a persistence handoff. */
internal object CatalogDualLocationVerifier {
    fun verifyReadback(
        provider: CatalogReadbackPort,
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: CatalogReadbackPolicy,
        local: LocalCatalogSnapshot,
    ): CatalogReadbackResult = verifyRaw(provider, initialBundleBytes, currentBundleBytes, policy, local).result

    private fun verifyRaw(
        provider: CatalogReadbackPort,
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: CatalogReadbackPolicy,
        local: LocalCatalogSnapshot,
        activationRecords: MutableList<ActivationRawRecord>? = null,
    ): ReadbackRun {
        val initial = snapshotBundle(initialBundleBytes)
        val current = snapshotBundle(currentBundleBytes)
        val trust = OfflineTrustBundleVerifier.verify(current, policy.chain.trustBundlePolicy)
        val validated = CatalogLocalSnapshotVerifier.validate(local, initial, trust, policy)
        val stream = CatalogReadbackStream(provider, trust, policy, validated)
        val failures = CatalogReadbackFailureRelay()
        val iterator = sequence {
            while (true) {
                val bytes = failures.produce { stream.nextOrNull() } ?: break
                activationRecords?.let { records ->
                    requireCatalogReadback(records.size < 3, CatalogReadbackFailure.HEAD_CONFLICT)
                    records.add(ActivationRawRecord(checkNotNull(stream.lastRead), if (stream.waitingForReplica) null else stream.lastPair))
                }
                yield(bytes)
            }
        }.iterator()
        if (!iterator.hasNext()) {
            val result = when (local) {
                LocalCatalogSnapshot.NeverAccepted -> CatalogReadbackResult.EmptyClosed

                is LocalCatalogSnapshot.PreparedGenesis ->
                    CatalogReadbackResult.PreparedGenesisUnpublished(local.mutation.operationToken, policy.expectedGenesisEnvelopeSha256)

                else -> throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
            }
            return ReadbackRun(result, null, null, Sha256.hex(initial), trust.envelopeSha256)
        }
        val chain = try {
            // Iterator.asSequence is one-use. Every body is already closed before its bytes can reach the old reader.
            OfflineCatalogInventoryChainVerifier.verifyInventoryChain(iterator.asSequence(), initial, current, policy.chain)
        } catch (failure: OfflineTrustBundleException) {
            failures.restore(failure)
        }
        val result = CatalogReadbackReconciliation.reconcile(validated, stream, chain, policy)
        return ReadbackRun(result, stream.lastRead, stream.lastPair, Sha256.hex(initial), trust.envelopeSha256)
    }

    private class ActivationRawRecord(val read: ReadCatalogVersion, val pair: ReadCatalogPair?)

    private fun snapshotBundle(bytes: ByteArray): ByteArray {
        requireCatalogReadback(bytes.size in 1..OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES, CatalogReadbackFailure.LIMIT_EXCEEDED)
        return bytes.copyOf()
    }

    /**
     * Private actual raw G1/no-extra-tail fold for the first non-G1 author. This is NOT a G1
     * refresh/projection Result and does not convert the G1 reader into an Accepted>1 reader.
     * Supplied diagnostic outcomes can never construct it or authorize an author SQL phase.
     */
    class SignerRotationAuthorReadback private constructor(
        private val commonHead: CatalogCommonHeadEvidence,
        private val genesis: ByteArray,
        private val observed: LocalCatalogSnapshot,
        val evaluatedAtEpochSecond: Long,
        val requiredRetainUntilEpochSecond: Long,
    ) {
        internal fun commonHeadEvidence(): CatalogCommonHeadEvidence = commonHead
        internal fun genesisBytes(): ByteArray = genesis.copyOf()
        internal fun manifest(): OfflineCatalogGenesisManifestV1 = OfflineTrustBundleParser.parseGenesis(genesis).manifest

        internal fun requireSnapshot(selected: LocalCatalogSnapshot) {
            val same = when (val before = observed) {
                is LocalCatalogSnapshot.Accepted -> selected is LocalCatalogSnapshot.Accepted && selected.head == before.head

                is LocalCatalogSnapshot.Prepared ->
                    selected is LocalCatalogSnapshot.Prepared && selected.head == before.head &&
                        sameSignerRotationMutation(selected.mutation, before.mutation)

                else -> false
            }
            requireCatalogReadback(same, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        }

        override fun toString(): String = "SignerRotationAuthorReadback(private-raw-G1-no-tail,no-SQL-or-current-authority)"

        companion object {
            internal fun verify(
                provider: CatalogReadbackPort,
                initialBundleBytes: ByteArray,
                currentBundleBytes: ByteArray,
                policy: CatalogReadbackPolicy,
                local: LocalCatalogSnapshot,
            ): SignerRotationAuthorReadback {
                requireConnectionFree()
                when (local) {
                    is LocalCatalogSnapshot.Accepted -> requireCatalogReadback(local.head.generation == 1L, CatalogReadbackFailure.INVALID_LOCAL_STATE)

                    is LocalCatalogSnapshot.Prepared -> {
                        val parsed = CatalogLocalSnapshotVerifier.validateMutation(local.mutation, policy.chain.limits)
                        requireCatalogReadback(
                            local.head.generation == 1L && parsed.schemaVersion == 1 && parsed.claims.generation == 2L &&
                                parsed.claims.operation == "ROTATION_OVERLAP",
                            CatalogReadbackFailure.INVALID_LOCAL_STATE,
                        )
                    }

                    else -> throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
                }
                val raw = verifyRaw(provider, initialBundleBytes, currentBundleBytes, policy, local)
                val evidence = when (val result = raw.result) {
                    is CatalogReadbackResult.CurrentHeadObserved -> result.evidence.takeIf { local is LocalCatalogSnapshot.Accepted }
                    is CatalogReadbackResult.NeedsSignaturePersistence -> result.evidence.takeIf { local is LocalCatalogSnapshot.Prepared }
                    is CatalogReadbackResult.NeedsConditionalPublication -> result.evidence.takeIf { local is LocalCatalogSnapshot.Prepared }
                    else -> null
                } ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
                requireCatalogReadback(
                    evidence.chain.tail.generation == 1L && evidence.chain.tail.envelopeSha256 == policy.expectedGenesisEnvelopeSha256 &&
                        raw.pair != null,
                    CatalogReadbackFailure.HEAD_CONFLICT,
                )
                val bytes = raw.read?.bytes ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
                return SignerRotationAuthorReadback(evidence, bytes.copyOf(), local, policy.evaluatedAtEpochSecond, policy.requiredRetainUntilEpochSecond)
            }
        }
    }

    /**
     * Actual raw fixed-overlap2 observations only. The supplied snapshot head is retained separately
     * from the external tail; neither becomes current DB, lease, custody, publication or SQL authority.
     * In particular, observing an absent successor does not prove that a publication arm is unspent.
     */
    class Overlap2Readback private constructor(
        val state: State,
        val snapshotHead: CatalogLocalHead,
        val operationToken: String,
        private val observed: LocalCatalogSnapshot,
        private val frozenEnvelope: ByteArray,
        private val observedEnvelope: ByteArray,
        val observedTail: CatalogTailEvidence,
        private val commonHead: CatalogCommonHeadEvidence?,
        private val policy: CatalogReadbackPolicy,
        val initialTrustBundleSha256: String,
        val currentTrustBundleSha256: String,
        metadata: CatalogObjectMetadata,
        dualPair: ReadCatalogPair?,
    ) {
        enum class State { PREPARED_UNPUBLISHED, PREPARED_AWAIT_REPLICATION, PREPARED_DUAL_COPY, PROJECTION_PENDING_DUAL_COPY }

        val frozenEnvelopeSha256: String = Sha256.hex(frozenEnvelope)

        /** These copy observations belong to observedTail, which is still G1 when unpublished. */
        val objectVersion: String = metadata.requestBinding.versionId
        val retainUntilEpochSecond: Long = checkNotNull(metadata.retainUntilEpochSecond)
        val evaluatedAtEpochSecond: Long = policy.evaluatedAtEpochSecond
        val requiredRetainUntilEpochSecond: Long = policy.requiredRetainUntilEpochSecond
        private val primaryBytes = dualPair?.let { copyEvidence(it.primary, frozenEnvelopeSha256) }
        private val replicaBytes = dualPair?.let { copyEvidence(it.replica, frozenEnvelopeSha256) }

        internal fun frozenEnvelopeBytes(): ByteArray = frozenEnvelope.copyOf()
        internal fun observedEnvelopeBytes(): ByteArray = observedEnvelope.copyOf()

        /** G1 common evidence when unpublished, G2 when dual, and deliberately none when primary-only. */
        internal fun commonHeadEvidence(): CatalogCommonHeadEvidence? = commonHead

        /** Only exact dual G2 has copy evidence; neither a G1 pair nor a primary-only G2 is promoted. */
        internal fun primaryEvidenceBytes(): ByteArray? = primaryBytes?.copyOf()
        internal fun replicaEvidenceBytes(): ByteArray? = replicaBytes?.copyOf()

        /** Reparse retained frozen2, not observedTail and not a new provider or database observation. */
        internal fun generation(): FrozenCatalogGeneration {
            requireConnectionFree()
            return CatalogFrozenManifestParser.signed(frozenEnvelope.copyOf(), policy.chain.limits)
        }

        internal fun requireSnapshot(selected: LocalCatalogSnapshot) {
            val same = when (val before = observed) {
                is LocalCatalogSnapshot.Prepared ->
                    selected is LocalCatalogSnapshot.Prepared && selected.head == before.head &&
                        sameSignerRotationMutation(selected.mutation, before.mutation)

                is LocalCatalogSnapshot.ProjectionPending ->
                    selected is LocalCatalogSnapshot.ProjectionPending && selected.head == before.head &&
                        selected.projection.operationToken == before.projection.operationToken &&
                        selected.projection.signedEnvelopeSha256 == before.projection.signedEnvelopeSha256 &&
                        selected.projection.signedEnvelopeBytes.contentEquals(before.projection.signedEnvelopeBytes)

                else -> false
            }
            requireCatalogReadback(same, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        }

        override fun toString(): String = "Overlap2Readback(private-raw-fixed2,no-custody-or-PUT-or-SQL-or-current-authority)"

        companion object {
            @Suppress("CyclomaticComplexMethod") // Keep the fixed four-state raw fold and exact local/tail admission together.
            internal fun verify(
                provider: CatalogReadbackPort,
                initialBundleBytes: ByteArray,
                currentBundleBytes: ByteArray,
                policy: CatalogReadbackPolicy,
                local: LocalCatalogSnapshot,
            ): Overlap2Readback {
                requireConnectionFree()
                val head = when (local) {
                    is LocalCatalogSnapshot.Prepared -> {
                        requireCatalogReadback(local.head.generation == 1L, CatalogReadbackFailure.INVALID_LOCAL_STATE)
                        local.head
                    }

                    is LocalCatalogSnapshot.ProjectionPending -> {
                        requireCatalogReadback(local.head.generation == 2L, CatalogReadbackFailure.INVALID_LOCAL_STATE)
                        local.head
                    }

                    else -> throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
                }
                val frozen = when (local) {
                    is LocalCatalogSnapshot.Prepared -> CatalogLocalSnapshotVerifier.validateMutation(local.mutation, policy.chain.limits)
                    is LocalCatalogSnapshot.ProjectionPending -> CatalogFrozenManifestParser.signed(local.projection.signedEnvelopeBytes, policy.chain.limits)
                    else -> throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
                }
                val envelope = frozen.envelopeBytes ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
                requireCatalogReadback(
                    frozen.schemaVersion == 1 && frozen.claims.generation == 2L && frozen.claims.operation == "ROTATION_OVERLAP" &&
                        frozen.claims.previousEnvelopeSha256 == policy.expectedGenesisEnvelopeSha256,
                    CatalogReadbackFailure.INVALID_LOCAL_STATE,
                )
                // The raw path checks local tuple/hash/signatures and current trust before I/O, then authenticates G1/the chain.
                val raw = verifyRaw(provider, initialBundleBytes, currentBundleBytes, policy, local)
                val token = frozen.claims.operationToken
                val (state, evidence, tail) = when (val result = raw.result) {
                    is CatalogReadbackResult.NeedsConditionalPublication -> {
                        requireCatalogReadback(local is LocalCatalogSnapshot.Prepared && result.operationToken == token, CatalogReadbackFailure.HEAD_CONFLICT)
                        Triple(State.PREPARED_UNPUBLISHED, result.evidence, result.evidence.chain.tail)
                    }

                    is CatalogReadbackResult.AwaitReplication -> {
                        requireCatalogReadback(
                            local is LocalCatalogSnapshot.Prepared && result.operationToken == token && result.predecessor == head,
                            CatalogReadbackFailure.HEAD_CONFLICT,
                        )
                        Triple(State.PREPARED_AWAIT_REPLICATION, null, result.candidate)
                    }

                    is CatalogReadbackResult.PreparedCompletionEvidence -> {
                        requireCatalogReadback(local is LocalCatalogSnapshot.Prepared && result.operationToken == token, CatalogReadbackFailure.HEAD_CONFLICT)
                        Triple(State.PREPARED_DUAL_COPY, result.evidence, result.evidence.chain.tail)
                    }

                    is CatalogReadbackResult.ProjectionResumeEvidence -> {
                        requireCatalogReadback(
                            local is LocalCatalogSnapshot.ProjectionPending && result.operationToken == token,
                            CatalogReadbackFailure.HEAD_CONFLICT,
                        )
                        Triple(State.PROJECTION_PENDING_DUAL_COPY, result.evidence, result.evidence.chain.tail)
                    }

                    else -> throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
                }
                val read = raw.read ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
                if (state == State.PREPARED_UNPUBLISHED) {
                    requireCatalogReadback(
                        tail.generation == 1L && tail.envelopeSha256 == policy.expectedGenesisEnvelopeSha256 && raw.pair != null,
                        CatalogReadbackFailure.HEAD_CONFLICT,
                    )
                } else {
                    requireCatalogReadback(
                        tail.generation == 2L && tail.envelopeSha256 == frozen.envelopeSha256 && read.bytes.contentEquals(envelope),
                        CatalogReadbackFailure.HEAD_CONFLICT,
                    )
                }
                val pair = when (state) {
                    State.PREPARED_DUAL_COPY, State.PROJECTION_PENDING_DUAL_COPY ->
                        raw.pair ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)

                    // lastPair still describes G1 on primary-only G2. It is not dual-copy G2 evidence.
                    State.PREPARED_UNPUBLISHED, State.PREPARED_AWAIT_REPLICATION -> null
                }
                return Overlap2Readback(
                    state,
                    head,
                    token,
                    local,
                    envelope.copyOf(),
                    read.bytes.copyOf(),
                    tail,
                    evidence,
                    policy,
                    raw.initialHash,
                    raw.currentHash,
                    read.metadata,
                    pair,
                )
            }
        }
    }

    /** Actual bounded raw G1/G2/(optional3), retaining the genuine Accepted/Prepared/Pending snapshot. */
    class Activation3Readback private constructor(
        val state: State,
        private val observed: LocalCatalogSnapshot,
        val snapshotHead: CatalogLocalHead,
        private val records: List<ActivationRawRecord>,
        private val policy: CatalogReadbackPolicy,
        val initialTrustBundleSha256: String,
        val currentTrustBundleSha256: String,
        private val common: CatalogCommonHeadEvidence?,
    ) {
        enum class State {
            HEAD2,
            PREPARED_UNSIGNED,
            PREPARED_UNPUBLISHED,
            PREPARED_AWAIT_REPLICATION,
            PREPARED_DUAL_COPY,
            PROJECTION_PENDING_DUAL_COPY,
            PROJECTED3,
        }

        val evaluatedAtEpochSecond: Long = policy.evaluatedAtEpochSecond
        val requiredRetainUntilEpochSecond: Long = policy.requiredRetainUntilEpochSecond
        val objectVersion: String = records.last().read.metadata.requestBinding.versionId
        val retainUntilEpochSecond: Long = checkNotNull(records.last().read.metadata.retainUntilEpochSecond)
        val overlapObjectVersion: String = records[1].read.metadata.requestBinding.versionId
        val overlapRetainUntilEpochSecond: Long = checkNotNull(records[1].read.metadata.retainUntilEpochSecond)
        internal fun genesisBytes(): ByteArray = records[0].read.bytes.copyOf()
        internal fun overlapBytes(): ByteArray = records[1].read.bytes.copyOf()
        internal fun commonHeadEvidence(): CatalogCommonHeadEvidence? = common
        internal fun observedEnvelopeBytes(): ByteArray = records.last().read.bytes.copyOf()
        internal fun observedGeneration(): FrozenCatalogGeneration = CatalogFrozenManifestParser.signed(observedEnvelopeBytes(), policy.chain.limits)
        internal fun overlapPrimaryEvidenceBytes(): ByteArray = copyEvidence(checkNotNull(records[1].pair).primary, Sha256.hex(records[1].read.bytes))
        internal fun overlapReplicaEvidenceBytes(): ByteArray = copyEvidence(checkNotNull(records[1].pair).replica, Sha256.hex(records[1].read.bytes))
        internal fun primaryEvidenceBytes(): ByteArray? = records.getOrNull(2)?.let { row ->
            row.pair?.let { copyEvidence(it.primary, Sha256.hex(row.read.bytes)) }
        }
        internal fun replicaEvidenceBytes(): ByteArray? = records.getOrNull(2)?.let { row ->
            row.pair?.let { copyEvidence(it.replica, Sha256.hex(row.read.bytes)) }
        }
        val operationToken: String get() = when (val local = observed) {
            is LocalCatalogSnapshot.Prepared -> local.mutation.operationToken
            is LocalCatalogSnapshot.ProjectionPending -> local.projection.operationToken
            else -> observedGeneration().claims.operationToken
        }
        val frozenEnvelopeSha256: String get() = Sha256.hex(frozenEnvelopeBytes())
        val observedTail: CatalogTailEvidence get() = observedGeneration().let {
            CatalogTailEvidence(it.claims.generation, it.manifestSha256, checkNotNull(it.envelopeSha256), it.claims.catalogWriterGenerationId)
        }
        internal fun frozenEnvelopeBytes(): ByteArray = when (val local = observed) {
            is LocalCatalogSnapshot.Prepared -> checkNotNull(local.mutation.signedEnvelopeBytes)
            is LocalCatalogSnapshot.ProjectionPending -> local.projection.signedEnvelopeBytes
            else -> observedEnvelopeBytes()
        }
        internal fun generation(): FrozenCatalogGeneration = CatalogFrozenManifestParser.signed(frozenEnvelopeBytes(), policy.chain.limits)
        internal fun retentionPairs(): List<Pair<Long, Long>> = records.map { row ->
            val frozen = CatalogFrozenManifestParser.signed(row.read.bytes, policy.chain.limits)
            frozen.claims.creation.createdAtEpochSecond to checkNotNull(row.read.metadata.retainUntilEpochSecond)
        }

        internal fun requireSnapshot(selected: LocalCatalogSnapshot) {
            val same = when (val before = observed) {
                is LocalCatalogSnapshot.Accepted -> selected is LocalCatalogSnapshot.Accepted && selected.head == before.head

                is LocalCatalogSnapshot.Prepared ->
                    selected is LocalCatalogSnapshot.Prepared && selected.head == before.head &&
                        sameSignerRotationMutation(selected.mutation, before.mutation)

                is LocalCatalogSnapshot.ProjectionPending ->
                    selected is LocalCatalogSnapshot.ProjectionPending && selected.head == before.head &&
                        selected.projection.operationToken == before.projection.operationToken &&
                        selected.projection.signedEnvelopeSha256 == before.projection.signedEnvelopeSha256 &&
                        selected.projection.signedEnvelopeBytes.contentEquals(before.projection.signedEnvelopeBytes)

                else -> false
            }
            requireCatalogReadback(same, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        }

        override fun toString(): String = "Activation3Readback(private-raw-fixed3,no-Sign-PUT-SQL-or-current-authority)"

        companion object {
            // Keep actual snapshot classification, the raw fold and exact-prefix validation together.
            @Suppress("CyclomaticComplexMethod")
            internal fun verify(
                provider: CatalogReadbackPort,
                initialBundleBytes: ByteArray,
                currentBundleBytes: ByteArray,
                policy: CatalogReadbackPolicy,
                local: LocalCatalogSnapshot,
            ): Activation3Readback {
                requireConnectionFree()
                val head = when (local) {
                    is LocalCatalogSnapshot.Accepted -> local.head.also {
                        requireCatalogReadback(it.generation in 2L..3L, CatalogReadbackFailure.INVALID_LOCAL_STATE)
                    }

                    is LocalCatalogSnapshot.Prepared -> local.head.also {
                        requireCatalogReadback(it.generation == 2L, CatalogReadbackFailure.INVALID_LOCAL_STATE)
                        val frozen = CatalogLocalSnapshotVerifier.validateMutation(local.mutation, policy.chain.limits)
                        requireCatalogReadback(
                            frozen.schemaVersion == 1 && frozen.claims.generation == 3L &&
                                frozen.claims.operation == "ROTATION_ACTIVATE" && frozen.claims.previousEnvelopeSha256 == it.envelopeSha256,
                            CatalogReadbackFailure.INVALID_LOCAL_STATE,
                        )
                    }

                    is LocalCatalogSnapshot.ProjectionPending -> local.head.also {
                        requireCatalogReadback(it.generation == 3L, CatalogReadbackFailure.INVALID_LOCAL_STATE)
                    }

                    else -> throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
                }
                val records = mutableListOf<ActivationRawRecord>()
                // Existing generic raw authentication/reconciliation sees the ACTUAL local snapshot, never a fabricated Pending.
                val raw = verifyRaw(provider, initialBundleBytes, currentBundleBytes, policy, local, records)
                val result = raw.result
                val state = when (result) {
                    is CatalogReadbackResult.CurrentHeadObserved -> {
                        requireCatalogReadback(local is LocalCatalogSnapshot.Accepted, CatalogReadbackFailure.HEAD_CONFLICT)
                        if (head.generation == 2L) State.HEAD2 else State.PROJECTED3
                    }

                    is CatalogReadbackResult.NeedsSignaturePersistence -> State.PREPARED_UNSIGNED

                    is CatalogReadbackResult.NeedsConditionalPublication -> State.PREPARED_UNPUBLISHED

                    is CatalogReadbackResult.AwaitReplication -> State.PREPARED_AWAIT_REPLICATION

                    is CatalogReadbackResult.PreparedCompletionEvidence -> State.PREPARED_DUAL_COPY

                    is CatalogReadbackResult.ProjectionResumeEvidence -> State.PROJECTION_PENDING_DUAL_COPY

                    else -> throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
                }
                val expectedSize = if (state in setOf(State.HEAD2, State.PREPARED_UNSIGNED, State.PREPARED_UNPUBLISHED)) 2 else 3
                requireCatalogReadback(
                    records.size == expectedSize && records[0].pair != null && records[1].pair != null,
                    CatalogReadbackFailure.HEAD_CONFLICT,
                )
                records.forEachIndexed { index, row ->
                    val frozen = CatalogFrozenManifestParser.signed(row.read.bytes, policy.chain.limits)
                    requireCatalogReadback(
                        frozen.schemaVersion == 1 && frozen.claims.generation == index + 1L &&
                            frozen.claims.operation == listOf("GENESIS", "ROTATION_OVERLAP", "ROTATION_ACTIVATE")[index],
                        CatalogReadbackFailure.HEAD_CONFLICT,
                    )
                }
                if (state != State.PREPARED_AWAIT_REPLICATION) requireCatalogReadback(records.last().pair != null, CatalogReadbackFailure.HEAD_CONFLICT)
                val common = when (result) {
                    is CatalogReadbackResult.CurrentHeadObserved -> result.evidence
                    is CatalogReadbackResult.NeedsSignaturePersistence -> result.evidence
                    is CatalogReadbackResult.NeedsConditionalPublication -> result.evidence
                    is CatalogReadbackResult.PreparedCompletionEvidence -> result.evidence
                    is CatalogReadbackResult.ProjectionResumeEvidence -> result.evidence
                    else -> null
                }
                return Activation3Readback(state, local, head, records.toList(), policy, raw.initialHash, raw.currentHash, common)
            }
        }
    }

    /** Actual raw Accepted>1 fold only. This is neither supplied-result promotion nor proof of a historical DB projection. */
    class ProjectedHeadReadback private constructor(
        private val commonHead: CatalogCommonHeadEvidence,
        private val envelope: ByteArray,
        private val policy: CatalogReadbackPolicy,
        val initialTrustBundleSha256: String,
        val currentTrustBundleSha256: String,
        primary: CatalogObjectMetadata,
        replica: CatalogObjectMetadata,
    ) {
        val envelopeSha256: String = Sha256.hex(envelope)
        val objectVersion: String = primary.requestBinding.versionId
        val retainUntilEpochSecond: Long = checkNotNull(primary.retainUntilEpochSecond)
        val evaluatedAtEpochSecond: Long = policy.evaluatedAtEpochSecond
        val requiredRetainUntilEpochSecond: Long = policy.requiredRetainUntilEpochSecond
        private val primaryBytes = copyEvidence(primary, envelopeSha256)
        private val replicaBytes = copyEvidence(replica, envelopeSha256)

        internal fun commonHeadEvidence(): CatalogCommonHeadEvidence = commonHead

        internal fun generation(): FrozenCatalogGeneration {
            requireConnectionFree()
            return CatalogFrozenManifestParser.signed(envelope.copyOf(), policy.chain.limits)
        }

        internal fun primaryEvidenceBytes(): ByteArray = primaryBytes.copyOf()
        internal fun replicaEvidenceBytes(): ByteArray = replicaBytes.copyOf()

        override fun toString(): String = "ProjectedHeadReadback(private-raw-fold,no-projection-or-current-authority)"

        companion object {
            fun verify(
                provider: CatalogReadbackPort,
                initialBundleBytes: ByteArray,
                currentBundleBytes: ByteArray,
                policy: CatalogReadbackPolicy,
                local: LocalCatalogSnapshot,
            ): ProjectedHeadReadback {
                requireConnectionFree()
                requireCatalogReadback(local is LocalCatalogSnapshot.Accepted && local.head.generation > 1, CatalogReadbackFailure.INVALID_LOCAL_STATE)
                val verified = verifyRaw(provider, initialBundleBytes, currentBundleBytes, policy, local)
                val observed = verified.result as? CatalogReadbackResult.CurrentHeadObserved
                    ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
                val pair = verified.pair ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
                val read = verified.read ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
                return ProjectedHeadReadback(
                    observed.evidence,
                    read.bytes.copyOf(),
                    policy,
                    verified.initialHash,
                    verified.currentHash,
                    pair.primary,
                    pair.replica,
                )
            }
        }
    }

    /**
     * The only constructor is owned by this raw-verification path. No factory accepts a checked
     * wrapper, result, metadata, Boolean, callback or caller-selected SQL. This is G1-only local
     * finalization input, not AWS/IAM, offline-ceremony, deployment-admission or restore proof.
     */
    class GenesisReadback private constructor(
        val resume: GenesisResume,
        private val commonHead: CatalogCommonHeadEvidence,
        private val envelope: ByteArray,
        val initialTrustBundleSha256: String,
        val currentTrustBundleSha256: String,
        val evaluatedAtEpochSecond: Long,
        val requiredRetainUntilEpochSecond: Long,
        primary: CatalogObjectMetadata,
        replica: CatalogObjectMetadata,
    ) {
        private val parsed = OfflineTrustBundleParser.parseGenesis(envelope)
        private val unsigned = CanonicalJson.canonicalize(OfflineCatalogGenesisManifestV1.serializer(), parsed.manifest).toByteArray(Charsets.UTF_8)
        val envelopeSha256: String = Sha256.hex(envelope)
        val objectVersion: String = primary.requestBinding.versionId
        val retainUntilEpochSecond: Long = checkNotNull(primary.retainUntilEpochSecond)
        private val primaryBytes = copyEvidence(primary, envelopeSha256)
        private val replicaBytes = copyEvidence(replica, envelopeSha256)
        private val frozen = CatalogFrozenMutation(
            1,
            parsed.manifest.operationToken,
            unsigned,
            Sha256.hex(unsigned),
            envelope,
            envelopeSha256,
            parsed.signatures.map { CatalogFrozenSignatureSlot(it.keyId, it.algorithmId, Base64.getDecoder().decode(it.signatureBase64)) },
        )

        init {
            requireCatalogReadback(unsigned.size in 1..CatalogGenesisCapacity.MAX_DOCUMENT_BYTES, CatalogReadbackFailure.LIMIT_EXCEEDED)
            requireCatalogReadback(envelope.size in 1..CatalogGenesisCapacity.MAX_DOCUMENT_BYTES, CatalogReadbackFailure.LIMIT_EXCEEDED)
        }

        /** Raw-verifier data only; callers still need actual SDK custody and committed projection. */
        internal fun commonHeadEvidence(): CatalogCommonHeadEvidence = commonHead

        internal fun mutation(): CatalogFrozenMutation = frozen

        internal fun manifest(): OfflineCatalogGenesisManifestV1 {
            requireConnectionFree()
            return OfflineTrustBundleParser.parseGenesis(envelope).manifest
        }

        internal fun primaryEvidenceBytes(): ByteArray = primaryBytes.copyOf()
        internal fun replicaEvidenceBytes(): ByteArray = replicaBytes.copyOf()

        override fun toString(): String = "GenesisReadback(private-raw-verifier-handoff,no-admission-or-restore-authority)"

        companion object {
            fun verify(
                provider: CatalogReadbackPort,
                initialBundleBytes: ByteArray,
                currentBundleBytes: ByteArray,
                policy: CatalogReadbackPolicy,
                local: LocalCatalogSnapshot,
            ): GenesisReadback {
                requireConnectionFree()
                val resume = when (local) {
                    is LocalCatalogSnapshot.PreparedGenesis -> GenesisResume.PREPARED

                    is LocalCatalogSnapshot.ProjectionPending -> {
                        requireCatalogReadback(local.head.generation == 1L, CatalogReadbackFailure.INVALID_LOCAL_STATE)
                        GenesisResume.PROJECTION_PENDING
                    }

                    is LocalCatalogSnapshot.Accepted -> {
                        requireCatalogReadback(local.head.generation == 1L, CatalogReadbackFailure.INVALID_LOCAL_STATE)
                        GenesisResume.PROJECTED_REPLAY
                    }

                    else -> throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
                }
                val verified = verifyRaw(provider, initialBundleBytes, currentBundleBytes, policy, local)
                val evidence = when (val result = verified.result) {
                    is CatalogReadbackResult.PreparedCompletionEvidence -> result.evidence.takeIf { resume == GenesisResume.PREPARED }
                    is CatalogReadbackResult.ProjectionResumeEvidence -> result.evidence.takeIf { resume == GenesisResume.PROJECTION_PENDING }
                    is CatalogReadbackResult.CurrentHeadObserved -> result.evidence.takeIf { resume == GenesisResume.PROJECTED_REPLAY }
                    else -> null
                } ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
                requireCatalogReadback(
                    evidence.chain.tail.generation == 1L && evidence.chain.tail.envelopeSha256 == policy.expectedGenesisEnvelopeSha256,
                    CatalogReadbackFailure.HEAD_CONFLICT,
                )
                val pair = verified.pair ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
                val read = verified.read ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
                return GenesisReadback(
                    resume,
                    evidence,
                    read.bytes.copyOf(),
                    verified.initialHash,
                    verified.currentHash,
                    policy.evaluatedAtEpochSecond,
                    policy.requiredRetainUntilEpochSecond,
                    pair.primary,
                    pair.replica,
                )
            }
        }
    }
}

internal enum class GenesisResume { PREPARED, PROJECTION_PENDING, PROJECTED_REPLAY }

private class ReadbackRun(
    val result: CatalogReadbackResult,
    val read: ReadCatalogVersion?,
    val pair: ReadCatalogPair?,
    val initialHash: String,
    val currentHash: String,
)

/** Stable exact-copy tuple: a later verification time does not rewrite the evidence committed by completion. */
@Serializable
private data class GenesisCopyEvidenceV1(
    val schemaVersion: Int,
    val canonicalizerId: String,
    val location: OfflineCatalogLocationV1,
    val objectKey: String,
    val objectVersion: String,
    val contentLength: Long,
    val envelopeSha256: String,
    val objectLockMode: String,
    val retainUntilEpochSecond: Long,
    val replicationStatus: String,
)

/** Stable copy bytes only; the concrete raw owner must separately retain their actual observation/cleanup. */
internal fun copyEvidence(metadata: CatalogObjectMetadata, hash: String): ByteArray {
    val value = GenesisCopyEvidenceV1(
        1, CanonicalJson.CANON_VERSION, metadata.requestBinding.location, metadata.requestBinding.key,
        metadata.requestBinding.versionId, metadata.contentLength, hash, checkNotNull(metadata.objectLockMode),
        checkNotNull(metadata.retainUntilEpochSecond), checkNotNull(metadata.replicationStatus),
    )
    val bytes = CanonicalJson.canonicalize(GenesisCopyEvidenceV1.serializer(), value).toByteArray(Charsets.UTF_8)
    requireCatalogReadback(bytes.size in 1..65536, CatalogReadbackFailure.LIMIT_EXCEEDED)
    return bytes
}

/** Restore only sanitized producer failures wrapped by the unchanged raw reader's supplier boundary; never replay a prefix. */
private class CatalogReadbackFailureRelay {
    private var recorded: RuntimeException? = null

    fun <T> produce(action: () -> T): T = try {
        action()
    } catch (failure: CatalogReadbackException) {
        recorded = failure
        throw failure
    } catch (cancelled: CancellationException) {
        recorded = cancelled
        throw cancelled
    }

    fun restore(failure: OfflineTrustBundleException): Nothing = throw (recorded ?: failure)
}
