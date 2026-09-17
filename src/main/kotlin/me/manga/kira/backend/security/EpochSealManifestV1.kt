package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import java.security.MessageDigest
import java.util.HexFormat

/** Two matching local passes, not proof that the database supplied every row or that a cutoff was authorized. */
internal class EpochSealManifestV1 private constructor(
    private val owner: VersionBoundComplaintJournalRouting,
    private val attempt: EpochSealAttemptV1,
    val range: EpochSealRangeV1,
    val eventCount: Long,
    val eventManifestSha256: String,
) {
    internal fun requireOwner(expected: VersionBoundComplaintJournalRouting, originalAttempt: EpochSealAttemptV1) {
        requireEpochSeal(owner === expected && attempt === originalAttempt)
        attempt.requireOwner(expected)
        attempt.remainingMillis(1)
    }

    override fun toString(): String = "EpochSealManifestV1(two-local-passes,redacted,no-authority)"

    /** O(1) retained memory. A malformed, duplicate, changed or incomplete second pass permanently poisons the builder. */
    internal class Builder internal constructor(
        private val owner: VersionBoundComplaintJournalRouting,
        private val range: EpochSealRangeV1,
        private val attempt: EpochSealAttemptV1,
    ) {
        private val declaration = owner.journalConfiguration.declaration()
        private val writer = declaration.writer.generationId
        private val ordinaryPrefix = OfflineBootstrapGrammar.ordinaryPrefix(writer)
        private val keyPrefix = "${ordinaryPrefix}writer/$writer/epoch/"
        private val routingIds = declaration.routing.keys.map { it.keyId }.toSet()
        private val capacity = declaration.limits.capacity
        private val countedEntries = MessageDigest.getInstance("SHA-256")
        private val repeatedEntries = MessageDigest.getInstance("SHA-256")
        private val manifest = MessageDigest.getInstance("SHA-256")
        private var phase = Phase.COUNT
        private var count = 0L
        private var repeatedCount = 0L
        private var countedBytes = 0L
        private var repeatedBytes = 0L
        private var previousKey: String? = null
        private var countFingerprint: ByteArray? = null

        fun firstPass(objectKey: String, versionId: String, ciphertextSha256: String): Unit = step(Phase.COUNT) {
            val fields = entry(objectKey, versionId, ciphertextSha256)
            requireEpochSeal(count < capacity.maximumRetainedVersions, EpochSealFailureV1.LIMIT_EXCEEDED)
            countedBytes = boundedBytes(countedBytes, EpochSealFramesV1.update(countedEntries, fields))
            count++
        }

        /** The count is learned here from actual first-pass entries, never supplied by the caller. */
        fun beginSecondPass() = step(Phase.COUNT) {
            countFingerprint = countedEntries.digest()
            val prefix = listOf(
                EpochSealFramesV1.DOMAIN, "1", "manifest", writer, ordinaryPrefix, "LIVE", OfflineBootstrapGrammar.LIVE_SCOPE_ID,
                range.epochStartInclusive.toString(), range.epochEndInclusive.toString(), count.toString(),
            )
            val prefixBytes = EpochSealFramesV1.update(manifest, prefix)
            boundedBytes(countedBytes, prefixBytes)
            previousKey = null
            phase = Phase.DIGEST
        }

        fun secondPass(objectKey: String, versionId: String, ciphertextSha256: String): Unit = step(Phase.DIGEST) {
            requireEpochSeal(repeatedCount < count)
            val fields = entry(objectKey, versionId, ciphertextSha256)
            repeatedBytes = boundedBytes(repeatedBytes, EpochSealFramesV1.update(repeatedEntries, fields))
            EpochSealFramesV1.update(manifest, fields)
            repeatedCount++
        }

        fun finish(): EpochSealManifestV1 = step(Phase.DIGEST) {
            requireEpochSeal(repeatedCount == count && repeatedBytes == countedBytes)
            val repeatedFingerprint = repeatedEntries.digest()
            try {
                requireEpochSeal(MessageDigest.isEqual(checkNotNull(countFingerprint), repeatedFingerprint))
            } finally {
                repeatedFingerprint.fill(0)
                countFingerprint?.fill(0)
            }
            val digest = manifest.digest()
            val result = try {
                EpochSealManifestV1(owner, attempt, range, count, HexFormat.of().formatHex(digest))
            } finally {
                digest.fill(0)
            }
            phase = Phase.DONE
            result
        }

        private fun entry(key: String, version: String, checksum: String): List<String> {
            requireEpochSeal(key.length in 1..1024 && key.all { it in ' '..'~' } && key.startsWith(keyPrefix))
            val parts = key.substring(keyPrefix.length).split('/', limit = 4)
            requireEpochSeal(parts.size == 3 && parts[0].length == 19 && parts[1] in routingIds)
            val epoch = parts[0].toLongOrNull()
            requireEpochSeal(epoch != null && epoch in range.epochStartInclusive..range.epochEndInclusive)
            requireEpochSeal(parts[0] == checkNotNull(epoch).toString().padStart(19, '0'))
            EpochSealFramesV1.opaque(parts[2])
            requireEpochSeal(previousKey?.let { it < key } != false) // ASCII keys: exactly unsigned UTF-8 byte order; duplicates refuse.
            requireEpochSeal(version.length in 1..1024 && version != "null" && version.none { it < ' ' || it == '\u007f' })
            EpochSealFramesV1.utf8(version, 1024).fill(0)
            requireEpochSeal(OfflineBootstrapGrammar.sha256(checksum))
            previousKey = key
            return listOf(key, version, checksum)
        }

        private fun boundedBytes(prior: Long, addition: Long): Long {
            requireEpochSeal(addition <= capacity.maximumScanStagingBytes - prior, EpochSealFailureV1.LIMIT_EXCEEDED)
            return prior + addition
        }

        private fun <T> step(expected: Phase, action: () -> T): T = epochSealBoundary {
            val result = runCatching {
                requireEpochSeal(phase == expected)
                attempt.requireOwner(owner)
                attempt.remainingMillis(1)
                action().also { attempt.remainingMillis(1) }
            }
            if (result.isFailure) {
                phase = Phase.FAILED
                countedEntries.reset()
                repeatedEntries.reset()
                manifest.reset()
                countFingerprint?.fill(0)
            }
            result.getOrThrow()
        }

        override fun toString(): String = "EpochSealManifestV1.Builder(bounded,redacted,no-authority)"

        private enum class Phase { COUNT, DIGEST, DONE, FAILED }
    }

    companion object {
        fun start(
            owner: VersionBoundComplaintJournalRouting,
            epochStartInclusive: Long,
            epochEndInclusive: Long,
            precedingSealSha256: String,
            attempt: EpochSealAttemptV1,
        ): Builder = epochSealBoundary {
            attempt.requireOwner(owner)
            attempt.remainingMillis(1)
            Builder(owner, EpochSealRangeV1(epochStartInclusive, epochEndInclusive, precedingSealSha256), attempt)
        }
    }
}
