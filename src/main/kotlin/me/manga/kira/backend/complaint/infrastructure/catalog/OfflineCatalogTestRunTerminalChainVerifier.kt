package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.domain.catalog.CatalogRestoreInventoryV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogRotationState
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogTestRunTerminalChain
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationManifestV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalManifestV4
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireOfflineTrustBundle
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunTerminalParser
import me.manga.kira.backend.complaint.parsing.catalog.ParsedOfflineCatalogGeneration
import me.manga.kira.backend.complaint.parsing.catalog.ParsedOfflineTerminalCatalogGeneration
import me.manga.kira.backend.complaint.parsing.catalog.ParsedOfflineTestRunCatalogGeneration

/**
 * Full raw chain fold: existing schema1/2 prefix, exactly one V3, exactly its final V4 successor.
 * A Checked result is signature/declaration evidence only, never local, native or denial authority.
 * The old first-activation reader still rejects every suffix, including this separately read schema.
 */
internal object OfflineCatalogTestRunTerminalChainVerifier {
    fun verify(
        envelopes: Sequence<ByteArray>,
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: OfflineCatalogChainReaderPolicy,
        expected: CatalogTestRunTerminalCanonicalV4,
    ): CheckedOfflineCatalogTestRunTerminalChain {
        requireOfflineTrustBundle(initialBundleBytes.size in 1..OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES &&
            currentBundleBytes.size in 1..OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        val initial = initialBundleBytes.copyOf()
        val current = currentBundleBytes.copyOf()
        expected.requireReader(initial, current, policy)
        val input = OfflineCatalogChainInput(envelopes, policy.limits)
        requireOfflineTrustBundle(input.hasNext())
        val state = OfflineCatalogChainAuthentication.bootstrap(input.next(), initial, current, policy)
        expected.requireTrust(state.trust)
        var inventory = CatalogRestoreInventoryV1(emptyList(), emptyList())
        var inventoryProfileStarted = false
        var activation: OfflineCatalogTestRunActivationManifestV3? = null
        var terminal: OfflineCatalogTestRunTerminalManifestV4? = null
        var terminalManifestBytes: ByteArray? = null
        var terminalEnvelopeBytes: ByteArray? = null
        while (input.hasNext()) {
            // Do not stop at a seemingly useful tail: any supplied suffix invalidates the chain.
            requireOfflineTrustBundle(terminal == null)
            val bytes = input.next()
            when (val parsed = OfflineCatalogTestRunTerminalParser.parseGeneration(bytes,
                policy.limits.maximumManifestRecords, policy.limits.maximumEnvelopeBytes)) {
                is ParsedOfflineTerminalCatalogGeneration.Predecessor -> {
                    requireOfflineTrustBundle(activation == null)
                    when (val predecessor = parsed.generation) {
                        is ParsedOfflineTestRunCatalogGeneration.Prefix -> when (val prefix = predecessor.generation) {
                            is ParsedOfflineCatalogGeneration.RotationV1 -> {
                                requireOfflineTrustBundle(!inventoryProfileStarted)
                                OfflineCatalogInventoryChainVerifier.appendPrefix(prefix.envelope, bytes, state, policy)
                            }
                            is ParsedOfflineCatalogGeneration.InventoryV2 -> {
                                inventory = OfflineCatalogInventoryChainVerifier.appendInventory(prefix.envelope, bytes, state, inventory, policy)
                                inventoryProfileStarted = true
                            }
                        }
                        is ParsedOfflineTestRunCatalogGeneration.ActivationV3 -> {
                            val manifest = predecessor.envelope.manifest
                            expected.activation.requireManifest(manifest)
                            requireOfflineTrustBundle(manifest.restoreInventory == inventory)
                            val unsigned = CanonicalJson.canonicalize(OfflineCatalogTestRunActivationManifestV3.serializer(), manifest).toByteArray(Charsets.UTF_8)
                            requireOfflineTrustBundle(unsigned.size in 1..minOf(policy.limits.maximumEnvelopeBytes,
                                OfflineCatalogTestRunActivationProtocol.MAX_DOCUMENT_BYTES), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                            state.appendTestRunActivation(manifest, predecessor.envelope.signatures, unsigned, bytes, policy)
                            activation = manifest
                        }
                    }
                }
                is ParsedOfflineTerminalCatalogGeneration.TerminalV4 -> {
                    val previous = activation ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_DOCUMENT)
                    val manifest = parsed.envelope.manifest
                    expected.requireManifest(manifest, previous, state.tail.envelopeSha256)
                    requireOfflineTrustBundle(manifest.restoreInventory == inventory)
                    val unsigned = CanonicalJson.canonicalize(OfflineCatalogTestRunTerminalManifestV4.serializer(), manifest).toByteArray(Charsets.UTF_8)
                    requireOfflineTrustBundle(unsigned.size in 1..minOf(policy.limits.maximumEnvelopeBytes,
                        OfflineCatalogTestRunTerminalProtocol.MAX_DOCUMENT_BYTES), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                    state.appendTestRunTerminal(manifest, parsed.envelope.signatures, unsigned, bytes, policy)
                    terminal = manifest
                    terminalManifestBytes = unsigned
                    terminalEnvelopeBytes = bytes
                }
            }
        }
        state.finish()
        val previous = activation ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_DOCUMENT)
        val final = terminal ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_DOCUMENT)
        val stable = state.rotation as? CatalogRotationState.Stable ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_DOCUMENT)
        return CheckedOfflineCatalogTestRunTerminalChain(state.tail, state.trust, stable, input.encodedBytes, previous, final,
            checkNotNull(terminalManifestBytes), checkNotNull(terminalEnvelopeBytes))
    }
}
