package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import java.util.HexFormat
import java.util.UUID

/** Existing unsigned custody records only. Old leadership is historical data, never the next SQL lease authority. */
internal class CatalogSignerRotationPreparedRecoveryInputsV1 private constructor(allocation: ByteArray, binding: ByteArray) {
    private val originalAllocation = allocation.copyOf()
    private val originalBinding = binding.copyOf()
    private val allocationValues = decode(allocation, "allocation", 8)
    private val bindingValues = decode(binding, "binding", 14)
    val historicalOwner: UUID = uuid(bindingValues[12])
    val historicalToken: Long = positive(bindingValues[13])
    val predecessorHash: String = bindingValues[8]

    init {
        requireRecovery(positive(bindingValues[2]) == 1L && positive(bindingValues[7]) == 1L)
        listOf(3, 8, 9, 11).forEach { requireRecovery(HASH.matches(bindingValues[it])) }
        listOf(4, 5, 6, 10).forEach { uuid(bindingValues[it]) }
        uuid(allocationValues[2])
        allocationValues.drop(3).forEach { requireRecovery(HASH.matches(it)) }
        requireRecovery(allocationValues[7] == Sha256.hex(binding))
    }

    fun bindingBytes(): ByteArray = originalBinding.copyOf()

    fun requireInputs(process: VersionBoundComplaintProcessConfiguration, inputs: CatalogSignerRotationInputsV1) {
        requireConnectionFree()
        process.requireUnchangedConfiguration()
        val desired = process.desiredSettings()
        val expected = listOf(
            desired.desiredGeneration.toString(), HexFormat.of().formatHex(process.configurationHashBytes()),
            desired.databaseIdentity.toString(), desired.restoreIdentity.toString(),
            process.consumers.journalConfiguration.declaration().writer.generationId,
            "1", inputs.manifest.previousEnvelopeSha256, Sha256.hex(inputs.currentBytes()), inputs.writer.catalogWriterGenerationId,
            HexFormat.of().formatHex(process.consumers.capacityPolicy.digestBytes()), historicalOwner.toString(), historicalToken.toString(),
        )
        requireRecovery(bindingValues.drop(2) == expected)
        requireRecovery(inputs.bindingRecord.contentEquals(originalBinding) && inputs.allocation.contentEquals(originalAllocation))
        requireRecovery(allocationValues[2] == inputs.manifest.operationToken)
    }

    fun requireReadback(readback: CatalogDualLocationVerifier.SignerRotationAuthorReadback) {
        val actual = readback.commonHeadEvidence().chain
        requireRecovery(
            actual.tail.generation == 1L && actual.tail.envelopeSha256 == predecessorHash &&
                actual.trust.currentBundleEnvelopeSha256 == bindingValues[9] && actual.tail.catalogWriterGenerationId == bindingValues[10],
        )
    }

    /** Raw tail2 never supplies accepted B1. Pending2 must already be the actual released SQL snapshot. */
    internal fun requireDeliveryReadback(readback: CatalogDualLocationVerifier.Overlap2Readback) {
        val matchingHead = when (readback.snapshotHead.generation) {
            1L -> readback.snapshotHead.envelopeSha256 == predecessorHash

            2L ->
                readback.state === CatalogDualLocationVerifier.Overlap2Readback.State.PROJECTION_PENDING_DUAL_COPY &&
                    readback.snapshotHead.envelopeSha256 == readback.frozenEnvelopeSha256

            else -> false
        }
        requireRecovery(
            matchingHead && readback.generation().claims.previousEnvelopeSha256 == predecessorHash &&
                readback.currentTrustBundleSha256 == bindingValues[9] && readback.observedTail.catalogWriterGenerationId == bindingValues[10],
        )
    }

    internal fun requireProjectedDeliveryReadback(readback: CatalogDualLocationVerifier.ProjectedHeadReadback) {
        val actual = readback.commonHeadEvidence().chain.tail
        requireRecovery(
            actual.generation == 2L && readback.generation().claims.previousEnvelopeSha256 == predecessorHash &&
                readback.currentTrustBundleSha256 == bindingValues[9] && actual.catalogWriterGenerationId == bindingValues[10],
        )
    }

    override fun toString(): String = "CatalogSignerRotationPreparedRecoveryInputsV1(original-B-and-allocation,redacted,no-current-authority)"

    companion object {
        internal fun read(
            original: CatalogSignerRotationDeliveryV1,
            custody: CatalogSignerRotationReleaseCustodyV1,
            allocation: ByteArray,
        ): CatalogSignerRotationPreparedRecoveryInputsV1 {
            original.requireHistoricalCustody(custody)
            val binding = custody.read(CatalogSignerRotationReleaseLeafV1.BINDING)
                ?: throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
            return CatalogSignerRotationPreparedRecoveryInputsV1(allocation, binding)
        }

        internal fun read(
            original: CatalogSignerRotationPreparedRecoveryV1,
            custody: CatalogSignerRotationReleaseCustodyV1,
            allocation: ByteArray,
        ): CatalogSignerRotationPreparedRecoveryInputsV1 {
            original.requireHistoricalCustody(custody)
            val binding = custody.read(CatalogSignerRotationReleaseLeafV1.BINDING)
                ?: throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
            return CatalogSignerRotationPreparedRecoveryInputsV1(allocation, binding)
        }

        private fun decode(bytes: ByteArray, kind: String, count: Int): List<String> {
            val values = CanonicalJson.json.decodeFromString(ListSerializer(String.serializer()), bytes.toString(Charsets.UTF_8))
            requireRecovery(values.size == count && values[0] == "catalog-signer-rotation-freeze-v1" && values[1] == kind)
            requireRecovery(signerRotationRecord(kind, *values.drop(2).toTypedArray()).contentEquals(bytes))
            return values
        }

        private fun positive(value: String): Long {
            requireRecovery(POSITIVE.matches(value))
            return value.toLong().also { requireRecovery(it > 0 && it.toString() == value) }
        }

        private fun uuid(value: String): UUID = UUID.fromString(value).also {
            requireRecovery(it.toString() == value && it.version() == 4 && it.variant() == 2)
        }

        private fun requireRecovery(condition: Boolean) = requireSignerRotation(condition, CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        private val POSITIVE = Regex("[1-9][0-9]{0,18}")
        private val HASH = Regex("[0-9a-f]{64}")
    }
}
