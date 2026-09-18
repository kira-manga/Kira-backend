package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisManifestV1
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Fixed independently checked bytes before phase entry. Not a signer, namespace probe, pin or publication permission. */
internal class CatalogGenesisMutationInput private constructor(
    internal val path: PersistencePhasePath,
    internal val before: CatalogFrozenMutation,
    internal val after: CatalogFrozenMutation,
    manifest: OfflineCatalogGenesisManifestV1,
    internal val finalization: CatalogGenesisFinalizationInput? = null,
) {
    private val token = UUID.fromString(manifest.operationToken)
    private val writer = UUID.fromString(manifest.catalogWriterGenerationId)
    private val approvals = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), manifest.approvals).toByteArray()
    private val approvalsHash = hash(approvals)
    private val unsigned = before.unsignedManifestBytes
    private val unsignedHash = hash(unsigned)
    private val key = CatalogReadbackProtocol.key(1)
    private val signer = before.signatureSlots.single()
    private val createdAt = Instant.ofEpochSecond(manifest.creation.createdAtEpochSecond)
    private val beforeEnvelopeHash = before.signedEnvelopeSha256?.let { HexFormat.of().parseHex(it) }
    private val afterEnvelopeHash = after.signedEnvelopeSha256?.let { HexFormat.of().parseHex(it) }

    // G1 finalization's bounded byte buffers are detached ONCE, before any phase exists. No query-time cloning.
    private val finalSqlArguments = finalization?.let { arrayOf<Any?>(*frozenArguments(), *beforeSignatureArguments(), *it.copyArguments()) }
    private val finalControlArguments = finalization?.controlArguments()

    init {
        requireConnectionFree()
        requireCatalogReadback(approvals.size in 1..4096, CatalogReadbackFailure.LIMIT_EXCEEDED)
        requireCatalogReadback(after.unsignedManifestSize <= CatalogGenesisCapacity.MAX_DOCUMENT_BYTES, CatalogReadbackFailure.LIMIT_EXCEEDED)
        requireCatalogReadback((after.signedEnvelopeSize ?: 0) <= CatalogGenesisCapacity.MAX_DOCUMENT_BYTES, CatalogReadbackFailure.LIMIT_EXCEEDED)
    }

    /** Fresh parameter arrays/copies, never mutable buffers borrowed from a caller while locks are held. */
    internal fun frozenArguments(): Array<Any?> = arrayOf(
        token, writer, approvals.copyOf(), approvalsHash.copyOf(), unsigned.copyOf(), unsignedHash.copyOf(),
        signer.keyId, signer.algorithmId, key, Timestamp.from(createdAt),
    )

    internal fun beforeSignatureArguments(): Array<Any?> = arrayOf(
        before.signatureSlots.single().signatureBytes,
        before.signedEnvelopeBytes,
        beforeEnvelopeHash?.copyOf(),
    )

    internal fun afterSignatureArguments(): Array<Any?> = arrayOf(
        after.signatureSlots.single().signatureBytes,
        after.signedEnvelopeBytes,
        afterEnvelopeHash?.copyOf(),
    )

    /** Private buffers can be borrowed only by the exact retained operation during its fixed SQL stages. */
    internal fun finalizationArguments(operation: CatalogGenesisMutationOperation, jdbc: JdbcTemplate): Array<Any?> {
        operation.requireFinalizationArguments(this, jdbc)
        return checkNotNull(finalSqlArguments)
    }

    internal fun finalizationControlArguments(operation: CatalogGenesisMutationOperation, jdbc: JdbcTemplate): Array<Any?> {
        operation.requireFinalizationArguments(this, jdbc)
        return checkNotNull(finalControlArguments)
    }

    override fun toString(): String = "CatalogGenesisMutationInput(frozen-G1-only,no-publication-authority)"

    companion object {
        fun complete(readback: CatalogDualLocationVerifier.GenesisReadback, expected: CatalogGenesisInitialLiveBinding): CatalogGenesisMutationInput {
            requireConnectionFree()
            requireCatalogReadback(readback.resume == GenesisResume.PREPARED, CatalogReadbackFailure.INVALID_LOCAL_STATE)
            return finalization(readback, expected, PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE)
        }

        fun project(readback: CatalogDualLocationVerifier.GenesisReadback, expected: CatalogGenesisInitialLiveBinding): CatalogGenesisMutationInput {
            requireConnectionFree()
            return finalization(readback, expected, PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT)
        }

        internal fun completeFinalizing(
            readback: CatalogDualLocationVerifier.GenesisReadback,
            expected: CatalogGenesisInitialLiveBinding,
            finalizer: CatalogGenesisFinalizeAttemptV1,
        ): CatalogGenesisMutationInput {
            requireCatalogReadback(readback.resume == GenesisResume.PREPARED, CatalogReadbackFailure.INVALID_LOCAL_STATE)
            return finalization(readback, expected, PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE, finalizer)
        }

        internal fun projectFinalizing(
            readback: CatalogDualLocationVerifier.GenesisReadback,
            expected: CatalogGenesisInitialLiveBinding,
            finalizer: CatalogGenesisFinalizeAttemptV1,
        ): CatalogGenesisMutationInput = finalization(readback, expected, PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT, finalizer)

        internal fun completeInitialAuthor(
            readback: CatalogDualLocationVerifier.GenesisReadback,
            expected: CatalogGenesisInitialLiveBinding,
            original: CatalogSignerRotationInitialAuthorV1,
        ): CatalogGenesisMutationInput {
            requireCatalogReadback(readback.resume == GenesisResume.PREPARED, CatalogReadbackFailure.INVALID_LOCAL_STATE)
            return finalization(readback, expected, PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE, initialAuthor = original)
        }

        internal fun projectInitialAuthor(
            readback: CatalogDualLocationVerifier.GenesisReadback,
            expected: CatalogGenesisInitialLiveBinding,
            original: CatalogSignerRotationInitialAuthorV1,
        ): CatalogGenesisMutationInput = finalization(readback, expected, PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT, initialAuthor = original)

        private fun finalization(
            readback: CatalogDualLocationVerifier.GenesisReadback,
            expected: CatalogGenesisInitialLiveBinding,
            path: PersistencePhasePath,
            finalizer: CatalogGenesisFinalizeAttemptV1? = null,
            initialAuthor: CatalogSignerRotationInitialAuthorV1? = null,
        ): CatalogGenesisMutationInput = CatalogGenesisMutationInput(
            path,
            readback.mutation(),
            readback.mutation(),
            readback.manifest(),
            when {
                initialAuthor != null -> CatalogGenesisFinalizationInput.initialAuthor(readback, expected, initialAuthor)
                finalizer != null -> CatalogGenesisFinalizationInput.durable(readback, expected, finalizer)
                else -> CatalogGenesisFinalizationInput.verified(readback, expected)
            },
        )

        fun prepare(
            offlineIntentManifestBytes: ByteArray,
            initialBundleBytes: ByteArray,
            currentBundleBytes: ByteArray,
            policy: OfflineCatalogChainReaderPolicy,
        ): CatalogGenesisMutationInput {
            requireConnectionFree()
            val intent = copyIntent(offlineIntentManifestBytes)
            val manifest = OfflineTrustBundleParser.parseGenesisManifest(intent)
            val slots = manifest.requiredSignerPolicy.members.map { CatalogFrozenSignatureSlot(it.keyId, it.algorithmId, null) }
            val mutation = CatalogFrozenMutation(1, manifest.operationToken, intent, Sha256.hex(intent), null, null, slots)
            CatalogGenesisPreparationVerifier.signingInput(UnverifiedGenesisPreparation(mutation), intent, initialBundleBytes, currentBundleBytes, policy)
            return CatalogGenesisMutationInput(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE, mutation, mutation, manifest)
        }

        fun signature(
            local: UnverifiedGenesisPreparation,
            offlineIntentManifestBytes: ByteArray,
            initialBundleBytes: ByteArray,
            currentBundleBytes: ByteArray,
            policy: OfflineCatalogChainReaderPolicy,
            signatureBytes: ByteArray?,
        ): CatalogGenesisMutationInput {
            requireConnectionFree()
            val intent = copyIntent(offlineIntentManifestBytes)
            val proposal = CatalogGenesisPreparationVerifier.proposeSignature(local, intent, initialBundleBytes, currentBundleBytes, policy, signatureBytes)
            // The accepted verifier's general policy can allow more; durable G1 keeps its own lower prepaid bound.
            UnverifiedGenesisPreparation(proposal.after)
            return CatalogGenesisMutationInput(
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE,
                proposal.before,
                proposal.after,
                OfflineTrustBundleParser.parseGenesisManifest(intent),
            )
        }

        private fun copyIntent(bytes: ByteArray): ByteArray {
            requireCatalogReadback(bytes.size in 1..CatalogGenesisCapacity.MAX_DOCUMENT_BYTES, CatalogReadbackFailure.LIMIT_EXCEEDED)
            return bytes.copyOf()
        }

        private fun hash(bytes: ByteArray): ByteArray = HexFormat.of().parseHex(Sha256.hex(bytes))
    }
}
