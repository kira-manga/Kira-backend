package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
import me.manga.kira.backend.security.EpochSealFramesV1
import java.security.MessageDigest
import java.util.HexFormat

/** Local expected terminal-epoch set, not either of the later all-version/provider inventories. */
internal class TestTerminalEpochSealManifestV1 private constructor(val count: Long, val sha256: String, private val comparison: String) {
    fun requireSame(other: TestTerminalEpochSealManifestV1) = requireTerminalSeal(count == other.count && sha256 == other.sha256 && comparison == other.comparison)
    override fun toString(): String = "TestTerminalEpochSealManifestV1(local-two-pass-terminal-set,no-final-inventory-proof)"

    /** Actual SQL owner supplies both passes over currently locked, authenticated predecessor references. */
    class Builder(private val journal: TestOwnerDeleteJournalConfigurationV1, private val epoch: Long, private val chunks: Int) {
        private val expected = chunks.let {
            requireTerminalSeal(epoch > 1 && it in 0..TestTerminalProfileV1.MAX_MANIFEST_CHUNKS)
            Math.addExact(it.toLong(), 1L).also { count -> requireTerminalSeal(count <= journal.declaration().limits.capacity.maximumRetainedVersions) }
        }
        private val first = MessageDigest.getInstance("SHA-256")
        private val second = MessageDigest.getInstance("SHA-256")
        private val root = MessageDigest.getInstance("SHA-256")
        private val seenChunks = BooleanArray(chunks)
        private var seenPurge = false
        private var fingerprint: String? = null
        private var previous: String? = null
        private var count = 0L
        private var repeated = 0L
        private var bytes = 0L
        private var repeatedBytes = 0L
        private var secondPass = false
        private var ended = false
        private var poisoned = false

        fun entry(kind: String, ordinal: Int, id: String, reference: TestTerminalObjectRefV1, proofSha256: String) = guarded {
            requireTerminalSeal(!ended && previous?.let { it < reference.objectKey } != false)
            TestTerminalSyntaxV1.opaque(id); TestTerminalSyntaxV1.hash(proofSha256)
            val objectKind = when (kind) {
                "INSTALLATION_MANIFEST" -> {
                    requireTerminalSeal(ordinal in 0 until chunks && !seenChunks[ordinal]); seenChunks[ordinal] = true
                    "installation-manifest"
                }
                "TEST_RUN_PURGE" -> {
                    requireTerminalSeal(ordinal == 0 && !seenPurge); seenPurge = true
                    "test-run-purge"
                }
                else -> throw TestTerminalEpochSealExceptionV1()
            }
            val keyId = TestTerminalSyntaxV1.terminalKey(reference.objectKey, journal.declaration().writer.generationId, journal.scope.id.toString(), epoch, objectKind)
            requireTerminalSeal(journal.declaration().routing.keys.any { it.keyId == keyId })
            val fields = listOf(reference.objectKey, reference.objectVersion, reference.ciphertextSha256)
            val comparison = fields + listOf(kind, ordinal.toString(), id, reference.canonicalSha256, proofSha256)
            if (!secondPass) {
                requireTerminalSeal(count < expected)
                val frame = EpochSealFramesV1.frame(fields)
                try { bytes = add(bytes, frame.size.toLong()) } finally { frame.fill(0) }
                EpochSealFramesV1.update(first, comparison); count++
            } else {
                requireTerminalSeal(repeated < expected)
                repeatedBytes = add(repeatedBytes, EpochSealFramesV1.update(root, fields))
                EpochSealFramesV1.update(second, comparison); repeated++
            }
            previous = reference.objectKey
        }
        fun beginSecond() = guarded {
            requireTerminalSeal(!ended && !secondPass && count == expected && seenPurge && seenChunks.all { it })
            fingerprint = HexFormat.of().formatHex(first.digest())
            val prefix = listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", journal.declaration().writer.generationId,
                journal.sealTerminalPrefix, "TEST", journal.scope.id.toString(), epoch.toString(), epoch.toString(), count.toString())
            add(bytes, EpochSealFramesV1.update(root, prefix))
            secondPass = true; previous = null; seenPurge = false; seenChunks.fill(false)
        }
        fun finish(): TestTerminalEpochSealManifestV1 = guarded {
            requireTerminalSeal(!ended && secondPass && repeated == count && count == expected && bytes == repeatedBytes &&
                seenPurge && seenChunks.all { it } && fingerprint == HexFormat.of().formatHex(second.digest()))
            ended = true
            TestTerminalEpochSealManifestV1(count, HexFormat.of().formatHex(root.digest()), checkNotNull(fingerprint))
        }
        private fun add(before: Long, next: Long): Long {
            requireTerminalSeal(next >= 0 && next <= journal.declaration().limits.capacity.maximumScanStagingBytes - before)
            return before + next
        }
        private fun <T> guarded(action: () -> T): T {
            requireTerminalSeal(!poisoned); poisoned = true
            return action().also { poisoned = false }
        }
    }
}
