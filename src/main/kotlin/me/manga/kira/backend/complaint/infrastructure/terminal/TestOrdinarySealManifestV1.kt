package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.TestTerminalAttemptV1
import java.security.MessageDigest
import java.util.HexFormat

/** Local TEST expected set, not an S3 inventory or proof that an unsupported lineage is empty. */
internal class TestOrdinarySealManifestV1 private constructor(val count: Long, val sha256: String, internal val comparison: String) {
    internal fun requireSame(other: TestOrdinarySealManifestV1) {
        requireOrdinarySeal(count == other.count && sha256 == other.sha256 && comparison == other.comparison)
    }
    override fun toString(): String = "TestOrdinarySealManifestV1(two-local-passes,no-provider-quiescence,redacted)"

    /** O(1) digest state. SQL's fixed producer supplies every relevant row twice; this builder alone cannot prove that. */
    internal class Builder(
        private val routing: TestOwnerDeleteJournalRoutingV1,
        private val cutoff: Long,
        private val attempt: TestTerminalAttemptV1,
    ) {
        private val capacity = routing.journalConfiguration.declaration().limits.capacity
        private var second = false
        private var ended = false
        private var poisoned = false
        private var count = 0L
        private var repeated = 0L
        private var bytes = 0L
        private var secondBytes = 0L
        private var previous: String? = null
        private val firstHash = MessageDigest.getInstance("SHA-256")
        private val secondHash = MessageDigest.getInstance("SHA-256")
        private val manifest = MessageDigest.getInstance("SHA-256")
        private var firstFingerprint: String? = null

        init { requireOrdinarySeal(cutoff > 0); attempt.requireJournal(routing.journalConfiguration) }

        fun entry(key: String, version: String, wireHash: String, rowStamps: String) = guarded {
            requireOrdinarySeal(!ended && key.length in 1..1024 && key.all { it in ' '..'~' } && previous?.let { it < key } != false)
            requireOrdinarySeal(version.length in 1..1024 && version != "null" && version.toByteArray(Charsets.UTF_8).size <= 1024 && version.none { it < ' ' || it == '\u007f' })
            requireOrdinarySeal(wireHash.matches(Regex("[0-9a-f]{64}")) && rowStamps.matches(Regex("[0-9]{1,10}(?::[0-9]{1,10}){3}")))
            val fields = listOf(key, version, wireHash)
            if (!second) {
                requireOrdinarySeal(count < capacity.maximumRetainedVersions)
                bytes = add(bytes, EpochSealFramesV1.frame(fields).let { try { it.size.toLong() } finally { it.fill(0) } })
                EpochSealFramesV1.update(firstHash, fields + rowStamps)
                count++
            } else {
                requireOrdinarySeal(repeated < count)
                secondBytes = add(secondBytes, EpochSealFramesV1.update(manifest, fields))
                EpochSealFramesV1.update(secondHash, fields + rowStamps)
                repeated++
            }
            previous = key
        }
        fun beginSecond() = guarded {
            requireOrdinarySeal(!second && !ended)
            firstFingerprint = HexFormat.of().formatHex(firstHash.digest())
            val journal = routing.journalConfiguration
            val prefix = listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", journal.declaration().writer.generationId,
                journal.ordinaryPrefix, "TEST", journal.scope.id.toString(), "1", cutoff.toString(), count.toString())
            add(bytes, EpochSealFramesV1.update(manifest, prefix))
            second = true; previous = null
        }
        fun finish(): TestOrdinarySealManifestV1 = guarded {
            requireOrdinarySeal(second && !ended && count == repeated && bytes == secondBytes)
            requireOrdinarySeal(firstFingerprint == HexFormat.of().formatHex(secondHash.digest()))
            ended = true
            TestOrdinarySealManifestV1(count, HexFormat.of().formatHex(manifest.digest()), checkNotNull(firstFingerprint))
        }
        private fun add(prior: Long, next: Long): Long {
            requireOrdinarySeal(next <= capacity.maximumScanStagingBytes - prior)
            return prior + next
        }
        private fun <T> guarded(body: () -> T): T {
            requireOrdinarySeal(!poisoned)
            poisoned = true
            attempt.requireJournal(routing.journalConfiguration)
            attempt.remainingMillis(1)
            val result = body()
            attempt.remainingMillis(1)
            poisoned = false
            return result
        }
    }
}
