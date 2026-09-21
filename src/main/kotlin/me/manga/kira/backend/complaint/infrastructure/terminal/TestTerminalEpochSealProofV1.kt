package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOrdinarySealS3ClientV1
import me.manga.kira.backend.security.aws.AwsTestTerminalDataKeyAdapterV1
import java.time.Instant

/** Distinct TERMINAL issuer; neither an ORDINARY proof nor stored reference can be relabelled. */
internal class TestTerminalEpochSealProofV1 private constructor(
    private val custody: TestOrdinarySealCustodyV1,
    val version: String,
    val lastModified: Instant,
    val retainUntil: Instant,
    val verifiedAt: Instant,
) {
    internal fun requireOriginal(original: TestRunTerminalEpochSealV1) = custody.requireReleasedProof(original, this)
    override fun toString(): String = "TestTerminalEpochSealProofV1(actual-native-terminal-readback,no-denial-or-catalog-authority)"

    companion object {
        internal fun publish(custody: TestOrdinarySealCustodyV1, s3: TestOrdinarySealS3ClientV1, keys: AwsTestTerminalDataKeyAdapterV1): TestTerminalEpochSealProofV1 {
            custody.requireTerminalEpochPublication()
            val actual = TestEpochSealReadbackV1.publish(custody, s3, keys)
            actual.requireCustody(custody)
            custody.requireTerminalEpochPublication()
            return TestTerminalEpochSealProofV1(custody, actual.version, actual.lastModified, actual.retainUntil, actual.verifiedAt)
        }
    }
}
