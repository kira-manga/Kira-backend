package me.manga.kira.backend.complaint.infrastructure.terminal

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOrdinarySealS3ClientV1
import me.manga.kira.backend.security.aws.AwsTestTerminalDataKeyAdapterV1
import java.time.Instant

/** Only the fixed raw LIST/GET plus real terminal AEAD/KMS path below issues these observations. */
internal class TestOrdinarySealProofV1 private constructor(
    private val custody: TestOrdinarySealCustodyV1,
    val version: String,
    val lastModified: Instant,
    val retainUntil: Instant,
    val verifiedAt: Instant,
) {
    internal fun requireOriginal(original: TestRunOrdinarySealV1) { custody.requireReleasedProof(original, this) }
    internal fun requireOriginal(original: TestActiveOrdinarySealV1) { custody.requireReleasedProof(original, this) }

    /** Stored comparison bytes, not a capability. Exact replay preserves its first observed timestamp. */
    internal fun canonicalBytes(row: TestTerminalDurableRowV1, at: Instant = verifiedAt): ByteArray {
        requireOrdinarySeal(at.nano % 1000 == 0 && !at.isBefore(lastModified) && !at.isAfter(verifiedAt) && retainUntil.isAfter(at))
        return CanonicalJson.canonicalize(buildJsonObject {
            put("schemaVersion", 1); put("objectKind", "EPOCH_SEAL"); put("role", "ORDINARY")
            put("dataScopeId", row.binding.run.dataScopeId); put("writerGeneration", row.binding.writerGeneration)
            put("epochStartInclusive", row.binding.epochStartInclusive); put("epochEndInclusive", row.binding.epochEndInclusive)
            put("operationToken", row.binding.operationToken); put("configurationSha256", row.binding.run.configurationSha256)
            put("journalConfigurationSha256", row.binding.journalConfigurationSha256)
            put("objectKey", row.binding.objectKey); put("objectId", row.binding.objectId); put("objectVersion", version)
            put("canonicalSha256", row.canonicalSha256); put("ciphertextSha256", checkNotNull(row.wireSha256))
            put("lastModified", lastModified.toString()); put("requestedRetainUntil", checkNotNull(row.retainUntil).toString())
            put("retainUntil", retainUntil.toString()); put("objectLockMode", "COMPLIANCE"); put("verifiedAt", at.toString())
        }).toByteArray(Charsets.UTF_8)
    }

    override fun toString(): String = "TestOrdinarySealProofV1(authenticated-exact-version,local-set-only,redacted)"

    companion object {
        internal fun publish(custody: TestOrdinarySealCustodyV1, s3: TestOrdinarySealS3ClientV1, keys: AwsTestTerminalDataKeyAdapterV1): TestOrdinarySealProofV1 {
            custody.requireOrdinaryPublication()
            val actual = TestEpochSealReadbackV1.publish(custody, s3, keys)
            actual.requireCustody(custody)
            custody.requireOrdinaryPublication()
            return TestOrdinarySealProofV1(custody, actual.version, actual.lastModified, actual.retainUntil, actual.verifiedAt)
        }
    }
}
