package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationEnvelopeV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunActivationParser
import java.util.Base64
import java.util.HexFormat

/** Exact detached schema3 signature/envelope bytes. Data only; neither this nor a SQL row authorizes Sign. */
internal class CatalogTestRunActivationSignedV1 private constructor(
    internal val frozen: CatalogTestRunActivationFrozenV1,
    private val signature: ByteArray,
    private val envelope: ByteArray,
) {
    val envelopeSha256: String = Sha256.hex(envelope)
    val signatureSha256: String = Sha256.hex(signature)
    private val envelopeDigest = HexFormat.of().parseHex(envelopeSha256)

    fun signatureBytes(): ByteArray = signature.copyOf()
    fun envelopeBytes(): ByteArray = envelope.copyOf()
    fun signatureArguments(): Array<Any?> = arrayOf(signature.copyOf(), envelope.copyOf(), envelopeDigest.copyOf())
    fun deliveryControlArguments(): Array<Any?> = arrayOf(frozen.generation - 1L, frozen.predecessorHashBytes(), frozen.generation, envelopeDigest.copyOf(), frozen.token)

    /** Byte comparison only, including under the fixed SQL lock. Parsing/crypto already happened connection-free. */
    fun requireExact(actualSignature: ByteArray, actualEnvelope: ByteArray, actualHash: ByteArray) {
        check(signature.contentEquals(actualSignature) && envelope.contentEquals(actualEnvelope) && envelopeDigest.contentEquals(actualHash))
    }

    override fun toString(): String = "CatalogTestRunActivationSignedV1(exact-schema3-bytes,no-Sign-or-run-authority)"

    companion object {
        internal fun verify(
            process: VersionBoundTestNamespaceProcessV1,
            frozen: CatalogTestRunActivationFrozenV1,
            signatureBytes: ByteArray,
            retainedEnvelope: ByteArray? = null,
        ): CatalogTestRunActivationSignedV1 {
            requireConnectionFree()
            process.requireUnchangedConfiguration()
            requireTestActivation(signatureBytes.size == OfflineTrustBundleProtocol.SIGNATURE_BYTES)
            val signature = signatureBytes.copyOf()
            val key = process.catalogActivation.signingKey
            val manifest = frozen.manifest()
            val member = manifest.requiredSignerPolicy.members.single()
            requireTestActivation(manifest.requiredSignerPolicy.mode == "SINGLE" && member.keyId == key.keyId && member.algorithmId == key.algorithmId)
            OfflineTrustBundleCrypto.verify(key.publicKey(), OfflineCatalogGenesisCrypto.signatureFrame(key.keyId, frozen.unsignedBytes()), signature)
            val envelope = CanonicalJson.canonicalize(
                OfflineCatalogTestRunActivationEnvelopeV3.serializer(),
                OfflineCatalogTestRunActivationEnvelopeV3(
                    OfflineCatalogTestRunActivationProtocol.SCHEMA_VERSION, manifest,
                    listOf(OfflineCatalogGenesisSignatureV1(key.keyId, key.algorithmId, Base64.getEncoder().encodeToString(signature))),
                ),
            ).toByteArray(Charsets.UTF_8)
            val limits = process.catalogReadback.chainPolicy.limits
            val maximum = minOf(limits.maximumEnvelopeBytes, OfflineCatalogTestRunActivationProtocol.MAX_DOCUMENT_BYTES)
            // The final envelope, not just its unsigned manifest, must satisfy the retained schema3 limit.
            val parsed = OfflineCatalogTestRunActivationParser.parse(envelope, limits.maximumManifestRecords, maximum)
            requireTestActivation(parsed.manifest == manifest && (retainedEnvelope == null || envelope.contentEquals(retainedEnvelope)))
            process.requireUnchangedConfiguration()
            return CatalogTestRunActivationSignedV1(frozen, signature, envelope)
        }
    }
}
