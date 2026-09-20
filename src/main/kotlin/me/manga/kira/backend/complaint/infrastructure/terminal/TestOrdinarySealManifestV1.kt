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

    /**
     * Closed-drain producer reads the complete actually applied alias set twice, after exact P-U
     * settlement and paid recycle. These are comparison folds, not a replacement native inventory.
     * The caller also compares the result with the admitted durable native cut in both transactions.
     */
    internal class ClosedBuilder(private val original: TestRunOrdinarySealV1) {
        private val drain = checkNotNull(original.closedDrain)
        private val first = MessageDigest.getInstance("SHA-256")
        private val second = MessageDigest.getInstance("SHA-256")
        private val manifest = MessageDigest.getInstance("SHA-256")
        private var firstHash: String? = null
        private var count = 0L
        private var repeated = 0L
        private var entryBytes = 0L
        private var repeatedBytes = 0L
        private var previous: Pair<String, String>? = null
        private var secondPass = false
        private var ended = false

        fun entry(row: TestOrdinaryDrainPersistenceV1.Applied) {
            drain.requireClosedSeal(original)
            original.codecAttempt.remainingMillis(1)
            requireDrain(!ended && previous?.let { TestOrdinaryDrainRowsV1.compare(it, row.locator) < 0 } != false &&
                row.key.length in 1..1024 && row.key.all { it in ' '..'~' } && row.ciphertext.matches(Regex("[0-9a-f]{64}")) &&
                row.stamp?.matches(Regex("[0-9]{1,10}")) == true)
            val fields = listOf(row.key, row.version, row.ciphertext)
            val stamps = fields + checkNotNull(row.stamp)
            if (!secondPass) {
                requireDrain(count < drain.maximumVersions)
                val frame = EpochSealFramesV1.frame(fields)
                try { entryBytes = Math.addExact(entryBytes, frame.size.toLong()) } finally { frame.fill(0) }
                EpochSealFramesV1.update(first, stamps)
                requireDrain(entryBytes <= drain.maximumFramedBytes)
                count++
            } else {
                requireDrain(repeated < count)
                repeatedBytes = Math.addExact(repeatedBytes, EpochSealFramesV1.update(manifest, fields))
                EpochSealFramesV1.update(second, stamps)
                requireDrain(repeatedBytes <= drain.maximumFramedBytes)
                repeated++
            }
            previous = row.locator
        }
        fun beginSecond() {
            requireDrain(!secondPass && !ended)
            val prefixBytes = EpochSealFramesV1.update(manifest, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", drain.writer,
                drain.routing.journalConfiguration.ordinaryPrefix, "TEST", drain.scope.toString(), "1", drain.cutoff.toString(), count.toString()))
            requireDrain(Math.addExact(prefixBytes, entryBytes) == drain.paidCut().framedByteCount)
            firstHash = HexFormat.of().formatHex(first.digest())
            secondPass = true; previous = null
        }
        fun finish(): TestOrdinarySealManifestV1 {
            requireDrain(secondPass && !ended && count == repeated && entryBytes == repeatedBytes && firstHash == HexFormat.of().formatHex(second.digest()))
            val root = HexFormat.of().formatHex(manifest.digest())
            requireDrain(count == drain.paidCut().denial.firstInventory.versionCount && root == drain.paidCut().denial.firstInventory.sha256)
            ended = true
            return TestOrdinarySealManifestV1(count, root, checkNotNull(firstHash))
        }
    }

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
