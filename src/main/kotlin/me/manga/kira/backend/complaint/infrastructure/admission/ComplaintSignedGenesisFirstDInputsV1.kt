package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisInitialLiveBinding
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPreparationVerifier
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Independent raw release inputs only. No SQL-derived trust, supplied D, deserialized receipt or signing/publication capability. */
internal class ComplaintSignedGenesisFirstDInputsV1 private constructor(
    intent: ByteArray,
    initial: ByteArray,
    current: ByteArray,
    envelope: ByteArray,
    private val independentlyHeldEnvelopeSha256: String,
) {
    private val intentBytes = copy(intent, CatalogGenesisCapacity.MAX_DOCUMENT_BYTES)
    private val initialBytes = copy(initial, OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES)
    private val currentBytes = copy(current, OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES)
    private val envelopeBytes = copy(envelope, CatalogGenesisCapacity.MAX_DOCUMENT_BYTES)

    init {
        valid(OfflineBootstrapGrammar.sha256(independentlyHeldEnvelopeSha256))
    }

    /** Every comparison is derived before checkout, against THIS actual cold target's J, P and reader policy. */
    internal fun verifyFor(target: VersionBoundComplaintProcessConfiguration): Verified = Verified.fromRaw(this, target)

    /** Private bounded comparison buffers, usable only by the retained first-D attempt; never a portable approval/pin receipt. */
    internal class Verified private constructor(private val target: VersionBoundComplaintProcessConfiguration, private val arguments: Array<Any?>) {
        internal fun requireTarget(selected: VersionBoundComplaintProcessConfiguration) {
            valid(selected === target)
            target.requireUnchangedConfiguration()
        }

        internal fun arguments(operation: ComplaintSignedGenesisFirstDOperationV1): Array<Any?> {
            operation.requireRelease(this)
            return arguments // The fixed retained operation alone borrows these before/after its one D-only CAS.
        }

        internal fun arguments(operation: ComplaintCatalogGenesisPublishRecheckOperationV1): Array<Any?> {
            operation.requireRelease(this)
            return arguments // Separate original publisher's current all-history comparison; no generic getter or selection receipt.
        }

        override fun toString(): String = "SignedGenesisFirstDInputs.Verified(private-comparison,no-authority)"

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun fromRaw(raw: ComplaintSignedGenesisFirstDInputsV1, target: VersionBoundComplaintProcessConfiguration): Verified = with(raw) {
                requireConnectionFree()
                target.requireUnchangedConfiguration()
                try {
                    val desired = target.desiredSettings()
                    val reader = target.catalogReadback ?: refused()
                    valid(desired.implementationSchema == 1 && desired.desiredGeneration == 1L && !reader.projectedCurrent)
                    valid(initialBytes.contentEquals(reader.initialBundleBytes()) && currentBytes.contentEquals(reader.currentBundleBytes()))
                    valid(reader.expectedGenesisEnvelopeSha256 == independentlyHeldEnvelopeSha256)
                    valid(Sha256.hex(envelopeBytes) == independentlyHeldEnvelopeSha256)
                    val manifest = OfflineTrustBundleParser.parseGenesisManifest(intentBytes)
                    val envelope = OfflineTrustBundleParser.parseGenesis(envelopeBytes)
                    valid(envelope.schemaVersion == 1 && envelope.manifest == manifest && envelope.signatures.size == 1)
                    val member = manifest.requiredSignerPolicy.members.single()
                    val signature = envelope.signatures.single()
                    valid(signature.keyId == member.keyId && signature.algorithmId == member.algorithmId)
                    val signatureBytes = Base64.getDecoder().decode(signature.signatureBase64)
                    valid(signatureBytes.size == OfflineTrustBundleProtocol.SIGNATURE_BYTES)
                    val unsigned = CatalogFrozenMutation(
                        1,
                        manifest.operationToken,
                        intentBytes,
                        Sha256.hex(intentBytes),
                        null,
                        null,
                        listOf(CatalogFrozenSignatureSlot(member.keyId, member.algorithmId, null)),
                    )
                    // Reuse the closed raw trust/intent/RSA-PSS verifier, not its observation as a write authority.
                    val proposal = CatalogGenesisPreparationVerifier.proposeSignature(
                        UnverifiedGenesisPreparation(unsigned),
                        intentBytes,
                        initialBytes,
                        currentBytes,
                        reader.chainPolicy,
                        signatureBytes,
                    )
                    valid(envelopeBytes.contentEquals(proposal.after.signedEnvelopeBytes))
                    CatalogGenesisPreparationVerifier.verifyPinnedReadback(
                        proposal,
                        UnverifiedGenesisPreparation(proposal.after),
                        initialBytes,
                        currentBytes,
                        reader.policyAt(Instant.now()),
                    )
                    CatalogGenesisInitialLiveBinding.fromRetained(target).requireMatchingRegistry(manifest.initialWriterRegistry)
                    val approvalBytes = CanonicalJson.canonicalize(
                        ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()),
                        manifest.approvals,
                    ).toByteArray()
                    valid(approvalBytes.size in 1..4096)
                    Verified(
                        target,
                        arrayOf(
                            UUID.fromString(manifest.operationToken), UUID.fromString(manifest.catalogWriterGenerationId), approvalBytes, hash(approvalBytes),
                            intentBytes.copyOf(), hash(intentBytes), member.keyId, member.algorithmId, signatureBytes,
                            envelopeBytes.copyOf(),
                            hash(
                                envelopeBytes,
                            ),
                            CatalogReadbackProtocol.key(1), Timestamp.from(Instant.ofEpochSecond(manifest.creation.createdAtEpochSecond)),
                        ),
                    )
                } catch (_: Exception) {
                    refused() // No raw parser, trust, key, path or byte diagnostics leave this boundary.
                }
            }
        }
    }

    override fun toString(): String = "ComplaintSignedGenesisFirstDInputsV1(raw-release,redacted,no-authority)"

    companion object {
        fun fromRaw(
            offlineIntentManifestBytes: ByteArray,
            initialBundleBytes: ByteArray,
            currentBundleBytes: ByteArray,
            signedEnvelopeBytes: ByteArray,
            independentlyHeldEnvelopeSha256: String,
        ): ComplaintSignedGenesisFirstDInputsV1 {
            requireConnectionFree()
            return ComplaintSignedGenesisFirstDInputsV1(
                offlineIntentManifestBytes,
                initialBundleBytes,
                currentBundleBytes,
                signedEnvelopeBytes,
                independentlyHeldEnvelopeSha256,
            )
        }

        private fun copy(bytes: ByteArray, maximum: Int): ByteArray {
            valid(bytes.size in 1..maximum)
            return bytes.copyOf()
        }

        private fun hash(bytes: ByteArray): ByteArray = HexFormat.of().parseHex(Sha256.hex(bytes))
        private fun valid(condition: Boolean) = requireDesiredInstallation(condition, ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        private fun refused(): Nothing = throw ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
    }
}
