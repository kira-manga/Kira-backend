package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.Serializable
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
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
                is LocalCatalogSnapshot.Prepared -> selected is LocalCatalogSnapshot.Prepared && selected.head == before.head &&
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

private fun copyEvidence(metadata: CatalogObjectMetadata, hash: String): ByteArray {
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
